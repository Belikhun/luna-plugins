// The crop catalog: the Stardew Valley crop roster, grown here as Nova blocks.
//
// What is taken from the game is its *data* - which crops exist, what season
// each belongs to, how many days each stage lasts, whether it keeps producing
// after the first harvest, what it sells for. None of its art is copied or
// traced: every sprite below is drawn from scratch out of a handful of plant
// silhouettes (a root's leaf fan, a rosette, a fruiting stalk, a trellis vine,
// a grain head, a bloom, a berry bush, a fruit lying on the ground), coloured
// per crop. That is the same bargain flora.ts struck with Floral Enchantment:
// the idea travels, the pixels are ours.
//
// A crop is one block with an `age` property counting Stardew *days*, not
// frames: parsnip's four days are four ages, ancient fruit's twenty-eight are
// twenty-eight, and the stage table says which sprite each age draws. That is
// what lets one row here describe both the picture and the pace, and it is why
// a regrowing crop can simply be wound back to `mature - regrow` when picked.
//
// Crops vanilla already grows (wheat, potato, carrot, beetroot, melon,
// pumpkin) are left out, and so are the three Stardew flowers the flora
// catalog already ships a vanilla equivalent of (poppy, tulip, sunflower).

import { CLEAR, type Bitmap, type Rgba, bitmap, disc, encodePng, put, rect, rgb, tint } from './pixels';
import type { Model } from './compose';

/** The four seasons a crop can be planted in. */
export type Season = 'spring' | 'summer' | 'fall' | 'winter';

/**
 * The silhouette a crop is drawn as.
 *
 * Eight shapes cover the whole roster, because what a growing plant looks like
 * from six blocks away is its outline, not its species: a fan of leaves over a
 * buried root, a rosette closing around a head, a stalk hung with fruit, a vine
 * up a trellis, thin stems topped with seed heads, a single bloom, a low berry
 * bush, or something big lying on the ground.
 */
export type Form = 'root' | 'leafy' | 'stalk' | 'vine' | 'grain' | 'bloom' | 'bush' | 'ground';

/** The shape the harvested item is drawn as. */
export type Produce =
	| 'root'
	| 'bulb'
	| 'head'
	| 'fruit'
	| 'long'
	| 'cob'
	| 'sheaf'
	| 'berries'
	| 'bloom'
	| 'leaves'
	| 'melon'
	| 'star'
	| 'bunch';

/** The colours a crop is drawn in; every sprite it owns comes out of these. */
export interface Palette {
	/** Stems and stalks. */
	stem: string;
	/** The main leaf colour, with its lit side derived from it. */
	leaf: string;
	/** The ripe part: the fruit, the head, the bloom, the grain. */
	fruit: string;
	/** The ripe part's lit side, and the colour its highlight is drawn in. */
	fruitLight: string;
}

export interface Crop {
	/** Slug: the block is `<id>_crop`, the seed `<id>_seeds`, the produce `<id>`. */
	id: string;
	/** The harvested item's name, which the block and the seeds are named after. */
	en: string;
	vi: string;
	/** Set when the seed packet is not simply "<en> Seeds". */
	seedEn?: string;
	seedVi?: string;
	seasons: Season[];
	/**
	 * How many days each growth stage lasts, straight off the wiki. The sum is
	 * the crop's maturity in days, and the length is how many sprites it has.
	 */
	stages: number[];
	/** Days back to the next harvest, for a crop that keeps producing. */
	regrow?: number;
	/** How many items one harvest gives; one unless the wiki says otherwise. */
	count?: number;
	/** The chance of one extra item on top of that, as the wiki states it. */
	extra?: number;
	/** The Stardew sell price, which only the tooltip uses. */
	price: number;
	/**
	 * The game's energy value for a normal-quality item, or 0 for the crops it
	 * calls inedible. Nutrition and saturation are derived from it in one place
	 * rather than guessed per crop.
	 */
	energy: number;
	/** Climbs a trellis: solid to walk into, the way the game's three are. */
	trellis?: boolean;
	form: Form;
	produce: Produce;
	palette: Palette;
}

/**
 * The roster, in the wiki's own order: spring, summer, fall, winter, then the
 * crops the game files under no season of its own.
 */
