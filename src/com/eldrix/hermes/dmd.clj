(ns com.eldrix.hermes.dmd
  "Provides functionality to process and understand data from the
  UK Dictionary of Medicines and Devices (dm+d).
  This is, by definition, a UK-only module and will not give expected results for
  drugs outside of the UK Product root.

  See https://www.nhsbsa.nhs.uk/sites/default/files/2018-10/doc_SnomedCTUKDrugExtensionModel%20-%20v1.0.pdf

  The dm+d model consists of the following components:
  VTM
  VMP
  VMPP
  TF
  AMP
  AMPP

  The relationships between these components are:
  VMP <<- IS_A -> VTM
  VMP <<- HAS_SPECIFIC_ACTIVE_INGREDIENT ->> SUBSTANCE
  VMP <<- HAS_DISPENSED_DOSE_FORM ->> QUALIFIER
  VMPP <<- HAS_VMP -> VMP
  AMPP <<- IS_A -> VMPP
  AMPP <<- HAS_AMP -> AMP
  AMP <<- IS_A -> VMP
  AMP <<- IS_A -> TF
  AMP <<- HAS_EXCIPIENT ->> QUALIFIER
  TF <<- HAS_TRADE_FAMILY_GROUP ->> QUALIFIER

  Cardinality rules are: (see https://www.nhsbsa.nhs.uk/sites/default/files/2017-02/Technical_Specification_of_data_files_R2_v3.1_May_2015.pdf)
  The SNOMED dm+d data file documents the cardinality rules for AMP<->TF (https://www.nhsbsa.nhs.uk/sites/default/files/2017-04/doc_UKTCSnomedCTUKDrugExtensionEditorialPolicy_Current-en-GB_GB1000001_v7_0.pdf)"
  (:require [com.eldrix.hermes.service :as svc]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store])
  (:import (com.eldrix.hermes.snomed Concept ExtendedConcept)))

;; Core concepts - types of dm+d product
(def UKProduct 10363601000001109)
(def ActualMedicinalProduct 10363901000001102)
(def ActualMedicinalProductPack 10364001000001104)
(def VirtualMedicinalProduct 10363801000001108)
(def VirtualMedicinalProductPack 8653601000001108)
(def VirtuaTherapeuticMoiety 10363701000001104)
(def TradeFamily 9191801000001103)

;; dm+d reference sets - membership of a reference set tells us which of six types of product
(def VtmReferenceSet 999000581000001102)
(def TfReferenceSet 999000631000001100)
(def AmpReferenceSet 999000541000001108)
(def AmppReferenceSet 999000551000001106)
(def VmpReferenceSet 999000561000001109)
(def VmppReferenceSet 999000571000001104)

(def refset-id->product
  {VtmReferenceSet  ::vtm
   TfReferenceSet   ::tf
   AmpReferenceSet  ::amp
   AmppReferenceSet ::ampp
   VmpReferenceSet  ::vmp
   VmppReferenceSet ::vmpp})

(def product->refset-id
  (clojure.set/map-invert refset-id->product))

;;  Language reference sets
(def NhsDmdRealmLanguageReferenceSet 999000671000001103)
(def NhsRealmPharmacyLanguageReferenceSet 999000691000001104)
(def NhsRealmClinicalLanguageReferenceSet 999001261000000100)
(def NhsEPrescribingRouteAdministrationReferenceSet 999000051000001100)
(def DoseFormReferenceSet 999000781000001107)
(def SugarFreeReferenceSet 999000601000001109)
(def GlutenFreeReferenceSet 999000611000001106)
(def PreservativeFreeReferenceSet 999000621000001102)
(def CombinationDrugVtm 999000771000001105)
(def ChlorofluorocarbonFreeReferenceSet 999000651000001105)
(def BlackTriangleReferenceSet 999000661000001108)

(def PendingMove 900000000000492006)
(def HasActiveIngredient 127489000)
(def HasVmp 10362601000001103)
(def HasAmp 10362701000001108)
(def HasTradeFamilyGroup 9191701000001107)
(def HasSpecificActiveIngredient 10362801000001104)
(def HasDispensedDoseForm 10362901000001105)                ;; UK dm+d version of "HasDoseForm"
(def HasDoseForm 411116001)                                 ;; Do not use - from International release - use dm+d relationship instead
(def HasExcipient 8653101000001104)
(def PrescribingStatus 8940001000001105)
(def NonAvailabilityIndicator 8940601000001102)
(def LegalCategory 8941301000001102)
(def DiscontinuedIndicator 8941901000001101)
(def HasBasisOfStrength 10363001000001101)
(def HasUnitOfAdministration 13085501000001109)
(def HasUnitOfPresentation 763032000)
(def HasNHSdmdBasisOfStrength 10363001000001101)
(def HasNHSControlledDrugCategory 13089101000001102)
(def HasVMPNonAvailabilityIndicator 8940601000001102)
(def VMPPrescribingStatus 8940001000001105)
(def HasNHSdmdVmpRouteOfAdministration 13088401000001104)
(def HasNHSdmdVmpOntologyFormAndRoute 13088501000001100)
(def HasPresentationStrengthNumerator 732944001)
(def HasPresentationStrengthDenominator 732946004)
(def HasPresentationStrengthNumeratorUnit 732945000)

(def CautionAMPLevelPrescribingAdvised 13291401000001100)
(def NeverValidToPrescribeAsVrp 12459601000001102)
(def NeverValidToPrescribeAsVmp 8940401000001100)
(def NotRecommendedToPrescribeAsVmp 8940501000001101)
(def InvalidAsPrescribableProduct 8940301000001108)
(def NotRecommendedBrandsNotBioequivalent 9900001000001104)
(def NotRecommendedNoProductSpecification 12468201000001102)
(def NotRecommendedPatientTraining 9900101000001103)
(def VmpValidAsPrescribableProduct 8940201000001104)
(def VrpValidAsPrescribableProduct 12223601000001104)

(defmulti product-type
          "Return the dm+d product type of the concept specified.
          Parameters:
          - store : MapDBStore
          - concept : a concept, either identifier, concept or extended concept."
          (fn [store concept] (class concept)))

(defmethod product-type Long [store concept-id]
  (let [refsets (store/get-component-refsets store concept-id)]
    (some identity (map refset-id->product refsets))))

(defmethod product-type nil [_ _] nil)

(defmethod product-type Concept [store concept]
  (product-type store (:id concept)))

(defmethod product-type ExtendedConcept [_ extended-concept]
  (some identity (map refset-id->product (:refsets extended-concept))))

(defn is-vtm? [store concept]
  (= ::vtm (product-type store concept)))

(defn is-vmp? [store concept]
  (= ::vmp (product-type store concept)))

(defn is-vmpp? [store concept]
  (= ::vmpp (product-type store concept)))

(defn is-amp? [store concept]
  (= ::amp (product-type store concept)))

(defn is-ampp? [store concept]
  (= ::ampp (product-type store concept)))

(defn is-tf? [store concept]
  (= ::tf (product-type store concept)))

(defmulti vmps (fn [store concept] [(class concept) (product-type store concept)]))
(defmulti vtms (fn [store concept] [(class concept) (product-type store concept)]))
(defmulti amps (fn [store concept] [(class concept) (product-type store concept)]))
(defmulti tfs (fn [store concept] [(class concept) (product-type store concept)]))

(defmethod vmps [Long ::vtm] [store concept-id]
  (filter (partial is-vmp? store) (store/get-all-children store concept-id)))
(defmethod vtms [Long ::vtm] [store concept-id]
  (filter (partial is-vtm? store) (store/get-all-children store concept-id)))
(defmethod amps [Long ::vtm] [store concept-id]
  (filter (partial is-amp? store) (store/get-all-children store concept-id)))
(defmethod tfs [Long ::amp] [store concept-id]
  (filter (partial is-tf? store) (store/get-all-parents store concept-id)))
(defmethod tfs [Long ::vtm] [store concept-id]
  (mapcat (partial tfs store) (amps store concept-id)))

(defmethod vmps [Concept ::vtm] [store concept]
  (vmps store (:id concept)))
(defmethod vmps [ExtendedConcept ::vtm] [store extended-concept]
  (vmps store (get-in extended-concept :concept :id)))



(comment
  (do
    (def store (store/open-store "snomed.db/store.db"))
    (def index-reader (search/open-index-reader "snomed.db/search.db"))
    (def searcher (org.apache.lucene.search.IndexSearcher. index-reader))
    (require '[clojure.pprint :as pp])
    (require '[com.eldrix.hermes.expression.ecl :as ecl])
    (defn search-dmd [s product]
      (if-let [refset-id (get product->refset-id product)]
        (search/do-search searcher {:s s :query (ecl/parse store searcher (str "^" refset-id))})))
    (defn fsn [concept-id]
      (:term (store/get-fully-specified-name store concept-id))))

  (def amlodipine-vtms (set (map :conceptId (search-dmd "amlodipine" ::vtm))))
  (every? true? (map (partial is-vtm? store) amlodipine-vtms))
  (map :term (map (partial store/get-fully-specified-name store) amlodipine-vtms))
  (def amlodipine-vtm (first amlodipine-vtms))
  (def amlodipine-vmps (vmps store amlodipine-vtm))
  (every? true? (map (partial is-vmp? store) amlodipine-vmps))
  (map fsn (mapcat (partial vmps store) amlodipine-vtms))
  
  )