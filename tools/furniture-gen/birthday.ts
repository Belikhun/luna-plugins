// The birthday set: a three-tier cake tower with candles and a sparkler, the
// gift box it comes with, the plate a slice is served on, and the small gifts
// that go inside the box - two chocolate bars, a party hat and a flower ring.
//
// Everything here is ours. The geometry is composed out of boxes the way the
// flora and the industrial blocks are, and every sheet is painted below; the
// only vanilla reference is the cake's own idea of a slice, which is a box
// growing shorter on one side with the sponge showing on the cut.
//
// The tower is two blocks tall so both halves have a real hitbox: the lower
// block carries the plate and the two big tiers (a full barrier), the upper
// block the small top tier with its candles (the eight-pixel heavy core that
// the tier fills). A slice is a cut through the lower tiers only, so the top
// tier and its candles stay whole however much has been eaten, and the eight
// cuts go round the cake - north, east, south, west, then round again - each
// one and a half pixels deep, which is what keeps the tiers stacked on
// whatever is left underneath them.

import { type Element, type Model, type Vec3, CROWN_DISPLAY, composed, part } from './compose';
import { type Bitmap, type Rgba, bitmap, encodePng, fill, put, rect, rgb, tint } from './pixels';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decodePng } from '/home/belikhun/luna-console/web/src/lib/server/imaging/png';
import { GIFTS, TREATS, treatSprites } from './treats';

// ---- ids and names ----------------------------------------------------------

export interface BirthdayEntry {
	id: string;
	en: string;
	vi: string;
}

/** The blocks, each of which needs a `configs/<id>.yml` for Breakable. */
export const BIRTHDAY_BLOCKS: BirthdayEntry[] = [
	{ id: 'birthday_cake', en: 'Birthday Cake', vi: 'Bánh Sinh Nhật' },
	{ id: 'birthday_cake_top', en: 'Birthday Cake (top tier)', vi: 'Bánh Sinh Nhật (tầng trên)' },
	{ id: 'gift_box', en: 'Gift Box', vi: 'Hộp Quà' },
	{ id: 'plated_cake', en: 'Plate of Birthday Cake', vi: 'Đĩa Bánh Sinh Nhật' },
	{ id: 'cake_stand', en: 'Cake Stand', vi: 'Đế Bánh Sinh Nhật' },
	{ id: 'phuynhi_plushie', en: 'Bé Ne Plushie', vi: 'Búp Bê Bé Ne' },
];

/**
 * The balloon bunches: five balloons of one colour tied to a point on the
 * floor, splayed out on angled strings, three blocks tall; and one mixed
 * bunch. Each bunch is its own block.
 */
export const BALLOON_COLOURS: { id: string; en: string; vi: string; colour: string }[] = [
	{ id: 'pink', en: 'Pink', vi: 'Hồng', colour: '#ff7fb0' },
	{ id: 'red', en: 'Red', vi: 'Đỏ', colour: '#e8304f' },
	{ id: 'gold', en: 'Gold', vi: 'Vàng', colour: '#f2c14e' },
	{ id: 'blue', en: 'Blue', vi: 'Xanh', colour: '#5ec8f2' },
	{ id: 'purple', en: 'Purple', vi: 'Tím', colour: '#a86be0' },
	{ id: 'white', en: 'White', vi: 'Trắng', colour: '#f7f3f6' },
];

export const BALLOON_BUNCHES: BirthdayEntry[] = [
	...BALLOON_COLOURS.map((colour) => ({
		id: `balloon_bunch_${colour.id}`,
		en: `${colour.en} Balloon Bunch`,
		vi: `Chùm Bóng Bay ${colour.vi}`,
	})),
	{ id: 'balloon_bunch_mix', en: 'Mixed Balloon Bunch', vi: 'Chùm Bóng Bay Nhiều Màu' },
];

BIRTHDAY_BLOCKS.push(...BALLOON_BUNCHES);

/** The items that place no block. */
export const BIRTHDAY_ITEMS: BirthdayEntry[] = [
	{ id: 'cake_plate', en: 'Empty Plate', vi: 'Đĩa Trống' },
	{ id: 'cake_slice', en: 'Plate of Birthday Cake', vi: 'Đĩa Bánh Sinh Nhật' },
	{ id: 'chocolate_bar', en: 'Dark Chocolate Bar', vi: 'Thanh Sô-cô-la Đen' },
	{ id: 'milk_chocolate_bar', en: 'Milk Chocolate Bar', vi: 'Thanh Sô-cô-la Sữa' },
	{ id: 'birthday_hat', en: 'Birthday Hat', vi: 'Nón Sinh Nhật' },
	{ id: 'flower_ring', en: 'Special Flower Ring', vi: 'Nhẫn Hoa Đặc Biệt' },
	{ id: 'sparkler', en: 'Sparkler', vi: 'Pháo Bông Cầm Tay' },
	{ id: 'sparkler_lit', en: 'Lit Sparkler', vi: 'Pháo Bông Cầm Tay (đang cháy)' },
	...TREATS,
	...GIFTS,
];

/** How many plates the lower tiers give before only the core is left. */
export const CAKE_SLICES = 8;

/** How deep each cut goes, in pixels. */
const CUT = 1.5;

// ---- palette ----------------------------------------------------------------

const CHOC = rgb('#5b3a26');
const CHOC_DARK = rgb('#43291a');
const CHOC_LIGHT = rgb('#74492f');
const MILK = rgb('#9a6a44');
const MILK_LIGHT = rgb('#b98157');
const SPONGE = rgb('#8a5a3a');
const CREAM = rgb('#fff1e3');
const CREAM_SHADE = rgb('#f1dcc7');
const PINK = rgb('#f6a9c4');
const PINK_DARK = rgb('#e88ab0');
const PINK_LIGHT = rgb('#fbd0df');
const PINK_SPONGE = rgb('#f2b4c7');
const JAM = rgb('#e0446a');
const BERRY = rgb('#e8304f');
const BERRY_DARK = rgb('#b81f3c');
const BERRY_LIGHT = rgb('#ff6b85');
const SEED = rgb('#ffd9a0');
const LEAF = rgb('#4caf50');
const LEAF_DARK = rgb('#2e7d32');
const WHITE = rgb('#ffffff');
const PORCELAIN = rgb('#f7f3ec');
const PORCELAIN_SHADE = rgb('#e9e2d6');
const GOLD = rgb('#d9b45a');
const GOLD_LIGHT = rgb('#ffe08a');
const GOLD_DARK = rgb('#b8902e');
const RIBBON = rgb('#f2c14e');
const WRAP = rgb('#ff7fb0');
const WRAP_DARK = rgb('#e86a9c');
const WICK = rgb('#2a2222');
const SILVER = rgb('#b8bcc4');
const SILVER_DARK = rgb('#8f949c');
const FLAME_OUT = rgb('#ff8c1a');
const FLAME_IN = rgb('#ffd75e');
const FLAME_CORE = rgb('#fffbe6');
const HAT_BLUE = rgb('#5ec8f2');
const HAT_YELLOW = rgb('#ffd94a');
const POM = rgb('#fff6f8');
const FOIL = rgb('#c9a227');
const FOIL_LIGHT = rgb('#f1d878');
const WRAPPER_RED = rgb('#c62a3c');
const WRAPPER_BLUE = rgb('#2f6fd0');
const WRAPPER_WHITE = rgb('#f4f4f4');

