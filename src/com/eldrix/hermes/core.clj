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
(ns com.eldrix.hermes.core
  "Provides a terminology service, wrapping the SNOMED store and
  search implementations as a single unified service."
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
  (:import (com.eldrix.hermes.snomed Result)
           (org.apache.lucene.index IndexReader)
           (org.apache.lucene.search IndexSearcher Query)
           (java.util Locale UUID)
           (java.time.format DateTimeFormatter)
           (java.time LocalDate LocalDateTime)
           (java.io Closeable)))

(set! *warn-on-reflection* true)

(def ^:private expected-manifest
  "Defines the current expected manifest."
  {:version "lmdb/15"
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
(s/def ::show-fsn? boolean?)
(s/def ::inactive-concepts? boolean?)
(s/def ::inactive-descriptions? boolean?)
(s/def ::remove-duplicates? boolean?)
(s/def ::properties (s/map-of int? int?))
(s/def ::concept-refsets (s/coll-of :info.snomed.Concept/id))
(s/def ::search-params (s/keys :req-un [(or ::s ::constraint)]
                               :opt-un [::max-hits ::fuzzy ::fallback-fuzzy ::query
                                        ::show-fsn? ::inactive-concepts? ::inactive-descriptions?
                                        ::remove-duplicates? ::properties ::concept-refsets]))


(definterface ^:deprecated Service)                         ;; for backwards compatibility in case a client referenced the old concrete deftype

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
  "Return an extended concept that includes the concept, its descriptions,
  its relationships and its refset memberships. See
  [[com.eldrix.hermes.snomed/ExtendedConcept]]"
  [^Svc svc concept-id]
  (store/extended-concept (.-store svc) concept-id))

(s/fdef descriptions
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn descriptions
  "Return a sequence of descriptions for the given concept."
  [^Svc svc concept-id]
  (store/concept-descriptions (.-store svc) concept-id))

(s/fdef synonyms
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id))
(defn synonyms
  "Returns a sequence of synonyms for the given concept."
  [^Svc svc concept-id]
  (->> (descriptions svc concept-id)
       (filter #(= snomed/Synonym (:typeId %)))))

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
  "DEPRECATED: use [[get-component-refset-items]] instead."
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
  "Return a set of identifiers representing installed reference sets."
  [^Svc svc]
  (store/installed-reference-sets (.-store svc)))

(defn member-field
  "Returns a set of referenced component identifiers that are members of the
  given reference set with a matching 'value' for the 'field' specified.
  For example, to perform a reverse map from ICD-10:
  ```
  (member-field svc 447562003 \"mapTarget\" \"G35\")
  ```"
  [^Svc svc refset-id field s]
  (members/search (.-memberSearcher svc)
                  (members/q-and
                    [(members/q-refset-id refset-id) (members/q-term field s)])))

(defn member-field-prefix
  "Return a set of referenced component identifiers that are members of the
  given reference set with a matching prefix 'value' for the 'field' specified.
  Example:
  ```
      (member-field-prefix svc 447562003 \"mapTarget\" \"G3\")
  ```"
  [^Svc svc refset-id field prefix]
  (members/search (.-memberSearcher svc)
                  (members/q-and
                    [(members/q-refset-id refset-id) (members/q-prefix field prefix)])))

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
  :args (s/cat :svc ::svc :language-range (s/? ::non-blank-string)))
(defn match-locale
  "Return an ordered sequence of refset ids that are the best match for the
  required language range. `language-range` should be a single string containing
  a list of comma-separated language ranges or a list of language ranges in the
  form of the \"Accept-Language \" header defined in RFC3066."
  [^Svc svc language-range]
  ((.-localeMatchFn svc) language-range))

(s/fdef preferred-synonym*
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :language-refset-ids (s/coll-of :info.snomed.Concept/id)))
(defn preferred-synonym*
  "Given an ordered sequence of preferred language reference set ids, return
  the preferred synonym for the concept specified."
  [^Svc svc concept-id language-refset-ids]
  (store/preferred-synonym (.-store svc) concept-id language-refset-ids))

