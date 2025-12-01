# Scoundrel Solitaire Game Engine

A Clojure implementation of the Scoundrel solitaire dungeon-crawler card game.

## Game Overview

Scoundrel is a single-player card game where you navigate through a dungeon fighting monsters, collecting weapons, and using potions to survive.

**Objective**: Play through all 44 cards without your health reaching zero.

## Rules Summary

### Deck Composition
- 44 cards total (standard 52-card deck minus red face cards and red aces)
- Includes: All clubs/spades, black diamonds/hearts, red number cards (2-10)

### Card Types
- **Monsters** (Clubs/Spades): Deal damage equal to their value unless defeated with a weapon
- **Weapons** (Diamonds): Equip to defeat monsters; must attack progressively weaker monsters
- **Potions** (Hearts): Heal for their value; only the first potion per turn heals

### Gameplay
1. Start with 20 health
2. Deal 4 cards face-up to create a room
3. Choose 3 cards to play in your preferred order
4. Apply card effects:
   - Monsters: Take damage OR defeat with weapon (if valid)
   - Weapons: Equip (replaces previous weapon)
   - Potions: Heal (first per turn only)
5. Remaining card stays for next room, deal 3 new cards
6. Repeat until all cards played (win) or health reaches 0 (lose)

### Special Rules
- **Weapon Constraint**: Each monster defeated must have a lower value than the previous one
- **Potion Constraint**: Only the first potion played per turn heals
- **Room Skip**: Place all 4 room cards at bottom of deck and deal fresh room (cannot skip consecutively)
- **Turn Definition**: Playing all 3 cards from one room = one turn

## Project Structure

```
scoundrel/
├── src/jmshelby/scoundrel/
│   ├── core.clj          # Game engine and state management
│   ├── definitions.clj   # Deck and card definitions
│   ├── player.clj        # Player decision protocol (multimethods)
│   ├── simulation.clj    # Batch simulation and analysis
│   ├── players/
│   │   ├── random.clj    # Random player AI
│   │   └── greedy.clj    # Greedy player AI with heuristics
│   └── simulation/
│       └── cli.clj       # Command-line interface
├── test/jmshelby/scoundrel/
│   ├── core_test.clj     # Core game mechanics tests
│   ├── definitions_test.clj  # Card and deck tests
│   ├── player_test.clj   # Player AI and game loop tests
│   └── simulation_test.clj   # Simulation and analysis tests
└── deps.edn              # Clojure dependencies
```

## Development

### Running Tests

```bash
cd scoundrel
clojure -M:test
```

### Running Simulations

```bash
cd scoundrel

# Run 5000 games with random player (default)
clojure -M:sim

# Run 10000 games with greedy player
clojure -M:sim -p :greedy -g 10000

# Run 1000 games with custom turn limit
clojure -M:sim -g 1000 -t 150

# Show help
clojure -M:sim -h
```

### REPL Development

```bash
cd scoundrel
clojure -M:repl
```

### Example Usage

```clojure
(require '[jmshelby.scoundrel.core :as core]
         '[jmshelby.scoundrel.players.random :as random]
         '[jmshelby.scoundrel.players.greedy :as greedy]
         '[jmshelby.scoundrel.simulation :as sim]
         '[clojure.core.async :as async])

;; Play a complete game with random player
(def random-player (random/make-random-player))
(def result (core/play-game random-player))
(:status result)      ; => :won or :lost
(:health result)      ; => Final health
(:turns-played result) ; => Number of turns played

;; Play a complete game with greedy player
(def greedy-player (greedy/make-greedy-player))
(def result2 (core/play-game greedy-player))

;; Run batch simulation
(def output-ch (sim/run-simulation 100 :greedy 100))
(def results (loop [acc []]
               (if-let [result (async/<!! output-ch)]
                 (recur (conj acc result))
                 acc)))
(def stats (sim/aggregate-results results))
(sim/print-simulation-results stats :greedy)

;; Manual game control
(def game (core/init-game-state))
(:room game) ; => #{card1 card2 card3 card4}

;; Play one turn with a player
(def game2 (core/play-turn game greedy-player))

;; Or play cards manually
(def game3 (core/play-card game (first (:room game))))
(:status game3) ; => :playing, :won, or :lost
(:health game3) ; => Current health
```