/** A tiny deterministic hash in [0, 1): a sheet always paints the same. */
function noise(seed: number, x: number, y: number): number {
	let h = seed ^ Math.imul(x + 17, 374761393) ^ Math.imul(y + 3, 668265263);
	h = Math.imul(h ^ (h >>> 13), 1274126177);

	return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

/** Fills a rectangle with a colour jittered a little per pixel, the crumb of a sponge. */
function crumb(image: Bitmap, x1: number, y1: number, x2: number, y2: number, colour: Rgba, seed: number, amount = 0.08): void {
	for (let y = y1; y < y2; y++) {
		for (let x = x1; x < x2; x++) {
			const factor = 1 - amount + noise(seed, x, y) * amount * 2;
			put(image, x, y, tint(colour, factor));
		}
	}
}

/**
 * Cream running down from a band: a full row at the top, then drips of
 * varying length below it, alternating two colours so the drips read as
 * frosting rather than as a fringe.
 */
function drips(image: Bitmap, row: number, colours: [Rgba, Rgba], seed: number): void {
	rect(image, 0, row, 16, row + 1, colours[0]);

	for (let x = 0; x < 16; x++) {
		const length = Math.floor(noise(seed, x, 0) * 4);
		const colour = noise(seed, x, 1) < 0.35 ? colours[1] : colours[0];

		for (let y = row + 1; y <= row + length; y++) {
			put(image, x, y, colour);
		}
	}
}

/** A 2x2 berry with a highlight pixel and one leaf pixel above it. */
function berry(image: Bitmap, x: number, y: number): void {
	rect(image, x, y, x + 2, y + 2, BERRY);
	put(image, x, y, BERRY_LIGHT);
	put(image, x + 1, y + 1, BERRY_DARK);
	put(image, x + (noise(9, x, y) < 0.5 ? 0 : 1), y - 1, LEAF);
}

/** A five-wide heart around a centre, in a solid colour. */
function heart(image: Bitmap, cx: number, cy: number, colour: Rgba): void {
	const rows = ['.X.X.', 'XXXXX', 'XXXXX', '.XXX.', '..X..'];

	for (let r = 0; r < rows.length; r++) {
		for (let c = 0; c < 5; c++) {
			if (rows[r]![c] === 'X') {
				put(image, cx - 2 + c, cy - 2 + r, colour);
			}
		}
	}
}

// ---- the sheets --------------------------------------------------------------

function plateSheet(): Bitmap {
	const image = bitmap(16, 16);
	fill(image, PORCELAIN);

	for (let i = 0; i < 16; i++) {
		put(image, i, 0, GOLD);
		put(image, i, 15, GOLD);
		put(image, 0, i, GOLD);
		put(image, 15, i, GOLD);
		put(image, i, 1, PORCELAIN_SHADE);
		put(image, i, 14, PORCELAIN_SHADE);
		put(image, 1, i, PORCELAIN_SHADE);
		put(image, 14, i, PORCELAIN_SHADE);
	}

	return image;
}

/**
 * A tier's side. A box's side faces sample the sheet at the rows the box
 * occupies (autoUv), so each tier gets its own sheet with the drips painted
 * at the rows where its top actually is: rows 8 to 11 for the lower tier
 * (y 1..8), the first rows for the middle tier (y 8..16), rows 11 onward for
 * the top tier (y 0..5 of its block).
 */
function tierSide(base: Rgba, dripRow: number, dripColours: [Rgba, Rgba], seed: number): Bitmap {
	const image = bitmap(16, 16);
	crumb(image, 0, 0, 16, 16, base, seed);
	drips(image, dripRow, dripColours, seed + 1);

	return image;
}

function strawberrySide(): Bitmap {
	const image = tierSide(PINK, 0, [WHITE, PINK_LIGHT], 31);

	// a row of piped dots near the foot of the tier, and sprinkles between
	for (let x = 0; x < 16; x += 2) {
		put(image, x, 6, WHITE);
		put(image, x + 1, 7, PINK_LIGHT);
	}

	const sprinkles = [HAT_YELLOW, HAT_BLUE, WHITE, LEAF, JAM];

	for (let i = 0; i < 10; i++) {
		const x = Math.floor(noise(41, i, 0) * 16);
		const y = 3 + Math.floor(noise(41, i, 1) * 3);
		put(image, x, y, sprinkles[i % sprinkles.length]!);
	}

	return image;
}

/** The cut face of a tier: sponge, cream and jam in repeating layers. */
function innerSheet(sponge: Rgba, filling: Rgba, seed: number): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		const layer = y % 5;
		const colour = layer === 3 ? CREAM : layer === 4 ? filling : sponge;

		if (layer < 3) {
			crumb(image, 0, y, 16, y + 1, colour, seed + y, 0.12);
		} else {
			rect(image, 0, y, 16, y + 1, colour);
		}
	}

	return image;
}

/** The lower tier's top: cream, with berries round the rim the middle tier leaves bare. */
function chocolateTop(): Bitmap {
	const image = bitmap(16, 16);
	crumb(image, 0, 0, 16, 16, CREAM, 51, 0.04);

	for (const at of [1, 5, 9, 13]) {
		berry(image, 1, at);
		berry(image, 13, at);
		berry(image, at, 1);
		berry(image, at, 13);
	}

	for (const at of [3, 7, 11]) {
		put(image, 1, at + 1, PINK_DARK);
		put(image, 14, at + 1, PINK_DARK);
		put(image, at + 1, 1, PINK_DARK);
		put(image, at + 1, 14, PINK_DARK);
	}

	return image;
}

/** The middle tier's top: pink, with a piped white ring where the top tier leaves it bare. */
function strawberryTop(): Bitmap {
	const image = bitmap(16, 16);
	crumb(image, 0, 0, 16, 16, PINK, 61, 0.05);

	for (let i = 3; i < 13; i++) {
		const on = i % 2 === 1;
		const colour = on ? WHITE : PINK_LIGHT;
		put(image, 3, i, colour);
		put(image, 12, i, colour);
		put(image, i, 3, colour);
		put(image, i, 12, colour);
	}

	return image;
}

/** The top tier's top: cream with a pink heart in the middle. */
function creamTop(): Bitmap {
	const image = bitmap(16, 16);
	crumb(image, 0, 0, 16, 16, CREAM, 71, 0.04);
	heart(image, 8, 8, PINK_DARK);
	put(image, 7, 7, PINK_LIGHT);

	return image;
}

function candleSheet(): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		rect(image, 0, y, 16, y + 1, y % 2 === 0 ? PINK_DARK : WHITE);
	}

	return image;
}

function solid(colour: Rgba): Bitmap {
	const image = bitmap(16, 16);
	fill(image, colour);

	return image;
}

function sparklerSheet(): Bitmap {
	const image = bitmap(16, 16);
	fill(image, SILVER);

	for (let y = 0; y < 16; y += 3) {
		rect(image, 0, y, 16, y + 1, SILVER_DARK);
	}

	return image;
}

/** A four-point star with a soft glow: the sparkler's tip. */
function sparkSheet(): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const dx = Math.abs(x + 0.5 - 8);
			const dy = Math.abs(y + 0.5 - 8);
			const arm = Math.min(dx, dy) < 0.8 && Math.max(dx, dy) < 7;
			const core = dx + dy < 3.2;

			if (core) {
				put(image, x, y, dx + dy < 1.6 ? WHITE : [255, 240, 160, 235]);
			} else if (arm) {
				const fade = 1 - Math.max(dx, dy) / 7;
				put(image, x, y, [255, 236, 150, Math.round(90 + 150 * fade)]);
			}
		}
	}

	return image;
}

/** A candle flame on an eight-pixel sheet: orange outside, yellow, a white core. */
function flameSheet(): Bitmap {
	const image = bitmap(8, 8);
	const rows = [
		'...XX...',
		'...XX...',
		'..XXXX..',
		'..XYYX..',
		'.XYYYYX.',
		'.XYWWYX.',
		'.XYWWYX.',
		'..XYYX..',
	];

	for (let y = 0; y < 8; y++) {
		for (let x = 0; x < 8; x++) {
			const c = rows[y]![x];

			if (c === 'X') {
				put(image, x, y, FLAME_OUT);
			} else if (c === 'Y') {
				put(image, x, y, FLAME_IN);
			} else if (c === 'W') {
				put(image, x, y, FLAME_CORE);
			}
		}
	}

	return image;
}

