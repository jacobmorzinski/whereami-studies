(ns whereami.fakes
  "Fake platforms for tests: reify implements the Java Platform interface
  inline. Each method calls the function given for it; a method with no
  function given fails the test if it's called."
  (:import (java.util Optional)
           (whereami.platform LocationError LocationException Platform)))

(defn fake [{:keys [location address geocode]}]
  (reify Platform
    (getLocation [_ _timeout]
      (if location (location) (throw (IllegalStateException. "getLocation should not be called"))))
    (reverseGeocode [_ _lat _lon]
      (if address (address) (Optional/empty)))
    (geocode [_ query]
      (if geocode (geocode query) (throw (IllegalStateException. "geocode should not be called"))))))

(defn fails-with
  "A function that throws the Java platform's LocationException."
  [^LocationError error]
  (fn [& _] (throw (LocationException. error))))
