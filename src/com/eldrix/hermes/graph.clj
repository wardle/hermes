(ns com.eldrix.hermes.graph
  "Provides a graph API around SNOMED CT structures."
  (:require [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.interface.eql :as p.eql]))

(defn record->map
  "Turn a record into a namespaced map."
  [n r]
  (reduce-kv (fn [m k v] (assoc m (keyword n (name k)) v)) {} r))

(def concept-properties
  [:info.snomed.Concept/id
   :info.snomed.Concept/active
   :info.snomed.Concept/effectiveTime
   :info.snomed.Concept/moduleId
   :info.snomed.Concept/definitionStatusId])

(def description-properties
  [:info.snomed.Description/id
   :info.snomed.Description/active
   :info.snomed.Description/term
   :info.snomed.Description/caseSignificanceId
   :info.snomed.Description/effectiveTime
   :info.snomed.Description/typeId
   :info.snomed.Description/languageCode
   :info.snomed.Description/moduleId])

(def refset-item-properties
  [:info.snomed.RefsetItem/id
   :info.snomed.RefsetItem/effectiveTime
   :info.snomed.RefsetItem/active
   :info.snomed.RefsetItem/moduleId
   :info.snomed.RefsetItem/refsetId
   :info.snomed.RefsetItem/referencedComponentId])

(pco/defresolver concept-by-id
  "Returns a concept by identifier; results namespaced to `:info.snomed.Concept/`"
  [{::keys [svc]} {:info.snomed.Concept/keys [id]}]
  {::pco/output concept-properties}
  (record->map "info.snomed.Concept" (hermes/get-concept svc id)))

(pco/defresolver concept-defined?
  "Is a concept fully defined?"
  [{:keys [:info.snomed.Concept/definitionStatusId]}]
  {:info.snomed.Concept/defined (= snomed/Defined definitionStatusId)})

(pco/defresolver concept-primitive?
  "Is a concept primitive?"
  [{:keys [:info.snomed.Concept/definitionStatusId]}]
  {:info.snomed.Concept/primitive (= snomed/Primitive definitionStatusId)})

(pco/defresolver concept-descriptions
  "Return the descriptions for a given concept."
  [{::keys [svc]} {:info.snomed.Concept/keys [id]}]
  {::pco/input  [:info.snomed.Concept/id]
   ::pco/output [{:info.snomed.Concept/descriptions description-properties}]}
  {:info.snomed.Concept/descriptions (map (partial record->map "info.snomed.Description") (hermes/get-descriptions svc id))})

(pco/defresolver concept-module
  "Return the module for a given concept."
  [{::keys [svc]} {:info.snomed.Concept/keys [moduleId]}]
  {::pco/output [{:info.snomed.Concept/module concept-properties}]}
  {:info.snomed.Concept/module {:info.snomed.Concept/id moduleId}})

(pco/defresolver preferred-description
  "Returns a concept's preferred description.
  Takes an optional single parameter :accept-language, a BCP 47 language
  preference string.

  For example:
  (p.eql/process registry {:id 80146002}
    [:info.snomed.Concept/id
     :info.snomed.Concept/active
     '(:info.snomed.Concept/preferred-description {:accept-language \"en-GB\"})
     {:info.snomed.Concept/descriptions
      [:info.snomed.Description/active :info.snomed.Description/term]}])"
  [{::keys [svc] :as env} {:info.snomed.Concept/keys [id]}]
  {::pco/input  [:info.snomed.Concept/id]
   ::pco/output [{:info.snomed.Concept/preferredDescription
                  description-properties}]}
  (let [lang (or (get (pco/params env) :accept-language) (.toLanguageTag (java.util.Locale/getDefault)))]
    {:info.snomed.Concept/preferredDescription (record->map "info.snomed.Description" (hermes/get-preferred-synonym svc id lang))}))

