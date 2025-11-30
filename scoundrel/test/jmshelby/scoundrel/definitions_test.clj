(ns jmshelby.scoundrel.definitions-test
  (:require [clojure.test :refer [deftest is testing]]
            [jmshelby.scoundrel.definitions :as def]))

(deftest test-rank-values
  (testing "Rank values are correct"
    (is (= 2 (def/rank-values :2)))
    (is (= 10 (def/rank-values :10)))
    (is (= 11 (def/rank-values :jack)))
    (is (= 12 (def/rank-values :queen)))
    (is (= 13 (def/rank-values :king)))
    (is (= 14 (def/rank-values :ace)))))

(deftest test-make-card
  (testing "Card creation with correct structure"
    (let [card (def/make-card :clubs :7)]
      (is (= :clubs (:suit card)))
      (is (= :7 (:rank card)))
      (is (= 7 (:value card))))
    (let [card (def/make-card :hearts :ace)]
      (is (= :hearts (:suit card)))
      (is (= :ace (:rank card)))
      (is (= 14 (:value card))))))

(deftest test-excluded-cards
  (testing "Red face cards are excluded"
    (is (def/excluded-card? :hearts :jack))
    (is (def/excluded-card? :hearts :queen))
    (is (def/excluded-card? :hearts :king))
    (is (def/excluded-card? :diamonds :jack))
    (is (def/excluded-card? :diamonds :queen))
    (is (def/excluded-card? :diamonds :king)))

  (testing "Red aces are excluded"
    (is (def/excluded-card? :hearts :ace))
    (is (def/excluded-card? :diamonds :ace)))

  (testing "Red number cards are NOT excluded"
    (is (not (def/excluded-card? :hearts :2)))
    (is (not (def/excluded-card? :hearts :10)))
    (is (not (def/excluded-card? :diamonds :5))))

  (testing "Black cards are NOT excluded"
    (is (not (def/excluded-card? :clubs :jack)))
    (is (not (def/excluded-card? :spades :king)))
    (is (not (def/excluded-card? :clubs :ace)))
    (is (not (def/excluded-card? :spades :2)))))

(deftest test-create-deck
  (testing "Deck has correct number of cards"
    (let [deck (def/create-deck)]
      (is (= 44 (count deck)))))

  (testing "Deck contains no red face cards or red aces"
    (let [deck (def/create-deck)]
      (is (not-any? #(and (#{:hearts :diamonds} (:suit %))
                          (#{:jack :queen :king :ace} (:rank %)))
                    deck))))

  (testing "Deck contains all black cards"
    (let [deck (def/create-deck)
          black-cards (filter #(#{:clubs :spades} (:suit %)) deck)]
      ;; 13 ranks * 2 suits = 26 black cards
      (is (= 26 (count black-cards)))))

  (testing "Deck contains red number cards only"
    (let [deck (def/create-deck)
          red-cards (filter #(#{:hearts :diamonds} (:suit %)) deck)]
      ;; 9 number ranks (2-10) * 2 suits = 18 red cards
      (is (= 18 (count red-cards)))
      (is (every? #(#{:2 :3 :4 :5 :6 :7 :8 :9 :10} (:rank %)) red-cards))))

  (testing "All cards have correct value"
    (let [deck (def/create-deck)]
      (is (every? #(= (:value %) (def/rank-values (:rank %))) deck)))))

(deftest test-card-type
  (testing "Clubs are monsters"
    (is (= :monster (def/card-type {:suit :clubs :rank :7 :value 7}))))

  (testing "Spades are monsters"
    (is (= :monster (def/card-type {:suit :spades :rank :king :value 13}))))

  (testing "Diamonds are weapons"
    (is (= :weapon (def/card-type {:suit :diamonds :rank :3 :value 3}))))

  (testing "Hearts are potions"
    (is (= :potion (def/card-type {:suit :hearts :rank :10 :value 10})))))
