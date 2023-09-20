(ns com.eldrix.hermes.server-test
  (:require [clojure.data.json :as json]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.eldrix.hermes.cmd.server :as server]
            [com.eldrix.hermes.core :as hermes]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.test :refer [response-for]]))

(def url-for
  (route/url-for-routes server/routes))

(def ^:dynamic *svc* nil)
(def ^:dynamic *server-fn* nil)

(defn live-test-fixture [f]
  (let [svc (hermes/open "snomed.db")]
    (binding [*svc* svc  ;; bindings are performed in parallel, unlike `let`
              *server-fn* (::http/service-fn (server/create-server svc {}))]
      (stest/with-instrument-disabled (f))  ;; turn off instrumentation, as we will be using erroneous input
      (hermes/close *svc*))))

(use-fixtures :once live-test-fixture)

(deftest ^:live get-concept
  (is (= 200 (:status (response-for *server-fn* :get (url-for ::server/get-concept :path-params {:concept-id "24700007"})))))
  (is (= 200 (:status (response-for *server-fn*
                                    :get (url-for ::server/get-concept :path-params {:concept-id "80146002"})
                                    :headers {"Accept" "application/edn"}))))
  (is (= 404 (:status (response-for *server-fn* :get (url-for ::server/get-concept :path-params {:concept-id "123"})))))
  (is (= 404 (:status (response-for *server-fn* :get (url-for ::server/get-concept :path-params {:concept-id "abc"}))))))

(deftest ^:live get-concept-descriptions
  (is (= 200 (:status (response-for *server-fn* :get (url-for ::server/get-concept-descriptions :path-params {:concept-id "24700007"})))))
  (is (= 404 (:status (response-for *server-fn* :get (url-for ::server/get-concept-descriptions :path-params {:concept-id "123"}))))))

(deftest ^:live get-preferred
  (let [en-GB (response-for *server-fn*
                            :get (url-for ::server/get-concept-preferred-description :path-params {:concept-id "80146002"})
                            :headers {"Accept-Language" "en-GB"})
        en-US (response-for *server-fn*
                            :get (url-for ::server/get-concept-preferred-description :path-params {:concept-id "80146002"})
                            :headers {"Accept-Language" "en-US"})]
    (is (= 200 (:status en-GB)))
    (is (= 200 (:status en-US)))
    (is (= "Appendicectomy" (get (json/read-str (:body en-GB)) "term")))
    (is (= "Appendectomy" (get (json/read-str (:body en-US)) "term")))))

(deftest ^:live get-extended-concept
  (is (= 200 (:status (response-for *server-fn* :get (url-for ::server/get-extended-concept :path-params {:concept-id "24700007"})))))
  (is (= 404 (:status (response-for *server-fn* :get (url-for ::server/get-extended-concept :path-params {:concept-id "123"}))))))

(deftest ^:live get-properties
  (is (= 200 (:status (response-for *server-fn* :get (url-for ::server/get-concept-properties :path-params {:concept-id "24700007"})))))
  (is (= 404 (:status (response-for *server-fn* :get (url-for ::server/get-concept-properties :path-params {:concept-id "123"}))))))

(comment
  (def *svc* (hermes/open "snomed.db"))
  (def *server-fn* (::http/service-fn (server/create-server *svc* {})))
  (response-for *server-fn* :get (url-for ::server/get-concept :path-params {:concept-id "24700007"}))
  (:status (response-for *server-fn* :get (url-for ::server/get-concept :path-params {:concept-id 24700007}))))
