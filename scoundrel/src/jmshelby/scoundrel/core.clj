(ns jmshelby.scoundrel.core
  (:require [jmshelby.scoundrel.definitions :as def]
            [jmshelby.scoundrel.player :as player]))

;; Game State Schema
;; {:deck []                    - Remaining undealt cards
;;  :room #{}                   - Current 4 cards (SET)
;;  :health 20                  - Current health
;;  :equipped-weapon nil        - {:card ... :defeated-monsters [...]}
;;  :skipped-last-room? false   - Consecutive skip prevention
;;  :turn-potions-used 0        - Potions used this turn (turn = room)
;;  :turn 0                     - Current turn number
;;  :transactions []            - Transaction log (history of all actions)
;;  :status :playing}           - :playing, :won, :lost

(def initial-health 20)
(def max-health 20)
(def room-size 4)
(def cards-to-play-per-room 3)

;; ============================================================================
;; Transaction Management
;; ============================================================================

(defn append-tx
  "Append one or more transactions to the game state transaction log.
  Transactions are maps with :type and additional context fields."
  [game-state & txs]
  (update game-state :transactions into (filter some? txs)))

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
         :turn 0
         :transactions []
         :status :playing}
        (deal-room room-size))))

;; ============================================================================
;; Game State Queries
;; ============================================================================

(defn game-over?
  "Check if game is over. Returns status or nil.
  Win: deck is empty and room has <= 1 card (no more playable rooms)
  Lose: health <= 0"
  [game-state]
  (cond
    (<= (:health game-state) 0) :lost
    (and (empty? (:deck game-state))
         (<= (count (:room game-state)) 1)) :won
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
    (-> game-state
        (defeat-monster-with-weapon monster-card)
        (append-tx {:type :monster-defeated
                    :turn (:turn game-state)
                    :card monster-card
                    :weapon (get-in game-state [:equipped-weapon :card])}))
    ;; Take damage
    (-> game-state
        (update :health - (:value monster-card))
        (append-tx {:type :damage-taken
                    :turn (:turn game-state)
                    :card monster-card
                    :damage (:value monster-card)
                    :health-after (- (:health game-state) (:value monster-card))}))))

(defn apply-weapon-card
  "Apply weapon card effect: equip the weapon"
  [game-state weapon-card]
  (let [old-weapon (get-in game-state [:equipped-weapon :card])]
    (-> game-state
        (equip-weapon weapon-card)
        (append-tx {:type :weapon-equipped
                    :turn (:turn game-state)
                    :card weapon-card
                    :replaced-weapon old-weapon}))))

(defn apply-potion-card
  "Apply potion card effect: heal if first potion this turn (capped at max-health)"
  [game-state potion-card]
  (if (zero? (:turn-potions-used game-state))
    (let [current-health (:health game-state)
          potion-value (:value potion-card)
          actual-healing (min potion-value (- max-health current-health))
          wasted-healing (- potion-value actual-healing)
          new-health (min max-health (+ current-health potion-value))]
      (-> game-state
          (assoc :health new-health)
          (update :turn-potions-used inc)
          (append-tx {:type :healed
                      :turn (:turn game-state)
                      :card potion-card
                      :amount actual-healing
                      :wasted-healing wasted-healing
                      :health-after new-health})))
    ;; Not first potion, no effect but still count it
    (-> game-state
        (update :turn-potions-used inc)
        (append-tx {:type :potion-wasted
                    :turn (:turn game-state)
                    :card potion-card
                    :reason :not-first-potion}))))

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
                      (append-tx {:type :card-played
                                  :turn (:turn game-state)
                                  :card card
                                  :card-type (def/card-type card)})
                      (apply-card-effect card)
                      (update :room disj card))
        game-status (game-over? new-state)]
    (if game-status
      (-> new-state
          (assoc :status game-status)
          (append-tx {:type :game-ended
                      :turn (:turn game-state)
                      :outcome game-status
                      :final-health (:health new-state)}))
      new-state)))

(defn complete-room
  "Complete current room: reset turn state, deal new cards.
  Should be called after playing 3 cards from a room."
  [game-state]
  (-> game-state
      (assoc :turn-potions-used 0)
      (assoc :skipped-last-room? false)
      (deal-room cards-to-play-per-room)
      (append-tx {:type :room-completed
                  :turn (:turn game-state)})))

(defn skip-room
  "Skip the current room: move all cards to bottom of deck, deal fresh room"
  [game-state]
  (when-not (can-skip-room? game-state)
    (throw (ex-info "Cannot skip consecutive rooms" {})))
  (let [room-cards (vec (:room game-state))]
    (-> game-state
        (append-tx {:type :room-skipped
                    :turn (:turn game-state)
                    :skipped-cards room-cards})
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

;; ============================================================================
;; Game Loop with Player AI
;; ============================================================================

(defn play-turn
  "Play one complete turn (room) using player decisions.
  Returns updated game state after the turn."
  [game-state player-ai]
  (let [room (:room game-state)
        ;; Increment turn at the start and record room state
        state-with-turn (-> game-state
                           (update :turn inc)
                           (append-tx {:type :turn-started
                                      :turn (inc (:turn game-state))
                                      :room-cards (vec room)}))]
    (if (player/should-skip-room? player-ai state-with-turn room)
      ;; Player chose to skip
      (skip-room state-with-turn)
      ;; Player chose to play cards
      (let [chosen-cards (player/choose-cards player-ai state-with-turn room)
            state-after-play (play-cards-in-order state-with-turn chosen-cards)]
        ;; If game is still ongoing, complete the room
        (if (= (:status state-after-play) :playing)
          (complete-room state-after-play)
          state-after-play)))))

(defn play-game
  "Play a complete game to completion using player AI.
  Returns final game state with :status of :won or :lost."
  [player-ai]
  (loop [state (init-game-state)
         turn-count 0
         max-turns 100] ; Safety limit to prevent infinite loops
    (cond
      ;; Game ended
      (not= (:status state) :playing)
      (assoc state :turns-played turn-count)

      ;; Safety limit reached
      (>= turn-count max-turns)
      (assoc state :status :failed :reason :max-turns-exceeded :turns-played turn-count)

      ;; Continue playing
      :else
      (recur (play-turn state player-ai) (inc turn-count) max-turns))))
