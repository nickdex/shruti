(ns shruti.domain.capture-test
  (:require [cljs.test :refer [deftest is testing]]
            [shruti.domain.capture :as capture]))

(def base-options
  {:id "f38b1d22-c4cc-4c0a-8120-8ef4e3f70b51"
   :occurred-at "2026-09-08T12:00:00.000Z"
   :source {:kind :voice :entrypoint :app :platform :web}
   :locale "en-IN"})

(defn- reviewed
  ([transcript] (reviewed base-options transcript))
  ([options transcript]
   (-> (capture/new-recording options)
       (capture/finalize-recording {:transcript transcript}))))

(defn- submitted
  ([text group-id] (submitted base-options text group-id))
  ([options text group-id]
   (-> (reviewed options text)
       (capture/submit text group-id 1000))))

(deftest a-finalized-recording-waits-for-an-explicit-send
  (let [recording (capture/new-recording base-options)
        sealed (capture/finalize-recording recording {:transcript "  Buy potatoes  "})]
    (is (= :recording (capture/content-state recording)))
    (is (= :not-ready (capture/delivery-state recording)))
    (testing "stopping the recognizer produces a review, never a queued delivery"
      (is (= :ready (capture/content-state sealed)))
      (is (= :not-ready (capture/delivery-state sealed)))
      (is (capture/unsent? sealed))
      (is (not (capture/pending? sealed)))
      (is (not (capture/sent? sealed)))
      (is (not (capture/delivery-due? sealed 0)))
      (is (not (capture/delivery-due? sealed 999999999999))))
    (testing "the sole unsent review blocks the next recording"
      (is (= sealed (capture/unsent-capture [sealed])))
      (is (false? (capture/can-start-recording? [sealed]))))))

(deftest transcript-only-voice-capture-becomes-editable-text
  (let [sealed (reviewed base-options "  Buy 20kg potatoes tomorrow.  ")]
    (is (= :text (get-in sealed [:content :type])))
    (is (= "Buy 20kg potatoes tomorrow." (get-in sealed [:content :text])))
    (is (nil? (get-in sealed [:content :audio])))
    (is (capture/unsent? sealed))))

(deftest a-review-can-be-corrected-before-sending
  (let [sealed (reviewed "draft transcript")
        edited (capture/edit-transcript sealed "  corrected transcript  ")]
    (is (= "corrected transcript" (get-in edited [:content :text])))
    (is (= "corrected transcript" (get-in edited [:content :transcript])))
    (is (capture/unsent? edited))
    (is (nil? (capture/submitted edited)))
    (testing "editing preserves the capture identity used by delivery"
      (is (= (:id sealed) (:id edited))))))

(deftest send-snapshots-the-reviewed-text-and-destination
  (let [sealed (reviewed "  original  ")
        sent (capture/submit sealed "  Final text  " "group-a" 1000)]
    (is (= {:text "Final text" :group-id "group-a" :at-ms 1000}
           (capture/submitted sent)))
    (is (= "Final text" (get-in sent [:content :text])))
    (is (= "group-a" (capture/destination-group-id sent)))
    (is (= :queued (capture/delivery-state sent)))
    (is (capture/pending? sent))
    (is (not (capture/unsent? sent)))
    (is (capture/delivery-due? sent 1000))
    (testing "the snapshot is queued before any delivery attempt"
      (is (not (capture/delivery-due? sent 999))))
    (testing "another recording may start while this one is pending"
      (is (true? (capture/can-start-recording? [sent]))))))

(deftest a-later-selection-cannot-redirect-a-sent-message
  (let [sent (submitted "Keep me" "group-a")]
    (testing "the destination chosen at Send is frozen into the message"
      (is (= "group-a" (get-in (capture/submitted sent) [:group-id])))
      (is (thrown? js/Error (capture/set-destination sent "group-b")))
      (is (thrown? js/Error (capture/edit-transcript sent "Rewrite")))
      (is (thrown? js/Error (capture/submit sent "Rewrite" "group-b" 2000))))
    (testing "a second Send cannot overwrite the payload already submitted"
      (is (= {:text "Keep me" :group-id "group-a" :at-ms 1000}
             (capture/submitted sent))))))

