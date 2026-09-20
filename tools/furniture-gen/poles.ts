// The overhead power line: the pole that carries it, and the wire that spans
// between two of them.
//
// A pole is three blocks, so its height is the builder's decision rather than
// ours: a base on the ground, any number of mast segments, and a head with the
// crossarm. Energy climbs it because the base and the masts are energy
// BRIDGES, exactly as a Logistics cable is one, and the head is an ENDPOINT
// with a buffer. That split is what makes the span cheap: the tie between two
// poles moves joules out of one head's buffer straight into the other's, with
// no walk over the network graph at all.
//
// The wire is not a block. It is a link the two heads remember, drawn as a
// vanilla lead between two client-side entities, and it carries ENERGY only -
// no items, no fluid, whatever the cable feeding the pole carries.
// Every tier takes its colour and its rate from the matching Logistics cable
// so a line and the cable that feeds it read as the same tier, and every tier
// has a maximum span, which is what makes crossing a valley a job for several
// poles instead of one.
//
// The art is Create: Electro Energetics (MIT, George VI): its concrete pole
// segments and its crossarm insulator stacks, read out of the staged source at
// generate time rather than transcribed, over its own textures. Its wires are
// drawn in code upstream and have no model to take, and the hanging line here
// is the client's own lead rather than geometry of ours; a tier's colour is
// therefore on its spool rather than along the span.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { type Element, type Model, box, composed } from './compose';
import { type Rgba, bitmap, encodePng, fill, put, rect, rgb, tint } from './pixels';

/** Where Electro Energetics' own assets sit inside the staged source. */
const EE = 'ee/src/main/resources/assets/electroenergetics';

/** Our names for the two textures the poles are dressed in. */
const POLE = 'lunasmp:block/power_pole';
const HEAD = 'lunasmp:block/power_pole_head';
const SPOOL = 'lunasmp:block/power_spool';

type Window = [number, number, number, number];

/**
 * One rung of the wire ladder.
 *
 * The rate is the matching Logistics cable's own transfer rate read off its
 * config (500, 2000, 20000 and 100000 joules a tick) turned into joules a
 * second, so a line never becomes the bottleneck of the cable that feeds it
 * nor a way around it. The span is ours: it climbs with the tier, but even
 * the top of the ladder stops well short of a chunk, because the point of a
 * pole line is that distance costs poles.
 */
export interface WireTier {
	id: string;
	en: string;
	vi: string;
	/** The tier's own word, for the other things named after it. */
	grade: { en: string; vi: string };
	/** The accent colour Logistics paints the matching cable tier with. */
	colour: string;
	/** Joules a second the span carries. */
	rate: number;
	/** How far it may reach, in blocks, head to head. */
	span: number;
}

export const WIRE_TIERS: WireTier[] = [
	{
		id: 'basic',
		en: 'Basic Power Line',
		vi: 'Dây Điện Cơ Bản',
		grade: { en: 'Basic', vi: 'Cơ Bản' },
		colour: '#d50606',
		rate: 10_000,
		span: 12,
	},
	{
		id: 'advanced',
		en: 'Advanced Power Line',
		vi: 'Dây Điện Nâng Cao',
		grade: { en: 'Advanced', vi: 'Nâng Cao' },
		colour: '#ff5f1d',
		rate: 40_000,
		span: 20,
	},
	{
		id: 'elite',
		en: 'Elite Power Line',
		vi: 'Dây Điện Ưu Việt',
		grade: { en: 'Elite', vi: 'Ưu Việt' },
		colour: '#2fc60c',
		rate: 400_000,
		span: 28,
	},
	{
		id: 'ultimate',
		en: 'Ultimate Power Line',
		vi: 'Dây Điện Tối Thượng',
		grade: { en: 'Ultimate', vi: 'Tối Thượng' },
		colour: '#268fd8',
		rate: 2_000_000,
		// 32 rather than a rounder 36 for a reason that is not aesthetic: a
		// span is drawn as a lead hanging off a client-side entity, and the
		// client stops drawing an entity (and its lead) past
		// `boundingBox.getSize() * 64` blocks, which for the one this uses is
		// a little over 33. A longer span would lose its rope when you stood
		// at the far end of it.
		span: 32,
	},
];

/** What a pole part does: stand on the ground, gain height, or carry wires. */
export type PoleRole = 'base' | 'mast' | 'head';

export interface PolePart {
	id: string;
	en: string;
	vi: string;
	role: PoleRole;
}

export const POLE_PARTS: PolePart[] = [
	{
		id: 'power_pole_base',
		en: 'Power Pole Base',
		vi: 'Chân Trụ Điện',
		role: 'base',
	},
	{
		id: 'power_pole',
		en: 'Power Pole',
		vi: 'Trụ Điện',
		role: 'mast',
	},
	{
		id: 'power_pole_head',
		en: 'Power Pole Head',
		vi: 'Đầu Trụ Điện',
		role: 'head',
	},
];

