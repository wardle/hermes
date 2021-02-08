; Copyright 2020 Mark Wardle and Eldrix Ltd
;
;   Licensed under the Apache License, Version 2.0 (the "License");
;   you may not use this file except in compliance with the License.
;   You may obtain a copy of the License at
;
;       http://www.apache.org/licenses/LICENSE-2.0
;
;   Unless required by applicable law or agreed to in writing, software
;   distributed under the License is distributed on an "AS IS" BASIS,
;   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;   See the License for the specific language governing permissions and
;   limitations under the License.
;;;;
(ns com.eldrix.hermes.core
  (:gen-class)
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [com.eldrix.hermes.config :as config]
            [com.eldrix.hermes.download :as download]
            [com.eldrix.hermes.import :as import]
            [com.eldrix.hermes.terminology :as terminology]
            [integrant.core :as ig]))

(defn import-from [{:keys [db]} args]
  (if db
    (let [dirs (if (= 0 (count args)) ["."] args)]
      (terminology/import-snomed db dirs))
    (log/error "no database directory specified")))

(defn list-from [_ args]
  (let [dirs (if (= 0 (count args)) ["."] args)
        metadata (map #(select-keys % [:name :effectiveTime :deltaFromDate :deltaToDate]) (mapcat import/all-metadata dirs))]
    (pp/print-table metadata)
    (doseq [dir dirs]
      (let [files (import/importable-files dir)
            heading (str "| Distribution files in " dir ":" (count files) " |")
            banner (apply str (repeat (count heading) "="))]
        (println "\n" banner "\n" heading "\n" banner)
        (pp/print-table (map #(select-keys % [:filename :component :version-date :format :content-subtype :content-type]) files))))))

(defn download [opts args]
  (let [provider (first args)
        params (rest args)]
    (when-let [unzipped-path (download/download provider params)]
        (import-from opts [(.toString unzipped-path)]))))

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
    (pp/pprint (terminology/get-status db))
    (log/error "no database directory specified")))

(defn serve [{:keys [db port]} _]
  (if db
    (let [conf (-> (:ig/system (config/config :live))
                   (assoc-in [:terminology/service :path] db)
                   (assoc-in [:http/server :port] port)
                   (assoc-in [:http/server :join?] true))]
      (log/info "starting terminology server " conf)
      (ig/init conf))
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
        " download [provider] [opts] Download a distribution from a provider."
        " index          Build indexes"
        " compact        Compact database"
        " serve          Start a terminology server"
        " status         Displays status information"]
       (str/join \newline)))

(def commands
  {"import"   {:fn import-from}
   "list"     {:fn list-from}
   "download" {:fn download}
   "index"    {:fn build-indices}
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