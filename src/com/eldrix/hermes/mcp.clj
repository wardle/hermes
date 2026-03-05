; Copyright (c) 2020-2026 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns com.eldrix.hermes.mcp
  "MCP (Model Context Protocol) tool, resource, and prompt definitions for
  hermes. Pure data and functions with no transport dependency — suitable for
  in-process use by any MCP host or LLM integration."
  (:require [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed]))

(set! *warn-on-reflection* true)

;;
;; Schema helpers
;;

(def ^:private concept-id-schema
  "Schema for concept_id: accepts a single integer or an array of integers."
  {:oneOf [{:type "integer"} {:type "array" :items {:type "integer"}}]
   :description "SNOMED CT concept identifier(s)"})

(def ^:private accept-language-schema
  "Schema for accept_language parameter."
  {:type "string" :description "BCP 47 / RFC 3066 Accept-Language header value (e.g. 'en-GB', 'en-US,en;q=0.9'). Defaults to server locale."})

;;
;; Tool handler helpers
;;

(defn- ->coll
  "Ensure x is sequential; wraps a scalar in a vector."
  [x]
  (if (sequential? x) x [x]))

(defn- for-concept
  "Apply f to concept_id. If concept_id is a scalar, returns a single result.
  If concept_id is sequential, returns a vector of results."
  [concept_id f]
  (if (sequential? concept_id)
    (mapv f concept_id)
    (f concept_id)))

;;
;; Tool handlers
;;

(defn- tool-search [svc {:keys [query constraint max_hits accept_language]}]
  (let [params (cond-> {:s query :max-hits (or max_hits 10)}
                 constraint (assoc :constraint constraint)
                 accept_language (assoc :accept-language accept_language))]
    (hermes/ranked-search svc params)))

(defn- tool-autocomplete [svc {:keys [query constraint max_hits fuzzy fallback_fuzzy accept_language]}]
  (let [params (cond-> {:s query :max-hits (or max_hits 200) :fuzzy (or fuzzy 0)}
                 constraint (assoc :constraint constraint)
                 fallback_fuzzy (assoc :fallback-fuzzy fallback_fuzzy)
                 accept_language (assoc :accept-language accept_language))]
    (hermes/search svc params)))

(defn- tool-concept [svc {:keys [concept_id]}]
  (for-concept concept_id
    (fn [id]
      (or (hermes/concept svc id)
          (throw (ex-info (str "Concept not found: " id) {:concept_id id}))))))

(defn- tool-extended-concept [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id
      (fn [id]
        (if-let [concept (hermes/extended-concept svc id)]
          (assoc concept :preferredDescription (hermes/preferred-synonym* svc id lang))
          (throw (ex-info (str "Concept not found: " id) {:concept_id id})))))))

(defn- tool-properties [svc {:keys [concept_id expand]}]
  (for-concept concept_id
    (fn [id]
      (let [props (hermes/properties svc id {:expand (boolean expand)})]
        (if (seq props)
          (hermes/pprint-properties svc props {:value-fmt :str-id-syn})
          props)))))

(defn- tool-expand-ecl [svc {:keys [ecl max_hits include_historic accept_language]}]
  (if include_historic
    (let [results (hermes/expand-ecl-historic svc ecl)]
      (if max_hits (vec (take max_hits results)) (vec results)))
    (let [lang    (hermes/match-locale svc accept_language true)
          results (hermes/expand-ecl* svc ecl lang)]
      (if max_hits (vec (take max_hits results)) (vec results)))))

(defn- tool-transitive-synonyms [svc {:keys [concept_id ecl]}]
  (cond
    ecl        (hermes/transitive-synonyms svc ecl)
    concept_id (hermes/transitive-synonyms svc (->coll concept_id))
    :else      (throw (ex-info "Provide either concept_id or ecl" {}))))