export const CROPS: Crop[] = [
	{
		id: 'blue_jazz',
		en: 'Blue Jazz',
		vi: 'Hoa Jazz Xanh',
		seedEn: 'Jazz Seeds',
		seedVi: 'Hạt Hoa Jazz',
		seasons: ['spring'],
		stages: [1, 2, 2, 2],
		price: 50,
		energy: 45,
		form: 'bloom',
		produce: 'bloom',
		palette: { stem: '#4f7a33', leaf: '#63964a', fruit: '#4a7fd4', fruitLight: '#8fc0f2' },
	},
	{
		id: 'cauliflower',
		en: 'Cauliflower',
		vi: 'Súp Lơ Trắng',
		seasons: ['spring'],
		stages: [1, 2, 4, 4, 1],
		price: 175,
		energy: 75,
		form: 'leafy',
		produce: 'head',
		palette: { stem: '#587f38', leaf: '#6d9c48', fruit: '#ece7cf', fruitLight: '#faf7e8' },
	},
	{
		id: 'coffee_bean',
		en: 'Coffee Bean',
		vi: 'Hạt Cà Phê',
		seedEn: 'Coffee Starter',
		seedVi: 'Cây Giống Cà Phê',
		seasons: ['spring', 'summer'],
		stages: [1, 2, 2, 3, 2],
		regrow: 2,
		count: 4,
		extra: 0.02,
		price: 15,
		energy: 0,
		form: 'bush',
		produce: 'berries',
		palette: { stem: '#4b6b33', leaf: '#3f7a3a', fruit: '#c0392b', fruitLight: '#e2634f' },
	},
	{
		id: 'garlic',
		en: 'Garlic',
		vi: 'Tỏi',
		seasons: ['spring'],
		stages: [1, 1, 1, 1],
		price: 60,
		energy: 20,
		form: 'root',
		produce: 'bulb',
		palette: { stem: '#5c8a3a', leaf: '#74a850', fruit: '#eee6da', fruitLight: '#fbf6ee' },
	},
	{
		id: 'green_bean',
		en: 'Green Bean',
		vi: 'Đậu Que',
		seedEn: 'Bean Starter',
		seedVi: 'Cây Giống Đậu Que',
		seasons: ['spring'],
		stages: [1, 1, 1, 3, 4],
		regrow: 3,
		price: 40,
		energy: 25,
		trellis: true,
		form: 'vine',
		produce: 'bunch',
		palette: { stem: '#5a7f34', leaf: '#6fa044', fruit: '#89c64f', fruitLight: '#b4e078' },
	},
	{
		id: 'kale',
		en: 'Kale',
		vi: 'Cải Xoăn',
		seasons: ['spring'],
		stages: [1, 2, 2, 1],
		price: 110,
		energy: 50,
		form: 'leafy',
		produce: 'head',
		palette: { stem: '#3f6b34', leaf: '#2f6b3c', fruit: '#7fc45e', fruitLight: '#aee08a' },
	},
	{
		id: 'parsnip',
		en: 'Parsnip',
		vi: 'Củ Cải Vàng',
		seasons: ['spring'],
		stages: [1, 1, 1, 1],
		price: 35,
		energy: 25,
		form: 'root',
		produce: 'root',
		palette: { stem: '#5e8c39', leaf: '#76ab4d', fruit: '#e8d59a', fruitLight: '#f6ecc4' },
	},
	{
		id: 'rhubarb',
		en: 'Rhubarb',
		vi: 'Đại Hoàng',
		seasons: ['spring'],
		stages: [2, 2, 2, 3, 4],
		price: 220,
		energy: 0,
		form: 'leafy',
		produce: 'long',
		palette: { stem: '#c0392b', leaf: '#4f8a3c', fruit: '#d8453a', fruitLight: '#ef7361' },
	},
	{
		// not `strawberry`: the birthday set already registered a strawberry
		// snack under that id, and an item id is forever once somebody is
		// carrying one. Renaming the whole crop rather than just its produce
		// keeps the block, the packet and the item on one slug, which is what
		// every tool that walks this roster assumes.
		id: 'garden_strawberry',
		en: 'Garden Strawberry',
		vi: 'Dâu Tây Vườn',
		seasons: ['spring'],
		stages: [1, 1, 2, 2, 2],
		regrow: 4,
		extra: 0.02,
		price: 120,
		energy: 50,
		form: 'bush',
		produce: 'berries',
		palette: { stem: '#4f7f36', leaf: '#63a044', fruit: '#e03a49', fruitLight: '#f4707a' },
	},
	{
		id: 'unmilled_rice',
		en: 'Unmilled Rice',
		vi: 'Thóc',
		seedEn: 'Rice Shoot',
		seedVi: 'Mạ Lúa',
		seasons: ['spring'],
		stages: [1, 2, 2, 3],
		extra: 0.1,
		price: 30,
		energy: 3,
		form: 'grain',
		produce: 'sheaf',
		palette: { stem: '#8aa84e', leaf: '#7fa746', fruit: '#d8c880', fruitLight: '#f0e4ae' },
	},
	{
		id: 'blueberry',
		en: 'Blueberry',
		vi: 'Việt Quất',
		seasons: ['summer'],
		stages: [1, 3, 3, 4, 2],
		regrow: 4,
		count: 3,
		extra: 0.02,
		price: 50,
		energy: 25,
		form: 'bush',
		produce: 'berries',
		palette: { stem: '#4a6f33', leaf: '#5d9040', fruit: '#3f4fa8', fruitLight: '#6f7fd4' },
	},
	{
		id: 'corn',
		en: 'Corn',
		vi: 'Ngô',
		seasons: ['summer', 'fall'],
		stages: [2, 3, 3, 3, 3],
		regrow: 4,
		price: 50,
		energy: 25,
		form: 'grain',
		produce: 'cob',
		palette: { stem: '#6d9a3e', leaf: '#7fae4a', fruit: '#f0cf4e', fruitLight: '#fbe98c' },
	},
	{
		id: 'hops',
		en: 'Hops',
		vi: 'Hoa Bia',
		seedEn: 'Hops Starter',
		seedVi: 'Cây Giống Hoa Bia',
		seasons: ['summer'],
		stages: [1, 1, 2, 3, 4],
		regrow: 1,
		price: 25,
		energy: 45,
		trellis: true,
		form: 'vine',
		produce: 'bunch',
		palette: { stem: '#5e7f33', leaf: '#74a044', fruit: '#a8c451', fruitLight: '#cfe487' },
	},
	{
		id: 'hot_pepper',
		en: 'Hot Pepper',
		vi: 'Ớt Cay',
		seedEn: 'Pepper Seeds',
		seedVi: 'Hạt Ớt',
		seasons: ['summer'],
		stages: [1, 1, 1, 1, 1],
		regrow: 3,
		extra: 0.03,
		price: 40,
		energy: 13,
		form: 'stalk',
		produce: 'long',
		palette: { stem: '#4f7f36', leaf: '#63a044', fruit: '#d03024', fruitLight: '#f05a45' },
	},
	{
		id: 'radish',
		en: 'Radish',
		vi: 'Củ Cải Đỏ',
		seasons: ['summer'],
		stages: [2, 1, 2, 1],
		price: 90,
		energy: 45,
		form: 'root',
		produce: 'root',
		palette: { stem: '#5c8a3a', leaf: '#74a850', fruit: '#d8374a', fruitLight: '#f06c78' },
	},
	{
		id: 'red_cabbage',
		en: 'Red Cabbage',
		vi: 'Bắp Cải Tím',
		seasons: ['summer'],
		stages: [2, 1, 2, 2, 2],
		price: 260,
		energy: 75,
		form: 'leafy',
		produce: 'head',
		palette: { stem: '#4f7a38', leaf: '#639348', fruit: '#8e3f9e', fruitLight: '#b96cc6' },
	},
	{
		id: 'starfruit',
		en: 'Starfruit',
		vi: 'Khế',
		seasons: ['summer'],
		stages: [2, 3, 2, 3, 3],
		price: 750,
		energy: 125,
		form: 'stalk',
		produce: 'star',
		palette: { stem: '#4f7f36', leaf: '#63a044', fruit: '#f2d43c', fruitLight: '#fbee8a' },
	},
	{
		id: 'summer_spangle',
		en: 'Summer Spangle',
		vi: 'Hoa Nắng Hạ',
		seedEn: 'Spangle Seeds',
		seedVi: 'Hạt Hoa Nắng Hạ',
		seasons: ['summer'],
		stages: [1, 2, 3, 2],
		price: 90,
		energy: 45,
		form: 'bloom',
		produce: 'bloom',
		palette: { stem: '#4f7a33', leaf: '#63964a', fruit: '#f08a2e', fruitLight: '#fbc06a' },
	},
	{
		id: 'summer_squash',
		en: 'Summer Squash',
		vi: 'Bí Ngòi',
		seasons: ['summer'],
		stages: [1, 1, 1, 2, 1],
		regrow: 3,
		price: 45,
		energy: 63,
		form: 'ground',
		produce: 'long',
		palette: { stem: '#5a8034', leaf: '#6fa044', fruit: '#f0c23c', fruitLight: '#fbdd84' },
	},
	{
		id: 'tomato',
		en: 'Tomato',
		vi: 'Cà Chua',
		seasons: ['summer'],
		stages: [2, 2, 2, 2, 3],
		regrow: 4,
		extra: 0.05,
		price: 60,
		energy: 20,
		form: 'stalk',
		produce: 'fruit',
		palette: { stem: '#4f7f36', leaf: '#63a044', fruit: '#d8382c', fruitLight: '#f0705a' },
	},
	{
		id: 'amaranth',
		en: 'Amaranth',
		vi: 'Rau Dền',
		seasons: ['fall'],
		stages: [1, 2, 2, 2],
		price: 150,
		energy: 50,
		form: 'grain',
		produce: 'sheaf',
		palette: { stem: '#7a4f6b', leaf: '#6f8a45', fruit: '#9e3470', fruitLight: '#c8629a' },
	},
	{
		id: 'artichoke',
		en: 'Artichoke',
		vi: 'A-ti-sô',
		seasons: ['fall'],
		stages: [2, 2, 1, 2, 1],
		price: 160,
		energy: 30,
		form: 'leafy',
		produce: 'head',
		palette: { stem: '#6f8a54', leaf: '#7fa060', fruit: '#6b5e9e', fruitLight: '#9488c8' },
	},
	{
		id: 'bok_choy',
		en: 'Bok Choy',
		vi: 'Cải Thìa',
		seasons: ['fall'],
		stages: [1, 1, 1, 1],
		price: 80,
		energy: 25,
		form: 'leafy',
		produce: 'head',
		palette: { stem: '#e4ecd0', leaf: '#4f8a3c', fruit: '#5e9a44', fruitLight: '#e8f0d4' },
	},
	{
		id: 'broccoli',
		en: 'Broccoli',
		vi: 'Súp Lơ Xanh',
		seasons: ['fall'],
		stages: [2, 2, 2, 2],
		regrow: 4,
		price: 70,
		energy: 63,
		form: 'leafy',
		produce: 'head',
		palette: { stem: '#5a8a44', leaf: '#6f9c4e', fruit: '#2b5e3a', fruitLight: '#4a8a52' },
	},
	{
		id: 'cranberries',
		en: 'Cranberries',
		vi: 'Nam Việt Quất',
		seedEn: 'Cranberry Seeds',
		seedVi: 'Hạt Nam Việt Quất',
		seasons: ['fall'],
		stages: [1, 2, 1, 1, 2],
		regrow: 5,
		count: 2,
		extra: 0.1,
		price: 75,
		energy: 38,
		form: 'bush',
		produce: 'berries',
		palette: { stem: '#4a6b33', leaf: '#5e8a40', fruit: '#b02434', fruitLight: '#da5461' },
	},
	{
		id: 'eggplant',
		en: 'Eggplant',
		vi: 'Cà Tím',
		seasons: ['fall'],
		stages: [1, 1, 1, 1, 1],
		regrow: 5,
		extra: 0.002,
		price: 60,
		energy: 20,
		form: 'stalk',
		produce: 'long',
		palette: { stem: '#4f7f36', leaf: '#63a044', fruit: '#5e3a86', fruitLight: '#8a63b4' },
	},
	{
		id: 'fairy_rose',
		en: 'Fairy Rose',
		vi: 'Hồng Tiên',
		seedEn: 'Fairy Seeds',
		seedVi: 'Hạt Hồng Tiên',
		seasons: ['fall'],
		stages: [1, 4, 4, 3],
		price: 290,
		energy: 45,
		form: 'bloom',
		produce: 'bloom',
		palette: { stem: '#4f7a33', leaf: '#63964a', fruit: '#e07ac4', fruitLight: '#f5b2e0' },
	},
	{
		id: 'grape',
		en: 'Grape',
		vi: 'Nho',
		seedEn: 'Grape Starter',
		seedVi: 'Cây Giống Nho',
		seasons: ['fall'],
		stages: [1, 1, 2, 3, 3],
		regrow: 3,
		price: 80,
		energy: 38,
		trellis: true,
		form: 'vine',
		produce: 'bunch',
		palette: { stem: '#6b5a3a', leaf: '#5e8a3c', fruit: '#7a3a9e', fruitLight: '#a869c8' },
	},
	{
		id: 'yam',
		en: 'Yam',
		vi: 'Khoai Mỡ',
		seasons: ['fall'],
		stages: [1, 3, 3, 3],
		price: 160,
		energy: 45,
		form: 'root',
		produce: 'root',
		palette: { stem: '#5c8a3a', leaf: '#74a850', fruit: '#a8562e', fruitLight: '#cd7f4f' },
	},
	{
		id: 'powdermelon',
		en: 'Powdermelon',
		vi: 'Dưa Tuyết',
		seasons: ['winter'],
		stages: [1, 2, 1, 2, 1],
		price: 60,
		energy: 63,
		form: 'ground',
		produce: 'melon',
		palette: { stem: '#5a8034', leaf: '#6fa044', fruit: '#9ec8d8', fruitLight: '#dceef4' },
	},
	{
		id: 'ancient_fruit',
		en: 'Ancient Fruit',
		vi: 'Quả Cổ Đại',
		seedEn: 'Ancient Seeds',
		seedVi: 'Hạt Cổ Đại',
		seasons: ['spring', 'summer', 'fall'],
		stages: [2, 7, 7, 7, 5],
		regrow: 7,
		price: 550,
		energy: 0,
		form: 'ground',
		produce: 'fruit',
		palette: { stem: '#5a7f42', leaf: '#6f9450', fruit: '#8a4fb4', fruitLight: '#b47fd8' },
	},
	{
		id: 'cactus_fruit',
		en: 'Cactus Fruit',
		vi: 'Quả Xương Rồng',
		seedEn: 'Cactus Seeds',
		seedVi: 'Hạt Xương Rồng',
		seasons: ['summer'],
		stages: [2, 2, 2, 3, 3],
		regrow: 3,
		price: 75,
		energy: 75,
		form: 'ground',
		produce: 'fruit',
		palette: { stem: '#4f7a3a', leaf: '#5e8a44', fruit: '#d0426a', fruitLight: '#f0788f' },
	},
	{
		id: 'pineapple',
		en: 'Pineapple',
		vi: 'Dứa',
		seasons: ['summer'],
		stages: [1, 3, 3, 4, 3],
		regrow: 7,
		price: 300,
		energy: 138,
		form: 'leafy',
		produce: 'head',
		palette: { stem: '#5a8a3c', leaf: '#6f9c48', fruit: '#e0a82e', fruitLight: '#f5cd6a' },
	},
	{
		id: 'sweet_gem_berry',
		en: 'Sweet Gem Berry',
		vi: 'Quả Ngọc Ngọt',
		seedEn: 'Rare Seed',
		seedVi: 'Hạt Quý Hiếm',
		seasons: ['fall'],
		stages: [2, 4, 6, 6, 6],
		price: 3000,
		energy: 0,
		form: 'ground',
		produce: 'fruit',
		palette: { stem: '#5a7f42', leaf: '#6f9450', fruit: '#d84f9e', fruitLight: '#f592c8' },
	},
	{
		id: 'taro_root',
		en: 'Taro Root',
		vi: 'Khoai Môn',
		seedEn: 'Taro Tuber',
		seedVi: 'Củ Giống Khoai Môn',
		seasons: ['summer'],
		stages: [1, 2, 3, 4],
		price: 100,
		energy: 38,
		form: 'root',
		produce: 'root',
		palette: { stem: '#5c8a3a', leaf: '#4f8a52', fruit: '#9e7f6b', fruitLight: '#c0a48e' },
	},
	{
		id: 'tea_leaves',
		en: 'Tea Leaves',
		vi: 'Lá Trà',
		seedEn: 'Tea Sapling',
		seedVi: 'Cây Giống Trà',
		seasons: ['spring', 'summer', 'fall'],
		stages: [10, 10],
		regrow: 1,
		price: 50,
		energy: 0,
		form: 'bush',
		produce: 'leaves',
		palette: { stem: '#5a4f33', leaf: '#3f7a42', fruit: '#a8d46a', fruitLight: '#cfeb9e' },
	},
];

