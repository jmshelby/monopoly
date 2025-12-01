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
            ;; Defeat with weapon
            (recur health
                   (update weapon :defeated-monsters conj card)
                   (rest remaining-cards)
                   used-potion?
                   wasted-healing)
            ;; Take damage
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

        ;; Sort monsters by value (descending) for weapon usage
        ;; BUT: if we already have defeated monsters, we can only attack
        ;; monsters smaller than the last defeated one
        attackable-limit (if weapon
                          (if-let [last-defeated (peek (:defeated-monsters weapon))]
                            (:value last-defeated)
                            Integer/MAX_VALUE)
                          Integer/MAX_VALUE)

        ;; Separate attackable and unattackable monsters
        attackable-monsters (filter #(< (:value %) attackable-limit) monsters)
        unattackable-monsters (filter #(>= (:value %) attackable-limit) monsters)

        ;; Sort attackable monsters in descending order
        sorted-attackable (vec (reverse (sort-by :value attackable-monsters)))
        ;; Sort unattackable by ascending order (take least damage first)
        sorted-unattackable (vec (sort-by :value unattackable-monsters))

        ;; Build optimal sequence
        sequence (cond
                   ;; If health is critical (< 8), start with potion
                   (and (< health 8) (seq potions))
                   (concat [(first potions)]
                           weapons
                           sorted-attackable
                           sorted-unattackable
                           (rest potions))

                   ;; If we have no weapon and there's one available, equip it first
                   (and (nil? weapon) (seq weapons))
                   (concat [(first weapons)]
                           (rest weapons)
                           sorted-attackable
                           sorted-unattackable
                           potions)

                   ;; Otherwise: weapons, then sorted monsters, then potions
                   :else
                   (concat weapons sorted-attackable sorted-unattackable potions))]
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
  ;; Only skip if:
  ;; 1. We're allowed to skip (didn't skip last room)
  ;; 2. The best possible play still results in death
  (let [can-skip? (not (:skipped-last-room? game-state))
        room-vec (vec room)
        ;; Find the best possible outcome
        evaluations (map #(evaluate-card-choice % room game-state) room-vec)
        best-outcome (apply max-key :score evaluations)
        best-final-health (:final-health best-outcome)]
    ;; Skip if we can and even the best play would kill us
    (and can-skip? (<= best-final-health 0))))
