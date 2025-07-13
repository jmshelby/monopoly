(ns jmshelby.monopoly.auction-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.util :as util]
            [jmshelby.monopoly.player :as player]
            [jmshelby.monopoly.players.dumb :as dumb]))

(defn test-player-function
  "Test player function that makes predictable decisions for testing"
  [game-state player-id method params]
  (let [player (util/player-by-id game-state player-id)]
    (case method
      ;; Always bid up to a specific amount based on player ID
      :auction-bid
      (let [{:keys [required-bid]} params
            max-bid (case player-id
                      "A" 150  ; Player A bids up to $150
                      "B" 250  ; Player B bids up to $250  
                      "C" 100  ; Player C bids up to $100
                      50)]     ; Default $50
        (if (and (<= required-bid max-bid)
                 (>= (:cash player) (+ required-bid 50))) ; Keep $50 reserve
          {:action :bid :bid required-bid}
          {:action :decline}))
      
      ;; Simple property purchase logic
      :property-option
      {:action :buy}
      
      ;; Default actions for other methods
      :take-turn {:action :done}
      :trade-proposal {:action :decline}
      :raise-funds {:action :mortgage-property :property-name "boardwalk"})))

(defn create-test-game-state
  "Create a game state with test players for auction testing"
  []
  (-> (core/init-game-state 3)
      ;; Replace default AI with test players
      (assoc-in [:players 0 :function] test-player-function)
      (assoc-in [:players 1 :function] test-player-function)
      (assoc-in [:players 2 :function] test-player-function)))

