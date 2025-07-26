; Copyright (c) 2020-2024 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns com.eldrix.hermes.core
  "A SNOMED CT terminology service."
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.impl.ecl :as ecl]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.members :as members]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.impl.search :as search]
            [com.eldrix.hermes.impl.store :as store]
            [com.eldrix.hermes.importer :as importer]
            [com.eldrix.hermes.rf2]
            [com.eldrix.hermes.snomed :as snomed]
            [com.eldrix.hermes.verhoeff :as verhoeff])
  (:import (com.eldrix.hermes.snomed Result Description)
           (org.apache.lucene.index IndexReader)
           (org.apache.lucene.search IndexSearcher Query)
           (java.util UUID)
           (java.time.format DateTimeFormatter)
           (java.time LocalDate LocalDateTime)
           (java.io Closeable)))

(set! *warn-on-reflection* true)

(def ^:private expected-manifest
  "Defines the current expected manifest."
  {:version "lmdb/18"
   :store   "store.db"
   :search  "search.db"
   :members "members.db"})

(s/def ::svc any?)
(s/def ::non-blank-string (s/and string? (complement str/blank?)))
(s/def ::component-id (s/and pos-int? verhoeff/valid?))

(s/def ::s string?)
(s/def ::constraint string?)
(s/def ::max-hits pos-int?)
(s/def ::fuzzy (s/int-in 0 3))
(s/def ::fallback-fuzzy (s/int-in 0 3))
(s/def ::query #(instance? Query %))
(s/def ::accept-language string?)
(s/def ::language-refset-ids (s/coll-of :info.snomed.Concept/id))
(s/def ::fold (s/or :bool boolean? :lang string?))
(s/def ::show-fsn? boolean?)
(s/def ::inactive-concepts? boolean?)
(s/def ::inactive-descriptions? boolean?)
(s/def ::remove-duplicates? boolean?)
(s/def ::properties (s/map-of int? int?))
(s/def ::concept-refsets (s/coll-of :info.snomed.Concept/id))

(s/def ::search-params
  (s/keys :req-un [(or ::s ::constraint)]
          :opt-un [::max-hits ::fuzzy ::fallback-fuzzy ::query
                   ::accept-language ::language-refset-ids ::fold
                   ::show-fsn? ::inactive-concepts? ::inactive-descriptions?
                   ::remove-duplicates? ::properties ::concept-refsets]))

;; for backwards compatibility in case a client referenced the old concrete deftype
(definterface ^:deprecated Service)

(defrecord ^:private Svc
           [^Closeable store
            ^IndexReader indexReader
            ^IndexSearcher searcher
            ^IndexReader memberReader
            ^IndexSearcher memberSearcher
            localeMatchFn
            mrcmDomainFn]
  Service
  Closeable
  (close [_]
    (.close store)
    (.close indexReader)
    (.close memberReader)))

(s/fdef concept
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn concept
  "Return the concept with the specified identifier."
  [^Svc svc concept-id]
  (store/concept (.-store svc) concept-id))

(s/fdef description
  :args (s/cat :svc ::svc :description-id :info.snomed.Description/id))
(defn description
  "Return the description with the specified identifier."
  [^Svc svc description-id]
  (store/description (.-store svc) description-id))

(s/fdef relationship
  :args (s/cat :svc ::svc :relationship-id :info.snomed.Relationship/id))
(defn relationship
  "Return the relationship with the specified identifier."
  [^Svc svc relationship-id]
  (store/relationship (.-store svc) relationship-id))

(s/fdef extended-concept
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn extended-concept
  "Return an extended concept that includes the concept, and related data 
  such as descriptions, relationships, concrete values and reference set 
  memberships. See `com.eldrix.hermes.snomed/ExtendedConcept`.
  - `:concept`             : the concept
  - `:descriptions`        : a sequence of `Description` items
  - `:parentRelationships` : a map of relationship type to a set of concept ids
  - `:directParentRelationships` : as per :parentRelationships but only proximal
  - `:concreteValues`      : a sequence of `ConcreteValue` items
  - `:refsets`             : a set of reference set ids to which concept a member"
  [^Svc svc concept-id]
  (store/extended-concept (.-store svc) concept-id))

(s/fdef descriptions
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn descriptions
  "Return a sequence of descriptions for the given concept."
  [^Svc svc concept-id]
  (store/concept-descriptions (.-store svc) concept-id))

(s/fdef synonyms
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :language-refset-ids (s/? (s/coll-of :info.snomed.Concept/id))))
(defn synonyms
  "Returns a sequence of synonyms for the given concept. If language-refset-ids
  is provided, then only synonyms that are preferred or acceptable in those
  reference sets are returned."
  ([^Svc svc concept-id]
   (->> (descriptions svc concept-id)
        (filter #(= snomed/Synonym (:typeId %)))))
  ([^Svc svc concept-id language-refset-ids]
   (store/language-synonyms (.-store svc) concept-id language-refset-ids)))

(s/fdef concrete-values
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn concrete-values
  "Returns a sequence of concrete values for the given concept."
  [^Svc svc concept-id]
  (store/concrete-values (.-store svc) concept-id))

(s/fdef stream-all-concepts
  :args (s/cat :svc ::svc :ch any? :close? (s/? boolean?)))
(defn stream-all-concepts
  "Streams all concepts on the channel specified. By default, closing the
  channel when done. Blocking, so run in a background thread."
  ([^Svc svc ch] (stream-all-concepts svc ch true))
  ([^Svc svc ch close?]
   (store/stream-all-concepts (.-store svc) ch close?)))

(defn- get-n-concept-ids
  "Returns 'n' concept identifiers.
  e.g.,
  ```
    (take 50 (shuffle (get-n-concept-ids svc 50000)))
  ```"
  [svc n]
  (let [ch (a/chan 1 (comp (map :id) (partition-all n)))]
    (a/thread (stream-all-concepts svc ch))
    (let [results (a/<!! ch)]
      (a/close! ch)
      results)))

(s/fdef all-parents
  :args (s/cat :svc ::svc :concept-id-or-ids (s/or :concept :info.snomed.Concept/id :concepts (s/coll-of :info.snomed.Concept/id)) :type-id (s/? :info.snomed.Concept/id)))
(defn all-parents
  "Returns a set of concept ids of the parents of the specified concept(s). By
  design, this includes the concept(s)."
  ([^Svc svc concept-id-or-ids]
   (all-parents svc concept-id-or-ids snomed/IsA))
  ([^Svc svc concept-id-or-ids type-id]
   (store/all-parents (.-store svc) concept-id-or-ids type-id)))

(s/fdef all-children
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :type-id (s/? :info.snomed.Concept/id)))
(defn all-children
  "Returns a set of concept ids of the children of the specified concept. By
  design, this includes the concept itself."
  ([^Svc svc concept-id]
   (store/all-children (.-store svc) concept-id))
  ([^Svc svc concept-id type-id]
   (store/all-children (.-store svc) concept-id type-id)))

(s/fdef parent-relationships
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn parent-relationships
  "Returns a map of the parent relationships keyed by type."
  [^Svc svc concept-id]
  (store/parent-relationships (.-store svc) concept-id))

(s/fdef parent-relationships-expanded
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :type-id (s/? :info.snomed.Concept/id)))
(defn parent-relationships-expanded
  "Returns a map of the parent relationships, with each value a set of
  identifiers representing the targets and their transitive closure tables. This
  makes it trivial to build queries that find all concepts with, for example, a
  common finding site at any level of granularity."
  ([^Svc svc concept-id]
   (store/parent-relationships-expanded (.-store svc) concept-id))
  ([^Svc svc concept-id type-id]
   (store/parent-relationships-expanded (.-store svc) concept-id type-id)))

(s/fdef parent-relationships-of-type
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :type-concept-id :info.snomed.Concept/id))
(defn parent-relationships-of-type
  "Returns a set of identifiers representing the parent relationships of the
  specified type of the specified concept."
  [^Svc svc concept-id type-concept-id]
  (store/parent-relationships-of-type (.-store svc) concept-id type-concept-id))

(s/fdef child-relationships-of-type
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :type-concent-id :info.snomed.Concept/id))
(defn child-relationships-of-type
  "Returns a set of identifiers representing the child relationships of the
  specified type of the specified concept."
  [^Svc svc concept-id type-concept-id]
  (store/child-relationships-of-type (.-store svc) concept-id type-concept-id))

(s/fdef component-refset-items
  :args (s/cat :svc ::svc :component-id ::component-id :refset-id (s/? :info.snomed.Concept/id)))
(defn component-refset-items
  "Returns a sequence of refset items for the given component."
  ([^Svc svc component-id]
   (store/component-refset-items (.-store svc) component-id))
  ([^Svc svc component-id refset-id]
   (store/component-refset-items (.-store svc) component-id refset-id)))

(defn ^:deprecated get-reference-sets
  "DEPRECATED: use [[component-refset-items]] instead."
  [^Svc svc component-id]
  (component-refset-items svc component-id))