(defn- tool-map-to [svc {:keys [concept_id refset_id]}]
  (for-concept concept_id
    (fn [id]
      (if-let [items (seq (hermes/component-refset-items svc id refset_id))]
        (vec items)
        (let [mapped-ids (hermes/map-concept-into svc id refset_id)]
          (vec (mapcat #(hermes/component-refset-items svc % refset_id) mapped-ids)))))))

(defn- tool-map-from [svc {:keys [refset_id code]}]
  (hermes/reverse-map svc refset_id code))

(defn- tool-map-into [svc {:keys [concept_id target]}]
  (let [ids     (->coll concept_id)
        results (hermes/map-into svc ids (or (parse-long target) target))]
    (mapv (fn [concept-id result-set]
            {:conceptId concept-id :mappedTo (vec result-set)})
          ids results)))

(defn- tool-intersect-ecl [svc {:keys [concept_id ecl]}]
  (vec (hermes/intersect-ecl svc (->coll concept_id) ecl)))

(defn- tool-subsumed-by [svc {:keys [concept_id subsumer_id]}]
  (let [concept-ids  (->coll concept_id)
        subsumer-ids (->coll subsumer_id)]
    (if (and (= 1 (count concept-ids)) (= 1 (count subsumer-ids)))
      {:subsumedBy (hermes/subsumed-by? svc (first concept-ids) (first subsumer-ids))}
      {:subsumedBy (boolean (hermes/are-any? svc concept-ids subsumer-ids))})))

(defn- tool-paths-to-root [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id
      (fn [id]
        (mapv (fn [path]
                (mapv (fn [pid] {:id pid :term (:term (hermes/preferred-synonym* svc pid lang))})
                      path))
              (hermes/paths-to-root svc id))))))

(defn- tool-historical [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id
      (fn [id]
        (mapv (fn [[refset-id items]]
                {:associationType refset-id
                 :associationName (:term (hermes/preferred-synonym* svc refset-id lang))
                 :targets         (mapv (fn [item]
                                          {:conceptId (:targetComponentId item)
                                           :term      (:term (hermes/preferred-synonym* svc (:targetComponentId item) lang))
                                           :active    (:active item)})
                                        items)})
              (hermes/historical-associations svc id))))))

(defn- tool-synonym [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id #(hermes/preferred-synonym* svc % lang))))

(defn- tool-valid-ecl [_svc {:keys [ecl]}]
  {:valid (hermes/valid-ecl? ecl)})

(defn- tool-membership [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id
      (fn [id]
        (mapv (fn [rid] {:id rid :name (:term (hermes/preferred-synonym* svc rid lang))})
              (hermes/component-refset-ids svc id))))))

(defn- tool-fully-specified-name [svc {:keys [concept_id accept_language]}]
  (for-concept concept_id #(hermes/fully-specified-name svc % accept_language)))

(defn- tool-descriptions [svc {:keys [concept_id]}]
  (for-concept concept_id #(hermes/descriptions svc %)))

(defn- tool-source-historical [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id
      (fn [id]
        (mapv (fn [[refset-id concept-ids]]
                {:associationType refset-id
                 :associationName (:term (hermes/preferred-synonym* svc refset-id lang))
                 :sources         (mapv (fn [src-id]
                                          {:conceptId src-id
                                           :term      (:term (hermes/preferred-synonym* svc src-id lang))
                                           :active    (:active (hermes/concept svc src-id))})
                                        concept-ids)})
              (hermes/source-historical-associations svc id))))))

(defn- tool-with-historical [svc {:keys [concept_id]}]
  (vec (hermes/with-historical svc (->coll concept_id))))

(defn- tool-refset-members [svc {:keys [refset_id]}]
  (vec (hermes/refset-members svc refset_id)))

(defn- tool-server-info [svc _args]
  (hermes/status* svc {:counts? false :modules? false :installed-refsets? true}))

(defn- tool-refsets [svc _args]
  (let [active? (fn [id] (:active (hermes/concept svc id)))]
    (->> (hermes/installed-reference-sets svc)
         (filter active?)
         (group-by (fn [id] (first (hermes/parent-relationships-of-type svc id snomed/IsA))))
         (keep (fn [[type-id refset-ids]]
                 (when type-id
                   {:typeId   type-id
                    :typeName (:term (hermes/preferred-synonym svc type-id))
                    :refsets  (->> refset-ids
                                   (mapv (fn [id] {:id id :name (:term (hermes/preferred-synonym svc id))}))
                                   (sort-by :name))})))
         (sort-by :typeName)
         vec)))

(def ^:private tools*
  [{:name        "search"
    :description "Ranked search for SNOMED CT concepts by clinical term. Returns results even when not all tokens match."
    :inputSchema {:type       "object"
                  :properties {:query           {:type "string" :description "Search text"}
                               :constraint      {:type "string" :description "SNOMED ECL constraint expression to filter results"}
                               :max_hits        {:type "integer" :description "Maximum number of results (default 10)"}
                               :accept_language accept-language-schema}
                  :required   ["query"]}
    :handler     tool-search}
   {:name        "autocomplete"
    :description "Autocomplete search for SNOMED CT concepts. All tokens must match. Suitable for interactive type-ahead."
    :inputSchema {:type       "object"
                  :properties {:query           {:type "string" :description "Search text"}
                               :constraint      {:type "string" :description "SNOMED ECL constraint expression to filter results"}
                               :max_hits        {:type "integer" :description "Maximum number of results (default 200)"}
                               :fuzzy           {:type "integer" :description "Fuzziness level 0-2 (default 0)"}
                               :fallback_fuzzy  {:type "integer" :description "Fallback fuzziness if no results found at primary level (0-2)"}
                               :accept_language accept-language-schema}
                  :required   ["query"]}
    :handler     tool-autocomplete}
   {:name        "concept"
    :description "Get a SNOMED CT concept record: id, effectiveTime, active, moduleId, definitionStatusId."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema}
                  :required   ["concept_id"]}
    :handler     tool-concept}
   {:name        "extended_concept"
    :description "Get a SNOMED CT concept with descriptions, parent/child relationships, concrete values, reference set memberships, and preferred synonym."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}
    :handler     tool-extended-concept}
   {:name        "properties"
    :description "Get the defining properties (attributes/relationships) of a SNOMED CT concept with human-readable labels."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :expand     {:type "boolean" :description "Include transitive relationships (default false)"}}
                  :required   ["concept_id"]}
    :handler     tool-properties}
   {:name        "expand_ecl"
    :description "Expand an ECL expression to matching concepts. Supports ECL v2.2 — see resource 'hermes://guides/ecl'."
    :inputSchema {:type       "object"
                  :properties {:ecl              {:type "string" :description "ECL expression to expand"}
                               :max_hits         {:type "integer" :description "Maximum number of results (unlimited if omitted)"}
                               :include_historic {:type "boolean" :description "Include inactive historical concepts (default false)"}
                               :accept_language  accept-language-schema}
                  :required   ["ecl"]}
    :handler     tool-expand-ecl}
   {:name        "transitive_synonyms"
    :description "Get all synonyms (terms) for a concept and all of its descendants. Provide either concept_id or ecl."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :ecl        {:type "string" :description "ECL expression selecting concepts"}}}
    :handler     tool-transitive-synonyms}
   {:name        "map_to"
    :description "Map a SNOMED CT concept to a target code system. If not directly mapped, walks up the hierarchy to the nearest mapped ancestor."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :refset_id  {:type "integer" :description "Reference set identifier for the target map (e.g. 999002271000000101 for UK ICD-10)"}}
                  :required   ["concept_id" "refset_id"]}
    :handler     tool-map-to}
   {:name        "map_from"
    :description "Reverse map from a target code system code back to SNOMED CT concepts."
    :inputSchema {:type       "object"
                  :properties {:refset_id {:type "integer" :description "Reference set identifier for the map"}
                               :code      {:type "string" :description "Code in the target code system"}}
                  :required   ["refset_id" "code"]}
    :handler     tool-map-from}
   {:name        "map_into"
    :description "Classify concepts into a target set by walking the hierarchy to find the most specific ancestor(s) in the target."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :target     {:type "string" :description "ECL expression or reference set identifier defining the target set"}}
                  :required   ["concept_id" "target"]}
    :handler     tool-map-into}
   {:name        "intersect_ecl"
    :description "Filter a list of SNOMED CT concept identifiers to only those matching a given ECL expression."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :ecl        {:type "string" :description "ECL expression to filter by"}}
                  :required   ["concept_id" "ecl"]}
    :handler     tool-intersect-ecl}
   {:name        "subsumed_by"
    :description "Check whether concept(s) are subsumed by (i.e. a kind of) another concept(s). With arrays, returns true if any pair matches."
    :inputSchema {:type       "object"
                  :properties {:concept_id  concept-id-schema
                               :subsumer_id (assoc concept-id-schema :description "SNOMED CT concept identifier(s) of the potential parent/ancestor(s)")}
                  :required   ["concept_id" "subsumer_id"]}
    :handler     tool-subsumed-by}
   {:name        "paths_to_root"
    :description "Get all IS-A paths from a concept to the SNOMED CT root."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}
    :handler     tool-paths-to-root}
   {:name        "historical"
    :description "Get historical associations for an inactive concept — successor concepts grouped by association type (SAME AS, POSSIBLY EQUIVALENT TO, etc.)."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}
    :handler     tool-historical}
   {:name        "synonym"
    :description "Get the preferred synonym for SNOMED CT concept(s)."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}
    :handler     tool-synonym}
   {:name        "valid_ecl"
    :description "Check whether an ECL expression is syntactically valid without executing it."
    :inputSchema {:type       "object"
                  :properties {:ecl {:type "string" :description "ECL expression to validate"}}
                  :required   ["ecl"]}
    :handler     tool-valid-ecl}
   {:name        "membership"
    :description "Get the reference sets to which a concept belongs, with human-readable names."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}
    :handler     tool-membership}
   {:name        "fully_specified_name"
    :description "Get the fully specified name (FSN) for a SNOMED CT concept, including its semantic tag (e.g. 'disorder', 'finding')."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}
    :handler     tool-fully-specified-name}
   {:name        "descriptions"
    :description "Get all descriptions (terms) for a SNOMED CT concept — FSNs, synonyms, and definitions in all languages."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema}
                  :required   ["concept_id"]}
    :handler     tool-descriptions}
   {:name        "source_historical"
    :description "Find inactive concepts that were historically mapped to a given active concept."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}
    :handler     tool-source-historical}
   {:name        "with_historical"
    :description "Expand concept(s) to include all historical associations — both predecessors and successors."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema}
                  :required   ["concept_id"]}
    :handler     tool-with-historical}
   {:name        "refset_members"
    :description "List all member concept identifiers of a reference set."
    :inputSchema {:type       "object"
                  :properties {:refset_id {:type "integer" :description "Reference set identifier"}}
                  :required   ["refset_id"]}
    :handler     tool-refset-members}
   {:name        "server_info"
    :description "Get installed SNOMED CT releases, supported locales, and available reference sets."
    :inputSchema {:type       "object"
                  :properties {}}
    :handler     tool-server-info}
   {:name        "refsets"
    :description "List all installed reference sets, grouped by type (simple map, language, association, etc.)."
    :inputSchema {:type       "object"
                  :properties {}}
    :handler     tool-refsets}])

