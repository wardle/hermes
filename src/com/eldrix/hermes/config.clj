(ns com.eldrix.hermes.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [com.eldrix.hermes.server :as server]
            [com.eldrix.hermes.service :as svc]
            [com.eldrix.hermes.terminology :as terminology]
            [integrant.core :as ig])
  (:import (com.eldrix.hermes.service SnomedService)))

(defmethod ig/init-key :terminology/service [_ {:keys [path]}]
  (terminology/open path))

(defmethod ig/halt-key! :terminology/service [_ ^SnomedService svc]
  (terminology/close svc))

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