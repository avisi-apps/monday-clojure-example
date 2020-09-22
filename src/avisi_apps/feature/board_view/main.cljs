(ns avisi-apps.feature.board-view.main
  (:require [avisi-apps.client.current-app :as curr-app]
            [avisi-apps.client.monday-api :as monday]
            [com.fulcrologic.fulcro.application :as app]
            [cljs-bean.core :as bean]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [goog.object :as gobj]
            [com.fulcrologic.fulcro.mutations :refer [defmutation]]
            [com.fulcrologic.fulcro.dom :as dom]))

(defmutation set-background-color [{:keys [background-color]}]
  (action [{:keys [state]}]
    (swap! state assoc :background-color background-color)))

(defonce app (app/fulcro-app))

(defsc Root [this {:keys [background-color]}]
  {:query [:background-color]
   :initial-state {:background-color "#fff"}}
  (dom/div {:style {:backgroundColor (or background-color "#fff")}}
    (dom/h1 "Our custom board")))

(defn handle-settings-change! [{:keys [background-color] :as data}]
  (when background-color
    (comp/transact! app [(set-background-color {:background-color background-color})])))

(defn ^:export init []
  (monday/listen! ["settings"] (fn [e]
                                 (handle-settings-change!
                                   (bean/bean (gobj/get e "data")))))
  (curr-app/mount! app Root "app")
  (js/console.log "Loaded"))
