(ns build
  "Builds target/whereami.jar, one jar with whereami, Clojure, the libraries
  and the Java platform jar inside. Run with: clojure -T:build uber
  tools.build is a library: a build is an ordinary Clojure program."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "target/whereami.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    ;; AOT-compile only the entry point (and what it loads), so `java -jar`
    ;; has a real main class to start.
    (b/compile-clj {:basis basis :ns-compile '[whereami.main] :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file jar-file
             :basis basis
             :main 'whereami.main
             ;; The JDK reads this from an executable jar's manifest: the
             ;; Java platform's FFM calls are allowed, with no warning.
             :manifest {"Enable-Native-Access" "ALL-UNNAMED"}})
    (println "Wrote" jar-file)))