(deftest private-is-the-absence-of-a-group-id
  (let [sealed (reviewed "A private thought")]
    (testing "nil and blank selections both mean Private"
      (is (nil? (capture/destination-group-id
                 (capture/submit sealed "A private thought" nil 0))))
      (is (nil? (capture/destination-group-id
                 (capture/submit sealed "A private thought" "   " 0))))
      (is (nil? (get-in (capture/submit sealed "A private thought" "" 0)
                        [:local :submitted :group-id]))))
    (testing "selecting a Group then tapping it again returns to Private"
      (is (= "group-a" (capture/destination-group-id
                        (capture/set-destination sealed "group-a"))))
      (is (nil? (capture/destination-group-id
                 (-> sealed
                     (capture/set-destination "group-a")
                     (capture/set-destination nil))))))))

(deftest a-review-needs-text-before-it-can-be-sent
  (let [audio-only (capture/finalize-recording
                    (capture/new-recording base-options)
                    {:audio {:asset-id "asset-1" :mime-type "audio/mp4"}})]
    (is (= :audio (get-in audio-only [:content :type])))
    (is (thrown? js/Error (capture/submit audio-only "   " nil 0)))
    (testing "adding a transcript to the recording keeps it sendable text"
      (let [edited (capture/edit-transcript audio-only "Spoken words")
            sent (capture/submit edited "Spoken words" nil 0)]
        (is (= :audio+text (get-in sent [:content :type])))
        (is (= "asset-1" (get-in sent [:content :audio :asset-id])))
        (is (capture/pending? sent))))))

(deftest discard-resolves-the-sole-unsent-review
  (let [sealed (reviewed "Do not keep this")
        discarded (capture/discard sealed)]
    (is (= :discarded (capture/content-state discarded)))
    (is (= :none (capture/delivery-state discarded)))
    (is (not (capture/unsent? discarded)))
    (is (true? (capture/can-start-recording? [discarded]))))
  (testing "an accepted message cannot be discarded"
    (is (thrown? js/Error
                 (capture/discard (submitted "Already sent" nil))))))

(deftest one-unsent-recording-is-restored-without-delivery
  (let [older (capture/discard (reviewed base-options "older review"))
        restored (reviewed (assoc base-options :id "restored-capture")
                           "Unsent before restart")]
    (testing "restoring finds the editable review in persisted order"
      (is (= restored (capture/unsent-capture [older restored])))
      (is (false? (capture/can-start-recording? [older restored]))))
    (testing "a restored review is never due for delivery by itself"
      (is (= :not-ready (capture/delivery-state restored)))
      (is (not (capture/delivery-due? restored 0)))
      (is (not (capture/delivery-due? restored (js/Date.now))))
      (is (empty? (capture/pending-captures [restored]))))))

(deftest automatic-retry-reuses-the-original-payload
  (let [sent (submitted "Keep me" "group-a")
        failed (capture/reject sent {:message "Group access changed"})
        retried (capture/retry failed 5000)]
    (is (= :rejected (capture/delivery-state failed)))
    (is (capture/needs-attention? failed))
    (is (capture/retryable? failed))
    (is (not (capture/conflicted? failed)))
    (is (= (capture/submitted sent) (capture/submitted retried)))
    (is (= (:destination sent) (:destination retried)))
    (is (= (:id sent) (:id retried)))
    (is (= :queued (capture/delivery-state retried)))
    (is (= 5000 (get-in retried [:local :delivery :next-at-ms])))
    (testing "manual retry starts the backoff over without changing the payload"
      (is (= 0 (get-in retried [:local :delivery :retry-count]))))
    (testing "credentials blocked messages may also be retried"
      (let [blocked (capture/block-auth sent {:message "Unauthorized"})]
        (is (capture/retryable? blocked))
        (is (= (capture/submitted sent)
               (capture/submitted (capture/retry blocked 0))))))))

(deftest reassignment-changes-only-the-destination-under-the-same-capture-id
  (let [sent (submitted "Keep me" "group-a")
        failed (capture/reject sent {:message "Not a member"})]
    (testing "nothing moves until the User explicitly reassigns"
      (is (= "group-a" (capture/destination-group-id failed))))
    (let [moved (capture/reassign failed "group-b" 2000)]
      (is (= "group-b" (capture/destination-group-id moved)))
      (is (= "group-b" (get-in (capture/submitted moved) [:group-id])))
      (is (= "Keep me" (get-in (capture/submitted moved) [:text])))
      (is (= (:id sent) (:id moved)))
      (is (= :queued (capture/delivery-state moved)))
      (is (= 2000 (get-in moved [:local :delivery :next-at-ms]))))
    (testing "reassignment can deliberately choose Private"
      (let [private (capture/reassign failed nil 0)]
        (is (nil? (capture/destination-group-id private)))
        (is (nil? (get-in private [:local :submitted :group-id])))))))

