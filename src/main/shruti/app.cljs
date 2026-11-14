(ns shruti.app
  (:require [reagent.dom.client :as rdom]
            [shruti.ui.root :as ui]))

(defonce !root (atom nil))

(defn- render! []
  (let [root (or @!root
                 (let [element (.getElementById js/document "app")
                       next-root (rdom/create-root element)]
                   (reset! !root next-root)
                   next-root))]
    (rdom/render root [ui/root])))

(defn init [] (render!))
(defn reload! [] (render!))

