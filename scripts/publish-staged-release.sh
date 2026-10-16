#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 2 ]] || {
  echo "Usage: MARKETPLACE_PUBLISHER_TOKEN_FILE=/secure/publisher-token $0 RELEASE_ID MARKETPLACE_URL" >&2
  exit 2
}
: "${MARKETPLACE_PUBLISHER_TOKEN_FILE:?Set MARKETPLACE_PUBLISHER_TOKEN_FILE to a protected publisher token file}"
python=${PYTHON:-python3}
command -v "$python" >/dev/null || { echo 'Set PYTHON to a Python 3 executable or install python3.' >&2; exit 2; }
"$python" - "$1" "${2%/}" "$MARKETPLACE_PUBLISHER_TOKEN_FILE" <<'PY'
import json, pathlib, sys, urllib.error, urllib.request
release_id, base, token_file = sys.argv[1:]
token = pathlib.Path(token_file).read_text(encoding='utf-8').strip()
if len(token) < 32: raise SystemExit('Publisher token must contain at least 32 characters')
url = f'{base}/api/v1/admin/marketplace/releases/{release_id}/publish'
request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token}, method='POST')
try:
    with urllib.request.urlopen(request, timeout=30) as response:
        print(json.dumps(json.loads(response.read()), indent=2))
except urllib.error.HTTPError as error:
    raise SystemExit(f'HTTP {error.code}: {error.read(4096).decode(errors="replace")}')
PY
