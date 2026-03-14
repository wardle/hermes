(ns com.eldrix.hermes.server-test
  (:require [clojure.data.json :as json]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.eldrix.hermes.cmd.server :as server]
            [com.eldrix.hermes.core :as hermes]
            [io.pedestal.connector.test :refer [response-for]]
            [io.pedestal.http.route :as route]))

(def ^:dynamic *svc* nil)
(def ^:dynamic *connector* nil)
(def ^:dynamic *url-for* nil)

(defn live-test-fixture [f]
  (let [svc (hermes/open "snomed.db")]
    (binding [*svc* svc
              *connector* (server/create-connector svc {})
              *url-for* (route/url-for-routes (route/expand-routes (server/routes svc)))]
      (stest/with-instrument-disabled (f))  ;; turn off instrumentation, as we will be using erroneous input
      (hermes/close *svc*))))

(use-fixtures :once live-test-fixture)

(deftest ^:live get-concept
  (is (= 200 (:status (response-for *connector* :get (*url-for* ::server/get-concept :path-params {:concept-id "24700007"})))))
  (is (= 200 (:status (response-for *connector*
                                    :get (*url-for* ::server/get-concept :path-params {:concept-id "80146002"})
                                    :headers {"accept" "application/edn"}))))
  (is (= 404 (:status (response-for *connector* :get (*url-for* ::server/get-concept :path-params {:concept-id "123"})))))
  (is (= 404 (:status (response-for *connector* :get (*url-for* ::server/get-concept :path-params {:concept-id "abc"}))))))

(deftest ^:live get-concept-descriptions
  (is (= 200 (:status (response-for *connector* :get (*url-for* ::server/get-concept-descriptions :path-params {:concept-id "24700007"})))))
  (is (= 404 (:status (response-for *connector* :get (*url-for* ::server/get-concept-descriptions :path-params {:concept-id "123"}))))))

(deftest ^:live get-preferred
  (let [en-GB (response-for *connector*
                            :get (*url-for* ::server/get-concept-preferred-description :path-params {:concept-id "80146002"})
                            :headers {"accept-language" "en-GB"})
        en-US (response-for *connector*
                            :get (*url-for* ::server/get-concept-preferred-description :path-params {:concept-id "80146002"})
                            :headers {"accept-language" "en-US"})]
    (is (= 200 (:status en-GB)))
    (is (= 200 (:status en-US)))
    (is (= "Appendicectomy" (get (json/read-str (:body en-GB)) "term")))
    (is (= "Appendectomy" (get (json/read-str (:body en-US)) "term")))))

(deftest ^:live get-extended-concept
  (is (= 200 (:status (response-for *connector* :get (*url-for* ::server/get-extended-concept :path-params {:concept-id "24700007"})))))
  (is (= 404 (:status (response-for *connector* :get (*url-for* ::server/get-extended-concept :path-params {:concept-id "123"}))))))

(deftest ^:live get-properties
  (is (= 200 (:status (response-for *connector* :get (*url-for* ::server/get-concept-properties :path-params {:concept-id "24700007"})))))
  (is (= 404 (:status (response-for *connector* :get (*url-for* ::server/get-concept-properties :path-params {:concept-id "123"}))))))

(deftest ^:live expression-subsumes
  (let [subsumes-url (*url-for* ::server/expression-subsumes)]
    (testing "valid subsumption — parent subsumes child"
      (let [resp (response-for *connector* :get (str subsumes-url "?a=6118003&b=24700007"))]
        (is (= 200 (:status resp)))
        (is (= "subsumes" (get (json/read-str (:body resp)) "outcome")))))
    (testing "valid subsumption — concept subsumes itself"
      (let [resp (response-for *connector* :get (str subsumes-url "?a=24700007&b=24700007"))]
        (is (= 200 (:status resp)))
        (is (= "equivalent" (get (json/read-str (:body resp)) "outcome")))))
    (testing "missing parameter a"
      (is (= 400 (:status (response-for *connector* :get (str subsumes-url "?b=24700007"))))))
    (testing "missing parameter b"
      (is (= 400 (:status (response-for *connector* :get (str subsumes-url "?a=24700007"))))))
    (testing "non-existent concept returns 400"
      (is (= 400 (:status (response-for *connector* :get (str subsumes-url "?a=24700007&b=100000102"))))))
    (testing "invalid concept ID (bad check digit) returns 400"
      (is (= 400 (:status (response-for *connector* :get (str subsumes-url "?a=24700007&b=24700001"))))))))

(comment
  (def *svc* (hermes/open "snomed.db"))
  (def *connector* (server/create-connector *svc* {}))
  (response-for *connector* :get (*url-for* ::server/get-concept :path-params {:concept-id "24700007"}))
  (:status (response-for *connector* :get (*url-for* ::server/get-concept :path-params {:concept-id 24700007}))))
