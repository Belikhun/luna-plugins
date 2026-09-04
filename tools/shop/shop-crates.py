#!/usr/bin/env python3
"""Add the compressed-crop blocks (hay block + Farmer's Delight crates and bales) to the
survival shop, and raise the per-Minecraft-day sell limits on the raw farm produce.

Text-level merge: every untouched entry keeps its exact bytes. New entries are
synthesised the way the server itself would write them (gzipped NBT -> base64, sha256
id), which is proved by round-tripping an entry the server wrote before anything else
happens.
"""

import base64
import gzip
import hashlib
import re
import struct
import sys
import os
import time
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shoplib

PATH = '/mnt/shulker/mrds/survival/plugins/LunaShop/items.yml'
DATA_VERSION = 4671

# --- item-data synthesis -------------------------------------------------------

def tag_string(name, value):
	raw = value.encode('utf-8')
	return b'\x08' + struct.pack('>H', len(name)) + name.encode('utf-8') + struct.pack('>H', len(raw)) + raw

def tag_int(name, value):
	return b'\x03' + struct.pack('>H', len(name)) + name.encode('utf-8') + struct.pack('>i', value)

def tag_compound(name, body):
	return b'\x0a' + struct.pack('>H', len(name)) + name.encode('utf-8') + body + b'\x00'

def item_nbt(material, nova_id, data_version):
	body = tag_int('DataVersion', data_version) + tag_string('id', material) + tag_int('count', 1)

	if nova_id is not None:
		body += tag_compound('components', tag_compound('minecraft:custom_data', tag_compound('nova', tag_string('id', nova_id))))

	return tag_compound('', body)

def java_gzip(plain):
	"""Byte-for-byte what java.util.zip.GZIPOutputStream at the default level writes."""
	compressor = zlib.compressobj(6, zlib.DEFLATED, -15)
	deflated = compressor.compress(plain) + compressor.flush()
	header = b'\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\xff'
	trailer = struct.pack('<II', zlib.crc32(plain) & 0xffffffff, len(plain))
	return header + deflated + trailer

def item_data(material, nova_id=None, data_version=DATA_VERSION):
	serialized = java_gzip(item_nbt(material, nova_id, data_version))
	return hashlib.sha256(serialized).hexdigest()[-7:], base64.b64encode(serialized).decode('ascii')

# --- file model ----------------------------------------------------------------

def split_file(text):
	lines = text.split('\n')
	head = lines.index('shop-items:')
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

	return lines[:head + 1], entries

def field(block, name):
	for line in block:
		match = re.match(r'^    ' + re.escape(name) + r': (.*)$', line)
		if match:
			return match.group(1)

	return None

def set_field(block, name, value):
	for index, line in enumerate(block):
		if re.match(r'^    ' + re.escape(name) + r': ', line):
			block[index] = '    %s: %s' % (name, value)
			return True

	return False

def quoted(key):
	return "'%s'" % key if re.fullmatch(r'[0-9]+', key) else key

def make_entry(key, category, buy, sell, sell_limit, added, material, data):
	return [
		'  %s:' % quoted(key),
		'    id: %s' % quoted(key),
		'    category: %s' % category,
		'    buy-price: %s' % buy,
		'    sell-price: %s' % sell,
		'    buy-trade-limit: 0',
		'    sell-trade-limit: %d' % sell_limit,
		'    added-date: %d' % added,
		'    item-id: %s' % material,
		'    item-name: %s' % material,
		'    item-lore: []',
		'    item-data: %s' % data,
	]

# --- what changes --------------------------------------------------------------

# A crate is nine of its crop, so it is priced at nine times that crop and carries a
# ninth of the crop's daily sell limit; otherwise compressing a harvest would walk
# straight past the cap.
NEW_ITEMS = [
	('minecraft:hay_block',     None,                            '4.5', '0.9', 384),
	('minecraft:shulker_shell', 'farmersdelight:carrot_crate',   '4.5', '0.9', 384),
	('minecraft:shulker_shell', 'farmersdelight:potato_crate',   '4.5', '0.9', 384),
	('minecraft:shulker_shell', 'farmersdelight:beetroot_crate', '4.5', '0.9', 384),
	('minecraft:shulker_shell', 'farmersdelight:cabbage_crate',  '9.0', '4.5', 0),
	('minecraft:shulker_shell', 'farmersdelight:tomato_crate',   '9.0', '4.5', 0),
	('minecraft:shulker_shell', 'farmersdelight:onion_crate',    '9.0', '4.5', 0),
	('minecraft:shulker_shell', 'farmersdelight:rice_bale',      '9.0', '4.5', 0),
	('minecraft:shulker_shell', 'farmersdelight:rice_bag',       '9.0', '4.5', 0),
	('minecraft:shulker_shell', 'farmersdelight:straw_bale',     '4.5', '0.9', 0),
	('minecraft:beetroot',      None,                            '0.5', '0.1', 3456),
]

# Raw produce: a Minecraft day is twenty real minutes, and nine stacks of wheat was
# less than one pass of a modest farm. A full double chest is the new ceiling.
NEW_LIMITS = {
	'minecraft:wheat': 3456,
	'minecraft:carrot': 3456,
	'minecraft:potato': 3456,
	'minecraft:apple': 576,
}

def main():
	text = open(PATH, encoding='utf-8').read()
	header, entries = split_file(text)
	by_key = dict((key, block) for key, block in entries)

	# The encoder must reproduce bytes the server wrote, or the ids would be wrong.
	for probe, material, nova_id, version in [
		('4b05022', 'minecraft:wheat', None, 4556),
		('08b53b2', 'minecraft:shulker_shell', 'farmersdelight:rice_panicle', 4556),
	]:
		key, data = item_data(material, nova_id, version)
		if key != probe or data != field(by_key[probe], 'item-data'):
			raise SystemExit('encoder does not reproduce the server\'s own bytes for ' + probe)

	print('encoder round-trip ok')

	added = int(time.time() * 1000)
	touched = []

	for key, block in entries:
		decoded = shoplib.decode(field(block, 'item-data'))
		if (decoded.get('components') or {}).get('minecraft:custom_data'):
			continue

		name = decoded.get('id')
		if name not in NEW_LIMITS:
			continue

		before = field(block, 'sell-trade-limit')
		set_field(block, 'sell-trade-limit', str(NEW_LIMITS[name]))
		touched.append((name, before, NEW_LIMITS[name]))

	for name, before, after in sorted(touched):
		print('limit %-18s %5s -> %d' % (name, before, after))

	if len(touched) != len(NEW_LIMITS):
		raise SystemExit('expected %d limit updates, made %d' % (len(NEW_LIMITS), len(touched)))

	for material, nova_id, buy, sell, limit in NEW_ITEMS:
		key, data = item_data(material, nova_id)
		if key in by_key:
			raise SystemExit('id collision for ' + (nova_id or material) + ': ' + key)

		block = make_entry(key, 'agricultural', buy, sell, limit, added, material, data)
		entries.append([key, block])
		by_key[key] = block
		print('add   %-30s %-8s buy %-4s sell %-4s limit %d' % (nova_id or material, key, buy, sell, limit))

	entries.sort(key=lambda pair: pair[0])

	out = list(header)
	for _, block in entries:
		out.extend(block)

	open(PATH, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
	print('wrote %d entries' % len(entries))

main()
