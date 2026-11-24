(ns whereami.specs-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [whereami.specs :as specs]))

(defn- loc [lat lon] {:latitude lat :longitude lon :accuracy 0.0})

(deftest at-location-range
  (is (s/valid? ::specs/at-location (loc 90.0 -180.0)))
  (is (not (s/valid? ::specs/at-location (loc 91.0 0.0))))
  (is (not (s/valid? ::specs/at-location (loc 0.0 -180.5)))))

(deftest coords-conformer
  (is (= (loc 1.0 2.0) (s/conform ::specs/coords "1,2")))
  (is (s/invalid? (s/conform ::specs/coords "x"))))

(deftest request-multi-spec
  (is (s/valid? ::specs/request {:mode :current :json false}))
  (is (s/valid? ::specs/request {:mode :mock :location (loc 200.0 400.0) :json false}))
  (is (s/valid? ::specs/request {:mode :lookup :query "Paris" :json true}))
  (is (not (s/valid? ::specs/request {:mode :at :location (loc 91.0 0.0) :json false})))
  (is (not (s/valid? ::specs/request {:mode :lookup :query "  " :json false})))
  (is (not (s/valid? ::specs/request {:mode :teleport :json false}))))
