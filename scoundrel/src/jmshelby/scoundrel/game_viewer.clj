(ns jmshelby.scoundrel.game-viewer
  (:require [jmshelby.scoundrel.core :as core]
            [jmshelby.scoundrel.definitions :as def]
            [jmshelby.scoundrel.players.random :as random]
            [jmshelby.scoundrel.players.greedy :as greedy]
            [jmshelby.scoundrel.players.smart :as smart]
            [clojure.string :as str]))

;; ============================================================================
;; Card Formatting
;; ============================================================================

(def suit-symbols
  {:hearts "♥"
   :diamonds "♦"
   :clubs "♣"
   :spades "♠"})

(def rank-names
  {:ace "A"
   :king "K"
   :queen "Q"
   :jack "J"
   :2 "2" :3 "3" :4 "4" :5 "5" :6 "6"
   :7 "7" :8 "8" :9 "9" :10 "10"})

(defn card-str
  "Format a card as a human-readable string like ♠A or ♥10"
  [card]
  (str (suit-symbols (:suit card)) (rank-names (:rank card))))

(defn card-type-icon
  "Get icon for card type"
  [card]
  (case (def/card-type card)
    :monster "👹"
    :weapon "🗡️"
    :potion "❤️"))

(defn health-bar
  "Create a visual health bar"
  [health max-health]
  (let [percentage (/ health max-health)
        bars (int (* 10 percentage))
        empty-bars (- 10 bars)
        icon (cond
               (<= health 0) "💀"
               (<= health 5) "⚠️"
               (<= health 10) "🔶"
               :else "❤️")]
    (str icon " " health "/" max-health " "
         "[" (str/join (repeat bars "█"))
         (str/join (repeat empty-bars "░")) "]")))

;; ============================================================================
;; Game Event Formatting
;; ============================================================================

(defn format-turn-header
  "Format the turn header with room cards"
  [turn room health max-health weapon]
  (let [room-cards (vec room)
        cards-str (if (empty? room-cards)
                   "(unknown)"
                   (str/join " " (map card-str room-cards)))
        weapon-str (if weapon
                     (str " | Weapon: " (card-str (:card weapon))
                          (when (seq (:defeated-monsters weapon))
                            (str " (defeated: " (count (:defeated-monsters weapon)) ")")))
                     " | No weapon")]
    (str "\n" (str/join (repeat 70 "═")) "\n"
         "🎲 TURN " turn "\n"
         (health-bar health max-health) weapon-str "\n"
         "🃏 Room: " cards-str "\n"
         (str/join (repeat 70 "─")))))

(defn format-card-played
  "Format a card being played with its effect"
  [card effect]
  (let [icon (card-type-icon card)
        card-name (card-str card)]
    (str "  " icon " " card-name " → " effect)))