(deftest a-conflict-is-not-a-retryable-failure
  (let [sent (submitted "Keep me" "group-a")
        conflicted (capture/conflict
                    sent
                    {:message "This capture was already sent."
                     :code "capture-conflict"
                     :accepted true})]
    (is (= :conflict (capture/delivery-state conflicted)))
    (is (capture/conflicted? conflicted))
    (is (capture/needs-attention? conflicted))
    (is (not (capture/retryable? conflicted)))
    (testing "the conflicting text and destination are retained for inspection"
      (is (= (capture/submitted sent) (capture/submitted conflicted)))
      (is (= "group-a" (capture/destination-group-id conflicted))))
    (testing "the changed payload is never retried automatically"
      (is (thrown? js/Error (capture/retry conflicted 0)))
      (is (thrown? js/Error (capture/reassign conflicted "group-b" 0))))
    (testing "acknowledging tells the User the original was accepted"
      (let [accepted (capture/acknowledge-conflict conflicted {:status 200})]
        (is (capture/sent? accepted))
        (is (= {:status 200} (get-in accepted [:local :delivery :receipt])))))))

(deftest a-conflict-can-become-a-deliberate-new-entry
  (let [sent (submitted "Keep me" "group-a")
        conflicted (capture/conflict sent {:code "capture-conflict"
                                           :accepted true})
        fresh (capture/new-entry conflicted "2026-09-09T00:00:00.000Z" 3000)]
    (is (not= (:id conflicted) (:id fresh)))
    (is (= "Keep me" (get-in fresh [:content :text])))
    (is (= "group-a" (capture/destination-group-id fresh)))
    (is (= {:text "Keep me" :group-id "group-a" :at-ms 3000}
           (capture/submitted fresh)))
    (is (= "2026-09-09T00:00:00.000Z" (:occurred-at fresh)))
    (is (= :queued (capture/delivery-state fresh)))
    (testing "the conflicting original keeps its own state and identity"
      (is (= :conflict (capture/delivery-state conflicted)))
      (is (= (:id sent) (:id conflicted))))))

(deftest pending-sent-and-attention-lists-are-distinct
  (let [options (fn [id occurred-at]
                  (assoc base-options :id id :occurred-at occurred-at))
        queued (submitted (options "queued" "2026-09-08T10:00:00.000Z")
                          "queued text" nil)
        failed (-> (submitted (options "failed" "2026-09-08T11:00:00.000Z")
                              "failed text" "group-a")
                   (capture/reject {:message "Not a member"}))
        accepted (-> (submitted (options "accepted" "2026-09-08T12:00:00.000Z")
                                "accepted text" nil)
                     (capture/transition-delivery
                      :leased {:lease {:owner "test" :until-ms 60000}})
                     (capture/accept {:status 200}))
        conflicted (-> (submitted (options "conflicted" "2026-09-08T13:00:00.000Z")
                                  "conflicted text" "group-b")
                       (capture/conflict {:code "capture-conflict"}))
        requests [queued failed accepted conflicted]]
    (testing "pending covers queued, in flight and needs-attention work"
      (is (= ["conflicted" "failed" "queued"]
             (mapv :id (capture/pending-captures requests)))))
    (testing "Sent is shown only after Smriti accepted the message"
      (is (= ["accepted"] (mapv :id (capture/accepted-captures requests)))))
    (testing "needs attention is a pending subset"
      (is (= ["conflicted" "failed"]
             (mapv :id (capture/failed-captures requests)))))))

(deftest a-new-capture-starts-private-and-keeps-its-own-identity
  (let [sent (submitted "First thought" "group-a")
        next-recording (capture/new-recording base-options)]
    (testing "a session selection is applied at Send, never inherited from storage"
      (is (nil? (capture/destination-group-id next-recording)))
      (is (= "group-a" (capture/destination-group-id sent))))
    (testing "two captures of the same text are different captures"
      (let [first (submitted "Same words" nil)
            second (submitted (assoc base-options :id "second-capture")
                              "Same words" nil)]
        (is (not= (:id first) (:id second)))))))

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
  (let [request (submitted "A thought" nil)]
    (is (thrown? js/Error (capture/transition-delivery request :accepted {})))
    (is (thrown? js/Error (capture/edit-transcript request "changed")))
    (is (thrown? js/Error (capture/submit request "changed" nil 0)))
    (is (thrown? js/Error (capture/retry request 0)))))