(s/fdef preferred-synonym
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :language-range (s/? ::non-blank-string)))
(defn preferred-synonym
  "Return the preferred synonym for the concept based on the language
  preferences specified.

  Use [[match-locale]] and then repeated calls to [[preferred-synonym*]] if
  preferred synonyms of a number of concepts are required (e.g. in a map/reduce etc)

  Parameters:
  - svc            : hermes service
  - concept-id     : concept identifier
  - language-range : a single string containing a list of comma-separated
                     language ranges or a list of language ranges in the form of
                     the \"Accept-Language \" header defined in RFC3066."
  ([^Svc svc concept-id]
   (preferred-synonym svc concept-id (.toLanguageTag (Locale/getDefault))))
  ([^Svc svc concept-id language-range]
   (preferred-synonym* svc concept-id (match-locale svc language-range))))

(s/fdef fully-specified-name
  :args (s/cat :svc ::svc :concept-id :info.snomed.Concept/id :language-range (s/? ::non-blank-string)))
(defn fully-specified-name
  ([^Svc svc concept-id]
   (fully-specified-name svc concept-id (.toLanguageTag (Locale/getDefault))))
  ([^Svc svc concept-id language-range]
   (store/fully-specified-name (.-store svc) concept-id (match-locale svc language-range) false)))

(defn release-information [^Svc svc]
  (store/release-information (.-store svc)))