// ---- the drawing ---------------------------------------------------------
//
// Every sprite is 16x16 and drawn bottom-up: row 15 is the soil line, row 0 is
// the top of the block. A stage is a number in [0, 1] saying how far along the
// plant is, and each form reads that as a height and a density; only the last
// stage of a crop's life draws the ripe part, which is what makes "is it
// ready" a thing you can see across a field.
//
// Two rules learned from the first cut, both about body. A leaf is an arc with
// a thick middle, not a diagonal run of single pixels: a field of one-pixel
// strokes reads as scratches on the dirt. And a ripe part sits *in* its plant,
// not above it, or the head of a cauliflower looks like it is hovering.
//
// Nothing anti-aliases and nothing dithers. A crop is looked at from a
// distance through a 16-pixel sprite, and a soft edge there is a smudge.

/** Deterministic hash in [0, 1): the same crop always grows the same shape. */
function jitter(seed: number, index: number): number {
	let h = seed ^ Math.imul(index + 17, 374761393);
	h = Math.imul(h ^ (h >>> 13), 1274126177);

	return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

function seedOf(id: string): number {
	let h = 2166136261;

	for (let i = 0; i < id.length; i++) {
		h = Math.imul(h ^ id.charCodeAt(i), 16777619);
	}

	return h >>> 0;
}

/** The working colours a crop draws with, already shaded. */
interface Ink {
	stem: Rgba;
	stemDark: Rgba;
	leaf: Rgba;
	leafLight: Rgba;
	leafDark: Rgba;
	fruit: Rgba;
	fruitLight: Rgba;
	fruitDark: Rgba;
}

function inkOf(palette: Palette): Ink {
	const leaf = rgb(palette.leaf);
	const fruit = rgb(palette.fruit);

	return {
		stem: rgb(palette.stem),
		stemDark: tint(rgb(palette.stem), 0.68),
		leaf,
		leafLight: tint(leaf, 1.3),
		leafDark: tint(leaf, 0.68),
		fruit,
		fruitLight: rgb(palette.fruitLight),
		fruitDark: tint(fruit, 0.66),
	};
}

/**
 * A stalk from the soil line up to `top`.
 *
 * `width` 2 is what anything meant to carry fruit gets: a one-pixel stem under
 * a tomato reads as the fruit hanging in mid-air.
 */
function stalk(image: Bitmap, x: number, top: number, ink: Ink, width = 1, lean = 0): void {
	const height = 15 - top;

	for (let i = 0; i <= height; i++) {
		const y = 15 - i;
		const offset = Math.round((lean * i * i) / Math.max(1, height * height));

		put(image, x + offset, y, ink.stem);

		if (width > 1) {
			put(image, x + offset + 1, y, i % 3 === 2 ? ink.stem : ink.stemDark);
		}
	}
}

/**
 * One leaf: an arc away from where it is rooted, thick through the middle and
 * tapering at both ends, with its upper edge caught by the light.
 *
 * `dx` is which way it goes and how far per step, `rise` how high it lifts
 * before its tip falls away again. A leaf drawn as a straight line looks like
 * a needle, which is why every form here asks for some rise.
 */
function leaf(
	image: Bitmap,
	x: number,
	y: number,
	dx: number,
	length: number,
	rise: number,
	ink: Ink,
	lit: boolean,
): void {
	const body = lit ? ink.leaf : ink.leafDark;
	const edge = lit ? ink.leafLight : ink.leaf;

	for (let step = 0; step <= length; step++) {
		const t = step / length;
		const px = Math.round(x + dx * step);

		// the arc peaks two thirds along and the tip only bends over, so the
		// leaf ends up in the air; a full sine brings it back to the soil and
		// the plant reads as a row of croquet hoops
		const py = Math.round(y - Math.sin(t * 0.75 * Math.PI) * rise + t * t * 1.5);

		// the blade is two pixels through its middle third and one at its ends
		const thick = t > 0.15 && t < 0.8 ? 2 : 1;

		put(image, px, py, edge);

		if (thick > 1) {
			put(image, px, py + 1, body);
		}
	}
}

/** A round fruit with its lit corner, from one pixel across up to five. */
function berry(image: Bitmap, cx: number, cy: number, radius: number, ink: Ink): void {
	if (radius <= 1) {
		put(image, cx, cy, ink.fruit);
		put(image, cx, cy - 1, ink.fruitLight);

		return;
	}

	disc(image, cx + 0.5, cy + 0.5, radius, ink.fruit);
	disc(image, cx, cy, radius - 0.9, ink.fruitLight);
	put(image, cx, cy + radius - 1, ink.fruitDark);
	put(image, cx + radius - 1, cy + radius - 1, ink.fruitDark);
}

/** The turned soil a young plant is still mostly showing. */
function crumbs(image: Bitmap, x: number, ink: Ink): void {
	put(image, x - 3, 15, ink.stemDark);
	put(image, x + 3, 15, ink.stemDark);
	put(image, x - 2, 15, ink.stemDark);
}

// ---- the eight forms ------------------------------------------------------

/**
 * A buried root: a fan of leaves, and at the end the crown breaking the soil.
 *
 * The crown is the whole signal, so it is drawn last and wide: the leaves of a
 * parsnip and the leaves of a radish are the same leaves, and what tells them
 * apart is the colour of the shoulder showing through them.
 */
function drawRoot(image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number): void {
	const height = Math.round(4 + p * 8);
	const count = 4 + Math.round(p * 3);

	for (let i = 0; i < count; i++) {
		const side = i % 2 === 0 ? -1 : 1;
		const spread = 0.6 + jitter(seed, i) * 0.7;
		const length = Math.max(3, Math.round(height * (0.7 + jitter(seed, i + 9) * 0.4)));

		leaf(image, 8, 14, side * spread, length, height * 0.8, ink, i % 2 === 0);
	}

	stalk(image, 8, 15 - Math.round(height * 0.5), ink);

	if (ripe) {
		// the shoulder, sitting in the soil rather than on it
		rect(image, 5, 13, 12, 16, ink.fruit);
		rect(image, 6, 13, 11, 15, ink.fruitLight);
		put(image, 5, 13, CLEAR);
		put(image, 11, 13, CLEAR);
		put(image, 6, 13, ink.fruit);
		put(image, 10, 13, ink.fruit);
		put(image, 5, 15, ink.fruitDark);
		put(image, 11, 15, ink.fruitDark);
		put(image, 8, 12, ink.stemDark);
	} else if (p < 0.3) {
		crumbs(image, 8, ink);
	}
}

/** A rosette closing around its head: broad leaves low and wide. */
function drawLeafy(image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number): void {
	const height = Math.round(4 + p * 8);
	const reach = 1.0 + p * 0.6;

	// the outer whorl first, so the inner leaves overlap it the way they grow
	for (let i = 0; i < 4; i++) {
		const side = i < 2 ? -1 : 1;
		const spread = reach * (0.9 + jitter(seed, i) * 0.5);
		const length = Math.max(3, Math.round(4 + p * 4));

		leaf(image, 8, 15 - Math.round(jitter(seed, i + 5) * 2), side * spread, length, 2 + p * 2, ink, i % 2 === 0);
	}

	for (let i = 0; i < 3; i++) {
		const side = i === 0 ? -1 : i === 1 ? 1 : 0;
		const length = Math.max(3, Math.round(height * 0.7));

		if (side === 0) {
			stalk(image, 8, 15 - height, ink);
			continue;
		}

		leaf(image, 8, 13, side * 0.7, length, height * 0.7, ink, side > 0);
	}

	if (ripe) {
		// the head sits low, with the inner leaves drawn again over its
		// shoulders: perched on top of the whorl it looks like a ball somebody
		// left on the plant
		const cy = 15 - Math.round(height * 0.35);

		berry(image, 8, cy, 3, ink);
		leaf(image, 8, cy + 2, -0.9, 4, 4, ink, false);
		leaf(image, 8, cy + 2, 0.9, 4, 4, ink, true);
	} else if (p < 0.3) {
		crumbs(image, 8, ink);
	}
}

/** A fruiting stalk: one strong stem, paired leaves, fruit hung off it. */
function drawStalk(image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number): void {
	const height = Math.round(5 + p * 10);
	const top = 15 - height;

	stalk(image, 8, top, ink, 2);

	const pairs = 2 + Math.round(p * 2);

	for (let i = 0; i < pairs; i++) {
		const y = 15 - Math.round(((i + 1) * height) / (pairs + 1));
		const length = 3 + Math.round(jitter(seed, i) * 2);

		leaf(image, 9, y, 1, length, 2, ink, true);
		leaf(image, 7, y + 1, -1, length, 2, ink, false);
	}

	if (ripe) {
		berry(image, 5, top + 4, 2, ink);
		berry(image, 11, top + 6, 2, ink);
		berry(image, 6, top + 9, 2, ink);

		if (height > 12) {
			berry(image, 11, top + 11, 2, ink);
		}
	} else if (p < 0.3) {
		crumbs(image, 8, ink);
	}
}

/**
 * A trellis climber.
 *
 * The lattice is drawn into the sprite rather than modelled, because the plant
 * and the frame it is tied to are one thing to look at and one thing to break;
 * what makes the trellis real is the block's collision, not its geometry.
 */
function drawVine(image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number): void {
	const height = Math.round(6 + p * 9);
	const top = 15 - height;
	const frame: Rgba = [124, 96, 62, 255];
	const frameDark: Rgba = [90, 68, 44, 255];

	for (let y = top; y <= 15; y++) {
		put(image, 3, y, y % 4 === 0 ? frameDark : frame);
		put(image, 12, y, y % 4 === 2 ? frameDark : frame);
	}

	for (let i = 1; i <= 3; i++) {
		const y = 15 - Math.round((i * height) / 4);

		if (y < top) {
			continue;
		}

		for (let x = 3; x <= 12; x++) {
			put(image, x, y, frameDark);
		}
	}

	// the vine weaves between the posts, a leaf at every turn
	for (let i = 0; i < 5; i++) {
		const y = 15 - Math.round(((i + 1) * height) / 6);

		if (y < top) {
			continue;
		}

		const side = i % 2 === 0 ? -1 : 1;

		leaf(image, side < 0 ? 4 : 11, y, side * 0.9, 3, 2, ink, i % 2 === 0);
		put(image, 7, y, ink.leaf);
		put(image, 8, y - 1, ink.leafLight);
		put(image, 8, y, ink.leafDark);
	}

	if (ripe) {
		// a cluster hanging off the frame, drawn as a narrowing stack
		const cy = top + Math.max(2, Math.round(height * 0.3));

		for (let row = 0; row < 3; row++) {
			const half = 2 - row;

			for (let x = 8 - half; x <= 8 + half; x++) {
				put(image, x, cy + row * 2, ink.fruit);
				put(image, x, cy + row * 2 + 1, ink.fruitDark);
			}
		}

		put(image, 7, cy, ink.fruitLight);
		put(image, 8, cy + 1, ink.fruitLight);
	}
}

/** Thin stems with seed heads at the tips: grain, and everything grain-shaped. */
function drawGrain(image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number): void {
	const height = Math.round(5 + p * 10);
	const stalks = 3 + Math.round(p * 2);

	for (let i = 0; i < stalks; i++) {
		const x = 5 + Math.round((i * 6) / Math.max(1, stalks - 1));
		const lean = i % 2 === 0 ? 1 : -1;
		const tall = Math.max(4, height - Math.round(jitter(seed, i) * 3));
		const top = 15 - tall;

		stalk(image, x, top, ink, 1, lean);

		const tip = x + lean;

		// two leaves per stem, arching away: a bare stem is a fence post
		leaf(image, x, top + Math.round(tall * 0.35), lean * 0.9, 3, 2, ink, i % 2 === 0);
		leaf(image, x, top + Math.round(tall * 0.7), -lean * 0.9, 3, 2, ink, i % 2 === 1);

		if (!ripe) {
			continue;
		}

		// the head: a short fat spike on the stem's own tip
		for (let row = 0; row < 5; row++) {
			put(image, tip, top + row - 2, row % 2 === 0 ? ink.fruit : ink.fruitLight);
			put(image, tip + lean, top + row - 1, ink.fruitDark);
		}
	}

	if (!ripe && p < 0.3) {
		crumbs(image, 8, ink);
	}
}

/** A single stem carrying a bloom, which is the whole point of a flower. */
function drawBloom(image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number): void {
	const height = Math.round(5 + p * 8);
	const top = 15 - height;

	stalk(image, 8, top, ink, 1);
	leaf(image, 9, 15 - Math.round(height * 0.35), 1, 4, 2, ink, true);
	leaf(image, 7, 15 - Math.round(height * 0.6), -1, 4, 2, ink, false);

	if (!ripe) {
		if (p > 0.6) {
			// the bud, in the leaf's own colour: a flower that has not opened
			put(image, 8, top, ink.leafLight);
			put(image, 8, top - 1, ink.leaf);
			put(image, 7, top, ink.leafDark);
		}

		if (p < 0.3) {
			crumbs(image, 8, ink);
		}

		return;
	}

	// The bloom: one rosette with its corners taken off and the petals told
	// apart by the creases between them, not six petals drawn separately.
	// Separate petals at six pixels across do not touch, and what came out was
	// a handful of unrelated dots sitting where a flower should be.
	const cy = top + 1;

	disc(image, 8.5, cy + 0.5, 2.9, ink.fruit);
	disc(image, 8.5, cy + 0.5, 2.0, ink.fruitLight);

	for (const [px, py] of [[6, cy - 2], [11, cy - 2], [6, cy + 3], [11, cy + 3]] as Array<[number, number]>) {
		put(image, px, py, CLEAR);
	}

	// the creases, which are what the eye reads as the gaps between petals
	for (const [px, py] of [[7, cy - 1], [10, cy - 1], [7, cy + 2], [10, cy + 2]] as Array<[number, number]>) {
		put(image, px, py, ink.fruitDark);
	}

	put(image, 8, cy, ink.fruitDark);
	put(image, 9, cy, ink.fruitDark);
	put(image, 8, cy + 1, ink.fruitDark);
	put(image, 9, cy + 1, ink.fruitLight);
}

/** A low dense bush, berries dotted through it once it is carrying. */
function drawBush(image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number): void {
	const height = Math.round(4 + p * 9);
	const top = 15 - height;
	const half = 2 + Math.round(p * 4);

	for (let y = top; y <= 15; y++) {
		const t = (15 - y) / Math.max(1, height);
		const width = Math.round(half * (1 - t * t * 0.55));

		for (let x = 8 - width; x <= 8 + width; x++) {
			const n = jitter(seed, x * 31 + y);

			if (n < 0.14) {
				continue;
			}

			put(image, x, y, n > 0.74 ? ink.leafLight : n > 0.34 ? ink.leaf : ink.leafDark);
		}
	}

	if (ripe) {
		const spots: Array<[number, number]> = [
			[6, top + 3],
			[10, top + 5],
			[8, top + 8],
			[5, top + 9],
			[11, top + 10],
		];

		for (const [x, y] of spots) {
			if (y > 14) {
				continue;
			}

			berry(image, x, y, 1.4, ink);
		}
	} else if (p < 0.3) {
		crumbs(image, 8, ink);
	}
}

/** A sprawling plant with something heavy lying in the middle of it. */
function drawGround(image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number): void {
	const reach = 3 + Math.round(p * 4);

	// the runners: two low arcs either side, which is what a melon vine is
	for (let side = -1; side <= 1; side += 2) {
		for (let i = 0; i <= reach; i++) {
			const x = 8 + side * i;
			const y = 15 - Math.round(Math.sin((i / Math.max(1, reach)) * Math.PI) * 2);

			put(image, x, y, i % 3 === 0 ? ink.stemDark : ink.stem);

			if (i % 2 === 1) {
				leaf(image, x, y - 1, side * 0.8, 3, 2, ink, i % 4 === 1);
			}
		}
	}

	if (!ripe) {
		if (p > 0.5) {
			// the flower every gourd sets before it swells
			put(image, 8, 12, ink.fruitLight);
			put(image, 7, 13, ink.fruit);
			put(image, 9, 13, ink.fruit);
			put(image, 8, 13, ink.fruitDark);
		}

		return;
	}

	// the fruit: a squat dome on the soil, with the stalk still on it
	disc(image, 8, 14, 4.5, ink.fruit);
	disc(image, 7.5, 13.5, 3.2, ink.fruitLight);
	rect(image, 4, 15, 13, 16, ink.fruitDark);
	put(image, 8, 9, ink.stemDark);
	put(image, 9, 8, ink.stem);
}

/** The withered crop every dead plant turns into, whatever it was. */
function deadSprite(): Bitmap {
	const image = bitmap(16, 16);
	const straw: Rgba = [150, 128, 84, 255];
	const strawDark: Rgba = [112, 93, 58, 255];
	const seed = seedOf('dead_crop');

	for (let i = 0; i < 5; i++) {
		const x = 4 + i * 2;
		const height = 4 + Math.round(jitter(seed, i) * 5);
		const lean = i % 2 === 0 ? 1 : -1;

		for (let j = 0; j < height; j++) {
			const y = 15 - j;
			const offset = Math.round((lean * j) / 3);

			put(image, x + offset, y, j % 3 === 2 ? strawDark : straw);
		}

		// the broken tip every dead stem folds over at
		put(image, x + lean * 2, 15 - height + 1, strawDark);
		put(image, x + lean * 3, 15 - height + 2, strawDark);
	}

	return image;
}

// ---- the items ------------------------------------------------------------
//
// Two pictures per crop. The harvested item is drawn as one of a dozen shapes
// - a root, a bulb, a head, a round fruit, a long one, a cob, a sheaf, a
// handful of berries, a bloom, leaves, a melon, a star, a bunch - coloured
// from the same palette its plant grew in, so the thing in the slot and the
// thing in the field are visibly the same crop.
//
// The seed is a paper packet, one shape for every crop, with the crop's own
// colour showing through the window. That is deliberate: thirty-six hand-drawn
// seed sprites would all read as "small brown speck" at 16 pixels, and the
// packet is what the game itself hands you.

const PAPER: Rgba = [214, 194, 152, 255];
const PAPER_DARK: Rgba = [176, 154, 112, 255];
const PAPER_LIGHT: Rgba = [236, 220, 186, 255];
const OUTLINE: Rgba = [58, 46, 34, 255];

/** The seed packet: a paper sachet with the crop's colour in its window. */
function seedSprite(crop: Crop): Bitmap {
	const image = bitmap(16, 16);
	const ink = inkOf(crop.palette);

	rect(image, 3, 2, 13, 15, PAPER);
	rect(image, 3, 2, 13, 4, PAPER_DARK);
	rect(image, 4, 3, 12, 4, PAPER_LIGHT);

	// the folded top, which is what makes it a packet rather than a card
	for (let x = 3; x < 13; x++) {
		put(image, x, 2, x % 2 === 0 ? PAPER_DARK : PAPER_LIGHT);
	}

	// the window: the crop's own colour, with a couple of seeds showing in it
	rect(image, 5, 6, 11, 12, ink.fruit);
	rect(image, 5, 6, 11, 7, ink.fruitLight);
	put(image, 6, 9, ink.fruitDark);
	put(image, 9, 8, ink.fruitDark);
	put(image, 8, 10, ink.fruitDark);

	for (let y = 2; y < 15; y++) {
		put(image, 2, y, OUTLINE);
		put(image, 13, y, OUTLINE);
	}

	for (let x = 2; x < 14; x++) {
		put(image, x, 15, OUTLINE);
		put(image, x, 1, OUTLINE);
	}

	return image;
}

/** A tapered root hanging from a leafy crown. */
function rootItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	for (let i = 0; i < 10; i++) {
		const y = 5 + i;
		const half = Math.max(0, 3 - Math.floor(i / 3));

		for (let x = 8 - half; x <= 8 + half; x++) {
			put(image, x, y, x === 8 - half ? ink.fruitDark : x > 8 ? ink.fruit : ink.fruitLight);
		}
	}

	leaf(image, 8, 5, 1, 4, 2, ink, true);
	leaf(image, 7, 5, -1, 4, 2, ink, false);
	put(image, 8, 4, ink.leafLight);

	return image;
}

