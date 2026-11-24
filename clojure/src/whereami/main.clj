(ns whereami.main
  "The entry point. :gen-class makes AOT compilation emit a real Java class,
  whereami.main, with a static main that calls -main."
  (:require [whereami.app :as app]
            [whereami.cli :as cli]
            [whereami.platform :as platform])
  (:import (java.io OutputStream OutputStreamWriter PrintWriter)
           (java.nio.charset StandardCharsets))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn utf8-writer
  "A writer that always encodes UTF-8, whatever the console's code page."
  ^PrintWriter [^OutputStream out]
  (PrintWriter. (OutputStreamWriter. out StandardCharsets/UTF_8) false))

(defn -main [& args]
  (let [out (utf8-writer System/out)
        err (utf8-writer System/err)
        parsed (cli/parse-args args)
        {:keys [stdout stderr exit-code]}
        (if-let [request (:request parsed)]
          (app/run request (platform/native) app/timeout-ms)
          {:stdout (:out parsed "") :stderr (:err parsed "") :exit-code (:exit parsed)})]
    (.write out ^String stdout)
    (.write err ^String stderr)
    (.flush out)
    (.flush err)
    ;; Explicit: future threads aren't daemons, and an abandoned platform call
    ;; must not keep the process alive.
    (System/exit exit-code)))
