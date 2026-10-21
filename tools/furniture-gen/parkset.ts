// The park set: benches in every wood and stone, public drinking fountains, and the
// seamless flower hedges. Models and sheets are composed here over vanilla textures;
// the Kotlin is hand-written in landscape/ParkSet.kt and landscape/Hedges.kt.
//
// Benches join with the bench beside them that faces the same way: a piece draws its
// armrest, its end post and its legs only on a side with no bench, so a row of them
// is one long bench. One model per join state: "single", "l" (joined on the sitter's
// left), "r", "lr". Every model is authored facing north (the sitter looks north,
// the back is at the south edge); Nova turns it to the block's facing.

import type { Model } from './compose';
import { type Bitmap, bitmap, encodePng, fill, put, rgb } from './pixels';

type Vec3 = [number, number, number];
type El = Record<string, unknown>;
const V = (n: string) => `minecraft:block/${n}`;
const L = (n: string) => `lunasmp:block/${n}`;

export interface Material { id: string; en: string; vi: string; seat: string; frame: string; stone: boolean }

const WOODS: [string, string, string, string?][] = [
	['oak', 'Oak', 'Gỗ Sồi'], ['spruce', 'Spruce', 'Gỗ Vân Sam'], ['birch', 'Birch', 'Gỗ Bạch Dương'],
	['jungle', 'Jungle', 'Gỗ Rừng'], ['acacia', 'Acacia', 'Gỗ Keo'], ['dark_oak', 'Dark Oak', 'Gỗ Sồi Sẫm'],
	['mangrove', 'Mangrove', 'Gỗ Đước'], ['cherry', 'Cherry', 'Gỗ Anh Đào'], ['pale_oak', 'Pale Oak', 'Gỗ Sồi Nhạt'],
	['bamboo', 'Bamboo', 'Tre', 'bamboo_block'], ['crimson', 'Crimson', 'Gỗ Đỏ Thẫm', 'crimson_stem'], ['warped', 'Warped', 'Gỗ Biến Dị', 'warped_stem'],
];

const STONES: [string, string, string, string, string?][] = [
	['stone', 'Stone', 'Đá', 'stone', 'smooth_stone'],
	['smooth_stone', 'Smooth Stone', 'Đá Nhẵn', 'smooth_stone'],
	['cobblestone', 'Cobblestone', 'Đá Cuội', 'cobblestone'],
	['mossy_cobblestone', 'Mossy Cobblestone', 'Đá Cuội Rêu', 'mossy_cobblestone'],
	['stone_brick', 'Stone Brick', 'Gạch Đá', 'stone_bricks'],
	['mossy_stone_brick', 'Mossy Stone Brick', 'Gạch Đá Rêu', 'mossy_stone_bricks'],
	['andesite', 'Andesite', 'Andesit', 'polished_andesite', 'andesite'],
	['granite', 'Granite', 'Đá Hoa Cương', 'polished_granite', 'granite'],
	['diorite', 'Diorite', 'Diorit', 'polished_diorite', 'diorite'],
	['deepslate', 'Deepslate', 'Đá Phiến Sâu', 'polished_deepslate', 'deepslate_bricks'],
	['sandstone', 'Sandstone', 'Sa Thạch', 'sandstone_top', 'sandstone'],
	['red_sandstone', 'Red Sandstone', 'Sa Thạch Đỏ', 'red_sandstone_top', 'red_sandstone'],
	['quartz', 'Quartz', 'Thạch Anh', 'quartz_block_top', 'quartz_block_side'],
	['blackstone', 'Blackstone', 'Đá Đen', 'polished_blackstone'],
	['tuff', 'Tuff', 'Đá Tuff', 'polished_tuff', 'tuff_bricks'],
	['brick', 'Brick', 'Gạch', 'bricks'],
	['mud_brick', 'Mud Brick', 'Gạch Bùn', 'mud_bricks'],
	['calcite', 'Calcite', 'Canxit', 'calcite'],
];

export const BENCH_MATERIALS: Material[] = [
	...WOODS.map(([id, en, vi, log]) => ({ id, en, vi, seat: V(`${id}_planks`), frame: V(log ?? (id === 'cherry' || id === 'pale_oak' || id === 'mangrove' ? `${id}_log` : `${id}_log`)), stone: false })),
	...STONES.map(([id, en, vi, seat, frame]) => ({ id, en, vi, seat: V(seat), frame: V(frame ?? seat), stone: true })),
];

export const BENCH_KEYS = ['single', 'l', 'r', 'lr'];

const FULL: [number, number, number, number] = [0, 0, 16, 16];

function cube(from: Vec3, to: Vec3, tex: string, top?: string): El {
	const faces: Record<string, unknown> = {};
	for (const dir of ['north', 'south', 'east', 'west', 'down']) faces[dir] = { texture: tex };
	faces.up = { texture: top ?? tex };
	return { from, to, faces };
}

