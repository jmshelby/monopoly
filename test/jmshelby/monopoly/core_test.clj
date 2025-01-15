(ns jmshelby.monopoly.core-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as c]))

;; Run several random end game simulations
(deftest exercise-game
  ;; TODO - this can be parallelized easily
  (doseq [n (range 40)]
    (print "Running sim " n)
    (let [end-state (c/rand-game-end-state 4 1000)]
      (println "  -> Status: " (:status end-state) " (" (-> end-state :transactions count) ")"))))
