(ns app-tenkyu.globe.scene
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
  (:require [kotoba.geo.projection :as proj]))

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
  (let [d (max 1.02 distance)
        eye (lat-lon->globe lat-deg lon-deg (* earth-radius-km (- d 1.0)))
        ;; Up is world-north except at the poles, where north is the view
        ;; direction and the cross product collapses.
        up (if (> (Math/abs lat-deg) 89.5) [0.0 0.0 1.0] [0.0 1.0 0.0])
        view (look-at eye [0.0 0.0 0.0] up)
        proj (perspective (* fov-deg deg->rad) aspect
                          ;; Near clip scales with distance: a fixed 0.01
                          ;; wastes most of the depth buffer when zoomed out
                          ;; and clips the surface when zoomed in.
                          (max 0.001 (* 0.01 (- d 1.0))) (+ d 2.0))]
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
  "Roughly how wide a tile is, in radians, at zoom `z`.

  A tile spans 360/2^z degrees of longitude, so its angular half-width is
  pi/2^z -- capped at pi/2, because past a quarter-turn the tile wraps
  round the planet and \"radius\" stops meaning anything useful."
  [z]
  (min (/ Math/PI 2.0) (/ Math/PI (Math/pow 2.0 z))))

(defn visible-tiles
  "The tiles to draw: every tile at `z` with any part facing the camera.

  ## Culling by centre alone is wrong, and wrong worst where it matters

  A tile is visible when its NEAR EDGE is on this side of the horizon, not
  when its centre is. For a tile of angular radius `r` whose centre is at
  angle theta from the camera, that is `theta - r < 90 degrees`, i.e.

      dot(centre, eye) > -sin(r)

  Comparing the centre against a fixed threshold instead is the same
  formula with `r = 0`. It looks reasonable and it is catastrophic at low
  zoom: at z0 there is ONE tile covering the whole planet, its centre sits
  at (0, 0), and any camera more than about 80 degrees away from that
  point culls it -- the entire globe disappears and the canvas draws
  nothing. Measured 2026-08-26 with the camera at 20N 140E: zero tiles
  requested, zero pixels drawn, no error anywhere.

  The extra 0.1 keeps tiles just past the horizon, whose near edge is
  still visible; without it the rim visibly unloads a tile early."
  [z {:keys [lat-deg lon-deg]}]
  (let [n (bit-shift-left 1 z)
        eye-dir (normalize (lat-lon->globe lat-deg lon-deg))
        threshold (- (- (Math/sin (tile-angular-radius z))) 0.1)]
    (for [x (range n) y (range n)
          :let [{:keys [lng lat]} (proj/tile-center-lng-lat {:x x :y y :z z})
                c (lat-lon->globe lat lng)]
          :when (> (dot (normalize c) eye-dir) threshold)]
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
