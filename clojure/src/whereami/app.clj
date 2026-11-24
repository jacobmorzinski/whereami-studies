(ns whereami.app
  "What whereami does, given a request and a platform. Data in, data out:
  run returns {:stdout :stderr :exit-code} instead of printing."
  (:require [clojure.spec.alpha :as s]
            [whereami.output :as output]
            [whereami.platform :as platform]
            [whereami.specs :as specs])
  (:import (java.util.concurrent ExecutionException)
           (whereami.platform Platform)))

(set! *warn-on-reflection* true)

(def timeout-ms 10000)

(defn- success [{:keys [json]} location address show-accuracy?]
  {:stdout (if json (output/json location address) (output/human location address show-accuracy?))
   :stderr ""
   :exit-code 0})

(defn- failure [{:keys [json]} key message]
  {:stdout (if json (output/json-error key) "") :stderr message :exit-code 1})

(def ^:private messages
  {:permission-denied (str "Error: location permission denied.\n"
                           "Grant location access in your system's privacy/location settings.\n")
   :location-unavailable "Error: location unavailable.\nMake sure location services are enabled.\n"
   :timeout "Error: location request timed out.\n"
   :platform-unsupported "Error: location is not supported on this platform yet.\n"
   :geocoding-failed "Error: unexpected error (GeocodingFailed)\n"})

(defn- location-failure [request error]
  (failure request (platform/json-key error) (messages error)))

(defn- work
  "All the platform calls for one request. Runs on one thread."
  [{:keys [mode location query] :as request} p ms]
  (case mode
    :current
    (let [loc (platform/get-location p ms)]
      (if-let [e (:error loc)]
        (location-failure request e)
        (let [address (platform/reverse-geocode p (:latitude loc) (:longitude loc))]
          (if-let [e (:error address)]
            (location-failure request e)
            (success request loc address true)))))

    :at
    (let [address (platform/reverse-geocode p (:latitude location) (:longitude location))]
      (cond
        (:error address) (location-failure request (:error address))
        address (success request location address false)
        ;; With --at the address is the whole point, so say so when there isn't one.
        :else (assoc (success request location nil false)
                     :stderr "Warning: no address found for these coordinates.\n")))

    :lookup
    (let [found (platform/geocode p query)]
      (cond
        (= :platform-unsupported (:error found))
        (failure request "platform_unsupported" "Error: address lookup is not supported on this platform.\n")
        (:error found) (location-failure request (:error found))
        found (success request (:location found) (:address found) false)
        :else (failure request "address_not_found"
                       (str "Error: couldn't find a location for \"" query "\".\n"
                            "Check the address and your network connection.\n"))))))

(defn run
  "Runs all of a request's platform calls in one future and waits at most
  timeout-ms. A future runs on a pooled platform thread (not a virtual one),
  which is what COM on Windows needs: one OS thread from start to finish."
  [{:keys [mode location] :as request} p ms]
  (if (= mode :mock)
    (success request location nil true)
    (let [f (future (work request p ms))
          result (try
                   (deref f ms ::timeout)
                   (catch ExecutionException e
                     ;; deref wraps whatever the future threw; rethrow the real exception.
                     (throw (.getCause e))))]
      (if (= result ::timeout)
        (do (future-cancel f)
            (location-failure request :timeout))
        result))))

;; The contract for run. stest/instrument (in the tests) checks every call's
;; arguments against :args.
(s/fdef run
  :args (s/cat :request ::specs/request
               :platform #(instance? Platform %)
               :timeout-ms pos-int?)
  :ret ::specs/outcome)