/** A bulb: a fat dome scored into cloves, with the dry stalk on top. */
function bulbItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	disc(image, 8, 10, 4.5, ink.fruit);
	disc(image, 8, 10, 3.5, ink.fruitLight);

	for (let y = 7; y < 14; y++) {
		put(image, 6, y, ink.fruitDark);
		put(image, 10, y, ink.fruitDark);
	}

	put(image, 8, 4, ink.leaf);
	put(image, 8, 5, ink.leaf);
	put(image, 7, 4, ink.leafDark);

	return image;
}

/** A leafy head: a ball of leaves with the outer ones peeling away. */
function headItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	disc(image, 8, 9, 5, ink.fruit);
	disc(image, 8, 8.5, 3.6, ink.fruitLight);

	leaf(image, 4, 11, -1, 3, 2, ink, false);
	leaf(image, 12, 11, 1, 3, 2, ink, true);
	put(image, 8, 3, ink.leafLight);
	put(image, 8, 4, ink.leaf);

	// the curl every wrapped head shows where its leaves meet
	put(image, 7, 7, ink.fruitDark);
	put(image, 9, 10, ink.fruitDark);

	return image;
}

/** A round fruit with a stalk and one leaf. */
function fruitItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	disc(image, 8, 10, 5, ink.fruit);
	disc(image, 7.5, 9.5, 3.4, ink.fruitLight);
	put(image, 8, 4, ink.stemDark);
	put(image, 8, 3, ink.stemDark);
	leaf(image, 9, 4, 1, 3, 2, ink, true);
	put(image, 11, 13, ink.fruitDark);

	return image;
}

