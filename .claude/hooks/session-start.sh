#!/bin/bash
set -euo pipefail

# Only run in Claude Code web sessions
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# Configure Maven proxy settings for Claude Code Web
# Maven doesn't honor HTTP_PROXY/HTTPS_PROXY by default
if [ -n "${HTTPS_PROXY:-}" ]; then
  echo "Configuring Maven proxy settings..."

  # Extract proxy components from HTTPS_PROXY
  # Format: http://username:password@host:port
  PROXY_URL="${HTTPS_PROXY}"

  # Extract username (before first colon in auth section)
  PROXY_USER=$(echo "$PROXY_URL" | sed -E 's|^https?://([^:]+):.*|\1|')

  # Extract password (between first colon and @ in auth section)
  PROXY_PASS=$(echo "$PROXY_URL" | sed -E 's|^https?://[^:]+:([^@]+)@.*|\1|')

  # Extract host (after @ and before final colon)
  PROXY_HOST=$(echo "$PROXY_URL" | sed -E 's|^https?://.*@([^:]+):.*|\1|')

  # Extract port (last number after final colon)
  PROXY_PORT=$(echo "$PROXY_URL" | sed -E 's|^.*:([0-9]+)$|\1|')

  # Create Maven settings directory
  mkdir -p ~/.m2

  # Generate Maven settings.xml with proxy configuration including authentication
  cat > ~/.m2/settings.xml << EOF
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd">
  <proxies>
    <proxy>
      <id>claude-code-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>${PROXY_HOST}</host>
      <port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.*|[::1]</nonProxyHosts>
    </proxy>
    <proxy>
      <id>claude-code-proxy-https</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>${PROXY_HOST}</host>
      <port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.*|[::1]</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
EOF

  echo "Maven proxy configured: ${PROXY_HOST}:${PROXY_PORT} (with authentication)"
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

echo "Clojure CLI installation complete: $(clojure --version)"
