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
      (println "  -> Status: " (:status sim) " (" (-> sim :transactions count) ")"))

    ;; Assertions to validate simulation results
    (testing "All simulations completed successfully"
      (is (= sim-count (count sims)) "Should have run the expected number of simulations"))

    (testing "Game states have valid structure"
      (doseq [[n sim] sims]
        (is (contains? #{:complete :playing} (:status sim)) 
            (str "Simulation " n " should have valid status"))
        (is (vector? (:transactions sim)) 
            (str "Simulation " n " should have transactions vector"))
        (is (vector? (:players sim)) 
            (str "Simulation " n " should have players vector"))
        (is (= 4 (count (:players sim))) 
            (str "Simulation " n " should have 4 players"))))

    (testing "Transaction counts are reasonable"
      (let [tx-counts (map #(-> % second :transactions count) sims)]
        (is (every? pos? tx-counts) "All games should have at least 1 transaction")
        ;; Some games may hit failsafe and continue beyond limit due to current player finishing their turn
        (is (every? #(<= % 3500) tx-counts) "No game should greatly exceed failsafe limit")))))
