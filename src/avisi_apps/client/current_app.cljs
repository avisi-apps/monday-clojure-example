(ns avisi-apps.client.current-app
  (:require [com.fulcrologic.fulcro.application :as app]
            [com.fulcrologic.fulcro.components :as comp]))

(defonce app (atom nil))
(defonce root (atom nil))
(defonce node-id (atom nil))

(defn  mount! [app' root' node-id']
  (reset! app app')
  (reset! root root')
  (reset! node-id node-id')
  (app/mount! app' root' node-id'))

(defn ^:export refresh! []
  (when @app
    (app/mount! @app @root @node-id)
    ;; As of Fulcro 3.3.0, this addition will help with stale queries when using dynamic routing:
    (comp/refresh-dynamic-queries! @app)
    (js/console.log "Hot reload")))
