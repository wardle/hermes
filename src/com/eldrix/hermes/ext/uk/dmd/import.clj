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
(ns com.eldrix.hermes.ext.uk.dmd.import
  "Support the UK NHS dm+d XML data files.
  This namespace provides a thin wrapper over the data files, keeping the
  original structures as much as possible and thus facilitating adapting
  to changes in those definitions as they occur.

  For more information see
  https://www.nhsbsa.nhs.uk/sites/default/files/2017-02/Technical_Specification_of_data_files_R2_v3.1_May_2015.pdf"
  (:require [clojure.core.async :as a]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [clojure.zip :as zip])
  (:import [java.time LocalDate]
           (java.time.format DateTimeFormatter DateTimeParseException)))

;; dm+d date format = CCYY-MM-DD
(defn- ^LocalDate parse-date [^String s] (try (LocalDate/parse s (DateTimeFormatter/ISO_LOCAL_DATE)) (catch DateTimeParseException _)))
(defn- ^Long parse-long [^String s] (Long/parseLong s))
(defn- ^Boolean parse-invalidity [^String s] (= "1" s))

(def ^:private file-matcher #"^f_([a-z]*)2_(\d*)\.xml$")

(def ^:private file-ordering
  "Order of file import for relational integrity."
  [:LOOKUP :INGREDIENT :VTM :VMP :AMP :VMPP :AMPP])

(defn ^:private parse-dmd-filename
  [f]
  (let [f2 (clojure.java.io/as-file f)]
    (when-let [[_ nm _] (re-matches file-matcher (.getName f2))]
      (let [kw (keyword (str/upper-case nm))]
        {:type  kw
         :order (.indexOf file-ordering kw)
         :file  f2}))))

(defn dmd-file-seq
  "Return an ordered sequence of dm+d files from the directory specified.
  Components are returned in an order to support referential integrity.
  Each result is a map containing :type, :order and :file."
  [dir]
  (->> dir
       clojure.java.io/file
       file-seq
       (map parse-dmd-filename)
       (filter some?)
       (sort-by :order)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Generic dm+d parsing functionality
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private property-parsers
  {[:UNIT_OF_MEASURE :CD]     parse-long
   [:UNIT_OF_MEASURE :CDPREV] parse-long
   [:FORM :CD]                parse-long
   [:FORM :CDPREV]            parse-long
   [:ROUTE :CD]               parse-long
   [:ROUTE :CDPREV]           parse-long
   [:SUPPLIER :CD]            parse-long
   [:SUPPLIER :CDPREV]        parse-long
   :CDDT                      parse-date
   :VTMID                     parse-long
   :VTMIDPREV                 parse-long
   :INVALID                   parse-invalidity
   :VTMIDDT                   parse-date
   :VPID                      parse-long
   :VPIDPREV                  parse-long
   :UDFS                      edn/read-string
   :UDFS_UOMCD                parse-long
   :UNIT_DOSE_UOMCD           parse-long
   :ISID                      parse-long
   :ISIDPREV                  parse-long
   :ISIDDT                    parse-date
   :BS_SUBID                  parse-long
   :STRNT_NMRTR_VAL           edn/read-string
   :STRNT_NMRTR_UOMCD         parse-long
   :STRNT_DNMTR_VAL           edn/read-string
   :STRNT_DNMTR_UOMCD         parse-long
   :ROUTECD                   parse-long
   :CATDT                     parse-date
   :NMDT                      parse-date
   :SUPPCD                    parse-long
   :APID                      parse-long
   :VPPID                     parse-long
   :QTYVAL                    edn/read-string
   :QTY_UOMCD                 parse-long
   :APPID                     parse-long
   :REIMB_STATDT              parse-date
   :DISCDT                    parse-date
   :PRNTVPPID                 parse-long
   :CHLDVPPID                 parse-long
   :PRNTAPPID                 parse-long
   :CHLDAPPID                 parse-long})

(defn- parse-property [kind kw v]
  (if-let [parser (get property-parsers [kind kw])]
    {kw (parser v)}
    (if-let [fallback (get property-parsers kw)]
      {kw (fallback v)}
      {kw v})))

(defn- parse-dmd-component
  "Parse a fragment of XML.
  Does not process nested XML but that is not required for the dm+d XML."
  ([node] (parse-dmd-component nil node))
  ([kind node]
   (reduce into (if kind {:TYPE (keyword "uk.nhs.dmd" (name kind))} {})
           (map #(parse-property kind (:tag %) (first (:content %))) (:content node)))))

(defn- resolve-in-xml
  "Generic resolution of a node given a path.
  Parameters:
   - root  : a root from 'clojure.data.xml/parse'
   - path  : a collection representing the path required.

  Resolution of nested tags uses only the *first* tag found; this is appropriate
  for the dm+d files, but use zippers if more complex navigation is required."
  [root path]
  (if (= 0 (count path))
    (:content root)
    (let [item (first path)]
      (resolve-in-xml (first (filter #(= item (:tag %)) (:content root))) (rest path)))))

(defn xf-component
  "A transducer to manipulate a first class dm+d component (VTM/VMP/AMP etc).
  Adds an ID that is the component ID."
  [kind id-key]
  (comp
    (map (partial parse-dmd-component kind))
    (map #(assoc % :ID (get % id-key)))))

(defn- stream-component
  [kind path id root ch]
  (loop [components (sequence (xf-component kind id) (resolve-in-xml root path))]
    (when (and (first components) (a/>!! ch (first components)))
      (recur (next components)))))

(defn xf-property
  "A transducer to manipulate a nested dm+d component.
  Adds an ID that is a tuple of the parent component ID and the property type."
  [kind fk-key]
  (comp
    (map (partial parse-dmd-component kind))
    (map #(assoc % :ID (vector (get % fk-key) kind)))))

(defn- stream-property
  [kind path fk-key root ch]
  (loop [components (sequence (xf-property kind fk-key) (resolve-in-xml root path))]
    (when (and (first components) (a/>!! ch (first components)))
      (recur (next components)))))

(defn xf-lookup
  "A transducer to process LOOKUP dm+d components.
  Adds an ID made up of the type and code."
  [kind]
  (comp
    (map (partial parse-dmd-component kind))
    (map #(assoc % :TYPE :uk.nhs.dmd/LOOKUP
                   :ID (keyword (str (name kind) "-" (:CD %)))))))

(defn- parse-lookup-xml
  [root ch]
  (let [zipper (zip/xml-zip root)
        tags (map :tag (zip/children zipper))
        lookups (mapcat
                  #(sequence (xf-lookup %) (zx/xml-> zipper :LOOKUP % :INFO zip/node))
                  tags)]
    (loop [result lookups]
      (if (and result (a/>!! ch (first result)))
        (recur (next result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; High-level dm+d processing functionality
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def file-configuration
  {:VTM        [{:nm :VTM :path [] :id :VTMID}]
   :VMP        [{:nm :VMP :path [:VMPS] :id :VPID}
                {:path [:VIRTUAL_PRODUCT_INGREDIENT] :fk :VPID}
                {:path [:ONT_DRUG_FORM] :fk :VPID}
                {:path [:DRUG_FORM] :fk :VPID}
                {:path [:DRUG_ROUTE] :fk :VPID}
                {:path [:CONTROL_DRUG_INFO] :fk :VPID}]
   :AMP        [{:nm :AMP :path [:AMPS] :id :APID}
                {:path [:AP_INGREDIENT] :fk :APID}
                {:path [:LICENSED_ROUTE] :fk :APID}
                {:path [:AP_INFORMATION] :fk :APID}]
   :VMPP       [{:nm :VMPP :path [:VMPPS] :id :VPPID}
                {:path [:DRUG_TARIFF_INFO] :fk :VPPID}
                {:path [:COMB_CONTENT] :fk :PRNTVPPID}]
   :AMPP       [{:nm :AMPP :path [:AMPPS] :id :APPID}
                {:path [:APPLIANCE_PACK_INFO] :fk :APPID}
                {:path [:DRUG_PRODUCT_PRESCRIB_INFO] :fk :APPID}
                {:path [:MEDICINAL_PRODUCT_PRICE] :fk :APPID}
                {:path [:REIMBURSEMENT_INFO] :fk :APPID}
                {:path [:COMB_CONTENT] :fk :PRNTAPPID}]
   :INGREDIENT [{:nm :INGREDIENT :path [] :id :ISID}]
   :LOOKUP     [{:nm :LOOKUP :func parse-lookup-xml}]})

(defn- filter-configurations
  "Return the selected configurations for the specified file-type.
  Parameters:
  - configs - base file configurations
  - file-type - file type or :all for all (useful at REPL)
  - opts - a map of options:
    |- include - if given, tuples of file type/component type to be included.
    |- exclude - if given, tuples of file type/component type to be excluded."
  [configs file-type {:keys [include exclude]}]
  (let [flattened (mapcat (fn [[k vs]] (map #(assoc % :component [k (or (:nm %) (first (:path %)))]) vs)) configs)
        available (map :component flattened)
        base (or include available)
        cfgs (set (if (and file-type (not= :all file-type)) (filter #(= file-type (first %)) base) base))
        selected (if exclude (clojure.set/difference cfgs (set exclude)) cfgs)]
    (filter #(contains? selected (:component %)) flattened)))

(defn- parse-configuration-item
  "Generates a function from the given configuration.

  A function will be generated that can take parsed XML and stream the result
  to the channel supplied. '(fn [root ch] ...)'

  Each product is exported as a first class key value pair using the ':id'
  Each property is exported as a compound key made up of the product
  identifier (':fk') and the relationship name (':nm').
  The relationship name defaults to the first entry in the path."
  [{:keys [nm path id fk func] :as config}]
  (let [nm' (or nm (first path))]
    (cond
      func func
      (and id fk) (throw (ex-info "cannot specify both 'id' and 'fk'" config))
      id (partial stream-component nm' path id)
      fk (partial stream-property nm' path fk)
      :else (throw (ex-info "must provide either 'id' or 'fk'" config)))))

(defn- do-import
  [dmd-file f ch]
  (with-open [rdr (io/reader (:file dmd-file))]
    (let [root (xml/parse rdr :skip-whitespace true)]
      (f root ch))))

(defn import-file
  [dmd-file ch {:keys [close?] :as opts}]
  (if-let [configs (filter-configurations file-configuration (:type dmd-file) opts)]
    (doseq [cfg configs] (do-import dmd-file (parse-configuration-item cfg) ch))
    (log/warn "skipping file " dmd-file ": no implemented parser"))
  (when close? (a/close! ch)))

(defn import-dmd
  "Streams UK dm+d data to the channel.
  Data are ordered in order to preserve relational integrity.
  Blocking so run in a thread if necessary.
  Parameters:
  - dir  : directory from which to import files
  - ch   : clojure.core.async channel to which to send data
  - opts : map of optional options including
    |- :close?  : whether to close the channel when done (default true)
    |- :include : a set of dm+d file / component types to include.
    |- :exclude : a set of dm+d file / component types to exclude.

  Inclusions and exclusions should be of the form of a set of tuples made up
  of the filetype (e.g. :VTM, :VMP, :AMP, :VMPP :AMPP, :LOOKUP etc) and component
  type (e.g. :VIRTUAL_PRODUCT_INGREDIENT, :ONT_DRUG_FORM etc)
  #{[:VMP :VIRTUAL_PRODUCT_INGREDIENT]}. The naming of components is as the
  dm+d standard, in the singular.

  As INGREDIENTS data is redundant in the context of a wider SNOMED terminology
  server, it would be usual to use
     {:exclude #{[:INGREDIENT :INGREDIENT]}}
  to exclude the import of ingredient data from dm+d XML files.

  Each streamed result is a map containing a dm+d component and an extra ':ID'
  property. The identifier is one of three types:
  - numeric: always a SNOMED concept identifier; used for first-class high-level
             dm+d components such as VTM/VMP/AMP etc.
  - keyword: a keyword key is an identifier representing a value in a valueset.
  - vector : a vector key represents a relation of a core concept, consisting of
   a tuple of the parent concept and a keyword representing relationship type.

  Example:
    (def ch (a/chan 1 (partition-all 500)))
    (thread (import-dmd \"dir\" ch))
    (a/<!! ch)"
  ([dir ch] (import-dmd dir ch nil))
  ([dir ch {:keys [close? include exclude] :or {close? true, exclude #{[:INGREDIENT :INGREDIENT]}} :as opts}]
   (let [files (dmd-file-seq dir)]
     (doseq [f files]
         (import-file f ch (assoc opts :close? false)))  ;; force 'close?' to be false
     (when close?
       (a/close! ch)))))

(defn statistics-dmd
  "Return statistics for dm+d data in the specified directory."
  [dir]
  (let [ch (a/chan)]
    (a/thread (import-dmd dir ch))
    (loop [item (a/<!! ch)
           counts {}]
      (if-not item
        counts
        (recur (a/<!! ch)
               (update counts (:TYPE item) (fnil inc 0)))))))

(comment
  (dmd-file-seq "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001")
  (def ch (a/chan))
  (a/thread (import-dmd "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001" ch
                        {:exclude #{[:INGREDIENT :INGREDIENT]}}))
  (a/<!! ch)
  (statistics-dmd "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001")
  )
