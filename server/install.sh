#!/bin/bash
# Run from a checked-out repository. No third-party Python packages required.
set -euo pipefail
if [[ ${EUID} -ne 0 ]]; then
    printf '%s\n' 'Run with sudo: sudo bash server/install.sh https://road.example.com'
    exit 1
fi
SOURCE=$(dirname "$(readlink -f "$0")")
PUBLIC_URL=${1:-}
if [[ -z "$PUBLIC_URL" ]]; then
    read -r -p 'Public HTTPS URL (https://road.example.com): ' PUBLIC_URL
fi
python3 - "$PUBLIC_URL" <<'PY'
import sys, urllib.parse
p = urllib.parse.urlsplit(sys.argv[1])
if (p.scheme != 'https' or not p.hostname or p.username or p.password or p.query or p.fragment
        or p.path not in ('', '/') or any(c.isspace() or c in "'\"\\" for c in sys.argv[1])):
    raise SystemExit('Expected HTTPS origin, e.g. https://road.example.com')
if sys.version_info < (3, 11):
    raise SystemExit('Python 3.11+ required (Debian 12+)')
PY
apt-get update
apt-get install -y python3 ca-certificates tzdata
if ! id nowbarroad >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/now-bar-road --shell /usr/sbin/nologin nowbarroad
fi
install -d -m 755 /opt/now-bar-road/server
install -m 644 "$SOURCE/road.py" /opt/now-bar-road/server/road.py
install -m 644 "$SOURCE/now-bar-road.service" /etc/systemd/system/now-bar-road.service
if [[ ! -f /etc/now-bar-road.env ]]; then
    TOKEN=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
    umask 077
    printf 'ROAD_HOST=127.0.0.1\nROAD_PORT=8080\nROAD_PUBLIC_URL=%s\nROAD_TOKEN=%s\n' \
        "${PUBLIC_URL%/}" "$TOKEN" > /etc/now-bar-road.env
else
    printf '%s\n' 'Existing /etc/now-bar-road.env preserved; change ROAD_PUBLIC_URL there if needed.'
fi
install -m 755 "$SOURCE/connection.sh" /usr/local/bin/now-bar-road-link
systemctl daemon-reload
systemctl enable --now now-bar-road
systemctl restart now-bar-road
printf '\n%s\n' 'Server installed. Configure HTTPS reverse proxy to 127.0.0.1:8080.'
printf '%s\n' 'Connection link (contains your private token):'
/usr/local/bin/now-bar-road-link
