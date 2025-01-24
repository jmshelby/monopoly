(ns jmshelby.monopoly.players.dumb-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as core]
            [jmshelby.monopoly.players.dumb :as dumb-player]))

(deftest trade-side-value
  (testing "fn trade-side-value"
    ;; Setup
    (let [{:keys [board players]}
          (core/init-game-state 4)
          ;; For convenience/concision
          test-it
          (fn self
            ([side] (self {} side))
            ([props-status side]
             ;; Build player prop state
             (let [player
                   (-> players
                       first
                       (assoc :properties
                              (->> props-status
                                   (map (fn [[name status]]
                                          [name {:status      status
                                                 :house-count 0}]))
                                   (into {}))))]
               (dumb-player/trade-side-value board player side))))]
      (testing "- general"
        (is (= 0 (test-it {}))
            "No keys included -> 0"))

      (testing "- cash"
        (is (= 0 (test-it {:cash 0}))
            "Cash 0 -> 0")
        (is (= 162 (test-it {:cash 162}))
            "Cash x -> x")
        (is (= 542 (test-it {:cash  542
                             :cards #{}}))
            "Cash x, other empty -> x")
        (is (= 542 (test-it {:cash       542
                             :properties #{}}))
            "Cash x, other empty -> x")
        (is (= 542 (test-it {:cash       542
                             :cards      #{}
                             :properties #{}}))
            "Cash x, other empty -> x"))

      (testing "- cards"
        (is (= 0 (test-it {:cards #{}}))
            "Cards empty -> 0")
        (is (= 50 (test-it {:cards #{{:type :bail}}}))
            "1 Card -> 50")
        (is (= 100 (test-it {:cards #{{:deck :chance
                                       :type :bail}
                                      {:deck :chest
                                       :type :bail}}}))
            "2 Card -> 100")
        (is (= 150 (test-it {:cards #{{:deck :chance
                                       :type :bail}
                                      {:deck :chest
                                       :type :bail}
                                      {:deck :lucky
                                       :type :bail}}}))
            "3 Card -> 100"))

      (testing "- properties"
        (is (= 0 (test-it {:properties #{}}))
            "Props empty -> 0")

        (testing "- face value"
          (is (= 350 (test-it
                       {:park-place :paid}
                       {:properties #{:park-place}}))
              "1 Prop -> property price")
          (is (= 150 (test-it
                       {:water-works :paid}
                       {:properties #{:water-works}}))
              "1 Prop -> property price")
          (is (= 160 (test-it
                       {:virginia-ave :paid}
                       {:properties #{:virginia-ave}}))
              "1 Prop -> property price")
          (is (= 460 (test-it
                       {:virginia-ave :paid
                        :pacific-ave  :paid}
                       {:properties #{:virginia-ave
                                      :pacific-ave}}))
              "2 Prop -> property price sum")
          (is (= 460 (test-it
                       {:virginia-ave :paid
                        :pacific-ave  :paid
                        ;; Other owned
                        :boardwalk    :paid}
                       {:properties #{:virginia-ave
                                      :pacific-ave}}))
              "2 Prop -> property price sum")

          (testing "- mortgaged"

            (is (= 75 (test-it
                        {:water-works :mortgaged}
                        {:properties #{:water-works}}))
                "1 Mort Prop -> mortgage price")
            (is (= 130 (test-it
                         {:atlantic-ave :mortgaged}
                         {:properties #{:atlantic-ave}}))
                "1 Mort Prop -> mortgage price")

            (is (= 230 (test-it
                         {:virginia-ave :mortgaged
                          :pacific-ave  :mortgaged
                          ;; Other owned
                          :boardwalk    :paid}
                         {:properties #{:virginia-ave
                                        :pacific-ave}}))
                "2 Mort Prop -> mortgage price sum"))))

      (testing "- mixed"
        (is (= 1448 (test-it
                      {:virginia-ave :mortgaged
                       :pacific-ave  :mortgaged
                       :boardwalk    :paid}
                      {:cash  1398
                       :cards #{{:deck :chance}}}))
            "cash + cards -> sum")
        (is (= 298 (test-it
                     {:virginia-ave :mortgaged
                      :pacific-ave  :mortgaged
                      :boardwalk    :paid}
                     {:cash       68
                      :properties #{:virginia-ave
                                    :pacific-ave}}))
            "cash + props -> sum")
        (is (= 648 (test-it
                     {:virginia-ave :paid
                      :pacific-ave  :mortgaged
                      :boardwalk    :mortgaged}
                     {:cash       188
                      :cards      #{{:deck :chance}
                                    {:deck :lucky}}
                      :properties #{:virginia-ave
                                    :boardwalk}}))
            "cash + cards + props -> sum")))))
