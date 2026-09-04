#!/usr/bin/env bash
# Restarts a backend without kicking anybody and without handing anyone a stale
# resource pack: everyone on it is moved to a holding server, the backend is
# restarted, its pack is rebuilt and re-registered, and only then are they
# brought back.
#
#   ./safe-restart.sh survival [sandbox]
#   RETURN_PLAYERS=0 ./safe-restart.sh survival   # move out, do not move back
#
# The order matters. Nova builds the pack a few seconds AFTER the server starts
# answering pings, and it skips the build entirely when the addon version has
# not changed - so a player let back in too early joins on the pack the proxy
# was serving before, which looks like every new block is missing its texture.

set -euo pipefail

TARGET="${1:-survival}"
HOLDING="${2:-sandbox}"
SECRET_FILE=/mnt/shulker/mrds/proxy/forwarding.secret
API=http://127.0.0.1:32452/api
LOG="/mnt/shulker/mrds/$TARGET/logs/latest.log"
SCREEN="luna.$TARGET"
PACK=/mnt/shulker/mrds/packs/smp.zip

SECRET="$(cat "$SECRET_FILE")"

occupants() {
	luna net players 2>/dev/null | awk -v s="$1" '$2 == s { print $1 }'
}

move() {
	local player="$1" server="$2" body status
	body="$(curl -s --max-time 30 -X POST -H "X-Luna-Forwarding-Secret: $SECRET" \
		--data "server=$server" "$API/admin/players/$player/transfer")"
	status="$(printf '%s' "$body" | python3 -c 'import json,sys
try:
	print(json.load(sys.stdin).get("data", {}).get("status", "ERROR"))
except Exception:
	print("ERROR")')"

	echo "  $player -> $server: $status"
	[ "$status" = "SUCCESS" ]
}

evacuate() {
	local attempt player left
	for attempt in 1 2 3; do
		left="$(occupants "$TARGET")"
		[ -z "$left" ] && return 0

		echo "evacuating $TARGET -> $HOLDING (attempt $attempt)"
		for player in $left; do
			move "$player" "$HOLDING" || true
		done
		sleep 2
	done

	left="$(occupants "$TARGET")"
	if [ -n "$left" ]; then
		echo "REFUSING to restart: still on $TARGET: $left" >&2
		return 1
	fi
}

# Waits for a "Resource pack built" line to appear, forcing a build when the
# server does not start one by itself.
await_pack() {
	local waited=0 forced=0

	while [ "$waited" -lt 180 ]; do
		if grep -q 'Resource pack built' "$LOG" 2>/dev/null; then
			echo "  pack built after ${waited}s"
			# the auto_copy out to packs/ lands just after the log line
			sleep 3
			return 0
		fi

		if [ "$waited" -ge 30 ] && [ "$forced" -eq 0 ]; then
			# nova skips the build when the addon version has not changed
			echo "  no build yet; asking for one"
			screen -S "$SCREEN" -p 0 -X stuff "nova resourcePack build$(printf '\r')"
			forced=1
		fi

		sleep 5
		waited=$((waited + 5))
	done

	echo "WARNING: no resource pack build seen in ${waited}s" >&2
}

MOVED="$(occupants "$TARGET")"
evacuate

echo "restarting $TARGET"
luna restart "$TARGET" 2>&1 | tail -1

echo "waiting for the resource pack"
await_pack

BEFORE_SHA="$(luna packs info smp 2>/dev/null | awk '/on the proxy/ { print $NF }')"
luna packs reload >/dev/null 2>&1
sleep 3
AFTER_SHA="$(luna packs info smp 2>/dev/null | awk '/on the proxy/ { print $NF }')"
FILE_SHA="$(sha1sum "$PACK" | cut -c1-12)"

echo "  pack $(stat -c%s "$PACK") bytes · file $FILE_SHA · proxy $AFTER_SHA (was $BEFORE_SHA)"

if [ "$FILE_SHA" != "$AFTER_SHA" ]; then
	echo "WARNING: the proxy is serving a different pack than the file on disk" >&2
fi

# RETURN_PLAYERS=0 leaves everyone on the holding server: useful when the point
# of the restart is something they want to walk back into on their own terms.
if [ "${RETURN_PLAYERS:-1}" = "1" ]; then
	for player in $MOVED; do
		move "$player" "$TARGET" || move "$player" "$TARGET" || true
	done
else
	echo "leaving on $HOLDING: ${MOVED:-nobody}"
fi

echo
luna net players 2>/dev/null | sed -n '1,8p'
