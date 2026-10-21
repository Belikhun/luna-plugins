// The love bouquet: a florist's bouquet in colourful layered paper with a pink satin bow.
//
// Everything is built from boxes and painted here, nothing borrowed but vanilla's own flowers:
//  - the wrap is three layers of paper: wide pink and sky sheets leaning out from the gathered
//    neck, lilac sheets on the diagonals behind them (stepped outward, since a sheet can only lean
//    about one axis), and a cream lining standing inside, so the paper flares open the way a florist
//    folds it;
//  - the flowers are a dome of roses, each a little ball with a round painted bloom on every face,
//    with cornflowers, daisies, allium and lily of the valley from vanilla and sprigs of baby's
//    breath and leaves tucked between;
//  - the stems show below the neck, tied with a pink satin bow: two loops, a knot and two tails.
// One model serves the slot, the ground, the placed block and the hand.

import { type Model, type Vec3 } from './compose';
import { type Bitmap, type Rgba, bitmap, blend, disc, encodePng, fill, put, rect, rgb, tint, triangle } from './pixels';

export const BOUQUET_ID = 'bo_hoa_tinh_yeu';

export const BOUQUET_ENTRY = { id: BOUQUET_ID, en: 'Love Bouquet', vi: 'Bó Hoa Tình Yêu' };

const T = (name: string): string => `lunasmp:block/${name}`;
const FULL: [number, number, number, number] = [0, 0, 16, 16];
const CLEAR: Rgba = [0, 0, 0, 0];

// ---- sheets -----------------------------------------------------------------------------

/** A sheet of wrapping paper: lighter toward the top, soft vertical folds, a scalloped top edge. */
function paper(base: string, light: string, dark: string): Bitmap {
	const image = bitmap(16, 16);
	const b = rgb(base);
	for (let y = 0; y < 16; y++) {
		const f = 1.06 - (y / 15) * 0.14;
		rect(image, 0, y, 16, y + 1, tint(b, f));
	}
	for (let y = 0; y < 16; y++) {
		put(image, 3, y, rgb(dark));
		put(image, 10, y, rgb(dark));
		put(image, 4, y, rgb(light));
		put(image, 11, y, rgb(light));
		put(image, 14, y, rgb(light));
	}
	// the sheet narrows toward the neck so a leaning square of them stays closed at every height,
	// and its top rises to a point like the corner of a folded sheet
	for (let y = 0; y < 16; y++) {
		const body = 8 * (0.3 + 0.7 * (1 - y / 15));
		const half = y < 5 ? Math.min(body, 8 * (y + 0.6) / 5) : body;
		for (let x = 0; x < 16; x++) if (Math.abs(x + 0.5 - 8) > half) put(image, x, y, CLEAR);
	}
	return image;
}

/** The point of a sheet turned on the diagonal: a leaf-shaped tip rising above the square. */
function paperPoint(base: string, light: string, dark: string): Bitmap {
	const image = paper(base, light, dark);
	for (let y = 0; y < 16; y++) {
		const half = 8 * (y + 0.6) / 16;
		for (let x = 0; x < 16; x++) if (Math.abs(x + 0.5 - 8) > half) put(image, x, y, CLEAR);
	}
	return image;
}

function satin(): Bitmap {
	const image = bitmap(16, 16);
	fill(image, rgb('#e2457f'));
	// a soft sheen: one lit band near the top and a shadow along the bottom
	rect(image, 0, 2, 16, 4, rgb('#f26e9f'));
	rect(image, 0, 3, 16, 4, rgb('#ff8ab4'));
	rect(image, 0, 13, 16, 16, rgb('#c23a72'));
	return image;
}

/** The knot: satin a shade darker, with a lit crease across the top. */
function knot(): Bitmap {
	const image = bitmap(16, 16);
	fill(image, rgb('#c93a70'));
	rect(image, 0, 2, 16, 4, rgb('#f07aa8'));
	rect(image, 0, 7, 16, 8, rgb('#e2457f'));
	rect(image, 0, 12, 16, 16, rgb('#8f2050'));
	for (let y = 0; y < 16; y++) { put(image, 5, y, rgb('#a8285a')); put(image, 11, y, rgb('#a8285a')); }
	return image;
}

