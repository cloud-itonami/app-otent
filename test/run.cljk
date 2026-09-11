(ns run
  (:require [clojure.test :as t]
            [app-otent.scene-test]
            [app-otent.route-test]
            [app-otent.propagate-test]
            [app-otent.db-test]
            [app-otent.views-test]
            [app-otent.iceberg-test]
            [app-otent.objects-test]
            [app-otent.prune-test]))

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

(t/run-tests 'app-otent.scene-test 'app-otent.route-test 'app-otent.propagate-test 'app-otent.db-test 'app-otent.views-test 'app-otent.iceberg-test 'app-otent.objects-test 'app-otent.prune-test)
