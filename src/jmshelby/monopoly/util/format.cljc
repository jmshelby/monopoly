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
                       ;; Match format specifiers: %s, %d, %f, %.1f, etc.
                       match (re-find #"%(\d*)(\.(\d+))?([sdf])" result)]
                   (if match
                     (let [[full-match width-str _ precision-str spec] match
                           width (if (seq width-str) (js/parseInt width-str) 0)
                           precision (if (seq precision-str) (js/parseInt precision-str) nil)
                           formatted-arg (cond
                                          ;; Handle %f and %.1f floating-point
                                          (= spec "f")
                                          (if precision
                                            (.toFixed arg precision)
                                            (str arg))
                                          
                                          ;; Handle %d with width (right-pad with spaces)
                                          (and (= spec "d") (> width 0))
                                          (let [str-arg (str arg)
                                                pad-needed (- width (count str-arg))]
                                            (if (> pad-needed 0)
                                              (str (apply str (repeat pad-needed " ")) str-arg)
                                              str-arg))
                                          
                                          ;; Default: convert to string
                                          :else (str arg))
                           new-result (clojure.string/replace-first result (re-pattern (clojure.string/escape full-match {\\ "\\\\" \$ "\\$" \^ "\\^" \. "\\." \| "\\|" \? "\\?" \* "\\*" \+ "\\+" \( "\\(" \) "\\)" \[ "\\[" \] "\\]" \{ "\\{" \} "\\}"})) formatted-arg)]
                       (recur new-result (rest remaining-args)))
                     ;; No more format specifiers found
                     result)))))))

(defn printf 
  "Cross-platform printf that works in both Clojure and ClojureScript"
  [fmt & args]
  (print (apply format fmt args)))