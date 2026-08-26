(ns app-otent.prune-test
  "Which data files a scan must open, and which it may skip."
  (:require [clojure.test :refer [deftest is testing]]
            [app-otent.prune :as ice]))

(defn- f [name upper] {:path name :records 10 :upper (if upper {8 upper} {})})

(deftest prune-keeps-only-files-that-can-hold-a-recent-row
  (let [files [(f "old" "1787000000000")
               (f "mid" "1787700000000")
               (f "new" "1787726417000")]
        r (ice/prune-files files 8 1800000)]      ; 30 min
    (is (= :pruned (:reason r)))
    (is (= ["new"] (map :path (:files r))))
    (is (= 2 (:pruned r)))
    (is (= "1787726417000" (:newest r)))))

(deftest a-file-with-no-bound-is-never-pruned
  (testing "absence of a bound is not a bound of negative infinity -- a
            reader that treated unknown as too old would return a subset
            and look fast doing it"
    (let [files [(f "new" "1787726417000") (f "unbounded" nil)]
          r (ice/prune-files files 8 1800000)]
      (is (= #{"new" "unbounded"} (set (map :path (:files r)))))
      (is (= 0 (:pruned r))))))

(deftest no-bounds-at-all-reads-everything-and-says-so
  (let [files [(f "a" nil) (f "b" nil)]
        r (ice/prune-files files 8 1800000)]
    (is (= :no-bounds (:reason r)))
    (is (= 2 (count (:files r))))
    (testing "the reason is reported so a caller cannot read `0 pruned` as
              `0 prunable`"
      (is (not= :pruned (:reason r))))))

(deftest an-unknown-field-prunes-nothing
  (let [r (ice/prune-files [(f "a" "1787726417000")] nil 1800000)]
    (is (= :no-field-id (:reason r)))
    (is (= 1 (count (:files r))))))

(deftest a-bound-that-is-not-13-digits-prunes-nothing
  (testing "observed_at is text, so string order equals numeric order only
            while every value has the same digit count. A 14-digit newest
            would compare above every row and prune the whole table."
    (let [r (ice/prune-files [(f "a" "17877264170000") (f "b" "1787000000000")] 8 1800000)]
      (is (= :bound-not-13-digits (:reason r)))
      (is (= 2 (count (:files r)))))))

(deftest the-boundary-file-is-kept
  (let [files [(f "exactly-at-cutoff" "1787724617000")   ; newest - 30 min
               (f "new" "1787726417000")]
        r (ice/prune-files files 8 1800000)]
    (is (= 0 (:pruned r))
        (str "a file whose newest row is exactly at the cutoff was dropped: "
             (pr-str r)))))

(deftest bounds-map-accepts-both-avro-shapes
  (testing "map<int,bytes> comes back as a map or as key/value records
            depending on the writer; guessing wrong yields an EMPTY bounds
            map, which means nothing is prunable -- slow but correct, so
            the mistake never announces itself"
    (is (= {8 "x"} (ice/bounds-map {8 "x"})))
    (is (= {8 "x"} (ice/bounds-map [{"key" 8 "value" "x"}])))
    (is (= {} (ice/bounds-map nil)))))

(deftest bytes-come-back-as-a-vector-of-integers
  (testing "the decoder hands Avro `bytes` back as a vector of byte ints.
            Buffer.from throws on that, the throw was caught, and every
            bound decoded to nil -- so the bounds map was empty and nothing
            was ever prunable. Measured on the live table as
            `files 5/5 pruned=0 reason=no-bounds`."
    (is (= "1787673071984" (ice/bytes->str [49 55 56 55 54 55 51 48 55 49 57 56 52])))
    (is (= "celestrak" (ice/bytes->str [99 101 108 101 115 116 114 97 107])))
    (is (nil? (ice/bytes->str nil)))
    (is (= "already" (ice/bytes->str "already")))))

(deftest the-real-manifest-shape-decodes-end-to-end
  (testing "one entry exactly as org-apache-avro returns it"
    (let [raw [{"key" 8 "value" [49 55 56 55 54 55 51 48 55 49 57 56 52]}
               {"key" 4 "value" [115 97 116 101 108 108 105 116 101]}]
          m (ice/bounds-map raw)]
      (is (= "1787673071984" (get m 8)))
      (is (= "satellite" (get m 4)))
      (testing "and it is usable for pruning, which the empty map was not"
        (let [r (ice/prune-files [{:path "old" :records 1 :upper m}
                                  {:path "new" :records 1 :upper {8 "1787726417000"}}]
                                 8 1800000)]
          (is (= :pruned (:reason r)))
          (is (= ["new"] (map :path (:files r)))))))))
