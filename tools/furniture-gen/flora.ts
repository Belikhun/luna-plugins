// The flora catalog: the flowering blocks luna-smp ships beside the furniture.
//
// Two sources, handled very differently.
//
// Many Flowers (MIT, GalievDev) is a straight adoption: its flower sprites are
// its own art and travel with their licence, so a block here is its texture and
// a cross model, exactly as the mod draws it.
//
// Floral Enchantment is *not* adopted. Its art is All Rights Reserved upstream
// whatever the stray MIT file in a source drop says, so nothing of it is copied
// or traced. What is taken is the idea - flowering wall vines, flowering mossy
// stone, bushes you harvest - and every model below is built here from our own
// geometry over `minecraft:` texture references, which the client already has
// and which we therefore never redistribute. That is also why these read as
// vanilla flowers growing on vanilla stone rather than as painted variants:
// composing beats copying, and it means one entry serves every flower.

import { type Element, type Model, box, cross, model, sheet } from './compose';

/** A vanilla flower a composed piece can be built around. */
export interface Flower {
	/** Slug used in every generated block id. */
	id: string;
	en: string;
	vi: string;
	/** The `minecraft:block/` texture drawn for it. */
	texture: string;
	/**
	 * The sprite is a full opaque square, so a cross of it is a wall. The one
	 * flower like that is drawn as small cubes instead, which is also what the
	 * real thing looks like: a chorus flower is a block, not a plant.
	 */
	cube?: boolean;
	/**
	 * The two colours its blooms are dotted with on a painted bush sprite,
	 * main petal first, paler second. Chosen here by eye, not sampled from
	 * anybody's art.
	 */
	accents: [string, string];
}

/**
 * The flowers every composed family is generated for.
 *
 * Only flowers whose sprite is a single clean cross qualify: a two-block plant
 * contributes its top half (`sunflower_front`, `lilac_top`), which is the half
 * that carries the bloom, and azalea is left out because its art is a leaf
 * block rather than a flower.
 */
