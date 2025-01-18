(ns jmshelby.monopoly.trade-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.trade :as trade]))

(deftest validation
  (let [player {:cash       100
                :cards      #{{:deck            :chance
                               :card/effect     :retain
                               :card.retain/use :bail}
                              {:deck            :community-chest
                               :card/effect     :retain
                               :card.retain/use :bail}
                              {:deck            :some-cool-deck
                               :card/effect     :retain
                               :card.retain/use :some-future-thing}}
                :properties {:boardwalk     {:house-count 0 :status :paid}
                             :park-place    {:house-count 1 :status :paid}
                             :baltic-avenue {:house-count 0 :status :mortgaged}}}]

    ;; Cash tests
    (testing "Cash"
      (is (false? (trade/validate-side player {:cash 200})) "not enough -> false")
      (is (true? (trade/validate-side player {:cash 100})) "exact amount -> true")
      (is (true? (trade/validate-side player {:cash 50})) "more than enough -> true"))

    ;; Cards tests
    (testing "Cards"
      ;; One card
      (is (true? (trade/validate-side player {:cards #{{:deck            :chance
                                                        :card/effect     :retain
                                                        :card.retain/use :bail}}}))
          "has 1")
      ;; Two Cards
      (is (true? (trade/validate-side player {:cards #{{:deck            :chance
                                                        :card/effect     :retain
                                                        :card.retain/use :bail}
                                                       {:deck            :community-chest
                                                        :card/effect     :retain
                                                        :card.retain/use :bail}}}))
          "has 2")
      ;; Incorrect card
      (is (false? (trade/validate-side player {:cards #{{:deck            :chance
                                                         :card/effect     :retain
                                                         :card.retain/use :bail}
                                                        {:deck            :invalid
                                                         :card/effect     :retain
                                                         :card.retain/use :bail}}}))
          "has 1, but not the other"))

    ;; Properties tests
    (testing "Properties"
      ;; Nope, doesn't own
      (is (false? (trade/validate-side player {:properties #{:mediterranean-avenue}}))
          "doesn't own -> false")
      ;; Nope, has house
      (is (false? (trade/validate-side player {:properties #{:park-place}}))
          "owns, but built on -> false")
      ;; All good, mortgaged
      (is (true? (trade/validate-side player {:properties #{:baltic-avenue}}))
          "owns, and mortgaged -> true")
      ;; All good, paid
      (is (true? (trade/validate-side player {:properties #{:boardwalk}}))
          "owns, and paid -> true")
      ;; All good, two properties
      (is (true? (trade/validate-side player {:properties #{:boardwalk :baltic-avenue}}))
          "2 properties, both owned -> true"))

    ;; Combined tests
    (testing "Combined resource"
      (is (false? (trade/validate-side player {:cash       200
                                               :cards      #{{:deck            :chance
                                                              :card/effect     :retain
                                                              :card.retain/use :bail}}
                                               :properties #{:boardwalk}})))
      (is (false? (trade/validate-side player {:cash       50
                                               :cards      #{{:deck            :invalid
                                                              :card/effect     :retain
                                                              :card.retain/use :bail}}
                                               :properties #{:boardwalk}})))
      (is (false? (trade/validate-side player {:cash       50
                                               :cards      #{{:deck            :chance
                                                              :card/effect     :retain
                                                              :card.retain/use :bail}}
                                               :properties #{:park-place}})))
      (is (true? (trade/validate-side player {:cash       50
                                              :cards      #{{:deck            :chance
                                                             :card/effect     :retain
                                                             :card.retain/use :bail}}
                                              :properties #{:boardwalk}})))
      (is (true? (trade/validate-side player {:cash       100
                                              :cards      #{{:deck            :chance
                                                             :card/effect     :retain
                                                             :card.retain/use :bail}
                                                            {:deck            :community-chest
                                                             :card/effect     :retain
                                                             :card.retain/use :bail}}
                                              :properties #{:boardwalk :baltic-avenue}}))))))


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
