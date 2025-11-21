(ns jmshelby.monopoly.jail-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.core :as c]))

;; Run several random end game simulations to test jail-related rules
(deftest jail-double-roll-rules-test
  (let [sim-count 250
        _         (println "Running" sim-count "game simulations for jail rule testing")
        sims      (time
                   (doall
                    (pmap (fn [n]
                            [n (c/rand-game-end-state 4 2000)])
                          (range 1 (inc sim-count)))))]
    (println "Running" sim-count "game simulations...DONE")

    (testing "Rolling doubles to escape jail should not grant extra roll"
      (doseq [[n sim] sims]
        (let [transactions (:transactions sim)
              ;; Find all bail transactions where player escaped via doubles
              bail-via-doubles-txs (->> transactions
                                        (filter #(and (= :bail (:type %))
                                                      (= :roll (second (:means %)))
                                                      (= :double (nth (:means %) 2)))))
              ;; For each bail-via-doubles, check if there's an immediate subsequent roll by same player
              illegal-double-rolls (for [bail-tx bail-via-doubles-txs
                                         :let [bail-idx (.indexOf transactions bail-tx)
                                               player-id (:player bail-tx)
                                               ;; Get next few transactions after bail
                                               next-txs (take 5 (drop (inc bail-idx) transactions))
                                               ;; Find if there's another :roll by same player before turn ends
                                               illegal-roll (first (filter #(and (= :roll (:type %))
                                                                                 (= player-id (:player %)))
                                                                           next-txs))]
                                         :when illegal-roll]
                                     {:simulation n
                                      :player player-id
                                      :bail-tx bail-tx
                                      :illegal-roll illegal-roll})]
          (is (empty? illegal-double-rolls)
              (str "Simulation " n " has illegal extra rolls after escaping jail with doubles: "
                   (pr-str illegal-double-rolls))))))))