export const FLOWERS: Flower[] = [
	{ id: 'dandelion', en: 'Dandelion', vi: 'Bồ Công Anh', texture: 'dandelion', accents: ['#f2d234', '#f7e77f'] },
	{ id: 'poppy', en: 'Poppy', vi: 'Hoa Anh Túc', texture: 'poppy', accents: ['#dc3e43', '#b52a31'] },
	{ id: 'blue_orchid', en: 'Blue Orchid', vi: 'Lan Xanh', texture: 'blue_orchid', accents: ['#4aa8d8', '#8fd0ee'] },
	{ id: 'allium', en: 'Allium', vi: 'Hoa Tỏi', texture: 'allium', accents: ['#b878d8', '#d7abee'] },
	{ id: 'azure_bluet', en: 'Azure Bluet', vi: 'Hoa Chuông Xanh', texture: 'azure_bluet', accents: ['#e9edf3', '#d9c661'] },
	{ id: 'red_tulip', en: 'Red Tulip', vi: 'Tulip Đỏ', texture: 'red_tulip', accents: ['#d0402f', '#ec6a50'] },
	{ id: 'orange_tulip', en: 'Orange Tulip', vi: 'Tulip Cam', texture: 'orange_tulip', accents: ['#e88a30', '#f5b45e'] },
	{ id: 'white_tulip', en: 'White Tulip', vi: 'Tulip Trắng', texture: 'white_tulip', accents: ['#eceeeb', '#cfd4cd'] },
	{ id: 'pink_tulip', en: 'Pink Tulip', vi: 'Tulip Hồng', texture: 'pink_tulip', accents: ['#eba9c6', '#f5cbdd'] },
	{ id: 'oxeye_daisy', en: 'Oxeye Daisy', vi: 'Cúc Trắng', texture: 'oxeye_daisy', accents: ['#eef2ee', '#ddc23e'] },
	{ id: 'cornflower', en: 'Cornflower', vi: 'Hoa Ngô', texture: 'cornflower', accents: ['#4a6cc8', '#7691e0'] },
	{ id: 'lily_of_the_valley', en: 'Lily of the Valley', vi: 'Linh Lan', texture: 'lily_of_the_valley', accents: ['#e9efec', '#f7fbf8'] },
	{ id: 'wither_rose', en: 'Wither Rose', vi: 'Hồng Wither', texture: 'wither_rose', accents: ['#211d21', '#463c46'] },
	{ id: 'torchflower', en: 'Torchflower', vi: 'Hoa Đuốc', texture: 'torchflower', accents: ['#e8762e', '#f5b02e'] },
	{ id: 'chorus_flower', en: 'Chorus Flower', vi: 'Hoa Chorus', texture: 'chorus_flower', cube: true, accents: ['#a98ac2', '#e7def0'] },
	{ id: 'pink_petals', en: 'Pink Petals', vi: 'Cánh Hoa Hồng', texture: 'pink_petals', accents: ['#f0b5ce', '#f8d4e4'] },
	{ id: 'spore_blossom', en: 'Spore Blossom', vi: 'Hoa Bào Tử', texture: 'spore_blossom', accents: ['#e39ab8', '#c97ba1'] },
	{ id: 'open_eyeblossom', en: 'Open Eyeblossom', vi: 'Hoa Mắt Nở', texture: 'open_eyeblossom', accents: ['#f0e6d2', '#d8862e'] },
	{ id: 'closed_eyeblossom', en: 'Closed Eyeblossom', vi: 'Hoa Mắt Khép', texture: 'closed_eyeblossom', accents: ['#8d7f95', '#6e6377'] },
	{ id: 'wildflowers', en: 'Wildflowers', vi: 'Hoa Dại', texture: 'wildflowers', accents: ['#e9cf3f', '#f6e67c'] },
	{ id: 'cactus_flower', en: 'Cactus Flower', vi: 'Hoa Xương Rồng', texture: 'cactus_flower', accents: ['#ec84a4', '#f6b0c5'] },
	{ id: 'sunflower', en: 'Sunflower', vi: 'Hoa Hướng Dương', texture: 'sunflower_front', accents: ['#eac22e', '#cb911f'] },
	{ id: 'lilac', en: 'Lilac', vi: 'Tử Đinh Hương', texture: 'lilac_top', accents: ['#b591c9', '#cfb2e2'] },
	{ id: 'rose_bush', en: 'Rose Bush', vi: 'Bụi Hồng', texture: 'rose_bush_top', accents: ['#d23c46', '#ec656d'] },
	{ id: 'peony', en: 'Peony', vi: 'Mẫu Đơn', texture: 'peony_top', accents: ['#e7abd6', '#f4c9e6'] },
	{ id: 'pitcher_plant', en: 'Pitcher Plant', vi: 'Cây Nắp Ấm', texture: 'pitcher_crop_top', accents: ['#8bc9b4', '#dceca3'] },
];

// ---- the composed families -----------------------------------------------

/**
 * A flowering stone: the client's own plain stone cube with our painted
 * moss-and-flowers overlay laid over it as a second, marginally inflated cube.
 * The inflation is what keeps two coplanar faces from fighting over which one
 * is drawn; both cubes cull against solid neighbours, so a buried face costs
 * nothing. The moss is entirely the overlay's - the base is deliberately the
 * plain stone, not vanilla's mossy one, or the two moss patterns would argue.
 */
function floweringStone(slug: string, base: string, flower: Flower): Model {
	const overlay = `lunasmp:block/${flower.id}_${slug}`;

	return model(
		{ base: `minecraft:block/${base}`, moss: overlay, particle: `minecraft:block/${base}` },
		[
			box([0, 0, 0], [16, 16, 16], '#base', true),
			box([-0.05, -0.05, -0.05], [16.05, 16.05, 16.05], '#moss', true),
		],
	);
}

/** Where the blooms sit on a wall vine, as centre, height up the wall and size. */
const VINE_BLOOMS: Array<[number, number, number]> = [
	[4, 2, 6],
	[11, 7, 7],
	[7, 11, 5],
];

