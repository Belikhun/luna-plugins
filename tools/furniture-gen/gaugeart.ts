// The gauge faces: every dial the network instruments wear, painted here.
//
// A dial is drawn the way real panel instruments are laid out - a bezel, a
// pale face, a graduated arc with major and minor ticks, coloured zones for
// the readings that mean something, numerals inside the arc and the unit in a
// plate under the pivot. The conventions are the industry's own: a 240-degree
// sweep for a level gauge, a centre-zero galvanometer for anything that flows
// both ways (red to the left for draining, green to the right for charging,
// as asked), a DIN-style square meter with the pivot low on the face for load
// and generation, and a Ferraris-style black meter with a digit window for
// the totaliser.
//
// The needle is NOT in these textures. It is a separate item display the
// server rotates, which is the whole point of the exercise: the face is
// static art, the needle is live data.
//
// Faces are 64x64 - twice the sign density - because a dial is exactly the
// texture a player walks up to and reads, and 5% ticks do not exist at 32px.

import {
	type Art,
	type Bitmap,
	type Palette,
	type Rgba,
	bitmap,
	blend,
	disc,
	encodePng,
	maskDifference,
	maskOf,
	paintMask,
	put,
	rect,
	rgb,
	stamp,
	tint,
} from './pixels';

/** Every gauge face is this many pixels square. */
export const FACE = 64;

// ---- palette ---------------------------------------------------------------

const BEZEL = rgb('#3a3e46');
const BEZEL_LIGHT = rgb('#565b66');
const FACE_PALE = rgb('#ece8dd');
const FACE_DARK = rgb('#16181d');
const INK = rgb('#1c1e24');
const ZONE_GREEN = rgb('#3d8b40');
const ZONE_AMBER = rgb('#d9a521');
const ZONE_RED = rgb('#c22f2a');
const ZONE_BLUE = rgb('#2f6fc2');
const WINDOW = rgb('#0c0d10');

