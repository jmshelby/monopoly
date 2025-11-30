(ns jmshelby.scoundrel.player)

;; Player protocol using multimethods
;; Players are represented as maps with a :type key that dispatches to the appropriate implementation

(defmulti choose-cards
  "Given game state and room, choose 3 cards and return them in play order.
  Returns: vector of 3 cards in the order to play them.
  Dispatches on player :type."
  (fn [player _game-state _room] (:type player)))

(defmulti should-skip-room?
  "Given game state and room, decide whether to skip this room.
  Returns: boolean.
  Dispatches on player :type."
  (fn [player _game-state _room] (:type player)))

;; Default implementations that throw errors if not implemented
(defmethod choose-cards :default
  [player _game-state _room]
  (throw (ex-info "No choose-cards implementation for player type"
                  {:player-type (:type player)})))

(defmethod should-skip-room? :default
  [player _game-state _room]
  (throw (ex-info "No should-skip-room? implementation for player type"
                  {:player-type (:type player)})))
