(ns jmshelby.monopoly.cards
  (:require [jmshelby.monopoly.util :as util]))

;; Invocations to be Required
;;  -

(defn- draw-card
  "Given a game state and deck name, return tuple of card drawn,
  and post-drawn state. Re-shuffles deck if depleted."
  [game-state deck]
  (let [card        (-> game-state :card-queue deck peek)
        ;; TODO - refill/re-shuffle
        ;; card (when-not card ...)
        drawn-state (update-in game-state [:card-queue deck] pop)]
    [drawn-state card]))

;; TODO - Need to always look for :card.retain/effect to add to players retained effects
;;        maybe we can do this on an entry function?

(defn card-effect-dispatch
  [_game-state _player card]
  ;; Simple: single abstract card effect type
  (:card/effect card)
  ;; Composite: multiple sub-card effects
  ;; TODO - If vector, dispatch to a "multi-effect" impl to invoke multiple times?
  )

(defmulti apply-card-effect
  "TODO doc"
  #'card-effect-dispatch)

;; TODO - test this later
;; (defmethod apply-card-effect :card.effect/multi
;;   [game-state card]
;;   ;; Multiple effects, just apply one after the other
;;   (reduce (fn [state sub-card]
;;             (apply-card-effect state sub-card))
;;           game-state
;;           (:card/effect card)))

(defmethod apply-card-effect :default
  [game-state _player _card]
  ;; TODO - bring in this line when it's time to test everything
  ;; (println "!WARN! apply-card-effect dispatch not implemented:" (card-effect-dispatch game-state card))
  game-state)

(defmethod apply-card-effect :retain
  [game-state player card]
  ;; Just add to the list of player's personal cards
  ;; TODO - Do we need a transaction for this specificaly?
  (update-in game-state
             [:players (:player-index player) :cards]
             conj card))

(defmethod apply-card-effect :incarcerate
  [game-state player _card]
  (util/send-to-jail game-state
                     (:id player)
                     [:card :go-to-jail]))

;; (defmethod apply-card-effect :pay
;;   [game-state player card])

;; (defmethod apply-card-effect :collect
;;   [game-state player card])

;; (defmethod apply-card-effect :move
;;   [game-state player card])


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
    (if (= with-tx with-effect)
      (println "_Would_ have applied card: " card)
      (println "Applied card: " card))

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
