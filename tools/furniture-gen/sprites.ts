// The painted flora textures: everything the composed models cannot borrow
// from vanilla is drawn here, fresh on every generator run and committed to
// nothing.
//
// The construction copies the original mod's, because the user asked for
// exactly its look; the pixels are all ours. Three kinds of picture:
//
// - a bush is one dense leaf field drawn on every face of a 14x14x14 box,
//   which only works because the field is nearly solid - the earlier attempt
//   with a sparse borrowed sprite turned to camouflage noise;
// - a flowering mossy stone is a mostly-transparent overlay laid over the
//   client's own cobblestone or stone bricks, so the stone underneath is
//   never ours to redistribute;
// - a vine is wandering leafy strands with blooms, hung as a flat sheet.
//
// Everything is deterministic per block id, so a re-run of the generator
// never repaints a texture behind anyone's back.

import { type Bitmap, bitmap, encodePng } from '/home/belikhun/luna-console/web/src/lib/server/imaging/png';

type Rgb = [number, number, number];

/** The bush leaf palette, dark to light; close to vanilla's own bush greens. */
const BUSH_GREENS: Rgb[] = [
	[30, 72, 29],
	[40, 94, 37],
	[50, 118, 46],
	[62, 146, 57],
];

/** Moss on cobblestone: dark and damp. */
const COBBLE_MOSS: Rgb[] = [
	[28, 70, 27],
	[36, 90, 34],
	[44, 110, 42],
];

/** Moss on stone bricks: brighter and yellower, the way sunlit moss reads. */
const BRICK_MOSS: Rgb[] = [
	[56, 106, 30],
	[70, 134, 38],
	[86, 162, 47],
];

