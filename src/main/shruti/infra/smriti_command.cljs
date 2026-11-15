(ns shruti.infra.smriti-command
  "Smriti's POST /api/command wire mapping.

   A capture becomes a scripted journal command. The mapping lives apart
   from the transport that sends it, so the transport stays usable for
   any JSON command endpoint."
  (:require [clojure.string :as str]
            [shruti.infra.command-transport :as transport]))

(def command-path
  "Smriti's command API path. It never changes, so the setup screen shows it as
   a label and the app appends it to whatever server host the user enters."
  "/api/command")

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

(defn- final-text [request]
  (some-> (or (get-in request [:content :transcript])
              (get-in request [:content :text]))
          str
          str/trim
          not-empty))

(defn capture->command
  "The JSON body Smriti's /api/command accepts for a text-bearing capture.

   `created-at` is the original occurred-at instant: Smriti parses an
   offset date-time as-is, so a note spoken near midnight keeps the day it
   happened rather than the day it was delivered. Returns nil when there is
   no final text, because the command creates an entry from text only."
  [request]
  (when-let [text (final-text request)]
    (cond-> {"command/name" "journal/create-entry"
             "text" text
             "tags" ["shruti" "todo"]}
      (not (str/blank? (str (:occurred-at request))))
      (assoc "created-at" (:occurred-at request)))))

(defn create
  "A transport that posts commands to the Smriti server at `server-url`. The
   `/api/command` path is appended here, so callers only deal with a host."
  [server-url]
  (transport/create (endpoint-url server-url) capture->command))
