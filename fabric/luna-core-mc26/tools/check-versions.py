#!/usr/bin/env python3
"""
Check that every Minecraft member the 26.x build links against exists on each
26.x release, by reading the game's own server jar.

This is luna-core-fabric/tools/check-versions.py for the other game line, and it
works differently for one reason: from 26.1 Mojang ships the server unobfuscated,
so there is no intermediary mapping to compare against - fabric publishes the
empty 0.0.0 placeholder instead. What there is, though, is better: the server jar
itself declares every member under the same names this build links against, so
the check reads the real thing rather than a record of it.

Supertypes are followed, so an inherited method resolves the way the JVM would.

Usage:  python3 tools/check-versions.py [path/to/mod.jar]

Needs javap on PATH and network access to Mojang's launcher metadata. Server jars
are cached under build/, so only the first run downloads. Exits non-zero when a
reference is missing on any checked version.
"""

import json
import re
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
ROOT = MODULE.parent

# Every 26.x release the build declares support for, oldest first. 26.1 is the
# first: the versions below it are obfuscated and belong to the other build.
VERSIONS = ["26.1", "26.1.1", "26.1.2", "26.2"]

MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

DEFAULT_JAR = ROOT / "output" / "fabric" / "luna-core-mc26-fabric-all.jar"
CACHE = MODULE / "build" / "servers"

REFERENCE = re.compile(
	r"net/minecraft/[A-Za-z0-9_/$]+\.[A-Za-z0-9_\"<>]+:(?:\([^)]*\)[^\s]*|L?[A-Za-z0-9_/$;\[]+)"
)

# javap -s prints a member declaration at two spaces of indent, then its
# descriptor on the following line
DECLARATION = re.compile(r"^\s{2}\S")
DESCRIPTOR = re.compile(r"^\s+descriptor:\s*(\S+)")
TYPE_DECLARATION = re.compile(
	r"^[\w\s]*\b(?:class|interface|enum|record)\s+([\w.$]+)"
	r"(?:\s+extends\s+(.+?))?(?:\s+implements\s+(.+?))?\s*\{"
)


def javap(jar: Path, classes: list[str], verbose: bool = False) -> str:
	return subprocess.run(
		["javap", "-p", "-s", *(["-v"] if verbose else []), "-classpath", str(jar), *classes],
		capture_output=True, text=True,
	).stdout


def server_jar(version: str) -> Path:
	"""The unobfuscated server classes for a version, downloaded once and cached.

	Mojang ships the server as a bundler holding the real jar under
	META-INF/versions/, so what is cached here is that inner jar, not the download.
	"""
	cached = CACHE / f"server-{version}.jar"

	if cached.exists():
		return cached

	CACHE.mkdir(parents=True, exist_ok=True)

	with urllib.request.urlopen(MANIFEST_URL) as response:
		manifest = json.load(response)

	entry = next((v for v in manifest["versions"] if v["id"] == version), None)

	if entry is None:
		raise SystemExit(f"{version} is not in Mojang's version manifest")

	with urllib.request.urlopen(entry["url"]) as response:
		meta = json.load(response)

	bundle = CACHE / f"bundle-{version}.jar"
	urllib.request.urlretrieve(meta["downloads"]["server"]["url"], bundle)

	with zipfile.ZipFile(bundle) as archive:
		inner = [
			name for name in archive.namelist()
			if name.startswith("META-INF/versions/") and name.endswith(".jar")
		]

		if not inner:
			raise SystemExit(f"{version}'s server download carries no bundled server jar")

		cached.write_bytes(archive.read(inner[0]))

	bundle.unlink()

	return cached


def strip_generics(line: str) -> str:
	"""Drop every balanced <...> group.

	Without this a type parameter's own bound reads as the class's supertype:
	`class Foo<R extends Runnable> extends Bar` would report Runnable as the
	parent and lose everything Foo actually inherits.
	"""
	kept: list[str] = []
	depth = 0

	for char in line:
		if char == "<":
			depth += 1
		elif char == ">":
			depth = max(0, depth - 1)
		elif depth == 0:
			kept.append(char)

	return "".join(kept)