function woodBench(m: Material, key: string): El[] {
	const left = key !== 'l' && key !== 'lr';
	const right = key !== 'r' && key !== 'lr';
	const els: El[] = [
		cube([0, 7, 2], [16, 8.5, 12.5], m.seat),
		cube([0, 6, 2], [16, 7, 3], m.seat),
		cube([0, 10, 12.5], [16, 12, 13.5], m.seat),
		cube([0, 13, 12.5], [16, 15, 13.5], m.seat),
	];
	const end = (x0: number, x1: number) => {
		els.push(cube([x0, 0, 2.5], [x1, 7, 4.5], m.frame));
		els.push(cube([x0, 0, 10.5], [x1, 7, 12.5], m.frame));
		els.push(cube([x0, 8.5, 12.5], [x1, 16, 14], m.frame));
		els.push(cube([x0, 8.5, 3], [x1, 11, 4.5], m.frame));
		els.push(cube([x0 - (x0 === 1 ? 1 : 0), 11, 2.5], [x1 + (x1 === 15 ? 1 : 0), 12, 13], m.seat));
	};
	if (left) end(1, 3);
	if (right) end(13, 15);
	if (!left && !right) {
		els.push(cube([7, 0, 3], [9, 7, 12], m.frame));
	}
	return els;
}

function stoneBench(m: Material, key: string): El[] {
	const left = key !== 'l' && key !== 'lr';
	const right = key !== 'r' && key !== 'lr';
	const els: El[] = [
		cube([0, 6, 2], [16, 9, 12], m.seat),
		cube([0, 9, 11], [16, 15, 13.5], m.frame, m.seat),
	];
	const end = (x0: number, x1: number, ax0: number, ax1: number) => {
		els.push(cube([x0, 0, 3], [x1, 6, 11], m.frame));
		els.push(cube([ax0, 9, 2], [ax1, 12, 11], m.frame, m.seat));
	};
	if (left) end(1, 5, 0, 2);
	if (right) end(11, 15, 14, 16);
	if (!left && !right) {
		els.push(cube([6, 0, 3], [10, 6, 11], m.frame));
	}
	return els;
}

// ---- drinking fountains ----------------------------------------------------------------------

export const FOUNTAINS: { id: string; en: string; vi: string; body: string; trim: string }[] = [
	{ id: 'stone_brick', en: 'Stone Brick', vi: 'Gạch Đá', body: V('stone_bricks'), trim: V('smooth_stone') },
	{ id: 'mossy_stone_brick', en: 'Mossy Stone Brick', vi: 'Gạch Đá Rêu', body: V('mossy_stone_bricks'), trim: V('smooth_stone') },
	{ id: 'andesite', en: 'Andesite', vi: 'Andesit', body: V('polished_andesite'), trim: V('andesite') },
	{ id: 'quartz', en: 'Quartz', vi: 'Thạch Anh', body: V('quartz_block_side'), trim: V('quartz_block_top') },
	{ id: 'deepslate', en: 'Deepslate', vi: 'Đá Phiến Sâu', body: V('deepslate_bricks'), trim: V('polished_deepslate') },
	{ id: 'sandstone', en: 'Sandstone', vi: 'Sa Thạch', body: V('cut_sandstone'), trim: V('sandstone_top') },
];

function fountain(f: (typeof FOUNTAINS)[number]): El[] {
	const water = L('park_fountain_water');
	const brass = L('park_fountain_brass');
	return [
		cube([2, 0, 2], [14, 2, 14], f.trim),
		cube([4, 2, 4], [12, 9, 12], f.body, f.trim),
		cube([3, 9, 3], [13, 10, 13], f.trim),
		// the basin: a floor and four rim walls round a pool of water
		cube([2, 10, 2], [14, 11, 14], f.trim),
		cube([2, 11, 2], [14, 13.5, 3], f.trim),
		cube([2, 11, 13], [14, 13.5, 14], f.trim),
		cube([2, 11, 3], [3, 13.5, 13], f.trim),
		cube([13, 11, 3], [14, 13.5, 13], f.trim),
		{ from: [3, 12.5, 3], to: [13, 12.5, 13], faces: { up: { texture: water }, down: { texture: water } } },
		// the bubbler: a brass stem rising from the back of the basin, its nozzle curving over the water
		cube([7.25, 11, 10], [8.75, 16, 11.5], brass),
		cube([7.25, 14.5, 7.5], [8.75, 16, 10], brass),
		cube([7.5, 14, 7.5], [8.5, 14.5, 8.5], brass),
		// the arc of water from the nozzle
		{ from: [7.5, 12.5, 6], to: [8.5, 14.5, 7.5], faces: { north: { texture: water }, south: { texture: water }, east: { texture: water }, west: { texture: water }, up: { texture: water } } },
		// the push button on the front of the rim
		cube([7, 12, 1.5], [9, 13, 2], brass),
	];
}

// ---- seamless flower hedges ------------------------------------------------------------------

