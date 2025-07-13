(ns jmshelby.monopoly.player-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.player :as player]
            [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.util :as util]
            [jmshelby.monopoly.players.dumb :as dumb]))

(deftest test-bankruptcy-workflow
  "Test bankruptcy workflow integration"
  (testing "Bankruptcy to bank processes correctly"
    ;; This is more of an integration test to ensure the private functions work together
    (let [game-state (-> (core/init-game-state 2)
                         (assoc-in [:players 0 :cash] 0)
                         (assoc-in [:players 0 :properties] 
                                  {:boardwalk {:status :paid :house-count 0}}))
          player-a (util/player-by-id game-state "A")
          ;; Test the private bankruptcy function
          result (#'player/bankrupt-to-bank game-state player-a)
          transactions (:transactions result)]
      
      ;; Should have at least bankruptcy transaction
      (is (some #(= :bankruptcy (:type %)) transactions))
      
      ;; Should have auction transaction  
      (is (some #(= :auction-initiated (:type %)) transactions)))))

(deftest test-player-decision-interface
  "Test that player decision functions receive correct player context"
  (testing "Player decision function gets correct player-id parameter"
    (let [test-calls (atom [])
          test-player-fn (fn [game-state player-id method params]
                          ;; Record the call for verification
                          (swap! test-calls conj {:player-id player-id :method method})
                          ;; Return simple decisions
                          (case method
                            :auction-bid {:action :decline}
                            :property-option {:action :buy}
                            :take-turn {:action :done}
                            :trade-proposal {:action :decline}
                            :raise-funds {:action :mortgage-property :property-name "boardwalk"}))
          
          game-state (-> (core/init-game-state 3)
                        ;; Replace all players with test function
                        (assoc-in [:players 0 :function] test-player-fn)
                        (assoc-in [:players 1 :function] test-player-fn)
                        (assoc-in [:players 2 :function] test-player-fn))]
      
      ;; Test auction call - should call each player with their own ID
      (util/apply-auction-property-workflow game-state "boardwalk")
      
      ;; Verify each player was called with correct player-id
      (let [auction-calls (filter #(= :auction-bid (:method %)) @test-calls)
            called-player-ids (set (map :player-id auction-calls))]
        ;; Should have called all 3 players for auction bidding
        (is (= #{"A" "B" "C"} called-player-ids))
        (is (= 3 (count auction-calls)))))))

(deftest test-player-context-in-auctions
  "Test that auction bidders use their own financial context"
  (testing "Each player bids based on their own cash, not current player's cash"
    (let [bidding-decisions (atom {})
          context-aware-player (fn [game-state player-id method params]
                                ;; Record what cash this player thinks they have
                                (let [player (util/player-by-id game-state player-id)]
                                  (when (= method :auction-bid)
                                    (swap! bidding-decisions assoc player-id (:cash player)))
                                  ;; Simple bidding logic - only bid if we can afford it
                                  (case method
                                    :auction-bid (if (and (> (:cash player) 100)
                                                         (>= (:cash player) (:required-bid params)))
                                                  {:action :bid :bid (:required-bid params)}
                                                  {:action :decline})
                                    {:action :done})))
          
          game-state (-> (core/init-game-state 3)
                        ;; Set different cash amounts
                        (assoc-in [:players 0 :cash] 500) ; Player A
                        (assoc-in [:players 1 :cash] 200) ; Player B  
                        (assoc-in [:players 2 :cash] 50)  ; Player C
                        ;; Set current turn to Player A
                        (assoc-in [:current-turn :player] "A")
                        ;; Use context-aware player function
                        (assoc-in [:players 0 :function] context-aware-player)
                        (assoc-in [:players 1 :function] context-aware-player)
                        (assoc-in [:players 2 :function] context-aware-player))]
      
      ;; Run auction
      (util/apply-auction-property-workflow game-state "park-place")
      
      ;; Verify each player used their own cash amount, not Player A's $500
      (is (= 500 (@bidding-decisions "A")))
      (is (= 200 (@bidding-decisions "B"))) 
      (is (= 50 (@bidding-decisions "C")))
      
      ;; All three players should have been called
      (is (= 3 (count @bidding-decisions))))))

(deftest test-bankruptcy-auction-player-exclusion
  "Test that bankrupt players are excluded from their own property auctions"
  (testing "Bankrupt player cannot bid on their own properties"
    (let [bid-attempts (atom #{})
          tracking-player (fn [game-state player-id method params]
                           (when (= method :auction-bid)
                             (swap! bid-attempts conj player-id))
                           {:action :decline})
          
          game-state (-> (core/init-game-state 3)
                        ;; Set up properties for Player A
                        (assoc-in [:players 0 :properties] 
                                 {:boardwalk {:status :paid :house-count 0}
                                  :park-place {:status :paid :house-count 0}})
                        (assoc-in [:players 0 :cash] 0)
                        ;; Use tracking function for all players
                        (assoc-in [:players 0 :function] tracking-player)
                        (assoc-in [:players 1 :function] tracking-player)
                        (assoc-in [:players 2 :function] tracking-player))
          
          player-a (util/player-by-id game-state "A")]
      
      ;; Run bankruptcy auction
      (#'player/auction-bankrupt-properties game-state player-a "test-bankruptcy-123")
      
      ;; Player A should not have been called for auction bidding
      ;; (they're excluded because they're bankrupt)
      (is (not (contains? @bid-attempts "A")))
      
      ;; Players B and C should have been called
      (is (contains? @bid-attempts "B"))
      (is (contains? @bid-attempts "C")))))