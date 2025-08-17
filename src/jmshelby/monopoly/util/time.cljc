(ns jmshelby.monopoly.util.time)

(defn now
  "Get current time in milliseconds since epoch.
   Cross-platform implementation that works in both Clojure and ClojureScript."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn elapsed-ms
  "Calculate elapsed time in milliseconds between start-time and now.
   If end-time is provided, calculates elapsed time between start-time and end-time."
  ([start-time]
   (- (now) start-time))
  ([start-time end-time]
   (- end-time start-time)))

(defn elapsed-seconds
  "Calculate elapsed time in seconds between start-time and now.
   If end-time is provided, calculates elapsed time between start-time and end-time."
  ([start-time]
   (/ (elapsed-ms start-time) 1000.0))
  ([start-time end-time]
   (/ (elapsed-ms start-time end-time) 1000.0)))