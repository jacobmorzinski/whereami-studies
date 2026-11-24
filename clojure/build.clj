(ns build
  "Builds target/whereami.jar. Run with: clojure -T:build uber"
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")

(defn clean [_]
  (b/delete {:path "target"}))
