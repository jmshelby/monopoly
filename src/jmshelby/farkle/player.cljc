(ns jmshelby.farkle.player
  (:require [jmshelby.farkle.util :as util]))

;; Player decision API for Farkle
;;
;; Players implement a decision function that takes:
;; - game-state: current game state
;; - player-id: the player making the decision
;; - context: map with current turn information
;;   - :last-roll - the most recent dice roll (if any)
;;   - :turn-score - current accumulated score for this turn
;;   - :available-dice - number of dice available to roll
;;
;; Returns a decision map with :action and optional parameters:
;; {:action :roll}                           ; Roll the available dice
;; {:action :score :dice [1 5]}              ; Score specific dice
;; {:action :bank}                           ; Bank current turn score and end turn

(defn validate-dice-selection
  "Validate that selected dice are scorable and available"
  [dice last-roll]
  (let [dice-freq (frequencies dice)
        roll-freq (frequencies last-roll)]
    ;; Check all selected dice were in the roll
    (every? (fn [[die count]]
              (<= count (get roll-freq die 0)))
            dice-freq)))

(defn suggest-greedy-score
  "Suggest scoring all available scoring dice (greedy strategy)"
  [dice]
  (let [options (util/calculate-all-scoring-options dice)
        best (apply max-key :score options)]
    (:used-dice best)))

(defn calculate-risk
  "Calculate risk factor for continuing vs banking
  Returns a value 0-1 where higher = more risky"
  [turn-score available-dice]
  (let [;; More dice = less risk
        dice-risk (/ (- 6 available-dice) 6.0)
        ;; Higher turn score = more to lose
        score-risk (min 1.0 (/ turn-score 2000.0))]
    ;; Weighted average
    (* 0.6 dice-risk 0.4 score-risk)))

(defn should-bank?
  "Decide if player should bank based on turn score and risk"
  [turn-score available-dice total-score risk-tolerance]
  (let [risk (calculate-risk turn-score available-dice)]
    (or
     ;; High turn score
     (> turn-score (* 1000 risk-tolerance))
     ;; High risk situation
     (> risk risk-tolerance)
     ;; Close to winning
     (and (> total-score 8000)
          (> turn-score 500)))))
