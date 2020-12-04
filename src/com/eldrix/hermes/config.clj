(ns com.eldrix.hermes.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [com.eldrix.hermes.terminology :as terminology]
            [com.eldrix.hermes.server :as server]))

(defmethod ig/init-key :terminology/service [_ {:keys [path]}]
  (terminology/open-service path))

(defmethod ig/halt-key! :terminology/service [_ svc]
  (terminology/close-service svc))

(defmethod ig/init-key :http/server [_ {:keys [svc port]}]
  (server/start-server svc port false))

(defmethod ig/halt-key! :http/server [_ sv]
  (server/stop-server sv))

(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(defn config [profile]
  (aero/read-config (io/resource "config.edn") {:profile profile}))


(defn prep [profile]
  (let [conf (config profile)]
    (ig/load-namespaces (:ig/system conf))))

(comment
  (prep :dev)
  (config :dev)
  (def system (ig/init (:ig/system (config :dev))))
  (ig/halt! system)
  )