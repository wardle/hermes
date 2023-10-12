; Copyright (c) 2020-2023 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns com.eldrix.hermes.download
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.trud.core :as trud]
            [hato.client :as hc])
  (:import (java.io FileNotFoundException)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(s/def ::api-key string?)
(s/def ::cache-dir string?)
(s/def ::release-date string?)
(s/def ::uk-trud (s/keys :req-un [::api-key ::cache-dir]
                         :opt-un [::release-date]))

(s/def ::username string?)
(s/def ::password string?)
(s/def ::mlds (s/keys :req-un [::username ::password]))

(defn download-from-trud
  "Download an item from TRUD
  Parameters:
  - item-identifier : item wanted, (e.g. 101)
  - config: a map containing:
    :api-key : path to file containing TRUD api-key (e.g.\"/var/hermes/api-key.txt\")
    :cache-dir : TRUD download cache directory (e.g. \"/tmp/trud\")
    :progress  : print progress?
    :release-date : (optional) an ISO 8601 date e.g. \"2022-02-03\"
  Returns TRUD metadata about the item specified.
  See com.eldrix.trud.core/get-latest for information about return value."
  [item-identifier {:keys [api-key cache-dir progress release-date]}]
  (let [trud-key (str/trim-newline (slurp api-key))]
    (if release-date
      (let [release-date' (try (LocalDate/parse release-date) (catch DateTimeParseException _))
            release (when release-date' (first (filter #(.isEqual ^LocalDate release-date' (:releaseDate %)) (trud/get-releases trud-key item-identifier))))]
        (if-not release
          (do (when-not (= "list" release-date) (log/info "Release not found for date:" release-date' "Available releases:"))
              (pp/print-table [:releaseDate :archiveFileName] (trud/get-releases trud-key item-identifier)))
          (assoc release :archiveFilePath (trud/download-release cache-dir release))))
      (trud/get-latest {:api-key   trud-key
                        :cache-dir cache-dir
                        :progress  progress}
                       item-identifier))))

(def trud-distributions
  [{:id   "uk.nhs/sct-clinical"
    :rc   "UK"
    :f    (fn [params] (:archiveFilePath (download-from-trud 101 params)))
    :desc "UK clinical edition"
    :spec ::uk-trud}
   {:id   "uk.nhs/sct-drug-ext"
    :rc   "UK"
    :f    (fn [params] (:archiveFilePath (download-from-trud 105 params)))
    :spec ::uk-trud
    :desc "UK drug extension"}])


(def mlds-base-url "https://mlds.ihtsdotools.org")

(defn mlds-packages
  "Return all MLDS packages for all members."
  []
  (some-> (str mlds-base-url "/api/releasePackages")
          hc/get
          :body
          (json/read-str :key-fn keyword)))

(defn make-mlds-id
  [{:keys [member releasePackageId]}]
  (str (str/lower-case (:key member)) ".mlds" "/" releasePackageId))

(declare download-from-mlds)

(defn available-mlds-distributions
  ([] (available-mlds-distributions (mlds-packages)))
  ([packages]
   (->> packages
        (map (fn [{package-id :releasePackageId nm :name m :member d :description :as p}]
               (let [id (make-mlds-id p)]
                 (merge p {:id   id, :rc (:key m), :desc nm
                           :spec ::mlds, :f (partial download-from-mlds id)})))))))

(defn distribution
  "Returns the distribution with the given identifier. Avoids network calls
  for locally known distributions, but checks online providers if not found."
  ([id] (distribution id true))
  ([id local]
   (let [dists (if local trud-distributions (into trud-distributions (available-mlds-distributions)))
         available (reduce (fn [acc v] (assoc acc (:id v) v)) {} dists)
         result (get available id)]
     (if (and (not result) local) (distribution id false) result))))

(def http-opts
  "Default HTTP client options"
  {:as                :stream
   :throw-exceptions? false
   :http-client       {:redirect-policy :normal}})

(defn download-mlds-release-file
  "Download an MLDS release file"
  [username password-file {url :clientDownloadUrl, label :label}]
  (log/info "downloading MLDS release file" {:url url :file label})
  (let [password (try (some-> (slurp (io/file password-file)) str/trim-newline)
                      (catch FileNotFoundException e (log/error "password file not found") (throw e)))
        target (Files/createTempFile label "" (make-array FileAttribute 0))
        opts (assoc http-opts :basic-auth {:user username :pass password})
        {:keys [status body error]} (hc/get (str mlds-base-url url) opts)]
    (cond
      (= 401 status)
      (log/error "invalid credentials. check username and password")
      (= 500 status)                                        ;; unfortunately, if the user is not authorised, the MLDS server returns 500 with JSON data
      (let [body' (json/read-str (slurp body))]
        (throw (ex-info (str "Unable to download: " (get body' "message")) {:url url :status status :body body'})))
      (or (not= 200 status) error)
      (throw (ex-info "Unable to download" {:url url :status status :error error :body (slurp body)}))
      :else (do (io/copy body (.toFile target)) target))))

(defn download-from-mlds
  "Download the MLDS package specified."
  [package-id {:keys [username password release-date]}]
  (let [versions (some->> (distribution package-id) :releaseVersions (sort-by :publishedAt) reverse)]
    (if (= "list" release-date)
      (pp/print-table [:releaseVersionId :publishedAt] versions)
      (if-let [version (if-not release-date (first versions) ;; use the latest version if not release date specified
                                            (first (filter #(= release-date (:publishedAt %)) versions)))]
        (let [zip-release-files (filter #(str/ends-with? (:label %) ".zip") (:releaseFiles version))
              n (count zip-release-files)]
          (cond
            (zero? n) (throw (ex-info "no zip files found for release" version))
            (> n 1) (throw (ex-info "release has more than one archive file" version))
            :else (do (log/info "downloading MLDS package" {:id package-id :version (:releaseVersionId version) :release-date (:publishedAt version)})
                      (download-mlds-release-file username password (first zip-release-files)))))
        (throw (ex-info (str "no release version found for release date " release-date) {}))))))

(defn download
  "Download the named distribution.
  Parameters:
  - nm         : name of the provider   e.g. \"uk.nhs/sct-clinical\"
  - parameters : a map of parameters

  The parameters will depend on the exact nature of the provider.
  Returns the java.nio.file.Path of the directory containing unzipped files."
  ^Path [nm params]
  (let [{:keys [f spec]} (distribution nm)
        list-releases? (= "list" (:release-date params))]
    (when-not f
      (throw (IllegalArgumentException. (str "Unknown provider: " nm))))
    (if-not list-releases?
      (log/info "installing distribution" {:distribution nm :params params})
      (println "\nAvailable releases for distribution" nm ":"))
    (if-not (and spec (s/valid? spec params))
      (throw (ex-info (str "Invalid parameters for provider '" nm "'") (if spec (s/explain-data spec params) {})))
      (if-let [zipfile (f params)]
        (trud/unzip zipfile)
        (when-not list-releases? (log/warn "no files returned" {:provider nm}))))))

(defn print-providers
  "Placeholder for a more sophisticated future method of printing available
  providers."
  []
  (println "Available distributions for automated 'install':\n")
  (pp/print-table [:rc :id :desc] (sort-by :id (into trud-distributions (available-mlds-distributions)))))

(comment

  (download "uk.nhs/sct-clinical" ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-drug-ext" ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-clinical" [])

  (def api-key (str/trim (slurp "../trud/api-key.txt")))
  (trud/get-releases api-key 101)


  (def params ["api-key" "/Users/mark/Dev/trud/api-key.txt" "cache-dir" "/tmp/trud"])
  (def params2 ["api-key" "api-key.txt" "cache-dir=../trud/cache"])
  (apply hash-map params))



