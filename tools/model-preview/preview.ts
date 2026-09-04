// Block-model previewer: renders a vanilla-format block model JSON to a PNG so
// furniture models can be judged without a deploy round-trip.
//
// Usage:
//   bun preview.ts <model.json> <out.png> [--yaw 225] [--pitch 30] [--size 512]
//     [--assets ns=dir ...]   namespace roots holding models/ and textures/
//
// minecraft: textures are fetched from the misode/mcmeta asset mirror on first
// use and cached beside this script. Vanilla directional shading is applied
// (up 1.0, down 0.5, n/s 0.8, e/w 0.6) so the preview reads like the game.

import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { decodePng, encodePng, bitmap, type Bitmap } from '/home/belikhun/luna-console/web/src/lib/server/imaging/png';

type Vec3 = [number, number, number];

interface Face {
	uv?: [number, number, number, number];
	texture: string;
	rotation?: number;
	tintindex?: number;
}

interface Element {
	from: Vec3;
	to: Vec3;
	rotation?: { origin: Vec3; axis: 'x' | 'y' | 'z'; angle: number; rescale?: boolean };
	shade?: boolean;
	faces: Partial<Record<'north' | 'south' | 'east' | 'west' | 'up' | 'down', Face>>;
}

interface Model {
	parent?: string;
	textures?: Record<string, string>;
	elements?: Element[];
}

const args = process.argv.slice(2);
const positional: string[] = [];
const assetRoots: Record<string, string> = {};
let yaw = 225;
let pitch = 30;
let size = 512;

for (let i = 0; i < args.length; i++) {
	const a = args[i]!;

	if (a === '--yaw') {
		yaw = Number(args[++i]);
	} else if (a === '--pitch') {
		pitch = Number(args[++i]);
	} else if (a === '--size') {
		size = Number(args[++i]);
	} else if (a === '--assets') {
		const [ns, dir] = args[++i]!.split('=');
		assetRoots[ns!] = dir!;
	} else {
		positional.push(a);
	}
}

const [modelPath, outPath] = positional;
if (!modelPath || !outPath) {
	console.error('usage: bun preview.ts <model.json> <out.png> [--yaw N] [--pitch N] [--size N] [--assets ns=dir ...]');
	process.exit(1);
}

const CACHE = join(dirname(new URL(import.meta.url).pathname), 'mcassets');
const MCMETA = 'https://raw.githubusercontent.com/misode/mcmeta/assets/assets';

function splitRef(ref: string): [string, string] {
	const idx = ref.indexOf(':');
	if (idx < 0) {
		return ['minecraft', ref];
	}
	return [ref.slice(0, idx), ref.slice(idx + 1)];
}

async function loadTexture(ref: string): Promise<Bitmap> {
	const [ns, path] = splitRef(ref);

	if (assetRoots[ns]) {
		const file = join(assetRoots[ns]!, 'textures', path + '.png');
		if (existsSync(file)) {
			return cropFrame(decodePng(readFileSync(file)));
		}
	}

	// vanilla fallback via the mcmeta mirror, cached locally
	const cached = join(CACHE, ns, 'textures', path + '.png');
	if (existsSync(cached)) {
		return cropFrame(decodePng(readFileSync(cached)));
	}

	const url = `${MCMETA}/${ns}/textures/${path}.png`;
	const res = await fetch(url);
	if (!res.ok) {
		throw new Error(`texture not found: ${ref} (${url} -> ${res.status})`);
	}

	const bytes = new Uint8Array(await res.arrayBuffer());
	mkdirSync(dirname(cached), { recursive: true });
	writeFileSync(cached, bytes);
	return cropFrame(decodePng(bytes));
}

/** Animation strips are taller than wide; keep the first frame. */
function cropFrame(img: Bitmap): Bitmap {
	if (img.height <= img.width) {
		return img;
	}
	const out = bitmap(img.width, img.width);
	out.data.set(img.data.subarray(0, img.width * img.width * 4));
	return out;
}

function loadModelFile(ref: string): Model {
	const [ns, path] = splitRef(ref);

	if (assetRoots[ns]) {
		const file = join(assetRoots[ns]!, 'models', path + '.json');
		if (existsSync(file)) {
			return JSON.parse(readFileSync(file, 'utf8'));
		}
	}

	const cached = join(CACHE, ns, 'models', path + '.json');
	if (!existsSync(cached)) {
		throw new Error(`parent model not found locally: ${ref}; fetch it or add --assets`);
	}
	return JSON.parse(readFileSync(cached, 'utf8'));
}

/** Merge the parent chain: child textures win, nearest elements win. */
function resolveModel(entry: Model): Model {
	let textures: Record<string, string> = {};
	let elements: Element[] | undefined;
	let current: Model | undefined = entry;
	let guard = 0;

	while (current && guard++ < 16) {
		textures = { ...(current.textures ?? {}), ...textures };
		if (!elements && current.elements) {
			elements = current.elements;
		}
		current = current.parent && !current.parent.startsWith('builtin/')
			? loadModelFile(current.parent)
			: undefined;
	}

	return { textures, elements: elements ?? [] };
}

