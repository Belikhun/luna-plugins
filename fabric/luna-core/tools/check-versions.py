#!/usr/bin/env python3
"""
Check that every Minecraft member luna-core-fabric links against still exists on
each game version the mod claims to support.

One build serves a whole range of game versions, and the compiler only ever sees
one of them, so the usual way a mod like this breaks is a method that was renamed
three versions later: it compiles, it ships, and it dies with a NoSuchMethodError
the first time that code path runs. Fabric's intermediary mappings are the record
of what a member is called on each version, so comparing the jar's references
against them turns that runtime surprise into a build-time answer.

Usage:  python3 tools/check-versions.py [path/to/mod.jar]

Needs javap on PATH and network access to maven.fabricmc.net. Exits non-zero when
a reference is missing on any checked version.
"""

import re
import subprocess
import sys
import urllib.request
import zipfile
from io import BytesIO
from pathlib import Path

# Every version the mod declares support for, oldest first. The 26.x line is
# deliberately absent: Mojang stopped obfuscating there, so fabric publishes an
# empty intermediary and mods are expected to link real names instead. An
# intermediary-mapped jar cannot serve both, which is why fabric.mod.json stops
# below 2.0.
VERSIONS = [
	"1.20.1", "1.20.2", "1.20.4", "1.20.6",
	"1.21", "1.21.1", "1.21.3", "1.21.4", "1.21.5",
	"1.21.6", "1.21.8", "1.21.9", "1.21.11",
]

INTERMEDIARY_URL = "https://maven.fabricmc.net/net/fabricmc/intermediary/{v}/intermediary-{v}-v2.jar"

DEFAULT_JAR = Path(__file__).resolve().parents[3] / "output" / "fabric" / "luna-core-fabric-all.jar"

REFERENCE = re.compile(
	r"net/minecraft/[A-Za-z0-9_/$]+\.[A-Za-z0-9_\"<>]+:(?:\([^)]*\)[^\s]*|L?[A-Za-z0-9_/$;\[]+)"
)


def mod_references(jar: Path) -> set[str]:
	"""Every net.minecraft member the mod's own classes name."""
	with zipfile.ZipFile(jar) as archive:
		classes = [
			name[:-len(".class")].replace("/", ".")
			for name in archive.namelist()
			if name.startswith("dev/belikhun/luna/core/fabric/") and name.endswith(".class")
		]

	if not classes:
		raise SystemExit(f"no mod classes found in {jar}")

	dump = subprocess.run(
		["javap", "-p", "-v", "-classpath", str(jar), *classes],
		capture_output=True, text=True, check=True,
	).stdout

	return set(REFERENCE.findall(dump))


def declared_members(version: str) -> set[str]:
	"""
	Every member intermediary names on this version, as `class.name:descriptor`.

	Tiny files write descriptors in the official namespace, so each one is
	rewritten into intermediary before comparing. Inheritance is not recorded, so
	membership is checked across all classes rather than per owner: that still
	catches a rename, a removal or a changed signature, which is what breaks a
	multi-version build.
	"""
	with urllib.request.urlopen(INTERMEDIARY_URL.format(v=version)) as response:
		payload = response.read()

	with zipfile.ZipFile(BytesIO(payload)) as archive:
		tiny = archive.read("mappings/mappings.tiny").decode("utf-8")

	class_names: dict[str, str] = {}
	raw_members: list[tuple[str, str]] = []

	for line in tiny.splitlines():
		parts = line.split("\t")

		if parts[0] == "c" and len(parts) >= 3:
			class_names[parts[1]] = parts[2]
		elif parts and parts[0] == "" and len(parts) >= 5 and parts[1] in ("m", "f"):
			raw_members.append((parts[2], parts[4]))

	def remap(descriptor: str) -> str:
		return re.sub(
			r"L([A-Za-z0-9_/$]+);",
			lambda match: "L" + class_names.get(match.group(1), match.group(1)) + ";",
			descriptor,
		)

	return {f"{name}:{remap(descriptor)}" for descriptor, name in raw_members}


def main() -> int:
	jar = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_JAR

	if not jar.exists():
		raise SystemExit(f"jar not found: {jar} (run `gradlew :luna-core-fabric:remapJar` first)")

	references = sorted(mod_references(jar))
	print(f"{jar.name}: {len(references)} minecraft references\n")

	# a reference whose owner or name was never obfuscated has no mapping entry to
	# find, so it is reported once rather than counted against every version
	unmapped: set[str] = set()
	failed = False

	for version in VERSIONS:
		declared = declared_members(version)
		missing = []

		for reference in references:
			owner, member = reference.rsplit(".", 1)
			name = member.split(":", 1)[0]
			descriptor = member.split(":", 1)[1]

			if not re.match(r"(method|field)_\d+$", name):
				unmapped.add(reference)
				continue

			if f"{name}:{descriptor}" not in declared:
				missing.append(f"{owner}.{member}")

		status = "ok" if not missing else f"MISSING {len(missing)}"
		print(f"  {version:<9} {status}")

		for reference in missing:
			print(f"      {reference}")
			failed = True

	if unmapped:
		print(f"\n  not obfuscated on any version, so unchecked ({len(unmapped)}):")
		for reference in sorted(unmapped):
			print(f"      {reference}")

	return 1 if failed else 0


if __name__ == "__main__":
	sys.exit(main())
