(ns app-otent.worker
  "The Cloudflare Worker: one document, one bundle, and the read API.

  The **only** namespace that touches a Request or a Response. Routing is
  `app-otent.route` (shared with the browser), the page is
  `app-otent.views` (shared with the browser), and the lake is
  `app-otent.iceberg`.

  ## The page is server-rendered, and it is the same page

  `views/app` runs here and in the browser. Not a hand-written HTML shell
  beside a ClojureScript app -- that is the arrangement that let one of
  this workspace's other appviews serve a landing page reporting
  `Routes 0` next to a config declaring two routes, for as long as nobody
  looked. There is one page function; the server renders it with the state
  it can know, the browser re-renders it with the state it fetches.

  ## The bucket is not public

  Every basemap byte and every Iceberg byte is read through this Worker's
  R2 binding. There is no public bucket URL, no signed link handed to the
  browser, and the catalog token never leaves the Worker."
  (:require [clojure.string :as str]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [app-otent.api :as api]
            [app-otent.route :as route]
            [app-otent.views :as views]
            [app-otent.db :as db]
            [shadow.resource :as rc]))

(def ^:private dds-css
  "DADS's CSS is baked into the bundle at build time. Zero external
  requests is the design system's rule, and a Worker has no path to read a
  classpath resource at runtime -- the same arrangement
  `cloud-itonami/air-book` uses."
  (rc/inline "jp_go_dds/dds.css"))

(def ^:private icon-data-uri
  (str "data:image/svg+xml,"
       (js/encodeURIComponent
        (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
             "<circle cx='16' cy='16' r='13' fill='#0b1220' stroke='#4da3ff' "
             "stroke-width='2'/></svg>"))))

(defn- shell-model
  "What the SERVER can honestly say about the page.

  Every layer is `:idle`, because the server has not fetched them -- and
  saying `:idle` is the point. Rendering counts here would mean either
  fetching five Iceberg tables to serve one HTML document, or printing
  numbers the server made up."
  [view]
  {:view view
   :backend nil
   :now 0
   :layers (for [k db/kinds] {:kind k :status :idle :count 0 :refused 0})
   :unavailable []})

(defn document
  "The full HTML document for a view.

  Rendered per request so the fragment's view is already in the markup: a
  reader landing on `#sources` sees the sources page in the first paint,
  not the globe followed by a flicker."
  [view]
  (page/->page
   {:title "\u304a\u5929\u9053\u69d8 otent \u2014 live public spatial intelligence"
    :description (str "Satellites, earthquakes, aircraft, fires and vessels "
                      "on a WebGPU globe, read from Cloudflare R2 Data Catalog.")
    :lang "ja"
    :css dds-css
    ;; The --hig-* contract on top of DADS, then this app's own layout.
    ;; Order matters: app CSS is unlayered and therefore wins.
    ;; `bridge-css` is a def, not a function -- a constant string of the
    ;; whole contract redefined on DADS primitives.
    :app-css (str tokens/bridge-css "\n" views/app-css)
    :dark? true
    :head [[:link {:rel "icon" :href icon-data-uri}]]}
   [:div {:id "app"} (views/expand [views/app (shell-model view)])]
   [:script {:src "/app.js" :type "module" :defer true}]))

(defn- html-response [body]
  (js/Response. body
                #js {:headers #js {"content-type" "text/html; charset=utf-8"
                                   ;; The document is cheap and changes with
                                   ;; every deploy; the bundle it names is
                                   ;; hashed, so a short TTL here is safe.
                                   "cache-control" "public, max-age=60"}}))

(defn handle [request env ctx]
  (let [url (js/URL. (.-url request))
        path (.-pathname url)]
    (cond
      (= "/api/basemap" path)
      (api/basemap-manifest env)

      (= "/api/buildings" path)
      (api/buildings-manifest env)

      (str/starts-with? path "/api/basemap/")
      (api/basemap-object env (subs path (count "/api/basemap/")))

      (str/starts-with? path "/api/objects/")
      (api/objects env (subs path (count "/api/objects/")) ctx)

      (= "/api/health" path)
      (js/Response. (js/JSON.stringify
                     #js {:ok true
                          :bucket (aget env "BUCKET_NAME")
                          :namespace (aget env "NAMESPACE")
                          ;; Whether the token is PRESENT, never what it is.
                          ;; A health check that cannot say why it is failing
                          ;; is a health check nobody can act on.
                          :catalog-token (if (str/blank? (str (aget env "CF_CATALOG_TOKEN")))
                                           "absent" "present")
                          :kinds (clj->js (vec (keys api/kinds)))})
                    #js {:headers #js {"content-type" "application/json"}})

      ;; Anything else is a view. Not a 404: this is a single-page app and
      ;; every path renders the page, with the fragment choosing the view
      ;; client-side. The server picks the default for the first paint.
      :else
      (js/Promise.resolve
       (html-response (document (:id (route/fragment->view (.-hash url)))))))))

(def handler
  #js {:fetch (fn [request env ctx] (handle request env ctx))})
