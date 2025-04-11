(ns jmshelby.monopoly.player
  (:require [jmshelby.monopoly.util :as util]
            [jmshelby.monopoly.cards :as cards]))

(defn- net-worth
  [game-state player]
  ;; Just need to add cash to property value
  (+ (:cash player)
     (util/player-property-sell-worth
       game-state (:id player))))

(defn- bankrupt-player
  [game-state pidx]
  (let [;; Get retained cards
        retain-cards (->> game-state
                          (get-in [:players pidx :cards])
                          (filter #(= :retain (:card/effect %))))]
    (-> game-state
        ;; Re/Set player state for bankruptcy
        (assoc-in [:players pidx :cash] 0)
        (assoc-in [:players pidx :cards] #{})
        (assoc-in [:players pidx :properties] {})
        (assoc-in [:players pidx :status] :bankrupt)
        ;; Put retained cards back into their decks (bottom)
        (cards/add-to-deck-queues retain-cards)
        ;; TODO - when we have a bank "house inventory", return houses back to it
        )))

;; NOTE - mostly taken from trade/exchange-properties
(defn- transfer-property
  ;; TODO - do mortgaged/acquistion workflow logic
  [game-state from to prop-names]
  (let [;; Get player maps
        to-pidx     (-> game-state
                        (util/player-by-id to)
                        :player-index)
        from-pidx   (-> game-state
                        (util/player-by-id from)
                        :player-index)
        from-player (get-in game-state [:players from-pidx])
        ;; Get 'from' player property states, only
        ;; needed to preserve mortgaged status
        prop-states (select-keys (:properties from-player) prop-names)]
    (-> game-state
        ;; Remove props from the 'from' player
        (update-in [:players from-pidx :properties]
                   ;; Just a dissoc that takes a set
                   (partial apply dissoc) prop-names)
        ;; Add props + existing state to the 'to' player
        (update-in [:players to-pidx :properties]
                   ;; Just a conj of existing prop states
                   ;; into player's own property state map
                   conj prop-states))))

(defn make-requisite-payment
  "Given a game-state, player, and amount of cash, perform a requisite payment if possible.
  Four things can happen:
  - player has enough cash
  - player doesn't have enough cash, but does have net worth..
  - [owes bank] player doesn't have enough net worth to cover the amount needed
  - [owes opponent] player doesn't have enough net worth to cover the amount needed"
  [game-state
   debtor debtee
   amount follow-up]
  ;; TODO - Should we also perform the transfer to debtee player??
  (let [player     (util/player-by-id game-state debtor)
        pidx       (:player-index player)
        net-worth  (partial net-worth game-state)
        deduct     (fn [gs] (update-in gs [:players pidx :cash]
                                       - amount))
        pay-debtee (fn [gs amount]
                     (let [player (util/player-by-id gs debtee)
                           pidx   (:player-index player)]
                       ;; TODO - We also need to pay half price per house
                       ;;        (simulating selling them back to the bank
                       ;;         right before paying debtee)
                       (update-in gs [:players pidx :cash]
                                  + amount)))]

    (cond

      ;; Player has enough cash
      ;;  -> Just deduct, and custom follow-up
      (<= amount (:cash player))
      (-> game-state
          deduct
          follow-up)

      ;; Player doesn't have enough cash,
      ;; but does have net worth..
      ;;  -> Force raise money workflow
      ;;  -> Then deduct, and custom follow-up
      (<= amount (net-worth player))
      (-> game-state
          ;; TODO - raise money workflow
          deduct
          follow-up)

      ;; Bankrupt to bank
      ;;  -> Bankruptcy sequence, no custom follow-up
      (= :bank debtee)
      (bankrupt-player game-state player)

      ;; Bankrupt to other player
      ;;  -> Bankrupcy flow
      ;;  -> Property + cash transfer/acquistion workflow
      ;;  -> No custom follow-up
      (string? debtee) ;; TODO - check that the player exists
      (-> game-state
          (bankrupt-player pidx)
          (pay-debtee (:cash player))
          (transfer-property debtor debtee
                             (-> player :properties keys)))

      :else
      nil ;; TODO - throw exception
      )

    ))