/** The bed of leaves under the flowers: small leaves in two greens on a dark ground. */
function bed(): Bitmap {
	const image = bitmap(16, 16);
	const ground = rgb('#2f6b26'), a = rgb('#4e9a3c'), b = rgb('#62b04c');
	fill(image, ground);
	const leaves: [number, number, Rgba][] = [[2, 2, a], [8, 1, b], [13, 3, a], [5, 6, b], [11, 7, a], [1, 10, b], [7, 11, a], [14, 11, b], [4, 14, a], [10, 14, b]];
	for (const [x, y, c] of leaves) {
		put(image, x, y, c); put(image, x + 1, y, c); put(image, x, y + 1, c); put(image, x + 1, y + 1, c);
		put(image, x - 1, y + 1, c); put(image, x + 2, y, c);
	}
	return image;
}

/** One loop of the bow, seen flat: a rounded shape with a fold line and a lit edge. */
function bowLoop(): Bitmap {
	const image = bitmap(16, 16);
	const base = rgb('#ee5a90'), light = rgb('#ff9fc3'), dark = rgb('#c23a72');
	// the loop: a fat teardrop with its point at the right (the knot side)
	disc(image, 6.5, 8, 6.5, base);
	triangle(image, [6.5, 1.5], [16, 8], [6.5, 14.5], base);
	// outline
	const inside = (x: number, y: number) => image.data[(y * 16 + x) * 4 + 3] !== 0;
	const edge: [number, number][] = [];
	for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
		if (!inside(x, y)) continue;
		if (x === 0 || y === 0 || x === 15 || y === 15 || !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1)) edge.push([x, y]);
	}
	for (const [x, y] of edge) put(image, x, y, dark);
	// the fold running back to the knot and a highlight on the upper half
	for (let x = 6; x < 15; x++) put(image, x, 8, dark);
	disc(image, 6, 8.5, 2.2, dark);
	disc(image, 6, 8.5, 1.2, rgb('#a8285a'));
	for (let x = 3; x < 11; x++) put(image, x, 4, light);
	rect(image, 3, 3, 8, 4, light);
	return image;
}

/** A hanging ribbon tail, pointed at the bottom like a cut ribbon. */
function bowTail(): Bitmap {
	const image = bitmap(16, 16);
	const base = rgb('#e2457f'), light = rgb('#ff8ab4'), dark = rgb('#a8285a');
	rect(image, 3, 0, 13, 12, base);
	triangle(image, [3, 11], [13, 11], [8, 16], base);
	triangle(image, [5, 13], [11, 13], [8, 16], CLEAR);
	for (let y = 0; y < 13; y++) { put(image, 3, y, dark); put(image, 12, y, dark); put(image, 5, y, light); }
	return image;
}

interface Rose { base: Rgba; light: Rgba; dark: Rgba; deep: Rgba }

const rose = (base: string, light: string, dark: string, deep: string): Rose => ({ base: rgb(base), light: rgb(light), dark: rgb(dark), deep: rgb(deep) });

const ROSES: Record<string, Rose> = {
	red: rose('#d8243f', '#f06478', '#9c1430', '#5e0a1c'),
	pink: rose('#f38fb3', '#ffc3d6', '#d65c8a', '#a63a66'),
	peach: rose('#f8b89a', '#ffd9c6', '#e08a6b', '#b45f48'),
	white: rose('#faf3ea', '#ffffff', '#dcc5a8', '#c2a380'),
	blush: rose('#f9c6d3', '#ffe4ec', '#e39bb2', '#c57590'),
};

