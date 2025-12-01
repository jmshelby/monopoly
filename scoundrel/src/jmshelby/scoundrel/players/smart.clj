(ns jmshelby.scoundrel.players.smart
  (:require [jmshelby.scoundrel.player :as player]
            [jmshelby.scoundrel.definitions :as def]
            [jmshelby.scoundrel.core :as core]))

;; Smart player - plays strategically like an experienced human
;; Strategy:
;; - Plan which card to leave in the room (leave worst card)
;; - Optimize card order to maximize weapon usage
;; - Calculate if room is survivable before skipping
;; - Use potions strategically for survival

(defn make-smart-player
  "Create a smart player"
  []
  {:type :smart})

(def max-health 20)

(defn simulate-card-sequence
  "Simulate playing a sequence of cards and return final health and wasted healing"
  [initial-health equipped-weapon cards]
  (loop [health initial-health
         weapon equipped-weapon
         remaining-cards cards
         used-potion? false
         wasted-healing 0]
    (if (empty? remaining-cards)
      {:final-health health :wasted-healing wasted-healing}
      (let [card (first remaining-cards)
            card-type (def/card-type card)]
        (case card-type
          :weapon
          (recur health
                 {:card card :defeated-monsters []}
                 (rest remaining-cards)
                 used-potion?
                 wasted-healing)

          :monster
          (if (and weapon
                   (if-let [last-defeated (peek (:defeated-monsters weapon))]
                     (< (:value card) (:value last-defeated))
                     true)) ; Can attack if no monsters defeated yet
            ;; Fight with weapon: damage = monster_value - weapon_value (min 0)
            (let [weapon-value (:value (:card weapon))
                  monster-value (:value card)
                  damage (max 0 (- monster-value weapon-value))]
              (recur (- health damage)
                     (update weapon :defeated-monsters conj card)
                     (rest remaining-cards)
                     used-potion?
                     wasted-healing))
            ;; Take full damage without weapon
            (recur (- health (:value card))
                   weapon
                   (rest remaining-cards)
                   used-potion?
                   wasted-healing))

          :potion
          (if used-potion?
            ;; Already used a potion, no effect
            (recur health weapon (rest remaining-cards) used-potion? wasted-healing)
            ;; Use potion (cap at max-health)
            (let [potion-value (:value card)
                  actual-healing (min potion-value (- max-health health))
                  new-wasted (- potion-value actual-healing)
                  new-health (min max-health (+ health potion-value))]
              (recur new-health
                     weapon
                     (rest remaining-cards)
                     true
                     (+ wasted-healing new-wasted)))))))))

