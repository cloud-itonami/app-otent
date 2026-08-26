(ns app-otent.globe.scene
  "Where things are on the globe, and where the camera is looking.

  Pure. No GPU type, no canvas, no `js/`. Both backends -- WebGPU and
  WebGL 2 -- consume what this produces, which is the only reason they can
  be checked against each other: if the geometry lived inside a backend,
  'the fallback draws the same scene' would be an assertion nobody could
  test without a GPU.

  ## The frame

  Positions are on a unit sphere, in the frame
  `kotoba.geo.mesh/globe-tile-patch` already uses, because the basemap
  tiles are its patches and a second convention would put the markers a
  few degrees off the coastline they belong to:

      y = north
      theta = longitude + 180 degrees
      x = -cos(lat) * sin(theta)
      z =  cos(lat) * cos(theta)

  That mapping is **not asserted here from the formula** -- it is checked
  in `scene_test` against the vertices `globe-tile-patch-terrain` itself
  emits for the same corners. A formula derived by reading a renderer and
  a formula the renderer uses are two different things, and only one of
  them moves when the library does."
  (:require [kotoba.geo.projection :as proj]
            [kotoba.geo.mesh :as mesh]))

(def ^{:doc "Earth's mean radius in km, as the scene's unit. Positions are
  in earth radii, so an altitude of 400 km is 0.0627 above the surface --
  small, and visibly so, which is the honest picture of low Earth orbit."}
  earth-radius-km 6371.0)

(def deg->rad (/ Math/PI 180.0))

(defn lat-lon->globe
  "`[lat-deg lon-deg alt-km]` -> `[x y z]` in earth radii."
  ([lat lon] (lat-lon->globe lat lon 0.0))
  ([lat lon alt-km]
   (let [phi (* lat deg->rad)
         theta (* (+ lon 180.0) deg->rad)
         r (+ 1.0 (/ (or alt-km 0.0) earth-radius-km))
         c (Math/cos phi)]
     [(* r (- (* c (Math/sin theta))))
      (* r (Math/sin phi))
      (* r c (Math/cos theta))])))

(defn globe->lat-lon
  "The inverse, for picking: a point in the scene back to a place."
  [[x y z]]
  (let [r (Math/sqrt (+ (* x x) (* y y) (* z z)))
        lat (/ (Math/asin (/ y r)) deg->rad)
        theta (Math/atan2 (- x) z)
        lon (- (/ theta deg->rad) 180.0)]
    {:lat-deg lat
     :lon-deg (cond (< lon -180.0) (+ lon 360.0)
                    (> lon 180.0) (- lon 360.0)
                    :else lon)
     :alt-km (* earth-radius-km (- r 1.0))}))

;; ---------------------------------------------------------------- camera

(defn- normalize [[x y z]]
  (let [n (Math/sqrt (+ (* x x) (* y y) (* z z)))]
    (if (zero? n) [0.0 0.0 0.0] [(/ x n) (/ y n) (/ z n)])))

(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])

(defn- dot [a b] (reduce + (map * a b)))

(defn look-at
  "Right-handed view matrix, column-major, as a flat vector of 16.

  Column-major because that is what both `uniformMatrix4fv` (with
  `transpose=false`) and a WGSL `mat4x4<f32>` expect. Row-major here would
  render a plausible but wrong scene in both backends identically, which
  is the worst way for a bug to be consistent."
  [eye target up]
  (let [f (normalize (map - target eye))
        s (normalize (cross f up))
        u (cross s f)]
    [(nth s 0) (nth u 0) (- (nth f 0)) 0.0
     (nth s 1) (nth u 1) (- (nth f 1)) 0.0
     (nth s 2) (nth u 2) (- (nth f 2)) 0.0
     (- (dot s eye)) (- (dot u eye)) (dot f eye) 1.0]))

(defn perspective
  "Perspective projection, column-major, **zero-to-one depth**.

  Not the OpenGL -1..1 convention. WebGPU's clip space is 0..1 in z, and a
  matrix built for OpenGL renders in WebGPU with everything in the near
  half of the depth buffer -- which does not look broken, it looks like
  z-fighting. WebGL 2 is told about the difference once, in the backend,
  rather than by keeping two matrices here."
  [fov-y-rad aspect near far]
  (let [f (/ 1.0 (Math/tan (/ fov-y-rad 2.0)))
        nf (/ 1.0 (- near far))]
    [(/ f aspect) 0.0 0.0 0.0
     0.0 f 0.0 0.0
     0.0 0.0 (* far nf) -1.0
     0.0 0.0 (* far near nf) 0.0]))

