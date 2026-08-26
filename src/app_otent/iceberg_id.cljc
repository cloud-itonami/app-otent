(ns app-otent.iceberg-id
  "How a snapshot is identified. Pure `.cljc`, and separate from
  `app-otent.iceberg` on purpose.

  `iceberg.cljs` needs an R2 binding to load, so it cannot be required from
  a test. The first version of this rule was therefore *transcribed* into
  the test file -- and the transcription disagreed with the code on the
  fallback branch, so two assertions failed against a function the
  implementation does not have. A rule worth testing is worth requiring."
  (:require [clojure.string :as str]))

(defn snapshot-id
  "A stable, exact identity for an Iceberg snapshot, for use as a cache key.

  Taken from the manifest-list FILENAME (`snap-<id>-<seq>-<uuid>.avro`),
  where the id is text.

  Not from the JSON number: an Iceberg snapshot id is a full 64-bit
  integer and `JSON.parse` produces a double, so 4043499409833639796 comes
  back as 4043499409833640000. It still looks like an id. As a cache key,
  two snapshots landing on the same double would mean serving the older
  one forever -- and the rounding is silent, so the first symptom would be
  a globe that stopped updating.

  When the name does not match -- a different writer, or a negative id --
  the fallback is the whole manifest-list path, which is unique per
  snapshot by construction. Falling back to the rounded number would
  reintroduce exactly the collision this avoids."
  [manifest-list rounded-number]
  (or (second (re-find #"/snap-(\d+)-" (str manifest-list)))
      (when-not (str/blank? (str manifest-list)) (str manifest-list))
      (str rounded-number)))
