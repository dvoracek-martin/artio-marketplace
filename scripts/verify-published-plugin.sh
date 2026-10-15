#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 3 ]] || { echo "Usage: $0 MARKETPLACE_URL PLUGIN_ID VERSION" >&2; exit 2; }
python=${PYTHON:-python3}
command -v "$python" >/dev/null || { echo 'Set PYTHON to a Python 3 executable or install python3.' >&2; exit 2; }
manifest=$("$python" - "$1" "$2" "$3" <<'PY'
import json,sys,urllib.request
url=f"{sys.argv[1].rstrip('/')}/api/v1/marketplace/plugins/{sys.argv[2]}/releases/{sys.argv[3]}/manifest"
with urllib.request.urlopen(url,timeout=20) as r: print(json.dumps(json.load(r)))
PY
)
"$python" -c 'import json,sys; m=json.loads(sys.argv[1]); print("\n".join([m["image"]]+[s["image"] for s in m.get("services",[])]))' "$manifest" | while IFS= read -r image; do echo "docker pull $image"; docker pull "$image"; done
