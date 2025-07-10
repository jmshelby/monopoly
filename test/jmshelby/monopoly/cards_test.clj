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

(deftest multiplier-logic
  (testing "get-payment-multiplier function"
    (let [game-state (-> (core/init-game-state 4)
                        ;; Give player some properties with houses
                        (assoc-in [:players 0 :properties]
                                  {:boardwalk {:status :paid :house-count 2}
                                   :park-place {:status :paid :house-count 5} ; hotel
                                   :baltic-avenue {:status :paid :house-count 1}}))]
      
      (testing ":house/count multiplier"
        (let [player (get-in game-state [:players 0])
              card {:card.cash/multiplier :house/count}
              multiplier (cards/get-payment-multiplier game-state player card)]
          (is (= 3 multiplier) "Should count houses (2+1, excluding hotel)")))
      
      (testing ":hotel/count multiplier"
        (let [player (get-in game-state [:players 0])
              card {:card.cash/multiplier :hotel/count}
              multiplier (cards/get-payment-multiplier game-state player card)]
          (is (= 1 multiplier) "Should count properties with 5 houses (hotels)")))
      
      (testing ":player/count multiplier"
        (let [player (get-in game-state [:players 0])
              card {:card.cash/multiplier :player/count}
              multiplier (cards/get-payment-multiplier game-state player card)]
          (is (= 3 multiplier) "Should count other players (4 total - 1 current = 3)")))
      
      (testing ":player/count with bankrupted players"
        (let [game-with-bankrupt (assoc-in game-state [:players 1 :status] :bankrupt)
              player (get-in game-with-bankrupt [:players 0])
              card {:card.cash/multiplier :player/count}
              multiplier (cards/get-payment-multiplier game-with-bankrupt player card)]
          (is (= 2 multiplier) "Should count only active other players (3 active - 1 current = 2)")))
      
      (testing "default multiplier"
        (let [player (get-in game-state [:players 0])
              card {:card.cash/multiplier :unknown}
              multiplier (cards/get-payment-multiplier game-state player card)]
          (is (= 1 multiplier) "Should default to 1 for unknown multiplier"))))))

(deftest card-effects-with-multipliers
  (testing "collect card with :player/count multiplier (Birthday card scenario)"
    (let [game-state (core/init-game-state 4)
          player (assoc (get-in game-state [:players 0]) :player-index 0)
          birthday-card {:text "It's your birthday! Collect $10 from each player"
                        :deck :community-chest
                        :card/effect :collect
                        :card.collect/cash 10
                        :card.cash/multiplier :player/count}
          result-state (cards/apply-card-effect game-state player birthday-card)
          cash-gained (- (get-in result-state [:players 0 :cash])
                        (get-in game-state [:players 0 :cash]))]
      (is (= 30 cash-gained) "Should collect $10 × 3 other players = $30")))
  
  (testing "pay card with :player/count multiplier"
    (let [game-state (core/init-game-state 3) ; 3 players total
          player (assoc (get-in game-state [:players 0]) :player-index 0)
          pay-card {:text "Pay each player $25"
                   :deck :chance
                   :card/effect :pay
                   :card.pay/cash 25
                   :card.cash/multiplier :player/count}
          result-state (cards/apply-card-effect game-state player pay-card)
          cash-lost (- (get-in game-state [:players 0 :cash])
                      (get-in result-state [:players 0 :cash]))]
      (is (= 50 cash-lost) "Should pay $25 × 2 other players = $50"))))

(deftest draw
  (testing "a 'Move Back' type card"
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
                                 :card.move/cell [:back 3]}]))
          ;; Run function in test
          after  (cards/apply-card-draw before)]
      ;; Assert, moved back 3 and looped around
      (is (= 4 (get-in after [:players 0 :cell-residency]))
          "Player moved back 3")
      ;; Assert, has a certain amount of cash
      ;; after buying landing on tax AND did
      ;; _not_ collect allowance
      (is (= 1300 (get-in after [:players 0 :cash]))
          "Paid $200 tax, cash balance"))))

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
