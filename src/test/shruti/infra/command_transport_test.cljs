(ns shruti.infra.command-transport-test
  (:require [cljs.test :refer [async deftest is testing]]
            [goog.object :as gobj]
            [shruti.application.ports :as ports]
            [shruti.infra.command-transport :as transport]))

(defn- with-fetch [fake f]
  (let [had-fetch? (exists? js/fetch)
        original (when had-fetch? js/fetch)]
    (gobj/set js/globalThis "fetch" fake)
    (try
      (f)
      (finally
        (if had-fetch?
          (gobj/set js/globalThis "fetch" original)
          (js-delete js/globalThis "fetch"))))))

(defn- install-native-plugin! [plugin]
  (when-not (exists? js/window)
    (gobj/set js/globalThis "window" #js {}))
  (gobj/set js/window "Capacitor"
            #js {:Plugins #js {:ShrutiCommandTransport plugin}}))

(defn- remove-native-plugin! []
  (when (exists? js/window)
    (js-delete js/window "Capacitor")))

(deftest classifies-command-responses-without-a-response-object
  (testing "a 2xx is accepted"
    (is (= :accepted (:kind (transport/response-result 200 {} {:ok true})))))
  (testing "a replayed acceptance is still Sent"
    (is (= :accepted (:kind (transport/response-result 200 {} {:replayed true})))))
  (testing "authentication must not become a retry loop"
    (is (= :auth-required (:kind (transport/response-result 401 {} {:error "Unauthorized"}))))
    (is (= :auth-required (:kind (transport/response-result 403 {} {:error "Forbidden"})))))
  (testing "a transient service response keeps the queue retryable"
    (let [result (transport/response-result 503 {"Retry-After" "4"} {:error "busy"})]
      (is (= :retryable (:kind result)))
      (is (= 4000 (:retry-after-ms result))))))

(deftest a-changed-payload-conflict-is-not-a-generic-rejection
  (testing "the server's conflict proof becomes a needs-attention conflict"
    (let [body {:error "This capture was already sent."
                :code "capture-conflict"
                :accepted true}
          result (transport/response-result 409 {} body)]
      (is (= :conflict (:kind result)))
      (is (= body (:receipt result)))
      (is (= body (:error result)))
      (is (transport/conflict-accepted? body))))
  (testing "accepted alone is enough proof"
    (is (= :conflict (:kind (transport/response-result 409 {} {:accepted true})))))
  (testing "an ordinary 409 stays rejected and is never shown as Sent"
    (let [result (transport/response-result 409 {} {:error "Conflict"})]
      (is (= :rejected (:kind result)))
      (is (not (transport/conflict-accepted? {:error "Conflict"})))
      (is (not (transport/conflict-accepted? nil)))))
  (testing "other 4xx responses remain visible rejections"
    (is (= :rejected (:kind (transport/response-result 422 {} {:error "Bad command"}))))))

(deftest browser-delivery-posts-json-with-auth-and-refuses-redirects
  (async done
         (let [calls (atom nil)
               transport-with-payload
               (transport/create "https://example.test/api/command"
                                 (fn [_] {"arbitrary" "payload"}))]
           (with-fetch
             (fn [url options]
               (reset! calls {:url url :options options})
               (js/Promise.resolve
                #js {:status 200
                     :headers #js {}
                     :text (fn [] (js/Promise.resolve "{\"ok\":true}"))}))
             (fn []
               (-> (ports/deliver! transport-with-payload
                                   {:id "capture-1"}
                                   nil
                                   {:headers {"Authorization" "Bearer key"}})
                   (.then
                    (fn [result]
                      (let [headers (.-headers (:options @calls))]
                        (is (= :accepted (:kind result)))
                        (is (= "https://example.test/api/command" (:url @calls)))
                        (is (= "POST" (.-method (:options @calls))))
                        (is (= "error" (.-redirect (:options @calls))))
                        (is (= "application/json" (gobj/get headers "Content-Type")))
                        (is (= "Bearer key" (gobj/get headers "Authorization")))
                        (is (= {"arbitrary" "payload"}
                               (js->clj (js/JSON.parse
                                         (gobj/get (:options @calls) "body")))))
                        (done)))
                    (.catch (fn [error]
                              (is false (str error))
                              (done)))))))))

(deftest a-capture-without-a-payload-is-rejected-without-a-request
  (async done
         (-> (ports/deliver! (transport/create "https://example.test/api/command"
                                               (constantly nil))
                             {:id "capture-1"} nil {})
             (.then (fn [result]
                      (is (= :rejected (:kind result)))
                      (done)))
             (.catch (fn [error]
                       (is false (str error))
                       (done))))))

(deftest browser-mode-cannot-perform-secure-group-discovery
  (async done
         (-> (transport/fetch-groups!
              (transport/create "https://example.test/api/command" (constantly nil))
              "/api/groups")
             (.then (fn [_]
                      (is false "browser discovery must not fall back to a token-less request")
                      (done)))
             (.catch (fn [error]
                       (is (= "Group selection is available in the Android app."
                              (.-message error)))
                       (done))))))

(deftest native-group-discovery-passes-only-a-same-origin-path
  (async done
         (let [options (atom nil)
               plugin #js {:fetchGroups
                           (fn [_ request]
                             (reset! options request)
                             (js/Promise.resolve
                              #js {:status 200
                                   :ok true
                                   :cached true
                                   :authRequired false
                                   :fetchedAt "2026-09-08T12:00:00.000Z"
                                   :body #js {:groups #js [#js {:id "group-1"
                                                                :name "Family"}]
                                              :quickPicks #js ["group-1"]
                                              :quickPicksConfigured true}}))}]
           (install-native-plugin! plugin)
           (let [transport* (transport/create "https://example.test/api/command"
                                              (constantly nil))]
             (-> (transport/fetch-groups! transport* "/api/groups")
                 (.then
                  (fn [result]
                    (remove-native-plugin!)
                    (testing "the native bridge receives no credential and no absolute URL"
                      (is (= {"path" "/api/groups"} (js->clj @options))))
                    (is (= true (:ok? result)))
                    (is (= 200 (:status result)))
                    (is (= true (:cached? result)))
                    (is (= false (:auth-required? result)))
                    (is (= "2026-09-08T12:00:00.000Z" (:fetched-at result)))
                    (is (nil? (:error result)))
                    (is (= {:groups [{:id "group-1" :name "Family"}]
                            :quickPicks ["group-1"]
                            :quickPicksConfigured true}
                           (:body result)))
                    (done)))
                 (.catch (fn [error]
                           (remove-native-plugin!)
                           (is false (str error))
                           (done)))))))))

