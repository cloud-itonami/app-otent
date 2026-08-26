(ns app-tenkyu.core
  "The browser entry point: one mount, one render loop.

  Everything here is glue. The scene is computed by
  `app-tenkyu.globe.scene`, the propagation by `app-tenkyu.propagate`, the
  state transitions by `app-tenkyu.db` -- all pure and all tested. This
  file owns the canvas, the animation frame and the pointer, and nothing
  else.

  ## One document

  The nav is `<a href=\"#objects\">`; crossing a view changes state. The
  canvas and the GPU device are created once and survive every crossing,
  which is the point -- re-creating a WebGPU device per screen would make
  the globe blink on every click."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.db :as rf-db]
            [app-tenkyu.db :as db]
            [app-tenkyu.events :as events]
            [app-tenkyu.subs :as subs]
            [app-tenkyu.views :as views]
            [app-tenkyu.route :as route]
            [app-tenkyu.globe.renderer :as renderer]
            [app-tenkyu.globe.scene :as scene]))

(defonce ^{:doc "The GPU side, kept out of the re-frame db: a device, a
  texture cache and a set of buffers are not values, and putting them in
  app-db would make every `assoc` walk them."}
  gpu (atom {:state nil :tiles-at nil :lines-done? false
             :buildings-key nil :buildings-loading? false}))

(defonce root (atom nil))

(declare start-globe!)

(defn- diag!
  "Publish a small diagnostic on `window`.

  A render loop that has silently stopped and a render loop that is drawing
  nothing produce the same blank canvas. This is how a test tells them
  apart, and it is why `test/browser/smoke.mjs` can assert the loop is
  ALIVE rather than inferring it from pixels."
  [k v]
  (let [d (or (aget js/window "__tenkyu") #js {})]
    (aset d (name k) v)
    (aset js/window "__tenkyu" d)))

(defn- live-canvas
  "The canvas that is in the document RIGHT NOW.

  Asked every frame rather than captured once, because React 18's
  `createRoot(...).render(...)` is a client render, not a hydration: it
  discards the server-rendered children of `#app` and builds new ones. A
  renderer created before that commit holds a canvas that is still a
  perfectly good object -- it keeps its drawing buffer, it accepts draw
  calls, it reports 402x600 -- and is no longer in the page.

  Measured 2026-08-26: the frame counter climbed past 700, the diagnostic
  said `canvas 402x600`, and `document.querySelectorAll('canvas')` found
  exactly one canvas, unsized at 300x150, showing nothing. Nothing errored,
  because nothing was wrong: the GPU was drawing into a detached node."
  []
  (.getElementById js/document "tenkyu-globe"))

(defn- tile-key [{:keys [z x y]}] (str z "/" x "/" y))

(defn- load-tile!
  "Fetch one basemap tile from OUR bucket, through the Worker, and hand the
  decoded bitmap to whichever backend is running.

  A 404 is left alone rather than retried: the tile was never ingested,
  and asking again every frame turns one hole into a request storm."
  [state coord]
  (-> (js/fetch (str "/api/basemap/blue-marble/" (:z coord) "/" (:x coord) "/" (:y coord) ".jpg"))
      (.then (fn [r] (when (.-ok r) (.blob r))))
      (.then (fn [b] (when b (js/createImageBitmap b))))
      (.then (fn [img] (when img (renderer/ensure-tile! state coord img 8))))
      (.catch (fn [_] nil))))

(defn- sync-tiles!
  "Bring the resident tile set in line with the camera."
  [state camera max-zoom]
  (let [z (scene/zoom-for-distance (:distance camera) max-zoom)
        want (scene/visible-tiles z camera)
        want-keys (set (map tile-key want))]
    (when (not= [z (:lat-deg camera) (:lon-deg camera)] (:tiles-at @gpu))
      (swap! gpu assoc :tiles-at [z (:lat-deg camera) (:lon-deg camera)])
      (renderer/drop-tiles! state want-keys)
      (doseq [c want] (load-tile! state c)))))

(defn- load-vector-lines!
  "Coastlines and borders, once, from the bucket."
  [state]
  (when-not (:lines-done? @gpu)
    (swap! gpu assoc :lines-done? true)
    (-> (js/Promise.all
         #js [(.then (js/fetch "/api/basemap/vector/coastline.json") #(.json %))
              (.then (js/fetch "/api/basemap/vector/borders.json") #(.json %))])
        (.then (fn [layers]
                 (let [lines (mapcat (fn [l] (js->clj (aget l "lines")))
                                     (array-seq layers))]
                   (renderer/set-lines! state (scene/line-vertices lines 0.0015)))))
        (.catch (fn [_] (swap! gpu assoc :lines-done? false))))))

(defn- sync-buildings!
  "Fetch and upload the building footprints for wherever the camera is.

  Keyed on the SET of tiles in view, so moving within one block does no
  work and leaving a covered area clears the mesh. Without the clear, the
  last city stays welded to the globe and rotates with it.

  Each tile is one R2 object the Worker serves; the MVT was decoded once
  at ingest, so nothing here parses protobuf."
  [state camera areas]
  (let [tiles (scene/building-tiles-in-view camera areas)
        k (str/join "," (map (fn [{:keys [z x y]}] (str z "/" x "/" y)) tiles))]
    (when (and (not (:buildings-loading? @gpu)) (not= k (:buildings-key @gpu)))
      (swap! gpu assoc :buildings-key k :buildings-loading? true)
      (if (empty? tiles)
        (do (renderer/set-buildings! state nil)
            (renderer/set-surface! state nil)
            ;; The diagnostic must be cleared HERE too. It was set only on
            ;; the load path, so flying out of a city cleared the mesh and
            ;; left `buildings: 7821` on `window.__tenkyu` -- and the
            ;; browser test read that as the city still being drawn.
            ;; A stale instrument and a stale scene look identical.
            (diag! :buildings 0)
            (diag! :surface 0)
            (swap! gpu assoc :buildings-loading? false))
        (-> (js/Promise.all
             (clj->js (for [{:keys [z x y]} tiles]
                        (-> (js/fetch (str "/api/basemap/buildings/" z "/" x "/" y ".json"))
                            (.then (fn [r] (when (.-ok r) (.json r))))
                            (.catch (constantly nil))))))
            (.then (fn [results]
                     (let [ok (remove nil? (array-seq results))
                           records (mapcat #(js->clj (aget % "buildings") :keywordize-keys true) ok)
                           ground (mapcat #(js->clj (or (aget % "surface") #js [])
                                                    :keywordize-keys true) ok)
                           mesh (scene/buildings->mesh records)
                           ;; Lifted 1 m off the sphere. The raster tile is
                           ;; drawn at exactly radius 1, and coplanar
                           ;; surfaces z-fight into a shimmer that looks
                           ;; like a driver bug.
                           smesh (scene/surface->mesh ground (/ 1.0 6371000.0))]
                       (diag! :buildings (count records))
                       (diag! :surface (count ground))
                       (renderer/set-surface! state (when (seq (:indices smesh)) smesh))
                       (renderer/set-buildings! state (when (seq (:indices mesh)) mesh))
                       (swap! gpu assoc :buildings-loading? false))))
            (.catch (fn [_] (swap! gpu assoc :buildings-loading? false
                                   :buildings-key nil))))))))

(defn- frame!
  "One frame.

  Reads `app-db` DIRECTLY rather than through `rf/subscribe`.

  A re-frame subscription deref'd outside a reactive context returns its
  cached value and does not necessarily recompute -- there is no reaction
  watching it, so nothing invalidates the cache. Measured: the plaque
  (rendered by reagent, in a reactive context) showed 7,214 aircraft while
  this loop received an empty vector and issued `Draw` with an instance
  count of 0, every frame, silently.

  Reading the atom and applying the same pure functions is also simply
  what this loop wants: `db/visible-objects` is a function of a map."
  [_ts]
  (diag! :frames (inc (or (some-> (aget js/window "__tenkyu") (aget "frames")) 0)))
  (let [{:keys [state]} @gpu]
    ;; If the node we hold is no longer the node in the page, rebuild on the
    ;; live one. Guarded by `:starting?`, so this cannot storm.
    ;; `start-globe!` is async and guarded, so this may fire on several
    ;; frames before the new state lands. It counts nothing -- the counter
    ;; is incremented where a rebuild actually BEGINS, or the number
    ;; measures how fast the loop runs rather than how often it rebuilt.
    (when (and state (not (identical? (live-canvas) (:canvas state))))
      (start-globe!))
    (when state
      (let [now (js/Date.now)]
        (rf/dispatch-sync [::events/advance now])
        (let [d @rf-db/app-db
              camera (:camera d)
              objects (db/visible-objects d)
              max-zoom (or (get-in d [:basemap :manifest :raster :max-zoom]) 2)
              {:keys [dpr aspect]} (renderer/resize! (:canvas state))]
          (diag! :canvas (str (.-width (:canvas state)) "x" (.-height (:canvas state))))
          (diag! :objects (count objects))
          (diag! :maxZoom max-zoom)
          (sync-tiles! state camera max-zoom)
          (sync-buildings! state camera (get-in d [:buildings :areas]))
          (load-vector-lines! state)
          (renderer/set-markers! state (scene/marker-vertices objects))
          (renderer/draw! state
                          {:view-proj (:view-proj (scene/orbit-camera
                                                   (assoc camera :aspect aspect)))
                           :dpr dpr})))))
  (js/requestAnimationFrame frame!))

(defn- attach-pointer! [canvas]
  (let [dragging (atom nil)]
    (.addEventListener canvas "pointerdown"
                       (fn [e] (reset! dragging [(.-clientX e) (.-clientY e)])
                         (.setPointerCapture canvas (.-pointerId e))))
    (.addEventListener canvas "pointerup" (fn [_] (reset! dragging nil)))
    (.addEventListener canvas "pointercancel" (fn [_] (reset! dragging nil)))
    (.addEventListener canvas "pointermove"
                       (fn [e]
                         (when-let [[x y] @dragging]
                           (rf/dispatch [::events/drag (- (.-clientX e) x) (- (.-clientY e) y)])
                           (reset! dragging [(.-clientX e) (.-clientY e)]))))
    (.addEventListener canvas "wheel"
                       (fn [e] (.preventDefault e)
                         (rf/dispatch [::events/zoom (.-deltaY e)]))
                       #js {:passive false})))

(defn start-globe!
  "Create the renderer on whatever canvas is currently in the page.

  Idempotent, and safe to call from anywhere: it does nothing if the
  renderer already holds the live canvas, and nothing if there is no canvas
  at all (the user is on another view -- not an error).

  Called from `mount!`, from the route listener, and from `frame!` when the
  node identity drifts."
  []
  (when-let [canvas (live-canvas)]
    (when-not (or (:starting? @gpu)
                  (identical? canvas (:canvas (:state @gpu))))
      ;; Set SYNCHRONOUSLY, and cleared only when the promise resolves.
      ;; Guarding on `:state` instead let two calls arriving in the same
      ;; tick -- which is exactly what `mount!` and the route listener do --
      ;; both see it empty and both build a renderer.
      (swap! gpu assoc :starting? true)
      (when (:state @gpu)
        (diag! :reattached
               (inc (or (some-> (aget js/window "__tenkyu") (aget "reattached")) 0))))
      (renderer/resize! canvas)
      (-> (renderer/create canvas)
          (.then (fn [state]
                   ;; Tile and line caches belong to the OLD GPU context;
                   ;; on a re-attach they must not be treated as resident.
                   (swap! gpu assoc :state state :starting? false
                          :tiles-at nil :lines-done? false)
                   (diag! :backend (name (or (:backend state) :nil)))
                   (rf/dispatch [::events/set-backend (:backend state)])
                   (when (not= :none (:backend state))
                     (attach-pointer! canvas)
                     (js/requestAnimationFrame frame!))))
          (.catch (fn [e]
                    (diag! :startError (str (.-message e)))
                    (swap! gpu assoc :starting? false)))))))

(defn ui []
  [views/app (assoc @(rf/subscribe [::subs/page-model])
                    :on-fly #(rf/dispatch [::events/fly-to %]))])

(defn- after-commit!
  "Run `f` once React has put its tree in the document.

  `setTimeout 0` is not that. React 18's scheduler and the timer queue are
  different queues, and which runs first has been observed to vary: the
  globe sometimes started on the SERVER-rendered canvas, which React then
  replaced, leaving the renderer drawing into a detached node. A double
  `requestAnimationFrame` lands after the commit and before the paint."
  [f]
  (js/requestAnimationFrame (fn [] (js/requestAnimationFrame f))))

(defn ^:dev/after-load mount! []
  (let [el (.getElementById js/document "app")]
    (when-not @root (reset! root (rdc/create-root el)))
    (rdc/render @root [ui])
    (after-commit! start-globe!)))

(defn init []
  (rf/dispatch-sync [::events/init])
  (route/listen! (fn [v]
                   (rf/dispatch [::events/set-view (:id v)])
                   (after-commit! start-globe!)))
  (rf/dispatch [::events/load-basemap])
  (rf/dispatch [::events/load-buildings])
  (rf/dispatch [::events/load-all])
  (mount!))