/** The bloom from above: a round head with the petals spiralling into the centre. */
function roseTop(c: Rose): Bitmap {
	const image = bitmap(16, 16);
	for (let i = 0; i < 5; i++) {
		const a = (i / 5) * Math.PI * 2 - Math.PI / 2;
		disc(image, 8 + Math.cos(a) * 4.6, 8 + Math.sin(a) * 4.6, 3.3, c.base);
	}
	disc(image, 8, 8, 6, c.base);
	// the outer petals' edges, only where the bumps meet
	for (let i = 0; i < 5; i++) {
		const a = ((i + 0.5) / 5) * Math.PI * 2 - Math.PI / 2;
		put(image, Math.floor(8 + Math.cos(a) * 5.6), Math.floor(8 + Math.sin(a) * 5.6), c.dark);
		put(image, Math.floor(8 + Math.cos(a) * 4.6), Math.floor(8 + Math.sin(a) * 4.6), c.dark);
	}
	// the spiral
	for (let t = 0; t < 1.85; t += 0.02) {
		const r = 0.8 + t * 2.9;
		const x = Math.floor(8 + Math.cos(t * Math.PI * 2) * r);
		const y = Math.floor(8 + Math.sin(t * Math.PI * 2) * r);
		if (r < 6) put(image, x, y, c.dark);
	}
	put(image, 8, 7, c.deep);
	put(image, 7, 8, c.deep);
	// light catching the upper-left petals
	for (const [x, y] of [[5, 4], [6, 4], [4, 6], [10, 3], [3, 9], [6, 11]] as [number, number][]) {
		if (image.data[(y * 16 + x) * 4 + 3] !== 0 && image.data[(y * 16 + x) * 4] === c.base[0]) put(image, x, y, c.light);
	}
	return image;
}

/** The bloom from the side: a cup of petals, wide at the top and narrowing to the calyx, so the
 * ball's top face meets its sides without a ledge. */
function roseSide(c: Rose): Bitmap {
	const image = bitmap(16, 16);
	for (let y = 0; y < 11; y++) {
		const half = y < 2 ? 8 : 7.6 - (y - 2) * 0.45;
		for (let x = 0; x < 16; x++) if (Math.abs(x + 0.5 - 8) <= half) put(image, x, y, c.base);
	}
	// scalloped petal tips along the top, and an outline down the sides
	for (let x = 0; x < 16; x++) if (x % 4 === 1) put(image, x, 0, CLEAR);
	for (let y = 1; y < 11; y++) {
		const half = y < 2 ? 8 : 7.6 - (y - 2) * 0.45;
		put(image, Math.floor(8 - half), y, c.dark);
		put(image, Math.ceil(8 + half) - 1, y, c.dark);
	}
	// two curved petal cups across the face, lit on the upper left
	for (let a = Math.PI * 1.1; a < Math.PI * 1.9; a += 0.03) {
		put(image, Math.floor(8 + Math.cos(a) * 5.2), Math.floor(5 + Math.sin(a) * 3.4), c.dark);
		put(image, Math.floor(8 + Math.cos(a) * 2.6), Math.floor(4.5 + Math.sin(a) * 2), c.dark);
	}
	for (const [x, y] of [[3, 3], [4, 3], [2, 5], [9, 3], [12, 4], [5, 6], [6, 6]] as [number, number][]) put(image, x, y, c.light);
	for (let a = Math.PI * 0.08; a < Math.PI * 0.92; a += 0.02) {
		put(image, Math.floor(8 + Math.cos(a) * 5.2), Math.floor(7.2 + Math.sin(a) * 3.2), c.deep);
		put(image, Math.floor(8 + Math.cos(a) * 5.2), Math.floor(6.4 + Math.sin(a) * 3.2), c.dark);
	}
	// the calyx and its sepals
	const green = rgb('#4f8f3a'), greenDark = rgb('#35692a');
	triangle(image, [4, 11], [12, 11], [8, 15], green);
	triangle(image, [2, 10], [5, 10], [1, 14], greenDark);
	triangle(image, [11, 10], [14, 10], [15, 14], greenDark);
	for (let x = 5; x < 11; x++) put(image, x, 11, greenDark);
	rect(image, 7, 14, 9, 16, greenDark);
	return image;
}

