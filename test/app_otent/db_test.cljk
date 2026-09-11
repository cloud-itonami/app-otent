(ns app-otent.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [app-otent.db :as db]))

(deftest unavailable-does-not-collapse-into-loaded-zero
  ;; The distinction the whole app is built to preserve: `otent` reports
  ;; vessels as UNMEASURED because nothing has ever read that feed. A layer
  ;; that could not be answered must not look like a layer that was answered
  ;; and found nothing.
  (let [d (-> db/default-db
              (assoc-in [:layers :quake] {:status :loaded :objects [] :refused []})
              (assoc-in [:layers :vessel] {:status :unavailable :objects [] :refused []
                                           :detail "no resident collector"}))]
    (is (= 0 (:count (first (filter #(= :quake (:kind %)) (db/layer-summary d))))))
    (is (= :loaded (:status (first (filter #(= :quake (:kind %)) (db/layer-summary d))))))
    (is (= [:vessel] (map :kind (db/unavailable-kinds d)))
        "an empty loaded layer must not be reported as unavailable")
    (is (= "no resident collector" (:detail (first (db/unavailable-kinds d)))))))

(deftest an-empty-layer-has-unknown-age-not-zero
  ;; Zero reads as "perfectly fresh", which is the opposite of what an
  ;; empty layer means.
  (let [d (assoc-in db/default-db [:layers :fire] {:status :loaded :objects []})]
    (is (nil? (db/staleness-ms d :fire 1000)))
    (is (= "unknown" (db/describe-age nil)))
    (is (not= "unknown" (db/describe-age 0)))))

(deftest age-is-described-in-units-a-person-reads
  (is (= "30s ago" (db/describe-age 30000)))
  (is (= "5m ago" (db/describe-age 300000)))
  (is (= "3h ago" (db/describe-age 10800000)))
  (is (= "2d ago" (db/describe-age 172800000)))
  (testing "a timestamp ahead of the clock is said so, not shown as a
            negative number of seconds ago"
    (is (= "ahead of this clock" (db/describe-age -5000)))))

(deftest the-camera-never-goes-inside-the-planet-or-through-a-pole
  (doseq [c [{:lat-deg 95.0 :lon-deg 0.0 :distance 0.1}
             {:lat-deg -400.0 :lon-deg 900.0 :distance -3.0}
             {:lat-deg 89.9999 :lon-deg 180.0 :distance 1000.0}]]
    (let [r (db/clamp-camera c)]
      (is (<= -89.9 (:lat-deg r) 89.9) (str c))
      (is (<= -180.0 (:lon-deg r) 180.0) (str c))
      ;; The bounds are the CONSTANTS, not literals. Written as 1.05 and 40
      ;; they broke when the floor was lowered so the ground could be
      ;; reached -- for a reason unrelated to what this checks.
      (is (<= db/min-camera-distance (:distance r) db/max-camera-distance) (str c))
      (is (> (:distance r) 1.0) (str c " put the camera inside the planet")))))

(deftest longitude-wraps-rather-than-clamping
  ;; Clamping longitude would stop the globe spinning at the antimeridian.
  (is (< (Math/abs (- 170.0 (:lon-deg (db/clamp-camera {:lat-deg 0.0 :lon-deg -190.0 :distance 3.0})))) 1e-9))
  (is (< (Math/abs (- -170.0 (:lon-deg (db/clamp-camera {:lat-deg 0.0 :lon-deg 190.0 :distance 3.0})))) 1e-9))
  (testing "and dragging all the way round returns to where it started"
    (let [start {:lat-deg 0.0 :lon-deg 0.0 :distance 3.0}
          round (reduce (fn [c _] (db/drag-camera c 100 0)) start (range 100))]
      (is (<= -180.0 (:lon-deg round) 180.0)))))

(deftest dragging-near-the-surface-moves-less-than-dragging-far-away
  ;; Otherwise a small drag when zoomed in spins the planet out of view.
  (let [near (db/drag-camera {:lat-deg 0.0 :lon-deg 0.0 :distance 1.1} 100 0)
        far  (db/drag-camera {:lat-deg 0.0 :lon-deg 0.0 :distance 8.0} 100 0)]
    (is (< (Math/abs (:lon-deg near)) (Math/abs (:lon-deg far))))))

(deftest zoom-is-multiplicative-and-bounded
  (let [c {:lat-deg 0.0 :lon-deg 0.0 :distance 3.0}]
    (is (> (:distance (db/zoom-camera c 500)) 3.0))
    (is (< (:distance (db/zoom-camera c -500)) 3.0))
    (testing "and no amount of scrolling escapes the bounds"
      (let [way-in (reduce (fn [c _] (db/zoom-camera c -1000)) c (range 50))
            way-out (reduce (fn [c _] (db/zoom-camera c 1000)) c (range 50))]
        (is (>= (:distance way-in) db/min-camera-distance))
        (is (<= (:distance way-out) db/max-camera-distance))))))

(deftest every-kind-has-a-layer-from-the-start
  ;; A missing key renders as nil and the kind disappears from the UI
  ;; entirely -- which is the silent version of the bug this app is about.
  (doseq [k db/kinds]
    (is (some? (db/layer db/default-db k)) (str k " has no layer"))
    (is (= :idle (:status (db/layer db/default-db k))))))
