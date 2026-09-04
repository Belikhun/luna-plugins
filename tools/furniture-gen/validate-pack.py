#!/usr/bin/env python3
"""Checks a built Nova resource pack for models and textures it names but does
not contain.

    ./validate-pack.py /mnt/shulker/mrds/packs/smp.zip [--mirror <.mcassets dir>]

validate.ts checks the addon's own sources, which is everything the generator
writes. It cannot see the other half: Nova generates a model per item at pack
build time from the `modelDefinition` blocks in the Kotlin, and a reference
that resolves to nothing there is invisible until somebody holds the item and
sees the purple and black cube.

That is exactly what happened to the flora: `fallback = buildModel {
defaultModel }` reads, on an item scope, as the item-sprite convention
`lunasmp:item/<id>`, and a block item has no texture at that path - so 62
flowers drew the missing texture in hand and on the head while looking correct
in every menu. This walks each item definition, follows every model it selects,
and checks that each model file and each texture it names is really in the zip.

Namespaces other than the addon's are the client's own and are assumed present,
unless --mirror points at a copy of the vanilla assets (Nova keeps one beside
the pack it builds at plugins/Nova/resource_pack/.mcassets), in which case they
are checked too. Exits non-zero when anything is missing.
"""

import argparse
import json
import os
import re
import sys
import zipfile


def split_ref(ref):
	if ':' in ref:
		namespace, path = ref.split(':', 1)
		return namespace, path

	return 'minecraft', ref


def model_references(node, out):
	"""Every model a definition selects, however deeply it nests."""
	if isinstance(node, dict):
		if node.get('type') == 'minecraft:model' and isinstance(node.get('model'), str):
			out.append(node['model'])

		for value in node.values():
			model_references(value, out)
	elif isinstance(node, list):
		for value in node:
			model_references(value, out)


def main():
	parser = argparse.ArgumentParser()
	parser.add_argument('pack')
	parser.add_argument('--mirror', help='vanilla assets to check minecraft: references against')
	args = parser.parse_args()

	pack = zipfile.ZipFile(args.pack)
	names = set(pack.namelist())
	faults = []
	checked = 0

	def has(namespace, path, kind, extension):
		entry = 'assets/%s/%s/%s%s' % (namespace, kind, path, extension)

		if entry in names:
			return True

		if namespace == 'minecraft' and args.mirror:
			return os.path.exists(os.path.join(args.mirror, 'assets/minecraft', kind, path + extension))

		# another pack in the stack may own it, and the client owns its own
		return namespace != 'lunasmp' and namespace != 'nova'

	# a model may parent onto another; the chain is followed to its end
	def check_model(item, ref, seen):
		if ref in seen:
			return

		seen.add(ref)
		namespace, path = split_ref(ref)
		entry = 'assets/%s/models/%s.json' % (namespace, path)

		if entry not in names:
			if not has(namespace, path, 'models', '.json'):
				faults.append((item, 'model %s is not in the pack' % ref))

			return

		model = json.loads(pack.read(entry))

		for slot, texture in (model.get('textures') or {}).items():
			if not isinstance(texture, str) or texture.startswith('#'):
				continue

			texture_ns, texture_path = split_ref(texture)

			if not has(texture_ns, texture_path, 'textures', '.png'):
				faults.append((item, '%s %s -> %s is not in the pack' % (ref, slot, texture)))

		parent = model.get('parent')

		if isinstance(parent, str):
			check_model(item, parent, seen)

	for name in sorted(names):
		match = re.fullmatch(r'assets/([^/]+)/items/(.+)\.json', name)

		if not match or match.group(1) not in ('lunasmp', 'nova'):
			continue

		checked += 1
		item = '%s:%s' % (match.group(1), match.group(2))
		refs = []
		model_references(json.loads(pack.read(name)), refs)

		if not refs:
			faults.append((item, 'the definition selects no model at all'))

		for ref in refs:
			check_model(item, ref, set())

	print('item definitions checked: %d' % checked)

	if not faults:
		print('no faults')
		return 0

	by_item = {}
	for item, why in faults:
		by_item.setdefault(item, []).append(why)

	print('FAULTS: %d references across %d items' % (len(faults), len(by_item)))

	for item in sorted(by_item):
		for why in sorted(set(by_item[item])):
			print('  %s: %s' % (item, why))

	return 1


sys.exit(main())