/**
 * One bloom growing out of the vine.
 *
 * The sprite quad starts BEHIND the curtain and leans out through it: the
 * stem half of the sprite stays buried in the foliage and only the head
 * emerges, a couple of pixels proud of the leaves. That burial is the whole
 * attachment illusion - the two earlier cuts (a free-standing cross at z 6,
 * then a rooted-but-fully-visible tilted pair) both read as flowers floating
 * beside the vine, because every pixel of empty sprite between the visible
 * bloom and the wall reads as air. 22.5 degrees is the smallest lean the
 * model format allows, and no perpendicular profile plane: edge-on the bloom
 * simply melts into the curtain, which is what a flower in foliage does.
 */
function vineBloom(flower: Flower, cx: number, y: number, width: number): Element[] {
	const half = width / 2;

	// the block-shaped flower becomes a flat stud set into the leaves rather
	// than a full cube on a stick
	if (flower.cube) {
		return [box([cx - half, y, 0.2], [cx + half, y + width, 2.6], '#flower')];
	}

	const uv = { uv: [0, 0, 16, 16] as [number, number, number, number], texture: '#flower' };

	return [
		{
			from: [cx - half, y, 0.2],
			to: [cx + half, y + width, 0.2],
			rotation: { origin: [cx, y, 0.2], axis: 'x', angle: 22.5 },
			shade: false,
			faces: { north: uv, south: uv },
		},
	];
}

/**
 * A curtain of vine on a wall with flowers opening out of it.
 *
 * The sheet is a whisker off the wall rather than flush with it, because two
 * coplanar faces fight over which one the client draws.
 */
function floweringVine(flower: Flower): Model {
	const curtain = `lunasmp:block/${flower.id}_vine`;
	const elements: Element[] = [sheet('#vine', 0.4)];

	for (const [cx, y, width] of VINE_BLOOMS) {
		elements.push(...vineBloom(flower, cx, y, width));
	}

	return model(
		{ vine: curtain, flower: `minecraft:block/${flower.texture}`, particle: curtain },
		elements,
	);
}

/**
 * A bush: a 14x14x14 box wearing one dense painted leaf field on every face,
 * which is the original mod's own construction. An earlier sprite-boxed
 * attempt failed because its borrowed sprite was sparse and the faces turned
 * to camouflage noise; the field `sprites.ts` paints is nearly solid, so the
 * box reads as a clipped shrub with only its rim fraying. The sides crop the
 * outermost pixel column off the texture so the fray sits on the corner
 * instead of floating beside it.
 *
 * It is placed grown and stays grown: an earlier round gave bushes growth
 * stages and a harvest loop, and the user cut that - these are decoration.
 */
function bush(flower: Flower): Model {
	const sprite = `lunasmp:block/${flower.id}_bush`;
	const side = { uv: [1, 1, 15, 15], texture: '#plant' };

	return model(
		{ plant: sprite, particle: sprite },
		[{
			from: [1, 0, 1],
			to: [15, 14, 15],
			faces: {
				north: side,
				south: side,
				east: side,
				west: side,
				up: { uv: [0, 0, 16, 16], texture: '#plant' },
				down: { uv: [0, 0, 16, 16], texture: '#plant', cullface: 'down' },
			},
		}],
	);
}

// ---- the catalog ---------------------------------------------------------

/**
 * How a flora block is put in the world.
 *
 * `cube` is a real vanilla block state under the hood (a reserved note block),
 * so a wall of them lights, occludes and culls like stone and costs no display
 * entities - which is the whole reason the flowering stone is worth generating
 * at all. `plant` and `wall` are entity-backed over a structure void: nothing to
 * walk into, a small box in the middle to aim at, and free geometry.
 */
export type Backing = 'cube' | 'plant' | 'wall';

export interface Flora {
	/** Block id inside the lunasmp namespace. */
	id: string;
	en: string;
	vi: string;
	backing: Backing;
	/** Model name to model; the plain `id` is the one an item shows. */
	models: Record<string, Model>;
	/**
	 * The sprite the item shows in a menu and on the ground, instead of the 3D
	 * model shrunk into a slot. Vanilla draws every flower item flat, and a
	 * cross model angled into 16 pixels is an unreadable dark sliver.
	 */
	icon?: string;
	/** Turns to face whoever places it; only the flora with a face needs it. */
	directional?: boolean;
	/** Lights up at night and goes dark at dawn, at this level. */
	nightLight?: number;
	/** The behaviour applied to whatever walks into or stands near it. */
	effect?: string;
	hardness: number;
	tool: 'axe' | 'pickaxe' | 'shovel' | 'hoe';
	sounds: 'WOOD' | 'STONE' | 'GRASS';
}

