(ns whereami.output-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [whereami.output :as output]))

(def loc0 {:latitude 40.7128 :longitude -74.0060 :accuracy 0.0})

(deftest human-with-accuracy
  (is (= "Location: 40.7128, -74.0060\nAccuracy: 0m\n" (output/human loc0 nil true))))

(deftest human-skips-empty-fields
  (is (= "Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n"
         (output/human {:latitude 38.8977 :longitude -77.0366 :accuracy 5.0}
                       {:street "1600 Pennsylvania Ave NW" :city "Washington" :state ""
                        :postal-code "20500" :country "US"}
                       false))))

(deftest json-null-address
  (let [text (output/json (assoc loc0 :longitude -74.006) nil)
        parsed (json/read-str text)]
    (is (= 40.7128 (get parsed "latitude")))
    (is (contains? parsed "address"))
    (is (nil? (get parsed "address")))
    (is (apply < (map #(str/index-of text %) ["\"latitude\"" "\"longitude\"" "\"accuracy\"" "\"address\""])))))

(deftest json-escapes-address
  (let [text (output/json {:latitude 1.0 :longitude 2.0 :accuracy 3.0}
                          {:street "5 \"Q\" St\\" :city "Montréal" :state "" :postal-code "H2X" :country "CA"})
        address (get (json/read-str text) "address")]
    (is (= "5 \"Q\" St\\" (get address "street")))
    (is (= "H2X" (get address "postal_code")))
    (is (str/includes? text "Montréal"))))

(deftest json-error-shape
  (is (= "{\"error\":\"timeout\"}\n" (output/json-error "timeout"))))
