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
(ns com.eldrix.hermes.ext.uk.dmd.store
  "A simple key value store for UK dm+d data.
  This is principally designed to be used as part of a wider SNOMED
  terminology server, so certain data are deliberately omitted by default."
  (:require [clojure.core.async :as a]
            [clojure.java.io :as io]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.ext.uk.dmd.import :as dmd-import])
  (:import (org.mapdb DB Serializer DBMaker BTreeMap)
           (java.io FileNotFoundException Closeable)
           (org.mapdb.serializer SerializerArrayTuple)))

(deftype DmdStore [^DB db
                   ^BTreeMap core
                   ^BTreeMap lookups]
  Closeable
  (close [this] (.close ^DB (.db this))))

(defn open-dmd-store
  ([^String filename] (open-dmd-store filename {}))
  ([^String filename {:keys [read-only? skip-check?] :or {read-only? true}}]
  (when (and read-only? (not (.exists (io/as-file filename))))
    (throw (FileNotFoundException. (str "file `" filename "` opened read-only but not found"))))
  (let [db (.make (cond-> (-> (DBMaker/fileDB filename)
                              (.fileMmapEnable)
                              (.closeOnJvmShutdown))
                          skip-check? (.checksumHeaderBypass)
                          read-only? (.readOnly)))
        ;; core dm+d components are stored keyed with identifier
        core (.createOrOpen
               (.treeMap db "core" Serializer/LONG Serializer/JAVA))
        ;; lookup tables are stored keyed with tableName-code as a string
        lookups (.createOrOpen
                  (.treeMap db "lookups" Serializer/STRING Serializer/JAVA))]
    (->DmdStore db core lookups))))

;; core dm+d components
(derive :uk.nhs.dmd/VTM :uk.nhs.dmd/COMPONENT)
(derive :uk.nhs.dmd/VMP :uk.nhs.dmd/COMPONENT)
(derive :uk.nhs.dmd/AMP :uk.nhs.dmd/COMPONENT)
(derive :uk.nhs.dmd/AMPP :uk.nhs.dmd/COMPONENT)
(derive :uk.nhs.dmd/VMPP :uk.nhs.dmd/COMPONENT)

(defmulti put
          "Store an arbitrary dm+d component into the backing store.
          Parameters:
          - store : DmdStore
          - m     : component / VTM / VMP / VMPP / AMP / AMPP / TF / TFG"
          (fn [^DmdStore store m] (:TYPE m)))

(defmethod put :uk.nhs.dmd/COMPONENT
  [^DmdStore store component]
  (.put ^BTreeMap (.core store) (:ID component) component))

(defmethod put :uk.nhs.dmd/LOOKUP
  [^DmdStore store lookup]
  (.put ^BTreeMap (.-lookups store) (name (:ID lookup)) (dissoc lookup :ID)))

(defmethod put :default
  [^DmdStore store component]
  (let [[concept-id property] (:ID component)]
    (if-let [v (.get ^BTreeMap (.core store) concept-id)]
      (let [new-value (update v property (fn [old] ((fnil conj []) old (dissoc component :ID :TYPE))))]
        (.put ^BTreeMap (.core store) concept-id new-value))
      (throw (ex-info "no existing component for id" {:id concept-id :component component})))))

(defn import-dmd [filename dir]
  (let [ch (a/chan)
        store (open-dmd-store filename {:read-only? false})]
    (a/thread (dmd-import/import-dmd dir ch {:exclude [:INGREDIENT :INGREDIENT]}))
    (loop [m (a/<!! ch)]
      (when m
        (try (put store m)
             (catch Exception e (throw (ex-info "failed to import" {:e e :component m}))))
        (recur (a/<!! ch))))
    (log/debug "compacting data store")
    (.compact (.getStore ^BTreeMap (.core store)))
    (log/debug "import/compaction completed")
    (.close ^DB (.db store))))

(comment
  (import-dmd "dmd3.db" "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001")
  (def store (open-dmd-store "dmd3.db"))
  (.get (.core store) 39211611000001104)
  (.get (.core store) 39233511000001107)
  (.get (.lookups store) (name :VIRTUAL_PRODUCT_NON_AVAIL-0001))
  (.get (.lookups store) (name :CONTROL_DRUG_CATEGORY-0000))
  (.get (.lookups store) (name :ONT_FORM_ROUTE-0022))
  (.lookups store)
  )