(ns whereami.main
  "The entry point. :gen-class makes AOT compilation emit a real Java class,
  whereami.main, with a static main that calls -main."
  (:require [whereami.app :as app]
            [whereami.cli :as cli]
            [whereami.platform :as platform])
  (:import (java.io OutputStream OutputStreamWriter PrintWriter Writer)
           (java.nio.charset StandardCharsets))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn utf8-writer
  "A writer that always encodes UTF-8, whatever the console's code page."
  ^PrintWriter [^OutputStream out]
  (PrintWriter. (OutputStreamWriter. out StandardCharsets/UTF_8) false))

(defn main*
  "Everything -main does except exiting: parse, run, write, and return the
  exit code. An unexpected exception (a bug, or a native failure the Java
  layer didn't map) prints its stack trace and gives exit 1, like an
  uncaught exception in the Java port."
  [args platform ^Writer out ^Writer err]
  (try
    (let [parsed (cli/parse-args args)
          {:keys [stdout stderr exit-code]}
          (if-let [request (:request parsed)]
            (app/run request platform app/timeout-ms)
            {:stdout (:out parsed "") :stderr (:err parsed "") :exit-code (:exit parsed)})]
      (.write out ^String stdout)
      (.write err ^String stderr)
      exit-code)
    (catch Throwable t
      (.printStackTrace t (PrintWriter. err true))
      1)
    (finally
      (.flush out)
      (.flush err))))

(defn -main [& args]
  ;; System/exit always runs, even on an error: future threads aren't
  ;; daemons, so without it an abandoned platform call would keep the JVM
  ;; alive (for about a minute, the pool's idle timeout).
  (System/exit (main* args (platform/native) (utf8-writer System/out) (utf8-writer System/err))))