function berrySheet(): Bitmap {
	const image = bitmap(16, 16);
	crumb(image, 0, 0, 16, 16, BERRY, 81, 0.1);

	for (let y = 1; y < 16; y += 3) {
		for (let x = (y % 2) * 2 + 1; x < 16; x += 4) {
			put(image, x, y, SEED);
		}
	}

	return image;
}

function leafSheet(): Bitmap {
	const image = bitmap(16, 16);
	crumb(image, 0, 0, 16, 16, LEAF, 91, 0.1);

	for (let i = 0; i < 16; i += 5) {
		put(image, i, i, LEAF_DARK);
	}

	return image;
}

/** Pink wrapping paper with little white hearts and a gold ribbon down the middle. */
function wrapSheet(cross: boolean): Bitmap {
	const image = bitmap(16, 16);
	fill(image, WRAP);

	// one heart per eight-pixel cell, so a heart sits whole either side of
	// the ribbon and none is cut at the sheet's edge
	for (const y of [1, 9]) {
		for (const x of [1, 9]) {
			heart(image, x + 2, y + 2, POM);
		}
	}

	ribbonBand(image, 6, 10, true);

	if (cross) {
		ribbonBand(image, 6, 10, false);
	}

	return image;
}

/** A satin band across the sheet, with a bright centre line and darker edges. */
function ribbonBand(image: Bitmap, from: number, to: number, vertical: boolean): void {
	for (let i = from; i < to; i++) {
		const colour = i === from || i === to - 1 ? GOLD_DARK : i === from + 1 ? GOLD_LIGHT : RIBBON;

		for (let j = 0; j < 16; j++) {
			if (vertical) {
				put(image, i, j, colour);
			} else {
				put(image, j, i, colour);
			}
		}
	}
}

function ribbonSheet(): Bitmap {
	const image = bitmap(16, 16);
	fill(image, RIBBON);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const d = (x + y) % 8;

			if (d === 0) {
				put(image, x, y, GOLD_LIGHT);
			} else if (d === 4) {
				put(image, x, y, GOLD_DARK);
			}
		}
	}

	return image;
}

/** The party hat's stripes, spiralling by shifting one pixel per row. */
function hatSheet(): Bitmap {
	const image = bitmap(16, 16);
	const bands = [PINK_DARK, HAT_YELLOW, HAT_BLUE, WHITE];

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			put(image, x, y, bands[Math.floor(((x + y) % 16) / 4)]!);
		}
	}

	return image;
}

/** A bar of chocolate in a wrapper torn open at one end, drawn as the flat item sprite. */
function chocolateBarSprite(chocolate: Rgba, chocolateLight: Rgba, wrapper: Rgba, band: Rgba): Bitmap {
	const image = bitmap(16, 16);

	// the bar runs diagonally from bottom-left to top-right, the wrapper
	// covering its lower half; drawn as a rotated rectangle by testing the
	// pixel centres against two axes
	const along = (x: number, y: number): number => (x + 0.5 - 8) * 0.7071 + (8 - (y + 0.5)) * 0.7071;
	const across = (x: number, y: number): number => (x + 0.5 - 8) * 0.7071 - (8 - (y + 0.5)) * 0.7071;

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const a = along(x, y);
			const c = across(x, y);

			if (Math.abs(c) > 3.2 || Math.abs(a) > 8.5) {
				continue;
			}

			if (a < 0.5) {
				// the wrapper, with a foil edge and a band of colour
				const edge = a > -0.7;
				const stripe = a < -4.2 && a > -6.2;
				put(image, x, y, edge ? FOIL_LIGHT : stripe ? band : wrapper);

				if (Math.abs(c) > 2.6) {
					put(image, x, y, tint(edge ? FOIL_LIGHT : stripe ? band : wrapper, 0.8));
				}
			} else {
				// the bare bar: segments split by darker grooves
				const groove = Math.abs(((a + 100) % 2.4) - 1.2) < 0.25 || Math.abs(c) < 0.25;
				const colour = groove ? tint(chocolate, 0.7) : c < -1.2 ? chocolateLight : chocolate;
				put(image, x, y, Math.abs(c) > 2.6 ? tint(chocolate, 0.75) : colour);
			}
		}
	}

	return image;
}

/** The party hat as it looks in a slot: a striped cone with a pom-pom. */
function hatSprite(): Bitmap {
	const image = bitmap(16, 16);
	const bands = [PINK_DARK, HAT_YELLOW, HAT_BLUE, WHITE];

	for (let y = 3; y < 15; y++) {
		const half = ((y - 3) / 12) * 6.5 + 0.5;

		for (let x = 0; x < 16; x++) {
			if (Math.abs(x + 0.5 - 8) <= half) {
				const shaded = x + 0.5 - 8 > half - 1.2;
				const colour = bands[Math.floor(((x + y) % 16) / 4)]!;
				put(image, x, y, shaded ? tint(colour, 0.75) : colour);
			}
		}
	}

	rect(image, 1, 14, 15, 15, GOLD);
	rect(image, 7, 1, 9, 3, POM);
	put(image, 6, 2, POM);
	put(image, 9, 2, POM);

	return image;
}

/** The hand-held sparkler as it looks in a slot: a wire with a coated tip, and the star when it burns. */
function sparklerSprite(lit: boolean): Bitmap {
	const image = bitmap(16, 16);

	for (let i = 0; i < 9; i++) {
		put(image, 8 - i, 7 + i, SILVER);
		put(image, 9 - i, 7 + i, SILVER_DARK);
	}

	for (let i = 0; i < 4; i++) {
		put(image, 10 + i, 5 - i, lit ? FLAME_OUT : WICK);
		put(image, 11 + i, 5 - i, lit ? FLAME_IN : WICK);
	}

	if (lit) {
		const rows = ['...X...', '.X.X.X.', '..XXX..', 'XXXOXXX', '..XXX..', '.X.X.X.', '...X...'];

		for (let r = 0; r < rows.length; r++) {
			for (let c = 0; c < 7; c++) {
				const ch = rows[r]![c];

				if (ch === 'X') {
					put(image, 9 + c, r - 1, [255, 240, 160, 230]);
				} else if (ch === 'O') {
					put(image, 9 + c, r - 1, WHITE);
				}
			}
		}
	}

	return image;
}

/** The empty plate as it looks in a slot: porcelain with a gold rim, seen a little from above. */
function plateSprite(): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const dx = (x + 0.5 - 8) / 7.5;
			const dy = (y + 0.5 - 8) / 4.5;
			const r = Math.sqrt(dx * dx + dy * dy);

			if (r <= 1) {
				put(image, x, y, r > 0.82 ? GOLD : r > 0.7 ? PORCELAIN_SHADE : PORCELAIN);
			}
		}
	}

	put(image, 5, 6, WHITE);
	put(image, 6, 6, WHITE);

	return image;
}

/** The ring as it looks in a slot: a gold band seen at an angle, a pink flower on top. */
function ringSprite(): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const dx = (x + 0.5 - 8) / 6;
			const dy = (y + 0.5 - 9.5) / 4;
			const r = Math.sqrt(dx * dx + dy * dy);

			if (r <= 1 && r >= 0.62) {
				put(image, x, y, dy > 0.3 ? GOLD_DARK : dx < -0.3 ? GOLD_LIGHT : GOLD);
			}
		}
	}

	// the bloom sits on the band's far edge
	const petals: Array<[number, number]> = [[6, 2], [8, 2], [5, 4], [9, 4], [6, 6], [8, 6]];

	for (const [x, y] of petals) {
		rect(image, x, y, x + 2, y + 2, PINK);
	}

	rect(image, 6, 3, 9, 6, PINK_DARK);
	put(image, 7, 4, GOLD_LIGHT);
	put(image, 7, 3, PINK_LIGHT);

	return image;
}

