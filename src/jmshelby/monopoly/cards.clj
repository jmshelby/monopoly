(ns jmshelby.monopoly.cards
  (:require [clojure.set :as set]
            [jmshelby.monopoly.definitions :as defs]
            [jmshelby.monopoly.util :as util]))

(defn cards->deck-queues
  [cards]
  (->> cards
       ;; Multiple decks, separate queues
       (group-by :deck)
       ;; Multiply certain cards
       (map (fn [[deck cards]]
              [deck (mapcat #(repeat (:count % 1) %) cards)]))
       ;; Do actual shuffle, per deck
       (map (fn [[deck cards]]
              [deck (shuffle cards)]))
       (into {})))

(defn- shuffle-deck
  "Given a game state, and a deck name, replinish the deck with a full shuffled one, in the card queue, minus the retained cards of the other players."
  [game-state deck]
  ;; TODO - We may need to consider the retained cards of inactive players?
  (let [cards    (-> game-state :board :cards)
        retained (->> game-state
                      :players
                      (mapcat :cards)
                      set)
        new-deck (->> retained
                      (set/difference cards)
                      (filter #(= deck (:deck %)))
                      shuffle)]
    (assoc-in game-state [:card-queue deck] new-deck)))

(defn- draw-card
  "Given a game state and deck name, return tuple of card drawn,
  and post-drawn state. Re-shuffles deck if depleted."
  [game-state deck]
  (let [;; First, attempt to get a card
        card        (-> game-state :card-queue deck peek)
        ;; Potential to need to shuffle
        [game-state card]
        (if card
          ;; All good
          [game-state card]
          ;; Replinish/shuffle deck
          (let [new-state (shuffle-deck game-state deck)
                real-card (-> new-state :card-queue deck peek)]
            [new-state real-card]))
        ;; Now that we have a card, remove from deck queue
        drawn-state (update-in game-state [:card-queue deck] pop)]
    ;; Return as pair
    [drawn-state card]))

;; TODO - Need to always look for :card.retain/effect to add to players retained effects
;;        maybe we can do this on an entry function?

(defn card-effect-dispatch
  [_game-state _player card]
  ;; Simple: single abstract card effect type
  (let [effect (:card/effect card)]
    (if (vector? effect)
      ;; Composite of multiple effects, dispatch as special case
      :card.effect/multi
      ;; Single effect on it's own
      effect)))

(defmulti apply-card-effect
  "TODO doc"
  #'card-effect-dispatch)

(defmethod apply-card-effect :card.effect/multi
  [game-state player card]
  ;; Multiple effects, just apply one after the other
  (reduce (fn [state sub-card]
            (apply-card-effect state player sub-card))
          game-state
          (:card/effect card)))

(defmethod apply-card-effect :default
  [game-state player card]
  (println "!WARN! apply-card-effect dispatch not implemented:" (card-effect-dispatch game-state player card))
  game-state)

(defmethod apply-card-effect :retain
  [game-state player card]
  ;; Just add to the list of player's personal cards
  ;; TODO - Do we need a transaction for this specifically?
  (update-in game-state
             [:players (:player-index player) :cards]
             conj card))

(defmethod apply-card-effect :incarcerate
  [game-state player _card]
  ;; Invoke jail workflow, which will do all the work
  (util/send-to-jail game-state
                     (:id player)
                     [:card :go-to-jail]))

(defn get-payment-multiplier
  [game-state player card]
  (case (:card.cash/multiplier card)
    ;; Count of houses this player
    ;; owns, minus properties with >4
    :house/count  (->> player :properties vals
                       (filter #(> 5 (:house-count %) 0))
                       (map :house-count)
                       (apply +))
    ;; Count of player's properties with
    ;; 5 houses on them
    :hotel/count  (->> player :properties vals
                       (filter #(= 5 (:house-count %)))
                       count)
    ;; Count of total active players,
    ;; other than current player
    :player/count (->> game-state :players
                       (filter #(= :playing (:status %)))
                       count
                       dec)
    ;; Default to 1, no multiplier
    1))

(defmethod apply-card-effect :pay
  [game-state player card]
  (let [{player-id :id
         pidx      :player-index}
        player
        mult   (get-payment-multiplier game-state player card)
        amount (* mult (:card.pay/cash card))]
    (-> game-state
        ;; Subtract money
        (update-in [:players pidx :cash] thing amount)
        ;; Track transaction
        (util/append-tx {:type   :payment
                         :from   player-id
                         :to     :bank
                         :amount amount
                         :reason :card
                         :card   card}))))

(defmethod apply-card-effect :collect
  [game-state player card]
  (let [{player-id :id
         pidx      :player-index} player
        ;; TODO - Need to check for :card.collect/multiplier, and apply
        ;;        Could be: :player/count
        amount                    (:card.collect/cash card)]
    (-> game-state
        ;; Add money
        (update-in [:players pidx :cash] + amount)
        ;; Track transaction
        (util/append-tx {:type   :payment
                         :from   :bank
                         :to     player-id
                         :amount amount
                         :reason :card
                         :card   card}))))

;; TODO - I coded these 3 fns quickly, could be refactored
(defn- get-cell-by-type
  [board type-val]
  (->> board
       :cells
       (map-indexed vector)
       (some (fn [[idx cell]]
               (when (= type-val (:type cell))
                 idx)))))

(defn- get-cell-property
  [board name]
  (->> board
       :cells
       (map-indexed vector)
       (some (fn [[idx cell]]
               (when
                   (and (= name (:name cell))
                        (= :property (:type cell)))
                 idx)))))

(defn- get-next-property-type
  ;; TODO - lots of refactoring needed here ...
  [board pos prop-type]
  (let [;; Generate name indexed properties map (easier lookup)
        name->prop
        (->> board
             :properties
             (reduce (fn [acc prop]
                       (assoc acc (:name prop) prop))
                     {}))
        ;; Generate cells w/property details maps attached
        enh-cells
        (->> board
             :cells
             (map-indexed vector)
             (map (fn [[idx cell]]
                    [idx (if (= :property (:type cell))
                           (assoc cell :details (name->prop (:name cell)))
                           cell)])))]
    ;; Cycle through cells...
    (->> enh-cells
         cycle
         ;; Starting at a certain cell position
         (drop pos)
         ;; Find the next cell with the right property type
         (some (fn [[idx cell]]
                 (when (and (= :property (:type cell))
                            (= prop-type
                               (get-in cell [:details :type])))
                   idx))))))

(defmethod apply-card-effect :move
  [{:keys [board]
    :as   game-state}
   player card]
  (let [move           (-> game-state :functions :move-to-cell)
        old-cell       (:cell-residency player)
        [style target] (:card.move/cell card)
        new-cell
        (case style
          :back             (util/next-cell board (* -1 target) old-cell)
          :type             (get-cell-by-type board target)
          :property         (get-cell-property board target)
          ;; TODO - Cheating a bit for now ...
          ;;        Make this smarter so it looks better
          [:type :property] (get-next-property-type board old-cell (second target)))]
    (move game-state new-cell :card)))

;; =====================================================

(defn- apply-card
  "Given a game state and card, apply the draw action and
  effects to current player."
  [game-state card]

  ;; TODO - Transaction can be added first to any dispatch?
  ;;        "draw card" transaction

  (let [player      (util/current-player game-state)
        with-tx     (util/append-tx game-state
                                    {:type   :card-draw
                                     :player (:id player)
                                     :card   card})
        with-effect (apply-card-effect with-tx player card)]

    ;; TEMP - logging if an effect apply didn't do anything
    (when (= with-tx with-effect)
      (println "_Would_ have applied card: " card))

    with-effect))

(defn apply-card-draw
  "Given a game state, when current player is on a card type
  cell, draw card from applicable deck, and apply it's actions
  and effects to the current player. All further possible
  effects, will be invoked and applied, including forced end
  of player turn if required."
  [{:keys [board]
    :as   game-state}]
  (let [{:keys [cell-residency]}
        (util/current-player game-state)
        ;; Get the definition of the current cell
        {deck      :name
         cell-type :type}
        (get-in board [:cells cell-residency])]
    (if-not (= :card cell-type)
      ;; Only apply if we're on a card type
      game-state
      ;; Draw Card, and apply
      (->> deck
           (draw-card game-state)
           (apply apply-card)))))
