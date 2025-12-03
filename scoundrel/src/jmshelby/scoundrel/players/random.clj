(ns jmshelby.scoundrel.players.random
  (:require [jmshelby.scoundrel.player :as player]))

;; Random player - makes completely random decisions
;; Useful for baseline testing and simulation

(defn make-random-player
  "Create a random player"
  []
  {:type :random})

(defmethod player/choose-cards :random
  [_player _game-state room]
  ;; Randomly select 3 cards from the 4 in the room
  (vec (take 3 (shuffle (vec room)))))

(defmethod player/should-skip-room? :random
  [_player _game-state _room]
  ;; Never skip rooms (too random would make games unpredictable)
  false)
