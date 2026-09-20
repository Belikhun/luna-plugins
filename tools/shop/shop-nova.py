#!/usr/bin/env python3
"""Add every Nova Logistics and Machines item to the survival shop.

Same shape as the earlier merges (shop-covers.py, shop-medieval.py): a text-level
merge where untouched entries keep their exact bytes, new entries are synthesised
the way the server writes them, and the encoder proves itself against entries the
server wrote before anything is changed.

Prices are derived, not invented: the two addon jars carry their own recipes, so
the cost of an item is the cost of what it is crafted from, all the way down to a
table of vanilla materials calibrated on the shop's own anchors (a hostile kill is
about a coin, a crop is one, machines:star_shards is one). The buy price is that
cost times MARKUP, rounded onto the shop's price scale; sell stays a tenth.

Pass --write to save; without it the merge is printed and nothing is touched.
"""

import base64
import glob
import json
import os
import re
import shutil
import struct
import sys
import zipfile
import zlib

PATH = '/mnt/shulker/mrds/survival/plugins/LunaShop/items.yml'
BACKUP = PATH + '.pre-nova'
PLUGINS = '/mnt/shulker/mrds/survival/plugins'
DATA_VERSION = 4671
# every offline-written entry shares one added-date, so a category page reads
# alphabetically by key rather than in the order the merge happened to run
ADDED = 1788428103122
# what the shop charges over the raw material cost: the star chain already prices
# this way (star_crystal is 4.5 of materials and sells at 10)
MARKUP = 2.0


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
# Coins, on the shop's own scale. Ores and drops are valued at roughly what an
# hour of getting them is worth next to a hostile kill; a tool or a block is
# valued at what it is made of.

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
	# the shop already sells the raw star drop, and the whole star chain hangs off it
	'machines:star_shards': 1.0,
}

# Two blocks have no recipe at all, so they carry a stated price instead of a
# derived one: the creative frame matches its creative siblings already in the
# shop, and the water source is priced as the plumbing it replaces.
HAND_PRICED = {
	'machines:creative_machine_frame': 1000000.0,
	'machines:infinite_water_source': 250.0,
}

# A pulverizer turns raw ore and ore blocks into more dust than the ingot does,
# so those routes would make the shop the cheapest source of metal in the game.
# A dust is priced from the ingot or gem it grinds down from.
ORE_ROUTES = ('raw_', '_ores', '_block', '_blocks', '_slabs', '_stairs', 'nether_gold_ore')

LOGISTICS_IDS = set()


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


def resolve(item, addon):
	"""`nova:x` names whichever addon defines x, which is how the two cross-reference."""
	if item.startswith('nova:'):
		name = item.split(':', 1)[1]

		if name in LOGISTICS_IDS:
			return 'logistics:' + name

		return 'machines:' + name

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

		result = resolve(result, addon)
		name = entry.rsplit('/', 1)[1][:-len('.json')]

		if result.endswith('_dust') and result != 'machines:star_dust':
			if any(token in name for token in ORE_ROUTES):
				continue

		by_result.setdefault(result, []).append((kind, addon, data))

	return by_result


def cost_of(item, recipes, addon_hint, stack):
	"""Cheapest material cost of one `item`, following the recipes down to vanilla."""
	item = resolve(item, addon_hint).split('[')[0]

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
				value = cost_of(alternative.strip(), recipes, addon, stack)

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
# The key is arbitrary (the smp entries prove it), and the category page sorts by
# added-date descending then by key, so a shared date plus a structured key is
# what makes a page read in a sensible order. Tiered families carry their tier
# number so the page runs basic to creative rather than alphabetically.

TIERS = ('basic', 'advanced', 'elite', 'ultimate', 'creative')

ITEMS = []


def tiered(category, prefix, suffix):
	for index, tier in enumerate(TIERS, start=1):
		ITEMS.append((('logistics' if category == 'logistics' else 'machines') + ':' + tier + '_' + suffix,
			category, '%s-%d-%s' % (prefix, index, tier)))


