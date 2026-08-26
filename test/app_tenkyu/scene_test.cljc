(ns app-tenkyu.scene-test
  "The scene frame, checked against the library that owns it.

  The point of this file: `lat-lon->globe` must agree with
  `kotoba.geo.mesh/globe-tile-patch-terrain`, because the basemap is that
  function's patches and the markers are this function's points. If they
  drift, every marker sits a few degrees off the coastline under it and
  nothing errors."
  (:require [clojure.test :refer [deftest is testing]]
            [app-tenkyu.globe.scene :as s]
            [kotoba.geo.mesh :as mesh]
            [kotoba.geo.projection :as proj]
            [app-tenkyu.db :as db]))

(defn- v3-dist [a b]
  (Math/sqrt (reduce + (map (fn [x y] (let [d (- x y)] (* d d))) a b))))

(deftest markers-land-where-the-basemap-puts-that-place
  ;; For each tile: take the vertices the mesh library emits, recover the
  ;; lat/lon each one is FOR from the tile bounds and its uv, and check
  ;; lat-lon->globe puts it in the same place.
  (let [checked (atom 0)]
    (doseq [z [0 1 2 3]
            x (range (min 4 (bit-shift-left 1 z)))
            y (range (min 4 (bit-shift-left 1 z)))]
      (let [coord {:x x :y y :z z}
            ;; terrain-scale 0: a clean sphere. The default adds procedural
            ;; relief, which is a look, not a datum -- comparing against it
            ;; would be comparing against noise.
            patch (mesh/globe-tile-patch-terrain coord 1.0 4 0.0)
            ;; A VECTOR [west south east north], not a map. Destructuring it
            ;; as a map binds every name to nil, and nil arithmetic in
            ;; ClojureScript is 0 -- so the first version of this test
            ;; compared every vertex against longitude 0 and reported 890
            ;; failures that were its own.
            [west _south east _north] (proj/tile-lng-lat-bounds coord)]
        (doseq [[px py pz _ _ _ u v] (partition 8 (:vertices patch))]
          ;; u interpolates longitude linearly; v interpolates the MERCATOR
          ;; y linearly, not the latitude -- so the latitude for a given v
          ;; comes back through the projection, not by lerping north/south.
          (let [lon (+ west (* u (- east west)))
                world-y (* (+ y v) 256.0)
                lat (:lat (proj/world-px->lng-lat {:x 0 :y world-y} z))
                mine (s/lat-lon->globe lat lon)]
            (swap! checked inc)
            (is (< (v3-dist mine [px py pz]) 1.0e-9)
                (str "z" z " (" x "," y ") uv=[" u " " v "] "
                     "mesh=" [px py pz] " scene=" mine))))))
    (is (<= 500 @checked) (str "only " @checked " vertices compared"))))

(deftest round-trip-through-lat-lon
  (doseq [lat (range -85.0 86.0 13.0)
          lon (range -180.0 180.0 37.0)
          alt [0.0 400.0 35786.0]]
    (let [back (s/globe->lat-lon (s/lat-lon->globe lat lon alt))]
      (is (< (Math/abs (- (:lat-deg back) lat)) 1e-9) (str lat "," lon))
      (is (< (Math/abs (- (:lon-deg back) lon)) 1e-9) (str lat "," lon))
      (is (< (Math/abs (- (:alt-km back) alt)) 1e-6) (str lat "," lon)))))

(deftest known-places-are-where-they-should-be
  (testing "the north pole is +y and nothing else"
    (let [[x y z] (s/lat-lon->globe 90.0 0.0)]
      (is (< (Math/abs x) 1e-9)) (is (< (Math/abs z) 1e-9))
      (is (< (Math/abs (- y 1.0)) 1e-9))))
  (testing "the south pole is -y"
    (is (< (Math/abs (+ 1.0 (second (s/lat-lon->globe -90.0 0.0)))) 1e-9)))
  (testing "every surface point is exactly on the unit sphere"
    (doseq [lat (range -90.0 91.0 10.0) lon (range -180.0 181.0 30.0)]
      (let [[x y z] (s/lat-lon->globe lat lon)]
        (is (< (Math/abs (- 1.0 (Math/sqrt (+ (* x x) (* y y) (* z z))))) 1e-12)))))
  (testing "altitude is in earth radii above the surface"
    (let [[_ y _] (s/lat-lon->globe 90.0 0.0 6371.0)]
      (is (< (Math/abs (- y 2.0)) 1e-9) "one earth radius up should be r=2"))))

