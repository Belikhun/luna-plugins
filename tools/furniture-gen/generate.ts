// Generates luna-smp's block assets: the furniture adapted from the staged
// source packs, and the flora, signs and industrial pieces drawn here.
//
//   bun generate.ts --sources <dir> --addon <luna-smp resources dir>
//
// Three catalogs come out of one pass, each with its own Kotlin table:
// catalog.ts -> FurnitureCatalog, flora.ts -> FloraCatalog, signs.ts ->
// SignCatalog. They share the model composer, the texture adoption and the
// language files, which is why they are generated together rather than apart.
//
// For every catalog entry it resolves the source model's parent chain, merges
// the listed models into one standalone model, rewrites bundled texture
// references into the lunasmp namespace (copying the PNGs), and writes the
// block model, the Nova config and both language files. It finishes by writing
// the Kotlin registration table.
//
// Every emitted block model is authored facing north, whatever its source
// faced: Nova rotates the north model per facing, so a source authored facing
// east (Adorn's shelf and sofa) declares `rotateY` and the turn is baked in
// here rather than special-cased at runtime.
//
// Nothing here runs on the server: this is a build-time tool, and its output is
// committed with the addon.

import { readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync, rmSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { CATALOG, CROWNS, type Piece, clockHandModels } from './catalog';
import { FLORA, FLOWERS, type Flora } from './flora';
import { bushSprite, mossOverlay, ropeSprite, vineSprite, waterSheet } from './sprites';
import { INDUSTRIAL_SPRITES } from './industrial';
import { frameSprites, medievalPieces } from './medieval';
import { POST_FACE_Z, SIGNS, WALL_FACE_Z, signModels, signSprites } from './signs';
import { BLOCK_FACE, DEVICES, DIODE, GAUGES, PANEL_FACE, SWITCHES, UNIT_FACE, alarmBeamModel, barModels, directionalJointLongModels, directionalJointModels, gaugeModels, gaugeSprites, ledChipModels, needleModels } from './gauges';

interface Model {
	parent?: string;
	textures?: Record<string, string>;
	elements?: unknown[];
	display?: Record<string, unknown>;
}

type Element = Record<string, unknown>;
type Vec3 = [number, number, number];
type Vec2 = [number, number];

const args = process.argv.slice(2);
let sourcesDir = '';
let addonDir = '';

for (let i = 0; i < args.length; i++) {
	if (args[i] === '--sources') {
		sourcesDir = args[++i]!;
	} else if (args[i] === '--addon') {
		addonDir = args[++i]!;
	}
}

if (!sourcesDir || !addonDir) {
	console.error('usage: bun generate.ts --sources <dir> --addon <dir>');
	process.exit(1);
}

/**
 * Where each source namespace's assets live. Adorn splits its tree: the
 * per-material models are generated into one root, the templates they inherit
 * from and every texture live in another, so a namespace maps to a list. The
 * `luna` namespace is ours: the few models nobody upstream ships.
 */
const SKNIRO = [
	join(sourcesDir, 'skniro/Fabric/src/main/generated/assets/skniro_furniture'),
	join(sourcesDir, 'skniro/Fabric/src/main/resources/assets/skniro_furniture'),
	join(sourcesDir, 'skniro/Neoforge/src/main/resources/assets/skniro_furniture'),
];

const ROOTS: Record<string, string[]> = {
	tac: [join(sourcesDir, 'tac/assets/tac')],
	adorn: [
		join(sourcesDir, 'adorn/common/src/generated/resources/assets/adorn'),
		join(sourcesDir, 'adorn/common/src/main/resources/assets/adorn'),
	],
	beautify: [join(sourcesDir, 'beautify-refab/src/main/resources/assets/beautify')],
	decorative_blocks: [join(sourcesDir, 'decoblocks/Common/src/main/resources/assets/decorative_blocks')],
	many_flowers: [join(sourcesDir, 'many-flowers/assets/many_flowers')],
	cozyhome: [
		join(sourcesDir, 'cozyhome/src/main/resources/assets/cozyhome'),
		join(sourcesDir, 'cozyhome/src/main/generated/assets/cozyhome'),
	],
	// the mod's own namespace is what its models reference each other by, so it
	// is registered under both that and the short name the catalog uses
	skniro: SKNIRO,
	skniro_furniture: SKNIRO,
	furnies: [join(sourcesDir, 'furnies/common/src/main/resources/assets/furnies')],
	analogclock: [join(sourcesDir, 'analog/src/main/resources/assets/analogclock')],
	engineersdecor: [join(sourcesDir, 'engdecor/src/main/resources/assets/engineersdecor')],
	// the medieval wave: Rustic's assets out of its 1.20.1 jar, Aesthetic Frames'
	// texture strips (cut into tiles by frameSprites, never read as models), and
	// Builder's Bounty's resource pack, whose models sit under the vanilla
	// namespace and are reached through this root instead
	rustic: [join(sourcesDir, 'rustic/assets/rustic')],
	bbounty: [join(sourcesDir, 'bbounty/assets/minecraft')],
	luna: [join(import.meta.dir, 'assets/luna')],
};

// every piece the addon ships. The medieval wave reads the wood and colour
// words out of catalog.ts, so catalog.ts cannot spread it in turn without an
// import cycle; the two lists meet here instead
const PIECES: Piece[] = [...CATALOG, ...medievalPieces(sourcesDir)];

const ADDON_MODELS = join(addonDir, 'assets/models/block');
const ADDON_TEXTURES = join(addonDir, 'assets/textures/block');
const ADDON_LANG = join(addonDir, 'assets/lang');
const ADDON_ITEMS = join(addonDir, 'assets/models/item');
const ADDON_CONFIGS = join(addonDir, 'configs');

function splitRef(ref: string): [string, string] {
	const idx = ref.indexOf(':');
	if (idx < 0) {
		return ['minecraft', ref];
	}
	return [ref.slice(0, idx), ref.slice(idx + 1)];
}

function findFile(ns: string, kind: 'models' | 'textures', path: string, ext: string): string | undefined {
	for (const root of ROOTS[ns] ?? []) {
		const candidate = join(root, kind, path + ext);
		if (existsSync(candidate)) {
			return candidate;
		}
	}
	return undefined;
}

function loadModel(ref: string): Model {
	const [ns, path] = splitRef(ref);
	const file = findFile(ns, 'models', path, '.json');

	if (!file) {
		throw new Error(`model not found: ${ref}`);
	}

	return JSON.parse(readFileSync(file, 'utf8'));
}

/** Collapses a model's parent chain: child textures win, nearest elements win. */
function resolveChain(entry: Model): Model {
	let textures: Record<string, string> = {};
	let elements: unknown[] | undefined;
	let display: Record<string, unknown> | undefined;
	let current: Model | undefined = entry;
	let guard = 0;

	while (current && guard++ < 16) {
		textures = { ...(current.textures ?? {}), ...textures };

		if (!elements && current.elements) {
			elements = current.elements;
		}

		if (!display && current.display) {
			display = current.display;
		}

		const parent: string | undefined = current.parent;
		// a bare parent such as "block/block" is minecraft's own, and the client
		// already has it: the chain ends wherever it leaves the source pack
		const parentNs = parent === undefined ? undefined : splitRef(parent)[0];
		current = parent !== undefined && !parent.startsWith('builtin/') && parentNs !== 'minecraft'
			? loadModel(parent)
			: undefined;
	}

	return { textures, elements: elements ?? [], display };
}

// bundled textures copied into the addon, keyed by their source reference so
// the same texture used by several pieces is copied once
const textureNames = new Map<string, string>();
const takenNames = new Set<string>();
const missingTextures: string[] = [];

/**
 * A texture reference as its own pack means it.
 *
 * A path with no namespace is the client's own - `block/oak_planks` - unless
 * the pack itself has a file at that path: Builder's Bounty names its art
 * `item/candlestick`, and reading that as `minecraft:item/candlestick` is a
 * missing-texture cube on every face that uses it.
 */
function qualify(ref: string, ns: string): string {
	if (ref.includes(':') || findFile(ns, 'textures', ref, '.png') === undefined) {
		return ref;
	}

	return `${ns}:${ref}`;
}

function adoptTexture(ref: string): string {
	const [ns, path] = splitRef(ref);

	// vanilla the client already has, and lunasmp is already ours: the painted
	// bush sprites are written straight into the addon under that namespace
	if (ns === 'minecraft' || ns === 'lunasmp') {
		return ref;
	}

	const existing = textureNames.get(ref);
	if (existing) {
		return existing;
	}

	let name = basename(path);
	if (takenNames.has(name)) {
		name = `${ns}_${name}`;
	}

	const source = findFile(ns, 'textures', path, '.png');
	if (!source) {
		missingTextures.push(ref);
		return ref;
	}

	mkdirSync(ADDON_TEXTURES, { recursive: true });
	copyFileSync(source, join(ADDON_TEXTURES, `${name}.png`));

	// an animated texture carries its timing beside it
	const meta = source + '.mcmeta';
	if (existsSync(meta)) {
		copyFileSync(meta, join(ADDON_TEXTURES, `${name}.png.mcmeta`));
	}

	const adopted = `lunasmp:block/${name}`;
	textureNames.set(ref, adopted);
	takenNames.add(name);
	return adopted;
}

// ---- geometry -----------------------------------------------------------

function faceCorners(dir: string, f: Vec3, t: Vec3): [Vec3, Vec3, Vec3, Vec3] {
	const [x1, y1, z1] = f;
	const [x2, y2, z2] = t;

	switch (dir) {
		case 'north': return [[x2, y2, z1], [x1, y2, z1], [x1, y1, z1], [x2, y1, z1]];
		case 'south': return [[x1, y2, z2], [x2, y2, z2], [x2, y1, z2], [x1, y1, z2]];
		case 'west': return [[x1, y2, z1], [x1, y2, z2], [x1, y1, z2], [x1, y1, z1]];
		case 'east': return [[x2, y2, z2], [x2, y2, z1], [x2, y1, z1], [x2, y1, z2]];
		case 'up': return [[x1, y2, z1], [x2, y2, z1], [x2, y2, z2], [x1, y2, z2]];
		case 'down': return [[x1, y1, z2], [x2, y1, z2], [x2, y1, z1], [x1, y1, z1]];
		default: throw new Error(`unknown face ${dir}`);
	}
}

function autoUv(dir: string, f: Vec3, t: Vec3): [number, number, number, number] {
	switch (dir) {
		case 'north': return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]];
		case 'south': return [f[0], 16 - t[1], t[0], 16 - f[1]];
		case 'west': return [f[2], 16 - t[1], t[2], 16 - f[1]];
		case 'east': return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]];
		case 'up': return [f[0], f[2], t[0], t[2]];
		case 'down': return [f[0], 16 - t[2], t[0], 16 - f[2]];
		default: throw new Error(`unknown face ${dir}`);
	}
}

/**
 * The uv coordinate each of a face's four corners takes. A face's uv is a
 * rectangle plus a spin, and the spin says which corner of that rectangle the
 * first vertex gets, which is what the client's own face baking does.
 */
function uvCorners(uv: number[], spin: number): Vec2[] {
	const rect: Vec2[] = [
		[uv[0]!, uv[1]!],
		[uv[2]!, uv[1]!],
		[uv[2]!, uv[3]!],
		[uv[0]!, uv[3]!],
	];
	const shift = (((spin / 90) % 4) + 4) % 4;

	return [0, 1, 2, 3].map((i) => rect[(i + shift) % 4]!);
}

/** The inverse: the uv rectangle that gives these corners under this spin. */
function uvRect(corners: Vec2[], spin: number): [number, number, number, number] {
	const shift = (((spin / 90) % 4) + 4) % 4;
	const first = corners[(4 - shift) % 4]!;
	const third = corners[(6 - shift) % 4]!;
	const round = (n: number): number => Math.round(n * 1e4) / 1e4;

	return [round(first[0]), round(first[1]), round(third[0]), round(third[1])];
}