/** A long fruit: a pepper, an aubergine, a squash. */
function longItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	for (let i = 0; i < 11; i++) {
		const y = 4 + i;
		const half = i < 2 ? 1 : i > 8 ? 1 : 2;

		for (let x = 8 - half; x <= 8 + half; x++) {
			put(image, x, y, x < 8 ? ink.fruitLight : x > 8 ? ink.fruitDark : ink.fruit);
		}
	}

	put(image, 8, 3, ink.stem);
	leaf(image, 8, 3, 1, 2, 2, ink, true);
	leaf(image, 7, 3, -1, 2, 2, ink, false);

	return image;
}

/** A cob, half in its husk. */
function cobItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 3; y < 14; y++) {
		for (let x = 6; x < 11; x++) {
			// the kernels: a brick pattern, which is what a cob is close up
			const lit = (x + (y % 2 === 0 ? 0 : 1)) % 2 === 0;

			put(image, x, y, lit ? ink.fruitLight : ink.fruit);
		}
	}

	for (let y = 6; y < 15; y++) {
		put(image, 5, y, ink.leaf);
		put(image, 11, y, ink.leafDark);
	}

	put(image, 4, 10, ink.leafDark);
	put(image, 12, 11, ink.leaf);
	put(image, 8, 2, ink.fruitDark);

	return image;
}

