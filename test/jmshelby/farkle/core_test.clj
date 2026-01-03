(ns jmshelby.farkle.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [jmshelby.farkle.core :as core]
            [jmshelby.farkle.util :as util]))

(deftest init-game-state-test
  (testing "Initialize game state"
    (let [state (core/init-game-state 4)]
      (is (= 4 (count (:players state))))
      (is (= :player-1 (get-in state [:current-turn :player])))
      (is (= 0 (get-in state [:current-turn :turn-score])))
      (is (= 6 (get-in state [:current-turn :available-dice])))
      (is (= :playing (:status state))))))

(deftest apply-score-dice-test
  (testing "Score dice and update state"
    (let [initial (core/init-game-state 2)
          ;; Simulate a roll first
          with-roll (core/apply-roll initial)
          ;; Score some dice (1s are worth 100 each)
          scored (core/apply-score-dice with-roll [1 1])]
      (is (>= (get-in scored [:current-turn :turn-score]) 100))
      (is (< (get-in scored [:current-turn :available-dice]) 6)))))

(deftest apply-bank-test
  (testing "Bank turn score"
    (let [initial (core/init-game-state 2)
          ;; Set up a turn score
          with-score (-> initial
                         (assoc-in [:current-turn :turn-score] 600))
          banked (core/apply-bank with-score)]
      ;; Should advance to next player
      (is (not= :player-1 (get-in banked [:current-turn :player])))
      ;; First player should have the score
      (is (= 600 (get-in banked [:players 0 :total-score]))))))

(deftest apply-farkle-test
  (testing "Farkle loses turn score"
    (let [initial (core/init-game-state 2)
          with-score (-> initial
                         (assoc-in [:current-turn :turn-score] 500))
          farkled (core/apply-farkle with-score)]
      ;; Should advance to next player
      (is (not= :player-1 (get-in farkled [:current-turn :player])))
      ;; Turn score should be reset
      (is (= 0 (get-in farkled [:current-turn :turn-score])))
      ;; Player score should be unchanged
      (is (= 0 (get-in farkled [:players 0 :total-score]))))))

(deftest rand-game-state-test
  (testing "Run game for specific iterations"
    (let [state (core/rand-game-state 2 10)]
      (is (some? state))
      (is (>= (count (:transactions state)) 1)))))

(deftest rand-game-end-state-test
  (testing "Run complete game"
    (let [state (core/rand-game-end-state 2 1000)]
      (is (some? state))
      (is (contains? #{:completed :failsafe :exception} (:status state))))))