/** A tiny deterministic hash in [0, 1). */
function noise(seed: number, x: number, y: number): number {
	let h = seed ^ Math.imul(x + 11, 374761393) ^ Math.imul(y + 7, 668265263);
	h = Math.imul(h ^ (h >>> 13), 1274126177);

	return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

// ---- a 3x5 micro font ------------------------------------------------------

// The 5x7 sign font is too big for the inside of a 22-pixel arc: a dial's
// numerals and unit plate use this one instead. Only what a dial ever says.

const MICRO: Record<string, string[]> = {
	'0': ['###', '#.#', '#.#', '#.#', '###'],
	'1': ['.#.', '##.', '.#.', '.#.', '###'],
	'2': ['###', '..#', '###', '#..', '###'],
	'3': ['###', '..#', '.##', '..#', '###'],
	'4': ['#.#', '#.#', '###', '..#', '..#'],
	'5': ['###', '#..', '###', '..#', '###'],
	'6': ['###', '#..', '###', '#.#', '###'],
	'7': ['###', '..#', '.#.', '.#.', '.#.'],
	'8': ['###', '#.#', '###', '#.#', '###'],
	'9': ['###', '#.#', '###', '..#', '###'],
	'%': ['#.#', '..#', '.#.', '#..', '#.#'],
	'/': ['..#', '..#', '.#.', '#..', '#..'],
	'+': ['...', '.#.', '###', '.#.', '...'],
	'-': ['...', '...', '###', '...', '...'],
	'.': ['...', '...', '...', '...', '.#.'],
	' ': ['...', '...', '...', '...', '...'],
	A: ['.#.', '#.#', '###', '#.#', '#.#'],
	B: ['##.', '#.#', '##.', '#.#', '##.'],
	E: ['###', '#..', '##.', '#..', '###'],
	I: ['###', '.#.', '.#.', '.#.', '###'],
	J: ['..#', '..#', '..#', '#.#', '.#.'],
	K: ['#.#', '##.', '#..', '##.', '#.#'],
	L: ['#..', '#..', '#..', '#..', '###'],
	M: ['#.#', '###', '#.#', '#.#', '#.#'],
	N: ['#.#', '###', '###', '#.#', '#.#'],
	O: ['###', '#.#', '#.#', '#.#', '###'],
	S: ['.##', '#..', '.#.', '..#', '##.'],
	T: ['###', '.#.', '.#.', '.#.', '.#.'],
	U: ['#.#', '#.#', '#.#', '#.#', '###'],
	G: ['.##', '#..', '#.#', '#.#', '.##'],
	W: ['#.#', '#.#', '#.#', '###', '#.#'],
	H: ['#.#', '#.#', '###', '#.#', '#.#'],
	R: ['##.', '#.#', '##.', '#.#', '#.#'],
	F: ['###', '#..', '##.', '#..', '#..'],
	D: ['##.', '#.#', '#.#', '#.#', '##.'],
	V: ['#.#', '#.#', '#.#', '#.#', '.#.'],
	C: ['.##', '#..', '#..', '#..', '.##'],
	P: ['##.', '#.#', '##.', '#..', '#..'],
	X: ['#.#', '#.#', '.#.', '#.#', '#.#'],
	Y: ['#.#', '#.#', '.#.', '.#.', '.#.'],
};

/** Width of a word in the micro font, one-pixel gaps included. */
export function microWidth(word: string): number {
	if (word.length === 0) {
		return 0;
	}

	return word.length * 4 - 1;
}

/** Draws a micro-font word with its top-left corner at `x`, `y`. */
export function microText(image: Bitmap, word: string, x: number, y: number, colour: Rgba): void {
	const palette: Palette = { '#': colour, '.': [0, 0, 0, 0] };
	let cursor = x;

	for (const character of word.toUpperCase()) {
		const glyph = MICRO[character] ?? MICRO[' ']!;

		stamp(image, glyph, palette, cursor, y, 1);
		cursor += 4;
	}
}

/** Draws a micro-font word centred on a point. */
function microCentered(image: Bitmap, word: string, cx: number, cy: number, colour: Rgba): void {
	microText(image, word, Math.round(cx - microWidth(word) / 2), Math.round(cy - 2.5), colour);
}

// ---- polar machinery -------------------------------------------------------

// Dial angles are measured the way a person reads a clock: zero at twelve,
// positive clockwise, in the viewer's own view of the face. The needle uses
// the same convention on the Kotlin side, so a value maps to one number that
// both the texture and the display agree on.

/** The unit vector a dial angle points along, in image coordinates (y down). */
function dirOf(deg: number): [number, number] {
	const rad = (deg * Math.PI) / 180;

	return [Math.sin(rad), -Math.cos(rad)];
}

/** The dial angle a pixel sits at, relative to a pivot. */
function angleAt(px: number, py: number, cx: number, cy: number): number {
	return (Math.atan2(px - cx, cy - py) * 180) / Math.PI;
}

/**
 * Fills the band between two radii across a span of dial angles: the coloured
 * zones, and the face of an arc window. Angles may run past +-180, so the
 * test walks the span rather than comparing raw atan2 values.
 */
function arcBand(
	image: Bitmap,
	cx: number,
	cy: number,
	rInner: number,
	rOuter: number,
	fromDeg: number,
	toDeg: number,
	colour: Rgba,
): void {
	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			const dx = x + 0.5 - cx;
			const dy = y + 0.5 - cy;
			const r = Math.sqrt(dx * dx + dy * dy);

			if (r < rInner || r > rOuter) {
				continue;
			}

			let a = angleAt(x + 0.5, y + 0.5, cx, cy);

			// bring the pixel's angle into the span's own winding
			while (a < fromDeg - 180) {
				a += 360;
			}
			while (a > fromDeg + 180) {
				a -= 360;
			}

			if (a >= fromDeg && a <= toDeg) {
				put(image, x, y, colour);
			}
		}
	}
}

/** One radial tick: a line from `r1` to `r2` along a dial angle. */
function tick(image: Bitmap, cx: number, cy: number, deg: number, r1: number, r2: number, colour: Rgba): void {
	const [dx, dy] = dirOf(deg);

	for (let r = r1; r <= r2; r += 0.5) {
		put(image, Math.round(cx + dx * r - 0.5), Math.round(cy + dy * r - 0.5), colour);
	}
}

// ---- the dial description ----------------------------------------------------

/** A coloured span of the scale, in fractions of full scale. */
export interface Zone {
	from: number;
	to: number;
	colour: Rgba;
}

/** A numeral printed inside the arc, at a fraction of full scale. */
export interface Numeral {
	at: number;
	text: string;
}

/**
 * Everything that shapes one dial. The same numbers are exported to the
 * Kotlin side, which is what keeps the painted arc and the live needle
 * telling the same story.
 */
