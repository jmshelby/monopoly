(ns jmshelby.monopoly.util.format
  (:refer-clojure :exclude [format printf])
  #?(:clj (:require [clojure.string])
     :cljs (:require
            [goog.string :as gstring]
            [goog.string.format])))

(defn format
  "Cross-platform format that works in both Clojure and ClojureScript"
  [fmt & args]
  #?(:clj (apply clojure.core/format fmt args)
     :cljs (apply gstring/format fmt args)))

(defn printf 
  "Cross-platform printf that works in both Clojure and ClojureScript"
  [fmt & args]
  (print (apply format fmt args)))