tiered('logistics', 'nova-logistics-cable', 'cable')
tiered('logistics', 'nova-logistics-power-cell', 'power_cell')
tiered('logistics', 'nova-logistics-fluid-tank', 'fluid_tank')

for index, tier in enumerate(TIERS[:4], start=1):
	ITEMS.append(('logistics:%s_item_filter' % tier, 'logistics', 'nova-logistics-filter-%d-%s' % (index, tier)))

for nova in ('storage_unit', 'fluid_storage_unit', 'vacuum_chest', 'trash_can'):
	ITEMS.append(('logistics:' + nova, 'logistics', 'nova-logistics-' + nova.replace('_', '-')))

for index, tier in enumerate(TIERS, start=1):
	ITEMS.append(('machines:%s_machine_frame' % tier, 'machines', 'nova-machine-frame-%d-%s' % (index, tier)))

MACHINES = (
	'mechanical_press', 'pulverizer', 'quarry', 'electric_furnace', 'chunk_loader',
	'block_breaker', 'block_placer', 'charger', 'mob_killer', 'breeder',
	'mob_duplicator', 'planter', 'harvester', 'fertilizer', 'wireless_charger',
	'auto_fisher', 'lightning_exchanger', 'tree_factory', 'star_collector',
	'lava_generator', 'cobblestone_generator', 'pump', 'fluid_infuser', 'freezer',
	'sprinkler', 'electric_brewing_stand', 'wind_turbine', 'furnace_generator',
	'solar_panel', 'infinite_water_source', 'crystallizer', 'auto_crafter',
)

for nova in MACHINES:
	ITEMS.append(('machines:' + nova, 'machines', 'nova-machine-' + nova.replace('_', '-')))

METALS = ('iron', 'gold', 'diamond', 'netherite', 'emerald', 'redstone', 'lapis', 'copper')

for metal in METALS:
	ITEMS.append(('machines:%s_plate' % metal, 'materials', 'nova-part-plate-' + metal))
	ITEMS.append(('machines:%s_gear' % metal, 'materials', 'nova-part-gear-' + metal))

for metal in ('iron', 'gold', 'diamond', 'netherite', 'emerald', 'coal', 'lapis', 'copper', 'star'):
	ITEMS.append(('machines:%s_dust' % metal, 'materials', 'nova-part-dust-' + metal))

ITEMS.append(('machines:star_dust_block', 'materials', 'nova-part-star-dust-block'))
ITEMS.append(('machines:solar_cell', 'materials', 'nova-part-solar-cell'))
ITEMS.append(('machines:scaffolding', 'materials', 'nova-part-scaffolding'))

EQUIPMENT = (
	'logistics:wrench', 'machines:netherite_drill', 'machines:mob_catcher',
	'machines:star_sword', 'machines:star_pickaxe', 'machines:star_axe',
	'machines:star_shovel', 'machines:star_hoe', 'machines:star_helmet',
	'machines:star_chestplate', 'machines:star_leggings', 'machines:star_boots',
)

for index, nova in enumerate(EQUIPMENT, start=1):
	ITEMS.append((nova, 'equipment', 'nova-equip-%02d-%s' % (index, nova.split(':')[1].replace('_', '-'))))

# The three creative logistics blocks were added in game and sit in `machines`;
# every other tier of their families now lives in `logistics`, so they follow.
RECATEGORISE = {
	'logistics:creative_cable': 'logistics',
	'logistics:creative_power_cell': 'logistics',
	'logistics:creative_fluid_tank': 'logistics',
}

NEW_CATEGORIES = (
	('logistics', 'logistics:storage_unit', '<dark_aqua>Truyền Tải & Lưu Trữ</dark_aqua>'),
	('equipment', 'machines:star_pickaxe', '<gold>Dụng Cụ & Trang Bị</gold>'),
)


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


def set_field(block, name, value):
	for index, line in enumerate(block):
		if re.match(r'^    ' + re.escape(name) + r': ', line):
			block[index] = '    %s: %s' % (name, value)
			return

	raise SystemExit('no %s field to set' % name)


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
		'    icon-data: %s' % item_data(icon),
		'    display-name: %s' % display,
	]


