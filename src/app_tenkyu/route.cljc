(ns app-tenkyu.route
  "Views as data, and the fragment that addresses them.

  Pure `.cljc` so addressability is testable without a browser; only the
  listener is behind a reader conditional.

  ## Views are a table, and the nav is generated from it

  A view added to the dispatch and forgotten in the nav is dead code that
  looks live. Generating the nav from the same vector makes that
  impossible rather than merely discouraged.

  ## The fragment, not a path

  This app is served by a Worker that could rewrite paths, but the
  artifact is also correct when opened from a static host or a file, and
  `pushState` to `/about` gives a URL that works until someone reloads it.
  A fragment survives a reload anywhere.

  ## There is no shared router yet

  Two other apps in this workspace each hold a ~60-line file shaped like
  this one; the extraction trigger is the third. This is the third, and
  routing is neither markup nor CSS, so it does not belong in
  `jp-go-dds` -- see the note in the README."
  (:require [clojure.string :as str]))

(def views
  "The single source. `:id` is the fragment, `:label` the nav text."
  [{:id :globe   :label "地球"     :nav? true
    :title "Globe"
    :blurb "Live public signals on a WebGPU globe, from the lake."}
   {:id :objects :label "一覧"     :nav? true
    :title "Objects"
    :blurb "What the lake currently holds, per kind."}
   {:id :sources :label "出所"     :nav? true
    :title "Sources"
    :blurb "Every feed, its licence, and whether it was actually read."}
   {:id :about   :label "この面について" :nav? true
    :title "About"
    :blurb "How this is put together, and what it does not do."}])

(def default-view :globe)
(def by-id (into {} (map (juxt :id identity)) views))

(defn fragment->view
  "`\"#objects\"` -> `:objects`. An unknown or absent fragment is the
  default view, not nil: nil reaches the dispatch and renders nothing,
  which looks like the app failing to start."
  [fragment]
  (let [f (-> (or fragment "") (str/replace #"^#/?" "") str/trim)]
    (or (get by-id (keyword f)) (get by-id default-view))))

(defn view->fragment [id] (str "#" (name id)))

(defn nav-items
  "Generated, never hand-listed."
  [current]
  (for [v views :when (:nav? v)]
    (assoc v :current? (= (:id v) (:id (if (map? current) current (by-id current)))))))

#?(:cljs
   (defn listen!
     "Call `on-change` with the view for the current fragment, now and on
      every `hashchange`. Returns a teardown."
     [on-change]
     (let [handler (fn [_] (on-change (fragment->view (.-hash js/location))))]
       (.addEventListener js/window "hashchange" handler)
       (handler nil)
       (fn [] (.removeEventListener js/window "hashchange" handler)))))
