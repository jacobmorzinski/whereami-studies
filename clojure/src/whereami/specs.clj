(ns whereami.specs
  "The shapes of whereami's data, as clojure.spec specs. A spec is a named
  predicate: s/valid? checks a value against it, s/conform checks and
  (for conformers) transforms, and s/explain says what's wrong."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [whereami.coords :as coords]))

;; --- Locations ---------------------------------------------------------------

(s/def ::finite (s/and double? #(Double/isFinite %)))
(s/def ::latitude ::finite)
(s/def ::longitude ::finite)
(s/def ::accuracy ::finite)

;; :req-un — required keys, written unqualified in the map: {:latitude …}.
(s/def ::location (s/keys :req-un [::latitude ::longitude ::accuracy]))

;; --at must be a real place on Earth; --mock isn't range-checked.
(s/def ::at-location
  (s/and ::location
         #(<= -90.0 (:latitude %) 90.0)
         #(<= -180.0 (:longitude %) 180.0)))

;; A conformer: conforming "40.7,-74.0" *produces* the location map.
(s/def ::coords
  (s/conformer #(or (and (string? %) (coords/parse %)) ::s/invalid)))

;; --- Addresses ---------------------------------------------------------------

(s/def ::street string?)
(s/def ::city string?)
(s/def ::state string?)
(s/def ::postal-code string?)
(s/def ::country string?)
(s/def ::address (s/keys :req-un [::street ::city ::state ::postal-code ::country]))

;; --- Requests: one shape per :mode -----------------------------------------------

(s/def ::mode #{:current :mock :at :lookup})
(s/def ::json boolean?)
(s/def ::query (s/and string? (complement str/blank?)))

;; A multi-spec picks the spec from the value itself, here its :mode.
(defmulti request-mode :mode)
(defmethod request-mode :current [_] (s/keys :req-un [::mode ::json]))
(defmethod request-mode :mock [_] (s/keys :req-un [::mode ::json ::location]))
(defmethod request-mode :at [_]
  (s/and (s/keys :req-un [::mode ::json ::location])
         #(s/valid? ::at-location (:location %))))
(defmethod request-mode :lookup [_] (s/keys :req-un [::mode ::json ::query]))

(s/def ::request (s/multi-spec request-mode :mode))

;; --- What app/run returns ----------------------------------------------------------

(s/def ::stdout string?)
(s/def ::stderr string?)
(s/def ::exit-code #{0 1})
(s/def ::outcome (s/keys :req-un [::stdout ::stderr ::exit-code]))
