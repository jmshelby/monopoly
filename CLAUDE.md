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