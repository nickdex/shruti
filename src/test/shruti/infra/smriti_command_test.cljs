(ns shruti.infra.smriti-command-test
  (:require [cljs.test :refer [async deftest is testing]]
            [shruti.domain.capture :as capture]
            [shruti.infra.command-transport :as transport]
            [shruti.infra.smriti-command :as command]))

(def base-options
  {:id "f38b1d22-c4cc-4c0a-8120-8ef4e3f70b51"
   :occurred-at "2026-07-27T12:30:00.000Z"
   :source {:kind :voice :entrypoint :app :platform :web}
   :locale "en-IN"})

(def request
  {:id "f38b1d22-c4cc-4c0a-8120-8ef4e3f70b51"
   :occurred-at "2026-07-27T12:30:00.000Z"
   :content {:type :text
             :text "Buy 20kg potatoes tomorrow."
             :transcript "Buy 20kg potatoes tomorrow."
             :audio nil}})

(defn- reviewed-and-sent [group-id text]
  (-> (capture/new-recording base-options)
      (capture/finalize-recording {:transcript "recognizer draft"})
      (capture/submit text group-id 1000)))

(deftest maps-a-capture-to-a-journal-create-entry-command
  (is (= {"command/name" "journal/create-entry"
          "text" "Buy 20kg potatoes tomorrow."
          "client-request-id" "f38b1d22-c4cc-4c0a-8120-8ef4e3f70b51"
          "tags" ["shruti" "todo"]
          "created-at" "2026-07-27T12:30:00.000Z"}
         (command/capture->command request))))

(deftest keeps-the-original-occurred-at-instead-of-the-delivery-time
  (testing "Smriti accepts the ISO instant as an offset date-time"
    (is (= (:occurred-at request)
           (get (command/capture->command request) "created-at"))))
  (testing "a different capture time is carried through unchanged"
    (let [backdated (assoc request :occurred-at "2026-01-02T03:04:05.000Z")]
      (is (= "2026-01-02T03:04:05.000Z"
             (get (command/capture->command backdated) "created-at"))))))

(deftest trims-the-final-text
  (let [padded (assoc-in request [:content :transcript] "  Buy potatoes  ")]
    (is (= "Buy potatoes" (get (command/capture->command padded) "text")))))

(deftest a-capture-without-a-final-transcript-has-no-command
  (is (nil? (command/capture->command
             (assoc request :content {:type :audio
                                      :audio {:asset-id "local"}})))))

(deftest send-uses-the-reviewed-text-and-the-capture-idempotency-key
  (let [sent (reviewed-and-sent "group-a" "  Corrected text  ")
        payload (command/capture->command sent)]
    (testing "the command carries the reviewed text, not the recognizer draft"
      (is (= "Corrected text" (get payload "text"))))
    (testing "the Shruti capture id is the only idempotency key"
      (is (= (:id sent) (get payload "client-request-id"))))
    (testing "the stable Group id, not its display name, is submitted"
      (is (= "group-a" (get payload "group-id")))
      (is (not (contains? payload "group-name"))))))

(deftest a-sent-capture-can-be-reassigned-without-changing-its-identity
  (let [sent (reviewed-and-sent "group-a" "Keep me")
        failed (capture/reject sent {:message "Not a member"})
        moved (capture/reassign failed "group-b" 2000)
        payload (command/capture->command moved)]
    (is (= "group-b" (get payload "group-id")))
    (is (= "Keep me" (get payload "text")))
    (is (= (:id sent) (get payload "client-request-id")))
    (testing "retrying the original payload also keeps the same key"
      (is (= (:id sent)
             (get (command/capture->command (capture/retry failed 0))
                  "client-request-id"))))))

(deftest private-omits-the-group-id
  (let [sent (reviewed-and-sent nil "A private thought")]
    (is (not (contains? (command/capture->command sent) "group-id")))
    (testing "a deliberately Private reassignment also omits the Group"
      (let [failed (capture/reject (reviewed-and-sent "group-a" "Moved")
                                   {:message "Not a member"})]
        (is (not (contains? (command/capture->command
                             (capture/reassign failed nil 0))
                            "group-id")))))))

