(ns jmshelby.scoundrel.players.greedy
  (:require [jmshelby.scoundrel.player :as player]
            [jmshelby.scoundrel.definitions :as def]))

;; Greedy player - uses basic heuristics for card selection
;; Strategy:
;; - Equip weapons when available
;; - Use potions when health is low
;; - Minimize damage taken
;; - Play cards in optimal order

(defn make-greedy-player
  "Create a greedy player"
  []
  {:type :greedy})

(defn card-value-for-sorting
  "Assign a value to a card for sorting purposes.
  Lower values will be played first."
  [card game-state]
  (let [card-type (def/card-type card)
        health (:health game-state)
        has-weapon? (some? (:equipped-weapon game-state))]
    (case card-type
      ;; Potions: Play early if health is low, later if high
      :potion (if (< health 12)
                0  ; Play first when low health
                20) ; Play later when healthy

      ;; Weapons: Play early to start defeating monsters
      :weapon (if has-weapon?
                15 ; Already have weapon, less urgent
                5) ; No weapon yet, prioritize

      ;; Monsters: Play last, after we have weapon/potion
      :monster 25)))

(defn sort-cards-for-play
  "Sort cards in optimal play order based on game state"
  [cards game-state]
  (vec (sort-by #(card-value-for-sorting % game-state) cards)))

(defmethod player/choose-cards :greedy
  [_player game-state room]
  ;; Choose 3 cards from room, leaving out the least useful one
  (let [room-vec (vec room)
        ;; For now, just pick first 3 after sorting
        ;; TODO: Could implement logic to leave out worst card
        sorted-room (sort-cards-for-play room-vec game-state)
        chosen-cards (take 3 sorted-room)]
    (vec chosen-cards)))

(defmethod player/should-skip-room? :greedy
  [_player game-state room]
  ;; Skip if room is too dangerous:
  ;; - Low health (< 8)
  ;; - All 4 cards are monsters
  ;; - Average monster value is high (> 8)
  (let [health (:health game-state)
        room-vec (vec room)
        all-monsters? (every? #(= :monster (def/card-type %)) room-vec)
        avg-value (/ (reduce + (map :value room-vec)) (count room-vec))]
    (and (< health 8)
         all-monsters?
         (> avg-value 8))))
