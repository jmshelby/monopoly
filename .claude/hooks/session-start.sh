#!/bin/bash
set -euo pipefail

# Only run in Claude Code web sessions
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

echo "Configuring Maven for Claude Code remote environment..."

# Create Maven config directory
mkdir -p ~/.m2

# Extract proxy host and port from HTTPS_PROXY environment variable
# Expected format: http://user:pass@host:port or http://host:port
if [ -n "${HTTPS_PROXY:-}" ]; then
  # Extract host and port from proxy URL
  PROXY_HOST=$(echo "$HTTPS_PROXY" | sed -E 's|https?://([^:@]*:)?([^:@]*)@||' | sed -E 's|https?://||' | cut -d':' -f1)
  PROXY_PORT=$(echo "$HTTPS_PROXY" | sed -E 's|https?://([^:@]*:)?([^:@]*)@||' | sed -E 's|https?://||' | cut -d':' -f2 | cut -d'/' -f1)

  echo "Detected proxy: $PROXY_HOST:$PROXY_PORT"

  # Get no_proxy list
  NO_PROXY_HOSTS="${NO_PROXY:-localhost|127.0.0.1}"

  # Create Maven settings.xml with proxy configuration
  cat > ~/.m2/settings.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
          http://maven.apache.org/xsd/settings-1.0.0.xsd">
    <proxies>
        <proxy>
            <id>http-proxy</id>
            <active>true</active>
            <protocol>http</protocol>
            <host>${PROXY_HOST}</host>
            <port>${PROXY_PORT}</port>
            <nonProxyHosts>${NO_PROXY_HOSTS}</nonProxyHosts>
        </proxy>
        <proxy>
            <id>https-proxy</id>
            <active>true</active>
            <protocol>https</protocol>
            <host>${PROXY_HOST}</host>
            <port>${PROXY_PORT}</port>
            <nonProxyHosts>${NO_PROXY_HOSTS}</nonProxyHosts>
        </proxy>
    </proxies>
</settings>
EOF

  echo "✅ Maven settings.xml created with proxy configuration"
else
  echo "⚠️  No HTTPS_PROXY environment variable found, skipping proxy configuration"
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

echo "Session start hook completed successfully"
