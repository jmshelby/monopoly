(ns jmshelby.farkle.core
  (:require [jmshelby.farkle.util :as util
             :refer [append-tx]]
            [jmshelby.farkle.definitions :as defs]
            [jmshelby.farkle.player :as player]
            [jmshelby.farkle.players.dumb :as dumb-player]))

;; ======= Game State Schema =======================
;;
;; {:rules {:winning-score 10000
;;          :min-entry-score 500
;;          :dice-count 6}
;;  :players [{:id :player-1
;;             :total-score 0
;;             :on-board? false
;;             :status :playing
;;             :function player-decision-fn}]
;;  :current-turn {:player :player-1
;;                 :turn-score 0
;;                 :rolls []
;;                 :available-dice 6}
;;  :transactions [{:type :roll, :player :player-1, :dice [1 2 3 4 5 6]}
;;                 {:type :score, :player :player-1, :points 100}
;;                 {:type :bank, :player :player-1, :turn-score 300, :new-total 300}
;;                 {:type :farkle, :player :player-1}]
;;  :status :playing}
;;
;; =====================================================

(defn init-game-state
  "Create initial game state for a new Farkle game"
  ([player-count]
   (init-game-state player-count defs/default-rules))
  ([player-count rules]
   (let [players (vec (for [i (range player-count)]
                        {:id (keyword (str "player-" (inc i)))
                         :total-score 0
                         :on-board? false
                         :status :playing
                         :function dumb-player/decide}))]
     {:rules rules
      :players players
      :current-turn {:player (:id (first players))
                     :turn-score 0
                     :rolls []
                     :available-dice (:dice-count rules)}
      :transactions []
      :status :playing})))

;; ======= Core Game Logic =========================

(defn apply-roll
  "Roll the available dice and update game state"
  [game-state]
  (let [player (util/current-player game-state)
        available-dice (get-in game-state [:current-turn :available-dice])
        new-roll (util/roll-dice available-dice)
        has-score? (util/has-scoring-dice? new-roll)]
    (-> game-state
        (update-in [:current-turn :rolls] conj new-roll)
        (append-tx {:type :roll
                    :player (:id player)
                    :dice new-roll
                    :available-dice available-dice
                    :has-score has-score?}))))

(defn apply-score-dice
  "Apply scoring from selected dice and update available dice count"
  [game-state dice-to-score]
  (let [player (util/current-player game-state)
        scoring-options (util/find-scoring-dice dice-to-score)
        total-score (reduce + (map :score scoring-options))
        available-dice (get-in game-state [:current-turn :available-dice])
        dice-used (count dice-to-score)
        new-available (- available-dice dice-used)
        ;; If all dice scored, get fresh set (hot dice)
        final-available (if (zero? new-available)
                          (get-in game-state [:rules :dice-count])
                          new-available)]
    (-> game-state
        (update-in [:current-turn :turn-score] + total-score)
        (assoc-in [:current-turn :available-dice] final-available)
        (append-tx {:type :score-dice
                    :player (:id player)
                    :dice dice-to-score
                    :points total-score
                    :new-turn-score (+ (get-in game-state [:current-turn :turn-score]) total-score)
                    :hot-dice (zero? new-available)}))))

(defn apply-bank
  "Bank the current turn score and end turn"
  [game-state]
  (let [player (util/current-player game-state)
        pidx (:player-index player)
        turn-score (get-in game-state [:current-turn :turn-score])
        old-total (:total-score player)
        new-total (+ old-total turn-score)
        min-entry (get-in game-state [:rules :min-entry-score])
        was-on-board (:on-board? player)
        now-on-board (or was-on-board (>= new-total min-entry))]

    (if (and (not was-on-board) (< new-total min-entry))
      ;; Not enough to get on board, lose the turn score
      (-> game-state
          (append-tx {:type :bank-failed
                      :player (:id player)
                      :turn-score turn-score
                      :total-score old-total
                      :min-entry-required min-entry})
          util/apply-end-turn)
      ;; Bank the score
      (-> game-state
          (assoc-in [:players pidx :total-score] new-total)
          (assoc-in [:players pidx :on-board?] now-on-board)
          (append-tx {:type :bank
                      :player (:id player)
                      :turn-score turn-score
                      :old-total old-total
                      :new-total new-total
                      :entered-board (and (not was-on-board) now-on-board)})
          util/apply-end-turn))))

(defn apply-farkle
  "Apply farkle penalty (lose all turn points) and end turn"
  [game-state]
  (let [player (util/current-player game-state)
        turn-score (get-in game-state [:current-turn :turn-score])]
    (-> game-state
        (append-tx {:type :farkle
                    :player (:id player)
                    :lost-score turn-score})
        util/apply-end-turn)))

;; ======= Game Loop ===============================

(defn advance-game
  "Advance game by one action/decision"
  [game-state]
  (if (util/game-over? game-state)
    ;; Game is over, don't advance
    (assoc game-state :status :completed)
    ;; Get current player and their decision
    (let [player (util/current-player game-state)
          last-roll (last (get-in game-state [:current-turn :rolls]))
          decision ((:function player)
                    game-state
                    (:id player)
                    {:last-roll last-roll
                     :turn-score (get-in game-state [:current-turn :turn-score])
                     :available-dice (get-in game-state [:current-turn :available-dice])})]

      (case (:action decision)
        ;; Roll the dice
        :roll
        (let [new-state (apply-roll game-state)
              new-roll (last (get-in new-state [:current-turn :rolls]))]
          (if (util/has-scoring-dice? new-roll)
            new-state
            ;; Farkle! No scoring dice
            (apply-farkle new-state)))

        ;; Score specific dice and continue turn
        :score
        (apply-score-dice game-state (:dice decision))

        ;; Bank points and end turn
        :bank
        (apply-bank game-state)

        ;; Invalid action
        (throw (ex-info "Invalid player action"
                        {:player (:id player)
                         :action (:action decision)}))))))

;; ======= Game Execution ==========================

(defn rand-game-state
  "Run a game for n iterations and return the state"
  [player-count n]
  (let [initial-state (init-game-state player-count)]
    (loop [state initial-state
           iterations 0]
      (if (or (>= iterations n)
              (util/game-over? state))
        state
        (recur (advance-game state) (inc iterations))))))

(defn rand-game-end-state
  "Run a complete game to completion with failsafe protection"
  ([player-count]
   (rand-game-end-state player-count 5000))
  ([player-count failsafe-thresh]
   (try
     (let [initial-state (init-game-state player-count)]
       (loop [state initial-state
              iterations 0]
         (cond
           ;; Failsafe triggered
           (>= iterations failsafe-thresh)
           (assoc state
                  :status :failsafe
                  :failsafe-stop true
                  :iterations iterations)

           ;; Game completed normally
           (util/game-over? state)
           (assoc state
                  :status :completed
                  :iterations iterations)

           ;; Continue playing
           :else
           (recur (advance-game state) (inc iterations)))))
     (catch #?(:clj Exception :cljs js/Error) e
       ;; Capture exceptions with full context
       {:status :exception
        :exception {:message #?(:clj (.getMessage e)
                                :cljs (.-message e))
                    :type (type e)
                    :trace #?(:clj (mapv str (.getStackTrace e))
                              :cljs nil)}
        :iterations (or (some-> e ex-data :iterations) 0)}))))
