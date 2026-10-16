#!/usr/bin/env bash
set -euo pipefail

if [[ ${MARKETPLACE_ACCEPTANCE_INJECT_FAILURE:-0} == 1 ]]; then
  echo 'Injected acceptance failure: CI gate must fail closed.' >&2
  exit 97
fi
exec bash ./scripts/docker-acceptance.sh
