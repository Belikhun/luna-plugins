#!/usr/bin/env python3
"""Add the luna-smp Stardew Valley crops to the survival shop's agricultural category.

Same offline writer as shop-delight.py: a Nova item is synthesised as a shulker shell carrying the
nova id, the key is the sha256 tail of the serialised bytes, and the encoder proves itself against
an entry the server wrote before anything is touched. Every id is checked against the lang keys of
the luna-smp jar that will actually be deployed, so a typo cannot register an item nobody can buy.

Seventy-two entries: the harvested crop and its seed packet for each of the thirty-six crops, both
on the category's standing scale (1.0 buy, 0.5 sell), which is what every Farmer's Delight crop and
seed already trades at. The crops carry Stardew's own prices in the catalog and they are
deliberately NOT used here: the category is uncapped, so a sweet gem berry priced anywhere near its
3,000g would be a money printer rather than a crop.

Nothing else of the roster is sold. The withered plant is not a crop, and the shop trades raw
produce and its storage forms, never processed food.
"""

import base64
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import time
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shoplib

PATH = '/mnt/shulker/mrds/survival/plugins/LunaShop/items.yml'
JAR = os.path.expanduser('~/luna-plugins/output/paper/luna-smp-paper.jar')
DATA_VERSION = 4671

# the standing scale for a crop and its seed in this category
PRODUCE = ('1.0', '0.5')

def tag_string(name, value):
	raw = value.encode('utf-8')
	return b'\x08' + struct.pack('>H', len(name)) + name.encode() + struct.pack('>H', len(raw)) + raw

def tag_int(name, value):
	return b'\x03' + struct.pack('>H', len(name)) + name.encode() + struct.pack('>i', value)

def tag_compound(name, body):
	return b'\x0a' + struct.pack('>H', len(name)) + name.encode() + body + b'\x00'

def item_nbt(material, nova_id, data_version):
	body = tag_int('DataVersion', data_version) + tag_string('id', material) + tag_int('count', 1)
	if nova_id is not None:
		body += tag_compound('components', tag_compound('minecraft:custom_data', tag_compound('nova', tag_string('id', nova_id))))
	return tag_compound('', body)

def java_gzip(plain):
	compressor = zlib.compressobj(6, zlib.DEFLATED, -15)
	deflated = compressor.compress(plain) + compressor.flush()
	return b'\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\xff' + deflated + struct.pack('<II', zlib.crc32(plain) & 0xffffffff, len(plain))

def item_data(material, nova_id=None, data_version=DATA_VERSION):
	serialized = java_gzip(item_nbt(material, nova_id, data_version))
	return hashlib.sha256(serialized).hexdigest()[-7:], base64.b64encode(serialized).decode('ascii')

def split_file(text):
	lines = text.split('\n')
	head = lines.index('shop-items:')
	entries, current = [], None
	for line in lines[head + 1:]:
		if line == '':
			continue
		match = re.match(r"^  (?:'([^']+)'|([^\s:]+)):\s*$", line)
		if match:
			current = [match.group(1) or match.group(2), [line]]
			entries.append(current)
			continue
		current[1].append(line)
	return lines[:head + 1], entries

def field(block, name):
	for line in block:
		match = re.match(r'^    ' + re.escape(name) + r': (.*)$', line)
		if match:
			return match.group(1)
	return None

def quoted(key):
	return "'%s'" % key if re.fullmatch(r'[0-9]+', key) else key

def make_entry(key, buy, sell, added, data):
	return [
		'  %s:' % quoted(key), '    id: %s' % quoted(key), '    category: agricultural',
		'    buy-price: %s' % buy, '    sell-price: %s' % sell, '    buy-trade-limit: 0', '    sell-trade-limit: 0',
		'    added-date: %d' % added, '    item-id: minecraft:shulker_shell', '    item-name: minecraft:shulker_shell',
		'    item-lore: []', '    item-data: %s' % data,
	]

def crop_ids():
	"""Every crop's produce and seed id, read off the jar's own language file."""
	lang = json.loads(subprocess.check_output(['unzip', '-p', JAR, 'assets/lang/en_us.json'], text=True))
	blocks = {k.split('.')[-1] for k in lang if k.startswith('block.lunasmp.')}
	items = {k.split('.')[-1] for k in lang if k.startswith('item.lunasmp.')}

	# a crop is whatever registered a `<id>_crop` block; the produce and the packet follow from it
	ids = []
	for block in sorted(blocks):
		if not block.endswith('_crop') or block == 'dead_crop':
			continue

		crop = block[:-len('_crop')]
		seed = crop + '_seeds'

		if crop not in items or seed not in items:
			raise SystemExit('%s has no produce or seed item' % block)

		ids += [crop, seed]

	return ids

def main():
	if not os.path.exists(JAR):
		raise SystemExit('build luna-smp first: no ' + JAR)

	names = crop_ids()
	print('%d ids read from %s' % (len(names), os.path.basename(JAR)))

	text = open(PATH, encoding='utf-8').read()
	header, entries = split_file(text)
	by_key = dict(entries)

	key, data = item_data('minecraft:shulker_shell', 'farmersdelight:rice_panicle', 4556)
	if key != '08b53b2' or data != field(by_key['08b53b2'], 'item-data'):
		raise SystemExit("encoder does not reproduce the server's own bytes")
	print('encoder round-trip ok')

	# exact ids, never a substring of the description: `lunasmp:corn` occurs inside
	# `lunasmp:cornflower_bush`, and matching loosely silently dropped the corn
	existing = set()
	for _, block in entries:
		described = str(shoplib.describe(shoplib.decode(field(block, 'item-data'))))
		existing.update(re.findall(r"'id': '([^']+)'", described))
	added = int(time.time() * 1000)
	count = 0

	for name in names:
		nova_id = 'lunasmp:' + name
		if nova_id in existing:
			print('skip  %-32s already sold' % nova_id)
			continue

		key, data = item_data('minecraft:shulker_shell', nova_id)
		if key in by_key:
			raise SystemExit('id collision ' + key)

		block = make_entry(key, PRODUCE[0], PRODUCE[1], added, data)
		entries.append([key, block])
		by_key[key] = block
		count += 1
		print('add   %-32s %s  buy %-4s sell %s' % (nova_id, key, PRODUCE[0], PRODUCE[1]))

	entries.sort(key=lambda p: p[0])
	out = list(header)
	for _, block in entries:
		out.extend(block)

	open(PATH, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
	print('added %d · %d entries total' % (count, len(entries)))

main()
