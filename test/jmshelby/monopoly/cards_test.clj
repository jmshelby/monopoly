(ns jmshelby.monopoly.cards-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.cards :as cards]))

;; Types:
;; - pay
;;   - w/multiplier
;;     - # players/houses/hotels
;; - collect
;;   - w/multiplier, # players
;; - retain (get out of jail free)
;; - incarcerate (jail)
;; - move
;;   - "go"
;;   - specific property
;;   - next util
;;   - next railroad

(deftest draw-move-back
  (let [;; Setup game
        before (-> (core/init-game-state 4)
                   ;; with current player landing on community chest
                   (update-in [:current-turn :dice-rolls] conj [3 4])
                   (assoc-in [:players 0 :cell-residency] 7)
                   ;; A certain amount of money
                   (assoc-in [:players 0 :cash] 1500)
                   ;; and just a "move back" card available
                   (assoc-in [:card-queue :chance]
                             [{:text           "Go Back 3 Spaces",
                               :deck           :chance,
                               :card/effect    :move,
                               :card.move/cell [:back 3]}]))]
    (let [after (cards/apply-card-draw before)]
      ;; Moved back 3 and looped around
      (is (= 4 (get-in after [:players 0 :cell-residency]))
          "Player moved back 3")
      ;; Has a certain amount of cash after
      ;; buying landing on tax AND
      ;; did _not_ collect allowance
      (is (= 1300 (get-in after [:players 0 :cash]))
          "Paid $200, cash balance"))))

(comment


  ;; Setup game
  (-> (core/init-game-state 4)
      ;; with current player landing on chance
      (update-in [:current-turn :dice-rolls] conj [3 4])
      (assoc-in [:players 0 :cell-residency] 7)
      ;; and just a move back card available
      (assoc-in [:card-queue :chance]
                [{:text           "Go Back 3 Spaces",
                  :deck           :chance
                  :card/effect    :move,
                  :card.move/cell [:back 3]}])
      (cards/apply-card-draw)
      )

  )
