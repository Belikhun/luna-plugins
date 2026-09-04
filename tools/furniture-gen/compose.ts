// Shared model composition: the geometry both catalogs build their own pieces
// out of, over `minecraft:` texture references the client already has.
//
// Composing rather than copying is what lets one entry serve twenty-six
// flowers, and it is also the only way to take a design's idea without taking
// its art: nothing here redistributes anybody's texture.

/** A vanilla-format block model, as the generator writes it out. */
export interface Model {
	textures: Record<string, string>;
	elements: unknown[];
	display?: Record<string, unknown>;
}

export type Vec3 = [number, number, number];
export type Element = Record<string, unknown>;

/** A crossed pair of planes, the shape every vanilla flower is drawn as.
 *
 * Vanilla's `block/cross` is one 14.4-wide plane turned 45 degrees about the
 * block's middle with `rescale`, plus its mirror on the other axis. This is
 * that, free to sit anywhere and at any size: `rescale` keeps the turned plane
 * covering the same footprint, so the width given is the width you get.
 */
export function cross(
	texture: string,
	cx: number,
	cz: number,
	y: number,
	height: number,
	width: number,
	uv: [number, number, number, number] = [0, 0, 16, 16],
): Element[] {
	const half = width / 2;
	const origin: Vec3 = [cx, y + height / 2, cz];
	const spin = { origin, axis: 'y', angle: 45, rescale: true };

	return [
		{
			from: [cx - half, y, cz],
			to: [cx + half, y + height, cz],
			rotation: spin,
			shade: false,
			faces: {
				north: { uv, texture },
				south: { uv, texture },
			},
		},
		{
			from: [cx, y, cz - half],
			to: [cx, y + height, cz + half],
			rotation: spin,
			shade: false,
			faces: {
				west: { uv, texture },
				east: { uv, texture },
			},
		},
	];
}

/** The six faces of a box, in the order a model lists them. */
export type Face = 'north' | 'east' | 'south' | 'west' | 'up' | 'down';

const FACES: Face[] = ['north', 'east', 'south', 'west', 'up', 'down'];

/**
 * The uv rectangle a face takes when a model says nothing, which is what the
 * client works out for itself: the texture runs across the piece at its own
 * scale rather than being squeezed onto every face whatever its size.
 *
 * A box reaching outside the block (the vanity's mirror runs to y 26, a door
 * handle pokes past z 0) would map outside [0, 16], and the client samples
 * whatever neighbours the sprite in the atlas: the mirror frame came back as
 * a strip of some other block. The window is slid back inside the sheet, and
 * a span past a full tile is capped at one; the grain shifts, which on the
 * uniform textures these builds use is invisible, and the bleed is not.
 */
export function autoUv(dir: Face, f: Vec3, t: Vec3): [number, number, number, number] {
	switch (dir) {
		case 'north':
			return fitUv([16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]]);
		case 'south':
			return fitUv([f[0], 16 - t[1], t[0], 16 - f[1]]);
		case 'west':
			return fitUv([f[2], 16 - t[1], t[2], 16 - f[1]]);
		case 'east':
			return fitUv([16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]]);
		case 'up':
			return fitUv([f[0], f[2], t[0], t[2]]);
		default:
			return fitUv([f[0], 16 - t[2], t[0], 16 - f[2]]);
	}
}

/** Slides a uv window back inside the [0, 16] sheet, capping spans past one tile. */
function fitUv(uv: [number, number, number, number]): [number, number, number, number] {
	for (const axis of [0, 1]) {
		let lo = Math.min(uv[axis], uv[axis + 2]);
		let hi = Math.max(uv[axis], uv[axis + 2]);

		let shift = 0;

		if (hi > 16) {
			shift = 16 - hi;
		}

		if (lo + shift < 0) {
			shift = -lo;
		}

		uv[axis] = Math.max(0, Math.min(16, uv[axis] + shift));
		uv[axis + 2] = Math.max(0, Math.min(16, uv[axis + 2] + shift));
	}

	return uv;
}

/**
 * A part of a piece: a box whose faces take their uv from where the box sits,
 * and which may wear a different texture on any face.
 *
 * This is what `box` should have been for anything with a grain. `box` puts
 * the whole texture on every face, which is fine for a full cube and for the
 * flat colours the planters are made of, and wrong for a plank drawer front:
 * a 16x3 face wearing a whole plank texture reads as a smear.
 *
 * @param faces one texture for the whole box, or one per face over a default
 * @param opts `cull` names the block faces the box is flush with, `only` the
 *   faces to draw at all (a part sunk into another needs no hidden sides)
 */
export function part(
	from: Vec3,
	to: Vec3,
	faces: string | ({ all: string } & Partial<Record<Face, string>>),
	opts: { cull?: Face[]; only?: Face[]; shade?: boolean } = {},
): Element {
	const drawn = opts.only ?? FACES;
	const out: Record<string, unknown> = {};

	for (const dir of drawn) {
		const texture = typeof faces === 'string' ? faces : (faces[dir] ?? faces.all);
		const face: Record<string, unknown> = { uv: autoUv(dir, from, to), texture };

		if (opts.cull?.includes(dir)) {
			face.cullface = dir;
		}

		out[dir] = face;
	}

	const element: Element = { from, to, faces: out };

	if (opts.shade === false) {
		element.shade = false;
	}

	return element;
}

