(ns jmshelby.scoundrel.definitions)

;; Card ranks and their values
(def rank-values
  {:2 2
   :3 3
   :4 4
   :5 5
   :6 6
   :7 7
   :8 8
   :9 9
   :10 10
   :jack 11
   :queen 12
   :king 13
   :ace 14})

;; All suits
(def suits [:clubs :spades :diamonds :hearts])

;; All ranks
(def ranks [:2 :3 :4 :5 :6 :7 :8 :9 :10 :jack :queen :king :ace])

(defn make-card
  "Create a card with suit, rank, and computed value"
  [suit rank]
  {:suit suit
   :rank rank
   :value (rank-values rank)})

(defn excluded-card?
  "Returns true if this card should be excluded from the Scoundrel deck.
  Excludes: red face cards (J/Q/K) and red Aces"
  [suit rank]
  (and (#{:hearts :diamonds} suit)
       (#{:jack :queen :king :ace} rank)))

(defn create-deck
  "Create a Scoundrel deck: 52 cards minus red face cards and red Aces = 44 cards"
  []
  (vec
   (for [suit suits
         rank ranks
         :when (not (excluded-card? suit rank))]
     (make-card suit rank))))

(defn card-type
  "Classify a card by its suit:
  - Clubs/Spades = :monster
  - Diamonds = :weapon
  - Hearts = :potion"
  [card]
  (case (:suit card)
    (:clubs :spades) :monster
    :diamonds :weapon
    :hearts :potion))


(comment

  (create-deck)


  )

