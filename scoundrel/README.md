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
│   └── definitions.clj   # Deck and card definitions
├── test/jmshelby/scoundrel/
│   ├── core_test.clj     # Core game mechanics tests
│   └── definitions_test.clj  # Card and deck tests
└── deps.edn              # Clojure dependencies
```

## Development

### Running Tests

```bash
cd scoundrel
clojure -M:test
```

### REPL Development

```bash
cd scoundrel
clojure -M:repl
```

### Example Usage

```clojure
(require '[jmshelby.scoundrel.core :as core])

;; Start a new game
(def game (core/init-game-state))

;; Examine the room
(:room game) ; => #{card1 card2 card3 card4}

;; Play cards from the room
(def game2 (core/play-card game (first (:room game))))

;; Check game status
(:status game2) ; => :playing, :won, or :lost
(:health game2) ; => Current health
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

### Phase 2: AI/Decision Logic (Planned)
- [ ] Player decision interface
- [ ] AI strategies for card selection
- [ ] Optimal play heuristics
- [ ] Room skip decision logic

### Phase 3: Simulation & Analysis (Planned)
- [ ] Batch game simulation
- [ ] Win rate analysis
- [ ] Strategy comparison
- [ ] Game history tracking

## Game State Schema

```clojure
{:deck []                    ; Remaining undealt cards
 :room #{}                   ; Current 4 cards (set - unordered)
 :health 20                  ; Current health
 :equipped-weapon nil        ; {:card ... :defeated-monsters [...]}
 :skipped-last-room? false   ; Consecutive skip prevention
 :turn-potions-used 0        ; Potions used this turn
 :status :playing}           ; :playing, :won, :lost
```

## Design Decisions

1. **Room as Set**: Makes card selection natural (choose subset of 4)
2. **Defeated Monsters Stack**: Full history for debugging, easy peek for constraint checking
3. **Turn Boundary**: 3 cards from one room = 1 turn (potion counter resets after)
4. **Immutable State**: All functions return new state (functional programming pattern)
5. **No Discard Pile**: Not needed for core mechanics; can be derived from game state

## Rules Reference

Full rules available at: https://rpdillon.net/scoundrel.html