(deftest a-new-entry-after-conflict-gets-a-fresh-idempotency-key
  (let [sent (reviewed-and-sent "group-a" "Repeat me")
        conflicted (capture/conflict sent {:code "capture-conflict"
                                           :accepted true})
        fresh (capture/new-entry conflicted "2026-07-28T08:00:00.000Z" 3000)
        original-payload (command/capture->command conflicted)
        fresh-payload (command/capture->command fresh)]
    (is (= "group-a" (get fresh-payload "group-id")))
    (is (= "Repeat me" (get fresh-payload "text")))
    (is (not= (get original-payload "client-request-id")
              (get fresh-payload "client-request-id")))
    (is (= (:id fresh) (get fresh-payload "client-request-id")))
    (is (= "2026-07-28T08:00:00.000Z" (get fresh-payload "created-at")))))

(deftest group-discovery-keeps-server-order-and-never-pads-the-picks
  (let [payload {:groups [{:id "g1" :name "Family"}
                          {:id "g2" :name "Friends"}
                          {:id "g3" :name "Work"}
                          {:id "g4" :name "Book club"}]
                 :quickPicks [{:id "g3" :name "Work" :position 1}
                              {:id "g1" :name "Family" :position 2}]
                 :quickPicksConfigured true}
        result (command/groups-payload->discovery payload {})]
    (testing "the server's explicit order and shorter length are preserved"
      (is (= ["g3" "g1"] (:quick-picks result)))
      (is (true? (:quick-picks-configured? result))))
    (testing "every available Group remains selectable outside the picks"
      (is (= ["g1" "g2" "g3" "g4"] (mapv :id (:groups result))))
      (is (= "Book club" (:name (last (:groups result))))))
    (testing "a fresh payload is not stale and needs no reauthentication"
      (is (false? (:stale? result)))
      (is (false? (:auth-required? result))))))

(deftest group-discovery-respects-an-explicitly-empty-pick-list
  (let [payload {:groups [{:id "g1" :name "Family"}
                          {:id "g2" :name "Friends"}]
                 :quickPicks []
                 :quickPicksConfigured true}
        result (command/groups-payload->discovery payload {})]
    (is (= [] (:quick-picks result)))
    (is (true? (:quick-picks-configured? result)))
    (testing "no local fallback invents destinations the User did not pin"
      (is (= ["g1" "g2"] (mapv :id (:groups result)))))))

(deftest group-discovery-keeps-the-server-fallback-when-unconfigured
  (let [payload {:groups [{:id "g1" :name "Family"}
                          {:id "g2" :name "Friends"}]
                 :quickPicks [{:id "g1" :name "Family" :position 1}]
                 :quickPicksConfigured false}
        result (command/groups-payload->discovery payload {})]
    (is (= ["g1"] (:quick-picks result)))
    (is (false? (:quick-picks-configured? result)))))

(deftest group-discovery-never-points-at-a-group-outside-the-payload
  (let [payload {:groups [{:id "g1" :name "Family"}]
                 :quickPicks [{:id "g1" :name "Family" :position 1}
                              {:id "gone" :name "Old Group" :position 2}]
                 :quickPicksConfigured true}
        result (command/groups-payload->discovery payload {:cached? true})]
    (testing "losing membership removes the pill rather than replacing it"
      (is (= ["g1"] (:quick-picks result))))
    (testing "cached data is labeled stale"
      (is (true? (:stale? result))))))