(s/fdef component-refset-ids
  :args (s/cat :svc ::svc :component-id ::component-id))
(defn component-refset-ids
  "Returns a collection of refset identifiers to which this concept is a member."
  [^Svc svc component-id]
  (store/component-refset-ids (.-store svc) component-id))

(s/fdef refset-item
  :args (s/cat :svc ::svc :uuid uuid?))
(defn refset-item
  "Return a specific refset item by UUID."
  [^Svc svc ^UUID uuid]
  (store/refset-item (.-store svc) uuid))

(s/fdef refset-descriptor-attribute-ids
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id))
(defn refset-descriptor-attribute-ids
  "Return a vector of attribute description concept ids for the given reference
  set."
  [^Svc svc refset-id]
  (store/refset-descriptor-attribute-ids (.-store svc) refset-id))

(s/fdef extended-refset-item
  :args (s/cat :svc ::svc :item :info.snomed/SimpleRefset))
(defn extended-refset-item
  "Merges a map of extended attributes to the specified reference set item.
  The attributes will be keyed based on information from the reference set
  descriptor information and known field names."
  [^Svc svc item]
  (store/extended-refset-item (.-store svc) item))

(defn component-refset-items-extended
  "Returns a sequence of refset items for the given component, supplemented
  with a map of extended attributes as defined by the refset descriptor"
  ([^Svc svc component-id]
   (->> (store/component-refset-items (.-store svc) component-id)
        (map #(extended-refset-item svc %))))
  ([^Svc svc component-id refset-id]
   (->> (store/component-refset-items (.-store svc) component-id refset-id)
        (map #(extended-refset-item svc %)))))

(defn active-association-targets
  "Return the active association targets for a given component."
  [^Svc svc component-id refset-id]
  (->> (component-refset-items svc component-id refset-id)
       (filter :active)
       (map :targetComponentId)))

(defn historical-associations
  "Returns all historical-type associations for the specified component.
  Result is a map, keyed by the type of association (e.g. SAME-AS) and
  a sequence of reference set items for that association. Some concepts
  may be ambiguous and therefore map to multiple targets. Annoyingly, but
  understandably, the 'moved-to' reference set does not actually reference
  the new component - but the namespace to which it has moved - so for example
  an ancient International release may have accidentally including UK specific
  concepts - so they will have been removed. It is hoped that most MOVED-TO
  concepts will also have a SAME-AS or POSSIBLY-EQUIVALENT-TO historical
  association.

  See https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.5.1+Historical+Association+Reference+Sets
  and https://confluence.ihtsdotools.org/display/editorialag/Component+Moved+Elsewhere

  Note: Unlike some other functions that deal with historical associations,
  this returns the reference set items themselves and will include both active
  *and* inactive reference set items. Other functions usually only take into
  account *active* reference set items."
  [^Svc svc component-id]
  (select-keys (group-by :refsetId (component-refset-items svc component-id))
               (store/all-children (.-store svc) snomed/HistoricalAssociationReferenceSet)))

(defn source-historical-associations
  "Returns all historical-type associations in which the specified component is
  the target. For example, searching for `24700007` will result in a map keyed
  by refset-id (e.g. `SAME-AS` reference set) and a set of concept identifiers."
  [^Svc svc component-id]
  (let [refset-ids (store/all-children (.-store svc) snomed/HistoricalAssociationReferenceSet)]
    (apply merge (map #(when-let [result (seq (store/source-association-referenced-components (.-store svc) component-id %))]
                         (hash-map % (set result))) refset-ids))))

(defn source-historical
  "Return the requested historical associations for the component of types as
  defined by refset-ids, or all association refsets if omitted."
  ([^Svc svc component-id]
   (store/source-historical (.-store svc) component-id))
  ([^Svc svc component-id refset-ids]
   (store/source-historical (.-store svc) component-id refset-ids)))

(s/fdef with-historical
  :args (s/cat :svc ::svc
               :concept-ids (s/coll-of :info.snomed.Concept/id)
               :refset-ids (s/? (s/coll-of :info.snomed.Concept/id))))
(defn with-historical
  "For a given sequence of concept identifiers, expand to include historical
  associations both backwards and forwards in time.

  For a currently active concept, this will return historic inactivated concepts
  in which it is the target. For a now inactive concept, this will return the
  active associations and their historic associations.

  By default, all types of historical associations except MoveTo and MovedFrom
  are included, but this is configurable. "
  ([^Svc svc concept-ids]
   (store/with-historical (.-store svc) concept-ids))
  ([^Svc svc concept-ids refset-ids]
   (store/with-historical (.-store svc) concept-ids refset-ids)))

(s/fdef history-profile
  :args (s/cat :svc ::svc :profile #{:HISTORY-MIN :HISTORY-MOD :HISTORY-MAX})
  :ret (s/coll-of :info.snomed.Concept/id))
(defn history-profile
  "Returns a set of reference sets matching the named profile. Use in
  conjunction with [[with-historical]]:
  ```
  (with-historical svc [24700007] (history-profile :HISTORY-MIN)
  ```
  See https://confluence.ihtsdotools.org/display/DOCECL/6.11+History+Supplements"
  [^Svc svc profile]
  (store/history-profile (.-store svc) profile))

(defn installed-reference-sets
  "Return a set of identifiers representing installed reference sets.
  Unlike simply using the SNOMED ontology to find all reference sets, this only
  returns reference sets with at least one installed member item."
  [^Svc svc]
  (store/installed-reference-sets (.-store svc)))

(s/fdef member-field
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :field ::non-blank-string :s ::non-blank-string))
(defn member-field
  "Returns a set of referenced component identifiers that are members of the
  given reference set with a matching value 's' for the 'field' specified.
  For example, to perform a reverse map from ICD-10:
  ```
  (member-field svc 447562003 \"mapTarget\" \"G35\")
  ```"
  [^Svc svc refset-id field s]
  (members/search (.-memberSearcher svc)
                  (members/q-and
                   [(members/q-refset-id refset-id) (members/q-term field s)])))

(s/fdef member-field-prefix
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :field ::non-blank-string :prefix ::non-blank-string))
(defn member-field-prefix
  "Return a set of referenced component identifiers that are members of the
  given reference set with a matching 'prefix' for the 'field' specified.
  Example:
  ```
      (member-field-prefix svc 447562003 \"mapTarget\" \"G3\")
  ```"
  [^Svc svc refset-id field prefix]
  (members/search (.-memberSearcher svc)
                  (members/q-and
                   [(members/q-refset-id refset-id) (members/q-prefix field prefix)])))

(s/fdef member-field-wildcard
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :field ::non-blank-string :s ::non-blank-string))
(defn member-field-wildcard
  "Return a set of referenced component identifiers that are members of the
  given reference set with a matching wildcard 'value' for the 'field'
  specified. Supported wildcards are *, which matches any character sequence
  (including the empty one), and ?, which matches any single character. '\\' is
  the escape character.
  Example:
  ```
      (member-field-wildcard svc 447562003 \"mapTarget\" \"G3?\")
  ```"
  [^Svc svc refset-id field s]
  (members/search (.-memberSearcher svc)
                  (members/q-and [(members/q-refset-id refset-id)
                                  (members/q-wildcard field s)])))

(s/fdef reverse-map
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :code ::non-blank-string))
(defn reverse-map
  "Returns a sequence of reference set items representing the reverse mapping
  from the reference set and mapTarget specified. It's almost always better to
  use [[member-field]] or [[member-field-prefix]] directly.

  A code in a target codesystem may map to multiple concepts. Each concept may
  map to multiple codes in that target codesystem. [[reverse-map-prefix]] returns
  only results that would meet the original criteria. This mimics the original
  behaviour of an older implementation and should be regarded as semi-deprecated.
  For more control, use [[member-field]]."
  [^Svc svc refset-id code]
  (->> (member-field svc refset-id "mapTarget" code)
       (mapcat #(store/component-refset-items (.-store svc) % refset-id))
       (filter #(= (:mapTarget %) code))))

(s/fdef reverse-map-prefix
  :args (s/cat :svc ::svc :refset-id :info.snomed.Concept/id :prefix ::non-blank-string))
(defn reverse-map-prefix
  "Returns a sequence of reference set items representing the reverse mapping
  from the reference set and mapTarget. It is almost always better to use
  [[member-field]] or [[member-field-prefix]] directly.

  A code in a target codesystem may map to multiple concepts. Each concept may
  map to multiple codes in that target codesystem. `reverse-map-prefix` returns
  only results that would meet the original criteria. This mimics the original
  behaviour of an older implementation and should be regarded as semi-deprecated.
  For more control, use [[member-field-prefix]]."
  [^Svc svc refset-id prefix]
  (->> (member-field-prefix svc refset-id "mapTarget" prefix)
       (mapcat #(store/component-refset-items (.-store svc) % refset-id))
       (filter #(.startsWith ^String (:mapTarget %) prefix))))

(s/fdef match-locale
  :args (s/cat :svc ::svc :language-range (s/? (s/nilable ::non-blank-string)) :fallback? (s/? boolean?)))
(defn match-locale
  "Return an ordered sequence of refset ids that are the best match for the
  required language range, or the database default.

  `language-range` should be a single string containing a list of
  comma-separated language ranges or a list of language ranges in the form of
  the \"Accept-Language \" header defined in RFC3066.

  If the installed language reference sets are not matched by any of the
  languages in the list, and `fallback?` is true, then the database default
  locale will be used. With no fallback, no reference set identifiers will be
  returned, which may mean that locale-specific functions may return nil."
  ([^Svc svc]
   ((.-localeMatchFn svc) nil true))
  ([^Svc svc language-range]
   ((.-localeMatchFn svc) language-range))
  ([^Svc svc language-range fallback?]
   ((.-localeMatchFn svc) language-range fallback?)))

(s/fdef preferred-synonym*
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :language-refset-ids (s/coll-of :info.snomed.Concept/id)))
(defn preferred-synonym*
  "Given an ordered sequence of preferred language reference set ids, return
  the preferred synonym for the concept specified."
  ^Description [^Svc svc concept-id language-refset-ids]
  (store/preferred-synonym (.-store svc) concept-id language-refset-ids))

(s/fdef preferred-synonym
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id
               :language-range (s/? (s/nilable string?))
               :fallback? (s/? boolean?)))
(defn preferred-synonym
  "Return the preferred synonym for the concept based on the language
  preferences specified.

  Use [[match-locale]] and then repeated calls to [[preferred-synonym*]] if
  preferred synonyms of a number of concepts are required (e.g. in a map/reduce etc).

  Parameters:

  - `svc`            : hermes service
  - `concept-id`     : concept identifier
  - `language-range` : a single string containing a list of comma-separated
                     language ranges or a list of language ranges in the form of
                     the \"Accept-Language \" header defined in RFC3066.
  - `fallback?`      : whether to fall back to database default language.

  When `fallback?` is true, there will *always* be a result for every concept."
  ([^Svc svc concept-id]
   (preferred-synonym* svc concept-id (match-locale svc nil true)))
  ([^Svc svc concept-id language-range]
   (preferred-synonym svc concept-id language-range false))
  ([^Svc svc concept-id language-range fallback?]
   (when-let [lang-refset-ids (seq (match-locale svc language-range fallback?))]
     (preferred-synonym* svc concept-id lang-refset-ids))))

(s/fdef fully-specified-name
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :language-range (s/? (s/nilable ::non-blank-string))))
(defn fully-specified-name
  "Return the fully specified name for the concept specified. If no language
  preferences are provided the database default locale will be used."
  ([^Svc svc concept-id]
   (fully-specified-name svc concept-id nil))
  ([^Svc svc concept-id language-range]
   (store/preferred-fully-specified-name (.-store svc) concept-id (match-locale svc language-range true))))

(defn release-information
  "Returns descriptions representing the installed distributions.
  Ordering will be by date except that the description for the 'core' module
  will always be first.
  See https://confluence.ihtsdotools.org/display/DOCTIG/4.1.+Root+and+top-level+Concepts"
  [^Svc svc]
  (store/release-information (.-store svc)))

(defn subsumed-by?
  "Is `concept-id` subsumed by `subsumer-concept-id`?"
  [^Svc svc concept-id subsumer-concept-id]
  (store/is-a? (.-store svc) concept-id subsumer-concept-id))

(s/fdef are-any?
  :args (s/cat :svc ::svc :concept-ids (s/coll-of :info.snomed.Concept/id) :parent-ids (s/coll-of :info.snomed.Concept/id)))
(defn are-any?
  "Are any of the concept-ids subsumed by any of the parent-ids?

  Checks the is-a relationships of the concepts in question against the set of
  parent identifiers."
  [^Svc svc concept-ids parent-ids]
  (some (set parent-ids) (all-parents svc concept-ids)))

(defn parse-expression [^Svc _svc s]
  (scg/parse s))

(defn ^:private make-search-params
  [^Svc svc {:keys [s query constraint accept-language language-refset-ids] :as params}]
  (let [lang-refset-ids (or (seq language-refset-ids) (match-locale svc accept-language true))]
    (cond-> (assoc params :language-refset-ids lang-refset-ids)
      ;; if there is a string, normalize it
      s
      (update :s #(lang/fold (first lang-refset-ids) %))

      ;; if there is a custom query AND a constraint, combine them
      (and query constraint)
      (assoc :query (search/q-and [query (ecl/parse svc constraint)]))

      ;; if there is a constraint, parse it into a Lucene query
      (and (not query) constraint)
      (assoc :query (ecl/parse svc constraint)))))

(s/fdef search
  :args (s/cat :svc ::svc :params ::search-params)
  :ret (s/coll-of ::result))
(defn search
  "Perform a search optimised for autocompletion against the index.

  Parameters:
  - svc    : hermes service
  - params : a map of search parameters, which include:

  | keyword               | description                                       |
  |-----------------------|---------------------------------------------------|
  | `:s`                  | search string                                     |
  | `:max-hits`           | maximum hits (see note below)                     |
  | `:constraint`         | SNOMED ECL constraint                             |
  | `:fuzzy`              | fuzziness (0-2, default 0)                        |
  | `:fallback-fuzzy`     | if no results, try fuzzy search (0-2, default 0). |
  | `:remove-duplicates?` | remove duplicate results (default, false)         |
  | `:accept-language`    | locales for preferred synonyms in results         |
  | `:language-refset-ids | languages for preferred synonyms in results       |

  If `max-hits` is omitted, search will return unlimited *unsorted* results.

  Example: to search for neurologist as an occupation ('IS-A' '14679004')
  ```
   (search svc {:s \"neurologist\" :constraint \"<14679004\"})
  ```
  For autocompletion, it is recommended to use `fuzzy=0`, and `fallback-fuzzy=2`.
  
  There are some lower-level search parameters available, but it is usually
  more appropriate to use a SNOMED ECL constraint instead of these.

  | keyword                   | description                                    |
  |---------------------------|------------------------------------------------|
  | `:query`                  | additional Lucene `Query` to apply             |
  | `:show-fsn?`              | show FSNs in results?                          |
  | `:inactive-concepts?`     | search descriptions of inactive concepts?      |
  | `:inactive-descriptions?` | search inactive descriptions?                  |
  | `:properties`             | a map of properties and their possible values. |
  | `:concept-refsets`        | a collection of refset ids to limit search     |

  By default, `:show-fsn?` and `:inactive-concepts?` are `false`, while
  `:inactive-descriptions?` is `true`.

  The properties map contains keys for a property and then either a single
  identifier or vector of identifiers to limit search.
  For example
  ```
   (search svc {:s \"neurologist\" :properties {snomed/IsA [14679004]}})
  ```
  However, concrete values are not supported, so to search using concrete values
  use a SNOMED ECL constraint instead.

  A FSN is a fully-specified name and should generally be left out of search."
  [^Svc svc params]
  (search/do-search (.-searcher svc) (make-search-params svc params)))

(defn ranked-search
  "A version of [[search]] that performs a ranked search in which results are
  returned that best match the tokens specified. Unlike the operation of 
  [[search]], in which no results would be returned if there is a token that
  matches no results, [[ranked-search]] simply scores from best to worst.
  This function is most useful for finding best matches, while [[search]]
  is best used for autocompletion. Unlike [[search]], this function returns
  no results if there is no search string, or no tokens in the search string."
  [^Svc svc params]
  (search/do-ranked-search (.-searcher svc) (make-search-params svc params)))

(defn- concept-id->result
  [^Svc svc concept-id language-refset-ids]
  (when-let [ps (preferred-synonym* svc concept-id language-refset-ids)]
    (snomed/->Result (.-id ps) concept-id (.-term ps) (.-term ps))))

(s/fdef search-concept-ids
  :args (s/cat :svc ::svc, :options (s/? (s/keys :opt-un [::accept-language ::language-refset-ids]))
               :concept-ids (s/? (s/coll-of :info.snomed.Concept/id))))
(defn search-concept-ids
  "Return search results containing the preferred descriptions of the concepts
  specified. Returns a transducer if no concept ids are specified. If a
  preferred description cannot be found for the locale specified, `nil` will be
  returned in the results unless `fallback?` is true, in which case the default
  fallback locale will be used.

  Parameters:
  |- svc            : service
  |- options        : a map
  |  |- :language-refset-ids
  |  |      A collection of reference set ids for the preferred language(s).
  |- |- :accept-language
  |  |      A single string containing a list of comma-separated language ranges
  |  |      or a list of language ranges in the form of the \"Accept-Language\"
  |  |      header as per RFC3066
  |  |- :fallback? (default true)
  |  |      Fallback to database default fallback locale if explicit language
  |  |      preference not available in installed reference sets
  |- concept-ids    : a collection of concept identifiers.

  For backwards compatibility, `:language-range` can be used instead of
  `:accept-language`."
  ([^Svc svc]
   (search-concept-ids svc {}))
  ([^Svc svc {:keys [language-refset-ids accept-language language-range fallback?] :or {fallback? true}}]
   (let [refset-ids (or language-refset-ids (match-locale svc (or accept-language language-range) fallback?))]
     (map #(concept-id->result svc % refset-ids))))
  ([^Svc svc {:keys [language-refset-ids accept-language language-range fallback?] :or {fallback? true}} concept-ids]
   (let [refset-ids (or language-refset-ids (match-locale svc (or accept-language language-range) fallback?))]
     (map #(concept-id->result svc % refset-ids) concept-ids))))

(s/fdef expand-ecl
  :args (s/cat :svc ::svc, :ecl ::non-blank-string, :max-hits (s/? int?))
  :ret (s/coll-of ::result))
(defn expand-ecl
  "Expand an ECL expression. Results are ordered iff max-hits is specified.
  It's usually more appropriate to use [[expand-ecl*]]."
  ([^Svc svc ecl]
   (let [q1 (ecl/parse svc ecl)
         q2 (search/q-synonym)]
     (search/do-query-for-results (.-searcher svc) (search/q-and [q1 q2]) (match-locale svc))))
  ([^Svc svc ecl max-hits]
   (let [q1 (ecl/parse svc ecl)
         q2 (search/q-synonym)]
     (search/do-query-for-results (.-searcher svc) (search/q-and [q1 q2]) (match-locale svc) max-hits))))

(s/fdef expand-ecl*
  :args (s/cat :svc ::svc :ecl ::non-blank-string :language-refset-ids (s/coll-of :info.snomed.Concept/id))
  :ret (s/coll-of ::result))
(defn expand-ecl*
  "Expand an ECL expression returning only preferred descriptions for the language reference set(s) specified.
  Use [[match-locale]] to determine a set of language reference set ids for a given 'Accept-Language' language range
  as defined in RFC3066, or manually specify language reference set ids if required. In order to return a single
  term per concept, use a single language reference set. Also see [[expand-ecl]] and [[expand-ecl-historic]]."
  [^Svc svc ecl language-refset-ids]
  (let [q1 (ecl/parse svc ecl)
        q2 (search/q-synonym)
        q3 (search/q-acceptabilityAny :preferred-in language-refset-ids)]
    (search/do-query-for-results (.-searcher svc) (search/q-and [q1 q2 q3]) nil)))

(s/fdef intersect-ecl
  :args (s/cat :svc ::svc :concept-ids (s/coll-of :info.snomed.Concept/id) :ecl ::non-blank-string))
(defn intersect-ecl
  "Returns the subset of the concept identifiers that satisfy the SNOMED ECL
  expression. Use [[intersect-ecl-fn]] if the same ECL expression will be
  used repeatedly."
  [^Svc svc concept-ids ^String ecl]
  (let [q1 (search/q-concept-ids concept-ids)
        q2 (ecl/parse svc ecl)]
    (search/do-query-for-concept-ids (.-searcher svc) (search/q-and [q1 q2]))))

(s/fdef intersect-ecl-fn
  :args (s/cat :svc ::svc :ecl ::non-blank-string))
(defn intersect-ecl-fn
  "Return a function that can return the subset the specified concept
  identifiers that satisfy the SNOMED ECL expression."
  [^Svc svc ^String ecl]
  (let [q2 (ecl/parse svc ecl)]
    (fn [concept-ids]
      (let [q1 (search/q-concept-ids concept-ids)]
        (search/do-query-for-concept-ids (.-searcher svc) (search/q-and [q1 q2]))))))

(s/fdef valid-ecl?
  :args (s/cat :s string?))
(defn valid-ecl?
  "Is the ECL valid?
  This does not attempt to expand the ECL, but simply checks that it is valid
  ECL according to the grammar."
  [s]
  (ecl/valid? s))

(s/fdef ecl-contains?
  :args (s/cat :svc ::svc, :concept-ids (s/coll-of :info.snomed.Concept/id)
               :ecl ::non-blank-string))
(defn ^:deprecated ecl-contains?
  "DEPRECATED: use [[intersect-ecl]] instead.

  Do any of the concept-ids satisfy the constraint expression specified?
  This is an alternative to expanding the valueset and then checking membership."
  [^Svc svc concept-ids ^String ecl]
  (seq (intersect-ecl svc concept-ids ecl)))

(s/fdef expand-ecl-historic
  :args (s/cat :svc ::svc, :ecl ::non-blank-string)
  :ret (s/coll-of ::result))
(defn expand-ecl-historic
  "Expand an ECL expression and include historic associations of the results,
  so that the results will include now inactive/deprecated concept identifiers."
  [^Svc svc ^String ecl]
  (let [base-query (search/q-and [(ecl/parse svc ecl) (search/q-synonym)])
        base-concept-ids (search/do-query-for-concept-ids (.-searcher svc) base-query)
        historic-concept-ids (into #{} (mapcat #(source-historical svc %)) base-concept-ids)
        historic-query (search/q-concept-ids historic-concept-ids)
        query (search/q-and [(search/q-or [base-query historic-query]) (search/q-synonym)])]
    (search/do-query-for-results (.-searcher svc) query (match-locale svc))))

(s/def ::transitive-synonym-params
  (s/or :by-search map? :by-ecl string? :by-concept-ids coll?))

(s/fdef transitive-synonyms
  :args (s/cat :svc ::svc
               :params (s/alt :by-ecl string? :by-search ::search-params ::by-concept-ids (s/coll-of :info.snomed.Concept/id))))
(defn transitive-synonyms
  "Returns all synonyms of the specified concepts, including those of its
  descendants.

  Parameters:
  - svc    : hermes service
  - params : search parameters to select concepts; one of:

          - a map        : search parameters as per [[search]]
          - a string     : a string containing an ECL expression
          - a collection : a collection of concept identifiers"
  [^Svc svc params]
  (let [[op v] (s/conform ::transitive-synonym-params params)]
    (if-not op
      (throw (ex-info "invalid parameters:" (s/explain-data ::transitive-synonym-params params)))
      (let [concept-ids (case op
                          :by-search (map :conceptId (search svc v))
                          :by-ecl (map #(.-conceptId ^Result %) (expand-ecl svc v))
                          :by-concept-ids v)]
        (mapcat (partial store/transitive-synonyms (.-store svc)) concept-ids)))))

(s/fdef refset-members
  :args (s/cat :svc ::svc :refset-ids (s/+ :info.snomed.Concept/id)))
(defn refset-members
  "Return a set of identifiers for the members of the given refset(s).

  Parameters:
  - refset-id  - SNOMED identifier representing the reference set."
  [^Svc svc refset-id & more]
  (members/search (.-memberSearcher svc)
                  (if more (members/q-refset-ids (into #{refset-id} more))
                      (members/q-refset-id refset-id))))

(s/fdef map-into
  :args (s/cat :svc ::svc
               :source-concept-ids (s/coll-of :info.snomed.Concept/id)
               :target (s/alt :ecl string?
                              :refset-id :info.snomed.Concept/id
                              :concepts (s/coll-of :info.snomed.Concept/id))))
(defn map-into
  "Map the source-concept-ids into the target, usually in order to reduce the
  dimensionality of the dataset. Returns a sequence of sets of identifiers that
  are in the 'target'. The target can be a collection of identifiers, an ECL
  expression or, for convenience, an identifier representing a reference set.
  The latter two will be expanded into a set of identifiers.

  Parameters:

  - `svc`                : hermes service
  - `source-concept-ids` : a collection of concept identifiers
  - `target`             : one of:
                           - a collection of concept identifiers
                           - an ECL expression
                           - a refset identifier

  If a source concept id resolves to multiple concepts in the target collection,
  then a collection will be returned such that no member of the subset is
  subsumed by another member.

  Callers will usually need to map any source concept identifiers into their
  modern active replacements, if they are now inactive, as inactive source
  concepts do not have relationships that can be used to perform `map-into`.

  The use of `map-into` is in reducing the granularity of user-entered
  data to aid analytics. For example, rather than limiting data entry to the UK
  emergency reference set, a set of commonly seen diagnoses in emergency
  departments in the UK, we can allow clinicians to enter highly specific,
  granular terms, and map to the contents of that reference set as required
  for analytics and reporting.

  For example, '991411000000109' is the UK emergency unit diagnosis refset:
  ```
  (map-into svc [24700007 763794005] 991411000000109)
       =>  (#{24700007} #{45170000})
  ```
  As multiple sclerosis (24700007) is in the reference set, it is returned.
  However, LGI1-associated limbic encephalitis (763794005) is not in the
  reference set, the best terms are returned (\"Encephalitis\" - 45170000).

  This can be used to do simple classification tasks - such as determining
  the broad types of illness. For example, here we use ECL to define a set to
  include 'neurological disease', 'respiratory disease' and
  'infectious disease':

  ```
  (map-into svc [24700007 763794005 95883001] \"118940003 OR 50043002 OR 40733004\")
      => (#{118940003} #{118940003} #{40733004 118940003})
  ```
  Both multiple sclerosis and LGI-1 encephalitis are types of neurological
  disease (118940003). However, 'Bacterial meningitis' (95883001) is mapped to
  both 'neurological disease' (118940003) AND 'infectious disease' (40733004)."
  [^Svc svc source-concept-ids target]
  (let [target-concept-ids
        (cond
          ;; a string should be an ECL expression -> expand to a set of identifiers
          (string? target) (into #{} (map :conceptId) (expand-ecl svc target))
          ;; a collection should be a collection of identifiers -> make a set
          (coll? target) (set target)
          ;; a single number will be a refset identifier -> get its members
          (number? target) (refset-members svc target))]
    (->> source-concept-ids
         (map #(set/intersection (all-parents svc %) target-concept-ids))
         (map #(store/leaves (.-store svc) %)))))

(s/fdef map-concept-into
  :args (s/cat :svc ::svc
               :concept-id :info.snomed.Concept/id
               :target (s/alt :ecl string?
                              :refset-id :info.snomed.Concept/id
                              :concepts (s/coll-of :info.snomed.Concept/id))))
(defn map-concept-into
  "Returns a set of concept identifiers representing the result of mapping a
  single concept into the target. For efficiency, it is almost always
  better to use [[map-into]] with a collection of identifiers, as the `target`
  will be then determined only once for all identifiers to be processed."
  [svc concept-id target]
  (first (map-into svc [concept-id] target)))

(defn ^:deprecated map-features
  "DEPRECATED: Use [[map-into]] instead."
  [^Svc svc source-concept-ids target]
  (map-into svc source-concept-ids target))

(s/fdef module-dependencies*
  :args (s/cat :items (s/coll-of :info.snomed/ModuleDependencyRefset)))
(defn module-dependencies*
  "Given a collection of module dependency reference set items, return
  transformed as `:source`, `:target`, `:actual` and `:valid` representing
  module dependencies. Returns a sequence of:

  - `:source` : source of the dependency (a map of :moduleId, :version)
  - `:target` : target on which the source depends (a map of :moduleId, :version)
  - `:actual` : actual version; may be nil
  - `:valid`  : is this dependency satisfied and consistent?

  Versions are represented as `java.time.LocalDate`.
  Dependencies are not transitive as per https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.4.2+Module+Dependency+Reference+Set"
  [items]
  (let [installed (reduce (fn [acc {:keys [moduleId sourceEffectiveTime]}] ;; as there may be multiple module dependency items with different dates, we use the latest one
                            (update acc moduleId #(if (or (not %) (.isAfter ^LocalDate sourceEffectiveTime %)) sourceEffectiveTime %))) {} items)
        installed' (assoc installed snomed/ModelModule (installed snomed/CoreModule))] ;; impute 'Model' module version based on 'Core' module version
    (->> items
         (map (fn [{:keys [moduleId sourceEffectiveTime referencedComponentId targetEffectiveTime]}]
                (let [actual (installed' referencedComponentId)]
                  (hash-map :source {:moduleId moduleId :version sourceEffectiveTime}
                            :target {:moduleId referencedComponentId :version targetEffectiveTime}
                            :actual actual
                            :valid (and actual (or (.isEqual ^LocalDate actual targetEffectiveTime)
                                                   (.isAfter ^LocalDate actual targetEffectiveTime))))))))))

(s/fdef module-dependencies
  :args (s/cat :svc ::svc))
(defn module-dependencies
  "Returns a sequence of module dependencies, each item a map containing:

  - `:source` : source of the dependency (a map of :moduleId, :version)
  - `:target` : target on which the source depends (a map of :moduleId, :version)
  - `:actual` : actual version; may be nil
  - `:valid`  : is this dependency satisfied and consistent?

  Versions are represented as `java.time.LocalDate`.
  Dependencies are not transitive as per https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.4.2+Module+Dependency+Reference+Set"
  [^Svc svc]
  (let [items (->> (refset-members svc snomed/ModuleDependencyReferenceSet)
                   (mapcat #(component-refset-items svc % snomed/ModuleDependencyReferenceSet)))]
    (module-dependencies* items)))

(s/fdef module-dependency-problems
  :args (s/cat :svc ::svc))
(defn module-dependency-problems
  "Returns a human-readable report of invalid dependencies and version mismatches."
  [svc]
  (->> (module-dependencies svc)
       (remove :valid)
       (map (fn [{:keys [source target] :as dep}]
              (-> dep
                  (dissoc :valid)
                  (assoc-in [:source :nm] (:term (preferred-synonym svc (:moduleId source))))
                  (assoc-in [:target :nm] (:term (preferred-synonym svc (:moduleId target)))))))
       (sort-by #(get-in % [:source :module]))))

;;
(defn- historical-association-counts
  "Returns counts of all historical association counts.

  Example:
  ```
  (-> (historical-association-counts svc)
      (update-keys #(->> (str/split (:term (preferred-synonym svc %)) #\" \")
                         (remove #{\"association\" \"reference\" \"set\"})
                         (str/join \"-\") str/lower-case keyword))
      (update-vals sort)
      sort)

  =>

  ([:alternative ([1 310] [2 263] [3 16] [4 17])]
   [:had-actual-medicinal-product ([1 3836])]
   [:had-virtual-medicinal-product ([1 1006])]
   [:moved-from ([1 9975] [2 61] [3 3])]
   [:moved-to ([1 21430] [2 17])]
   [:partially-equivalent-to ([2 17] [3 5])]
   [:possibly-equivalent-to ([1 23967] [2 11441] [3 2084] [4 590] [5 166] [6 69] [7 32] [8 10] [9 7] [10 9] [11 4] [12 3] [13 32] [14 3] [15 1] [16 2])]
   [:possibly-replaced-by ([2 19] [3 6])]
   [:replaced-by ([1 21621] [2 50])]
   [:same-as ([1 115391] [2 2880] [3 90] [4 18])]
   [:was-a ([1 43882] [2 4913] [3 791] [4 286] [5 5] [7 1])])
  ```
  In this example, from the March 2023 release of the UK edition of SNOMED CT,
  we see that there are 50 inactive concepts with two 'REPLACED BY' historical
  associations."
  [^Svc svc]
  (let [ch (a/chan 100 (remove :active))]
    (a/thread (store/stream-all-concepts (.-store svc) ch))
    (loop [result {}]
      (let [c (a/<!! ch)]
        (if-not c
          result
          (recur (reduce-kv (fn [m k v]
                              (update-in m [k (count (filter :active v))] (fnil inc 0)))
                            result
                            (historical-associations svc (:id c)))))))))

(s/fdef example-historical-associations
  :args (s/cat :svc ::svc :type-id :info.snomed.Concept/id :n pos-int?))
(defn- example-historical-associations
  "Returns 'n' examples of the type of historical association specified."
  [^Svc svc type-id n]
  (let [ch (a/chan 100 (remove :active))]
    (a/thread (store/stream-all-concepts (.-store svc) ch))
    (loop [i 0
           result {}]
      (let [c (a/<!! ch)]
        (if-not (and c (< i n))
          result
          (let [assocs (historical-associations svc (:id c))
                append? (contains? assocs type-id)]
            (recur (if append? (inc i) i)
                   (if append? (assoc result (:id c) assocs) result))))))))

(s/fdef paths-to-root
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn paths-to-root
  "Return a sequence of paths from the concept to root node.
  Each path is a sequence of identifiers, starting with the concept itself
  and ending with the root node.

  e.g.

  ```
  (paths-to-root svc 24700007)
  =>
  ((24700007 6118003 80690008 23853001 118940003 362965005 64572001 404684003 138875005)
   (24700007 6118003 80690008 23853001 246556002 404684003 138875005)
   (24700007 6118003 80690008 362975008 64572001 404684003 138875005)
   (24700007 39367000 23853001 118940003 362965005 64572001 404684003 138875005)
   (24700007 39367000 23853001 246556002 404684003 138875005)
   (24700007 39367000 363171009 362965005 64572001 404684003 138875005)
   (24700007 39367000 363171009 363170005 128139000 64572001 404684003 138875005)
   (24700007 414029004 64572001 404684003 138875005))
  ```"
  [^Svc svc concept-id]
  (store/paths-to-root (.-store svc) concept-id))

(defn some-indexed
  "Returns index and first logical true value of (pred x) in coll, or nil.

  e.g.
  ```
  (some-indexed #{64572001} '(385093006 233604007 205237003 363169009 363170005 123946008 64572001 404684003 138875005))
  ```
  returns: `[6 664572001]`"
  [pred coll]
  (first (keep-indexed (fn [idx v] (when (pred v) [idx v])) coll)))

(defn ^:private mrcm-refset-ids
  "Return a set of MRCM reference ids, optionally of the specified type."
  ([{:keys [store memberSearcher]}]
   (into #{}
         (comp (mapcat #(store/component-refset-items store % snomed/MRCMModuleScopeReferenceSet))
               (map :mrcmRuleRefsetId))
         (members/search memberSearcher (members/q-refset-id snomed/MRCMModuleScopeReferenceSet))))
  ([{:keys [store memberSearcher]} type-id]
   (into #{}
         (comp (mapcat #(store/component-refset-items store % snomed/MRCMModuleScopeReferenceSet))
               (map :mrcmRuleRefsetId)
               (filter #(store/is-a? store % type-id)))
         (members/search memberSearcher (members/q-refset-id snomed/MRCMModuleScopeReferenceSet)))))

(s/fdef mrcm-domains
  :args (s/cat :svc ::svc)
  :ret (s/coll-of :info.snomed/MRCMDomainRefset))
(defn mrcm-domains
  "Return a sequence of MRCM Domain reference set items for the given service.
  Each item essentially represents an 'MRCM domain'. "
  [{:keys [store memberSearcher] :as svc}]                  ;; this deliberately accepts a map as it will usually be used *before* a service is fully initialised
  (let [refset-ids (mrcm-refset-ids svc snomed/MRCMDomainReferenceSet)]
    (->> (members/search memberSearcher (members/q-refset-ids refset-ids)) ;; all members of the given reference sets
         (mapcat #(mapcat (fn [refset-id] (store/component-refset-items store % refset-id)) refset-ids)))))

(defn ^:private mrcm-domain-fn
  "Create a function that can provide a set of domains for any given concept."
  [{:keys [searcher] :as svc}]                              ;; this deliberately accepts a map as it will usually be used *before* a service is fully initialised
  (let [domains (->> (mrcm-domains svc)                     ;; for each domain, create a constraint query
                     (reduce (fn [acc v] (assoc acc (:referencedComponentId v) (ecl/parse svc (:domainConstraint v)))) {}))]
    (fn [concept-id]
      (let [q1 (search/q-self concept-id)]
        (reduce-kv (fn [acc domain-id q2]
                     (if (seq (search/do-query-for-concept-ids searcher (search/q-and [q1 q2])))
                       (conj acc domain-id) acc)) #{} domains)))))

(defn ^:private concept-domains
  "Return a set of concept ids representing the domains for the given concept."
  [^Svc svc concept-id]
  ((.-mrcmDomainFn svc) concept-id))

(defn ^:private attribute-domain
  "Returns a single MRCMAttributeDomainRefsetItem for the attribute specified
  in the context of the concept specified.

  Some attributes can be used in multiple domains, and so there may be multiple
  reference set items for the same attribute. Iff there are multiple items,
  the domain of the concept is determined and used to return the correct item
  in context."
  [svc concept-id attribute-concept-id]
  (let [items (->> (mrcm-refset-ids svc snomed/MRCMAttributeDomainReferenceSet)
                   (mapcat #(component-refset-items svc attribute-concept-id %))
                   (filter :active))]
    (case (count items)
      0 nil
      1 (first items)
      (let [domain-ids (concept-domains svc concept-id)]    ;; only lookup concept's domains if we really need to
        (->> items
             (filter #(domain-ids (:domainId %)))
             (sort-by :effectiveTime)
             last)))))

(defn ^:private attribute-range
  "Return a valid attribute range for the concept specified."
  [{:keys [store] :as svc} concept-id]
  (->> (mrcm-refset-ids svc snomed/MRCMAttributeRangeReferenceSet)
       (mapcat #(store/component-refset-items store concept-id %))
       (filter :active)
       (sort-by :effectiveTime)
       last))

(defn ^:private -fix-property-values
  "Given a map of attributes and values (props), unwrap any values for
  attributes that have a cardinality of 0..1 or 1..1 leaving others as a set of
  values. If `only-concrete` is true, only concrete values are unwrapped."
  [svc concept-id group-id props only-concrete]
  (let [kw (if (zero? group-id) :attributeCardinality :attributeInGroupCardinality)]
    (reduce-kv
     (fn [acc k v]
       (assoc acc k
              (let [ad (when (= 1 (count v)) (attribute-domain svc concept-id k))]
                (if (and ad
                         (or (not only-concrete) (string? (first v)))
                         (#{"0..1" "1..1"} (kw ad))    ;; convert to single if cardinality permits
                         (= snomed/MandatoryConceptModelRule (:ruleStrengthId ad))) (first v) v)))) {} props)))

(s/def ::expand boolean?)
(s/fdef properties
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id
               :opts (s/? (s/nilable (s/keys :opt-un [::expand])))))
(defn properties
  "Returns a concept's properties, including concrete values. Ungrouped
  properties are returned under key '0', with other groups returned with
  non-zero keys. There is no other intrinsic meaning to the group identifier.

  Attribute values will be returned as a set of values optionally expanded to
  include the transitive relationships. If the value for the attribute is a
  concrete value or the SNOMED machine-readable concept model (MRCM) for the
  attribute in the context of the concept's domain states that the cardinality
  of the property is 0..1 or 1..1 and the values are not expanded to include
  transitive dependencies, the value will be unwrapped to a single value.

  e.g. for lamotrigine:
  ```
  (properties svc (properties svc 1231295007))
  =>
  {0 {116680003 #{779653004}, 411116001 385060002, 763032000 732936001,
      766939001 #{773862006}, 1142139005 \"#1\"},
   1 {732943007 387562000, 732945000 258684004, 732947008 732936001,
      762949000 387562000, 1142135004 \"#250\", 1142136003 \"#1\"}}
  ```
  See https://confluence.ihtsdotools.org/display/DOCRELFMT/4.2.3+Relationship+File+Specification
  >  \"The relationshipGroup field is used to group relationships with the same
  >  sourceId field into one or more logical sets. A relationship with a
  >  relationshipGroup field value of '0' is considered not to be grouped. All
  >  relationships with the same sourceId and non-zero relationshipGroup are
  >  considered to be logically grouped.\""
  ([^Svc svc concept-id] (properties svc concept-id nil))
  ([^Svc svc concept-id {:keys [expand]}]
   (reduce-kv (fn [acc group-id props]
                (assoc acc group-id (-fix-property-values svc concept-id group-id props expand)))
              {} (if expand (store/properties-expanded (.-store svc) concept-id)
                     (store/properties (.-store svc) concept-id)))))

(s/def ::fmt #{:map-id-syn :vec-id-syn :str-id-syn :syn :id})
(s/def ::key-fmt ::fmt)
(s/def ::value-fmt ::fmt)
(s/def ::language-range string?)
(s/fdef pprint-properties
  :args (s/cat :svc ::svc, :props map?, :opts (s/? (s/nilable (s/keys :opt-un [::key-fmt ::value-fmt ::fmt ::language-range])))))
(defn pprint-properties
  "Pretty print properties. Keys and values can be formatted using `fmt` or
  separately using `key-fmt` and `value-fmt`. `language-range` should be a
  language range such as \"en-GB\". If `language-range` is omitted, or does not
  match any installed reference sets, the database default language range will
  be used instead.

  Valid formats are:

  | format        | description                  |
  |---------------|------------------------------|
  | `:map-id-syn` | map of id to synonym         |
  | `:vec-id-syn` | vector of id and synonym     |
  | `:str-id-syn` | id and synonym as a string   |
  | `:syn`        | synonym                      |
  | `:id`         | id                           |

  For example,

  ```
  (pprint-properties svc (properties svc 1231295007) {:fmt :vec-id-syn})
  =>
  {0 {[116680003 \"Is a\"] [[779653004 \"Lamotrigine only product in oral dose form\"]],
      [411116001 \"Has manufactured dose form\"] [385060002 \"Prolonged-release oral tablet\"],
      [763032000 \"Has unit of presentation\"] [732936001 \"Tablet\"],
      [766939001 \"Plays role\"] [[773862006 \"Anticonvulsant therapeutic role\"]],
      [1142139005 \"Count of base of active ingredient\"] \"#1\"},
   1 {[732943007 \"Has BoSS\"] [387562000 \"Lamotrigine\"],
      [732945000 \"Has presentation strength numerator unit\"] [258684004 \"mg\"],
      [732947008 \"Has presentation strength denominator unit\"] [732936001 \"Tablet\"],
      [762949000 \"Has precise active ingredient\"] [387562000 \"Lamotrigine\"],
      [1142135004 \"Has presentation strength numerator value\"] \"#250\",
      [1142136003 \"Has presentation strength denominator value\"] \"#1\"}}
  ```
  "
  ([svc props]
   (pprint-properties svc props {}))
  ([svc props {:keys [key-fmt value-fmt fmt language-range]}]
   (let [lang-refset-ids (match-locale svc language-range true)
         ps (fn [concept-id] (:term (preferred-synonym* svc concept-id lang-refset-ids)))
         make-fmt (fn [fmt] (fn [v]
                              (if-not (number? v)
                                v
                                (case fmt :id v, :syn (ps v)
                                      :map-id-syn (hash-map v (ps v))
                                      :vec-id-syn (vector v (ps v))
                                      :str-id-syn (str v ":" (ps v))))))
         key-fn (make-fmt (or key-fmt fmt :vec-id-syn))
         val-fn (make-fmt (or value-fmt fmt :vec-id-syn))]
     (update-vals props #(reduce-kv (fn [acc k v]
                                      (assoc acc (key-fn k) (if (coll? v) (mapv val-fn v) (val-fn v)))) {} %)))))

(def ^:deprecated ^:no-doc get-concept "DEPRECATED. Use [[concept]] instead" concept)
(def ^:deprecated ^:no-doc get-description "DEPRECATED. Use [[description]] instead." description)
(def ^:deprecated ^:no-doc get-relationship "DEPRECATED. Use [[relationship]] instead." relationship)
(def ^:deprecated ^:no-doc get-extended-concept "DEPRECATED. Use [[extended-concept]] instead." extended-concept)
(def ^:deprecated ^:no-doc get-descriptions "DEPRECATED. Use [[descriptions]] instead." descriptions)
(def ^:deprecated ^:no-doc get-synonyms "DEPRECATED. Use [[synonyms]] instead." synonyms)
(def ^:deprecated ^:no-doc get-all-parents "DEPRECATED. Use [[all-parents]] instead." all-parents)
(def ^:deprecated ^:no-doc get-all-children "DEPRECATED. Use [[all-children]] instead." all-children)
(def ^:deprecated ^:no-doc get-parent-relationships "DEPRECATED. Use [[parent-relationships]] instead." parent-relationships)
(def ^:deprecated ^:no-doc get-parent-relationships-expanded "DEPRECATED. Use [[parent-relationships-expanded]] instead." parent-relationships-expanded)
(def ^:deprecated ^:no-doc get-parent-relationships-of-type "DEPRECATED. Use [[parent-relationships-of-type]] instead." parent-relationships-of-type)
(def ^:deprecated ^:no-doc get-child-relationships-of-type "DEPRECATED. Use [[child-relationships-of-type]] instead." child-relationships-of-type)
(def ^:deprecated ^:no-doc get-component-refset-items "DEPRECATED. Use [[component-refset-items]] instead." component-refset-items)
(def ^:deprecated ^:no-doc get-component-refset-ids "DEPRECATED. Use [[component-refset-ids]] instead." component-refset-ids)
(def ^:deprecated ^:no-doc get-refset-item "DEPRECATED. Use [[refset-item]] instead." refset-item)
(def ^:deprecated ^:no-doc get-refset-descriptor-attribute-ids "DEPRECATED. Use [[refset-descriptor-attribute-ids]] instead." refset-descriptor-attribute-ids)
(def ^:deprecated ^:no-doc get-component-refset-items-extended "DEPRECATED. Use [[component-refset-items-extended]] instead." component-refset-items-extended)
(def ^:deprecated ^:no-doc get-installed-reference-sets "DEPRECATED. Use [[installed-reference-sets]] instead." installed-reference-sets)
(def ^:deprecated ^:no-doc get-preferred-synonym "DEPRECATED. Use [[preferred-synonym]] instead." preferred-synonym)
(def ^:deprecated ^:no-doc get-fully-specified-name "DEPRECATED. Use [[fully-specified-name]] instead." fully-specified-name)
(def ^:deprecated ^:no-doc get-release-information "DEPRECATED. Use [[release-information]] instead." release-information)
(def ^:deprecated ^:no-doc all-transitive-synonyms "DEPRECATED. Use [[transitive-synonyms]] instead." transitive-synonyms)
(def ^:deprecated ^:no-doc get-refset-members "DEPRECATED. Use [[refset-members]] instead." refset-members)

;;;;
;;;;
;;;;

(defn- open-manifest
  "Open or, if it doesn't exist, optionally create a manifest at the location specified."
  ([root] (open-manifest root false))
  ([root create?]
   (let [manifest-file (io/file root "manifest.edn")]
     (cond
       (.exists manifest-file)
       (if-let [manifest (edn/read-string (slurp manifest-file))]
         (if (= (:version manifest) (:version expected-manifest))
           manifest
           (throw (Exception. (str "error: incompatible database version. expected:'" (:version expected-manifest) "' got:'" (:version manifest) "'"))))
         (throw (Exception. (str "error: unable to read manifest from " root))))
       create?
       (let [manifest (assoc expected-manifest
                             :created (.format DateTimeFormatter/ISO_DATE_TIME (LocalDateTime/now)))]
         (io/make-parents manifest-file)
         (spit manifest-file (pr-str manifest))
         manifest)
       :else
       (throw (ex-info (str root ": no database found and operating read-only") {:path root}))))))

(defn open
  "Open a (read-only) SNOMED service from `root`, which should be anything
  coercible to a `java.io.File`. Use `default-locale` to set the default
  fallback locale for functions taking language preferences called without
  explicit priority lists, or when installed language reference sets don't
  support a requested language range."
  (^Closeable [root] (open root {}))
  (^Closeable [root {:keys [quiet default-locale] :or {quiet false}}]
   (let [manifest (open-manifest root)
         st (store/open-store (io/file root (:store manifest)))
         index-reader (search/open-index-reader (io/file root (:search manifest)))
         member-reader (members/open-index-reader (io/file root (:members manifest)))
         index-searcher (IndexSearcher. index-reader)
         locale-match-fn (lang/make-match-fn st default-locale)
         svc {:store          st
              :indexReader    index-reader
              :searcher       index-searcher
              :memberReader   member-reader
              :memberSearcher (IndexSearcher. member-reader)
              :localeMatchFn  locale-match-fn}]
     ;; report configuration when appropriate
     (when-not quiet
       (let [lang-refset-ids (locale-match-fn)]
         (log/info "opening hermes terminology service " root
                   (assoc manifest :releases (map :term (store/release-information st))
                          :default-languages (map #(:term (store/preferred-synonym st % lang-refset-ids)) lang-refset-ids)
                          :installed-locales (lang/available-locales st)))))
     (map->Svc (assoc svc :mrcmDomainFn (mrcm-domain-fn svc))))))

(defn close [^Closeable svc]
  (.close svc))

(s/fdef do-import-snomed
  :args (s/cat :store-file any?
               :files (s/coll-of :info.snomed/ReleaseFile)))
(defn- do-import-snomed
  "Import a SNOMED distribution from the specified files into a local
   file-based database `store-file`.
   Blocking; will return when done. Throws an exception on the calling thread if
   there are any import problems."
  [store-file files]
  (with-open [store (store/open-store store-file {:read-only? false})]
    (let [nthreads (.availableProcessors (Runtime/getRuntime))
          data-c (importer/load-snomed-files files :nthreads nthreads)]
      (loop [batch (a/<!! data-c)]
        (when batch
          (if (instance? Throwable batch)
            (throw batch)
            (do (store/write-batch-with-fallback store batch)
                (recur (a/<!! data-c)))))))))

(defn log-metadata [dir]
  (let [metadata (importer/all-metadata dir)
        module-names (->> metadata (map :modules) (mapcat vals))
        n-modules (count module-names)]
    (if (seq metadata)
      (log/info "importing" (count metadata) "distribution(s) from" dir)
      (log/info "no distribution(s) found in " dir))
    (doseq [dist metadata]
      (log/info "distribution: " (select-keys dist [:name :effectiveTime]))
      (log/info "license: " (or (:licenceStatement dist) (:error dist))))
    (when (pos? n-modules)
      (log/info n-modules "modules listed in distribution metadata")
      (doseq [module-name (sort-by str/lower-case module-names)]
        (log/info "module:" module-name)))))

(def ^:private core-components
  #{"Concept" "Description" "Relationship" "RefsetDescriptorRefset"})

(defn import-snomed
  "Import SNOMED distribution files from the directories `dirs` specified into
  the database directory `root` specified.

  Import is performed in three phases for each directory:

    1. import of core components and essential metadata, and
    2. interim indexing
    3. import of non-core and extension files.

  Interim indexing is necessary in order to ensure correct reification in
  subsequent import(s)."
  [root dirs]
  (let [manifest (open-manifest root true)
        store-file (io/file root (:store manifest))]
    (doseq [dir dirs]
      (log-metadata dir)
      (let [files (importer/importable-files dir)]
        (if (seq files)
          (do
            (do-import-snomed store-file (->> files (filter #(core-components (:component %)))))
            (with-open [st (store/open-store store-file {:read-only? false})]
              (store/index st))
            (do-import-snomed store-file (->> files (remove #(core-components (:component %))))))
          (log/warn "no importable files found when processing:" dir))))))

(defn compact
  [root]
  (let [manifest (open-manifest root false)]
    (log/info "Compacting database at " root "...")
    (let [st (store/open-store (io/file root (:store manifest)) {:read-only? false})]
      (store/compact-and-close st))
    (log/info "Compacting database... complete")))

(s/fdef index
  :args (s/cat :root any?))
(defn index
  "Build component  and search indices for the database in directory 'root'
  specified."
  [root]
  (let [manifest (open-manifest root false)
        store-filename (io/file root (:store manifest))
        search-filename (io/file root (:search manifest))
        members-filename (io/file root (:members manifest))]
    (log/info "Indexing..." {:root root})
    (log/info "Building component index")
    (with-open [st (store/open-store store-filename {:read-only? false})]
      (store/index st))
    (log/info "Building search index")
    (search/build-search-index store-filename search-filename)
    (log/info "Building members index")
    (members/build-members-index store-filename members-filename)
    (log/info "Indexing... complete")))

(def ^:deprecated build-search-indices
  "DEPRECATED: Use [[index]] instead"
  index)

(def ^:deprecated build-search-index
  "DEPRECATED: Use [[index]] instead"
  index)

(defn ^:private safe-lower-case [s]
  (when s (str/lower-case s)))

(defn status*
  "Return status information for the given service. Returns a map containing:

  - `:releases`          : a sequence of strings for installed distributions
  - `:locales`           : installed/supported locales
  - `:components`        : a map of component counts and indices
  - `:modules`           : a map of module id to term, for installed modules
  - `:installed-refsets` : a map of reference set id to term

  What is returned is configurable using options:

  - `:counts?`            : whether to include counts of components
  - `:modules?`           : whether to include installed modules
  - `:installed-refsets?` : whether to include installed reference sets"
  [^Svc svc {:keys [counts? modules? installed-refsets?] :or {counts? true installed-refsets? false modules? false}}]
  (merge
   {:releases (map :term (release-information svc))
    :locales  (lang/available-locales (.-store svc))}
   (when counts?
     {:components (-> (store/status (.-store svc))
                      (assoc-in [:indices :descriptions-search] (.numDocs ^IndexReader (.-indexReader svc)))
                      (assoc-in [:indices :members-search] (.numDocs ^IndexReader (.-memberReader svc))))})
   (when modules?
     {:modules (let [results (reduce (fn [acc {source :source}]
                                       (assoc acc (:moduleId source) (str (:term (fully-specified-name svc (:moduleId source))) ": " (:version source))))
                                     {} (module-dependencies svc))]
                 (into (sorted-map-by #(compare (safe-lower-case (get results %1)) (safe-lower-case (get results %2)))) results))})

   (when installed-refsets?
     {:installed-refsets (let [results (->> (installed-reference-sets svc)
                                            (reduce (fn [acc id] (assoc acc id (:term (fully-specified-name svc id)))) {}))]
                           (into (sorted-map-by #(compare (safe-lower-case (get results %1)) (safe-lower-case (get results %2)))) results))})))

(defn status
  "Return status information for the database at 'root' where `root` is
  something coercible to `java.io.File`. This is a convenience wrapper for
  [[status*]] that opens and closes the service for you."
  ([root] (status root nil))
  ([root {:keys [log?] :as opts}]
   (with-open [^Svc svc (open root {:quiet true})]
     (when log? (log/info "Status information for database at '" root "'..."))
     (status* svc opts))))

(defn ^:deprecated get-status
  "Backwards-compatible status report. Use [[status]] instead. This flattens the
  component counts at the top-level to mimic legacy deprecated behaviour."
  [root & {:keys [counts? installed-refsets? modules? log?]
           :or   {counts? false installed-refsets? true modules? false log? true}}]
  (let [st (status root {:counts? counts? :installed-refsets? installed-refsets? :modules? modules? :log? log?})]
    (-> st
        (dissoc :components)
        (merge (:components st)))))

(defn create-service
  "Create a terminology service combining both store and search functionality
  in a single step. It would be unusual to use this; usually each step would be
  performed interactively by an end-user."
  ([root import-from]
   (import-snomed root import-from)
   (index root)
   (compact root)))

(defn ^:private analyse-diacritics
  [^Svc svc]
  (let [ch (a/chan 1 (filter :active))]
    (a/thread (stream-all-concepts svc ch))
    (loop [n-concepts 0, missing 0, results []]
      (if-let [c (a/<!! ch)]
        (let [s1 (set (map :term (synonyms svc (:id c))))
              s2 (set (map #(lang/fold "en" %) s1))
              diff (set/difference s2 s1)
              diff' (remove #(or (are-any? svc (set (map :conceptId (search svc {:s %}))) [(:id c)])
                                 (are-any? svc [(:id c)] (set (map :conceptId (search svc {:s %}))))) diff)]
          (recur (if (seq diff) (inc n-concepts) n-concepts)
                 (+ missing (count diff'))
                 (if (seq diff') (conj results {:concept-id (:id c) :missing diff'}) results)))
        {:n-concepts n-concepts :missing missing :results results}))))

(comment
  (require '[dev.nu.morse :as morse])
  (morse/launch-in-proc)
  (def svc (open "snomed.db"))
  (concept svc 24700007)
  (all-children svc 24700007)
  (time (all-parents svc 24700007))
  (time (extended-concept svc 24700007))
  (s/valid? :info.snomed/Concept (concept svc 24700007))

  (tap> (concept svc 24700007))
  (tap> (extended-concept svc 205631000000104))
  (morse/inspect (pprint-properties svc (properties svc 1231295007) {}))
  (extended-concept svc 24700007)
  (search svc {:s "mult scl"})
  (tap> (search svc {:s "mult scl"}))
  (search svc {:s "mult scl" :constraint "<< 24700007"})

  (search svc {:s "ICD-10 complex map"})
  (->> (member-field-prefix svc 447562003 "mapTarget" "I30")
       (map #(:term (preferred-synonym svc % "en"))))

  (search svc {:constraint "<900000000000455006 {{ term = \"emerg\"}}"})
  (search svc {:constraint "<900000000000455006 {{ term = \"household\", type = syn, dialect = (en-GB)  }}"})

  (member-field-prefix svc 447562003 "mapTarget" "I")
  (component-refset-items svc 24700007 447562003)
  (map :mapTarget (component-refset-items svc 24700007 447562003))

  (extended-concept svc 24700007)
  (subsumed-by? svc 24700007 6118003)                       ;; demyelinating disease of the CNS

  (are-any? svc [24700007] [45454])

  (search svc {:constraint "<  64572001 |Disease|  {{ term = wild:\"cardi*opathy\"}}"})
  (search svc {:constraint "<24700007" :inactive-concepts? false})
  (search svc {:constraint "<24700007" :inactive-concepts? true})
  (def ecl-q (ecl/parse {:store           (.-store svc)
                         :searcher        (.-searcher svc)
                         :member-searcher (.-memberSearcher svc)} "<24700007"))
  ecl-q
  (def q1 (search/q-and [ecl-q (#'search/make-search-query {:inactive-concepts? true})]))
  (def q2 (search/q-and [ecl-q (#'search/make-search-query {:inactive-concepts? false})]))
  q1
  q2
  (count (#'search/do-query-for-concept-ids (.-searcher svc) q1))
  (count (#'search/do-query-for-concepts-ids (.-searcher svc) q2))
  q2

  (search svc {:constraint "<  404684003 |Clinical finding| :\n   [0..0] { [2..*]  363698007 |Finding site|  = <  91723000 |Anatomical structure| }"})

  ;; explore SNOMED - get counts of historical association types / frequencies
  (def counts (historical-association-counts svc))
  (reduce-kv (fn [m k v] (assoc m (:term (fully-specified-name svc k)) (apply max v))) {} counts)

  (historical-associations svc 5171008)
  (fully-specified-name svc 900000000000526001)
  (example-historical-associations svc snomed/PossiblyEquivalentToReferenceSet 2)
  (filter :active (component-refset-items svc 203004 snomed/PossiblyEquivalentToReferenceSet))
  (preferred-synonym svc 24700007 "en-GB")
  (parent-relationships-of-type svc 24700007 snomed/IsA)
  (child-relationships-of-type svc 24700007 snomed/IsA)
  (set (map :conceptId (expand-ecl-historic svc "<<24700007")))

  (require '[criterium.core :as crit])
  (crit/bench (extended-concept svc 24700007))
  (crit/bench (search svc {:s "multiple sclerosis"})))

(comment
  (def svc (open "snomed.db"))
  (extended-concept svc 24700007)
  (search svc {:s "occupation" :max-hits 1})
  (search svc {:s "consultant neurologist" :constraint "<14679004" :mode :ranked-search :max-hits 1000})
  (search svc {:s "CNS" :constraint "<14679004" :mode :ranked-search :max-hits 1}))