/**
 * A wire cover: a cable of one tier poured into a solid block.
 *
 * A Logistics cable is a display entity over a hitbox, so a long run of it
 * costs the client an entity per block and never occludes anything behind
 * it. The cover is the same conductor as a reserved note block state: it
 * lights, culls and occludes like stone, chains with cables and covers of
 * its tier exactly as a cable does, and buries a line under a floor or a
 * road. One per tier, because a run's tier is what meters it.
 */
export interface WireCover {
	id: string;
	en: string;
	vi: string;
	tier: WireTier;
}

export const WIRE_COVERS: WireCover[] = WIRE_TIERS.map((tier) => ({
	id: `wire_cover_${tier.id}`,
	en: `${tier.grade.en} Wire Cover`,
	vi: `Hộp Dây Điện ${tier.grade.vi}`,
	tier,
}));

/**
 * How high above a head's own block a span is clamped, in blocks.
 *
 * Just under the insulator caps rather than on top of them, and the two
 * hundredths matter: the rope hangs off a pair of client-side entities placed
 * relative to this point, and at this height both of their bounding boxes
 * finish inside the head's own block. An anchor any higher leaves an
 * invisible box poking out of the crossarm, which would quietly swallow the
 * right-click that strings the next wire.
 */
export const TERMINAL_HEIGHT = 0.72;

interface SourceModel {
	textures?: Record<string, string>;
	elements: Element[];
}

/**
 * Elements out of one of Electro Energetics' own models, lifted by [dy] and
 * repainted onto one of our textures.
 *
 * Reading the source at generate time rather than transcribing its boxes is
 * what keeps the geometry honest: the uv windows come with it, and they are
 * windows onto the very texture we copy beside them, so the two cannot drift.
 */
function eeElements(sourcesDir: string, path: string, texture: string, dy = 0): Element[] {
	const file = join(sourcesDir, EE, 'models/block', `${path}.json`);
	const source = JSON.parse(readFileSync(file, 'utf8')) as SourceModel;

	return source.elements.map((element) => {
		const copy = JSON.parse(JSON.stringify(element)) as Record<string, unknown>;
		const from = copy.from as [number, number, number];
		const to = copy.to as [number, number, number];

		copy.from = [from[0], from[1] + dy, from[2]];
		copy.to = [to[0], to[1] + dy, to[2]];

		const rotation = copy.rotation as { origin: [number, number, number] } | undefined;

		if (rotation !== undefined) {
			rotation.origin = [rotation.origin[0], rotation.origin[1] + dy, rotation.origin[2]];
		}

		for (const face of Object.values(copy.faces as Record<string, Record<string, unknown>>)) {
			face.texture = texture;
		}

		return copy as Element;
	});
}

/**
 * A plain box wearing one window of a texture on all four flanks and the
 * cap window top and bottom, which is how the source dresses its own pole:
 * the sheet holds one tall sprite per segment kind and one plan view for the
 * cut ends.
 */
function shaft(
	from: [number, number, number],
	to: [number, number, number],
	texture: string,
	side: Window,
	cap: Window,
): Element {
	return {
		from,
		to,
		faces: {
			north: { uv: side, texture },
			east: { uv: side, texture },
			south: { uv: side, texture },
			west: { uv: side, texture },
			up: { uv: cap, texture },
			down: { uv: cap, texture },
		},
	};
}

/** The plan view of a cut pole, which the sheet keeps beside the segments. */
const POLE_CAP: Window = [4, 4, 8, 8];

/** The three tall sprites: the top of a pole, its foot, and the segment. */
const POLE_TOP: Window = [0, 0, 4, 8];
const POLE_FOOT: Window = [0, 8, 4, 16];

/**
 * The base: a cast plinth with the pole's own foot standing out of it.
 *
 * The plinth is ours - the source's pole is one width all the way down, and a
 * mast that just stops at the ground reads as sunk rather than founded. It
 * wears the bottom of the foot sprite, so it is the same concrete.
 */
function baseModel(): Model {
	const plinth: Window = [0, 14, 4, 16];

	return composed(
		[
			shaft([3, 0, 3], [13, 3, 13], POLE, plinth, POLE_CAP),
			shaft([4, 3, 4], [12, 16, 12], POLE, POLE_FOOT, POLE_CAP),
		],
		POLE,
	);
}

/** The mast: the source's own middle segment, cast slots and all. */
function mastModel(sourcesDir: string): Model {
	return composed(eeElements(sourcesDir, 'concrete_pole/block_middle', POLE), POLE);
}

/**
 * The head: the last stretch of pole with the crossarm on top of it.
 *
 * The arm is the source's triple connector - a full-width bar carrying three
 * insulator stacks - lifted onto the top of the block. Wires terminate on the
 * middle stack whichever way they leave, so a pole in the middle of a run
 * carries its line straight through instead of jogging sideways at every
 * span; the outer two are the spare ways a real arm has.
 */
function headModel(sourcesDir: string): Model {
	return composed(
		[
			shaft([4, 0, 4], [12, 9, 12], POLE, POLE_TOP, POLE_CAP),
			...eeElements(sourcesDir, 'triple_connector/block', HEAD, 9),
		],
		POLE,
	);
}

