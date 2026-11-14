(ns shruti.infra.command-transport-test
  (:require [cljs.test :refer [async deftest is testing]]
            [goog.object :as gobj]
            [shruti.application.ports :as ports]
            [shruti.infra.command-transport :as transport]))

(deftest classifies-command-responses-without-a-response-object
  (testing "a 2xx is accepted"
    (is (= :accepted (:kind (transport/response-result 200 {} {:ok true})))))
  (testing "409 is a rejection, never a duplicate acknowledgement"
    (is (= :rejected (:kind (transport/response-result 409 {} {:error "Conflict"})))))
  (testing "authentication must not become a retry loop"
    (is (= :auth-required (:kind (transport/response-result 401 {} {:error "Unauthorized"})))))
  (testing "a transient service response keeps the queue retryable"
    (let [result (transport/response-result 503 {"Retry-After" "4"} {:error "busy"})]
      (is (= :retryable (:kind result)))
      (is (= 4000 (:retry-after-ms result))))))

(deftest browser-delivery-posts-json-with-the-capture-id
  (let [request (transport/browser-request
                 {"command/name" "journal/create-entry"
                  "text" "Buy potatoes"}
                 "capture-1"
                 {:headers {"Authorization" "Bearer key"}})
        headers (.-headers request)]
    (is (= "POST" (.-method request)))
    (is (= "error" (.-redirect request)))
    (is (= "application/json" (gobj/get headers "Content-Type")))
    (is (= "capture-1" (gobj/get headers "X-Capture-Id")))
    (is (= "Bearer key" (gobj/get headers "Authorization")))
    (is (= {"command/name" "journal/create-entry" "text" "Buy potatoes"}
           (js->clj (js/JSON.parse (.-body request)))))))

(deftest delivers-an-arbitrary-json-payload
  (async done
         (let [original-fetch (gobj/get js/globalThis "fetch")
               calls (atom nil)
               restore! (fn [] (gobj/set js/globalThis "fetch" original-fetch))]
           (gobj/set js/globalThis "fetch"
                     (fn [url options]
                       (reset! calls {:url url :options options})
                       (js/Promise.resolve
                        #js {:status 200
                             :headers #js {}
                             :text (fn [] (js/Promise.resolve "{\"ok\":true}"))})))
           (-> (ports/deliver!
                (transport/create "https://example.test/api/command"
                                  (fn [_] {"arbitrary" "payload"}))
                {:id "capture-1"}
                nil
                {})
               (.then (fn [result]
                        (restore!)
                        (is (= :accepted (:kind result)))
                        (is (= "https://example.test/api/command" (:url @calls)))
                        (is (= {"arbitrary" "payload"}
                               (js->clj (js/JSON.parse
                                         (gobj/get (:options @calls) "body")))))
                        (done)))
               (.catch (fn [error]
                         (restore!)
                         (is false (str error))
                         (done)))))))

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
