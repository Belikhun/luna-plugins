#!/usr/bin/env python3
"""Add the five Nova Simple Upgrades items to the survival shop.

Same shape as shop-nova.py, which put the Logistics and Machines addons in:
a text-level merge where untouched entries keep their exact bytes, new entries
are synthesised the way the server writes them, and the encoder proves itself
against an entry the server wrote before anything is changed.

Prices are derived the same way too. An upgrade's recipe is read out of the
addon jar and costed down through the Machines and Logistics recipes to a table
of vanilla material values, then marked up. Simple Upgrades' recipes accept a
vanilla stand-in for every addon part they ask for (`nova:iron_plate;
minecraft:paper`), so the cheapest route is what a player actually pays, which
is the route the costing follows.

Pass --write to save; without it the merge is printed and nothing is touched.
"""

import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0])

import base64
import glob
import gzip
import json
import os
import re
import shutil
import struct
import zipfile
import zlib

PATH = '/mnt/shulker/mrds/survival/plugins/LunaShop/items.yml'
BACKUP = PATH + '.pre-upgrades'
PLUGINS = '/mnt/shulker/mrds/survival/plugins'
DATA_VERSION = 4671
ADDED = 1788428103122
MARKUP = 2.0

# the addons whose recipes an upgrade's cost can walk through, and the
# namespace each one's items carry
ADDONS = (
	('Simple_Upgrades', 'simple_upgrades'),
	('Machines', 'machines'),
	('Logistics', 'logistics'),
)


# ---- the encoder ----------------------------------------------------------

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


# ---- what a vanilla material is worth -------------------------------------
#
# The subset of shop-nova.py's table the upgrade recipes and everything under
# them can reach. Same values, so an upgrade and a machine are priced off the
# same anchors.

VANILLA = {
	'minecraft:iron_ingot': 1.0,
	'minecraft:raw_iron': 0.8,
	'minecraft:iron_block': 9.0,
	'minecraft:iron_bars': 0.4,
	'minecraft:iron_trapdoor': 4.0,
	'minecraft:gold_ingot': 2.0,
	'minecraft:gold_nugget': 0.25,
	'minecraft:raw_gold': 1.6,
	'minecraft:nether_gold_ore': 1.6,
	'minecraft:copper_ingot': 0.5,
	'minecraft:raw_copper': 0.4,
	'minecraft:redstone': 0.3,
	'minecraft:redstone_block': 2.7,
	'minecraft:lapis_lazuli': 0.5,
	'minecraft:lapis_block': 4.5,
	'minecraft:coal': 0.4,
	'minecraft:coal_block': 3.6,
	'minecraft:diamond': 8.0,
	'minecraft:diamond_block': 72.0,
	'minecraft:emerald': 6.0,
	'minecraft:emerald_block': 54.0,
	'minecraft:netherite_ingot': 60.0,
	'minecraft:netherite_block': 540.0,
	'minecraft:amethyst_shard': 0.5,
	'minecraft:nether_star': 150.0,
	'minecraft:end_crystal': 10.0,
	'minecraft:ender_pearl': 2.0,
	'minecraft:ghast_tear': 5.0,
	'minecraft:blaze_powder': 1.5,
	'minecraft:glowstone_dust': 0.5,
	'minecraft:glowstone': 2.0,
	'minecraft:glass': 0.15,
	'minecraft:glass_pane': 0.1,
	'minecraft:glass_bottle': 0.15,
	'minecraft:tinted_glass': 1.1,
	'minecraft:stone': 0.05,
	'minecraft:cobblestone': 0.05,
	'minecraft:smooth_stone_slab': 0.05,
	'minecraft:gravel': 0.05,
	'minecraft:sand': 0.05,
	'minecraft:stick': 0.05,
	'minecraft:paper': 0.1,
	'minecraft:chest': 0.4,
	'minecraft:furnace': 0.4,
	'minecraft:crafting_table': 0.2,
	'minecraft:piston': 1.8,
	'minecraft:hopper': 5.4,
	'minecraft:anvil': 31.0,
	'minecraft:bucket': 3.0,
	'minecraft:water_bucket': 3.0,
	'minecraft:lava_bucket': 3.2,
	'minecraft:powder_snow_bucket': 3.2,
	'minecraft:cod_bucket': 3.5,
	'minecraft:salmon_bucket': 3.5,
	'minecraft:shears': 2.0,
	'minecraft:fishing_rod': 0.6,
	'minecraft:string': 0.2,
	'minecraft:wool': 0.8,
	'minecraft:bone': 0.3,
	'minecraft:bone_meal': 0.1,
	'minecraft:bone_block': 0.9,
	'minecraft:oak_sapling': 0.3,
	'minecraft:wheat_seeds': 0.2,
	'minecraft:carrot': 1.0,
	'minecraft:potato': 1.0,
	'minecraft:brown_mushroom': 0.3,
	'minecraft:crimson_fungus': 0.5,
	'minecraft:yellow_dye': 0.2,
	'minecraft:brewing_stand': 1.8,
	'minecraft:lightning_rod': 1.5,
	'minecraft:diamond_pickaxe': 24.1,
	'minecraft:diamond_sword': 16.1,
	'minecraft:iron_pickaxe': 3.1,
	'minecraft:iron_axe': 3.1,
	'minecraft:iron_hoe': 2.1,
	'minecraft:sponge': 5.0,
	'minecraft:wet_sponge': 5.0,
	'minecraft:ice': 0.3,
	'minecraft:snowball': 0.05,
	'minecraft:kelp': 0.2,
	'minecraft:dried_kelp': 0.2,
	'machines:star_shards': 1.0,
}

