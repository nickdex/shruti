(ns shruti.ui.root
  (:require [goog.object :as gobj]
            [reagent.core :as r]
            [shruti.application.intake :as intake]
            [shruti.application.ports :as ports]
            [shruti.application.sync :as sync]
            [shruti.composition :as composition]
            [shruti.domain.capture :as capture]
            [shruti.infra.smriti-command :as smriti-command]))

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
  (or (.-message error) "Recording could not start. Check microphone permission and try again."))

(defn- default-server-url [system]
  (get-in system [:config :server-url]))

(defn- delivery-configured? [!model]
  (= :configured (:delivery-state @!model)))

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
                        "Tap once to speak."
                        "Connect Shruti to Smriti once.")))
    configured?))

(defn- sync-after-configuration! [system !model]
  (-> (sync/sync-due! system)
      (.then
       (fn [captures]
         (when (some #(= :blocked-auth (capture/delivery-state %)) captures)
           (swap! !model assoc
                  :delivery-state :needs-setup
                  :screen :setup
                  :setup-error "Smriti rejected the saved key. Enter the current key again."))))
      (.catch (fn [_] nil))))

(defn- load-delivery-status! [system !model]
  (-> (ports/delivery-status! (:delivery-configuration system))
      (.then
       (fn [status]
         (when (apply-delivery-status! system !model status)
           (sync-after-configuration! system !model))))
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
                 (sync-after-configuration! system !model))))
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
  (let [request (intake/create-recording (recording-input system))]
    (swap! !model assoc :phase :starting :message "Starting recording" :request request)
    (-> (ports/put-capture! (:store system) request)
        (.then (fn [_] (ports/start! (:recorder system) {:capture-id (:id request)})))
        (.then (fn [_]
                 (vibrate!)
                 (swap! !model assoc :phase :recording :message "Recording")))
        (.catch (fn [error]
                  (discard-after-start-error! (:store system) request)
                  (swap! !model assoc :phase :error :message (error-message error)))))))

(defn- listen-for-record-shortcut! [system !model]
  (when-let [plugin (some-> js/window
                            (gobj/get "Capacitor")
                            (gobj/get "Plugins")
                            (gobj/get "ShrutiShortcut"))]
    (.call (gobj/get plugin "addListener") plugin "record"
           (fn [_]
             (swap! !model assoc :screen :home)
             (when-not (contains? #{:starting :recording :saving} (:phase @!model))
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

(defn stop-recording! [system !model]
  (if-let [request (:request @!model)]
    (do
      (swap! !model assoc :phase :saving :message "Saving locally")
      (-> (ports/stop! (:recorder system))
          (.then (fn [audio] (seal-and-store! (:store system) request audio)))
          (.then (fn [sealed]
                   (vibrate!)
                   (let [configured? (delivery-configured? !model)]
                     (swap! !model assoc :phase :ready
                            :message (if configured?
                                       "Saved. Delivery will continue automatically."
                                       "Saved locally. Configure a backend to deliver.")
                            :transcript (get-in sealed [:content :transcript])
                            :request nil)
                     (when configured?
                       (sync-after-configuration! system !model)))
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

(defn- button-label [phase]
  (case phase
    :recording "Stop recording"
    :starting "Starting recording"
    :saving "Saving recording"
    "Start recording"))

(defn capture-screen [system !model]
  (let [{:keys [phase message]} @!model
        recording? (= :recording phase)
        busy? (contains? #{:starting :saving} phase)]
    [:section.capture-screen
     [:header.capture-header
      [:h1.capture-title "Shruti"]
      [:p.capture-subtitle "Capture a thought. The backend decides what it means."]]
     [:div.capture-center
      [:button.record-button
       {:class (when recording? "recording")
        :type "button"
        :disabled busy?
        :aria-label (button-label phase)
        :on-click #(activate! system !model)}
       [:span.record-glyph {:aria-hidden true}]]
     [:p.capture-status {:class (when (= :error phase) "error")} message]]
     (when-let [transcript (:transcript @!model)]
       [:p.capture-transcript transcript])
     [:footer.capture-footer
      [:p "Captured data is written locally before delivery."]
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

(defn root []
  (r/with-let [system (composition/make-system)
               !model (r/atom {:phase :ready
                                :message "Checking secure delivery…"
                                :delivery-state :checking
                                :server-url (default-server-url system)
                                :request nil})
               startup-status (load-delivery-status! system !model)
               shortcut-listener (listen-for-record-shortcut! system !model)]
    (cond
      (= :checking (:delivery-state @!model)) [checking-screen]
      (= :setup (:screen @!model)) [setup-screen system !model]
      (or (= :home (:screen @!model))
          (= :configured (:delivery-state @!model))) [capture-screen system !model]
      :else [setup-screen system !model])))
