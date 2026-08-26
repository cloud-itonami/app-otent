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

;; ── amortised propagation ────────────────────────────────────────────────────

(deftest a-satellite-outside-the-slice-keeps-its-last-position
  (testing "the whole point of amortising: returning only what was computed
            would strobe the star field, and a reader counting objects would
            see a number that swings with the slice rather than with the sky"
    (let [prev {25544 {:id 25544 :lat-deg 1.0 :lon-deg 2.0 :kind :satellite}
                99999 {:id 99999 :lat-deg 3.0 :lon-deg 4.0 :kind :satellite}}
          r (prop/positions-slice [] prev {} 1787726417000 0)]
      (is (= prev (:by-id r))
          "an empty satellite list dropped positions that were already known"))))

(deftest the-cursor-advances-and-eventually-covers-everything
  (testing "a cursor that stalls repropagates the same slice forever and
            never reaches the rest -- and because every satellite outside
            the slice keeps its last position, a stalled cursor looks
            exactly like a working one on screen"
    (let [n 10
          fake (mapv (fn [i] {:satnum i :display-name (str i)}) (range n))
          step 3]
      (let [c1 (:cursor (prop/positions-slice fake {} {} 1787726417000 0 step))]
        (is (= 3 c1) "the cursor did not advance by the slice size"))
      ;; four steps of three over ten wraps past the end and back
      (let [cs (reductions (fn [c _] (:cursor (prop/positions-slice fake {} {} 1787726417000 c step)))
                           0 (range 4))]
        (is (= [0 3 6 9 2] (vec cs))
            (str "the cursor did not walk and wrap: " (pr-str (vec cs)))))
      (testing "and every satellite is visited within ceil(n/step) advances"
        (let [seen (loop [c 0 i 0 acc #{}]
                     (if (>= i 4)
                       acc
                       (let [r (prop/positions-slice fake {} {} 1787726417000 c step)]
                         (recur (:cursor r) (inc i) (into acc (keys (:failed-by-id r)))))))]
          (is (= (set (range n)) seen)
              (str "not every satellite was reached: " (pr-str (sort seen)))))))))

(deftest a-refusal-clears-when-the-satellite-succeeds-again
  (testing "and a stale entry survives the advances where its satellite is
            not in the slice -- rebuilding the map from the slice alone
            would report four stale satellites as zero most of the time"
    (let [prev-failed {123 {:id 123 :error :sgp4/decayed}}
          r (prop/positions-slice [] {} prev-failed 1787726417000 0)]
      (is (= prev-failed (:failed-by-id r))))))

(deftest the-slice-size-is-a-number-someone-chose
  (testing "measured at 18.2 us per propagation, so 15,258 satellites is
            278 ms/frame and a 16.7 ms frame fits 917"
    (is (number? prop/slice-size))
    (is (pos? prop/slice-size))
    (is (< prop/slice-size 2000)
        "a slice that large stops being amortisation")))
