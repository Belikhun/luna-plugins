// Writes the park set (benches, drinking fountains, flower hedges) and the bouquet into the addon:
// models, painted sheets, block configs and both language files.
//
//   bun write-park.ts --addon ../../paper/luna-smp/src/main/resources

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { BENCH_MATERIALS, FOUNTAINS, HEDGE_FLOWERS, parkModels, parkSprites } from './parkset';
import { BOUQUET_ENTRY, bouquetItemModels, bouquetModels, bouquetSprites } from './bouquet';

const args = process.argv.slice(2);
const at = args.indexOf('--addon');

if (at < 0 || !args[at + 1]) {
	console.error('usage: bun write-park.ts --addon <luna-smp resources dir>');
	process.exit(1);
}

const addon = args[at + 1]!;
const models = join(addon, 'assets/models/block');
const items = join(addon, 'assets/models/item');
const textures = join(addon, 'assets/textures/block');
const configs = join(addon, 'configs');
const lang = join(addon, 'assets/lang');

for (const d of [models, items, textures, configs]) {
	try {
		mkdirSync(d, { recursive: true });
	} catch {}
}

let count = 0;

for (const [name, model] of Object.entries({ ...parkModels(), ...bouquetModels() })) {
	writeFileSync(join(models, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
	count++;
}

for (const [name, model] of Object.entries(bouquetItemModels())) {
	writeFileSync(join(items, `${name}.json`), JSON.stringify(model, null, '\t') + '\n');
}

for (const [name, bytes] of Object.entries({ ...parkSprites(), ...bouquetSprites() })) {
	writeFileSync(join(textures, `${name}.png`), bytes);
}

const names: Record<string, [string, string]> = {};
const config = (tool: string) => `tool_categories: [${tool}]\ntool_tier: wood\nrequires_tool_for_drops: false\n`;

for (const m of BENCH_MATERIALS) {
	names[`park_bench_${m.id}`] = [`${m.en} Park Bench`, `Ghế Công Viên ${m.vi}`];
	writeFileSync(join(configs, `park_bench_${m.id}.yml`), config(m.stone ? 'pickaxe' : 'axe'));
}

for (const f of FOUNTAINS) {
	names[`drinking_fountain_${f.id}`] = [`${f.en} Drinking Fountain`, `Vòi Nước Uống ${f.vi}`];
	writeFileSync(join(configs, `drinking_fountain_${f.id}.yml`), config('pickaxe'));
}

for (const h of HEDGE_FLOWERS) {
	names[`${h.id}_hedge`] = [`${h.en} Hedge`, `Hàng Rào ${h.vi}`];
	writeFileSync(join(configs, `${h.id}_hedge.yml`), config('hoe'));
}

names[BOUQUET_ENTRY.id] = [BOUQUET_ENTRY.en, BOUQUET_ENTRY.vi];
writeFileSync(join(configs, `${BOUQUET_ENTRY.id}.yml`), config('axe'));

for (const [file, index] of [['en_us.json', 0], ['vi_vn.json', 1]] as const) {
	const path = join(lang, file);
	const text = readFileSync(path, 'utf8');
	const map = JSON.parse(text) as Record<string, string>;

	for (const [id, pair] of Object.entries(names)) {
		map[`block.lunasmp.${id}`] = pair[index];
	}

	writeFileSync(path, JSON.stringify(map, null, text.includes('\n\t"') ? '\t' : '  ') + '\n');
}

console.log(`${count} models, ${Object.keys(names).length} named blocks`);
