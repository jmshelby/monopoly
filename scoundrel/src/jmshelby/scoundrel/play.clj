(ns jmshelby.scoundrel.play
  (:require [jmshelby.scoundrel.core :as core]
            [jmshelby.scoundrel.player :as player]
            [jmshelby.scoundrel.players.human :as human]
            [jmshelby.scoundrel.game-viewer :as viewer]
            [jmshelby.scoundrel.definitions :as def]
            [clojure.string :as str])
  (:gen-class))

(defn display-card-play
  "Display a card being played and its effect"
  [card game-state-before game-state-after]
  (let [card-type (def/card-type card)
        health-before (:health game-state-before)
        health-after (:health game-state-after)
        weapon-before (:equipped-weapon game-state-before)
        weapon-after (:equipped-weapon game-state-after)
        icon (viewer/card-type-icon card)]
    (print (format "\n  %s Playing: %s [%d] - "
                  icon
                  (viewer/card-str card)
                  (:value card)))
    (case card-type
      :weapon
      (println (format "⚔️  Equipped weapon! (strength: %d)" (:value card)))

      :monster
      (let [defeated? (and weapon-after
                          (> (count (:defeated-monsters weapon-after))
                             (count (:defeated-monsters (or weapon-before {:defeated-monsters []})))))]
        (if defeated?
          (let [weapon-value (:value (:card weapon-after))
                damage (max 0 (- (:value card) weapon-value))]
            (if (> damage 0)
              (println (format "🗡️  Defeated with weapon! Took %d damage (HP: %d → %d)"
                              damage health-before health-after))
              (println (format "🗡️  Defeated with weapon! No damage taken (HP: %d)"
                              health-after))))
          (let [damage (- health-before health-after)]
            (println (format "💥 Took %d damage! (HP: %d → %d)"
                            damage health-before health-after)))))

      :potion
      (let [healed (- health-after health-before)]
        (if (> healed 0)
          (println (format "💚 Healed %d HP! (HP: %d → %d)"
                          healed health-before health-after))
          (println "❌ Potion wasted (not first potion this turn)"))))))

(defn play-cards-with-display
  "Play cards one by one with visual feedback"
  [game-state cards]
  (loop [state game-state
         remaining-cards cards]
    (if (or (empty? remaining-cards) (not= (:status state) :playing))
      state
      (let [card (first remaining-cards)
            new-state (core/play-card state card)]
        (display-card-play card state new-state)
        (recur new-state (rest remaining-cards))))))

(defn play-turn-interactive
  "Play one turn with visual feedback"
  [game-state player-ai]
  (let [room (:room game-state)
        state-with-turn (update game-state :turn inc)]
    (if (player/should-skip-room? player-ai state-with-turn room)
      (do
        (println "\n⏭️  SKIPPING ROOM")
        (println "   Cards go to bottom of deck, dealing 4 new cards...")
        (core/skip-room state-with-turn))
      (do
        (let [chosen-cards (player/choose-cards player-ai state-with-turn room)]
          (println "\n▶️  PLAYING CARDS:")
          (let [state-after-play (play-cards-with-display state-with-turn chosen-cards)]
            (if (= (:status state-after-play) :playing)
              (do
                (println "\n✅ Room completed!")
                (core/complete-room state-after-play))
              state-after-play)))))))

(defn display-final-result
  "Display the final game result"
  [game-state]
  (let [status (:status game-state)
        health (:health game-state)
        turns (:turns-played game-state)
        transactions (:transactions game-state)
        total-damage (reduce + 0 (map :damage (filter #(= :damage-taken (:type %)) transactions)))
        monsters-defeated (count (filter #(= :monster-defeated (:type %)) transactions))
        healing-used (reduce + 0 (map :amount (filter #(= :healed (:type %)) transactions)))]
    (println "\n" (str/join (repeat 70 "═")))
    (println)
    (case status
      :won (println "🎉 VICTORY! 🎉")
      :lost (println "💀 DEFEAT 💀")
      :failed (println "⏱️  TIME LIMIT REACHED ⏱️"))
    (println)
    (println "📊 FINAL STATISTICS:")
    (println (format "   Health: %s" (viewer/health-bar health 20)))
    (println (format "   Turns played: %d" turns))
    (println (format "   Total damage taken: %d HP" total-damage))
    (println (format "   Total healing used: %d HP" healing-used))
    (println (format "   Monsters defeated: %d" monsters-defeated))
    (println)
    (println (str/join (repeat 70 "═")))))

(defn play-interactive-game
  "Play a complete interactive game"
  []
  (println "\n╔═══════════════════════════════════════════════════════════════════╗")
  (println "║                    🎴 SCOUNDREL SOLITAIRE 🎴                      ║")
  (println "╚═══════════════════════════════════════════════════════════════════╝")
  (println "\n📜 RULES:")
  (println "   • Start with 20 HP")
  (println "   • Each turn you see 4 cards from the deck")
  (println "   • Choose 1 card to LEAVE, play the other 3 in order")
  (println "   • Monsters: damage you (or fight with weapon)")
  (println "   • Weapons: defeat monsters with value < last defeated")
  (println "   • Potions: heal HP (only first per turn)")
  (println "   • Win by clearing all 44 cards!")
  (println)
  (print "Press Enter to start...")
  (flush)
  (read-line)

  (let [player (human/make-human-player)]
    (loop [state (core/init-game-state)
           turn-count 0
           max-turns 100]
      (cond
        (not= (:status state) :playing)
        (do
          (display-final-result (assoc state :turns-played turn-count))
          state)

        (>= turn-count max-turns)
        (do
          (display-final-result (assoc state :status :failed :turns-played turn-count))
          state)

        :else
        (recur (play-turn-interactive state player) (inc turn-count) max-turns)))))

(defn -main [& _args]
  (play-interactive-game)
  (System/exit 0))
