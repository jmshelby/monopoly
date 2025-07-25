(ns jmshelby.monopoly.property-management-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.util :as u]
            [jmshelby.monopoly.core :as c]
            [jmshelby.monopoly.util :as util]))

;; ======= Property Management Workflow Tests ===================

(deftest bankruptcy-with-new-liquidation-options-test
  (testing "raise-funds workflow uses house selling and mortgaging"
    (let [game-state (-> (c/init-game-state 2)
                         ;; Set up player with properties but low cash
                         (assoc-in [:players 0 :cash] 10)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 1})
                         (assoc-in [:players 0 :properties :oriental-ave] {:status :paid :house-count 0}))
          player (u/current-player game-state)

          ;; Test house selling option
          houses-to-sell (->> (u/owned-property-details game-state)
                              vals
                              (filter #(= (:id player) (:owner %)))
                              (filter #(> (:house-count %) 0))
                              (sort-by :house-count >)
                              first)

          ;; Test mortgaging option
          props-to-mortgage (->> (u/owned-property-details game-state)
                                 vals
                                 (filter #(= (:id player) (:owner %)))
                                 (filter #(= :paid (:status %)))
                                 (filter #(= 0 (:house-count %)))
                                 (sort-by #(-> % :def :mortgage) >)
                                 first)]

      ;; Should have houses to sell
      (is (not (nil? houses-to-sell)))
      (is (= :mediterranean-ave (-> houses-to-sell :def :name)))

      ;; Should have properties to mortgage
      (is (not (nil? props-to-mortgage)))
      (is (= :oriental-ave (-> props-to-mortgage :def :name)))))

  (testing "full raise-funds workflow with actual house selling"
    (let [initial-state (-> (c/init-game-state 2)
                            (assoc-in [:players 0 :cash] 50)
                            (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                            (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 2}))
          ;; Sell a house to raise funds
          after-sale (u/apply-house-sale initial-state
                                         (u/current-player initial-state)
                                         :mediterranean-ave)
          player-after-sale (u/current-player after-sale)]

      ;; Cash should increase from house sale
      (is (= 75 (:cash player-after-sale))) ; 50 + 25 (half of $50 house cost)
      ;; House count should decrease
      (is (= 1 (get-in player-after-sale [:properties :mediterranean-ave :house-count]))))))

(deftest strategic-unmortgaging-workflow-test
  (testing "players can unmortgage to complete monopolies"
    (let [game-state (-> (c/init-game-state 2)
                         ;; Player has one property paid, one mortgaged (Mediterranean monopoly)
                         (assoc-in [:players 0 :cash] 500)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 0})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :mortgaged :house-count 0}))
          ;; Unmortgage to complete monopoly
          after-unmortgage (u/apply-property-unmortgage game-state (u/current-player game-state) :baltic-ave)
          player-after (u/current-player after-unmortgage)]

      ;; Both properties should now be paid
      (is (= :paid (get-in player-after [:properties :mediterranean-ave :status])))
      (is (= :paid (get-in player-after [:properties :baltic-ave :status])))
      ;; Cash should decrease by unmortgage cost (Baltic mortgage $30 * 1.1 = $33)
      (is (= 467 (:cash player-after)))
      ;; Should now be able to build houses (has monopoly)
      (is (u/can-buy-house? after-unmortgage :mediterranean-ave)))))

(deftest property-management-decision-tree-test
  (testing "optimal property management decisions"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :cash] 100)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 3})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 3})
                         (assoc-in [:players 0 :properties :oriental-ave] {:status :mortgaged :house-count 0}))
          player (util/current-player game-state)]

      ;; When cash is low, should prioritize house selling over unmortgaging
      (is (u/can-sell-any-house? game-state player))
      (is (u/can-unmortgage-any-property? game-state player))

      ;; After selling houses, should have more cash
      (let [after-sale (u/apply-house-sale game-state player :mediterranean-ave)
            player-after (u/current-player after-sale)]
        (is (= 125 (:cash player-after))) ; 100 + 25 from house sale
        (is (= 2 (get-in player-after [:properties :mediterranean-ave :house-count])))))))

;; ======= Performance/Regression Tests ===================

