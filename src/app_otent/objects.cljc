(ns app-otent.objects
  "What the globe should be shown, out of what the table holds.

  The table is append-only: every tick writes a fresh row for every object
  the feed reported, so an aircraft polled four times is four rows at four
  positions. Until 2026-08-26 the API returned all of them and the map drew
  all of them, which meant **the same aircraft appeared several times, at
  positions up to a day and a half old, indistinguishable from live ones**.
  Measured that day: 25,219 rows for 15,573 distinct aircraft, spanning
  33.8 hours, 6.5 MB and 33.9 s on the wire.

  That is a correctness fault before it is a size one. A view that shows a
  33-hour-old position next to a current one, as two aircraft, is not a
  slow view of the sky; it is a wrong one.

  ## The window is measured from the data, not from the clock

  `newest` is the largest `observed-at` in the scan, and the cutoff is
  relative to it -- not to `Date.now()`. That is what makes this fold a
  pure function of the snapshot, which is what the caching layer already
  assumes: the response URL carries the Iceberg snapshot id and is served
  `immutable`, so a body computed against wall-clock would freeze one
  moment's answer under a key that promises the snapshot's answer.

  It is also the honest reading. If the snapshot has not moved, nothing
  newer exists, and an empty result would say `the sky is empty` when it
  means `nobody has written since`.

  ## Superseded and stale are counted separately

  A row dropped because a newer one exists for the same object is not the
  same fact as a row dropped for being older than the window, and neither
  is the same as an object we simply do not have. The stats travel in the
  response so a reader can tell `no aircraft` from `no aircraft recently`."
  (:require [clojure.string :as str]))

(def default-window-ms
  "How far back an object may have been observed and still be drawn.

  Chosen per kind because the kinds are not the same sort of thing. An
  aircraft position is a fix that decays in minutes. An earthquake is an
  event that happened and stays happened -- the dedup there is for USGS
  revising a magnitude, not for the quake moving. A satellite row is a set
  of elements the browser propagates itself, so what matters is that the
  elements are fresh enough to propagate, not that the row is recent."
  {"aircraft"  1800000                  ; 30 min
   "vessel"    3600000                  ; 1 h
   "fire"      259200000                ; 3 days -- FIRMS publishes in batches
   "quake"     604800000                ; 7 days
   "satellite" 604800000})              ; 7 days -- TLE accuracy degrades past that

(def fallback-window-ms 3600000)

(defn window-ms [kind]
  (get default-window-ms (some-> kind name str/lower-case) fallback-window-ms))

(defn fold
  "`objects` -> the newest observation per object, within the window.

  Returns `{:objects [...] :stats {...}}`. Order is by `:id` so the body is
  byte-stable for a given snapshot; the cache key promises that.

  An object with no `:t` is KEPT and counted. We cannot say it is stale, and
  dropping what we cannot judge would quietly shrink the answer."
  [kind objects]
  (let [w (window-ms kind)
        timed (filter :t objects)
        untimed (remove :t objects)
        newest (when (seq timed) (reduce max (map :t timed)))
        cutoff (when newest (- newest w))
        fresh (if cutoff (filter #(>= (:t %) cutoff) timed) timed)
        stale (- (count timed) (count fresh))
        latest (->> fresh
                    (reduce (fn [acc o]
                              (let [k (:id o)
                                    prev (get acc k)]
                                (if (or (nil? prev) (> (:t o) (:t prev)))
                                  (assoc acc k o)
                                  acc)))
                            {})
                    vals
                    (concat untimed)
                    (sort-by #(str (:id %)))
                    vec)]
    {:objects latest
     :stats {:rows (count objects)
             :returned (count latest)
             :dropped-stale stale
             :dropped-superseded (- (count fresh) (- (count latest) (count untimed)))
             :untimed (count untimed)
             :window-ms w
             :newest-observed-at newest
             :oldest-shown (when (seq latest) (reduce min (map :t (filter :t latest))))}}))
