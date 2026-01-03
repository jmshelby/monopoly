(ns jmshelby.farkle.util
  (:require [jmshelby.farkle.definitions :as defs]))

;; ======= General =============================

(defn roll-dice
  "Roll n dice, returning a vector of values 1-6"
  [n]
  #?(:clj (vec (repeatedly n #(inc (rand-int 6))))
     :cljs (vec (repeatedly n #(inc (rand-int 6))))))

(defn dice-frequencies
  "Return a frequency map of dice values"
  [dice]
  (frequencies dice))

(defn append-tx
  "Append one or more transactions to the game state"
  [game-state & txs]
  (let [prepped
        (->> txs
             (mapcat (fn [tx]
                       (cond
                         (map? tx)        [tx]
                         (sequential? tx) tx
                         (nil? tx)        []
                         :else
                         (throw (ex-info
                                 "Appending a tx requires a map or collection of maps"
                                 {:type-given (type tx)})))))
             vec)]
    (update game-state :transactions (comp vec concat) prepped)))

;; ======= Player Management ===================

(defn current-player
  "Get the current player with their index"
  [{:keys [players current-turn]}]
  (let [player-id (:player current-turn)]
    (->> players
         (map-indexed (fn [idx p] (assoc p :player-index idx)))
         (filter #(= player-id (:id %)))
         first)))

(defn player-by-id
  "Get a player by ID with their index"
  [{:keys [players]} id]
  (->> players
       (map-indexed (fn [idx p] (assoc p :player-index idx)))
       (filter #(= id (:id %)))
       first))

(defn next-player
  "Get the next active player in turn order"
  [{:keys [players current-turn]}]
  (->> players
       (map-indexed (fn [idx p] (assoc p :player-index idx)))
       cycle
       (drop-while #(not= (:id %) (:player current-turn)))
       rest
       (filter #(= :playing (:status %)))
       first))

(defn apply-end-turn
  "End the current turn and advance to the next player"
  [game-state]
  (-> game-state
      (assoc-in [:current-turn :player]
                (-> game-state next-player :id))
      (assoc-in [:current-turn :turn-score] 0)
      (assoc-in [:current-turn :rolls] [])
      (assoc-in [:current-turn :available-dice] (get-in game-state [:rules :dice-count]))))

;; ======= Scoring Logic =======================

(defn find-scoring-dice
  "Find all possible scoring combinations in a dice roll.
  Returns a vector of scoring options, each with :score, :used-dice, :description"
  [dice]
  (let [freq (dice-frequencies dice)]
    (->> defs/scoring-combinations
         (keep #(% freq dice))
         vec)))

(defn has-scoring-dice?
  "Check if any dice in the roll can score"
  [dice]
  (boolean (seq (find-scoring-dice dice))))

(defn best-score
  "Find the highest scoring combination from available dice"
  [dice]
  (let [options (find-scoring-dice dice)]
    (when (seq options)
      (apply max-key :score options))))

(defn calculate-all-scoring-options
  "Calculate all valid ways to score the given dice.
  Returns a vector of maps with :score, :used-dice, :remaining-dice"
  [dice]
  (letfn [(build-combinations
            ([dice] (build-combinations dice [] 0))
            ([remaining-dice used-dice current-score]
             (if (empty? remaining-dice)
               [{:score current-score
                 :used-dice used-dice
                 :remaining-dice []}]
               (let [scoring-opts (find-scoring-dice remaining-dice)]
                 (if (empty? scoring-opts)
                   [{:score current-score
                     :used-dice used-dice
                     :remaining-dice remaining-dice}]
                   (mapcat
                    (fn [opt]
                      (let [new-remaining (reduce (fn [d die-val]
                                                    (let [idx (.indexOf d die-val)]
                                                      (if (>= idx 0)
                                                        (vec (concat (subvec d 0 idx)
                                                                     (subvec d (inc idx))))
                                                        d)))
                                                  (vec remaining-dice)
                                                  (:used-dice opt))]
                        (build-combinations
                         new-remaining
                         (vec (concat used-dice (:used-dice opt)))
                         (+ current-score (:score opt)))))
                    scoring-opts))))))]
    (distinct (build-combinations dice))))

(defn max-possible-score
  "Calculate the maximum possible score from the given dice"
  [dice]
  (let [options (calculate-all-scoring-options dice)
        max-option (apply max-key :score options)]
    (:score max-option)))

;; ======= Game State Queries ==================

(defn game-over?
  "Check if the game is over (one player reached winning score)"
  [{:keys [players rules]}]
  (let [winning-score (:winning-score rules)]
    (boolean (some #(>= (:total-score %) winning-score) players))))

(defn winner
  "Get the winner if game is over"
  [game-state]
  (when (game-over? game-state)
    (->> game-state
         :players
         (apply max-key :total-score))))

(defn active-players
  "Get all players still in the game"
  [{:keys [players]}]
  (filter #(= :playing (:status %)) players))

(defn player-count
  "Get the number of active players"
  [game-state]
  (count (active-players game-state)))