export interface Dial {
	shape: 'round' | 'square' | 'column' | 'lcd';
	/** Needle pivot, in face pixels. Centred for round; low for a DIN meter. */
	pivot: [number, number];
	/** Scale radius, in face pixels. */
	radius: number;
	/** Dial angle at zero and at full scale, clockwise from twelve o'clock. */
	startDeg: number;
	endDeg: number;
	zones: Zone[];
	/** Major ticks (with minor ticks at a fifth of the spacing). */
	majors: number;
	numerals: Numeral[];
	/** The unit plate. */
	unit: string;
	/** What the instrument is for. */
	legend?: string;
	/**
	 * Where the two labels sit, in face pixels. Every dial says, because the
	 * free patch of face is somewhere else on every layout: under the hub on
	 * a centred dial, at the top of a galvanometer, mid-face on a DIN meter.
	 */
	unitPos: [number, number];
	legendPos?: [number, number];
	/**
	 * A large stamped letter, the way a real panel meter wears its quantity
	 * symbol: the micro font scaled up, put wherever the layout leaves room.
	 */
	emblem?: { text: string; at: [number, number]; scale: number };
	/** Dark face for the totaliser; pale for everything else. */
	darkFace?: boolean;
	/** A cut-out window (digits, or the auto-range plate), in face pixels. */
	window?: [number, number, number, number];
}

/** Linear interpolation along the dial's sweep. */
function angleOf(dial: Dial, fraction: number): number {
	return dial.startDeg + (dial.endDeg - dial.startDeg) * fraction;
}

// ---- painting ----------------------------------------------------------------

/** The brushed-metal bezel and face plate common to every instrument. */
function paintBody(image: Bitmap, dial: Dial): void {
	const faceColour = dial.darkFace ? FACE_DARK : FACE_PALE;

	if (dial.shape === 'round') {
		const outer = maskOf(image, (t) => disc(t, 32, 32, 31.5, FACE_PALE));
		const rim = maskOf(image, (t) => disc(t, 32, 32, 29, FACE_PALE));
		const inner = maskOf(image, (t) => disc(t, 32, 32, 27.5, FACE_PALE));

		paintMask(image, outer, BEZEL);
		paintMask(image, maskDifference(rim, inner), BEZEL_LIGHT);
		paintMask(image, inner, faceColour);
	} else {
		rect(image, 0, 0, 64, 64, BEZEL);
		rect(image, 2, 2, 62, 62, BEZEL_LIGHT);
		rect(image, 4, 4, 60, 60, faceColour);
	}

	// the faintest paper grain, so a pale face is not a dead fill
	if (!dial.darkFace) {
		for (let y = 4; y < 60; y++) {
			for (let x = 4; x < 60; x++) {
				if (noise(0x9e3779b9, x, y) > 0.9) {
					blend(image, x, y, [0, 0, 0, 14]);
				}
			}
		}
	}
}

/** The graduated scale: zones, the arc line, ticks and numerals. */
function paintScale(image: Bitmap, dial: Dial): void {
	// the totaliser has no scale at all: its face is the digit window
	if (dial.majors === 0) {
		return;
	}

	const [cx, cy] = dial.pivot;
	const r = dial.radius;
	const ink = dial.darkFace ? FACE_PALE : INK;

	for (const zone of dial.zones) {
		const a = angleOf(dial, zone.from);
		const b = angleOf(dial, zone.to);

		arcBand(image, cx, cy, r + 1, r + 4, Math.min(a, b), Math.max(a, b), zone.colour);
	}

	arcBand(
		image, cx, cy, r - 1, r + 1,
		Math.min(dial.startDeg, dial.endDeg), Math.max(dial.startDeg, dial.endDeg), ink,
	);

	const minors = dial.majors * 5;

	for (let index = 0; index <= minors; index++) {
		const major = index % 5 === 0;
		const deg = angleOf(dial, index / minors);

		tick(image, cx, cy, deg, major ? r - 6 : r - 3, r + (major ? 4 : 1), ink);
	}

	for (const numeral of dial.numerals) {
		const [dx, dy] = dirOf(angleOf(dial, numeral.at));

		microCentered(image, numeral.text, cx + dx * (r - 12), cy + dy * (r - 12), ink);
	}
}

