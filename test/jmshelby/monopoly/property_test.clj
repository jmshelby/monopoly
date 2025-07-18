(ns jmshelby.monopoly.property-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.property :as property]
            [jmshelby.monopoly.util :as util]))

(defn test-player-fn
  "Test player function that returns predetermined decisions for mortgaged acquisitions."
  [decisions]
  (fn [_game-state _player-id method params]
    (case method
      :acquisition decisions
      ;; Default responses for other methods
      :property-option {:action :buy}
      :auction-bid {:action :decline}
      :take-turn {:action :done})))

(deftest mortgaged-property-transfer-immediate-unmortgage
  (testing "Player chooses immediate unmortgage for acquired mortgaged property"
    (let [initial-state (core/init-game-state 2)
          ;; Set up player 0 with mortgaged property
          player0-with-mortgaged (-> initial-state
                                    (assoc-in [:players 0 :properties :reading-railroad]
                                             {:status :mortgaged :house-count 0})
                                    (assoc-in [:players 0 :cash] 1000))
          ;; Set up player 1 with enough cash and decision function
          player1-with-cash (-> player0-with-mortgaged
                               (assoc-in [:players 1 :cash] 1500)
                               (assoc-in [:players 1 :function]
                                        (test-player-fn {:reading-railroad :pay-mortgage})))

          ;; Transfer mortgaged property from player 0 to player 1
          result-state (property/transfer player1-with-cash
                                         (util/player-by-id player1-with-cash "A")
                                         (util/player-by-id player1-with-cash "B")
                                         [:reading-railroad])
          ;; Check results
          player1-final (get-in result-state [:players 1])
          player1-property (get-in player1-final [:properties :reading-railroad])]
      ;; Property should be transferred and unmortgaged
      (is (= :paid (:status player1-property)) "Property should be unmortgaged")
      (is (nil? (:deferred-interest player1-property)) "Should not have deferred interest flag")

      ;; Player 1 should have paid 110% of mortgage value (rounded up to 111)
      (is (= 1389 (:cash player1-final)) "Player should have paid $111 for immediate unmortgage")
      ;; Should have transaction record
      (let [acquisition-tx (->> result-state :transactions
                               (filter #(= :mortgaged-acquisition (:type %)))
                               last)]
        (is (some? acquisition-tx) "Should have mortgaged acquisition transaction")
        (is (= :immediate-unmortgage (:choice acquisition-tx)) "Should record immediate unmortgage choice")
        (is (= 111 (:amount acquisition-tx)) "Should record correct payment amount")))))

(deftest mortgaged-property-transfer-deferred-unmortgage
  (testing "Player chooses deferred unmortgage for acquired mortgaged property"
    (let [initial-state (core/init-game-state 2)
          ;; Set up player 0 with mortgaged property
          player0-with-mortgaged (-> initial-state
                                    (assoc-in [:players 0 :properties :reading-railroad]
                                             {:status :mortgaged :house-count 0})
                                    (assoc-in [:players 0 :cash] 1000))
          ;; Set up player 1 with decision function to pay interest
          player1-with-cash (-> player0-with-mortgaged
                               (assoc-in [:players 1 :cash] 500)
                               (assoc-in [:players 1 :function]
                                        (test-player-fn {:reading-railroad :pay-interest})))

          ;; Transfer mortgaged property from player 0 to player 1
          result-state (property/transfer player1-with-cash
                                         (util/player-by-id player1-with-cash "A")
                                         (util/player-by-id player1-with-cash "B")
                                         [:reading-railroad])
          ;; Check results
          player1-final (get-in result-state [:players 1])
          player1-property (get-in player1-final [:properties :reading-railroad])]
      ;; Property should be transferred but remain mortgaged with deferred interest
      (is (= :mortgaged (:status player1-property)) "Property should remain mortgaged")
      (is (true? (:deferred-interest player1-property)) "Should have deferred interest flag")

      ;; Player 1 should have paid 10% of mortgage value (10% of 100 = 10)
      (is (= 490 (:cash player1-final)) "Player should have paid $10 for deferred unmortgage")
      ;; Should have transaction record
      (let [acquisition-tx (->> result-state :transactions
                               (filter #(= :mortgaged-acquisition (:type %)))
                               last)]
        (is (some? acquisition-tx) "Should have mortgaged acquisition transaction")
        (is (= :deferred-unmortgage (:choice acquisition-tx)) "Should record deferred unmortgage choice")
        (is (= 10 (:amount acquisition-tx)) "Should record correct payment amount")))))

(deftest multiple-mortgaged-properties-transfer
  (testing "Transfer multiple mortgaged properties with mixed decisions"
    (let [initial-state (core/init-game-state 2)
          ;; Set up player 0 with multiple mortgaged properties
          player0-with-props (-> initial-state
                                (assoc-in [:players 0 :properties :reading-railroad]
                                         {:status :mortgaged :house-count 0})
                                (assoc-in [:players 0 :properties :pennsylvania-railroad]
                                         {:status :mortgaged :house-count 0})
                                (assoc-in [:players 0 :cash] 1000))
          ;; Set up player 1 with mixed decisions
          player1-with-cash (-> player0-with-props
                               (assoc-in [:players 1 :cash] 1500)
                               (assoc-in [:players 1 :function]
                                        (test-player-fn {:reading-railroad :pay-mortgage
                                                        :pennsylvania-railroad :pay-interest})))

          ;; Transfer both mortgaged properties
          result-state (property/transfer player1-with-cash
                                         (util/player-by-id player1-with-cash "A")
                                         (util/player-by-id player1-with-cash "B")
                                         [:reading-railroad :pennsylvania-railroad])
          ;; Check results
          player1-final (get-in result-state [:players 1])
          reading-prop (get-in player1-final [:properties :reading-railroad])
          penn-prop (get-in player1-final [:properties :pennsylvania-railroad])]

      ;; Reading railroad should be unmortgaged, Pennsylvania should remain mortgaged
      (is (= :paid (:status reading-prop)) "Reading railroad should be unmortgaged")
      (is (= :mortgaged (:status penn-prop)) "Pennsylvania railroad should remain mortgaged")
      (is (true? (:deferred-interest penn-prop)) "Pennsylvania should have deferred interest")

      ;; Player should have paid 111 + 10 = 121 total
      (is (= 1379 (:cash player1-final)) "Player should have paid $121 total")

      ;; Should have two acquisition transactions
      (let [acquisition-txs (->> result-state :transactions
                                (filter #(= :mortgaged-acquisition (:type %))))]
        (is (= 2 (count acquisition-txs)) "Should have two acquisition transactions")))))

(deftest normal-property-transfer-no-workflow
  (testing "Transfer of non-mortgaged properties skips acquisition workflow"
    (let [initial-state (core/init-game-state 2)
          ;; Set up player 0 with paid property
          player0-with-props (-> initial-state
                                (assoc-in [:players 0 :properties :reading-railroad]
                                         {:status :paid :house-count 0})
                                (assoc-in [:players 0 :cash] 1000))
          player1 (assoc-in player0-with-props [:players 1 :cash] 1500)

          ;; Transfer paid property (should not trigger workflow)
          result-state (property/transfer player1
                                         (util/player-by-id player1 "A")
                                         (util/player-by-id player1 "B")
                                         [:reading-railroad])
          ;; Check results
          player1-final (get-in result-state [:players 1])
          player1-property (get-in player1-final [:properties :reading-railroad])]
      ;; Property should be transferred as-is
      (is (= :paid (:status player1-property)) "Property should remain paid")
      (is (nil? (:deferred-interest player1-property)) "Should not have deferred interest flag")

      ;; No cash should be deducted
      (is (= 1500 (:cash player1-final)) "Player cash should be unchanged")

      ;; No acquisition transactions
      (let [acquisition-txs (->> result-state :transactions
                                (filter #(= :mortgaged-acquisition (:type %))))]
        (is (zero? (count acquisition-txs)) "Should have no acquisition transactions")))))