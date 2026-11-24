(ns whereami.locale-test
  "Runs with a locale whose decimal separator is a comma."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [whereami.coords :as coords]
            [whereami.output :as output])
  (:import (java.util Locale)))

(use-fixtures :each
  (fn [test]
    (let [saved (Locale/getDefault)]
      (Locale/setDefault Locale/GERMANY)
      (try (test) (finally (Locale/setDefault saved))))))

(deftest locale-is-german
  (is (= "1,5" (format "%.1f" 1.5)) "the fixture is in effect: plain format uses a comma"))

(deftest human-is-locale-independent
  (is (str/starts-with? (output/human {:latitude 40.7128 :longitude -74.0060 :accuracy 0.0} nil true)
                        "Location: 40.7128, -74.0060")))

(deftest parse-is-locale-independent
  (is (= 40.7128 (:latitude (coords/parse "40.7128,-74.006")))))
