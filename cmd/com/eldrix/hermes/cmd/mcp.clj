; Copyright (c) 2020-2026 Mark Wardle and Eldrix Ltd
;
; This program and the accompanying materials are made available under the
; terms of the Eclipse Public License 2.0 which is available at
; http://www.eclipse.org/legal/epl-2.0.
;
; SPDX-License-Identifier: EPL-2.0
;;;;
(ns com.eldrix.hermes.cmd.mcp
  "A native MCP (Model Context Protocol) server for hermes.
  Communicates via stdio using newline-delimited JSON-RPC 2.0."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging.readable :as log]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.io BufferedReader InputStreamReader PrintStream)))

(set! *warn-on-reflection* true)

(def ^:private concept-id-schema
  "Schema for concept_id: accepts a single integer or an array of integers."
  {:oneOf [{:type "integer"} {:type "array" :items {:type "integer"}}]
   :description "SNOMED CT concept identifier(s)"})

(def ^:private accept-language-schema
  "Schema for accept_language parameter."
  {:type "string" :description "BCP 47 / RFC 3066 Accept-Language header value (e.g. 'en-GB', 'en-US,en;q=0.9'). Defaults to server locale."})

(def tools
  [{:name        "search"
    :description "Search for SNOMED CT concepts by clinical term. Results are ranked by relevance and returned even when not all tokens match. Best for identifying which concept a clinical term refers to. Use 'constraint' to limit by ECL expression — e.g., query='heart attack', constraint='<404684003' to search within clinical findings only. Use 'autocomplete' instead when building up a search interactively token by token."
    :inputSchema {:type       "object"
                  :properties {:query           {:type "string" :description "Search text"}
                               :constraint      {:type "string" :description "SNOMED ECL constraint expression to filter results"}
                               :max_hits        {:type "integer" :description "Maximum number of results (default 10)"}
                               :accept_language accept-language-schema}
                  :required   ["query"]}}
   {:name        "autocomplete"
    :description "Search for SNOMED CT concepts with autocompletion semantics — all tokens must match and results are suitable for interactive type-ahead. Use 'constraint' to limit by ECL. Use 'search' instead when looking for the best match for a complete clinical term."
    :inputSchema {:type       "object"
                  :properties {:query           {:type "string" :description "Search text"}
                               :constraint      {:type "string" :description "SNOMED ECL constraint expression to filter results"}
                               :max_hits        {:type "integer" :description "Maximum number of results (default 200)"}
                               :fuzzy           {:type "integer" :description "Fuzziness level 0-2 (default 0)"}
                               :fallback_fuzzy  {:type "integer" :description "Fallback fuzziness if no results found at primary level (0-2)"}
                               :accept_language accept-language-schema}
                  :required   ["query"]}}
   {:name        "concept"
    :description "Get a SNOMED CT concept record: id, effectiveTime, active, moduleId, and definitionStatusId. A lightweight way to check whether a concept exists and its active/inactive status. Use 'extended_concept' for the full picture including descriptions and relationships. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema}
                  :required   ["concept_id"]}}
   {:name        "extended_concept"
    :description "Get a SNOMED CT concept with all of its descriptions, parent/child relationships, concrete values, and reference set memberships. Includes the preferred synonym. Use 'concept' for just the concept record. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}}
   {:name        "properties"
    :description "Get the defining properties (attributes/relationships) of a SNOMED CT concept, with human-readable labels. For example, a drug's active ingredients, dose form, and strength; or a clinical finding's associated morphology and finding site. Set 'expand' to true to include transitive relationships. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :expand     {:type "boolean" :description "Include transitive relationships (default false)"}}
                  :required   ["concept_id"]}}
   {:name        "expand_ecl"
    :description "Expand a SNOMED CT Expression Constraint Language expression to its matching concepts. Common patterns: '<73211009' for all types of diabetes (descendants), '<<73211009' for diabetes including the concept itself (self and descendants), '>73211009' for ancestors. Attribute refinement: '<404684003: 363698007 = <<39057004' for clinical findings with a pulmonary finding site. Set: '^refset_id' for reference set members. Set 'include_historic' to true to include now-inactive concepts. Read resource 'hermes://guides/ecl' for full ECL syntax reference."
    :inputSchema {:type       "object"
                  :properties {:ecl              {:type "string" :description "ECL expression to expand"}
                               :max_hits         {:type "integer" :description "Maximum number of results (unlimited if omitted)"}
                               :include_historic {:type "boolean" :description "Include inactive historical concepts (default false)"}
                               :accept_language  accept-language-schema}
                  :required   ["ecl"]}}
   {:name        "transitive_synonyms"
    :description "Get all synonyms (terms) used for a concept and all of its descendants. Useful for understanding the full vocabulary of a clinical domain — e.g., all the ways any type of diabetes might be referred to. You must provide either a concept_id or an ECL expression."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :ecl        {:type "string" :description "ECL expression selecting concepts"}}
                  :oneOf      [{:required ["concept_id"]} {:required ["ecl"]}]}}
   {:name        "map_to"
    :description "Map a SNOMED CT concept to a target code system (e.g. ICD-10). Returns reference set items containing the target codes. If the concept is not directly mapped, walks up the hierarchy to find the nearest mapped ancestor. Use 'server_info' to discover available map reference sets. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :refset_id  {:type "integer" :description "Reference set identifier for the target map (e.g. 999002271000000101 for UK ICD-10)"}}
                  :required   ["concept_id" "refset_id"]}}
   {:name        "map_from"
    :description "Reverse map from a target code system code (e.g. an ICD-10 code) back to SNOMED CT concepts. Returns reference set items for matching concepts. Use 'server_info' to discover available map reference sets."
    :inputSchema {:type       "object"
                  :properties {:refset_id {:type "integer" :description "Reference set identifier for the map"}
                               :code      {:type "string" :description "Code in the target code system"}}
                  :required   ["refset_id" "code"]}}
   {:name        "map_into"
    :description "Classify SNOMED CT concepts into a target set by walking the hierarchy. For each source concept, finds the most specific ancestor(s) present in the target. The target can be an ECL expression (e.g. '118940003 OR 50043002' for broad disease categories) or a reference set identifier. Useful for reducing granularity for analytics or categorisation. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :target     {:type "string" :description "ECL expression or reference set identifier defining the target set"}}
                  :required   ["concept_id" "target"]}}
   {:name        "intersect_ecl"
    :description "From a list of SNOMED CT concept identifiers, return only those that match the given ECL expression. For example, given a patient's list of diagnosis concept IDs, use ecl='<<73211009' to find which are types of diabetes, or ecl='<404684003: 363698007 = <<39057004' to find which have a pulmonary finding site. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema
                               :ecl        {:type "string" :description "ECL expression to filter by"}}
                  :required   ["concept_id" "ecl"]}}
   {:name        "subsumed_by"
    :description "Check whether SNOMED CT concept(s) are subsumed by (i.e. are a kind of) another concept or concepts. For example, check whether 'multiple sclerosis' is a kind of 'demyelinating disease of the CNS'. Accepts a single concept_id or an array of concept_ids, and a single subsumer_id or an array of subsumer_ids. When arrays are given, returns true if ANY concept is subsumed by ANY subsumer."
    :inputSchema {:type       "object"
                  :properties {:concept_id  concept-id-schema
                               :subsumer_id {:oneOf [{:type "integer"} {:type "array" :items {:type "integer"}}]
                                             :description "SNOMED CT concept identifier(s) of the potential parent/ancestor(s)"}}
                  :required   ["concept_id" "subsumer_id"]}}
   {:name        "paths_to_root"
    :description "Get all paths from a concept to the SNOMED CT root, showing the full ontological hierarchy. Each path is a sequence of concept identifiers from the concept up to the root. Invaluable for understanding what kind of thing a concept is — e.g., seeing that 'appendicectomy' is a 'procedure on digestive system' which is a 'surgical procedure' which is a 'procedure'. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}}
   {:name        "historical"
    :description "Get historical associations for an inactive or deprecated SNOMED CT concept. Returns successor concepts grouped by association type (SAME AS, POSSIBLY EQUIVALENT TO, etc.). Use when a concept identifier is inactive and you need to find its current replacement(s). Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}}
   {:name        "synonym"
    :description "Get the preferred synonym and active status for one or more SNOMED CT concepts. A lightweight alternative to 'concept' when you only need the human-readable name. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}}
   {:name        "valid_ecl"
    :description "Check whether a SNOMED CT Expression Constraint Language expression is syntactically valid, without executing it. Use before 'expand_ecl' to verify your ECL is well-formed. Read resource 'hermes://guides/ecl' for ECL syntax reference."
    :inputSchema {:type       "object"
                  :properties {:ecl {:type "string" :description "ECL expression to validate"}}
                  :required   ["ecl"]}}
   {:name        "membership"
    :description "Get the reference set identifiers to which a concept belongs, with human-readable names. Useful for understanding a concept's context — is it in the ICD-10 map? A particular drug subset? An assessment scale? Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}}
   {:name        "fully_specified_name"
    :description "Get the fully specified name (FSN) for a SNOMED CT concept. The FSN includes a semantic tag in parentheses that disambiguates the concept's meaning — e.g., 'Multiple sclerosis (disorder)', 'Cold (qualifier value)', 'Cold (finding)'. The semantic tag reveals what kind of entity the concept is: disorder, procedure, substance, body structure, finding, etc. Invaluable for disambiguation when a clinical term is ambiguous. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}}
   {:name        "descriptions"
    :description "Get all descriptions (terms) for a SNOMED CT concept — including fully specified names, synonyms, and definitions in all available languages. Unlike 'synonym' (which returns only the single preferred term), this returns every term that can refer to the concept. Useful for entity linking, understanding all the ways a concept may appear in clinical text, or finding translations. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema}
                  :required   ["concept_id"]}}
   {:name        "source_historical"
    :description "Find all inactive/deprecated concepts that historically pointed to a given active concept. The reverse of 'historical': given a current active concept, discover which old concept identifiers were retired and mapped to it (via SAME AS, POSSIBLY EQUIVALENT TO, etc.). Useful for understanding the history of a concept or building backward-compatible lookups that accept legacy codes. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id      concept-id-schema
                               :accept_language accept-language-schema}
                  :required   ["concept_id"]}}
   {:name        "with_historical"
    :description "Return a set of SNOMED CT concept identifiers that includes the input concepts together with all historical associations — both predecessors (inactive concepts that map to these) and successors (active concepts that inactive ones map to). Essential for building robust value sets that work across SNOMED CT release versions. For example, expanding a list of diagnoses to catch records coded with now-retired concept IDs. Accepts a single concept_id or an array."
    :inputSchema {:type       "object"
                  :properties {:concept_id concept-id-schema}
                  :required   ["concept_id"]}}
   {:name        "refset_members"
    :description "List all member concept identifiers of a given reference set. For example, get all concepts in a particular drug subset or clinical finding list. Returns a set of SNOMED CT concept identifiers. Use 'refsets' or 'server_info' to discover available reference set identifiers."
    :inputSchema {:type       "object"
                  :properties {:refset_id {:type "integer" :description "Reference set identifier"}}
                  :required   ["refset_id"]}}
   {:name        "server_info"
    :description "Get information about the installed SNOMED CT content: which releases are loaded, which locales are supported, and which reference sets are available (with their names). Use this to discover map reference set identifiers for 'map_to' and 'map_from'."
    :inputSchema {:type       "object"
                  :properties {}}}
   {:name        "refsets"
    :description "List all installed reference sets, grouped by type (e.g. simple map, language, association). Each group includes the type concept name and all member reference sets with their names. Useful for discovering what reference sets are available and understanding their categories."
    :inputSchema {:type       "object"
                  :properties {}}}])

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

