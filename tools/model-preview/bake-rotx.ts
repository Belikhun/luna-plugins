// Bakes a -90° rotation about X into a flattened block model (up -> north),
// used for models authored flat that must stand vertically. Re-centers x/z
// and floors y afterward. Face directions, uv rects and uv rotations are
// re-derived by corner matching, so grain direction survives the bake.
//
// Usage: bun bake-rotx.ts <in.json> <out.json>

import { readFileSync, writeFileSync } from 'node:fs';

type Vec3 = [number, number, number];

const [inPath, outPath] = process.argv.slice(2);
const model = JSON.parse(readFileSync(inPath!, 'utf8'));

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

/** rotX(-90): (x, y, z) -> (x, z, -y), i.e. the up face turns to face north. */
function rotPoint(p: Vec3): Vec3 {
	return [p[0], p[2], -p[1]];
}

const DIR_MAP: Record<string, string> = {
	up: 'north', north: 'down', down: 'south', south: 'up', east: 'east', west: 'west',
};

const elements = model.elements as Array<Record<string, unknown>>;

for (const el of elements) {
	const f = el.from as Vec3;
	const t = el.to as Vec3;

	const rf = rotPoint(f);
	const rt = rotPoint(t);
	const nf: Vec3 = [Math.min(rf[0], rt[0]), Math.min(rf[1], rt[1]), Math.min(rf[2], rt[2])];
	const nt: Vec3 = [Math.max(rf[0], rt[0]), Math.max(rf[1], rt[1]), Math.max(rf[2], rt[2])];

	const newFaces: Record<string, unknown> = {};

	for (const [dir, spec] of Object.entries(el.faces as Record<string, Record<string, unknown>>)) {
		const uv = (spec.uv as number[] | undefined) ?? autoUv(dir, f, t);
		const rot = ((spec.rotation as number | undefined) ?? 0) / 90;

		// texcoords per corner under the current rotation: cyclic shift of the
		// base assignment [(u1,v1),(u2,v1),(u2,v2),(u1,v2)]
		const oldCorners = faceCorners(dir, f, t).map(rotPoint);

		const newDir = DIR_MAP[dir]!;
		const base = faceCorners(newDir, nf, nt);

		// find cyclic shift k with base[j] == oldCorners[(j + k) % 4]
		let shift = -1;
		for (let k = 0; k < 4; k++) {
			let match = true;
			for (let j = 0; j < 4; j++) {
				const a = base[j]!;
				const b = oldCorners[(j + k) % 4]!;
				if (Math.abs(a[0] - b[0]) > 1e-6 || Math.abs(a[1] - b[1]) > 1e-6 || Math.abs(a[2] - b[2]) > 1e-6) {
					match = false;
					break;
				}
			}
			if (match) {
				shift = k;
				break;
			}
		}
		if (shift < 0) {
			throw new Error(`corner match failed for face ${dir}`);
		}

		const out: Record<string, unknown> = { ...spec, uv };
		const newRot = ((rot + shift) % 4) * 90;
		if (newRot !== 0) {
			out.rotation = newRot;
		} else {
			delete out.rotation;
		}
		delete out.cullface;
		newFaces[newDir] = out;
	}

	el.from = nf;
	el.to = nt;
	el.faces = newFaces;

	const er = el.rotation as { origin: Vec3; axis: string; angle: number } | undefined;
	if (er && er.angle !== 0) {
		er.origin = rotPoint(er.origin);
		if (er.axis === 'y') {
			er.axis = 'z';
			er.angle = -er.angle;
		} else if (er.axis === 'z') {
			er.axis = 'y';
		}
	} else if (er) {
		er.origin = rotPoint(er.origin);
	}
}

// re-center x/z, floor y
let minY = Infinity, minZ = Infinity, maxZ = -Infinity, minX = Infinity, maxX = -Infinity;
for (const el of elements) {
	const f = el.from as Vec3;
	const t = el.to as Vec3;
	minY = Math.min(minY, f[1]);
	minZ = Math.min(minZ, f[2]); maxZ = Math.max(maxZ, t[2]);
	minX = Math.min(minX, f[0]); maxX = Math.max(maxX, t[0]);
}

const dy = -minY;
const dz = 8 - (minZ + maxZ) / 2;
const dx = 8 - (minX + maxX) / 2;

for (const el of elements) {
	for (const key of ['from', 'to'] as const) {
		const p = el[key] as Vec3;
		el[key] = [p[0] + dx, p[1] + dy, p[2] + dz];
	}
	const er = el.rotation as { origin: Vec3 } | undefined;
	if (er) {
		er.origin = [er.origin[0] + dx, er.origin[1] + dy, er.origin[2] + dz];
	}
}

writeFileSync(outPath!, JSON.stringify(model, null, '\t') + '\n');
console.log(`baked rotX(-90) -> ${outPath} (shift x ${dx}, y ${dy}, z ${dz})`);