/** The unit plate, the legend line, the pivot hub and the window cut-out. */
function paintFurniture(image: Bitmap, dial: Dial): void {
	const [cx, cy] = dial.pivot;
	const ink = dial.darkFace ? FACE_PALE : INK;

	if (dial.legend && dial.legendPos) {
		microCentered(image, dial.legend, dial.legendPos[0], dial.legendPos[1], ink);
	}

	if (dial.emblem) {
		const palette: Palette = { '#': ink, '.': [0, 0, 0, 0] };
		let cursor = dial.emblem.at[0];

		for (const character of dial.emblem.text.toUpperCase()) {
			stamp(image, MICRO[character] ?? MICRO[' ']!, palette, cursor, dial.emblem.at[1], dial.emblem.scale);
			cursor += 4 * dial.emblem.scale;
		}
	}

	microCentered(image, dial.unit, dial.unitPos[0], dial.unitPos[1], ink);

	if (dial.window) {
		const [wx, wy, ww, wh] = dial.window;

		rect(image, wx - 1, wy - 1, wx + ww + 1, wy + wh + 1, BEZEL_LIGHT);
		rect(image, wx, wy, wx + ww, wy + wh, WINDOW);
	}

	// the pivot hub, over everything: the needle appears to grow out of it
	disc(image, cx, cy, 2.6, tint(BEZEL, 0.8));
	disc(image, cx, cy, 1.4, BEZEL_LIGHT);
}

/** Paints one complete face and returns the PNG. */
export function dialFace(dial: Dial): Uint8Array {
	const image = bitmap(FACE, FACE);

	if (dial.shape === 'column') {
		paintColumn(image, dial);

		return encodePng(image);
	}

	if (dial.shape === 'lcd') {
		paintLcd(image, dial);

		return encodePng(image);
	}

	paintBody(image, dial);
	paintScale(image, dial);
	paintFurniture(image, dial);

	return encodePng(image);
}

/**
 * The LED level column: a dark slot the live bar climbs in, graduations and
 * numerals beside it, the zone band on the other flank. The body is the
 * square DIN housing; the slot's numbers come from the dial spec so the
 * painted scale and the live bar agree about where 100% is, exactly as a
 * needle dial's arc does.
 */
function paintColumn(image: Bitmap, dial: Dial): void {
	rect(image, 0, 0, 64, 64, BEZEL);
	rect(image, 2, 2, 62, 62, BEZEL_LIGHT);
	rect(image, 4, 4, 60, 60, FACE_DARK);

	const [wx, wy, ww, wh] = dial.window ?? [26, 10, 12, 44];

	// the slot the bar rides in, recessed a pixel into the housing
	rect(image, wx - 1, wy - 1, wx + ww + 1, wy + wh + 1, BEZEL_LIGHT);
	rect(image, wx, wy, wx + ww, wy + wh, WINDOW);

	// unlit segment rungs: the off state of the LED ladder, so an empty
	// column still reads as an instrument rather than as a black hole
	for (let index = 0; index <= 10; index++) {
		const y = Math.round(wy + wh - 1 - (wh - 2) * (index / 10));

		rect(image, wx + 1, y, wx + ww - 1, y + 1, rgb('#20242c'));
	}

	// graduations up the right flank, numerals past them
	for (let index = 0; index <= 10; index++) {
		const major = index % 5 === 0;
		const y = Math.round(wy + wh - 1 - (wh - 2) * (index / 10));

		rect(image, wx + ww + 2, y, wx + ww + (major ? 6 : 4), y + 1, FACE_PALE);
	}

	for (const numeral of dial.numerals) {
		const y = Math.round(wy + wh - 1 - (wh - 2) * numeral.at);

		microText(image, numeral.text, wx + ww + 8, y - 2, FACE_PALE);
	}

	// the zone band down the left flank, bottom = zero
	for (const zone of dial.zones) {
		const yTop = Math.round(wy + wh - 1 - (wh - 2) * zone.to);
		const yBottom = Math.round(wy + wh - 1 - (wh - 2) * zone.from);

		rect(image, wx - 5, yTop, wx - 2, yBottom + 1, zone.colour);
	}

	if (dial.legend && dial.legendPos) {
		microCentered(image, dial.legend, dial.legendPos[0], dial.legendPos[1], FACE_PALE);
	}

	microCentered(image, dial.unit, dial.unitPos[0], dial.unitPos[1], FACE_PALE);
}

