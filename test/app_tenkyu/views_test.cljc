(ns app-tenkyu.views-test
  "The views, rendered as data. No browser, no GPU, no DOM."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jp-go-dds.tokens :as tokens]
            [app-tenkyu.views :as views]
            [app-tenkyu.route :as route]
            [app-tenkyu.db :as db]))

(defn- render
  "The view tree as the SERVER produces it -- function components resolved.

  Not walking the raw tree: `dispatch-view` returns `[sources-view m]`,
  a reagent component reference, and walking that finds the model's
  strings rather than the view's. The first version of this file did
  exactly that and reported that Natural Earth was uncredited while the
  page credited it."
  [x] (views/expand x))

(defn- flatten-hiccup [x]
  (cond (vector? x) (mapcat flatten-hiccup x)
        (seq? x) (mapcat flatten-hiccup x)
        (map? x) (mapcat flatten-hiccup (vals x))
        :else [x]))

(defn- text-of [x] (str/join " " (map str (filter string? (flatten-hiccup (render x))))))

(def model
  {:view :globe
   :backend :webgpu
   :now 1787700000000
   :layers [{:kind :satellite :status :loaded :count 21 :refused 3 :as-of 1787699000000}
            {:kind :quake :status :loaded :count 33 :refused 0 :as-of 1787699500000}
            {:kind :vessel :status :unavailable :refused 0 :detail "no resident collector"}]
   :unavailable [{:kind :vessel :detail "no resident collector"}]})

(deftest every-view-in-the-table-renders
  ;; A view listed in the route table with no branch in `dispatch-view`
  ;; renders "No such view", which is at least legible. A view that renders
  ;; NOTHING looks like a crash.
  (doseq [v route/views]
    (let [h (views/app (assoc model :view (:id v)))]
      (is (vector? h) (str (:id v) " did not render"))
      (is (not (str/includes? (text-of h) "has no renderer"))
          (str (:id v) " is in the route table but has no renderer"))
      (is (str/includes? (text-of h) (:label v))
          (str (:id v) " did not render its own nav label")))))