export const HEDGE_FLOWERS: { id: string; en: string; vi: string }[] = [
	{ id: 'rose_bush', en: 'Rose', vi: 'Hoa Hồng' },
	{ id: 'peony', en: 'Peony', vi: 'Mẫu Đơn' },
	{ id: 'lilac', en: 'Lilac', vi: 'Tử Đinh Hương' },
	{ id: 'pink_tulip', en: 'Pink Tulip', vi: 'Tulip Hồng' },
	{ id: 'white_tulip', en: 'White Tulip', vi: 'Tulip Trắng' },
	{ id: 'oxeye_daisy', en: 'Daisy', vi: 'Cúc Trắng' },
	{ id: 'poppy', en: 'Poppy', vi: 'Anh Túc' },
	{ id: 'allium', en: 'Allium', vi: 'Hành Tím' },
];

const ARMS: { letter: string; from: Vec3; to: Vec3 }[] = [
	{ letter: 'n', from: [2, 0, 0], to: [14, 16, 2] },
	{ letter: 'e', from: [14, 0, 2], to: [16, 16, 14] },
	{ letter: 's', from: [2, 0, 14], to: [14, 16, 16] },
	{ letter: 'w', from: [0, 0, 2], to: [2, 16, 14] },
];

export function hedgeKeys(): string[] {
	const keys: string[] = [];
	for (let mask = 0; mask < 16; mask++) keys.push(ARMS.filter((_, i) => mask & (1 << i)).map((a) => a.letter).join('') || 'post');
	return keys;
}

function hedge(texture: string, key: string): El[] {
	// a post as tall as a block and twelve wide, and arms the same height and width, so a run
	// of hedges is one unbroken wall of leaves; faces where an arm meets the post are left out
	const has = (l: string) => key !== 'post' && key.includes(l);
	const faces = (skip: string[]) => Object.fromEntries(['north', 'south', 'east', 'west', 'up', 'down'].filter((d) => !skip.includes(d)).map((d) => [d, { texture }]));
	const postSkip = [has('n') ? 'north' : '', has('s') ? 'south' : '', has('e') ? 'east' : '', has('w') ? 'west' : ''].filter(Boolean);
	const els: El[] = [{ from: [2, 0, 2], to: [14, 16, 14], faces: faces(postSkip) }];
	const inner: Record<string, string> = { n: 'south', s: 'north', e: 'west', w: 'east' };
	const outer: Record<string, string> = { n: 'north', s: 'south', e: 'east', w: 'west' };
	for (const arm of ARMS) {
		if (has(arm.letter)) {
			// the outer face meets the next hedge's arm: left out too, so the join is seamless
			els.push({ from: arm.from, to: arm.to, faces: faces([inner[arm.letter]!, outer[arm.letter]!]) });
		}
	}
	return els;
}

// ---- output ------------------------------------------------------------------------------------

function withVariables(els: El[], particle: string): Model {
	const textures: Record<string, string> = {};
	const names = new Map<string, string>();
	for (const el of els) {
		for (const face of Object.values(el.faces as Record<string, { texture: string; uv?: unknown }>)) {
			let n = names.get(face.texture);
			if (!n) {
				n = `t${names.size}`;
				names.set(face.texture, n);
				textures[n] = face.texture;
			}
			face.texture = `#${n}`;
		}
	}
	textures.particle = particle;
	return {
		textures,
		elements: els,
		display: {
			gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
			ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
			fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
			thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
			thirdperson_lefthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
			firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
			firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
		},
	} as unknown as Model;
}

export function parkModels(): Record<string, Model> {
	const out: Record<string, Model> = {};
	for (const m of BENCH_MATERIALS) {
		for (const key of BENCH_KEYS) {
			out[`park_bench_${m.id}_${key}`] = withVariables(m.stone ? stoneBench(m, key) : woodBench(m, key), m.seat);
		}
	}
	for (const f of FOUNTAINS) out[`drinking_fountain_${f.id}`] = withVariables(fountain(f), f.body);
	for (const h of HEDGE_FLOWERS) {
		const texture = L(`${h.id}_bush`);
		for (const key of hedgeKeys()) out[`${h.id}_hedge_${key}`] = withVariables(hedge(texture, key), texture);
	}
	return out;
}

function waterSheet(): Bitmap {
	const image = bitmap(16, 16);
	fill(image, rgb('#3f8fe0', 220));
	for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
		if ((x + y * 3) % 7 === 0) put(image, x, y, rgb('#7cc0f5', 230));
		if ((x * 5 + y) % 11 === 0) put(image, x, y, rgb('#2c6fc0', 230));
	}
	return image;
}

function brassSheet(): Bitmap {
	const image = bitmap(16, 16);
	fill(image, rgb('#c9a646'));
	for (let y = 0; y < 16; y++) {
		put(image, 2, y, rgb('#f0d27a'));
		put(image, 12, y, rgb('#9a7b2c'));
	}
	return image;
}

export function parkSprites(): Record<string, Uint8Array> {
	return {
		park_fountain_water: new Uint8Array(encodePng(waterSheet())),
		park_fountain_brass: new Uint8Array(encodePng(brassSheet())),
	};
}
