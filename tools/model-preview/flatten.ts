// Flattens one or more vanilla block models (resolving parent chains) into a
// single standalone model JSON suitable for a Nova addon: textures merged with
// collision-free keys, elements concatenated, display kept (first input wins),
// and non-vanilla texture refs optionally rewritten + the files copied.
//
// Usage:
//   bun flatten.ts --assets ns=dir ... --out file.json [--display std|keep]
//     [--retex 'beautify:blocks/=lunasmp:block/'] [--copytex fromdir=todir]
//     <model.json or ns:path> ...

import { readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';

const args = process.argv.slice(2);
const inputs: string[] = [];
const assetRoots: Record<string, string> = {};
const retex: Array<[string, string]> = [];
let outPath = '';
let displayMode = 'keep';
let copyTex: [string, string] | undefined;

for (let i = 0; i < args.length; i++) {
	const a = args[i]!;
	if (a === '--assets') {
		const [ns, dir] = args[++i]!.split('=');
		assetRoots[ns!] = dir!;
	} else if (a === '--out') {
		outPath = args[++i]!;
	} else if (a === '--display') {
		displayMode = args[++i]!;
	} else if (a === '--retex') {
		const [from, to] = args[++i]!.split('=');
		retex.push([from!, to!]);
	} else if (a === '--copytex') {
		const [from, to] = args[++i]!.split('=');
		copyTex = [from!, to!];
	} else {
		inputs.push(a);
	}
}

interface Model {
	parent?: string;
	textures?: Record<string, string>;
	elements?: unknown[];
	display?: Record<string, unknown>;
}

function splitRef(ref: string): [string, string] {
	const idx = ref.indexOf(':');
	if (idx < 0) {
		return ['minecraft', ref];
	}
	return [ref.slice(0, idx), ref.slice(idx + 1)];
}

function loadModel(refOrPath: string): Model {
	if (existsSync(refOrPath)) {
		return JSON.parse(readFileSync(refOrPath, 'utf8'));
	}
	const [ns, path] = splitRef(refOrPath);
	const root = assetRoots[ns];
	if (!root) {
		throw new Error(`no asset root for ${refOrPath}`);
	}
	const file = join(root, 'models', path + '.json');
	return JSON.parse(readFileSync(file, 'utf8'));
}

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
		current = current.parent && !current.parent.startsWith('builtin/')
			? loadModel(current.parent)
			: undefined;
	}

	return { textures, elements: elements ?? [], display };
}

const STD_DISPLAY = {
	gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
	ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
	fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
	head: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
	thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
	thirdperson_lefthand: { rotation: [75, 315, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
	firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
	firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
};

const mergedTextures: Record<string, string> = {};
const valueToKey = new Map<string, string>();
let mergedElements: unknown[] = [];
let mergedDisplay: Record<string, unknown> | undefined;
const copied: string[] = [];

function applyRetex(value: string): string {
	for (const [from, to] of retex) {
		if (value.startsWith(from)) {
			const renamed = to + value.slice(from.length);
			if (copyTex) {
				const [srcRoot, dstRoot] = copyTex;
				const src = join(srcRoot, value.slice(from.length) + '.png');
				const dst = join(dstRoot, basename(value) + '.png');
				if (existsSync(src)) {
					mkdirSync(dirname(dst), { recursive: true });
					copyFileSync(src, dst);
					copied.push(`${src} -> ${dst}`);
				} else {
					console.warn(`! texture file missing: ${src}`);
				}
				// flatten the path: everything lands in one block/ dir
				return to.replace(/\/$/, '/') + basename(value);
			}
			return renamed;
		}
	}
	return value;
}

for (let mi = 0; mi < inputs.length; mi++) {
	const model = resolveChain(loadModel(inputs[mi]!));
	const keyMap: Record<string, string> = {};

	for (const [key, rawValue] of Object.entries(model.textures!)) {
		if (rawValue.startsWith('#')) {
			// alias to another slot; resolve later via keyMap of same model
			keyMap[key] = rawValue.slice(1);
			continue;
		}
		const value = applyRetex(rawValue);
		let finalKey = valueToKey.get(value);
		if (!finalKey) {
			finalKey = mergedTextures[key] === undefined ? key : `m${mi}_${key}`;
			mergedTextures[finalKey] = value;
			valueToKey.set(value, finalKey);
		}
		keyMap[key] = finalKey;
	}

	// second pass for alias slots (#other)
	let guard = 0;
	let unresolved = true;
	while (unresolved && guard++ < 8) {
		unresolved = false;
		for (const [key, target] of Object.entries(keyMap)) {
			if (mergedTextures[keyMap[target] ?? ''] !== undefined && keyMap[target] !== undefined && keyMap[key] === target && target in keyMap && keyMap[target] !== key) {
				keyMap[key] = keyMap[target]!;
			}
			if (keyMap[key]! in keyMap && keyMap[keyMap[key]!] !== keyMap[key]) {
				unresolved = true;
			}
		}
	}

	const remapped = JSON.parse(JSON.stringify(model.elements), (k, v) => {
		if (k === 'texture' && typeof v === 'string' && v.startsWith('#')) {
			const mapped = keyMap[v.slice(1)];
			return mapped ? `#${mapped}` : v;
		}
		return v;
	});

	mergedElements = mergedElements.concat(remapped);

	if (!mergedDisplay && model.display) {
		mergedDisplay = model.display;
	}
}

// particle: keep if set, else first texture
if (!mergedTextures['particle']) {
	const first = Object.values(mergedTextures)[0];
	if (first) {
		mergedTextures['particle'] = first;
	}
}

const out: Model = {
	textures: mergedTextures,
	elements: mergedElements,
	display: displayMode === 'std'
		? STD_DISPLAY
		: (mergedDisplay ?? STD_DISPLAY),
};

writeFileSync(outPath, JSON.stringify(out, null, '\t') + '\n');
console.log(`${inputs.length} model(s) -> ${outPath}: ${mergedElements.length} elements, textures: ${JSON.stringify(mergedTextures)}`);
for (const c of copied) {
	console.log(`  copied ${c}`);
}
