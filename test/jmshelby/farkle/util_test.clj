(ns jmshelby.farkle.util-test
  (:require [clojure.test :refer [deftest is testing]]
            [jmshelby.farkle.util :as util]))

(deftest dice-frequencies-test
  (testing "Dice frequency calculation"
    (is (= {1 2, 5 1, 3 1}
           (util/dice-frequencies [1 1 5 3])))
    (is (= {6 6}
           (util/dice-frequencies [6 6 6 6 6 6])))))

(deftest has-scoring-dice-test
  (testing "Detect scoring dice"
    (is (true? (util/has-scoring-dice? [1 2 3 4 5 6])) "Straight")
    (is (true? (util/has-scoring-dice? [1])) "Single 1")
    (is (true? (util/has-scoring-dice? [5])) "Single 5")
    (is (true? (util/has-scoring-dice? [2 2 2])) "Three 2s")
    (is (false? (util/has-scoring-dice? [2 3 4 6])) "No scoring dice")))

(deftest find-scoring-dice-test
  (testing "Find scoring combinations"
    (let [straight-options (util/find-scoring-dice [1 2 3 4 5 6])]
      (is (some #(= 3000 (:score %)) straight-options) "Finds straight"))

    (let [triple-options (util/find-scoring-dice [2 2 2 3 4 6])]
      (is (some #(= 200 (:score %)) triple-options) "Finds three 2s"))

    (let [ones-options (util/find-scoring-dice [1 1 3 4 6 2])]
      (is (some #(= 200 (:score %)) ones-options) "Finds two 1s"))))

(deftest max-possible-score-test
  (testing "Calculate maximum score from dice"
    (is (= 3000 (util/max-possible-score [1 2 3 4 5 6])) "Straight")
    (is (= 1000 (util/max-possible-score [1 1 1 2 3 4])) "Three 1s")
    (is (>= (util/max-possible-score [1 1 5 5 2 3]) 300) "Two 1s and two 5s")))

(deftest game-over-test
  (testing "Game over detection"
    (let [not-over {:rules {:winning-score 10000}
                    :players [{:total-score 5000}
                              {:total-score 3000}]}
          game-over {:rules {:winning-score 10000}
                     :players [{:total-score 12000}
                               {:total-score 3000}]}]
      (is (false? (util/game-over? not-over)))
      (is (true? (util/game-over? game-over))))))

(deftest winner-test
  (testing "Winner detection"
    (let [game-state {:rules {:winning-score 10000}
                      :players [{:id :player-1 :total-score 12000}
                                {:id :player-2 :total-score 3000}]}]
      (is (= :player-1 (:id (util/winner game-state)))))))
