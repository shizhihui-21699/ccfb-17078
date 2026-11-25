#!/usr/bin/env bash
set -euo pipefail

# On this macOS image the /usr/bin/java shim can exist without a registered JVM.
# Prefer a real JAVA_HOME if the current java already works; otherwise discover Homebrew JDKs.
if ! java -version >/dev/null 2>&1; then
  candidates=(
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
  )
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi

exec sbt "$@"
