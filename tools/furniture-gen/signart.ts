// The pictograms the signs wear, and the machinery that paints a sign face.
//
// Every grid below is drawn here by hand, pixel by pixel. The shapes are the
// standard public pictograms - ISO 7010 for the safety signs, the Vienna
// Convention for the road signs - which are conventions rather than anybody's
// artwork; none of these is traced or sampled from another mod's file, which
// is what lets them travel in our pack under our own terms.
//
// A sign face is a 32x32 texture: the field, a border that follows the field's
// own shape, and a symbol stamped in the middle. The geometry that wears it is
// a single plane, so the transparent corners of a triangle or a disc are
// actually cut out rather than filled in with backing.

import {
	type Art,
	type Bitmap,
	type Palette,
	type Rgba,
	bitmap,
	disc,
	encodePng,
	maskDifference,
	maskOf,
	paintMask,
	put,
	rect,
	rgb,
	stamp,
	triangle,
} from './pixels';

/** The face of every sign, in pixels. */
export const FACE = 32;

// ---- palette -------------------------------------------------------------

export const INK = rgb('#16181c');
export const PAPER = rgb('#f2f2ee');
export const SIGN_RED = rgb('#c8202b');
export const SIGN_BLUE = rgb('#12469b');
export const SIGN_GREEN = rgb('#1a7a44');
export const SIGN_YELLOW = rgb('#f2c318');
export const SIGN_WHITE = rgb('#f7f7f3');
export const SIGN_GREY = rgb('#8b9099');

/** What a symbol grid's characters mean; every symbol shares this key. */
const SYMBOL: Palette = {
	'#': INK,
	'.': [0, 0, 0, 0],
	o: rgb('#4a4d55'),
	w: PAPER,
	r: SIGN_RED,
	y: SIGN_YELLOW,
	b: SIGN_BLUE,
};

// ---- a 5x7 capital font --------------------------------------------------

// Sign text is short and always upper case - STOP, P, EXIT, a speed limit -
// so a five-by-seven capital alphabet covers it. Anything a sign wants to say
// at length is a text display, not a texture.

