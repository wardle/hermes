(ns com.eldrix.hermes.snomed
  "Package snomed defines the specification for SNOMED-CT releases in the RF2
  format.

  See the [release file specifications](https://confluence.ihtsdotools.org/display/DOCRELFMT/SNOMED+CT+Release+File+Specifications)

  These are, in large part, raw representations of the release files with some
  small additions, predominantly relating to valid enumerations, to aid
  computability.

  The structures are not intended for use in a working terminology server which
  needs optimised structures in order to be performant.

  These structures are designed to cope with importing any SNOMED-CT
  distribution, including full distributions, a snapshot or a delta.

  * Full	   The files representing each type of component contain every version
             of every component ever released.
  * Snapshot The files representing each type of component contain one version
             of every component released up to the time of the snapshot. The
             version of each component contained in a snapshot is the most
             recent version of that component at the time of the snapshot.
  * Delta	   The files representing each type of component contain only
             component versions created since the previous release. Each
             component version in a delta release represents either a new
             component or a change to an existing component."

  (:require [clojure.spec.alpha :as s]
            [com.eldrix.hermes.verhoeff :as verhoeff]
            [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]))


(defn ^LocalDate parse-date [^String s] (LocalDate/parse s (DateTimeFormatter/BASIC_ISO_DATE)))

;;
(def snomed-file-pattern
  #"^(([x|z]*)(sct|der|doc|res|tls)(.*?))_(((.*?)(Concept|Relationship|Refset|Description|TextDefinition|StatedRelationship|Identifier))|(.*?))_(((.*?)((Full|Snapshot|Delta)*(Current|Draft|Review)*)(-(.*?))?)_)?((.*?)(\d*))_(.+)\.(.+)$")

(defn parse-snomed-filename
  "Parse a filename according the specifications outlined in
  https://confluence.ihtsdotools.org/display/DOCRELFMT/3.3.2+Release+File+Naming+Convention
  Each filename should match the following pattern:
  [FileType]_[ContentType]_[ContentSubType]_[CountryNamespace]_[VersionDate].[FileExtension]"
  [filename]
  (when-let [m (re-matches snomed-file-pattern filename)]
    {:filename          filename
     :file-type         (m 1)
     :status            (m 2)
     :type              (m 3)
     :format            (m 4)
     :content-type      (m 5)
     :pattern           (m 7)
     :content           (m 8)
     :content-subtype   (m 11)
     :summary           (m 12)
     :release-type      (m 14)
     :doc-status        (m 15)
     :language-code     (m 17)
     :country-namespace (m 18)
     :country-code      (m 19)
     :namespace-id      (m 20)
     :version-date      (parse-date (m 21))
     :file-extension    (m 22)}))

(def ^:private snomed-files
  "Pattern matched SNOMED distribution files and their 'type'"
  {#"sct2_Concept_Full_\S+_\S+.txt"      :info.snomed/Concept
   #"sct2_Description_Full-\S+_\S+.txt"  :info.snomed/Description
   #"sct2_Relationship_Full_\S+_\S+.txt" :info.snomed/Relationship
   })

(defn is-snomed-file? [filename]
  (first (filter #(re-find % filename) (keys snomed-files))))

(defn get-snomed-type
  "Returns the SNOMED 'type' for the filename specified."
  [filename]
  (get snomed-files (is-snomed-file? filename)))


;; The core SNOMED entities are Concept, Description and Relationship.
(defrecord Concept [^long id
                    ^LocalDate effectiveTime
                    ^boolean active
                    ^long moduleId
                    ^long definitionStatusId])

(defrecord Description [^long id
                        ^LocalDate effectiveTime
                        ^boolean active
                        ^long moduleId
                        ^long conceptId
                        ^String languageCode
                        ^long typeId ^String term ^long caseSignificanceId])

(defrecord Relationship [^long id effectiveTime
                         ^boolean active
                         ^long moduleId
                         ^long sourceId
                         ^long destinationId
                         ^long relationshipGroup
                         ^long typeId
                         ^long characteristicTypeId
                         ^long modifierId])

;; ReferenceSet support customization and enhancement of SNOMED CT content. These include representation of subsets,
;; language preferences maps for or from other code systems. There are multiple reference set types which extend
;; this structure.

;; RefSetDescriptorRefsetItem is a type of reference set that provides information about a different reference set
;; See https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.11+Reference+Set+Descriptor
;; It provides the additional structure for a given reference set.
(defrecord RefsetDescriptorRefsetItem [^String id
                                       ^LocalDate effectiveTime
                                       ^boolean active
                                       ^long moduleId
                                       ^long refsetId
                                       ^long referencedComponentId
                                       ^long attributeDescriptionId
                                       ^long attributeTypeId
                                       ^int attributeOrder])

;; SimpleReferenceSet is a simple reference set usable for defining subsets
;; See https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.1+Simple+Reference+Set
(defrecord SimpleRefsetItem [^String id
                             ^LocalDate effectiveTime
                             ^boolean active
                             ^long moduleId
                             ^long refsetId
                             ^long referencedComponentId])

;; LanguageReferenceSet is a A 900000000000506000 |Language type reference set| supporting the representation of
;; language and dialects preferences for the use of particular descriptions.
;; "The most common use case for this type of reference set is to specify the acceptable and preferred terms
;; for use within a particular country or region. However, the same type of reference set can also be used to
;; represent preferences for use of descriptions in a more specific context such as a clinical specialty,
;; organization or department."
;;
;; No more than one description of a specific description type associated with a single concept may have the
;; acceptabilityId value 900000000000548007 |Preferred|.
;; Every active concept should have one preferred synonym in each language.
;; This means that a language reference set should assign the acceptabilityId  900000000000548007 |Preferred|
;; to one  synonym (a  description with  typeId value 900000000000013009 |synonym|) associated with each concept.
;; This description is the preferred term for that concept in the specified language or dialect.
;; Any  description which is not referenced by an active row in the   reference set is regarded as unacceptable
;;(i.e. not a valid  synonym in the language or  dialect ).
;; If a description becomes unacceptable, the relevant language reference set member is inactivated by adding a new
;; row with the same id, the effectiveTime of the change and the value active=0.
;; For this reason there is no requirement for an "unacceptable" value."
;; See https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.4+Language+Reference+Set
;; - acceptabilityId is a subtype of 900000000000511003 |Acceptability| indicating whether the description is acceptable
;; or preferred for use in the specified language or dialect .
(defrecord LanguageRefsetItem [^String id
                               ^LocalDate effectiveTime
                               ^boolean active
                               ^long moduleId
                               ^long refsetId
                               ^long referencedComponentId
                               ^long acceptabilityId])

;; SimpleMapReferenceSet is a straightforward one-to-one map between SNOMED-CT concepts and another
;; coding system. This is appropriate for simple maps.
;; See https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.9+Simple+Map+Reference+Set
(defrecord SimpleMapRefsetItem [^String id
                                ^LocalDate effectiveTime
                                ^boolean active
                                ^long moduleId
                                ^long refsetId
                                ^long referencedComponentId
                                ^String mapTarget])

;;// ComplexMapReferenceSet represents a complex one-to-many map between SNOMED-CT and another
;// coding system.
;// A 447250001 |Complex map type reference set|enables representation of maps where each SNOMED
;// CT concept may map to one or more codes in a target scheme.
;// The type of reference set supports the general set of mapping data required to enable a
;// target code to be selected at run-time from a number of alternate codes. It supports
;// target code selection by accommodating the inclusion of machine readable rules and/or human readable advice.
;// An 609331003 |Extended map type reference set|adds an additional field to allow categorization of maps.
;// Unfortunately, the documentation for complex and extended reference sets is out of date.
;// https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.10+Complex+and+Extended+Map+Reference+Sets
;// A complex map includes an undocumented "map block", and an extended map contains a "category".
;//  I have quite deliberately kept both.
(defrecord ComplexMapRefsetItem [^String id
                                 ^LocalDate effectiveTime
                                 ^boolean active
                                 ^long moduleId
                                 ^long refsetId
                                 ^long referencedComponentId
                                 ^long mapGroup             ;; An Integer, grouping a set of complex map records from which one may be selected as a target code.
                                 ^long mapPriority          ;; Within a mapGroup, the mapPriority specifies the order in which complex map records should be checked
                                 ^String mapRule            ;; A machine-readable rule, (evaluating to either 'true' or 'false' at run-time) that indicates whether this map record should be selected within its mapGroup.
                                 ^String mapAdvice          ;; Human-readable advice, that may be employed by the software vendor to give an end-user advice on selection of the appropriate target code from the alternatives presented to him within the group.
                                 ^String mapTarget          ;; The target code in the target terminology, classification or code system.
                                 ^long correlationId        ;; A child of 447247004 |SNOMED CT source code to target map code correlation value|in the metadata hierarchy, identifying the correlation between the SNOMED CT concept and the target code.
                                 ^long mapBlock             ;; Only for complex map refsets: der2_iisssciRefset
                                 ^long mapCategoryId])      ;; Only for extended complex map refsets: Identifies the SNOMED CT concept in the metadata hierarchy which represents the MapCategory for the associated map member.

;; AttributeValueReferenceSet provides a way to associate arbitrary attributes with a SNOMED-CT component
;; See https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.3+Attribute+Value+Reference+Set
(defrecord AttributeValueRefsetItem [^String id
                                     ^LocalDate effectiveTime
                                     ^boolean active
                                     ^long moduleId
                                     ^long refsetId
                                     ^long referencedComponentId
                                     ^long valueId])

;; An extended concept is a denormalised representation of a single concept bringing together all useful data into one
;; convenient structure, that can then be cached and used for inference.
(defrecord ExtendedConcept [concept descriptions parent-relationships child-relationships all-parents reference-sets])



(defn parse-concept [v]
  (->Concept
    (Long/parseLong (v 0))
    (parse-date (v 1))
    (Boolean/parseBoolean (v 2))
    (Long/parseLong (v 3))
    (Long/parseLong (v 4))))

(defn parse-description [v]
  (->Description
    (Long/parseLong (v 0))
    (parse-date (v 1))
    (Boolean/parseBoolean (v 2))
    (Long/parseLong (v 3))
    (Long/parseLong (v 4))
    (v 5)
    (Long/parseLong (v 6))
    (v 7)
    (Long/parseLong (v 8))))

(defn parse-relationship [v]
  (->Relationship
    (Long/parseLong (v 0))
    (parse-date (v 1))
    (Boolean/parseBoolean (v 2))
    (Long/parseLong (v 3))                                  ;; moduleId
    (Long/parseLong (v 4))                                  ;; sourceId
    (Long/parseLong (v 5))                                  ;; destinationId
    (Long/parseLong (v 6))                                  ;; relationshipGroup
    (Long/parseLong (v 7))                                  ;; typeId
    (Long/parseLong (v 8))                                  ;; characteristicTypeId
    (Long/parseLong (v 9))))                                ;; modifierId

(defn parse-simple-refset-item [v]
  (->SimpleRefsetItem
    (v 0)                                                   ;; component id
    (parse-date (v 1))                                      ;; effective time
    (Boolean/parseBoolean (v 2))                            ;; active?
    (Long/parseLong (v 3))                                  ;; module Id
    (Long/parseLong (v 4))                                  ;; refset Id
    (Long/parseLong (v 5))))                                ;; referenced component Id

(defn parse-language-refset-item [v]
  (->LanguageRefsetItem
    (v 0)                                                   ;; component id
    (parse-date (v 1))                                      ;; effective time
    (Boolean/parseBoolean (v 2))                            ;; active?
    (Long/parseLong (v 3))                                  ;; module Id
    (Long/parseLong (v 4))                                  ;; refset Id
    (Long/parseLong (v 5))                                  ;; referenced component id
    (Long/parseLong (v 6))))

(defn parse-refset-descriptor-item [v]
  (->RefsetDescriptorRefsetItem
    (v 0)                                                   ;; component id
    (parse-date (v 1))                                      ;; effective time
    (Boolean/parseBoolean (v 2))                            ;; active?
    (Long/parseLong (v 3))                                  ;; module Id
    (Long/parseLong (v 4))                                  ;; refset Id
    (Long/parseLong (v 5))                                  ;; referenced component id
    (Long/parseLong (v 6))
    (Long/parseLong (v 7))
    (Integer/parseInt (v 8))))

(defn parse-simple-map-refset-item [v]
  (->SimpleMapRefsetItem
    (v 0)                                                   ;; component id
    (parse-date (v 1))                                      ;; effective time
    (Boolean/parseBoolean (v 2))                            ;; active?
    (Long/parseLong (v 3))                                  ;; module Id
    (Long/parseLong (v 4))                                  ;; refset Id
    (Long/parseLong (v 5))                                  ;; referenced component id
    (v 6)))                                                 ;; map target

(defn parse-complex-map-refset-item [v]
  (->ComplexMapRefsetItem
    (v 0)                                                   ;; component id
    (parse-date (v 1))                                      ;; effective time
    (Boolean/parseBoolean (v 2))                            ;; active?
    (Long/parseLong (v 3))                                  ;; module Id
    (Long/parseLong (v 4))                                  ;; refset Id
    (Long/parseLong (v 5))                                  ;; referenced component id
    (Long/parseLong (v 6))                                  ;; map group
    (Long/parseLong (v 7))                                  ;; map priority
    (v 8)                                                   ;; map rule
    (v 9)                                                   ;; map advice
    (v 10)                                                  ;; map target
    (Long/parseLong (v 11))                                 ;; correlation
    (Long/parseLong (v 12))                                 ;; map block
    (Long/parseLong (v 13))))                               ;; map category

(defn parse-attribute-value-refset-item [v]
  (->AttributeValueRefsetItem
    (v 0)                                                   ;; component id
    (parse-date (v 1))                                      ;; effective time
    (Boolean/parseBoolean (v 2))                            ;; active?
    (Long/parseLong (v 3))                                  ;; module Id
    (Long/parseLong (v 4))                                  ;; refset Id
    (Long/parseLong (v 5))                                  ;; referenced component id
    (Long/parseLong (v 6))))

(def parsers
  {:info.snomed/Concept              parse-concept
   :info.snomed/Description          parse-description
   :info.snomed/Relationship         parse-relationship

   ;; types of reference set
   :info.snomed/RefsetDescriptor     parse-refset-descriptor-item
   :info.snomed/SimpleRefset         parse-simple-refset-item
   :info.snomed/LanguageRefset       parse-language-refset-item
   :info.snomed/SimpleMapRefset      parse-simple-map-refset-item
   :info.snomed/ComplexMapRefset     parse-complex-map-refset-item
   :info.snomed/ExtendedMapRefset    parse-complex-map-refset-item
   :info.snomed/AttributeValueRefset parse-attribute-value-refset-item})

(s/def ::type parsers)
(s/def ::data seq)
(s/def ::batch (s/keys :req-un [::type ::data]))

(defn parse-batch
  "Lazily parse a batch of SNOMED entities,"
  [batch]
  (when-not (s/valid? ::batch batch)
    (throw (ex-info "invalid batch:" (s/explain-data ::batch batch))))
  (if-let [parse (get parsers (:type batch))]
    (try
      (map parse (:data batch))
      (catch Exception e (ex-info "unable to parse" (dissoc batch :data) e)))
    (throw (Exception. (str "no parser for batch type" (:type batch))))))

(defn partition-identifier
  "Return the partition from the identifier.
  The partition identifier is stored in the penultimate last two digits.
  See https://confluence.ihtsdotools.org/display/DOCRELFMT/5.5.+Partition+Identifier
  identifier: 0123456789
  meaning:    xxxxxxxppc
  pp - partition identifier
  c  - check digit."
  [id]
  (let [s (str id)
        l (.length s)]
    (when (> l 2)
      (.substring s (- l 3) (- l 1)))))

(def partitions
  "Map of partition identifiers to type of entity.
  The penultimate two digits of a SNOMED CT identifier given the partition identifier."
  {"00" :info.snomed/Concept
   "10" :info.snomed/Concept
   "01" :info.snomed/Description
   "11" :info.snomed/Description
   "02" :info.snomed/Relationship
   "12" :info.snomed/Relationship})

(defn identifier->type [id]
  "Get the type of SNOMED CT entity from the identifier specified."
  (get partitions (partition-identifier id)))


;; just an experiment with multimethods...
(defmulti valid? #(identifier->type (:id %)))
(defmethod valid? :info.snomed/Concept [m]
  (and
    (verhoeff/valid? (:id m))
    (not (nil? (:effectiveDate m)))))
(defmethod valid? :default [m] false)

(comment


  (identifier->type 24700007)
  (identifier->type 24700030)
  (verhoeff/valid? 24700002)

  (valid? {:wibble "Hi there" :flibble "Flibble"})

  (clojure.pprint/pprint (parse-batch {:type :info.snomed/Concept :data [["24700007" "20200101" "true" "0" "0"]]})))
