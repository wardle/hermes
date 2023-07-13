(ns com.eldrix.hermes.cmd.cli
  (:require [clojure.core.match :as match]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.cli :as cli])
  (:import (java.util Locale)))


;; Specific options relating to automatic import from a well-known distribution

(def uk-trud-opts
  [[nil "--api-key API-KEY-PATH" "Path to a file containing TRUD API key"
    :missing "Missing TRUD API key"]
   [nil "--cache-dir PATH" "Path to a download cache (optional)"
    :default-fn (fn [_] (System/getProperty "java.io.tmpdir"))
    :default-desc ""]
   [nil "--release-date DATE" "Date of release, ISO 8601. e.g. \"2022-02-03\" (optional)"]])

(def mlds-opts
  [[nil "--username USERNAME" "Username for MLDS"
    :missing "Missing username"]
   [nil "--password PASSWORD_FILE" "Path to a file containing password for MLDS"
    :missing "Missing password"]
   [nil "--release-date DATE" "Date of release, ISO 8601. e.g. \"2022-02-03\" (optional)"]])

(defn distribution-opts
  [id]
  (match/match (str/split id #"\p{Punct}")
    ["uk" "nhs" & _] uk-trud-opts
    [_ "mlds" & _] mlds-opts
    :else nil))

(def install-parameters
  #{"api-key" "cache-dir" "release-date" "username" "password"})

(def re-install-parameters
  "A regular expression to match one of the special 'install' parameters for
  a distribution."
  (re-pattern (str "(" (str/join "|" install-parameters) ")=.*")))

;;
;; Generic options relating to each sub-command
;;

(def all-options
  {:db              ["-d" "--db PATH" "Path to database directory"]
   :port            ["-p" "--port PORT" "Port number"
                     :default 8080
                     :parse-fn parse-long
                     :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   :bind-address    ["-a" "--bind-address BIND_ADDRESS" "Address to bind"]
   :allowed-origins [nil "--allowed-origins \"*\" or ORIGINS" "Set CORS policy, with \"*\" or comma-delimited hostnames"]
   :allowed-origin  [nil "--allowed-origin \"*\" or ORIGIN" "Set CORS policy, with \"*\" or hostname"
                     :multi true :default [] :update-fn conj]
   :locale          [nil "--locale LOCALE" "Set default / fallback locale"
                     :default (.toLanguageTag (Locale/getDefault))]
   :format          [nil "--format FMT" "Format for status output ('json' or 'edn')"
                     :parse-fn keyword :validate [#{:json :edn} "Format must be 'json' or 'edn'"]]
   :dist            [nil "--dist DST" "Distribution(s) e.g. uk.nhs/sct-clinical"
                     :multi true :default [] :update-fn conj :default-desc ""]
   :verbose         ["-v" "--verbose"]
   :progress        [nil "--progress" "Turn on progress reporting"]
   :help            ["-h" "--help"]})

(defn option
  "Return an option with additional parameters appended."
  ([opt] (option opt nil))
  ([opt extra-params] (into (all-options opt) (mapcat seq extra-params))))

(def db-mandatory {:missing "No path to database directory specified"})

(defn- make-distribution-options*
  "Create CLI options for a given distribution. "
  ([db?] (make-distribution-options* db? nil))
  ([db? extra-opts] (concat (when db? [(option :db db-mandatory)])
                            [(option :dist)]
                            extra-opts
                            [(option :progress)
                             (option :help)])))

(defn make-distribution-options
  "Generate a dynamic sequence of cli options for an distribution based on
  command-line arguments. This performs a two-pass parse, first with the basic
  options specification, ignoring errors, and then using that parsed result to
  identify any chosen distributions. Those are then used to generate the full
  required options specification dynamically."
  [{:keys [db?]} args]
  (let [{:keys [arguments options]} (clojure.tools.cli/parse-opts args (make-distribution-options* db?))
        possible (into (set (:dist options)) (set arguments)) ;; all possibly selected distributions
        opts (distinct (mapcat distribution-opts possible))]
    (make-distribution-options* db? opts)))

(defn expand-legacy-parameters
  "Parse a sequence of well-known string arguments returning a vector of
  command-line args. Arguments can be provided as alternating key value pairs,
  or as strings of the format key = value. This is for backwards compatibility.

  The following are equivalent:
  ```
  (expand-legacy-parameters [\"api-key\" \"../trud/api-key.txt\"])
  (expand-legacy-parameters [\"api-key=../trud/api-key.txt\"])
   => [\"--api-key\" \"../trud/api-key.txt\"}
  ```"
  [args]
  (reduce (fn [acc v] (cond
                        (install-parameters v)              ;; a well-known 'install' parameter e.g. 'api-key'?
                        (conj acc (str "--" v))             ;; -> flag
                        (re-matches re-install-parameters v) ;; a 'api-key=xxxx' pair  of a well-known 'install' flag?
                        (let [[k v] (str/split v #"=")]     ;; -> flag and value
                          (conj acc (str "--" k) v))
                        :else
                        (conj acc v))) [] args))

(defn parse-legacy-distributions
  "If any command line 'arguments' are not flags, but can be recognised as
   well-known distributions, then for backwards compatibility, add to the
   selected distributions."
  [{:keys [arguments] :as parsed}]
  (if-let [dists (seq (filter distribution-opts arguments))]
    (-> parsed
        (assoc :arguments (remove (set dists) arguments))
        (update-in [:options :dist] into dists))
    parsed))

(def commands*
  [{:cmd  "list" :usage "list [paths]"
    :desc "List importable files from the path(s) specified."
    :opts [(option :help)]}
   {:cmd  "import" :usage "import [paths]"
    :desc "Import SNOMED distribution files from the path(s) specified"
    :opts [(option :db db-mandatory) (option :help)]}
   {:cmd  "available" :desc "List available distributions, or releases for 'install'"
    :opts #(make-distribution-options {} %)}
   {:cmd  "download" :usage "download [dists]" :deprecated true :warning "Use 'install' instead"
    :desc "Download and install specified distributions"
    :opts #(make-distribution-options {:db? true} %)}
   {:cmd  "install" :desc "Download and install specified distribution(s)"
    :opts #(make-distribution-options {:db? true} %)}
   {:cmd  "index" :desc "Build search indices"
    :opts [(option :db db-mandatory) (option :help)]}
   {:cmd  "compact" :desc "Compact database"
    :opts [(option :db db-mandatory) (option :help)]}
   {:cmd  "status" :desc "Display status information"
    :opts [(option :db db-mandatory)
           (option :format)
           [nil "--modules" "Show installed modules"]
           [nil "--refsets" "Show installed refsets"]
           (option :help)]}
   {:cmd  "serve" :desc "Start a terminology server"
    :opts [(option :db db-mandatory) (option :port) (option :bind-address)
           (option :allowed-origins) (option :allowed-origin) (option :locale)
           (option :help)]}])

(def commands
  "Return information about the command specified."
  (reduce (fn [acc {:keys [cmd] :as v}] (assoc acc cmd v)) {} commands*))

(def all-commands (set (map :cmd commands*)))

(defn format-command [{:keys [cmd usage desc]}]
  (str " " (format "%-14s" (or usage cmd)) " - " desc))

(defn format-commands
  "Returns a string representing the list of commands and their descriptions."
  []
  (->> commands*
       (remove :deprecated)
       (map format-command)
       (str/join \newline)))

(defn- warn-if-deprecated
  "Add a warning for each deprecated command."
  [{:keys [cmds] :as parsed}]
  (let [warnings (->> cmds
                      (map commands)
                      (filter :deprecated)
                      (map #(str "Command '" (:cmd %) "' is deprecated. " (:warning %))))]
    (if (seq warnings)
      (update parsed :warnings (fnil conj []) warnings)
      parsed)))

(defn- parse-allowed-origins
  "Incorporate single comma-delimited allowed origins into the main
  'allowed-origin' vector."
  [{:keys [options] :as parsed}]
  (if (str/blank? (:allowed-origins options))
    parsed
    (-> parsed
        (update :options dissoc :allowed-origins)
        (update-in [:options :allowed-origin] #(apply conj % (str/split (:allowed-origins options) #","))))))


(defn opts-for-commands
  "Given a collection of command-line arguments, return sorted options."
  [args]
  (->> (filter all-commands args)
       (map commands)
       (map :opts)
       (mapcat #(if (fn? %) (% args) %))
       distinct
       (sort-by second)))

(defn parse-cli
  "Parse command-line arguments and return a map containing:
  - :cmds      - a vector of the commands requested, in order given, may be [].
  - :options   - parsed parameters for the commands
  - :arguments - any remaining command line arguments
  - :warnings  - a sequence of warnings, if any
  - :errors    - a sequence of errors, if any

  Commands are returned in order given on the command-line."
  [args]
  (let [cmds (filterv all-commands args)                    ;; return commands in order given on command-line
        opts (or (seq (opts-for-commands args)) [(option :help)])] ;; determine options for command(s)
    (-> args
        expand-legacy-parameters
        (cli/parse-opts opts)
        (update :arguments #(remove (set cmds) %))
        (assoc :cmds cmds)
        parse-allowed-origins
        parse-legacy-distributions
        warn-if-deprecated)))

(comment
  (def args ["--db wibble.db" "serve" "flibble"])
  (def cmd (set/intersection (set args) all-commands))
  (count cmd)
  (cli/parse-opts ["--db" "wibble.db"] (commands "download")))