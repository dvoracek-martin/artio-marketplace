#!/usr/bin/env bash
set -euo pipefail
api_base="${MARKETPLACE_PRODUCTION_API_BASE:-http://127.0.0.1:8080}"
for path in /dev-admin/ /api/v1/dev-admin/session; do
  status=$(curl --silent --show-error -o /dev/null -w '%{http_code}' "$api_base$path")
  if [[ "$status" != 404 ]]; then
    echo "Production dev-admin route $path returned $status (expected 404)." >&2
    exit 1
  fi
done
echo 'Production image has no local admin UI or dev API routes.'
