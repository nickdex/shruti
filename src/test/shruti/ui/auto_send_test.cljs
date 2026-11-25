(ns shruti.ui.auto-send-test
  (:require [cljs.test :refer [deftest is testing]]
            [shruti.ui.auto-send :as auto-send]))

(def ^:private duration auto-send/duration-ms)

(defn- expire!
  "Runs every scheduled callback whose time is due, like the environment does."
  [{:keys [!model !fired !now !pending]}]
  (let [due (filter #(<= (:due %) @!now) @!pending)]
    (swap! !pending #(vec (remove (set due) %)))
    (doseq [{:keys [callback]} due]
      (callback))
    {:fired @!fired :model @!model}))

(defn- with-clock
  "Runs body with a controllable clock and scheduler. A started countdown
   records its callback; `run` advances time and executes what is due."
  [body]
  (let [!now (atom 1000)
        !pending (atom [])]
    (binding [auto-send/*now-ms* (fn [] @!now)
              auto-send/*schedule!* (fn [callback delay-ms]
                                      (swap! !pending conj {:callback callback
                                                            :due (+ @!now delay-ms)}))]
      (let [!model (atom {:review-id "review-1"})
            !fired (atom [])
            expire (fn [] (expire! {:!model !model :!fired !fired
                                    :!now !now :!pending !pending}))]
        (body {:!model !model :!fired !fired :!now !now :!pending !pending
               :start! (fn []
                         (auto-send/start! !model
                                           (fn [review-id]
                                             (swap! !fired conj review-id)
                                             (swap! !model assoc :review-id nil))))
               :run (fn [at-ms] (reset! !now at-ms) (expire))})))))

(deftest a-countdown-fires-once-after-three-seconds
  (with-clock
    (fn [{:keys [!model !fired !now start! run]}]
      (start!)
      (testing "nothing submits before the duration has elapsed"
        (is (auto-send/countdown? @!model))
        (is (= 0 (auto-send/fraction @!model @!now)))
        (let [{:keys [fired model]} (run (+ @!now (- duration 1)))]
          (is (empty? fired))
          (is (= "review-1" (:review-id model)))))
      (testing "expiry submits exactly one automatic Send"
        (let [{:keys [fired model]} (run (+ @!now duration))]
          (is (= ["review-1"] fired))
          (is (nil? (:review-id model))
              "the fired callback consumed the review before submitting")
          (is (nil? (:auto-send model))))))))

(deftest manual-send-during-the-countdown-prevents-auto-submission
  (with-clock
    (fn [{:keys [!model !now start! run]}]
      (start!)
      (testing "the User pressed Send immediately"
        (auto-send/cancel! !model)
        (is (nil? (:auto-send @!model)))
        (is (= 0 (auto-send/fraction @!model))))
      (let [{:keys [fired]} (run (+ @!now (* 10 duration)))]
        (testing "the timer's expiry must not submit the same recording twice"
          (is (empty? fired)))))))

(deftest cancellation-stops-the-fill-and-keeps-the-review
  (with-clock
    (fn [{:keys [!model !now start! run]}]
      (start!)
      (swap! !now #(+ % (quot duration 2)))
      (is (= 0.5 (auto-send/fraction @!model @!now)))
      (auto-send/cancel! !model)
      (is (nil? (:auto-send @!model)))
      (testing "the recording stays available for manual Send or Discard"
        (is (= "review-1" (:review-id @!model))))
      (let [{:keys [fired]} (run (+ @!now (* 10 duration)))]
        (is (empty? fired))
        (testing "the countdown never restarts after cancellation"
          (is (not (auto-send/countdown? @!model))))))))

(deftest a-stale-expiry-cannot-fire-a-replaced-review
  (with-clock
    (fn [{:keys [!model !now start! run]}]
      (start!)
      (auto-send/cancel! !model)
      (start!)
      (testing "only the newest timer may fire"
        (let [{:keys [fired]} (run (+ @!now (* 2 duration)))]
          (is (= ["review-1"] fired)
              "the stale callback expired but the newest countdown fires exactly once"))))))

(deftest fill-progress-is-derivable-without-timers
  (let [model {:auto-send {:started-at-ms 0 :expires-at-ms duration}}
        span auto-send/duration-ms]
    (testing "the fill fraction grows from left to right, clamped to 1"
      (is (= 0 (auto-send/fraction model 0)))
      (is (= 0.25 (auto-send/fraction model (quot span 4))))
      (is (= 1 (auto-send/fraction model span)))
      (is (= 1 (auto-send/fraction model (* 10 span)))))
    (testing "a model without a countdown shows no fill"
      (is (= 0 (auto-send/fraction {} span))))
    (testing "an empty or blank transcript never enters the countdown"
      (is (false? (auto-send/sendable-transcript? "   ")))
      (is (false? (auto-send/sendable-transcript? "")))
      (is (true? (auto-send/sendable-transcript? "Buy potatoes"))))))
