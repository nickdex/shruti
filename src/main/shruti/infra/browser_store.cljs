(ns shruti.infra.browser-store
  (:require [shruti.application.ports :as ports]))

(def ^:private database-name "shruti-browser-dev")
(def ^:private database-version 1)

(defn- request->promise [request]
  (js/Promise.
   (fn [resolve reject]
     (set! (.-onsuccess request) (fn [event] (resolve (.. event -target -result))))
     (set! (.-onerror request) (fn [event] (reject (.. event -target -error)))))))

(defn- transaction->promise [transaction]
  (js/Promise.
   (fn [resolve reject]
     (set! (.-oncomplete transaction) (fn [_] (resolve true)))
     (set! (.-onabort transaction) (fn [event] (reject (.. event -target -error))))
     (set! (.-onerror transaction) (fn [event] (reject (.. event -target -error)))))))

(defn- open-db! []
  (if-not (exists? js/indexedDB)
    (js/Promise.reject (js/Error. "IndexedDB is unavailable"))
    (js/Promise.
     (fn [resolve reject]
       (let [request (.open js/indexedDB database-name database-version)]
         (set! (.-onupgradeneeded request)
               (fn [event]
                 (let [database (.. event -target -result)
                       names (.-objectStoreNames database)]
                   (when-not (.contains names "captures")
                     (.createObjectStore database "captures" #js {:keyPath "id"}))
                   (when-not (.contains names "media")
                     (.createObjectStore database "media" #js {:keyPath "id"})))))
         (set! (.-onsuccess request) (fn [event] (resolve (.. event -target -result))))
         (set! (.-onerror request) (fn [event] (reject (.. event -target -error)))))))))

(defn- write! [store-name operation]
  (-> (open-db!)
      (.then
       (fn [database]
         (let [transaction (.transaction database (clj->js [store-name]) "readwrite")
               store (.objectStore transaction store-name)]
           (-> (operation store)
               (.then (fn [value]
                        (-> (transaction->promise transaction)
                            (.then (fn [_] value)))))))))))

(defn- read! [store-name operation]
  (-> (open-db!)
      (.then
       (fn [database]
         (let [transaction (.transaction database (clj->js [store-name]) "readonly")
               store (.objectStore transaction store-name)]
           (operation store))))))

(defn- encode-request [request]
  (-> request
      (update-in [:source :kind] name)
      (update-in [:source :entrypoint] name)
      (update-in [:source :platform] name)
      (update-in [:content :type] name)
      (update-in [:local :content-state] name)
      (update-in [:local :delivery :state] name)
      clj->js))

(defn- decode-request [value]
  (when value
    (let [request (js->clj value :keywordize-keys true)]
      (-> request
          (update-in [:source :kind] keyword)
          (update-in [:source :entrypoint] keyword)
          (update-in [:source :platform] keyword)
          (update-in [:content :type] keyword)
          (update-in [:local :content-state] keyword)
          (update-in [:local :delivery :state] keyword)))))

(defrecord IndexedDbStore []
  ports/CaptureStore
  (put-capture! [_ request]
    (write! "captures" (fn [store] (request->promise (.put store (encode-request request))))))
  (get-capture! [_ capture-id]
    (-> (read! "captures" (fn [store] (request->promise (.get store capture-id))))
        (.then decode-request)))
  (list-captures! [_]
    (-> (read! "captures" (fn [store] (request->promise (.getAll store))))
        (.then (fn [values] (mapv decode-request (array-seq values))))))
  (put-media! [_ asset-id blob]
    (write! "media" (fn [store]
                      (request->promise (.put store #js {:id asset-id :blob blob})))))
  (get-media! [_ asset-id]
    (-> (read! "media" (fn [store] (request->promise (.get store asset-id))))
        (.then (fn [value] (when value (.-blob value))))))
  (delete-media! [_ asset-id]
    (write! "media" (fn [store] (request->promise (.delete store asset-id))))))

(defn create [] (->IndexedDbStore))