(defn mat4-mul
  "a * b, both column-major flat vectors of 16."
  [a b]
  (vec (for [c (range 4) r (range 4)]
         (reduce + (for [k (range 4)]
                     (* (nth a (+ r (* 4 k))) (nth b (+ k (* 4 c)))))))))

(defn orbit-camera
  "An orbiting camera as `{:eye :view :proj :view-proj}`.

  `distance` is in earth radii from the centre, so 1.0 is standing on the
  surface and anything below that is inside the planet -- clamped, because
  a camera inside the globe renders the inside of the sphere and reads as
  the globe having vanished."
  [{:keys [lat-deg lon-deg distance fov-deg aspect]
    :or {lat-deg 0.0 lon-deg 0.0 distance 3.0 fov-deg 45.0 aspect 1.0}}]
  (let [d (max 1.00005 distance)
        eye (lat-lon->globe lat-deg lon-deg (* earth-radius-km (- d 1.0)))
        ;; Up is world-north except at the poles, where north is the view
        ;; direction and the cross product collapses.
        up (if (> (Math/abs lat-deg) 89.5) [0.0 0.0 1.0] [0.0 1.0 0.0])
        view (look-at eye [0.0 0.0 0.0] up)
        proj (perspective (* fov-deg deg->rad) aspect
                          ;; Near clip scales with HEIGHT ABOVE THE SURFACE,
                          ;; not with distance from the centre. A fixed
                          ;; floor of 0.001 earth radii is 6.4 km, which at
                          ;; 320 m up clips away the ground and everything
                          ;; standing on it -- the screen goes empty and
                          ;; nothing reports why.
                          (max 1.0e-7 (* 0.2 (- d 1.0)))
                          (+ d 2.0))]
    {:eye eye :view view :proj proj :view-proj (mat4-mul proj view)}))

;; ---------------------------------------------------------------- tiles

(defn zoom-for-distance
  "Which basemap zoom level to draw at `distance` earth radii.

  Doubling the detail every time the camera halves its height above the
  surface, clamped to what the ingest actually put in R2. Clamped rather
  than extrapolated: asking for a tile that was never ingested is a 404
  per tile per frame, and the globe develops holes that look like a
  rendering bug."
  [distance max-zoom]
  (let [h (max 0.02 (- distance 1.0))
        z (Math/round (- 1.0 (/ (Math/log h) (Math/log 2.0))))]
    (max 0 (min max-zoom z))))

(defn tile-angular-radius
  "Roughly how far a tile reaches from its own centre, in radians, at zoom
  `z`.

  A tile spans 360/2^z degrees of longitude, so its angular half-width is
  pi/2^z. Capped at **pi**, not pi/2: the z0 tile covers the whole planet,
  and its corners really are half a turn from its centre at (0,0). Capping
  at pi/2 said a tile could never reach further than a quarter turn, which
  culled the single z0 tile whenever the camera was on the far side."
  [z]
  (min Math/PI (/ Math/PI (Math/pow 2.0 z))))

(defn horizon-angle
  "How far, in radians, you can see past the point below you at `distance`
  earth radii from the centre.

  `acos(1/d)`. From geostationary orbit it is 81 degrees; from 2.5 km up it
  is **1.6 degrees**. That collapse is the whole point of this function."
  [distance]
  (if (<= distance 1.0) 0.0 (Math/acos (/ 1.0 distance))))

