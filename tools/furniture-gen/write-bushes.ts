// Regenerates only the bush sheets (every `<flower>_bush.png`), for a change to
// bushSprite that should not wait for a full generator run.
//
//   bun write-bushes.ts --addon ../../paper/luna-smp/src/main/resources

import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { FLOWERS } from './flora';
import { bushSprite } from './sprites';

const args = process.argv.slice(2);
const at = args.indexOf('--addon');

if (at < 0 || !args[at + 1]) {
	console.error('usage: bun write-bushes.ts --addon <luna-smp resources dir>');
	process.exit(1);
}

const textures = join(args[at + 1]!, 'assets/textures/block');
let count = 0;

for (const flower of FLOWERS) {
	const bushName = `${flower.id}_bush`;
	writeFileSync(join(textures, `${bushName}.png`), bushSprite(bushName, flower.accents));
	count++;
}

console.log(`${count} bush sheets written`);
