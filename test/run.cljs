(ns run
  (:require [clojure.test :as t]
            [app-tenkyu.scene-test]
            [app-tenkyu.route-test]
            [app-tenkyu.propagate-test]
            [app-tenkyu.db-test]
            [app-tenkyu.views-test]
            [app-tenkyu.iceberg-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (println)
  (println (str "Ran " (:test m) " tests, " (:pass m) " assertions passed, "
                (:fail m) " failed, " (:error m) " errored."))
  (cond
    (< (:test m) 10)
    (do (println "REFUSING to report a pass:" (:test m) "tests ran.")
        (set! (.-exitCode js/process) 3))
    (t/successful? m) (println "OK")
    :else (set! (.-exitCode js/process) 1)))

(t/run-tests 'app-tenkyu.scene-test 'app-tenkyu.route-test 'app-tenkyu.propagate-test 'app-tenkyu.db-test 'app-tenkyu.views-test 'app-tenkyu.iceberg-test)
