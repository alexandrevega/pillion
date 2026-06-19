#!/usr/bin/env bash
# Spike: HERE Routing API v8 — inspect the maneuver + traffic data shape.
# Purpose: see what HERE returns so we know how to map it onto NaviLite
# turn-icon ordinals (docs/PROTOCOL.md) before touching the KMP NavEngine.
#
# Usage:
#   export HERE_API_KEY=your_key_here        # from developer.here.com (free tier)
#   ./spikes/here-routing.sh                 # default Amsterdam route
#   ./spikes/here-routing.sh 52.3463,4.8889 52.3791,4.9003   # origin dest
#
# BYOK only — never commit a key. This script reads it from the environment.

set -euo pipefail

if [[ -z "${HERE_API_KEY:-}" ]]; then
  echo "ERROR: set HERE_API_KEY (get a free key at https://developer.here.com)" >&2
  exit 1
fi

# Default: Churchilllaan, Amsterdam -> Amsterdam Centraal (matches the dash photo locale)
ORIGIN="${1:-52.3463,4.8889}"
DEST="${2:-52.3791,4.9003}"

# transportMode: HERE v8 has car/truck/scooter/bicycle/pedestrian — NO dedicated
# motorcycle profile (unlike Valhalla). 'car' is the closest for now; flag for later.
MODE="car"

echo ">> HERE Routing v8: $ORIGIN -> $DEST (mode=$MODE, traffic=live)"
echo

# Notes (learned the hard way against the live API):
#   - DO NOT send departureTime=now — invalid in v8; omitting it gives live traffic.
#   - 'actions' REQUIRES 'polyline' in the return list (error E605013 otherwise).
RESP="$(curl -fsS -G "https://router.hereapi.com/v8/routes" \
  --data-urlencode "transportMode=$MODE" \
  --data-urlencode "origin=$ORIGIN" \
  --data-urlencode "destination=$DEST" \
  --data-urlencode "return=polyline,summary,actions,instructions" \
  --data-urlencode "apiKey=$HERE_API_KEY")"

# Pretty-print if jq is available, else fall back to python.
pp() { if command -v jq >/dev/null; then jq "$@"; else python3 -m json.tool; fi; }

if ! command -v jq >/dev/null; then
  echo "(jq not found — dumping raw JSON; install jq for the summary)"
  echo "$RESP" | pp
  exit 0
fi

echo "== Route summary (traffic-aware) =="
echo "$RESP" | jq -r '.routes[0].sections[0].summary
  | "distance: \(.length) m   duration(with traffic): \(.duration) s   base: \(.baseDuration) s   delay: \(.duration - .baseDuration) s"'
echo

echo "== Maneuvers (action/direction/severity -> instruction) =="
echo "$RESP" | jq -r '.routes[0].sections[0].actions[]
  | "  [\(.offset // 0)] \(.action)\(if .direction then "/"+.direction else "" end)\(if .severity then " ("+.severity+")" else "" end)  —  \(.instruction)"'
echo

echo "== Distinct action types (what we must map to NaviLite turn-icon ordinals) =="
echo "$RESP" | jq -r '[.routes[0].sections[0].actions[].action] | unique | .[]' | sed 's/^/  - /'