/**
 * The spool a builder strings a span with: a drum of wire between two steel
 * cheeks, wound in the tier's own colour so the four are told apart in the
 * hand as well as on the pole.
 */
function spoolModel(tier: WireTier): Model {
	const wire = `lunasmp:block/power_wire_${tier.id}`;
	const cheek: Window = [0, 0, 16, 16];

	return composed(
		[
			shaft([2, 2, 2], [14, 14, 4], SPOOL, cheek, cheek),
			shaft([2, 2, 12], [14, 14, 14], SPOOL, cheek, cheek),
			shaft([3, 3, 4], [13, 13, 12], wire, cheek, cheek),
			shaft([7, 7, 1], [9, 9, 15], SPOOL, cheek, cheek),
		],
		SPOOL,
	);
}

/**
 * A wire cover: one full cube wearing the cover sheet on all six faces, with
 * cull faces set, since a solid block that hides its covered faces is the
 * whole point of it.
 */
function coverModel(cover: WireCover): Model {
	const sheet = `lunasmp:block/${cover.id}`;

	return composed([box([0, 0, 0], [16, 16, 16], sheet, true)], sheet);
}

/** Every block model the power line needs, by the name it is written under. */
export function poleModels(sourcesDir: string): Record<string, Model> {
	const models: Record<string, Model> = {
		power_pole_base: baseModel(),
		power_pole: mastModel(sourcesDir),
		power_pole_head: headModel(sourcesDir),
	};

	for (const cover of WIRE_COVERS) {
		models[cover.id] = coverModel(cover);
	}

	return models;
}

/**
 * The item models: just the spool a player carries. A span itself is a vanilla
 * lead the client hangs, so it wears no model of ours.
 */
export function poleItemModels(): Record<string, Model> {
	const models: Record<string, Model> = {};

	for (const tier of WIRE_TIERS) {
		models[`power_wire_${tier.id}`] = spoolModel(tier);
	}

	return models;
}

/** A flat sheet of one colour: what a one-pixel wire samples. */
function flat(colour: Rgba): Uint8Array {
	const image = bitmap(16, 16);

	fill(image, colour);

	return encodePng(image);
}

/**
 * The spool's steel: a dark plate with a lighter band across it, so the
 * cheeks of the drum read as metal rather than as a hole.
 */
function spoolSheet(): Uint8Array {
	const image = bitmap(16, 16);

	fill(image, rgb('#3b4046'));
	rect(image, 0, 5, 15, 6, rgb('#4d545c'));
	rect(image, 0, 10, 15, 11, rgb('#2e3338'));

	return encodePng(image);
}

/**
 * The cover's sheet: the cable's own dark casing colours, a frame of the
 * tier's colour one pixel in from the edge, and the casing's faint grain
 * inside it. The frame is what reads: a run of covers draws a coloured
 * ribbon along every edge of the duct, so the tier is legible from any side
 * and a line stays traceable after it has gone into the floor.
 */
function coverSheet(cover: WireCover): Uint8Array {
	const image = bitmap(16, 16);
	const casing = rgb('#2a2626');
	const grain = rgb('#302a2a');
	const edge = rgb('#201d1d');

	fill(image, casing);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			if ((x + y * 3) % 7 === 0) {
				put(image, x, y, grain);
			}
		}
	}

	// rect's far corner is exclusive: each line here is one pixel wide
	rect(image, 0, 0, 16, 1, edge);
	rect(image, 0, 15, 16, 16, edge);
	rect(image, 0, 0, 1, 16, edge);
	rect(image, 15, 0, 16, 16, edge);

	const accent = rgb(cover.tier.colour);
	rect(image, 1, 1, 15, 2, accent);
	rect(image, 1, 14, 15, 15, accent);
	rect(image, 1, 1, 2, 15, accent);
	rect(image, 14, 1, 15, 15, accent);

	return encodePng(image);
}

/**
 * The textures the poles wear.
 *
 * Two are Electro Energetics' own, copied as they are because the uv windows
 * that came with its models are windows onto them; the rest is painted here.
 * A wire's sheet is one flat colour, three quarters of the tier's accent, so
 * a line reads as a dark conductor with its tier still legible at distance.
 */
export function poleSprites(sourcesDir: string): Record<string, Uint8Array> {
	const sprites: Record<string, Uint8Array> = {
		power_pole: new Uint8Array(readFileSync(join(sourcesDir, EE, 'textures/block/concrete_pole.png'))),
		power_pole_head: new Uint8Array(readFileSync(join(sourcesDir, EE, 'textures/block/connector.png'))),
		power_spool: spoolSheet(),
	};

	for (const tier of WIRE_TIERS) {
		sprites[`power_wire_${tier.id}`] = flat(tint(rgb(tier.colour), 0.75));
	}

	for (const cover of WIRE_COVERS) {
		sprites[cover.id] = coverSheet(cover);
	}

	return sprites;
}