(defn subsumed-by? [^Svc svc concept-id subsumer-concept-id]
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

(s/fdef search
  :args (s/cat :svc ::svc :params ::search-params)
  :ret (s/coll-of ::result))
(defn search
  "Search the descriptions index using the search parameters specified."
  [^Svc svc params]
  (if-let [constraint (:constraint params)]
    (search/do-search (.-searcher svc) (assoc params :query (ecl/parse svc constraint)))
    (search/do-search (.-searcher svc) params)))

(s/fdef expand-ecl
  :args (s/cat :svc ::svc :ecl ::non-blank-string :max-hits (s/? int?))
  :ret (s/coll-of ::result))
(defn expand-ecl
  "Expand an ECL expression."
  ([^Svc svc ecl]
   (let [q1 (ecl/parse svc ecl)
         q2 (search/q-not q1 (search/q-fsn))]
     (search/do-query-for-results (.-searcher svc) q2)))
  ([^Svc svc ecl max-hits]
   (let [q1 (ecl/parse svc ecl)
         q2 (search/q-not q1 (search/q-fsn))]
     (search/do-query-for-results (.-searcher svc) q2 max-hits))))

(s/fdef intersect-ecl
  :args (s/cat :svc ::svc :concept-ids (s/coll-of :info.snomed.Concept/id) :ecl ::non-blank-string))
(defn intersect-ecl
  "Returns a set of concept identifiers that satisfy the SNOMED ECL expression."
  [^Svc svc concept-ids ^String ecl]
  (let [q1 (search/q-concept-ids concept-ids)
        q2 (ecl/parse svc ecl)]
    (search/do-query-for-concept-ids (.-searcher svc) (search/q-and [q1 q2]))))

(s/fdef valid-ecl?
  :args (s/cat :s string?))
(defn valid-ecl?
  "Is the ECL valid?"
  [s]
  (ecl/valid? s))

(s/fdef ecl-contains?
  :args (s/cat :svc ::svc
               :concept-ids (s/coll-of :info.snomed.Concept/id)
               :ecl ::non-blank-string))
(defn ^:deprecated ecl-contains?
  "DEPRECATED: use `intersect-ecl` instead.

  Do any of the concept-ids satisfy the constraint expression specified?
  This is an alternative to expanding the valueset and then checking membership."
  [^Svc svc concept-ids ^String ecl]
  (seq (intersect-ecl svc concept-ids ecl)))

(s/fdef expand-ecl-historic
  :args (s/cat :svc ::svc :ecl ::non-blank-string)
  :ret (s/coll-of ::result))
(defn expand-ecl-historic
  "Expand an ECL expression and include historic associations of the results,
  so that the results will include now inactive/deprecated concept identifiers."
  [^Svc svc ^String ecl]
  (let [q1 (ecl/parse svc ecl)
        q2 (search/q-not q1 (search/q-fsn))
        base-concept-ids (search/do-query-for-concept-ids (.-searcher svc) q2)
        historic-concept-ids (into #{} (mapcat #(source-historical svc %)) base-concept-ids)
        historic-query (search/q-concept-ids historic-concept-ids)
        query (search/q-not (search/q-or [q1 historic-query]) (search/q-fsn))]
    (search/do-query-for-results (.-searcher svc) query)))

(s/def ::transitive-synonym-params (s/or :by-search map? :by-ecl string? :by-concept-ids coll?))

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
  - svc                : hermes service
  - source-concept-ids : a collection of concept identifiers
  - target             : one of:
                           - a collection of concept identifiers
                           - an ECL expression
                           - a refset identifier

  If a source concept id resolves to multiple concepts in the target collection,
  then a collection will be returned such that no member of the subset is
  subsumed by another member.

  Callers will usually need to map any source concept identifiers into their
  modern active replacements, if they are now inactive, as inactive source
  concepts do not have relationships that can be used to perform map-into.

  The use of 'map-into' is in reducing the granularity of user-entered
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
  transformed as :source, :target, :actual and :valid representing module
  dependencies. Returns a sequence of:
  - :source : source of the dependency (a map of :moduleId, :version)
  - :target : target on which the source depends (a map of :moduleId, :version)
  - :actual : actual version; may be nil
  - :valid  : is this dependency satisfied and consistent?
  Versions are represented as `java.time.LocalDate.
  Dependencies are not transitive as per [[https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.4.2+Module+Dependency+Reference+Set]]."
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
  "Returns a sequence of module dependencies, containing:
  - :source : source of the dependency (a map of :moduleId, :version)
  - :target : target on which the source depends (a map of :moduleId, :version)
  - :actual : actual version; may be nil
  - :valid  : is this dependency satisfied and consistent?
  Versions are represented as `java.time.LocalDate.
  Dependencies are not transitive as per [[https://confluence.ihtsdotools.org/display/DOCRELFMT/5.2.4.2+Module+Dependency+Reference+Set]]."
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
(defn paths-to-root [^Svc svc concept-id]
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
  "Return a set of domains for the given concept."
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
    \"The relationshipGroup field is used to group relationships with the same
    sourceId field into one or more logical sets. A relationship with a
    relationshipGroup field value of '0' is considered not to be grouped. All
    relationships with the same sourceId and non-zero relationshipGroup are
    considered to be logically grouped.\""
  ([^Svc svc concept-id] (properties svc concept-id nil))
  ([^Svc svc concept-id {:keys [expand]}]
   (reduce-kv (fn [acc group-id props]
                (assoc acc group-id (-fix-property-values svc concept-id group-id props expand)))
              {} (if expand (store/properties-expanded (.-store svc) concept-id)
                            (store/properties (.-store svc) concept-id)))))

(defn pprint-properties
  "Pretty print properties. Keys and values can be formatted using `fmt` or
  separately using `key-fmt` and `value-fmt`.
  Valid formats are:
    :map-id-syn : map of id to synonym
    :vec-id-syn : vector of id and synonym
    :str-id-syn : id and synonym as a string
    :syn        : synonym
    :id         : id
  `language-range` should be a language range such as \"en-GB\"."
  [svc props {:keys [key-fmt value-fmt fmt language-range]}]
  (let [lang-refset-ids (match-locale svc (or language-range (.toLanguageTag (Locale/getDefault))))
        ps (fn [concept-id] (:term (preferred-synonym* svc concept-id lang-refset-ids)))
        make-fmt (fn [fmt] (fn [v]
                             (if-not (number? v) v
                               (case fmt :id v, :syn (ps v)
                                         :map-id-syn (hash-map v (ps v))
                                         :vec-id-syn (vector v (ps v))
                                         :str-id-syn (str v ":" (ps v))))))
        key-fn (make-fmt (or key-fmt fmt :vec-id-syn))
        val-fn (make-fmt (or value-fmt fmt :vec-id-syn))]
    (update-vals props #(reduce-kv (fn [acc k v]
                                     (assoc acc (key-fn k) (if (coll? v) (mapv val-fn v) (val-fn v)))) {} %))))


(def ^:deprecated get-concept "DEPRECATED. Use [[concept]] instead" concept)
(def ^:deprecated get-description "DEPRECATED. Use [[description]] instead." description)
(def ^:deprecated get-relationship "DEPRECATED. Use [[relationship]] instead." relationship)
(def ^:deprecated get-extended-concept "DEPRECATED. Use [[extended-concept]] instead." extended-concept)
(def ^:deprecated get-descriptions "DEPRECATED. Use [[descriptions]] instead." descriptions)
(def ^:deprecated get-synonyms "DEPRECATED. Use [[synonyms]] instead." synonyms)
(def ^:deprecated get-all-parents "DEPRECATED. Use [[all-parents]] instead." all-parents)
(def ^:deprecated get-all-children "DEPRECATED. Use [[all-children]] instead." all-children)
(def ^:deprecated get-parent-relationships "DEPRECATED. Use [[parent-relationships]] instead." parent-relationships)
(def ^:deprecated get-parent-relationships-expanded "DEPRECATED. Use [[parent-relationships-expanded]] instead." parent-relationships-expanded)
(def ^:deprecated get-parent-relationships-of-type "DEPRECATED. Use [[parent-relationships-of-type]] instead." parent-relationships-of-type)
(def ^:deprecated get-child-relationships-of-type "DEPRECATED. Use [[child-relationships-of-type]] instead." child-relationships-of-type)
(def ^:deprecated get-component-refset-items "DEPRECATED. Use [[component-refset-items]] instead." component-refset-items)
(def ^:deprecated get-component-refset-ids "DEPRECATED. Use [[component-refset-ids]] instead." component-refset-ids)
(def ^:deprecated get-refset-item "DEPRECATED. Use [[refset-item]] instead." refset-item)
(def ^:deprecated get-refset-descriptor-attribute-ids "DEPRECATED. Use [[refset-descriptor-attribute-ids]] instead." refset-descriptor-attribute-ids)
(def ^:deprecated get-component-refset-items-extended "DEPRECATED. Use [[component-refset-items-extended]] instead." component-refset-items-extended)
(def ^:deprecated get-installed-reference-sets "DEPRECATED. Use [[installed-reference-sets]] instead." installed-reference-sets)
(def ^:deprecated get-preferred-synonym "DEPRECATED. Use [[preferred-synonym]] instead." preferred-synonym)
(def ^:deprecated get-fully-specified-name "DEPRECATED. Use [[fully-specified-name]] instead." fully-specified-name)
(def ^:deprecated get-release-information "DEPRECATED. Use [[release-information]] instead." release-information)
(def ^:deprecated all-transitive-synonyms "DEPRECATED. Use [[transitive-synonyms]] instead." transitive-synonyms)
(def ^:deprecated get-refset-members "DEPRECATED. Use [[refset-members]] instead." refset-members)

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
                        :created (.format (DateTimeFormatter/ISO_DATE_TIME) (LocalDateTime/now)))]
         (io/make-parents manifest-file)
         (spit manifest-file (pr-str manifest))
         manifest)
       :else
       (throw (ex-info (str root ": no database found and operating read-only") {:path root}))))))