function breath(): Bitmap {
	const image = bitmap(16, 16);
	const stem = rgb('#b9d9a8'), bloom = rgb('#ffffff'), shade = rgb('#e6e6dc');
	fill(image, stem);
	for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
		const k = (x * 3 + y * 5 + ((x ^ y) & 3)) % 5;
		if (k < 3) put(image, x, y, k === 2 ? shade : bloom);
	}
	return image;
}

function leaf(): Bitmap {
	const image = bitmap(16, 16);
	const g = rgb('#4e9a3c'), light = rgb('#6fbd57'), dark = rgb('#2f6b26');
	triangle(image, [8, 0], [1, 9], [8, 16], g);
	triangle(image, [8, 0], [15, 9], [8, 16], g);
	for (let y = 1; y < 16; y++) put(image, 8, y, dark);
	for (let y = 4; y < 14; y += 3) { put(image, 5, y, light); put(image, 11, y + 1, light); }
	return image;
}

function stems(): Bitmap {
	const image = bitmap(16, 16);
	fill(image, rgb('#4f8f3a'));
	for (let y = 0; y < 16; y++) for (const x of [2, 6, 10, 13]) put(image, x, y, rgb('#3a6e2a'));
	for (let x = 0; x < 16; x++) if (x % 4 !== 1) put(image, x, 15, CLEAR);
	return image;
}

export function bouquetSprites(): Record<string, Uint8Array> {
	const sheets: Record<string, Bitmap> = {
		bouquet_paper_pink: paper('#f291b5', '#fbb8d0', '#d66d96'),
		bouquet_paper_lilac: paper('#c9a6ee', '#e2d0f8', '#a584d4'),
		bouquet_paper_cream: paper('#fbf0dc', '#ffffff', '#e9d8bb'),
		bouquet_paper_sky: paper('#b4ddfb', '#dcf0ff', '#8fc3e8'),
		bouquet_paper_lilac_point: paperPoint('#b99ae0', '#dccbf3', '#9676c6'),
		bouquet_satin: satin(),
		bouquet_knot: knot(),
		bouquet_bed: bed(),
		bouquet_bow_loop: bowLoop(),
		bouquet_bow_tail: bowTail(),
		bouquet_breath: breath(),
		bouquet_leaf: leaf(),
		bouquet_stems: stems(),
	};
	for (const [name, c] of Object.entries(ROSES)) {
		sheets[`bouquet_rose_${name}_top`] = roseTop(c);
		sheets[`bouquet_rose_${name}_side`] = roseSide(c);
	}
	return Object.fromEntries(Object.entries(sheets).map(([k, v]) => [k, new Uint8Array(encodePng(v))]));
}

// ---- geometry -----------------------------------------------------------------------------

type El = Record<string, unknown>;
type Uv = [number, number, number, number];

function box(from: Vec3, to: Vec3, faces: Partial<Record<string, string>>, rotation?: El | undefined, uv: Uv = FULL, shade?: boolean): El {
	const out: Record<string, unknown> = {};
	for (const [dir, tex] of Object.entries(faces)) if (tex) out[dir] = { uv, texture: tex };
	const el: El = { from, to, faces: out };
	if (rotation) el.rotation = rotation;
	if (shade === false) el.shade = false;
	return el;
}

const allSides = (side: string, top?: string, bottom?: string) => ({ north: side, south: side, east: side, west: side, up: top ?? side, down: bottom ?? side });

/**
 * A paper sheet standing at the neck's edge, leaning out by `tilt` about its own bottom edge.
 * Built facing north (thin in z) or east (thin in x); a sheet can only lean about one axis.
 */
function petal(dir: 'n' | 's' | 'e' | 'w', width: number, y0: number, y1: number, r: number, tex: string, tilt: number, offset = 0): El {
	const half = width / 2;
	const c = 8 + offset;
	const t = 0;
	if (dir === 'n' || dir === 's') {
		const z = dir === 'n' ? 8 - r : 8 + r;
		return box([c - half, y0, z - t], [c + half, y1, z + t], { north: tex, south: tex }, { origin: [c, y0, z], axis: 'x', angle: dir === 'n' ? -tilt : tilt }, FULL, false);
	}
	const x = dir === 'e' ? 8 + r : 8 - r;
	return box([x - t, y0, c - half], [x + t, y1, c + half], { east: tex, west: tex }, { origin: [x, y0, c], axis: 'z', angle: dir === 'e' ? -tilt : tilt }, FULL, false);
}

