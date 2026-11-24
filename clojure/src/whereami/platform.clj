(ns whereami.platform
  "The boundary to the Java port's OS layer (java/platform). Java hands back
  records, Optionals and checked exceptions; this namespace turns them into
  Clojure data: maps, nil, and {:error keyword}."
  (:require [clojure.string :as str])
  (:import (java.time Duration)
           (java.util Optional)
           (whereami.platform Address GeocodeResult Location LocationError LocationException
                              Platform Platforms)))

;; Interop on a value of unknown type is resolved by reflection at run time.
;; With this on, the compiler warns about each such call; the type hints
;; (^Platform, ^Location, …) below remove them all.
(set! *warn-on-reflection* true)

(defn native
  "The Java platform for the OS we're running on (WindowsPlatform on Windows)."
  ^Platform []
  (Platforms/nativePlatform))

(defn error-key
  "LocationError/PERMISSION_DENIED → :permission-denied."
  [^LocationError e]
  (keyword (str/lower-case (str/replace (.name e) "_" "-"))))

(defn json-key
  "The --json error key for an error keyword."
  [kw]
  (if (= kw :geocoding-failed) "unknown_error" (str/replace (name kw) "-" "_")))

(defn- location->map [^Location l]
  {:latitude (.latitude l) :longitude (.longitude l) :accuracy (.accuracy l)})

(defn- address->map [^Address a]
  {:street (.street a) :city (.city a) :state (.state a) :postal-code (.postalCode a) :country (.country a)})

(defn- present
  "Optional → the value or nil."
  [^Optional o]
  (.orElse o nil))

(defn- catching
  "Calls f; a LocationException becomes {:error keyword}."
  [f]
  (try
    (f)
    (catch LocationException e
      {:error (error-key (.error e))})))

(defn get-location [^Platform p timeout-ms]
  (catching #(location->map (.getLocation p (Duration/ofMillis timeout-ms)))))

(defn reverse-geocode
  "An address map, nil if there's none, or {:error keyword}."
  [^Platform p lat lon]
  (catching #(some-> (present (.reverseGeocode p (double lat) (double lon))) address->map)))

(defn geocode
  "{:location … :address …}, nil if nothing matches, or {:error keyword}."
  [^Platform p ^String query]
  (catching #(when-let [^GeocodeResult found (present (.geocode p query))]
               {:location (location->map (.location found))
                :address (address->map (.address found))})))