/** A small pink bloom for the ring's crossed planes. */
function bloomSheet(): Bitmap {
	const image = bitmap(8, 8);
	const rows = [
		'..PP.PP.',
		'.PPPPPPP',
		'.PPDDPPP',
		'..PDGDP.',
		'.PPDDPPP',
		'.PPPPPPP',
		'..PP.PP.',
		'........',
	];

	for (let y = 0; y < 8; y++) {
		for (let x = 0; x < 8; x++) {
			const c = rows[y]![x];

			if (c === 'P') {
				put(image, x, y, PINK);
			} else if (c === 'D') {
				put(image, x, y, PINK_DARK);
			} else if (c === 'G') {
				put(image, x, y, GOLD_LIGHT);
			}
		}
	}

	return image;
}

/** Every painted sheet that goes into `textures/block`, by name. */
export function birthdaySprites(): Record<string, Uint8Array> {
	const sheets: Record<string, Bitmap> = {
		cake_plate: plateSheet(),
		cake_tier1_side: tierSide(CHOC, 8, [CREAM, PINK_LIGHT], 11),
		cake_tier2_side: strawberrySide(),
		cake_tier3_side: tierSide(CHOC, 11, [CREAM, PINK_LIGHT], 21),
		cake_choc_inner: innerSheet(SPONGE, JAM, 101),
		cake_straw_inner: innerSheet(PINK_SPONGE, BERRY, 111),
		cake_choc_top: chocolateTop(),
		cake_straw_top: strawberryTop(),
		cake_cream_top: creamTop(),
		cake_candle: candleSheet(),
		cake_wick: solid(WICK),
		cake_sparkler: sparklerSheet(),
		cake_spark: sparkSheet(),
		cake_flame: flameSheet(),
		cake_berry: berrySheet(),
		cake_leaf: leafSheet(),
		gift_wrap: wrapSheet(false),
		gift_lid_top: wrapSheet(true),
		gift_ribbon: ribbonSheet(),
		gift_bottom: solid(WRAP_DARK),
		party_hat: hatSheet(),
		party_pom: solid(POM),
		ring_gold: ribbonSheet(),
		ring_bloom: bloomSheet(),
		choc_dark: solid(CHOC_DARK),
		choc_milk: solid(MILK),
	};

	sheets['balloon_string'] = solid(SILVER_DARK);

	for (const colour of BALLOON_COLOURS) {
		const base = rgb(colour.colour);
		sheets[`balloon_${colour.id}`] = balloonSheet(base, 1);
		sheets[`balloon_${colour.id}_light`] = balloonSheet(base, 1.18);
		sheets[`balloon_${colour.id}_knot`] = solid(tint(base, 0.7));
	}

	return {
		...Object.fromEntries(Object.entries(sheets).map(([name, image]) => [name, new Uint8Array(encodePng(image))])),
		// her own skin, as the game served it, laid out for the plushie's faces
		phuynhi_plush: new Uint8Array(encodePng(plushSheet(readFileSync(join(import.meta.dir, 'assets/skins/phuynhi.png'))))),
	};
}

/** The flat sprites that go into `textures/item`, for the slot and the ground. */
export function birthdayItemSprites(): Record<string, Uint8Array> {
	const sheets: Record<string, Bitmap> = {
		chocolate_bar: chocolateBarSprite(CHOC_DARK, CHOC_LIGHT, WRAPPER_RED, FOIL),
		milk_chocolate_bar: chocolateBarSprite(MILK, MILK_LIGHT, WRAPPER_BLUE, WRAPPER_WHITE),
		birthday_hat: hatSprite(),
		flower_ring: ringSprite(),
		cake_plate: plateSprite(),
		sparkler: sparklerSprite(false),
		sparkler_lit: sparklerSprite(true),
	};

	return {
		...Object.fromEntries(Object.entries(sheets).map(([name, image]) => [name, new Uint8Array(encodePng(image))])),
		...treatSprites(),
	};
}

// ---- the models -----------------------------------------------------------------

const T = (name: string): string => `lunasmp:block/${name}`;

/**
 * How deep the cake is cut on each side after `bites` plates: the cuts go
 * north, east, south, west and round again, so side k has taken
 * ceil((bites - k) / 4) cuts.
 */
function cutDepths(bites: number): { north: number; east: number; south: number; west: number } {
	const taken = (side: number): number => Math.max(0, Math.ceil((bites - side) / 4)) * CUT;

	return { north: taken(0), east: taken(1), south: taken(2), west: taken(3) };
}

/**
 * A tier cut back by the given depths: a box between its own bounds and the
 * cuts, wearing its side sheet where the crust is left and the sponge sheet
 * where a knife went through. Returns nothing when the cuts ate the tier.
 */
function tier(
	bounds: [Vec3, Vec3],
	cuts: { north: number; east: number; south: number; west: number },
	side: string,
	inner: string,
	top: string,
	drawBottom: boolean,
): Element | undefined {
	const [from, to] = bounds;
	const x0 = Math.max(from[0], 1 + cuts.west);
	const x1 = Math.min(to[0], 15 - cuts.east);
	const z0 = Math.max(from[2], 1 + cuts.north);
	const z1 = Math.min(to[2], 15 - cuts.south);

	if (x1 - x0 < 0.5 || z1 - z0 < 0.5) {
		return undefined;
	}

	const faces = {
		all: side,
		up: top,
		down: side,
		north: z0 > from[2] ? inner : side,
		south: z1 < to[2] ? inner : side,
		west: x0 > from[0] ? inner : side,
		east: x1 < to[0] ? inner : side,
	};

	const only: Array<'north' | 'east' | 'south' | 'west' | 'up' | 'down'> = ['north', 'east', 'south', 'west', 'up'];

	if (drawBottom) {
		only.push('down');
	}

	return part([x0, from[1], z0], [x1, to[1], z1], faces, { only });
}

/** The lower block: the plate and the two big tiers, after `bites` plates have been cut. */
function lowerElements(bites: number): Element[] {
	const cuts = cutDepths(bites);
	const elements: Element[] = [
		part([0, 0, 0], [16, 1, 16], T('cake_plate'), { cull: ['down'] }),
	];

	const first = tier([[1, 1, 1], [15, 8, 15]], cuts, T('cake_tier1_side'), T('cake_choc_inner'), T('cake_choc_top'), false);
	const second = tier([[3, 8, 3], [13, 16, 13]], cuts, T('cake_tier2_side'), T('cake_straw_inner'), T('cake_straw_top'), false);

	for (const element of [first, second]) {
		if (element) {
			elements.push(element);
		}
	}

	return elements;
}

/** Where the four candles stand on the top tier, in model x/z. */
const CANDLES: Array<[number, number]> = [[6, 6], [10, 6], [6, 10], [10, 10]];

/** Where the strawberries sit: the middle of each edge of the top tier. */
const BERRIES: Array<[number, number]> = [[8, 5.25], [10.75, 8], [8, 10.75], [5.25, 8]];

/** Two crossed planes, drawn on both sides and unshaded: a flame or a spark. */
function crossed(texture: string, cx: number, cz: number, y0: number, y1: number, half: number): Element[] {
	const uv: [number, number, number, number] = [0, 0, 16, 16];

	return [
		{
			from: [cx - half, y0, cz],
			to: [cx + half, y1, cz],
			shade: false,
			faces: {
				north: { uv, texture },
				south: { uv, texture },
			},
		},
		{
			from: [cx, y0, cz - half],
			to: [cx, y1, cz + half],
			shade: false,
			faces: {
				west: { uv, texture },
				east: { uv, texture },
			},
		},
	];
}

