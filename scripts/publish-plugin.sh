#!/usr/bin/env bash
set -euo pipefail
dry_run=false
if [[ ${1:-} == --dry-run ]]; then dry_run=true; shift; fi
[[ $# -eq 2 ]] || { echo "Usage: MARKETPLACE_PUBLISHER_TOKEN_FILE=/secure/token $0 [--dry-run] PACKAGE MARKETPLACE_URL" >&2; exit 2; }
package=$(realpath "$1"); base=${2%/}; : "${MARKETPLACE_PUBLISHER_TOKEN_FILE:?Set MARKETPLACE_PUBLISHER_TOKEN_FILE to a protected token file}"
python=${PYTHON:-python3}
command -v "$python" >/dev/null || { echo 'Set PYTHON to a Python 3 executable or install python3.' >&2; exit 2; }
"$python" - "$package" "$base" "$MARKETPLACE_PUBLISHER_TOKEN_FILE" "$dry_run" <<'PY'
import hashlib, json, os, pathlib, re, sys, urllib.error, urllib.request, uuid
root, base, token_file, dry = pathlib.Path(sys.argv[1]), sys.argv[2], pathlib.Path(sys.argv[3]), sys.argv[4] == 'true'
token = token_file.read_text(encoding='utf-8').strip()
if len(token) < 32: raise SystemExit('Publisher token must contain at least 32 characters')
manifest = json.loads((root/'manifest.json').read_text(encoding='utf-8'))
catalog = json.loads((root/'catalog.json').read_text(encoding='utf-8'))
images = [manifest['image']] + [s['image'] for s in manifest.get('services', [])]
for image in images:
    if not re.fullmatch(r'[a-zA-Z0-9.-]+(?::[0-9]{1,5})?/[a-zA-Z0-9._/-]+@sha256:[a-f0-9]{64}', image): raise SystemExit(f'Invalid digest reference: {image}')
icon = root/catalog['icon']; shots = [root/s['file'] for s in catalog.get('screenshots', [])]
if not icon.is_file() or any(not p.is_file() for p in shots): raise SystemExit('Icon or screenshot file is missing')
placeholder = 'sha256-' + '0'*64
catalog['icon'] = placeholder
for shot in catalog.get('screenshots', []): shot['assetId'] = placeholder; shot.pop('file', None)
headers = {'Authorization': 'Bearer '+token}
def request(path, method='GET', obj=None, extra=None, data=None, content_type=None):
    h = dict(headers); h.update(extra or {})
    if obj is not None: data=json.dumps(obj,separators=(',',':')).encode();content_type='application/json'
    if content_type: h['Content-Type']=content_type
    req=urllib.request.Request(base+path,data=data,headers=h,method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as r: return json.loads(r.read()) if r.headers.get_content_type()=='application/json' else r.read()
    except urllib.error.HTTPError as e: raise SystemExit(f'HTTP {e.code} {path}: {e.read(4096).decode(errors="replace")}')
payload={'manifest':manifest,'catalog':catalog}
validation=request('/api/v1/admin/marketplace/releases/validate','POST',payload)
if not validation.get('valid'): raise SystemExit('Validation failed: '+'; '.join(validation.get('errors',[])))
if dry: print('Validation passed; dry run did not publish.');sys.exit(0)
mhash=hashlib.sha256((root/'manifest.json').read_bytes()).hexdigest();key=f"publish-{manifest['id']}-{manifest['version']}-{mhash}"
draft=request('/api/v1/admin/marketplace/releases','POST',payload,{'Idempotency-Key':key}); rid=draft['releaseId']
if draft.get('state') == 'PUBLISHED': print(f"Already published {manifest['id']} {manifest['version']} (idempotent retry).\nreleaseId={rid}");sys.exit(0)
def upload(path, kind):
    boundary='----artio'+uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+path.read_bytes()+f'\r\n--{boundary}--\r\n'.encode()
    return request(f'/api/v1/admin/marketplace/releases/{rid}/assets?kind={kind}','POST',extra={'Content-Type':f'multipart/form-data; boundary={boundary}'},data=body)['assetId']
catalog['icon']=upload(icon,'icon')
for i,path in enumerate(shots):catalog['screenshots'][i]['assetId']=upload(path,'screenshot')
request(f'/api/v1/admin/marketplace/releases/{rid}/catalog','PUT',catalog)
result=request(f'/api/v1/admin/marketplace/releases/{rid}/publish','POST')
print(f"Published {manifest['id']} {manifest['version']}: {base}/api/v1/marketplace/plugins/{manifest['id']}/releases/{manifest['version']}")
print('releaseId='+rid)
print('manifestDigest='+validation['manifestDigest'])
PY