function sub(a: Vec3, b: Vec3): Vec3 {
	return [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
}

function dot(a: Vec3, b: Vec3): number {
	return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

/** Where a point on a face lands in that face's texture. */
function uvAt(corners: Vec3[], uv: Vec2[], p: Vec3): Vec2 {
	const e1 = sub(corners[1]!, corners[0]!);
	const e2 = sub(corners[3]!, corners[0]!);
	const d = sub(p, corners[0]!);
	const s = dot(e1, e1) === 0 ? 0 : dot(d, e1) / dot(e1, e1);
	const t = dot(e2, e2) === 0 ? 0 : dot(d, e2) / dot(e2, e2);

	return [
		uv[0]![0] + s * (uv[1]![0] - uv[0]![0]) + t * (uv[3]![0] - uv[0]![0]),
		uv[0]![1] + s * (uv[1]![1] - uv[0]![1]) + t * (uv[3]![1] - uv[0]![1]),
	];
}

/** Turns a point about an axis through an origin, the way a model does. */
function turnPoint(p: Vec3, origin: Vec3, axis: string, angle: number): Vec3 {
	const t = (angle * Math.PI) / 180;
	const cos = Math.cos(t);
	const sin = Math.sin(t);
	const [dx, dy, dz] = sub(p, origin);

	switch (axis) {
		case 'x': return [origin[0] + dx, origin[1] + dy * cos - dz * sin, origin[2] + dy * sin + dz * cos];
		case 'y': return [origin[0] + dx * cos + dz * sin, origin[1] + dy, origin[2] - dx * sin + dz * cos];
		default: return [origin[0] + dx * cos - dy * sin, origin[1] + dx * sin + dy * cos, origin[2] + dz];
	}
}

/**
 * The dimension a bar-like element runs along, or -1 when it is not bar-like.
 *
 * A rotated element can only be cut along its own length: that cut is an axis
 * aligned one in the element's own frame, and the square end it leaves sits at
 * most a pixel from where the true diagonal cut would fall. A rotated *sheet*
 * (the trellis's flower fans) has no single length and is left alone.
 */
function barAxis(from: Vec3, to: Vec3): number {
	const size: Vec3 = [to[0] - from[0], to[1] - from[1], to[2] - from[2]];
	const longest = size.indexOf(Math.max(...size));
	const others = [0, 1, 2].filter((i) => i !== longest);

	return others.every((i) => size[i] <= 4) ? longest : -1;
}

/** Whether [clip] can cut this element down rather than keep or drop it whole. */
function isClippable(el: Element): boolean {
	const rotation = el.rotation as { angle: number } | undefined;

	if (rotation === undefined || rotation.angle === 0) {
		return true;
	}

	return barAxis(el.from as Vec3, el.to as Vec3) >= 0;
}

/**
 * Cuts the elements down to the slab between [lo, hi] on one axis, carrying
 * each face's uv rectangle along with the geometry so the texture does not
 * slide across what is left.
 *
 * A bar with a rotation of its own is cut along its own length instead, at the
 * point where the turned bar crosses the boundary. Anything else that is
 * rotated is kept or dropped whole: its faces are no longer axis aligned, and a
 * box clip would cut the wrong plane.
 */
function clip(elements: Element[], axis: 0 | 1 | 2, lo: number, hi: number): Element[] {
	const out: Element[] = [];

	for (const source of elements) {
		const el = JSON.parse(JSON.stringify(source)) as Element;
		const from = el.from as Vec3;
		const to = el.to as Vec3;
		const rotation = el.rotation as { origin: Vec3; axis: string; angle: number } | undefined;
		let cutAxis: 0 | 1 | 2 = axis;
		let cutLo = lo;
		let cutHi = hi;

		if (rotation !== undefined && rotation.angle !== 0) {
			const bar = barAxis(from, to);

			if (bar < 0) {
				const middle = (from[axis] + to[axis]) / 2;

				if (middle >= lo && middle <= hi) {
					out.push(el);
				}

				continue;
			}

			// where the bar's own length crosses the boundary: its turned
			// centreline moves along the clipped axis at a constant rate
			const centre = (i: number): number => (from[i] + to[i]) / 2;
			const at = (length: number): Vec3 => {
				const p: Vec3 = [centre(0), centre(1), centre(2)];
				p[bar] = length;
				return turnPoint(p, rotation.origin, rotation.axis, rotation.angle);
			};

			const start = at(from[bar]);
			const end = at(to[bar]);
			const travel = end[axis] - start[axis];

			if (Math.abs(travel) < 1e-6) {
				if (start[axis] < lo || start[axis] > hi) {
					continue;
				}
			} else {
				const span = to[bar] - from[bar];
				const cuts = [(lo - start[axis]) / travel, (hi - start[axis]) / travel]
					.map((fraction) => from[bar] + fraction * span)
					.sort((a, b) => a - b);

				cutAxis = bar as 0 | 1 | 2;
				cutLo = cuts[0]!;
				cutHi = cuts[1]!;
			}
		}

		const nf: Vec3 = [...from];
		const nt: Vec3 = [...to];
		nf[cutAxis] = Math.max(from[cutAxis], cutLo);
		nt[cutAxis] = Math.min(to[cutAxis], cutHi);

		// gone entirely, or a box thinned into a plane it never was
		if (nf[cutAxis] > nt[cutAxis] || (nf[cutAxis] === nt[cutAxis] && from[cutAxis] !== to[cutAxis])) {
			continue;
		}

		if (nf[cutAxis] !== from[cutAxis] || nt[cutAxis] !== to[cutAxis]) {
			for (const [dir, raw] of Object.entries(el.faces as Record<string, Record<string, unknown>>)) {
				const uv = (raw.uv as number[] | undefined) ?? autoUv(dir, from, to);
				const spin = (raw.rotation as number | undefined) ?? 0;
				const before = faceCorners(dir, from, to);
				const after = faceCorners(dir, nf, nt);
				const corners = uvCorners(uv, spin);

				raw.uv = uvRect(after.map((p) => uvAt(before, corners, p)), spin);
			}

			el.from = nf;
			el.to = nt;
		}

		out.push(el);
	}

	return out;
}

interface Rotation {
	point: (p: Vec3) => Vec3;
	faces: Record<string, string>;
	/** How an element's own rotation axis and angle carry through. */
	axis: (axis: string, angle: number) => [string, number];
}

/** -90 degrees about X: a model authored flat against a wall stands upright. */
const UPRIGHT: Rotation = {
	point: (p) => [p[0], p[2], -p[1]],
	faces: { up: 'north', north: 'down', down: 'south', south: 'up', east: 'east', west: 'west' },
	axis: (axis, angle) => {
		if (axis === 'y') {
			return ['z', -angle];
		}
		if (axis === 'z') {
			return ['y', angle];
		}
		return [axis, angle];
	},
};

/** One quarter turn about Y, clockwise from above - vanilla's `"y": 90`. */
const QUARTER_CW: Rotation = {
	point: (p) => [16 - p[2], p[1], p[0]],
	faces: { north: 'east', east: 'south', south: 'west', west: 'north', up: 'up', down: 'down' },
	axis: (axis, angle) => {
		if (axis === 'x') {
			return ['z', angle];
		}
		if (axis === 'z') {
			return ['x', -angle];
		}
		return [axis, angle];
	},
};

/**
 * Applies a rotation to every element, re-deriving faces, UV rects and UV
 * spins by corner matching, which is what keeps the wood grain running the
 * right way through a bake.
 */
function bake(elements: Element[], rotation: Rotation): void {
	for (const el of elements) {
		// Blockbench lets a box run from its far corner to its near one, which
		// the client draws as the same box with mirrored faces; the corner
		// match below wants every box the right way round
		const from = el.from as Vec3;
		const to = el.to as Vec3;

		for (let axis = 0; axis < 3; axis++) {
			if (from[axis]! > to[axis]!) {
				const swap = from[axis]!;
				from[axis] = to[axis]!;
				to[axis] = swap;
			}
		}

		const a = rotation.point(from);
		const b = rotation.point(to);
		const nf: Vec3 = [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])];
		const nt: Vec3 = [Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])];
		const faces: Record<string, unknown> = {};

		for (const [dir, raw] of Object.entries(el.faces as Record<string, Record<string, unknown>>)) {
			const uv = (raw.uv as number[] | undefined) ?? autoUv(dir, from, to);
			const spin = ((raw.rotation as number | undefined) ?? 0) / 90;
			const rotated = faceCorners(dir, from, to).map(rotation.point);
			const target = rotation.faces[dir]!;
			const base = faceCorners(target, nf, nt);

			let shift = -1;
			for (let k = 0; k < 4 && shift < 0; k++) {
				let match = true;

				for (let j = 0; j < 4 && match; j++) {
					const p = base[j]!;
					const q = rotated[(j + k) % 4]!;
					match = Math.abs(p[0] - q[0]) < 1e-6 && Math.abs(p[1] - q[1]) < 1e-6 && Math.abs(p[2] - q[2]) < 1e-6;
				}

				if (match) {
					shift = k;
				}
			}

			if (shift < 0) {
				throw new Error(`corner match failed rotating face ${dir}`);
			}

			const out: Record<string, unknown> = { ...raw, uv };
			const spun = ((spin + shift) % 4) * 90;

			if (spun !== 0) {
				out.rotation = spun;
			} else {
				delete out.rotation;
			}

			delete out.cullface;
			faces[target] = out;
		}

		el.from = nf;
		el.to = nt;
		el.faces = faces;

		const rot = el.rotation as { origin: Vec3; axis: string; angle: number } | undefined;
		if (rot) {
			rot.origin = rotation.point(rot.origin);

			if (rot.angle !== 0) {
				const [axis, angle] = rotation.axis(rot.axis, rot.angle);
				rot.axis = axis;
				rot.angle = angle;
			}
		}
	}
}

interface Bounds {
	minX: number; maxX: number;
	minY: number; maxY: number;
	minZ: number; maxZ: number;
}

function boundsOf(elements: Element[]): Bounds {
	const b: Bounds = {
		minX: Infinity, maxX: -Infinity,
		minY: Infinity, maxY: -Infinity,
		minZ: Infinity, maxZ: -Infinity,
	};

	for (const el of elements) {
		const f = el.from as Vec3;
		const t = el.to as Vec3;
		b.minX = Math.min(b.minX, f[0]); b.maxX = Math.max(b.maxX, t[0]);
		b.minY = Math.min(b.minY, f[1]); b.maxY = Math.max(b.maxY, t[1]);
		b.minZ = Math.min(b.minZ, f[2]); b.maxZ = Math.max(b.maxZ, t[2]);
	}

	return b;
}

/**
 * Grounds and positions an upright-baked piece. A centred piece stands in the
 * middle of its block; a south-anchored one sits flush against the south edge,
 * the way a wall panel does.
 */
function settle(elements: Element[], anchor: 'center' | 'south'): void {
	const b = boundsOf(elements);
	const dx = 8 - (b.minX + b.maxX) / 2;
	const dy = -b.minY;
	const dz = anchor === 'south' ? 16 - b.maxZ : 8 - (b.minZ + b.maxZ) / 2;

	for (const el of elements) {
		for (const key of ['from', 'to'] as const) {
			const p = el[key] as Vec3;
			el[key] = [p[0] + dx, p[1] + dy, p[2] + dz];
		}

		const rot = el.rotation as { origin: Vec3 } | undefined;
		if (rot) {
			rot.origin = [rot.origin[0] + dx, rot.origin[1] + dy, rot.origin[2] + dz];
		}
	}
}