/** A sheaf: stems gathered and tied, heads out the top. */
function sheafItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	const cord: Rgba = [148, 114, 72, 255];

	// the stems fan below the tie and gather above it, which is the whole
	// silhouette of a sheaf; three parallel lines just read as chopsticks
	for (let i = 0; i < 3; i++) {
		const lean = i - 1;

		for (let y = 6; y < 15; y++) {
			const spread = y > 10 ? Math.round(((y - 10) * lean * 3) / 4) : 0;

			put(image, 8 + lean + spread, y, y % 3 === 2 ? ink.stemDark : ink.stem);
		}

		// the head, wider than its stem and heavy enough to bend it over
		for (let row = 0; row < 5; row++) {
			const x = 8 + lean * 2;

			put(image, x, 2 + row, row % 2 === 0 ? ink.fruit : ink.fruitLight);
			put(image, x + Math.sign(lean || 1), 3 + row, ink.fruitDark);
		}
	}

	// the tie, in cord rather than in the crop's own colour, so it reads as
	// the thing holding the bundle instead of as more grain
	for (let x = 5; x < 12; x++) {
		put(image, x, 10, cord);
		put(image, x, 11, x % 2 === 0 ? cord : ink.stemDark);
	}

	return image;
}

/** A handful of berries on a sprig. */
function berriesItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	berry(image, 6, 9, 2, ink);
	berry(image, 10, 8, 2, ink);
	berry(image, 8, 12, 2, ink);

	put(image, 8, 5, ink.stemDark);
	put(image, 8, 6, ink.stem);
	leaf(image, 9, 5, 1, 3, 2, ink, true);
	leaf(image, 7, 5, -1, 3, 2, ink, false);

	return image;
}

