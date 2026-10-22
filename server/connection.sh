#!/bin/bash
set -euo pipefail
if [[ ${EUID} -ne 0 ]]; then
    printf '%s\n' 'Run: sudo now-bar-road-link'
    exit 1
fi
set -a
source /etc/now-bar-road.env
set +a
exec /usr/bin/python3 /opt/now-bar-road/server/road.py --connection-link
