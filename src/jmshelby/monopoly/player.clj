(ns jmshelby.monopoly.player
  (:require [jmshelby.monopoly.util :as util]))

(defn- net-worth
  [game-state player]
  ;; Just need to add cash to property value
  (+ (:cash player)
     (util/player-property-sell-worth
       game-state (:id player))))

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
  (let [player    (util/player-by-id game-state debtor)
        pidx      (:player-index player)
        net-worth (partial net-worth game-state)]

    (cond

      ;; -> player has enough cash
      (<= amount (:cash player))
      ;; * subtract cash and execute follow-up, returning the new game-state
      (-> game-state
          (update-in [:players pidx :cash]
                     - amount)
          follow-up)

      ;; -> player doesn't have enough cash, but does have net worth..
      (<= amount (net-worth player))
      (-> game-state
          ;; * execute 'raise money' workflow, force player to raise enough money and when they are there...
          ;; * subtract cash and execute follow-up, returning the new game-state
          (update-in [:players pidx :cash]
                     - amount)
          follow-up)

      ;; -> [owes bank] player doesn't have enough net worth to cover the amount needed
      (= :bank debtee)
      ;; * execute 'bankruptcy' workflow, but *don't* execute follow-up logic
      ;; * mark player as bankrupt
      ;; * mark player cash at zero
      ;; * put all houses back into inventory
      ;; * put all props back into inventory

      ;; -> [owes opponent] player doesn't have enough net worth to cover the amount needed
      (string? debtee) ;; TODO - check that the player exists
      ;; * execute 'bankruptcy' workflow, but *don't* execute follow-up logic
      ;; * mark player as bankrupt
      ;; * transfer player cash to opponent
      ;; * put all houses back into inventory
      ;; * execute property transfer/acquistion workflow to opponent
      ;;   (asking target player their mortgaged options and applying)

      :else
      nil ;; TODO - throw exception
      )



    ))
