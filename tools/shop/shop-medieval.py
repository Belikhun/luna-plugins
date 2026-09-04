#!/usr/bin/env python3
"""Add the medieval wave (230 luna-smp blocks) to the survival shop.

Same shape as shop-steelworks2.py: a text-level merge where untouched entries
keep their exact bytes, new entries are synthesised the way the server writes
them, and the encoder proves itself against an entry the server wrote before
anything is changed. Three categories are new, so this one also rewrites the
categories block, which every earlier merge left alone.

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
BACKUP = PATH + '.pre-medieval'
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


# ---- the wave, family by family -------------------------------------------
#
# The family is the middle of the key, and the key is what orders a category
# page, so a family is a run of blocks a builder shops for together. Prices
# follow the scale the shop already uses: a cube meant to be laid by the stack
# stays under a coin, a piece of furniture is a few coins, a light fixture or
# an ornament more.

WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'bamboo', 'crimson', 'warped']
FRAME_PATTERNS = [
	'timber_frame', 'timber_frame_beam', 'timber_frame_post', 'timber_frame_cross',
	'braced_timber_frame', 'braced_timber_frame_beam', 'braced_timber_frame_post', 'braced_timber_frame_cross',
]
PILLAR_STONES = ['stone', 'smooth_stone', 'sandstone', 'red_sandstone', 'blackstone', 'basalt', 'tuff', 'mud']
COLORS = [
	'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
	'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
]
METALS = ['iron', 'gold', 'silver']


def group(family, category, buy, ids):
	return [(family, category, buy, block) for block in ids]


ITEMS = []

# the half-timbered walls: eight patterns for eleven woods
ITEMS += group('frame', 'timber', 0.5, ['%s_%s' % (wood, pattern) for wood in WOODS for pattern in FRAME_PATTERNS])

# stone: the pillars stand as their own shape, the rest are laid as cubes
ITEMS += group('stone', 'masonry', 1.0, ['%s_pillar' % stone for stone in PILLAR_STONES])
ITEMS += group('stone', 'masonry', 0.3, ['slate', 'slate_bricks', 'chiseled_slate', 'slate_tiles', 'slate_roof'])
ITEMS += group('stone', 'masonry', 0.4, ['slate_pillar'])
ITEMS += group('stone', 'masonry', 0.4, ['stone_column', 'andesite_column', 'diorite_column', 'granite_column'])
ITEMS += group('stone', 'masonry', 0.3, ['clay_wall', 'crossed_clay_wall', 'diagonal_clay_wall', 'rocky_dirt'])

# the timber structure: what holds a building up, and what hangs off it
ITEMS += group('palisade', 'carpentry', 1.0, ['%s_palisade' % wood for wood in WOODS])
ITEMS += group('support', 'carpentry', 1.0, ['%s_support' % wood for wood in WOODS])
ITEMS += group('support', 'carpentry', 1.0, ['%s_hanging_support' % wood for wood in WOODS])
ITEMS += group('beam', 'carpentry', 0.4, ['%s_beam' % wood for wood in WOODS if wood != 'bamboo'])
ITEMS += group('painted', 'carpentry', 0.4, ['%s_painted_wood' % colour for colour in COLORS])
ITEMS += group('fitting', 'carpentry', 0.4, ['rope_coil'])
ITEMS += group('fitting', 'carpentry', 2.0, ['step_ladder', 'gold_chain', 'silver_chain'])
ITEMS += group('fitting', 'carpentry', 4.0, ['iron_bar_panel'])

# light
ITEMS += group('lantern', 'lighting', 4.0, ['%s_lantern' % metal for metal in METALS])
ITEMS += group('lantern', 'lighting', 4.0, ['hanging_%s_lantern' % metal for metal in METALS])
ITEMS += group('lantern', 'lighting', 4.0, ['%s_wall_lantern' % metal for metal in METALS])
ITEMS += group('lantern', 'lighting', 4.0, ['wooden_lantern', 'wooden_wall_lantern'])
ITEMS += group('chandelier', 'lighting', 10.0, ['%s_chandelier' % metal for metal in METALS])
ITEMS += group('candle', 'lighting', 3.0, ['%s_candle' % metal for metal in METALS])
ITEMS += group('candle', 'lighting', 4.0, ['candlestick'])
ITEMS += group('candle', 'lighting', 5.0, ['gold_candlestick'])
ITEMS += group('torch', 'lighting', 3.0, ['iron_torch', 'iron_wall_torch'])
ITEMS += group('bonfire', 'lighting', 5.0, ['bonfire', 'soul_bonfire'])

# the rest sit in the categories those kinds of thing already live in
ITEMS += group('seat', 'furniture', 3.0, ['%s_seat' % wood for wood in WOODS])
ITEMS += group('store', 'storage', 5.0, ['wooden_barrel', 'cabinet'])
ITEMS += group('store', 'storage', 3.0, ['iron_barrel'])
ITEMS += group('book', 'storage', 1.0, ['book_stack', 'paper_stack'])
ITEMS += group('vase', 'living', 3.0, ['clay_vase', 'patterned_vase', 'striped_vase', 'jade_vase', 'dark_vase', 'painted_vase'])
ITEMS += group('ornament', 'living', 2.0, ['ink_and_quill', 'potion_bottles', 'bone_pile'])
ITEMS += group('ornament', 'living', 5.0, ['globe'])
ITEMS += group('ornament', 'living', 8.0, ['trophy', 'gargoyle'])
ITEMS += group('farm', 'agricultural', 5.0, ['apiary', 'crushing_tub', 'scarecrow'])
ITEMS += group('farm', 'agricultural', 4.0, ['straw_beehive'])
ITEMS += group('farm', 'agricultural', 6.0, ['brewing_barrel'])

# id, display name, and the block whose item is the page's icon
NEW_CATEGORIES = [
	('timber', '<gold>Khung Gỗ Trát Vữa</gold>', 'oak_timber_frame'),
	('masonry', '<gray>Đá Xây & Cột</gray>', 'stone_pillar'),
	('carpentry', '<dark_green>Kết Cấu Gỗ</dark_green>', 'oak_beam'),
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

	seen = set()
	for _, _, _, nova in ITEMS:
		if nova in seen:
			raise SystemExit('listed twice: ' + nova)
		seen.add(nova)

	for key, display, icon in NEW_CATEGORIES:
		if key in known:
			print('skip  category %-12s already present' % key)
			continue

		categories.append([key, make_category(key, display, icon)])
		known.add(key)
		print('add   category %-12s %s' % (key, display))

	counts = {}
	added = 0

	for family, category, buy, nova in ITEMS:
		if category not in known:
			raise SystemExit('unknown category %s for %s' % (category, nova))

		key = 'smp-%s-%s' % (family, nova.replace('_', '-'))

		if key in by_key:
			print('skip  %-44s already present' % key)
			continue

		sell = max(0.1, round(buy / 10, 1))
		block = make_entry(key, category, price(buy), price(sell), item_data('lunasmp:' + nova))
		entries.append([key, block])
		by_key[key] = block
		counts[category] = counts.get(category, 0) + 1
		added += 1

	categories.sort(key=lambda pair: pair[0])
	entries.sort(key=lambda pair: pair[0])

	out = ['categories:']
	for _, block in categories:
		out.extend(block)

	out.append('shop-items:')
	for _, block in entries:
		out.extend(block)

	print()
	for category in sorted(counts):
		print('  %-12s +%d' % (category, counts[category]))

	print('\n%d new entries, %d total, %d categories' % (added, len(entries), len(categories)))

	if not write:
		print('dry run: pass --write to save')
		return

	shutil.copyfile(PATH, BACKUP)
	open(PATH, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
	print('wrote %s (backup %s)' % (PATH, BACKUP))


main()