/** A cut bloom: the flower and a length of stem. */
function bloomItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 8; y < 15; y++) {
		put(image, 8, y, y % 3 === 2 ? ink.stemDark : ink.stem);
	}

	leaf(image, 9, 11, 1, 3, 2, ink, true);

	const petals: Array<[number, number]> = [
		[8, 3],
		[5, 5],
		[11, 5],
		[6, 8],
		[10, 8],
	];

	for (const [px, py] of petals) {
		disc(image, px + 0.5, py + 0.5, 1.8, ink.fruit);
		put(image, px, py - 1, ink.fruitLight);
	}

	disc(image, 8.5, 6, 1.6, ink.fruitDark);
	put(image, 8, 5, ink.fruitLight);

	return image;
}

/** A few picked leaves, overlapping. */
function leavesItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	for (let i = 0; i < 3; i++) {
		const cx = 5 + i * 3;
		const cy = 6 + (i % 2) * 4;

		disc(image, cx + 0.5, cy + 0.5, 3, i % 2 === 0 ? ink.fruit : ink.leaf);
		disc(image, cx + 0.5, cy, 2, i % 2 === 0 ? ink.fruitLight : ink.leafLight);

		// the midrib, which is the whole difference between a leaf and a blob
		for (let j = -2; j <= 2; j++) {
			put(image, cx + j, cy + j, ink.leafDark);
		}
	}

	return image;
}

/** A whole melon, striped. */
function melonItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	disc(image, 8, 9, 6, ink.fruit);
	disc(image, 7.5, 8.5, 4.6, ink.fruitLight);

	for (let y = 4; y < 15; y++) {
		put(image, 5, y, ink.fruitDark);
		put(image, 8, y, ink.fruitDark);
		put(image, 11, y, ink.fruitDark);
	}

	put(image, 8, 3, ink.stemDark);
	put(image, 9, 2, ink.stem);

	return image;
}

/** A star fruit, drawn as the five-point section everybody knows it by. */
function starItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);
	const points: Array<[number, number]> = [
		[8, 2],
		[3, 7],
		[5, 13],
		[11, 13],
		[13, 7],
	];

	disc(image, 8, 9, 3.4, ink.fruit);

	for (const [px, py] of points) {
		const dx = Math.sign(px - 8);
		const dy = Math.sign(py - 9);

		for (let i = 0; i < 4; i++) {
			put(image, px - dx * i, py - dy * i, i === 0 ? ink.fruitDark : ink.fruit);
		}
	}

	disc(image, 7.5, 8.5, 2, ink.fruitLight);
	put(image, 8, 9, ink.fruitDark);

	return image;
}

