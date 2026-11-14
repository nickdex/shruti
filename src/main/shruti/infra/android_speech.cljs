(ns shruti.infra.android-speech
  (:require [goog.object :as gobj]
            [clojure.string :as str]
            [shruti.application.ports :as ports]))

(defn- plugin []
  (some-> (gobj/get js/window "Capacitor")
          (gobj/get "Plugins")
          (gobj/get "ShrutiSpeechRecognition")))

(defn- native-call
  ([native-plugin method]
   (.call (gobj/get native-plugin method) native-plugin))
  ([native-plugin method options]
   (.call (gobj/get native-plugin method) native-plugin options)))

(defn available? []
  (some? (plugin)))

(defn- transcript-from [result]
  (some-> (gobj/get result "transcript") str str/trim not-empty))

(defrecord AndroidSpeechCapture [native-plugin !session]
  ports/VoiceCapture
  (start! [_ {:keys [capture-id locale]}]
    (if @!session
      (js/Promise.reject (js/Error. "A voice capture is already active"))
      (do
        (reset! !session {:capture-id capture-id :state :starting})
        (-> (native-call native-plugin "startListening" (clj->js {:locale locale}))
            (.then (fn [_]
                     (reset! !session {:capture-id capture-id :state :listening})
                     {:capture-id capture-id}))
            (.catch (fn [error]
                      (reset! !session nil)
                      (js/Promise.reject error)))))))
  (stop! [_]
    (if-not @!session
      (js/Promise.reject (js/Error. "No voice capture is active"))
      (do
        (swap! !session assoc :state :stopping)
        (-> (native-call native-plugin "stopListening")
            (.then (fn [result]
                     (if-let [transcript (transcript-from result)]
                       (do
                         (reset! !session nil)
                         {:transcript transcript})
                       (js/Promise.reject
                        (js/Error. "No speech was recognized. Please try again.")))))
            (.catch (fn [error]
                      (reset! !session nil)
                      (js/Promise.reject error)))))))
  (cancel! [_]
    (if @!session
      (-> (native-call native-plugin "cancelListening")
          (.then (fn [_]
                   (reset! !session nil)
                   true))
          (.catch (fn [error]
                    (reset! !session nil)
                    (js/Promise.reject error))))
      (js/Promise.resolve true))))

(defn create []
  (->AndroidSpeechCapture (plugin) (atom nil)))
