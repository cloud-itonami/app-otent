(ns app-tenkyu.views
  "The page, in `jp-go-dds` hiccup.

  Pure `.cljc`: every view is a function of a plain map, so the whole UI
  can be rendered and asserted in a test without a browser.

  ## The rules this obeys, and why they show up in the code

  - Only `jp-go-dds` components. No hand-rolled buttons, no raw hex, no
    px font sizes -- every value is a `--hig-*` token that `tokens/bridge-css`
    resolves onto DADS primitives.
  - The nav is **generated** from `app-tenkyu.route/views`, so a view
    cannot exist in the dispatch and be missing from the nav.
  - One document. Crossing a view changes state, never location.

  The one place app CSS exists is the globe canvas, which DADS has no
  component for -- a full-bleed GPU surface is not markup."
  (:require [clojure.string :as str]
            [jp-go-dds.core :as dds]
            [app-tenkyu.route :as route]
            [app-tenkyu.db :as db]))

(def app-css
  "Layout for the one thing DADS does not have: a full-bleed GPU surface.

  Written entirely against the `--hig-*` contract, so it follows the design
  system rather than sitting beside it.

  **Every token here must be one `jp-go-dds.tokens/hig->dads` carries**, and
  `views-test` asserts it. An app on a DADS base has no `shitsuke.hig`
  underneath, so an unbridged token resolves to *nothing* -- not to a
  default. This file shipped `--hig-color-bg-elevated`,
  `--hig-color-fill-tertiary` and `--hig-color-label-secondary`, none of
  which exist; the overlay plaque had no background at all and its text sat
  directly on the imagery, unreadable, while every other rule worked."
  "
.tenkyu-shell { display:flex; flex-direction:column; min-height:100dvh; }
.tenkyu-main { flex:1; display:flex; flex-direction:column; }
.tenkyu-stage--hidden { display:none; }
.tenkyu-stage { position:relative; flex:1; min-height:60dvh;
                border-radius: var(--hig-radius-md);
                overflow:hidden; background: var(--hig-color-tertiary-system-fill); }
.tenkyu-canvas { position:absolute; inset:0; width:100%; height:100%; display:block;
                 touch-action:none; cursor:grab; }
.tenkyu-canvas:active { cursor:grabbing; }
.tenkyu-overlay { position:absolute; inset-block-start: var(--hig-spacing-3);
                  inset-inline-start: var(--hig-spacing-3);
                  display:flex; flex-direction:column; gap: var(--hig-spacing-2);
                  pointer-events:none; max-inline-size: 22rem; }
.tenkyu-overlay > * { pointer-events:auto; }
.tenkyu-plaque { background: var(--hig-color-secondary-system-background);
                 border: var(--hig-hairline) solid var(--hig-color-separator);
                 border-radius: var(--hig-radius-sm);
                 padding: var(--hig-spacing-2) var(--hig-spacing-3);
                 font-size: var(--hig-text-footnote-font-size); }
.tenkyu-legend { display:flex; flex-wrap:wrap; gap: var(--hig-spacing-2); }
.tenkyu-swatch { display:inline-flex; align-items:center; gap: var(--hig-spacing-1); }
.tenkyu-dot { inline-size:.65rem; block-size:.65rem; border-radius:50%; }
.tenkyu-nav { display:flex; gap: var(--hig-spacing-2); flex-wrap:wrap; }
.tenkyu-foot { font-size: var(--hig-text-caption1-font-size);
               color: var(--hig-color-secondary-label); }
")

(defn expand
  "Resolve function-headed hiccup into plain hiccup.

  `[some-view m]` is how reagent is written, and reagent resolves it in
  the browser. `html.core` does not -- it renders tags, and a function in
  head position throws `Doesn't support name`. So the same view tree needs
  one expansion step to be server-rendered, and this is it.

  Having it here rather than in a test is deliberate: the Worker uses it
  to render the first paint. If it lived in the test, the test would be
  checking a tree the server never produces."
  [x]
  (cond
    (and (vector? x) (fn? (first x)))
    (expand (apply (first x) (rest x)))

    (vector? x)
    (with-meta (mapv expand x) (meta x))

    (seq? x) (map expand x)
    :else x))

(defn- swatch [kind rgb]
  [:span {:class "tenkyu-swatch"}
   [:span {:class "tenkyu-dot"
           :style {:background (str "rgb(" (str/join "," (map #(Math/round (* 255 %)) rgb)) ")")}}]
   (name kind)])

(defn nav
  "Generated from the view table. `dds/button` with an `:href` -- a real
  link that is still a design-system control."
  [current]
  [:nav {:class "tenkyu-nav" :aria-label "views"}
   (for [{:keys [id label current?]} (route/nav-items current)]
     ^{:key id}
     ;; (button LABEL opts) -- label first. Passing an opts map as the first
     ;; argument renders a button whose visible text is a map and whose
     ;; attributes are empty, and it does not throw.
     [dds/button label {:href (route/view->fragment id)
                        :type (if current? :solid-fill :text)
                        :size "sm"
                        :attrs (when current? {:aria-current "page"})}])])

(defn status-plaque
  "What is on the globe, how old it is, and what is not on it."
  [{:keys [backend layers unavailable now]}]
  [:div {:class "tenkyu-plaque"}
   [:div (if backend
           (str "renderer: " (name backend))
           "renderer: starting")]
   (for [{:keys [kind status count as-of refused]} layers]
     ^{:key kind}
     [:div (str (name kind) ": "
                (case status
                  :idle "-"
                  :loading "loading"
                  :unavailable "UNMEASURED"
                  (str count
                       (when (pos? refused) (str " (+" refused " refused)"))
                       " · " (db/describe-age (when as-of (- now as-of))))))])
   (when (seq unavailable)
     [:div {:style {:color "var(--hig-color-secondary-label)"}}
      "not read: " (str/join ", " (map (comp name :kind) unavailable))])])

(defn globe-overlay
  "The plaques that sit on top of the globe.

  The canvas itself is NOT here -- see `app`. This is only what is drawn
  over it, and it is mounted only on the globe view."
  [{:keys [backend] :as m}]
  [:div {:class "tenkyu-overlay"}
   [status-plaque m]
   [:div {:class "tenkyu-plaque tenkyu-legend"}
    (for [[k rgb] [[:satellite [1.0 0.85 0.25]] [:quake [1.0 0.35 0.30]]
                   [:aircraft [0.40 0.85 1.0]] [:fire [1.0 0.55 0.15]]
                   [:vessel [0.55 1.0 0.65]]]]
      ^{:key k} [swatch k rgb])]
   (when (= :none backend)
     [dds/notification-banner
      {:type :error :heading "この端末では地球儀を描画できません"}
      "WebGPU も WebGL 2.0 も利用できません。データは下の一覧で読めます。"])])

(defn objects-view
  [{:keys [layers now]}]
  [dds/stack
   [dds/heading 2 "Objects in the lake"]
   [:p "Each row is one Iceberg table in "
    [:code "cloud_itonami"] ". "
    [:strong "UNMEASURED"] " means the feed was never read -- not that it was empty."]
   [dds/table
    {:caption "Layers"
     :headers ["kind" "status" "rows" "refused" "observed" "snapshot"]
     :rows (for [{:keys [kind status count refused as-of snapshot-id detail]} layers]
             [(name kind)
              (if (= :unavailable status)
                (dds/chip-label "UNMEASURED" {:color "red"})
                (name status))
              (if (= :unavailable status) "-" (str count))
              (if (pos? (or refused 0)) (str refused) "-")
              (if as-of (db/describe-age (- now as-of)) "-")
              (or (some-> snapshot-id (subs 0 (min 10 (clojure.core/count snapshot-id))))
                  (or detail "-"))])}]])

(defn sources-view [_]
  [dds/stack
   [dds/heading 2 "Where every mark comes from"]
   [:p "Every layer is a public feed, ingested by "
    [:code "cloud-itonami/tenkyu"] ", governed, and stored in Cloudflare R2 "
    "Data Catalog. The browser reads the lake, never the upstream service."]
   [dds/table
    {:caption "Feeds"
     :headers ["kind" "source" "access" "licence / terms"]
     :rows [["satellite" "CelesTrak GP element sets" "no key"
             "celestrak.org/publications"]
            ["quake" "USGS earthquake summary feed" "no key"
             "USGS -- public domain"]
            ["aircraft" "OpenSky Network state vectors" "no key (anonymous)"
             "opensky-network.org/about/terms-of-use"]
            ["fire" "NASA FIRMS active fire detections" "free key required"
             "firms.modaps.eosdis.nasa.gov/usage"]
            ["vessel" "AISStream vessel positions" "free key + resident collector"
             "aisstream.io/terms"]]}]
   [dds/heading 3 "Basemap"]
   [dds/table
    {:caption "Basemap layers"
     :headers ["layer" "source" "licence"]
     :rows [["raster" "NASA GIBS BlueMarble_ShadedRelief_Bathymetry" "NASA -- public domain"]
            ["vector" "Natural Earth 110m coastline and land borders" "public domain (CC0)"]]}]
   [:p {:class "tenkyu-foot"}
    "Both basemap layers are ingested once into our own bucket and served from it. "
    "Nothing on this page is fetched from a third party at render time."]])

(defn about-view [_]
  [dds/stack
   [dds/heading 2 "How this is built"]
   [:p "A ClojureScript single-page app on "
    [:code "jp-go-dds"] ", drawing a globe with WebGPU where the browser has it "
    "and WebGL 2.0 where it does not. There is no Cesium and no Three.js: the "
    "geometry comes from " [:code "kotoba-lang/geo"] " and both backends consume "
    "the same vertex data."]
   [dds/heading 3 "Satellites are elements, not positions"]
   [:p "The lake stores each satellite's two-line element set, and "
    [:code "kotoba-lang/sgp4"] " evaluates it in your browser at the instant the "
    "frame is drawn. That is why one row per satellite per day is enough. "
    "Deep-space element sets are " [:strong "refused"] " rather than approximated "
    "-- a geostationary satellite run through the near-Earth model returns a "
    "confidently wrong position, and this page would rather show you a count of "
    "refusals than a wrong dot."]
   [dds/heading 3 "What this does not do"]
   [:p "No named-person search, no face recognition, no tracking of individuals. "
    "The ingest actor holds aircraft and vessel identifiers because a transponder "
    "broadcasts them in the clear, and refuses any field whose name marks a "
    "person. Every source is public and every layer says whether it was actually "
    "read."]])

(defn dispatch-view
  "The body BELOW the stage. `:globe` has none -- its content is the canvas,
  which `app` keeps mounted in every view."
  [id m]
  (case id
    :globe nil
    :objects [objects-view m]
    :sources [sources-view m]
    :about [about-view m]
    ;; A view in the table with no branch here would render nothing and look
    ;; like a blank page rather than a mistake.
    [dds/notification-banner {:type "error" :title "No such view"}
     (str "The view " (pr-str id) " is in the route table but has no renderer.")]))

(defn app
  "The whole page. One shell, one nav, one mount -- and **one canvas**,
  mounted in every view.

  The canvas lives here rather than inside the globe view because React
  unmounts a view's subtree when the view changes, and unmounting the
  canvas destroys the GPU device with it. Crossing to `#sources` and back
  therefore meant requesting an adapter, building three pipelines and
  re-uploading every basemap tile -- a visible blink, for a screen the
  reader was on two seconds ago. Hidden with `display:none`, the element
  stays, the context stays, and the tiles stay resident."
  [{:keys [view] :as m}]
  [:div {:class "tenkyu-shell"}
   [dds/container
    [dds/stack
     [dds/heading 1 "天球 tenkyu"]
     [:p {:class "tenkyu-foot"}
      "Live public spatial intelligence, read from Cloudflare R2 Data Catalog."]
     [nav view]
     [:div {:class (str "tenkyu-stage"
                        (when-not (= :globe view) " tenkyu-stage--hidden"))}
      [:canvas {:class "tenkyu-canvas" :id "tenkyu-globe"
                :aria-label "3D globe showing live public signals"}]
      (when (= :globe view) [globe-overlay m])]
     [dispatch-view view m]]]])