(defn visible-tiles
  "The tiles to draw: every tile at `z` with any part inside the horizon.

  ## Culling by centre alone is wrong, and wrong worst where it matters

  A tile is visible when its NEAR EDGE is inside the horizon, not when its
  centre is. For a tile of angular radius `r` whose centre is at angle
  theta from the point below the camera, that is
  `theta - r < horizon`, i.e.

      dot(centre, eye) > cos(horizon + r)

  Two things this replaced, both of which drew a plausible globe:

  1. Ignoring `r` is the same formula with the tile's angular radius set to
     zero. At z0 there is ONE tile covering the planet, its centre sits at
     (0, 0), and any camera more than ~80 degrees away culled it -- the
     globe vanished and nothing errored.
  2. Ignoring `distance` assumes the camera is infinitely far away, where
     the horizon is 90 degrees. It is not: at 2.5 km up the horizon is
     1.6 degrees, and the old form kept **624 tiles resident** when about
     one was visible. Nothing looked wrong -- the globe rendered, at the
     cost of 624 textures and meshes on the GPU.

  The extra 0.1 rad keeps tiles just past the edge, whose near side is
  still visible; without it the rim visibly unloads a tile early."
  [z {:keys [lat-deg lon-deg distance] :or {distance 3.0}}]
  (let [n (bit-shift-left 1 z)
        eye-dir (normalize (lat-lon->globe lat-deg lon-deg))
        ;; A tile reaching more than half a turn from its centre is
        ;; visible from everywhere, and `cos` cannot say so: cos(pi) is
        ;; -1 and `dot > -1` is false for exactly antipodal. So that case
        ;; is named rather than computed.
        theta-max (+ (horizon-angle distance) (tile-angular-radius z) 0.1)
        cutoff (if (>= theta-max Math/PI) -1.1 (Math/cos theta-max))]
    (for [x (range n) y (range n)
          :let [{:keys [lng lat]} (proj/tile-center-lng-lat {:x x :y y :z z})
                c (lat-lon->globe lat lng)]
          :when (> (dot (normalize c) eye-dir) cutoff)]
      {:x x :y y :z z})))

;; ---------------------------------------------------------------- markers

(def kind-colour
  "One colour per kind, as linear-ish RGB. Chosen for hue separation on a
  dark blue marble rather than from a palette: these are marks on imagery,
  not UI, so the design system's tokens do not reach here and inventing a
  token for `satellite` would put a scene decision in a stylesheet."
  {:satellite [1.00 0.85 0.25]
   :quake     [1.00 0.35 0.30]
   :aircraft  [0.40 0.85 1.00]
   :fire      [1.00 0.55 0.15]
   :vessel    [0.55 1.00 0.65]})

(defn marker-vertices
  "Objects -> a flat `Float32Array`-shaped vector of
  `[x y z r g b size]` per point.

  Interleaved rather than one buffer per attribute: this is rebuilt every
  time the propagation advances, and one upload beats five."
  [objects]
  (into []
        (mapcat (fn [{:keys [lat-deg lon-deg alt-km kind size]}]
                  (let [[x y z] (lat-lon->globe lat-deg lon-deg alt-km)
                        [r g b] (get kind-colour kind [1.0 1.0 1.0])]
                    [x y z r g b (or size 4.0)])))
        objects))

(defn line-vertices
  "Basemap vector lines -> flat `[x y z]` pairs for `LINES` drawing.

  Lifted off the surface by `lift`, because a line exactly on the sphere
  z-fights with the tile it sits on and the coastline flickers."
  [lines lift]
  (let [alt (* earth-radius-km lift)]
    (into []
          (mapcat (fn [flat]
                    ;; flat is [lon lat lon lat ...]
                    (let [pts (partition 2 flat)]
                      (mapcat (fn [[a b]]
                                (let [[lon1 lat1] a [lon2 lat2] b]
                                  (concat (lat-lon->globe lat1 lon1 alt)
                                          (lat-lon->globe lat2 lon2 alt))))
                              (partition 2 1 pts)))))
          lines)))

;; ---------------------------------------------------------------- buildings