const GLYPHS: Record<string, string[]> = {
	A: ['.###.', '#...#', '#...#', '#####', '#...#', '#...#', '#...#'],
	B: ['####.', '#...#', '#...#', '####.', '#...#', '#...#', '####.'],
	C: ['.###.', '#...#', '#....', '#....', '#....', '#...#', '.###.'],
	D: ['####.', '#...#', '#...#', '#...#', '#...#', '#...#', '####.'],
	E: ['#####', '#....', '#....', '####.', '#....', '#....', '#####'],
	F: ['#####', '#....', '#....', '####.', '#....', '#....', '#....'],
	G: ['.###.', '#...#', '#....', '#..##', '#...#', '#...#', '.###.'],
	H: ['#...#', '#...#', '#...#', '#####', '#...#', '#...#', '#...#'],
	I: ['#####', '..#..', '..#..', '..#..', '..#..', '..#..', '#####'],
	J: ['..###', '...#.', '...#.', '...#.', '...#.', '#..#.', '.##..'],
	K: ['#...#', '#..#.', '#.#..', '##...', '#.#..', '#..#.', '#...#'],
	L: ['#....', '#....', '#....', '#....', '#....', '#....', '#####'],
	M: ['#...#', '##.##', '#.#.#', '#...#', '#...#', '#...#', '#...#'],
	N: ['#...#', '##..#', '#.#.#', '#..##', '#...#', '#...#', '#...#'],
	O: ['.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
	P: ['####.', '#...#', '#...#', '####.', '#....', '#....', '#....'],
	Q: ['.###.', '#...#', '#...#', '#...#', '#.#.#', '#..#.', '.##.#'],
	R: ['####.', '#...#', '#...#', '####.', '#.#..', '#..#.', '#...#'],
	S: ['.####', '#....', '#....', '.###.', '....#', '....#', '####.'],
	T: ['#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..'],
	U: ['#...#', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
	V: ['#...#', '#...#', '#...#', '#...#', '#...#', '.#.#.', '..#..'],
	W: ['#...#', '#...#', '#...#', '#...#', '#.#.#', '##.##', '#...#'],
	X: ['#...#', '#...#', '.#.#.', '..#..', '.#.#.', '#...#', '#...#'],
	Y: ['#...#', '#...#', '.#.#.', '..#..', '..#..', '..#..', '..#..'],
	Z: ['#####', '....#', '...#.', '..#..', '.#...', '#....', '#####'],
	'0': ['.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
	'1': ['..#..', '.##..', '..#..', '..#..', '..#..', '..#..', '.###.'],
	'2': ['.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####'],
	'3': ['####.', '....#', '....#', '.###.', '....#', '....#', '####.'],
	'4': ['...#.', '..##.', '.#.#.', '#..#.', '#####', '...#.', '...#.'],
	'5': ['#####', '#....', '####.', '....#', '....#', '#...#', '.###.'],
	'6': ['.###.', '#...#', '#....', '####.', '#...#', '#...#', '.###.'],
	'7': ['#####', '....#', '...#.', '..#..', '.#...', '.#...', '.#...'],
	'8': ['.###.', '#...#', '#...#', '.###.', '#...#', '#...#', '.###.'],
	'9': ['.###.', '#...#', '#...#', '.####', '....#', '#...#', '.###.'],
	'-': ['.....', '.....', '.....', '#####', '.....', '.....', '.....'],
	' ': ['.....', '.....', '.....', '.....', '.....', '.....', '.....'],
};

const GLYPH_WIDTH = 5;
const GLYPH_HEIGHT = 7;

/** How wide a word comes out, in pixels, at a scale, with one-pixel gaps. */
export function textWidth(word: string, scale: number): number {
	if (word.length === 0) {
		return 0;
	}

	return (word.length * (GLYPH_WIDTH + 1) - 1) * scale;
}

/** Draws a word with its top-left corner at `x`, `y`. */
export function drawText(image: Bitmap, word: string, x: number, y: number, scale: number, colour: Rgba): void {
	const palette: Palette = { '#': colour, '.': [0, 0, 0, 0] };
	let cursor = x;

	for (const character of word.toUpperCase()) {
		const glyph = GLYPHS[character] ?? GLYPHS[' ']!;

		stamp(image, glyph, palette, cursor, y, scale);
		cursor += (GLYPH_WIDTH + 1) * scale;
	}
}

/** Draws a word centred on the face, at a vertical position. */
export function drawTextCentered(image: Bitmap, word: string, y: number, scale: number, colour: Rgba): void {
	drawText(image, word, Math.round((image.width - textWidth(word, scale)) / 2), y, scale, colour);
}

// ---- the pictograms ------------------------------------------------------

/**
 * The symbols, each on a transparent field, drawn at the size that reads best
 * in the shape it goes into: a disc takes a taller symbol than a triangle,
 * whose usable area narrows towards the apex.
 */
export const SYMBOLS: Record<string, Art> = {
	// a person walking, the figure every pedestrian sign is built on
	pedestrian: [
		'.....####.......',
		'.....####.......',
		'.....####.......',
		'................',
		'...#######......',
		'..########.###..',
		'.###.####.####..',
		'###..####.###...',
		'.....####.......',
		'.....#####......',
		'....####.##.....',
		'....###..###....',
		'...###....###...',
		'..###......###..',
		'.###.......####.',
		'####........###.',
	],

	// a hard hat: the brim and the ridged crown
	helmet: [
		'................',
		'......####......',
		'....########....',
		'...##########...',
		'..############..',
		'..#.########.#..',
		'.##.########.##.',
		'.##.########.##.',
		'.##.########.##.',
		'.##.########.##.',
		'.###########.##.',
		'################',
		'################',
		'.##..........##.',
		'................',
		'................',
	],

	// earmuffs: two cups on a headband
	earmuffs: [
		'................',
		'.....######.....',
		'...##########...',
		'..####....####..',
		'.###........###.',
		'.##..........##.',
		'###..........###',
		'####........####',
		'#####......#####',
		'#####......#####',
		'#####......#####',
		'#####......#####',
		'#####......#####',
		'####........####',
		'.###........###.',
		'................',
	],

	// goggles: a wide lens band with a strap
	goggles: [
		'................',
		'................',
		'................',
		'..############..',
		'.##############.',
		'###..####...####',
		'##....##.....###',
		'##....##.....###',
		'###..####...####',
		'.##############.',
		'..############..',
		'....#......#....',
		'...#........#...',
		'..#..........#..',
		'................',
		'................',
	],

	// a lit cigarette with smoke: what a no-smoking sign strikes out
	cigarette: [
		'................',
		'..#.............',
		'...#....#.......',
		'..#....#........',
		'...#....#.......',
		'..#....#........',
		'................',
		'................',
		'................',
		'###############.',
		'###############o',
		'###############.',
		'................',
		'................',
		'................',
		'................',
	],

	// an open flame
	flame: [
		'................',
		'.......##.......',
		'......####......',
		'......####......',
		'.....######.....',
		'....###..###....',
		'....##....##....',
		'...###.....##...',
		'...##...#..###..',
		'..###..###..###.',
		'..##..#####..##.',
		'..##..#####..##.',
		'..###..###..###.',
		'...####...####..',
		'....##########..',
		'......######....',
	],

	// a figure losing their footing: arms thrown out, legs going from under
	// them, skid marks on the floor. The pose is the whole message, so it is
	// drawn wide rather than tall
	slip: [
		'.......###......',
		'......#####.....',
		'......#####.....',
		'.......###......',
		'.##############.',
		'.##....###......',
		'.......###......',
		'.......###......',
		'.......####.....',
		'......###.###...',
		'.....###....##..',
		'....###......##.',
		'...###........##',
		'..###...........',
		'................',
		'##.###.##.###.##',
	],

	// objects falling off a shelf onto a foot
	falling: [
		'................',
		'#####...........',
		'#####...........',
		'#####..####.....',
		'#####..####.....',
		'#####..####.....',
		'#####...........',
		'.......###......',
		'.......###......',
		'..####..........',
		'..####..###.....',
		'..####..###.....',
		'................',
		'..############..',
		'.##############.',
		'................',
	],

	// a running figure leaving through a doorway
	runner: [
		'.....####.......',
		'.....####.......',
		'.....####.......',
		'................',
		'...#####..###...',
		'..######.####...',
		'.####.#####.....',
		'.###..####......',
		'......####......',
		'.....#####......',
		'....####.##.....',
		'...###....##....',
		'..###......##...',
		'.###........##..',
		'###..........##.',
		'##............#.',
	],

	// a fire extinguisher, seen side on
	extinguisher: [
		'.......###......',
		'......#####.....',
		'.....##...##....',
		'....##.....#....',
		'...##...####....',
		'..##....####....',
		'..#.....####....',
		'........####....',
		'.......######...',
		'.......######...',
		'.......######...',
		'.......######...',
		'.......######...',
		'.......######...',
		'.......######...',
		'........####....',
	],

	// the lightning bolt every electrical sign carries
	bolt: [
		'.........###....',
		'........####....',
		'.......####.....',
		'......####......',
		'.....####.......',
		'....#####.......',
		'...##########...',
		'..##########....',
		'.........###....',
		'........###.....',
		'.......###......',
		'......###.......',
		'.....###........',
		'....###.........',
		'...###..........',
		'..##............',
	],

	// a skull: danger of death, over the bolt
	skull: [
		'................',
		'....########....',
		'..############..',
		'.##############.',
		'################',
		'###..######..###',
		'##....####....##',
		'##....####....##',
		'###..######..###',
		'################',
		'#####.####.#####',
		'.####.####.####.',
		'..############..',
		'...#.#.##.#.#...',
		'...#.#.##.#.#...',
		'....########....',
	],

	// a droplet: water where electricity is
	droplet: [
		'.......##.......',
		'.......##.......',
		'......####......',
		'......####......',
		'.....######.....',
		'....########....',
		'...##########...',
		'..############..',
		'..############..',
		'.##############.',
		'.##############.',
		'.##############.',
		'..############..',
		'..############..',
		'...##########...',
		'.....######.....',
	],

	// the first-aid cross
	cross: [
		'................',
		'................',
		'.....######.....',
		'.....######.....',
		'.....######.....',
		'.....######.....',
		'##############..',
		'##############..',
		'##############..',
		'##############..',
		'.....######.....',
		'.....######.....',
		'.....######.....',
		'.....######.....',
		'................',
		'................',
	],

	// a shovel standing in a mound of earth: roadworks.
	//
	// The Vienna sign is a figure digging, and a figure holding a tool is one
	// silhouette too many for sixteen pixels - every draft of it read as a
	// person waving. The tool and the spoil on their own are unmistakable.
	digger: [
		'..........###...',
		'.........###....',
		'........###.....',
		'.......###......',
		'......###.......',
		'.....###........',
		'....#####.......',
		'...#######......',
		'...#######......',
		'....#####.......',
		'................',
		'......####......',
		'....########....',
		'..############..',
		'.##############.',
		'################',
	],

	// the earth symbol: the bars an earthing point is drawn as
	earth: [
		'................',
		'.......##.......',
		'.......##.......',
		'.......##.......',
		'.......##.......',
		'.......##.......',
		'.......##.......',
		'..############..',
		'..############..',
		'................',
		'....########....',
		'....########....',
		'................',
		'......####......',
		'......####......',
		'................',
	],
};

// ---- sign faces ----------------------------------------------------------

/** The outline a sign's field takes; the border follows the same shape. */
export type Shape = 'disc' | 'triangle' | 'triangle_down' | 'octagon' | 'square' | 'diamond' | 'plate';

/** What a sign is made of: the field, the ring around it and what goes on it. */
export interface Face {
	shape: Shape;
	/** The colour the field is painted. */
	field: Rgba;
	/** The colour of the ring around the field, and how many pixels wide. */
	border?: { colour: Rgba; width: number };
	/** A second ring inside the border: the red annulus of a speed limit. */
	inner?: { colour: Rgba; width: number };
	/** A pictogram from SYMBOLS, stamped in the middle. */
	symbol?: string;
	/** The colour the symbol is recoloured to; its own ink by default. */
	symbolColour?: Rgba;
	/** Short upper-case text on the face, and how big. */
	text?: { word: string; scale: number; colour: Rgba; y?: number };
	/** A red diagonal stroke over everything: the prohibition slash. */
	slash?: Rgba;
	/** A white horizontal bar across the middle: a no-entry sign. */
	bar?: { colour: Rgba; height: number };
	/** An arrow pointing up, for a one-way plate. */
	arrow?: Rgba;
}

/** Where a shape's mask comes from, shrunk by `inset` pixels on every side. */
function shapeMask(shape: Shape, inset: number): (target: Bitmap) => void {
	const lo = inset;
	const hi = FACE - inset;
	const mid = FACE / 2;

	return (target: Bitmap) => {
		switch (shape) {
			case 'disc':
				disc(target, mid, mid, mid - inset, PAPER);
				break;

			case 'triangle':
				// an equilateral-ish triangle with its corners nipped off, the
				// way a real warning sign's corners are radiused
				triangle(target, [mid, lo], [hi, hi], [lo, hi], PAPER);
				break;

			case 'triangle_down':
				triangle(target, [lo, lo], [hi, lo], [mid, hi], PAPER);
				break;

			case 'octagon': {
				const cut = Math.round((hi - lo) * 0.3);

				rect(target, lo, lo + cut, hi, hi - cut, PAPER);
				rect(target, lo + cut, lo, hi - cut, hi, PAPER);
				triangle(target, [lo, lo + cut], [lo + cut, lo], [lo + cut, lo + cut], PAPER);
				triangle(target, [hi, lo + cut], [hi - cut, lo], [hi - cut, lo + cut], PAPER);
				triangle(target, [lo, hi - cut], [lo + cut, hi], [lo + cut, hi - cut], PAPER);
				triangle(target, [hi, hi - cut], [hi - cut, hi], [hi - cut, hi - cut], PAPER);
				break;
			}

			case 'diamond':
				triangle(target, [mid, lo], [hi, mid], [lo, mid], PAPER);
				triangle(target, [mid, hi], [hi, mid], [lo, mid], PAPER);
				break;

			case 'plate':
				// a wide plate: full width, shallower than it is broad
				rect(target, lo, Math.round(FACE * 0.25) + lo, hi, Math.round(FACE * 0.75) - lo, PAPER);
				break;

			default:
				rect(target, lo, lo, hi, hi, PAPER);
				break;
		}
	};
}

/** Recolours a symbol grid's ink without touching its shading characters. */
function symbolPalette(colour?: Rgba): Palette {
	if (!colour) {
		return SYMBOL;
	}

	return { ...SYMBOL, '#': colour };
}

/** Stamps a symbol centred in the field, nudged for the shapes that need it. */
function placeSymbol(image: Bitmap, name: string, shape: Shape, colour: Rgba | undefined, lift: number): void {
	const art = SYMBOLS[name];

	if (!art) {
		throw new Error(`no such symbol: ${name}`);
	}

	const width = Math.max(...art.map((line) => line.length));
	const height = art.length;

	// a triangle's usable area is the bottom two-thirds, so its symbol sits
	// low; everything else centres
	const offset = shape === 'triangle' ? 3 : shape === 'triangle_down' ? -3 : 0;

	stamp(
		image,
		art,
		symbolPalette(colour),
		Math.round((FACE - width) / 2),
		Math.round((FACE - height) / 2) + offset - lift,
		1,
	);
}

/** Paints one sign face and returns the PNG. */
export function signFace(face: Face): Uint8Array {
	const image = bitmap(FACE, FACE);
	const outer = maskOf(image, shapeMask(face.shape, 0));

	// what may be drawn on: inside the border, and inside the second ring when
	// there is one. Everything stamped afterwards is clipped to it, so a
	// symbol drawn a shade too large is cropped rather than laid over the ring
	let field = outer;

	if (face.border) {
		paintMask(image, outer, face.border.colour);

		field = maskOf(image, shapeMask(face.shape, face.border.width));
		paintMask(image, field, face.field);

		if (face.inner) {
			const gap = maskOf(image, shapeMask(face.shape, face.border.width + face.inner.width));

			paintMask(image, maskDifference(field, gap), face.inner.colour);
			field = gap;
		}
	} else {
		paintMask(image, outer, face.field);
	}

	const before = new Uint8Array(image.data);

	if (face.bar) {
		const top = Math.round((FACE - face.bar.height) / 2);

		for (let y = top; y < top + face.bar.height; y++) {
			for (let x = 0; x < FACE; x++) {
				if (outer[y * FACE + x]) {
					put(image, x, y, face.bar.colour);
				}
			}
		}
	}

	if (face.arrow) {
		triangle(image, [FACE / 2, 5], [FACE - 8, 15], [8, 15], face.arrow);
		rect(image, FACE / 2 - 3, 15, FACE / 2 + 3, FACE - 6, face.arrow);
	}

	if (face.symbol) {
		placeSymbol(image, face.symbol, face.shape, face.symbolColour, 0);
	}

	if (face.text) {
		const y = face.text.y ?? Math.round((FACE - GLYPH_HEIGHT * face.text.scale) / 2);

		drawTextCentered(image, face.text.word, y, face.text.scale, face.text.colour);
	}

	// put back everything that landed outside the field
	for (let i = 0; i < FACE * FACE; i++) {
		if (field[i]) {
			continue;
		}

		image.data.set(before.subarray(i * 4, i * 4 + 4), i * 4);
	}

	if (face.slash) {
		// the prohibition stroke runs corner to corner inside the ring, three
		// pixels thick, and is clipped to the sign's own outline
		for (let y = 0; y < FACE; y++) {
			for (let x = 0; x < FACE; x++) {
				if (!outer[y * FACE + x]) {
					continue;
				}

				const along = x + y - FACE + 1;

				if (Math.abs(along) <= 2) {
					put(image, x, y, face.slash);
				}
			}
		}
	}

	return encodePng(image);
}

/** The plain grey silhouette that backs a face, so a sign is not see-through. */
export function signBack(shape: Shape): Uint8Array {
	const image = bitmap(FACE, FACE);
	const outer = maskOf(image, shapeMask(shape, 0));

	paintMask(image, outer, SIGN_GREY);

	// a hairline of shadow around the rim, which is what stops the back
	// reading as a flat grey sticker
	const inner = maskOf(image, shapeMask(shape, 1));

	paintMask(image, maskDifference(outer, inner), rgb('#6b6f77'));

	return encodePng(image);
}
