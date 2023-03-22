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
  (let [dirs (if (zero? (count args)) ["."] args)]
    (hermes/import-snomed db dirs)))

(defn list-from [_ args]
  (let [dirs (if (zero? (count args)) ["."] args)
        metadata (map #(select-keys % [:name :effectiveTime :deltaFromDate :deltaToDate]) (mapcat importer/all-metadata dirs))]
    (pp/print-table metadata)
    (doseq [dir dirs]
      (let [files (importer/importable-files dir)
            heading (str "| Distribution files in " dir ":" (count files) " |")
            banner (str/join (repeat (count heading) "="))]
        (println "\n" banner "\n" heading "\n" banner)
        (pp/print-table (map #(select-keys % [:filename :component :version-date :format :content-subtype :content-type]) files))))))

(defn install [{:keys [dist] :as opts} _]
  (if-not (seq dist)
    (do (println "No distribution specified. Specify with --dist.")
        (download/print-providers))
    (try
      (doseq [distribution dist]
        (when-let [unzipped-path (download/download distribution (dissoc opts :dist))]
          (import-from opts [(.toString unzipped-path)])))
      (catch Exception e
        (let [exd (ex-data e)]
          (if (contains? exd :clojure.spec.alpha/problems)
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

(defn status [{:keys [db verbose modules refsets] fmt :format} _]
  (let [st (hermes/status db {:counts? true :modules? (or verbose modules) :installed-refsets? (or verbose refsets) :log? false})]
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
   (->> [(str "Usage: hermes [options] 'commands' ")
         ""
         "For more help on a command, use hermes --help 'command'"
         ""
         "Options:"
         options-summary
         ""
         "Commands:"
         (cli/format-commands)]
        (str/join \newline)))
  ([options-summary cmds]
   (let [n (count cmds), cmds' (map cli/commands cmds)      ;; get information about each command requested
         {:keys [usage cmd desc]} (first cmds')]   ;; handle case of one command specially
     (->> [(str "Usage: hermes [options] " (if (= 1 n) (or usage cmd) (str/join " " cmds)))
           ""
           (if (= 1 n) desc (str/join \newline (map cli/format-command cmds')))
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
  (let [{:keys [cmds options arguments summary errors warnings]} (cli/parse-cli args)]
    (doseq [warning warnings] (log/warn warning))
    (cond
      ;; asking for help with a specific command?
      (and (seq cmds) (:help options))
      (println (usage summary cmds))
      ;; asking for help with no command?
      (:help options)
      (println (usage summary))
      ;; if we have no command, exit with error message
      (empty? cmds)
      (exit 1 (usage summary))
      ;; if we have any errors, exit with error message(s)
      errors
      (exit 1 (str (str/join \newline (map #(str "ERROR: " %) errors)) "\n\n" (usage summary cmds)))
      ;; invoke commands one by one
      :else (doseq [cmd cmds]
              (invoke-command (commands cmd) options arguments)))))

(comment)