# A pulverizer turns raw ore and ore blocks into more dust than the ingot does,
# so those routes would make the shop the cheapest source of metal in the game.
ORE_ROUTES = ('raw_', '_ores', '_block', '_blocks', '_slabs', '_stairs', 'nether_gold_ore')

# which addon defines each bare `nova:<id>` a recipe names
NAMESPACES = {}


# ---- the addons' own recipes ----------------------------------------------

def addon_jar(prefix):
	matches = sorted(glob.glob(os.path.join(PLUGINS, prefix + '-*.jar')))

	if not matches:
		raise SystemExit('no %s jar under %s' % (prefix, PLUGINS))

	return matches[-1]


def read_addon(prefix, addon):
	"""The addon's English names and every recipe it ships."""
	names = {}
	recipes = []

	with zipfile.ZipFile(addon_jar(prefix)) as jar:
		lang = json.loads(jar.read('assets/lang/en_us.json'))

		for key, value in lang.items():
			parts = key.split('.')

			if parts[0] in ('block', 'item') and len(parts) == 3 and parts[1] == addon:
				names[addon + ':' + parts[2]] = value

		for entry in jar.namelist():
			if not entry.startswith('recipes/') or not entry.endswith('.json'):
				continue

			data = json.loads(jar.read(entry))
			kind = entry[len('recipes/'):].rsplit('/', 1)[0]
			recipes.append((kind, addon, entry, data))

	return names, recipes


def resolve(item):
	"""`nova:x` names whichever addon defines x, which is how they cross-reference."""
	if item.startswith('nova:'):
		name = item.split(':', 1)[1]

		if name not in NAMESPACES:
			raise SystemExit('no addon defines nova:' + name)

		return NAMESPACES[name] + ':' + name

	return item


def ingredients_of(kind, data):
	"""Any recipe shape reduced to a {ingredient: count} map plus the amount it yields."""
	if kind.startswith('minecraft/shaped'):
		counts = {}
		ingredients = data['ingredients']

		for row in data['shape']:
			for char in row:
				# ' ' and '.' both stand for an empty slot, and mechanical_press
				# carries a letter its ingredient map never defines
				item = ingredients.get(char)

				if item is None:
					continue

				counts[item] = counts.get(item, 0) + 1

		return counts, data.get('amount', 1)

	if kind.startswith('minecraft/shapeless'):
		return dict(data['ingredients']), data.get('amount', 1)

	if 'input' in data:
		value = data['input']

		if isinstance(value, list):
			return {'; '.join(value): 1}, data.get('amount', 1)

		if isinstance(value, dict):
			return dict(value), data.get('amount', 1)

		return {value: 1}, data.get('amount', 1)

	return None


def index_recipes(recipes):
	"""Every recipe keyed by the item it produces, ore-doubling routes dropped."""
	by_result = {}

	for kind, addon, entry, data in recipes:
		result = data.get('result')

		if not isinstance(result, str):
			continue

		result = resolve(result)
		name = entry.rsplit('/', 1)[1][:-len('.json')]

		if result.endswith('_dust') and result != 'machines:star_dust':
			if any(token in name for token in ORE_ROUTES):
				continue

		by_result.setdefault(result, []).append((kind, addon, data))

	return by_result


def cost_of(item, recipes, stack):
	"""Cheapest material cost of one `item`, following the recipes down to vanilla."""
	item = resolve(item).split('[')[0]

	if item in VANILLA:
		return VANILLA[item]

	if item in stack:
		return None

	best = None
	stack = stack | {item}

	for kind, addon, data in recipes.get(item, []):
		parsed = ingredients_of(kind, data)

		if parsed is None:
			continue

		counts, amount = parsed
		total = 0.0
		known = True

		for ingredient, count in counts.items():
			options = []

			for alternative in ingredient.split(';'):
				value = cost_of(alternative.strip(), recipes, stack)

				if value is not None:
					options.append(value)

			if not options:
				known = False
				break

			total += min(options) * count

		if not known:
			continue

		value = total / amount

		if best is None or value < best:
			best = value

	return best


