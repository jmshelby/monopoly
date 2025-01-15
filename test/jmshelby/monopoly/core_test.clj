(ns jmshelby.monopoly.core-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as c]))

;; Run several random end game simulations
(deftest exercise-game
  (let [sim-count 100
        _         (println "Running" sim-count "game simulations")
        sims      (time
                    (doall
                      (pmap (fn [n]
                              [n (c/rand-game-end-state 4 2000)])
                            (range 1 (inc sim-count)))))]
    (println "Running" sim-count "game simulations...DONE")
    (println "Sims:")
    (doseq [[n sim] sims]
      (println "  -> Status: " (:status sim) " (" (-> sim :transactions count) ")"))))