/**
 * The digital multimeter face: a dark housing around one wide LCD window.
 * The digits are a live text display, so the paint is only the panel: the
 * recess, a resting segment ghost, and the labels.
 */
function paintLcd(image: Bitmap, dial: Dial): void {
	rect(image, 0, 0, 64, 64, BEZEL);
	rect(image, 2, 2, 62, 62, BEZEL_LIGHT);
	rect(image, 4, 4, 60, 60, FACE_DARK);

	const [wx, wy, ww, wh] = dial.window ?? [8, 22, 48, 18];

	rect(image, wx - 1, wy - 1, wx + ww + 1, wy + wh + 1, BEZEL_LIGHT);
	rect(image, wx, wy, wx + ww, wy + wh, rgb('#0d1a10'));

	// the faint unlit segments of an LCD at rest
	for (let x = wx + 3; x < wx + ww - 3; x += 6) {
		rect(image, x, wy + 3, x + 4, wy + wh - 3, rgb('#122416'));
	}

	if (dial.legend && dial.legendPos) {
		microCentered(image, dial.legend, dial.legendPos[0], dial.legendPos[1], FACE_PALE);
	}

	microCentered(image, dial.unit, dial.unitPos[0], dial.unitPos[1], FACE_PALE);
}

// ---- the needles -------------------------------------------------------------

// Each needle is a flat sprite the item model extrudes, authored pointing at
// twelve o'clock with its pivot on the sprite's own centre: an item display
// rotates about the model's middle, so the pivot costs nothing to line up.
// The blade runs from the centre to the top edge and a short counterweight
// tail drops behind the hub, the way a balanced instrument needle is built.

/**
 * One needle sprite: a single-pixel blade up from the pivot, a short dark
 * counterweight past it. One pixel is the whole point - a knife edge is what
 * a real instrument needle looks like, and anything wider reads as a clock
 * hand once it is standing on a dial in the world.
 */
function needleSprite(blade: Rgba): Uint8Array {
	const image = bitmap(16, 16);

	// radium lume on the last two pixels: a real instrument needle carries a
	// dab of luminous paint at the tip, and it is what keeps a black blade
	// readable in a dark room even at full display brightness
	put(image, 7, 1, rgb('#dcf5c6'));
	put(image, 7, 2, rgb('#bfe3a8'));

	for (let y = 3; y <= 7; y++) {
		put(image, 7, y, blade);
	}

	put(image, 7, 8, tint(blade, 0.55));
	put(image, 7, 9, tint(blade, 0.5));

	return encodePng(image);
}

/**
 * The corner meters' needle: same knife edge, no counterweight. A corner
 * pivot sits pixels from the face's edge, so the standard needle's tail -
 * harmless on a centred dial - swept far off the housing once the long
 * corner blade scaled it up. This one ends at the hub, and carries a single
 * pixel of lume instead of two: the corner blades run half again as long,
 * and the tip scales with them.
 */
function cornerNeedleSprite(blade: Rgba): Uint8Array {
	const image = bitmap(16, 16);

	put(image, 7, 1, rgb('#cfeeb8'));

	for (let y = 2; y <= 7; y++) {
		put(image, 7, y, blade);
	}

	// the hub pixel the blade grows out of
	put(image, 7, 8, tint(blade, 0.55));

	return encodePng(image);
}

/** The instrument casing: dark blue-steel, for bodies, sides and backs. */
function casingSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x165667b1, x, y >> 1) * 0.1 + 0.93;
			let colour = tint(rgb('#434855'), grain);

			// a rivet in each corner, which is what says "panel" at a glance
			if ((x === 2 || x === 13) && (y === 2 || y === 13)) {
				colour = rgb('#6a707d');
			}

			put(image, x, y, colour);
		}
	}

	return encodePng(image);
}

/**
 * The isolator's top plate: the O and I of a rotary disconnect, with the pip
 * for the live state lit and the other one dark. The orientation matches the
 * model: I towards the conduit's far end, O towards the near one.
 */
