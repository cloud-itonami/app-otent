(ns app-otent.api
  "The read API the globe talks to, and the cache that makes it affordable.

  ## Cached on the snapshot id, which makes staleness impossible

  Decoding 7,000 aircraft out of Parquet costs real CPU, and the ingest
  actor commits at most once a minute. So the response is cached -- keyed
  on the Iceberg **snapshot id**, which changes exactly when and only when
  the table does.

  That is the whole trick: a time-based TTL trades staleness against cost
  and is wrong in one direction or the other at every moment. A snapshot
  id is a content address for the table's state. A hit is provably the
  current data; a miss happens exactly once per commit.

  Getting the snapshot id still costs one small catalog request, and that
  is the floor: this reads the table's identity every time and its
  contents only when they change.

  ## Every response says what it is and when it was measured

  `:snapshot-id`, `:rows`, `:files` and `:as-of` travel with the payload.
  A globe drawing month-old aircraft looks exactly like a globe drawing
  live ones, so the age has to be in the data, not in the operator's head."
  (:require [app-otent.iceberg :as ice]
            [app-otent.objects :as obj]
            [clojure.string :as str]))

(def kinds
  {"satellite" {:table "otent_satellite"
                :columns ["object_id" "observed_at" "attrs_json"]}
   "quake"     {:table "otent_quake"
                :columns ["object_id" "observed_at" "lat" "lon" "alt_km" "attrs_json"]}
   "aircraft"  {:table "otent_aircraft"
                :columns ["object_id" "observed_at" "lat" "lon" "alt_km" "attrs_json"]}
   "fire"      {:table "otent_fire"
                :columns ["object_id" "observed_at" "lat" "lon" "attrs_json"]}
   "vessel"    {:table "otent_vessel"
                :columns ["object_id" "observed_at" "lat" "lon" "attrs_json"]}})

;; `env` is read with `aget`, never `(.-FOO env)`.
;;
;; Under `:advanced` the Closure compiler renames property accesses it does
;; not have an extern for, and a Worker's `env` is a plain object whose keys
;; come from wrangler.jsonc. A renamed access reads `undefined` -- so the
;; binding is silently absent rather than missing loudly, and the first
;; symptom is a 502 with no cause. Same convention as
;; `cloud-itonami/air-book` and `listingops.edge.worker`.

(defn- num* [s]
  (when-not (str/blank? (str s))
    (let [v (js/Number s)] (when-not (js/isNaN v) v))))

(defn- attrs [s]
  (try (js->clj (js/JSON.parse (or s "{}")))
       (catch :default _ {})))

(defn row->object
  "One Parquet row -> the shape the globe consumes.

  Everything in the table is stored as text (see `otent.observation` on
  why); the cast happens here, once, at the surface that decided what the
  numbers are for."
  [r]
  (let [a (attrs (get r "attrs_json"))]
    (cond-> {:id (get r "object_id")
             :t (num* (get r "observed_at"))
             :attrs a}
      (num* (get r "lat")) (assoc :lat (num* (get r "lat")))
      (num* (get r "lon")) (assoc :lon (num* (get r "lon")))
      (num* (get r "alt_km")) (assoc :alt (num* (get r "alt_km")))
      ;; A satellite row carries elements, not a fix. The two lines travel
      ;; to the browser and `sgp4` evaluates them there, at the instant the
      ;; frame is drawn -- which is the only reason the table can hold one
      ;; row per satellite per day instead of one per satellite per second.
      (get a "line1") (assoc :line1 (get a "line1") :line2 (get a "line2")
                             :name (get a "name")))))

(def body-version
  "Bumped whenever the SHAPE of a response body changes.

  The cache key is the snapshot id, and the reasoning for that is sound as
  far as it goes: a snapshot id is a content address for the table's state,
  so a hit is provably the current data. What it misses is that the body is
  a function of the snapshot AND of the code that renders it. On 2026-08-26
  the fold that collapses an append-only table to one row per object landed,
  was deployed, and changed nothing a reader could see -- the table had not
  committed since, so every request was served the pre-fold body out of
  cache, 25,219 rows of it, from a Worker whose code no longer produced
  that.

  A stale deploy is the failure mode the snapshot key was chosen to
  eliminate, arriving through the other door. This is the version of the
  renderer; the snapshot is the version of the data; a body is identified
  by both."
  "v2")

