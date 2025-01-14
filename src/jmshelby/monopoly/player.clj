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

;; TODO - multimethods..
(defn dumb-player-decision
  [game-state method params]
  (let [{my-id :id
         cash  :cash} (util/current-player game-state)]
    (case method

      ;; Dumb, always decline these actions
      :acquisition    {:action :decline}
      :auction-bid    {:action :decline}
      :offer-proposal {:action :decline}

      ;; Dumb, always buy a property if we can
      ;; TODO - maybe keep _some_ money minimum?
      :property-option {:action :buy}

      ;; Turn Logic...
      :take-turn
      (cond

        ;; First, check if we can roll, and do it
        (-> params :actions-available :roll)
        {:action :roll}

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