const STONES: Array<[string, string, string, string]> = [
	['mossy_cobblestone', 'cobblestone', 'Mossy Cobblestone', 'Đá Cuội Rêu'],
	['mossy_stone_bricks', 'stone_bricks', 'Mossy Stone Bricks', 'Gạch Đá Rêu'],
];

const FLOWERING_STONE: Flora[] = STONES.flatMap(([slug, base, stoneEn, stoneVi]) =>
	FLOWERS.map((flower): Flora => ({
		id: `${flower.id}_${slug}`,
		en: `${flower.en} ${stoneEn}`,
		vi: `${stoneVi} ${flower.vi}`,
		backing: 'cube',
		models: { [`${flower.id}_${slug}`]: floweringStone(slug, base, flower) },
		hardness: 2.0,
		tool: 'pickaxe',
		sounds: 'STONE',
	})),
);

const FLOWERING_VINES: Flora[] = FLOWERS.map((flower): Flora => ({
	id: `${flower.id}_vine`,
	en: `${flower.en} Vine`,
	vi: `Dây Leo ${flower.vi}`,
	backing: 'wall',
	models: { [`${flower.id}_vine`]: floweringVine(flower) },
	icon: `lunasmp:block/${flower.id}_vine`,
	hardness: 0.2,
	tool: 'axe',
	sounds: 'GRASS',
}));

// the rose bush is already a bush: `Rose Bush Bush` is what naming a family by
// suffix costs, and the one place it has to be paid
const FLOWER_BUSHES: Flora[] = FLOWERS.map((flower): Flora => ({
	id: `${flower.id}_bush`,
	en: flower.en.endsWith('Bush') ? flower.en : `${flower.en} Bush`,
	vi: flower.vi.startsWith('Bụi') ? flower.vi : `Bụi ${flower.vi}`,
	backing: 'plant',
	models: { [`${flower.id}_bush`]: bush(flower) },
	hardness: 0.2,
	tool: 'axe',
	sounds: 'GRASS',
}));


// ---- Many Flowers --------------------------------------------------------

/** A flower adopted from Many Flowers, drawn with the mod's own sprite. */
interface Bloom {
	id: string;
	en: string;
	vi: string;
	/** The behaviour key applied to whoever walks into or stands near it. */
	effect?: string;
}

/**
 * The flowers Many Flowers draws as one cross in one block.
 *
 * Everything that reached beyond its own block in the mod - the ore economy,
 * the biome spread, teleporting, turning the overworld into nether - is left
 * behind: what a flower does here it does to whoever is standing on it or
 * beside it, so a garden full of them cannot rewrite the world around it.
 */