(defn tools
  "Return tool definitions for the wire — :name, :description and :inputSchema only."
  []
  (mapv #(dissoc % :handler) tools*))

(def ^:private tools-by-name
  (into {} (map (juxt :name identity)) tools*))

(defn call-tool
  "Call a named MCP tool with the given arguments. Returns Clojure data."
  [svc tool-name arguments]
  (if-let [{:keys [handler]} (tools-by-name tool-name)]
    (handler svc arguments)
    (throw (ex-info (str "Unknown tool: " tool-name) {:tool tool-name}))))

(def ^:private ecl-guide
  "# ECL Quick Reference — SNOMED CT Expression Constraint Language

Hermes supports ECL through v2.2 (November 2023). All keywords are case-insensitive.

## 1. Hierarchy Operators
  <id         Descendants (excluding self)
  <<id        Self and descendants
  <!id        Direct children only
  <<!id       Self and direct children
  >id         Ancestors (excluding self)
  >>id        Self and ancestors
  >!id        Direct parents only
  >>!id       Self and direct parents
  id          Exactly this concept (self)
  *           Any concept (wildcard)

## 2. Logical Operators
  expr AND expr     Intersection — concepts matching both
  expr OR expr      Union — concepts matching either
  expr MINUS expr   Difference — concepts in first but not second
  When mixing operators, use parentheses: (A AND B) OR C

## 3. Member Of (Reference Sets)
  ^refset_id                     Members of a reference set
  ^(refset_id1 OR refset_id2)   Members of multiple reference sets
  ^<<refset_id                  Members of refset or its descendant refsets
  ^*                            Member of any reference set
  ^[referencedComponentId] id   Return specific field(s) from refset rows

## 4. Attribute Refinement
Refinements follow a colon (:) and constrain defining relationships.

  <<404684003: 363698007 |Finding site| = <<39057004 |Pulmonary structure|
  → Clinical findings with a pulmonary finding site

  Comparison operators for concept values: =, !=
  Comparison operators for concrete values: =, !=, <, <=, >, >=

  Conjunction (AND) within refinements uses a comma:
  <<404684003: 363698007 = <<39057004, 116676008 = <<415582006

  Disjunction uses OR:
  <<404684003: 116676008 = <<55641003 OR 42752001 = <<22298006

  Constraint operators on attribute names:
  <<404684003: <<47429007 |Associated with| = <<267038008 |Edema|
  → Matches any subtype of 'Associated with' as the attribute

  Attribute value set (disjunction on value side):
  <<404684003: 246075003 = (<373873005 OR <105590001)

  Nested expression constraints as attribute values:
  <<404684003: 363698007 = (<<39057004: 272741003 = 7771000 |Left|)

## 5. Attribute Groups
  { attr1 = val1, attr2 = val2 }
  → Attributes must co-occur within the same relationship group

  Multiple groups:
  <<404684003: { 363698007 = <<39057004, 116676008 = <<415582006 }
               OR { 363698007 = <<53085002, 116676008 = <<56246009 }

## 6. Cardinality  [min..max]
  [0..0] attr = *      Attribute must be absent
  [1..1] attr = *      Exactly one occurrence
  [1..3] attr = val    Between 1 and 3 occurrences
  [2..*] attr = val    Two or more

  On groups: [1..3] { 127489000 = <105590001 }
  Default (when omitted) is [1..*]

## 7. Concrete Values
  Use # prefix for numeric values, quotes for strings:
  <27658006: 1142135004 |Has presentation strength numerator value| >= #250
  <concept: attr = #5.5          (decimal)
  <concept: attr = \"some text\"   (string)

## 8. Reverse Flag
  R reverses attribute traversal — 'what concepts point to this one?'
  <105590001: [3..3] R 127489000 = *
  → Substances that are the active ingredient of exactly 3 products

## 9. Dot Notation (Attribute Value Navigation)
  Retrieves target values of an attribute for a set of concepts.
  <125605004 |Fracture of bone| . 363698007 |Finding site|
  → Returns all body structures that are finding sites of fracture subtypes

  Chaining (multiple dots):
  <125605004 . 363698007 . 272741003 |Laterality|
  → Retrieves finding sites, then retrieves their laterality values

## 10. Filters  {{ }}
  Filters restrict results by metadata. Multiple filter blocks can be chained.

### Description Filters (default, or {{ D ... }})
  {{ term = \"heart attack\" }}                       Word-prefix-any-order match
  {{ term = wild:\"cardi*opathy\" }}                  Wildcard matching
  {{ term = (\"heart\" \"cardiac\") }}                  Term set (OR logic)
  {{ term != \"fracture\" }}                          Exclude matching terms
  {{ type = syn }}                                  Synonyms only (also: fsn, def)
  {{ language = en }}                               Language filter
  {{ dialect = en-us }}                             Dialect filter
  {{ dialect = en-us (prefer) }}                    Preferred terms only
  {{ dialect = (en-gb (prefer) en-us (accept)) }}   Multiple dialects
  {{ D active = 1 }}                                Active descriptions only
  {{ D moduleId = 731000124108 }}                   By module
  {{ D effectiveTime >= \"20210131\" }}                By effective time

### Concept Filters {{ C ... }}
  {{ C definitionStatus = defined }}    Fully defined concepts only (also: primitive)
  {{ C moduleId = 900000000000207008 }} By module
  {{ C effectiveTime >= \"20210131\" }}   By effective time
  {{ C active = 1 }}                    Active concepts only

### Member Filters {{ M ... }} (with ^ memberOf)
  ^ 447562003 {{ M mapTarget = wild:\"J45*\" }}              ICD-10 codes starting J45
  ^ 447562003 {{ M mapGroup = #2, mapPriority = #1 }}       By map group and priority
  ^ 447562003 {{ M mapTarget = \"J45.9\" }}                   Specific map target

  Combining filters: <<404684003 {{ term = \"heart\" }} {{ C active = 1 }}

## 11. History Supplements  {{ + HISTORY-... }}
  Augment results with inactive concepts via historical associations.
  <<306206005 {{ + HISTORY-MIN }}    SAME AS associations only (high precision)
  <<306206005 {{ + HISTORY-MOD }}    + POSSIBLY EQUIVALENT TO (balanced)
  <<306206005 {{ + HISTORY-MAX }}    All historical associations (high recall)
  <<306206005 {{ + HISTORY }}        Server default (equivalent to MAX)

## 12. Top / Bottom (ECL v2.2)
  !!< (<<73211009)    Leaf concepts — no descendants in the result set
  !!> (<<73211009)    Root concepts — no ancestors in the result set
  Parentheses around the subexpression are required.

## 13. Comments
  /* This is a comment */ <<73211009

## Common Patterns

### All types of diabetes
  <<73211009 |Diabetes mellitus|

### All drugs containing a specific ingredient
  <373873005: 127489000 = <<[ingredient_concept_id]

### Disorders of a body site
  <<404684003: 363698007 = <<[body_site_concept_id]

### Procedures on a body site
  <<71388002: 363704007 |Procedure site| = <<[body_site_concept_id]

### Descendants excluding certain subtypes
  <<73211009 MINUS <<46635009 |Type 1 diabetes|

### Clinical findings with a specific morphology at a specific site
  <<404684003: { 363698007 = <<[body_site], 116676008 = <<[morphology] }

### Primitive concepts in a hierarchy (candidates for further modelling)
  <404684003 {{ C definitionStatus = primitive }}

### Concepts with preferred terms matching a word in a specific dialect
  <<404684003 {{ term = \"heart\", dialect = en-us (prefer) }}

### ICD-10 map entries matching a code pattern
  ^447562003 {{ M mapTarget = wild:\"I2*\" }}

### Value set with historical coverage
  <<73211009 {{ + HISTORY-MOD }}

### Most specific (leaf) concepts in a hierarchy
  !!< (<<73211009)

## Tips
- Concept IDs can be written with or without pipe-delimited terms: 73211009 or 73211009 |Diabetes mellitus|
- Use 'expand_ecl' with 'max_hits' to preview large result sets
- Use 'valid_ecl' to check syntax when building ECL for storage or sharing without expanding
- Use 'intersect_ecl' to test whether specific concepts match an expression
- Alternate identifiers (e.g. LOINC#\"code\") are NOT supported")

(def ^:private concept-model-guide
  "# SNOMED CT Concept Model — Quick Reference

## Semantic Tags (from Fully Specified Names)
Each SNOMED concept has a Fully Specified Name with a semantic tag in parentheses.
Use 'fully_specified_name' to retrieve the FSN and disambiguate concepts.

  (disorder)           — Clinical conditions, diseases, injuries
  (finding)            — Clinical observations and assessments
  (procedure)          — Interventions, operations, therapies
  (body structure)     — Anatomical structures
  (organism)           — Bacteria, viruses, parasites
  (substance)          — Chemical substances, drugs (as ingredients)
  (product)            — Manufactured clinical products and drugs
  (qualifier value)    — Values used to qualify other concepts (e.g., severity, laterality)
  (observable entity)  — Things that can be measured or observed
  (morphologic abnormality) — Types of structural change (inflammation, neoplasm, etc.)
  (situation)          — Clinical situations with context (e.g., \"family history of...\")
  (event)              — Occurrences (e.g., accidents, exposures)
  (physical object)    — Devices, implants
  (specimen)           — Laboratory specimens
  (environment)        — Places and settings

## Key Relationship Types (Attributes)
Use 'properties' to retrieve these for any concept.

### Clinical Findings / Disorders
  363698007  Finding site           — Where in the body
  116676008  Associated morphology  — What structural change (e.g., inflammation, neoplasm)
  246075003  Causative agent        — What causes it (organism, substance)
  370135005  Pathological process   — The underlying process

### Procedures
  363704007  Procedure site         — Where the procedure is performed
  260686004  Method                 — How it is done (e.g., excision, incision)
  405815000  Procedure device       — Device used
  424361007  Using substance        — Substance administered

### Pharmaceutical Products
  127489000  Has active ingredient  — The drug substance(s)
  411116001  Has dose form          — Tablet, capsule, injection, etc.
  732943007  Has basis of strength substance
  732945000  Has presentation strength numerator value

### Evaluation Procedures / Lab Tests
  246093002  Component              — What is measured
  370132008  Scale type             — Quantitative, qualitative, ordinal, etc.
  370130000  Property               — The property measured

## Key Top-Level Hierarchies
  138875005  SNOMED CT Concept (root)
  404684003  Clinical finding
  71388002   Procedure
  123037004  Body structure
  105590001  Substance
  373873005  Pharmaceutical / biologic product
  243796009  Situation with explicit context
  363787002  Observable entity
  78621006   Physical force
  260787004  Physical object
  410607006  Organism
  308916002  Environment or geographical location
  362981000  Qualifier value
  900000000000441003  SNOMED CT Model Component

## Common Reference Set Types
Use 'refsets' to discover installed reference sets.
Use 'server_info' to find specific reference set IDs.

  Simple map reference set        — Maps to other code systems (e.g., ICD-10)
  Language reference set          — Defines preferred/acceptable terms per locale
  Association reference set       — Historical associations (SAME AS, etc.)
  Simple reference set            — Curated subsets of concepts
  Attribute value reference set   — Concept inactivation reasons")

(def ^:private resources*
  [{:uri         "hermes://guides/ecl"
    :name        "ECL Quick Reference"
    :description "SNOMED CT Expression Constraint Language (ECL) v2.2 reference: hierarchy operators, refinements, dot notation, filters, history supplements, cardinality, concrete values, and common patterns"
    :mimeType    "text/plain"
    :content     ecl-guide}
   {:uri         "hermes://guides/concept-model"
    :name        "SNOMED CT Concept Model"
    :description "Quick reference to common SNOMED CT concept types, their semantic tags, and defining attributes"
    :mimeType    "text/plain"
    :content     concept-model-guide}])

(defn resources
  "Return resource definitions for the wire — :uri, :name, :description and :mimeType only."
  []
  (mapv #(dissoc % :content) resources*))

(def ^:private resources-by-uri
  (into {} (map (juxt :uri identity)) resources*))

(defn resource-content
  "Return the content string for a resource URI."
  [uri]
  (if-let [{:keys [content]} (resources-by-uri uri)]
    content
    (throw (ex-info (str "Resource not found: " uri) {:uri uri}))))

(def ^:private prompts*
  [{:name        "clinical_coding"
    :description "Guide through coding a clinical term to SNOMED CT and mapping to ICD-10. Walks step-by-step: search for the concept, verify it, check its properties, and map to the target code system."
    :arguments   [{:name        "clinical_term"
                   :description "The clinical term or diagnosis to code (e.g., 'type 2 diabetes mellitus')"
                   :required    true}
                  {:name        "target_refset_id"
                   :description "Optional: reference set ID for the target code system mapping (e.g., ICD-10). Use 'server_info' to discover available map reference sets."
                   :required    false}]
    :messages-fn (fn [{:strs [clinical_term target_refset_id]}]
                   [{:role "user"
                     :content {:type "text"
                               :text (str "I need to code the following clinical term to SNOMED CT"
                                          (when target_refset_id (str " and then map it to the target code system (reference set " target_refset_id ")"))
                                          ":\n\n\"" clinical_term "\"\n\n"
                                          "Please follow these steps:\n"
                                          "1. Use 'search' to find candidate SNOMED CT concepts for this term\n"
                                          "2. For the best match(es), use 'extended_concept' to verify — check the fully specified name's semantic tag for disambiguation, and review the defining relationships\n"
                                          "3. Use 'properties' if you need more detail on the concept's defining attributes\n"
                                          "4. Use 'paths_to_root' to confirm the concept sits in the right part of the hierarchy\n"
                                          (when target_refset_id
                                            (str "5. Use 'map_to' with refset_id " target_refset_id " to find the corresponding code in the target system\n"
                                                 "6. If no direct mapping exists, explain what ancestor mapping was used\n"))
                                          "\nExplain your reasoning at each step.")}}])}
   {:name        "concept_exploration"
    :description "Systematically explore a SNOMED CT concept: what it is, where it sits in the hierarchy, its defining attributes, related concepts, and available mappings. Good for understanding an unfamiliar concept."
    :arguments   [{:name        "concept_id"
                   :description "The SNOMED CT concept identifier to explore"
                   :required    true}]
    :messages-fn (fn [{:strs [concept_id]}]
                   [{:role "user"
                     :content {:type "text"
                               :text (str "Please explore SNOMED CT concept " concept_id " in detail:\n\n"
                                          "1. Use 'extended_concept' to get the full concept with descriptions, relationships, and reference set memberships — this includes the fully specified name, preferred synonym, all terms, parent/child relationships, and refset IDs\n"
                                          "2. Use 'properties' to see the defining attributes (e.g., finding site, associated morphology, active ingredients)\n"
                                          "3. Use 'paths_to_root' to see where it sits in the full hierarchy\n"
                                          "4. If the concept is inactive, use 'historical' to find its replacement(s)\n"
                                          "\nSummarise what kind of concept this is, its key characteristics, and how it relates to the broader SNOMED CT hierarchy.")}}])}
   {:name        "value_set_construction"
    :description "Guide through building a SNOMED CT value set (a defined subset of concepts) using ECL expressions. Covers choosing the right ancestor concept, applying attribute filters, and validating the result set."
    :arguments   [{:name        "clinical_domain"
                   :description "The clinical domain for the value set (e.g., 'all types of diabetes', 'cardiac procedures')"
                   :required    true}]
    :messages-fn (fn [{:strs [clinical_domain]}]
                   [{:role "user"
                     :content {:type "text"
                               :text (str "I need to build a SNOMED CT value set for: \"" clinical_domain "\"\n\n"
                                          "Please follow these steps:\n"
                                          "1. Use 'search' to find the most appropriate ancestor concept for this domain\n"
                                          "2. Use 'fully_specified_name' to verify it has the right semantic tag\n"
                                          "3. Draft an ECL expression (e.g., <<concept_id for all descendants)\n"
                                          "4. Use 'expand_ecl' with a max_hits limit to preview the results\n"
                                          "5. If too broad, refine using:\n"
                                          "   - Attribute constraints (e.g., filtering by finding site or morphology)\n"
                                          "   - MINUS to exclude certain subtypes\n"
                                          "   - Filters (e.g., {{ C definitionStatus = defined }} or {{ term = \"...\", dialect = en-us (prefer) }})\n"
                                          "6. If the value set needs to match historical/legacy coded data, add a history supplement: {{ + HISTORY-MIN }} for high precision or {{ + HISTORY-MOD }} for balanced recall\n"
                                          "7. Present the final ECL expression and a sample of matching concepts\n"
                                          "\nRead 'hermes://guides/ecl' for full ECL syntax. Explain your reasoning at each refinement step.")}}])}])

(defn prompts
  "Return prompt definitions for the wire — :name, :description and :arguments only."
  []
  (mapv #(dissoc % :messages-fn) prompts*))

(def ^:private prompts-by-name
  (into {} (map (juxt :name identity)) prompts*))

(defn get-prompt
  "Look up a prompt by name and return its description and generated messages.
  `arguments` is a map with string keys (as received from JSON).
  Returns {:description ... :messages [...]}."
  [prompt-name arguments]
  (if-let [{:keys [description messages-fn]} (prompts-by-name prompt-name)]
    {:description description
     :messages    (messages-fn arguments)}
    (throw (ex-info (str "Prompt not found: " prompt-name) {:prompt prompt-name}))))
