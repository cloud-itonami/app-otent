(ns app-otent.route-test
  (:require [clojure.test :refer [deftest is testing]]
            [app-otent.route :as route]))

(deftest every-view-is-reachable-from-its-own-fragment
  ;; The property that makes "views are data" worth anything.
  (doseq [v route/views]
    (is (= (:id v) (:id (route/fragment->view (route/view->fragment (:id v)))))
        (str (:id v) " does not round-trip through its fragment"))))

(deftest the-nav-is-generated-from-the-view-table
  ;; A view added to the dispatch and forgotten in the nav is dead code
  ;; that looks live. This is the assertion that makes that impossible.
  (let [nav (route/nav-items :globe)]
    (is (= (count (filter :nav? route/views)) (count nav)))
    (is (= (set (map :id (filter :nav? route/views))) (set (map :id nav))))
    (is (= 1 (count (filter :current? nav))) "exactly one item must be current")
    (is (:current? (first (filter #(= :globe (:id %)) nav))))))

(deftest an-unknown-fragment-is-the-default-view-not-nil
  ;; nil reaches the dispatch and renders nothing, which looks like the app
  ;; failing to start rather than like a bad link.
  (doseq [f ["" nil "#" "#/" "#nonsense" "#globe/extra" "  "]]
    (is (some? (route/fragment->view f)) (str "fragment " (pr-str f) " gave nil")))
  (is (= :globe (:id (route/fragment->view "#nonsense"))))
  (is (= :globe (:id (route/fragment->view nil)))))

(deftest fragments-tolerate-the-shapes-browsers-produce
  (is (= :objects (:id (route/fragment->view "#objects"))))
  (is (= :objects (:id (route/fragment->view "objects"))))
  (is (= :objects (:id (route/fragment->view "#/objects")))))

(deftest every-view-has-what-the-page-needs-to-render-it
  (doseq [v route/views]
    (is (keyword? (:id v)))
    (is (string? (:label v)) (str (:id v) " has no nav label"))
    (is (string? (:title v)) (str (:id v) " has no title"))
    (is (string? (:blurb v)) (str (:id v) " has no blurb"))))
