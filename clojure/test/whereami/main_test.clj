(ns whereami.main-test
  (:require [clojure.test :refer [deftest is]]
            [whereami.main :as main])
  (:import (java.io ByteArrayOutputStream Writer)))

(deftest utf8-writer
  (let [bytes (ByteArrayOutputStream.)
        ^Writer w (main/utf8-writer bytes)]
    (.write w "é")
    (.flush w)
    (is (= [0xC3 0xA9] (map #(bit-and % 0xff) (.toByteArray bytes))))))
