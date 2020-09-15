(ns com.eldrix.hermes.expression
  (:require [clojure.java.io :as io]
            [instaparse.core :as insta]))

(defonce cg-parser
         (insta/parser (io/resource "cg.abnf") :input-format :abnf))

(defonce ecl-parser
         (insta/parser (io/resource "ecl-long.abnf") :input-format :abnf))

(comment
  (cg-parser "24700007")
  (ecl-parser "<<24700007")
  )