(deftest the-projection-uses-webgpu-depth-not-opengl-depth
  ;; A -1..1 depth matrix renders in WebGPU with the whole scene in the near
  ;; half of the depth buffer. It does not look broken; it looks like
  ;; z-fighting, which is why this is asserted rather than commented.
  (let [p (s/perspective (* 45.0 s/deg->rad) 1.0 0.1 10.0)
        ;; A point at the near plane must map to z=0 after the perspective
        ;; divide, and one at the far plane to z=1.
        clip (fn [z] (let [zc (+ (* (nth p 10) z) (nth p 14))
                           wc (- z)]
                       (/ zc wc)))]
    (is (< (Math/abs (- (clip -0.1) 0.0)) 1e-6)
        "the near plane must map to depth 0, not -1")
    (is (< (Math/abs (- (clip -10.0) 1.0)) 1e-6)
        "the far plane must map to depth 1")))

(deftest the-camera-never-ends-up-inside-the-planet
  ;; A camera below r=1 renders the inside of the sphere, which reads as the
  ;; globe having disappeared rather than as a bad camera.
  ;;
  ;; The bound is `db/min-camera-distance`, NOT a literal. It was written as
  ;; 1.02 when that happened to be the floor; lowering the floor so the
  ;; ground is reachable made this test fail for a reason that had nothing
  ;; to do with what it checks.
  (doseq [d [-5.0 0.0 0.5 1.0 1.01]]
    (let [{:keys [eye]} (s/orbit-camera {:distance d :lat-deg 20.0 :lon-deg 30.0})
          r (Math/sqrt (reduce + (map * eye eye)))]
      (is (>= r db/min-camera-distance) (str "distance " d " put the eye at r=" r))
      (is (> r 1.0) (str "distance " d " put the eye INSIDE the planet at r=" r)))))

