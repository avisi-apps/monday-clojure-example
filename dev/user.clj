(ns user
  (:require
    [avisi-apps.server.main :as server]
    [mount.core :as mount :refer [stop]]
    [clojure.tools.namespace.repl :as tn]))

(defn start []
  (mount/start)
  :ready)

(defn reset []
  (stop)
  (tn/refresh :after 'dev/go))
