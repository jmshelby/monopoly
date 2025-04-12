(ns jmshelby.monopoly.player
  (:require [jmshelby.monopoly.util :as util]
            [jmshelby.monopoly.cards :as cards]))

(defn- net-worth
  [game-state player]
  ;; Just need to add cash to property value
  (+ (:cash player)
     (util/player-property-sell-worth
       game-state (:id player))))

(defn- reset-player-assets
  [game-state player]
  (let [pidx (:player-index player)]
    (-> game-state
        ;; Re/Set player state for bankruptcy
        (assoc-in [:players pidx :cash] 0)
        (assoc-in [:players pidx :cards] #{})
        (assoc-in [:players pidx :properties] {})
        (assoc-in [:players pidx :status] :bankrupt))))

(defn- transfer-cash
  "Given a game state, from and to entities, and an amount;
  deduct amount 'from' one player 'to' other player. Accepts
  bank as the from or to player ID. This is a lower level
  function and does *not* verify funds are available, nor
  does it trigger additional higher level workflows like
  raising money or bankruptcy."
  [game-state amount from to]
  (letfn [(txact [gs p op]
            (update-in gs [:players (:player-index p) :cash]
                       op amount))]
    (cond-> game-state
      (not= :bank to)   (txact to +)
      (not= :bank from) (txact from -))))

;; NOTE - mostly taken from trade/exchange-properties NS/fn
(defn- transfer-property
  ;; TODO - do mortgaged/acquistion workflow logic
  ;;        TODO - as a part of this, should we signal something in the game-state that we're in the middle of a bankruptcy asset transfer???
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

(defn- bankrupt-to-bank
  [game-state player]
  (let [;; Get retained cards
        retain-cards
        (->> game-state
             (get-in [:players (:player-index player) :cards])
             (filter #(= :retain (:card/effect %))))]
    ;; Put retained cards back into their decks (bottom)
    (cards/add-to-deck-queues game-state retain-cards)
    ;; TODO - when we have a bank "house inventory", return houses back to it
    ;; TODO - finish

    )
  )

(defn- bankrupt-to-player
  [game-state debtor debtee]

  (-> game-state
      (bankrupt-player pidx)
      ;; TODO - We also need to sell off half price per house
      ;;        (simulating selling them back to the bank
      ;;         right before paying debtee)
      (pay-debtee (:cash player))
      (transfer-property debtor debtee
                         (-> player :properties keys)))
  )


(defn make-requisite-payment
  "Given a game-state, player, and amount of cash, perform a requisite payment if possible.
  Four things can happen:
  - player has enough cash
  - player doesn't have enough cash, but does have net worth..
  - [owes bank] player doesn't have enough net worth to cover the amount needed
  - [owes opponent] player doesn't have enough net worth to cover the amount needed"
  [game-state
   debtor-id debtee-id
   amount follow-up]
  ;; TODO - Should we also add the transaction?
  (let [debtor (util/player-by-id game-state debtor-id)
        debtee (util/player-by-id game-state debtee-id)]
    (cond
      ;; Player has enough cash
      ;;  -> Just deduct, and custom follow-up
      (<= amount (:cash debtor))
      (-> game-state
          (transfer-cash amount player debtee)
          follow-up)
      ;; Player doesn't have enough cash,
      ;; but does have net worth..
      ;;  -> Force raise money workflow
      ;;  -> Then deduct, and custom follow-up
      (<= amount (net-worth game-state debtor))
      (-> game-state
          ;; TODO - raise money workflow
          (transfer-cash amount player debtee)
          follow-up)
      ;; Bankrupt to bank
      ;;  -> Bankruptcy sequence, no custom follow-up
      (= :bank debtee-id)
      (-> game-state
          (bankrupt-to-bank debtor)
          (reset-player-assets debtor))
      ;; Bankrupt to other player
      ;;  -> Bankrupcy flow
      ;;  -> Property + cash transfer/acquistion workflow
      ;;  -> No custom follow-up
      debtee
      (-> game-state
          (bankrupt-to-player debtor debtee)
          (reset-player-assets debtor))
      ;; Anything else is an invalid state
      :else
      nil ;; TODO - throw exception
      )))
