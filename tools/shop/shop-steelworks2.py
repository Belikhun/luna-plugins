#!/usr/bin/env python3
"""Add the second steelworks wave (doors, hatch, gate, catwalk, window, panzer glass,
double-T support, pole caps, edge lights) to the survival shop.

Text-level merge in the shape of shop-crates.py: untouched entries keep their exact
bytes, new entries are synthesised the way the server writes them, proved first by
round-tripping an entry the server wrote.
"""

import base64
import hashlib
import re
import struct
import sys
import os
import shutil
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shoplib

PATH = '/mnt/shulker/mrds/survival/plugins/LunaShop/items.yml'
DATA_VERSION = 4671
# every smp entry shares one added-date so a category page reads alphabetically
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


# (family, nova id, category, buy) - sell is a tenth, floored at 0.1
NEW_ITEMS = [
	('door', 'metal_sliding_door', 'doors', 6.0),
	('door', 'metal_sliding_door_top', 'doors', 3.0),
	('door', 'industrial_wood_door', 'doors', 6.0),
	('door', 'industrial_wood_door_top', 'doors', 3.0),
	('door', 'iron_hatch', 'doors', 4.0),
	('steel', 'steel_mesh_gate', 'building', 4.0),
	('steel', 'steel_catwalk', 'building', 1.0),
	('steel', 'steel_framed_window', 'building', 2.0),
	('steel', 'panzerglass', 'building', 1.0),
	('steel', 'steel_double_t_support', 'building', 1.0),
	('steel', 'treated_wood_pole_head', 'building', 1.0),
	('steel', 'treated_wood_pole_support', 'building', 1.0),
	('steel', 'thin_steel_pole_head', 'building', 1.0),
	('steel', 'thick_steel_pole_head', 'building', 1.0),
	('light', 'ceiling_edge_light', 'lighting', 4.0),
	('light', 'floor_edge_light', 'lighting', 3.0),
]


def price(value):
	return ('%.1f' % value)


def main():
	text = open(PATH, encoding='utf-8').read()
	header, entries = split_file(text)
	by_key = dict((key, block) for key, block in entries)

	probe = by_key['smp-steel-steel-mesh-fence']
	if item_data('lunasmp:steel_mesh_fence') != field(probe, 'item-data'):
		raise SystemExit('encoder does not reproduce the server\'s own bytes')

	print('encoder round-trip ok')

	for family, nova, category, buy in NEW_ITEMS:
		key = 'smp-%s-%s' % (family, nova.replace('_', '-'))

		if key in by_key:
			print('skip  %-32s already present' % key)
			continue

		sell = max(0.1, round(buy / 10, 1))
		block = make_entry(key, category, price(buy), price(sell), item_data('lunasmp:' + nova))
		entries.append([key, block])
		by_key[key] = block
		print('add   %-32s %-9s buy %-4s sell %s' % (key, category, price(buy), price(sell)))

	entries.sort(key=lambda pair: pair[0])

	out = list(header)
	for _, block in entries:
		out.extend(block)

	shutil.copyfile(PATH, PATH + '.pre-steelworks2')
	open(PATH, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
	print('wrote %d entries' % len(entries))


main()
