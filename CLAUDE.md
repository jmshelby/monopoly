# CLAUDE.md - Monopoly Game Engine

## Project Overview
This is a Clojure-based Monopoly game engine with a pluggable player API. The project implements core Monopoly game logic including property management, player actions, card effects, and game state management.

## Project Structure
- `src/jmshelby/monopoly/` - Main source code
  - `core.clj` - Game engine and state management
  - `definitions.clj` - Board and property definitions
  - `player.clj` - Player logic and actions
  - `cards.clj` - Card deck and effects
  - `trade.clj` - Trading and proposal system
  - `util.clj` - Utility functions
  - `players/dumb.clj` - Example AI player implementation
- `simulation.clj` - Game simulation and batch analysis
- `test/` - Test files mirroring source structure
- `deps.edn` - Clojure dependencies and aliases

## Development Commands

### Running Tests
```bash
clojure -M:test
```

### REPL Development
```bash
clojure -M:repl
```

### Running Simulations
```bash
clojure -M -m jmshelby.monopoly.simulation -g 1000  # Run 1000 games
clojure -M -m jmshelby.monopoly.simulation -g 500 -p 3 -s 2000  # 500 games, 3 players, safety 2000
```

## Current Implementation Status

### Completed Features
- Basic dice roll and movement
- Property purchase and rent collection
- House/hotel building with monopoly rules
- Player bankruptcy detection and raise-funds workflow
- Jail mechanics (go to jail, get out of jail)
- Card system with multiple decks
- Trading/proposal system
- Game analysis and summary functions
- Exception handling in game simulations

### In Progress
- Auction system for declined property purchases
- Mortgage/unmortgage functionality
- Property acquisition workflow for mortgaged assets

### Future Features
- HTTP interface for remote players
- Seeded random number generators
- Advanced auction mechanics
- Obscure rule implementations

## Key Files to Understand

### Core Game Logic
- `core.clj:14-50` - Game state schema and structure
- `player.clj` - Player action handlers
- `definitions.clj` - Board layout and property definitions

### Critical Game Execution Functions in core.clj

#### `init-game-state` (lines 334-368)
Creates initial game state for a new Monopoly game.
- **Input**: `player-count` (number of players)
- **Output**: Complete initial game state map
- **Key details**: Players start with $1500, position 0 (GO), `:playing` status, and dumb AI by default

#### `rand-game-end-state` (lines 378-420) 
**PRIMARY SIMULATION FUNCTION** - Runs complete games to completion with robust error handling.
- **Input**: `players` (count), `failsafe-thresh` (max iterations, default 2000)
- **Output**: Final game state (completed, failed, or exception)
- **Exception handling**: Comprehensive error capture including message, type, stack trace, iteration count, player states
- **Failsafe protection**: Prevents infinite games with iteration limit
- **Returns special keys**: `:exception` for errors, `:failsafe-stop` for timeouts

#### `rand-game-state` (lines 370-376)
Returns game state after a specific number of iterations (for testing).
- **Input**: `players` (count), `n` (iterations to run)
- **Output**: Game state after exactly `n` iterations
- **Use case**: Testing specific game scenarios or mid-game analysis

#### `advance-board` (lines 201-330)
**CORE GAME ENGINE** - Advances game by exactly one turn/action.
- **Input**: Current game state
- **Output**: Updated game state after one player action
- **Key logic**: Game completion checks, player AI decision calls, action dispatch, turn management
- **Action types**: `:done`, `:roll`, `:buy-house`, `:trade-proposal`, jail actions

#### `move-to-cell` (lines 73-166)
Moves current player to specific cell and applies all effects.
- **Input**: `game-state`, `new-cell` (destination), `driver` (reason), optional `allowance?`
- **Output**: Updated game state after move and cell effects
- **Key effects**: GO allowance ($200), rent collection, taxes, card draws, property options

#### `apply-dice-roll` (lines 168-199)
Processes dice roll and moves current player.
- **Input**: `game-state`, `new-roll` (dice vector)
- **Output**: Updated game state after roll effects
- **Key logic**: Jail on 3rd double, board wrapping, delegates to `move-to-cell`

### Game Simulation System (simulation.clj)

#### `run-simulation` (lines 36-176)
**BATCH SIMULATION ENGINE** - Runs large numbers of games using core.async for performance.
- **Input**: `num-games`, `num-players` (default 4), `safety-threshold` (default 1500)
- **Output**: Comprehensive statistics map
- **Key features**: Parallel processing with backpressure, memory efficient, progress reporting
- **Uses**: `rand-game-end-state` for each individual game

#### `analyze-game-outcome` (lines 8-34)
Analyzes individual game results and extracts statistics.
- **Input**: Single game state from `rand-game-end-state`
- **Output**: Statistics map with winner, transaction counts, auction data, exception status
- **Exception tracking**: Checks `:exception` key and extracts error message

#### `print-simulation-results` (lines 188-280)
Displays comprehensive simulation report including exception details.
- **Exception reporting**: Shows count/percentage of games with exceptions
- **Exception breakdown**: Lists each unique exception message with frequency
- **Only displays**: Exception section when exceptions actually occur

### Testing
- All test files follow the same namespace structure as source
- Tests use `cognitect.test-runner`

## Development Notes
- Uses Clojure 1.12.0
- Functional programming approach with immutable game state
- Modular design allows for pluggable player strategies
- Current branch: `correct-bankrupt-logic` - working on bankruptcy mechanics

## AI Player Development
See `players/dumb.clj` for example AI implementation. Players need to implement decision functions for:
- Property purchases
- Trading proposals
- Bankruptcy fund-raising
- Building purchases

## Code Style Guidelines

### Formatting
- Empty lines should contain no trailing whitespace (spaces or tabs)
- Use completely empty lines for visual separation
- Maintain consistent indentation with spaces (not tabs)