(ns whereami.turkish-locale-test
  "Turkish lowercases \"I\" to a dotless \"ı\", so text that is lowercased with
  the default locale changes on a Turkish machine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [whereami.app :as app]
            [whereami.fakes :refer [fake fails-with]]
            [whereami.platform :as platform])
  (:import (java.util Locale)
           (whereami.platform LocationError)))

(use-fixtures :each
  (fn [test]
    (let [saved (Locale/getDefault)]
      (Locale/setDefault (Locale/forLanguageTag "tr-TR"))
      (try (test) (finally (Locale/setDefault saved))))))

(deftest locale-is-turkish
  (is (= "ı" (str/lower-case "I")) "the fixture is in effect"))

(deftest error-keys-are-locale-independent
  (is (= [:permission-denied :location-unavailable :timeout :geocoding-failed :platform-unsupported]
         (map platform/error-key (LocationError/values))))
  (is (= ["permission_denied" "location_unavailable" "timeout" "unknown_error" "platform_unsupported"]
         (map (comp platform/json-key platform/error-key) (LocationError/values)))))

(deftest permission-denied-message-on-turkish-machine
  (let [out (app/run {:mode :current :json true}
                     (fake {:location (fails-with LocationError/PERMISSION_DENIED)})
                     1000)]
    (is (= "{\"error\":\"permission_denied\"}\n" (:stdout out)))
    (is (str/starts-with? (:stderr out) "Error: location permission denied."))))
