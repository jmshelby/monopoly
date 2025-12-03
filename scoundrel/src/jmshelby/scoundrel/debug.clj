(ns jmshelby.scoundrel.debug
  (:require [jmshelby.scoundrel.core :as core]
            [jmshelby.scoundrel.players.smart :as smart]
            [jmshelby.scoundrel.players.greedy :as greedy]
            [jmshelby.scoundrel.players.random :as random]))

(defn test-single-game [player-type]
  (let [player (case player-type
                 :smart (smart/make-smart-player)
                 :greedy (greedy/make-greedy-player)
                 :random (random/make-random-player))
        result (core/play-game player)]
    (println "=== Game Result ===")
    (println "Player Type:" player-type)
    (println "Status:" (:status result))
    (println "Final Health:" (:health result))
    (println "Turns Played:" (:turns-played result))
    (when (= :failed (:status result))
      (println "Failure Reason:" (:reason result)))
    result))

(defn -main []
  (println "\n--- Testing Smart Player ---")
  (test-single-game :smart)

  (println "\n--- Testing Greedy Player ---")
  (test-single-game :greedy)

  (println "\n--- Testing Random Player ---")
  (test-single-game :random))