const BLOOMS: Bloom[] = [
	{ id: 'alstroemeria', en: 'Alstroemeria', vi: 'Lan Huệ Peru' },
	{ id: 'hydrangea', en: 'Hydrangea', vi: 'Cẩm Tú Cầu' },
	{ id: 'marigold', en: 'Marigold', vi: 'Cúc Vạn Thọ' },
	{ id: 'daisies', en: 'Daisies', vi: 'Hoa Cúc Dại' },
	{ id: 'purple_cornflower', en: 'Purple Cornflower', vi: 'Hoa Ngô Tím' },
	{ id: 'petunia', en: 'Petunia', vi: 'Dạ Yến Thảo' },
	{ id: 'begonia', en: 'Begonia', vi: 'Thu Hải Đường' },
	{ id: 'snapdragon', en: 'Snapdragon', vi: 'Hoa Mõm Chó' },
	{ id: 'sweet_alyssum', en: 'Sweet Alyssum', vi: 'Hoa Tuyết Điểm', effect: 'GLIMMER' },
	{ id: 'gaillardia', en: 'Gaillardia', vi: 'Cúc Lửa', effect: 'BURN' },
	{ id: 'oriental_poppy', en: 'Oriental Poppy', vi: 'Anh Túc Phương Đông', effect: 'BLAST' },
	{ id: 'water_hemlock', en: 'Water Hemlock', vi: 'Độc Cần Nước', effect: 'POISON' },
	{ id: 'chrysanthemum', en: 'Chrysanthemum', vi: 'Hoa Cúc', effect: 'MEND' },
	{ id: 'autumn_crocus', en: 'Autumn Crocus', vi: 'Nghệ Tây Mùa Thu', effect: 'DREAD' },
	{ id: 'black_eyed_susan', en: 'Black-Eyed Susan', vi: 'Cúc Mắt Đen', effect: 'GUARD' },
	{ id: 'coreopsis', en: 'Coreopsis', vi: 'Cúc Sao Vàng', effect: 'FORTUNE' },
	{ id: 'dahlia', en: 'Dahlia', vi: 'Thược Dược', effect: 'RALLY' },
	{ id: 'lavender', en: 'Lavender', vi: 'Oải Hương', effect: 'LULL' },
	{ id: 'velvets', en: 'Velvets', vi: 'Hoa Nhung' },
	{ id: 'bone_flower', en: 'Bone Flower', vi: 'Hoa Xương' },
	{ id: 'trade_flower', en: 'Trade Flower', vi: 'Hoa Đổi Chác' },
	{ id: 'root_of_the_worlds', en: 'Root of the Worlds', vi: 'Rễ Thế Giới' },
	{ id: 'ethereal_orchid', en: 'Ethereal Orchid', vi: 'Lan Hư Ảo' },
	{ id: 'dreadpetal', en: 'Dreadpetal', vi: 'Cánh Hoa Khiếp Sợ', effect: 'REPEL' },
	{ id: 'blindblossom', en: 'Blindblossom', vi: 'Hoa Mù Lòa', effect: 'LURE' },
	{ id: 'coal_flower', en: 'Coal Flower', vi: 'Hoa Than' },
	{ id: 'copper_flower', en: 'Copper Flower', vi: 'Hoa Đồng' },
	{ id: 'iron_flower', en: 'Iron Flower', vi: 'Hoa Sắt' },
	{ id: 'gold_flower', en: 'Gold Flower', vi: 'Hoa Vàng' },
	{ id: 'diamond_flower', en: 'Diamond Flower', vi: 'Hoa Kim Cương' },
	{ id: 'emerald_flower', en: 'Emerald Flower', vi: 'Hoa Ngọc Lục Bảo' },
];

/** The flowers Many Flowers draws over two blocks, joined here into one. */
const TALL_BLOOMS: Bloom[] = [
	{ id: 'zinnia', en: 'Zinnia', vi: 'Cúc Ngũ Sắc' },
	{ id: 'cosmos', en: 'Cosmos', vi: 'Hoa Sao Nhái' },
	{ id: 'geranium', en: 'Geranium', vi: 'Phong Lữ Thảo' },
	{ id: 'oenothera', en: 'Oenothera', vi: 'Anh Thảo Đêm', effect: 'RUE' },
	{ id: 'autumn_asters', en: 'Autumn Asters', vi: 'Cúc Sao Mùa Thu' },
];

/** The ore petal blocks, which are plain cubes of their own sprite. */
const PETAL_BLOCKS: Array<[string, string, string]> = [
	['coal', 'Coal', 'Than'],
	['copper', 'Copper', 'Đồng'],
	['iron', 'Iron', 'Sắt'],
	['gold', 'Gold', 'Vàng'],
	['diamond', 'Diamond', 'Kim Cương'],
	['emerald', 'Emerald', 'Ngọc Lục Bảo'],
];

/** One cross filling the block, which is how vanilla draws a flower. */
function bloom(texture: string): Model {
	return model(
		{ cross: texture, particle: texture },
		cross('#cross', 8, 8, 0, 16, 14.4),
	);
}

/**
 * A two-block flower drawn as one block.
 *
 * The mod stacks a lower and an upper half; here the upper half is simply
 * lifted onto the same model, which keeps the flower one block to place, one
 * block to break and one block to store, and costs only that something may be
 * built through its head.
 */
