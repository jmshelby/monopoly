(ns jmshelby.monopoly.util.format
  (:refer-clojure :exclude [format printf])
  (:require [clojure.string]))

(defn format
  "Cross-platform format that works in both Clojure and ClojureScript"
  [fmt & args]
  #?(:clj (apply clojure.core/format fmt args)
     :cljs (let [fmt-str (str fmt)]
             (loop [result fmt-str
                    remaining-args args]
               (if (empty? remaining-args)
                 result
                 (let [arg (first remaining-args)
                       ;; Match format specifiers with optional width
                       match (re-find #"%(\d*)([sd])" result)]
                   (if match
                     (let [[full-match width-str spec] match
                           width (if (seq width-str) (js/parseInt width-str) 0)
                           formatted-arg (if (and (= spec "d") (> width 0))
                                          ;; Right-pad numbers with spaces
                                           (let [str-arg (str arg)
                                                 pad-needed (- width (count str-arg))]
                                             (if (> pad-needed 0)
                                               (str (apply str (repeat pad-needed " ")) str-arg)
                                               str-arg))
                                          ;; For %s or %d without width, just convert to string
                                           (str arg))
                           new-result (clojure.string/replace-first result (re-pattern (str "%" width-str spec)) formatted-arg)]
                       (recur new-result (rest remaining-args)))
                     ;; No more format specifiers found
                     result)))))))

(defn printf 
  "Cross-platform printf that works in both Clojure and ClojureScript"
  [fmt & args]
  (print (apply format fmt args)))