/** The upper block: the top tier, its berries, four candles and the sparkler. */
function upperElements(lit: boolean): Element[] {
	const elements: Element[] = [
		part([4.5, 0, 4.5], [11.5, 5, 11.5], { all: T('cake_tier3_side'), up: T('cake_cream_top') }, { only: ['north', 'east', 'south', 'west', 'up'] }),
	];

	for (const [x, z] of BERRIES) {
		elements.push(part([x - 0.75, 5, z - 0.75], [x + 0.75, 6.5, z + 0.75], T('cake_berry'), { only: ['north', 'east', 'south', 'west', 'up'] }));
		elements.push(part([x - 0.5, 6.5, z - 0.5], [x + 0.5, 7, z + 0.5], T('cake_leaf'), { only: ['north', 'east', 'south', 'west', 'up'] }));
	}

	for (const [x, z] of CANDLES) {
		elements.push(part([x - 0.75, 5, z - 0.75], [x + 0.75, 10, z + 0.75], { all: T('cake_candle'), up: T('cake_candle') }, { only: ['north', 'east', 'south', 'west', 'up'] }));
		elements.push(part([x - 0.25, 10, z - 0.25], [x + 0.25, 10.75, z + 0.25], T('cake_wick'), { only: ['north', 'east', 'south', 'west', 'up'] }));

		if (lit) {
			elements.push(...crossed(T('cake_flame'), x, z, 10.5, 13, 1));
		}
	}

	// the sparkler: a thin stick in the middle, a star at its tip while it burns
	elements.push(part([7.75, 5, 7.75], [8.25, 13, 8.25], T('cake_sparkler'), { only: ['north', 'east', 'south', 'west', 'up'] }));

	if (lit) {
		elements.push(...crossed(T('cake_spark'), 8, 8, 11.5, 16, 2.25));
	}

	return elements;
}

/** Lifts every element by `dy`, for the one-piece picture of the whole tower. */
function lifted(elements: Element[], dy: number): Element[] {
	return elements.map((element) => {
		const copy = JSON.parse(JSON.stringify(element)) as Element;
		const from = copy.from as Vec3;
		const to = copy.to as Vec3;
		copy.from = [from[0], from[1] + dy, from[2]];
		copy.to = [to[0], to[1] + dy, to[2]];

		return copy;
	});
}

/**
 * The transforms the whole tower is shown with as an item: it is two blocks
 * tall, so it is drawn at about half the size of a block item and shifted
 * down so its middle, not its foot, sits at the slot's centre.
 */
const TOWER_DISPLAY = {
	gui: { rotation: [30, 225, 0], translation: [0, -2.4, 0], scale: [0.35, 0.35, 0.35] },
	ground: { rotation: [0, 0, 0], translation: [0, 1, 0], scale: [0.15, 0.15, 0.15] },
	fixed: { rotation: [0, 0, 0], translation: [0, -2, 0], scale: [0.25, 0.25, 0.25] },
	thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 1, 0], scale: [0.2, 0.2, 0.2] },
	thirdperson_lefthand: { rotation: [75, 315, 0], translation: [0, 1, 0], scale: [0.2, 0.2, 0.2] },
	firstperson_righthand: { rotation: [0, 45, 0], translation: [0, -2, 0], scale: [0.22, 0.22, 0.22] },
	firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, -2, 0], scale: [0.22, 0.22, 0.22] },
};

/** The gift box, lid down or lifted open on its back hinge. */
function giftBoxElements(open: boolean): Element[] {
	const elements: Element[] = [
		part([1.5, 0, 1.5], [14.5, 10, 14.5], { all: T('gift_wrap'), up: T('gift_bottom'), down: T('gift_bottom') }, { cull: ['down'] }),
	];

	const lid: Element[] = [
		part([1, 9, 1], [15, 12, 15], { all: T('gift_wrap'), up: T('gift_lid_top') }, { only: ['north', 'east', 'south', 'west', 'up'] }),
		// the bow: a knot with a loop either side and two tails
		part([6.5, 12, 6.5], [9.5, 14, 9.5], T('gift_ribbon')),
		part([3, 12, 6.75], [6.5, 15, 9.25], T('gift_ribbon')),
		part([9.5, 12, 6.75], [13, 15, 9.25], T('gift_ribbon')),
		part([5.5, 12, 9.5], [7, 12.5, 12.5], T('gift_ribbon')),
		part([9, 12, 9.5], [10.5, 12.5, 12.5], T('gift_ribbon')),
	];

	if (open) {
		// vanilla models only turn by 22.5-degree steps: the lid swings up on
		// its back edge and lifts a little, the way a shulker's lid rises
		for (const element of lid) {
			const from = element.from as Vec3;
			const to = element.to as Vec3;
			element.from = [from[0], from[1] + 1, from[2]];
			element.to = [to[0], to[1] + 1, to[2]];
			element.rotation = { origin: [8, 10, 15], axis: 'x', angle: 22.5 };
		}
	}

	elements.push(...lid);

	return elements;
}

/** A plate with one slice on it: two stacked wedges of the lower tiers and a berry. */
function platedCakeElements(): Element[] {
	return [
		part([3, 0, 3], [13, 1, 13], T('cake_plate'), { cull: ['down'] }),
		part([6, 1, 5], [10, 5, 11], {
			all: T('cake_choc_inner'),
			north: T('cake_tier1_side'),
			up: T('cake_choc_top'),
		}, { only: ['north', 'east', 'south', 'west', 'up'] }),
		part([6.5, 5, 6], [9.5, 8, 10], {
			all: T('cake_straw_inner'),
			north: T('cake_tier2_side'),
			up: T('cake_straw_top'),
		}, { only: ['north', 'east', 'south', 'west', 'up'] }),
		part([7.25, 8, 7.25], [8.75, 9.5, 8.75], T('cake_berry'), { only: ['north', 'east', 'south', 'west', 'up'] }),
		part([7.5, 9.5, 7.5], [8.5, 10, 8.5], T('cake_leaf'), { only: ['north', 'east', 'south', 'west', 'up'] }),
	];
}

// ---- the plushie ----------------------------------------------------------------------------

/** A face's uv on the plush sheet, in the model's sixteenths. */
type Uv = [number, number, number, number];

/** The plush sheet is the 64x64 skin with a 64-pixel margin on the right for the turned leg strips. */
const SHEET_W = 128;
const SHEET_H = 64;

/** Pixel coordinates on the sheet to model uv: sixteen units across the whole sheet each way. */
function uv(x1: number, y1: number, x2: number, y2: number): Uv {
	return [(x1 * 16) / SHEET_W, (y1 * 16) / SHEET_H, (x2 * 16) / SHEET_W, (y2 * 16) / SHEET_H];
}

/**
 * The six uv windows of one limb of a player skin: the strips run right
 * side, front, left side, back along the row under the top and bottom
 * squares, the layout every skin has used since 1.8. `slim` arms are three
 * pixels wide and the skin leaves their fourth column empty. Nothing here is
 * turned or mirrored on the face: a face that needs its strip turned reads a
 * turned copy the sheet carries instead.
 */