(deftest group-discovery-limits-picks-to-three-and-drops-malformed-entries
  (let [payload {:groups (mapv (fn [n] {:id (str "g" n) :name (str "Group " n)})
                               (range 1 6))
                 :quickPicks [{:id "g1" :position 1}
                              {:id "g2" :position 2}
                              {:id "g3" :position 3}
                              {:id "g4" :position 4}
                              {:name "No id" :position 5}
                              {:id "" :position 6}]
                 :quickPicksConfigured true}
        result (command/groups-payload->discovery payload {})]
    (is (= ["g1" "g2" "g3"] (:quick-picks result)))
    (is (= 5 (count (:groups result))))))

(defn- discovery-transport [result]
  (reify transport/GroupDiscovery
    (fetch-groups! [_ path]
      (is (= "/api/groups" path))
      (js/Promise.resolve result))))

(deftest discovery-labels-an-offline-cache-as-stale
  (async done
         (let [system {:delivery-configuration
                       (discovery-transport
                        {:body {:groups [{:id "g1" :name "Family"}]
                                :quickPicks [{:id "g1" :name "Family" :position 1}]
                                :quickPicksConfigured true}
                         :cached? true
                         :auth-required? false})}]
           (-> (command/discover-groups! system)
               (.then
                (fn [result]
                  (is (= ["g1"] (:quick-picks result)))
                  (is (= "Family" (:name (first (:groups result)))))
                  (is (true? (:stale? result)))
                  (done)))
               (.catch (fn [error]
                         (is false (str error))
                         (done)))))))

(deftest discovery-without-a-group-list-fails-instead-of-inventing-destinations
  (async done
         (let [system {:delivery-configuration
                       (discovery-transport
                        {:body {:error "Unauthorized"}
                         :cached? false
                         :auth-required? true
                         :error {:message "Unauthorized"}})}]
           (-> (command/discover-groups! system)
               (.then (fn [_]
                        (is false "an error body must not become an empty Group list")
                        (done)))
               (.catch (fn [error]
                         (is (= "Unauthorized" (.-message error)))
                         (is (= :groups-unavailable (:code (ex-data error))))
                         (done)))))))

(deftest appends-the-fixed-command-path-to-an-entered-host
  (testing "the setup screen collects a host; the path is fixed"
    (is (= "https://smriti.example.com/api/command"
           (command/endpoint-url "https://smriti.example.com")))
    (is (= "https://smriti.example.com/api/command"
           (command/endpoint-url "https://smriti.example.com/")))
    (is (= "http://10.0.2.2:8080/api/command"
           (command/endpoint-url "http://10.0.2.2:8080"))))
  (testing "an already complete endpoint is not doubled"
    (is (= "https://smriti.example.com/api/command"
           (command/endpoint-url "https://smriti.example.com/api/command")))
    (is (= "https://smriti.example.com/api/command"
           (command/endpoint-url "  https://smriti.example.com/api/command/  "))))
  (testing "a blank entry stays blank so setup can reject it"
    (is (= "" (command/endpoint-url "")))
    (is (= "" (command/endpoint-url nil)))))

(deftest group-discovery-targets-the-same-authenticated-origin
  (is (= "/api/groups" command/groups-path))
  (testing "the groups route replaces the command path on the configured host"
    (is (= "https://smriti.example.com/api/groups"
           (command/groups-endpoint-url "https://smriti.example.com/api/command")))
    (is (= "https://smriti.example.com/api/groups"
           (command/groups-endpoint-url "https://smriti.example.com")))
    (is (= "http://10.0.2.2:8080/api/groups"
           (command/groups-endpoint-url "http://10.0.2.2:8080/"))))
  (testing "group discovery never visits a different host"
    (is (= "https://smriti.example.com:8443/api/groups"
           (command/groups-endpoint-url "https://smriti.example.com:8443")))))

(deftest shows-the-host-without-the-fixed-path
  (is (= "https://smriti.example.com"
         (command/base-url "https://smriti.example.com/api/command")))
  (is (= "http://localhost:8080"
         (command/base-url "http://localhost:8080/api/command")))
  (is (= "https://smriti.example.com"
         (command/base-url "https://smriti.example.com")))
  (is (= "" (command/base-url ""))))