(defn find-best-card-order
  "Find the best order to play cards to maximize survival and weapon usage"
  [cards game-state]
  (let [health (:health game-state)
        weapon (:equipped-weapon game-state)
        monsters (filter #(= :monster (def/card-type %)) cards)
        weapons (filter #(= :weapon (def/card-type %)) cards)
        potions (filter #(= :potion (def/card-type %)) cards)

        ;; Sort weapons in ascending order (equip weak ones first, keep strongest)
        sorted-weapons (vec (sort-by :value weapons))
        best-new-weapon (last sorted-weapons)

        ;; Check if we should preserve the best weapon for future rooms
        ;; Preserve if: have current weapon, best new weapon is significantly better (+4),
        ;; and there are monsters to fight
        current-weapon-value (if weapon (:value (:card weapon)) 0)
        best-new-weapon-value (if best-new-weapon (:value best-new-weapon) 0)
        weapon-upgrade-significant? (>= (- best-new-weapon-value current-weapon-value) 4)
        should-preserve-best-weapon? (and weapon
                                          best-new-weapon
                                          weapon-upgrade-significant?
                                          (seq monsters)
                                          (>= health 12)) ; Only preserve if healthy enough

        ;; Determine attackable limit based on weapon situation
        attackable-limit (if (seq weapons)
                          ;; New weapon in room - but consider current weapon if preserving
                          (if should-preserve-best-weapon?
                            ;; Use current weapon's constraint for fighting monsters
                            (if-let [last-defeated (peek (:defeated-monsters weapon))]
                              (:value last-defeated)
                              Integer/MAX_VALUE)
                            ;; Fresh weapon, no constraints
                            Integer/MAX_VALUE)
                          ;; No new weapon - use current weapon's constraint
                          (if weapon
                            (if-let [last-defeated (peek (:defeated-monsters weapon))]
                              (:value last-defeated)
                              Integer/MAX_VALUE)
                            Integer/MAX_VALUE))

        ;; Separate attackable and unattackable monsters
        attackable-monsters (filter #(< (:value %) attackable-limit) monsters)
        unattackable-monsters (filter #(>= (:value %) attackable-limit) monsters)

        ;; Sort potions in descending order (use highest value first, minimize waste)
        sorted-potions (vec (reverse (sort-by :value potions)))
        ;; Sort attackable monsters in descending order
        sorted-attackable (vec (reverse (sort-by :value attackable-monsters)))
        ;; Sort unattackable by ascending order (take least damage first)
        sorted-unattackable (vec (sort-by :value unattackable-monsters))

        ;; Build optimal sequence
        sequence (cond
                   ;; If health is critical (< 8), start with potion
                   (and (< health 8) (seq sorted-potions))
                   (concat [(first sorted-potions)]
                           sorted-weapons
                           sorted-attackable
                           sorted-unattackable
                           (rest sorted-potions))

                   ;; If we have no weapon and there's one available, equip it first
                   (and (nil? weapon) (seq sorted-weapons))
                   (concat [(first sorted-weapons)]
                           (rest sorted-weapons)
                           sorted-attackable
                           sorted-unattackable
                           sorted-potions)

                   ;; WEAPON PRESERVATION STRATEGY
                   ;; If upgrading to a significantly better weapon, preserve it for future rooms
                   ;; Fight monsters with current/weaker weapons, then equip best weapon last
                   should-preserve-best-weapon?
                   (let [weaker-weapons (vec (butlast sorted-weapons))] ; All except best
                     (concat weaker-weapons
                             sorted-attackable
                             sorted-unattackable
                             [(last sorted-weapons)]  ; Best weapon last
                             sorted-potions))

                   ;; Otherwise: weapons, then sorted monsters, then potions
                   :else
                   (concat sorted-weapons sorted-attackable sorted-unattackable sorted-potions))]
    (vec sequence)))

(defn evaluate-card-choice
  "Evaluate leaving out a specific card - return expected final health and wasted healing"
  [card-to-leave room game-state]
  (let [cards-to-play (vec (remove #{card-to-leave} room))
        ordered-cards (find-best-card-order cards-to-play game-state)
        simulation (simulate-card-sequence
                    (:health game-state)
                    (:equipped-weapon game-state)
                    ordered-cards)
        final-health (:final-health simulation)
        wasted-healing (:wasted-healing simulation)
        ;; Score: prioritize survival, but avoid wasted healing as tiebreaker
        ;; If health >= 15 (comfortable), penalize wasted healing more heavily
        ;; Otherwise, survival is paramount
        waste-penalty (if (>= final-health 15) (* 5 wasted-healing) wasted-healing)
        score (- (* 100 final-health) waste-penalty)]
    {:card card-to-leave
     :final-health final-health
     :wasted-healing wasted-healing
     :score score
     :order ordered-cards}))

(defmethod player/choose-cards :smart
  [_player game-state room]
  (let [room-vec (vec room)
        ;; Evaluate each possible card to leave out
        evaluations (map #(evaluate-card-choice % room game-state) room-vec)
        ;; Choose the option with the best score (max health, min wasted healing)
        best-choice (apply max-key :score evaluations)]
    (:order best-choice)))

(defmethod player/should-skip-room? :smart
  [_player game-state room]
  ;; Skip if:
  ;; 1. We're allowed to skip (didn't skip last room) AND
  ;; 2. Either:
  ;;    a) Best possible play results in death, OR
  ;;    b) Room wastes too many potions (multiple potions, high waste, not desperate)
  (let [can-skip? (not (:skipped-last-room? game-state))
        room-vec (vec room)
        current-health (:health game-state)

        ;; Count potions in room
        potion-count (count (filter #(= :potion (def/card-type %)) room))

        ;; Find the best possible outcome
        evaluations (map #(evaluate-card-choice % room game-state) room-vec)
        best-outcome (apply max-key :score evaluations)
        best-final-health (:final-health best-outcome)
        best-wasted-healing (:wasted-healing best-outcome)

        ;; Skip conditions
        would-die? (<= best-final-health 0)
        excessive-potion-waste? (and (>= potion-count 2)           ; Multiple potions
                                     (>= best-wasted-healing 8)     ; High waste (8+ HP)
                                     (>= current-health 8))]        ; Not desperate for healing
    ;; Skip if we can and either we'd die or waste too many potions
    (and can-skip? (or would-die? excessive-potion-waste?))))
