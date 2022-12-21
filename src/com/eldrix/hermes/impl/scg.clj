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
(ns com.eldrix.hermes.impl.scg
  "Support for SNOMED CT compositional grammar.
  See http://snomed.org/scg"
  (:require [clojure.data.zip.xml :as zx]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.zip :as zip]
            [com.eldrix.hermes.impl.language :as lang]
            [com.eldrix.hermes.impl.store :as store]
            [instaparse.core :as insta]))

(def cg-parser
  (insta/parser (io/resource "cg-v2.4.abnf") :input-format :abnf :output-format :enlive))

(defn- parse-sctId [sctId]
  (edn/read-string (zx/xml1-> sctId zx/text)))

(defn- parse-conceptId [conceptId]
  (zx/xml1-> conceptId :sctId parse-sctId))

(defn- parse-concept-reference [cr]
  (let [conceptId (zx/xml1-> cr :conceptId parse-conceptId)
        term (zx/xml1-> cr :term zx/text)]
    (merge {:conceptId conceptId}
           (when term {:term term}))))

(defn- parse-focus-concept [focus-concept]
  (zx/xml-> focus-concept :conceptReference parse-concept-reference))

(defn- parse-attribute-name
  "attributeName = conceptReference"
  [attribute-name]
  (zx/xml1-> attribute-name :conceptReference parse-concept-reference))

(declare parse-subexpression)

(defn- parse-expression-value
  "expressionValue = conceptReference / '(' ws subExpression ws ')'"
  [expression-value]
  (let [conceptReference (zx/xml1-> expression-value :conceptReference parse-concept-reference)
        subExpression (zx/xml1-> expression-value :subExpression parse-subexpression)]
    (if conceptReference conceptReference subExpression)))


(defn- parse-attribute-value
  "attributeValue = expressionValue / QM stringValue QM / '#' numericValue / booleanValue"
  [attribute-value]
  (let [expressionValue (zx/xml1-> attribute-value :expressionValue parse-expression-value)
        stringValue (zx/xml1-> attribute-value :stringValue zx/text)
        numericValue (zx/xml1-> attribute-value :numericValue zx/text)
        booleanValue (zx/xml1-> attribute-value :booleanValue zx/text)]
    (cond
      expressionValue expressionValue
      stringValue stringValue
      numericValue (edn/read-string numericValue)
      booleanValue [(Boolean/parseBoolean booleanValue)]))) ;; hide boolean in vector so we don't break zipper

(defn- parse-attribute
  "attribute = attributeName ws \"=\" ws attributeValue"
  [attribute]
  {(zx/xml1-> attribute :attributeName parse-attribute-name)
   (zx/xml1-> attribute :attributeValue parse-attribute-value)})

(defn- parse-attribute-set
  "attributeSet = attribute *(ws \",\" ws attribute)"
  [attribute-set]
  (apply merge (zx/xml-> attribute-set :attribute parse-attribute)))

(defn- parse-attribute-group
  "attributeGroup = '{' ws attributeSet ws '}'"
  [attribute-group]
  (zx/xml-> attribute-group :attributeSet parse-attribute-set))

(defn- parse-refinement
  "refinement = (attributeSet / attributeGroup) *( ws [\",\" ws] attributeGroup )"
  [refinement]
  (let [attributeSet (zx/xml-> refinement :attributeSet parse-attribute-set)
        attributeGroup (zx/xml-> refinement :attributeGroup parse-attribute-group)]
    (concat
      (when (seq attributeSet) attributeSet)
      (when (seq attributeGroup) attributeGroup))))


(defn- parse-subexpression
  "subExpression = focusConcept [ws \":\" ws refinement]"
  [subexpression]
  (let [focusConcept {:focusConcepts (zx/xml-> subexpression :focusConcept parse-focus-concept)}
        refinements (zx/xml-> subexpression :refinement parse-refinement)]
    (if (seq refinements) (merge focusConcept {:refinements refinements})
                          focusConcept)))

(defn- parse-expression [expression]
  (let [ds (zx/xml1-> expression :definitionStatus zx/text)]
    {:definitionStatus (if ds ds "===")
     :subExpression    (zx/xml1-> expression :subExpression parse-subexpression)}))

(defn parse
  "Parse a SNOMED-CT expression, as defined by the compositional grammar.
  See https://confluence.ihtsdotools.org/display/DOCSCG/Compositional+Grammar+-+Specification+and+Guide"
  [s] (zx/xml1-> (zip/xml-zip (cg-parser s)) :expression parse-expression))

(defn- simplify-focus-concepts
  [node]
  (into #{} (map :conceptId (:focusConcepts node))))

(defn- simplify-refinement
  [node]
  (if (map? node)
    (zipmap (map :conceptId (keys node)) (map #(if (map? %) (dissoc % :term) %) (vals node)))
    node))

(defn simplify
  "Simplify a SNOMED CT expression by removing content that does not aid computability."
  [expression]
  (walk/prewalk
    (fn [node]
      (if (map? node)
        (cond-> node
                (contains? node :focusConcepts) (assoc :focusConcepts (simplify-focus-concepts node))
                (contains? node :refinements) (assoc :refinements (map simplify-refinement (:refinements node)))
                true (dissoc :term))
        node))
    expression))

(defn- render-concept
  [config concept]
  (str (:conceptId concept)
       (when-not (:hide-terms? config)
         (let [has-term (or (:term concept) (:get-preferred-synonym-fn config))]
           (str
             (when has-term "|")
             (if-let [f (:get-preferred-synonym-fn config)]
               (or (:term (f (:conceptId concept))) (:term concept))
               (:term concept))
             (when has-term "|"))))))

(declare render-subexpression)

(defn- render-value
  [config value]
  (cond
    (map? value) (cond
                   (get value :conceptId) (render-concept config value)
                   (get value :focusConcepts) (str "(" (render-subexpression config value) ")")
                   :else (throw (ex-info (str "** unknown value:'" value "' **") {:error "Unknown value" :value value})))
    (number? value) (str "#" value)
    (boolean? value) (str value)
    :else (str "\"" value "\"")))


(defn- render-refinement-set
  [config refinements]

  (let [k (map (partial render-concept config) (keys refinements))
        v (map (partial render-value config) (vals refinements))]
    (str/join "," (map (fn [k1 v1] (str/join "=" [k1 v1])) k v))))

(defn- render-refinements
  [config refinements]
  (case (count refinements)
    0 ""
    1 (render-refinement-set config (first refinements))
    (str "{" (str/join "} {" (map (partial render-refinement-set config) refinements)) "}")))

(defn- render-subexpression
  [config subexp]
  (let [focus-concepts (str/join "+" (map (partial render-concept config) (:focusConcepts subexp)))
        refinements (:refinements subexp)]
    (if refinements (str focus-concepts ":" (render-refinements config refinements)) focus-concepts)))

(defn render
  "Render an expression into string form.
  Parameters:
  - st            : SNOMED store
  - hide-terms?   : do not include textual terms in output
  - update-terms? : update terms for the preferred synonyms in locale(s) specified.
  - locale-priorities : list of locale priorities (e.g. \"en-GB\")"
  [{:keys [store update-terms? locale-priorities] :as config} exp]
  (let [lang-refsets (when store (lang/match store locale-priorities))
        cfg (if (and store update-terms? (seq lang-refsets))
              (assoc config :get-preferred-synonym-fn (fn [concept-id] (store/get-preferred-synonym store concept-id lang-refsets)))
              config)]
    (str (:definitionStatus exp) " " (render-subexpression cfg (:subExpression exp)))))


(comment)