(defn- map-to-one [svc id refset_id]
  (if-let [items (seq (hermes/component-refset-items svc id refset_id))]
    (vec items)
    (let [mapped-ids (hermes/map-concept-into svc id refset_id)]
      (vec (mapcat #(hermes/component-refset-items svc % refset_id) mapped-ids)))))

(defn- tool-map-to [svc {:keys [concept_id refset_id]}]
  (for-concept concept_id #(map-to-one svc % refset_id)))

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

(defn- paths-to-root-one [svc id lang]
  (mapv (fn [path]
          (mapv (fn [pid] {:id pid :term (:term (hermes/preferred-synonym* svc pid lang))})
                path))
        (hermes/paths-to-root svc id)))

(defn- tool-paths-to-root [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id #(paths-to-root-one svc % lang))))

(defn- historical-one [svc id lang]
  (mapv (fn [[refset-id items]]
          {:associationType refset-id
           :associationName (:term (hermes/preferred-synonym* svc refset-id lang))
           :targets         (mapv (fn [item]
                                    {:conceptId (:targetComponentId item)
                                     :term      (:term (hermes/preferred-synonym* svc (:targetComponentId item) lang))
                                     :active    (:active item)})
                                  items)})
        (hermes/historical-associations svc id)))

(defn- tool-historical [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id #(historical-one svc % lang))))

(defn- concept-synonym [svc concept-id lang]
  {:id concept-id :term (:term (hermes/preferred-synonym* svc concept-id lang)) :active (:active (hermes/concept svc concept-id))})

(defn- tool-synonym [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id #(concept-synonym svc % lang))))

(defn- tool-valid-ecl [_svc {:keys [ecl]}]
  {:valid (hermes/valid-ecl? ecl)})

(defn- membership-one [svc id lang]
  (mapv (fn [rid] {:id rid :name (:term (hermes/preferred-synonym* svc rid lang))})
        (hermes/component-refset-ids svc id)))

(defn- tool-membership [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id #(membership-one svc % lang))))

(defn- concept-fsn [svc concept-id accept_language]
  (let [fsn (hermes/fully-specified-name svc concept-id accept_language)]
    {:id concept-id :term (:term fsn) :active (:active (hermes/concept svc concept-id))}))

(defn- tool-fully-specified-name [svc {:keys [concept_id accept_language]}]
  (for-concept concept_id #(concept-fsn svc % accept_language)))

(defn- tool-descriptions [svc {:keys [concept_id]}]
  (for-concept concept_id #(hermes/descriptions svc %)))

(defn- source-historical-one [svc id lang]
  (mapv (fn [[refset-id concept-ids]]
          {:associationType refset-id
           :associationName (:term (hermes/preferred-synonym* svc refset-id lang))
           :sources         (mapv (fn [src-id]
                                    {:conceptId src-id
                                     :term      (:term (hermes/preferred-synonym* svc src-id lang))
                                     :active    (:active (hermes/concept svc src-id))})
                                  concept-ids)})
        (hermes/source-historical-associations svc id)))

(defn- tool-source-historical [svc {:keys [concept_id accept_language]}]
  (let [lang (hermes/match-locale svc accept_language true)]
    (for-concept concept_id #(source-historical-one svc % lang))))

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

;;
;; MCP Resources
;;

(def resources
  [{"uri"         "hermes://guides/ecl"
    "name"        "ECL Quick Reference"
    "description" "SNOMED CT Expression Constraint Language (ECL) syntax cheat sheet with common patterns and examples"
    "mimeType"    "text/plain"}
   {"uri"         "hermes://guides/concept-model"
    "name"        "SNOMED CT Concept Model"
    "description" "Quick reference to common SNOMED CT concept types, their semantic tags, and defining attributes"
    "mimeType"    "text/plain"}])

(def ^:private ecl-guide
  "# ECL Quick Reference — SNOMED CT Expression Constraint Language

## Basic Operators
  <concept_id       Descendants (excluding self)
  <<concept_id      Self and descendants
  >concept_id       Ancestors (excluding self)
  >>concept_id      Self and ancestors
  concept_id        Exactly this concept (self)
  *                 Any concept

## Logical Operators
  expr1 AND expr2   Intersection — concepts matching both
  expr1 OR expr2    Union — concepts matching either
  expr1 MINUS expr2 Difference — concepts in first but not second

## Attribute Constraints
  <404684003 |Clinical finding|: 363698007 |Finding site| = <<39057004 |Pulmonary structure|
  → All clinical findings with a finding site in the pulmonary structure hierarchy

  <373873005 |Pharmaceutical / biologic product|: 127489000 |Has active ingredient| = <<387207008 |Ibuprofen|
  → All drugs containing ibuprofen or a subtype

## Attribute Groups
  { attr1 = val1, attr2 = val2 }
  → Attributes within the same group (co-occurring in the same relationship group)

## Cardinality
  [0..0] 363698007 |Finding site| = *
  → Concepts with NO finding site defined

  [1..1] 127489000 |Has active ingredient| = *
  → Concepts with exactly one active ingredient

## Refinement
  <<404684003: 116676008 |Associated morphology| = <<23583003 |Inflammation|
  → Clinical findings with inflammatory morphology

## Common Patterns

### All types of diabetes
  <<73211009 |Diabetes mellitus|

### All drugs containing a specific ingredient
  <373873005: 127489000 = <<[ingredient_concept_id]

### Disorders of a body site
  <<404684003: 363698007 = <<[body_site_concept_id]

### Procedures on a body site
  <<71388002 |Procedure|: 363704007 |Procedure site| = <<[body_site_concept_id]

### Descendants excluding certain subtypes
  <<73211009 MINUS <<46635009 |Type 1 diabetes|

### Members of a reference set
  ^[refset_id]

## Tips
- Concept IDs can be written with or without pipe-delimited terms: 73211009 or 73211009 |Diabetes mellitus|
- Use 'valid_ecl' to check syntax before 'expand_ecl'
- Use 'expand_ecl' with 'max_hits' to preview large result sets
- Use 'intersect_ecl' to test whether specific concepts match an expression")

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

(defn- resource-content [uri]
  (case uri
    "hermes://guides/ecl"           ecl-guide
    "hermes://guides/concept-model" concept-model-guide
    (throw (ex-info (str "Resource not found: " uri) {:uri uri}))))

;;
;; MCP Prompts
;;

(def prompts
  [{"name"        "clinical_coding"
    "description" "Guide through coding a clinical term to SNOMED CT and mapping to ICD-10. Walks step-by-step: search for the concept, verify it, check its properties, and map to the target code system."
    "arguments"   [{"name"        "clinical_term"
                    "description" "The clinical term or diagnosis to code (e.g., 'type 2 diabetes mellitus')"
                    "required"    true}
                   {"name"        "target_refset_id"
                    "description" "Optional: reference set ID for the target code system mapping (e.g., ICD-10). Use 'server_info' to discover available map reference sets."
                    "required"    false}]}
   {"name"        "concept_exploration"
    "description" "Systematically explore a SNOMED CT concept: what it is, where it sits in the hierarchy, its defining attributes, related concepts, and available mappings. Good for understanding an unfamiliar concept."
    "arguments"   [{"name"        "concept_id"
                    "description" "The SNOMED CT concept identifier to explore"
                    "required"    true}]}
   {"name"        "value_set_construction"
    "description" "Guide through building a SNOMED CT value set (a defined subset of concepts) using ECL expressions. Covers choosing the right ancestor concept, applying attribute filters, and validating the result set."
    "arguments"   [{"name"        "clinical_domain"
                    "description" "The clinical domain for the value set (e.g., 'all types of diabetes', 'cardiac procedures')"
                    "required"    true}]}])

(defn- prompt-messages [prompt-name arguments]
  (case prompt-name
    "clinical_coding"
    (let [term (get arguments "clinical_term")
          refset (get arguments "target_refset_id")]
      [{"role" "user"
        "content" {"type" "text"
                   "text" (str "I need to code the following clinical term to SNOMED CT"
                               (when refset (str " and then map it to the target code system (reference set " refset ")"))
                               ":\n\n\"" term "\"\n\n"
                               "Please follow these steps:\n"
                               "1. Use 'search' to find candidate SNOMED CT concepts for this term\n"
                               "2. Review the results and pick the best match, using 'fully_specified_name' to check the semantic tag if the term is ambiguous\n"
                               "3. Use 'extended_concept' or 'properties' to verify the concept is correct by checking its defining attributes\n"
                               "4. Use 'paths_to_root' to confirm the concept sits in the right part of the hierarchy\n"
                               (when refset
                                 (str "5. Use 'map_to' with refset_id " refset " to find the corresponding code in the target system\n"
                                      "6. If no direct mapping exists, explain what ancestor mapping was used\n"))
                               "\nExplain your reasoning at each step.")}}])

    "concept_exploration"
    (let [concept-id (get arguments "concept_id")]
      [{"role" "user"
        "content" {"type" "text"
                   "text" (str "Please explore SNOMED CT concept " concept-id " in detail:\n\n"
                               "1. Use 'fully_specified_name' to get the full name with semantic tag\n"
                               "2. Use 'synonym' to get the preferred term\n"
                               "3. Use 'descriptions' to see all available terms/synonyms\n"
                               "4. Use 'properties' to see the defining attributes\n"
                               "5. Use 'paths_to_root' to see where it sits in the hierarchy\n"
                               "6. Use 'membership' to see which reference sets it belongs to\n"
                               "7. Check if it's active; if not, use 'historical' to find its replacement(s)\n"
                               "\nSummarise what kind of concept this is, its key characteristics, and how it relates to the broader SNOMED CT hierarchy.")}}])

    "value_set_construction"
    (let [domain (get arguments "clinical_domain")]
      [{"role" "user"
        "content" {"type" "text"
                   "text" (str "I need to build a SNOMED CT value set for: \"" domain "\"\n\n"
                               "Please follow these steps:\n"
                               "1. Use 'search' to find the most appropriate ancestor concept for this domain\n"
                               "2. Use 'fully_specified_name' to verify it has the right semantic tag\n"
                               "3. Draft an ECL expression (e.g., <<concept_id for all descendants)\n"
                               "4. Use 'valid_ecl' to verify the syntax\n"
                               "5. Use 'expand_ecl' with a max_hits limit to preview the results\n"
                               "6. If too broad, refine using attribute constraints (e.g., filtering by finding site or morphology)\n"
                               "7. If you need to exclude certain subtypes, use MINUS\n"
                               "8. Present the final ECL expression and a sample of matching concepts\n"
                               "\nExplain your reasoning at each refinement step.")}}])

    (throw (ex-info (str "Prompt not found: " prompt-name) {:prompt prompt-name}))))

(defn- call-tool [svc tool-name arguments]
  (case tool-name
    "search"               (tool-search svc arguments)
    "autocomplete"         (tool-autocomplete svc arguments)
    "concept"              (tool-concept svc arguments)
    "extended_concept"     (tool-extended-concept svc arguments)
    "properties"           (tool-properties svc arguments)
    "expand_ecl"           (tool-expand-ecl svc arguments)
    "transitive_synonyms"  (tool-transitive-synonyms svc arguments)
    "map_to"               (tool-map-to svc arguments)
    "map_from"             (tool-map-from svc arguments)
    "map_into"             (tool-map-into svc arguments)
    "intersect_ecl"        (tool-intersect-ecl svc arguments)
    "subsumed_by"          (tool-subsumed-by svc arguments)
    "paths_to_root"        (tool-paths-to-root svc arguments)
    "historical"           (tool-historical svc arguments)
    "synonym"              (tool-synonym svc arguments)
    "valid_ecl"            (tool-valid-ecl svc arguments)
    "membership"           (tool-membership svc arguments)
    "fully_specified_name" (tool-fully-specified-name svc arguments)
    "descriptions"         (tool-descriptions svc arguments)
    "source_historical"    (tool-source-historical svc arguments)
    "with_historical"      (tool-with-historical svc arguments)
    "refset_members"       (tool-refset-members svc arguments)
    "server_info"          (tool-server-info svc arguments)
    "refsets"              (tool-refsets svc arguments)
    (throw (ex-info (str "Unknown tool: " tool-name) {:tool tool-name}))))

(defn- json-rpc-response [id result]
  {"jsonrpc" "2.0" "id" id "result" result})

(defn- json-rpc-error [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(defn- write-message! [msg]
  (let [s (json/write-str msg)]
    (.write *out* ^String s)
    (.write *out* "\n")
    (.flush *out*)))

(def server-info
  {"protocolVersion" "2024-11-05"
   "capabilities"    {"tools"     {}
                      "resources" {}
                      "prompts"   {}}
   "serverInfo"      {"name"    "hermes"
                      "version" (try (:version (edn/read-string (slurp (io/resource "version.edn"))))
                                     (catch Exception _ "unknown"))}})

(defn- handle-initialize [id _params]
  (json-rpc-response id server-info))

(defn- handle-ping [id _params]
  (json-rpc-response id {}))

(defn- handle-tools-list [id _params]
  (json-rpc-response id {"tools" tools}))

(defn- handle-resources-list [id _params]
  (json-rpc-response id {"resources" resources}))

(defn- handle-resources-read [id params]
  (let [uri (get params "uri")]
    (try
      (json-rpc-response id
                         {"contents" [{"uri"      uri
                                       "mimeType" "text/plain"
                                       "text"     (resource-content uri)}]})
      (catch Exception e
        (json-rpc-response id
                           {"contents" [{"uri"      uri
                                         "mimeType" "text/plain"
                                         "text"     (str "Error: " (ex-message e))}]})))))

(defn- handle-prompts-list [id _params]
  (json-rpc-response id {"prompts" prompts}))

(defn- handle-prompts-get [id params]
  (let [prompt-name (get params "name")
        arguments   (get params "arguments" {})]
    (try
      (json-rpc-response id
                         {"description" (:description (first (filter #(= prompt-name (get % "name")) prompts)))
                          "messages"    (prompt-messages prompt-name arguments)})
      (catch Exception e
        (json-rpc-error id -32602 (ex-message e))))))

(defn- handle-tools-call [svc id params]
  (let [tool-name (get params "name")
        arguments (update-keys (get params "arguments" {}) keyword)]
    (try
      (let [result (call-tool svc tool-name arguments)]
        (json-rpc-response id
                           {"content" [{"type" "text"
                                        "text" (json/write-str result)}]}))
      (catch Exception e
        (json-rpc-response id
                           {"content" [{"type" "text"
                                        "text" (str "Error: " (ex-message e))}]
                            "isError" true})))))

(defn dispatch [svc {:strs [method id params]}]
  (case method
    "initialize"                (handle-initialize id params)
    "ping"                      (handle-ping id params)
    "tools/list"                (handle-tools-list id params)
    "tools/call"                (handle-tools-call svc id params)
    "resources/list"            (handle-resources-list id params)
    "resources/read"            (handle-resources-read id params)
    "prompts/list"              (handle-prompts-list id params)
    "prompts/get"               (handle-prompts-get id params)
    "notifications/initialized" nil
    "notifications/cancelled"   nil
    (when id
      (json-rpc-error id -32601 (str "Method not found: " method)))))

(defn start!
  "Start the MCP server, reading JSON-RPC messages from stdin and writing
  responses to stdout. Logs to stderr. Blocks until stdin is closed."
  [svc]
  (log/info "starting MCP server")
  (let [out-stream System/out
        rdr (BufferedReader. (InputStreamReader. System/in))]
    ;; Redirect System/out to stderr so stray prints don't corrupt the JSON-RPC stream.
    (System/setOut (PrintStream. System/err true))
    (try
      (binding [*out* (java.io.OutputStreamWriter. out-stream)]
        (loop []
          (when-let [line (.readLine rdr)]
            (when-not (str/blank? line)
              (try
                (let [msg (json/read-str line)]
                  (when-let [response (dispatch svc msg)]
                    (write-message! response)))
                (catch Exception e
                  (log/error e "error processing MCP message")
                  (try
                    (write-message! (json-rpc-error nil -32700 "Parse error"))
                    (catch Exception _)))))
            (recur))))
      (finally
        (System/setOut (PrintStream. out-stream true))))))
