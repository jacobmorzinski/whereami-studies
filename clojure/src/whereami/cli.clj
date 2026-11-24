(ns whereami.cli
  "Turns the command line into data: a request to run, text to print with
  exit 0 (help, version), or a usage error with exit 2."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.cli :as tools.cli]
            [whereami.specs :as specs])
  (:import (whereami.platform Platforms)))

(def version "0.4.0")

(def ^:private description
  "Get your current location using native OS location services, or look up an address or a pair of coordinates.")

;; tools.cli describes options as vectors: short flag, long flag (with a
;; placeholder if it takes a value), description. The values stay strings
;; here; parse-args conforms them with ::specs/coords, so the coordinate rule
;; lives in one place.
(def ^:private options
  [["-h" "--help" "Show this help and exit."]
   ["-v" "--version" "Print the version and exit."]
   [nil "--json" "Print JSON instead of text."]
   [nil "--mock LAT,LON" "Use these coordinates instead of location services (no address lookup)."]
   [nil "--at LAT,LON" "Look up the address at these coordinates."]])

(defn- usage-error [message]
  {:exit 2 :err (str "Error: " message "\n")})

(defn- help-text [summary]
  (str description "\n\n"
       "Usage: whereami [options] [ADDRESS...]\n\n"
       "Options:\n" summary "\n"))

(defn- request-for [{:keys [json]} mock-location at-location query]
  (let [json (boolean json)]
    (cond
      mock-location {:mode :mock :location mock-location :json json}
      at-location {:mode :at :location at-location :json json}
      (seq query) {:mode :lookup :query query :json json}
      :else {:mode :current :json json})))

(defn parse-args
  "Returns {:request …}, {:exit 0 :out …} or {:exit 2 :err …}."
  [args]
  (let [{:keys [options arguments errors summary]} (tools.cli/parse-opts args options)
        {:keys [mock at]} options
        query (str/trim (str/join " " arguments))
        ;; nil when the option is absent, ::s/invalid when it isn't LAT,LON
        mock-location (some->> mock (s/conform ::specs/coords))
        at-location (some->> at (s/conform ::specs/coords))
        dash-word (first (filter #(str/starts-with? % "-") arguments))]
    (cond
      errors (usage-error (str/join "; " errors))
      (:help options) {:exit 0 :out (help-text summary)}
      (:version options) {:exit 0 :out (str "whereami " version " (" (Platforms/osName) ")\n")}
      (= "completions" (first arguments)) (usage-error "completions are not available in this port")
      ;; tools.cli already rejects unknown options; this catches words after "--".
      dash-word (usage-error (str "unknown flag '" dash-word "'"))
      (s/invalid? mock-location) (usage-error (str "--mock expects LAT,LON with two decimal numbers, got '" mock "'"))
      (s/invalid? at-location) (usage-error (str "--at expects LAT,LON with two decimal numbers, got '" at "'"))
      (and at-location (not (s/valid? ::specs/at-location at-location)))
      (usage-error "--at must be within latitude -90..90 and longitude -180..180")
      (and (seq arguments) (str/blank? query)) (usage-error "the address is empty")
      (and mock at) (usage-error "--mock and --at can't be used together")
      (and (seq arguments) (or mock at)) (usage-error "an address can't be combined with --mock or --at")
      :else
      (let [request (request-for options mock-location at-location query)]
        ;; Every check above exists to make this true.
        (assert (s/valid? ::specs/request request) (s/explain-str ::specs/request request))
        {:request request}))))
