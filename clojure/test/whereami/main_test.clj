(ns whereami.main-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [whereami.fakes :refer [fake fails-with]]
            [whereami.main :as main])
  (:import (java.io ByteArrayOutputStream StringWriter Writer)
           (whereami.platform LocationError)))

(deftest utf8-writer
  (let [bytes (ByteArrayOutputStream.)
        ^Writer w (main/utf8-writer bytes)]
    (.write w "é")
    (.flush w)
    (is (= [0xC3 0xA9] (map #(bit-and % 0xff) (.toByteArray bytes))))))

;; main* is -main without System/exit: it returns the exit code.
(defn- run-main [args platform]
  (let [out (StringWriter.)
        err (StringWriter.)
        code (main/main* args platform out err)]
    {:exit code :out (str out) :err (str err)}))

(deftest usage-error-end-to-end
  (is (= {:exit 2 :out "" :err "Error: completions are not available in this port\n"}
         (run-main ["completions"] (fake {})))))

(deftest runtime-error-end-to-end
  (let [r (run-main ["--json"] (fake {:location (fails-with LocationError/PERMISSION_DENIED)}))]
    (is (= 1 (:exit r)))
    (is (= "{\"error\":\"permission_denied\"}\n" (:out r)))
    (is (str/starts-with? (:err r) "Error: location permission denied."))))

(deftest unexpected-exception-exits-1-promptly
  ;; Before: the exception escaped -main, System/exit never ran, and the
  ;; future's non-daemon thread kept the JVM alive for about a minute.
  (let [r (run-main [] (fake {:location #(throw (IllegalStateException. "bug"))}))]
    (is (= 1 (:exit r)))
    (is (str/includes? (:err r) "IllegalStateException"))))
