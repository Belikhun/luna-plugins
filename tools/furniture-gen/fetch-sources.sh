#!/usr/bin/env bash
# Stages the MIT-licensed furniture packs the generator reads.
#
#   ./fetch-sources.sh <dir>
#   bun generate.ts --sources <dir> --addon ../../paper/luna-smp/src/main/resources
#
# Nothing here is committed: the sources are large, and their licences are
# reproduced in the addon's THIRD-PARTY-ASSETS.md instead. Re-run this whenever
# a source is updated, then re-run the generator and rebuild the addon.

set -euo pipefail

DEST="${1:-}"

if [ -z "$DEST" ]; then
	echo "usage: $0 <dir>" >&2
	exit 1
fi

mkdir -p "$DEST"
cd "$DEST"

fetch_tarball() {
	local name="$1"
	local url="$2"

	echo "-> $name"
	curl -fsSL -o "$name.tar.gz" "$url"
	mkdir -p "$name"
	tar xzf "$name.tar.gz" -C "$name" --strip-components=1
	rm "$name.tar.gz"
}

fetch_tarball adorn https://github.com/Juuxel/Adorn/archive/refs/heads/1.21.tar.gz
fetch_tarball beautify-refab https://github.com/Suel-ki/Beautify-Refabricated/archive/refs/heads/1.21.tar.gz
fetch_tarball decoblocks https://github.com/lilypuree/Decorative-Blocks/archive/refs/heads/1.20.tar.gz

# the second furniture wave. Each licence was checked at the repository rather
# than on its download page, and each has a real LICENSE file covering the whole
# tree: Cozy Home CC0-1.0, skniro's Furniture MIT, Furnies MIT, Analog CC0-1.0.
fetch_tarball cozyhome https://github.com/PrelawAverage/cozyhome-fabric/archive/refs/heads/master.tar.gz
fetch_tarball skniro https://github.com/skniro/skniro-Furniture/archive/refs/heads/1.21.11.tar.gz
fetch_tarball furnies https://github.com/gifnoozy/furnies/archive/refs/heads/1.21.4.tar.gz
fetch_tarball analog https://github.com/Fabiofdez/Analog-Clock/archive/refs/heads/main.tar.gz

# The industrial deco wave: Engineer's Decor (stfwi), MIT over the whole tree,
# same author and same licence as the rsgauges the instruments were checked
# against. Source of the clinker/slag/rebar cubes, the steel furniture, the
# poles, the mesh fence and the iron lights.
fetch_tarball engdecor https://github.com/stfwi/engineers-decor/archive/refs/heads/1.19.tar.gz

# The medieval wave. Aesthetic Frames (Alminoris) is MIT over the whole tree;
# only its texture strips are read, cut into single tiles by the generator.
fetch_tarball aframes https://github.com/Alminoris/AestheticFrames/archive/refs/heads/master.tar.gz

# Rustic (cadaverous_queen) declares MIT on CurseForge, on Modrinth and in the
# mods.toml inside the jar; its repository carries no LICENSE file, which is
# recorded in THIRD-PARTY-ASSETS.md. The 1.20.1 "Rustic Renaissance" build the
# author co-published is the one with models in the current format, so that
# jar is resolved through the Modrinth API and unpacked for its assets.
echo "-> rustic"
RUSTIC_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/rustic-renaissance/version" \
	| python3 -c 'import json,sys; vs=[v for v in json.load(sys.stdin) if v["version_number"]=="1.20.1-1.0.0"]; print(vs[0]["files"][0]["url"])')
curl -fsSL -o rustic.jar "$RUSTIC_URL"
mkdir -p rustic
unzip -oq rustic.jar -d rustic 'assets/*'
rm rustic.jar

# Builder's Bounty (Diesse) is CC0-1.0; its models ride in the resource pack
# half of the datapack release, under the vanilla namespace
echo "-> bbounty"
BOUNTY_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/builders-boundry/version" \
	| python3 -c 'import json,sys; fs=[f for v in json.load(sys.stdin) if v["version_number"]=="0.2" for f in v["files"] if f["filename"]=="builder_boundry_rsc.zip"]; print(fs[0]["url"])')
curl -fsSL -o bbounty.zip "$BOUNTY_URL"
mkdir -p bbounty
unzip -oq bbounty.zip -d bbounty 'assets/*'
rm bbounty.zip

# Create: Electro Energetics (George VI) is MIT over the whole tree, LICENSE
# file included. Only its power-line art is read: the concrete pole segments,
# the crossarm mount and the insulator stacks, plus the wire and grip
# textures. Its wires themselves are drawn in code upstream, so the hanging
# line here is our own geometry over its wire sprite.
fetch_tarball ee https://github.com/george8188625/Create-Electro-Energetics/archive/refs/heads/1.21.1.tar.gz

# Tables & Chairs 2 ships as a resource pack on Modrinth rather than a repo, so
# its newest file for the server's Minecraft version is resolved through the API
echo "-> tac"
TAC_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/furniture-resources/version?game_versions=%5B%221.21.11%22%5D" \
	| python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["files"][0]["url"])')
curl -fsSL -o tac.zip "$TAC_URL"
mkdir -p tac
unzip -oq tac.zip -d tac
rm tac.zip

# Many Flowers ships as a Fabric mod rather than a repo tarball we can read
# models out of, so its newest jar is resolved through the API and unpacked for
# its assets; only its own sprites are used, and they carry its MIT licence
echo "-> many-flowers"
MF_URL=$(curl -fsSL "https://api.modrinth.com/v2/project/many-flowers/version" \
	| python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["files"][0]["url"])')
curl -fsSL -o many-flowers.jar "$MF_URL"
mkdir -p many-flowers
unzip -oq many-flowers.jar -d many-flowers 'assets/*'
rm many-flowers.jar

# Floral Enchantment is deliberately absent. Its art is All Rights Reserved
# upstream on every branch, whatever a stray MIT file in a source drop says, so
# the families taken from it are composed in flora.ts out of our own geometry
# over vanilla textures rather than staged from anywhere.

# The pot skins are not staged here: they are not a pack, they are sixty
# individual textures named by id in catalog.ts, and the generator downloads
# them itself into <dir>/heads on the first run.

echo
echo "staged in $DEST:"
ls -1