(def ^{:doc "Vertical exaggeration for extruded buildings. **1.0 = true
  scale**, and that is what this is.

  It was 25, on the reasoning that a 40 m building is 6.3e-6 of an earth
  radius and therefore invisible. That reasoning ignored where buildings
  are actually *seen* from: they only load below
  `building-visible-distance`, and `fly-to` lands at 2.5 km up, where a
  40 m building is 1.6% of the view height and perfectly legible.

  At 25x it was a 1 km needle 20 m wide. Manhattan rendered as a fan of
  white streaks radiating past the camera -- which does not look like a
  bug, it looks like a stylistic choice about a city.

  The constant stays because the reasoning is worth keeping next to the
  number, and because a globe view that never descends might want it."}
  building-exaggeration 1.0)

(defn buildings->mesh
  "Building records from the lake -> one merged mesh, in the globe frame.

  Each record is `{:h height-m :b base-m :r [lon lat lon lat ...]}`, which
  is what `otent`'s buildings ingest writes. The extrusion itself is
  `kotoba.geo.mesh/globe-polygon-to-extrude-earcut` -- deriving sphere
  positions here instead would put the walls in a different frame from the
  tiles under them.

  Records marked `:hide-3d` are skipped. OpenStreetMap uses that flag for
  footprints that should not be extruded (bridges, roofs over open space);
  drawing them gives a city a scattering of solid blocks where there is
  nothing."
  ([records] (buildings->mesh records building-exaggeration))
  ([records exaggeration]
   (let [scale (/ exaggeration earth-radius-km 1000.0)]  ; metres -> earth radii
     (reduce
      (fn [{:keys [vertices indices] :as acc} {:keys [h b r hide-3d]}]
        (if (or hide-3d (nil? r) (< (count r) 8))
          acc
          (let [ring (mapv vec (partition 2 r))
                m (mesh/globe-polygon-to-extrude-earcut
                   ring 1.0 (* (or b 0.0) scale) (* (or h 3.0) scale))
                base (quot (count vertices) 8)]
            (if (empty? (:vertices m))
              acc
              {:vertices (into vertices (:vertices m))
               :indices (into indices (map #(+ base %)) (:indices m))}))))
      {:vertices [] :indices []}
      records))))

(def ^{:doc "Below this distance the building layer is fetched.

  1.01 earth radii is about 64 km up. At 25x exaggeration a 40 m building
  is 1 km tall, which subtends roughly 11 pixels from there -- visible,
  and not so far out that a whole city's footprints load while the reader
  is still looking at a continent.

  `db/min-camera-distance` MUST be below this, or the threshold is
  unreachable and the layer silently never loads. `scene-test` asserts the
  two against each other rather than trusting that whoever changes one
  remembers the other."}
  building-visible-distance 1.01)

(def surface-colour
  "Ground colours by MVT class. Not design-system tokens: these are marks
  on imagery, and a `--hig-*` for `swimming_pool` would put a scene
  decision in a stylesheet."
  {"ocean" [0.05 0.11 0.24] "river" [0.07 0.15 0.30] "lake" [0.07 0.15 0.30]
   "pond" [0.07 0.15 0.30] "swimming_pool" [0.10 0.28 0.42]
   "grass" [0.10 0.19 0.11] "wood" [0.08 0.16 0.09] "forest" [0.08 0.16 0.09]
   "park" [0.10 0.20 0.12] "sand" [0.28 0.25 0.17] "rock" [0.16 0.16 0.17]
   "ice" [0.35 0.38 0.42] "farmland" [0.14 0.16 0.10]})

(def surface-default [0.09 0.10 0.12])

(defn surface->mesh
  "Ground polygons from the lake -> one merged mesh, lifted a hair off the
  surface so it does not z-fight the raster tile beneath it.

  Vertices carry a colour in the normal slot's place -- see
  `webgl/surface-vs`. Reusing the mesh library's 8-float stride keeps one
  vertex layout in the renderer rather than two."
  [records lift]
  (reduce
   (fn [{:keys [vertices indices] :as acc} {:keys [c r]}]
     (if (or (nil? r) (< (count r) 8))
       acc
       (let [ring (mapv vec (partition 2 r))
             m (mesh/globe-polygon-to-fill-earcut ring 1.0 lift)
             [cr cg cb] (get surface-colour c surface-default)
             base (quot (count vertices) 8)]
         (if (empty? (:vertices m))
           acc
           {:vertices (into vertices
                            (mapcat (fn [[x y z _ _ _ u v]] [x y z cr cg cb u v]))
                            (partition 8 (:vertices m)))
            :indices (into indices (map #(+ base %)) (:indices m))})))) 
   {:vertices [] :indices []}
   records))

(defn building-tiles-in-view
  "Which building tiles to ask for, given the camera and the manifest.

  Returns `[]` unless the camera is close enough that buildings would be
  more than a pixel, AND it is over an area the ingest actually covered.
  Both halves matter: without the first the app fetches city tiles while
  looking at the whole planet, and without the second it 404s over the
  99.99% of the planet that has none.

  `areas` is the manifest's `:areas`, each carrying the `x0 x1 y0 y1`
  block that was ingested."
  [{:keys [lat-deg lon-deg distance]} areas]
  (if (or (empty? areas) (> distance building-visible-distance))
    []
    (let [z (:z (first areas))
          n (Math/pow 2 z)
          cx (Math/floor (* (/ (+ lon-deg 180.0) 360.0) n))
          phi (* lat-deg deg->rad)
          cy (Math/floor (* (/ (- 1.0 (/ (Math/asinh (Math/tan phi)) Math/PI)) 2.0) n))]
      (vec
       (for [a areas
             x (range (max (:x0 a) (- cx 1)) (inc (min (:x1 a) (+ cx 1))))
             y (range (max (:y0 a) (- cy 1)) (inc (min (:y1 a) (+ cy 1))))]
         {:z z :x x :y y})))))
