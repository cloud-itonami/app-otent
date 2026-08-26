(ns app-otent.prune
  "Deciding which Iceberg data files a scan has to open.

  Pure, and `.cljc`, for the same reason `objects.cljc` is: the decision is
  arithmetic on manifest metadata and has no business being reachable only
  from a Worker.

  `iceberg.cljs` does the fetching and hands the file list here."
  (:require [clojure.string :as str]))

(defn bytes->str
  "An Avro `bytes` value -> the string it encodes.

  The decoder in `kotoba-lang/org-apache-avro` hands `bytes` back as a
  **vector of byte integers**, not a `Uint8Array` and not a string. The
  first version of this called `js/Buffer.from` on it, which throws on a
  PersistentVector, which was caught, which returned nil -- so every bound
  decoded to nil, the bounds map came back empty, and `prune-files` reported
  `:no-bounds` and read every file.

  That is precisely the failure `bounds-map` warns about one docstring
  below: nothing is prunable, which is slow but correct, so the mistake does
  not announce itself. It was caught only because `:reason` travels on the
  response -- measured on the live table, `files 5/5 pruned=0
  reason=no-bounds` on a table whose manifests demonstrably carry bounds."
  [b]
  (cond
    (nil? b) nil
    (string? b) b
    (sequential? b) (.toString (js/Buffer.from (js/Uint8Array.from (clj->js b))) "utf8")
    :else (try (.toString (js/Buffer.from b) "utf8") (catch :default _ nil))))

(defn bounds-map
  "Iceberg `lower_bounds` / `upper_bounds` -> `{field-id \"value\"}`.

  The Avro type is `map<int, bytes>`, which a decoder may hand back either
  as a map or as an array of `{\"key\" .. \"value\" ..}` records depending on
  how the writer encoded it. Both shapes are accepted, because guessing one
  and getting the other yields an EMPTY bounds map -- and an empty bounds
  map means `no file can be pruned`, which is slow but correct, so the
  mistake would never announce itself.

  The bytes for a string column are its UTF-8, possibly truncated. Callers
  must therefore treat an upper bound as `>=` the true maximum and a lower
  bound as `<=` the true minimum, which is the direction that keeps
  pruning safe."
  [v]
  (cond
    (nil? v) {}
    (map? v) (into {} (map (fn [[k b]] [(js/Number k) (bytes->str b)])) v)
    (sequential? v) (into {} (map (fn [e] [(js/Number (get e "key"))
                                           (bytes->str (get e "value"))])) v)
    :else {}))

(defn prune-files
  "Which data files can hold a row at or after the cutoff.

  The cutoff is derived from the files themselves: `newest` is the largest
  upper bound any file reports for the field, and the cutoff is that minus
  the window. So the window stays measured from the data, exactly as
  `app-otent.objects/fold` measures it, and the two cannot drift apart into
  a scan that reads a window the fold then discards.

  **A file with no bound for the field is kept.** Absence of a bound is not
  a bound of negative infinity: a writer may omit them, and a reader that
  treated `unknown` as `too old` would silently return a subset and look
  fast doing it. Pruning must only ever remove files it can prove are
  irrelevant.

  Comparison is string-wise because `observed_at` is stored as text (see
  `otent.observation`). Millisecond epochs are 13 digits from 2001 to 2286,
  so lexicographic order equals numeric order across every value these
  tables can hold -- and `newest` is checked to be 13 digits before any
  file is dropped, because a 14-digit value would compare below every row
  and prune the entire table."
  [files field-id window-ms]
  (let [uppers (keep #(get (:upper %) field-id) files)
        newest (when (seq uppers) (reduce (fn [a b] (if (pos? (compare b a)) b a)) uppers))]
    (cond
      (nil? field-id)
      {:files files :pruned 0 :reason :no-field-id}

      (nil? newest)
      ;; No file reported a bound. Read everything; say so, so a caller
      ;; cannot read "0 pruned" as "0 prunable".
      {:files files :pruned 0 :reason :no-bounds}

      (not= 13 (count newest))
      {:files files :pruned 0 :reason :bound-not-13-digits :newest newest}

      :else
      (let [cutoff (str (- (js/Number newest) window-ms))
            keep? (fn [f] (let [u (get (:upper f) field-id)]
                            (or (nil? u) (>= (compare u cutoff) 0))))
            kept (filterv keep? files)]
        {:files kept
         :pruned (- (count files) (count kept))
         :reason :pruned
         :newest newest
         :cutoff cutoff}))))

