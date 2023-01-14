(ns com.eldrix.hermes.cmd.core
  (:gen-class)
  (:require
    [clojure.data.json :as json]
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [clojure.tools.logging.readable :as log]
    [com.eldrix.hermes.cmd.cli :as cli]
    [com.eldrix.hermes.cmd.server :as server]
    [com.eldrix.hermes.core :as hermes]
    [com.eldrix.hermes.download :as download]
    [com.eldrix.hermes.importer :as importer]
    [expound.alpha :as expound]))

(defn- log-module-dependency-problems [svc]
  (let [problem-deps (seq (hermes/module-dependency-problems svc))]
    (doseq [dep problem-deps]
      (log/warn "module dependency mismatch" dep))))

(defn import-from [{:keys [db]} args]
  (let [dirs (if (= 0 (count args)) ["."] args)]
    (hermes/import-snomed db dirs)))

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

(defn install [{:keys [dist] :as opts} _]
  (if-not (seq dist)
    (do (println "No distribution specified. Specify with --dist.")
        (download/print-providers))
    (doseq [distribution dist]
      (try
        (when-let [unzipped-path (download/download distribution (dissoc opts :dist))]
          (import-from opts [(.toString unzipped-path)]))
        (catch Exception e (if-let [exd (ex-data e)]
                             ((expound/custom-printer {:print-specs? false :theme :figwheel-theme}) exd)
                             (log/error (.getMessage e))))))))

(defn available [{:keys [dist] :as opts} _]
  (if-not (seq dist)
    (download/print-providers)
    (install (assoc opts :release-date "list") [])))

(defn build-index [{:keys [db locale]} _]
  (if (str/blank? locale) (hermes/index db) (hermes/index db locale))
  (with-open [svc (hermes/open db {:quiet true})]
    (log-module-dependency-problems svc)))


(defn compact [{:keys [db]} _]
  (hermes/compact db))

(defn status [{:keys [db verbose modules refsets] fmt :format} args]
  (let [st (hermes/get-status db :counts? true :modules? (or verbose modules) :installed-refsets? (or verbose refsets) :log? false)]
    (case fmt
      :json (json/pprint st)
      (clojure.pprint/pprint st))))


(defn serve [{:keys [db _port _bind-address allowed-origin] :as params} _]
  (let [svc (hermes/open db)
        params' (cond (= ["*"] allowed-origin) (assoc params :allowed-origins (constantly true))
                      (seq allowed-origin) (assoc params :allowed-origins allowed-origin)
                      :else params)]
    (log/info "env" (-> (System/getProperties)
                        (select-keys ["os.name" "os.arch" "os.version" "java.vm.name" "java.vm.version"])
                        (update-keys keyword)))
    (log-module-dependency-problems svc)
    (log/info "starting terminology server " (dissoc params' :allowed-origin))
    (server/start-server svc params')))

(defn usage
  ([options-summary]
   (->> [(str "Usage: hermes [options] 'command' [parameters]")
         ""
         "For more help on a command, use hermes --help 'command'"
         ""
         "Options:"
         options-summary
         ""
         "Commands:"
         (cli/format-commands)]
        (str/join \newline)))
  ([options-summary cmd]
   (when-let [{:keys [usage desc]} (cli/commands cmd)]
     (->> [(str "Usage: hermes [options] " (or usage cmd))
           ""
           desc
           ""
           "Options:"
           options-summary]
          (str/join \newline)))))

(def commands
  {"import"    {:fn import-from}
   "list"      {:fn list-from}
   "download"  {:fn install}
   "install"   {:fn install}
   "available" {:fn available}
   "index"     {:fn build-index}
   "compact"   {:fn compact}
   "serve"     {:fn serve}
   "status"    {:fn status}})

(defn exit [status-code msg]
  (println msg)
  (System/exit status-code))

(defn invoke-command [cmd opts args]
  (if-let [f (:fn cmd)]
    (f opts args)
    (exit 1 "ERROR: not implemented ")))

(defn -main [& args]
  (let [{:keys [cmd options arguments summary errors warnings]} (cli/parse-cli args)]
    (doseq [warning warnings] (log/warn warning))
    (cond
      ;; asking for help with a specific command?
      (and cmd (:help options))
      (println (usage summary cmd))
      ;; asking for help with no command?
      (:help options)
      (println (usage summary))
      ;; if we have any errors, exit with error message(s)
      errors
      (exit 1 (str (str/join \newline (map #(str "ERROR: " %) errors)) "\n\n" (usage summary cmd)))
      ;; if we have no command, exit with error message
      (not cmd)
      (exit 1 (usage summary))
      ;; invoke command
      :else (invoke-command (commands cmd) options arguments))))

(comment)

