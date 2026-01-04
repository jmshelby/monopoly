# Farkle Game Engine

A Clojure implementation of the dice game Farkle, following the same architectural patterns as the Monopoly game engine in this repository.

## Game Rules

Farkle is a dice game where:
- Players take turns rolling 6 dice
- Players score points based on dice combinations
- After each roll, players can choose to bank their score or continue rolling
- If a roll has no scoring dice, the player "farkles" and loses all points for that turn
- First player to reach 10,000 points wins
- Players must score at least 500 points to get "on the board"

## Scoring

| Combination | Points | Notes |
|------------|--------|-------|
| Straight (1-2-3-4-5-6) | 3000 | All six dice |
| Three pairs | 1500 | Any three pairs |
| Six of a kind | 3000 | All same number |
| Five of a kind | 2000 | Five same number |
| Four of a kind | 1000 | Four same number |
| Three of a kind | 100 × value | Three 1s = 1000 |
| Single 1 | 100 | Per die |
| Single 5 | 50 | Per die |

**Hot Dice**: When all 6 dice score, the player gets a fresh set of 6 dice to continue their turn.

## Project Structure

```
src/jmshelby/farkle/
├── definitions.cljc      # Scoring rules and game configuration
├── core.cljc             # Game engine and state management
├── util.cljc             # Utility functions (dice, scoring, queries)
├── player.cljc           # Player decision API
├── players/
│   └── dumb.cljc         # Simple AI player implementation
└── simulation/
    └── cli.clj           # Command-line simulation runner
```

## API Overview

### Game State Schema

```clojure
{:rules {:winning-score 10000
         :min-entry-score 500
         :dice-count 6}
 :players [{:id :player-1
            :total-score 0
            :on-board? false
            :status :playing
            :function player-decision-fn}]
 :current-turn {:player :player-1
                :turn-score 0
                :rolls []
                :available-dice 6}
 :transactions [...]
 :status :playing}
```

### Player Decision API

Players implement a decision function that receives:
- `game-state` - Current game state
- `player-id` - ID of the deciding player
- `context` - Map with `:last-roll`, `:turn-score`, `:available-dice`

Returns a decision map:
```clojure
{:action :roll}                    ; Roll available dice
{:action :score :dice [1 5]}       ; Score specific dice
{:action :bank}                    ; Bank turn score and end turn
```

### Core Functions

#### `init-game-state`
```clojure
(init-game-state player-count)
(init-game-state player-count custom-rules)
```
Creates initial game state.

#### `rand-game-end-state`
```clojure
(rand-game-end-state player-count)
(rand-game-end-state player-count failsafe-threshold)
```
Runs a complete game to completion with exception handling.

#### `advance-game`
```clojure
(advance-game game-state)
```
Advances game by exactly one player action.

### Utility Functions

- `(roll-dice n)` - Roll n dice
- `(find-scoring-dice dice)` - Find all scoring combinations
- `(has-scoring-dice? dice)` - Check if any dice can score
- `(max-possible-score dice)` - Calculate maximum possible score
- `(game-over? game-state)` - Check if game is complete
- `(winner game-state)` - Get winning player

## Running Simulations

### Programmatically

```clojure
(require '[jmshelby.farkle.simulation :as sim])

;; Run 1000 games with 4 players each
(def results (sim/run-simulation 1000 4))

;; Print formatted results
(sim/print-simulation-results results)
```

### Command Line

```bash
# Run 100 games (default)
clojure -M -m jmshelby.farkle.simulation.cli

# Run 1000 games with 4 players
clojure -M -m jmshelby.farkle.simulation.cli -g 1000 -p 4

# Custom safety threshold
clojure -M -m jmshelby.farkle.simulation.cli -g 500 -p 3 -s 3000
```

## Running Tests

```bash
# Run all Farkle tests
clojure -M:test -n jmshelby.farkle

# Run specific test namespace
clojure -M:test -n jmshelby.farkle.util-test
clojure -M:test -n jmshelby.farkle.core-test
```

## AI Player Strategy

The included `dumb` player uses a conservative strategy:
- Always scores all available scoring dice (greedy approach)
- Banks when turn score ≥ 500 OR only 1-2 dice remaining
- Considers minimum entry requirement (must score 500 to get on board)
- More cautious when close to winning

## Design Philosophy

This implementation follows the same patterns as the Monopoly engine:

1. **Immutable State** - Game state is an immutable map passed through pure functions
2. **Transaction Log** - All game events are recorded for analysis
3. **Pluggable Players** - Easy to create custom AI strategies
4. **Functional Composition** - Small, composable functions
5. **Cross-Platform** - Uses .cljc for Clojure and ClojureScript compatibility
6. **Simulation-Friendly** - Designed for running thousands of games efficiently

## Extending the Engine

### Custom AI Players

Create a new namespace and implement the decision function:

```clojure
(ns jmshelby.farkle.players.my-player
  (:require [jmshelby.farkle.util :as util]))

(defn decide
  [game-state player-id context]
  ;; Your decision logic here
  {:action :roll})
```

### Custom Scoring Rules

Modify `definitions.cljc` to add new scoring combinations:

```clojure
(defn score-custom-combination
  [dice-freq dice]
  (when (custom-condition? dice-freq)
    {:score 500
     :used-dice [1 2 3]
     :description "Custom combo"}))
```

### Custom Game Rules

Pass custom rules when initializing:

```clojure
(init-game-state 4 {:winning-score 5000
                    :min-entry-score 300
                    :dice-count 6})
```

## Performance

The simulation engine uses core.async for parallel game execution in Clojure:
- Processes multiple games concurrently
- Memory-efficient result aggregation
- Progress reporting for long simulations

Typical performance: ~1000-5000 games/second on modern hardware (varies by game complexity).

## Code Style

Follows the same style guidelines as the Monopoly codebase:
- NO TRAILING WHITESPACE
- Consistent indentation with spaces
- Descriptive function names
- Comprehensive documentation strings
