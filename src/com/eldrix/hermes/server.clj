(ns com.eldrix.hermes.server
  (:require [clojure.tools.logging.readable :as log]
            [cheshire.core :as json]
            [cheshire.generate :as json-gen]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor.error :as errintc]
            [io.pedestal.http.content-negotiation :as conneg]
            [com.eldrix.hermes.snomed :as snomed])
  (:import (java.time.format DateTimeFormatter)
           (java.time LocalDate)
           (com.fasterxml.jackson.core JsonGenerator)
           (com.eldrix.hermes.terminology SnomedService)))

(set! *warn-on-reflection* true)

(def supported-types ["text/html" "application/edn" "application/json" "text/plain"])
(def content-neg-intc (conneg/negotiate-content supported-types))

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))
(def not-found (partial response 404))

(defn not-found []
  {:status 404
   :body   "Not found\n"})

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "application/json"))

(json-gen/add-encoder LocalDate
                      (fn [^LocalDate o ^JsonGenerator out]
                     (.writeString out (.format (DateTimeFormatter/ISO_DATE) o))))

(defn transform-content
  [body content-type]
  (case content-type
    "text/html" body
    "text/plain" body
    "application/edn" (pr-str body)
    "application/json" (json/generate-string body)))

(defn coerce-to
  [response content-type]
  (-> response
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-body
  {:name ::coerce-body
   :leave
         (fn [context]
           (if (get-in context [:response :headers "Content-Type"])
             context
             (update-in context [:response] coerce-to (accepted-type context))))})

(defonce snomed-service (atom nil))

(def svc-interceptor
  {:name  :service-interceptor
   :enter (fn [context] (update context :request assoc :service @snomed-service))})

(def entity-render
  {:name :entity-render
   :leave
         (fn [context]
           (if-let [item (:result context)]
             (assoc context :response (ok item))
             context))})

(def service-error-handler
  (errintc/error-dispatch
    [context err]
    [{:exception-type :java.lang.NumberFormatException :interceptor ::get-search}]
    (assoc context :response {:status 400
                              :body   (str "Invalid search parameters; invalid number: " (ex-message (:exception (ex-data err))))})
    [{:exception-type :java.lang.IllegalArgumentException :interceptor ::get-search}]
    (assoc context :response {:status 400 :body (str "invalid search parameters: " (ex-message (:exception (ex-data err))))})

    :else
    (assoc context :io.pedestal.interceptor.chain/error err)))

(def get-concept
  {:name  ::get-concept
   :enter (fn [context]
            (if-let [concept-id (Long/parseLong (get-in context [:request :path-params :concept-id]))]
              (if-let [concept (.getConcept ^SnomedService (get-in context [:request :service]) concept-id)]
                (assoc context :result concept))))})

(defn make-search-params [context]
  (let [{:keys [s maxHits isA] :as params} (get-in context [:request :params])]
    (log/info "Parsing parameters:" params)
    (cond-> {}
            s (assoc :s s)
            maxHits (assoc :max-hits (Integer/parseInt maxHits))
            (string? isA) (assoc :properties {snomed/IsA (Long/parseLong isA)})
            (vector? isA) (assoc :properties {snomed/IsA (into [] (map #(Long/parseLong %) isA))}))))

(def get-search
  {:name  ::get-search
   :enter (fn [context]
            (let [params (make-search-params context)
                  ^SnomedService svc (get-in context [:request :service])]
              (log/info "search params: " params)
              (when (= (:max-hits params) 0) (throw (IllegalArgumentException. "invalid parameter: 0 maxHits")))
              (assoc context :result (.search svc params))))})

(def routes
  (route/expand-routes
    #{["/v1/snomed/concepts/:concept-id" :get [coerce-body content-neg-intc entity-render svc-interceptor get-concept]]
      ["/v1/snomed/search" :get [service-error-handler coerce-body content-neg-intc entity-render svc-interceptor get-search]]}))

(def server-config
  {::http/routes routes
   ::http/type   :jetty
   ::http/port   8081})

(defn start-server [svc port]
  (reset! snomed-service svc)
  (http/start (http/create-server (assoc server-config ::http/port port))))

(defonce server (atom nil))

(defn start-dev []
  (reset! server (http/start (http/create-server (assoc server-config ::http/join? false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart []
  (stop-dev)
  (start-dev))

(comment
  (start-dev)
  (stop-dev)
  (restart)
  (reset! snomed-service (com.eldrix.hermes.terminology/open-service "snomed.db"))
  )