(ns shruti.composition
  (:require [goog.object :as gobj]
            [shruti.infra.auth :as auth]
            [shruti.infra.android-speech :as android-speech]
            [shruti.infra.browser-recorder :as recorder]
            [shruti.infra.browser-store :as store]
            [shruti.infra.smriti-command :as smriti-command]))

(defn- platform []
  (let [capacitor (gobj/get js/window "Capacitor")
        is-native-platform (some-> capacitor (gobj/get "isNativePlatform"))
        native? (and is-native-platform (.call is-native-platform capacitor))
        get-platform (some-> capacitor (gobj/get "getPlatform"))]
    (keyword (if native?
               (.call get-platform capacitor)
               "web"))))

(defn runtime-config []
  (let [config (or (.-SHRUTI_CONFIG js/window) #js {})]
    {:default-webhook-url (or (.-webhookUrl config) "")
     :locale (or (.-locale config) "und")
     :client (or (.-client config) "shruti")
     :platform (platform)}))

(defn- voice-capture [{:keys [platform]}]
  (if (and (= :android platform) (android-speech/available?))
    (android-speech/create)
    (recorder/create)))

(defn make-system []
  (let [{:keys [default-webhook-url] :as config} (runtime-config)
        delivery (smriti-command/create default-webhook-url)]
    {:config config
     :store (store/create)
     :recorder (voice-capture config)
     :transport delivery
     :delivery-configuration delivery
     :auth (auth/no-auth-provider)}))
