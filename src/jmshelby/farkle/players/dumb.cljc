(ns jmshelby.farkle.players.dumb
  (:require [jmshelby.farkle.util :as util]
            [jmshelby.farkle.player :as player]))

;; Simple AI player for Farkle
;; Strategy:
;; - Always score all available scoring dice (greedy)
;; - Bank when turn score >= 500 OR only 1-2 dice remaining
;; - Conservative play to minimize farkle risk

(defn decide
  "Simple decision function for AI player"
  [game-state player-id context]
  (let [{:keys [last-roll turn-score available-dice]} context
        player (util/player-by-id game-state player-id)
        total-score (:total-score player)
        on-board? (:on-board? player)
        min-entry (get-in game-state [:rules :min-entry-score])]

    (cond
      ;; First action of turn - roll the dice
      (nil? last-roll)
      {:action :roll}

      ;; Just rolled - need to score the dice
      (and last-roll (= available-dice (count last-roll)))
      (let [scorable-dice (player/suggest-greedy-score last-roll)]
        (if (seq scorable-dice)
          {:action :score
           :dice (vec scorable-dice)}
          ;; No scoring dice - this is a farkle (will be handled by game engine)
          {:action :roll}))

      ;; Already scored some dice - decide whether to continue or bank
      :else
      (let [;; If not on board yet, need at least min-entry to bank
            can-bank? (or on-board? (>= (+ total-score turn-score) min-entry))
            ;; Risk assessment
            should-bank? (and can-bank?
                              (or
                               ;; Good score accumulated
                               (>= turn-score 500)
                               ;; Only 1-2 dice left (high risk)
                               (<= available-dice 2)
                               ;; Close to winning and have decent score
                               (and (>= total-score 8000)
                                    (>= turn-score 300))))]
        (if should-bank?
          {:action :bank}
          {:action :roll})))))
