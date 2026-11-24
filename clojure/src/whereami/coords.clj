(ns whereami.coords
  "Parses \"LAT,LON\" for --mock and --at."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; A plain decimal: optional sign, digits with an optional fraction, optional
;; exponent. Checked before Double/parseDouble, which would also accept "NaN",
;; "Infinity", hex floats ("0x1p3"), type suffixes ("1d") and surrounding
;; control characters.
(def ^:private decimal #"[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?")

(defn- trim-spaces [s]
  (str/replace s #"^ +| +$" ""))

(defn- number [part]
  (let [text (trim-spaces part)]
    (when (re-matches decimal text)
      (let [value (Double/parseDouble text)] ; never locale-dependent
        (when (Double/isFinite value)        ; "1e999" overflows to Infinity
          value)))))

(defn parse
  "Splits at the first comma and trims spaces from each half. Returns a
  location map, or nil if the text isn't two decimal numbers."
  [^String text]
  (let [comma (.indexOf text ",")]
    (when-not (neg? comma)
      (let [lat (number (subs text 0 comma))
            lon (number (subs text (inc comma)))]
        (when (and lat lon)
          {:latitude lat :longitude lon :accuracy 0.0})))))