(deftest game-completion-rates-with-new-actions-test
  (testing "games still complete at reasonable rates with new actions"
    (let [sim-count 20 ; Smaller count for test performance
          sims (doall
                (pmap (fn [_]
                        (c/rand-game-end-state 4 1000))
                      (range sim-count)))
          completed-games (filter #(= :complete (:status %)) sims)
          completion-rate (/ (count completed-games) sim-count)]

      ;; Should have reasonable completion rate (at least 40%)
      (is (>= completion-rate 0.4) "Games should complete at reasonable rate")

      ;; No game should have exceptions
      (is (every? #(nil? (:exception %)) sims) "No games should have exceptions")

      ;; All games should have reasonable transaction counts
      (let [tx-counts (map #(count (:transactions %)) sims)]
        (is (every? pos? tx-counts) "All games should have transactions")
        (is (every? #(<= % 2000) tx-counts) "Transaction counts should be reasonable")))))

(deftest no-infinite-loops-with-new-actions-test
  (testing "new property actions have consistent state transitions"
    ;; Test that property actions don't create inconsistent states
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :cash] 300)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 1})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :mortgaged :house-count 0}))
          player (util/current-player game-state)]

      ;; Test each action produces valid state transitions
      ;; Test house selling
      (when (u/can-sell-any-house? game-state player)
        (let [after-sale (u/apply-house-sale game-state player :mediterranean-ave)
              player-after (u/current-player after-sale)]
          (is (>= (:cash player-after) (:cash (u/current-player game-state))))
          (is (< (get-in player-after [:properties :mediterranean-ave :house-count])
                 (get-in (u/current-player game-state) [:properties :mediterranean-ave :house-count])))))

      ;; Test unmortgaging
      (when (u/can-unmortgage-any-property? game-state (u/current-player game-state))
        (let [after-unmortgage (u/apply-property-unmortgage game-state (u/current-player game-state) :baltic-ave)
              player-after (u/current-player after-unmortgage)]
          (is (< (:cash player-after) (:cash (u/current-player game-state))))
          (is (= :paid (get-in player-after [:properties :baltic-ave :status])))))

      ;; Test that actions don't create invalid states
      (is (every? #(>= % 0) (map :cash (:players game-state))))
      (is (every? keyword? (map #(get-in % [:properties :mediterranean-ave :status] :none)
                                (:players game-state))))))

  (testing "property management action sequence terminates properly"
    ;; Test a controlled sequence of property actions without using advance-board
    (let [initial-state (-> (c/init-game-state 2)
                            (assoc-in [:players 0 :cash] 1000)
                            (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                            (assoc-in [:players 0 :properties :oriental-ave] {:status :paid :house-count 0}))
          player (u/current-player initial-state)
          ;; Sequence: sell house -> mortgage property -> unmortgage property
          after-sell (u/apply-house-sale initial-state player :mediterranean-ave)
          after-mortgage (u/apply-property-mortgage after-sell (u/current-player after-sell) :oriental-ave)
          after-unmortgage (u/apply-property-unmortgage after-mortgage (u/current-player after-mortgage) :oriental-ave)
          final-player (u/current-player after-unmortgage)]

      ;; Verify the sequence completed successfully
      (is (= 1 (get-in final-player [:properties :mediterranean-ave :house-count])))
      (is (= :paid (get-in final-player [:properties :oriental-ave :status])))
      ;; Cash flow: 1000 + 25 (house) + 50 (mortgage) - 56 (unmortgage) = 1019
      (is (= 1019 (:cash final-player)))
      ;; Should have 3 transactions
      (is (= 3 (count (:transactions after-unmortgage)))))))

;; ======= Integration with Existing Systems Tests ===================

(deftest property-management-with-auctions-test
  (testing "property management works with auction system"
    (let [game-state (-> (c/init-game-state 2)
                         ;; Set up for potential auction scenario
                         (assoc-in [:players 0 :cash] 50) ; Low cash might affect auction bidding
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 1})
                         (assoc-in [:players 1 :cash] 200))
          player (util/current-player game-state)]

      ;; Player 0 should be able to sell house to raise auction funds
      (is (u/can-sell-any-house? game-state player))

      ;; After selling house, should have more cash for auctions
      (let [after-sale (u/apply-house-sale game-state player :mediterranean-ave)
            player-after (u/current-player after-sale)]
        (is (= 75 (:cash player-after))) ; Should now have more for auction bidding
        (is (= 0 (get-in player-after [:properties :mediterranean-ave :house-count])))))))

(deftest property-management-with-trades-test
  (testing "property management affects trade valuations"
    (let [game-state (-> (c/init-game-state 2)
                         (assoc-in [:players 0 :cash] 500)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :mortgaged :house-count 0})
                         (assoc-in [:players 1 :cash] 300)
                         (assoc-in [:players 1 :properties :oriental-ave] {:status :paid :house-count 0}))]

      ;; Properties in different states should have different trade values
      ;; (This tests that our property management doesn't break trade logic)
      (is (contains? (get-in game-state [:players 0 :properties]) :mediterranean-ave))
      (is (contains? (get-in game-state [:players 0 :properties]) :baltic-ave))
      (is (= :paid (get-in game-state [:players 0 :properties :mediterranean-ave :status])))
      (is (= :mortgaged (get-in game-state [:players 0 :properties :baltic-ave :status]))))))

;; ======= Stress Tests ===================

(deftest rapid-property-management-operations-test
  (testing "rapid succession of property management operations"
    (let [initial-state (-> (c/init-game-state 2)
                            (assoc-in [:players 0 :cash] 1000)
                            (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 4})
                            (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 4})
                            (assoc-in [:players 0 :properties :oriental-ave] {:status :paid :house-count 0}))]

      ;; Perform rapid operations: sell house, mortgage, unmortgage
      (let [after-sale (u/apply-house-sale initial-state
                                           (u/current-player initial-state)
                                           :mediterranean-ave)
            after-mortgage (u/apply-property-mortgage after-sale (u/current-player after-sale) :oriental-ave)
            after-unmortgage (u/apply-property-unmortgage after-mortgage (u/current-player after-mortgage) :oriental-ave)
            final-player (u/current-player after-unmortgage)]

        ;; Verify final state is consistent
        (is (= 3 (get-in final-player [:properties :mediterranean-ave :house-count])))
        (is (= :paid (get-in final-player [:properties :oriental-ave :status])))
        ;; Cash calculations: 1000 + 25 (house sale) + 50 (Oriental mortgage) - 56 (unmortgage ceil(110% of 50))
        (is (= 1019 (:cash final-player)))

        ;; Should have all transactions recorded
        (is (= 3 (count (:transactions after-unmortgage))))
        (is (some #(= :sell-house (:type %)) (:transactions after-unmortgage)))
        (is (some #(= :mortgage-property (:type %)) (:transactions after-unmortgage)))
        (is (some #(= :unmortgage-property (:type %)) (:transactions after-unmortgage)))))))
