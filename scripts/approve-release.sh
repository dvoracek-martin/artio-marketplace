#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 3 ]] || {
  echo "Usage: MARKETPLACE_ADMIN_TOKEN_FILE=/secure/admin-token $0 RELEASE_ID MARKETPLACE_URL REVIEW_REASON" >&2
  exit 2
}
: "${MARKETPLACE_ADMIN_TOKEN_FILE:?Set MARKETPLACE_ADMIN_TOKEN_FILE to a protected admin token file}"
python=${PYTHON:-python3}
command -v "$python" >/dev/null || { echo 'Set PYTHON to a Python 3 executable or install python3.' >&2; exit 2; }
"$python" - "$1" "${2%/}" "$3" "$MARKETPLACE_ADMIN_TOKEN_FILE" <<'PY'
import json, pathlib, sys, urllib.error, urllib.request
release_id, base, reason, token_file = sys.argv[1:]
token = pathlib.Path(token_file).read_text(encoding='utf-8').strip()
if len(token) < 32: raise SystemExit('Admin token must contain at least 32 characters')
if not reason.strip() or len(reason) > 1000: raise SystemExit('Review reason must be 1 to 1000 characters')
url = f'{base}/api/v1/admin/marketplace/releases/{release_id}/approve'
request = urllib.request.Request(url, data=json.dumps({'reason': reason}).encode(), headers={
    'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, method='POST')
try:
    with urllib.request.urlopen(request, timeout=20) as response:
        print(json.dumps(json.loads(response.read()), indent=2))
except urllib.error.HTTPError as error:
    raise SystemExit(f'HTTP {error.code}: {error.read(4096).decode(errors="replace")}')
PY
