(ns com.eldrix.hermes.lang-test
  (:require [clojure.test :refer [deftest is]]
            [com.eldrix.hermes.impl.language :as lang]))


(deftest test-folding
  (is (= "fi" (lang/fold "en" "ﬁ")))
  (is (= "nodulo hepatico" (lang/fold "es" "nódulo hepático")))
  (is (= "nodulo hepatico" (lang/fold 450828004 "nódulo hepático")))
  (is (= "hjarta" (lang/fold "en" "hjärta")) "Diacritic ä should be stripped in English")
  (is (= "hjarta" (lang/fold 999001251000000103 "hjärta")) "Diacritic ä should be stripped in English")
  (is (= "hjärta" (lang/fold "sv" "hjärta")) "Diacritic ä should be preserved in Swedish")
  (is (= "Lasegues test" (lang/fold "en" "Laségues test")) "Diacritic é should be stripped in English")
  (is (= "Lasegues test" (lang/fold "sv" "Laségues test")) "Diacritic é should be stripped in Swedish")
  (is (= "Spælsau sheep breed" (lang/fold "en" "Spælsau sheep breed")) "'æ' should not be folded"))