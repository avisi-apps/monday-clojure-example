(ns avisi-apps.client.monday-api
  (:require ["monday-sdk-js" :as monday-sdk]))

(defonce monday (monday-sdk))

(defn get-all-users! []
  (->
    (.api ^js monday "query { users { id, name } }")
    (.then (fn [res]
             (js/console.log res)))))

(defn listen! [events cb]
  (.listen ^js monday (apply array events) cb))

(comment

  (get-all-users!))
