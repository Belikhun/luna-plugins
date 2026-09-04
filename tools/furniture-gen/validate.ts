// Checks the generated models against the textures they actually name.
//
//   bun validate.ts --addon ../../paper/luna-smp/src/main/resources \
//     [--vanilla <dir holding assets/minecraft/textures>]
//
// The generator refuses what it can see from inside itself: a model with no
// elements, a face naming a variable nothing defines, a texture missing from a
// source pack. What it cannot see is what the art looks like once the client
// has it, and three kinds of wrong have shipped that way:
//
//   missing       a face's texture resolves to no file at all, which the
//                 client draws as the purple and black missing cube. A path
//                 with no namespace is vanilla's, so this needs the vanilla
//                 assets to judge; Nova keeps a copy of them beside the
//                 resource pack it builds, which is the default below.
//   cutout-cube   a face of a real cube samples transparent pixels. The
//                 silhouette then reads as a hole rather than as a shape,
//                 which is what Rustic's vases, its iron barrel and its
//                 crushing tub looked like: a drawing of a round object
//                 painted on a box you can see through.
//   uv-range      a window reaching past the sprite, which the client answers
//                 with whatever the atlas packed next to it.
//
// Two more classes are reported for reading rather than as faults. `empty` is
// a face whose window is entirely transparent: usually a hidden underside or
// the middle of a pane of glass, occasionally a piece of geometry that will
// never be seen. `cutout-body` is a chunky box with a partly transparent
// texture: an overlay shell, a head skin's hat layer and a pot's rounded
// corners are all built that way on purpose.
//
// Exits non-zero when a fault class has any finding, so it can gate a build.

import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { type Bitmap, decodePng } from '/home/belikhun/luna-console/web/src/lib/server/imaging/png';

const NOVA_ASSETS = '/mnt/shulker/mrds/survival/plugins/Nova/resource_pack/.mcassets';

const args = process.argv.slice(2);
let addonDir = '';
let vanillaDir = NOVA_ASSETS;

for (let i = 0; i < args.length; i++) {
	if (args[i] === '--addon') {
		addonDir = args[++i]!;
	} else if (args[i] === '--vanilla') {
		vanillaDir = args[++i]!;
	}
}

if (!addonDir) {
	console.error('usage: bun validate.ts --addon <dir> [--vanilla <dir>]');
	process.exit(2);
}

const ADDON_TEXTURES = join(addonDir, 'assets/textures');
const VANILLA_TEXTURES = join(vanillaDir, 'assets/minecraft/textures');

if (!existsSync(VANILLA_TEXTURES)) {
	console.error(`vanilla textures not found at ${VANILLA_TEXTURES}; pass --vanilla`);
	process.exit(2);
}

/** The fault classes, in the order they are reported. */
const FAULTS = ['missing', 'dangling', 'cutout-cube', 'uv-range'] as const;
const NOTES = ['empty', 'cutout-body'] as const;

interface Finding {
	model: string;
	kind: string;
	detail: string;
}

const findings: Finding[] = [];
const cache = new Map<string, Bitmap | null>();

/** The file a texture reference names, wherever it lives. */
function texture(ref: string): Bitmap | null {
	if (cache.has(ref)) {
		return cache.get(ref)!;
	}

	const colon = ref.indexOf(':');
	const ns = colon < 0 ? 'minecraft' : ref.slice(0, colon);
	const path = colon < 0 ? ref : ref.slice(colon + 1);
	const file = ns === 'lunasmp'
		? join(ADDON_TEXTURES, `${path}.png`)
		: join(VANILLA_TEXTURES, `${path}.png`);

	let image: Bitmap | null = null;

	if (existsSync(file)) {
		image = decodePng(new Uint8Array(readFileSync(file)));
	}

	cache.set(ref, image);
	return image;
}

/**
 * How much of a uv window is transparent, on the first frame of the sprite.
 *
 * An animated texture is a column of frames and only its first is what the
 * model shows at rest, so the rest of the sheet must not be sampled here.
 */
function transparency(image: Bitmap, uv: number[]): number {
	const frame = image.height > image.width ? image.width : image.height;
	const sx = image.width / 16;
	const sy = frame / 16;
	const x1 = Math.min(uv[0]!, uv[2]!) * sx;
	const x2 = Math.max(uv[0]!, uv[2]!) * sx;
	const y1 = Math.min(uv[1]!, uv[3]!) * sy;
	const y2 = Math.max(uv[1]!, uv[3]!) * sy;

	let seen = 0;
	let clear = 0;

	for (let y = Math.floor(y1); y < Math.ceil(y2); y++) {
		for (let x = Math.floor(x1); x < Math.ceil(x2); x++) {
			if (x < 0 || y < 0 || x >= image.width || y >= frame) {
				continue;
			}

			seen++;

			if (image.data[(y * image.width + x) * 4 + 3]! < 16) {
				clear++;
			}
		}
	}

	return seen === 0 ? 1 : clear / seen;
}

