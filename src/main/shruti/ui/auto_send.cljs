(ns shruti.ui.auto-send
  "The three-second auto-send experiment.

   One countdown may run at a time. It starts only for a newly finalized,
   non-empty transcript whose review is saved locally and displayed, and it is
   cancelled by any other interaction with the review, any destination
   interaction, backgrounding, or a completed Send. The app never auto-sends a
   restored or backgrounded review; only a fresh recording enters the
   countdown.

   Time is injectable through `*now-ms*` and `*schedule!*` so tests can drive
   the countdown deterministically without sleeping."
  (:require [clojure.string :as str]))

(def duration-ms 3000)

(defonce ^:dynamic *now-ms*
  (fn [] (js/Date.now)))

(defonce ^:dynamic *schedule!*
  (fn [callback delay-ms]
    (js/setTimeout callback delay-ms)))

(defn- now-ms [] (*now-ms*))

(defn countdown? [model]
  (and (some? (:auto-send model))
       (> (:expires-at-ms (:auto-send model)) (now-ms))))

(defn- active-or-cancelled
  "A model where an expired countdown counts as cancelled: expiry is only
   observed by the timer callback, so any state read after expiry must not
   treat the countdown as running."
  [model]
  (if (countdown? model) model (dissoc model :auto-send)))

(defn start!
  "Begin the countdown for the current review. The fill progress is derived,
   never stored: `started-at-ms` plus duration is the whole truth."
  [!model on-fire!]
  (let [expires-at-ms (+ (now-ms) duration-ms)
        timer (*schedule!*
               (fn []
                 (let [{:keys [auto-send review-id] :as model} (deref !model)]
                   (when (and auto-send
                              (= expires-at-ms (:expires-at-ms auto-send))
                              review-id)
                     (swap! !model dissoc :auto-send)
                     (on-fire! review-id))))
               duration-ms)]
    (swap! !model assoc :auto-send {:expires-at-ms expires-at-ms
                                    :started-at-ms (now-ms)
                                    :timer timer})))

(defn cancel!
  "Stop the countdown without sending. A recording stays available for manual
   Send or Discard; the countdown never restarts afterwards."
  [!model]
  (when-let [timer (:timer (:auto-send @!model))]
    (js/clearTimeout timer))
  (swap! !model dissoc :auto-send))

(defn fraction
  "Elapsed fraction of the countdown, 0 at start and 1 at expiry, for the
   Send button fill."
  ([model] (fraction model (now-ms)))
  ([model now]
   (let [{:keys [auto-send]} model]
     (if-not auto-send
       0
       (let [{:keys [started-at-ms expires-at-ms]} auto-send
             span (- expires-at-ms started-at-ms)]
         (if (pos? span)
           (-> (- now started-at-ms)
               (/ span)
               (max 0)
               (min 1))
           0))))))

(defn sendable-transcript? [text]
  (not (str/blank? (str text))))
