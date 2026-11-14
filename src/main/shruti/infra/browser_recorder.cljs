(ns shruti.infra.browser-recorder
  (:require [clojure.string :as str]
            [shruti.application.ports :as ports]))

(defn- recorder-options []
  (let [preferred "audio/webm;codecs=opus"]
    (if (and (exists? js/MediaRecorder)
             (.isTypeSupported js/MediaRecorder preferred))
      #js {:mimeType preferred}
      #js {})))

(defn- stop-stream! [stream]
  (doseq [track (array-seq (.getTracks stream))]
    (.stop track)))

(defrecord BrowserVoiceCapture [!session]
  ports/VoiceCapture
  (start! [_ {:keys [capture-id]}]
    (if @!session
      (js/Promise.reject (js/Error. "A recording is already active"))
      (-> (.getUserMedia (.-mediaDevices js/navigator) #js {:audio true})
          (.then
           (fn [stream]
             (let [chunks (array)
                   recorder (js/MediaRecorder. stream (recorder-options))]
               (set! (.-ondataavailable recorder)
                     (fn [event]
                       (let [data (.-data event)]
                         (when (pos? (.-size data))
                           (.push chunks data)))))
               (.start recorder 1000)
               (reset! !session {:capture-id capture-id
                                 :stream stream
                                 :recorder recorder
                                 :chunks chunks
                                 :started-at (.now js/Date)})
               {:capture-id capture-id}))))))
  (stop! [_]
    (if-let [{:keys [capture-id stream recorder chunks started-at]} @!session]
      (js/Promise.
       (fn [resolve reject]
         (set! (.-onerror recorder) (fn [event] (reject (.-error event))))
         (set! (.-onstop recorder)
               (fn [_]
                 (let [mime-type (let [type (.-mimeType recorder)]
                                   (if (str/blank? type) "audio/webm" type))
                       blob (js/Blob. chunks #js {:type mime-type})]
                   (stop-stream! stream)
                   (reset! !session nil)
                   (resolve {:audio {:asset-id capture-id
                                     :blob blob
                                     :mime-type mime-type
                                     :bytes (.-size blob)
                                     :duration-ms (- (.now js/Date) started-at)}}))))
         (try
           (.stop recorder)
           (catch :default error
             (reject error)))))
      (js/Promise.reject (js/Error. "No recording is active"))))
  (cancel! [_]
    (when-let [{:keys [stream recorder]} @!session]
      (when (= "recording" (.-state recorder))
        (.stop recorder))
      (stop-stream! stream)
      (reset! !session nil))
    (js/Promise.resolve true)))

(defn create [] (->BrowserVoiceCapture (atom nil)))
