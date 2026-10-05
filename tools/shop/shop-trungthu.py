#!/usr/bin/env python3
"""Add the Tết Trung Thu set to the survival shop, in a category of its own.

Same shape as shop-medieval.py: a text-level merge where untouched entries
keep their exact bytes, new entries are synthesised the way the server writes
them, and the encoder proves itself against an entry the server wrote before
anything is changed. The ids are read out of the generated TrungThuTable.kt,
so the shop cannot list a lantern the addon does not register.

Run it only once the addon carrying these items is running on survival, and
follow it with `shopadmin reload` before any restart: LunaShop saves its
in-memory copy over the file on shutdown, and a save made by a build that
does not know the ids would deserialise them into nothing.

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
BACKUP = PATH + '.pre-trungthu'
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


# ---- the set ---------------------------------------------------------------
#
# Prices on the shop's standing scale, sell a tenth of buy. A mooncake is a
# meal and a gift, dearer than the flour and crop it is made of but still a
# few kills' worth; the lanterns sit with the lamps, the plainer paper ones
# lower and the revolving lantern (the one with moving parts) highest; the
# fruit trades like the other produce, a little above it since nothing here
# grows it.

TABLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../paper/luna-smp/src/main/kotlin/dev/belikhun/luna/smp/trungthu/TrungThuTable.kt')

CATEGORY = 'trungthu'
CATEGORY_NAME = '<gradient:#ffc94d:#e53935>Tết Trung Thu</gradient>'
CATEGORY_ICON = 'lantern_star_red'


def price_of(family, nova):
	if family == 'mooncake':
		if nova in ('mooncake_thap_cam', 'mooncake_ga_quay', 'mooncake_trung_chay'):
			return 5.0

		# the heart cakes are the gift ones
		if '_tim_' in nova:
			return 5.0

		if nova.startswith('banh_rau_cau'):
			return 3.0

		return 4.0

	if family == 'lantern':
		if nova.startswith('lantern_star_') or nova == 'lantern_accordion':
			return 5.0

		if nova.startswith('lantern_heart_'):
			return 6.0

		if nova == 'lantern_red':
			return 6.0

		if nova.startswith('lantern_hoian_'):
			return 8.0

		if nova == 'lantern_revolving':
			return 10.0

		return 7.0

	if family == 'mask':
		if nova == 'mask_rong':
			return 15.0

		return 12.0 if nova == 'mask_lan' else 6.0

	if family == 'toy':
		return 8.0

	return {
		'lion_drum': 15.0,
		'pomelo_dog': 4.0,
		'tra_nong': 2.0,
		'durian': 3.0,
		'coconut': 2.0,
		'pomelo': 2.0,
	}[nova]


def table_ids():
	"""Every id the addon registers, by family, in the table's own order."""
	text = open(TABLE, encoding='utf-8').read()
	ids = []

	for family, kind in (('mooncake', 'Mooncake'), ('lantern', 'Lantern'), ('mask', 'Mask'), ('fruit', 'Fruit'), ('toy', 'Sword')):
		for match in re.finditer(r'\t\t' + kind + r'\("([a-z0-9_]+)"', text):
			ids.append((family, match.group(1)))

	ids.append(('decor', 'lion_drum'))
	ids.append(('decor', 'pomelo_dog'))

	return ids


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


def make_category(key, display, icon):
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
	known = set(key for key, _ in categories)

	# the encoder has to reproduce bytes the server itself wrote before it is
	# allowed to write any of its own
	probe = by_key['smp-steel-steel-mesh-fence']
	if item_data('lunasmp:steel_mesh_fence') != field(probe, 'item-data'):
		raise SystemExit('encoder does not reproduce the server\'s own bytes')

	print('encoder round-trip ok')

	items = table_ids()

	if len(items) != len(set(nova for _, nova in items)):
		raise SystemExit('an id is listed twice')

	if len(items) < 40:
		raise SystemExit('only %d ids read from %s; the table format changed?' % (len(items), TABLE))

	if CATEGORY not in known:
		categories.append([CATEGORY, make_category(CATEGORY, CATEGORY_NAME, CATEGORY_ICON)])
		print('add   category %s' % CATEGORY)

	added = 0

	for family, nova in items:
		category = CATEGORY
		buy = price_of(family, nova)
		key = 'smp-tt-%s-%s' % (family, nova.replace('_', '-'))

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