function skinFaces(u0: number, v0: number, w: number, h: number, d: number): Record<string, Uv> {
	return {
		east: uv(u0, v0 + d, u0 + d, v0 + d + h),
		north: uv(u0 + d, v0 + d, u0 + d + w, v0 + d + h),
		west: uv(u0 + d + w, v0 + d, u0 + 2 * d + w, v0 + d + h),
		south: uv(u0 + 2 * d + w, v0 + d, u0 + 2 * d + 2 * w, v0 + d + h),
		up: uv(u0 + d, v0, u0 + d + w, v0 + d),
		down: uv(u0 + d + w, v0 + d, u0 + d + 2 * w, v0),
	};
}

/** The four leg layers, each with its skin offset, in the order their turned strips sit on the sheet. */
const LEG_LAYERS: Array<[number, number]> = [[16, 48], [0, 48], [0, 16], [0, 32]];

/** Where a leg layer's turned side strips start on the sheet: two 12x4 strips per layer, east then west. */
function turnedStrip(layer: number, side: 'east' | 'west'): [number, number] {
	return [64 + (side === 'east' ? 0 : 12) + (layer % 2) * 24, Math.floor(layer / 2) * 4];
}

/**
 * The plush sheet: the skin as it is, plus a copy of each leg's two side
 * strips turned a quarter so a leg lying on the floor can wear them with a
 * plain uv. Face texture rotation is exactly the kind of thing two renderers
 * disagree about, so the sheet carries the turn and the model asks for none.
 *
 * A standing leg's side strip runs hip to foot downward and back to front
 * across; lying down with the shin up, the east face reads hip on its left
 * (south) and shin along its top, and the west face the mirror of that.
 */
function plushSheet(skinPng: Uint8Array): Bitmap {
	const skin = decodePng(skinPng);
	const sheet = bitmap(SHEET_W, SHEET_H);

	const pixel = (x: number, y: number): Rgba => {
		const at = (y * skin.width + x) * 4;
		return [skin.data[at]!, skin.data[at + 1]!, skin.data[at + 2]!, skin.data[at + 3]!];
	};

	for (let y = 0; y < 64; y++) {
		for (let x = 0; x < 64; x++) {
			put(sheet, x, y, pixel(x, y));
		}
	}

	LEG_LAYERS.forEach(([u0, v0], layer) => {
		const [ex, ey] = turnedStrip(layer, 'east');
		const [wx, wy] = turnedStrip(layer, 'west');

		for (let sy = 0; sy < 12; sy++) {
			for (let sx = 0; sx < 4; sx++) {
				// the right-side strip (east face, its right edge joining the
				// front) and the left-side strip (west face, its left edge
				// joining the front) both keep hip on the body's side
				put(sheet, ex + sy, ey + (3 - sx), pixel(u0 + sx, v0 + 4 + sy));
				put(sheet, wx + (11 - sy), wy + sx, pixel(u0 + 4 + 4 + sx, v0 + 4 + sy));
			}
		}
	});

	return sheet;
}

/** A box wearing the sheet windows given, one per face. */
function skinBox(from: Vec3, to: Vec3, texture: string, faces: Record<string, Uv>): Element {
	const out: Record<string, unknown> = {};

	for (const [dir, window] of Object.entries(faces)) {
		out[dir] = { uv: window, texture };
	}

	return { from, to, faces: out };
}

/** The same box grown by `by` on every side: the skin's second layer. */
function inflate(from: Vec3, to: Vec3, by: number): [Vec3, Vec3] {
	return [
		[from[0] - by, from[1] - by, from[2] - by],
		[to[0] + by, to[1] + by, to[2] + by],
	];
}

/**
 * A plushie of a player: their skin on a sitting doll, three quarters the
 * size of the player with the head kept full size, which is what makes it
 * read as a plush rather than a statue. Legs out in front on the floor, the
 * body on them, the head on top, arms hanging straight at the sides. Every
 * part wears its skin layer and its overlay layer, so hair, sleeves and
 * straps come along; overlay pixels the skin leaves clear draw nothing.
 * Every box is axis-aligned and every face a plain window: nothing here
 * depends on which way a renderer turns a texture or an element.
 */
function plushieElements(sheet: string, slim: boolean): Element[] {
	const arm = slim ? 3 : 4;
	const armWidth = arm * 0.75;
	const elements: Element[] = [];

	const layer = (from: Vec3, to: Vec3, base: Record<string, Uv>, overlay: Record<string, Uv>): void => {
		elements.push(skinBox(from, to, sheet, base));
		const [f, t] = inflate(from, to, 0.25);
		elements.push(skinBox(f, t, sheet, overlay));
	};

	// legs: 4 x 12 x 4 in the skin, laid flat 3 wide, 3 tall, 9 long with
	// the shin on top. The shin strip is read hip-first from the south, so
	// its window is flipped in v; the sides come off the sheet's turned copies
	const legFaces = (u0: number, v0: number, layerIndex: number): Record<string, Uv> => {
		const f = skinFaces(u0, v0, 4, 12, 4);
		const [ex, ey] = turnedStrip(layerIndex, 'east');
		const [wx, wy] = turnedStrip(layerIndex, 'west');
		const shin = f.north!;
		const calf = f.south!;

		return {
			up: [shin[0], shin[3], shin[2], shin[1]],
			down: [calf[0], calf[3], calf[2], calf[1]],
			east: uv(ex, ey, ex + 12, ey + 4),
			west: uv(wx, wy, wx + 12, wy + 4),
			north: f.down!,
			south: f.up!,
		};
	};

	layer([4.75, 0, 1], [7.75, 3, 10], legFaces(16, 48, 0), legFaces(0, 48, 1));
	layer([8.25, 0, 1], [11.25, 3, 10], legFaces(0, 16, 2), legFaces(0, 32, 3));

	// body: 8 x 12 x 4, three quarters, sitting on the legs
	layer([5, 3, 7], [11, 12, 10], skinFaces(16, 16, 8, 12, 4), skinFaces(16, 32, 8, 12, 4));

	// arms hang straight at the sides, three quarters of a pixel off the
	// body: both wear an overlay a quarter pixel proud, and two overlays
	// meeting on one plane flicker against each other
	layer([11.75, 3.5, 7], [11.75 + armWidth, 12.5, 10], skinFaces(40, 16, arm, 12, 4), skinFaces(40, 32, arm, 12, 4));
	layer([4.25 - armWidth, 3.5, 7], [4.25, 12.5, 10], skinFaces(32, 48, arm, 12, 4), skinFaces(48, 48, arm, 12, 4));

	// the head, full size, a little forward of the body; its overlay is the
	// skin's hat layer and sits half a pixel proud, like the game's own
	elements.push(skinBox([4, 12, 4.5], [12, 20, 12.5], sheet, skinFaces(0, 0, 8, 8, 8)));
	const [hf, ht] = inflate([4, 12, 4.5], [12, 20, 12.5], 0.5);
	elements.push(skinBox(hf, ht, sheet, skinFaces(32, 0, 8, 8, 8)));

	return elements;
}

/** The plushie's slot picture: it is twenty pixels tall, so it is drawn a little smaller than a block. */
const PLUSHIE_DISPLAY = {
	gui: { rotation: [30, 225, 0], translation: [0, -1.5, 0], scale: [0.5, 0.5, 0.5] },
	ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.25, 0.25, 0.25] },
	fixed: { rotation: [0, 0, 0], translation: [0, -1, 0], scale: [0.4, 0.4, 0.4] },
	thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2, 0], scale: [0.3, 0.3, 0.3] },
	thirdperson_lefthand: { rotation: [75, 315, 0], translation: [0, 2, 0], scale: [0.3, 0.3, 0.3] },
	firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.35, 0.35, 0.35] },
	firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.35, 0.35, 0.35] },
};

