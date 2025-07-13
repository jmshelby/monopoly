(ns jmshelby.monopoly.util-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.util :as u]
            [jmshelby.monopoly.core :as c]))

(deftest sum-test
  (is (= 0 (u/sum [])))
  (is (= 9 (u/sum [9])))
  (is (= 15 (u/sum [1 2 3 4 5])))
  (is (= 15 (u/sum [5 4 3 2 1]))))

(deftest property
  ;; For now we'll just exercise this function across a number of random game simulations
  (testing "player-property-sell-worth"
    (testing "exercise"
      (let [sim-count 50
            _         (println "Running" sim-count "game simulations")
            sims      (time
                       (doall
                        (pmap (fn [n]
                                (let [players (+ 2 (rand-int 5))
                                      tx-max  (+ 20 (rand-int 500))
                                      state   (c/rand-game-end-state
                                               players
                                               tx-max)
                                        ;; Invoke the fn-in-test here
                                      appended
                                      (update state :players
                                              (fn [players]
                                                (map (fn [player]
                                                       (assoc player :prop-sell-worth
                                                              (u/player-property-sell-worth state (:id player))))
                                                     players)))]
                                  [[n players tx-max] appended]))
                              (range 1 (inc sim-count)))))]
        (println "Running" sim-count "game simulations...DONE")
        (println "Sims:")
        (doseq [[[_ players tx-max] sim] sims]
          (println "  [" players "/" tx-max "/" (-> sim :transactions count) "]"
                   "->" (->> sim :players (map :prop-sell-worth))))

        ;; Assertions to validate property sell worth calculations
        (is (= sim-count (count sims)) "Should have run the expected number of simulations")

        (doseq [[[n players tx-max] sim] sims]
          (is (sequential? (:players sim)) (str "Simulation " n " should have players collection"))
          (is (= players (count (:players sim))) (str "Simulation " n " should have expected number of players"))

          ;; Test that all sell worth values are non-negative integers
          (doseq [player (:players sim)]
            (is (contains? player :prop-sell-worth)
                (str "Player " (:id player) " should have prop-sell-worth"))
            (is (integer? (:prop-sell-worth player))
                (str "Player " (:id player) " sell worth should be integer"))
            (is (>= (:prop-sell-worth player) 0)
                (str "Player " (:id player) " sell worth should be non-negative")))

          ;; Test that total sell worth is reasonable compared to starting cash
          (let [total-sell-worth (reduce + (map :prop-sell-worth (:players sim)))
                starting-cash (* players 1500)]
            (is (<= total-sell-worth (* starting-cash 5))
                "Total sell worth shouldn't be excessively high")))))))

;; ======= Building Inventory Tests ===================

(deftest building-inventory-limits-test
  (testing "Initial game state has correct building inventory limits"
    (let [game-state (c/init-game-state 4)]
      (is (= 32 (get-in game-state [:board :rules :building-limits :houses])))
      (is (= 12 (get-in game-state [:board :rules :building-limits :hotels])))
      (is (= 32 (u/houses-available game-state)))
      (is (= 12 (u/hotels-available game-state))))))

(deftest houses-and-hotels-in-play-calculation-test
  (testing "Correctly calculates houses and hotels in play"
    (let [game-state (-> (c/init-game-state 3)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 5})
                         (assoc-in [:players 1 :properties :oriental-ave] {:status :paid :house-count 3}))]
      (is (= 5 (u/houses-in-play game-state)))
      (is (= 1 (u/hotels-in-play game-state)))
      (is (= 27 (u/houses-available game-state)))
      (is (= 11 (u/hotels-available game-state))))))

(deftest building-inventory-prevents-purchase-test
  (testing "Cannot build house when inventory is exhausted"
    ;; Create a game state where many houses are in play to exhaust inventory
    (let [base-game-state (c/init-game-state 4)
          ;; Add many properties with 4 houses each to use up the 32 house limit
          game-state (-> base-game-state
                         (assoc-in [:players 0 :cash] 2000)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 0})
                         ;; Use real properties from the board to exhaust houses
                         (assoc-in [:players 1 :properties :oriental-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 1 :properties :vermont-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 1 :properties :connecticut-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 2 :properties :st-charles-place] {:status :paid :house-count 4})
                         (assoc-in [:players 2 :properties :states-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 2 :properties :virginia-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 3 :properties :st-james-place] {:status :paid :house-count 4})
                         (assoc-in [:players 3 :properties :tennessee-ave] {:status :paid :house-count 4}))]
      ;; Should have exhausted most houses (32 total)
      (is (<= (u/houses-available game-state) 0))
      (is (false? (u/can-buy-house? game-state :mediterranean-ave))))))

(deftest building-inventory-prevents-action-availability-test
  (testing "Buy-house action is not available when inventory is exhausted"
    ;; This tests the integration between inventory limits and game action availability
    (let [base-game-state (c/init-game-state 4)
          ;; Create a game state where houses are exhausted but player has monopoly and cash
          game-state (-> base-game-state
                         ;; Give current player monopoly and cash
                         (assoc-in [:players 0 :cash] 2000)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 0})
                         ;; Exhaust house inventory with other players
                         (assoc-in [:players 1 :properties :oriental-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 1 :properties :vermont-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 1 :properties :connecticut-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 2 :properties :st-charles-place] {:status :paid :house-count 4})
                         (assoc-in [:players 2 :properties :states-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 2 :properties :virginia-ave] {:status :paid :house-count 4})
                         (assoc-in [:players 3 :properties :st-james-place] {:status :paid :house-count 4})
                         (assoc-in [:players 3 :properties :tennessee-ave] {:status :paid :house-count 4}))
          ;; Simulate the game action availability logic from advance-board
          player (u/current-player game-state)
          cash (:cash player)
          can-build? (u/can-buy-house? game-state)
          actions (cond-> #{}
                    ;; Always available
                    true (conj :done)
                    ;; House building - should NOT be available due to inventory
                    can-build? (conj :buy-house))]
      ;; Verify inventory is exhausted
      (is (<= (u/houses-available game-state) 0))
      ;; Verify player otherwise meets building requirements
      (is (>= cash 50)) ; Has enough cash for house
      (is (contains? (:properties player) :mediterranean-ave)) ; Has monopoly property
      ;; But can-build? should be false due to inventory
      (is (false? can-build?))
      ;; And therefore :buy-house should NOT be in available actions
      (is (not (contains? actions :buy-house))))))