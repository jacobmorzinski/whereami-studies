(ns whereami.output
  "Formats results for stdout. Pure: maps in, strings out."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.util Locale)))

(set! *warn-on-reflection* true)

(defn- fmt
  "String/format with Locale/ROOT: always \"40.7128\". Clojure's own format
  uses the machine's locale and would print \"40,7128\" on a German machine."
  [pattern & args]
  (String/format Locale/ROOT pattern (object-array args)))

(defn human [{:keys [latitude longitude accuracy]} address show-accuracy?]
  (let [fields (when address
                 (remove str/blank? ((juxt :street :city :state :postal-code :country) address)))]
    (str (fmt "Location: %.4f, %.4f\n" latitude longitude)
         (when show-accuracy? (fmt "Accuracy: %.0fm\n" accuracy))
         (when (seq fields) (str "Address: " (str/join ", " fields) "\n")))))

(defn- json-key
  ":postal-code → \"postal_code\"."
  [k]
  (str/replace (name k) "-" "_"))

(defn- write [value]
  (str (json/write-str value :key-fn json-key :escape-unicode false) "\n"))

(defn json
  "A nil address is written as \"address\":null. array-map keeps the keys in
  this order (a small map literal would too, but this says it on purpose)."
  [{:keys [latitude longitude accuracy]} address]
  (write (array-map :latitude latitude :longitude longitude :accuracy accuracy :address address)))

(defn json-error [key]
  (write {:error key}))
