(ns app-otent.iceberg
  "Reading an Apache Iceberg table out of Cloudflare R2, in ClojureScript.

  Four steps, all of them the ones a real Iceberg client takes:

  1. `GET /v1/config` -> the catalog's `prefix` override
  2. `GET /v1/{prefix}/namespaces/{ns}/tables/{t}` -> the table metadata,
     **inline**, including the current snapshot's manifest-list
  3. R2 GET the manifest-list and each manifest (Avro) -> data file paths
  4. R2 GET each data file (Parquet) -> rows

  Every byte after step 2 comes through the Worker's R2 **binding**, not
  over the network: the data files are in a bucket this Worker is bound
  to, so there is no second credential and no egress.

  ## Why not list the bucket and skip the manifests

  Because it is wrong in exactly the way that does not show up until it
  matters. R2 still holds data files from expired snapshots and orphans
  from failed commits; listing a prefix returns those too. Today, on an
  append-only table that has never been compacted, the shortcut agrees
  with the manifests -- so it would pass every test written now and start
  returning deleted rows the first time the table is maintained.

  ## Step 1 is not optional

  The prefix is a per-account UUID. `pyiceberg` fetches it from
  `/v1/config` and so does this; hard-coding the warehouse name there
  returns HTTP 500 from Cloudflare, and guessing it returns 404. Measured
  both, 2026-08-26."
  (:require [app-otent.iceberg-id :as ice-id]
            [app-otent.prune :as prune]
            [avro.binary :as abin]
            [avro.file :as avro]
            [parquet.source :as pq]
            [columnar.plan :as plan]
            [clojure.string :as str]))

(defn- json [r] (.json r))

(defn- api-error [where status body]
  {:ok? false :error :iceberg/catalog-error
   :detail (str where " -> HTTP " status " " (subs (str body) 0 240))})

