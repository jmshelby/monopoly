(ns jmshelby.farkle.definitions)

;; Farkle game definitions and scoring rules

(def default-rules
  "Default Farkle game rules and configuration"
  {:winning-score 10000          ; Points needed to win
   :min-entry-score 500          ; Minimum score required to get on the board
   :dice-count 6                 ; Number of dice in the game
   :farkle-penalty 0             ; Points lost on farkle (default: just lose turn score)
   })

;; Scoring combinations for Farkle
;; Priority order matters - check for larger combinations first

(defn score-straight
  "Check for 1-2-3-4-5-6 straight (3000 points)"
  [dice-freq dice]
  (when (and (= 6 (count dice))
             (every? #(= 1 (dice-freq %)) [1 2 3 4 5 6]))
    {:score 3000
     :used-dice dice
     :description "Straight (1-2-3-4-5-6)"}))

(defn score-three-pairs
  "Check for three pairs (1500 points)"
  [dice-freq dice]
  (let [pairs (filter #(= 2 (val %)) dice-freq)]
    (when (= 3 (count pairs))
      {:score 1500
       :used-dice dice
       :description "Three pairs"})))

(defn score-six-of-kind
  "Check for six of a kind (3000 points)"
  [dice-freq _dice]
  (when-let [entry (->> dice-freq
                        (filter #(= 6 (val %)))
                        first)]
    {:score 3000
     :used-dice (repeat 6 (key entry))
     :description (str "Six " (key entry) "s")}))

(defn score-five-of-kind
  "Check for five of a kind (2000 points)"
  [dice-freq _dice]
  (when-let [entry (->> dice-freq
                        (filter #(= 5 (val %)))
                        first)]
    {:score 2000
     :used-dice (repeat 5 (key entry))
     :description (str "Five " (key entry) "s")}))

(defn score-four-of-kind
  "Check for four of a kind (1000 points)"
  [dice-freq _dice]
  (when-let [entry (->> dice-freq
                        (filter #(= 4 (val %)))
                        first)]
    {:score 1000
     :used-dice (repeat 4 (key entry))
     :description (str "Four " (key entry) "s")}))

(defn score-three-of-kind
  "Check for three of a kind (value * 100, except 1s = 1000)"
  [dice-freq _dice]
  (when-let [entry (->> dice-freq
                        (filter #(>= (val %) 3))
                        first)]
    (let [value (key entry)
          base-score (if (= 1 value) 1000 (* value 100))]
      {:score base-score
       :used-dice (repeat 3 value)
       :description (str "Three " value "s")})))

(defn score-single-ones
  "Score individual 1s (100 points each)"
  [dice-freq _dice]
  (when-let [count (dice-freq 1)]
    (when (< count 3)  ; Only if not part of three-of-a-kind
      {:score (* count 100)
       :used-dice (repeat count 1)
       :description (str count " one(s)")})))

(defn score-single-fives
  "Score individual 5s (50 points each)"
  [dice-freq _dice]
  (when-let [count (dice-freq 5)]
    (when (< count 3)  ; Only if not part of three-of-a-kind
      {:score (* count 50)
       :used-dice (repeat count 5)
       :description (str count " five(s)")})))

;; Ordered list of scoring functions to check
;; Higher value combinations are checked first
(def scoring-combinations
  [score-straight
   score-three-pairs
   score-six-of-kind
   score-five-of-kind
   score-four-of-kind
   score-three-of-kind
   score-single-ones
   score-single-fives])