function resolveTextureRef(textures: Record<string, string>, ref: string): string {
	let guard = 0;
	while (ref.startsWith('#') && guard++ < 16) {
		const next = textures[ref.slice(1)];
		if (!next) {
			throw new Error(`unresolved texture ref ${ref}`);
		}
		ref = next;
	}
	return ref;
}

// ---- geometry ----------------------------------------------------------

function rot(p: Vec3, axis: 'x' | 'y' | 'z', deg: number, origin: Vec3): Vec3 {
	const r = (deg * Math.PI) / 180;
	const c = Math.cos(r);
	const s = Math.sin(r);
	const [x, y, z] = [p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]];
	let v: Vec3;

	if (axis === 'x') {
		v = [x, y * c - z * s, y * s + z * c];
	} else if (axis === 'y') {
		v = [x * c + z * s, y, -x * s + z * c];
	} else {
		v = [x * c - y * s, x * s + y * c, z];
	}

	return [v[0] + origin[0], v[1] + origin[1], v[2] + origin[2]];
}

const FACE_SHADE: Record<string, number> = {
	up: 1.0, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6,
};

interface Quad {
	corners: [Vec3, Vec3, Vec3, Vec3]; // uv (0,0) (1,0) (1,1) (0,1) order
	uv: [number, number, number, number];
	uvRotation: number;
	texture: string;
	tint: boolean;
	shade: number;
}

/**
 * Corners for each face, ordered so texture (u,v) runs left-right, top-bottom
 * exactly as vanilla maps them.
 */
function faceCorners(dir: string, f: Vec3, t: Vec3): [Vec3, Vec3, Vec3, Vec3] {
	const [x1, y1, z1] = f;
	const [x2, y2, z2] = t;

	switch (dir) {
		case 'north': return [[x2, y2, z1], [x1, y2, z1], [x1, y1, z1], [x2, y1, z1]];
		case 'south': return [[x1, y2, z2], [x2, y2, z2], [x2, y1, z2], [x1, y1, z2]];
		case 'west':  return [[x1, y2, z1], [x1, y2, z2], [x1, y1, z2], [x1, y1, z1]];
		case 'east':  return [[x2, y2, z2], [x2, y2, z1], [x2, y1, z1], [x2, y1, z2]];
		case 'up':    return [[x1, y2, z1], [x2, y2, z1], [x2, y2, z2], [x1, y2, z2]];
		case 'down':  return [[x1, y1, z2], [x2, y1, z2], [x2, y1, z1], [x1, y1, z1]];
		default: throw new Error(dir);
	}
}

function autoUv(dir: string, f: Vec3, t: Vec3): [number, number, number, number] {
	switch (dir) {
		case 'north': return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]];
		case 'south': return [f[0], 16 - t[1], t[0], 16 - f[1]];
		case 'west':  return [f[2], 16 - t[1], t[2], 16 - f[1]];
		case 'east':  return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]];
		case 'up':    return [f[0], f[2], t[0], t[2]];
		case 'down':  return [f[0], 16 - t[2], t[0], 16 - f[2]];
		default: throw new Error(dir);
	}
}

// ---- main --------------------------------------------------------------

const raw: Model = JSON.parse(readFileSync(modelPath, 'utf8'));
const model = resolveModel(raw);
const textures = model.textures!;

const quads: Quad[] = [];

for (const el of model.elements!) {
	for (const [dir, face] of Object.entries(el.faces)) {
		if (!face) {
			continue;
		}

		let corners = faceCorners(dir, el.from, el.to);

		if (el.rotation) {
			const { origin, axis, angle, rescale } = el.rotation;
			corners = corners.map((p) => rot(p, axis, angle, origin)) as Quad['corners'];

			if (rescale) {
				const scale = 1 / Math.cos((Math.abs(angle) * Math.PI) / 180);
				corners = corners.map((p) => {
					const out: Vec3 = [...p];
					for (const ax of [0, 1, 2] as const) {
						if ((axis === 'x' && ax !== 0) || (axis === 'y' && ax !== 1) || (axis === 'z' && ax !== 2)) {
							out[ax] = origin[ax] + (p[ax] - origin[ax]) * scale;
						}
					}
					return out;
				}) as Quad['corners'];
			}
		}

		quads.push({
			corners,
			uv: face.uv ?? autoUv(dir, el.from, el.to),
			uvRotation: face.rotation ?? 0,
			texture: resolveTextureRef(textures, face.texture),
			tint: face.tintindex !== undefined,
			shade: el.shade === false ? 1.0 : FACE_SHADE[dir]!,
		});
	}
}

const textureCache = new Map<string, Bitmap>();
for (const q of quads) {
	if (!textureCache.has(q.texture)) {
		textureCache.set(q.texture, await loadTexture(q.texture));
	}
}

// camera: yaw about Y, then pitch about X, orthographic
const cy = Math.cos((yaw * Math.PI) / 180);
const sy = Math.sin((yaw * Math.PI) / 180);
const cp = Math.cos((pitch * Math.PI) / 180);
const sp = Math.sin((pitch * Math.PI) / 180);

