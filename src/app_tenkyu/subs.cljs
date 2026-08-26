(ns app-tenkyu.subs
  "Subscriptions. Thin on purpose: each one delegates to a pure function in
  `app-tenkyu.db`, so what the UI sees is testable without re-frame."
  (:require [re-frame.core :as rf]
            [app-tenkyu.db :as db]))

(rf/reg-sub ::view :-> :view)
(rf/reg-sub ::camera :-> :camera)
(rf/reg-sub ::backend :-> :backend)
(rf/reg-sub ::now (fn [d _] (or (get-in d [:clock :now]) 0)))
(rf/reg-sub ::basemap :-> :basemap)
(rf/reg-sub ::layers db/layer-summary)
(rf/reg-sub ::unavailable db/unavailable-kinds)
(rf/reg-sub ::visible-objects db/visible-objects)

(rf/reg-sub
 ::page-model
 :<- [::view] :<- [::backend] :<- [::layers] :<- [::unavailable] :<- [::now]
 (fn [[view backend layers unavailable now] _]
   {:view view :backend backend :layers layers
    :unavailable unavailable :now now}))