/** A vertical sheet on a diagonal: built facing north and turned 45 degrees about the bouquet's axis. */
function diagonalSheet(quadrant: number, width: number, y0: number, y1: number, r: number, tex: string, t = 0): El {
	const half = width / 2;
	const z = quadrant >= 2 ? 8 + r : 8 - r;
	const faces: Partial<Record<string, string>> = t > 0 ? { north: tex, south: tex, up: tex, east: tex, west: tex } : { north: tex, south: tex };
	return box([8 - half, y0, z - t], [8 + half, y1, z + t], faces, { origin: [8, y0, 8], axis: 'y', angle: quadrant % 2 === 0 ? 45 : -45 }, FULL, false);
}

/** How far the bouquet stands up on its stems: the paper starts this high, the stems fill below. */
const LIFT = 4.5;

/** The cone: each outer sheet starts at the neck this far from the axis and leans out 22.5 degrees. */
const NECK_Y = 1.8;
const NECK_R = 2;

function wrap(): El[] {
	const pink = T('bouquet_paper_pink');
	const lilac = T('bouquet_paper_lilac');
	const cream = T('bouquet_paper_cream');
	const sky = T('bouquet_paper_sky');
	const els: El[] = [];
	// the gathered neck, narrow, where the hand holds it
	els.push(box([6.3, -0.6, 6.3], [9.7, 2.2, 9.7], allSides(pink, pink, pink)));
	// the outer layer: a cone of four sheets leaning out from the narrow neck, sky blue front and back
	// so the pink bow and a pink table stand apart, pink at the sides, each rising to a point
	const front = petal('s', 14.6, NECK_Y, 14.6, NECK_R, sky, 22.5);
	(front.faces as Record<string, { texture: string }>).north!.texture = lilac;
	els.push(front, petal('n', 14.6, NECK_Y, 14.6, NECK_R, lilac, 22.5));
	for (const d of ['e', 'w'] as const) els.push(petal(d, 14.6, NECK_Y, 15.2, NECK_R, pink, 22.5));
	// a second, shorter layer of lilac over the back and the sides, so no face is one flat sheet
	els.push(petal('n', 7, NECK_Y + 0.4, 11.4, NECK_R + 0.45, lilac, 22.5, -2.4));
	els.push(petal('n', 7, NECK_Y + 0.4, 10.4, NECK_R + 0.6, lilac, 22.5, 2.4));
	for (const d of ['e', 'w'] as const) els.push(petal(d, 10, NECK_Y + 0.4, 11, NECK_R + 0.5, lilac, 22.5));
	// lilac behind each corner seam, so a hairline there shows paper, never the inside
	for (let q = 0; q < 4; q++) els.push(diagonalSheet(q, 5, 2.4, 11.4, 3.4, lilac));
	// the cream lining, standing closer so it shows as a rim inside the flowers
	for (const d of ['n', 's', 'e', 'w'] as const) els.push(petal(d, 6.5, 2.8, 11.2, 1.6, cream, 22.5));
	for (let q = 0; q < 4; q++) els.push(diagonalSheet(q, 4, 2.8, 11.4, 2, cream));
	// a bed of leaves filling the mouth of the cone just under the flowers
	els.push(box([2.6, 11.4, 2.6], [13.4, 12.4, 13.4], allSides(T('bouquet_bed'))));
	return els;
}

/** A crossed pair of planes carrying a flower sprite, the way vanilla draws every flower. */
function sprigCross(cx: number, y: number, cz: number, width: number, height: number, tex: string, uv: Uv = FULL, spin = 45): El[] {
	const half = width / 2;
	const origin: Vec3 = [cx, y, cz];
	return [
		box([cx - half, y, cz], [cx + half, y + height, cz], { north: tex, south: tex }, { origin, axis: 'y', angle: spin, rescale: true }, uv, false),
		box([cx, y, cz - half], [cx, y + height, cz + half], { east: tex, west: tex }, { origin, axis: 'y', angle: spin, rescale: true }, uv, false),
	];
}