/** A box drawn with one texture on every face, optionally culled at the block's own faces. */
export function box(from: Vec3, to: Vec3, texture: string, cull = false): Element {
	const faces: Record<string, unknown> = {};
	const sides = ['north', 'east', 'south', 'west', 'up', 'down'] as const;

	for (const side of sides) {
		const face: Record<string, unknown> = { uv: [0, 0, 16, 16], texture };

		if (cull) {
			face.cullface = side;
		}

		faces[side] = face;
	}

	return { from, to, faces };
}

/** A single flat plane facing north and south: the sheet a wall vine hangs as. */
export function sheet(texture: string, z: number): Element {
	return {
		from: [0, 0, z],
		to: [16, 16, z],
		shade: false,
		faces: {
			north: { uv: [0, 0, 16, 16], texture },
			south: { uv: [0, 0, 16, 16], texture },
		},
	};
}

/** The transforms a composed block model is shown with in hand and in the menu. */
export const DISPLAY = {
	gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
	ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
	fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
	thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
	thirdperson_lefthand: { rotation: [75, 315, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
	firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
	firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
};

/**
 * A model out of parts that name their textures directly.
 *
 * A face's texture has to be a `#variable`: a bare path there resolves to
 * nothing and the piece is drawn as the missing-texture cube, which is exactly
 * how the first cut of the beds and the vanities shipped. This collects every
 * distinct path the parts name, gives each one a variable, and rewrites the
 * faces to point at it, so a builder can go on writing the texture it means.
 *
 * @param elements parts from `part`, whose faces may name paths
 * @param particle the texture the break particles take; the first one used
 *   when it is not given
 */
export function composed(elements: Element[], particle?: string): Model {
	const textures: Record<string, string> = {};
	const names = new Map<string, string>();

	const variable = (path: string): string => {
		if (path.startsWith('#')) {
			return path;
		}

		const existing = names.get(path);

		if (existing) {
			return `#${existing}`;
		}

		const name = `t${names.size}`;
		names.set(path, name);
		textures[name] = path;

		return `#${name}`;
	};

	for (const element of elements) {
		const faces = element.faces as Record<string, Record<string, unknown>>;

		for (const face of Object.values(faces)) {
			face.texture = variable(face.texture as string);
		}
	}

	textures.particle = particle ?? textures.t0 ?? 'minecraft:block/oak_planks';

	return { textures, elements, display: DISPLAY };
}


export function model(textures: Record<string, string>, elements: Element[]): Model {
	return { textures, elements, display: DISPLAY };
}


/**
 * A crown of flowers around a worn head.
 *
 * A leafy band first, hugging the brow the way the wreath does, and then the
 * blooms standing out of it. The first cut of this was the blooms alone, and
 * twelve quads floating in a ring read as loose petals, not as a crown: the
 * band is what makes it one object. Blooms are small quads rather than one
 * stretched sheet, three to a side, cycling through the crown's flowers, each
 * facing out of the head it sits on; their UV crops to the bloom so no stem
 * hangs down the wearer's forehead.
 */
export function garland(flowers: string[]): Element[] {
	const elements: Element[] = [];
	const lo = -0.8;
	const hi = 16.8;

	const band: Array<[Vec3, Vec3]> = [
		[[lo, 10, lo], [hi, 13, lo + 2]],
		[[lo, 10, hi - 2], [hi, 13, hi]],
		[[lo, 10, lo + 2], [lo + 2, 13, hi - 2]],
		[[hi - 2, 10, lo + 2], [hi, 13, hi - 2]],
	];

	for (const [from, to] of band) {
		elements.push(box(from, to, '#leaf'));
	}

	const uv = [3, 0, 13, 10];
	const offsets = [0.5, 5.75, 11];
	let next = 0;

	const quad = (from: Vec3, to: Vec3, sides: [string, string]): void => {
		const texture = `#f${next % flowers.length}`;
		next++;

		elements.push({
			from,
			to,
			shade: false,
			faces: {
				[sides[0]]: { uv, texture },
				[sides[1]]: { uv, texture },
			},
		});
	};

	for (const at of offsets) {
		quad([at, 11, lo - 0.2], [at + 5, 17, lo - 0.2], ['north', 'south']);
		quad([at, 11, hi + 0.2], [at + 5, 17, hi + 0.2], ['north', 'south']);
		quad([lo - 0.2, 11, at], [lo - 0.2, 17, at + 5], ['west', 'east']);
		quad([hi + 0.2, 11, at], [hi + 0.2, 17, at + 5], ['west', 'east']);
	}

	return elements;
}

/** The transforms a crown is worn and held with. */
export const CROWN_DISPLAY = {
	head: { scale: [1.0, 1.0, 1.0] },
	gui: { rotation: [30, 225, 0], translation: [0, -3, 0], scale: [0.75, 0.75, 0.75] },
	ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.3, 0.3, 0.3] },
	fixed: { rotation: [0, 0, 0], translation: [0, -3, 0], scale: [0.6, 0.6, 0.6] },
	thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.35, 0.35, 0.35] },
	thirdperson_lefthand: { rotation: [75, 315, 0], translation: [0, 2.5, 0], scale: [0.35, 0.35, 0.35] },
	firstperson_righthand: { rotation: [15, 45, 0], translation: [0, 3, 0], scale: [0.4, 0.4, 0.4] },
	firstperson_lefthand: { rotation: [15, 315, 0], translation: [0, 3, 0], scale: [0.4, 0.4, 0.4] },
};