function view(p: Vec3): Vec3 {
	const x = p[0] - 8;
	const y = p[1] - 8;
	const z = p[2] - 8;
	const rx = x * cy + z * sy;
	const rz = -x * sy + z * cy;
	const ry = y * cp - rz * sp;
	const rz2 = y * sp + rz * cp;
	return [rx, ry, rz2];
}

let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
for (const q of quads) {
	for (const c of q.corners) {
		const v = view(c);
		minX = Math.min(minX, v[0]); maxX = Math.max(maxX, v[0]);
		minY = Math.min(minY, v[1]); maxY = Math.max(maxY, v[1]);
	}
}

const pad = 1;
const span = Math.max(maxX - minX, maxY - minY) + pad * 2;
const scale = size / span;
const offX = (size - (maxX - minX) * scale) / 2 - minX * scale;
const offY = (size - (maxY - minY) * scale) / 2 + maxY * scale;

// painter's algorithm: draw back to front by centroid depth
quads.sort((a, b) => {
	const da = a.corners.reduce((s, c) => s + view(c)[2], 0);
	const db = b.corners.reduce((s, c) => s + view(c)[2], 0);
	return da - db;
});

const out = bitmap(size, size);
const SS = 3; // supersamples per axis

for (const q of quads) {
	const tex = textureCache.get(q.texture)!;
	const pts = q.corners.map((c) => {
		const v = view(c);
		return [v[0] * scale + offX, offY - v[1] * scale] as [number, number];
	});

	// projected quad is a parallelogram (affine transform of a rectangle):
	// p = p0 + s*e1 + t*e2, with (s,t) the texture-space coordinates
	const e1 = [pts[1]![0] - pts[0]![0], pts[1]![1] - pts[0]![1]];
	const e2 = [pts[3]![0] - pts[0]![0], pts[3]![1] - pts[0]![1]];
	const det = e1[0]! * e2[1]! - e1[1]! * e2[0]!;
	if (Math.abs(det) < 1e-9) {
		continue;
	}

	const bx0 = Math.max(0, Math.floor(Math.min(...pts.map((p) => p[0]))));
	const bx1 = Math.min(size - 1, Math.ceil(Math.max(...pts.map((p) => p[0]))));
	const by0 = Math.max(0, Math.floor(Math.min(...pts.map((p) => p[1]))));
	const by1 = Math.min(size - 1, Math.ceil(Math.max(...pts.map((p) => p[1]))));

	const [u1, v1, u2, v2] = q.uv;

	for (let py = by0; py <= by1; py++) {
		for (let px = bx0; px <= bx1; px++) {
			let r = 0, g = 0, b = 0, a = 0, hits = 0;

			for (let sy2 = 0; sy2 < SS; sy2++) {
				for (let sx2 = 0; sx2 < SS; sx2++) {
					const fx = px + (sx2 + 0.5) / SS - pts[0]![0];
					const fy = py + (sy2 + 0.5) / SS - pts[0]![1];
					let s = (fx * e2[1]! - fy * e2[0]!) / det;
					let t = (fy * e1[0]! - fx * e1[1]!) / det;

					if (s < 0 || s > 1 || t < 0 || t > 1) {
						continue;
					}

					// uv rotation spins texture coords within the face
					for (let n = 0; n < q.uvRotation / 90; n++) {
						const ns = t;
						t = 1 - s;
						s = ns;
					}

					const tu = (u1 + (u2 - u1) * s) / 16 * tex.width;
					const tv = (v1 + (v2 - v1) * t) / 16 * tex.height;
					const ix = Math.min(tex.width - 1, Math.max(0, Math.floor(tu)));
					const iy = Math.min(tex.height - 1, Math.max(0, Math.floor(tv)));
					const off = (iy * tex.width + ix) * 4;
					const ta = tex.data[off + 3]!;

					if (ta < 128) {
						continue;
					}

					let tr = tex.data[off]!, tg = tex.data[off + 1]!, tb = tex.data[off + 2]!;
					if (q.tint) {
						tr = (tr * 0x7c) >> 8; tg = (tg * 0xbd) >> 8; tb = (tb * 0x6b) >> 8;
					}

					r += tr * q.shade; g += tg * q.shade; b += tb * q.shade; a += 255;
					hits++;
				}
			}

			if (hits === 0) {
				continue;
			}

			const total = SS * SS;
			const cov = a / total / 255;
			const off2 = (py * size + px) * 4;
			const inv = 1 - cov;
			out.data[off2] = Math.round(r / hits * cov + out.data[off2]! * inv);
			out.data[off2 + 1] = Math.round(g / hits * cov + out.data[off2 + 1]! * inv);
			out.data[off2 + 2] = Math.round(b / hits * cov + out.data[off2 + 2]! * inv);
			out.data[off2 + 3] = Math.max(out.data[off2 + 3]!, Math.round(cov * 255));
		}
	}
}

writeFileSync(outPath, encodePng(out));
console.log(`rendered ${quads.length} quads -> ${outPath}`);
