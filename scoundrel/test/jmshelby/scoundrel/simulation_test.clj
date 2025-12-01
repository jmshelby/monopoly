(ns jmshelby.scoundrel.simulation-test
  (:require [clojure.test :refer [deftest is testing]]
            [jmshelby.scoundrel.simulation :as sim]
            [jmshelby.scoundrel.core :as core]
            [jmshelby.scoundrel.players.random :as random]
            [jmshelby.scoundrel.players.greedy :as greedy]
            [clojure.core.async :as async]))

(deftest test-analyze-game-outcome
  (testing "Analyze won game with various transactions"
    (let [game-state {:status :won
                      :health 15
                      :turns-played 12
                      :transactions [{:type :damage-taken :damage 7}
                                     {:type :damage-taken :damage 5}
                                     {:type :monster-defeated}
                                     {:type :monster-defeated}
                                     {:type :weapon-equipped}
                                     {:type :weapon-equipped :replaced-weapon {:suit :diamonds}}
                                     {:type :healed :amount 8}
                                     {:type :healed :amount 4}
                                     {:type :potion-wasted}
                                     {:type :room-skipped}]}
          result (sim/analyze-game-outcome game-state :random)]

      (is (= :won (:outcome result)))
      (is (= :random (:player-type result)))
      (is (= 15 (:final-health result)))
      (is (= 12 (:turns-played result)))

      ;; Damage stats
      (is (= 12 (:total-damage result)))
      (is (= 2 (:damage-instances result)))
      (is (= 6.0 (:avg-damage-per-hit result)))

      ;; Combat stats
      (is (= 2 (:monsters-defeated result)))
      (is (= 0.5 (:combat-efficiency result))) ; 2 defeated / 4 total encounters

      ;; Weapon stats
      (is (= 2 (:weapons-equipped result)))
      (is (= 1 (:weapon-replacements result)))

      ;; Potion stats
      (is (= 12 (:total-healing result)))
      (is (= 2 (:potions-used result)))
      (is (= 1 (:potions-wasted result)))
      (is (= (/ 2.0 3.0) (:potion-efficiency result)))

      ;; Room stats
      (is (= 1 (:rooms-skipped result)))))

  (testing "Analyze lost game with no weapons or potions"
    (let [game-state {:status :lost
                      :health -3
                      :turns-played 5
                      :transactions [{:type :damage-taken :damage 10}
                                     {:type :damage-taken :damage 13}]}
          result (sim/analyze-game-outcome game-state :greedy)]

      (is (= :lost (:outcome result)))
      (is (= 23 (:total-damage result)))
      (is (= 0 (:monsters-defeated result)))
      (is (= 0 (:weapons-equipped result)))
      (is (= 0 (:potions-used result)))))

  (testing "Analyze game with perfect combat efficiency"
    (let [game-state {:status :won
                      :health 20
                      :turns-played 8
                      :transactions [{:type :monster-defeated}
                                     {:type :monster-defeated}
                                     {:type :monster-defeated}]}
          result (sim/analyze-game-outcome game-state :greedy)]

      (is (= 1.0 (:combat-efficiency result))) ; 3 defeated / 3 total
      (is (= 0 (:total-damage result))))))

