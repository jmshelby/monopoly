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

;; ======= New Property Management Function Tests ===================

(deftest can-sell-house-validation-test
  (testing "can-sell-house? validates property ownership"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2}))
          player (u/current-player game-state)]
      ;; Can sell from owned property with houses
      (is (first (u/can-sell-house? game-state player :mediterranean-ave)))
      ;; Cannot sell from unowned property
      (is (false? (first (u/can-sell-house? game-state player :baltic-ave))))))

  (testing "can-sell-house? validates house inventory"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0}))
          player (u/current-player game-state)]
      ;; Cannot sell from property with no houses
      (let [[valid? reason] (u/can-sell-house? game-state player :mediterranean-ave)]
        (is (false? valid?))
        (is (= :house-inventory reason)))))

  (testing "can-sell-house? validates even distribution"
    (let [game-state (-> (c/init-game-state 2)
                         ;; Mediterranean monopoly - give uneven house distribution
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 1})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 2}))
          player (u/current-player game-state)]
      ;; Cannot sell from property without max houses in group
      (let [[valid? reason] (u/can-sell-house? game-state player :mediterranean-ave)]
        (is (false? valid?))
        (is (= :even-house-distribution reason)))
      ;; Can sell from property with max houses in group
      (is (first (u/can-sell-house? game-state player :baltic-ave))))))

(deftest can-sell-any-house-test
  (testing "returns true when player has sellable houses"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 2}))
          player (u/current-player game-state)]
      (is (true? (u/can-sell-any-house? game-state player)))))

  (testing "returns false when player has no sellable houses"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0}))
          player (u/current-player game-state)]
      (is (false? (u/can-sell-any-house? game-state player))))))

(deftest can-mortgage-any-property-test
  (testing "returns true when player has mortgageable properties"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0}))]
      (is (true? (u/can-mortgage-any-property? game-state (u/current-player game-state))))))

  (testing "returns false when player has no mortgageable properties"
    (let [game-state (-> (c/init-game-state 2)
                         ;; Property with houses cannot be mortgaged
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 1}))]
      (is (false? (u/can-mortgage-any-property? game-state (u/current-player game-state))))))

  (testing "returns false when all properties are already mortgaged"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :mortgaged :house-count 0}))]
      (is (false? (u/can-mortgage-any-property? game-state (u/current-player game-state)))))))

(deftest can-unmortgage-any-property-test
  (testing "returns true when player has mortgaged properties and sufficient cash"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :cash] 500)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :mortgaged :house-count 0}))]
      (is (true? (u/can-unmortgage-any-property? game-state (u/current-player game-state))))))

  (testing "returns false when player has insufficient cash"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :cash] 10)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :mortgaged :house-count 0}))]
      (is (false? (u/can-unmortgage-any-property? game-state (u/current-player game-state))))))

  (testing "returns false when player has no mortgaged properties"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :cash] 500)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0}))]
      (is (false? (u/can-unmortgage-any-property? game-state (u/current-player game-state)))))))

;; ======= Property Action Application Tests ===================

(deftest apply-house-sale-test
  (testing "apply-house-sale updates game state correctly"
    (let [initial-state (-> (c/init-game-state 2)
                            (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                            (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 2})
                            (assoc-in [:players 0 :cash] 1000))
          result-state (u/apply-house-sale initial-state
                                           (u/current-player initial-state)
                                           :mediterranean-ave)
          player (u/current-player result-state)]
      ;; House count should decrease
      (is (= 1 (get-in player [:properties :mediterranean-ave :house-count])))
      ;; Cash should increase by 50% of house price (Mediterranean house cost is $50, so +$25)
      (is (= 1025 (:cash player)))
      ;; Should have a transaction recorded
      (is (some #(= :sell-house (:type %)) (:transactions result-state)))))

  (testing "apply-house-sale validates before applying"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0}))
          player (u/current-player game-state)]
      ;; Should throw exception for invalid house sale
      (is (thrown? Exception (u/apply-house-sale game-state player :mediterranean-ave))))))