(defn- cache-key [kind snapshot-id]
  (str "https://app-otent.internal/" body-version "/" kind "/" snapshot-id))

(defn objects
  "`GET /api/objects/:kind`.

  Returns a Response. Refusals are JSON with a status, never an empty
  array -- a globe with no aircraft on it and a globe whose aircraft
  request failed must not look the same."
  [env kind ctx]
  (let [spec (get kinds kind)]
    (if-not spec
      (js/Promise.resolve
       (js/Response. (js/JSON.stringify #js {:error "unknown-kind"
                                             :kinds (clj->js (vec (keys kinds)))})
                     #js {:status 404
                          :headers #js {"content-type" "application/json"}}))
      (let [cfg {:catalog-uri (aget env "CATALOG_URI")
                 :warehouse (aget env "WAREHOUSE")
                 :token (aget env "CF_CATALOG_TOKEN")
                 :bucket (aget env "BUCKET_NAME")
                 :r2 (aget env "DATALAKE")}]
        (-> (ice/catalog-prefix cfg)
            (.then
             (fn [p]
               (if-not (:ok? p)
                 (js/Response. (js/JSON.stringify (clj->js (dissoc p :ok?)))
                               #js {:status 502
                                    :headers #js {"content-type" "application/json"}})
                 (-> (ice/load-table cfg (:prefix p) (aget env "NAMESPACE") (:table spec))
                     (.then
                      (fn [t]
                        (if-not (:ok? t)
                          (js/Response.
                           (js/JSON.stringify (clj->js (assoc (dissoc t :ok?) :kind kind)))
                           ;; A table that does not exist is a 404 about THAT
                           ;; kind, not a server error: `otent` reports fires
                           ;; and vessels as unmeasured, and this is the same
                           ;; fact arriving at the browser.
                           #js {:status (if (= :iceberg/catalog-error (:error t)) 404 502)
                                :headers #js {"content-type" "application/json"}})
                          (let [ck (cache-key kind (:snapshot-id t))
                                cache (.-default js/caches)]
                            (-> (.match cache ck)
                                (.then
                                 (fn [hit]
                                   (if hit
                                     ;; The snapshot has not moved: this IS
                                     ;; the current data, not a stale copy.
                                     (let [r (js/Response. (.-body hit) hit)]
                                       (.set (.-headers r) "x-otent-cache" "hit")
                                       r)
                                     (-> (ice/scan-table cfg (:prefix p) (aget env "NAMESPACE")
                                                         (:table spec) (:columns spec))
                                         (.then
                                          (fn [s]
                                            (if-not (:ok? s)
                                              (js/Response.
                                               (js/JSON.stringify (clj->js (dissoc s :ok?)))
                                               #js {:status 502
                                                    :headers #js {"content-type" "application/json"}})
                                              ;; The table is append-only, so a
                                              ;; scan holds every past position of
                                              ;; every object. Returning them all
                                              ;; drew the same aircraft several
                                              ;; times, at fixes up to a day and a
                                              ;; half old, indistinguishable from
                                              ;; live ones. The fold is pure and
                                              ;; measured from the data, not the
                                              ;; clock, so this body stays a
                                              ;; function of the snapshot id the
                                              ;; cache key promises.
                                              (let [folded (obj/fold kind (mapv row->object (:rows s)))
                                                    body (js/JSON.stringify
                                                          (clj->js
                                                           {:kind kind
                                                            :snapshot-id (:snapshot-id s)
                                                            :files (:files s)
                                                            :count (count (:objects folded))
                                                            ;; What was read, and what was
                                                            ;; dropped for which of the two
                                                            ;; reasons. Without this a reader
                                                            ;; cannot tell `no aircraft` from
                                                            ;; `no aircraft recently`.
                                                            :scan (:stats folded)
                                                            :as-of (js/Date.now)
                                                            :objects (:objects folded)}))
                                                    resp (js/Response.
                                                          body
                                                          #js {:headers
                                                               #js {"content-type" "application/json"
                                                                    ;; Immutable: the URL contains
                                                                    ;; the snapshot id, so this body
                                                                    ;; can never change.
                                                                    "cache-control" "public, max-age=31536000, immutable"
                                                                    "x-otent-cache" "miss"
                                                                    "x-otent-snapshot" (:snapshot-id s)}})]
                                                (.waitUntil ctx (.put cache ck (.clone resp)))
                                                resp))))))))))))))))))))))

(defn basemap-manifest
  "`GET /api/basemap` -- what the ingest actually put in R2.

  Read from the bucket rather than declared here. The renderer clamps its
  zoom to `max-zoom`, and a constant in the app that disagreed with the
  bucket would produce a globe full of holes at exactly one zoom level."
  [env]
  (-> (.get (aget env "DATALAKE") "otent/basemap/manifest.json")
      (.then (fn [o]
               (if (nil? o)
                 (js/Response. (js/JSON.stringify
                                #js {:error "no-basemap-manifest"
                                     :detail (str "otent/basemap/manifest.json is not in the "
                                                  "bucket -- run `basemap.cljs vector` to write it")})
                               #js {:status 503
                                    :headers #js {"content-type" "application/json"}})
                 (js/Response. (.-body o)
                               #js {:headers #js {"content-type" "application/json"
                                                  "cache-control" "public, max-age=300"}}))))))

