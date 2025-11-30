(ns jmshelby.scoundrel.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [jmshelby.scoundrel.core :as core]
            [jmshelby.scoundrel.definitions :as def]))

;; Test helper cards
(def test-monster-7 {:suit :clubs :rank :7 :value 7})
(def test-monster-5 {:suit :spades :rank :5 :value 5})
(def test-monster-10 {:suit :clubs :rank :10 :value 10})
(def test-weapon-3 {:suit :diamonds :rank :3 :value 3})
(def test-potion-8 {:suit :hearts :rank :8 :value 8})
(def test-potion-4 {:suit :hearts :rank :4 :value 4})

(deftest test-shuffle-deck
  (testing "Shuffle returns a vector"
    (let [deck (def/create-deck)
          shuffled (core/shuffle-deck deck)]
      (is (vector? shuffled))
      (is (= (count deck) (count shuffled))))))

(deftest test-init-game-state
  (testing "Initial game state has correct structure"
    (let [state (core/init-game-state)]
      (is (= core/initial-health (:health state)))
      (is (= #{} (type (:room state)))) ; room is a set
      (is (= core/room-size (count (:room state))))
      (is (nil? (:equipped-weapon state)))
      (is (false? (:skipped-last-room? state)))
      (is (= 0 (:turn-potions-used state)))
      (is (= :playing (:status state)))
      (is (= 40 (count (:deck state)))))) ; 44 - 4 dealt to room

  (testing "Room cards are from the deck"
    (let [state (core/init-game-state)
          all-cards (concat (:deck state) (:room state))]
      (is (= 44 (count all-cards))))))

(deftest test-deal-room
  (testing "Deal cards into room"
    (let [state {:deck [test-monster-7 test-weapon-3 test-potion-8]
                 :room #{}}
          new-state (core/deal-room state 2)]
      (is (= 2 (count (:room new-state))))
      (is (= 1 (count (:deck new-state))))
      (is (contains? (:room new-state) test-monster-7))
      (is (contains? (:room new-state) test-weapon-3))))

  (testing "Deal limited by deck size"
    (let [state {:deck [test-monster-7]
                 :room #{}}
          new-state (core/deal-room state 5)]
      (is (= 1 (count (:room new-state))))
      (is (empty? (:deck new-state))))))

(deftest test-game-over
  (testing "Lose when health <= 0"
    (is (= :lost (core/game-over? {:health 0 :deck [] :room #{}})))
    (is (= :lost (core/game-over? {:health -5 :deck [test-monster-7] :room #{}}))))

  (testing "Win when deck and room are empty"
    (is (= :won (core/game-over? {:health 10 :deck [] :room #{}}))))

  (testing "Not over when still playing"
    (is (nil? (core/game-over? {:health 10 :deck [test-monster-7] :room #{}})))
    (is (nil? (core/game-over? {:health 10 :deck [] :room #{test-monster-7}})))))

(deftest test-can-skip-room
  (testing "Can skip when not skipped last room"
    (is (true? (core/can-skip-room? {:skipped-last-room? false}))))

  (testing "Cannot skip consecutive rooms"
    (is (false? (core/can-skip-room? {:skipped-last-room? true})))))

(deftest test-equip-weapon
  (testing "Equip weapon with empty defeated-monsters"
    (let [state {:equipped-weapon nil}
          new-state (core/equip-weapon state test-weapon-3)]
      (is (= test-weapon-3 (get-in new-state [:equipped-weapon :card])))
      (is (= [] (get-in new-state [:equipped-weapon :defeated-monsters])))))

  (testing "Replace existing weapon"
    (let [state {:equipped-weapon {:card test-weapon-3 :defeated-monsters [test-monster-7]}}
          new-weapon {:suit :diamonds :rank :5 :value 5}
          new-state (core/equip-weapon state new-weapon)]
      (is (= new-weapon (get-in new-state [:equipped-weapon :card])))
      (is (= [] (get-in new-state [:equipped-weapon :defeated-monsters]))))))

(deftest test-can-attack-with-weapon
  (testing "No weapon equipped"
    (is (false? (core/can-attack-with-weapon? {:equipped-weapon nil} test-monster-7))))

  (testing "Weapon equipped, no monsters defeated yet"
    (let [state {:equipped-weapon {:card test-weapon-3 :defeated-monsters []}}]
      (is (true? (core/can-attack-with-weapon? state test-monster-7)))
      (is (true? (core/can-attack-with-weapon? state test-monster-5)))))

  (testing "Monster must be weaker than last defeated"
    (let [state {:equipped-weapon {:card test-weapon-3
                                    :defeated-monsters [test-monster-10]}}]
      (is (true? (core/can-attack-with-weapon? state test-monster-7)))
      (is (true? (core/can-attack-with-weapon? state test-monster-5)))
      (is (false? (core/can-attack-with-weapon? state test-monster-10)))
      (is (false? (core/can-attack-with-weapon? state {:suit :clubs :rank :king :value 13})))))

  (testing "Descending sequence enforcement"
    (let [state {:equipped-weapon {:card test-weapon-3
                                    :defeated-monsters [test-monster-10 test-monster-7]}}]
      ;; Last defeated was 7, so only values < 7 are valid
      (is (true? (core/can-attack-with-weapon? state test-monster-5)))
      (is (false? (core/can-attack-with-weapon? state test-monster-7)))
      (is (false? (core/can-attack-with-weapon? state test-monster-10))))))

(deftest test-defeat-monster-with-weapon
  (testing "Add monster to defeated stack"
    (let [state {:equipped-weapon {:card test-weapon-3 :defeated-monsters []}}
          new-state (core/defeat-monster-with-weapon state test-monster-10)]
      (is (= [test-monster-10] (get-in new-state [:equipped-weapon :defeated-monsters])))))

  (testing "Build up defeated stack"
    (let [state {:equipped-weapon {:card test-weapon-3 :defeated-monsters [test-monster-10]}}
          new-state (core/defeat-monster-with-weapon state test-monster-7)]
      (is (= [test-monster-10 test-monster-7]
             (get-in new-state [:equipped-weapon :defeated-monsters]))))))

(deftest test-apply-monster-card
  (testing "Monster damages player when no weapon"
    (let [state {:health 20 :equipped-weapon nil}
          new-state (core/apply-monster-card state test-monster-7)]
      (is (= 13 (:health new-state)))))

  (testing "Monster defeated with weapon when valid"
    (let [state {:health 20
                 :equipped-weapon {:card test-weapon-3 :defeated-monsters []}}
          new-state (core/apply-monster-card state test-monster-7)]
      (is (= 20 (:health new-state))) ; No damage
      (is (= [test-monster-7] (get-in new-state [:equipped-weapon :defeated-monsters])))))

  (testing "Monster deals damage when weapon cannot attack"
    (let [state {:health 20
                 :equipped-weapon {:card test-weapon-3
                                   :defeated-monsters [test-monster-7]}}
          new-state (core/apply-monster-card state test-monster-10)]
      (is (= 10 (:health new-state))) ; Took damage
      (is (= [test-monster-7] (get-in new-state [:equipped-weapon :defeated-monsters])))))) ; No change

(deftest test-apply-weapon-card
  (testing "Equip weapon"
    (let [state {:equipped-weapon nil}
          new-state (core/apply-weapon-card state test-weapon-3)]
      (is (= test-weapon-3 (get-in new-state [:equipped-weapon :card])))
      (is (= [] (get-in new-state [:equipped-weapon :defeated-monsters]))))))

(deftest test-apply-potion-card
  (testing "First potion heals"
    (let [state {:health 10 :turn-potions-used 0}
          new-state (core/apply-potion-card state test-potion-8)]
      (is (= 18 (:health new-state)))
      (is (= 1 (:turn-potions-used new-state)))))

  (testing "Second potion has no effect"
    (let [state {:health 10 :turn-potions-used 1}
          new-state (core/apply-potion-card state test-potion-4)]
      (is (= 10 (:health new-state))) ; No healing
      (is (= 2 (:turn-potions-used new-state))))) ; Still counted

  (testing "Multiple potions"
    (let [state {:health 10 :turn-potions-used 0}
          state1 (core/apply-potion-card state test-potion-8)
          state2 (core/apply-potion-card state1 test-potion-4)]
      (is (= 18 (:health state2))) ; Only first potion healed
      (is (= 2 (:turn-potions-used state2))))))

(deftest test-play-card
  (testing "Play card from room applies effect"
    (let [state {:room #{test-monster-7 test-weapon-3}
                 :health 20
                 :equipped-weapon nil
                 :status :playing}
          new-state (core/play-card state test-monster-7)]
      (is (= 13 (:health new-state)))
      (is (not (contains? (:room new-state) test-monster-7)))))

  (testing "Cannot play card not in room"
    (let [state {:room #{test-weapon-3} :status :playing}]
      (is (thrown? Exception (core/play-card state test-monster-7)))))

  (testing "Game over after card causes death"
    (let [state {:room #{test-monster-10}
                 :health 5
                 :equipped-weapon nil
                 :status :playing}
          new-state (core/play-card state test-monster-10)]
      (is (= :lost (:status new-state)))
      (is (<= (:health new-state) 0))))

  (testing "Game won when last card played"
    (let [state {:room #{test-weapon-3}
                 :deck []
                 :health 20
                 :status :playing}
          new-state (core/play-card state test-weapon-3)]
      (is (= :won (:status new-state))))))

(deftest test-complete-room
  (testing "Reset turn state and deal new cards"
    (let [state {:room #{test-monster-7}
                 :deck [test-weapon-3 test-potion-8 test-monster-5]
                 :turn-potions-used 2
                 :skipped-last-room? true}
          new-state (core/complete-room state)]
      (is (= 0 (:turn-potions-used new-state)))
      (is (false? (:skipped-last-room? new-state)))
      (is (= 4 (count (:room new-state)))) ; 1 + 3 new cards
      (is (empty? (:deck new-state))))))

(deftest test-skip-room
  (testing "Skip room moves cards to deck bottom and deals fresh room"
    (let [state {:room #{test-monster-7 test-weapon-3 test-potion-8 test-monster-5}
                 :deck [test-monster-10]
                 :skipped-last-room? false
                 :turn-potions-used 2}
          new-state (core/skip-room state)]
      (is (true? (:skipped-last-room? new-state)))
      (is (= 0 (:turn-potions-used new-state)))
      (is (= 4 (count (:room new-state))))
      ;; Old room cards should be at the END of deck (after test-monster-10)
      ;; But we dealt 4 from deck, so deck should have 1 card left
      (is (= 1 (count (:deck new-state))))))

  (testing "Cannot skip consecutive rooms"
    (let [state {:room #{test-monster-7}
                 :deck []
                 :skipped-last-room? true}]
      (is (thrown? Exception (core/skip-room state))))))

(deftest test-play-cards-in-order
  (testing "Play multiple cards in sequence"
    (let [state {:room #{test-weapon-3 test-monster-7 test-monster-5}
                 :health 20
                 :equipped-weapon nil
                 :status :playing}
          new-state (core/play-cards-in-order state [test-weapon-3 test-monster-7 test-monster-5])]
      (is (= 20 (:health new-state))) ; Weapon defeated both monsters
      (is (empty? (:room new-state)))
      (is (= [test-monster-7 test-monster-5]
             (get-in new-state [:equipped-weapon :defeated-monsters])))))

  (testing "Stop playing when game ends"
    (let [state {:room #{test-monster-10 test-monster-7}
                 :health 5
                 :equipped-weapon nil
                 :status :playing}
          new-state (core/play-cards-in-order state [test-monster-10 test-monster-7])]
      (is (= :lost (:status new-state)))
      ;; Second card should not be played
      (is (contains? (:room new-state) test-monster-7)))))