/** A tiny deterministic hash in [0, 1): the same texture always paints the same. */
function noise(seed: number, x: number, y: number): number {
	let h = seed ^ Math.imul(x + 11, 374761393) ^ Math.imul(y + 7, 668265263);
	h = Math.imul(h ^ (h >>> 13), 1274126177);

	return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

/**
 * Smooth value noise: hash values on a coarse lattice, blended bilinearly, so
 * a threshold over it cuts connected organic patches instead of speckle.
 */
function blobNoise(seed: number, x: number, y: number, cell: number): number {
	const gx = Math.floor(x / cell);
	const gy = Math.floor(y / cell);
	const fx = x / cell - gx;
	const fy = y / cell - gy;

	const a = noise(seed, gx, gy);
	const b = noise(seed, gx + 1, gy);
	const c = noise(seed, gx, gy + 1);
	const d = noise(seed, gx + 1, gy + 1);

	const top = a + (b - a) * fx;
	const bottom = c + (d - c) * fx;

	return top + (bottom - top) * fy;
}

function seedOf(id: string): number {
	let h = 2166136261;

	for (let i = 0; i < id.length; i++) {
		h = Math.imul(h ^ id.charCodeAt(i), 16777619);
	}

	return h >>> 0;
}

function put(image: Bitmap, x: number, y: number, rgb: Rgb): void {
	if (x < 0 || y < 0 || x > 15 || y > 15) {
		return;
	}

	const at = (y * 16 + x) * 4;
	image.data[at] = rgb[0];
	image.data[at + 1] = rgb[1];
	image.data[at + 2] = rgb[2];
	image.data[at + 3] = 255;
}

function filled(image: Bitmap, x: number, y: number): boolean {
	return x >= 0 && y >= 0 && x <= 15 && y <= 15 && image.data[(y * 16 + x) * 4 + 3]! > 0;
}

function parse(hex: string): Rgb {
	return [
		parseInt(hex.slice(1, 3), 16),
		parseInt(hex.slice(3, 5), 16),
		parseInt(hex.slice(5, 7), 16),
	];
}

function shade(rgb: Rgb, f: number): Rgb {
	return [
		Math.min(255, Math.round(rgb[0] * f)),
		Math.min(255, Math.round(rgb[1] * f)),
		Math.min(255, Math.round(rgb[2] * f)),
	];
}

/**
 * One flower cluster, the plus shape every reference texture builds its blooms
 * from: a dark heart with four petals around it, one petal in the paler shade.
 */
function cluster(image: Bitmap, x: number, y: number, accents: [string, string], pick: number): void {
	const primary = parse(accents[0]);
	const secondary = parse(accents[1]);
	const heart = shade(primary, 0.55);

	const arms: Array<[number, number]> = [
		[x, y - 1],
		[x + 1, y],
		[x, y + 1],
		[x - 1, y],
	];

	put(image, x, y, heart);

	for (let i = 0; i < arms.length; i++) {
		const [ax, ay] = arms[i]!;

		put(image, ax, ay, i === (pick & 3) ? secondary : primary);
	}
}

/**
 * Scatters cluster centres with breathing room: candidates are sampled from
 * the noise, kept when they land on an allowed pixel and clear of each other.
 */
function scatter(
	seed: number,
	count: number,
	spacing: number,
	allowed: (x: number, y: number) => boolean,
): Array<[number, number]> {
	const spots: Array<[number, number]> = [];

	for (let attempt = 0; attempt < 96 && spots.length < count; attempt++) {
		const x = 2 + Math.floor(noise(seed ^ 0x85ebca6b, attempt, 1) * 12);
		const y = 2 + Math.floor(noise(seed ^ 0xc2b2ae35, 2, attempt) * 12);

		if (!allowed(x, y)) {
			continue;
		}

		if (spots.some(([sx, sy]) => Math.abs(sx - x) + Math.abs(sy - y) < spacing)) {
			continue;
		}

		spots.push([x, y]);
	}

	return spots;
}

/**
 * Paints one flowering bush face: a dense leaf field with nicked edges and the
 * flower's clusters scattered through it, returned as PNG bytes. The model
 * shows columns 1..14 on its sides and the full square on top, so the field
 * fills the whole tile and only the rim is allowed to fray.
 */
export function bushSprite(id: string, accents: [string, string]): Uint8Array {
	const seed = seedOf(id);
	const image = bitmap(16, 16);

	for (let y = 0; y <= 15; y++) {
		for (let x = 0; x <= 15; x++) {
			const edge = Math.min(x, y, 15 - x, 15 - y);

			// the rim frays and the corners round off, so a wall of bushes
			// reads as foliage rather than dice
			if (edge === 0) {
				const corner = (x === 0 || x === 15) && (y === 0 || y === 15);

				if (corner && noise(seed ^ 0x27d4eb2f, x, y) < 0.8) {
					continue;
				}

				if (!corner && noise(seed ^ 0x27d4eb2f, x, y) > 0.6) {
					continue;
				}
			}

			// the odd interior hole, the way every leaf texture lets light in
			if (edge > 0 && noise(seed ^ 0x9e3779b9, x, y) > 0.988) {
				continue;
			}

			const tone = noise(seed ^ 0x5bd1e995, x >> 1, y >> 1) * 0.62
				+ noise(seed ^ 0x165667b1, x, y) * 0.38
				- y * 0.012;

			const rgb = tone > 0.72
				? BUSH_GREENS[3]!
				: tone > 0.48
					? BUSH_GREENS[2]!
					: tone > 0.24
						? BUSH_GREENS[1]!
						: BUSH_GREENS[0]!;

			put(image, x, y, rgb);
		}
	}

	const spots = scatter(seed, 6, 5, (x, y) => filled(image, x, y));

	for (let i = 0; i < spots.length; i++) {
		const [x, y] = spots[i]!;

		cluster(image, x, y, accents, Math.floor(noise(seed, x, y) * 4));
	}

	// single stray petals between the clusters, so the spread looks grown
	// rather than stamped
	const strays = scatter(seed ^ 0x2545f491, 4, 3, (x, y) =>
		filled(image, x, y) && spots.every(([sx, sy]) => Math.abs(sx - x) + Math.abs(sy - y) >= 3));

	for (const [x, y] of strays) {
		put(image, x, y, parse(accents[0]));
	}

	return encodePng(image);
}

/**
 * Paints the moss-and-flowers overlay one flowering stone lays over the
 * client's own texture: transparent where the stone shows through, moss in
 * connected patches hanging in from the edges, the flower's clusters rooted
 * on the moss. Cobblestone moss is darker and denser than the bricks'.
 */
export function mossOverlay(id: string, stone: 'mossy_cobblestone' | 'mossy_stone_bricks', accents: [string, string]): Uint8Array {
	const seed = seedOf(`${id}/${stone}`);
	const image = bitmap(16, 16);
	const cobble = stone === 'mossy_cobblestone';
	const palette = cobble ? COBBLE_MOSS : BRICK_MOSS;

	// the same patch shape at every seed would still cover wildly different
	// areas, so the threshold is taken from the tile's own value distribution
	// and every stone carries the same amount of moss
	const blobs: number[] = [];

	for (let y = 0; y <= 15; y++) {
		for (let x = 0; x <= 15; x++) {
			blobs.push(blobNoise(seed ^ 0x7f4a7c15, x, y, 5) * 0.72
				+ blobNoise(seed ^ 0x84222325, x, y, 2) * 0.18
				+ noise(seed ^ 0x9e3779b9, x, y) * 0.1);
		}
	}

	const sorted = [...blobs].sort((a, b) => a - b);
	const cut = sorted[Math.floor(256 * (cobble ? 0.44 : 0.58))]!;

	for (let y = 0; y <= 15; y++) {
		for (let x = 0; x <= 15; x++) {
			const blob = blobs[y * 16 + x]!;

			// moss creeps in from above: the drip band vanilla's own overlays
			// hang from the top edge
			const drip = noise(seed ^ 0x38495ab5, x, 7);
			const dripped = drip > 0.4 && y <= Math.floor((drip - 0.4) * 6);

			if (blob < cut && !dripped) {
				continue;
			}

			const tone = noise(seed ^ 0x5bd1e995, x >> 1, y >> 1) * 0.6
				+ noise(seed ^ 0x165667b1, x, y) * 0.4;

			const rgb = tone > 0.66
				? palette[2]!
				: tone > 0.33
					? palette[1]!
					: palette[0]!;

			put(image, x, y, rgb);
		}
	}

	const spots = scatter(seed, cobble ? 6 : 5, 5, (x, y) => filled(image, x, y));

	for (let i = 0; i < spots.length; i++) {
		const [x, y] = spots[i]!;

		cluster(image, x, y, accents, Math.floor(noise(seed, x, y) * 4));
	}

	return encodePng(image);
}

/**
 * Paints one flowering vine sheet: three leafy strands wandering down the
 * tile with the flower's clusters opening along them. The wander is drift-
 * corrected so a strand leaves the bottom edge where it entered the top and
 * stacked vines join up.
 */
export function vineSprite(id: string, accents: [string, string]): Uint8Array {
	const seed = seedOf(`${id}/vine`);
	const image = bitmap(16, 16);
	const strandRoots = [2, 8, 13];

	for (let s = 0; s < strandRoots.length; s++) {
		const base = strandRoots[s]! + (noise(seed, s, 99) > 0.5 ? 1 : 0);

		// a random walk, then the drift is paid back evenly so row 16 lands
		// where row 0 started
		const offsets: number[] = [0];

		for (let y = 1; y <= 16; y++) {
			const step = noise(seed ^ 0x2545f491, s, y);
			const move = step > 0.72
				? 1
				: step < 0.28
					? -1
					: 0;

			offsets.push(Math.max(-2, Math.min(2, offsets[y - 1]! + move)));
		}

		const drift = offsets[16]!;
		let previous: number | null = null;

		for (let y = 0; y <= 15; y++) {
			const sx = base + offsets[y]! - Math.round((y * drift) / 16);

			// a sidestep fills the corner too, so the strand stays a trail
			// instead of a dashed line
			if (previous !== null && previous !== sx) {
				put(image, previous, y, BUSH_GREENS[1]!);
			}

			previous = sx;

			const stemTone = noise(seed ^ 0x165667b1, sx, y);
			const stem = stemTone > 0.5
				? BUSH_GREENS[1]!
				: BUSH_GREENS[0]!;

			put(image, sx, y, stem);

			// every row carries a leaf on one flank, and most rows a second
			// pixel somewhere near, which is what fills the strand out into
			// foliage rather than wire
			const leaf = noise(seed ^ 0x9e3779b9, s * 31 + sx, y);
			const flank = leaf > 0.5 ? 1 : -1;

			put(image, sx + flank, y, leaf > 0.75 || leaf < 0.25 ? BUSH_GREENS[3]! : BUSH_GREENS[2]!);

			const tuft = noise(seed ^ 0x27d4eb2f, s * 17 + sx, y);

			if (tuft > 0.55) {
				put(image, sx - flank, y, BUSH_GREENS[2]!);
			}

			if (tuft < 0.3) {
				put(image, sx + flank * 2, y, BUSH_GREENS[3]!);
			}
		}
	}

	const spots = scatter(seed, 5, 5, (x, y) => filled(image, x, y));

	for (let i = 0; i < spots.length; i++) {
		const [x, y] = spots[i]!;

		cluster(image, x, y, accents, Math.floor(noise(seed, x, y) * 4));
	}

	return encodePng(image);
}

/**
 * The water a tap pours and a tub holds: our own, because vanilla's is grey.
 *
 * Minecraft's water textures carry no colour at all - the blue is a biome tint
 * applied at render time, which a model drawn by a display entity never gets.
 * So this paints a blue one: a strip of frames the client plays as an
 * animation, each a gentle interference of two travelling waves, and the strip
 * wraps so the loop never jumps.
 *
 * @param frames how many frames the animation runs for
 */
export function waterSheet(frames = 16): Uint8Array {
	const size = 16;
	const image = bitmap(size, size * frames);

	for (let frame = 0; frame < frames; frame++) {
		const phase = (frame / frames) * Math.PI * 2;

		for (let y = 0; y < size; y++) {
			for (let x = 0; x < size; x++) {
				// two waves at right angles, both wrapping over the tile, so
				// the surface neither repeats obviously nor seams at the edge
				const a = Math.sin((x / size) * Math.PI * 2 + phase);
				const b = Math.sin((y / size) * Math.PI * 4 - phase * 1.5);
				const lift = (a * 0.6 + b * 0.4 + 1) / 2;

				const at = ((frame * size + y) * size + x) * 4;
				image.data[at] = Math.round(48 + lift * 40);
				image.data[at + 1] = Math.round(110 + lift * 60);
				image.data[at + 2] = Math.round(190 + lift * 45);
				image.data[at + 3] = 205;
			}
		}
	}

	return encodePng(image);
}

/**
 * The rope a hanging pot swings from: an 8x8 sheet whose left two columns are
 * a braided strand (the straps stretch it tall, which reads as lay), and whose
 * right half is the wrap the straps knot into under the ceiling.
 */
export function ropeSprite(): Uint8Array {
	const image = bitmap(8, 8);
	const dark: Rgb = [92, 66, 42];
	const mid: Rgb = [122, 90, 58];
	const light: Rgb = [147, 113, 76];

	const paint = (x: number, y: number, rgb: Rgb): void => {
		const at = (y * 8 + x) * 4;
		image.data[at] = rgb[0];
		image.data[at + 1] = rgb[1];
		image.data[at + 2] = rgb[2];
		image.data[at + 3] = 255;
	};

	// the strand: alternating twists over two columns
	for (let y = 0; y < 8; y++) {
		const twist = y % 4;

		paint(0, y, twist < 2 ? light : mid);
		paint(1, y, twist < 2 ? mid : dark);
	}

	// the knot: horizontal wraps, the outer columns a shade darker
	for (let y = 0; y < 4; y++) {
		for (let x = 4; x < 8; x++) {
			const band = y % 2 === 0 ? mid : dark;
			paint(x, y, x === 4 || x === 7 ? dark : band);
		}
	}

	return encodePng(image);
}