function tallBloom(id: string): Model {
	return model(
		{ lower: `many_flowers:block/${id}_bottom`, upper: `many_flowers:block/${id}_top`, particle: `many_flowers:block/${id}_bottom` },
		[
			...cross('#lower', 8, 8, 0, 16, 14.4),
			...cross('#upper', 8, 8, 16, 16, 14.4),
		],
	);
}

/** A little carved pumpkin on a stem, lit or dark. */
function jackFlower(face: string): Model {
	const head: Element = {
		from: [5, 7, 5],
		to: [11, 13, 11],
		faces: {
			north: { uv: [0, 0, 16, 16], texture: '#face' },
			east: { uv: [0, 0, 16, 16], texture: '#gourd' },
			south: { uv: [0, 0, 16, 16], texture: '#gourd' },
			west: { uv: [0, 0, 16, 16], texture: '#gourd' },
			up: { uv: [0, 0, 16, 16], texture: '#top' },
			down: { uv: [0, 0, 16, 16], texture: '#top' },
		},
	};

	return model(
		{
			face: `minecraft:block/${face}`,
			gourd: 'minecraft:block/pumpkin_side',
			top: 'minecraft:block/pumpkin_top',
			stem: 'minecraft:block/bamboo_stalk',
			particle: 'minecraft:block/pumpkin_side',
		},
		// a narrow strip of a bamboo stalk: a whole vine sheet under the head
		// reads as a bush the pumpkin happens to be sitting on
		[...cross('#stem', 8, 8, 0, 8, 4, [6, 0, 10, 16]), head],
	);
}

const MANY_FLOWERS: Flora[] = [
	...BLOOMS.map((entry): Flora => ({
		id: entry.id,
		en: entry.en,
		vi: entry.vi,
		backing: 'plant',
		models: { [entry.id]: bloom(`many_flowers:block/${entry.id}`) },
		icon: `many_flowers:block/${entry.id}`,
		effect: entry.effect,
		hardness: 0.0,
		tool: 'hoe',
		sounds: 'GRASS',
	})),

	// a two-block flower's face is its upper half, which is also the sprite
	// vanilla hands you for a lilac or a peony
	...TALL_BLOOMS.map((entry): Flora => ({
		id: entry.id,
		en: entry.en,
		vi: entry.vi,
		backing: 'plant',
		models: { [entry.id]: tallBloom(entry.id) },
		icon: `many_flowers:block/${entry.id}_top`,
		effect: entry.effect,
		hardness: 0.0,
		tool: 'hoe',
		sounds: 'GRASS',
	})),

	// the one flower that answers to the sky: dark by day, a lantern by night.
	// Both of Many Flowers' sprites for it are corrupt in every build it ships
	// (64x64 of noise), so this one is composed here like the vine and the bush
	{
		id: 'jack_flower',
		en: "Jack-o'-Flower",
		vi: 'Hoa Đèn Lồng',
		backing: 'plant',
		models: {
			jack_flower: jackFlower('jack_o_lantern'),
			jack_flower_off: jackFlower('carved_pumpkin'),
		},
		nightLight: 15,
		directional: true,
		hardness: 0.0,
		tool: 'hoe',
		sounds: 'GRASS',
	},

	...PETAL_BLOCKS.map(([slug, en, vi]): Flora => ({
		id: `${slug}_petal_block`,
		en: `Block of ${en} Petals`,
		vi: `Khối Cánh Hoa ${vi}`,
		backing: 'cube',
		models: {
			[`${slug}_petal_block`]: model(
				{ all: `many_flowers:block/${slug}_petal_block`, particle: `many_flowers:block/${slug}_petal_block` },
				[box([0, 0, 0], [16, 16, 16], '#all', true)],
			),
		},
		hardness: 3.0,
		tool: 'pickaxe',
		sounds: 'STONE',
	})),
];

/** Every flora block, in the order the generated table registers them. */
export const FLORA: Flora[] = [
	...FLOWERING_STONE,
	...FLOWERING_VINES,
	...FLOWER_BUSHES,
	...MANY_FLOWERS,
];