(deftest test-auction-player-context
  "Test that auction bidders use their own cash, not current player's cash"
  (testing "Each bidder uses correct player context during auctions"
    (let [;; Create game with different cash amounts per player
          gs (-> (create-test-game-state)
                 (assoc-in [:players 0 :cash] 1000) ; Player A: $1000
                 (assoc-in [:players 1 :cash] 500)  ; Player B: $500  
                 (assoc-in [:players 2 :cash] 100)  ; Player C: $100
                 (assoc-in [:current-turn :player] "A")) ; A is current player
          
          ;; Run auction for a property
          result (util/apply-auction-property-workflow gs "boardwalk")
          transactions (:transactions result)
          auction-txs (filter #(= :auction-initiated (:type %)) transactions)
          completion-txs (filter #(= :auction-completed (:type %)) transactions)]
      
      ;; Verify auction was initiated
      (is (= 1 (count auction-txs)))
      (is (= "boardwalk" (:property (first auction-txs))))
      
      ;; Verify auction completed (someone should win)
      (is (= 1 (count completion-txs)))
      
      ;; Player B should win (has $250 max bid and $500 cash)
      ;; Player A would bid higher but auction logic should use each player's actual cash
      (let [completion-tx (first completion-txs)]
        (is (= "B" (:winner completion-tx)))
        (is (<= (:winning-bid completion-tx) 250))))))

(deftest test-bankruptcy-auction-workflow
  "Test that bankruptcy auctions work correctly"
  (testing "Properties are auctioned when player goes bankrupt to bank"
    (let [;; Create game state with a player who has properties but no cash
          gs (-> (create-test-game-state)
                 (assoc-in [:players 0 :cash] 0)
                 (assoc-in [:players 0 :properties] 
                           {:park-place {:status :paid :house-count 0}
                            :boardwalk {:status :paid :house-count 0}})
                 (assoc-in [:players 1 :cash] 1000)
                 (assoc-in [:players 2 :cash] 800))
          
          player-a (util/player-by-id gs "A")
          
          ;; Trigger bankruptcy auction
          result (#'player/auction-bankrupt-properties gs player-a)
          transactions (:transactions result)
          auction-initiated-txs (filter #(= :auction-initiated (:type %)) transactions)]
      
      ;; Should have 2 auction initiations (for 2 properties)
      (is (= 2 (count auction-initiated-txs)))
      
      ;; Verify properties are being auctioned
      (let [auctioned-props (set (map :property auction-initiated-txs))]
        (is (= #{:park-place :boardwalk} auctioned-props)))
      
      ;; Verify bankrupt player is excluded from bidding
      (doseq [auction-tx auction-initiated-txs]
        (is (not (some #(= "A" %) (:eligible-bidders auction-tx))))
        (is (= 2 (:participant-count auction-tx)))))))

(deftest test-bankruptcy-to-bank-full-workflow
  "Test complete bankruptcy to bank workflow with property auctions"
  (testing "Complete bankruptcy workflow auctions properties and records transactions"
    (let [;; Create game state with bankrupt player
          gs (-> (create-test-game-state)
                 (assoc-in [:players 0 :cash] 50)
                 (assoc-in [:players 0 :properties] 
                           {:oriental-ave {:status :paid :house-count 0}})
                 (assoc-in [:players 1 :cash] 800)
                 (assoc-in [:players 2 :cash] 600))
          
          player-a (util/player-by-id gs "A")
          
          ;; Run full bankruptcy workflow
          result (#'player/bankrupt-to-bank gs player-a)
          transactions (:transactions result)
          
          auction-txs (filter #(= :auction-initiated (:type %)) transactions)
          bankruptcy-txs (filter #(= :bankruptcy (:type %)) transactions)]
      
      ;; Should have auction for the property
      (is (= 1 (count auction-txs)))
      (is (= :oriental-ave (:property (first auction-txs))))
      
      ;; Should have bankruptcy transaction
      (is (= 1 (count bankruptcy-txs)))
      (let [bankruptcy-tx (first bankruptcy-txs)]
        (is (= "A" (:player bankruptcy-tx)))
        (is (= :bank (:to bankruptcy-tx)))
        (is (= 50 (:cash bankruptcy-tx)))
        (is (= {:oriental-ave {:status :paid :house-count 0}} (:properties bankruptcy-tx)))))))

(deftest test-auction-bidding-validation
  "Test auction bidding validation and error cases"
  (testing "Auction properly validates bids and handles insufficient cash"
    (let [gs (-> (create-test-game-state)
                 (assoc-in [:players 0 :cash] 50)   ; Low cash
                 (assoc-in [:players 1 :cash] 1000) ; High cash
                 (assoc-in [:players 2 :cash] 200)) ; Medium cash
          
          ;; Run auction - players should bid according to their cash
          result (util/apply-auction-property-workflow gs "boardwalk")
          transactions (:transactions result)
          completion-txs (filter #(= :auction-completed (:type %)) transactions)]
      
      ;; Auction should complete
      (is (= 1 (count completion-txs)))
      
      ;; Player B should win (has most cash and highest max bid)
      (let [completion-tx (first completion-txs)]
        (is (= "B" (:winner completion-tx)))
        ;; Winning bid should be reasonable given Player B's max bid of $250
        (is (<= (:winning-bid completion-tx) 250))))))

(deftest test-auction-player-exclusion
  "Test that specific players can be excluded from auctions"
  (testing "Bankrupt players are properly excluded from auction participation"
    (let [gs (-> (create-test-game-state)
                 ;; Set player A as bankrupt
                 (assoc-in [:players 0 :status] :bankrupt)
                 (assoc-in [:players 1 :cash] 500)
                 (assoc-in [:players 2 :cash] 300))
          
          ;; Run auction
          result (util/apply-auction-property-workflow gs "park-place")
          transactions (:transactions result)
          auction-txs (filter #(= :auction-initiated (:type %)) transactions)]
      
      ;; Verify auction was initiated
      (is (= 1 (count auction-txs)))
      
      ;; Verify bankrupt player A is not in eligible bidders
      (let [auction-tx (first auction-txs)]
        (is (not (some #(= "A" %) (:eligible-bidders auction-tx))))
        (is (= 2 (:participant-count auction-tx)))))))

(deftest test-auction-no-bidders
  "Test auction behavior when no players can or want to bid"
  (testing "Auction passes when no eligible bidders"
    (let [gs (-> (create-test-game-state)
                 ;; Give all players very little cash
                 (assoc-in [:players 0 :cash] 10)
                 (assoc-in [:players 1 :cash] 20)
                 (assoc-in [:players 2 :cash] 15))
          
          ;; Run auction for expensive property
          result (util/apply-auction-property-workflow gs "boardwalk")
          transactions (:transactions result)
          passed-txs (filter #(= :auction-passed (:type %)) transactions)]
      
      ;; Should have auction passed transaction
      (is (= 1 (count passed-txs)))
      (is (= "boardwalk" (:property (first passed-txs)))))))

(deftest test-multiple-property-bankruptcy-auction
  "Test bankruptcy auction with multiple properties"
  (testing "Multiple properties are auctioned sequentially during bankruptcy"
    (let [gs (-> (create-test-game-state)
                 (assoc-in [:players 0 :properties] 
                           {:oriental-ave {:status :paid :house-count 0}
                            :vermont-ave {:status :paid :house-count 0}
                            :connecticut-ave {:status :paid :house-count 0}})
                 (assoc-in [:players 1 :cash] 800)
                 (assoc-in [:players 2 :cash] 600))
          
          player-a (util/player-by-id gs "A")
          
          ;; Run bankruptcy auction
          result (#'player/auction-bankrupt-properties gs player-a)
          transactions (:transactions result)
          auction-txs (filter #(= :auction-initiated (:type %)) transactions)]
      
      ;; Should have 3 auctions for 3 properties
      (is (= 3 (count auction-txs)))
      
      ;; Verify all properties are auctioned
      (let [auctioned-props (set (map :property auction-txs))]
        (is (= #{:oriental-ave :vermont-ave :connecticut-ave} auctioned-props)))
      
      ;; Verify Player A is excluded from all auctions
      (doseq [auction-tx auction-txs]
        (is (not (some #(= "A" %) (:eligible-bidders auction-tx))))))))

(deftest test-bankruptcy-transaction-ordering
  "Test that bankruptcy transaction comes before auction transactions"
  (testing "Bankruptcy tx is recorded first, then auctions with proper linking"
    (let [gs (-> (create-test-game-state)
                 (assoc-in [:players 0 :properties] 
                          {:oriental-ave {:status :paid :house-count 0}
                           :vermont-ave {:status :paid :house-count 0}})
                 (assoc-in [:players 0 :cash] 25)
                 (assoc-in [:players 1 :cash] 800)
                 (assoc-in [:players 2 :cash] 600))
          
          player-a (util/player-by-id gs "A")
          
          ;; Run full bankruptcy workflow
          result (#'player/bankrupt-to-bank gs player-a)
          transactions (:transactions result)
          
          bankruptcy-txs (filter #(= :bankruptcy (:type %)) transactions)
          auction-initiated-txs (filter #(= :auction-initiated (:type %)) transactions)
          auction-completed-txs (filter #(= :auction-completed (:type %)) transactions)]
      
      ;; Should have exactly 1 bankruptcy transaction
      (is (= 1 (count bankruptcy-txs)))
      
      ;; Should have 2 auction initiations (for 2 properties)
      (is (= 2 (count auction-initiated-txs)))
      
      ;; Bankruptcy transaction should be FIRST
      (is (= :bankruptcy (:type (first transactions))))
      
      ;; All auction transactions should have bankruptcy context
      (doseq [auction-tx (concat auction-initiated-txs auction-completed-txs)]
        (is (true? (:bankruptcy-driven auction-tx))))
      
      ;; Verify transaction order: bankruptcy first, then auctions
      (let [tx-types (map :type transactions)]
        (is (= :bankruptcy (first tx-types)))
        (is (every? #{:auction-initiated :auction-completed} (rest tx-types)))))))