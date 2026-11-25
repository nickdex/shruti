(ns shruti.infra.smriti-command
  "Smriti's authenticated HTTP wire mapping.

   A capture becomes a scripted journal command, and the same configured
   connection reads the User's Groups and ordered quick picks for the
   destination pills. The mapping lives apart from the transport that
   sends it, so the transport stays usable for any JSON command
   endpoint. Only non-secret Group ids and names leave the native bridge;
   the bearer key never reaches JavaScript."
  (:require [clojure.string :as str]
            [shruti.infra.command-transport :as transport]))

(def command-path
  "Smriti's command API path. It never changes, so the setup screen shows it as
   a label and the app appends it to whatever server host the user enters."
  "/api/command")

(def groups-path
  "Smriti's authenticated Group discovery path. It lists every Group the key's
   User currently belongs to plus that User's ordered quick picks."
  "/api/groups")

(defn- without-trailing-slashes [url]
  (str/replace url #"/+$" ""))

(defn base-url
  "The server origin behind a stored endpoint URL, so the setup screen can show
   and edit the host without duplicating the fixed command path."
  [url]
  (let [url (without-trailing-slashes (str/trim (or url "")))]
    (if (str/ends-with? url command-path)
      (subs url 0 (- (count url) (count command-path)))
      url)))

(defn endpoint-url
  "The full POST target: the entered server host plus the fixed command path.
   Accepts an origin, a trailing-slash variant, or an already complete command
   URL, so pasting a saved endpoint keeps working."
  [url]
  (let [base (base-url url)]
    (cond
      (str/blank? base) ""
      (str/ends-with? base command-path) base
      :else (str base command-path))))

(defn groups-endpoint-url
  "The full GET target for Group discovery on the same server origin. Blank
   when no server is configured, so the caller can refuse before asking the
   native bridge for a credential."
  [url]
  (let [base (base-url url)]
    (if (str/blank? base)
      ""
      (str base groups-path))))

(defn- submitted-text [request]
  (some-> (get-in request [:local :submitted :text])
          str
          str/trim
          not-empty))

(defn- final-text [request]
  (or (submitted-text request)
      (some-> (or (get-in request [:content :transcript])
                  (get-in request [:content :text]))
              str
              str/trim
              not-empty)))

(defn- submitted-group-id [request]
  (let [submitted (get-in request [:local :submitted])]
    (if (contains? submitted :group-id)
      (:group-id submitted)
      (get-in request [:destination :group-id]))))

(defn capture->command
  "The JSON body Smriti's /api/command accepts for a text-bearing capture.

   `client-request-id` is the capture id: Smriti deduplicates one User's
   submissions by it, so an automatic retry of this same body cannot create
   a second Entry. `group-id` is the snapshotted destination, or \"\" for
   Private. Both come from the submitted snapshot, which stays frozen
   across retries; a corrected failed message updates only its snapshot
   destination.

   `created-at` is the original occurred-at instant: Smriti parses an
   offset date-time as-is, so a note spoken near midnight keeps the day it
   happened rather than the day it was delivered. Returns nil when there is
   no final text, because the command creates an entry from text only."
  [request]
  (when-let [text (final-text request)]
    (cond-> {"command/name" "journal/create-entry"
             "text" text
             "group-id" (or (some-> (submitted-group-id request) str str/trim not-empty) "")
             "client-request-id" (str (:id request))
             "tags" ["shruti" "todo"]}
      (not (str/blank? (str (:occurred-at request))))
      (assoc "created-at" (:occurred-at request)))))

(defn- group-entry [group]
  (when-let [id (some-> (:id group) str not-empty)]
    {:id id
     :name (or (some-> (:name group) str) "")}))

(defn groups-payload->discovery
  "Smriti's Group payload as the destination UI consumes it.

   Only stable ids and display names are kept; quick picks stay in the
   server's order and are limited to Groups present in this same payload,
   so a stale cache can never point at a Group it does not list. An empty
   pick list is a real answer, never padded locally: which Groups are
   pinned is Smriti's policy, not the plugin's."
  [payload {:keys [cached? auth-required?]}]
  (let [groups (vec (keep group-entry (:groups payload)))
        ids (into #{} (map :id) groups)
        picks (->> (:quickPicks payload)
                   (keep (fn [pick] (some-> (:id pick) str not-empty)))
                   (filter #(contains? ids %))
                   (take 3)
                   vec)]
    {:configured? true
     :groups groups
     :quick-picks picks
     :quick-picks-configured? (true? (:quickPicksConfigured payload))
     :stale? (boolean cached?)
     :auth-required? (boolean auth-required?)}))

(defn discover-groups!
  "Fetch the User's current Groups and ordered quick picks through the
   credential-owning transport.

   Resolves `{:configured? true :groups :quick-picks
   :quick-picks-configured? :stale? :auth-required?}`. A stale payload is
   served only by the native cache and is always labeled `:stale? true`;
   with no cache, or when the server returns no Group list, the promise
   rejects with a readable Error. Cached ids are for selection only and
   never authorize a write: Smriti rechecks membership on delivery."
  [system]
  (if-let [transport (:delivery-configuration system)]
    (-> (transport/fetch-groups! transport groups-path)
        (.then
         (fn [{:keys [body cached? auth-required? error]}]
           (if (and (map? body) (sequential? (:groups body)))
             (groups-payload->discovery body
                                        {:cached? cached?
                                         :auth-required? auth-required?})
             (throw (ex-info (or (:message error)
                                 "Smriti returned no Group list.")
                             {:code :groups-unavailable}))))))
    (js/Promise.reject
     (js/Error. "The Smriti connection is not configured."))))

(defn create
  "A transport that posts commands to the Smriti server at `server-url`. The
   `/api/command` path is appended here, so callers only deal with a host."
  [server-url]
  (transport/create (endpoint-url server-url) capture->command))