/** How much of a rose head's corners are cut away: each head is a plus of two boxes, an octagon. */
const NOTCH = 0.14;

function flowers(): El[] {
	const els: El[] = [];
	const rose = (c: string, cx: number, cy: number, cz: number, s: number, rot?: El, tall = 0.72) => {
		const h = s / 2;
		const n = s * (0.5 - NOTCH);
		const top = cy + s * tall;
		const side = T(`bouquet_rose_${c}_side`);
		const up = T(`bouquet_rose_${c}_top`);
		const cut = 16 * NOTCH;
		const full: Uv = FULL;
		const narrow: Uv = [cut, 0, 16 - cut, 16];
		// wide in x: its north and south faces are whole, its east and west faces are the middle cut
		const a: El = { from: [cx - h, cy, cz - n], to: [cx + h, top, cz + n], faces: {
			north: { uv: full, texture: side }, south: { uv: full, texture: side },
			east: { uv: narrow, texture: side }, west: { uv: narrow, texture: side },
			up: { uv: [0, cut, 16, 16 - cut], texture: up },
		} };
		// wide in z: the other way round
		const b: El = { from: [cx - n, cy, cz - h], to: [cx + n, top, cz + h], faces: {
			north: { uv: narrow, texture: side }, south: { uv: narrow, texture: side },
			east: { uv: full, texture: side }, west: { uv: full, texture: side },
			up: { uv: [cut, 0, 16 - cut, 16], texture: up },
		} };
		if (rot) {
			a.rotation = rot;
			b.rotation = { ...rot };
		}
		els.push(a, b);
	};
	// the rim: eight roses between the paper's points, leaning out over the paper, in two sizes
	const rim = ['red', 'pink', 'white', 'red', 'peach', 'pink', 'red', 'blush'];
	for (let i = 0; i < 8; i++) {
		const a = ((i + 0.5) / 8) * Math.PI * 2;
		const cx = 8 + Math.cos(a) * 4.9;
		const cz = 8 + Math.sin(a) * 4.9;
		const alongX = i % 4 === 0 || i % 4 === 3 ? Math.abs(Math.cos(a)) > 0.5 : Math.abs(Math.cos(a)) > 0.9;
		const sign = alongX ? Math.sign(Math.cos(a)) : Math.sign(Math.sin(a));
		const s = i % 2 ? 4.8 : 5.4;
		rose(rim[i]!, cx, 10.8 + (i % 2) * 0.5, cz, s, { origin: [cx, 10.8, cz], axis: alongX ? 'z' : 'x', angle: alongX ? -22.5 * sign : 22.5 * sign }, 0.8);
	}
	// the middle ring, turned so no two edges line up, and the crown lifting the dome
	const mid = ['pink', 'red', 'white', 'peach', 'red'];
	for (let i = 0; i < 5; i++) {
		const a = (i / 5) * Math.PI * 2 + 0.3;
		const cx = 8 + Math.cos(a) * 3, cz = 8 + Math.sin(a) * 3;
		const s = i % 2 ? 4.6 : 5;
		rose(mid[i]!, cx, 13.4 + (i % 2) * 0.6, cz, s, { origin: [cx, 13.4, cz], axis: 'y', angle: [22.5, -22.5, 45, -45, 22.5][i]! });
	}
	rose('pink', 8, 15, 8, 4.6, { origin: [8, 15, 8], axis: 'y', angle: 22.5 });
	// a ring of smaller roses filling the gaps between the middle ring and the rim
	const fill = ['white', 'red', 'blush', 'red', 'peach'];
	for (let i = 0; i < 5; i++) {
		const a = (i / 5) * Math.PI * 2 + 0.3 + Math.PI / 5;
		const cx = 8 + Math.cos(a) * 4.3, cz = 8 + Math.sin(a) * 4.3;
		rose(fill[i]!, cx, 12.2, cz, 4.4, { origin: [cx, 12.3, cz], axis: 'y', angle: i % 2 ? 45 : -22.5 });
	}
	// baby's breath: little white heads peeking between the petals, sitting on the leaf bed
	const b = T('bouquet_breath');
	for (let i = 0; i < 8; i++) {
		const a = (i / 8) * Math.PI * 2;
		const cx = 8 + Math.cos(a) * 5.2, cz = 8 + Math.sin(a) * 5.2;
		els.push(box([cx - 0.6, 12.6, cz - 0.6], [cx + 0.6, 13.8, cz + 0.6], allSides(b)));
	}
	// a collar of leaves spilling out under the rim roses, one under each
	const lf = T('bouquet_leaf');
	for (let i = 0; i < 8; i++) {
		const a = ((i + 0.5) / 8) * Math.PI * 2;
		const cx = 8 + Math.cos(a) * 6.3, cz = 8 + Math.sin(a) * 6.3;
		const alongX = i % 4 === 0 || i % 4 === 3;
		const turn = (i % 4 === 0 || i % 4 === 2) ? 22.5 : -22.5;
		const el = alongX
			? box([cx - 2.6, 12.2, cz - 1.5], [cx + 2.6, 12.2, cz + 1.5], { up: lf, down: lf }, { origin: [cx, 12.2, cz], axis: 'y', angle: turn }, FULL, false)
			: box([cx - 1.5, 12.2, cz - 2.6], [cx + 1.5, 12.2, cz + 2.6], { up: lf, down: lf }, { origin: [cx, 12.2, cz], axis: 'y', angle: turn }, FULL, false);
		els.push(el);
	}
	return els;
}