(defn open
  "Open a (read-only) SNOMED service from `root`, which should be anything
  coercible to a `java.io.File`"
  (^Closeable [root] (open root {}))
  (^Closeable [root {:keys [quiet] :or {quiet false}}]
   (let [manifest (open-manifest root)
         st (store/open-store (io/file root (:store manifest)))
         index-reader (search/open-index-reader (io/file root (:search manifest)))
         member-reader (members/open-index-reader (io/file root (:members manifest)))
         svc {:store          st
              :indexReader    index-reader
              :searcher       (IndexSearcher. index-reader)
              :memberReader   member-reader
              :memberSearcher (IndexSearcher. member-reader)
              :localeMatchFn  (lang/match-fn st)}]
     (when-not quiet (log/info "opening hermes terminology service " root (assoc manifest :releases (map :term (store/release-information st)))))
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
    (when (seq metadata)
      (log/info "importing" (count metadata) "distribution(s) from" dir))
    (doseq [dist metadata]
      (log/info "distribution: " (select-keys dist [:name :effectiveTime]))
      (log/info "license: " (or (:licenceStatement dist) (:error dist))))
    (when (pos? n-modules)
      (log/info n-modules "modules listed in distribution metadata")
      (doseq [module-name module-names]
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
        (do-import-snomed store-file (->> files (filter #(core-components (:component %)))))
        (with-open [st (store/open-store store-file {:read-only? false})]
          (store/index st))
        (do-import-snomed store-file (->> files (remove #(core-components (:component %)))))))))

(defn compact
  [root]
  (let [manifest (open-manifest root false)]
    (log/info "Compacting database at " root "...")
    (with-open [st (store/open-store (io/file root (:store manifest)) {:read-only? false})]
      (store/compact st))
    (log/info "Compacting database... complete")))

(defn index
  "Build search indices for the database in directory 'root' specified."
  ([root] (index root (.toLanguageTag (Locale/getDefault))))
  ([root language-priority-list]
   (let [manifest (open-manifest root false)
         store-filename (io/file root (:store manifest))
         search-filename (io/file root (:search manifest))
         members-filename (io/file root (:members manifest))]
     (log/info "Indexing..." {:root root})
     (log/info "Building component index")
     (with-open [st (store/open-store store-filename {:read-only? false})]
       (store/index st))
     (log/info "Building search index" {:languages language-priority-list})
     (search/build-search-index store-filename search-filename language-priority-list)
     (log/info "Building members index")
     (members/build-members-index store-filename members-filename)
     (log/info "Indexing... complete"))))

(def ^:deprecated build-search-indices
  "DEPRECATED: Use [[build-indices]] instead"
  index)

(def ^:deprecated build-search-index
  "DEPRECATED: Use [[build-search-indices]] instead"
  index)

(defn ^:private safe-lower-case [s]
  (when s (str/lower-case s)))


(defn status*
  [^Svc svc {:keys [counts? modules? installed-refsets?] :or {counts? true installed-refsets? false modules? false}}]
  (merge
    {:releases
     (map :term (release-information svc))}
    {:locales
     (->> (keys (lang/installed-language-reference-sets (.-store svc)))
          (map #(.toLanguageTag ^Locale %)))}
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
  "Return status information for the database at 'root'."
  ([root] (status root nil))
  ([root {:keys [log?] :as opts}]
   (with-open [^Svc svc (open root {:quiet true})]
     (when log? (log/info "Status information for database at '" root "'..."))
     (status* svc opts))))

(defn ^:deprecated get-status
  "Backwards-compatible status report. Use `status` instead. This flattens the
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
  ([root import-from] (create-service root import-from))
  ([root import-from locale-preference-string]
   (import-snomed root import-from)
   (index root locale-preference-string)
   (compact root)))

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