(pco/defresolver fully-specified-name
  [{::keys [svc]} {:info.snomed.Concept/keys [id]}]
  {::pco/output [{:info.snomed.Concept/fullySpecifiedName description-properties}]}
  {:info.snomed.Concept/fullySpecifiedName (record->map "info.snomed.Description" (hermes/get-fully-specified-name svc id))})

(pco/defresolver lowercase-term
  "Returns a SNOMED description as a lowercase term."
  [{:info.snomed.Description/keys [caseSignificanceId term]}]
  {:info.snomed.Description/lowercaseTerm
   (case caseSignificanceId
     ;; initial character is case-sensitive - we can make initial character lowercase
     900000000000020002
     (when (> (count term) 0)
       (str (str/lower-case (first term)) (subs term 1)))
     ;; entire term case insensitive - just make it all lower-case
     900000000000448009
     (str/lower-case term)
     ;; entire term is case sensitive - can't do anything
     900000000000017005
     term
     ;; fallback option - don't do anything
     term)})

(pco/defresolver concept-refset-ids
  "Returns a concept's reference set identifiers."
  [{::keys [svc]} {:info.snomed.Concept/keys [id]}]
  {::pco/input  [:info.snomed.Concept/id]
   ::pco/output [:info.snomed.Concept/refsetIds]}
  {:info.snomed.Concept/refsetIds (set (hermes/get-reference-sets svc id))})

(pco/defresolver concept-refsets
  "Returns the refset items for a concept."
  [{::keys [svc]} {:info.snomed.Concept/keys [id]}]
  {::pco/output [{:info.snomed.Concept/refsetItems refset-item-properties}]}
  {:info.snomed.Concept/refsetItems (map (partial record->map "info.snomed.RefsetItem") (hermes/get-component-refset-items svc id 0))})

(pco/defresolver concept-relationships
  [{::keys [svc] :as env} {:info.snomed.Concept/keys [id]}]
  {::pco/output [:info.snomed.Concept/parentRelationshipIds
                 :info.snomed.Concept/directParentRelationshipIds]}
  (let [ec (hermes/get-extended-concept svc id)
        rel-type (:type (pco/params env))
        parents (if rel-type {rel-type (get-in ec [:parentRelationships rel-type])}
                             (:parentRelationships ec))
        dp (if rel-type {rel-type (get-in ec [:directParentRelationships rel-type])}
                        (:directParentRelationships ec))]
    {:info.snomed.Concept/parentRelationshipIds       parents
     :info.snomed.Concept/directParentRelationshipIds dp}))

(pco/defresolver refsetitem-concept
  [{:info.snomed.RefsetItem/keys [refsetId]}]
  {::pco/output [{:info.snomed.RefsetItem/refset [:info.snomed.Concept/id]}]}
  {:info.snomed.RefsetItem/refset {:info.snomed.Concept/id refsetId}})

(pco/defresolver readctv3-concept
  "Each Read CTV3 code has a direct one-to-one map to a SNOMED identifier."
  [{::keys [svc]} {:info.read/keys [ctv3]}]
  {::pco/output [:info.snomed.Concept/id]}
  {:info.snomed.Concept/id (:referencedComponentId (first (hermes/reverse-map svc 900000000000497000 ctv3)))})

(pco/defresolver concept-readctv3
  "Each Read CTV3 code has a direct one-to-one map to a SNOMED identifier."
  [{::keys [svc]} {:info.snomed.Concept/keys [id]}]
  {::pco/output [:info.read/ctv3]}
  {:info.read/ctv3 (:mapTarget (first (hermes/get-component-refset-items svc id 900000000000497000)))})

