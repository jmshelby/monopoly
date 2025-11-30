(ns jmshelby.scoundrel.core
  (:require [jmshelby.scoundrel.definitions :as def]))

;; Game State Schema
;; {:deck []                    - Remaining undealt cards
;;  :room #{}                   - Current 4 cards (SET)
;;  :health 20                  - Current health
;;  :equipped-weapon nil        - {:card ... :defeated-monsters [...]}
;;  :skipped-last-room? false   - Consecutive skip prevention
;;  :turn-potions-used 0        - Potions used this turn (turn = room)
;;  :status :playing}           - :playing, :won, :lost

(def initial-health 20)
(def room-size 4)
(def cards-to-play-per-room 3)

;; ============================================================================
;; Deck and Initialization
;; ============================================================================

(defn shuffle-deck
  "Shuffle a deck of cards"
  [deck]
  (vec (shuffle deck)))

(defn deal-room
  "Deal cards from deck into room. Returns updated game state."
  [game-state num-cards]
  (let [cards-to-deal (min num-cards (count (:deck game-state)))
        new-room-cards (take cards-to-deal (:deck game-state))
        remaining-deck (vec (drop cards-to-deal (:deck game-state)))]
    (-> game-state
        (update :room into new-room-cards)
        (assoc :deck remaining-deck))))

(defn init-game-state
  "Create initial game state with shuffled deck and first room dealt"
  []
  (let [deck (shuffle-deck (def/create-deck))]
    (-> {:deck deck
         :room #{}
         :health initial-health
         :equipped-weapon nil
         :skipped-last-room? false
         :turn-potions-used 0
         :status :playing}
        (deal-room room-size))))

;; ============================================================================
;; Game State Queries
;; ============================================================================

(defn game-over?
  "Check if game is over. Returns status or nil.
  Win: deck and room are both empty
  Lose: health <= 0"
  [game-state]
  (cond
    (<= (:health game-state) 0) :lost
    (and (empty? (:deck game-state))
         (empty? (:room game-state))) :won
    :else nil))

(defn cards-remaining-in-room
  "Count cards left in room"
  [game-state]
  (count (:room game-state)))

(defn can-skip-room?
  "Check if room skip is allowed (not consecutive)"
  [game-state]
  (not (:skipped-last-room? game-state)))

;; ============================================================================
;; Weapon Logic
;; ============================================================================

(defn equip-weapon
  "Equip a weapon card, replacing any existing weapon"
  [game-state weapon-card]
  (assoc game-state :equipped-weapon
         {:card weapon-card
          :defeated-monsters []}))

(defn can-attack-with-weapon?
  "Check if weapon can attack this monster.
  Constraint: monster value must be < last defeated monster value"
  [game-state monster-card]
  (if-let [weapon (:equipped-weapon game-state)]
    (if-let [last-defeated (peek (:defeated-monsters weapon))]
      (< (:value monster-card) (:value last-defeated))
      true) ; No monsters defeated yet, any monster is valid
    false)) ; No weapon equipped

(defn defeat-monster-with-weapon
  "Update weapon state after defeating a monster"
  [game-state monster-card]
  (update-in game-state [:equipped-weapon :defeated-monsters] conj monster-card))

;; ============================================================================
;; Card Effect Handlers
;; ============================================================================

(defn apply-monster-card
  "Apply monster card effect: damage player or defeat with weapon"
  [game-state monster-card]
  (if (and (:equipped-weapon game-state)
           (can-attack-with-weapon? game-state monster-card))
    ;; Defeat with weapon
    (defeat-monster-with-weapon game-state monster-card)
    ;; Take damage
    (update game-state :health - (:value monster-card))))

(defn apply-weapon-card
  "Apply weapon card effect: equip the weapon"
  [game-state weapon-card]
  (equip-weapon game-state weapon-card))

(defn apply-potion-card
  "Apply potion card effect: heal if first potion this turn"
  [game-state potion-card]
  (if (zero? (:turn-potions-used game-state))
    (-> game-state
        (update :health + (:value potion-card))
        (update :turn-potions-used inc))
    ;; Not first potion, no effect but still count it
    (update game-state :turn-potions-used inc)))

(defn apply-card-effect
  "Apply card effect based on card type"
  [game-state card]
  (case (def/card-type card)
    :monster (apply-monster-card game-state card)
    :weapon (apply-weapon-card game-state card)
    :potion (apply-potion-card game-state card)))

;; ============================================================================
;; Game Flow
;; ============================================================================

(defn play-card
  "Play a card from the room. Apply effects, remove from room, check game over."
  [game-state card]
  (when-not (contains? (:room game-state) card)
    (throw (ex-info "Card not in room" {:card card :room (:room game-state)})))
  (let [new-state (-> game-state
                      (apply-card-effect card)
                      (update :room disj card))
        game-status (game-over? new-state)]
    (if game-status
      (assoc new-state :status game-status)
      new-state)))

(defn complete-room
  "Complete current room: reset turn state, deal new cards.
  Should be called after playing 3 cards from a room."
  [game-state]
  (-> game-state
      (assoc :turn-potions-used 0)
      (assoc :skipped-last-room? false)
      (deal-room cards-to-play-per-room)))

(defn skip-room
  "Skip the current room: move all cards to bottom of deck, deal fresh room"
  [game-state]
  (when-not (can-skip-room? game-state)
    (throw (ex-info "Cannot skip consecutive rooms" {})))
  (let [room-cards (vec (:room game-state))]
    (-> game-state
        (assoc :room #{})
        (update :deck into room-cards)
        (assoc :skipped-last-room? true)
        (assoc :turn-potions-used 0)
        (deal-room room-size))))

(defn play-cards-in-order
  "Play multiple cards in the specified order"
  [game-state cards]
  (reduce (fn [state card]
            (if (= (:status state) :playing)
              (play-card state card)
              (reduced state))) ; Stop if game is over
          game-state
          cards))
