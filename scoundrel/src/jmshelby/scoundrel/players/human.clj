(ns jmshelby.scoundrel.players.human
  (:require [jmshelby.scoundrel.player :as player]
            [jmshelby.scoundrel.definitions :as def]
            [jmshelby.scoundrel.game-viewer :as viewer]
            [clojure.string :as str]))

;; Human player - prompts the user for decisions via CLI

(defn make-human-player
  "Create a human player (interactive CLI)"
  []
  {:type :human})

(defn display-game-state
  "Display current game state to the user"
  [game-state]
  (let [health (:health game-state)
        weapon (:equipped-weapon game-state)
        deck-remaining (count (:deck game-state))
        turn (:turn game-state)]
    (println "\n" (str/join (repeat 70 "═")))
    (println "🎲 TURN" turn)
    (println (viewer/health-bar health 20))
    (when weapon
      (let [weapon-card (:card weapon)
            defeated (count (:defeated-monsters weapon))
            last-defeated (peek (:defeated-monsters weapon))
            constraint-str (if last-defeated
                            (str " (can only attack < " (:value last-defeated) ")")
                            "")]
        (println "🗡️  Weapon:" (viewer/card-str weapon-card)
                 (str "[" (:value weapon-card) "]")
                 (when (> defeated 0)
                   (str "- defeated " defeated " monster" (when (> defeated 1) "s")))
                 constraint-str)))
    (println "📚 Deck remaining:" deck-remaining "cards")
    (println (str/join (repeat 70 "─")))))

(defn display-room
  "Display room cards with indices"
  [room]
  (let [cards (vec room)
        indexed-cards (map-indexed vector cards)]
    (println "\n🃏 ROOM CARDS:")
    (doseq [[idx card] indexed-cards]
      (let [type-icon (viewer/card-type-icon card)
            card-str (viewer/card-str card)
            value (:value card)
            type-name (name (def/card-type card))]
        (println (format "  [%d] %s %s [%d] - %s"
                        (inc idx)
                        type-icon
                        card-str
                        value
                        type-name))))))

(defn prompt-yes-no
  "Prompt user for yes/no decision"
  [question]
  (print (str question " (y/n): "))
  (flush)
  (let [response (str/lower-case (str/trim (read-line)))]
    (case response
      ("y" "yes") true
      ("n" "no") false
      (do
        (println "Invalid input. Please enter 'y' or 'n'.")
        (recur question)))))

(defn read-integer
  "Read an integer from user input with validation"
  [prompt min-val max-val]
  (loop []
    (print (str prompt ": "))
    (flush)
    (let [result (try
                   (let [input (str/trim (read-line))
                         num (Integer/parseInt input)]
                     (if (and (>= num min-val) (<= num max-val))
                       {:valid true :value num}
                       (do
                         (println (format "Please enter a number between %d and %d." min-val max-val))
                         {:valid false})))
                   (catch Exception _
                     (println "Invalid number. Please try again.")
                     {:valid false}))]
      (if (:valid result)
        (:value result)
        (recur)))))

(defn prompt-card-to-leave
  "Prompt user to choose which card to leave in the room"
  [room]
  (let [cards (vec room)
        num-cards (count cards)]
    (println "\n❓ You must choose ONE card to LEAVE in the room.")
    (println "   You will play the other 3 cards in the order you specify.")
    (let [choice (read-integer
                  (format "Which card to LEAVE? (1-%d)" num-cards)
                  1
                  num-cards)]
      (nth cards (dec choice)))))

(defn prompt-card-order
  "Prompt user for the order to play cards"
  [cards-to-play]
  (loop []
    (println "\n📋 Now choose the ORDER to play these cards:")
    (let [indexed-cards (map-indexed #(vector %1 %2) cards-to-play)]
      (doseq [[idx card] indexed-cards]
        (println (format "  [%d] %s %s [%d] - %s"
                        (inc idx)
                        (viewer/card-type-icon card)
                        (viewer/card-str card)
                        (:value card)
                        (name (def/card-type card))))))
    (println "\nEnter the order as comma-separated numbers (e.g., '2,1,3'):")
    (print "> ")
    (flush)
    (let [result (try
                   (let [input (str/trim (read-line))
                         parts (str/split input #",")
                         indices (map #(Integer/parseInt (str/trim %)) parts)]
                     (if (and (= (count indices) (count cards-to-play))
                              (= (set indices) (set (range 1 (inc (count cards-to-play))))))
                       {:valid true :value (mapv #(nth cards-to-play (dec %)) indices)}
                       (do
                         (println "Invalid order. Please use each number 1-" (count cards-to-play) " exactly once.")
                         {:valid false})))
                   (catch Exception _
                     (println "Invalid input. Please use format like: 1,2,3")
                     {:valid false}))]
      (if (:valid result)
        (:value result)
        (recur)))))

(defmethod player/should-skip-room? :human
  [_player game-state room]
  (display-game-state game-state)
  (display-room room)

  ;; Check if skipping is allowed
  (if (:skipped-last-room? game-state)
    (do
      (println "\n⚠️  You CANNOT skip this room (you skipped the last one).")
      false)
    (do
      (println "\n💡 You may choose to SKIP this room:")
      (println "   - All cards go to bottom of deck")
      (println "   - You get 4 new cards")
      (println "   - You cannot skip the next room")
      (prompt-yes-no "Do you want to SKIP this room?"))))

(defmethod player/choose-cards :human
  [_player game-state room]
  (let [room-vec (vec room)
        card-to-leave (prompt-card-to-leave room)
        cards-to-play (vec (remove #{card-to-leave} room))]
    (println "\n✅ Leaving:" (viewer/card-str card-to-leave)
             "[" (:value card-to-leave) "]"
             "-" (name (def/card-type card-to-leave)))
    (prompt-card-order cards-to-play)))