(pco/defmutation search
  "Performs a search. Parameters:
    |- :s                  : search string to use
    |- :constraint         : SNOMED ECL constraint to apply
    |- :max-hits           : maximum hits (if omitted returns unlimited but
                             *unsorted* results)."
  [{::keys [svc]} params]
  {::pco/op-name 'info.snomed.Search/search
   ::pco/params  [:s :constraint :max-hits]
   ::pco/output  [[:info.snomed.Description/id
                   :info.snomed.Concept/id
                   :info.snomed.Description/term
                   {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}]]}

  (map (fn [result] {:info.snomed.Description/id               (:id result)
                     :info.snomed.Concept/id                   (:conceptId result)
                     :info.snomed.Description/term             (:term result)
                     :info.snomed.Concept/preferredDescription {:info.snomed.Description/term (:preferredTerm result)}})
       (hermes/search svc (select-keys params [:s :constraint :max-hits]))))

(def all-resolvers
  "SNOMED resolvers; each expects an environment that contains
  a key :com.eldrix.hermes.graph/svc representing a SNOMED svc."
  [concept-by-id
   concept-defined?
   concept-primitive?
   concept-descriptions
   concept-module
   concept-refset-ids
   concept-refsets
   readctv3-concept
   concept-readctv3
   refsetitem-concept
   preferred-description
   concept-relationships
   lowercase-term
   search])

(comment
  (def svc (hermes/open "/Users/mark/Dev/hermes/snomed.db"))
  svc
  (hermes/get-concept svc 24700007)
  (hermes/get-extended-concept svc 24700007)
  (hermes/search svc {:s          "amyliod"
                      :fuzzy      2
                      :constraint "<404684003"
                      :max-hits   10})
  (map (partial record->map "info.snomed.Description") (hermes/get-descriptions svc 24700007))

  concept-by-id
  (concept-by-id {::svc svc} {:info.snomed.Concept/id 24700007})
  (concept-descriptions {::svc svc} {:info.snomed.Concept/id 24700007})
  (preferred-description {::svc svc} {:info.snomed.Concept/id 24700007})

  (def registry (-> (pci/register all-resolvers)
                    (assoc ::svc svc)))
  (require '[com.wsscode.pathom.viz.ws-connector.core :as pvc]
           '[com.wsscode.pathom.viz.ws-connector.pathom3 :as p.connector])
  (p.connector/connect-env registry {:com.wsscode.pathom.viz.ws-connector.core/parser-id 'registry})

  (sort (map #(vector (:id %) (:term %))
             (map #(hermes/get-preferred-synonym svc % "en-GB") (hermes/get-installed-reference-sets svc))))
  (hermes/reverse-map svc 900000000000497000 "A130.")
  (map #(hermes/get-component-refset-items svc 24700007 %) (hermes/get-reference-sets svc 24700007))
  (first (hermes/get-component-refset-items svc 24700007 900000000000497000))
  (p.eql/process registry
                 {:info.snomed.Concept/id 80146002}
                 [:info.snomed.Concept/id
                  :info.snomed.Concept/active
                  '(:info.snomed.Concept/preferredDescription {:accept-language "en-GB"})
                  :info.snomed.Concept/refsets
                  {:info.snomed.Concept/descriptions
                   [:info.snomed.Description/active :info.snomed.Description/lowercaseTerm]}])

  (p.eql/process registry
                 {:info.snomed.Concept/id 24700007}
                 [:info.snomed.Concept/id
                  :info.read/ctv3
                  '(:info.snomed.Concept/parentRelationshipIds {:type 116676008})
                  '(:info.snomed.Concept/preferredDescription {:accept-language "en-GB"})])

  (p.eql/process registry
                 {:info.read/ctv3 "F20.."}
                 [:info.snomed.Concept/id
                  {:info.snomed.Concept/preferredDescription [:info.snomed.Description/lowercaseTerm]}])
  
  (hermes/reverse-map svc 900000000000497000 "F20..")

  (p.eql/process registry
                 [{'(info.snomed.Search/search
                      {:s          "mult scl"
                       :constraint "<404684003"
                       :max-hits   10})
                   [:info.snomed.Concept/id
                    :info.snomed.Description/id
                    :info.snomed.Description/term
                    {:info.snomed.Concept/preferredDescription [:info.snomed.Description/term]}
                    :info.snomed.Concept/active]}]))