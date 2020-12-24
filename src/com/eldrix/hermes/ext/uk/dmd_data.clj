(ns com.eldrix.hermes.ext.uk.dmd-data
  "Support the UK NHS dm+d XML data files.
  This namespace provides a thin wrapper over the data files, keeping the
  original structures as much as possible and thus facilitating adapting
  to changes in those definitions as they occur.
  For more information see
  https://www.nhsbsa.nhs.uk/sites/default/files/2017-02/Technical_Specification_of_data_files_R2_v3.1_May_2015.pdf"
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [clojure.zip :as zip]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.snomed :as snomed])
  (:import [java.time LocalDate]
           (java.time.format DateTimeFormatter DateTimeParseException)
           (org.apache.lucene.search IndexSearcher)))

;; dm+d date format = CCYY-MM-DD
(defn ^LocalDate parse-date [^String s] (try (LocalDate/parse s (DateTimeFormatter/ISO_LOCAL_DATE)) (catch DateTimeParseException _)))
(defn ^Long parse-long [^String s] (Long/parseLong s))
(defn ^Boolean parse-invalidity [^String s] (= "1" s))

(def file-matcher #"^f_([a-z]*)2_(\d*)\.xml$")

(def file-ordering
  "Order of file import for relational integrity."
  [:LOOKUP :INGREDIENT :VTM :VMP :AMP :VMPP :AMPP])

(defn parse-dmd-filename
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

(defn ^:private make-dmd-keyword [valueset code]
  (keyword "uk.nhs.dmd" (str (name valueset) "-" code)))

(def property-parsers
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
   :BS_SUBID                  parse-long
   :STRNT_NMRTR_VAL           clojure.edn/read-string
   :STRNT_NMRTR_UOMCD         parse-long
   :STRNT_DNMTR_VAL           clojure.edn/read-string
   :STRNT_DNMTR_UOMCD         parse-long
   :ROUTECD                   parse-long
   :CATDT                     parse-date
   :NMDT                      parse-date})

(defn parse-property [kind k v]
  (if-let [parser (get property-parsers [kind k])]
    {k (parser v)}
    (if-let [fallback (get property-parsers k)]
      {k (fallback v)}
      {k v})))

(defn parse-simple-xml
  "Very simple and crude conversion of arbitrary *non-nested* XML into a map."
  ([loc] (parse-simple-xml nil loc))
  ([type loc]
   (let [node (zip/node loc)
         content (:content node)]
     (reduce into (if type {:TYPE type} {}) (map #(parse-property type (:tag %) (first (:content %))) content)))))

(defn parse-lookup-xml
  "Extracts lookup (value set) data from the dm+d 'lookup' file.
  The Lookup XML contains multiple value set (codesystem) definitions.
  Turns each value into a map with :TYPE and :CD and :DESC as a minimum.
  Returns a map keyed to a dm+d keyword of the form described in
  `make-dmd-keyword`: :uk.nhs.dmd/TYPE-CODE

  Parameters:
  - root : a tree of element records from (clojure.data.xml/parse)."
  [root]
  (let [zipper (zip/xml-zip root)
        tags (map :tag (zip/children zipper))
        lookups (mapcat #(zx/xml-> zipper :LOOKUP % :INFO (partial parse-simple-xml %)) tags)]
    (into {} (map #(vector (make-dmd-keyword (:TYPE %) (:CD %)) %)) lookups)))

(defn parse-vtm-xml
  "The VTM XML structure is very simple containing only VTM entities."
  [root]
  (let [zipper (zip/xml-zip root)]
    (into {} (map #(hash-map (:VTMID %) %)) (zx/xml-> zipper :VIRTUAL_THERAPEUTIC_MOIETIES :VTM (partial parse-simple-xml :VTM)))))

(defn- properties-grouped-by-primary-key
  "Takes a collection and returns a map of results keyed by primary key.
  Parameters:
   - coll         : collection of maps, each map with a primary key
   - primary-key  : primary key (e.g. :VPID)
   - property-key : name of the property (e.g. :VPIS)
   - value-key    : (optional) if specified, property will be a set of the
                    values of the 'value key'."
  ([coll primary-key property-key] (properties-grouped-by-primary-key coll primary-key property-key nil))
  ([coll primary-key property-key value-key]
   (->> coll
        (map #(hash-map (get % primary-key) [(dissoc % primary-key)]))
        (apply merge-with concat)
        (reduce-kv (fn [m k v]
                     (assoc-in m [k property-key]
                               (if value-key (set (map value-key v)) v))) {}))))

(defn- vmp-ingredients
  "Returns ingredients keyed by VMP identifier."
  [zipper]
  (properties-grouped-by-primary-key
    (zx/xml-> zipper :VIRTUAL_MED_PRODUCTS :VIRTUAL_PRODUCT_INGREDIENT :VPI parse-simple-xml)
    :VPID
    :VPIS))

(defn- vmp-ont-drug-forms
  "Returns 'ontological' drug routes keyed by VMP identifier."
  [zipper]
  (properties-grouped-by-primary-key
    (zx/xml-> zipper :VIRTUAL_MED_PRODUCTS :ONT_DRUG_FORM :ONT parse-simple-xml)
    :VPID
    :ONT_DRUG_FORMS
    :FORMCD))

(defn- vmp-drug-forms
  "Returns drug forms keyed by VMP identifier."
  [zipper]
  (properties-grouped-by-primary-key
    (zx/xml-> zipper :VIRTUAL_MED_PRODUCTS :DRUG_FORM :DFORM parse-simple-xml)
    :VPID
    :DRUG_FORMS
    :FORMCD))

(defn- vmp-drug-routes
  "Returns drug routes keyed by VMP identifier."
  [zipper]
  (properties-grouped-by-primary-key
    (zx/xml-> zipper :VIRTUAL_MED_PRODUCTS :DRUG_ROUTE :DROUTE parse-simple-xml)
    :VPID
    :DRUG_ROUTES
    :ROUTECD))

(defn- vmp-controlled-drug-info
  [zipper]
  (->> (zx/xml-> zipper :VIRTUAL_MED_PRODUCTS :CONTROL_DRUG_INFO :CONTROL_INFO partial parse-simple-xml)
       (into {} (map #(hash-map (get % :VPID) {:CONTROL_DRUG_INFO (dissoc % :VPID)})))))

(defn parse-vmp-xml
  "The VMP structure contains multiple elements including
  - :VMPS ->> :VMP
  - :VIRTUAL_PRODUCT_INGREDIENT ->> :VPI   (for ingredients of each VMP)
  - :ONT_DRUG_FORM ->> :ONT   (for 'ontological' drug forms of each VMP)
  - :DRUG_FORM ->> :DFORM (for drug forms of each VMP/formulation)
  - :DRUG_ROUTE ->> :DROUTE (for drug routes of each VMP)
  - :CONTROL_DRUG_INFO ->> :CONTROL_INFO (for controlled drug information)

  This function creates a nested structure for each VMP instead."
  [root]
  (let [zipper (zip/xml-zip root)
        vmps (->> (zx/xml-> zipper :VIRTUAL_MED_PRODUCTS :VMPS :VMP (partial parse-simple-xml :VMP))
                  (into {} (map #(hash-map (:VPID %) %))))]
    (merge-with conj vmps
                (vmp-ingredients zipper)
                (vmp-ont-drug-forms zipper)
                (vmp-drug-forms zipper)
                (vmp-drug-routes zipper)
                (vmp-controlled-drug-info zipper))))



;;;;;
;;;;;
;;;;;
;;;;;
;;;;;
;;;;;
;;;;;
;;;;;

(comment
  (dmd-file-seq "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001")
  (def filename "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001/f_vtm2_3101220.xml")
  (def filename "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001/f_vmp2_3101220.xml")
  (def filename "/Users/mark/Downloads/nhsbsa_dmd_12.1.0_20201214000001/f_lookup2_3101220.xml")
  (parse-dmd-filename filename)
  (def rdr (io/reader filename))
  (def vtms (parse-file rdr))
  (def root (xml/parse rdr :skip-whitespace true))
  (parse-lookup-xml root)
  (parse-vtm-xml root)
  (parse-vmp-xml root)
  (def zipper (zip/xml-zip root))
  (zx/xml-> zipper :VIRTUAL_THERAPEUTIC_MOIETIES :VTM (partial parse-simple-xml :VTM))
  (xml->json root)

  (zx/xml-> zipper :LOOKUP :FLAVOUR :INFO zip/node)
  (def vmps)
  (zx/xml-> root :VIRTUAL_THERAPEUTIC_MOIETIES :VTM parse-vtm)
  (take 10 (zx/xml-> root :VIRTUAL_MED_PRODUCTS :VMPS :VMP parse-vmp))


  (first data)
  (second data)
  (def vtms (filter #(= :VTM (:tag %)) (:content data)))
  (def vtm (zip/xml-zip (first vtms)))
  vtm
  (zx/xml1-> vtm :VTM :VTMID zx/text)

  (do
    (def index-reader (search/open-index-reader "snomed.db/search.db"))
    (def searcher (org.apache.lucene.search.IndexSearcher. index-reader))
    )


  ;; does work
  (def ingreds (apply merge-with concat (map #(hash-map (:VPID %) {:VPIS %}) (zx/xml-> zipper :VIRTUAL_MED_PRODUCTS :VIRTUAL_PRODUCT_INGREDIENT :VPI parse-simple-xml))))

  )