function switchTopSprite(on: boolean): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x27d4eb2f, x, y >> 1) * 0.08 + 0.94;

			put(image, x, y, tint(rgb('#4a505d'), grain));
		}
	}

	// I at the top of the plate (the far end of the run), O at the bottom
	microText(image, 'I', 2, 2, on ? rgb('#8fe08f') : rgb('#20242c'));
	microText(image, 'O', 2, 9, on ? rgb('#20242c') : rgb('#e08f8f'));

	// the state pips, on the other flank of the handle
	rect(image, 12, 3, 15, 6, on ? rgb('#3d8b40') : rgb('#1d2b1d'));
	rect(image, 12, 10, 15, 13, on ? rgb('#3b1d1d') : rgb('#c22f2a'));

	return encodePng(image);
}

/** The handle: safety red, with a grip line down the middle. */
function switchHandleSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x84222325, x, y) * 0.1 + 0.92;
			let colour = tint(rgb('#b3271f'), grain);

			if (x === 7 || x === 8) {
				colour = tint(colour, 0.8);
			}

			put(image, x, y, colour);
		}
	}

	return encodePng(image);
}

// ---- the device dressings ---------------------------------------------------

// The switch family and the one-way bridge wear small purpose textures: a
// porcelain base for the knife switch, weathered copper for its blade, the
// safety wheel of the valve, the moulded fronts of the contactor and the
// breaker, and the flow arrows on the bridge. All in the same dark blue-steel
// world as the casing, so a run of devices reads as one product line.

/** Glazed porcelain: the knife switch's base and hinge blocks. */
function ceramicSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x51ce214a, x >> 1, y >> 1) * 0.06 + 0.95;
			let colour = tint(rgb('#e3e0d6'), grain);

			// the glaze catches light along the top edge and shades below
			if (y === 0) {
				colour = tint(colour, 1.05);
			} else if (y === 15) {
				colour = tint(colour, 0.82);
			}

			put(image, x, y, colour);
		}
	}

	return encodePng(image);
}

/** Bare copper, slightly weathered: the knife blade and its jaws. */
function copperSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x7a6c9d31, x, y >> 1) * 0.12 + 0.9;
			let colour = tint(rgb('#c47f45'), grain);

			// streaks of tarnish along the pull direction
			if (noise(0x2f1b9e55, x >> 2, y) > 0.85) {
				colour = tint(rgb('#8f6a3e'), grain);
			}

			put(image, x, y, colour);
		}
	}

	return encodePng(image);
}

/** The valve's handwheel: safety red with a worn metal rim line. */
function wheelSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x4d2c611f, x, y) * 0.1 + 0.92;
			let colour = tint(rgb('#a82420'), grain);

			if ((x + y) % 7 === 0 && noise(0x66ddf1a2, x, y) > 0.7) {
				colour = tint(rgb('#7d7f86'), grain);
			}

			put(image, x, y, colour);
		}
	}

	return encodePng(image);
}

/** The pulse button's box: signal yellow with a black hazard rim. */
function buttonBoxSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x1d872ba3, x, y) * 0.08 + 0.94;
			const border = x < 2 || x > 13 || y < 2 || y > 13;
			const band = ((x + y) >> 1) & 1;
			const colour = border && band ? rgb('#1f1f22') : tint(rgb('#d9a521'), grain);

			put(image, x, y, colour);
		}
	}

	return encodePng(image);
}

/** The mushroom cap of the pulse button: red, domed by shading. */
function buttonCapSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const dx = (x + 0.5 - 8) / 8;
			const dy = (y + 0.5 - 8) / 8;
			const dome = 1 - Math.min(1, Math.sqrt(dx * dx + dy * dy)) * 0.35;
			const grain = noise(0x3e91c7d5, x, y) * 0.06 + 0.97;

			put(image, x, y, tint(rgb('#c22f2a'), dome * grain));
		}
	}

	return encodePng(image);
}

/**
 * The contactor's moulded front: terminal screws along the top and bottom
 * rows, the state flag in the middle window, and a pilot dot that lights
 * with the coil. Drawn for both flanks of the housing, so the state reads
 * from either side of the line.
 */
function relaySprite(on: boolean): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x5c11ab29, x, y >> 1) * 0.07 + 0.93;

			put(image, x, y, tint(rgb('#585e6a'), grain));
		}
	}

	// terminal rows: three screws top, three bottom
	for (const y of [1, 13]) {
		for (const x of [2, 7, 12]) {
			rect(image, x, y, x + 2, y + 2, rgb('#2a2d34'));
			put(image, x, y, rgb('#8d939f'));
		}
	}

	// the state window and its flag
	rect(image, 4, 5, 12, 11, rgb('#20242c'));
	microText(image, on ? 'I' : 'O', 6, 5, on ? rgb('#8fe08f') : rgb('#e08f8f'));

	// the coil pilot
	rect(image, 13, 6, 15, 8, on ? rgb('#3d8b40') : rgb('#1d2b1d'));

	return encodePng(image);
}

