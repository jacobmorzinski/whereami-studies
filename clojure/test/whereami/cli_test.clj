(ns whereami.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [whereami.cli :as cli])
  (:import (whereami.platform Platforms)))

(defn- request [& args]
  (let [result (cli/parse-args args)]
    (is (contains? result :request) (pr-str args result))
    (:request result)))

(defn- usage-error? [& args]
  (let [result (cli/parse-args args)]
    (and (= 2 (:exit result)) (nil? (:out result)) (string? (:err result)))))

(defn- loc [lat lon] {:latitude lat :longitude lon :accuracy 0.0})

(deftest version-both-spellings
  (doseq [flag ["--version" "-v"]]
    (is (= {:exit 0 :out (str "whereami 0.4.0 (" (Platforms/osName) ")\n")}
           (cli/parse-args [flag])))))

(deftest help-both-spellings
  (doseq [flag ["-h" "--help"]]
    (let [result (cli/parse-args [flag])]
      (is (= 0 (:exit result)))
      (is (str/includes? (:out result) "--mock")))))

(deftest unknown-flag
  (is (usage-error? "--bogus"))
  (is (usage-error? "--" "-x")))

(deftest at-sign-is-address
  (is (= {:mode :lookup :query "@home" :json false} (request "@home"))))

(deftest words-around-a-flag-are-kept
  (is (= {:mode :lookup :query "350 5th Ave" :json true} (request "350" "5th" "--json" "Ave")))
  (is (usage-error? "--bogus" "--json" "Paris")))

(deftest mock-negative-equals-form
  (is (= {:mode :mock :location (loc -33.8688 151.2093) :json false}
         (request "--mock=-33.8688,151.2093"))))

(deftest completions-reserved
  (let [result (cli/parse-args ["completions" "bash"])]
    (is (= 2 (:exit result)))
    (is (nil? (:out result)))
    (is (str/includes? (:err result) "completions are not available in this port"))))

(deftest empty-mock-value
  (is (usage-error? "--mock=")))

(deftest conflicts
  (is (usage-error? "--at=1,2" "--mock=1,2"))
  (is (usage-error? "--mock=1,2" "Paris"))
  (is (usage-error? "   "))
  (is (usage-error? "--at=91,0"))
  (is (usage-error? "--at=0,-180.5"))
  (is (usage-error? "--json" "--mock=x")))

(deftest at-boundary
  (is (= {:mode :at :location (loc 90.0 -180.0) :json false} (request "--at=90,-180"))))

(deftest no-arguments
  (is (= {:request {:mode :current :json false}} (cli/parse-args []))))