/** The ribbon is tied at the cone's waist: this high on the paper (before the lift). */
const WAIST = 4.6;

function bow(): El[] {
	const satin = T('bouquet_satin');
	const knot = T('bouquet_knot');
	const loop = T('bouquet_bow_loop');
	const tail = T('bouquet_bow_tail');
	const els: El[] = [];
	const y = WAIST;
	// the cone's half width at the waist, and the ribbon band round it
	const r = NECK_R + (y - NECK_Y) * Math.sin(Math.PI / 8) + 0.75;
	els.push(box([8 - r, y - 0.7, 8 - r], [8 + r, y + 0.7, 8 + r], { north: satin, south: satin, east: satin, west: satin }));
	const f = 8 + r + 0.3;
	// two loops tipped up from the knot, each a sheet with the loop sprite and a satin core
	els.push(box([3.8, y - 2.1, f], [7.2, y + 2.1, f], { south: loop, north: loop }, { origin: [7.2, y, f], axis: 'z', angle: 22.5 }, FULL, false));
	els.push(box([8.8, y - 2.1, f], [12.2, y + 2.1, f], { south: loop, north: loop }, { origin: [8.8, y, f], axis: 'z', angle: -22.5 }, [16, 0, 0, 16], false));
	els.push(box([4.6, y - 0.9, f - 0.8], [7.2, y + 0.9, f + 0.8], allSides(satin), { origin: [7.2, y, f], axis: 'z', angle: 22.5 }));
	els.push(box([8.8, y - 0.9, f - 0.8], [11.4, y + 0.9, f + 0.8], allSides(satin), { origin: [8.8, y, f], axis: 'z', angle: -22.5 }));
	// the knot, a shade darker and standing proud
	els.push(box([6.8, y - 1.2, f - 1.6], [9.2, y + 1.2, f + 1.2], allSides(knot)));
	// tails hanging from the knot and swinging apart over the neck
	els.push(box([5.8, y - 4.4, f + 0.2], [7.8, y - 0.6, f + 0.2], { south: tail, north: tail }, { origin: [6.9, y - 0.6, f], axis: 'z', angle: 22.5 }, FULL, false));
	els.push(box([8.2, y - 4.4, f + 0.8], [10.2, y - 0.6, f + 0.8], { south: tail, north: tail }, { origin: [9.1, y - 0.6, f], axis: 'z', angle: -22.5 }, FULL, false));
	els.push(box([6.4, y - 3.6, f - 0.3], [7.2, y - 0.6, f + 0.5], allSides(satin), { origin: [6.9, y - 0.6, f], axis: 'z', angle: 22.5 }));
	els.push(box([8.8, y - 3.6, f + 0.4], [9.6, y - 0.6, f + 1.2], allSides(satin), { origin: [9.1, y - 0.6, f], axis: 'z', angle: -22.5 }));
	return els;
}

