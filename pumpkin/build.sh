#!/usr/bin/env bash
#
# Build the Pumpkin components and stage them where `luna luna sync` looks.
#
# Each component ships with a `.permissions.json` beside it. That file is not
# decoration: Pumpkin asks the operator to approve a plugin's capabilities on
# first load and caches the answer against the exact list the plugin declares,
# and a server started inside a screen session has nobody to answer the prompt.
# `luna` pre-approves the deployment from this file, so the list has to travel
# with the artefact rather than living only inside it.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$(cd "$here/.." && pwd)/output/pumpkin"

mkdir -p "$out"

cargo build --release --target wasm32-wasip2 --manifest-path "$here/Cargo.toml"

# crate directory -> artefact name, since cargo turns dashes into underscores
components=("luna-core:luna_core")

for entry in "${components[@]}"; do
	crate="${entry%%:*}"
	artefact="${entry##*:}"
	wasm="$here/target/wasm32-wasip2/release/$artefact.wasm"

	if [[ ! -f "$wasm" ]]; then
		echo "missing $wasm" >&2
		exit 1
	fi

	cp "$wasm" "$out/$artefact.wasm"

	# the same permissions.toml the component compiled in, as the JSON array
	# luna reads; python is here for the TOML parse alone. The heredoc is not
	# indented because a tab-stripping one would break python's own indentation.
	python3 - "$here/$crate/permissions.toml" "$out/$artefact.wasm.permissions.json" <<'PY'
import json, sys, tomllib

source, target = sys.argv[1], sys.argv[2]

with open(source, "rb") as handle:
    permissions = tomllib.load(handle)["permissions"]

with open(target, "w") as handle:
    json.dump({"permissions": permissions}, handle, indent="\t")
    handle.write("\n")
PY

	echo "staged $artefact.wasm ($(wc -c <"$out/$artefact.wasm") bytes) + permissions"
done