(deftest a-tile-is-never-culled-while-part-of-it-faces-the-camera
  ;; The bug this replaced a weaker test for: culling compared each tile's
  ;; CENTRE against a fixed threshold, which is the correct formula with the
  ;; tile's angular radius set to zero. At z0 there is one tile covering the
  ;; whole planet, its centre is at (0,0), and any camera more than ~80
  ;; degrees away culled it -- the globe vanished and nothing errored.
  (doseq [lat [-80.0 -40.0 0.0 20.0 60.0 85.0]
          lon [-180.0 -140.0 -60.0 0.0 60.0 140.0 179.0]]
    (let [cam {:lat-deg lat :lon-deg lon}]
      (testing (str "z0 from " lat "," lon)
        (is (= 1 (count (s/visible-tiles 0 cam)))
            "the single z0 tile covers the whole planet and is always visible"))
      (testing (str "z1 from " lat "," lon)
        ;; Four tiles, each spanning 180 degrees of longitude. At least one
        ;; hemisphere's worth must survive from any camera.
        (is (<= 2 (count (s/visible-tiles 1 cam)))
            (str "only " (count (s/visible-tiles 1 cam)) " of 4 z1 tiles kept")))
      (testing (str "the tile NEAREST the camera is kept at every zoom, from " lat "," lon)
        ;; Stated as a property rather than by recomputing the tile index
        ;; here: a test that re-derives the projection is testing its own
        ;; arithmetic, and the first version of this one did exactly that
        ;; and failed at lon -180 for reasons that had nothing to do with
        ;; culling.
        (doseq [z [2 3 4 5]]
          (let [n (bit-shift-left 1 z)
                eye (s/lat-lon->globe lat lon)
                dot* (fn [{:keys [x y]}]
                       (let [{clng :lng clat :lat}
                             (proj/tile-center-lng-lat {:x x :y y :z z})]
                         (reduce + (map * (s/lat-lon->globe clat clng) eye))))
                all (for [x (range n) y (range n)] {:x x :y y :z z})
                nearest (apply max-key dot* all)
                kept (set (map #(select-keys % [:x :y]) (s/visible-tiles z cam)))]
            (is (contains? kept (select-keys nearest [:x :y]))
                (str "z" z " culled the tile the camera is looking straight at, "
                     "from " lat "," lon))))))))

(deftest culling-still-removes-the-antipode
  ;; The fix must not turn culling off. Stated as the property that
  ;; matters -- the tile on the exact opposite side of the planet is behind
  ;; the horizon and must go -- rather than as a percentage. A percentage
  ;; is a number somebody picked: measured 2026-08-26, correct culling keeps
  ;; 208 of 256 tiles at z4, because Mercator packs tile centres towards
  ;; the poles and most of them fall inside the visible hemisphere.
  (doseq [z [3 4 5]
          [lat lon] [[0.0 0.0] [35.0 139.0] [-20.0 -60.0]]]
    (let [cam {:lat-deg lat :lon-deg lon}
          n (bit-shift-left 1 z)
          eye (s/lat-lon->globe lat lon)
          dot* (fn [{:keys [x y]}]
                 (let [{clng :lng clat :lat} (proj/tile-center-lng-lat {:x x :y y :z z})]
                   (reduce + (map * (s/lat-lon->globe clat clng) eye))))
          all (for [x (range n) y (range n)] {:x x :y y :z z})
          antipode (apply min-key dot* all)
          kept (set (map #(select-keys % [:x :y]) (s/visible-tiles z cam)))]
      (is (not (contains? kept (select-keys antipode [:x :y])))
          (str "z" z " from " lat "," lon " kept the tile on the far side"))
      (is (< (count kept) (* n n))
          (str "z" z " culled nothing at all")))))

(deftest the-angular-radius-shrinks-with-zoom-and-is-capped
  ;; Capped at PI, not PI/2. The z0 tile covers the whole planet and its
  ;; corners really are half a turn from its centre; saying a tile can
  ;; never reach further than a quarter turn culled it whenever the camera
  ;; was on the far side.
  (is (= Math/PI (s/tile-angular-radius 0)) "z0 reaches half a turn")
  (is (= (/ Math/PI 2.0) (s/tile-angular-radius 1)))
  (is (> (s/tile-angular-radius 2) (s/tile-angular-radius 5)))
  (doseq [z (range 0 12)]
    (is (<= 0.0 (s/tile-angular-radius z) Math/PI))))

(deftest zoom-is-clamped-to-what-was-ingested
  (is (= 0 (s/zoom-for-distance 100.0 5)))
  (is (= 5 (s/zoom-for-distance 1.001 5)) "must clamp, not extrapolate")
  (is (<= 0 (s/zoom-for-distance 1.5 5) 5))
  (doseq [d [1.001 1.05 1.2 2.0 5.0 50.0]]
    (is (<= 0 (s/zoom-for-distance d 5) 5) (str "distance " d))))

(deftest marker-vertices-are-seven-floats-each
  (let [vs (s/marker-vertices [{:lat-deg 0.0 :lon-deg 0.0 :alt-km 0.0 :kind :quake}
                               {:lat-deg 10.0 :lon-deg 20.0 :alt-km 400.0 :kind :satellite :size 9.0}])]
    (is (= 14 (count vs)))
    (is (= [1.0 0.35 0.30] (subvec vs 3 6)) "quake colour")
    (is (= 9.0 (nth vs 13)) "explicit size must survive")
    (testing "an unknown kind gets a colour rather than a nil that reaches the GPU"
      (is (= [1.0 1.0 1.0] (subvec (s/marker-vertices [{:lat-deg 0.0 :lon-deg 0.0 :kind :unknown}]) 3 6))))))

(deftest line-vertices-pair-up-and-are-lifted-off-the-surface
  ;; [lon lat lon lat lon lat] -- 3 points -> 2 segments -> 4 endpoints.
  (let [vs (s/line-vertices [[0.0 0.0 10.0 0.0 20.0 0.0]] 0.002)]
    (is (= (* 4 3) (count vs)))
    (doseq [[x y z] (partition 3 vs)]
      (is (> (Math/sqrt (+ (* x x) (* y y) (* z z))) 1.0)
          "a line sitting exactly on the sphere z-fights with the tile"))))

;; ---------------------------------------------------------------------------
;; Buildings

(def ^:private tokyo-block
  ;; A square footprint near Tokyo station, in the shape tenkyu writes.
  [{:h 40.0 :b 0.0 :r [139.767 35.681 139.768 35.681 139.768 35.682 139.767 35.682]}
   {:h 12.0 :b 0.0 :r [139.769 35.683 139.770 35.683 139.770 35.684 139.769 35.684]}])

(deftest buildings-become-one-mesh-in-the-globe-frame
  (let [m (s/buildings->mesh tokyo-block)
        vs (partition 8 (:vertices m))]
    (is (pos? (count vs)))
    (is (every? #(< % (count vs)) (:indices m))
        "an index past the vertex count draws garbage, or nothing")
    (testing "every vertex is on or just above the unit sphere -- the same
              frame the basemap tiles are in, not a separate one"
      ;; ON, not above: a building whose base is 0 has its base ring
      ;; exactly at radius 1. An exclusive lower bound rejected the
      ;; correct answer, which is what the first version of this did.
      (doseq [[x y z] (map #(take 3 %) vs)]
        (let [r (Math/sqrt (+ (* x x) (* y y) (* z z)))]
          (is (<= (- 1.0 1e-12) r 1.01) (str "vertex at radius " r)))))))

(deftest merging-buildings-rebases-the-indices
  ;; Two buildings merged into one mesh: the second's indices must be
  ;; shifted past the first's vertices. Forgetting that draws the second
  ;; building's triangles across the first one's corners -- a mesh that is
  ;; wrong but not empty.
  (let [one (s/buildings->mesh [(first tokyo-block)])
        two (s/buildings->mesh tokyo-block)
        n1 (quot (count (:vertices one)) 8)]
    (is (> (count (:vertices two)) (count (:vertices one))))
    (is (> (apply max (:indices two)) (apply max (:indices one)))
        "the merged mesh never references a vertex past the first building")
    (is (some #(>= % n1) (:indices two))
        "the second building's indices were not rebased")))

(deftest hide-3d-footprints-are-skipped
  ;; OSM marks bridges and roofs-over-open-space this way. Extruding them
  ;; scatters solid blocks through a city where there is nothing.
  (let [with (s/buildings->mesh (conj (vec tokyo-block)
                                      {:h 30.0 :b 0.0 :hide-3d true
                                       :r [139.771 35.685 139.772 35.685
                                           139.772 35.686 139.771 35.686]}))
        without (s/buildings->mesh tokyo-block)]
    (is (= (count (:vertices without)) (count (:vertices with))))))

(deftest degenerate-building-records-do-not-break-the-batch
  ;; One bad row out of a thousand must not lose the other 999.
  (let [good (s/buildings->mesh tokyo-block)
        mixed (s/buildings->mesh (concat [{:h 10.0 :r nil}
                                          {:h 10.0 :r [1.0 2.0]}
                                          {:h 0.0 :b 0.0 :r [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0]}]
                                         tokyo-block))]
    (is (= (count (:vertices good)) (count (:vertices mixed))))))

(deftest taller-buildings-reach-higher
  ;; The heights have to survive the metres -> earth-radii conversion. If
  ;; the scale were dropped, every building would be the same height and a
  ;; skyline would be a flat slab.
  (let [tall (s/buildings->mesh [{:h 300.0 :b 0.0 :r [0.0 0.0 0.001 0.0 0.001 0.001 0.0 0.001]}])
        short (s/buildings->mesh [{:h 10.0 :b 0.0 :r [0.0 0.0 0.001 0.0 0.001 0.001 0.0 0.001]}])
        top (fn [m] (apply max (map (fn [[x y z]] (Math/sqrt (+ (* x x) (* y y) (* z z))))
                                    (map #(take 3 %) (partition 8 (:vertices m))))))]
    (is (> (top tall) (top short)))
    (testing "and by the right ratio, exaggeration included"
      (let [expected (/ (* 300.0 s/building-exaggeration) (* s/earth-radius-km 1000.0))
            actual (- (top tall) 1.0)]
        (is (< (Math/abs (- actual expected)) 1e-9))))))

(deftest building-tiles-are-asked-for-only-where-they-exist
  (let [areas [{:id "tokyo" :z 14 :x0 14550 :x1 14554 :y0 6449 :y1 6453}]]
    (testing "not while looking at the whole planet"
      (is (empty? (s/building-tiles-in-view {:lat-deg 35.68 :lon-deg 139.76 :distance 3.0} areas))))
    (testing "not over an area that was never ingested"
      (is (empty? (s/building-tiles-in-view {:lat-deg -33.87 :lon-deg 151.21 :distance 1.001} areas))))
    (testing "yes when close over a covered area"
      (let [ts (s/building-tiles-in-view {:lat-deg 35.6812 :lon-deg 139.7671 :distance 1.001} areas)]
        (is (seq ts))
        (is (every? #(and (<= 14550 (:x %) 14554) (<= 6449 (:y %) 6453)) ts)
            "asked for a tile outside the ingested block")))
    (testing "and never when the manifest lists nothing"
      (is (empty? (s/building-tiles-in-view {:lat-deg 35.68 :lon-deg 139.76 :distance 1.001} []))))))

(deftest the-camera-can-actually-reach-the-buildings
  ;; THE test this pair of constants exists for.
  ;;
  ;; `db/min-camera-distance` was 1.05 -- 318 km up -- while buildings are
  ;; only requested below `scene/building-visible-distance`. The floor sat
  ;; entirely above the threshold, so the building layer could never load,
  ;; ever, on any machine. Nothing looked wrong: the globe rendered, drag
  ;; worked, zoom stopped somewhere plausible.
  ;;
  ;; Two constants in two namespaces, each defensible alone.
  (is (< db/min-camera-distance s/building-visible-distance)
      (str "the camera can get no closer than " db/min-camera-distance
           " but buildings only load below " s/building-visible-distance
           " -- the layer is unreachable"))
  (testing "and zooming all the way in really does cross the threshold"
    (let [in (reduce (fn [c _] (db/zoom-camera c -400))
                     {:lat-deg 35.68 :lon-deg 139.77 :distance 3.2}
                     (range 60))]
      (is (< (:distance in) s/building-visible-distance)
          (str "60 scroll steps only reached " (:distance in)))))
  (testing "and the near plane does not clip the ground away when it does"
    ;; A fixed near-plane floor of 0.001 earth radii is 6.4 km. From 320 m
    ;; up that clips the ground and everything standing on it: the screen
    ;; goes empty and nothing reports why.
    (let [{:keys [proj]} (s/orbit-camera {:distance db/min-camera-distance
                                          :lat-deg 0.0 :lon-deg 0.0 :aspect 1.0})
          ;; recover the near plane from the projection matrix
          m10 (nth proj 10) m14 (nth proj 14)
          near (/ m14 (- m10 1.0))
          height (- db/min-camera-distance 1.0)]
      (is (< near height)
          (str "near plane " near " is beyond the camera's own height above "
               "the surface " height " -- the ground is clipped")))))

(deftest dragging-still-moves-the-globe-when-standing-on-it
  ;; The drag scale is proportional to height above the surface, so at
  ;; 320 m up it would be 1e-5 and the globe would not turn at all.
  (let [low {:lat-deg 0.0 :lon-deg 0.0 :distance db/min-camera-distance}
        moved (db/drag-camera low 120 0)]
    (is (> (Math/abs (- (:lon-deg moved) (:lon-deg low))) 0.01)
        (str "a 120 px drag moved the camera "
             (Math/abs (- (:lon-deg moved) (:lon-deg low))) " degrees"))))

(deftest the-horizon-shrinks-as-the-camera-descends
  (is (< (s/horizon-angle 1.0004) 0.03) "from 2.5 km up you see about 1.6 degrees")
  (is (> (s/horizon-angle 6.6) 1.4) "from geostationary you see about 81 degrees")
  (is (= 0.0 (s/horizon-angle 1.0)) "on the surface the horizon is at your feet")
  (is (= 0.0 (s/horizon-angle 0.5)) "and inside the planet it is not negative")
  (testing "it is monotonic"
    (is (apply < (map s/horizon-angle [1.0001 1.001 1.01 1.1 2.0 10.0])))))

(deftest low-altitude-does-not-keep-the-whole-planet-resident
  ;; Measured before this: at 2.5 km up the renderer held 624 z5 tiles --
  ;; 624 textures and 624 meshes on the GPU -- when about one was visible.
  ;; The globe rendered correctly the whole time.
  (let [z 5
        all (* (bit-shift-left 1 z) (bit-shift-left 1 z))
        low (count (s/visible-tiles z {:lat-deg 35.68 :lon-deg 139.77 :distance 1.0004}))
        high (count (s/visible-tiles z {:lat-deg 35.68 :lon-deg 139.77 :distance 3.2}))]
    (is (< low 20) (str "at 2.5 km up " low " of " all " tiles were kept"))
    (is (> high low) "descending must keep FEWER tiles, not more")
    (testing "and the tile under the camera survives at every altitude"
      (doseq [d [1.0001 1.001 1.01 1.2 3.2 20.0]]
        (let [cam {:lat-deg 35.68 :lon-deg 139.77 :distance d}
              eye (s/lat-lon->globe 35.68 139.77)
              dot* (fn [{:keys [x y]}]
                     (let [{clng :lng clat :lat} (proj/tile-center-lng-lat {:x x :y y :z z})]
                       (reduce + (map * (s/lat-lon->globe clat clng) eye))))
              nearest (apply max-key dot* (for [x (range (bit-shift-left 1 z))
                                                y (range (bit-shift-left 1 z))]
                                            {:x x :y y :z z}))
              kept (set (map #(select-keys % [:x :y]) (s/visible-tiles z cam)))]
          (is (contains? kept (select-keys nearest [:x :y]))
              (str "distance " d " culled the tile directly below the camera")))))))

(deftest ground-polygons-carry-a-colour-and-sit-above-the-raster
  (let [recs [{:l "water" :c "ocean" :r [0.0 0.0 0.01 0.0 0.01 0.01 0.0 0.01]}
              {:l "landcover" :c "grass" :r [1.0 1.0 1.01 1.0 1.01 1.01 1.0 1.01]}]
        lift (/ 1.0 6371000.0)
        m (s/surface->mesh recs lift)
        vs (vec (partition 8 (:vertices m)))]
    (is (pos? (count vs)))
    (testing "every vertex is ABOVE radius 1 -- coplanar with the raster
              tile it covers would z-fight into a shimmer that reads as a
              driver bug"
      (doseq [[x y z] (map #(take 3 %) vs)]
        (is (> (Math/sqrt (+ (* x x) (* y y) (* z z))) 1.0))))
    (testing "the colour is in the vertex, taken from the MVT class"
      (let [colours (set (map #(vec (take 3 (drop 3 %))) vs))]
        (is (contains? colours (get s/surface-colour "ocean")))
        (is (contains? colours (get s/surface-colour "grass")))))
    (testing "an unknown class gets a colour rather than a nil that reaches the GPU"
      (let [u (s/surface->mesh [{:l "x" :c "no-such-class"
                                 :r [0.0 0.0 0.01 0.0 0.01 0.01 0.0 0.01]}] lift)]
        (is (= s/surface-default
               (vec (take 3 (drop 3 (first (partition 8 (:vertices u)))))))))))
  (testing "and degenerate rings do not break the batch"
    (let [m (s/surface->mesh [{:c "ocean" :r nil} {:c "ocean" :r [1.0 2.0]}] 0.001)]
      (is (empty? (:vertices m))))))