/** The transforms a piece falls back on when its source model carries none. */
const STD_DISPLAY = {
	gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
	ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
	fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
	thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
	thirdperson_lefthand: { rotation: [75, 315, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
	firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
	firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
};

/** Drops the flat planes drawn with one of these textures: a torch's flame. */
function dropFlames(model: Model, names: string[]): void {
	const flaming = new Set(
		Object.entries(model.textures!)
			.filter(([, value]) => names.some((name) => value.includes(name)))
			.map(([key]) => `#${key}`),
	);

	model.elements = (model.elements as Element[]).filter((el) => {
		const from = el.from as Vec3;
		const to = el.to as Vec3;
		// a flame plane is authored a thousandth of a pixel thick, not zero
		const flat = [0, 1, 2].some((axis) => to[axis] - from[axis] < 0.05);

		if (!flat) {
			return true;
		}

		return !Object.values(el.faces as Record<string, Record<string, unknown>>)
			.some((face) => flaming.has(face.texture as string));
	});
}

function buildModel(sources: string[], piece: Piece): Model {
	const textures: Record<string, string> = {};
	const byValue = new Map<string, string>();
	let elements: unknown[] = [];
	let display: Record<string, unknown> | undefined;

	sources.forEach((ref, index) => {
		const model = resolveChain(loadModel(ref));
		const keyMap: Record<string, string> = {};
		const sourceNs = splitRef(ref)[0];

		for (const [key, rawValue] of Object.entries(model.textures!)) {
			if (rawValue.startsWith('#')) {
				keyMap[key] = rawValue.slice(1);
				continue;
			}

			const value = adoptTexture(qualify(rawValue, sourceNs));
			let finalKey = byValue.get(value);

			if (!finalKey) {
				finalKey = textures[key] === undefined ? key : `m${index}_${key}`;
				textures[finalKey] = value;
				byValue.set(value, finalKey);
			}

			keyMap[key] = finalKey;
		}

		// slots that alias another slot resolve once every real slot is known
		for (const [key, target] of Object.entries(keyMap)) {
			if (keyMap[target] !== undefined && keyMap[target] !== key) {
				keyMap[key] = keyMap[target]!;
			}
		}

		const remapped = JSON.parse(JSON.stringify(model.elements), (key, value) => {
			if (key === 'texture' && typeof value === 'string' && value.startsWith('#')) {
				const mapped = keyMap[value.slice(1)];
				return mapped ? `#${mapped}` : value;
			}
			return value;
		});

		// A face may name a texture PATH instead of a variable. A path with no
		// namespace is the client's own (`block/oak_planks`) unless the source
		// pack has a file at it, which is how Builder's Bounty names its
		// `item/...` art - and reading that as vanilla's is a missing-texture
		// cube. Resolve against the source's namespace first, then adopt it
		// like any other texture, so what reaches the model is a variable.
		for (const element of remapped as Element[]) {
			for (const face of Object.values((element.faces ?? {}) as Record<string, Record<string, unknown>>)) {
				const path = face.texture;

				if (typeof path !== 'string' || path.startsWith('#')) {
					continue;
				}

				const value = adoptTexture(qualify(path, sourceNs));
				let finalKey = byValue.get(value);

				if (!finalKey) {
					finalKey = `p${index}_${basename(path)}`;
					textures[finalKey] = value;
					byValue.set(value, finalKey);
				}

				face.texture = `#${finalKey}`;
			}
		}

		elements = elements.concat(remapped);

		if (!display && model.display) {
			display = model.display;
		}
	});

	if (piece.bakeUpright) {
		bake(elements as Element[], UPRIGHT);
		settle(elements as Element[], piece.anchor ?? 'center');
		// the source's transforms describe a wall piece; they no longer apply
		display = undefined;
	}

	if (piece.rotateY) {
		for (let turn = 0; turn < piece.rotateY / 90; turn++) {
			bake(elements as Element[], QUARTER_CW);
		}
		// the display transforms were tuned for the unrotated model
		display = undefined;
	}

	if (piece.shift) {
		translate(elements as Element[], piece.shift);
	}

	if (piece.center) {
		const body = boundsOf(elements as Element[]);
		const shift = 8 - (body.minZ + body.maxZ) / 2;

		for (const el of elements as Element[]) {
			for (const key of ['from', 'to'] as const) {
				const point = el[key] as Vec3;
				el[key] = [point[0], point[1], point[2] + shift];
			}

			const rot = el.rotation as { origin: Vec3 } | undefined;
			if (rot) {
				rot.origin = [rot.origin[0], rot.origin[1], rot.origin[2] + shift];
			}
		}
	}

	if (piece.raise) {
		lift(elements as Element[], piece.raise);
	}

	if (piece.standModels) {
		for (const ref of piece.standModels) {
			const stand = resolveChain(loadModel(ref));

			for (const [key, value] of Object.entries(stand.textures!)) {
				if (!value.startsWith('#') && textures[key] === undefined) {
					textures[key] = adoptTexture(value);
				}
			}

			elements = elements.concat(JSON.parse(JSON.stringify(stand.elements)));
		}
	}

	if (!textures['particle']) {
		const first = Object.values(textures)[0];
		if (first) {
			textures['particle'] = first;
		}
	}

	return { textures, elements, display: display ?? STD_DISPLAY };
}

/** Moves every element, rotation origins included. */
function translate(elements: Element[], by: Vec3): void {
	for (const el of elements) {
		for (const key of ['from', 'to'] as const) {
			const p = el[key] as Vec3;
			el[key] = [p[0] + by[0], p[1] + by[1], p[2] + by[2]];
		}

		const rot = el.rotation as { origin: Vec3 } | undefined;
		if (rot) {
			rot.origin = [rot.origin[0] + by[0], rot.origin[1] + by[1], rot.origin[2] + by[2]];
		}
	}
}

/** Shifts every element up: a piece set on a stand. */
function lift(elements: Element[], pixels: number): void {
	for (const el of elements) {
		for (const key of ['from', 'to'] as const) {
			const p = el[key] as Vec3;
			el[key] = [p[0], p[1] + pixels, p[2]];
		}

		const rot = el.rotation as { origin: Vec3 } | undefined;
		if (rot) {
			rot.origin = [rot.origin[0], rot.origin[1] + pixels, rot.origin[2]];
		}
	}
}

// ---- head pots ----------------------------------------------------------

/**
 * Where each of a head's six faces is cut from the skin, in skin pixels.
 *
 * This is the net Minecraft unwraps a head with, and it is read here in the
 * same terms the console's own skin renderer reads it in, so the two agree on
 * which region is the face. Two of them need turning: the model format runs
 * the top face's texture from north to south and the bottom's from south to
 * north, and the skin's net runs both the other way.
 */
const HEAD_NET: Record<string, { u: number; v: number; rotation?: number }> = {
	up: { u: 8, v: 0, rotation: 180 },
	down: { u: 16, v: 0, rotation: 180 },
	east: { u: 0, v: 8 },
	north: { u: 8, v: 8 },
	west: { u: 16, v: 8 },
	south: { u: 24, v: 8 },
};

/**
 * How many model UV units one skin pixel is worth.
 *
 * The format spans any texture with the same 16 units whatever its resolution,
 * so this is not one number: a skin is always 64 across, but three of the pots
 * are drawn on the legacy 64x32 sheet, where a pixel is worth twice as much
 * down the image as it is across it. Reading it off the file is the only way
 * to be right about both, and the head's own net is in the same place either
 * way, so nothing else changes.
 */
function skinScale(file: string): Vec2 {
	const header = readFileSync(file).subarray(16, 24);

	return [16 / header.readUInt32BE(0), 16 / header.readUInt32BE(4)];
}

/**
 * One of a head's two cubes.
 *
 * @param from lower corner, in model pixels
 * @param to upper corner
 * @param shift how far along the skin this layer's net starts (32 for the hat)
 * @param floor whether to draw the underside
 * @param scale model UV units per skin pixel, across and down
 */
function headCube(from: Vec3, to: Vec3, shift: number, floor: boolean, scale: Vec2): Element {
	const faces: Record<string, unknown> = {};

	for (const [side, net] of Object.entries(HEAD_NET)) {
		if (side === 'down' && !floor) {
			continue;
		}

		const u = (net.u + shift) * scale[0];
		const v = net.v * scale[1];
		const face: Record<string, unknown> = {
			uv: [u, v, u + 8 * scale[0], v + 8 * scale[1]],
			texture: '#skin',
		};

		if (net.rotation) {
			face.rotation = net.rotation;
		}

		faces[side] = face;
	}

	return { from, to, faces };
}

/**
 * A pot: the head cube, and the hat layer as a second cube a quarter of a pixel
 * outside it, which is the inflation vanilla draws a head's overlay with. The
 * hat keeps its floor undrawn, since it would land on the base cube's own and
 * the two would fight over every pot standing on something you can see under.
 */
function headModel(piece: Piece): Model {
	const texture = `lunasmp:block/${piece.id}`;
	const scale = skinScale(join(ADDON_TEXTURES, `${piece.id}.png`));

	return {
		textures: { skin: texture, particle: texture },
		elements: [
			headCube([4, 0, 4], [12, 8, 12], 0, true, scale),
			headCube([3.75, 0, 3.75], [12.25, 8.25, 12.25], 32, false, scale),
		],
		display: STD_DISPLAY,
	};
}

/**
 * The ropes a hanging pot swings from: four straps rising from the rim,
 * leaning in to meet a knot under the ceiling. The geometry follows the
 * beautify hanging pot's, whose pot body is the same eight-pixel cube at the
 * bottom of the block, so the pot itself needs no moving: the straps are the
 * whole difference between standing and hanging.
 */
function ropeStraps(): Element[] {
	const strapUv: [number, number, number, number] = [0, 0, 2, 8];
	const knotUv: [number, number, number, number] = [4, 0, 8, 4];

	const strap = (
		from: Vec3,
		to: Vec3,
		rotation: { angle: number; axis: string; origin: Vec3 },
	): Element => ({
		from,
		to,
		rotation,
		faces: {
			north: { uv: strapUv, texture: '#rope' },
			south: { uv: strapUv, texture: '#rope' },
			west: { uv: strapUv, texture: '#rope' },
			east: { uv: strapUv, texture: '#rope' },
		},
	});

	return [
		strap([7.5, 7.25, 4], [8.5, 16.25, 5], { angle: 22.5, axis: 'x', origin: [8, 8, 4.5] }),
		strap([7.5, 7.25, 11], [8.5, 16.25, 12], { angle: -22.5, axis: 'x', origin: [8, 8, 11.5] }),
		strap([4, 7.25, 7.5], [5, 16.25, 8.5], { angle: -22.5, axis: 'z', origin: [4.5, 8, 8] }),
		strap([11, 7.25, 7.5], [12, 16.25, 8.5], { angle: 22.5, axis: 'z', origin: [11.5, 8, 8] }),
		{
			from: [7, 15, 7],
			to: [9, 16, 9],
			faces: {
				north: { uv: knotUv, texture: '#rope' },
				south: { uv: knotUv, texture: '#rope' },
				west: { uv: knotUv, texture: '#rope' },
				east: { uv: knotUv, texture: '#rope' },
				down: { uv: knotUv, texture: '#rope' },
			},
		},
	];
}

/**
 * Downloads the skins the pots are drawn from, once, into the staged sources.
 *
 * The datapack carries them as base64 profile properties on sixty stonecutting
 * recipes rather than as files, so there is nothing to copy out of it: the
 * texture id in the catalog is the whole reference, and Mojang's texture
 * server is where it resolves.
 */
async function stageHeads(): Promise<void> {
	const cache = join(sourcesDir, 'heads');
	mkdirSync(cache, { recursive: true });

	for (const piece of PIECES) {
		if (!piece.head) {
			continue;
		}

		const file = join(cache, `${piece.head}.png`);

		if (!existsSync(file)) {
			const response = await fetch(`https://textures.minecraft.net/texture/${piece.head}`);

			if (!response.ok) {
				throw new Error(`${piece.id}: skin ${piece.head} is ${response.status}`);
			}

			writeFileSync(file, new Uint8Array(await response.arrayBuffer()));
		}

		const name = `${piece.id}.png`;

		if (takenNames.has(name)) {
			throw new Error(`${piece.id}: texture name ${name} is already taken`);
		}

		mkdirSync(ADDON_TEXTURES, { recursive: true });
		copyFileSync(file, join(ADDON_TEXTURES, name));
		takenNames.add(name);
	}
}

// ---- connecting panels --------------------------------------------------

/** Sides in the order a state's model name spells them. */
const SIDES = ['n', 'e', 's', 'w'] as const;

/** Every arm set that is drawn: two arms or more, since a lone panel runs on. */
function armKeys(): string[] {
	const keys: string[] = [];

	for (let mask = 0; mask < 16; mask++) {
		const sides = SIDES.filter((_, i) => (mask & (1 << i)) !== 0);

		if (sides.length >= 2) {
			keys.push(sides.join(''));
		}
	}

	return keys;
}

// ---- connecting tables ----------------------------------------------------
//
// A run of tables reads as one table: legs survive only on corners no
// neighbour touches, and the skirts under the top vanish along a joined
// edge. Which element is a leg and which a skirt is read off the geometry
// itself, because the tables come from different source packs: a leg stands
// on the floor with a small footprint, a skirt hugs exactly one edge and
// hangs at or below the top board.
const LEG_SIDES = ['n', 'e', 's', 'w'] as const;

function legVariants(model: Model): Array<[string, Model]> {
	const elements = model.elements as Element[];
	const area = (el: Element) => (el.to[0] - el.from[0]) * (el.to[2] - el.from[2]);
	const top = elements.reduce((a, b) => (area(b) > area(a) ? b : a));
	const topFrom = top.from[1];

	const legOf = (el: Element): string | null => {
		if (el.from[1] > 0.01) {
			return null;
		}

		if (el.to[0] - el.from[0] > 4.5 || el.to[2] - el.from[2] > 4.5) {
			return null;
		}

		const cx = (el.from[0] + el.to[0]) / 2;
		const cz = (el.from[2] + el.to[2]) / 2;

		return (cz < 8 ? 'n' : 's') + (cx < 8 ? 'w' : 'e');
	};

	const skirtOf = (el: Element): string | null => {
		if (legOf(el) !== null || el.to[1] > topFrom + 0.6) {
			return null;
		}

		const sides: string[] = [];

		if (el.to[2] <= 2.5) sides.push('n');
		if (el.from[0] >= 13.5) sides.push('e');
		if (el.from[2] >= 13.5) sides.push('s');
		if (el.to[0] <= 2.5) sides.push('w');

		return sides.length === 1 ? sides[0]! : null;
	};

	const out: Array<[string, Model]> = [];

	for (let mask = 1; mask < 16; mask++) {
		const joined = new Set(LEG_SIDES.filter((_, i) => (mask & (1 << i)) !== 0));

		const kept = elements.filter((el) => {
			const leg = legOf(el);

			if (leg !== null) {
				return !joined.has(leg[0]!) && !joined.has(leg[1]!);
			}

			const skirt = skirtOf(el);

			return skirt === null || !joined.has(skirt);
		});

		out.push([`_c${mask}`, { ...model, elements: kept }]);
	}

	return out;
}

// ---- railed walkways ------------------------------------------------------
//
// A catwalk joins up like a table, but what it loses along a joined edge is
// its railing. The source ships the bare plate and the plate with one railing
// along its north edge; the difference is the railing, and the other three
// are that one turned. The plain model (no neighbours) wears all four, and
// each `_c<mask>` drops the railings on the joined sides, in the same mask
// order the table variants and the SIDES properties use.

function railVariants(plate: Model, railed: Model): Array<[string, Model]> {
	const plateKeys = new Set((plate.elements as Element[]).map((el) => JSON.stringify([el.from, el.to])));
	const north = (railed.elements as Element[]).filter((el) => !plateKeys.has(JSON.stringify([el.from, el.to])));

	if (north.length === 0) {
		throw new Error('railings model adds nothing to the plate');
	}

	const turned = (times: number): Element[] => {
		const copy = JSON.parse(JSON.stringify(north)) as Element[];

		for (let turn = 0; turn < times; turn++) {
			bake(copy, QUARTER_CW);
		}

		return copy;
	};

	const rails: Record<string, Element[]> = {
		n: north,
		e: turned(1),
		s: turned(2),
		w: turned(3),
	};

	// the railed model owns the railing's textures, so every variant is
	// written against its texture map rather than the bare plate's
	const textures = { ...plate.textures, ...railed.textures };
	const out: Array<[string, Model]> = [];

	for (let mask = 0; mask < 16; mask++) {
		const joined = new Set(LEG_SIDES.filter((_, i) => (mask & (1 << i)) !== 0));
		const elements = JSON.parse(JSON.stringify(plate.elements)) as Element[];

		for (const side of LEG_SIDES) {
			if (!joined.has(side)) {
				elements.push(...JSON.parse(JSON.stringify(rails[side])) as Element[]);
			}
		}

		out.push([mask === 0 ? '' : `_c${mask}`, { ...railed, textures, elements }]);
	}

	return out;
}

// ---- surfaces -----------------------------------------------------------

/**
 * Where items set down on a piece sit: spread across the top of the model, in
 * block units, for a piece facing north.
 */
function surfaceSlots(elements: Element[], count: number): Vec3[] {
	const b = boundsOf(elements);
	const top = b.maxY / 16;
	// cell centres rather than interior fractions: three slots land at 1/6,
	// 1/2 and 5/6 of the board instead of crowding the middle half, which is
	// the spacing a shelf's items need to read as separate things
	const spread = (n: number, lo: number, hi: number): number[] =>
		Array.from({ length: n }, (_, i) => (lo + ((hi - lo) * (i + 0.5)) / n) / 16);

	if (count === 4) {
		const xs = spread(2, b.minX, b.maxX);
		const zs = spread(2, b.minZ, b.maxZ);
		return xs.flatMap((x) => zs.map((z) => [x, top, z] as Vec3));
	}

	const z = (b.minZ + b.maxZ) / 2 / 16;
	return spread(count, b.minX, b.maxX).map((x) => [x, top, z] as Vec3);
}

// ---- emit ---------------------------------------------------------------

rmSync(ADDON_MODELS, { recursive: true, force: true });
rmSync(ADDON_TEXTURES, { recursive: true, force: true });
rmSync(ADDON_CONFIGS, { recursive: true, force: true });
mkdirSync(ADDON_MODELS, { recursive: true });
mkdirSync(ADDON_CONFIGS, { recursive: true });
mkdirSync(ADDON_LANG, { recursive: true });

mkdirSync(ADDON_TEXTURES, { recursive: true });

let spriteCount = 0;

// everything painted here claims its name before a source pack gets the
// chance to adopt one of its own textures under it
const painted: Record<string, Uint8Array> = { ...signSprites(), ...gaugeSprites() };

for (const [name, paint] of Object.entries(INDUSTRIAL_SPRITES)) {
	painted[name] = paint();
}

for (const [name, bytes] of Object.entries(frameSprites(sourcesDir))) {
	painted[name] = bytes;
}

for (const [name, bytes] of Object.entries(painted)) {
	writeFileSync(join(ADDON_TEXTURES, `${name}.png`), bytes);
	takenNames.add(name);
	spriteCount++;
}

// the pots are drawn from skins rather than from a staged pack, and claim
// their texture names before the source packs get to adopt any of their own
await stageHeads();

const en: Record<string, string> = {};
const vi: Record<string, string> = {};
const failures: string[] = [];
const specs: string[] = [];
let modelCount = 0;

/**
 * Fills in a piece's own texture variables and refuses a model that still
 * names one nothing defines.
 *
 * Every model a piece writes goes through this, not only its default one: an
 * open state, a hanging state and an unlit state are built separately, and a
 * variable the source leaves to its own loader is just as missing in those.
 */
function finish(model: Model, piece: Piece): void {
	if (piece.textures) {
		const filled: Record<string, string> = {};

		for (const [key, value] of Object.entries(piece.textures)) {
			filled[key] = adoptTexture(value);
		}

		model.textures = { ...model.textures, ...filled };
	}

	const defined = new Set(Object.keys(model.textures ?? {}));
	const dangling = new Set<string>();
	const paths = new Set<string>();

	for (const element of (model.elements ?? []) as Element[]) {
		for (const face of Object.values((element.faces ?? {}) as Record<string, { texture?: string; uv?: number[] }>)) {
			const ref = face.texture ?? '';

			if (!ref.startsWith('#')) {
				paths.add(ref);
			} else if (!defined.has(ref.slice(1))) {
				dangling.add(ref);
			}

			// a window reaching past the sprite samples whatever the atlas
			// packed next to it, which shows up as a stripe of another block
			if (face.uv) {
				face.uv = fitWindow(face.uv);
			}
		}
	}

	if (dangling.size > 0) {
		throw new Error(`undefined texture variables: ${[...dangling].join(', ')}`);
	}

	if (paths.size > 0) {
		throw new Error(`faces naming a texture path rather than a variable: ${[...paths].join(', ')}`);
	}
}

/**
 * Slides a uv window back inside the sprite, keeping its size where it can.
 *
 * A source model may name a window past the sprite's own edge (skniro's oven
 * and refrigerators do), which the client answers with the neighbouring
 * texture on the atlas rather than with nothing.
 */
function fitWindow(uv: number[]): number[] {
	const out = [...uv];

	for (const axis of [0, 1]) {
		const lo = Math.min(out[axis]!, out[axis + 2]!);
		const hi = Math.max(out[axis]!, out[axis + 2]!);
		let shift = 0;

		if (hi > 16) {
			shift = 16 - hi;
		}

		if (lo + shift < 0) {
			shift = -lo;
		}

		out[axis] = Math.max(0, Math.min(16, out[axis]! + shift));
		out[axis + 2] = Math.max(0, Math.min(16, out[axis + 2]! + shift));
	}

	return out;
}

/** Rewrites a composed model's bundled texture references into our namespace. */
function adoptAll(model: Model): Model {
	const copy = JSON.parse(JSON.stringify(model)) as Model;

	for (const [key, value] of Object.entries(copy.textures!)) {
		copy.textures![key] = adoptTexture(value);
	}

	return copy;
}

function writeModel(name: string, model: Model): void {
	writeFileSync(join(ADDON_MODELS, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
	modelCount++;
}

for (const piece of PIECES) {
	let slots: Vec3[] = [];

	try {
		let model: Model;

		if (piece.built) {
			model = JSON.parse(JSON.stringify(piece.built)) as Model;

			for (const [key, value] of Object.entries(model.textures ?? {})) {
				model.textures![key] = adoptTexture(value);
			}
		} else {
			model = piece.head ? headModel(piece) : buildModel(piece.models!, piece);
		}

		finish(model, piece);

		if (!piece.head && (model.elements as unknown[] | undefined)?.length === 0) {
			// a source model with no geometry is drawn by that mod's own code
			// (`builtin/entity`), and porting it would ship an invisible block
			throw new Error('source model has no elements: nothing to draw');
		}

		if (piece.connects) {
			// the panel is not mirror-symmetric through its thickness (logs on
			// one face, flowers on the other), so arms are never built by
			// turning another arm around: each model clips the whole east-west
			// panel and the whole panel turned once, and a straight run is the
			// original model untouched. The flower fans are rotated panel-wide
			// sheets that cannot be clipped, so they are added whole, oriented
			// along the arms the model has.
			const all = model.elements as Element[];
			const flat = all.filter(isClippable);
			const fans = all.filter((el) => !isClippable(el));

			// centre the panel body itself (settle centred on the whole bounds,
			// fans included, which drags the slats off-centre): the slats must
			// sit inside the pane hitbox's own 7..9 slice of the block
			const body = boundsOf(flat);
			const centre = 8 - (body.minZ + body.maxZ) / 2;

			for (const el of all) {
				for (const key of ['from', 'to'] as const) {
					const point = el[key] as Vec3;
					el[key] = [point[0], point[1], point[2] + centre];
				}

				const rot = el.rotation as { origin: Vec3 } | undefined;
				if (rot) {
					rot.origin = [rot.origin[0], rot.origin[1], rot.origin[2] + centre];
				}
			}

			const across = JSON.parse(JSON.stringify(flat)) as Element[];
			bake(across, QUARTER_CW);
			const fansAcross = JSON.parse(JSON.stringify(fans)) as Element[];
			bake(fansAcross, QUARTER_CW);

			// the flipped variants turn each straight panel half way round, so
			// a flipped state shows its flowered face on the other side while
			// the arms it clips stay exactly where the state says they are
			const half = (source: Element[]): Element[] => {
				const turned = JSON.parse(JSON.stringify(source)) as Element[];

				bake(turned, QUARTER_CW);
				bake(turned, QUARTER_CW);

				return turned;
			};

			const flat180 = half(flat);
			const fans180 = half(fans);
			const across180 = half(across);
			const fansAcross180 = half(fansAcross);

			const assemble = (key: string, flipped: boolean): Element[] => {
				const body = flipped ? flat180 : flat;
				const bodyAcross = flipped ? across180 : across;
				const fan = flipped ? fans180 : fans;
				const fanAcross = flipped ? fansAcross180 : fansAcross;
				const elements: Element[] = [];
				const e = key.includes('e');
				const w = key.includes('w');
				const n = key.includes('n');
				const so = key.includes('s');

				if (e || w) {
					elements.push(...clip(body, 0, w ? 0 : 7, e ? 16 : 9));
					elements.push(...JSON.parse(JSON.stringify(fan)) as Element[]);
				}

				if (n || so) {
					elements.push(...clip(bodyAcross, 2, n ? 0 : 7, so ? 16 : 9));
					elements.push(...JSON.parse(JSON.stringify(fanAcross)) as Element[]);
				}

				return elements;
			};

			// the plain model is a straight run, which is what the item shows
			writeModel(piece.id, { ...model, elements: assemble('ew', false) });

			for (const key of armKeys()) {
				writeModel(`${piece.id}_${key}`, { ...model, elements: assemble(key, false) });
				writeModel(`${piece.id}_${key}_f`, { ...model, elements: assemble(key, true) });
			}
		} else if (piece.railings) {
			const railed = buildModel([piece.railings], piece);

			finish(railed, piece);

			for (const [suffix, variant] of railVariants(model, railed)) {
				writeModel(`${piece.id}${suffix}`, variant);
			}
		} else {
			writeModel(piece.id, model);

			if (piece.legs) {
				for (const [suffix, pruned] of legVariants(model)) {
					writeModel(`${piece.id}${suffix}`, pruned);
				}
			}
		}

		if (piece.surface) {
			slots = surfaceSlots(model.elements as Element[], piece.surface);
		}

		if (piece.hangingModels) {
			const hanging = buildModel(piece.hangingModels, piece);

			finish(hanging, piece);
			writeModel(`hanging_${piece.id}`, hanging);
		} else if (piece.hangable && piece.head) {
			// the hanging pot is the standing pot with rope straps grown up
			// to the ceiling; the cube itself stays at the block's bottom, so
			// the hitbox and the plant slots hold for both states
			const hanging = JSON.parse(JSON.stringify(model)) as Model;

			hanging.textures = { ...hanging.textures, rope: 'lunasmp:block/pot_rope' };
			(hanging.elements as Element[]).push(...ropeStraps());
			writeModel(`hanging_${piece.id}`, hanging);
		}

		if (piece.topModel) {
			const topHalf = JSON.parse(JSON.stringify(piece.topModel)) as Model;

			finish(topHalf, piece);
			writeModel(`${piece.id}_top`, topHalf);
		} else if (piece.topLift !== undefined) {
			// the merged closed leaf, carried up whole the way a vanilla
			// trapdoor's top half is; a railed walkway lifts its bare plate
			const topHalf = JSON.parse(JSON.stringify(model)) as Model;

			lift(topHalf.elements as Element[], piece.topLift);
			writeModel(`${piece.id}_top`, topHalf);
		}

		let opened: Model | undefined;

		if (piece.openable) {
			opened = piece.openable.built
				? JSON.parse(JSON.stringify(piece.openable.built)) as Model
				: buildModel(piece.openable.models!, piece);

			if (piece.openable.rotateY) {
				for (let turn = 0; turn < piece.openable.rotateY / 90; turn++) {
					bake(opened.elements as Element[], QUARTER_CW);
				}
			}

			finish(opened, piece);
			writeModel(`${piece.id}_open`, opened);
		}

		// the second leaf of a double doorway is the mirrored one, so the
		// pair reads as one double door; the backing hinge flips with it.
		// Every door leaf gets the pair, composed or merged: the state
		// machine asks for both models and a missing one is a boot crash
		if (piece.collider === 'door' && opened) {
			const mirrored = mirrorX(JSON.parse(JSON.stringify(model)) as Model);

			finish(mirrored, piece);
			writeModel(`${piece.id}_mirror`, mirrored);

			const openedMirror = mirrorX(JSON.parse(JSON.stringify(opened)) as Model);

			finish(openedMirror, piece);
			writeModel(`${piece.id}_mirror_open`, openedMirror);
		}

		if (piece.lamp) {
			// a composed piece has no source models to fall back on, so it
			// carries its own unlit geometry
			const off = piece.lamp.offBuilt
				? adoptAll(JSON.parse(JSON.stringify(piece.lamp.offBuilt)) as Model)
				: buildModel(piece.lamp.offModels ?? piece.models!, piece);

			if (piece.lamp.offDrop) {
				dropFlames(off, piece.lamp.offDrop);
			}

			finish(off, piece);
			writeModel(`${piece.id}_off`, off);
		}
	} catch (error) {
		failures.push(`${piece.id}: ${(error as Error).message}`);
		continue;
	}

	const tool = piece.tool ?? 'axe';
	writeFileSync(
		join(ADDON_CONFIGS, `${piece.id}.yml`),
		`tool_categories: [${tool}]\ntool_tier: wood\nrequires_tool_for_drops: false\n`,
	);

	en[`block.lunasmp.${piece.id}`] = piece.en;
	vi[`block.lunasmp.${piece.id}`] = piece.vi;

	const fields = [`id = "${piece.id}"`];

	if (piece.seatHeight !== undefined) {
		fields.push(`seatHeight = ${piece.seatHeight.toFixed(2)}`);
	}

	if (piece.directional) {
		fields.push('directional = true');
	}

	if (piece.hangingModels || (piece.hangable && piece.head)) {
		fields.push('hanging = true');
	}

	if (piece.openable) {
		fields.push(`openable = Openable(sound = OpenSound.${piece.openable.sound.toUpperCase()})`);
	}

	if (piece.aquarium) {
		fields.push('aquarium = true');
	}

	if (piece.upper) {
		fields.push('upper = true');
	}

	if (piece.openPartner) {
		fields.push(`openPartner = "${piece.openPartner}"`);
	}

	if (piece.legs || piece.railings) {
		fields.push('legs = true');
	}

	if (piece.connects) {
		fields.push(`connects = "${piece.connects}"`);
	}

	if (piece.joins) {
		fields.push(`joins = "${piece.joins}"`);
	}

	if (piece.cube) {
		fields.push('cube = true');
	}

	if (piece.collider !== undefined && piece.collider !== 'solid') {
		fields.push(`box = Box.${piece.collider.toUpperCase()}`);
	}

	if (piece.faceAway) {
		fields.push('faceAway = true');
	}

	if (piece.clock) {
		fields.push('clock = true');
	}

	if (piece.light) {
		fields.push(`light = Light(level = ${piece.light.level}, offset = ${piece.light.offset})`);
	}

	if (piece.lamp) {
		const lamp = piece.lamp;
		fields.push(
			`lamp = Lamp(level = ${lamp.level}, offset = ${lamp.offset}, `
			+ `flint = ${lamp.ignite === 'flint'}, litByDefault = ${lamp.litByDefault === true})`,
		);
	}

	if (slots.length > 0) {
		const written = slots.map(([x, y, z]) => `Slot(${x.toFixed(3)}, ${y.toFixed(3)}, ${z.toFixed(3)})`);
		fields.push(`surface = listOf(${written.join(', ')})`);
	}

	if (piece.surfaceUpright) {
		fields.push('surfaceUpright = true');
	}

	if (piece.storage) {
		fields.push(`storageRows = ${piece.storage}`);
	}

	if (piece.showcase) {
		fields.push('showcase = true');
	}

	if (piece.pot) {
		fields.push('pot = true');
	}

	if (piece.plantSlots) {
		const written = piece.plantSlots.map(([x, y, z]) => `Slot(${x.toFixed(4)}, ${y.toFixed(4)}, ${z.toFixed(4)})`);
		fields.push(`plantSlots = listOf(${written.join(', ')})`);
	}

	fields.push(`hardness = ${(piece.hardness ?? 1.0).toFixed(1)}`);
	fields.push(`sounds = SoundGroup.${tool === 'axe' ? 'WOOD' : 'STONE'}`);

	specs.push(`\t\tSpec(${fields.join(', ')}),`);
}

// ---- flora ---------------------------------------------------------------

// the flowering blocks: composed geometry over vanilla textures for everything
// taken from Floral Enchantment, whose own art we may not ship, and Many
// Flowers' own sprites for everything taken from there, which we may

const floraSpecs: string[] = [];

// the tap water: an animation strip, so it needs the mcmeta beside it that
// tells the client how fast to play it
writeFileSync(join(ADDON_TEXTURES, 'tap_water.png'), waterSheet());
writeFileSync(join(ADDON_TEXTURES, 'pot_rope.png'), ropeSprite());
writeFileSync(
	join(ADDON_TEXTURES, 'tap_water.png.mcmeta'),
	JSON.stringify({ animation: { frametime: 2 } }, null, '\t') + '\n',
);
takenNames.add('tap_water');
spriteCount++;

// every painted texture lands before its model is written, so the name a
// model references is already on disk when the pack builder goes looking
for (const flower of FLOWERS) {
	const bushName = `${flower.id}_bush`;

	writeFileSync(join(ADDON_TEXTURES, `${bushName}.png`), bushSprite(bushName, flower.accents));
	takenNames.add(bushName);
	spriteCount++;

	for (const stone of ['mossy_cobblestone', 'mossy_stone_bricks'] as const) {
		const overlayName = `${flower.id}_${stone}`;

		writeFileSync(join(ADDON_TEXTURES, `${overlayName}.png`), mossOverlay(flower.id, stone, flower.accents));
		takenNames.add(overlayName);
		spriteCount++;
	}

	const vineName = `${flower.id}_vine`;

	writeFileSync(join(ADDON_TEXTURES, `${vineName}.png`), vineSprite(flower.id, flower.accents));
	takenNames.add(vineName);
	spriteCount++;
}

for (const entry of FLORA as Flora[]) {
	for (const [name, model] of Object.entries(entry.models)) {
		writeModel(name, adoptAll(model as Model));
	}

	writeFileSync(
		join(ADDON_CONFIGS, `${entry.id}.yml`),
		`tool_categories: [${entry.tool}]\ntool_tier: wood\nrequires_tool_for_drops: false\n`,
	);

	en[`block.lunasmp.${entry.id}`] = entry.en;
	vi[`block.lunasmp.${entry.id}`] = entry.vi;

	const fields = [`id = "${entry.id}"`, `backing = Backing.${entry.backing.toUpperCase()}`];

	if (entry.icon) {
		fields.push(`icon = "${adoptTexture(entry.icon)}"`);
	}

	if (entry.nightLight) {
		fields.push(`nightLight = ${entry.nightLight}`);
	}

	if (entry.directional) {
		fields.push('directional = true');
	}

	if (entry.effect) {
		fields.push(`effect = Effect.${entry.effect}`);
	}

	fields.push(`hardness = ${entry.hardness.toFixed(1)}`);
	fields.push(`sounds = SoundGroup.${entry.sounds}`);

	floraSpecs.push(`\t\tSpec(${fields.join(', ')}),`);
}

// ---- signs ---------------------------------------------------------------

// road, safety and electrical signs, plus the boards somebody writes on. Each
// is one flat plane wearing a painted face, in two mountings: hung on a wall,
// or on its own post for a roadside.

const signSpecs: string[] = [];

for (const sign of SIGNS) {
	for (const [name, model] of Object.entries(signModels(sign))) {
		writeModel(name, adoptAll(model as Model));
	}

	writeFileSync(
		join(ADDON_CONFIGS, `${sign.id}.yml`),
		`tool_categories: [${sign.tool}]\ntool_tier: wood\nrequires_tool_for_drops: false\n`,
	);

	en[`block.lunasmp.${sign.id}`] = sign.en;
	vi[`block.lunasmp.${sign.id}`] = sign.vi;

	const fields = [`id = "${sign.id}"`];

	if (sign.post) {
		fields.push('post = true');
	}

	if (sign.text) {
		const area = sign.text;

		fields.push(
			`text = Text(width = ${area.width}, height = ${area.height}, across = ${area.across}, `
			+ `colour = 0x${area.colour.toString(16).padStart(6, '0')}, shadow = ${area.shadow}, `
			+ `merges = ${area.merges})`,
		);
	}

	fields.push(`hardness = ${sign.hardness.toFixed(1)}`);
	fields.push(`sounds = SoundGroup.${sign.tool === 'axe' ? 'WOOD' : 'STONE'}`);

	signSpecs.push(`\t\tSpec(${fields.join(', ')}),`);
}

/** Mirrors a built model across x, for the second leaf of a double door. */
function mirrorX(model: Model): Model {
	for (const element of (model.elements ?? []) as {
		from: number[]; to: number[];
		faces: Record<string, { uv?: number[] } | undefined>;
	}[]) {
		const from = 16 - element.to[0]!;
		const to = 16 - element.from[0]!;

		element.from[0] = from;
		element.to[0] = to;

		// a merged source leaf may carry rotated parts (hinge straps, braces):
		// the pivot mirrors with the box, and a spin about y or z reverses
		const rotation = (element as { rotation?: { origin: number[]; axis: string; angle: number } }).rotation;

		if (rotation) {
			rotation.origin[0] = 16 - rotation.origin[0]!;

			if (rotation.axis !== 'x') {
				rotation.angle = -rotation.angle;
			}
		}

		// swap by deleting, not by assigning: a merged element may lack a
		// face, and an undefined entry left in the map breaks every later
		// pass that walks the faces
		const east = element.faces.east;
		const west = element.faces.west;

		delete element.faces.east;
		delete element.faces.west;

		if (west) {
			element.faces.east = west;
		}

		if (east) {
			element.faces.west = east;
		}

		for (const dir of ['north', 'south', 'up', 'down']) {
			const uv = element.faces[dir]?.uv;

			if (uv) {
				element.faces[dir]!.uv = [uv[2]!, uv[1]!, uv[0]!, uv[3]!];
			}
		}
	}

	return model;
}

// ---- gauges and the network switch ----------------------------------------

// the instruments: painted faces, live needles, and the isolator. Ten gauge
// kinds in panel and free-standing form, eight of them also as a full
// dashboard block, plus the switch in its on and off states.

const gaugeSpecs: string[] = [];

for (const [name, model] of Object.entries(gaugeModels())) {
	writeModel(name, adoptAll(model as Model));
}

for (const gauge of GAUGES) {
	writeFileSync(
		join(ADDON_CONFIGS, `${gauge.id}.yml`),
		'tool_categories: [pickaxe]\ntool_tier: wood\nrequires_tool_for_drops: false\n',
	);

	en[`block.lunasmp.${gauge.id}`] = gauge.en;
	vi[`block.lunasmp.${gauge.id}`] = gauge.vi;

	const dial = gauge.dial;
	const fields = [
		`id = "${gauge.id}"`,
		`metric = Metric.${gauge.metric.toUpperCase()}`,
		`pivotX = ${dial.pivot[0]}`,
		`pivotY = ${dial.pivot[1]}`,
		`startDeg = ${dial.startDeg.toFixed(1)}`,
		`endDeg = ${dial.endDeg.toFixed(1)}`,
		`needleLen = ${Math.max(0, dial.radius - 1)}`,
	];

	if (gauge.needle) {
		fields.push(`needle = "gauge_needle_${gauge.needle}"`);
	}

	if (gauge.window) {
		fields.push(`windowX = ${gauge.window[0]}, windowY = ${gauge.window[1]}`);
	}

	if (gauge.style) {
		fields.push(`style = Style.${gauge.style.toUpperCase()}`);
	}

	gaugeSpecs.push(`\t\tSpec(${fields.join(', ')}),`);

	if (gauge.dashboard) {
		writeFileSync(
			join(ADDON_CONFIGS, `${gauge.id}_block.yml`),
			'tool_categories: [pickaxe]\ntool_tier: wood\nrequires_tool_for_drops: false\n',
		);

		en[`block.lunasmp.${gauge.id}_block`] = `${gauge.en} Block`;
		vi[`block.lunasmp.${gauge.id}_block`] = `Khối ${gauge.vi}`;

		const blockFields = fields.map((field) =>
			field === `id = "${gauge.id}"` ? `id = "${gauge.id}_block"` : field);

		blockFields.push('dashboard = true');
		gaugeSpecs.push(`\t\tSpec(${blockFields.join(', ')}),`);
	}
}

const switchSpecs: string[] = [];

for (const sw of SWITCHES) {
	writeFileSync(
		join(ADDON_CONFIGS, `${sw.id}.yml`),
		'tool_categories: [pickaxe]\ntool_tier: wood\nrequires_tool_for_drops: false\n',
	);

	en[`block.lunasmp.${sw.id}`] = sw.en;
	vi[`block.lunasmp.${sw.id}`] = sw.vi;

	const fields = [
		`id = "${sw.id}"`,
		`toggles = Toggles.${sw.toggles.toUpperCase()}`,
		`momentaryTicks = ${(sw.momentary ?? 0) * 20}`,
		`redstone = ${sw.redstone ?? false}`,
		`sound = "${sw.sound}"`,
		`defaultOn = ${sw.defaultOn}`,
	];

	switchSpecs.push(`\t\tSwitchSpec(${fields.join(', ')}),`);
}

// the one-way bridge: a network device like the switches, but an endpoint
// with a will of its own rather than a bridge with a handle
writeFileSync(
	join(ADDON_CONFIGS, `${DIODE.id}.yml`),
	'tool_categories: [pickaxe]\ntool_tier: wood\nrequires_tool_for_drops: false\n',
);
en[`block.lunasmp.${DIODE.id}`] = DIODE.en;
vi[`block.lunasmp.${DIODE.id}`] = DIODE.vi;

// the indicator, the alarm and the light panel
for (const dev of DEVICES) {
	writeFileSync(
		join(ADDON_CONFIGS, `${dev.id}.yml`),
		'tool_categories: [pickaxe]\ntool_tier: wood\nrequires_tool_for_drops: false\n',
	);

	en[`block.lunasmp.${dev.id}`] = dev.en;
	vi[`block.lunasmp.${dev.id}`] = dev.vi;
}

// the needles ride as hidden items: an item display can only wear a model
// that some item owns
mkdirSync(ADDON_ITEMS, { recursive: true });

for (const [name, model] of Object.entries(needleModels())) {
	writeFileSync(join(ADDON_ITEMS, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
	en[`item.lunasmp.${name}`] = 'Gauge Needle';
	vi[`item.lunasmp.${name}`] = 'Kim Đồng Hồ';
}

// the LED bars of the level columns ride as hidden items too
for (const [name, model] of Object.entries(barModels())) {
	writeFileSync(join(ADDON_ITEMS, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
	en[`item.lunasmp.${name}`] = 'Gauge Bar';
	vi[`item.lunasmp.${name}`] = 'Thanh Đèn Đồng Hồ';
}

// the indicator's LED chips and the alarm's sweeping beam
for (const [name, model] of Object.entries(ledChipModels())) {
	writeFileSync(join(ADDON_ITEMS, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
	en[`item.lunasmp.${name}`] = 'Indicator LED';
	vi[`item.lunasmp.${name}`] = 'Đèn LED Báo Hiệu';
}

writeFileSync(join(ADDON_ITEMS, 'alarm_beam.json'), JSON.stringify(alarmBeamModel(), null, '\t') + '\n');
en['item.lunasmp.alarm_beam'] = 'Alarm Beam';
vi['item.lunasmp.alarm_beam'] = 'Tia Đèn Báo Động';

// the wall clock's live hands ride as hidden items the same way
for (const [name, model] of Object.entries(clockHandModels())) {
	writeFileSync(join(ADDON_ITEMS, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
	en[`item.lunasmp.${name}`] = 'Clock Hand';
	vi[`item.lunasmp.${name}`] = 'Kim Đồng Hồ Treo';
}

// the wire couplings ride as hidden items, one prebaked per direction -
// runtime rotation of a single model is retired (the vertical pitch sign
// cannot be falsified on a horizontal wire, and it shipped wrong twice)
for (const [name, model] of Object.entries(directionalJointModels())) {
	writeFileSync(join(ADDON_ITEMS, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
	en[`item.lunasmp.${name}`] = 'Gauge Coupling';
	vi[`item.lunasmp.${name}`] = 'Khớp Nối Đồng Hồ';
}

// and the isolator's dead-line sleeves, for the off state
for (const [name, model] of Object.entries(directionalJointLongModels())) {
	writeFileSync(join(ADDON_ITEMS, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
	en[`item.lunasmp.${name}`] = 'Isolator Sleeve';
	vi[`item.lunasmp.${name}`] = 'Ống Nối Cầu Dao';
}

// ---- flower crowns -------------------------------------------------------

mkdirSync(ADDON_ITEMS, { recursive: true });

for (const crown of CROWNS) {
	writeFileSync(join(ADDON_ITEMS, `${crown.id}.json`), JSON.stringify(crown.model, null, '\t') + '\n');
	en[`item.lunasmp.${crown.id}`] = crown.en;
	vi[`item.lunasmp.${crown.id}`] = crown.vi;
}

// the wreath is an item, not a block, and keeps its hand-written entry
en['item.lunasmp.vong_hoa'] = 'Flower Wreath';
vi['item.lunasmp.vong_hoa'] = 'Vòng Hoa';

writeFileSync(join(ADDON_LANG, 'en_us.json'), JSON.stringify(en, null, '\t') + '\n');
writeFileSync(join(ADDON_LANG, 'vi_vn.json'), JSON.stringify(vi, null, '\t') + '\n');

const kotlin = `package dev.belikhun.luna.smp.furniture

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoorHingeSide
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Half
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BackingStateCategory
import xyz.xenondevs.nova.resources.builder.layout.block.BlockSelectorScope
import xyz.xenondevs.nova.resources.builder.layout.block.BlockModelSelectorScope
import xyz.xenondevs.nova.resources.builder.model.ModelBuilder
import xyz.xenondevs.nova.world.BlockPos
import xyz.xenondevs.nova.world.block.AbstractNovaBlockBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.DefaultScopedBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import xyz.xenondevs.nova.world.format.WorldDataManager
import kotlin.math.abs

/**
 * The generated furniture table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/catalog.ts
 * and re-run the generator; the models, configs and language files beside this
 * table come from the same pass, so editing one of them alone drifts the set.
 *
 * Every piece is entity-backed: furniture is exactly the case of a model that
 * is neither a cube nor block-shaped. The vanilla block behind the model is
 * what the game collides with and what the outline is drawn around: a barrier
 * (full solid cube) for anything you stand or sit on, a structure void (a
 * 6x6x6 box in the middle, no collider) for small decor and hanging pieces,
 * and for the panels a glass pane, whose post-and-arms shape follows the
 * panel's own connections. Borrowed blocks are skinned invisible by the hitbox
 * base pack (resource_pack/base_packs/luna-hitboxes), which is what reserves
 * them for this job server-wide. None of them may be an occluding block: an
 * occluder culls the face of whatever it sits against, and an invisible
 * occluder turns that culled face into a hole in the ground.
 */
@Suppress("unused")
object FurnitureCatalog {

	/** Whether a piece that lights up is currently burning. */
	val LIT = BooleanProperty(Key.key("lunasmp", "lit"))

	/**
	 * Whether a hanging-capable piece hangs. Decided once, at placement, by
	 * which face the player clicked: the underside of a ceiling hangs the
	 * piece, anything else stands it on the floor.
	 */
	val HANGING = BooleanProperty(Key.key("lunasmp", "hanging"))

	/** One per side: whether a connecting panel reaches out that way. */
	/**
	 * Whether a panel's flowered face is turned back at whoever placed it.
	 * A panel is not mirror-symmetric through its thickness, so without this
	 * a straight run could only ever face south or west.
	 */
	val FLIPPED = BooleanProperty(Key.key("lunasmp", "flipped"))

	/** Whether a piece that opens is open. */
	val OPEN = BooleanProperty(Key.key("lunasmp", "open"))

	/** The flipped leaf of a double door, hinge and handle mirrored. */
	val MIRROR = BooleanProperty(Key.key("lunasmp", "mirror"))
	val TOP = BooleanProperty(Key.key("lunasmp", "top"))

	val NORTH = BooleanProperty(Key.key("lunasmp", "north"))
	val EAST = BooleanProperty(Key.key("lunasmp", "east"))
	val SOUTH = BooleanProperty(Key.key("lunasmp", "south"))
	val WEST = BooleanProperty(Key.key("lunasmp", "west"))

	/** The side properties, in the order a model's name spells them. */
	/** A standing player's eye above their feet; sneaking is close enough. */
	private const val EYE_HEIGHT = 1.62

	val SIDES: Map<BlockFace, BooleanProperty> = linkedMapOf(
		BlockFace.NORTH to NORTH,
		BlockFace.EAST to EAST,
		BlockFace.SOUTH to SOUTH,
		BlockFace.WEST to WEST,
	)

	/** Where an item set down on a piece sits, in block units, facing north. */
	data class Slot(val x: Double, val y: Double, val z: Double)

	/** A piece that is simply always lit. */
	/**
	 * What a piece that opens sounds like, both ways.
	 *
	 * The sounds are held as their vanilla names, not as Sound constants:
	 * this table is built while the addon is still loading, and touching the
	 * sound registry that early loads it before the server means to, which
	 * takes the whole boot down with "registry is already loaded".
	 */
	enum class OpenSound(val open: String, val close: String) {
		DOOR("block.iron_door.open", "block.iron_door.close"),
		WOOD("block.wooden_door.open", "block.wooden_door.close"),
		SHUTTER("block.bamboo_wood_trapdoor.open", "block.bamboo_wood_trapdoor.close"),
		TAP("item.bucket.fill", "block.lever.click"),
	}

	data class Openable(val sound: OpenSound = OpenSound.WOOD)

	data class Light(
		val level: Int,
		val offset: Int,
	)

	/** What a piece that toggles does when it is lit. */
	data class Lamp(
		val level: Int,
		val offset: Int,
		val flint: Boolean,
		val litByDefault: Boolean,
	)

	/** The vanilla shape behind a piece; every value past SMALL is a borrowed
	 * visible block, skinned invisible by the hitbox base pack. */
	enum class Box {
		/** A barrier: the full cube. */
		SOLID,
		/** A structure void: a 6x6x6 box in the middle, no collider. */
		SMALL,
		/** A glass pane: post and arms, following a panel's connections. */
		PANE,
		/** A door's own slab, which swings with the state; two of these stack. */
		DOOR,
		/** A trapdoor stood on end: three pixels flush against one wall. */
		FLAT,
		/** A real trapdoor's swing: flat on the floor, standing when open. */
		TRAPDOOR,
		/** A closed iron trapdoor: a three-pixel plate on the floor or the
		 * ceiling that no hand click moves; the walkways stand on it. */
		PLATE,
		/** A closed trapdoor at the top of the block: three pixels of ceiling. */
		CEILING,
		/** A heavy core: the 8x8x8 block in the middle, under seats and boards. */
		PEDESTAL,
		/** An empty flower pot: the little box a planter stands as. */
		POT,
		/** A bare fence post: the four-pixel full-height column of a pole. */
		POST,
	}

	data class Spec(
		val id: String,
		val seatHeight: Double? = null,
		val directional: Boolean = false,
		/** Faces away from the placer instead of back at them: a railing goes
		 * on the edge you are looking past, not the one you stand at. */
		val faceAway: Boolean = false,

		/** A wall clock: the tile spins two live hands over the painted face. */
		val clock: Boolean = false,
		/** A real cube, drawn from a reserved note block state. */
		val cube: Boolean = false,
		val hanging: Boolean = false,
		/** Swings, rolls or folds open on a click; null when it does not. */
		val openable: Openable? = null,
		/** Fish swim about inside it. */
		val aquarium: Boolean = false,
		/** The upper half of a two-block piece, for the shape it borrows. */
		val upper: Boolean = false,
		/** A piece beside this one that opens along with it. */
		val openPartner: String? = null,
		val connects: String? = null,
		/** A member of a connecting family for its neighbours' sake only: a
		 * gate the fence on either side reaches out to. */
		val joins: String? = null,
		/** Joins neighbouring copies of itself: shared legs, joined skirts. */
		val legs: Boolean = false,
		val box: Box = Box.SOLID,
		val light: Light? = null,
		val lamp: Lamp? = null,
		val surface: List<Slot> = emptyList(),
		val surfaceUpright: Boolean = false,
		val storageRows: Int = 0,
		val showcase: Boolean = false,
		val pot: Boolean = false,
		val plantSlots: List<Slot> = listOf(Slot(0.5, 0.4375, 0.5)),
		val hardness: Double = 1.0,
		val sounds: SoundGroup = SoundGroup.WOOD,
	)

	private val SPECS = listOf(
${specs.join('\n')}
	)

	/** Every piece's spec, by block id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/**
	 * What each connecting piece joins up with, by block id. A panel only
	 * reaches out to its own family: every trellis meets every other trellis,
	 * whatever it flowers, but a lattice is a different screen at a different
	 * depth and stands on its own.
	 */
	val CONNECTING: Map<String, String> = SPECS
		.filter { it.connects != null || it.joins != null }
		.associate { it.id to (it.connects ?: it.joins!!) }

	/** Every generated block, by its id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	/** The wearable flower crowns, by item id; each has a model and nothing else. */
	val CROWNS: List<String> = listOf(
${CROWNS.map((crown) => `		"${crown.id}",`).join('\n')}
	)

	private fun register(spec: Spec): NovaBlock {
		val stateful = spec.lamp != null || spec.surface.isNotEmpty()
			|| spec.storageRows > 0 || spec.showcase || spec.pot || spec.openable != null
			|| spec.aquarium || spec.clock

		if (!stateful) {
			return LunaSmp.block(spec.id) {
				configure(spec)
				behaviors(*(behaviorsOf(spec) + BlockDrops).toTypedArray())
			}
		}

		val constructor = when {
			spec.lamp != null -> ::FurnitureLamp
			spec.storageRows > 0 -> ::FurnitureStorage
			spec.pot -> ::FurniturePot
			spec.openable != null -> ::FurnitureOpen
			spec.aquarium -> ::FurnitureAquarium
			spec.clock -> ::FurnitureClock
			else -> ::FurnitureDisplay
		}

		return LunaSmp.tileEntity(spec.id, constructor) {
			configure(spec)
			// only the tank has anything to do per tick: the lamp reacts to a
			// click, the containers to their own inventories
			tickrate(if (spec.aquarium || spec.clock || spec.openable?.sound == OpenSound.TAP) 1 else 0)
			// the auto-built upper half of a door drops nothing: its item
			// comes back from the lower half, which breaks with it
			val drops = if (spec.box == Box.DOOR && spec.upper) {
				emptyList()
			} else {
				listOf(TileEntityDrops)
			}

			behaviors(*(behaviorsOf(spec) + drops + TileEntityInteractive).toTypedArray())
		}
	}

	private fun behaviorsOf(spec: Spec) = buildList {
		if (spec.seatHeight != null) {
			add(Seating(spec.seatHeight))
		}

		if (spec.connects != null) {
			add(PanelConnect)
		}

		if (spec.legs) {
			add(TableConnect)
		}

		if (spec.light != null) {
			add(EmitsLight(spec.light.offset, spec.light.level))
		}

		// the pot behind a standing planter is a real flower pot: a click with
		// a pottable plant must be swallowed, or vanilla pots the plant into
		// the hitbox block itself
		if (spec.box == Box.POT) {
			add(PotGuard)
		}

		add(Breakable(hardness = spec.hardness))
		add(BlockSounds(spec.sounds))
	}

	private fun AbstractNovaBlockBuilder<*>.configure(spec: Spec) {
		// a real cube needs no display entity and no borrowed hitbox: it is a
		// reserved note block state, so it lights, occludes and culls exactly
		// like the block it looks like
		if (spec.cube) {
			stateBacked(BackingStateCategory.NOTE_BLOCK) { defaultModel }
			return
		}

		if (spec.connects != null) {
			stateProperties(*SIDES.values.map { scopedSide(spec, it) }.toTypedArray(), scopedFlipped(spec))
		}

		if (spec.hanging) {
			stateProperties(scopedHanging())
		}

		if (spec.openable != null) {
			stateProperties(OPEN.scope(setOf(false, true)) { false })
		}

		if (spec.box == Box.TRAPDOOR || spec.box == Box.PLATE) {
			stateProperties(TOP.scope(setOf(false, true)) { ctx -> topOnPlace(ctx) })
		}

		// real doors only: the steel railing borrows the door COLLIDER for
		// its hitbox but has no leaf to mirror - giving it the property once
		// killed a boot on a steel_railing_mirror model that never existed
		if (spec.box == Box.DOOR && spec.openable != null) {
			stateProperties(MIRROR.scope(setOf(false, true)) { ctx -> doorMirror(spec, ctx) })
		}

		if (spec.legs) {
			stateProperties(*SIDES.map { (face, property) -> scopedLegSide(spec, face, property) }.toTypedArray())
		}

		if (spec.directional) {
			when {
				spec.box == Box.DOOR && spec.openable != null -> stateProperties(DefaultBlockStateProperties.FACING.scope(
					setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST),
				) { ctx -> doorFacing(spec, ctx) })

				spec.faceAway -> stateProperties(DefaultBlockStateProperties.FACING.scope(
					setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST),
				) { ctx -> awayFacing(ctx) })

				else -> stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL)
			}
		}

		if (spec.lamp != null) {
			stateProperties(scopedLit(spec))
		}

		entityBacked(stateSelector = { hitboxOf(spec, this) }) {
			var model = when {
				spec.connects != null -> getModel("lunasmp:block/\${spec.id}_\${PanelConnect.armKey(this)}\${flipKey()}")
				spec.box == Box.DOOR && spec.openable != null -> doorModel(spec, this)
				spec.openable != null && getPropertyValueOrNull(OPEN) == true -> getModel("lunasmp:block/\${spec.id}_open")
				(spec.box == Box.TRAPDOOR || spec.box == Box.PLATE) && getPropertyValueOrNull(TOP) == true -> getModel("lunasmp:block/\${spec.id}_top")
				spec.legs && legMask(this) != 0 -> getModel("lunasmp:block/\${spec.id}_c\${legMask(this)}")
				spec.hanging && getPropertyValueOrNull(HANGING) == true -> getModel("lunasmp:block/hanging_\${spec.id}")
				spec.lamp != null && getPropertyValueOrNull(LIT) == false -> getModel("lunasmp:block/\${spec.id}_off")
				else -> defaultModel
			}

			if (spec.directional) {
				model = model.rotated()
			}

			model
		}
	}

	/**
	 * The vanilla block state whose shape backs this piece. A pane-backed
	 * panel gets the pane state matching the arms it draws, so the collision
	 * and the outline follow the panel through corners and crossings.
	 */
	private fun hitboxOf(spec: Spec, scope: BlockSelectorScope): BlockState {
		// an open door is walked through: the shape goes with the state, not
		// with the piece, or it would still stand in its own doorway. Only a
		// door, though - a running tap is not a hole in the sink, and the
		// pane box is what says a piece is a door
		if (spec.openable != null && spec.box == Box.PANE && scope.getPropertyValueOrNull(OPEN) == true) {
			return Blocks.STRUCTURE_VOID.defaultBlockState()
		}

		return shapeOf(spec, scope)
	}

	private fun shapeOf(spec: Spec, scope: BlockSelectorScope): BlockState = when (spec.box) {
		Box.SOLID -> Blocks.BARRIER.defaultBlockState()

		Box.SMALL -> Blocks.STRUCTURE_VOID.defaultBlockState()

		Box.PANE ->
			if (spec.connects != null) {
				paneState(PanelConnect.drawnArms(scope))
			} else {
				paneState(acrossArms(scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING)))
			}

		Box.DOOR -> doorState(spec, scope)

		// the one shape that still borrows a real warped trapdoor, because a
		// hatch WANTS the trapdoor's behaviour: the client's open prediction
		// matches the toggle the server makes, so nothing flickers
		Box.TRAPDOOR -> Blocks.WARPED_TRAPDOOR.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, facingOf(scope))
			.setValue(BlockStateProperties.OPEN, spec.openable != null && scope.getPropertyValueOrNull(OPEN) == true)
			.setValue(
				BlockStateProperties.HALF,
				if (scope.getPropertyValueOrNull(TOP) == true) Half.TOP else Half.BOTTOM,
			)

		// the same plate, but a closed IRON trapdoor: nothing a hand click
		// moves, so the client predicts no swing on a walkway
		Box.PLATE -> Hitboxes.plate(scope.getPropertyValueOrNull(TOP) == true)

		// a resin clump against the wall: one pixel of outline exactly where
		// the panel hangs, and a block the client predicts nothing for - the
		// warped trapdoor it replaced swung open client-side on every click
		Box.FLAT -> Hitboxes.clumpAgainst(
			scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH,
		)

		// the same clump on the ceiling, for the flush fittings up there
		Box.CEILING -> Hitboxes.clumpOnCeiling()

		Box.PEDESTAL -> Blocks.HEAVY_CORE.defaultBlockState()

		Box.POST -> Hitboxes.post()

		// a hanging planter is a rope-hung pot mid-block: nothing pot-shaped
		// hangs, so that state falls back to the small box
		Box.POT ->
			if (scope.getPropertyValueOrNull(HANGING) == true) {
				Blocks.STRUCTURE_VOID.defaultBlockState()
			} else {
				Blocks.FLOWER_POT.defaultBlockState()
			}
	}

	/**
	 * The door slab this half of a door stands in.
	 *
	 * A vanilla door faces the way you came at it, so the state takes the
	 * piece's own facing turned around; open swings it a quarter turn, which
	 * is the whole reason a door borrows a door rather than a pane.
	 */
	private fun doorState(spec: Spec, scope: BlockSelectorScope): BlockState {
		val open = spec.openable != null && scope.getPropertyValueOrNull(OPEN) == true

		// an IRON door, tenth sacrifice: a hand-openable backing makes every
		// client toggle it predictively, and that local change cascades shape
		// updates into the neighbours - a note-block-canvas Nova block above
		// or below (clinker, hazard...) recomputes its instrument and renders
		// as a bare noteblock until the server resyncs. Iron doors ignore
		// hand clicks client-side, so nothing is predicted and nothing
		// flickers; our own toggle stays a plain server-side state swap.
		return Blocks.IRON_DOOR.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, facingOf(scope).opposite)
			.setValue(BlockStateProperties.OPEN, open)
			// POWERED marks the state as OURS: the luna-hitboxes skin hides
			// only powered iron doors, so real (unpowered) iron doors stay
			// visible in the world - the first all-states sacrifice made
			// every player-built iron door transparent. Powered is inert
			// here: Nova neuters vanilla block logic at Nova positions.
			.setValue(BlockStateProperties.POWERED, true)
			// the mirrored leaf swings the other way, outline and all
			.setValue(
				BlockStateProperties.DOOR_HINGE,
				if (scope.getPropertyValueOrNull(MIRROR) == true) DoorHingeSide.RIGHT else DoorHingeSide.LEFT,
			)
			.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, if (spec.upper) DoubleBlockHalf.UPPER else DoubleBlockHalf.LOWER)
	}

	/**
	 * Vanilla's trapdoor half, recovered without the click point this nova
	 * version hands out: top when a ceiling was clicked, bottom for a floor,
	 * and for a wall the placer's own look ray is run onto the clicked plane
	 * - the same point the client aimed at - and the half is whichever side
	 * of the middle it lands on.
	 */
	/** The door leaf's model: base or mirrored, closed or swung open. */
	private fun doorModel(spec: Spec, scope: BlockModelSelectorScope): ModelBuilder {
		val open = scope.getPropertyValueOrNull(OPEN) == true
		val mirror = scope.getPropertyValueOrNull(MIRROR) == true
		val suffix = (if (mirror) "_mirror" else "") + (if (open) "_open" else "")

		if (suffix.isEmpty()) {
			return scope.defaultModel
		}

		return scope.getModel("lunasmp:block/\${spec.id}\$suffix")
	}

	/**
	 * Whether a freshly placed door leaf is the mirrored one. A neighbouring
	 * half of the same door decides first: the second leaf of a pair takes
	 * the opposite of its neighbour, so the two read as one double door.
	 * Otherwise the WALL decides, the way vanilla picks a hinge: the leaf
	 * anchors its hinge against the solid side of the doorway, so a single
	 * door in an opening swings off the jamb rather than into it.
	 */
	private fun doorMirror(spec: Spec, ctx: Context<BlockPlace>): Boolean {
		val pos = ctx[DefaultContextParamTypes.BLOCK_POS] ?: return false

		// the wall decides first (the user's own priority): the leaf seats
		// its hinge against the solid side of the doorway, so a single door
		// swings off the jamb. The unmirrored leaf hinges on the facing's
		// CLOCKWISE side - the viewer's left, verified in game 2026-09-03
		// after the first guess shipped the other chirality. Occluding = a
		// real jamb, so glass, slabs and our own doors do not count.
		val facing = ctx[DefaultContextParamTypes.SOURCE_DIRECTION]
			?.let { direction ->
				if (abs(direction.x) > abs(direction.z)) {
					if (direction.x > 0) BlockFace.WEST else BlockFace.EAST
				} else {
					if (direction.z > 0) BlockFace.NORTH else BlockFace.SOUTH
				}
			}
			?: BlockFace.NORTH

		val hingeWall = pos.advance(clockwise(facing), 1).block.type.isOccluding
		val mirrorWall = pos.advance(counterclockwise(facing), 1).block.type.isOccluding

		if (hingeWall != mirrorWall) {
			return mirrorWall
		}

		// no deciding wall: a neighbouring half of the same door - the second
		// leaf takes the opposite, so the pair reads as one double door
		val family = spec.id.removeSuffix("_top")

		for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
			val state = WorldDataManager.getBlockState(pos.advance(face, 1)) ?: continue

			if (state.block.id.value().removeSuffix("_top") != family) {
				continue
			}

			return state[MIRROR] != true
		}

		return false
	}

	private fun clockwise(face: BlockFace): BlockFace = when (face) {
		BlockFace.NORTH -> BlockFace.EAST
		BlockFace.EAST -> BlockFace.SOUTH
		BlockFace.SOUTH -> BlockFace.WEST
		else -> BlockFace.NORTH
	}

	private fun counterclockwise(face: BlockFace): BlockFace = when (face) {
		BlockFace.NORTH -> BlockFace.WEST
		BlockFace.WEST -> BlockFace.SOUTH
		BlockFace.SOUTH -> BlockFace.EAST
		else -> BlockFace.NORTH
	}

	/**
	 * Which way a door half faces when placed: the way a neighbouring half of
	 * the same door already faces, so a double doorway lines up whatever
	 * angle each leaf was placed from - two adjacent doors placed from
	 * slightly different angles used to land on opposite facings, one leaf
	 * two pixels behind the other. With no neighbour, back at the placer.
	 */
	private fun doorFacing(spec: Spec, ctx: Context<BlockPlace>): BlockFace {
		val pos = ctx[DefaultContextParamTypes.BLOCK_POS]

		if (pos != null) {
			val family = spec.id.removeSuffix("_top")

			for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
				val state = WorldDataManager.getBlockState(pos.advance(face, 1)) ?: continue

				if (state.block.id.value().removeSuffix("_top") != family) {
					continue
				}

				val neighbour = state[DefaultBlockStateProperties.FACING] ?: continue

				return neighbour
			}
		}

		return awayFacing(ctx).oppositeFace
	}

	/** The horizontal face the placer is looking along, not the one looking back. */
	private fun awayFacing(ctx: Context<BlockPlace>): BlockFace {
		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return BlockFace.NORTH

		return if (abs(direction.x) > abs(direction.z)) {
			if (direction.x > 0) BlockFace.EAST else BlockFace.WEST
		} else {
			if (direction.z > 0) BlockFace.SOUTH else BlockFace.NORTH
		}
	}

	private fun topOnPlace(ctx: Context<BlockPlace>): Boolean {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: return false

		if (clicked == BlockFace.UP) {
			return false
		}

		if (clicked == BlockFace.DOWN) {
			return true
		}

		val pos = ctx[DefaultContextParamTypes.BLOCK_POS] ?: return false
		val source = ctx[DefaultContextParamTypes.SOURCE_LOCATION] ?: return false
		val look = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return false

		// the clicked face belongs to the supporting block, so its plane is
		// this block's own boundary on the side facing that support
		val plane = when (clicked) {
			BlockFace.NORTH -> pos.z + 1.0
			BlockFace.SOUTH -> pos.z.toDouble()
			BlockFace.EAST -> pos.x.toDouble()
			else -> pos.x + 1.0
		}

		val origin = when (clicked) {
			BlockFace.NORTH, BlockFace.SOUTH -> source.z
			else -> source.x
		}

		val delta = when (clicked) {
			BlockFace.NORTH, BlockFace.SOUTH -> look.z
			else -> look.x
		}

		if (abs(delta) < 1e-6) {
			return false
		}

		val t = (plane - origin) / delta
		val hitY = source.y + EYE_HEIGHT + look.y * t

		return hitY - pos.y > 0.5
	}

	/** A table side connects when the block there is the same table. */
	private fun scopedLegSide(spec: Spec, face: BlockFace, property: BooleanProperty) =
		property.scope(setOf(false, true)) { ctx ->
			val pos = ctx[DefaultContextParamTypes.BLOCK_POS]

			pos != null && sameBlock(pos.add(face.modX, 0, face.modZ), spec.id)
		}

	/** Whether the nova block at [pos] is the piece named [id]. */
	fun sameBlock(pos: BlockPos, id: String): Boolean =
		WorldDataManager.getBlockState(pos)?.block?.id?.value() == id

	/** Which sides of a connecting table have a matching neighbour, as bits. */
	private fun legMask(scope: BlockSelectorScope): Int {
		var mask = 0

		SIDES.values.forEachIndexed { index, property ->
			if (scope.getPropertyValueOrNull(property) == true) {
				mask = mask or (1 shl index)
			}
		}

		return mask
	}

	/** The way a piece faces, as the game names directions. */
	private fun facingOf(scope: BlockSelectorScope): Direction = when (scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING)) {
		BlockFace.NORTH -> Direction.NORTH
		BlockFace.EAST -> Direction.EAST
		BlockFace.WEST -> Direction.WEST
		else -> Direction.SOUTH
	}

	private fun paneState(arms: Set<BlockFace>): BlockState {
		var state = Blocks.MAGENTA_STAINED_GLASS_PANE.defaultBlockState()
		state = state.setValue(BlockStateProperties.NORTH, BlockFace.NORTH in arms)
		state = state.setValue(BlockStateProperties.EAST, BlockFace.EAST in arms)
		state = state.setValue(BlockStateProperties.SOUTH, BlockFace.SOUTH in arms)
		state = state.setValue(BlockStateProperties.WEST, BlockFace.WEST in arms)

		return state
	}

	/** A facing panel stands across its facing: a north-facing wall runs east-west. */
	private fun acrossArms(facing: BlockFace?): Set<BlockFace> = when (facing) {
		BlockFace.EAST, BlockFace.WEST -> setOf(BlockFace.NORTH, BlockFace.SOUTH)
		else -> setOf(BlockFace.EAST, BlockFace.WEST)
	}

	private fun scopedLit(spec: Spec) =
		LIT.scope(setOf(false, true)) { spec.lamp?.litByDefault == true }

	private fun scopedHanging() =
		HANGING.scope(setOf(false, true)) { ctx -> ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] == BlockFace.DOWN }

	private fun BlockSelectorScope.flipKey(): String =
		if (getPropertyValueOrNull(FLIPPED) == true) "_f" else ""

	private fun scopedFlipped(spec: Spec) =
		FLIPPED.scope(setOf(false, true)) { ctx -> PanelConnect.flippedOnPlace(ctx, spec.connects!!) }

	private fun scopedSide(spec: Spec, property: BooleanProperty) =
		property.scope(setOf(false, true)) { ctx -> property in PanelConnect.armsOnPlace(ctx, spec.connects!!) }
}
`;

writeFileSync(join(dirname(addonDir), 'kotlin/dev/belikhun/luna/smp/furniture/FurnitureCatalog.kt'), kotlin);

const floraKotlin = `package dev.belikhun.luna.smp.flora

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BackingStateCategory
import xyz.xenondevs.nova.world.block.AbstractNovaBlockBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockBehaviorHolder
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.DefaultScopedBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import kotlin.math.abs

/**
 * The generated flora table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/flora.ts and
 * re-run the generator; the models, configs and language files beside this table
 * come from the same pass, so editing one of them alone drifts the set.
 *
 * Two backings, chosen by what the block actually is. A flowering stone block is
 * a full cube, so it is a reserved note block state: a wall of them lights,
 * occludes and culls exactly like the stone it is made of, and costs no display
 * entities at all, which matters for something meant to be built with in bulk.
 * Everything else is a plant, and gets the entity backing over a structure void:
 * nothing to walk into, a small box in the middle to aim at, and geometry that
 * is free to leave the block, which is what lets a two-block flower be one block.
 */
@Suppress("unused")
object FloraCatalog {

	/** Whether a flower that watches the sky is lit. */
	val GLOWING = BooleanProperty(Key.key("lunasmp", "glowing"))

	/** The walls a vine can hang on. */
	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/** What the block is, which decides how the client is told to draw it. */
	enum class Backing { CUBE, PLANT, WALL }

	/**
	 * What a flower does to whoever is near it.
	 *
	 * A \`ticking\` effect reaches past its own block and has to go looking, so its
	 * flower is registered as a tile entity; the rest are answered from the
	 * collision that triggered them and cost nothing until somebody steps in.
	 * \`harmful\` is what makes a flower hold its fire on peaceful.
	 */
	enum class Effect(val ticking: Boolean, val harmful: Boolean = false) {
		/** Sets whoever steps on it alight, unless they are at home in fire. */
		BURN(false, true),
		/** Goes off underfoot. */
		BLAST(false, true),
		/** Poisons whoever wades into it. */
		POISON(false, true),
		/** Simply hurts. */
		RUE(false, true),
		/** Heals whoever stands in it. */
		MEND(false),
		/** Hardens whoever stands in it. */
		GUARD(false),
		/** Turns luck for whoever stands in it. */
		FORTUNE(false),
		/** Strengthens the people around it while something is hunting them. */
		RALLY(true),
		/** Takes the fight out of everything around it. */
		LULL(true),
		/** Hostiles walk back out of its ring. */
		REPEL(true),
		/** Hostiles drop what they were chasing and come to it. */
		LURE(true),
		/** Gives off a little light of its own, which is only ever particles. */
		GLIMMER(true),
		/** Makes the noises of things that are not there. */
		DREAD(true),
	}

	data class Spec(
		val id: String,
		val backing: Backing,
		/** The flat sprite the item shows in a menu, on the ground and framed. */
		val icon: String? = null,
		val nightLight: Int = 0,
		val effect: Effect? = null,
		/** Turns to face whoever places it, the way a carved pumpkin does. */
		val directional: Boolean = false,
		val hardness: Double = 0.0,
		val sounds: SoundGroup = SoundGroup.GRASS,
	)

	private val SPECS = listOf(
${floraSpecs.join('\n')}
	)

	/** Every flora block's spec, by block id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/** Every flora block, by its id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	/** Whether a spec has anything to do that a collision cannot answer. */
	private fun ticks(spec: Spec): Boolean =
		spec.nightLight > 0 || spec.effect?.ticking == true

	private fun register(spec: Spec): NovaBlock {
		if (!ticks(spec)) {
			return LunaSmp.block(spec.id) {
				configure(spec)
				behaviors(*(behaviorsOf(spec) + BlockDrops).toTypedArray())
			}
		}

		return LunaSmp.tileEntity(spec.id, ::FloraTile) {
			configure(spec)
			// once a second covers both jobs: an aura only has to be refreshed
			// before it lapses, and dusk is not a moment anybody can time
			tickrate(1)
			behaviors(*(behaviorsOf(spec) + TileEntityDrops).toTypedArray())
		}
	}

	private fun behaviorsOf(spec: Spec): List<BlockBehaviorHolder> = buildList {
		if (spec.effect != null && !spec.effect.ticking) {
			add(StepEffect(spec.effect))
		}

		add(Breakable(hardness = spec.hardness))
		add(BlockSounds(spec.sounds))
	}

	private fun AbstractNovaBlockBuilder<*>.configure(spec: Spec) {
		if (spec.nightLight > 0) {
			stateProperties(GLOWING.scope(setOf(false, true)) { false })
		}

		if (spec.backing == Backing.WALL) {
			stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> wallOf(ctx) })
		}

		if (spec.directional) {
			stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL)
		}

		if (spec.backing == Backing.CUBE) {
			stateBacked(BackingStateCategory.NOTE_BLOCK) { defaultModel }
			return
		}

		// a wall plant's outline is the wall's: the flat clump against the
		// face it hangs on, exactly like a vanilla vine, instead of a small
		// box floating in the middle of the block
		entityBacked(stateSelector = {
			if (spec.backing == Backing.WALL) {
				val facing = getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH

				Hitboxes.clumpAgainst(facing.oppositeFace)
			} else {
				Blocks.STRUCTURE_VOID.defaultBlockState()
			}
		}) {
			var model = when {
				spec.nightLight > 0 && getPropertyValueOrNull(GLOWING) != true -> getModel("lunasmp:block/\${spec.id}_off")
				else -> defaultModel
			}

			if (spec.backing == Backing.WALL || spec.directional) {
				model = model.rotated()
			}

			model
		}
	}

	/**
	 * The wall a vine hangs on: the one it was stuck to, not the one the placer
	 * happened to be facing. Stuck to a floor or a ceiling there is no wall to
	 * read, and it falls back to standing across the placer's view.
	 */
	private fun wallOf(ctx: Context<BlockPlace>): BlockFace {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]

		if (clicked != null && clicked in WALLS) {
			return clicked.oppositeFace
		}

		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return BlockFace.NORTH

		if (abs(direction.x) > abs(direction.z)) {
			return if (direction.x > 0) BlockFace.WEST else BlockFace.EAST
		}

		return if (direction.z > 0) BlockFace.NORTH else BlockFace.SOUTH
	}
}
`;

const signKotlin = `package dev.belikhun.luna.smp.signs

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BlockSelectorScope
import xyz.xenondevs.nova.world.block.AbstractNovaBlockBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockDrops
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import kotlin.math.abs

/**
 * The generated sign table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/signs.ts and
 * re-run the generator; the models, configs and language files beside this table
 * come from the same pass, so editing one of them alone drifts the set.
 *
 * Every sign is one flat, two-sided plane wearing a 32x32 painted face, which is
 * what lets a triangular sign actually be triangular: the corners of the texture
 * are transparent and there is no geometry behind them.
 *
 * A sign is placed one of two ways, decided by what was clicked. Against a wall
 * it hangs flat and looks away from that wall, and its shape is a trapdoor held
 * open - three pixels against the wall. On the ground or a ceiling it stands on
 * its own post instead, and its shape is a glass pane: a two-pixel column
 * through the whole block with the panel's width across it. Both vanilla blocks
 * are already skinned invisible by the hitbox base pack.
 *
 * Signs that carry words carry a text display rather than a texture; see
 * [SignTile].
 */
@Suppress("unused")
object SignCatalog {

	/** Whether the sign stands on its own post rather than hanging on a wall. */
	val POSTED = BooleanProperty(Key.key("lunasmp", "posted"))

	/** The walls a sign can hang on. */
	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	/**
	 * Where a sign's face sits, in blocks from the north edge of its own block,
	 * with the model authored facing north. The text display is parked a hair in
	 * front of this.
	 */
	const val WALL_FACE = ${(WALL_FACE_Z / 16).toFixed(4)}

	/** The same, for a sign on a post: the panel stands nearer the middle. */
	const val POST_FACE = ${(POST_FACE_Z / 16).toFixed(4)}

	/** How a sign is written on, when it is written on at all. */
	data class Text(
		/** How wide one block of board is, in blocks, inside its border. */
		val width: Double,
		/** How tall one block of board is. */
		val height: Double,
		/** Characters that fit across one block of board. */
		val across: Int,
		/** The colour text takes when it names no colour of its own. */
		val colour: Int,
		val shadow: Boolean,
		/**
		 * Boards of this kind that touch, in the same plane and facing the same
		 * way, merge into one: a solid rectangle of blackboards is a single
		 * board with a single text display spanning it.
		 */
		val merges: Boolean,
	)

	data class Spec(
		val id: String,
		/** Present when the sign carries words rather than a pictogram. */
		val text: Text? = null,
		/** The bare pole a sign stands on: no face, no facing, no mounting. */
		val post: Boolean = false,
		val hardness: Double = 1.0,
		val sounds: SoundGroup = SoundGroup.STONE,
	)

	private val SPECS = listOf(
${signSpecs.join('\n')}
	)

	/** Every sign's spec, by block id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/** Every sign block, by its id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	private fun register(spec: Spec): NovaBlock {
		if (spec.post) {
			return LunaSmp.block(spec.id) {
				// a glass pane with nothing to connect to is the two-pixel
				// column through the middle of the block - the same shape a
				// single iron bar has, because a stained glass pane *is* an
				// IronBarsBlock with the same node width. So the outline sits
				// on the pole, and a stack of poles collides as one post.
				entityBacked(stateSelector = { Blocks.MAGENTA_STAINED_GLASS_PANE.defaultBlockState() }) {
					defaultModel
				}

				behaviors(Breakable(hardness = spec.hardness), BlockSounds(spec.sounds), BlockDrops)
			}
		}

		// a pictogram says everything it has to say in its texture, so it needs
		// no tile entity, no display entity and nothing stored
		if (spec.text == null) {
			return LunaSmp.block(spec.id) {
				configure(spec)
				behaviors(Breakable(hardness = spec.hardness), BlockSounds(spec.sounds), BlockDrops)
			}
		}

		return LunaSmp.tileEntity(spec.id, ::SignTile) {
			configure(spec)
			// nothing to do per tick: a board changes when somebody writes on
			// it or when a neighbour appears, and both of those are events
			tickrate(0)
			behaviors(
				Breakable(hardness = spec.hardness),
				BlockSounds(spec.sounds),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}
	}

	private fun AbstractNovaBlockBuilder<*>.configure(spec: Spec) {
		stateProperties(
			DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) },
			POSTED.scope(setOf(false, true)) { ctx -> postedOnPlace(ctx) },
		)

		entityBacked(stateSelector = { hitboxOf(this) }) {
			val model = if (getPropertyValueOrNull(POSTED) == true) {
				getModel("lunasmp:block/\${spec.id}_post")
			} else {
				defaultModel
			}

			model.rotated()
		}
	}

	/**
	 * Which way a sign looks when it is placed.
	 *
	 * Clicking a wall hangs it on that wall, so it looks along the face that was
	 * clicked - away from the block it is stuck to. Anywhere else it is on a
	 * post, and turns to face whoever put it there.
	 */
	private fun facingOnPlace(ctx: Context<BlockPlace>): BlockFace {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]

		if (clicked != null && clicked in WALLS) {
			return clicked
		}

		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return BlockFace.NORTH

		if (abs(direction.x) > abs(direction.z)) {
			return if (direction.x > 0) BlockFace.WEST else BlockFace.EAST
		}

		return if (direction.z > 0) BlockFace.NORTH else BlockFace.SOUTH
	}

	/** A sign is on a post unless it was hung on a wall. */
	private fun postedOnPlace(ctx: Context<BlockPlace>): Boolean {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]

		return clicked == null || clicked !in WALLS
	}

	private fun hitboxOf(scope: BlockSelectorScope): BlockState {
		val facing = scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH

		if (scope.getPropertyValueOrNull(POSTED) == true) {
			return paneState(facing)
		}

		return Hitboxes.clumpAgainst(facing)
	}

	/**
	 * The pane state a post-mounted sign borrows: the post itself plus the two
	 * arms lying across the sign's facing, which is the panel's own width.
	 */
	private fun paneState(facing: BlockFace): BlockState {
		val northSouth = facing == BlockFace.EAST || facing == BlockFace.WEST

		return Blocks.MAGENTA_STAINED_GLASS_PANE.defaultBlockState()
			.setValue(BlockStateProperties.NORTH, northSouth)
			.setValue(BlockStateProperties.SOUTH, northSouth)
			.setValue(BlockStateProperties.EAST, !northSouth)
			.setValue(BlockStateProperties.WEST, !northSouth)
	}

}
`;

const gaugeKotlin = `package dev.belikhun.luna.smp.gauges

import dev.belikhun.luna.smp.Hitboxes
import dev.belikhun.luna.smp.LunaSmp
import net.kyori.adventure.key.Key
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import xyz.xenondevs.nova.context.Context
import xyz.xenondevs.nova.context.intention.DefaultContextIntentions.BlockPlace
import xyz.xenondevs.nova.context.param.DefaultContextParamTypes
import xyz.xenondevs.nova.resources.builder.layout.block.BlockSelectorScope
import xyz.xenondevs.nova.resources.builder.layout.block.BlockModelSelectorScope
import xyz.xenondevs.nova.resources.builder.model.ModelBuilder
import xyz.xenondevs.nova.world.block.AbstractNovaBlockBuilder
import xyz.xenondevs.nova.world.block.NovaBlock
import xyz.xenondevs.nova.world.block.behavior.BlockSounds
import xyz.xenondevs.nova.world.block.behavior.Breakable
import xyz.xenondevs.nova.world.block.behavior.TileEntityDrops
import xyz.xenondevs.nova.world.block.behavior.TileEntityInteractive
import xyz.xenondevs.nova.world.block.sound.SoundGroup
import xyz.xenondevs.nova.world.block.state.property.DefaultBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.DefaultScopedBlockStateProperties
import xyz.xenondevs.nova.world.block.state.property.impl.BooleanProperty
import xyz.xenondevs.nova.world.block.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.world.format.WorldDataManager
import kotlin.math.abs

/**
 * The generated instrument table.
 *
 * GENERATED FILE - do not edit by hand. Change tools/furniture-gen/gauges.ts
 * and re-run the generator; the dial faces, the models and the language files
 * come from the same pass, so editing one of them alone drifts the set.
 *
 * Every number here was used to paint the matching face texture: the pivot,
 * the sweep and the scale radius are the same figures, which is what keeps
 * the live needle and the painted arc agreeing about where full scale is.
 * The dial convention is a clock's: zero at twelve, positive clockwise, as
 * the viewer sees the face.
 */
@Suppress("unused")
object GaugeCatalog {

	/** Whether the instrument is screwed flat onto the block it reads. */
	val ATTACHED = BooleanProperty(Key.key("lunasmp", "attached"))

	/** Whether the isolator conducts. */
	val ON = BooleanProperty(Key.key("lunasmp", "on"))

	/** The walls an instrument can hang on. */
	private val WALLS = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

	enum class Metric {
		ENERGY_STORED, ENERGY_FLOW, ENERGY_LOAD, ENERGY_GEN, ENERGY_TOTAL,
		ITEM_FLOW, ITEM_STORED, FLUID_STORED, FLUID_FLOW, MULTI, MULTI_FLOW,
	}

	/** How the live reading is drawn in front of the painted face. */
	enum class Style {
		NEEDLE, BAR, DIGITAL,
	}

	/** What a switch's handle cuts; the rest keeps bridging through it. */
	enum class Toggles {
		ALL, FLUID, ENERGY,
	}

	/**
	 * The three bodies an instrument comes in. 'faceDepth' is where the dial
	 * plane sits, in blocks from the north edge of a north-facing block;
	 * 'faceSpan' is how much of a block the 64-pixel face covers, and
	 * 'faceCenterY' is where the face's vertical centre sits in the block -
	 * the unit's housing rides low over its feet, so its centre is not 0.5.
	 * The needle and the range plate hang off these three numbers.
	 */
	enum class Form(val faceDepth: Double, val faceSpan: Double, val faceCenterY: Double) {
		PANEL(${(PANEL_FACE.z / 16).toFixed(4)}, ${(PANEL_FACE.span / 16).toFixed(4)}, ${((PANEL_FACE.low + PANEL_FACE.span / 2) / 16).toFixed(4)}),
		UNIT(${(UNIT_FACE.z / 16).toFixed(4)}, ${(UNIT_FACE.span / 16).toFixed(4)}, ${((UNIT_FACE.low + UNIT_FACE.span / 2) / 16).toFixed(4)}),
		BLOCK(${(BLOCK_FACE.z / 16).toFixed(4)}, ${(BLOCK_FACE.span / 16).toFixed(4)}, ${((BLOCK_FACE.low + BLOCK_FACE.span / 2) / 16).toFixed(4)}),
	}

	data class Spec(
		val id: String,
		val metric: Metric,
		/** Needle pivot, in face pixels of the 64-pixel dial. */
		val pivotX: Int,
		val pivotY: Int,
		/** Dial angle at zero and at full scale, clockwise from twelve. */
		val startDeg: Double,
		val endDeg: Double,
		/** Blade length, in face pixels; zero on the totaliser. */
		val needleLen: Int,
		/** The item whose model swings on the dial; null on the totaliser. */
		val needle: String? = null,
		/** Where the live range plate (or digit window) sits, in face pixels. */
		val windowX: Int? = null,
		val windowY: Int? = null,
		/** The full-block dashboard form of the same instrument. */
		val dashboard: Boolean = false,
		/** Needle, LED bar or LCD digits. */
		val style: Style = Style.NEEDLE,
	)

	/**
	 * One switch-family device: what it toggles, whether it snaps back on a
	 * timer, and whether a redstone coil drives it instead of a hand.
	 */
	data class SwitchSpec(
		val id: String,
		val toggles: Toggles,
		/** A pulse device holds for this many ticks, then lets go; zero holds. */
		val momentaryTicks: Int,
		/** Driven by redstone power at its own block, not by clicks. */
		val redstone: Boolean,
		/** Which pair of noises the state change makes. */
		val sound: String,
		val defaultOn: Boolean,
	)

	private val SWITCH_SPECS = listOf(
${switchSpecs.join('\n')}
	)

	/** Every switch-family spec, by block id. */
	val SWITCH_BY_ID: Map<String, SwitchSpec> = SWITCH_SPECS.associateBy { it.id }

	private val SPECS = listOf(
${gaugeSpecs.join('\n')}
	)

	/** Every instrument's spec, by block id. */
	val BY_ID: Map<String, Spec> = SPECS.associateBy { it.id }

	/** Every instrument block, by its id. */
	val BLOCKS: Map<String, NovaBlock> = SPECS.associate { it.id to register(it) }

	/** The switch family: every device that cuts or restores a line. */
	val SWITCH_BLOCKS: Map<String, NovaBlock> = SWITCH_SPECS.associate { it.id to registerSwitch(it) }

	/** The one-way bridge: two separated networks, and a flow between them. */
	val DIODE: NovaBlock = registerDiode()

	/** The activity indicator: the port lights of a network. */
	val LED: NovaBlock = registerLed()

	/** The indicator as a full block: a status wall's building brick. */
	val LED_BLOCK: NovaBlock = registerLedBlock()

	/** The alarm beacon: sweeps and wails while its condition holds. */
	val ALARM: NovaBlock = registerAlarm()

	/** The beacon on a full-block base, its dome on the face that was clicked. */
	val ALARM_BLOCK: NovaBlock = registerAlarmBlock()

	/** The powered light panel: a lit cube that is its own wiring. */
	val LIGHT_PANEL: NovaBlock = registerLightPanel()

	private fun register(spec: Spec): NovaBlock =
		LunaSmp.tileEntity(spec.id, ::GaugeTile) {
			stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) })

			if (!spec.dashboard) {
				stateProperties(ATTACHED.scope(setOf(false, true)) { ctx -> attachedOnPlace(ctx) })
			}

			// the needle moves once a second: any faster is packets for a
			// twitch nobody can read, any slower reads as a stuck meter
			tickrate(1)

			entityBacked(stateSelector = { hitboxOf(spec, this) }) {
				val model = if (spec.dashboard || getPropertyValueOrNull(ATTACHED) == true) {
					defaultModel
				} else {
					getModel("lunasmp:block/\${spec.id}_unit")
				}

				model.rotated()
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * Rotates a north-authored line device onto its FACING. Nova's own
	 * rotated() walks its vertical ring backwards (FACING = UP applies
	 * rotateX(-90), which lands model-north on DOWN), so a diode placed
	 * while looking up wore its arrows against the actual flow; the two
	 * vertical cases are rotated explicitly, model-north onto FACING.
	 */
	private fun BlockModelSelectorScope.lineRotated(model: ModelBuilder): ModelBuilder =
		when (getPropertyValueOrNull(DefaultBlockStateProperties.FACING)) {
			BlockFace.UP -> model.rotateX(90.0)
			BlockFace.DOWN -> model.rotateX(-90.0)
			else -> model.rotated()
		}

	private fun registerSwitch(spec: SwitchSpec): NovaBlock =
		LunaSmp.tileEntity(spec.id, ::NetworkSwitchTile) {
			// cartesian, not horizontal: aimed steeply up or down (the piston
			// gesture) the device stands vertical and its line runs up-down,
			// so a riser can carry a switch. Widening the scope is safe for
			// placed blocks: Nova's block state id map is persistent and only
			// appends ids for the new permutations.
			stateProperties(
				DefaultScopedBlockStateProperties.FACING_CARTESIAN,
				ON.scope(setOf(false, true)) { spec.defaultOn },
			)

			// once a second the switch re-checks which cable line it sits in:
			// bridges only link to bridges with an equal type id, so it has to
			// keep impersonating its neighbours (see NetworkSwitchTile). The
			// timed and redstone-driven ones tick every tick, because a pulse
			// that lets go on whole seconds and a contactor that answers its
			// coil a second late both read as broken.
			tickrate(if (spec.momentaryTicks > 0 || spec.redstone) 20 else 1)

			// the line devices are authored around the cable at block centre,
			// so the backing is the centred wall post, not a floor-hugging
			// core: an 8px column through the middle, whatever the facing
			entityBacked(stateSelector = { Hitboxes.linePost() }) {
				val model = if (getPropertyValueOrNull(ON) == false) {
					getModel("lunasmp:block/\${spec.id}_off")
				} else {
					defaultModel
				}

				lineRotated(model)
			}

			behaviors(
				Breakable(hardness = 1.5),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerLed(): NovaBlock =
		LunaSmp.tileEntity("network_led", ::LedTile) {
			stateProperties(DefaultBlockStateProperties.FACING.scope(WALLS) { ctx -> facingOnPlace(ctx) })

			// every tick, for the blink; the actual network read is 1/s
			tickrate(20)

			entityBacked(stateSelector = {
				val facing = getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH

				Hitboxes.clumpAgainst(facing)
			}) {
				defaultModel.rotated()
			}

			behaviors(
				Breakable(hardness = 0.8),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerAlarm(): NovaBlock =
		LunaSmp.tileEntity("alarm_light", ::AlarmTile) {
			// mounted on the face the placer clicked, like the block form's
			// dome: up on a floor, out of a wall, hanging under a ceiling
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(
					setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN),
				) { ctx -> ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: BlockFace.UP },
			)

			// every tick: the beam sweep and the horn are timed in ticks
			tickrate(20)

			entityBacked(stateSelector = {
				when (getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.UP) {
					BlockFace.UP -> Blocks.HEAVY_CORE.defaultBlockState()
					BlockFace.DOWN -> Hitboxes.clumpOnCeiling()
					else -> Hitboxes.clumpAgainst(
						getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH,
					)
				}
			}) {
				lineRotated(defaultModel)
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerLedBlock(): NovaBlock =
		LunaSmp.tileEntity("network_led_block", ::LedTile) {
			stateProperties(DefaultScopedBlockStateProperties.FACING_HORIZONTAL)

			// every tick, for the blink; the actual network read is 1/s
			tickrate(20)

			// barrier, like the gauge dashboards: an OCCLUDING backing starves
			// the display entity of light (level 0 inside a solid block) and
			// the whole cube renders pitch black - the first deploy's bug
			entityBacked(stateSelector = { Blocks.BARRIER.defaultBlockState() }) {
				defaultModel.rotated()
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerAlarmBlock(): NovaBlock =
		LunaSmp.tileEntity("alarm_light_block", ::AlarmTile) {
			// the dome rides the face the placer clicked: placing on a floor
			// points it up, under a ceiling down, against a wall outward
			stateProperties(
				DefaultBlockStateProperties.FACING.scope(
					setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN),
				) { ctx -> ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: BlockFace.UP },
				ON.scope(setOf(false, true)) { false },
			)

			// every tick: the beam sweep and the horn are timed in ticks
			tickrate(20)

			// quiet: barrier, so room light reaches the display (a solid
			// backing renders it black). Alarming: the LIT reserved bulb -
			// its own light 15 both glows and floodlights the model
			entityBacked(stateSelector = {
				if (getPropertyValueOrNull(ON) == true) {
					Hitboxes.bulb(true)
				} else {
					Blocks.BARRIER.defaultBlockState()
				}
			}) {
				lineRotated(defaultModel)
			}

			behaviors(
				Breakable(hardness = 1.0),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	private fun registerLightPanel(): NovaBlock =
		LunaSmp.tileEntity("light_panel", ::LightPanelTile) {
			stateProperties(ON.scope(setOf(false, true)) { false })

			// once a second: the panel draws its joules and settles lit or dark
			tickrate(1)

			// the backing carries the light itself: a reserved copper bulb,
			// solid and full-cube, LIT following the panel's own state
			entityBacked(stateSelector = { Hitboxes.bulb(getPropertyValueOrNull(ON) == true) }) {
				if (getPropertyValueOrNull(ON) == false) {
					getModel("lunasmp:block/light_panel_off")
				} else {
					defaultModel
				}
			}

			behaviors(
				Breakable(hardness = 0.5),
				BlockSounds(SoundGroup.GLASS),
				TileEntityDrops,
			)
		}

	private fun registerDiode(): NovaBlock =
		LunaSmp.tileEntity("network_diode", ::NetworkDiodeTile) {
			// cartesian like the switches: placed while aiming steeply down,
			// the inlet faces UP (towards the placer) and the flow runs down
			stateProperties(DefaultScopedBlockStateProperties.FACING_CARTESIAN)

			// every tick: the bridge sums what actually crossed it off its
			// own holders, and once-a-second totals would alias burst traffic
			tickrate(20)

			entityBacked(stateSelector = { Hitboxes.linePost() }) {
				lineRotated(defaultModel)
			}

			behaviors(
				Breakable(hardness = 1.5),
				BlockSounds(SoundGroup.STONE),
				TileEntityDrops,
				TileEntityInteractive,
			)
		}

	/**
	 * Which way an instrument looks when placed: along the wall face that was
	 * clicked, or back at whoever set it down anywhere else.
	 */
	private fun facingOnPlace(ctx: Context<BlockPlace>): BlockFace {
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE]

		if (clicked != null && clicked in WALLS) {
			return clicked
		}

		val direction = ctx[DefaultContextParamTypes.SOURCE_DIRECTION] ?: return BlockFace.NORTH

		if (abs(direction.x) > abs(direction.z)) {
			return if (direction.x > 0) BlockFace.WEST else BlockFace.EAST
		}

		return if (direction.z > 0) BlockFace.NORTH else BlockFace.SOUTH
	}

	/**
	 * Flat on the host only when the host is something worth reading: a
	 * networked machine, tank or cell, or a plain container. Anywhere else
	 * the instrument stands in its own housing.
	 */
	private fun attachedOnPlace(ctx: Context<BlockPlace>): Boolean {
		val pos = ctx[DefaultContextParamTypes.BLOCK_POS] ?: return false
		val clicked = ctx[DefaultContextParamTypes.CLICKED_BLOCK_FACE] ?: return false

		if (clicked !in WALLS) {
			return false
		}

		val host = pos.advance(clicked.oppositeFace, 1)

		if (WorldDataManager.getTileEntity(host) is NetworkedTileEntity) {
			return true
		}

		return host.block.state is Container
	}

	private fun hitboxOf(spec: Spec, scope: BlockSelectorScope): BlockState {
		if (spec.dashboard) {
			return Blocks.BARRIER.defaultBlockState()
		}

		if (scope.getPropertyValueOrNull(ATTACHED) == true) {
			val facing = scope.getPropertyValueOrNull(DefaultBlockStateProperties.FACING) ?: BlockFace.NORTH

			return Hitboxes.clumpAgainst(facing)
		}

		return Blocks.STRUCTURE_VOID.defaultBlockState()
	}
}
`;

const gaugeDir = join(dirname(addonDir), 'kotlin/dev/belikhun/luna/smp/gauges');
mkdirSync(gaugeDir, { recursive: true });
writeFileSync(join(gaugeDir, 'GaugeCatalog.kt'), gaugeKotlin);

const signDir = join(dirname(addonDir), 'kotlin/dev/belikhun/luna/smp/signs');
mkdirSync(signDir, { recursive: true });
writeFileSync(join(signDir, 'SignCatalog.kt'), signKotlin);

const floraDir = join(dirname(addonDir), 'kotlin/dev/belikhun/luna/smp/flora');
mkdirSync(floraDir, { recursive: true });
writeFileSync(join(floraDir, 'FloraCatalog.kt'), floraKotlin);


console.log(`pieces:   ${PIECES.length - failures.length}/${PIECES.length}`);
console.log(`flora:    ${FLORA.length}`);
console.log(`signs:    ${SIGNS.length}`);
console.log(`gauges:   ${GAUGES.length} kinds, ${SWITCHES.length} switch`);
console.log(`crowns:   ${CROWNS.length}`);
console.log(`models:   ${modelCount} written`);
console.log(`textures: ${textureNames.size} copied, ${spriteCount} painted`);

if (missingTextures.length > 0) {
	console.log(`\nmissing textures (${missingTextures.length}):`);
	for (const ref of [...new Set(missingTextures)].slice(0, 20)) {
		console.log(`  ${ref}`);
	}
}

if (failures.length > 0) {
	console.log(`\nfailed (${failures.length}):`);
	for (const line of failures) {
		console.log(`  ${line}`);
	}
	process.exit(1);
}