(deftest test-run-simulation
  (testing "Run small simulation with random player"
    (let [output-ch (sim/run-simulation 5 :random 100)
          results (loop [acc []]
                    (if-let [result (async/<!! output-ch)]
                      (recur (conj acc result))
                      acc))]

      (is (= 5 (count results)))
      (is (every? #(contains? % :outcome) results))
      (is (every? #(= :random (:player-type %)) results))
      (is (every? #(contains? #{:won :lost :failed} (:outcome %)) results))))

  (testing "Run small simulation with greedy player"
    (let [output-ch (sim/run-simulation 5 :greedy 100)
          results (loop [acc []]
                    (if-let [result (async/<!! output-ch)]
                      (recur (conj acc result))
                      acc))]

      (is (= 5 (count results)))
      (is (every? #(= :greedy (:player-type %)) results)))))

(deftest test-aggregate-results
  (testing "Aggregate results from multiple games"
    (let [results [{:outcome :won :turns-played 10 :total-damage 20 :monsters-defeated 5
                    :weapons-equipped 2 :total-healing 10 :potions-used 2 :potions-wasted 0
                    :rooms-skipped 1 :combat-efficiency 0.8 :weapon-replacements 1 :potion-efficiency 1.0}
                   {:outcome :won :turns-played 15 :total-damage 30 :monsters-defeated 8
                    :weapons-equipped 3 :total-healing 15 :potions-used 3 :potions-wasted 1
                    :rooms-skipped 0 :combat-efficiency 0.9 :weapon-replacements 2 :potion-efficiency 0.75}
                   {:outcome :lost :turns-played 8 :total-damage 40 :monsters-defeated 2
                    :weapons-equipped 1 :total-healing 5 :potions-used 1 :potions-wasted 2
                    :rooms-skipped 2 :combat-efficiency 0.5 :weapon-replacements 0 :potion-efficiency 0.33}]
          stats (sim/aggregate-results results)]

      (is (= 3 (:total-games stats)))
      (is (= 2 (:games-won stats)))
      (is (= 1 (:games-lost stats)))
      (is (= 0 (:games-failed stats)))
      (is (= (/ 200.0 3.0) (:win-rate stats)))

      ;; Turn stats
      (is (= 8 (get-in stats [:turn-stats :min])))
      (is (= 15 (get-in stats [:turn-stats :max])))
      (is (= 11.0 (get-in stats [:turn-stats :avg])))

      ;; Won turn stats
      (is (= 10 (get-in stats [:won-turn-stats :min])))
      (is (= 15 (get-in stats [:won-turn-stats :max])))

      ;; Damage stats
      (is (= 20 (get-in stats [:damage-stats :min])))
      (is (= 40 (get-in stats [:damage-stats :max])))
      (is (= 30.0 (get-in stats [:damage-stats :avg])))

      ;; Combat stats
      (is (= 5.0 (get-in stats [:monsters-defeated-stats :avg])))

      ;; Weapon stats
      (is (= 2.0 (get-in stats [:weapons-equipped-stats :avg])))

      ;; Potion stats
      (is (= 10.0 (get-in stats [:healing-stats :avg])))
      (is (= 2.0 (get-in stats [:potions-used-stats :avg])))

      ;; Room stats
      (is (= 1.0 (get-in stats [:rooms-skipped-stats :avg])))))

  (testing "Aggregate empty results"
    (let [stats (sim/aggregate-results [])]
      (is (= 0 (:total-games stats)))
      (is (= 0 (:games-won stats)))
      (is (= 0.0 (get-in stats [:turn-stats :avg])))))

  (testing "Aggregate single game"
    (let [results [{:outcome :won :turns-played 12 :total-damage 25 :monsters-defeated 6
                    :weapons-equipped 2 :total-healing 12 :potions-used 2 :potions-wasted 0
                    :rooms-skipped 1 :combat-efficiency 0.85 :weapon-replacements 1 :potion-efficiency 1.0}]
          stats (sim/aggregate-results results)]
      (is (= 1 (:total-games stats)))
      (is (= 100.0 (:win-rate stats)))
      (is (= 12.0 (get-in stats [:turn-stats :avg]))))))

(deftest test-print-simulation-results
  (testing "Print results without errors"
    (let [stats {:total-games 100
                 :games-won 75
                 :games-lost 24
                 :games-failed 1
                 :win-rate 75.0
                 :turn-stats {:min 5 :max 20 :avg 12.5 :median 12}
                 :won-turn-stats {:min 5 :max 18 :avg 11.0 :median 11}
                 :damage-stats {:min 0 :max 50 :avg 20.0 :median 18}
                 :won-damage-stats {:avg 15.0}
                 :monsters-defeated-stats {:avg 8.5}
                 :combat-efficiency-stats {:avg 0.75}
                 :weapons-equipped-stats {:avg 2.5}
                 :weapon-replacement-stats {:avg 1.2}
                 :healing-stats {:avg 12.0}
                 :potions-used-stats {:avg 2.5}
                 :potions-wasted-stats {:avg 0.8}
                 :potion-efficiency-stats {:avg 0.85}
                 :rooms-skipped-stats {:avg 0.5}}]
      ;; Just verify it doesn't throw an exception
      (is (nil? (sim/print-simulation-results stats :random))))))

(deftest test-integration
  (testing "Full simulation workflow with small sample"
    (let [output-ch (sim/run-simulation 10 :random 100)
          results (loop [acc []]
                    (if-let [result (async/<!! output-ch)]
                      (recur (conj acc result))
                      acc))
          stats (sim/aggregate-results results)]

      (is (= 10 (:total-games stats)))
      (is (>= (:games-won stats) 0))
      (is (>= (:games-lost stats) 0))
      (is (>= (get-in stats [:turn-stats :avg]) 0))
      (is (>= (:win-rate stats) 0.0))
      (is (<= (:win-rate stats) 100.0)))))
