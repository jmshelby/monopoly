(ns jmshelby.monopoly.util-test
  (:require [clojure.test :refer :all]
            [jmshelby.monopoly.util :as u]
            [jmshelby.monopoly.core :as c]))

(deftest sum-test
  (is (= 0 (u/sum [])))
  (is (= 9 (u/sum [9])))
  (is (= 15 (u/sum [1 2 3 4 5])))
  (is (= 15 (u/sum [5 4 3 2 1]))))

(deftest property
  ;; For now we'll just exercise this function across a number of random game simulations
  (testing "player-property-sell-worth"
    (testing "exercise"
      (let [sim-count 50
            _         (println "Running" sim-count "game simulations")
            sims      (time
                        (doall
                          (pmap (fn [n]
                                  (let [players    (+ 2 (rand-int 5))
                                        iterations (+ 20 (rand-int 500))
                                        state      (c/rand-game-state players
                                                                      iterations)
                                        ;; Invoke the fn-in-test here
                                        appended
                                        (update state :players
                                                (fn [players]
                                                  (map (fn [player]
                                                         (assoc player :prop-sell-worth
                                                                (u/player-property-sell-worth state (:id player))))
                                                       players)))
                                        ]
                                    [[n players iterations] appended])
                                  )
                                (range 1 (inc sim-count)))))]
        (println "Running" sim-count "game simulations...DONE")
        (println "Sims:")
        (doseq [[[n players iterations] sim] sims]
          (println "  [" players "/" iterations "/" (-> sim :transactions count) "]"
                   "->" (->> sim :players (map :prop-sell-worth))
                   )))
      ;; TODO - maybe assert that they are all integers??

      )
    )
  )
