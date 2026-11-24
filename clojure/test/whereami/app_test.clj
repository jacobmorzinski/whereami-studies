(ns whereami.app-test
  (:require [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [whereami.app :as app]
            [whereami.fakes :refer [fake fails-with]])
  (:import (java.util Optional)
           (whereami.platform Address GeocodeResult Location LocationError UnsupportedPlatform)))

;; While these tests run, every call to app/run is checked against its s/fdef.
(use-fixtures :once
  (fn [tests]
    (stest/instrument `app/run)
    (try (tests) (finally (stest/unstrument `app/run)))))

(def ^:private white-house (Address. "1600 Pennsylvania Ave NW" "Washington" "DC" "20500" "US"))

(defn- run
  ([request platform] (app/run request platform 5000))
  ([request platform timeout-ms] (app/run request platform timeout-ms)))

(deftest mock-skips-platform
  (is (= {:stdout "Location: 40.7128, -74.0060\nAccuracy: 0m\n" :stderr "" :exit-code 0}
         (run {:mode :mock :location {:latitude 40.7128 :longitude -74.006 :accuracy 0.0} :json false}
              (fake {})))))

(deftest current-with-address
  (is (= {:stdout (str "Location: 1.0000, 2.0000\nAccuracy: 3m\n"
                       "Address: 1600 Pennsylvania Ave NW, Washington, DC, 20500, US\n")
          :stderr "" :exit-code 0}
         (run {:mode :current :json false}
              (fake {:location #(Location. 1.0 2.0 3.0) :address #(Optional/of white-house)})))))

(deftest at-without-address-warns
  (is (= {:stdout "Location: 90.0000, -180.0000\n"
          :stderr "Warning: no address found for these coordinates.\n"
          :exit-code 0}
         (run {:mode :at :location {:latitude 90.0 :longitude -180.0 :accuracy 0.0} :json false} (fake {})))))

(deftest permission-denied-json
  (let [out (run {:mode :current :json true} (fake {:location (fails-with LocationError/PERMISSION_DENIED)}))]
    (is (= "{\"error\":\"permission_denied\"}\n" (:stdout out)))
    (is (str/starts-with? (:stderr out) "Error: location permission denied."))
    (is (= 1 (:exit-code out)))))

(deftest unsupported-location
  (is (= {:stdout "" :stderr "Error: location is not supported on this platform yet.\n" :exit-code 1}
         (run {:mode :current :json false} (UnsupportedPlatform.)))))

(deftest lookup-unsupported
  (is (= {:stdout "{\"error\":\"platform_unsupported\"}\n"
          :stderr "Error: address lookup is not supported on this platform.\n"
          :exit-code 1}
         (run {:mode :lookup :query "Paris" :json true} (UnsupportedPlatform.)))))

(deftest lookup-not-found
  (let [out (run {:mode :lookup :query "Paris" :json true} (fake {:geocode (fn [_] (Optional/empty))}))]
    (is (= "{\"error\":\"address_not_found\"}\n" (:stdout out)))
    (is (str/includes? (:stderr out) "couldn't find a location for \"Paris\""))
    (is (= 1 (:exit-code out)))))

(deftest lookup-found-omits-accuracy
  (let [found (GeocodeResult. (Location. 38.8977 -77.0366 0.0) white-house)
        out (run {:mode :lookup :query "1600 Pennsylvania Ave" :json false}
                 (fake {:geocode (fn [_] (Optional/of found))}))]
    (is (= 0 (:exit-code out)))
    (is (not (str/includes? (:stdout out) "Accuracy:")))
    (is (str/includes? (:stdout out) "Address: 1600 Pennsylvania Ave NW"))))

(deftest timeout-holds
  ;; A badly behaved OS call: ignores interrupts. It gives up by itself after
  ;; 3 s only so the test JVM can exit afterwards.
  (let [stubborn #(let [stop (+ (System/currentTimeMillis) 3000)]
                    (while (< (System/currentTimeMillis) stop)
                      (try (Thread/sleep 50) (catch InterruptedException _)))
                    (Location. 0.0 0.0 0.0))
        start (System/nanoTime)
        out (run {:mode :current :json true} (fake {:location stubborn}) 200)]
    (is (= {:stdout "{\"error\":\"timeout\"}\n" :stderr "Error: location request timed out.\n" :exit-code 1} out))
    (is (< (- (System/nanoTime) start) 2e9))))

(deftest platform-runs-on-one-platform-thread
  (let [seen (atom [])
        record! #(swap! seen conj (Thread/currentThread))]
    (run {:mode :current :json false}
         (fake {:location #(do (record!) (Location. 1.0 2.0 3.0))
                :address #(do (record!) (Optional/empty))}))
    (let [[^Thread a ^Thread b] @seen]
      (is (= 2 (count @seen)))
      (is (identical? a b) "the whole request runs on one thread")
      (is (not (.isVirtual a)) "a platform thread, as COM needs")
      (is (not (identical? a (Thread/currentThread)))))))

(deftest unexpected-exception-propagates
  (is (thrown? IllegalStateException
               (run {:mode :current :json false} (fake {:location #(throw (IllegalStateException. "bug"))})))))

(deftest instrument-catches-bad-request
  (is (thrown? clojure.lang.ExceptionInfo
               (run {:mode :at :location {:latitude 91.0 :longitude 0.0 :accuracy 0.0} :json false} (fake {})))))
