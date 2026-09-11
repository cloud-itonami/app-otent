(ns app-otent.objects-test
  "The fold that decides what the globe is shown.

  Every test here asserts both directions: what survives and what is
  dropped, and for which of the two reasons."
  (:require [clojure.test :refer [deftest is testing]]
            [app-otent.objects :as o]))

(def t0 1787726417000)

(defn ac [id t] {:id id :t t :lat 1.0 :lon 2.0})

(deftest one-row-per-object-not-one-per-observation
  (testing "the bug this exists for: an aircraft polled four times was four
            markers at four positions, all drawn as live"
    (let [rows [(ac "a" (- t0 60000)) (ac "a" (- t0 30000)) (ac "a" t0)
                (ac "b" (- t0 90000))]
          {:keys [objects stats]} (o/fold "aircraft" rows)]
      (is (= 2 (count objects)))
      (is (= #{"a" "b"} (set (map :id objects))))
      (testing "and the one kept is the newest, not the first seen"
        (is (= t0 (:t (first (filter #(= "a" (:id %)) objects))))))
      (is (= 2 (:dropped-superseded stats)))
      (is (= 0 (:dropped-stale stats))))))

(deftest positions-older-than-the-window-are-dropped
  (let [rows [(ac "fresh" t0)
              (ac "old" (- t0 (inc (o/window-ms "aircraft"))))]  ; one ms past it
        {:keys [objects stats]} (o/fold "aircraft" rows)]
    (is (= ["fresh"] (map :id objects)))
    (is (= 1 (:dropped-stale stats)))
    (testing "dropped-stale and dropped-superseded are different facts and
              are not summed into one number"
      (is (= 0 (:dropped-superseded stats))))))

(deftest the-window-is-measured-from-the-data-not-the-clock
  (testing "a snapshot written a year ago still shows its own contents --
            otherwise a table nobody has appended to reads as an empty sky,
            and the immutable cache would freeze one moment's answer under
            a key that promises the snapshot's"
    (let [ancient (- t0 (* 365 86400000))
          rows [(ac "a" ancient) (ac "b" (- ancient 60000))]
          {:keys [objects stats]} (o/fold "aircraft" rows)]
      (is (= 2 (count objects)))
      (is (= 0 (:dropped-stale stats)))
      (is (= ancient (:newest-observed-at stats))))))

(deftest each-kind-carries-its-own-window
  (testing "an earthquake is an event that stays happened; an aircraft fix
            decays in minutes. One window for both would either hide quakes
            or show day-old aircraft."
    (is (< (o/window-ms "aircraft") (o/window-ms "quake")))
    (is (< (o/window-ms "aircraft") 3600000)
        "an aircraft fix an hour old is not a live position"))
  (testing "and every window must outlast the poll interval, or the map
            empties between polls with nothing in the output saying why"
    (doseq [k ["aircraft" "vessel" "quake" "fire" "satellite"]]
      (is (> (o/window-ms k) o/ingest-interval-ms)
          (str k " has a window shorter than the ingest interval"))))
  (testing "an unknown kind gets a window, not nil -- a nil cutoff would
            silently disable the filter for exactly the kinds nobody
            thought about"
    (is (number? (o/window-ms "something-new")))
    (is (number? (o/window-ms nil)))))

(deftest a-quake-revised-by-usgs-collapses-to-the-revision
  (testing "same id, later observation: the dedup here is for USGS revising
            a magnitude, not for the quake moving"
    (let [rows [{:id "us7000" :t (- t0 3600000) :attrs {"mag" 4.7}}
                {:id "us7000" :t t0 :attrs {"mag" 5.1}}]
          {:keys [objects]} (o/fold "quake" rows)]
      (is (= 1 (count objects)))
      (is (= 5.1 (get (:attrs (first objects)) "mag"))))))

(deftest rows-we-cannot-judge-are-kept-and-counted
  (testing "an object with no timestamp cannot be called stale, and
            dropping what we cannot judge would quietly shrink the answer"
    (let [rows [(ac "a" t0) {:id "notime" :lat 1.0 :lon 2.0}]
          {:keys [objects stats]} (o/fold "aircraft" rows)]
      (is (= 2 (count objects)))
      (is (= 1 (:untimed stats))))))

(deftest the-order-is-stable-so-the-immutable-cache-is-honest
  (testing "the response URL carries the snapshot id and is served
            immutable, so the same snapshot must produce the same bytes"
    (let [rows [(ac "c" t0) (ac "a" t0) (ac "b" t0)]]
      (is (= ["a" "b" "c"] (map :id (:objects (o/fold "aircraft" rows)))))
      (is (= (:objects (o/fold "aircraft" rows))
             (:objects (o/fold "aircraft" (reverse rows))))))))

(deftest an-empty-scan-stays-empty-without-throwing
  (let [{:keys [objects stats]} (o/fold "aircraft" [])]
    (is (= [] objects))
    (is (= 0 (:rows stats)))
    (is (nil? (:newest-observed-at stats)))))
