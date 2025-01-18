(ns jmshelby.monopoly.trade-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.trade :as trade]))


;; (deftest draw
;;   (testing "a 'Move Back' type card"
;;     (let [;; Setup game
;;           before (-> (core/init-game-state 4)
;;                      ;; with current player landing on community chest
;;                      (update-in [:current-turn :dice-rolls] conj [3 4])
;;                      (assoc-in [:players 0 :cell-residency] 7)
;;                      ;; A certain amount of money
;;                      (assoc-in [:players 0 :cash] 1500)
;;                      ;; and just a "move back" card available
;;                      (assoc-in [:card-queue :chance]
;;                                [{:text           "Go Back 3 Spaces",
;;                                  :deck           :chance,
;;                                  :card/effect    :move,
;;                                  :card.move/cell [:back 3]}]))
;;           ;; Run function in test
;;           after  (cards/apply-card-draw before)]
;;       ;; Assert, moved back 3 and looped around
;;       (is (= 4 (get-in after [:players 0 :cell-residency]))
;;           "Player moved back 3")
;;       ;; Assert, has a certain amount of cash
;;       ;; after buying landing on tax AND did
;;       ;; _not_ collect allowance
;;       (is (= 1300 (get-in after [:players 0 :cash]))
;;           "Paid $200 tax, cash balance"))))


(comment

  (validate-proposal-side
    ;; Player
    {:cash 99}
    ;; Resources
    {:cash 100})

  (validate-proposal-side
    ;; Player
    {:cash  99
     :cards #{{:deck            :chance
               :card/effect     :retain
               :card.retain/use :bail}}}
    ;; Resources
    {:cards #{{:deck            :chance
               :card/effect     :retain
               :card.retain/use :bail}}})

  (validate-proposal-side
    ;; Player
    {:cash       99
     :properties {:lacey-lane   {:status      :mortgaged
                                 :house-count 0}
                  :cool-place   {:status      :paid
                                 :house-count 0}
                  :sweet-street {:status      :paid
                                 :house-count 0}}}
    ;; Resources
    {:properties #{:cool-place :sweet-street}})
  )


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
