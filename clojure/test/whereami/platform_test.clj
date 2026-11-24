(ns whereami.platform-test
  "The boundary between the Java platform (records, Optional, exceptions) and
  Clojure data (maps, nil, {:error keyword})."
  (:require [clojure.test :refer [deftest is]]
            [whereami.fakes :refer [fake fails-with]]
            [whereami.platform :as platform])
  (:import (java.util Optional)
           (whereami.platform Address GeocodeResult Location LocationError)))

(deftest location-record-to-map
  (is (= {:latitude 1.0 :longitude 2.0 :accuracy 3.0}
         (platform/get-location (fake {:location #(Location. 1.0 2.0 3.0)}) 1000))))

(deftest error-to-keyword
  (is (= {:error :permission-denied}
         (platform/get-location (fake {:location (fails-with LocationError/PERMISSION_DENIED)}) 1000))))

(deftest empty-optional-is-nil
  (is (nil? (platform/reverse-geocode (fake {:address #(Optional/empty)}) 1.0 2.0)))
  (is (= {:street "s" :city "c" :state "" :postal-code "p" :country "CC"}
         (platform/reverse-geocode (fake {:address #(Optional/of (Address. "s" "c" "" "p" "CC"))}) 1.0 2.0))))

(deftest geocode-result-to-map
  (is (= {:location {:latitude 1.0 :longitude 2.0 :accuracy 0.0}
          :address {:street "s" :city "c" :state "" :postal-code "p" :country "CC"}}
         (platform/geocode (fake {:geocode (fn [_] (Optional/of (GeocodeResult. (Location. 1.0 2.0 0.0)
                                                                                (Address. "s" "c" "" "p" "CC"))))})
                           "x")))
  (is (nil? (platform/geocode (fake {:geocode (fn [_] (Optional/empty))}) "x")))
  (is (= {:error :platform-unsupported}
         (platform/geocode (fake {:geocode (fails-with LocationError/PLATFORM_UNSUPPORTED)}) "x"))))

(deftest json-keys
  (is (= ["permission_denied" "location_unavailable" "timeout" "unknown_error" "platform_unsupported"]
         (map (comp platform/json-key platform/error-key)
              [LocationError/PERMISSION_DENIED LocationError/LOCATION_UNAVAILABLE LocationError/TIMEOUT
               LocationError/GEOCODING_FAILED LocationError/PLATFORM_UNSUPPORTED]))))