(deftest apply-property-mortgage-test
  (testing "apply-property-mortgage updates game state correctly"
    (let [initial-state (-> (c/init-game-state 2)
                            (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0})
                            (assoc-in [:players 0 :cash] 1000))
          result-state (u/apply-property-mortgage initial-state (u/current-player initial-state) :mediterranean-ave)
          player (u/current-player result-state)]
      ;; Property status should change to mortgaged
      (is (= :mortgaged (get-in player [:properties :mediterranean-ave :status])))
      ;; Cash should increase by mortgage value (Mediterranean mortgage is $30)
      (is (= 1030 (:cash player)))
      ;; Should have a transaction recorded
      (is (some #(= :mortgage-property (:type %)) (:transactions result-state)))))

  (testing "apply-property-mortgage validates property ownership"
    (let [game-state (c/init-game-state 2)]
      ;; Should throw exception for unowned property
      (is (thrown? Exception (u/apply-property-mortgage game-state (u/current-player game-state) :mediterranean-ave)))))

  (testing "apply-property-mortgage validates no houses on property"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 1}))]
      ;; Should throw exception for property with houses
      (is (thrown? Exception (u/apply-property-mortgage game-state (u/current-player game-state) :mediterranean-ave)))))

  (testing "apply-property-mortgage validates property not already mortgaged"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :mortgaged :house-count 0}))]
      ;; Should throw exception for already mortgaged property
      (is (thrown? Exception (u/apply-property-mortgage game-state (u/current-player game-state) :mediterranean-ave))))))

(deftest apply-property-unmortgage-test
  (testing "apply-property-unmortgage updates game state correctly"
    (let [initial-state (-> (c/init-game-state 2)
                            (assoc-in [:players 0 :properties :mediterranean-ave] {:status :mortgaged :house-count 0})
                            (assoc-in [:players 0 :cash] 1000))
          result-state (u/apply-property-unmortgage initial-state (u/current-player initial-state) :mediterranean-ave)
          player (u/current-player result-state)]
      ;; Property status should change to paid
      (is (= :paid (get-in player [:properties :mediterranean-ave :status])))
      ;; Cash should decrease by 110% of mortgage value (Mediterranean: $30 * 1.1 = $33)
      (is (= 967 (:cash player)))
      ;; Should have a transaction recorded
      (is (some #(= :unmortgage-property (:type %)) (:transactions result-state)))))

  (testing "apply-property-unmortgage validates sufficient funds"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :mortgaged :house-count 0})
                         (assoc-in [:players 0 :cash] 10))]
      ;; Should throw exception for insufficient funds
      (is (thrown? Exception (u/apply-property-unmortgage game-state (u/current-player game-state) :mediterranean-ave)))))

  (testing "apply-property-unmortgage validates property is mortgaged"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0})
                         (assoc-in [:players 0 :cash] 1000))]
      ;; Should throw exception for non-mortgaged property
      (is (thrown? Exception (u/apply-property-unmortgage game-state (u/current-player game-state) :mediterranean-ave))))))

;; ======= Bankruptcy Turn Order Tests ===================
;; 
;; These tests validate the critical "player after bankruptcy getting skipped" bug fix
;; by testing the next-player function and turn management utilities

(deftest next-player-with-bankruptcy-test
  "Test that next-player function properly skips bankrupt players"
  (testing "next-player skips single bankrupt player"
    (let [game-state (-> (c/init-game-state 4)
                         (assoc-in [:current-turn :player] "A")
                         (assoc-in [:players 1 :status] :bankrupt))] ; Mark B as bankrupt
      ;; From A, next should be C (skipping bankrupt B)
      (is (= "C" (:id (u/next-player game-state))))))

  (testing "next-player handles multiple bankrupt players"
    (let [game-state (-> (c/init-game-state 4)
                         (assoc-in [:current-turn :player] "A")
                         (assoc-in [:players 1 :status] :bankrupt) ; Mark B as bankrupt
                         (assoc-in [:players 2 :status] :bankrupt))] ; Mark C as bankrupt
      ;; From A, next should be D (skipping bankrupt B and C)
      (is (= "D" (:id (u/next-player game-state))))))

  (testing "next-player handles wrap-around with bankruptcy"
    (let [game-state (-> (c/init-game-state 4)
                         (assoc-in [:current-turn :player] "D")
                         (assoc-in [:players 1 :status] :bankrupt))] ; Mark B as bankrupt
      ;; From D, next should wrap to A (skipping bankrupt B)
      (is (= "A" (:id (u/next-player game-state))))))

  (testing "next-player systematic turn order validation"
    ;; Test all possible current player positions with bankrupt middle player
    (let [base-state (-> (c/init-game-state 4)
                         (assoc-in [:players 1 :status] :bankrupt))] ; Mark B as bankrupt
      
      (doseq [[current-player expected-next] [["A" "C"]  ; A -> C (skip B)
                                              ["C" "D"]  ; C -> D 
                                              ["D" "A"]]] ; D -> A (wrap around, skip B)
        (let [game-state (assoc-in base-state [:current-turn :player] current-player)
              result (u/next-player game-state)]
          (is (= expected-next (:id result))
              (str "From player " current-player " should go to " expected-next 
                   " but got " (:id result)))))))

  (testing "next-player edge cases"
    ;; Last player bankruptcy
    (let [game-state (-> (c/init-game-state 4)
                         (assoc-in [:current-turn :player] "D")
                         (assoc-in [:players 3 :status] :bankrupt))]
      (is (= "A" (:id (u/next-player game-state)))))
    
    ;; First player bankruptcy  
    (let [game-state (-> (c/init-game-state 4)
                         (assoc-in [:current-turn :player] "A")
                         (assoc-in [:players 0 :status] :bankrupt))]
      (is (= "B" (:id (u/next-player game-state)))))
    
    ;; Only one active player remaining
    (let [game-state (-> (c/init-game-state 4)
                         (assoc-in [:current-turn :player] "A")
                         (assoc-in [:players 1 :status] :bankrupt)
                         (assoc-in [:players 2 :status] :bankrupt)
                         (assoc-in [:players 3 :status] :bankrupt))]
      (is (= "A" (:id (u/next-player game-state)))))))

