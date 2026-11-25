#!/usr/bin/env bash

set -euo pipefail

REPOSITORY_ROOT="$(git rev-parse --show-toplevel)"

git -C "$REPOSITORY_ROOT" diff --check

cd "$REPOSITORY_ROOT/backend"
./gradlew \
  :ordering:ordering-server:spotlessCheck \
  :ordering:ordering-server:test \
  :archone-framework:architecture-rules:test \
  :fulfillment:compileTestJava \
  :deployments:monolith:compileTestJava \
  "$@"
