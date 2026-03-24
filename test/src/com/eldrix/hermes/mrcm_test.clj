(ns com.eldrix.hermes.mrcm-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [com.eldrix.hermes.core :as hermes]
            [com.eldrix.hermes.impl.mrcm :as mrcm]
            [com.eldrix.hermes.impl.scg :as scg]
            [com.eldrix.hermes.snomed :as snomed]))

(def ^:private expression-error-cases
  [{:description "Simple concept — no refinements"
    :expression  "24700007"
    :expected    nil}
   {:description "Finding with grouped finding site (SNOMED Starter Guide)"
    :expression  "73211009 : { 363698007 = 39057004 }"
    :expected    nil}
   {:description "Procedure with grouped method + site (SNOMED Starter Guide)"
    :expression  "80146002 : { 260686004 = 129304002 , 405813007 = 181255000 }"
    :expected    nil}
   {:description "Oophorectomy with procedure device (CG examples)"
    :expression  "83152002 : { 405815000 = 122456005 }"
    :expected    nil}
   {:description "Burn with grouped morphology + site + causative agent (WASP paper)"
    :expression  "284196006 : { 116676008 = 80247002 , 363698007 = 83738005 , 246075003 = 47448006 }"
    :expected    nil}

{:description "Non-existent focus concept"
    :expression  "100000102"
    :expected    :concept-not-found}
   {:description "Non-existent attribute type"
    :expression  "73211009 : 100000102 = 39057004"
    :expected    :concept-not-found}
   {:description "Non-existent attribute value"
    :expression  "73211009 : { 363698007 = 100000102 }"
    :expected    :concept-not-found}

{:description "Non-attribute concept used as attribute"
    :expression  "367430006 : { 24028007 = 272741003 }"
    :expected    :attribute-invalid}

{:description "Finding site on procedure — wrong domain (SNOMED Postcoordination Guide)"
    :expression  "80146002 : { 363698007 = 181255000 }"
    :expected    :attribute-not-in-domain}
   {:description "Method on clinical finding — wrong domain"
    :expression  "73211009 : { 260686004 = 129304002 }"
    :expected    :attribute-not-in-domain}

{:description "Clinical finding as finding site value — wrong range"
    :expression  "73211009 : { 363698007 = 73211009 }"
    :expected    :value-out-of-range}
   {:description "Body structure as morphology value — wrong range"
    :expression  "22298006 : { 116676008 = 39057004 }"
    :expected    :value-out-of-range}

{:description "Grouped attribute used ungrouped"
    :expression  "73211009 : 363698007 = 39057004"
    :expected    :attribute-must-be-grouped}
   {:description "Ungrouped attribute used in group — Has disposition on Water"
    :expression  "11713004 : { 726542003 = 726711005 }"
    :expected    :attribute-must-be-ungrouped}])

(deftest ^:live expression-errors
  (with-open [svc (hermes/open "snomed.db")]
    (doseq [{:keys [description expression expected]} expression-error-cases]
      (testing description
        (let [errs (mrcm/expression-errors svc (scg/str->ctu expression) snomed/PostcoordinatedContent)
              ret-spec (:ret (s/get-spec `mrcm/expression-errors))]
          (is (s/valid? ret-spec errs)
              (str "Return value must conform to ret spec: " (pr-str errs)))
          (if (nil? expected)
            (is (nil? errs))
            (is (some #(= expected (:error %)) errs))))))))

(deftest ^:live domains
  (with-open [svc (hermes/open "snomed.db")]
    (is (seq (mrcm/domains svc)) "Should return at least one MRCM domain")))
