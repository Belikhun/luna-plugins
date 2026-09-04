#!/usr/bin/env python3
"""Add the Corn Delight and Rustic Delight produce to the survival shop's agricultural category.

Same offline writer as shop-crates.py: Nova items are synthesised as shulker shells carrying the
nova id, ids are the sha256 tail of the serialised bytes, and the encoder proves itself against an
entry the server wrote before touching anything. Every nova id is checked against the lang keys of
the FarmersDelight jar actually deployed, so a typo cannot register an item that does not exist.
"""

import base64
import gzip
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
JAR = subprocess.check_output('ls /mnt/shulker/mrds/survival/plugins/FarmersDelight-*.jar', shell=True, text=True).strip()
DATA_VERSION = 4671

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

COLOURS = ['red', 'orange', 'yellow', 'green', 'blue', 'purple', 'pink', 'white', 'black']
PRODUCE, STORAGE = ('1.0', '0.5'), ('9.0', '4.5')
# a giant pepper is crafted from nine slices, and a slice is half a pepper
GIANT = ('4.5', '2.25')

NEW = [('corn', PRODUCE), ('corn_seeds', PRODUCE), ('wild_corn', PRODUCE), ('corn_crate', STORAGE), ('corn_kernel_bag', STORAGE),
	('bell_pepper_seeds', PRODUCE), ('wild_bell_peppers', PRODUCE), ('bell_pepper_seeds_bag', STORAGE),
	('coffee_beans', PRODUCE), ('wild_coffee', PRODUCE), ('coffee_beans_bag', STORAGE),
	('cotton_seeds', PRODUCE), ('cotton_boll', PRODUCE), ('wild_cotton', PRODUCE), ('cotton_seeds_bag', STORAGE), ('cotton_boll_crate', STORAGE)]
for c in COLOURS:
	NEW += [('bell_pepper_%s' % c, PRODUCE), ('bell_pepper_%s_crate' % c, STORAGE), ('bell_pepper_%s_block' % c, GIANT)]

def main():
	lang = json.loads(subprocess.check_output(['unzip', '-p', JAR, 'assets/lang/en_us.json'], text=True))
	known = {k.split('.')[-1] for k in lang if k.startswith(('item.farmersdelight.', 'block.farmersdelight.'))}
	unknown = [n for n, _ in NEW if n not in known]
	if unknown:
		raise SystemExit('not in the deployed FarmersDelight: %r' % unknown)

	text = open(PATH, encoding='utf-8').read()
	header, entries = split_file(text)
	by_key = dict(entries)

	key, data = item_data('minecraft:shulker_shell', 'farmersdelight:rice_panicle', 4556)
	if key != '08b53b2' or data != field(by_key['08b53b2'], 'item-data'):
		raise SystemExit("encoder does not reproduce the server's own bytes")
	print('encoder round-trip ok · %d ids verified against %s' % (len(NEW), os.path.basename(JAR)))

	existing = {shoplib.describe(shoplib.decode(field(b, 'item-data'))) for _, b in entries}
	added = int(time.time() * 1000)
	count = 0
	for name, (buy, sell) in NEW:
		nova_id = 'farmersdelight:' + name
		if any(nova_id in e for e in existing):
			print('skip  %-28s already sold' % nova_id)
			continue
		key, data = item_data('minecraft:shulker_shell', nova_id)
		if key in by_key:
			raise SystemExit('id collision ' + key)
		block = make_entry(key, buy, sell, added, data)
		entries.append([key, block])
		by_key[key] = block
		count += 1
		print('add   %-28s %s  buy %-4s sell %s' % (nova_id, key, buy, sell))

	entries.sort(key=lambda p: p[0])
	out = list(header)
	for _, block in entries:
		out.extend(block)
	open(PATH, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
	print('added %d · %d entries total' % (count, len(entries)))

main()
