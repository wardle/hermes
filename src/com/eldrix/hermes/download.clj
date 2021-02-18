(ns com.eldrix.hermes.download
  (:require [clojure.spec.alpha :as s]
            [expound.alpha :as expound]
            [com.eldrix.trud.core :as trud]
            [com.eldrix.trud.zip :as zip]
            [clojure.pprint :as pprint]))

(s/def ::api-key string?)
(s/def ::cache-dir string?)
(s/def ::uk-trud (s/keys :req-un [::api-key ::cache-dir]))

(def registry
  "A registry of download providers. "
  {"uk.nhs/sct-clinical" {:f    (fn [params] (:archiveFilePath (trud/get-latest params 101)))
                          :spec ::uk-trud}
   "uk.nhs/sct-drug-ext" {:f    (fn [params] (:archiveFilePath (trud/get-latest params 105)))
                          :spec ::uk-trud}})

(s/def ::provider-parameters (s/* (s/cat ::key string? ::value string?)))

(defn print-providers
  "Placeholder for a more sophisticated future method of printing available
  providers."
  []
  (clojure.pprint/print-table  (map #(hash-map :identifier %) (keys registry))))

(defn download
  "Download the named distribution.
  Parameters:
  - nm         : name of the provider   e.g. \"uk.nhs/sct-clinical\"
  - parameters : a sequence of key value pairs with optional parameters.

  The parameters will depend on the exact nature of the provider."
  [nm parameters]
  (if-not (s/valid? ::provider-parameters parameters)
    (println "Parameters must be given as key value pairs. e.g. \"api-key 123" \" (expound/expound-str ::provider-parameters parameters))
    (let [{:keys [f spec]} (get registry nm)]
      (when-not f
        (throw (IllegalArgumentException. (str "Unknown provider: " nm))))
      (let [params (clojure.walk/keywordize-keys (apply hash-map parameters))]
        (println "Downloading using provider " nm " with params" params)
        (if-not (and spec (s/valid? spec params))
          (println "Invalid parameters for provider '" nm "':" (expound/expound-str spec params {:print-specs? false :theme :figwheel-theme}))
          (when-let [zipfile (f params)]
            (zip/unzip zipfile)))))))

(comment

  (download "uk.nhs/sct-clinical" ["api-key" "xxx" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-drug-ext" ["api-key" "xxx" "cache-dir" "/tmp/trud"])
  (download "uk.nhs/sct-clinical" [])

                      )
