#!/usr/bin/env python3
"""Reorganise the instrument fleet in the survival shop: one category per
quantity, and the gauges the matrix was missing.

Same encoder and text-level merge as shop-power.py - untouched entries keep
their exact bytes, and the encoder proves itself against an entry the server
wrote before anything changes. What is new here: existing entries MOVE (only
their `category:` line changes), four categories are added, and the
`industrial` category loses the "Đồng Hồ" from its name and the gauge from its
icon now that no gauge lives there any more.

Order matters on the server: after --write, run `shopadmin reload` BEFORE any
restart. LunaShop rewrites items.yml from memory on shutdown, so a merge that
was never reloaded is thrown away by the next restart.

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
BACKUP = PATH + '.pre-gauge-categories'
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


# ---- the categories -----------------------------------------------------------
#
# One per quantity the fleet measures. The icon is the plainest dial of each,
# the way `industrial` wore the multipurpose meter.

CATEGORIES = [
	('gauge_energy', 'gauge_energy_stored', '<yellow>Đồng Hồ Điện</yellow>'),
	('gauge_item', 'gauge_item_flow', '<gold>Đồng Hồ Vật Phẩm</gold>'),
	('gauge_fluid', 'gauge_fluid_stored', '<blue>Đồng Hồ Chất Lỏng</blue>'),
	('gauge_multi', 'gauge_multi', '<aqua>Đồng Hồ Đa Năng</aqua>'),
]

INDUSTRIAL_NAME = '<aqua>Công Nghiệp</aqua>'

# the category used to wear the multipurpose meter, which now has a tab of
# its own; the knife switch is the plainest thing left in it
INDUSTRIAL_ICON = 'network_lever'


def category_of(nova_id):
	"""Which of the four a gauge id belongs to, by the quantity in its name."""
	if 'multi' in nova_id or 'digital' in nova_id:
		return 'gauge_multi'

	if 'energy' in nova_id:
		return 'gauge_energy'

	if 'item' in nova_id:
		return 'gauge_item'

	if 'fluid' in nova_id:
		return 'gauge_fluid'

	raise SystemExit('cannot place gauge in a category: ' + nova_id)


# ---- the gauges the matrix was missing -------------------------------------------
#
# Priced as their round and corner siblings are: a panel at 8, its full-block
# dashboard at 10.

NEW_KINDS = [
	'gauge_item_stored',
	'gauge_energy_stored_square',
	'gauge_energy_flow_square',
	'gauge_item_stored_square',
	'gauge_item_flow_square',
	'gauge_fluid_stored_square',
	'gauge_fluid_flow_square',
	# the intake/output pairs, the consumption/production gauges' shape
	'gauge_item_in',
	'gauge_item_out',
	'gauge_fluid_in',
	'gauge_fluid_out',
	'gauge_item_in_corner',
	'gauge_item_out_corner',
	'gauge_fluid_in_corner',
	'gauge_fluid_out_corner',
	# the other two corner seats of every one-sided pair, and the totalisers
	'gauge_corner_energy_load_bl',
	'gauge_corner_energy_load_tr',
	'gauge_corner_energy_gen_br',
	'gauge_corner_energy_gen_tr',
	'gauge_corner_item_in_br',
	'gauge_corner_item_in_tr',
	'gauge_corner_item_out_bl',
	'gauge_corner_item_out_tr',
	'gauge_corner_fluid_in_br',
	'gauge_corner_fluid_in_tr',
	'gauge_corner_fluid_out_bl',
	'gauge_corner_fluid_out_tr',
	'gauge_item_meter',
	'gauge_fluid_meter',
]

ITEMS = []
for kind in NEW_KINDS:
	if kind.endswith('_meter'):
		# a totaliser is priced as the electrical meter is
		ITEMS.append((12.0, kind))
		ITEMS.append((14.0, kind + '_block'))
	else:
		ITEMS.append((8.0, kind))
		ITEMS.append((10.0, kind + '_block'))


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


def set_field(block, name, value):
	"""Rewrites one field of a block in place, keeping every other byte."""
	for index, line in enumerate(block):
		if re.match(r'^    ' + re.escape(name) + r': ', line):
			block[index] = '    %s: %s' % (name, value)
			return

	raise SystemExit('block has no field %s: %s' % (name, block[0]))


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


def make_category(key, icon, display):
	return [
		'  %s:' % key,
		'    id: %s' % key,
		'    icon-data: %s' % item_data('lunasmp:' + icon),
		'    display-name: %s' % display,
	]


def price(value):
	return '%.1f' % value


def main():
	write = '--write' in sys.argv
	text = open(PATH, encoding='utf-8').read()
	categories, entries = split_file(text)
	by_key = dict((key, block) for key, block in entries)
	cat_by_key = dict((key, block) for key, block in categories)

	# the encoder has to reproduce bytes the server itself wrote before it is
	# allowed to write any of its own
	probe = by_key['smp-steel-steel-mesh-fence']
	if item_data('lunasmp:steel_mesh_fence') != field(probe, 'item-data'):
		raise SystemExit('encoder does not reproduce the server\'s own bytes')

	print('encoder round-trip ok')

	# ---- categories
	for key, icon, display in CATEGORIES:
		if key in cat_by_key:
			print('skip  category %-16s already present' % key)
			continue

		block = make_category(key, icon, display)
		categories.append([key, block])
		cat_by_key[key] = block
		print('add   category %-16s %s' % (key, display))

	industrial = cat_by_key['industrial']
	if field(industrial, 'display-name') != INDUSTRIAL_NAME:
		set_field(industrial, 'display-name', INDUSTRIAL_NAME)
		print('name  category industrial -> %s' % INDUSTRIAL_NAME)

	if field(industrial, 'icon-data') != item_data('lunasmp:' + INDUSTRIAL_ICON):
		set_field(industrial, 'icon-data', item_data('lunasmp:' + INDUSTRIAL_ICON))
		print('icon  category industrial -> %s' % INDUSTRIAL_ICON)

	# ---- move every gauge into its quantity's category
	moved = 0
	for key, block in entries:
		if not key.startswith('smp-gauge-gauge-'):
			continue

		nova = key[len('smp-gauge-'):].replace('-', '_')
		target = category_of(nova)

		if field(block, 'category') != target:
			set_field(block, 'category', target)
			moved += 1

	print('moved %d gauge entries into their categories' % moved)

	# ---- the new gauges
	added = 0
	for buy, nova in ITEMS:
		key = 'smp-gauge-%s' % nova.replace('_', '-')

		if key in by_key:
			print('skip  %-44s already present' % key)
			continue

		sell = max(0.1, round(buy / 10, 1))
		category = category_of(nova)
		block = make_entry(key, category, price(buy), price(sell), item_data('lunasmp:' + nova))
		entries.append([key, block])
		by_key[key] = block
		added += 1
		print('add   %-44s %-13s %s' % (key, category, price(buy)))

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
	print('NOW run: luna send survival "shopadmin reload" - before any restart')


main()