(deftest apply-end-turn-with-bankruptcy-test
  "Test that apply-end-turn advances to correct player after bankruptcy"
  (testing "apply-end-turn skips bankrupt players"
    (let [initial-state (-> (c/init-game-state 4)
                            (assoc-in [:current-turn :player] "A")
                            (assoc-in [:players 1 :status] :bankrupt)) ; Mark B as bankrupt
          result-state (u/apply-end-turn initial-state)]
      ;; Should advance to C (skipping bankrupt B)
      (is (= "C" (get-in result-state [:current-turn :player])))
      ;; Dice rolls should be cleared
      (is (empty? (get-in result-state [:current-turn :dice-rolls])))))

  (testing "apply-end-turn handles multiple consecutive bankruptcies"
    (let [initial-state (-> (c/init-game-state 5)
                            (assoc-in [:current-turn :player] "A")
                            (assoc-in [:players 1 :status] :bankrupt) ; Mark B as bankrupt
                            (assoc-in [:players 2 :status] :bankrupt) ; Mark C as bankrupt
                            (assoc-in [:players 3 :status] :bankrupt)) ; Mark D as bankrupt
          result-state (u/apply-end-turn initial-state)]
      ;; Should advance to E (skipping all bankrupt players)
      (is (= "E" (get-in result-state [:current-turn :player]))))))

(deftest bankruptcy-turn-order-regression-test
  "Regression test for the specific 'player after bankruptcy getting skipped' bug"
  (testing "Player immediately after bankrupt player gets their turn"
    ;; This test would have failed before the bug fix
    (let [game-state (-> (c/init-game-state 4)
                         (assoc-in [:current-turn :player] "A")
                         (assoc-in [:players 1 :status] :bankrupt))] ; B goes bankrupt
      
      ;; Critical test: from A, next should be C (not skip C and go to D)
      (is (= "C" (:id (u/next-player game-state)))
          "After B goes bankrupt, turn from A should go to C (not skip C)")))

  (testing "Bug doesn't occur with different bankrupt positions"
    (doseq [[bankrupt-idx next-expected] [[0 "B"]  ; A bankrupt -> B
                                          [1 "C"]  ; B bankrupt -> C  
                                          [2 "D"]  ; C bankrupt -> D
                                          [3 "A"]]] ; D bankrupt -> A (wrap)
      
      (let [bankrupt-player (nth ["A" "B" "C" "D"] bankrupt-idx)
            game-state (-> (c/init-game-state 4)
                           (assoc-in [:current-turn :player] bankrupt-player)
                           (assoc-in [:players bankrupt-idx :status] :bankrupt))
            next-player-result (u/next-player game-state)]
        
        (is (= next-expected (:id next-player-result))
            (str "When " bankrupt-player " is bankrupt, next player should be " 
                 next-expected " not " (:id next-player-result))))))

  (testing "Complex scenario with multiple bankruptcies preserves turn order"
    ;; Simulate progressive bankruptcies to ensure no cascading skips
    (let [initial-state (c/init-game-state 4)]
      
      ;; Test sequence: Normal -> B bankrupt -> B+C bankrupt
      (let [b-bankrupt (assoc-in initial-state [:players 1 :status] :bankrupt)
            bc-bankrupt (assoc-in b-bankrupt [:players 2 :status] :bankrupt)]
        
        ;; With B bankrupt: A -> C -> D -> A
        (is (= "C" (:id (u/next-player (assoc-in b-bankrupt [:current-turn :player] "A")))))
        (is (= "D" (:id (u/next-player (assoc-in b-bankrupt [:current-turn :player] "C")))))
        (is (= "A" (:id (u/next-player (assoc-in b-bankrupt [:current-turn :player] "D")))))
        
        ;; With B+C bankrupt: A -> D -> A
        (is (= "D" (:id (u/next-player (assoc-in bc-bankrupt [:current-turn :player] "A")))))
        (is (= "A" (:id (u/next-player (assoc-in bc-bankrupt [:current-turn :player] "D")))))))))
