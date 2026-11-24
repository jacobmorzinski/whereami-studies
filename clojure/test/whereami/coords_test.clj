(ns whereami.coords-test
  (:require [clojure.test :refer [deftest are is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [whereami.coords :as coords]))

(deftest parses
  (are [text lat lon] (= {:latitude lat :longitude lon :accuracy 0.0} (coords/parse text))
    "40.7128,-74.0060" 40.7128 -74.006
    " 51.5 , -0.1276" 51.5 -0.1276
    "-33.8688,151.2093" -33.8688 151.2093
    "1e1,+2" 10.0 2.0))

;; Double/parseDouble would accept most of these.
(deftest rejects-non-decimal
  (doseq [text ["40.7128" "abc,def" "1,2,3" "nan,0" "NaN,0" "0,inf" "Infinity,0"
                "0x1p3,0" "1d,2" "1f,2" "1e999,0" "\t1,2" "," "" "1,"]]
    (is (nil? (coords/parse text)) (pr-str text))))

;; A generative test: test.check makes up 200 pairs of finite doubles, and
;; each must survive being printed with str and parsed back.
(def finite (gen/double* {:infinite? false :NaN? false}))

(defspec round-trips 200
  (prop/for-all [lat finite
                 lon finite]
                (= {:latitude lat :longitude lon :accuracy 0.0}
                   (coords/parse (str lat "," lon)))))
