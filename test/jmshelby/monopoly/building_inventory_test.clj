(ns jmshelby.monopoly.building-inventory-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.util :as util]))

(deftest test-initial-building-inventory
  (testing "Initial game state has correct building inventory limits"
    (let [game-state (core/init-game-state 4)]
      (is (= 32 (get-in game-state [:board :rules :building-limits :houses])))
      (is (= 12 (get-in game-state [:board :rules :building-limits :hotels])))
      (is (= 32 (util/houses-available game-state)))
      (is (= 12 (util/hotels-available game-state))))))

(deftest test-houses-and-hotels-in-play-calculation
  (testing "Correctly calculates houses and hotels in play"
    (let [game-state (-> (core/init-game-state 3)
                         (assoc-in [:players 0 :properties :mediterranean-ave] {:status :paid :house-count 2})
                         (assoc-in [:players 0 :properties :baltic-ave] {:status :paid :house-count 5})
                         (assoc-in [:players 1 :properties :oriental-ave] {:status :paid :house-count 3}))]
      (is (= 5 (util/houses-in-play game-state)))
      (is (= 1 (util/hotels-in-play game-state)))
      (is (= 27 (util/houses-available game-state)))
      (is (= 11 (util/hotels-available game-state))))))

(deftest test-building-inventory-prevents-purchase
  (testing "Cannot build house when inventory is exhausted"
    ;; Create a game state where many houses are in play to exhaust inventory
    (let [base-game-state (core/init-game-state 4)
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
      (is (<= (util/houses-available game-state) 0))
      (is (false? (util/can-buy-house? game-state :mediterranean-ave))))))