/** A bunch: a narrowing stack of fruit under a leaf, grapes and hops alike. */
function bunchItem(ink: Ink): Bitmap {
	const image = bitmap(16, 16);

	for (let row = 0; row < 4; row++) {
		const half = 3 - row;

		for (let x = 8 - half; x <= 8 + half; x++) {
			put(image, x, 6 + row * 2, ink.fruit);
			put(image, x, 7 + row * 2, ink.fruitDark);
		}

		put(image, 8 - half, 6 + row * 2, ink.fruitLight);
	}

	put(image, 8, 4, ink.stemDark);
	leaf(image, 9, 4, 1, 3, 2, ink, true);
	leaf(image, 7, 4, -1, 3, 2, ink, false);

	return image;
}

const PRODUCE_ART: Record<Produce, (ink: Ink) => Bitmap> = {
	root: rootItem,
	bulb: bulbItem,
	head: headItem,
	fruit: fruitItem,
	long: longItem,
	cob: cobItem,
	sheaf: sheafItem,
	berries: berriesItem,
	bloom: bloomItem,
	leaves: leavesItem,
	melon: melonItem,
	star: starItem,
	bunch: bunchItem,
};

const FORM_ART: Record<Form, (image: Bitmap, p: number, ripe: boolean, ink: Ink, seed: number) => void> = {
	root: drawRoot,
	leafy: drawLeafy,
	stalk: drawStalk,
	vine: drawVine,
	grain: drawGrain,
	bloom: drawBloom,
	bush: drawBush,
	ground: drawGround,
};

// ---- what the generator asks for ------------------------------------------

/** The block id a crop's plant is registered under. */
export function cropBlockId(crop: Crop): string {
	return `${crop.id}_crop`;
}

/** The seed item's id. */
export function seedItemId(crop: Crop): string {
	return `${crop.id}_seeds`;
}

/** How many days a crop takes to mature, which is also its highest age. */
export function maturity(crop: Crop): number {
	return crop.stages.reduce((total, days) => total + days, 0);
}

/**
 * The age each stage sprite takes over at, as a rising list.
 *
 * A crop is planted at age 0 and matures at [maturity]; the wiki's stage table
 * says how long each picture is on screen, so the first sprite covers ages 0
 * up to `stages[0]`, the second up to `stages[0] + stages[1]`, and so on. The
 * last entry is maturity itself, which is the ripe sprite.
 */
export function stageThresholds(crop: Crop): number[] {
	const thresholds: number[] = [];
	let total = 0;

	for (const days of crop.stages) {
		total += days;
		thresholds.push(total);
	}

	return thresholds;
}

/**
 * What eating the crop is worth, from the game's own energy number.
 *
 * Stardew counts energy in the hundreds and Minecraft counts hunger in halves
 * of a drumstick, so the two scales meet here and nowhere else: a parsnip's 25
 * energy is one shank, a starfruit's 125 is five, and nothing is worth more
 * than a steak. A crop the game calls inedible comes out at zero, and the item
 * is then registered with no consumable behaviour at all.
 */
export function nutritionOf(crop: Crop): number {
	if (crop.energy <= 0) {
		return 0;
	}

	return Math.max(1, Math.min(8, Math.round(crop.energy / 25)));
}

/** Saturation, at the ratio vanilla gives raw produce. */
export function saturationOf(crop: Crop): number {
	return Math.round(nutritionOf(crop) * 0.6 * 10) / 10;
}

/** The block id of the withered crop every dead plant becomes. */
export const DEAD_CROP = 'dead_crop';

/**
 * Every crop sprite, keyed by texture name: one per growth stage, plus the one
 * shared withered plant.
 */
export function cropSprites(): Record<string, Uint8Array> {
	const sheets: Record<string, Uint8Array> = {
		[DEAD_CROP]: new Uint8Array(encodePng(deadSprite())),
	};

	for (const crop of CROPS) {
		const ink = inkOf(crop.palette);
		const seed = seedOf(crop.id);
		const count = crop.stages.length;
		const draw = FORM_ART[crop.form];

		for (let stage = 0; stage < count; stage++) {
			const image = bitmap(16, 16);
			const ripe = stage === count - 1;

			// the ripe stage is drawn at full size rather than at its share of
			// the run, or the last picture would be a hair taller than the one
			// before it and the field would read as still growing
			const p = ripe ? 1 : stage / Math.max(1, count - 1);

			draw(image, p, ripe, ink, seed);
			sheets[`${crop.id}_crop_stage${stage}`] = new Uint8Array(encodePng(image));
		}
	}

	return sheets;
}

/** Every crop item sprite: the produce and the seed packet, keyed by name. */
export function cropItemSprites(): Record<string, Uint8Array> {
	const sheets: Record<string, Uint8Array> = {};

	for (const crop of CROPS) {
		const ink = inkOf(crop.palette);

		sheets[crop.id] = new Uint8Array(encodePng(PRODUCE_ART[crop.produce](ink)));
		sheets[seedItemId(crop)] = new Uint8Array(encodePng(seedSprite(crop)));
	}

	return sheets;
}

/**
 * Vanilla's own crop geometry: four upright planes, two across each axis,
 * dropped a hair below the block so the plant meets the soil with no seam.
 *
 * This is the shape every crop in the game is drawn as, and it is what makes a
 * field of ours read as a field rather than as a row of X-shaped flowers; the
 * cross the flora catalog uses is right for a single bloom and wrong here.
 */
function cropPlanes(texture: string): Model {
	const uv: [number, number, number, number] = [0, 0, 16, 16];
	const low = -0.001;
	const high = 15.999;

	return {
		textures: { crop: texture, particle: texture },
		elements: [
			{
				from: [0, low, 4],
				to: [16, high, 4],
				shade: false,
				faces: { north: { uv, texture: '#crop' }, south: { uv, texture: '#crop' } },
			},
			{
				from: [0, low, 12],
				to: [16, high, 12],
				shade: false,
				faces: { north: { uv, texture: '#crop' }, south: { uv, texture: '#crop' } },
			},
			{
				from: [4, low, 0],
				to: [4, high, 16],
				shade: false,
				faces: { west: { uv, texture: '#crop' }, east: { uv, texture: '#crop' } },
			},
			{
				from: [12, low, 0],
				to: [12, high, 16],
				shade: false,
				faces: { west: { uv, texture: '#crop' }, east: { uv, texture: '#crop' } },
			},
		],
	};
}

/** Every crop block model, keyed by model name: one per stage, plus the dead one. */
export function cropModels(): Record<string, Model> {
	const models: Record<string, Model> = {
		[DEAD_CROP]: cropPlanes(`lunasmp:block/${DEAD_CROP}`),
	};

	for (const crop of CROPS) {
		for (let stage = 0; stage < crop.stages.length; stage++) {
			const name = `${crop.id}_crop_stage${stage}`;

			models[name] = cropPlanes(`lunasmp:block/${name}`);
		}
	}

	return models;
}