(deftest the-canvas-is-mounted-in-every-view
  ;; React unmounts a view's subtree when the view changes, and unmounting
  ;; the canvas destroys the GPU device with it -- so crossing to another
  ;; view and back meant requesting an adapter, rebuilding three pipelines
  ;; and re-fetching every basemap tile. Keeping one canvas mounted in all
  ;; views is the fix, and this is the assertion that keeps it that way.
  (doseq [v route/views]
    (let [rendered (render (views/app (assoc model :view (:id v))))
          flat (flatten-hiccup rendered)]
      (is (some #(= "tenkyu-globe" %) flat)
          (str "the canvas is missing from the " (:id v) " view"))))
  (testing "and it is HIDDEN, not unmounted, when the globe is not showing"
    (let [t (str (render (views/app (assoc model :view :sources))))]
      (is (str/includes? t "tenkyu-stage--hidden")))
    (let [t (str (render (views/app (assoc model :view :globe))))]
      (is (not (str/includes? t "tenkyu-stage--hidden"))))))

(deftest the-nav-holds-every-nav-view-and-exactly-one-current
  (let [h (views/nav :objects)
        t (text-of h)]
    (doseq [v (filter :nav? route/views)]
      (is (str/includes? t (:label v)) (str (:label v) " is missing from the nav")))))

(deftest unmeasured-is-said-out-loud
  ;; The single most important thing this UI does: a layer that was never
  ;; read must not render as a layer with nothing in it.
  (let [t (text-of (views/app model))]
    (is (str/includes? t "UNMEASURED"))
    (is (str/includes? t "vessel")))
  (let [t (text-of (views/app (assoc model :view :objects)))]
    (is (str/includes? t "UNMEASURED"))))

(deftest the-renderer-in-use-is-shown
  ;; A WebGPU build silently falling back to WebGL for a month is exactly
  ;; the kind of thing nobody notices unless the page says which ran.
  (is (str/includes? (text-of (views/app model)) "webgpu"))
  (is (str/includes? (text-of (views/app (assoc model :backend :webgl2)))
                     "webgl2"))
  (testing "and a browser with neither is TOLD, not left with a blank box"
    (let [t (text-of (views/app (assoc model :backend :none)))]
      (is (or (str/includes? t "WebGPU") (str/includes? t "描画できません"))))))

(deftest ages-are-shown-not-just-counts
  ;; A globe drawing month-old aircraft looks exactly like one drawing live
  ;; ones unless the age is on the page.
  (let [t (text-of (views/app model))]
    (is (re-find #"\d+[smhd] ago" t) (str "no age in: " (subs t 0 300)))))

(deftest refusals-are-counted-in-the-ui
  ;; sgp4 refuses deep-space element sets. Drawing the rest and saying
  ;; nothing would show a partial sky and call it the sky.
  (is (str/includes? (text-of (views/app model)) "+3 refused")))

(deftest no-raw-hex-or-px-in-the-app-stylesheet
  ;; The design-system contract: every value is a --hig-* token.
  (is (not (re-find #"#[0-9a-fA-F]{3,8}\b" views/app-css))
      "a raw hex colour appeared in app CSS")
  (is (not (re-find #":\s*\d+px" views/app-css))
      "a px length appeared in app CSS -- use a --hig-spacing-* token")
  (testing "and it is small: a big app stylesheet means the design system
            is being fought rather than used"
    (is (< (count (str/split-lines views/app-css)) 40))))

(deftest every-token-the-app-uses-is-actually-bridged
  ;; The documented hazard, and one this file did not catch the first time.
  ;;
  ;; An app on a DADS base has no `shitsuke.hig` underneath it, so a
  ;; `--hig-*` token the bridge does not carry resolves to NOTHING -- the
  ;; declaration is dropped and the rule around it still applies. This CSS
  ;; shipped three invented names (`--hig-color-bg-elevated`,
  ;; `--hig-color-fill-tertiary`, `--hig-color-label-secondary`); the
  ;; overlay plaque lost its background entirely and its text sat on the
  ;; globe imagery, unreadable, while the layout it shares a rule with
  ;; worked perfectly.
  (let [used (set (map second (re-seq #"var\((--hig-[a-z0-9-]+)\)" views/app-css)))
        bridged (set (map (fn [[k _]] (name k)) tokens/hig->dads))
        missing (sort (remove bridged used))]
    (is (seq used) "no tokens found at all -- the regex is wrong, not the CSS")
    (is (empty? missing)
        (str "these --hig-* tokens are not in jp-go-dds.tokens/hig->dads and "
             "will resolve to nothing: " (str/join ", " missing)))))

(deftest the-sources-view-names-a-licence-for-every-feed
  (let [t (text-of (views/app (assoc model :view :sources)))]
    (doseq [s ["CelesTrak" "USGS" "OpenSky" "FIRMS" "AISStream"
               "Natural Earth" "GIBS"
               ;; ODbL REQUIRES this attribution. A licence line that can
               ;; be dropped by an unrelated edit is not attribution.
               "OpenStreetMap" "ODbL"]]
      (is (str/includes? t s) (str s " is not credited")))
    (testing "and the building coverage is stated -- four cities is an
              honest picture only if the page says it is four cities"
      (doseq [c ["Tokyo" "Manhattan" "London" "Singapore"]]
        (is (str/includes? t c) (str c " is not listed as covered"))))))

(def ^:private areas
  [{:id "tokyo" :label "Tokyo" :lat 35.6812 :lon 139.7671 :buildings 2199
    :z 14 :x0 14550 :x1 14554 :y0 6449 :y1 6453}
   {:id "manhattan" :label "New York (Manhattan)" :lat 40.758 :lon -73.9855
    :buildings 10129 :z 14 :x0 4822 :x1 4826 :y0 6155 :y1 6159}])

(deftest city-shortcuts-are-generated-from-the-manifest
  ;; A city ingested but missing from this row would be invisible; a city
  ;; listed but never ingested would fly the reader somewhere with nothing
  ;; in it. Both are the bug the nav-from-data rule exists to prevent, and
  ;; the manifest is the only thing that knows which cities were ingested.
  (let [flown (atom nil)
        t (text-of (views/city-shortcuts areas #(reset! flown %)))]
    (doseq [a areas]
      (is (str/includes? t (:label a)) (str (:label a) " is missing")))
    (testing "and the count is shown, so an empty city is visible as empty"
      (is (str/includes? t "2199"))
      (is (str/includes? t "10129")))))

(deftest no-shortcuts-when-nothing-was-ingested
  ;; An empty row of buttons is worse than no row: it says the feature
  ;; exists and does nothing.
  (is (nil? (views/city-shortcuts [] identity)))
  (is (nil? (views/city-shortcuts nil identity))))

(deftest the-fly-to-handler-carries-the-place
  (let [flown (atom nil)
        rendered (render (views/city-shortcuts areas #(reset! flown %)))
        ;; find the first :on-click in the rendered attribute maps
        clicks (->> (tree-seq coll? seq rendered)
                    (filter map?)
                    (keep :on-click))]
    (is (seq clicks) "no click handler reached the button")
    ((first clicks))
    (is (= {:lat 35.6812 :lon 139.7671} @flown))))

(deftest the-globe-overlay-shows-where-the-camera-is
  ;; Without it, "why are there no buildings" has no answer on screen --
  ;; the reader cannot tell they are over the Pacific.
  (let [t (text-of (views/globe-overlay
                    (assoc model :building-areas areas :on-fly identity
                           :camera {:lat-deg 35.681 :lon-deg 139.767 :distance 1.0004})))]
    (is (str/includes? t "35.681"))
    (is (str/includes? t "km up"))))
