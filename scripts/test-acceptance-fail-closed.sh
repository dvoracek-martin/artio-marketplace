#!/usr/bin/env bash
set -euo pipefail

set +e
MARKETPLACE_ACCEPTANCE_INJECT_FAILURE=1 bash ./scripts/run-acceptance.sh >/dev/null 2>&1
status=$?
set -e
if [[ $status -ne 97 ]]; then
  echo "Acceptance wrapper did not propagate the injected failure (exit $status)." >&2
  exit 1
fi
echo 'Acceptance failure-gate self-check passed: the injected failure exits nonzero.'
