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

(defn ^:private make-dmd-keyword [kw]
  (keyword "uk.nhs.dmd" (name kw)))


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
   :UNIT_DOSE_UOMCD           parse-long
   :ISID                      parse-long
   :ISIDPREV                  parse-long
   :ISIDDT                    parse-date
   :BS_SUBID                  parse-long
   :STRNT_NMRTR_VAL           clojure.edn/read-string
   :STRNT_NMRTR_UOMCD         parse-long
   :STRNT_DNMTR_VAL           clojure.edn/read-string
   :STRNT_DNMTR_UOMCD         parse-long
   :ROUTECD                   parse-long
   :CATDT                     parse-date
   :NMDT                      parse-date
   :SUPPCD                    parse-long
   :APID                      parse-long
   :VPPID                     parse-long
   :QTY_UOMCD                 parse-long
   :APPID                     parse-long
   :REIMB_STATDT              parse-date
   :DISCDT                    parse-date})

(defn- parse-property [kind k v]
  (let [kw (make-dmd-keyword k)]
    (if-let [parser (get property-parsers [kind k])]
      {kw (parser v)}
      (if-let [fallback (get property-parsers k)]
        {kw (fallback v)}
        {kw v}))))

(defn- parse-dmd-component
  "Parse a fragment of XML into a simple flat map, adding an optional ':TYPE'
  parameter if specified. Does not process nested XML but that is not required
  for the dm+d XML."
  ([node] (parse-dmd-component nil node))
  ([kind node]
   (reduce
     into
     (if type {:uk.nhs.dmd/TYPE (make-dmd-keyword kind)} {})
     (map #(parse-property type (:tag %) (first (:content %))) (:content node)))))

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

(defn- import-component
  [kind path root]
  (map (partial parse-dmd-component kind) (resolve-in-xml root path)))

(defn- stream-component
  [kind path root ch]
  (loop [components (import-component kind path root)]
    (when (and (first components) (a/>!! ch (first components)))
      (recur (next components)))))

(defn- parse-lookup-xml
  [root ch]
  (let [zipper (zip/xml-zip root)
        tags (map :tag (zip/children zipper))
        lookups (mapcat #(map (partial parse-dmd-component %) (zx/xml-> zipper :LOOKUP % :INFO zip/node)) tags)]
    (loop [result lookups]
      (if (and result (a/>!! ch (first result)))
        (recur (next result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; High-level dm+d processing functionality
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def file-configuration
  {:VTM        [{:name :VTM :path []}]
   :VMP        [{:name :VMP :path [:VMPS]}
                {:path [:VIRTUAL_PRODUCT_INGREDIENT]}
                {:path [:ONT_DRUG_FORM]}
                {:path [:DRUG_FORM]}
                {:path [:DRUG_ROUTE]}
                {:path [:CONTROL_DRUG_INFO]}]
   :AMP        [{:name :AMP :path [:AMPS]}
                {:path [:AP_INGREDIENT]}
                {:path [:LICENSED_ROUTE]}
                {:path [:AP_INFORMATION]}]
   :VMPP       [{:name :VMPP :path [:VMPPS]}
                {:path [:DRUG_TARIFF_INFO]}
                {:path [:COMB_CONTENT]}]
   :AMPP       [{:name :AMPP :path [:AMPPS]}
                {:path [:APPLIANCE_PACK_INFO]}
                {:path [:DRUG_PRODUCT_PRESCRIB_INFO]}
                {:path [:MEDICINAL_PRODUCT_PRICE]}
                {:path [:REIMBURSEMENT_INFO]}
                {:path [:COMB_CONTENT]}]
   :INGREDIENT [{:name :INGREDIENT :path []}]
   :LOOKUP     {:fn parse-lookup-xml}})

(defn- parse-configuration
  [{:keys [name path product-key fn] :or {path []}}]
  (if fn
    fn
    (let [nm (or name (first path))]
      (partial stream-component nm path product-key))))

(defn- do-import
  [dmd-file f ch]
  (with-open [rdr (io/reader (:file dmd-file))]
    (let [root (xml/parse rdr :skip-whitespace true)]
      (f root ch))))

(defn import-file
  [dmd-file ch close?]
  (if-let [configs (get file-configuration (:type dmd-file))]
    (if (map? configs)
      (do-import dmd-file (parse-configuration configs) ch)
      (doseq [cfg configs] (do-import dmd-file (parse-configuration cfg) ch)))
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
    |- :close? : whether to close the channel when done (default true)
    |- :types  : a set of dm+d filetypes to include if different from default.

  The default comprises all known dm+d file types except Ingredients as that
  component is redundant in the context of a wider SNOMED terminology server.

  Each streamed result is a key value pair.
  A numeric key is always a SNOMED concept identifier.
  A keyword key is a namespaced identifier representing a value in a valueset.
  For all, the value is a close representation of the dm+d data structure.

  Example:
    (def ch (a/chan 1 (partition-all 500)))
    (thread (import-dmd \"dir\" ch))
    (a/<!! ch)"
  ([dir ch] (import-dmd dir ch nil))
  ([dir ch {:keys [close? types] :or {close? true, types #{:LOOKUP :VTM :VMP :AMP :VMPP :AMPP}}}]
   (let [files (dmd-file-seq dir)]
     (doseq [f files]
       (when (contains? types (:type f))
         (import-file f ch false)))
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
               (update counts (:uk.nhs.dmd/TYPE item) (fnil inc 0)))))))

(comment
  (dmd-file-seq "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001")
  (def ch (a/chan))
  (a/thread (import-dmd "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001" ch {:types #{:VMP}}))
  (a/<!! ch)
  (statistics-dmd "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001")
  )