interface Element {
	from: number[];
	to: number[];
	faces?: Record<string, { uv?: number[]; texture?: string }>;
}

function check(dir: string, label: string): number {
	const root = join(addonDir, 'assets/models', dir);

	if (!existsSync(root)) {
		return 0;
	}

	const names = readdirSync(root).filter((name) => name.endsWith('.json')).sort();

	for (const name of names) {
		const model = `${label}/${name.replace(/\.json$/, '')}`;
		const json = JSON.parse(readFileSync(join(root, name), 'utf8')) as {
			textures?: Record<string, string>;
			elements?: Element[];
		};
		const vars = json.textures ?? {};

		// the particle slot is drawn by nothing, so no face names it and the
		// walk below never reaches it - and a model whose particle resolves to
		// nothing throws purple and black flecks on every break and footstep
		for (const [slot, ref] of Object.entries(vars)) {
			if (ref.startsWith('#') || texture(ref) !== null) {
				continue;
			}

			findings.push({ model, kind: 'missing', detail: `${slot} slot -> ${ref}` });
		}

		for (const element of json.elements ?? []) {
			const size = [0, 1, 2].map((axis) => element.to[axis]! - element.from[axis]!);
			// a box filling the block on every axis is a cube, and a hole in
			// its texture is a hole through the block; a hole in a thin panel
			// - a trellis, a sign, a pane - is the point of the panel
			const cube = size.every((n) => Math.abs(n - 16) < 0.01);
			const body = Math.min(...size) >= 4;

			for (const [face, drawn] of Object.entries(element.faces ?? {})) {
				let ref = drawn.texture ?? '';
				let guard = 0;

				while (ref.startsWith('#') && guard++ < 8) {
					const next = vars[ref.slice(1)];

					if (next === undefined) {
						findings.push({ model, kind: 'dangling', detail: `${face} -> ${ref}` });
						ref = '';
						break;
					}

					ref = next;
				}

				if (!ref) {
					continue;
				}

				const image = texture(ref);

				if (!image) {
					findings.push({ model, kind: 'missing', detail: `${face} -> ${ref}` });
					continue;
				}

				const uv = drawn.uv ?? [0, 0, 16, 16];

				if (uv.some((n) => n < -0.01 || n > 16.01)) {
					findings.push({ model, kind: 'uv-range', detail: `${face} uv ${uv.join(',')} on ${ref}` });
				}

				// a zero-area window belongs to a side face of a flat element
				// and is never drawn, so it says nothing about the art
				if (Math.abs(uv[0]! - uv[2]!) < 0.01 || Math.abs(uv[1]! - uv[3]!) < 0.01) {
					continue;
				}

				const clear = transparency(image, uv);

				if (clear > 0.995) {
					findings.push({ model, kind: 'empty', detail: `${face} -> ${ref}` });
				} else if (cube && clear > 0.02) {
					findings.push({ model, kind: 'cutout-cube', detail: `${face} ${(clear * 100).toFixed(0)}% clear -> ${ref}` });
				} else if (body && clear > 0.08) {
					findings.push({ model, kind: 'cutout-body', detail: `${face} ${(clear * 100).toFixed(0)}% clear -> ${ref}` });
				}
			}
		}
	}

	return names.length;
}

const checked = check('block', 'block') + check('item', 'item');

function report(kind: string, limit: number): number {
	const list = findings.filter((finding) => finding.kind === kind);

	if (list.length === 0) {
		return 0;
	}

	const byModel = new Map<string, string[]>();

	for (const finding of list) {
		const details = byModel.get(finding.model) ?? [];
		details.push(finding.detail);
		byModel.set(finding.model, details);
	}

	console.log(`\n${kind}: ${list.length} faces across ${byModel.size} models`);

	for (const [model, details] of [...byModel].slice(0, limit)) {
		const shown = details.slice(0, 3).join(' · ');
		console.log(`  ${model}: ${shown}${details.length > 3 ? ` (+${details.length - 3})` : ''}`);
	}

	if (byModel.size > limit) {
		console.log(`  ... and ${byModel.size - limit} more models`);
	}

	return list.length;
}

let faults = 0;

for (const kind of FAULTS) {
	faults += report(kind, 40);
}

for (const kind of NOTES) {
	report(kind, 10);
}

console.log(`\nmodels checked: ${checked}`);
console.log(faults === 0 ? 'no faults' : `FAULTS: ${faults}`);
process.exit(faults === 0 ? 0 : 1);
