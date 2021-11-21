(ns com.eldrix.hermes.cmd.core
  (:gen-class)
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [com.eldrix.hermes.cmd.server :as server]
            [com.eldrix.hermes.download :as download]
            [com.eldrix.hermes.importer :as importer]
            [com.eldrix.hermes.core :as hermes]))

(defn import-from [{:keys [db]} args]
  (if db
    (let [dirs (if (= 0 (count args)) ["."] args)]
      (hermes/import-snomed db dirs))
    (log/error "no database directory specified")))

(defn list-from [_ args]
  (let [dirs (if (= 0 (count args)) ["."] args)
        metadata (map #(select-keys % [:name :effectiveTime :deltaFromDate :deltaToDate]) (mapcat importer/all-metadata dirs))]
    (pp/print-table metadata)
    (doseq [dir dirs]
      (let [files (importer/importable-files dir)
            heading (str "| Distribution files in " dir ":" (count files) " |")
            banner (apply str (repeat (count heading) "="))]
        (println "\n" banner "\n" heading "\n" banner)
        (pp/print-table (map #(select-keys % [:filename :component :version-date :format :content-subtype :content-type]) files))))))

(defn download [opts args]
  (if-let [[provider & params] (seq args)]
    (when-let [unzipped-path (download/download provider params)]
      (import-from opts [(.toString unzipped-path)]))
    (do (println "No provider specified. Available providers:")
        (download/print-providers))))

(defn build-index [{:keys [db locale]} _]
  (if db
    (if (str/blank? locale)
      (hermes/build-search-index db)
      (hermes/build-search-index db locale))
    (log/error "no database directory specified")))

(defn compact [{:keys [db]} _]
  (if db
    (hermes/compact db)
    (log/error "no database directory specified")))

(defn status [{:keys [db verbose]} _]
  (if db
    (pp/pprint (hermes/get-status db :counts? verbose :installed-refsets? true))
    (log/error "no database directory specified")))

(defn serve [{:keys [db _port _bind-address allowed-origins] :as params} _]
  (if db
    (let [svc (hermes/open db)
          allowed-origins' (when allowed-origins (str/split allowed-origins #","))
          params' (cond (= ["*"] allowed-origins') (assoc params :allowed-origins (constantly true))
                        (seq allowed-origins') (assoc params :allowed-origins allowed-origins')
                        :else params)]
      (log/info "starting terminology server " params')
      (server/start-server svc params'))
    (log/error "no database directory specified")))

(def cli-options
  [["-p" "--port PORT" "Port number"
    :default 8080
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-a" "--bind-address BIND_ADDRESS" "Address to bind"]

   [nil "--allowed-origins */ORIGINS" "Set CORS policy, with \"*\" or comma-delimited hostnames"]

   ["-d" "--db PATH" "Path to database directory"
    :validate [string? "Missing database path"]]

   [nil "--locale LOCALE" "Locale to use, if different from system"]

   ["-v" "--verbose"]

   ["-h" "--help"]])

(defn usage [options-summary]
  (->>
    ["Usage: hermes [options] command [parameters]"
     ""
     "Options:"
     options-summary
     ""
     "Commands:"
     " import [paths]             Import SNOMED distribution files"
     " list [paths]               List importable files"
     " download [provider] [opts] Download & install distribution from provider"
     " index                      Build search index."
     " compact                    Compact database"
     " serve                      Start a terminology server"
     " status                     Displays status information"]
    (str/join \newline)))

(def commands
  {"import"   {:fn import-from}
   "list"     {:fn list-from}
   "download" {:fn download}
   "index"    {:fn build-index}
   "compact"  {:fn compact}
   "serve"    {:fn serve}
   "status"   {:fn status}})

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

  )