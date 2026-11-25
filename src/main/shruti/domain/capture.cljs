(ns shruti.domain.capture
  (:require [clojure.string :as str]))

(def schema-version 1)

(def source-kinds #{:voice :text :audio :url :clipboard})
(def entrypoints #{:app :share-sheet :widget :shortcut :quick-settings :app-intent
                   :app-action :url-scheme :notification :external-accessory})
(def platforms #{:web :ios :android})
(def content-types #{:text :audio :audio+text})

(def content-transitions
  {:recording #{:finalizing :discarded}
   :finalizing #{:ready :discarded}
   :ready #{:discarded}
   :discarded #{}})

(def delivery-transitions
  "A capture is born :not-ready (the sole editable unsent review) and only an
   explicit Send moves it to :queued. :conflict is the uncertain case where the
   server already accepted the capture but the payload changed before a retry;
   it can only be resolved by acknowledging that acceptance, never by retrying
   the changed payload."
  {:not-ready #{:queued :none}
   :queued #{:leased :blocked-auth :rejected :conflict :none}
   :leased #{:queued :accepted :blocked-auth :rejected :conflict}
   :accepted #{}
   :blocked-auth #{:queued :none}
   :rejected #{:queued :none}
   :conflict #{:accepted :none}
   :none #{}})

(defn capture-id []
  (if-let [random-uuid (some-> js/crypto .-randomUUID)]
    (.call random-uuid js/crypto)
    (str (random-uuid) "-" (.getTime (js/Date.)))))

(defn- required! [value label]
  (when (or (nil? value) (and (string? value) (str/blank? value)))
    (throw (ex-info (str label " is required") {:label label})))
  value)

(defn- valid-source? [{:keys [kind entrypoint platform]}]
  (and (contains? source-kinds kind)
       (contains? entrypoints entrypoint)
       (contains? platforms platform)))

(defn- valid-destination?
  "Private is the absence of a Group id. A present id is always a nonblank
   stable Group id, never a display name."
  [{:keys [group-id]}]
  (or (nil? group-id)
      (and (string? group-id) (not (str/blank? group-id)))))

(defn valid? [{:keys [id occurred-at source content locale local destination] :as capture}]
  (and (string? id)
       (string? occurred-at)
       (valid-source? source)
       (contains? content-types (:type content))
       (string? locale)
       (contains? content-transitions (:content-state local))
       (contains? delivery-transitions (get-in local [:delivery :state]))
       (or (nil? destination) (valid-destination? destination))
       (= schema-version (:schema-version capture))))

(defn assert-valid! [capture]
  (when-not (valid? capture)
    (throw (ex-info "Invalid CaptureRequest" {:capture capture})))
  capture)

(defn- base-capture [{:keys [id occurred-at source content locale tags metadata destination]} local]
  (let [request {:schema-version schema-version
                 :id (required! id "id")
                 :occurred-at (required! occurred-at "occurred-at")
                 :source source
                 :content content
                 :locale (or locale "und")
                 :tags (vec (or tags []))
                 :metadata (or metadata {})
                 :destination (or destination {:group-id nil})
                 :local local}]
    (assert-valid! request)))

(defn new-recording [options]
  (base-capture
   (assoc options :content {:type :audio :transcript nil :audio nil})
   {:content-state :recording
    :delivery {:state :not-ready
               :retry-count 0
               :next-at-ms nil
               :lease nil
               :last-error nil
               :receipt nil}}))

(defn new-text [options]
  (let [text (get-in options [:content :text])]
    (required! text "content.text")
    (base-capture
     (assoc options :content {:type :text :text text :transcript text :audio nil})
     {:content-state :ready
      :submitted {:text text
                  :group-id (get-in options [:destination :group-id])
                  :at-ms 0}
      :delivery {:state :queued
                 :retry-count 0
                 :next-at-ms 0
                 :lease nil
                 :last-error nil
                 :receipt nil}})))

(defn content-state [request] (get-in request [:local :content-state]))
(defn delivery-state [request] (get-in request [:local :delivery :state]))

(defn transition-content [request next-state]
  (let [current (content-state request)]
    (when-not (contains? (get content-transitions current #{}) next-state)
      (throw (ex-info "Invalid content transition"
                      {:from current :to next-state :capture-id (:id request)})))
    (assoc-in request [:local :content-state] next-state)))

(defn transition-delivery [request next-state patch]
  (let [current (delivery-state request)]
    (when-not (contains? (get delivery-transitions current #{}) next-state)
      (throw (ex-info "Invalid delivery transition"
                      {:from current :to next-state :capture-id (:id request)})))
    (-> request
        (assoc-in [:local :delivery :state] next-state)
        (update-in [:local :delivery] merge patch))))

(defn- normalized-transcript [value]
  (let [value (some-> value str str/trim)]
    (when (seq value) value)))

(defn destination-group-id [request] (get-in request [:destination :group-id]))
(defn submitted [request] (get-in request [:local :submitted]))

(defn unsent?
  "The one editable recording awaiting an explicit Send or Discard."
  [request]
  (and (= :ready (content-state request))
       (= :not-ready (delivery-state request))))

(defn pending?
  "Submitted work still owned locally: queued, in flight, or needing attention."
  [request]
  (and (= :ready (content-state request))
       (contains? #{:queued :leased :blocked-auth :rejected :conflict}
                  (delivery-state request))))

(defn needs-attention? [request]
  (contains? #{:blocked-auth :rejected :conflict} (delivery-state request)))

(defn retryable? [request]
  (contains? #{:blocked-auth :rejected} (delivery-state request)))

(defn conflicted? [request] (= :conflict (delivery-state request)))

(defn sent? [request] (= :accepted (delivery-state request)))

(defn unsent-capture
  "The sole unsent review in `requests`, or nil."
  [requests]
  (first (filter unsent? requests)))

(defn can-start-recording?
  "A new recording waits for the current unsent review to be Sent or Discarded."
  [requests]
  (nil? (unsent-capture requests)))

(defn newest-first [requests]
  (vec (sort-by :occurred-at #(compare %2 %1) requests)))

(defn pending-captures [requests]
  (newest-first (filter pending? requests)))

(defn accepted-captures [requests]
  (newest-first (filter sent? requests)))

(defn failed-captures [requests]
  (newest-first (filter needs-attention? requests)))

(defn finalize-recording
  "Seals a voice capture from either an audio asset, a final transcript, or both.

   A platform recognizer can produce text without retaining audio. That is still a
   valid voice capture: the source remains :voice while the content becomes :text.

   The sealed capture stays :not-ready: it is the User's sole unsent review and
   is never delivered until an explicit Send."
  [request {:keys [audio transcript]}]
  (when-not (= :recording (content-state request))
    (throw (ex-info "Only recordings can be finalized" {:capture-id (:id request)})))
  (let [transcript (normalized-transcript transcript)
        audio? (some? audio)]
    (when (and audio? (not (string? (:asset-id audio))))
      (throw (ex-info "A finalized audio recording requires an asset id"
                      {:capture-id (:id request)})))
    (when-not (or audio? transcript)
      (throw (ex-info "A finalized voice capture requires audio or a transcript"
                      {:capture-id (:id request)})))
    (-> request
        (transition-content :finalizing)
        (assoc :content
               (cond
                 (and audio? transcript)
                 {:type :audio+text :text transcript :transcript transcript :audio audio}

                 audio?
                 {:type :audio :text nil :transcript nil :audio audio}

                 :else
                 {:type :text :text transcript :transcript transcript :audio nil}))
        (transition-content :ready)
        (assoc-in [:local :delivery :next-at-ms] nil))))

(defn edit-transcript
  "Replace the reviewed text of the sole unsent capture. Never touches a
   submitted message, whose text is frozen for idempotent retries."
  [request text]
  (when-not (unsent? request)
    (throw (ex-info "Only an unsent capture can be edited"
                    {:capture-id (:id request) :state (delivery-state request)})))
  (let [text (normalized-transcript text)
        audio (get-in request [:content :audio])
        content (cond
                  (and audio text)
                  {:type :audio+text :text text :transcript text :audio audio}

                  audio
                  {:type :audio :text nil :transcript nil :audio audio}

                  :else
                  {:type :text :text text :transcript nil :audio nil})]
    (assoc request :content (assoc content :text text :transcript text))))

(defn set-destination
  "Private is a nil Group id; selecting a Group again is how the User clears it.
   Allowed while reviewing and while correcting a failed delivery."
  [request group-id]
  (when-not (contains? #{:not-ready :blocked-auth :rejected} (delivery-state request))
    (throw (ex-info "This capture's destination cannot change"
                    {:capture-id (:id request) :state (delivery-state request)})))
  (assoc request :destination
         {:group-id (not-empty (str/trim (str (or group-id ""))))}))

(defn submit
  "Snapshot the reviewed text and destination, then queue for delivery.

   The snapshot is the submitted payload identity: automatic retries reuse it
   unchanged, so the same capture id and meaning reach Smriti as one Entry."
  [request text group-id now-ms]
  (when-not (unsent? request)
    (throw (ex-info "Only an unsent capture can be sent"
                    {:capture-id (:id request) :state (delivery-state request)})))
  (let [text (normalized-transcript text)]
    (when-not text
      (throw (ex-info "A capture needs text before it can be sent"
                      {:capture-id (:id request)})))
    (let [request (-> request
                      (edit-transcript text)
                      (set-destination group-id))
          group-id (destination-group-id request)]
      (-> request
          (assoc-in [:local :submitted]
                    {:text text :group-id group-id :at-ms (or now-ms 0)})
          (transition-delivery :queued {:retry-count 0
                                        :next-at-ms (or now-ms 0)
                                        :lease nil
                                        :last-error nil
                                        :receipt nil})))))

(defn reassign
  "Correct a failed message's destination and queue it again under the same
   capture id. The server accepts the correction only if no Entry was created;
   otherwise it answers with a conflict."
  [request group-id now-ms]
  (when-not (retryable? request)
    (throw (ex-info "This capture cannot be reassigned"
                    {:capture-id (:id request) :state (delivery-state request)})))
  (let [request (set-destination request group-id)
        group-id (destination-group-id request)]
    (-> request
        (assoc-in [:local :submitted :group-id] group-id)
        (transition-delivery :queued {:retry-count 0
                                      :next-at-ms (or now-ms 0)
                                      :lease nil
                                      :last-error nil}))))

(defn retry
  "Queue a failed message again without changing its submitted payload."
  [request now-ms]
  (when-not (retryable? request)
    (throw (ex-info "This capture cannot be retried"
                    {:capture-id (:id request) :state (delivery-state request)})))
  (transition-delivery request :queued {:retry-count 0
                                        :next-at-ms (or now-ms 0)
                                        :lease nil}))

(defn discard
  "Resolve the sole unsent review (or clean up a recording that never started).
   A submitted message is never discarded: it stays in Pending until Smriti
   accepts it or the User reassigns/retries it."
  [request]
  (when-not (or (unsent? request)
                (contains? #{:recording :finalizing} (content-state request)))
    (throw (ex-info "Only an unsent capture can be discarded"
                    {:capture-id (:id request) :state (delivery-state request)})))
  (-> request
      (assoc-in [:local :content-state] :discarded)
      (assoc-in [:local :delivery :state] :none)
      (assoc-in [:local :delivery :lease] nil)))

(defn new-entry
  "An explicit new capture carrying the same reviewed text and destination under
   a fresh capture id. Used after a conflict proves the original was accepted,
   so intentionally creating another Entry never reuses the accepted id."
  [request occurred-at now-ms]
  (let [text (or (get-in request [:content :text])
                 (get-in request [:content :transcript]))]
    (base-capture
     {:id (capture-id)
      :occurred-at occurred-at
      :source (:source request)
      :content {:type :text :text text :transcript text :audio nil}
      :locale (:locale request)
      :tags (:tags request)
      :metadata (:metadata request)
      :destination (:destination request)}
     {:content-state :ready
      :submitted {:text text
                  :group-id (destination-group-id request)
                  :at-ms (or now-ms 0)}
      :delivery {:state :queued
                 :retry-count 0
                 :next-at-ms (or now-ms 0)
                 :lease nil
                 :last-error nil
                 :receipt nil}})))

(defn delivery-due? [request now-ms]
  (let [{:keys [state next-at-ms]} (get-in request [:local :delivery])]
    (and (= :ready (content-state request))
         (= :queued state)
         (<= (or next-at-ms 0) now-ms))))

(defn retry-delay-ms
  "Capped exponential backoff with full jitter. `random` must be in [0, 1]."
  [retry-count random]
  (let [cap-ms (min 3600000 (* 1000 (Math/pow 2 (min 12 retry-count))))]
    (long (* cap-ms (max 0 (min 1 random))))))

(defn schedule-retry [request now-ms random retry-after-ms error]
  (let [retry-count (inc (or (get-in request [:local :delivery :retry-count]) 0))
        delay-ms (or retry-after-ms (retry-delay-ms retry-count random))]
    (-> request
        (transition-delivery :queued {:retry-count retry-count
                                      :next-at-ms (+ now-ms delay-ms)
                                      :lease nil
                                      :last-error error}))))

(defn accept [request receipt]
  (transition-delivery request :accepted {:receipt receipt :lease nil :last-error nil}))

(defn block-auth [request error]
  (transition-delivery request :blocked-auth {:lease nil :last-error error}))

(defn reject [request error]
  (transition-delivery request :rejected {:lease nil :last-error error}))

(defn conflict
  "The uncertain case: the server says this capture id was already accepted but
   the submitted payload no longer matches. The capture keeps its text and
   destination; only an explicit decision resolves it."
  [request error]
  (transition-delivery request :conflict {:lease nil :last-error error}))

(defn acknowledge-conflict
  "Mark the conflicting capture as the accepted original, keeping its receipt."
  [request receipt]
  (transition-delivery request :accepted {:receipt receipt :lease nil}))

(defn backend-manifest [request]
  (-> request
      (dissoc :local)
      (update-in [:content :audio]
                 (fn [audio]
                   (when audio
                     (dissoc audio :asset-id :file-uri :blob))))))