(defn catalog-prefix
  "Step 1. The catalog's own prefix for this warehouse.

  Cached by the caller against the Worker's lifetime; it is a property of
  the account, not of a request."
  [{:keys [catalog-uri warehouse token]}]
  (-> (js/fetch (str catalog-uri "/v1/config?warehouse=" (js/encodeURIComponent warehouse))
                #js {:headers #js {"Authorization" (str "Bearer " token)}})
      (.then (fn [r]
               (if-not (.-ok r)
                 (.then (.text r) #(api-error "GET /v1/config" (.-status r) %))
                 (.then (json r)
                        (fn [c]
                          (let [p (some-> c (aget "overrides") (aget "prefix"))]
                            (if (str/blank? (str p))
                              {:ok? false :error :iceberg/no-prefix
                               :detail "/v1/config returned no overrides.prefix"}
                              {:ok? true :prefix p})))))))
      (.catch (fn [e] {:ok? false :error :iceberg/unreachable
                       :detail (str (.-message e))}))))

(defn load-table
  "Step 2. The table's current metadata, straight from the catalog."
  [{:keys [catalog-uri token]} prefix namespace* table]
  (-> (js/fetch (str catalog-uri "/v1/" prefix "/namespaces/" namespace*
                     "/tables/" table)
                #js {:headers #js {"Authorization" (str "Bearer " token)}})
      (.then (fn [r]
               (if-not (.-ok r)
                 (.then (.text r) #(api-error (str "loadTable " namespace* "." table)
                                              (.-status r) %))
                 (.then (json r)
                        (fn [t]
                          (let [m (js->clj (aget t "metadata"))
                                snap-id (get m "current-snapshot-id")
                                snaps (get m "snapshots")
                                snap (first (filter #(= snap-id (get % "snapshot-id")) snaps))]
                            (cond
                              (nil? snap-id)
                              {:ok? false :error :iceberg/no-snapshot
                               :detail (str namespace* "." table
                                            " has no current snapshot: it exists but "
                                            "nothing has ever been committed to it")}
                              (nil? snap)
                              {:ok? false :error :iceberg/dangling-snapshot
                               :detail (str "current-snapshot-id " snap-id
                                            " is not in the snapshot list")}
                              :else
                              (let [ml (get snap "manifest-list")]
                                {:ok? true
                                 ;; See `app-otent.iceberg-id`: the id comes
                                 ;; from the manifest-list NAME, because
                                 ;; JSON.parse cannot hold a 64-bit one.
                                 :snapshot-id (ice-id/snapshot-id ml snap-id)
                                 :snapshot-id-rounded (str snap-id)
                                 :manifest-list ml
                                 :summary (get snap "summary")
                                 :schema (mapv #(get % "name")
                                               (get (last (get m "schemas")) "fields"))
                                 ;; name -> field id. The manifest states
                                 ;; per-file bounds keyed by ID, not by name,
                                 ;; so pruning needs this map and cannot
                                 ;; guess it.
                                 :field-ids (into {} (map (juxt #(get % "name") #(get % "id")))
                                                  (get (last (get m "schemas")) "fields"))}))))))))
      (.catch (fn [e] {:ok? false :error :iceberg/unreachable
                       :detail (str (.-message e))}))))

(defn s3-uri->key
  "`s3://bucket/a/b/c` -> `a/b/c`, so it can be read through the binding.

  Refuses a URI naming a different bucket rather than reading the path out
  of it: that would silently read the right-looking key from the wrong
  place the day a table is registered from elsewhere."
  [uri bucket]
  (let [pre (str "s3://" bucket "/")]
    (if (str/starts-with? uri pre)
      {:ok? true :key (subs uri (count pre))}
      {:ok? false :error :iceberg/foreign-bucket
       :detail (str uri " is not in " bucket)})))

(defn- r2-bytes
  "One object from the R2 binding as a byte VECTOR.

  A vector, not a Uint8Array: `avro.binary` and `parquet.bytes` index with
  `nth`. The copy is real and it is why `api` caches -- doing it per
  request would be the most expensive thing in the Worker."
  [r2 key]
  (-> (.get r2 key)
      (.then (fn [obj]
               (if (nil? obj)
                 {:ok? false :error :iceberg/object-missing
                  :detail (str key " is in the manifest but not in the bucket")}
                 (.then (.arrayBuffer obj)
                        (fn [ab] {:ok? true :bytes (vec (js/Uint8Array. ab))})))))))

(defn- avro-records
  "Avro OCF -> records, with 64-bit values kept exact.

  `:bigint` because an Iceberg manifest carries `snapshot_id` and
  `sequence_number` as full-width longs. Under the default the decoder
  refuses the whole record -- and the field actually wanted here,
  `data_file.file_path`, is a string sitting right beside them."
  [bytes]
  (binding [abin/*long-mode* :bigint]
    (avro/records bytes)))

(defn data-files
  "Steps 3. Manifest-list -> manifests -> the data files of the current
  snapshot.

  Only entries whose `status` is not 2 (DELETED). Iceberg keeps deleted
  entries in the manifest so a reader can reconstruct history; treating
  them as live is how a table appears to grow after rows are removed."
  [r2 bucket manifest-list-uri]
  (let [k (s3-uri->key manifest-list-uri bucket)]
    (if-not (:ok? k)
      (js/Promise.resolve k)
      (-> (r2-bytes r2 (:key k))
          (.then
           (fn [ml]
             (if-not (:ok? ml)
               ml
               (let [entries (avro-records (:bytes ml))
                     paths (keep #(get % "manifest_path") entries)]
                 (-> (js/Promise.all
                      (clj->js
                       (for [p paths]
                         (let [mk (s3-uri->key p bucket)]
                           (if-not (:ok? mk)
                             (js/Promise.resolve mk)
                             (-> (r2-bytes r2 (:key mk))
                                 (.then (fn [m]
                                          (if-not (:ok? m)
                                            m
                                            {:ok? true
                                             :files
                                             (for [e (avro-records (:bytes m))
                                                   :when (not= 2 (get e "status"))
                                                   :let [d (get e "data_file")]]
                                               {:path (get d "file_path")
                                                :records (js/Number (get d "record_count"))
                                                :bytes (js/Number (get d "file_size_in_bytes"))
                                                :format (get d "file_format")
                                                :lower (prune/bounds-map (get d "lower_bounds"))
                                                :upper (prune/bounds-map (get d "upper_bounds"))})})))))))))
                     (.then (fn [rs]
                              (let [rs (js->clj rs)
                                    bad (remove :ok? rs)]
                                (if (seq bad)
                                  (first bad)
                                  {:ok? true :files (vec (mapcat :files rs))})))))))))))))

(defn read-rows
  "Step 4. One Parquet data file -> rows, projected to `columns`."
  [r2 bucket file columns]
  (let [k (s3-uri->key (:path file) bucket)]
    (if-not (:ok? k)
      (js/Promise.resolve k)
      (-> (r2-bytes r2 (:key k))
          (.then (fn [b]
                   (if-not (:ok? b)
                     b
                     (let [src (pq/open (:bytes b))
                           {:keys [rows]} (plan/scan src {:columns columns})]
                       (if (not= (count rows) (:records file))
                         ;; The manifest states the row count. A reader that
                         ;; produces a different number has decoded something
                         ;; else, and the rows it did produce look fine.
                         {:ok? false :error :iceberg/row-count-mismatch
                          :detail (str (:path file) " states " (:records file)
                                       " records; the reader produced " (count rows))}
                         {:ok? true :rows rows})))))))))

(defn scan-table
  "The whole chain, with the counts checked against what the snapshot claims.

  Returns `{:ok? true :rows [...] :snapshot-id ... :files n}`.

  The final count check is the one that matters: `total-records` in the
  snapshot summary is written by whoever committed, and this reader got
  here by an entirely different route. If they disagree, something between
  the manifest and the Parquet decoder is wrong, and rows that look
  perfectly good are missing."
  ([cfg prefix namespace* table columns]
   (scan-table cfg prefix namespace* table columns nil))
  ([{:keys [r2 bucket] :as cfg} prefix namespace* table columns prune]
  (-> (load-table cfg prefix namespace* table)
      (.then (fn [t]
               (if-not (:ok? t)
                 t
                 (-> (data-files r2 bucket (:manifest-list t))
                     (.then (fn [df0]
                              (if-not (:ok? df0)
                                df0
                                (let [p (if prune
                                          (prune/prune-files (:files df0)
                                                       (get (:field-ids t) (:field prune))
                                                       (:window-ms prune))
                                          {:files (:files df0) :pruned 0 :reason :not-requested})
                                      df (assoc df0 :files (:files p))]
                                (-> (js/Promise.all
                                     (clj->js (map #(read-rows r2 bucket % columns) (:files df))))
                                    (.then (fn [rs]
                                             (let [rs (js->clj rs)
                                                   bad (remove :ok? rs)]
                                               (if (seq bad)
                                                 (first bad)
                                                 (let [rows (vec (mapcat :rows rs))
                                                       ;; The files actually READ state their own row
                                                       ;; counts. Checking against those localises a
                                                       ;; mismatch to the decoder; checking against
                                                       ;; the snapshot total only works when nothing
                                                       ;; was pruned, so both are checked and neither
                                                       ;; is dropped when pruning is on.
                                                       expected (reduce + 0 (map :records (:files df)))
                                                       claimed (some-> (get (:summary t) "total-records")
                                                                       js/Number)]
                                                   (cond
                                                     (not= expected (count rows))
                                                     {:ok? false :error :iceberg/record-count-mismatch
                                                      :detail (str "the " (count (:files df))
                                                                   " file(s) read declare " expected
                                                                   " records; the scan produced "
                                                                   (count rows))}

                                                     (and claimed (zero? (:pruned p))
                                                          (not= claimed (count rows)))
                                                     {:ok? false :error :iceberg/total-records-mismatch
                                                      :detail (str "snapshot " (:snapshot-id t)
                                                                   " claims " claimed
                                                                   " records; the unpruned scan produced "
                                                                   (count rows))}

                                                     :else
                                                     {:ok? true
                                                      :rows rows
                                                      :snapshot-id (:snapshot-id t)
                                                      :files (count (:files df))
                                                      :files-total (count (:files df0))
                                                      :files-pruned (:pruned p)
                                                      :prune (dissoc p :files)
                                                      :table-records claimed
                                                      :schema (:schema t)}))))))))))))))))))
