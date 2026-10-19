#!/usr/bin/env bash
# Renderer tests. Builds the image, runs it hardened on an internal (no outbound) network,
# and talks to it with `docker exec` (an internal network has no published ports).
set -u
cd "$(dirname "$0")/.."
IMG=resume-renderer:test
NET=renderer-test-net-$$
NAME=renderer-test-$$
FAILS=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILS=$((FAILS+1)); }
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1; }
trap cleanup EXIT

docker build -q -t "$IMG" . >/dev/null || { echo "build failed"; exit 1; }
docker network create --internal "$NET" >/dev/null
docker run -d --name "$NAME" --network "$NET" --read-only --tmpfs /work:rw,size=64m,uid=10001 \
  --cap-drop ALL --security-opt no-new-privileges --pids-limit 128 --memory 512m \
  -e RENDER_TIMEOUT_SECONDS=5 -e RENDER_MAX_BYTES=300000 "$IMG" >/dev/null
for i in $(seq 30); do
  docker exec "$NAME" python3 -c "import urllib.request as u;u.urlopen('http://localhost:8080/health')" 2>/dev/null && break
  sleep 1
done

# post FILE -> prints "STATUS PAGES"; response body saved in /tmp inside the container (/work/out)
post() {
  docker exec -i "$NAME" python3 -c '
import sys, urllib.request as u, urllib.error as e
req = u.Request("http://localhost:8080/render", data=sys.stdin.buffer.read(), method="POST")
try:
    r = u.urlopen(req, timeout=60); b = r.read()
    print(r.status, r.headers.get("X-Page-Count", "-"), b[:5].decode("latin1"))
except e.HTTPError as x:
    print(x.code, "-", x.read()[:300].decode("latin1").replace("\n", " "))
' < "$1"
}

out=$(post tests/fixtures/jake.tex)
[[ "$out" == "200 1 %PDF-" ]] && pass "Jake's template renders to a 1-page PDF" || fail "Jake render: $out"

printf '\\documentclass{article}\\begin{document}Hello\\end{document}' > /tmp/known-$$.tex
out=$(post /tmp/known-$$.tex)
[[ "$out" == "200 1 %PDF-" ]] && pass "known document renders to PDF" || fail "known doc: $out"

out=$(post tests/fixtures/loop.tex)
[[ "$out" == 504* || "$out" == 422* ]] && pass "infinite-loop document is stopped ($out)" || fail "loop: $out"
# the loop fixture usually ends via TeX's own capacity error; this one proves the wall-clock kill:
cat > /tmp/spin-$$.tex <<'TEX'
\documentclass{article}
\begin{document}
\newcount\n
\def\spin{\advance\n by 1 \spin}\spin
\end{document}
TEX
start=$(date +%s)
out=$(post /tmp/spin-$$.tex)
secs=$(( $(date +%s) - start ))
[[ "$out" == 504* && $secs -lt 15 ]] && pass "tail-recursive spin killed by the hard timeout in ${secs}s" || fail "spin: $out after ${secs}s"

out=$(post tests/fixtures/shell.tex)
if docker exec "$NAME" sh -c 'ls /work | grep -q pwned'; then fail "shell escape created a file"; else pass "shell escape wrote nothing ($out)"; fi
[[ "$out" == 422* || "$out" == 200* ]] || fail "shell escape: unexpected $out"

out=$(post tests/fixtures/readfile.tex)
[[ "$out" == 422* ]] && pass "reading an absolute path outside the work dir is refused" || fail "readfile: $out"

head -c 400000 /dev/zero | tr '\0' 'a' > /tmp/big-$$.tex
out=$(post /tmp/big-$$.tex)
[[ "$out" == 413* ]] && pass "oversized document rejected with 413" || fail "size limit: $out"

if docker exec "$NAME" python3 -c "
import socket
s = socket.create_connection(('1.1.1.1', 443), timeout=4)" 2>/dev/null; then
  fail "container reached the internet"
else
  pass "outbound network access from the container fails"
fi
if docker exec "$NAME" python3 -c "
import socket; socket.gethostbyname('example.com')" 2>/dev/null; then
  fail "container resolved external DNS"
else
  pass "external DNS resolution fails"
fi
rm -f /tmp/known-$$.tex /tmp/spin-$$.tex /tmp/big-$$.tex
echo "failures: $FAILS"
exit $FAILS
