(ns com.eldrix.hermes.expression
  (:require [clojure.java.io :as io]
            [instaparse.core :as insta]
            [net.cgrand.enlive-html :as enlive]
            [clojure.xml :as xml]
            [clojure.data.zip.xml :as zx]
            [clojure.walk :as walk]
            [clojure.zip :as zip]))

(def cg-parser
  (insta/parser (io/resource "cg-v2.4--edited.abnf") :input-format :abnf :output-format :enlive))

(def ecl-parser
  (insta/parser (io/resource "ecl.abnf") :input-format :abnf :output-format :enlive))

;;
(defn coelesce-digits
  [exp]
  (walk/postwalk
    (fn [x] (let [tag (if (vector? x) (first x) nil)]
              (cond
                (= tag :term) {:term (apply str (map second (rest x)))}
                (= tag :sctId) {:sctId (apply str (map second (rest x)))}
                (not (nil? tag)) {tag (rest x)}
                :else x)))
    exp))

(comment
  (def p
    (cg-parser "24700007"))

  (def root (zip/xml-zip p))
  (zx/xml1-> root :expression :subExpression :focusConcept :conceptReference :conceptId :sctId zx/text)

  (insta/transform {
                    :sctId (comp clojure.edn/read-string str)
                    :term  (comp str)
                    } p)
  (def expressions
    ["24700007"
     "24700007 |Multiple sclerosis|"
     "   421720008 |Spray dose form|  +  7946007 |Drug suspension|"
     "73211009 |Diabetes mellitus|"
     " 83152002 |Oophorectomy| :  405815000 |Procedure device|  =  122456005 |Laser device|"])

  (def results (doseq [exp expressions]
                 ((insta/transform
                    {
                     :sctId str
                     :term  (fn [& more] [:term (apply str more)])
                     }
                    (cg-parser exp)))))

  (def ms (first results))





  (def p
    (cg-parser "24700007"))
  ((enlive/select p [:sctId])
   )
  )
