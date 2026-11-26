(ns whereami.platform
  "The boundary to the Java port's OS layer (java/platform). Java hands back
  records, Optionals and checked exceptions; this namespace turns them into
  Clojure data: maps, nil, and {:error keyword}."
  (:import (java.time Duration)
           (java.util Optional)
           (whereami.platform Address GeocodeResult Location LocationError LocationException
                              Platform Platforms)))

;; Interop on a value of unknown type is resolved by reflection at run time.
;; With this on, the compiler warns about each such call; the type hints
;; (^Platform, ^Location, …) below remove them all.
(set! *warn-on-reflection* true)

(defn native
  "The Java platform for the OS we're running on (WindowsPlatform on Windows, LinuxPlatform on Linux)."
  ^Platform []
  (Platforms/nativePlatform))

;; Spelled out rather than derived from the enum names: lowercasing with
;; str/lower-case uses the machine's locale, and on a Turkish machine "I"
;; becomes a dotless "ı" (:t?meout), which broke the error messages and JSON.
(def ^:private error-keys
  {LocationError/PERMISSION_DENIED :permission-denied
   LocationError/LOCATION_UNAVAILABLE :location-unavailable
   LocationError/TIMEOUT :timeout
   LocationError/GEOCODING_FAILED :geocoding-failed
   LocationError/PLATFORM_UNSUPPORTED :platform-unsupported})

;; The --json keys come from the Java enum's own jsonKey(), so there is one
;; source of truth for them.
(def ^:private json-keys
  (into {} (for [[^LocationError e kw] error-keys] [kw (.jsonKey e)])))

(defn error-key
  "LocationError/PERMISSION_DENIED → :permission-denied."
  [^LocationError e]
  (error-keys e))

(defn json-key
  "The --json error key for an error keyword: :permission-denied → \"permission_denied\"."
  [kw]
  (json-keys kw))

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
