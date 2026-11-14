(ns shruti.application.intake
  (:require [shruti.application.ports :as ports]
            [shruti.domain.capture :as capture]))

(defn now-iso [] (.toISOString (js/Date.)))

(defn capture-options [{:keys [source locale tags metadata]}]
  {:id (capture/capture-id)
   :occurred-at (now-iso)
   :source source
   :locale (or locale "und")
   :tags tags
   :metadata metadata})

(defn create-text [input]
  (capture/new-text (assoc (capture-options input) :content {:text (:text input)})))

(defn create-recording [input]
  (capture/new-recording (capture-options input)))

(defn persist-text! [store input]
  (let [request (create-text input)]
    (-> (ports/put-capture! store request)
        (.then (fn [_] request)))))