/** The empty stand: a gold foot and stem carrying the plate the cake will be set on. */
function cakeStandElements(): Element[] {
	const gold = T('gift_ribbon');

	return [
		part([5, 0, 5], [11, 1, 11], gold, { cull: ['down'] }),
		part([7, 1, 7], [9, 4, 9], gold, { only: ['north', 'east', 'south', 'west'] }),
		part([0.5, 4, 0.5], [15.5, 5, 15.5], { all: gold, up: T('cake_plate'), down: T('cake_plate') }),
	];
}

/**
 * The party hat, worn: a cone of shrinking boxes standing on the crown of the
 * head (the head is the 0..16 cube in the head display) with a pom-pom on the
 * point. Boxes rather than a turned plane, because a cone of six stacked
 * squares reads as a party hat at Minecraft's resolution and a smooth one
 * would not look like it belongs.
 */
function hatElements(): Element[] {
	const elements: Element[] = [];
	const steps = 6;
	const height = 13;

	// the base starts a pixel inside the head: the skin's hat layer stands
	// half a pixel proud of the head cube, and a cone that began exactly on
	// top of the cube showed a seam of hair between the two
	for (let i = 0; i < steps; i++) {
		const half = 6 - (i * 5) / steps;
		const y0 = 15 + (i * height) / steps;
		const y1 = 15 + ((i + 1) * height) / steps;
		elements.push(part([8 - half, y0, 8 - half], [8 + half, y1, 8 + half], T('party_hat'), { only: ['north', 'east', 'south', 'west', 'up'] }));
	}

	elements.push(part([6.75, 28, 6.75], [9.25, 30.5, 9.25], T('party_pom')));

	return elements;
}

/** The ring, held: eight short gold bars round an octagon, a bloom standing on the top one. */
function ringElements(): Element[] {
	const elements: Element[] = [];
	const radius = 4.5;
	const gold = T('ring_gold');
	const length = 3.8;
	const thick = 1.3;

	// the band lies in the y/z plane so it reads as a ring held up to look
	// at. Each bar runs along the ring's tangent: the four on the axes are
	// plain boxes, long in y or in z; the four between them are z-long boxes
	// turned 45 degrees about x, the only turn a model allows that fits
	for (let i = 0; i < 8; i++) {
		const angle = (i * Math.PI) / 4;
		const cy = 8 + Math.cos(angle) * radius;
		const cz = 8 + Math.sin(angle) * radius;

		if (i % 2 === 0) {
			const alongZ = i % 4 === 0;
			const dy = alongZ ? thick / 2 : length / 2;
			const dz = alongZ ? length / 2 : thick / 2;
			elements.push(part([7.25, cy - dy, cz - dz], [8.75, cy + dy, cz + dz], gold));
		} else {
			const bar = part([7.25, cy - thick / 2, cz - length / 2], [8.75, cy + thick / 2, cz + length / 2], gold);
			// the sign is the game's, not the previewer's: the previewer turns
			// elements the other way, and showed these bars radial when they
			// are tangent in game
			bar.rotation = { origin: [8, cy, cz], axis: 'x', angle: i === 1 || i === 5 ? -45 : 45 };
			elements.push(bar);
		}
	}

	// the setting on the top of the band, and the bloom standing in it
	elements.push(part([7, 12.25, 7], [9, 14, 9], T('gift_ribbon')));
	elements.push(...crossed(T('ring_bloom'), 8, 8, 13.5, 16.5, 1.5));

	return elements;
}

/**
 * The sparkler, held: a wire with the coated tip, and while it burns the same
 * star the cake's sparkler wears. Authored standing up the middle of the
 * block, the way a torch is, so vanilla's hand-held transforms carry it.
 */
function sparklerElements(lit: boolean): Element[] {
	const elements: Element[] = [
		part([7.6, 0, 7.6], [8.4, 9, 8.4], T('cake_sparkler')),
		part([7.25, 9, 7.25], [8.75, 15, 8.75], lit ? T('cake_flame') : T('cake_wick')),
	];

	if (lit) {
		elements.push(...crossed(T('cake_spark'), 8, 8, 12.5, 18.5, 3));
	}

	return elements;
}

/** Vanilla's own hand-held transforms: what a torch or a sword is held with. */
const HANDHELD_DISPLAY = {
	thirdperson_righthand: { rotation: [0, -90, 55], translation: [0, 4, 0.5], scale: [0.85, 0.85, 0.85] },
	thirdperson_lefthand: { rotation: [0, 90, -55], translation: [0, 4, 0.5], scale: [0.85, 0.85, 0.85] },
	firstperson_righthand: { rotation: [0, -90, 25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
	firstperson_lefthand: { rotation: [0, 90, -25], translation: [1.13, 3.2, 1.13], scale: [0.68, 0.68, 0.68] },
	gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
	ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
	fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
};

/** The transforms the ring is shown with: bigger in the slot, where its detail is. */
const RING_DISPLAY = {
	gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.9, 0.9, 0.9] },
	ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.4, 0.4, 0.4] },
	fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.7, 0.7, 0.7] },
	thirdperson_righthand: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.4, 0.4, 0.4] },
	thirdperson_lefthand: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.4, 0.4, 0.4] },
	firstperson_righthand: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
	firstperson_lefthand: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
};

/** Every block model of the set, by file name. */
export function birthdayModels(): Record<string, Model> {
	const models: Record<string, Model> = {};

	models['birthday_cake'] = composed(lowerElements(0), T('cake_tier1_side'));

	for (let bites = 1; bites <= CAKE_SLICES; bites++) {
		models[`birthday_cake_bite${bites}`] = composed(lowerElements(bites), T('cake_tier1_side'));
	}

	models['birthday_cake_top'] = composed(upperElements(true), T('cake_tier3_side'));
	models['birthday_cake_top_off'] = composed(upperElements(false), T('cake_tier3_side'));

	const tower = composed([...lowerElements(0), ...lifted(upperElements(true), 16)], T('cake_tier1_side'));
	tower.display = TOWER_DISPLAY;
	models['birthday_cake_full'] = tower;

	models['gift_box'] = composed(giftBoxElements(false), T('gift_wrap'));
	models['gift_box_open'] = composed(giftBoxElements(true), T('gift_wrap'));
	models['plated_cake'] = composed(platedCakeElements(), T('cake_plate'));
	models['cake_stand'] = composed(cakeStandElements(), T('cake_plate'));

	// her skin was read at generate time: slim arms, all overlay layers in use
	for (const bunch of BALLOON_BUNCHES) {
		const colour = bunch.id.slice('balloon_bunch_'.length);
		const particle = T(`balloon_${colour === 'mix' ? 'pink' : colour}`);
		models[bunch.id] = composed(bunchElements(colour), particle);

		// the slot cannot show three blocks: the same bunch at a third, built
		// from fresh elements since composing rewrites the faces it is given
		const item = composed(shrunk(bunchElements(colour), 1 / 3), particle);
		item.display = BUNCH_ITEM_DISPLAY;
		models[`${bunch.id}_item`] = item;
	}

	const plushie = composed(plushieElements(T('phuynhi_plush'), true), T('phuynhi_plush'));
	plushie.display = PLUSHIE_DISPLAY;
	models['phuynhi_plushie'] = plushie;

	return models;
}

/** The item models of the set that are not a block's: the hat and the ring, by file name. */
export function birthdayItemModels(): Record<string, Model> {
	const hat = composed(hatElements(), T('party_hat'));
	hat.display = CROWN_DISPLAY;

	const ring = composed(ringElements(), T('ring_gold'));
	ring.display = RING_DISPLAY;

	const sparkler = composed(sparklerElements(false), T('cake_sparkler'));
	sparkler.display = HANDHELD_DISPLAY;

	const sparklerLit = composed(sparklerElements(true), T('cake_sparkler'));
	sparklerLit.display = HANDHELD_DISPLAY;

	return {
		birthday_hat: hat,
		flower_ring: ring,
		sparkler,
		sparkler_lit: sparklerLit,
	};
}

