(ns shruti.application.sync
  (:require [shruti.application.ports :as ports]
            [shruti.domain.capture :as capture]))

(defn now-ms [] (.getTime (js/Date.)))

(defn- update-capture! [store request]
  (-> (ports/put-capture! store request)
      (.then (fn [_] request))))

(defn result->capture
  "Apply one delivery result to its capture.

   `:conflict` is not a failure to retry: Smriti proved this capture id was
   already accepted but the payload changed. The message keeps its text and
   destination, is excluded from automatic delivery, and waits for an
   explicit decision (reassign a failed message, retry the identical
   payload, or send as a new entry). Retryable failures keep the queue.
   Authentication failures stay visible until the key is fixed."
  [request result]
  (case (:kind result)
    :accepted (capture/accept request (:receipt result))
    :duplicate (capture/accept request (:receipt result))
    :conflict (capture/conflict request (:error result))
    :auth-required (capture/block-auth request (:error result))
    :rejected (capture/reject request (:error result))
    :retryable (capture/schedule-retry request (now-ms) (js/Math.random)
                                       (:retry-after-ms result) (:error result))
    :configuration-error (capture/reject request (:error result))
    (capture/schedule-retry request (now-ms) (js/Math.random) nil
                            {:message "Unknown transport result" :result result})))

(defn sync-one! [{:keys [store transport auth]} request]
  (let [leased (capture/transition-delivery
                request :leased {:lease {:owner "web" :until-ms (+ (now-ms) 120000)}})
        audio (get-in leased [:content :audio])]
    (-> (update-capture! store leased)
        (.then (fn [_]
                 (if audio
                   (ports/get-media! store (:asset-id audio))
                   (js/Promise.resolve nil))))
        (.then (fn [media]
                 (if (and audio (nil? media))
                   {:kind :rejected :error {:message "Local audio asset is unavailable"}}
                   (-> (ports/request-auth! auth)
                       (.then (fn [request-auth]
                                (ports/deliver! transport leased media request-auth)))))))
        (.then (fn [result]
                 (update-capture! store (result->capture leased result))))
        (.then (fn [updated]
                 (if (= :accepted (capture/delivery-state updated))
                   (if-let [asset-id (get-in updated [:content :audio :asset-id])]
                     (-> (ports/delete-media! store asset-id)
                         (.then (fn [_] updated))
                         (.catch (fn [_] updated)))
                     updated)
                   updated)))
        (.catch
         (fn [error]
           (update-capture!
            store
            (capture/schedule-retry leased (now-ms) (js/Math.random) nil
                                    {:message (or (.-message error) "Local delivery error")})))))))

(defn- recover-expired-leases! [store requests current-time]
  (let [expired (filter (fn [request]
                          (let [delivery (get-in request [:local :delivery])]
                            (and (= :leased (:state delivery))
                                 (<= (or (get-in delivery [:lease :until-ms]) 0) current-time))))
                        requests)]
    (js/Promise.all
     (clj->js
      (map (fn [request]
             (update-capture! store
                              (capture/transition-delivery request :queued
                                                           {:lease nil :next-at-ms 0})))
           expired)))))

(defn sync-due! [system]
  (let [{:keys [store]} system
        current-time (now-ms)]
    (-> (ports/list-captures! store)
        (.then
         (fn [requests]
           (-> (recover-expired-leases! store requests current-time)
               (.then (fn [_] (ports/list-captures! store))))))
        (.then
         (fn [requests]
           (let [due (filter #(capture/delivery-due? % current-time) requests)]
             (js/Promise.all (clj->js (map #(sync-one! system %) due)))))))))