/** The stems, below everything else, long enough to read as the place a hand holds. */
function stemBundle(): El[] {
	const s = T('bouquet_stems');
	return [
		box([7, 0, 7], [9, LIFT + 1.2, 9], allSides(s)),
		...sprigCross(8, 0.2, 8, 3.6, LIFT + 0.6, s, FULL, 45),
	];
}

/** Moves elements up by `LIFT`, so the paper and flowers stand on the stems. */
function lift(els: El[]): El[] {
	const up = (v: Vec3): Vec3 => [v[0], v[1] + LIFT, v[2]];
	for (const el of els) {
		el.from = up(el.from as Vec3);
		el.to = up(el.to as Vec3);
		const rot = el.rotation as { origin: Vec3 } | undefined;
		if (rot) rot.origin = up(rot.origin);
	}
	return els;
}

function variables(els: El[]): Model {
	const textures: Record<string, string> = {};
	const names = new Map<string, string>();
	for (const el of els) {
		for (const face of Object.values(el.faces as Record<string, { texture: string }>)) {
			let n = names.get(face.texture);
			if (!n) {
				n = `t${names.size}`;
				names.set(face.texture, n);
				textures[n] = face.texture;
			}
			face.texture = `#${n}`;
		}
	}
	textures.particle = T('bouquet_paper_pink');
	return { textures, elements: els } as unknown as Model;
}

/** The placed bouquet stands this much of a block tall: a bouquet on a table, not a bush. */
const SCALE = 0.9;

const DISPLAY = {
	thirdperson_righthand: { rotation: [-10, 0, 0], translation: [0, 2.5, 1], scale: [0.75, 0.75, 0.75] },
	thirdperson_lefthand: { rotation: [-10, 0, 0], translation: [0, 2.5, 1], scale: [0.75, 0.75, 0.75] },
	firstperson_righthand: { rotation: [-5, -12, 12], translation: [0, 3, -1], scale: [0.37, 0.37, 0.37] },
	firstperson_lefthand: { rotation: [-5, 12, -12], translation: [0, 3, -1], scale: [0.37, 0.37, 0.37] },
	gui: { rotation: [20, -35, 0], translation: [0, -2.5, 0], scale: [0.6, 0.6, 0.6] },
	ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.35, 0.35, 0.35] },
	fixed: { rotation: [0, 0, 0], translation: [0, -4.5, 0], scale: [0.6, 0.6, 0.6] },
	head: { rotation: [0, 0, 0], translation: [0, 13, 0], scale: [0.82, 0.82, 0.82] },
};

/** Shrinks every element about the bouquet's axis and its base, so it stands at `SCALE` of a block. */
function shrink(els: El[]): El[] {
	const sx = (v: number) => 8 + (v - 8) * SCALE;
	const sy = (v: number) => v * SCALE;
	const p = (v: Vec3): Vec3 => [sx(v[0]), sy(v[1]), sx(v[2])];
	for (const el of els) {
		el.from = p(el.from as Vec3);
		el.to = p(el.to as Vec3);
		const rot = el.rotation as { origin: Vec3 } | undefined;
		if (rot) rot.origin = p(rot.origin);
	}
	return els;
}

function model(): Model {
	const m = variables(shrink([...stemBundle(), ...lift([...bow(), ...wrap(), ...flowers()])]));
	(m as unknown as { display: unknown }).display = DISPLAY;
	return m;
}

export function bouquetModels(): Record<string, Model> {
	return { [BOUQUET_ID]: model() };
}

export function bouquetItemModels(): Record<string, Model> {
	return { [`${BOUQUET_ID}_held`]: model() };
}
