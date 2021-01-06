(ns avisi-apps.client.monday-api
  (:require
    ["monday-sdk-js" :as monday-sdk]
    [goog.object :as gobj]
    [com.wsscode.pathom.graphql :refer [query->graphql]]
    [cljs-bean.core :refer [bean ->js ->clj]]))

(defonce monday (monday-sdk))

(defn listen! [events cb]
  (.listen ^js monday (apply array events) cb))

(defn execute! [type args]
  (.execute ^js monday type (->js args)))

(defn get-all-users! []
  (->
    (.api ^js monday (query->graphql [{:users [:id :name]}]))
    (.then (fn [res]
             (js/console.log res)))))

(defn get-boards! [ids]
  (->
    (.api ^js monday (query->graphql [(list {:boards [{(list :items {:limit 1})
                                                       [:id
                                                        :name
                                                        {:column_values [:title :text]}]}]}
                                        {:ids ids})]))
    (.then (fn [res]
             (js/console.log (->clj res))))))

(comment

  (get-all-users!)

  (get-boards! [749796977])

  (-> (.execute ^js monday "confirm"
        (->js {:message "Are you sure??"
               :confirmButton "Let's go"
               :cancelButton "No way"
               :excludeCancelButton false}))
    (.then (fn [res]
             (js/console.log :res res))))

  (execute! "notice"
    {:message "Success!!!!!"
     :type "success"
     :timeout 10000})

  (execute! "openItemCard"
    {:itemId "765413571"})

  (listen! ["context"]
    (fn [res]
      (let [data (->clj (gobj/get res "data"))]
        (js/console.log data))))

  (query->graphql [{[:boards 123] [:name]}])

  (query->graphql '[(:foo {:with "params"})])

  (println (query->graphql '[({:boards [:id {:items [:name {:column-values [:title :text]}]}]}
                              {:last [1 2 3]})]))

  (query->graphql [(list {:boards [:id {:items [:name {:column-values [:title :text]}]}]}
                     {:ids [1 2 3]})])

  )
