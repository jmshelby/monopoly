# Test Coverage

This project uses [cloverage](https://github.com/cloverage/cloverage) for test coverage reporting.

## Running Coverage Locally

```bash
# Generate coverage report
./bin/coverage

# View HTML report
open target/coverage/index.html
```

## Coverage Reports

The coverage system generates multiple report formats:

- **HTML Report**: `target/coverage/index.html` - Interactive web interface
- **LCOV Report**: `target/coverage/lcov.info` - For GitHub integration  
- **Codecov Report**: `target/coverage/codecov.json` - For Codecov.io

## CI Integration

Coverage is automatically generated on every PR and provides:

1. **Codecov Integration**: Upload to codecov.io for trend tracking
2. **PR Comments**: Detailed coverage report posted as PR comment
3. **GitHub Checks**: Coverage status displayed in PR checks

## Coverage Thresholds

- Current coverage target: **75%**
- PRs won't fail if below threshold (warning only)
- Focus on testing critical game logic and edge cases

## What's Covered

Coverage includes all source namespaces under `jmshelby.monopoly.*` excluding:

- Test files (`*-test` namespaces)
- Development utilities
- REPL helpers

## Improving Coverage

Focus coverage efforts on:

1. **Core game logic** (`core.clj`, `util.clj`)
2. **Player actions** (`player.clj`)  
3. **Game rules** (`definitions.clj` - already at 100%)
4. **Edge cases** in building, trading, bankruptcy

The current low coverage (~12%) is expected for a game engine with complex AI logic and simulation code that's harder to unit test effectively.