/** The breaker's flank: moulded black case with the rating stamp. */
function breakerSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x753af015, x, y >> 1) * 0.06 + 0.94;

			put(image, x, y, tint(rgb('#2b2e35'), grain));
		}
	}

	// the moulding line and the rating stamp
	rect(image, 1, 8, 15, 9, rgb('#22252b'));
	microText(image, 'C63', 3, 2, rgb('#8d939f'));

	return encodePng(image);
}

/** The breaker's top plate: the I/O stamps beside the lever slot. */
function breakerTopSprite(on: boolean): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x1fb0c9e7, x, y >> 1) * 0.06 + 0.94;

			put(image, x, y, tint(rgb('#32353d'), grain));
		}
	}

	// the lever slot down the middle of the plate
	rect(image, 6, 2, 10, 14, rgb('#1a1c21'));

	// I towards the far end, O towards the near one, lit by state
	microText(image, 'I', 1, 2, on ? rgb('#8fe08f') : rgb('#20242c'));
	microText(image, 'O', 1, 9, on ? rgb('#20242c') : rgb('#e08f8f'));

	return encodePng(image);
}

/**
 * The one-way bridge's flank: casing steel with the flow arrow. The arrow
 * runs from sprite left (the inlet) to sprite right (the outlet); the model
 * orients each face's uv so that direction lands on +z, the outlet end of a
 * north-facing block, on every side the body shows.
 */
function diodeSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x165667b1, x, y >> 1) * 0.1 + 0.93;

			put(image, x, y, tint(rgb('#434855'), grain));
		}
	}

	// the arrow: shaft up the middle, head at the outlet edge (sprite right),
	// in the hazard amber every industrial flow marking wears
	const amber = rgb('#d9a521');

	rect(image, 2, 7, 10, 9, amber);

	for (let step = 0; step < 4; step++) {
		rect(image, 10 + step, 4 + step, 11 + step, 12 - step, amber);
	}

	return encodePng(image);
}

/** The outlet flange ring: the green end you may draw from. */
function diodeOutSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x9142ffab, x, y >> 1) * 0.1 + 0.92;
			const border = x < 2 || x > 13 || y < 2 || y > 13;

			put(image, x, y, tint(border ? rgb('#2e5c31') : rgb('#3d8b40'), grain));
		}
	}

	return encodePng(image);
}

/**
 * The live LED bar the level columns climb: a 2-pixel strip of lit segments,
 * every fourth row a dark gap so the bar reads as a ladder of LEDs rather
 * than as a paint stripe. The blade half above the sprite's middle row is
 * what the model shows; scaling the display stretches the ladder, which at
 * gameplay distance reads as more segments lighting, not as taffy.
 */
function barSprite(lit: Rgba): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (const x of [7, 8]) {
			const gap = y % 4 === 3;
			const edge = x === 7 ? 1.0 : 0.86;

			put(image, x, y, gap ? tint(lit, 0.25) : tint(lit, edge));
		}
	}

	return encodePng(image);
}

/**
 * The activity indicator's plate: a 32px face for an 8-pixel panel, three
 * LED wells down the left and the E/I/F letters beside them - the port
 * lights of a network, in the order energy, items, fluid.
 */
function ledPanelSprite(): Uint8Array {
	const image = bitmap(32, 32);

	for (let y = 0; y < 32; y++) {
		for (let x = 0; x < 32; x++) {
			const grain = noise(0x33ab77cd, x >> 1, y >> 1) * 0.08 + 0.9;
			const border = x < 2 || x > 29 || y < 2 || y > 29;

			put(image, x, y, tint(border ? rgb('#2b2e35') : rgb('#434855'), grain));
		}
	}

	// the wells the LEDs sit in, recessed dark
	for (const cy of [7, 16, 25]) {
		rect(image, 6, cy - 3, 14, cy + 3, rgb('#15171b'));
	}

	// near-white: gray-on-slate proved unreadable on a survival night
	microText(image, 'E', 18, 5, rgb('#e8ebf0'));
	microText(image, 'I', 18, 14, rgb('#e8ebf0'));
	microText(image, 'F', 18, 23, rgb('#e8ebf0'));

	return encodePng(image);
}

