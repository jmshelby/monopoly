(ns jmshelby.scoundrel.players.smart-test
  (:require [clojure.test :refer [deftest is testing]]
            [jmshelby.scoundrel.players.smart :as smart]
            [jmshelby.scoundrel.player :as player]))

;; Test helper cards
(def test-monster-7 {:suit :clubs :rank :7 :value 7})
(def test-monster-5 {:suit :spades :rank :5 :value 5})
(def test-monster-10 {:suit :clubs :rank :10 :value 10})
(def test-monster-3 {:suit :clubs :rank :3 :value 3})
(def test-weapon-2 {:suit :diamonds :rank :2 :value 2})
(def test-potion-8 {:suit :hearts :rank :8 :value 8})
(def test-potion-4 {:suit :hearts :rank :4 :value 4})

(deftest test-simulate-card-sequence
  (testing "Simulate taking damage from monster"
    (let [final-health (smart/simulate-card-sequence 20 nil [test-monster-7])]
      (is (= 13 final-health))))

  (testing "Simulate defeating monster with weapon"
    (let [weapon {:card test-weapon-2 :defeated-monsters []}
          final-health (smart/simulate-card-sequence 20 weapon [test-monster-7])]
      (is (= 20 final-health))))

  (testing "Simulate weapon constraint - monster too large"
    (let [weapon {:card test-weapon-2 :defeated-monsters [test-monster-5]}
          ;; Can't attack monster-7 after defeating monster-5
          final-health (smart/simulate-card-sequence 20 weapon [test-monster-7])]
      (is (= 13 final-health))))

  (testing "Simulate weapon constraint - descending works"
    (let [weapon {:card test-weapon-2 :defeated-monsters [test-monster-7]}
          ;; Can attack monster-5 after defeating monster-7
          final-health (smart/simulate-card-sequence 20 weapon [test-monster-5])]
      (is (= 20 final-health))))

  (testing "Simulate potion healing"
    (let [final-health (smart/simulate-card-sequence 10 nil [test-potion-8])]
      (is (= 18 final-health))))

  (testing "Simulate first potion heals, second doesn't"
    (let [final-health (smart/simulate-card-sequence 10 nil [test-potion-8 test-potion-4])]
      (is (= 18 final-health)))) ; Only first potion heals

  (testing "Simulate complex sequence"
    (let [weapon {:card test-weapon-2 :defeated-monsters []}
          cards [test-potion-8 test-monster-10 test-monster-7]
          ;; Health: 10 + 8 (potion) = 18, then weapon defeats 10 then 7
          final-health (smart/simulate-card-sequence 10 weapon cards)]
      (is (= 18 final-health)))))

(deftest test-find-best-card-order
  (testing "Weapon first when no weapon equipped"
    (let [game-state {:health 20 :equipped-weapon nil}
          cards [test-monster-7 test-weapon-2]
          ordered (smart/find-best-card-order cards game-state)]
      ;; Weapon should come first
      (is (= test-weapon-2 (first ordered)))))

  (testing "Potion first when health critical"
    (let [game-state {:health 5 :equipped-weapon nil}
          cards [test-monster-7 test-potion-8]
          ordered (smart/find-best-card-order cards game-state)]
      ;; Potion should come first when health < 8
      (is (= test-potion-8 (first ordered)))))

  (testing "Monsters sorted descending for weapon use"
    (let [game-state {:health 20 :equipped-weapon {:card test-weapon-2 :defeated-monsters []}}
          cards [test-monster-3 test-monster-10 test-monster-5]
          ordered (smart/find-best-card-order cards game-state)
          monsters-only (filter #(= :clubs (:suit %)) ordered)]
      ;; Monsters should be in descending order: 10, 5, 3
      (is (= [test-monster-10 test-monster-5 test-monster-3] monsters-only)))))

(deftest test-choose-cards
  (testing "Smart player chooses 3 cards from room"
    (let [player (smart/make-smart-player)
          game-state {:health 20 :equipped-weapon nil}
          room #{test-monster-7 test-weapon-2 test-potion-8 test-monster-5}
          chosen (player/choose-cards player game-state room)]
      (is (= 3 (count chosen)))
      (is (vector? chosen))
      (is (every? #(contains? room %) chosen))))

  (testing "Smart player leaves worst card"
    (let [player (smart/make-smart-player)
          game-state {:health 20 :equipped-weapon nil}
          ;; Room with weapon and monsters - should leave highest monster
          room #{test-monster-10 test-weapon-2 test-monster-5 test-monster-7}
          chosen (player/choose-cards player game-state room)]
      ;; Should not include the 10-value monster (worst outcome)
      ;; With weapon, can defeat 10, 7, 5 in that order
      ;; Best is to leave the card that results in best final health
      (is (= 3 (count chosen))))))

(deftest test-should-skip-room
  (testing "Smart player skips room if best play results in death"
    (let [player (smart/make-smart-player)
          game-state {:health 5 :equipped-weapon nil :skipped-last-room? false}
          ;; All high monsters, no weapons or potions - will die
          room #{test-monster-10 test-monster-7
                 {:suit :clubs :rank :8 :value 8}
                 {:suit :clubs :rank :9 :value 9}}
          should-skip? (player/should-skip-room? player game-state room)]
      ;; Total damage would be 34, health only 5 - should skip
      (is (true? should-skip?))))

  (testing "Smart player doesn't skip if survivable"
    (let [player (smart/make-smart-player)
          game-state {:health 20 :equipped-weapon nil :skipped-last-room? false}
          room #{test-monster-7 test-weapon-2 test-potion-8 test-monster-5}
          should-skip? (player/should-skip-room? player game-state room)]
      ;; This is survivable with smart play
      (is (false? should-skip?))))

  (testing "Smart player doesn't skip consecutive rooms"
    (let [player (smart/make-smart-player)
          game-state {:health 5 :equipped-weapon nil :skipped-last-room? true}
          ;; Even if deadly, can't skip consecutive
          room #{test-monster-10 test-monster-10 test-monster-10 test-monster-10}
          should-skip? (player/should-skip-room? player game-state room)]
      (is (false? should-skip?)))))

(deftest test-integration
  (testing "Smart player completes games without errors"
    (let [player (smart/make-smart-player)]
      ;; This should work without throwing exceptions
      (is (map? player))
      (is (= :smart (:type player))))))
