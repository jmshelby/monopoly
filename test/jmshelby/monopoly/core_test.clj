(ns jmshelby.monopoly.core-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as c]
            [jmshelby.monopoly.util :as u]))

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
        (is (every? #(<= % 3500) tx-counts) "No game should greatly exceed failsafe limit"))))

;; ======= New Property Management Integration Tests ===================

  (deftest sell-house-action-integration-test
    (testing "sell-house action integrates with core game engine"
    ;; Test the action dispatch logic from advance-board without relying on dumb player
      (let [initial-state (-> (c/init-game-state 2)
                            ;; Give current player monopoly with houses
                              (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                              (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 2})
                              (assoc-in [:players 0 :cash] 1000)
                            ;; Replace default player function with test-specific one
                              (assoc-in [:players 0 :function]
                                        (fn [game-state player-id method params]
                                          (case method
                                            :take-turn (if (-> params :actions-available :sell-house)
                                                         {:action :sell-house :property-name :mediterranean-ave}
                                                         {:action :done})
                                            {:action :decline})))
                              (assoc-in [:players 1 :function]
                                        (fn [game-state player-id method params]
                                          {:action :done})))
            result-state (c/advance-board initial-state)
            player (u/current-player result-state)]
      ;; House should be sold
        (is (= 1 (get-in player [:properties :mediterranean-ave :house-count])))
      ;; Cash should increase
        (is (> (:cash player) 1000))
      ;; Should have transaction
        (is (some #(= :sell-house (:type %)) (:transactions result-state)))))

    (testing "sell-house action dispatch works correctly"
    ;; Test that the core action dispatch recognizes :sell-house
      (let [game-state (-> (c/init-game-state 2)
                           (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                           (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 2})
                           (assoc-in [:players 0 :cash] 1000))
          ;; Simulate what advance-board does for :sell-house action
            decision {:action :sell-house :property-name :mediterranean-ave}
          ;; This is the core logic from advance-board case :sell-house
            result-state (u/apply-house-sale game-state (:property-name decision))
            player (u/current-player result-state)]
      ;; Verify the action was applied correctly
        (is (= 1 (get-in player [:properties :mediterranean-ave :house-count])))
        (is (= 1025 (:cash player))) ; 1000 + 25 from house sale
        (is (some #(= :sell-house (:type %)) (:transactions result-state))))))

  (deftest mortgage-unmortgage-action-cycle-test
    (testing "mortgage then unmortgage same property"
      (let [initial-state (-> (c/init-game-state 2)
                              (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0})
                              (assoc-in [:players 0 :cash] 1000))
          ;; First mortgage the property
            mortgaged-state (u/apply-property-mortgage initial-state :mediterranean-ave)
            player-after-mortgage (u/current-player mortgaged-state)
          ;; Then unmortgage it
            unmortgaged-state (u/apply-property-unmortgage mortgaged-state :mediterranean-ave)
            player-after-unmortgage (u/current-player unmortgaged-state)]

      ;; After mortgage: status should be mortgaged, cash increased
        (is (= :mortgaged (get-in player-after-mortgage [:properties :mediterranean-ave :status])))
        (is (= 1030 (:cash player-after-mortgage))) ; +30 mortgage value

      ;; After unmortgage: status should be paid, cash decreased by 110%
        (is (= :paid (get-in player-after-unmortgage [:properties :mediterranean-ave :status])))
        (is (= 997 (:cash player-after-unmortgage))) ; 1030 - 33 (110% of 30)

      ;; Should have both transactions
        (is (some #(= :mortgage-property (:type %)) (:transactions unmortgaged-state)))
        (is (some #(= :unmortgage-property (:type %)) (:transactions unmortgaged-state))))))

  (deftest actions-available-includes-new-actions-test
    (testing "sell-house appears when can-sell-any-house? is true"
      (let [game-state (-> (c/init-game-state 2)
                           (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                           (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 2}))
            player (u/current-player game-state)
            actions (cond-> #{}
                      true (conj :done)
                      (u/can-sell-any-house? game-state) (conj :sell-house))]
        (is (contains? actions :sell-house))))

    (testing "mortgage-property appears when can-mortgage-any-property? is true"
      (let [game-state (-> (c/init-game-state 2)
                           (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0}))
            actions (cond-> #{}
                      true (conj :done)
                      (u/can-mortgage-any-property? game-state) (conj :mortgage-property))]
        (is (contains? actions :mortgage-property))))

    (testing "unmortgage-property appears when can-unmortgage-any-property? is true"
      (let [game-state (-> (c/init-game-state 2)
                           (assoc-in [:players 0 :cash] 500)
                           (assoc-in [:players 0 :properties :mediterranean-ave] {:status :mortgaged :house-count 0}))
            actions (cond-> #{}
                      true (conj :done)
                      (u/can-unmortgage-any-property? game-state) (conj :unmortgage-property))]
        (is (contains? actions :unmortgage-property)))))

  (deftest actions-available-excludes-unavailable-actions-test
    (testing "actions don't appear when conditions not met"
      (let [game-state (c/init-game-state 2) ; Fresh game with no properties
            actions (cond-> #{}
                      true (conj :done)
                      (u/can-sell-any-house? game-state) (conj :sell-house)
                      (u/can-mortgage-any-property? game-state) (conj :mortgage-property)
                      (u/can-unmortgage-any-property? game-state) (conj :unmortgage-property))]
      ;; Should only have :done since player has no properties
        (is (= #{:done} actions)))))

;; ======= Edge Case & Validation Tests ===================

  (deftest house-selling-even-distribution-test
    (testing "cannot sell from property without max houses in group"
      (let [game-state (-> (c/init-game-state 2)
                         ;; Mediterranean monopoly with uneven distribution
                           (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 1})
                           (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 2}))]
      ;; Should not be able to sell from Mediterranean (doesn't have max houses)
        (is (thrown? Exception (u/apply-house-sale game-state :mediterranean-ave)))
      ;; Should be able to sell from Baltic (has max houses)
        (is (u/apply-house-sale game-state :baltic-ave)))))

  (deftest mortgage-property-with-houses-fails-test
    (testing "cannot mortgage property with houses"
      (let [game-state (-> (c/init-game-state 2)
                           (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 1}))]
        (is (thrown? Exception (u/apply-property-mortgage game-state :mediterranean-ave))))))

  (deftest unmortgage-insufficient-funds-fails-test
    (testing "cannot unmortgage without sufficient funds"
      (let [game-state (-> (c/init-game-state 2)
                           (assoc-in [:players 0 :properties :mediterranean-ave] {:status :mortgaged :house-count 0})
                           (assoc-in [:players 0 :cash] 10))] ; Not enough for 110% of $30 mortgage
        (is (thrown? Exception (u/apply-property-unmortgage game-state :mediterranean-ave))))))

  (deftest sell-house-from-unowned-property-fails-test
    (testing "cannot sell house from unowned property"
      (let [game-state (c/init-game-state 2)] ; Fresh game, no properties owned
        (is (thrown? Exception (u/apply-house-sale game-state :mediterranean-ave))))))

;; ======= Game State Consistency Tests ===================

  (deftest house-sale-updates-inventory-test
    (testing "house sale updates house count and cash correctly"
      (let [initial-state (-> (c/init-game-state 2)
                              (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 3})
                              (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 3})
                              (assoc-in [:players 0 :cash] 1000))
            result-state (u/apply-house-sale initial-state :mediterranean-ave)
            player (u/current-player result-state)]
      ;; House count decreases by 1
        (is (= 2 (get-in player [:properties :mediterranean-ave :house-count])))
      ;; Cash increases by 50% of house cost ($50 house -> $25 proceeds)
        (is (= 1025 (:cash player)))
      ;; Transaction should record the sale
        (let [tx (first (filter #(= :sell-house (:type %)) (:transactions result-state)))]
          (is (= :mediterranean-ave (:property tx)))
          (is (= "A" (:player tx)))
          (is (= 25 (:proceeds tx)))))))

  (deftest transaction-logging-for-new-actions-test
    (testing "all new actions create proper transaction records"
      (let [initial-state (-> (c/init-game-state 2)
                              (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                              (assoc-in [:players 0 :cash] 1000))
          ;; Test house sale transaction
            after-sale (u/apply-house-sale initial-state :mediterranean-ave)
            sale-tx (first (filter #(= :sell-house (:type %)) (:transactions after-sale)))

          ;; Test mortgage transaction
            after-mortgage (u/apply-property-mortgage initial-state :mediterranean-ave)
            mortgage-tx (first (filter #(= :mortgage-property (:type %)) (:transactions after-mortgage)))

          ;; Test unmortgage transaction (need to start with mortgaged property)
            mortgaged-state (assoc-in initial-state [:players 0 :properties :mediterranean-ave :status] :mortgaged)
            after-unmortgage (u/apply-property-unmortgage mortgaged-state :mediterranean-ave)
            unmortgage-tx (first (filter #(= :unmortgage-property (:type %)) (:transactions after-unmortgage)))]

      ;; Validate house sale transaction
        (is (= :sell-house (:type sale-tx)))
        (is (= "A" (:player sale-tx)))
        (is (= :mediterranean-ave (:property sale-tx)))
        (is (= 25 (:proceeds sale-tx)))

      ;; Validate mortgage transaction
        (is (= :mortgage-property (:type mortgage-tx)))
        (is (= "A" (:player mortgage-tx)))
        (is (= :mediterranean-ave (:property mortgage-tx)))
        (is (= 30 (:proceeds mortgage-tx)))

      ;; Validate unmortgage transaction
        (is (= :unmortgage-property (:type unmortgage-tx)))
        (is (= "A" (:player unmortgage-tx)))
        (is (= :mediterranean-ave (:property unmortgage-tx)))
        (is (= 33 (:cost unmortgage-tx)))))))