// ---- balloons -----------------------------------------------------------------
//
// A bunch of five balloons tied to one point on the floor. Every string
// leaves the anchor at its own angle and its balloon continues the line, so
// the bunch splays the way a real one does when the strings are all held at
// the bottom. The whole thing is authored at true size, three blocks tall;
// Nova shrinks a display model past the 32-unit limit and scales the entity
// back up, so the block needs nothing more. The item gets the same geometry
// at a third, which fits the slot.
//
// Each balloon is boxes stacked into a silhouette, its upper layers a shade
// lighter so the top catches the light the way latex does. The balloon and
// its string share one rotation about the anchor, which is what keeps them
// rigid to each other whatever the angle.

/** Latex: the colour with a faint sheen of noise, so a big face is not one flat pixel. */
function balloonSheet(colour: Rgba, factor: number): Bitmap {
	const image = bitmap(16, 16);
	crumb(image, 0, 0, 16, 16, tint(colour, factor), 131, 0.03);

	return image;
}

/** Where all the strings are tied. */
const ANCHOR: Vec3 = [8, 0, 8];

/** A horizontal slab centred on the axis, `half` wide either side of it, `depth` deep. */
function slab(y0: number, y1: number, half: number, depth: number, texture: string): Element {
	return part([8 - half, y0, 8 - depth / 2], [8 + half, y1, 8 + depth / 2], texture);
}

/**
 * One balloon standing upright on the axis with the bottom of its knot at
 * `y`, and the string from the anchor up to it. The shape's bottom is the
 * knot's top, `y + 1.25`.
 */
function balloon(shape: string, colour: string, y: number): Element[] {
	const base = T(`balloon_${colour}`);
	const light = T(`balloon_${colour}_light`);
	const knot = T(`balloon_${colour}_knot`);
	const b = y + 1.25;

	const elements: Element[] = [
		part([7.75, 0, 7.75], [8.25, y, 8.25], T('balloon_string')),
		part([7.25, y, 7.25], [8.75, b, 8.75], knot),
	];

	switch (shape) {
		case 'round': {
			// a stepped sphere: six layers, the widest two in the middle
			const halves = [2.5, 4, 5, 5, 4, 2.5];

			for (let i = 0; i < halves.length; i++) {
				const y0 = b + i * 1.75;
				elements.push(slab(y0, y0 + 1.75, halves[i]!, halves[i]! * 2, i >= 4 ? light : base));
			}

			break;
		}

		case 'long': {
			// a capsule: narrower than the round one and a little taller
			const layers: [number, number][] = [[1.5, 2], [2, 3], [2, 3.5], [2, 3.5], [2, 3.5], [2, 3], [1.5, 2]];
			let top = b;

			for (let i = 0; i < layers.length; i++) {
				const [height, half] = layers[i]!;
				elements.push(slab(top, top + height, half, half * 2, i >= 5 ? light : base));
				top += height;
			}

			break;
		}

		case 'heart': {
			// a point at the knot widening to two lobes at the top, four deep
			elements.push(slab(b, b + 2, 1, 4, base));
			elements.push(slab(b + 2, b + 4, 3, 4, base));
			elements.push(slab(b + 4, b + 6.5, 5, 4, base));
			elements.push(part([2.5, b + 6.5, 6], [7.5, b + 8.5, 10], light));
			elements.push(part([8.5, b + 6.5, 6], [13.5, b + 8.5, 10], light));
			elements.push(part([7.5, b + 6.5, 6], [8.5, b + 7.5, 10], light));
			elements.push(part([3.5, b + 8.5, 6], [6.5, b + 9.5, 10], light));
			elements.push(part([9.5, b + 8.5, 6], [12.5, b + 9.5, 10], light));
			break;
		}

		case 'star': {
			// a five-pointed star, three deep: a body, a spike up, two arms out and two legs down
			const z0 = 6.5;
			const z1 = 9.5;
			const box = (x0: number, y0: number, x1: number, y1: number, texture: string): Element =>
				part([x0, b + y0, z0], [x1, b + y1, z1], texture);

			elements.push(box(5, 2.5, 11, 6.5, base));
			elements.push(box(6.5, 6.5, 9.5, 8, light));
			elements.push(box(7.25, 8, 8.75, 9.5, light));
			elements.push(box(2.5, 4.5, 5, 6.25, base));
			elements.push(box(1, 5, 2.5, 5.75, base));
			elements.push(box(11, 4.5, 13.5, 6.25, base));
			elements.push(box(13.5, 5, 15, 5.75, base));
			elements.push(box(4.5, 1, 6.5, 2.5, base));
			elements.push(box(3.75, 0, 5.25, 1, base));
			elements.push(box(9.5, 1, 11.5, 2.5, base));
			elements.push(box(10.75, 0, 12.25, 1, base));
			break;
		}
	}

	return elements;
}

/** Tilts a balloon and its string together about the anchor. */
function splayed(elements: Element[], axis: 'x' | 'z', angle: number): Element[] {
	if (angle === 0) {
		return elements;
	}

	return elements.map((element) => ({ ...element, rotation: { origin: ANCHOR, axis, angle } }));
}

/** Where each of the five balloons goes: its tilt and how long its string is. */
const BUNCH_LAYOUT: { shape: string; axis: 'x' | 'z'; angle: number; length: number }[] = [
	{ shape: 'heart', axis: 'z', angle: 0, length: 35 },
	{ shape: 'round', axis: 'z', angle: 22.5, length: 30 },
	{ shape: 'star', axis: 'z', angle: -22.5, length: 26 },
	{ shape: 'long', axis: 'x', angle: 22.5, length: 31 },
	{ shape: 'round', axis: 'x', angle: -22.5, length: 24 },
];

/** The mixed bunch's colours, in layout order. */
const MIX_COLOURS = ['pink', 'red', 'gold', 'blue', 'purple'];

/**
 * A bunch: five balloons in one colour with the heart red, or the mixed one.
 * Kept as a table so a bunch is one line per balloon.
 */
function bunchElements(colour: string): Element[] {
	const elements: Element[] = [];

	for (let i = 0; i < BUNCH_LAYOUT.length; i++) {
		const spot = BUNCH_LAYOUT[i]!;
		const own = colour === 'mix'
			? MIX_COLOURS[i]!
			: spot.shape === 'heart' && colour !== 'red' && colour !== 'white'
				? 'red'
				: colour;

		elements.push(...splayed(balloon(spot.shape, own, spot.length), spot.axis, spot.angle));
	}

	return elements;
}

/** The same geometry scaled about the anchor, for a model that has to fit a slot. */
function shrunk(elements: Element[], factor: number): Element[] {
	const scalePoint = (p: Vec3): Vec3 => [8 + (p[0] - 8) * factor, p[1] * factor, 8 + (p[2] - 8) * factor];

	return elements.map((element) => ({
		...element,
		from: scalePoint(element.from),
		to: scalePoint(element.to),
		rotation: element.rotation ? { ...element.rotation, origin: scalePoint(element.rotation.origin) } : undefined,
	}));
}

const BUNCH_ITEM_DISPLAY = {
	gui: { rotation: [30, 225, 0], translation: [0, -1, 0], scale: [0.9, 0.9, 0.9] },
	ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.4, 0.4, 0.4] },
	fixed: { rotation: [0, 0, 0], translation: [0, -2, 0], scale: [0.7, 0.7, 0.7] },
	thirdperson_righthand: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
	thirdperson_lefthand: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.5, 0.5, 0.5] },
	firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 1, 0], scale: [0.5, 0.5, 0.5] },
	firstperson_lefthand: { rotation: [0, 315, 0], translation: [0, 1, 0], scale: [0.5, 0.5, 0.5] },
};