(deftest native-group-discovery-reports-expired-credentials
  (async done
         (let [plugin #js {:fetchGroups
                           (fn [_ _]
                             (js/Promise.resolve
                              #js {:status 401
                                   :ok false
                                   :cached false
                                   :authRequired true
                                   :fetchedAt ""
                                   :body #js {:error "Unauthorized"}}))}]
           (install-native-plugin! plugin)
           (let [transport* (transport/create "https://example.test/api/command"
                                              (constantly nil))]
             (-> (transport/fetch-groups! transport* "/api/groups")
                 (.then
                  (fn [result]
                    (remove-native-plugin!)
                    (is (= false (:ok? result)))
                    (is (= true (:auth-required? result)))
                    (is (= false (:cached? result)))
                    (done)))
                 (.catch (fn [error]
                           (remove-native-plugin!)
                           (is false (str error))
                           (done))))))))

(deftest native-group-discovery-surfaces-a-refused-path
  (async done
         (let [plugin #js {:fetchGroups
                           (fn [_ _]
                             (js/Promise.reject
                              #js {:code "INVALID_GROUPS_REQUEST"
                                   :message "Groups path must stay on the configured host."}))}]
           (install-native-plugin! plugin)
           (let [transport* (transport/create "https://example.test/api/command"
                                              (constantly nil))]
             (-> (transport/fetch-groups! transport* "https://evil.test/api/groups")
                 (.then (fn [_]
                          (remove-native-plugin!)
                          (is false "a cross-origin discovery request must be refused")
                          (done)))
                 (.catch (fn [error]
                           (remove-native-plugin!)
                           (is (= "Groups path must stay on the configured host."
                                  (.-message error)))
                           (is (= "INVALID_GROUPS_REQUEST" (:code (ex-data error))))
                           (done))))))))
