(ns app-otent.iceberg-test
  "The pure parts of the Iceberg reader."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [app-otent.iceberg-id :as ice-id]))

;; REQUIRED, not transcribed. The first version of this file copied the
;; regex out of `iceberg.cljs` and got the fallback branch wrong, so two
;; assertions failed against a function the implementation did not have.
;; The rule now lives in its own pure namespace so there is one of it.
(def ^:private snapshot-id-from ice-id/snapshot-id)

(deftest a-snapshot-id-survives-being-64-bits-wide
  ;; JSON.parse gives a double. 4043499409833639796 comes back as
  ;; 4043499409833640000 -- still shaped like an id, off by 204, and this is
  ;; the cache key.
  (let [ml (str "s3://b/__r2_data_catalog/u1/u2/metadata/"
                "snap-4043499409833639796-0-39fad8bf-72b3-4d49-93f0-3f0d81242c2c.avro")]
    (is (= "4043499409833639796" (snapshot-id-from ml 4043499409833640000)))
    (testing "and specifically it is NOT the rounded number"
      (is (not= "4043499409833640000" (snapshot-id-from ml 4043499409833640000))))))

(deftest an-unparseable-manifest-list-falls-back-to-something-unique
  ;; The fallback must still differ between snapshots. Returning the rounded
  ;; number would be the one thing that could collide.
  (let [a "s3://b/metadata/unexpected-name-a.avro"
        b "s3://b/metadata/unexpected-name-b.avro"]
    (is (not= (snapshot-id-from a 1) (snapshot-id-from b 1))
        "two different snapshots produced the same cache key")))

(deftest negative-snapshot-ids-fall-back-rather-than-mismatching
  ;; Iceberg ids are signed. A negative one does not match the digits-only
  ;; pattern, and falling back to the full path is correct -- silently
  ;; returning a positive substring would not be.
  (let [ml "s3://b/metadata/snap--12345-0-uuid.avro"]
    (is (str/includes? (snapshot-id-from ml -12345) "snap--12345"))))