(defn analyze-turn-transactions
  "Analyze transactions for a single turn and format them"
  [turn-txs game-state-before]
  (let [health-before (:health game-state-before)
        cards-played (filter #(= :card-played (:type %)) turn-txs)
        skipped? (some #(= :room-skipped (:type %)) turn-txs)]

    (if skipped?
      ["  🏃 SKIPPED ROOM (avoiding danger)"]

      (for [card-tx cards-played]
        (let [card (:card card-tx)
              card-type (:card-type card-tx)
              ;; Find effects immediately following this card
              next-txs (drop-while #(not= card-tx %) turn-txs)
              next-tx (second next-txs)]

          (case card-type
            :weapon
            (format-card-played card "Equipped weapon")

            :monster
            (if (and next-tx (= :monster-defeated (:type next-tx)))
              (format-card-played card "⚔️ Defeated with weapon!")
              (let [damage (:damage next-tx)
                    health-after (:health-after next-tx)]
                (format-card-played card
                  (str "💔 Took " damage " damage (HP: " health-before " → " health-after ")"))))

            :potion
            (if (and next-tx (= :healed (:type next-tx)))
              (let [amount (:amount next-tx)
                    wasted (:wasted-healing next-tx 0)
                    health-after (:health-after next-tx)]
                (if (> wasted 0)
                  (format-card-played card
                    (str "💚 Healed " amount " (wasted " wasted ") → " health-after))
                  (format-card-played card
                    (str "💚 Healed " amount " → " health-after))))
              (format-card-played card
                (str "❌ Potion wasted (" (:value card) " HP lost - not first this turn)")))

            (format-card-played card "Unknown effect")))))))

;; ============================================================================
;; Game Replay
;; ============================================================================

(defn display-game
  "Display a completed game state with detailed output"
  [game-state player-type]
  (let [transactions (:transactions game-state)
        final-status (:status game-state)
        final-health (:health game-state)
        turns-played (:turns-played game-state)]

    (println "\n" (str/join (repeat 70 "═")))
    (println "🎮 SCOUNDREL GAME REPLAY")
    (println "Player:" (str/upper-case (name player-type)))
    (println (str/join (repeat 70 "═")))

    ;; Group transactions by turn
    (let [turn-groups (group-by :turn transactions)]
      (doseq [turn (sort (keys turn-groups))]
        (when (> turn 0) ; Skip turn 0 (initial state)
          (let [turn-txs (turn-groups turn)
                ;; Get game state at start of turn by finding room-completed from previous turn
                prev-completed (first (filter #(= :room-completed (:type %))
                                             (turn-groups (dec turn))))
                ;; Approximate health at turn start
                damage-before (reduce + 0 (map :damage
                                             (filter #(and (= :damage-taken (:type %))
                                                         (< (:turn %) turn))
                                                     transactions)))
                healing-before (reduce + 0 (map :amount
                                              (filter #(and (= :healed (:type %))
                                                          (< (:turn %) turn))
                                                      transactions)))
                health-at-start (- (+ 20 healing-before) damage-before)

                ;; Get weapon at turn start (approximate)
                weapon-txs-before (filter #(and (= :weapon-equipped (:type %))
                                              (< (:turn %) turn))
                                         transactions)
                weapon-at-start (when (seq weapon-txs-before)
                                 {:card (:card (last weapon-txs-before))})

                ;; Get room cards from turn-started transaction
                turn-start-tx (first (filter #(= :turn-started (:type %)) turn-txs))
                room-cards (if turn-start-tx
                            (:room-cards turn-start-tx)
                            ;; Fallback: try to reconstruct from played/skipped cards
                            (let [skip-tx (first (filter #(= :room-skipped (:type %)) turn-txs))
                                  played-cards (map :card (filter #(= :card-played (:type %)) turn-txs))]
                              (if skip-tx
                                (:skipped-cards skip-tx)
                                played-cards)))]

            (println (format-turn-header turn
                                       room-cards
                                       health-at-start
                                       20
                                       weapon-at-start))

            ;; Print card actions
            (doseq [action-line (analyze-turn-transactions turn-txs
                                                         {:health health-at-start})]
              (println action-line))))))

    ;; Print final result
    (println "\n" (str/join (repeat 70 "═")))
    (if (= final-status :won)
      (println "🏆 VICTORY! Completed all rooms!")
      (println "💀 DEFEAT - Health reached" final-health))
    (println "Turns played:" turns-played)
    (println (str/join (repeat 70 "═")) "\n")))

(defn replay-game
  "Play a new game and display it"
  [player-type]
  (let [player (case player-type
                 :random (random/make-random-player)
                 :greedy (greedy/make-greedy-player)
                 :smart (smart/make-smart-player))
        game-state (core/play-game player)]
    (display-game game-state player-type)))

;; ============================================================================
;; Find Winning Game
;; ============================================================================

(defn find-winning-game
  "Run games until we find a winner, then replay it"
  [player-type max-attempts]
  (println "🔍 Searching for a winning game...")
  (println "Player type:" (str/upper-case (name player-type)))
  (println "Max attempts:" max-attempts)

  (loop [attempt 1]
    (when (zero? (mod attempt 100))
      (println "  Attempt" attempt "..."))

    (if (> attempt max-attempts)
      (println "\n❌ No winning game found in" max-attempts "attempts")

      (let [player (case player-type
                     :random (random/make-random-player)
                     :greedy (greedy/make-greedy-player)
                     :smart (smart/make-smart-player))
            game-state (core/play-game player)]

        (if (= :won (:status game-state))
          (do
            (println "\n✅ Found winning game after" attempt "attempts!\n")
            (display-game game-state player-type))
          (recur (inc attempt)))))))

;; ============================================================================
;; CLI Interface
;; ============================================================================

(defn -main
  [& args]
  (let [command (first args)
        player-type (keyword (or (second args) "smart"))]

    (case command
      "replay"
      (replay-game player-type)

      "find-winner"
      (let [max-attempts (Integer/parseInt (or (nth args 2 nil) "1000"))]
        (find-winning-game player-type max-attempts))

      ;; Default: replay one game
      (do
        (println "Usage:")
        (println "  replay <player-type>              - Replay a single game")
        (println "  find-winner <player-type> <max>   - Find and replay a winning game")
        (println "\nPlayer types: random, greedy, smart")
        (println "\nRunning single smart game as default...\n")
        (replay-game :smart)))))
