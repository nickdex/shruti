(ns shruti.infra.command-transport
  "Generic JSON command transport.

   The native bridge owns credentials and HTTP; this adapter only turns a
   CaptureRequest into an arbitrary JSON object through the supplied
   `payload-fn` and classifies the response. It knows no backend field
   names, so another command endpoint needs a new mapping, not a new
   transport."
  (:require [goog.object :as gobj]
            [clojure.string :as str]
            [shruti.application.ports :as ports]))

(defn parse-json [text]
  (when-not (str/blank? text)
    (try
      (js->clj (js/JSON.parse text) :keywordize-keys true)
      (catch :default _ {:raw text}))))

(defn- header-value [headers name]
  (cond
    (map? headers) (or (get headers name)
                       (get headers (str/lower-case name)))
    (nil? headers) nil
    (some? (some-> headers .-get)) (.call (.-get headers) headers name)
    :else (or (gobj/get headers name)
              (gobj/get headers (str/lower-case name)))))

(defn retry-after-ms [headers]
  (when-let [value (header-value headers "Retry-After")]
    (let [seconds (js/Number value)]
      (when-not (js/isNaN seconds)
        (* 1000 seconds)))))

(defn response-result
  "A 409 conflict means Smriti refused the command; it is not a duplicate
   acknowledgement, so it stays rejected instead of being retried."
  [status headers body]
  (let [receipt (or body {:status status})]
    (cond
      (<= 200 status 299) {:kind :accepted :receipt receipt}
      (or (= 401 status) (= 403 status)) {:kind :auth-required :error receipt}
      (or (= 408 status) (= 425 status) (= 429 status) (>= status 500))
      {:kind :retryable :retry-after-ms (retry-after-ms headers) :error receipt}
      :else {:kind :rejected :error receipt})))

(defprotocol JsonDelivery
  "POSTs one arbitrary JSON object to the configured endpoint."
  (send-json! [transport payload capture-id request-auth]))

(defn- delivery-headers [capture-id request-auth]
  (merge {"Accept" "application/json"
          "Content-Type" "application/json"
          "Idempotency-Key" capture-id
          "X-Capture-Id" capture-id}
         (or (:headers request-auth) {})))

(defn browser-request
  "The browser fallback's request options. The body is JSON, never
   multipart, and redirects are refused so a bearer token cannot be
   forwarded to another location."
  [payload capture-id request-auth]
  #js {:method "POST"
       :headers (clj->js (delivery-headers capture-id request-auth))
       :body (js/JSON.stringify (clj->js payload))
       :redirect "error"})

(defn- browser-deliver! [webhook-url payload capture-id request-auth]
  (if (str/blank? webhook-url)
    (js/Promise.resolve
     {:kind :configuration-error
      :error {:message "The command endpoint is not configured"}})
    (-> (js/fetch webhook-url (browser-request payload capture-id request-auth))
        (.then (fn [response]
                 (-> (.text response)
                     (.then (fn [text]
                              (response-result (.-status response)
                                               (.-headers response)
                                               (parse-json text)))))))
        (.catch (fn [error]
                  {:kind :retryable
                   :error {:message (or (.-message error) "Network error")}})))))

(defn- native-plugin []
  (some-> (when (exists? js/window) (gobj/get js/window "Capacitor"))
          (gobj/get "Plugins")
          (gobj/get "ShrutiCommandTransport")))

(defn- native-call
  ([plugin method]
   (.call (gobj/get plugin method) plugin))
  ([plugin method options]
   (.call (gobj/get plugin method) plugin options)))

(defn- native-response-body [response]
  (let [body (or (gobj/get response "body")
                 (gobj/get response "responseBody"))]
    (cond
      (string? body) (parse-json body)
      (nil? body) nil
      :else (js->clj body :keywordize-keys true))))

(defn- native-retry-after [response]
  (or (gobj/get response "retryAfter")
      (header-value (gobj/get response "headers") "Retry-After")))

(defn- configuration-error-code? [code]
  (and (string? code)
       (or (str/includes? code "NOT_CONFIGURED")
           (str/includes? code "CONFIGURATION"))))

(defn- native-error-result [error]
  (let [code (gobj/get error "code")
        message (or (gobj/get error "message") "Native delivery failed")]
    (if (configuration-error-code? code)
      {:kind :configuration-error :error {:message message}}
      {:kind :retryable :error {:message message :code code}})))

(defn- configuration-status [result]
  {:configured? (true? (gobj/get result "configured"))
   :webhook-url (or (gobj/get result "webhookUrl") "")})

(defn- deliver-capture! [transport payload-fn request request-auth]
  (if-let [payload (payload-fn request)]
    (send-json! transport payload (:id request) request-auth)
    (js/Promise.resolve
     {:kind :rejected
      :error {:message "The capture produced no command payload"}})))

(defrecord NativeCommandTransport [native-plugin payload-fn]
  JsonDelivery
  (send-json! [_ payload capture-id _request-auth]
    (-> (native-call native-plugin "deliver"
                     (clj->js {"payload" (clj->js payload)
                               "captureId" capture-id}))
        (.then (fn [response]
                 (response-result (gobj/get response "status")
                                  {"Retry-After" (native-retry-after response)}
                                  (native-response-body response))))
        (.catch native-error-result)))
  ports/CaptureTransport
  (deliver! [this request _media request-auth]
    (deliver-capture! this payload-fn request request-auth))
  ports/DeliveryConfiguration
  (delivery-status! [_]
    (-> (native-call native-plugin "status")
        (.then configuration-status)))
  (configure-delivery! [_ {:keys [webhook-url bearer-token]}]
    (-> (native-call native-plugin "configure"
                     (clj->js {"webhookUrl" webhook-url
                               "bearerToken" bearer-token}))
        (.then configuration-status)))
  (clear-delivery! [_]
    (native-call native-plugin "clearConfiguration")))

(defrecord BrowserCommandTransport [webhook-url payload-fn]
  JsonDelivery
  (send-json! [_ payload capture-id request-auth]
    (browser-deliver! webhook-url payload capture-id request-auth))
  ports/CaptureTransport
  (deliver! [this request _media request-auth]
    (deliver-capture! this payload-fn request request-auth))
  ports/DeliveryConfiguration
  (delivery-status! [_]
    (js/Promise.resolve {:configured? false
                         :webhook-url webhook-url
                         :reason "Secure command setup is available in the Android app."}))
  (configure-delivery! [_ _options]
    (js/Promise.reject
     (js/Error. "Secure command setup is available in the Android app.")))
  (clear-delivery! [_]
    (js/Promise.resolve true)))

(defn create
  "Builds a transport that posts `payload-fn`'s JSON object for each
   capture. `payload-fn` returns nil when the capture has nothing the
   backend can accept."
  [webhook-url payload-fn]
  (if-let [plugin (native-plugin)]
    (->NativeCommandTransport plugin payload-fn)
    (->BrowserCommandTransport webhook-url payload-fn)))
