(ns com.eldrix.hermes.verhoeff-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [com.eldrix.hermes.verhoeff :refer [append calculate valid?]]))

(deftest valid-verhoeffs
  (is (= 3 (calculate 236)))
  (is (valid? 2363))
  (is (= 3 (calculate "236")))
  (is (valid? "2363"))
  (is (= 6 (calculate 31122019000)))
  (is (= "311220190006" (append "31122019000")))
  (is (= "311220190006" (append 31122019000)))
  (is (valid? "311220190006"))
  (is (valid? "1234567890"))
  (is (valid? "14567894")))

(deftest invalid-verhoeffs
  (is (not (valid? "311220190007")))
  (is (not (valid? "1234567891")))
  (is (not (valid? "14567895"))))

(comment
  (run-tests))