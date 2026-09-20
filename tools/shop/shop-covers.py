#!/usr/bin/env python3
"""Add the four wire covers to the survival shop.

Same shape as shop-power.py: a text-level merge where untouched entries keep
their exact bytes, new entries are synthesised the way the server writes
them, and the encoder proves itself against an entry the server wrote before
anything is changed. A cover is network hardware like the cable it replaces,
so it goes into `industrial` beside the poles and the spools.

Pass --write to save; without it the merge is printed and nothing is touched.
"""

import base64
import os
import re
import shutil
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

PATH = '/mnt/shulker/mrds/survival/plugins/LunaShop/items.yml'
BACKUP = PATH + '.pre-covers'
DATA_VERSION = 4671
# every smp entry shares one added-date, so a category page reads alphabetically
ADDED = 1788428103122


def tag_string(name, value):
	raw = value.encode('utf-8')
	return b'\x08' + struct.pack('>H', len(name)) + name.encode('utf-8') + struct.pack('>H', len(raw)) + raw


def tag_int(name, value):
	return b'\x03' + struct.pack('>H', len(name)) + name.encode('utf-8') + struct.pack('>i', value)


def tag_compound(name, body):
	return b'\x0a' + struct.pack('>H', len(name)) + name.encode('utf-8') + body + b'\x00'


def item_nbt(material, nova_id, data_version):
	body = tag_int('DataVersion', data_version) + tag_string('id', material) + tag_int('count', 1)
	body += tag_compound('components', tag_compound('minecraft:custom_data', tag_compound('nova', tag_string('id', nova_id))))
	return tag_compound('', body)


def java_gzip(plain):
	compressor = zlib.compressobj(6, zlib.DEFLATED, -15)
	deflated = compressor.compress(plain) + compressor.flush()
	header = b'\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\xff'
	trailer = struct.pack('<II', zlib.crc32(plain) & 0xffffffff, len(plain))
	return header + deflated + trailer


def item_data(nova_id):
	return base64.b64encode(java_gzip(item_nbt('minecraft:shulker_shell', nova_id, DATA_VERSION))).decode('ascii')


# ---- the wave -------------------------------------------------------------
#
# A cover is priced a step over the spool of its tier: it is the cable plus
# the block around it, and the ladder stays as steep as the spools' so that
# burying an ultimate line costs what an ultimate line costs.

ITEMS = [
	('cover', 'industrial', 5.0, 'wire_cover_basic'),
	('cover', 'industrial', 12.0, 'wire_cover_advanced'),
	('cover', 'industrial', 30.0, 'wire_cover_elite'),
	('cover', 'industrial', 70.0, 'wire_cover_ultimate'),
]


def split_file(text):
	"""The categories block, the shop-items header line, and the entries."""
	lines = text.split('\n')
	head = lines.index('shop-items:')
	categories = []
	current = None

	for line in lines[1:head]:
		if line == '':
			continue

		match = re.match(r"^  (?:'([^']+)'|([^\s:]+)):\s*$", line)
		if match:
			current = [match.group(1) or match.group(2), [line]]
			categories.append(current)
			continue

		if current is None:
			raise SystemExit('unexpected line in the categories block: ' + line[:60])

		current[1].append(line)

	entries = []
	current = None

	for line in lines[head + 1:]:
		if line == '':
			continue

		match = re.match(r"^  (?:'([^']+)'|([^\s:]+)):\s*$", line)
		if match:
			current = [match.group(1) or match.group(2), [line]]
			entries.append(current)
			continue

		if current is None:
			raise SystemExit('unexpected line outside an entry: ' + line[:60])

		current[1].append(line)

	return categories, entries


def field(block, name):
	for line in block:
		match = re.match(r'^    ' + re.escape(name) + r': (.*)$', line)
		if match:
			return match.group(1)

	return None


def make_entry(key, category, buy, sell, data):
	return [
		'  %s:' % key,
		'    id: %s' % key,
		'    category: %s' % category,
		'    buy-price: %s' % buy,
		'    sell-price: %s' % sell,
		'    buy-trade-limit: 0',
		'    sell-trade-limit: 0',
		'    added-date: %d' % ADDED,
		'    item-id: minecraft:shulker_shell',
		'    item-name: minecraft:shulker_shell',
		'    item-lore: []',
		'    item-data: %s' % data,
	]


def price(value):
	return '%.1f' % value


def main():
	write = '--write' in sys.argv
	text = open(PATH, encoding='utf-8').read()
	categories, entries = split_file(text)
	by_key = dict((key, block) for key, block in entries)
	known = set(key for key, _ in categories)

	# the encoder has to reproduce bytes the server itself wrote before it is
	# allowed to write any of its own
	probe = by_key['smp-steel-steel-mesh-fence']
	if item_data('lunasmp:steel_mesh_fence') != field(probe, 'item-data'):
		raise SystemExit('encoder does not reproduce the server\'s own bytes')

	print('encoder round-trip ok')

	seen = set()
	for _, _, _, nova in ITEMS:
		if nova in seen:
			raise SystemExit('listed twice: ' + nova)
		seen.add(nova)

	added = 0

	for family, category, buy, nova in ITEMS:
		if category not in known:
			raise SystemExit('unknown category %s for %s' % (category, nova))

		key = 'smp-%s-%s' % (family, nova.replace('_', '-'))

		if key in by_key:
			print('skip  %-40s already present' % key)
			continue

		sell = max(0.1, round(buy / 10, 1))
		block = make_entry(key, category, price(buy), price(sell), item_data('lunasmp:' + nova))
		entries.append([key, block])
		by_key[key] = block
		added += 1
		print('add   %-40s %s -> %s' % (key, category, price(buy)))

	categories.sort(key=lambda pair: pair[0])
	entries.sort(key=lambda pair: pair[0])

	out = ['categories:']
	for _, block in categories:
		out.extend(block)

	out.append('shop-items:')
	for _, block in entries:
		out.extend(block)

	print('\n%d new entries, %d total, %d categories' % (added, len(entries), len(categories)))

	if not write:
		print('dry run: pass --write to save')
		return

	shutil.copyfile(PATH, BACKUP)
	open(PATH, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
	print('wrote %s (backup %s)' % (PATH, BACKUP))


main()
