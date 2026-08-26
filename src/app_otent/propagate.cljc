(ns app-otent.propagate
  "Satellites, from element sets to points on the globe, in the browser.

  This is why the satellite table holds twenty-one rows a day instead of
  twenty-one rows a second: a TLE is a *function of time*, and evaluating
  it is cheap enough to do per frame. `kotoba-lang/sgp4` does the
  evaluation; this decides what to do with what it returns.

  Pure, so the propagation can be checked without a canvas."
  (:require [sgp4.core :as sgp4]
            [sgp4.frames :as frames]
            [sgp4.time :as stime]))

(defn initialize
  "Element sets from the API -> initialized propagators, **and** the ones
  that were refused, with the reason.

  Both, always. Roughly one satellite in five in a mixed catalogue is deep
  space, and `sgp4` refuses those rather than returning a wrong position.
  A UI that silently drew the other four fifths would be showing a partial
  sky and calling it the sky."
  [objects]
  (reduce
   (fn [acc {:keys [id line1 line2 name]}]
     (if-not (and line1 line2)
       (update acc :refused conj {:id id :name name :error :no-element-set})
       (let [r (sgp4/initialize-tle line1 line2 {:name name})]
         (if (:ok? r)
           (update acc :ready conj (assoc (:sat r) :display-name (or name id)))
           (update acc :refused conj {:id id :name name
                                      :error (:error r) :detail (:detail r)})))))
   {:ready [] :refused []}
   objects))

(defn positions
  "Propagate every satellite to `unix-ms`.

  Returns `{:objects [...] :failed [...]}`. A satellite whose element set
  has aged out of validity comes back in `:failed` -- it does not vanish
  from the count, because a shrinking star field with no explanation is
  worse than a number that says four are stale."
  [sats unix-ms]
  (let [[jd frac] (stime/unix-ms->jd unix-ms)]
    (reduce
     (fn [acc sat]
       (let [p (sgp4/propagate-at sat unix-ms)]
         (if-not (:ok? p)
           (update acc :failed conj {:id (:satnum sat)
                                     :name (:display-name sat)
                                     :error (:error p)})
           (let [g (frames/subpoint p jd frac)]
             (update acc :objects conj
                     {:id (:satnum sat)
                      :name (:display-name sat)
                      :kind :satellite
                      :lat-deg (:lat-deg g)
                      :lon-deg (:lon-deg g)
                      :alt-km (:alt-km g)
                      :speed-km-s (:speed-km-s g)
                      :size 5.0})))))
     {:objects [] :failed []}
     sats)))

(defn ground-track
  "Where a satellite will be over the next `minutes`, sampled every
  `step-min`. For drawing one orbit's track under a selected object.

  Stops at the first refusal rather than skipping it: a track with a hole
  in the middle is drawn as a straight line across the hole, which is a
  path the satellite does not take."
  [sat unix-ms minutes step-min]
  (loop [t 0.0 out []]
    (if (> t minutes)
      out
      (let [ms (+ unix-ms (* t 60000.0))
            p (sgp4/propagate-at sat ms)]
        (if-not (:ok? p)
          out
          (let [[jd frac] (stime/unix-ms->jd ms)
                g (frames/subpoint p jd frac)]
            (recur (+ t step-min)
                   (conj out [(:lon-deg g) (:lat-deg g)]))))))))

;; ── amortised propagation ────────────────────────────────────────────────────

(def slice-size
  "How many satellites are propagated per advance.

  `positions` propagates every satellite on every advance, which is right
  for twenty-one and impossible for fifteen thousand: SGP4 measured at
  **18.2 µs** per propagation on this machine, so the full CelesTrak
  `active` catalogue — 15,258 of 16,057 element sets, the other 799 being
  deep-space and refused rather than propagated wrongly — would cost 278 ms
  per frame. A 16.7 ms frame fits 917.

  So a slice moves each advance and the rest keep the position they last
  had. 512 is ~9 ms at the measured rate, and browsers are typically faster
  than nbb; the whole catalogue refreshes every 30 advances, about half a
  second. At 7.5 km/s that is 3.7 km of drift, and at full-globe zoom the
  screen is roughly 18 km per pixel, so it is a fifth of a pixel.

  This is the number to change if the star field looks like it is stepping.
  Raising it costs frame time linearly; lowering it costs refresh latency
  linearly."
  512)

(defn positions-slice
  "Propagate `slice-size` satellites starting at `cursor`, over `prev`.

  `prev` and `prev-failed` are maps keyed by satnum. Returns
  `{:by-id :failed-by-id :cursor}`.

  **A satellite not in this slice keeps its last position rather than
  disappearing.** The alternative — returning only what was computed —
  would make the star field strobe, and worse, a reader counting objects
  would see a number that swings with the slice rather than with the sky.

  The same holds for refusals: a stale element set stays in `failed-by-id`
  until its satellite is revisited and either succeeds or fails again.
  Rebuilding that map from the slice alone would report four stale
  satellites as zero for twenty-nine advances out of thirty."
  ([sats prev prev-failed unix-ms cursor]
   (positions-slice sats prev prev-failed unix-ms cursor slice-size))
  ([sats prev prev-failed unix-ms cursor take-size]
  (let [n (count sats)]
    (if (zero? n)
      {:by-id prev :failed-by-id prev-failed :cursor 0}
      (let [[jd frac] (stime/unix-ms->jd unix-ms)
            take-n (min take-size n)]
        (loop [i 0, c (mod cursor n), by prev, bad prev-failed]
          (if (>= i take-n)
            {:by-id by :failed-by-id bad :cursor c}
            (let [sat (nth sats c)
                  id (:satnum sat)
                  p (sgp4/propagate-at sat unix-ms)]
              (if-not (:ok? p)
                (recur (inc i) (mod (inc c) n)
                       (dissoc by id)
                       (assoc bad id {:id id :name (:display-name sat) :error (:error p)}))
                (let [g (frames/subpoint p jd frac)]
                  (recur (inc i) (mod (inc c) n)
                         (assoc by id {:id id
                                       :name (:display-name sat)
                                       :kind :satellite
                                       :lat-deg (:lat-deg g)
                                       :lon-deg (:lon-deg g)
                                       :alt-km (:alt-km g)
                                       :speed-km-s (:speed-km-s g)
                                       :size 5.0})
                         (dissoc bad id))))))))))))