def price(value):
	return '%.1f' % value


def nova_id_of(block):
	"""The nova id an existing entry's serialized stack carries, if it has one."""
	raw = base64.b64decode(field(block, 'item-data'))

	if raw[:2] == b'\x1f\x8b':
		import gzip
		raw = gzip.decompress(raw)

	match = re.search(rb'\x08\x00\x02id\x00.([\w:_]+)', raw[raw.find(b'nova'):])

	if match:
		return match.group(1).decode('utf-8')

	return None


def main():
	write = '--write' in sys.argv

	logistics_names, logistics_recipes = read_addon('Logistics', 'logistics')
	machines_names, machines_recipes = read_addon('Machines', 'machines')

	for item in logistics_names:
		LOGISTICS_IDS.add(item.split(':')[1])

	names = dict(logistics_names)
	names.update(machines_names)
	recipes = index_recipes(logistics_recipes + machines_recipes)

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

	# and the item list has to be the addons' own, with nothing invented and
	# nothing missed
	listed = set(nova for nova, _, _ in ITEMS)
	present = {}

	for key, block in entries:
		nova = nova_id_of(block)

		if nova and (nova.startswith('logistics:') or nova.startswith('machines:')):
			present[nova] = key

	missed = set(names) - listed - set(present)
	phantom = listed - set(names)

	if phantom:
		raise SystemExit('not items of either addon: ' + ', '.join(sorted(phantom)))

	if missed:
		raise SystemExit('addon items nobody placed: ' + ', '.join(sorted(missed)))

	print('%d addon items: %d to place, %d already in the shop' % (len(names), len(listed), len(present)))

	added = 0
	skipped = 0

	for nova, category, key in ITEMS:
		if category not in known and category not in [c for c, _, _ in NEW_CATEGORIES]:
			raise SystemExit('unknown category %s for %s' % (category, nova))

		if nova in present:
			print('skip  %-40s already in the shop as %s' % (nova, present[nova]))
			skipped += 1
			continue

		if key in by_key:
			raise SystemExit('key already taken: ' + key)

		if nova in HAND_PRICED:
			buy = HAND_PRICED[nova]
			source = 'stated'
		else:
			cost = cost_of(nova, recipes, nova.split(':')[0], frozenset())

			if cost is None:
				raise SystemExit('no way to price ' + nova)

			buy = shop_price(cost)
			source = '%.2f of materials' % cost

		sell = max(0.1, round(buy / 10, 1))
		block = make_entry(key, category, price(buy), price(sell), item_data(nova))
		entries.append([key, block])
		by_key[key] = block
		added += 1
		print('add   %-34s %-11s %10s   %-28s %s' % (key, category, price(buy), names[nova], source))

	moved = 0

	for nova, category in RECATEGORISE.items():
		key = present.get(nova)

		if key is None:
			continue

		block = by_key[key]

		if field(block, 'category') == category:
			continue

		set_field(block, 'category', category)
		moved += 1
		print('move  %-34s -> %s (%s)' % (key, category, names[nova]))

	for key, icon, display in NEW_CATEGORIES:
		if key in known:
			print('skip  category %s already exists' % key)
			continue

		categories.append([key, make_category(key, icon, display)])
		print('cat   %-34s %s' % (key, display))

	categories.sort(key=lambda pair: pair[0])
	entries.sort(key=lambda pair: pair[0])

	out = ['categories:']
	for _, block in categories:
		out.extend(block)

	out.append('shop-items:')
	for _, block in entries:
		out.extend(block)

	print('\n%d new entries, %d skipped, %d recategorised, %d total, %d categories'
		% (added, skipped, moved, len(entries), len(categories)))

	if not write:
		print('dry run: pass --write to save')
		return

	shutil.copyfile(PATH, BACKUP)
	open(PATH, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
	print('wrote %s (backup %s)' % (PATH, BACKUP))


main()
