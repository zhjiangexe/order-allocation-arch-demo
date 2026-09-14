#!/usr/bin/env bash

set -euo pipefail

REPOSITORY_ROOT="$(git rev-parse --show-toplevel)"

git -C "$REPOSITORY_ROOT" diff --check

cd "$REPOSITORY_ROOT/backend"
./gradlew \
  :ordering-context:spotlessCheck \
  :ordering-context:test \
  :fulfillment-process:compileTestJava \
  :deployments:monolith:compileTestJava \
  "$@"
