(ns jmshelby.monopoly.player
  (:require [jmshelby.monopoly.util :as util]))

;; TODO - in order to implement the aquisition/transfer logic, we need to indicate the target entity of the cash
(defn make-requisite-payment
  "Given a game-state, player, and amount of cash, perform a requisite payment if possible.
  Four things can happen:
  - player has enough cash
     -> subtract cash and execute follow-up, returning the new game-state
  - player doesn't have enough cash, but does have net worth..
     -> execute 'raise money' workflow, force player to raise enough money and when they are there...
     -> subtract the needed amount
     -> execute follow-up logic, returning the new game-state
  - [owes bank] player doesn't have enough net worth to cover the amount needed
     -> execute 'bankruptcy' workflow, but *don't* execute follow-up logic
     -> mark player as bankrupt
     -> mark player cash at zero
     -> put all houses back into inventory
     -> put all props back into inventory
  - [owes opponent] player doesn't have enough net worth to cover the amount needed
     -> execute 'bankruptcy' workflow, but *don't* execute follow-up logic
     -> mark player as bankrupt
     -> transfer player cash to opponent
     -> put all houses back into inventory
     -> execute property transfer/acquistion workflow to opponent
        (asking target player their mortgaged options and applying)
  "
  [game-state player-id amount follow-up]

  ;; TODO - WIP .... This should do the simple first goal
  ;; first iteration: just do what it does now ...
  (let [player         (util/player-by-id game-state player-id)
        pidx           (:player-index player)
        with-deduction (update-in game-state
                                  [:players pidx :cash]
                                  - amount)]
    (follow-up with-deduction)))