## Implementation Status

### Phase 1: Core Game Engine ✅ COMPLETE
- [x] Card definitions and deck creation
- [x] Game state management
- [x] Weapon mechanics with defeated monsters stack
- [x] Monster damage and weapon defeat logic
- [x] Potion healing with per-turn constraint
- [x] Room completion and skip mechanics
- [x] Win/lose condition checking
- [x] Comprehensive test coverage (26 tests)

### Phase 2: AI/Decision Logic ✅ COMPLETE
- [x] Player decision protocol (multimethods)
- [x] Random player AI implementation
- [x] Greedy player AI with heuristics:
  - [x] Weapon prioritization
  - [x] Potion usage when health is low
  - [x] Strategic card ordering
  - [x] Dangerous room skip logic
- [x] Game loop integration (play-turn, play-game)
- [x] Full game playthrough support
- [x] Transaction logging system:
  - [x] 9 transaction types covering all actions
  - [x] Turn tracking throughout game
  - [x] Complete game history for analysis
- [x] Comprehensive test coverage (47 tests)

### Phase 3: Simulation & Analysis ✅ COMPLETE
- [x] Batch game simulation with core.async
  - [x] Parallel execution using pipeline pattern
  - [x] Automatic CPU-based parallelism
  - [x] Backpressure handling for memory efficiency
- [x] Win rate analysis by player type
- [x] Strategy comparison and statistics
  - [x] Damage analysis (total, avg, min/max)
  - [x] Combat efficiency (monsters defeated vs damage taken)
  - [x] Weapon utilization (equipped, replacements)
  - [x] Potion efficiency (used vs wasted)
  - [x] Room skip frequency
- [x] Game history tracking via transaction logs
- [x] Performance metrics (games per second)
- [x] CLI interface with options:
  - [x] -g: Number of games (default 5000)
  - [x] -p: Player type (:random or :greedy)
  - [x] -t: Max turns per game
- [x] Comprehensive test coverage (58 tests)

## Game State Schema

```clojure
{:deck []                    ; Remaining undealt cards
 :room #{}                   ; Current 4 cards (set - unordered)
 :health 20                  ; Current health
 :equipped-weapon nil        ; {:card ... :defeated-monsters [...]}
 :skipped-last-room? false   ; Consecutive skip prevention
 :turn-potions-used 0        ; Potions used this turn
 :turn 0                     ; Current turn number
 :transactions []            ; Transaction log (full game history)
 :status :playing}           ; :playing, :won, :lost
```

## Transaction Logging

All game actions are recorded in the `:transactions` vector for analysis and debugging. Each transaction is a map with `:type` and `:turn` plus action-specific data:

- `:card-played` - Records each card play with card type
- `:damage-taken` - Monster damage with health before/after
- `:monster-defeated` - Weapon defeating monster
- `:weapon-equipped` - Equipping weapons (tracks replacements)
- `:healed` - Potion healing with amount and health after
- `:potion-wasted` - Non-first potions with reason
- `:room-skipped` - Room skip with skipped cards
- `:room-completed` - Room completion marker
- `:game-ended` - Final outcome with health

Transaction logs enable:
- Game replay and analysis
- Strategy evaluation
- Debugging game logic
- Win/loss pattern identification

## Design Decisions

1. **Room as Set**: Makes card selection natural (choose subset of 4)
2. **Defeated Monsters Stack**: Full history for debugging, easy peek for constraint checking
3. **Turn Boundary**: 3 cards from one room = 1 turn (potion counter resets after)
4. **Immutable State**: All functions return new state (functional programming pattern)
5. **No Discard Pile**: Not needed for core mechanics; can be derived from game state
6. **Player Protocol**: Multimethod dispatch on `:type` key for extensible AI strategies
7. **Greedy Heuristics**: Simple card sorting based on game state (health, weapon equipped)
8. **Safety Limits**: Max turn count prevents infinite loops in game simulation
9. **Transaction Logging**: Comprehensive action history following Monopoly engine pattern
10. **Turn Tracking**: Turn counter increments at start of each room for consistent logging

## Rules Reference

Full rules available at: https://rpdillon.net/scoundrel.html