(defn buildings-manifest
  "`GET /api/buildings` -- which metro areas actually have footprints.

  Read from the bucket, like the basemap manifest, and for the same
  reason: the renderer asks for building tiles only inside these blocks,
  so a constant here that disagreed with the bucket would either hide a
  city or 404 over the rest of the planet."
  [env]
  (-> (.get (aget env "DATALAKE") "otent/basemap/buildings/manifest.json")
      (.then (fn [o]
               (if (nil? o)
                 ;; 200 with an empty area list, NOT a 503: no buildings
                 ;; ingested is a real and legible state, and the app draws
                 ;; a globe without them rather than reporting a fault.
                 (js/Response. (js/JSON.stringify #js {:version 1 :areas #js []
                                                       :detail "no buildings ingested"})
                               #js {:headers #js {"content-type" "application/json"
                                                  "cache-control" "public, max-age=300"}})
                 (js/Response. (.-body o)
                               #js {:headers #js {"content-type" "application/json"
                                                  "cache-control" "public, max-age=300"}}))))))

(defn basemap-object
  "`GET /api/basemap/*` -- a raster tile or a vector layer, straight from R2.

  The Worker is the only thing holding the bucket, so this is also the
  only way the browser can see it: there is no public bucket URL and no
  signed link handed out."
  [env rest-path]
  (let [key (str "otent/basemap/" rest-path)]
    (-> (.get (aget env "DATALAKE") key)
        (.then (fn [o]
                 (if (nil? o)
                   ;; 404 with the key, not an empty 200. A missing tile
                   ;; renders as a hole either way; only one of them says
                   ;; which tile to go and ingest.
                   (js/Response. (js/JSON.stringify #js {:error "not-ingested" :key key})
                                 #js {:status 404
                                      :headers #js {"content-type" "application/json"}})
                   (js/Response. (.-body o)
                                 #js {:headers
                                      #js {"content-type"
                                           (cond (str/ends-with? key ".jpg") "image/jpeg"
                                                 (str/ends-with? key ".json") "application/json"
                                                 :else "application/octet-stream")
                                           ;; Basemap objects are rewritten only
                                           ;; by a deliberate re-ingest.
                                           "cache-control" "public, max-age=86400"}})))))))
