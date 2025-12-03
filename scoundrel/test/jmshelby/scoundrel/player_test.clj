(ns jmshelby.scoundrel.player-test
  (:require [clojure.test :refer [deftest is testing]]
            [jmshelby.scoundrel.core :as core]
            [jmshelby.scoundrel.player :as player]
            [jmshelby.scoundrel.players.random :as random]
            [jmshelby.scoundrel.players.greedy :as greedy]))

;; Test helper cards
(def test-monster-7 {:suit :clubs :rank :7 :value 7})
(def test-monster-5 {:suit :spades :rank :5 :value 5})
(def test-monster-10 {:suit :clubs :rank :10 :value 10})
(def test-weapon-3 {:suit :diamonds :rank :3 :value 3})
(def test-potion-8 {:suit :hearts :rank :8 :value 8})
(def test-potion-4 {:suit :hearts :rank :4 :value 4})

(deftest test-random-player
  (testing "Random player chooses 3 cards from room"
    (let [player (random/make-random-player)
          state {:health 20}
          room #{test-monster-7 test-weapon-3 test-potion-8 test-monster-5}
          chosen (player/choose-cards player state room)]
      (is (= 3 (count chosen)))
      (is (vector? chosen))
      (is (every? #(contains? room %) chosen))))

  (testing "Random player never skips rooms"
    (let [player (random/make-random-player)
          state {:health 20}
          room #{test-monster-7 test-weapon-3 test-potion-8 test-monster-5}]
      (is (false? (player/should-skip-room? player state room))))))

(deftest test-greedy-player
  (testing "Greedy player chooses 3 cards from room"
    (let [player (greedy/make-greedy-player)
          state {:health 20 :equipped-weapon nil}
          room #{test-monster-7 test-weapon-3 test-potion-8 test-monster-5}
          chosen (player/choose-cards player state room)]
      (is (= 3 (count chosen)))
      (is (vector? chosen))
      (is (every? #(contains? room %) chosen))))

  (testing "Greedy player prioritizes weapon when no weapon equipped"
    (let [player (greedy/make-greedy-player)
          state {:health 20 :equipped-weapon nil}
          room #{test-monster-7 test-weapon-3 test-potion-8 test-monster-5}
          chosen (player/choose-cards player state room)]
      ;; Weapon should be in chosen cards (and ideally early)
      (is (some #(= % test-weapon-3) chosen))))

  (testing "Greedy player prioritizes potion when health is low"
    (let [player (greedy/make-greedy-player)
          state {:health 5 :equipped-weapon nil}
          room #{test-monster-7 test-weapon-3 test-potion-8 test-monster-5}
          chosen (player/choose-cards player state room)]
      ;; Potion should be in chosen cards
      (is (some #(= % test-potion-8) chosen))
      ;; Potion should be first (index 0)
      (is (= test-potion-8 (first chosen)))))

  (testing "Greedy player skips dangerous rooms when low health"
    (let [player (greedy/make-greedy-player)
          state {:health 5}
          room #{test-monster-7 test-monster-5 test-monster-10
                 {:suit :clubs :rank :9 :value 9}}]
      ;; All monsters, low health, high average value
      (is (true? (player/should-skip-room? player state room)))))

  (testing "Greedy player does not skip safe rooms"
    (let [player (greedy/make-greedy-player)
          state {:health 15}
          room #{test-monster-7 test-weapon-3 test-potion-8 test-monster-5}]
      (is (false? (player/should-skip-room? player state room))))))

(deftest test-play-turn
  (testing "Play turn with random player"
    (let [player (random/make-random-player)
          state (core/init-game-state)
          new-state (core/play-turn state player)]
      ;; Room should have 4 cards again (1 left + 3 new)
      (is (or (= 4 (count (:room new-state)))
              ;; Or game ended
              (not= :playing (:status new-state))))))

  (testing "Play turn with greedy player"
    (let [player (greedy/make-greedy-player)
          state (core/init-game-state)
          new-state (core/play-turn state player)]
      ;; Room should have 4 cards again or game ended
      (is (or (= 4 (count (:room new-state)))
              (not= :playing (:status new-state)))))))

(deftest test-play-game
  (testing "Play complete game with random player"
    (let [player (random/make-random-player)
          final-state (core/play-game player)]
      ;; Game should end (won, lost, or failed)
      (is (not= :playing (:status final-state)))
      (is (contains? final-state :turns-played))))

  (testing "Play complete game with greedy player"
    (let [player (greedy/make-greedy-player)
          final-state (core/play-game player)]
      ;; Game should end
      (is (not= :playing (:status final-state)))
      (is (contains? final-state :turns-played))))

  (testing "Multiple games complete without errors"
    (let [random-player (random/make-random-player)
          greedy-player (greedy/make-greedy-player)]
      ;; Play 5 games with each player
      (dotimes [_ 5]
        (let [random-result (core/play-game random-player)
              greedy-result (core/play-game greedy-player)]
          (is (not= :playing (:status random-result)))
          (is (not= :playing (:status greedy-result))))))))
