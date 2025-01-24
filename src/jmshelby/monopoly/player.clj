(ns jmshelby.monopoly.player
  (:require [jmshelby.monopoly.util :as util]))

;; Right now, just the fn/methods that act as a player logic "function"

;; For now we just have some dumb logic, to help
;; develop and test the engine until it's done

(defn- percent-props-owned
  [game-state]
  (let [total (->> game-state :board :properties
                   (map :name) count)
        owned (->> game-state
                   util/owned-properties
                   count)]
    (/ owned total)))


(defn- accept-proposal?
  "Given a game-state and proposal map, return wether we should accept/decline the trade proposal."
  [game-state proposal]
  ;; Real simple, only accept if offerred resources value
  ;; (taking mortgaged into account) add up to 1.5 times
  ;; the amount of the asking resources
  )

(defn- proposal?
  "Given a game-state, return the best current proposal
  available, _if_ it's a smart time to propose one."
  [game-state]
  ;; Very dumb initial logic
  ;;  - NOTE: no cash or jail free cards involved yet
  ;; Street Properties:
  ;; - If we own 50% or more of a street group,
  ;;   AND someone else owns a property of the same street...
  ;;       -> Attempt to build offer for that "target" property
  ;;           - From list of our "undesirable" properties, ordered
  ;;             by current value (taking mortgaged into account),
  ;;             take enough props to reach a value that is more
  ;;             than the face value of the "target" property.
  ;;             - "undesirable" properties:
  ;;               - utils
  ;;               - railroads
  ;;               - we own less than 50% of the street group
  ;;       -> Make sure attempt offer has never been made before,
  ;;          from transaction history


  nil

  ;; When we're ready to send a proposal
  ;; {:action                   :trade-proposal
  ;;  :trade/to-player "A"
  ;;  ;; Note - You'd never have :cash in both asking+offering
  ;;  ;;        I guess you _could_, but there'd be no point, so we should restrict it
  ;;  ;; Note - You'd never have :cards in both asking+offering (in standard rules board)
  ;;  ;; Note - You can (and often will) have properties in both asking+offering
  ;;  ;; Note - A single player _could_ have 2 get out of jail free cards and offer them
  ;;  :trade/asking    {;; Cash dollar amount > 0
  ;;                    :cash       123
  ;;                    ;; Full card definitions
  ;;                    :cards      #{}
  ;;                    ;; Just names of properties
  ;;                    :properties #{}}
  ;;  :trade/offering  {:cash       123
  ;;                    :cards      #{}
  ;;                    :properties #{}}}


  )

;; TODO - multimethods..
(defn dumb-player-decision
  [game-state method params]
  (let [{my-id :id
         cash  :cash} (util/current-player game-state)]
    (case method

      ;; Dumb, always decline these actions
      :acquisition {:action :decline}
      :auction-bid {:action :decline}

      ;; TODO
      ;; A trade proposal sent to you
      :trade-proposal {:action :decline}

      ;; Dumb, always buy a property if we can
      ;; TODO - maybe keep _some_ money minimum?
      :property-option {:action :buy}

      ;; Turn Logic...
      :take-turn
      (cond

        ;; First, check if we can roll, and do it
        (-> params :actions-available :roll)
        {:action :roll}

        ;; Check to see if we can AND should make an offer
        (and (-> params :actions-available :trade-proposal)
             (proposal? game-state))
        (proposal? game-state)

        ;; OR, if we are in jail and have a free out card
        ;; THEN use it
        (-> params :actions-available :jail/bail-card)
        {:action :jail/bail-card}

        ;; OR, if we are in jail and can pay bail,
        ;; AND there's more than 25% of prop for sell,
        ;; THEN post bail
        (and (-> params :actions-available :jail/bail)
             (> 0.75 (percent-props-owned game-state)))
        {:action :jail/bail}

        ;; OR, if we are in jail and can roll for doubles, do that
        (-> params :actions-available :jail/roll)
        {:action :jail/roll}

        ;; Next, if we can buy a house,
        ;; and have more than $40 left (yes very dumb)
        (and (-> params :actions-available :buy-house)
             (> cash 40))
        {:action :buy-house
         :property-name
         (let [potential (->> game-state
                              util/owned-property-details
                              (map second)
                              (filter #(= my-id (:owner %)))
                              util/potential-house-purchases)
               cheapest  (->> potential
                              (sort-by #(nth % 2))
                              first)]
           ;; Property ID/Name, tuple 2 pos
           (second cheapest))}

        ;; No other options, end turn
        ;; TODO - soon, "done" might not be available in all cases
        :else {:action :done}))))
