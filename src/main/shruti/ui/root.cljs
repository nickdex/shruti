(ns shruti.ui.root
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [reagent.core :as r]
            [shruti.application.intake :as intake]
            [shruti.application.ports :as ports]
            [shruti.application.sync :as sync]
            [shruti.composition :as composition]
            [shruti.domain.capture :as capture]
            [shruti.infra.smriti-command :as smriti-command]
            [shruti.ui.auto-send :as auto-send]))

(def ^:private delivery-poll-ms 15000)

(defn- vibrate! []
  (when-let [vibrate (some-> js/navigator .-vibrate)]
    (.call vibrate js/navigator 12)))

(defn- source [system]
  {:kind :voice
   :entrypoint :app
   :platform (get-in system [:config :platform])})

(defn- recording-input [system]
  {:source (source system)
   :locale (get-in system [:config :locale])
   :metadata {:client (get-in system [:config :client])}})

(defn- error-message [error]
  (or (.-message error) "The last action could not be completed. Try again."))

(defn- default-server-url [system]
  (get-in system [:config :server-url]))

(defn- delivery-configured? [!model]
  (= :configured (:delivery-state @!model)))

(defn- capture-text [request]
  (or (get-in request [:content :text])
      (get-in request [:content :transcript])
      ""))

(defn- find-capture [model capture-id]
  (first (filter #(= capture-id (:id %)) (:captures model))))

(defn- groups-by-id [model]
  (into {} (map (juxt :id identity) (:groups model))))

(defn- group-name [model group-id]
  (or (:name (get (groups-by-id model) group-id)) "Group"))

(defn- destination-label [model group-id]
  (if group-id (group-name model group-id) "Private"))

(defn- pill-group-ids
  "Server order and length win. Only an unconfigured payload falls back to the
   first three alphabetical Groups, which is what a server-side fallback also
   produces."
  [model]
  (if (and (empty? (:quick-picks model))
           (not (:groups-configured? model)))
    (->> (:groups model)
         (sort-by (juxt :name :id))
         (take 3)
         (mapv :id))
    (:quick-picks model)))

(defn- select-destination! [!model group-id]
  ;; Any destination interaction cancels auto-send: changing the audience
  ;; must never race an automatic submission.
  (auto-send/cancel! !model)
  (swap! !model assoc :destination-group-id group-id))

(defn- toggle-destination! [!model group-id]
  (auto-send/cancel! !model)
  (swap! !model update :destination-group-id
         (fn [current] (when-not (= current group-id) group-id))))

;; ---------------------------------------------------------------------------
;; Local capture state

(defn- clear-draft-timer! [!model]
  (when-let [timer (:draft-timer @!model)]
    (js/clearTimeout timer))
  (swap! !model dissoc :draft-timer))

(defn- refresh-captures!
  "Re-read local captures after any local write or delivery attempt.

   The review draft survives a refresh for the same capture, so a background
   sync never overwrites text the User is still correcting. A capture that was
   just Sent or Discarded is never resurrected by a read that started earlier."
  [system !model]
  (-> (ports/list-captures! (:store system))
      (.then
       (fn [captures]
         (let [captures (vec captures)
               dismissed (:dismissed-review-id @!model)
               captures (vec (remove #(and (= dismissed (:id %))
                                           (capture/unsent? %))
                                     captures))]
           (swap! !model
                  (fn [model]
                    (let [current (:review-id model)
                          candidate (when current
                                      (first (filter #(= current (:id %)) captures)))
                          review (if (and candidate (capture/unsent? candidate))
                                   candidate
                                   (capture/unsent-capture captures))
                          same? (= current (:id review))]
                      (assoc model
                             :captures captures
                             :review-id (:id review)
                             :draft-text (if (and same? (string? (:draft-text model)))
                                           (:draft-text model)
                                           (if review (capture-text review) "")))))))
           captures))
      (.catch (fn [error]
                (swap! !model assoc :message (error-message error))
                nil))))

(defn- load-groups!
  "Cached quick picks keep the pills usable offline. Failure leaves whatever
   the last successful discovery showed, so a transient outage never invents a
   destination."
  [system !model]
  (-> (smriti-command/discover-groups! system)
      (.then
       (fn [result]
         (swap! !model assoc
                :groups (vec (:groups result))
                :quick-picks (vec (:quick-picks result))
                :groups-configured? (boolean (:quick-picks-configured? result))
                :groups-stale? (boolean (:stale? result))
                :groups-auth-required? (boolean (:auth-required? result))
                :groups-error nil)))
      (.catch (fn [error]
                (swap! !model assoc :groups-error (error-message error))))))

(defn- apply-delivery-status! [system !model {:keys [configured? webhook-url]}]
  (let [configured? (true? configured?)
        saved-host (not-empty (smriti-command/base-url webhook-url))]
    (swap! !model assoc
           :delivery-state (if configured? :configured :needs-setup)
           :server-url (or saved-host (default-server-url system))
           :setup-error nil
           :message (if (contains? #{:starting :recording :saving} (:phase @!model))
                      (:message @!model)
                      (if configured?
                        "Review the transcript, then Send."
                        "Connect Shruti to Smriti once.")))
    configured?))

(defn- sync-now!
  "Deliver every due capture under its original id and payload. One sync at a
   time; a periodic tick keeps automatic retries moving while the app is open."
  [system !model]
  (if-not (delivery-configured? !model)
    (js/Promise.resolve nil)
    (if (:syncing? @!model)
      (js/Promise.resolve nil)
      (do
        (swap! !model assoc :syncing? true)
        (-> (sync/sync-due! system)
            (.then
             (fn [captures]
               (when (some #(= :blocked-auth (capture/delivery-state %)) captures)
                 (swap! !model assoc
                        :delivery-state :needs-setup
                        :screen :home
                        :message "Smriti rejected the saved key. Reconnect, then retry pending messages."))
               ;; A Group rejection may mean membership changed; refresh the
               ;; cached picks without touching the pending message itself.
               (when (some capture/needs-attention? captures)
                 (load-groups! system !model))
               captures))
            (.catch (fn [_] nil))
            (.then (fn [_]
                     (swap! !model assoc :syncing? false)
                     (refresh-captures! system !model))))))))

(defn- load-delivery-status! [system !model]
  (-> (ports/delivery-status! (:delivery-configuration system))
      (.then
       (fn [status]
         (when (apply-delivery-status! system !model status)
           (load-groups! system !model)
           (sync-now! system !model))))
      (.catch
       (fn [error]
         (swap! !model assoc
                :delivery-state :needs-setup
                :setup-error (error-message error))))))

(defn- configure-delivery! [system !model]
  (let [{:keys [bearer-token]} @!model
        endpoint (smriti-command/endpoint-url (:server-url @!model))]
    (cond
      (not (seq endpoint))
      (swap! !model assoc :setup-error "Enter the Smriti server URL.")

      (not (seq (or bearer-token "")))
      (swap! !model assoc :setup-error "Enter the Smriti API key.")

      :else
      (do
        (swap! !model assoc :delivery-state :saving-setup :setup-error nil)
        (-> (ports/configure-delivery! (:delivery-configuration system)
                                       {:webhook-url endpoint
                                        :bearer-token bearer-token})
            (.then
             (fn [status]
               ;; Keep this only for the submit call; it is never written to WebView storage.
               (swap! !model dissoc :bearer-token)
               (when (apply-delivery-status! system !model status)
                 (swap! !model assoc :screen :home)
                 (load-groups! system !model)
                 (sync-now! system !model))))
            (.catch
             (fn [error]
               (swap! !model assoc
                      :delivery-state :needs-setup
                      :setup-error (error-message error)))))))))

(defn- open-delivery-setup! [!model]
  (swap! !model assoc
         :screen :setup
         :bearer-token ""
         :setup-error nil))

(defn- open-home! [!model]
  (swap! !model #(-> %
                     (assoc :screen :home :setup-error nil)
                     (dissoc :bearer-token))))

(defn- discard-after-start-error! [store request]
  (-> (ports/put-capture! store (capture/discard request))
      (.catch (fn [_] nil))))

(defn start-recording! [system !model]
  (let [{:keys [phase captures sending?]} @!model]
    (cond
      (or sending? (contains? #{:starting :recording :saving} phase))
      (swap! !model assoc :message "Finish the current action first.")

      (not (capture/can-start-recording? captures))
      (swap! !model assoc
             :message "Send or discard the saved recording before recording another.")

      :else
      (let [request (intake/create-recording (recording-input system))]
        (swap! !model assoc :phase :starting :message "Starting recording" :request request)
        (-> (ports/put-capture! (:store system) request)
            (.then (fn [_] (ports/start! (:recorder system) {:capture-id (:id request)})))
            (.then (fn [_]
                     (vibrate!)
                     (swap! !model assoc :phase :recording :message "Recording")))
            (.catch (fn [error]
                      (discard-after-start-error! (:store system) request)
                      (swap! !model assoc :phase :error :message (error-message error)))))))))

(defn- listen-for-record-shortcut! [system !model]
  (when-let [plugin (some-> js/window
                            (gobj/get "Capacitor")
                            (gobj/get "Plugins")
                            (gobj/get "ShrutiShortcut"))]
    (.call (gobj/get plugin "addListener") plugin "record"
           (fn [_]
             (swap! !model assoc :screen :home)
             (when-not (contains? #{:starting :recording :saving :sending} (:phase @!model))
               (start-recording! system !model))))))

(defn- seal-and-store! [store request {:keys [audio transcript]}]
  (let [asset-id (:asset-id audio)
        blob (:blob audio)
        sealed (capture/finalize-recording request {:audio (some-> audio (dissoc :blob))
                                                    :transcript transcript})]
    (-> (if blob
          (ports/put-media! store asset-id blob)
          (js/Promise.resolve nil))
        (.then (fn [_] (ports/put-capture! store sealed)))
        (.then (fn [_] sealed)))))

(defn- scroll-to-review! []
  (js/setTimeout
   (fn []
     (when-let [element (.getElementById js/document "shruti-review-text")]
       (.scrollIntoView element #js {:behavior "smooth" :block "center"})))
   60))

(declare ^:private send-review!)

(defn stop-recording! [system !model]
  (if-let [request (:request @!model)]
    (do
      (swap! !model assoc :phase :saving :message "Saving the transcript locally")
      (-> (ports/stop! (:recorder system))
          (.then (fn [audio] (seal-and-store! (:store system) request audio)))
          (.then
           (fn [sealed]
             (vibrate!)
             (swap! !model assoc
                    :phase :ready
                    :message "Review the transcript, then Send."
                    :request nil
                    :review-id (:id sealed)
                    :dismissed-review-id nil
                    :draft-text (capture-text sealed))
             (scroll-to-review!)
             (refresh-captures! system !model)
             ;; The countdown starts only after the review is saved locally
             ;; and displayed, so the recording stays recoverable. A blank
             ;; transcript never enters the countdown.
             (when (auto-send/sendable-transcript? (capture-text sealed))
               (auto-send/start! !model
                                 (fn [review-id]
                                   (when (= review-id (:review-id @!model))
                                     (send-review! system !model)))))
             sealed))
          (.catch (fn [error]
                    (swap! !model assoc :phase :error :message (error-message error))))))
    (swap! !model assoc :phase :error :message "There is no active recording.")))

(defn- activate! [system !model]
  (case (:phase @!model)
    :recording (stop-recording! system !model)
    :starting nil
    :saving nil
    (start-recording! system !model)))

(defn- persist-review-text! [system !model]
  (when-let [review (find-capture @!model (:review-id @!model))]
    (let [edited (try
                   (capture/edit-transcript review (:draft-text @!model))
                   (catch :default _ nil))]
      (when edited
        (-> (ports/put-capture! (:store system) edited)
            (.catch (fn [_] nil)))))))

(defn- edit-review-text! [system !model text]
  ;; Touching the transcript cancels the countdown before editing.
  (auto-send/cancel! !model)
  (swap! !model assoc :draft-text text)
  (when-let [timer (:draft-timer @!model)]
    (js/clearTimeout timer))
  (swap! !model assoc :draft-timer
         (js/setTimeout (fn [] (persist-review-text! system !model)) 300)))

(defn- send-review! [system !model]
  (let [{:keys [review-id draft-text destination-group-id sending?]} @!model
        review (find-capture @!model review-id)]
    (cond
      sending? nil

      (nil? review)
      (swap! !model assoc :message "There is no saved recording to send.")

      (str/blank? (str draft-text))
      (swap! !model assoc :message "The transcript is empty. Discard it or write something to send.")

      :else
      (let [queued (try
                     (capture/submit review draft-text destination-group-id (sync/now-ms))
                     (catch :default _ nil))]
        (if (nil? queued)
          (swap! !model assoc :message "This recording cannot be sent as it stands.")
          (do
            (auto-send/cancel! !model)
            (clear-draft-timer! !model)
            (swap! !model assoc
                   :sending? true
                   :message "Saving before delivery…"
                   :dismissed-review-id (:id review))
            (-> (ports/put-capture! (:store system) queued)
                (.then
                 (fn [_]
                   ;; The destination and text are now snapshotted. Later pill
                   ;; changes only affect the next capture.
                   (swap! !model assoc
                          :sending? false
                          :review-id nil
                          :draft-text ""
                          :phase :ready
                          :message "Queued for delivery.")
                   (refresh-captures! system !model)
                   (sync-now! system !model)))
                (.catch (fn [error]
                          (swap! !model assoc
                                 :sending? false
                                 :dismissed-review-id nil
                                 :message (error-message error))
                          (refresh-captures! system !model))))))))))

(defn- drop-media! [store request]
  (when-let [asset-id (get-in request [:content :audio :asset-id])]
    (-> (ports/delete-media! store asset-id)
        (.catch (fn [_] nil)))))

(defn- discard-review! [system !model]
  (let [review (find-capture @!model (:review-id @!model))]
    (if review
      (do
        (auto-send/cancel! !model)
        (clear-draft-timer! !model)
        (swap! !model assoc
               :review-id nil
               :draft-text ""
               :dismissed-review-id (:id review)
               :message "Discarding…")
        (-> (ports/put-capture! (:store system) (capture/discard review))
            (.then
             (fn [_]
               (drop-media! (:store system) review)
               (swap! !model assoc
                      :phase :ready
                      :message "Recording discarded.")
               (refresh-captures! system !model)))
            (.catch (fn [error]
                      (swap! !model assoc
                             :dismissed-review-id nil
                             :message (error-message error))
                      (refresh-captures! system !model)))))
      (swap! !model assoc :review-id nil :draft-text ""))))

(defn- retry-pending! [system !model request group-id !busy]
  (reset! !busy true)
  (let [request (or (find-capture @!model (:id request)) request)
        current (capture/destination-group-id request)
        queued (try
                 (if (= group-id current)
                   (capture/retry request (sync/now-ms))
                   (capture/reassign request group-id (sync/now-ms)))
                 (catch :default error ::error))]
    (if (= ::error queued)
      (do
        (reset! !busy false)
        (swap! !model assoc :message "That message cannot be retried."))
      (-> (ports/put-capture! (:store system) queued)
          (.then (fn [_] (refresh-captures! system !model)))
          (.then (fn [_] (sync-now! system !model)))
          (.catch (fn [error] (swap! !model assoc :message (error-message error))))
          (.then (fn [_] (reset! !busy false)))))))

(defn- send-as-new-entry! [system !model request !busy]
  (reset! !busy true)
  (let [request (or (find-capture @!model (:id request)) request)
        receipt (get-in request [:local :delivery :last-error])
        fresh (try
                (capture/new-entry request (intake/now-iso) (sync/now-ms))
                (catch :default _ nil))
        original (try
                   (capture/acknowledge-conflict request receipt)
                   (catch :default _ nil))]
    (if (and fresh original)
      (-> (ports/put-capture! (:store system) fresh)
          (.then (fn [_]
                   ;; The original keeps its accepted status, so the Journal
                   ;; tells the truth: it was sent once.
                   (ports/put-capture! (:store system) original)))
          (.then (fn [saved]
                   (drop-media! (:store system) request)
                   saved))
          (.then (fn [_] (refresh-captures! system !model)))
          (.then (fn [_] (sync-now! system !model)))
          (.catch (fn [error] (swap! !model assoc :message (error-message error))))
          (.then (fn [_] (reset! !busy false))))
      (do
        (reset! !busy false)
        (swap! !model assoc :message "The new entry could not be prepared.")))))

;; ---------------------------------------------------------------------------
;; UI pieces

(defn- state-label [state]
  (case state
    :queued "Queued"
    :leased "Sending"
    :accepted "Sent"
    :blocked-auth "Needs connection"
    :rejected "Needs attention"
    :conflict "Original already sent"
    "Pending"))

(defn- status-note [request]
  (let [state (capture/delivery-state request)
        error (get-in request [:local :delivery :last-error])
        server-message (some (fn [value]
                              (when (or (string? value) (number? value))
                                (str value)))
                            (concat [(when (string? error) error)]
                                    [(:message error) (:error error) (:raw error)]))]
    (case state
      :blocked-auth "Smriti rejected the saved key. Reconnect, then retry. Nothing was sent privately."
      :rejected (or server-message "Smriti did not accept this message. Its text and destination are unchanged.")
      :conflict "The original was already sent. Change nothing, or send this text as a new entry."
      nil)))

(defn- destination-pill [label selected? on-click variant]
  [:button.dest-pill
   {:type "button"
    :class (str/join " " (cond-> [(name variant)]
                           selected? (conj "selected")))
    :aria-pressed (str (boolean selected?))
    :on-click on-click}
   label])

(defn- destination-choices [model selected-id on-select]
  [:div.pill-row {:role "group" :aria-label "Send to"}
   (let [picks (pill-group-ids model)
         picks (if (and selected-id (not (some #(= selected-id %) picks)))
                 (conj (vec picks) selected-id)
                 picks)]
     [destination-pill "Private" (nil? selected-id) #(on-select nil) :private]
     (for [group-id picks]
       ^{:key group-id}
       [destination-pill (group-name model group-id)
        (= group-id selected-id)
        #(on-select group-id)
        :group]))])

(defn- destination-options [model current-id]
  (let [groups (sort-by (juxt :name :id) (:groups model))]
    (cond-> groups
      (and current-id (not (some #(= current-id (:id %)) groups)))
      (into [{:id current-id :name (destination-label model current-id)}]))))

(defn- pending-destination-select [model current-id on-change]
  (let [options (destination-options model current-id)]
    [:select.pending-select
     {:value (or current-id "")
      :aria-label "Send to"
      :on-change #(on-change (not-empty (.. % -target -value)))}
     [:option {:value ""} "Private"]
     (for [group options]
       ^{:key (:id group)}
       [:option {:value (:id group)} (:name group)])]))


(defn- all-groups-panel [model selected-id on-select]
  (when (:show-all-groups? model)
    [:div.group-list
     (for [group (sort-by (juxt :name :id) (:groups model))]
       ^{:key (:id group)}
       [:button.group-option
        {:type "button"
         :class (when (= (:id group) selected-id) "selected")
         :aria-pressed (str (= (:id group) selected-id))
         :on-click #(on-select (:id group))}
        (:name group)])]))

(defn- destination-block [!model]
  (let [{:keys [destination-group-id groups groups-stale? groups-error
                show-all-groups?]} @!model]
    [:section.destination-block {:aria-label "Destination"}
     [:p.destination-heading
      [:span.destination-label "Send to"]
      [:span.destination-current (destination-label @!model destination-group-id)]]
     [:div.destination-row
      [destination-choices @!model destination-group-id
       (fn [group-id]
         (if (nil? group-id)
           (select-destination! !model nil)
           (toggle-destination! !model group-id)))]
      (when (seq groups)
        [:button.dest-pill.all-groups
         {:type "button"
          :aria-expanded (str (boolean show-all-groups?))
          :on-click #(do (auto-send/cancel! !model)
                         (swap! !model update :show-all-groups? not))}
         (if show-all-groups? "Fewer Groups" "All Groups")])]
     (all-groups-panel @!model destination-group-id
                       (fn [group-id] (toggle-destination! !model group-id)))
     (when groups-stale?
       [:p.groups-note "Offline Group list. Delivery rechecks membership."])
     (when (:groups-auth-required? @!model)
       [:p.groups-note.error "Smriti rejected the saved key. Showing cached Groups."])
     (when (and destination-group-id
                (seq groups)
                (not (some #(= destination-group-id (:id %)) groups)))
       [:p.groups-note.error
        "This Group is no longer in your Groups. Smriti will reject a send to it; choose another."])
     (when groups-error
       [:p.groups-note.error groups-error])]))

(defn- review-card [system !model]
  (let [{:keys [draft-text sending? auto-send]} @!model
        sendable? (and (not sending?) (not (str/blank? (str draft-text))))
        counting? (auto-send/countdown? @!model)]
    ;; Touching anywhere in the review, including the transcript and Discard,
    ;; cancels the countdown. The Send button's own activation still sends.
    [:section.review-card
     {:on-pointer-down #(auto-send/cancel! !model)
      :style {:--auto-send-duration-ms (str auto-send/duration-ms "ms")}}
     [:div.review-heading
      [:h2.review-title "Review before sending"]
      [:span.review-badge "Not sent"]]
     [:label.review-label {:for "shruti-review-text"} "Transcript"]
     [:textarea#shruti-review-text.review-text
      {:rows 4
       :value (or draft-text "")
       :placeholder "What did you say?"
       :on-change #(edit-review-text! system !model (.. % -target -value))}]
     [:p.review-hint
      (if counting?
        "Sending automatically soon. Touch anything here to keep it unsent."
        "Correct anything the recognizer missed. Nothing is shared until you press Send.")]
     [:div.review-actions
      [:button.review-action.primary
       {:type "button"
        :class (when counting? "counting")
        :disabled (not sendable?)
        :aria-label "Send this message"
        :on-click #(send-review! system !model)}
       (when counting?
         [:span.send-fill {:aria-hidden true}])
       [:span.send-label "Send"]]
      [:button.review-action.secondary
       {:type "button"
        :disabled sending?
        :on-click #(discard-review! system !model)}
       "Discard"]]]))

(defn- pending-item [system !model request]
  (r/with-let [!choice (r/atom (capture/destination-group-id request))
               !busy (r/atom false)]
    (let [model @!model
          state (capture/delivery-state request)
          failed? (capture/retryable? request)
          conflict? (capture/conflicted? request)
          change? (not= @!choice (capture/destination-group-id request))]
      [:article.pending-item
       {:class (name state)}
       [:div.pending-head
        [:span.pending-state (state-label state)]
        [:span.pending-destination (destination-label model (capture/destination-group-id request))]]
       [:p.pending-text (capture-text request)]
       (when-let [note (status-note request)]
         [:p.pending-note note])
       (when failed?
         [:div.pending-reassign
          [:p.pending-reassign-label "Change the destination if this Group is no longer available:"]
          [pending-destination-select model @!choice #(reset! !choice %)]
          [:button.pending-action.primary
           {:type "button"
            :disabled @!busy
            :on-click #(retry-pending! system !model request @!choice !busy)}
           (if change? "Retry with this destination" "Retry")]])
       (when conflict?
         [:div.pending-actions
          [:button.pending-action.primary
           {:type "button"
            :disabled @!busy
            :on-click #(send-as-new-entry! system !model request !busy)}
           "Send as new entry"]])])))

(defn- pending-list [system !model]
  (let [model @!model
        pending (capture/pending-captures (:captures model))]
    (when (seq pending)
      [:section.pending-section
       [:div.pending-header
        [:h2.pending-title "Pending messages"]
        [:span.pending-count (str (count pending))]]
       [:div.pending-list
        (for [request pending]
          ^{:key (:id request)}
          [pending-item system !model request])]])))

(defn- sent-strip [!model]
  (let [model @!model
        sent (take 5 (capture/accepted-captures (:captures model)))]
    (when (seq sent)
      [:section.sent-strip {:aria-label "Sent messages"}
       [:h2.sent-title "Sent"]
       (for [request sent]
         ^{:key (:id request)}
         [:p.sent-item
          [:span.sent-destination (destination-label model (capture/destination-group-id request))]
          [:span.sent-text (capture-text request)]])])))

(defn- button-label [phase has-review?]
  (cond
    has-review? "Send or discard the saved recording first"
    (= :recording phase) "Stop recording"
    (= :starting phase) "Starting recording"
    (= :saving phase) "Saving recording"
    :else "Start recording"))

(defn capture-screen [system !model]
  (let [{:keys [phase message review-id sending?]} @!model
        recording? (= :recording phase)
        busy? (or sending? (contains? #{:starting :saving} phase))
        has-review? (boolean review-id)]
    [:section.capture-screen
     [:header.capture-header
      [:h1.capture-title "Shruti"]
      [:p.capture-subtitle "Capture a thought. Review it, choose who can see it, then send."]]
     [destination-block !model]
     (when has-review?
       [review-card system !model])
     [pending-list system !model]
     [sent-strip !model]
     [:div.capture-center
      [:p.capture-status {:class (when (= :error phase) "error")} message]
      (when has-review?
        [:p.capture-status.review-hint
         "One recording is waiting. Send or discard it before recording another."])
      [:button.record-button
       {:class (str/join " " (cond-> []
                               recording? (conj "recording")
                               has-review? (conj "blocked")))
        :type "button"
        :disabled (or busy? has-review?)
        :aria-label (button-label phase has-review?)
        :on-click #(activate! system !model)}
       [:span.record-glyph {:aria-hidden true}]]]
     [:footer.capture-footer
      [:p "Messages are saved locally before delivery. Sent means Smriti accepted them."]
      [:button.connection-button
       {:type "button"
        :on-click #(open-delivery-setup! !model)}
       "Connection"]]]))

(defn- setup-screen [system !model]
  (let [{:keys [delivery-state server-url bearer-token setup-error]} @!model
        saving? (= :saving-setup delivery-state)
        android? (= :android (get-in system [:config :platform]))]
    [:section.setup-screen
     [:header.capture-header
      [:h1.capture-title "Connect Shruti"]
      [:p.capture-subtitle
       (if android?
         "Save a Smriti connection once. The API key stays in Android Keystore."
         "Finish this one-time connection step in the Android app.")]]
     [:form.setup-form
      {:on-submit (fn [event]
                    (.preventDefault event)
                    (configure-delivery! system !model))}
      [:div.setup-field
       [:label.setup-label-text {:for "shruti-server-url"} "Command URL"]
       [:input.setup-input
        {:id "shruti-server-url"
         :type "url"
         :input-mode "url"
         :autocomplete "url"
         :disabled (not android?)
         :placeholder "https://smriti.example.com"
         :value (or server-url "")
         :on-change #(swap! !model assoc :server-url (.. % -target -value))}]
       [:p.setup-url-hint
        [:span "Posts to"]
        [:code.setup-url-path smriti-command/command-path]]]
      [:label.setup-label
       "Smriti API key"
       [:input.setup-input
        {:type "password"
         :autocomplete "off"
         :disabled (or saving? (not android?))
         :value (or bearer-token "")
         :on-change #(swap! !model assoc :bearer-token (.. % -target -value))}]]
      (when setup-error
        [:p.setup-error {:role "alert"} setup-error])
      (if android?
        [:button.setup-submit {:type "submit" :disabled saving?}
         (if saving? "Saving securely…" "Save connection")]
        [:p.capture-status "Browser mode is a local development fallback."])]
     [:footer.capture-footer
      [:p "Shruti writes final transcripts through Smriti's JSON command API."]
      [:button.connection-button
       {:type "button"
        :disabled saving?
        :on-click #(open-home! !model)}
       "Back to home"]]]))

(defn- checking-screen []
  [:section.setup-screen
   [:div.capture-center
    [:p.capture-status "Checking secure delivery…"]]])

(defn- watch-visibility!
  "Coming back to the app refreshes cached Groups and resumes due delivery.
   Leaving the app cancels auto-send: a hidden review never submits."
  [system !model]
  (let [handler (fn [_]
                  (if (= "visible" (.-visibilityState js/document))
                    (when (= :configured (:delivery-state @!model))
                      (load-groups! system !model)
                      (sync-now! system !model))
                    (auto-send/cancel! !model)))]
    (.addEventListener js/document "visibilitychange" handler)
    handler))

(defn root []
  (r/with-let [system (composition/make-system)
               !model (r/atom {:phase :ready
                               :message "Checking secure delivery…"
                               :delivery-state :checking
                               :server-url (default-server-url system)
                               :request nil
                               :captures []
                               :review-id nil
                               :draft-text ""
                               :destination-group-id nil
                               :groups []
                               :quick-picks []
                               :groups-configured? false
                               :groups-stale? false
                               :groups-auth-required? false
                               :groups-error nil
                               :show-all-groups? false
                               :sending? false
                               :syncing? false})
               _ (refresh-captures! system !model)
               _ (load-delivery-status! system !model)
               shortcut-listener (listen-for-record-shortcut! system !model)
               visibility-handler (watch-visibility! system !model)
               delivery-timer (js/setInterval
                               (fn []
                                 (when (and (= :configured (:delivery-state @!model))
                                            (not (:syncing? @!model)))
                                   (sync-now! system !model)))
                               delivery-poll-ms)]
    (cond
      (= :checking (:delivery-state @!model)) [checking-screen]
      (= :setup (:screen @!model)) [setup-screen system !model]
      (or (= :home (:screen @!model))
          (= :configured (:delivery-state @!model))) [capture-screen system !model]
      :else [setup-screen system !model])
    (finally
      (js/clearInterval delivery-timer)
      (.removeEventListener js/document "visibilitychange" visibility-handler)
      (auto-send/cancel! !model)
      (when-let [timer (:draft-timer @!model)]
        (js/clearTimeout timer)))))
