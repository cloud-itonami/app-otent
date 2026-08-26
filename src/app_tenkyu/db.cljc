(ns app-tenkyu.db
  "The app database, and the pure functions over it.

  Pure `.cljc`: every question the UI asks -- what is loaded, what
  failed, what has not been measured, how old the data is -- is answered
  here and testable without a browser or a GPU.

  ## A layer has four states, not two

  | | |
  |---|---|
  | `:idle` | not requested yet |
  | `:loading` | requested, no answer |
  | `:loaded` | answered, `n` rows (which may be 0) |
  | `:unavailable` | asked and could not be answered, with a reason |

  `:unavailable` is the one that earns its place. The ingest actor reports
  fires and vessels as UNMEASURED because nothing has ever read those
  feeds; the API turns that into a 404; and the UI has to say *that*
  rather than draw an empty sky. Collapsing it into `:loaded` with zero
  rows would make 'no vessels are broadcasting' and 'nobody is listening'
  the same picture."
  (:require [clojure.string :as str]))

(def kinds [:satellite :quake :aircraft :fire :vessel])

(def default-db
  {:view :globe
   :camera {:lat-deg 20.0 :lon-deg 140.0 :distance 3.2 :fov-deg 45.0}
   :backend nil
   :clock {:now nil :rate 1.0 :playing? true}
   :layers (into {} (map (fn [k] [k {:status :idle :objects [] :refused []}])) kinds)
   :basemap {:status :idle :manifest nil}
   :selected nil})

(defn layer [db k] (get-in db [:layers k]))

(defn layer-summary
  "One line per kind for the UI, including the kinds that are not there."
  [db]
  (for [k kinds
        :let [{:keys [status objects refused as-of snapshot-id detail count*]} (layer db k)]]
    {:kind k
     :status status
     :count (or count* (clojure.core/count objects))
     :refused (clojure.core/count refused)
     :as-of as-of
     :snapshot-id snapshot-id
     :detail detail}))

(defn visible-objects
  "Everything currently drawable, across every loaded layer.

  Satellites arrive already propagated to `:clock/now`; the rest carry the
  position they were observed at. Both are marks on the same globe, and
  the difference is real -- which is why `:observed-at` travels with each
  one and the UI can say how old a mark is."
  [db]
  (into []
        (mapcat (fn [k] (:objects (layer db k))))
        kinds))

(defn any-loading? [db]
  (boolean (some #(= :loading (:status (layer db %))) kinds)))

(defn unavailable-kinds
  "The kinds that were asked for and could not be answered. The UI states
  these; it does not draw an empty sky for them."
  [db]
  (for [k kinds
        :let [l (layer db k)]
        :when (= :unavailable (:status l))]
    {:kind k :detail (:detail l)}))

(defn staleness-ms
  "How old the newest observation in a layer is, relative to `now`.

  nil when the layer holds nothing -- not 0. Zero would read as 'perfectly
  fresh', which is the opposite of what an empty layer means."
  [db k now-ms]
  (let [ts (keep :observed-at (:objects (layer db k)))]
    (when (seq ts) (- now-ms (apply max ts)))))

(defn describe-age
  "Milliseconds -> something a person reads. nil -> \"unknown\", which is
  not \"now\"."
  [ms]
  (cond
    (nil? ms) "unknown"
    (neg? ms) "ahead of this clock"
    (< ms 60000) (str (Math/round (/ ms 1000.0)) "s ago")
    (< ms 3600000) (str (Math/round (/ ms 60000.0)) "m ago")
    (< ms 86400000) (str (Math/round (/ ms 3600000.0)) "h ago")
    :else (str (Math/round (/ ms 86400000.0)) "d ago")))

;; ---------------------------------------------------------------- camera

(defn clamp-camera
  "Keep the camera outside the planet and inside the poles.

  Latitude is clamped a hair short of the pole because at exactly +/-90
  the up vector and the view direction are parallel and `look-at`'s cross
  product collapses -- the view flips inside out, once, and looks like a
  bug in the data."
  [{:keys [lat-deg lon-deg distance] :as c}]
  (assoc c
         :lat-deg (max -89.9 (min 89.9 lat-deg))
         :lon-deg (let [l (mod (+ lon-deg 180.0) 360.0)]
                    (- (if (neg? l) (+ l 360.0) l) 180.0))
         :distance (max 1.05 (min 40.0 distance))))

(defn drag-camera
  "A pointer drag in pixels -> a new camera.

  The rotation per pixel shrinks with distance, so dragging near the
  surface moves the ground by roughly the distance dragged rather than
  spinning the planet away."
  [camera dx dy]
  (let [k (* 0.25 (min 1.0 (- (:distance camera) 1.0)))]
    (clamp-camera (-> camera
                      (update :lon-deg - (* dx k))
                      (update :lat-deg + (* dy k))))))

(defn zoom-camera [camera delta]
  (clamp-camera (update camera :distance * (Math/exp (* 0.0015 delta)))))