def shop_price(cost):
	"""The material cost marked up and pulled onto the shop's price scale."""
	value = cost * MARKUP

	if value < 1:
		return max(0.1, round(value, 1))

	if value < 10:
		return round(value * 2) / 2

	if value < 50:
		return float(round(value))

	if value < 200:
		return float(round(value / 5) * 5)

	if value < 1000:
		return float(round(value / 25) * 25)

	return float(round(value / 100) * 100)


# ---- where each item goes -------------------------------------------------
#
# An upgrade is fitted to a machine, so it belongs on the machines page. The
# five are siblings rather than a tier ladder, so the key carries no number and
# the page lists them alphabetically after the machines themselves.

UPGRADES = ('speed', 'efficiency', 'energy', 'range', 'fluid')

ITEMS = [('simple_upgrades:%s_upgrade' % name, 'machines', 'nova-upgrade-' + name) for name in UPGRADES]


# ---- the merge ------------------------------------------------------------

def split_file(text):
	"""The categories block, and the entries under shop-items."""
	lines = text.split('\n')
	head = lines.index('shop-items:')

	def blocks(source):
		out = []
		current = None

		for line in source:
			if line == '':
				continue

			match = re.match(r"^  (?:'([^']+)'|([^\s:]+)):\s*$", line)
			if match:
				current = [match.group(1) or match.group(2), [line]]
				out.append(current)
				continue

			if current is None:
				raise SystemExit('unexpected line: ' + line[:60])

			current[1].append(line)

		return out

	return blocks(lines[1:head]), blocks(lines[head + 1:])


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


def nova_id_of(block):
	"""The nova id an existing entry's serialized stack carries, if it has one."""
	raw = base64.b64decode(field(block, 'item-data'))

	if raw[:2] == b'\x1f\x8b':
		raw = gzip.decompress(raw)

	match = re.search(rb'\x08\x00\x02id\x00.([\w:_]+)', raw[raw.find(b'nova'):])

	if match:
		return match.group(1).decode('utf-8')

	return None


def main():
	write = '--write' in sys.argv

	names = {}
	recipes = []

	for prefix, addon in ADDONS:
		addon_names, addon_recipes = read_addon(prefix, addon)

		for item in addon_names:
			NAMESPACES.setdefault(item.split(':')[1], addon)

		names.update(addon_names)
		recipes.extend(addon_recipes)

	recipes = index_recipes(recipes)

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

	# and the item list has to be the addon's own, with nothing invented and
	# nothing missed
	addon_items = set(item for item in names if item.startswith('simple_upgrades:'))
	listed = set(nova for nova, _, _ in ITEMS)
	present = {}

	for key, block in entries:
		nova = nova_id_of(block)

		if nova and nova.startswith('simple_upgrades:'):
			present[nova] = key

	phantom = listed - addon_items
	missed = addon_items - listed - set(present)

	if phantom:
		raise SystemExit('not items of the addon: ' + ', '.join(sorted(phantom)))

	if missed:
		raise SystemExit('addon items nobody placed: ' + ', '.join(sorted(missed)))

	print('%d Simple Upgrades items: %d to place, %d already in the shop' % (len(addon_items), len(listed), len(present)))

	added = 0
	skipped = 0

	for nova, category, key in ITEMS:
		if category not in known:
			raise SystemExit('unknown category %s for %s' % (category, nova))

		if nova in present:
			print('skip  %-40s already in the shop as %s' % (nova, present[nova]))
			skipped += 1
			continue

		if key in by_key:
			raise SystemExit('key already taken: ' + key)

		cost = cost_of(nova, recipes, frozenset())

		if cost is None:
			raise SystemExit('no way to price ' + nova)

		buy = shop_price(cost)
		sell = max(0.1, round(buy / 10, 1))
		block = make_entry(key, category, price(buy), price(sell), item_data(nova))
		entries.append([key, block])
		by_key[key] = block
		added += 1
		print('add   %-24s %-10s %8s   %-20s %.2f of materials' % (key, category, price(buy), names[nova], cost))

	categories.sort(key=lambda pair: pair[0])
	entries.sort(key=lambda pair: pair[0])

	out = ['categories:']
	for _, block in categories:
		out.extend(block)

	out.append('shop-items:')
	for _, block in entries:
		out.extend(block)

	print('\n%d new entries, %d skipped, %d total, %d categories' % (added, skipped, len(entries), len(categories)))

	if not write:
		print('dry run: pass --write to save')
		return

	shutil.copyfile(PATH, BACKUP)
	open(PATH, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
	print('wrote %s (backup %s)' % (PATH, BACKUP))


main()
