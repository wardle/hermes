(ns com.eldrix.hermes.core
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.cli :as cli]
            [com.eldrix.hermes.import :as import]
            [com.eldrix.hermes.server :as server]
            [com.eldrix.hermes.store :as store]
            [com.eldrix.hermes.search :as search]
            [com.eldrix.hermes.terminology :as terminology]))

(defn import-from [{:keys [db]} args]
  (if db
    (let [dirs (if (= 0 (count args)) ["."] args)]
      (terminology/import-snomed db dirs))
    (log/error "no database directory specified")))

(defn list-from [_ args]
  (let [dirs (if (= 0 (count args)) ["."] args)]
    (doseq [dir dirs]
      (let [files (import/importable-files dir)
            heading (str "| Distribution files in " dir ":" (count files) " |")
            banner (apply str (repeat (count heading) "="))]
        (println "\n" banner "\n" heading "\n" banner)
        (clojure.pprint/print-table (map #(select-keys % [:filename :component :version-date :format :content-subtype :content-type]) files))))))

(defn build-indices [{:keys [db]} _]
  (if db
    (do (terminology/build-indices db)
        (terminology/build-search-index db))
    (log/error "no database directory specified")))

(defn compact [{:keys [db]} _]
  (if db
    (terminology/compact db)
    (log/error "no database directory specified")))


(defn status [{:keys [db]} _]
  (if db
    (clojure.pprint/pprint (terminology/get-status db))
    (log/error "no database directory specified")))


(defn serve [{:keys [db port]} args]
  (if db
    (let [svc (terminology/open-service db)]
      (log/info "****** starting server; port:" port " db:" db)
      (server/start-server svc port))
    (log/error "no database directory specified")))

(def cli-options
  [["-p" "--port PORT" "Port number"
    :default 8080
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-d" "--db PATH" "Path to database directory"
    :validate [string? "Missing database path"]]

   ["-h" "--help"]])


(defn usage [options-summary]
  (->> ["Usage: hermes [options] command [parameters]"
        ""
        "Options:"
        options-summary
        ""
        "Commands:"
        " import [paths] Import SNOMED distribution files from paths specified."
        " list [paths]   List importable files from the paths specified."
        " index          Build indexes"
        " compact        Compact database"
        " serve          Start a terminology server"
        " status         Displays status information"]
       (str/join \newline)))

(def commands
  {"import"  {:fn import-from}
   "list"    {:fn list-from}
   "index"   {:fn build-indices}
   "compact" {:fn compact}
   "serve"   {:fn serve}
   "status"  {:fn status}})

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn invoke-command [cmd opts args]
  (if-let [f (:fn cmd)]
    (f opts args)
    (exit 1 "error: not implemented")))

(defn -main [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options)
        command (get commands ((fnil str/lower-case "") (first arguments)))]
    (cond
      ;; asking for help?
      (:help options)
      (println (usage summary))
      ;; if we have any errors, exit with error message(s)
      errors
      (exit 1 (str/join \newline errors))
      ;; if we have no command, exit with error message
      (not command)
      (exit 1 (str "invalid command\n" (usage summary)))
      ;; invoke command
      :else (invoke-command command options (rest arguments)))))

(comment
  (def filename "/Users/mark/Downloads/uk_sct2cl_30.0.0_20200805000001/SnomedCT_InternationalRF2_PRODUCTION_20190731T120000Z")
  (def filename "C:\\Users\\mark\\Dev\\downloads\\uk_sct2cl_30.0.0_20200805000001")

  (def st (store/open-store "snomed.db"))
  (store/get-concept st 24700007)
  (store/get-description-refsets st 41398015)

  (search/build-search-index "snomed.db" "search.db" "en-GB")
  )