def member_name(declaration: str, simple_name: str) -> str:
	"""The JVM name of the member a javap declaration line describes."""
	line = declaration.strip().rstrip(";").strip()

	if "(" not in line:
		tokens = line.split()

		return tokens[-1] if tokens else ""

	head = line.split("(", 1)[0].strip()
	tokens = head.split()
	token = tokens[-1].split(".")[-1] if tokens else ""

	# javap spells a constructor as the class's own qualified name
	return "<init>" if token == simple_name else token


def inspect(server: Path, owner: str) -> tuple[set[str], set[str]] | None:
	"""A class's declared members and its supertypes, or None when it is absent."""
	text = javap(server, [owner.replace("/", ".")])

	if not text.strip():
		return None

	simple_name = owner.split("/")[-1]
	members: set[str] = set()
	supertypes: set[str] = set()
	pending: str | None = None

	for line in text.splitlines():
		declared_type = TYPE_DECLARATION.match(strip_generics(line))

		if declared_type:
			for group in (declared_type.group(2), declared_type.group(3)):
				if not group:
					continue

				for name in group.split(","):
					supertypes.add(name.strip().split("<")[0].replace(".", "/"))

			continue

		descriptor = DESCRIPTOR.match(line)

		if descriptor and pending is not None:
			members.add(f"{pending}:{descriptor.group(1)}")
			pending = None
			continue

		if DECLARATION.match(line):
			pending = member_name(line, simple_name)

	return members, supertypes


def resolve(server: Path, cache: dict, owner: str, member: str, seen: set[str] | None = None) -> bool | None:
	"""True when the member exists, False when its class does but the member does
	not, None when the class itself is gone."""
	seen = set() if seen is None else seen

	if owner in seen:
		return False

	seen.add(owner)

	if owner not in cache:
		cache[owner] = inspect(server, owner)

	entry = cache[owner]

	if entry is None:
		return None

	members, supertypes = entry

	if member in members:
		return True

	for parent in supertypes:
		if parent.startswith("net/minecraft") and resolve(server, cache, parent, member, seen):
			return True

	return False


def mod_references(jar: Path) -> list[str]:
	"""Every net.minecraft member the mod's own classes name."""
	with zipfile.ZipFile(jar) as archive:
		classes = [
			name[:-len(".class")].replace("/", ".")
			for name in archive.namelist()
			if name.startswith("dev/belikhun/luna/core/fabric/") and name.endswith(".class")
		]

	if not classes:
		raise SystemExit(f"no mod classes found in {jar}")

	return sorted(set(REFERENCE.findall(javap(jar, classes, verbose=True))))


def main() -> int:
	jar = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_JAR

	if not jar.exists():
		raise SystemExit(f"jar not found: {jar} (run `gradlew :luna-core-mc26-fabric:shadowJar` first)")

	references = mod_references(jar)
	print(f"{jar.name}: {len(references)} minecraft references\n")

	failed = False

	for version in VERSIONS:
		server = server_jar(version)
		cache: dict = {}
		missing: list[str] = []

		for reference in references:
			owner, rest = reference.rsplit(".", 1)
			# a constant-pool name is quoted when it is not a plain identifier
			member = rest.replace('"', "")
			verdict = resolve(server, cache, owner, member)

			if verdict is None:
				missing.append(f"{reference}  (class absent)")
			elif not verdict:
				missing.append(reference)

		print(f"  {version:<9} {'ok' if not missing else f'MISSING {len(missing)}'}")

		for reference in missing:
			print(f"      {reference}")
			failed = True

	return 1 if failed else 0


if __name__ == "__main__":
	sys.exit(main())
