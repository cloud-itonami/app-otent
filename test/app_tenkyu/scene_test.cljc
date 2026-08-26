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
            [kotoba.geo.projection :as proj]))

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
  (doseq [d [-5.0 0.0 0.5 1.0 1.01]]
    (let [{:keys [eye]} (s/orbit-camera {:distance d :lat-deg 20.0 :lon-deg 30.0})
          r (Math/sqrt (reduce + (map * eye eye)))]
      (is (>= r 1.02) (str "distance " d " put the eye at r=" r)))))

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
  (is (= (/ Math/PI 2.0) (s/tile-angular-radius 0)) "z0 must be capped at a quarter turn")
  (is (= (/ Math/PI 2.0) (s/tile-angular-radius 1)))
  (is (> (s/tile-angular-radius 2) (s/tile-angular-radius 5)))
  (doseq [z (range 0 12)]
    (is (<= 0.0 (s/tile-angular-radius z) (/ Math/PI 2.0)))))

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
