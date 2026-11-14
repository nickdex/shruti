(ns shruti.domain.capture-test
  (:require [cljs.test :refer [deftest is testing]]
            [shruti.domain.capture :as capture]))

(def base-options
  {:id "f38b1d22-c4cc-4c0a-8120-8ef4e3f70b51"
   :occurred-at "2026-09-08T12:00:00.000Z"
   :source {:kind :voice :entrypoint :app :platform :web}
   :locale "en-IN"})

(deftest recording-becomes-queued-only-after-audio-is-sealed
  (let [recording (capture/new-recording base-options)
        sealed (capture/finalize-recording recording {:audio {:asset-id (:id recording)
                                                              :mime-type "audio/mp4"
                                                              :duration-ms 2100
                                                              :bytes 11000}})]
    (is (= :recording (capture/content-state recording)))
    (is (= :not-ready (capture/delivery-state recording)))
    (is (= :ready (capture/content-state sealed)))
    (is (= :queued (capture/delivery-state sealed)))
    (is (capture/delivery-due? sealed 0))))

(deftest transcript-only-voice-capture-becomes-deliverable-text
  (let [recording (capture/new-recording base-options)
        sealed (capture/finalize-recording recording {:transcript "  Buy 20kg potatoes tomorrow.  "})]
    (is (= :text (get-in sealed [:content :type])))
    (is (= "Buy 20kg potatoes tomorrow." (get-in sealed [:content :text])))
    (is (nil? (get-in sealed [:content :audio])))
    (is (= :queued (capture/delivery-state sealed)))))

(deftest capture-id-and-local-delivery-data-do-not-leak-into-the-manifest
  (let [request (capture/new-text (assoc base-options :content {:text "Buy potatoes"}))
        manifest (capture/backend-manifest request)]
    (is (= (:id request) (:id manifest)))
    (is (nil? (:local manifest)))
    (is (= "Buy potatoes" (get-in manifest [:content :text])))))

(deftest retry-backoff-is-capped-and-jittered
  (testing "zero jitter can retry immediately when the scheduler allows it"
    (is (= 0 (capture/retry-delay-ms 1 0))))
  (testing "full jitter never exceeds the cap"
    (is (<= (capture/retry-delay-ms 30 1) 3600000))))

(deftest invalid-transitions-are-rejected
  (let [request (capture/new-text (assoc base-options :content {:text "A thought"}))]
    (is (thrown? js/Error (capture/transition-delivery request :accepted {})))))
