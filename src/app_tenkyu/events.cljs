(ns app-tenkyu.events
  "re-frame events: the only place a request is made or a clock is read.

  Everything these events compute is computed by a pure function in
  `app-tenkyu.db`, `.scene` or `.propagate`. What lives here is the effect
  -- the fetch, the timer, the GPU upload -- because that is what re-frame
  is for and what a test cannot hold."
  (:require [re-frame.core :as rf]
            [app-tenkyu.db :as db]
            [app-tenkyu.propagate :as prop]
            [app-tenkyu.route :as route]))

(rf/reg-event-db ::init (fn [_ _] db/default-db))

(rf/reg-event-db ::set-view (fn [d [_ v]] (assoc d :view v)))
(rf/reg-event-db ::set-backend (fn [d [_ b]] (assoc d :backend b)))
(rf/reg-event-db ::tick (fn [d [_ now]] (assoc-in d [:clock :now] now)))

(rf/reg-event-db ::drag (fn [d [_ dx dy]] (update d :camera db/drag-camera dx dy)))
(rf/reg-event-db ::zoom (fn [d [_ delta]] (update d :camera db/zoom-camera delta)))

(rf/reg-event-db
 ::fly-to
 (fn [d [_ {:keys [lat lon]}]]
   ;; Straight there, no animation. An eased flight is nicer and is also a
   ;; second place for the camera to live; this moves the one camera the
   ;; render loop already reads.
   (update d :camera (fn [c] (db/clamp-camera
                              (assoc c :lat-deg lat :lon-deg lon
                                     :distance db/street-level-distance))))))

;; ---------------------------------------------------------------- fetch

(rf/reg-fx
 ::fetch-json
 (fn [{:keys [url on-ok on-fail]}]
   (-> (js/fetch url)
       (.then (fn [r]
                (-> (.json r)
                    (.then (fn [j]
                             (if (.-ok r)
                               (rf/dispatch (conj on-ok (js->clj j :keywordize-keys true)))
                               ;; The body of a refusal carries the reason the
                               ;; Worker gave. Discarding it and keeping only
                               ;; the status is how "vessels were never
                               ;; ingested" becomes "something went wrong".
                               (rf/dispatch (conj on-fail
                                                  {:status (.-status r)
                                                   :body (js->clj j :keywordize-keys true)}))))))))
       (.catch (fn [e] (rf/dispatch (conj on-fail {:status 0
                                                   :body {:detail (.-message e)}})))))))

(rf/reg-event-fx
 ::load-layer
 (fn [{:keys [db]} [_ kind]]
   {:db (assoc-in db [:layers kind :status] :loading)
    ::fetch-json {:url (str "/api/objects/" (name kind))
                  :on-ok [::layer-loaded kind]
                  :on-fail [::layer-failed kind]}}))

(rf/reg-event-db
 ::layer-loaded
 (fn [d [_ kind payload]]
   (if (= :satellite kind)
     ;; Element sets are initialized ONCE and propagated per frame. Doing it
     ;; per frame would re-run sgp4init for every satellite sixty times a
     ;; second to get the same record back.
     (let [{:keys [ready refused]} (prop/initialize (:objects payload))]
       (-> d
           (assoc-in [:layers kind] {:status :loaded
                                     :sats ready
                                     :objects []
                                     :refused refused
                                     :count* (count ready)
                                     :as-of (:as-of payload)
                                     :snapshot-id (:snapshot-id payload)})))
     (assoc-in d [:layers kind]
               {:status :loaded
                :objects (mapv (fn [o]
                                 {:id (:id o)
                                  :kind kind
                                  :lat-deg (:lat o)
                                  :lon-deg (:lon o)
                                  :alt-km (:alt o)
                                  :observed-at (:t o)
                                  :attrs (:attrs o)
                                  :size (case kind :quake 7.0 :fire 5.0 3.5)})
                               (filter #(and (:lat %) (:lon %)) (:objects payload)))
                :refused []
                :as-of (:as-of payload)
                :snapshot-id (:snapshot-id payload)}))))

(rf/reg-event-db
 ::layer-failed
 (fn [d [_ kind {:keys [status body]}]]
   ;; :unavailable, never :loaded with nothing in it. This is the whole
   ;; distinction the app exists to preserve.
   (assoc-in d [:layers kind]
             {:status :unavailable
              :objects [] :refused []
              :detail (or (:detail body) (:error body) (str "HTTP " status))})))

(rf/reg-event-fx
 ::load-all
 (fn [_ _]
   {:fx (mapv (fn [k] [:dispatch [::load-layer k]]) db/kinds)}))

(rf/reg-event-fx
 ::load-basemap
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:basemap :status] :loading)
    ::fetch-json {:url "/api/basemap"
                  :on-ok [::basemap-loaded]
                  :on-fail [::basemap-failed]}}))

(rf/reg-event-fx
 ::load-buildings
 (fn [_ _]
   {::fetch-json {:url "/api/buildings"
                  :on-ok [::buildings-loaded]
                  :on-fail [::buildings-failed]}}))

(rf/reg-event-db ::buildings-loaded
                 (fn [d [_ m]] (assoc d :buildings {:status :loaded
                                                    :areas (:areas m)})))
(rf/reg-event-db ::buildings-failed
                 (fn [d [_ e]] (assoc d :buildings {:status :unavailable
                                                    :areas []
                                                    :detail (get-in e [:body :detail])})))

(rf/reg-event-db ::basemap-loaded
                 (fn [d [_ m]] (assoc d :basemap {:status :loaded :manifest m})))
(rf/reg-event-db ::basemap-failed
                 (fn [d [_ e]] (assoc d :basemap {:status :unavailable
                                                  :detail (get-in e [:body :detail])})))

;; ---------------------------------------------------------------- frame

(rf/reg-event-db
 ::advance
 (fn [d [_ now]]
   ;; Satellites are recomputed from their elements; everything else keeps
   ;; the position it was observed at. That difference is the reason the
   ;; satellite table is a thousand times smaller than the aircraft one.
   (let [sats (get-in d [:layers :satellite :sats])
         d (assoc-in d [:clock :now] now)]
     (if (seq sats)
       (let [{:keys [objects failed]} (prop/positions sats now)]
         (-> d
             (assoc-in [:layers :satellite :objects] objects)
             ;; REPLACED, not accumulated. `into` here would append the same
             ;; stale satellites sixty times a second and the refusal count
             ;; would climb forever -- a counter that only goes up reads as a
             ;; worsening problem rather than as a constant one.
             (assoc-in [:layers :satellite :stale] failed)))
       d))))
