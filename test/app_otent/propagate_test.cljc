(ns app-otent.propagate-test
  (:require [clojure.test :refer [deftest is testing]]
            [app-otent.propagate :as prop]))

;; Real element sets, fetched from CelesTrak 2026-08-26. The GEO one is
;; here because it must be REFUSED, and a fixture with only near-earth
;; satellites in it could not show that.
(def objects
  [{:id "25544" :name "ISS (ZARYA)"
    :line1 "1 25544U 98067A   26237.66055538  .00007716  00000+0  14485-3 0  9993"
    :line2 "2 25544  51.6329 316.2335 0007673  83.1052 277.0809 15.49625410582525"}
   {:id "25994" :name "TERRA"
    :line1 "1 25994U 99068A   26237.80543928  .00000296  00000+0  69197-4 0  9992"
    :line2 "2 25994  97.9403 284.5724 0002839  40.3429  49.8605 14.61149123419771"}
   {:id "41866" :name "GOES 16"
    :line1 "1 41866U 16071A   26237.66711072 -.00000094  00000+0  00000+0 0  9992"
    :line2 "2 41866   0.5042  84.9564 0001350 108.7145 275.7029  1.00273070 35807"}
   {:id "99999" :name "NO ELEMENTS"}])

(def t0 1787680800000)   ; 2026-08-25T18:00:00Z

(deftest refused-satellites-are-reported-not-dropped
  (let [{:keys [ready refused]} (prop/initialize objects)]
    (is (= 2 (count ready)) "the two near-earth satellites should initialize")
    (is (= 2 (count refused)))
    (testing "and each refusal says WHY, so the UI can distinguish 'deep
              space, needs SDP4' from 'this row had no element set'"
      (is (= #{:sgp4/deep-space-unsupported :no-element-set}
             (set (map :error refused)))))))

(deftest positions-are-plausible-and-labelled
  (let [{:keys [ready]} (prop/initialize objects)
        {:keys [objects failed]} (prop/positions ready t0)]
    (is (= 2 (count objects)))
    (is (empty? failed))
    (doseq [o objects]
      (is (= :satellite (:kind o)))
      (is (<= -90.0 (:lat-deg o) 90.0))
      (is (<= -180.0 (:lon-deg o) 180.0))
      (is (< 300.0 (:alt-km o) 900.0)
          (str (:name o) " at " (:alt-km o) " km is not a low Earth orbit"))
      (is (string? (:name o)) "a marker with no name cannot be identified"))
    (testing "and each satellite stays inside its own inclination"
      (let [iss (first (filter #(= "25544" (:id %)) objects))]
        (is (< (Math/abs (:lat-deg iss)) 52.0))))))

(deftest the-satellite-actually-moves
  ;; The whole reason elements are stored instead of positions. If this
  ;; returned the same point for every instant, the table's design would
  ;; be pointless and the globe would look fine.
  (let [{:keys [ready]} (prop/initialize objects)
        a (:objects (prop/positions ready t0))
        b (:objects (prop/positions ready (+ t0 600000)))    ; +10 min
        pa (first (filter #(= "25544" (:id %)) a))
        pb (first (filter #(= "25544" (:id %)) b))
        d (+ (Math/abs (- (:lat-deg pa) (:lat-deg pb)))
             (Math/abs (- (:lon-deg pa) (:lon-deg pb))))]
    (is (> d 10.0)
        (str "the ISS moved " d " degrees in ten minutes -- it covers about "
             "40 of longitude, so this is not propagating"))))

(deftest a-ground-track-is-continuous
  (let [{:keys [ready]} (prop/initialize objects)
        sat (first (filter #(= "25544" (:satnum %)) ready))
        track (prop/ground-track sat t0 90.0 2.0)]
    (is (<= 40 (count track)) "a 90 minute track at 2 minute steps is ~46 points")
    (testing "consecutive points are adjacent -- apart from the antimeridian,
              where longitude legitimately jumps 360"
      (doseq [[[lon1 lat1] [lon2 lat2]] (partition 2 1 track)]
        (is (< (Math/abs (- lat2 lat1)) 8.0)
            (str "latitude jumped from " lat1 " to " lat2))
        (let [dlon (Math/abs (- lon2 lon1))]
          (is (or (< dlon 20.0) (> dlon 340.0))
              (str "longitude jumped from " lon1 " to " lon2)))))))