/** One LED chip: a bright centre falling off to the rim, 4x4. */
function ledChipSprite(core: Rgba): Uint8Array {
	const image = bitmap(4, 4);

	for (let y = 0; y < 4; y++) {
		for (let x = 0; x < 4; x++) {
			const edge = x === 0 || x === 3 || y === 0 || y === 3;

			put(image, x, y, edge ? tint(core, 0.6) : core);
		}
	}

	return encodePng(image);
}

/** The alarm dome: safety red, glassy highlight up one flank. */
function alarmDomeSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x8811fe23, x, y) * 0.08 + 0.9;
			let colour = tint(rgb('#c22f2a'), grain);

			if (x >= 3 && x <= 5 && y >= 2 && y <= 10) {
				colour = tint(rgb('#e8837f'), grain);
			}

			put(image, x, y, colour);
		}
	}

	return encodePng(image);
}

/** The alarm's sweeping beam: hot in the middle, falling to the ends. */
function alarmBeamSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const off = Math.abs(x + 0.5 - 8) / 8;
			const hot = 1 - off * 0.55;

			put(image, x, y, tint(rgb('#ffb347'), hot));
		}
	}

	return encodePng(image);
}

/** The light panel's face: a framed diffuser, warm when fed, grey when not. */
function lightPanelSprite(lit: boolean): Uint8Array {
	const image = bitmap(16, 16);
	const base = lit ? rgb('#fff3d1') : rgb('#a7aaa4');

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const border = x < 1 || x > 14 || y < 1 || y > 14;

			if (border) {
				put(image, x, y, tint(rgb('#434855'), noise(0x165667b1, x, y >> 1) * 0.1 + 0.93));
				continue;
			}

			const dx = (x + 0.5 - 8) / 8;
			const dy = (y + 0.5 - 8) / 8;
			const centre = 1 - Math.min(1, Math.sqrt(dx * dx + dy * dy)) * (lit ? 0.12 : 0.08);
			const grain = noise(0x2545f491, x, y) * 0.04 + 0.98;

			put(image, x, y, tint(base, centre * grain));
		}
	}

	return encodePng(image);
}

/** Every texture the gauges paint for themselves, by written name. */
export function gaugeCommonSprites(): Record<string, Uint8Array> {
	return {
		gauge_needle_red: needleSprite(rgb('#c22f2a')),
		gauge_needle_black: needleSprite(rgb('#1c1e24')),
		gauge_needle_orange: needleSprite(rgb('#d97e21')),
		gauge_needle_corner_red: cornerNeedleSprite(rgb('#c22f2a')),
		gauge_needle_corner_black: cornerNeedleSprite(rgb('#1c1e24')),
		gauge_needle_corner_orange: cornerNeedleSprite(rgb('#d97e21')),
		gauge_casing: casingSprite(),
		gauge_switch_top_on: switchTopSprite(true),
		gauge_switch_top_off: switchTopSprite(false),
		gauge_switch_handle: switchHandleSprite(),
		gauge_ceramic: ceramicSprite(),
		gauge_copper: copperSprite(),
		gauge_wheel: wheelSprite(),
		gauge_button_box: buttonBoxSprite(),
		gauge_button_cap: buttonCapSprite(),
		gauge_relay_on: relaySprite(true),
		gauge_relay_off: relaySprite(false),
		gauge_breaker: breakerSprite(),
		gauge_breaker_top_on: breakerTopSprite(true),
		gauge_breaker_top_off: breakerTopSprite(false),
		gauge_diode_side: diodeSprite(),
		gauge_diode_out: diodeOutSprite(),
		gauge_bar_amber: barSprite(rgb('#e8b422')),
		gauge_bar_blue: barSprite(rgb('#3d8bd9')),
		network_led: ledPanelSprite(),
		gauge_led_amber: ledChipSprite(rgb('#ffcf4d')),
		gauge_led_green: ledChipSprite(rgb('#6fe06f')),
		gauge_led_blue: ledChipSprite(rgb('#5fa8ff')),
		alarm_dome: alarmDomeSprite(),
		alarm_beam: alarmBeamSprite(),
		light_panel_on: lightPanelSprite(true),
		light_panel_off: lightPanelSprite(false),
	};
}
