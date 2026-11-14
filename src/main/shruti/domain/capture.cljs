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
  {:not-ready #{:queued :none}
   :queued #{:leased :blocked-auth :rejected :none}
   :leased #{:queued :accepted :blocked-auth :rejected}
   :accepted #{}
   :blocked-auth #{:queued :none}
   :rejected #{:queued :none}
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

(defn valid? [{:keys [id occurred-at source content locale local] :as capture}]
  (and (string? id)
       (string? occurred-at)
       (valid-source? source)
       (contains? content-types (:type content))
       (string? locale)
       (contains? content-transitions (:content-state local))
       (contains? delivery-transitions (get-in local [:delivery :state]))
       (= schema-version (:schema-version capture))))

(defn assert-valid! [capture]
  (when-not (valid? capture)
    (throw (ex-info "Invalid CaptureRequest" {:capture capture})))
  capture)

(defn- base-capture [{:keys [id occurred-at source content locale tags metadata]} local]
  (let [request {:schema-version schema-version
                 :id (required! id "id")
                 :occurred-at (required! occurred-at "occurred-at")
                 :source source
                 :content content
                 :locale (or locale "und")
                 :tags (vec (or tags []))
                 :metadata (or metadata {})
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

(defn finalize-recording
  "Seals a voice capture from either an audio asset, a final transcript, or both.

   A platform recognizer can produce text without retaining audio. That is still a
   valid voice capture: the source remains :voice while the content becomes :text."
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
                 {:type :audio+text :transcript transcript :audio audio}

                 audio?
                 {:type :audio :transcript nil :audio audio}

                 :else
                 {:type :text :text transcript :transcript transcript :audio nil}))
        (transition-content :ready)
        (transition-delivery :queued {:next-at-ms 0}))))

(defn discard [request]
  (let [state (content-state request)]
    (when-not (contains? #{:recording :finalizing :ready} state)
      (throw (ex-info "Capture cannot be discarded" {:capture-id (:id request) :state state})))
    (-> request
        (assoc-in [:local :content-state] :discarded)
        (assoc-in [:local :delivery :state] :none))))

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

(defn backend-manifest [request]
  (-> request
      (dissoc :local)
      (update-in [:content :audio]
                 (fn [audio]
                   (when audio
                     (dissoc audio :asset-id :file-uri :blob))))))
