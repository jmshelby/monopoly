(ns jmshelby.monopoly.core
  (:require [jmshelby.monopoly.definitions :as defs]))

;; TODO - need to determine where and how many "seeds" to store

;; Game state, schema
(def example-state
  {;; The list of players, in their game play order,
   ;; and their current state in the game.
   ;; When a game starts, players will be randomly sorted
   :players [{;; Probably some auto-generated one
              :id                  "some-uuid"
              ;; Status, playing/bankrupt
              :status              :playing
              ;; Current amount of money on hand
              :cash                1
              ;; Special card collection, current set
              :cards               [:get-out-of-jail-free]
              ;; Which cell on the board are they currently in
              :cell-residency      0
              ;; Number of consecutive doubles, current count
              :consecutive-doubles 2
              ;; If on jail cell (haha), visiting or incarcerated
              :jail-status         :visiting-OR-incarcerated-OR-nil-for-none
              ;; The current set of owned "properties", and current state
              :properties          #{{:name        :park-place
                                      :status      :paid-OR-mortgaged
                                      :house-count 0}}}]

   ;; Separately, track what is going on with the current "turn".
   ;; At any given type there is always a single player who's turn it is,
   ;; but other things can be happening at the same time.
   :current-turn {:player "player uuid"
                  ;; During a "turn", the player can be in different phases,
                  ;; pre-roll or post-roll (anything else?)
                  :phase  :pre-roll
                  ;; Some sort of state around the comm status with the player
                  ;; TODO - need to figure out what this looks like
                  :status :?
                  }

   ;; The current *ordered* care queue to pull from.
   ;; At the beginning of the game these will be loaded at random,
   ;; when one queue is exhausted, it is randomly filled again.
   ;; TODO - Just the keyword name? Or is a map needed?
   :card-queue {:chance          []
                :community-chest []}

   ;; A list of all game move history, and it's details.
   ;; This is probably more of an enhanced feature...
   :transactions [
                  ;; Types:
                  ;; - Player rolls
                  ;;   - Player Current State? (or just ID?)
                  ;;   - Die numbers
                  ;;   - From spot -> to spot -> to redirected spot (like going to jail)
                  ;; - Player Draws Card
                  ;;   - Card type
                  ;;   - Card id
                  ;;   - Kept Card?
                  ;;   - Action? (or can/should that always be another transaction)
                  ;; - [Player Card Action?]
                  ;; - Player makes offer
                  ;;   - Player from/to
                  ;;   - Is counter offer?
                  ;;   - Status: accepted/rejected/countered
                  ;; - Player goes bankrupt
                  ;; etc ...

                  ]

   })


(defn init-game-state
  ;; Very early version of this function,
  ;; just creates n # of simple players
  [player-count]
  (let [;; Define initial player state
        players
        (->> (range 1 (inc player-count))
             (map (partial hash-map :id))
             ;; Add starting state values
             (map #(assoc %
                          :status :playing
                          :cash 1500
                          :cell-residency 0 ;; All starting on "Go"
                          :cards []
                          :properties #{}
                          :consecutive-doubles 0)))
        ;; Define initial game state
        initial-state
        {:players      players
         :current-turn {:player (-> players first :id)
                        :phase  :pre-roll}
         ;; Grab and preserve the default board/layout
         :board        defs/board
         ;; Shuffle all cards by deck
         :card-queue   (->> defs/board
                            :cards
                            (group-by :deck)
                            (map (fn [[deck cards]]
                                   ;; TODO - this should be the card name/id when we have one
                                   [deck (mapcat #(repeat (:count % 1)
                                                          (:text %)) cards)]))
                            (map (fn [[deck cards]]
                                   [deck (shuffle cards)]))
                            (into {}))}]
    ;; Just return this state
    initial-state))


(comment

  (->> (init-game-state 4)
       )




  )



;;
