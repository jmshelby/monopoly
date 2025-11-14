#!/bin/bash
set -euo pipefail

# Only run in Claude Code web sessions
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# Check if Clojure CLI is already installed
if command -v clojure &> /dev/null; then
  echo "Clojure CLI already installed: $(clojure --version)"
  # Ensure PATH is set in case it's not already
  if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$CLAUDE_ENV_FILE"
  fi
  exit 0
fi

echo "Installing Clojure CLI tools..."

# Download and install Clojure CLI to user directory
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
./linux-install.sh --prefix "$HOME/.local"
rm linux-install.sh

# Add to PATH for this session
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$CLAUDE_ENV_FILE"
fi

echo "Clojure CLI installation complete: $(clojure --version)"
