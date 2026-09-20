// The wireless lamps: a node that pushes power through the air to every
// fixture within a cube of blocks around it, and the two fixtures that light
// up inside that cube.
//
// A fixture is a block with no wire and no buffer: each second, every node
// pays for every fixture inside its reach out of its own charge, and a
// fixture that was paid for in the last couple of seconds is lit. So a room
// is lit by one node behind a wall, with nothing run to the lamps at all, and
// a node that runs dry darkens its whole room at once. The reach and the
// buffer grow with Simple Upgrades' range and energy upgrades, and the
// efficiency upgrade cuts what each fixture costs; that is the whole of the
// "upgradeable" in the brief, on the same upgrade items every Machines block
// already takes.
//
// Both fixtures mount on any of the six faces of a block, so the geometry is
// authored the way the small siren is: standing off the model's SOUTH wall
// and pointing NORTH, which `lineRotated` then lands on whichever face was
// clicked. The bulb is Engineer's Decor's bulb light (MIT, already ported as
// the iron bulb light) turned half round and retextured; the flat panel and
// the node are drawn here.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decodePng } from '/home/belikhun/luna-console/web/src/lib/server/imaging/png';
import { type Element, type Model, type Vec3, composed, part } from './compose';
import { type Bitmap, type Rgba, bitmap, encodePng, fill, get, put, rect, rgb, tint } from './pixels';

/** Where Engineer's Decor's own assets sit inside the staged source. */
const ED = 'engdecor/src/main/resources/assets/engineersdecor';

/** The instrument housing every network device here is cased in. */
const CASING = 'lunasmp:block/gauge_casing';

/** Our names for the sheets the fixtures wear. */
const FRAME = 'lunasmp:block/wireless_frame';
const COIL = 'lunasmp:block/wireless_coil';
const GLASS_ON = 'lunasmp:block/wireless_glass_on';
const GLASS_OFF = 'lunasmp:block/wireless_glass_off';
const PANEL_ON = 'lunasmp:block/wireless_panel_on';
const PANEL_OFF = 'lunasmp:block/wireless_panel_off';
const BLOCK_ON = 'lunasmp:block/wireless_block_on';
const BLOCK_OFF = 'lunasmp:block/wireless_block_off';
const EMITTER_ON = 'lunasmp:block/wireless_emitter_on';
const EMITTER_OFF = 'lunasmp:block/wireless_emitter_off';

/**
 * The shapes a fixture comes in. `street` is the odd one: those are furniture
 * pieces registered by the furniture catalog, which only borrow the wireless
 * tile - the generator derives them from every piece flagged `wireless`.
 */
export type LampKind = 'bulb' | 'panel' | 'block' | 'pole' | 'street';

export interface LampSpec {
	id: string;
	en: string;
	vi: string;
	kind: LampKind;
	/** Joules a second the fixture costs the node that lights it. */
	draw: number;
	/** The vanilla light level it throws into the block in front of it. */
	level: number;
}

/**
 * The fixtures.
 *
 * A panel costs more than a bulb because it throws more light, and the full
 * block costs twice the panel: it is the panel's own diffuser on all six
 * faces, and it lights the room from inside its own block rather than through
 * one face. The whole ladder sits well above the wired light panel's forty a
 * second, and that is the point of it - going wireless is the convenience,
 * and a lit room is meant to be a real load on a grid rather than a rounding
 * error against one furnace.
 */
export const LAMPS: LampSpec[] = [
	{
		id: 'wireless_bulb',
		en: 'Wireless Bulb',
		vi: 'Bóng Đèn Không Dây',
		kind: 'bulb',
		draw: 90,
		level: 14,
	},
	{
		id: 'wireless_light_panel',
		en: 'Wireless Light Panel',
		vi: 'Tấm Đèn Không Dây',
		kind: 'panel',
		draw: 150,
		level: 15,
	},
	{
		id: 'wireless_light_block',
		en: 'Wireless Light Block',
		vi: 'Khối Đèn Không Dây',
		kind: 'block',
		// the whole block is the fitting, and its backing block carries the
		// light level itself, so the level here is never read
		draw: 300,
		level: 15,
	},
	{
		id: 'wireless_light_pole',
		en: 'Wireless Light Pole',
		vi: 'Cột Đèn Không Dây',
		kind: 'pole',
		// a one-block industrial bollard for a yard or a path where there is
		// no wall to hang a bulb on; it lights the block above it
		draw: 120,
		level: 15,
	},
];

/** The switch's sheets: the plate, the rocker, the push button, and the little locator LED. */
const SWITCH_PLATE = 'lunasmp:block/wireless_switch_plate';
const SWITCH_ROCKER = 'lunasmp:block/wireless_switch_rocker';
const SWITCH_BUTTON = 'lunasmp:block/wireless_switch_button';
const LED_ON = 'lunasmp:block/wireless_switch_led_on';
const LED_OFF = 'lunasmp:block/wireless_switch_led_off';

/**
 * The furniture that runs off a node - the street lights, the lanterns, the
 * torches, the standing and table lamps, the ceiling lamp - is not listed
 * here. Each piece carries `wireless: true` in its own catalog and the
 * generator reads its light level and offset from there, so the fixture
 * table in LampCatalog.kt is derived, never transcribed. Kind `street` is
 * what those entries come out as.
 */

/**
 * The light switch: a modern rocker on a wall plate. It is not a fixture -
 * it draws nothing and lights nothing - but it lives with the lamps because
 * it toggles a lamp CHANNEL on every node whose reach covers it, which is
 * how a room's lights get one switch by the door.
 */
export const SWITCH = {
	id: 'wireless_light_switch',
	en: 'Light Switch',
	vi: 'Công Tắc Đèn',
};

/**
 * The push button: the same plate with a square button instead of a rocker.
 * A press toggles the channel exactly as the rocker does, but a button
 * carries no position, so its state is the locator LED under it; the press
 * itself is shown for a few ticks and springs back.
 */
export const BUTTON = {
	id: 'wireless_light_button',
	en: 'Light Button',
	vi: 'Nút Nhấn Đèn',
};

/** The node itself. */
export const NODE = {
	id: 'wireless_power_node',
	en: 'Wireless Power Node',
	vi: 'Trạm Điện Không Dây',
};

/**
 * How far a bare node reaches, in blocks each way from itself: a cube of
 * seventeen. Every range upgrade adds RANGE_STEP, so a fully upgraded node
 * reaches 28 each way - a cube of fifty-seven, a whole base rather than a room.
 */
export const NODE_RADIUS = 8;

/**
 * Blocks of reach one range upgrade buys, and how many upgrades fit.
 *
 * Simple Upgrades reads a block's own `upgrade_values` table from its config
 * before falling back to the global one, so the node ships a range ladder of
 * its own: 0, 2, 4 ... 20 rather than the global 0 ... 10. The upgrades GUI
 * shows the same table, so what a player is promised is what they get.
 */
export const RANGE_STEP = 2;
export const RANGE_LEVELS = 10;

/**
 * What radiating the field costs, in joules a second per block of radius,
 * before the efficiency upgrade divides it and on top of what the fixtures
 * draw. A bare node therefore idles at eighty a second - about one bulb - and
 * a fully extended one at two hundred and eighty, which is what makes the
 * reach knob in the menu worth turning down when a building does not need it.
 */
export const FIELD_DRAW_PER_BLOCK = 10;

/**
 * What a bare node holds, in joules: a little over a minute of a room's worth
 * of fixtures, so a generator that stutters does not flicker the lights. The
 * energy upgrade multiplies it, up to tenfold.
 */
export const NODE_CAPACITY = 10_000;

/**
 * The flat panel's frame plate and the glass set into it, in model pixels.
 *
 * These are shared with the sheet the glass wears, and that is the point.
 * `part` derives a face's uv from where its box sits - one texel per model
 * pixel, the vanilla convention - so the ten-pixel plate samples texels
 * 3..12 of its sixteen-pixel sheet and NOTHING ELSE. The first cut drew a
 * four-pixel grid across the whole sheet, and that window cut it off-centre:
 * one line hard against the top-left frame and a one-pixel sliver of a cell
 * in the far corner, which is the unevenness the panel shipped with. Art for
 * a part-sheet window has to be drawn for the window.
 */
const PANEL_FRAME_LOW = 2;
const PANEL_FRAME_HIGH = 14;
const PANEL_GLASS_LOW = 3;
const PANEL_GLASS_HIGH = 13;

interface SourceModel {
	elements: Element[];
}

/** Which face a half turn about the vertical swaps each face onto. */
const HALF_TURN: Record<string, string> = {
	north: 'south',
	south: 'north',
	east: 'west',
	west: 'east',
	up: 'up',
	down: 'down',
};

/**
 * Turns elements half round about the block's vertical axis.
 *
 * Engineer's Decor authors its wall pieces against the model's NORTH wall;
 * the six-way mounting here wants the fixture standing off the SOUTH wall
 * and pointing north, so the whole model turns once. A half turn is the one
 * rotation that keeps a face's own uv honest: north and south, east and west
 * simply trade places, and the two horizontal faces spin half round.
 */
function halfTurn(elements: Element[]): Element[] {
	return elements.map((element) => {
		const copy = JSON.parse(JSON.stringify(element)) as Record<string, unknown>;
		const from = copy.from as Vec3;
		const to = copy.to as Vec3;

		copy.from = [16 - to[0], from[1], 16 - to[2]];
		copy.to = [16 - from[0], to[1], 16 - from[2]];

		const rotation = copy.rotation as { origin: Vec3; axis: string; angle: number } | undefined;

		if (rotation !== undefined) {
			rotation.origin = [16 - rotation.origin[0], rotation.origin[1], 16 - rotation.origin[2]];

			if (rotation.axis !== 'y') {
				rotation.angle = -rotation.angle;
			}
		}

		const faces = copy.faces as Record<string, Record<string, unknown>>;
		const turned: Record<string, Record<string, unknown>> = {};

		for (const [dir, face] of Object.entries(faces)) {
			const out = { ...face };

			if (dir === 'up' || dir === 'down') {
				out.rotation = (((face.rotation as number | undefined) ?? 0) + 180) % 360;
			}

			turned[HALF_TURN[dir]!] = out;
		}

		copy.faces = turned;

		return copy as Element;
	});
}

/**
 * The bulb: Engineer's Decor's bulb light, a steel plate with a small caged
 * glass bulb standing off it, turned to stand off the south wall and wearing
 * the copper frame and our glass. Lit, the glass is drawn unshaded so it
 * reads as the source of the light rather than a thing lit by it.
 */
function bulbModel(sourcesDir: string, lit: boolean): Model {
	const file = join(sourcesDir, ED, 'models/block/light/bulb_light_model.json');
	const source = JSON.parse(readFileSync(file, 'utf8')) as SourceModel;
	const glass = lit ? GLASS_ON : GLASS_OFF;

	const elements = halfTurn(source.elements).map((element) => {
		let wearsGlass = false;

		for (const face of Object.values(element.faces as Record<string, Record<string, unknown>>)) {
			if (face.texture === '#light') {
				face.texture = glass;
				wearsGlass = true;
			} else {
				face.texture = FRAME;
			}
		}

		if (wearsGlass && lit) {
			element.shade = false;
		}

		return element;
	});

	return composed(elements, FRAME);
}

/**
 * The flat panel: a twelve-pixel plate against the wall with a ten-pixel
 * diffuser set into its face. One pixel of frame shows all round the glass.
 */
function panelModel(lit: boolean): Model {
	const glass = lit ? PANEL_ON : PANEL_OFF;

	return composed(
		[
			part([PANEL_FRAME_LOW, PANEL_FRAME_LOW, 15], [PANEL_FRAME_HIGH, PANEL_FRAME_HIGH, 16], FRAME),
			part(
				[PANEL_GLASS_LOW, PANEL_GLASS_LOW, 14.7],
				[PANEL_GLASS_HIGH, PANEL_GLASS_HIGH, 15],
				glass,
				{ shade: lit ? false : undefined },
			),
		],
		FRAME,
	);
}

/**
 * The full block: the panel's diffuser on all six faces of a whole block, so
 * one sunk into a floor or a ceiling sits flush with it and a run of them
 * reads as a lit surface rather than as a row of fittings.
 *
 * It needs no frame geometry, because its sheet carries its own one-pixel
 * copper border: that is what keeps the seam between two of them legible and
 * what tells it apart from the wired light panel, which is the same shape in
 * plain white. Every face is culled - a wall of these hides its own insides.
 */
function blockModel(lit: boolean): Model {
	const sheet = lit ? BLOCK_ON : BLOCK_OFF;

	return composed(
		[
			part([0, 0, 0], [16, 16, 16], sheet, {
				cull: ['north', 'east', 'south', 'west', 'up', 'down'],
				shade: lit ? false : undefined,
			}),
		],
		sheet,
	);
}

/**
 * The node: an instrument housing with a copper coil standing out of its
 * top and a glass emitter on the coil, which glows while the node holds any
 * charge. The housing is the same casing the meters and switches wear, so a
 * machinery wall reads as one family.
 */
function nodeModel(lit: boolean): Model {
	const emitter = lit ? EMITTER_ON : EMITTER_OFF;

	return composed(
		[
			part([1, 0, 1], [15, 9, 15], CASING),
			part([3, 9, 3], [13, 10, 13], FRAME),
			part([4, 10, 4], [12, 14, 12], { all: COIL, up: FRAME, down: FRAME }),
			part([5, 14, 5], [11, 16, 11], emitter, { shade: lit ? false : undefined }),
		],
		CASING,
	);
}

/**
 * The light pole: a plate on the floor, a short post, and a lamp head whose
 * glass band runs all the way round so it lights a path in every direction.
 * One block tall, which is what makes it a bollard rather than a street lamp.
 */
function poleModel(lit: boolean): Model {
	const glass = lit ? GLASS_ON : GLASS_OFF;

	return composed(
		[
			part([4, 0, 4], [12, 1, 12], FRAME),
			part([6.5, 1, 6.5], [9.5, 10, 9.5], CASING),
			part([3, 10, 3], [13, 11, 13], FRAME),
			part([3.5, 11, 3.5], [12.5, 14, 12.5], glass, { shade: lit ? false : undefined }),
			part([3, 14, 3], [13, 16, 13], CASING),
		],
		CASING,
	);
}

/**
 * The wall plate every switch here hangs on: a flush square, a little over
 * half a pixel thick, authored on the model's SOUTH wall the way every flat
 * fitting is, so the furniture rotation lands it on the wall the placer
 * clicked. The look is the modern flat-plate switch: a square plate the
 * size of a hand, a wide control that nearly fills it, matte white, and a
 * locator LED under the control that shows the channel's state from across
 * the room. Drawn here: the one mod found with a wall switch keeps its
 * assets all rights reserved.
 */
function switchPlate(on: boolean): Element[] {
	return [
		part([4, 4, 15.4], [12, 12, 16], SWITCH_PLATE),
		part([7.25, 4.5, 15.1], [8.75, 5.1, 15.4], on ? LED_ON : LED_OFF, { shade: on ? false : undefined, only: ['north', 'up', 'down', 'east', 'west'] }),
	];
}

/**
 * The rocker: six by six and thin, tilting on its own middle so one edge
 * sits into the plate and the other stands a pixel out. Top pressed in is
 * ON, bottom pressed in is OFF, the convention of a modern switch.
 */
function switchModel(on: boolean): Model {
	const rocker: Element = part([5, 5.4, 14.8], [11, 11.4, 15.4], SWITCH_ROCKER);

	rocker.rotation = { origin: [8, 8.4, 15.1], axis: 'x', angle: on ? -22.5 : 22.5 };

	return composed([...switchPlate(on), rocker], SWITCH_PLATE);
}

/** The push button: a square standing a pixel proud of the plate, or flush while it is pressed. */
function buttonModel(on: boolean, pressed: boolean): Model {
	const front = pressed ? 15.0 : 14.4;
	const button = part([6, 6.4, front], [10, 10.4, 15.4], SWITCH_BUTTON);

	return composed([...switchPlate(on), button], SWITCH_PLATE);
}

/** Every block model the wireless lamps need, by the name it is written under. */
export function lampModels(sourcesDir: string): Record<string, Model> {
	const models: Record<string, Model> = {
		[NODE.id]: nodeModel(true),
		[`${NODE.id}_off`]: nodeModel(false),
		[SWITCH.id]: switchModel(true),
		[`${SWITCH.id}_off`]: switchModel(false),
		[BUTTON.id]: buttonModel(true, false),
		[`${BUTTON.id}_off`]: buttonModel(false, false),
		[`${BUTTON.id}_pressed`]: buttonModel(true, true),
		[`${BUTTON.id}_pressed_off`]: buttonModel(false, true),
	};

	for (const lamp of LAMPS) {
		const build = (lit: boolean): Model => {
			switch (lamp.kind) {
				case 'bulb':
					return bulbModel(sourcesDir, lit);
				case 'block':
					return blockModel(lit);
				case 'pole':
					return poleModel(lit);
				default:
					return panelModel(lit);
			}
		};

		models[lamp.id] = build(true);
		models[`${lamp.id}_off`] = build(false);
	}

	return models;
}

/** A sheet run pixel by pixel through a colour function. */
function recolour(bytes: Uint8Array, paint: (colour: Rgba) => Rgba): Uint8Array {
	const image = decodePng(bytes);

	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			put(image, x, y, paint(get(image, x, y)));
		}
	}

	return encodePng(image);
}

/** Steel pushed toward copper: warmer, darker in the blue. */
function copper(colour: Rgba): Rgba {
	return [
		Math.min(255, Math.round(colour[0] * 1.35)),
		Math.min(255, Math.round(colour[1] * 0.88)),
		Math.min(255, Math.round(colour[2] * 0.6)),
		colour[3],
	];
}

/** The lit glass seen dark: a third of its brightness, alpha untouched. */
function unlit(colour: Rgba): Rgba {
	return tint(colour, 0.32);
}

/** A diffuser's two colours: the panes, and the gutter between them. */
function diffuser(lit: boolean): { ground: Rgba; gutter: Rgba } {
	return {
		ground: lit ? rgb('#fff4d8') : rgb('#474b51'),
		gutter: lit ? rgb('#ecd7a8') : rgb('#383c41'),
	};
}

/**
 * A cross of gutters centred in a window, which is the whole of what makes a
 * diffuser read as panes rather than as a blank rectangle.
 *
 * Both fixtures share this because both got it wrong once, in the same way:
 * the flat panel's first sheet drew its grid across the whole sixteen-pixel
 * sheet while its plate only samples ten of them, so the lines came out hard
 * against one edge with a sliver of a pane at the other. The window is the
 * argument here, and the arithmetic is done from it.
 *
 * The window must be an even number of pixels. The gutter is two wide so it
 * can straddle the exact centre; an odd window would put it half a pixel off
 * and leave one pane bigger than the other three, so it throws rather than
 * quietly drawing something lopsided.
 */
function paneCross(image: Bitmap, low: number, high: number, gutter: Rgba): void {
	if ((high - low) % 2 !== 0) {
		throw new Error(`a diffuser window must be an even number of pixels: ${low}..${high}`);
	}

	const middle = (low + high) / 2;

	rect(image, low, middle - 1, high, middle + 1, gutter);
	rect(image, middle - 1, low, middle + 1, high, gutter);
}

/**
 * The flat panel's diffuser, drawn for the ten-pixel window its glass plate
 * samples: four panes of four pixels square behind a centred cross. The
 * frame around it is real geometry, so the sheet draws none.
 */
function panelSheet(lit: boolean): Uint8Array {
	const image = bitmap(16, 16);
	const { ground, gutter } = diffuser(lit);

	// the whole sheet takes the ground colour, not just the window: a clear
	// pixel outside it would read as a hole the day the plate grows
	fill(image, ground);
	paneCross(image, PANEL_GLASS_LOW, PANEL_GLASS_HIGH, gutter);

	return encodePng(image);
}

/**
 * The full block's diffuser, drawn for the whole sheet, because a full cube's
 * faces sample all of it: a one-pixel copper border, then four panes of six
 * pixels square behind the same centred cross.
 *
 * The border is painted rather than built, since the block has no frame
 * geometry of its own - it is what keeps the joint between two of them
 * visible when a floor is paved with them.
 */
function blockSheet(lit: boolean): Uint8Array {
	const image = bitmap(16, 16);
	const { ground, gutter } = diffuser(lit);

	fill(image, rgb('#8a4a22'));
	rect(image, 1, 1, 15, 15, ground);
	paneCross(image, 1, 15, gutter);

	return encodePng(image);
}

/** Copper windings: four-pixel bands of highlight, body and shadow. */
function coilSheet(): Uint8Array {
	const image = bitmap(16, 16);
	const bands = ['#d98045', '#c2652e', '#a6542a', '#7e3e1e'];

	for (let y = 0; y < 16; y++) {
		rect(image, 0, y, 16, y + 1, rgb(bands[y % 4]!));
	}

	return encodePng(image);
}

/** The emitter glass: a pale cyan dome with a white heart, or the same dome dark. */
function emitterSheet(lit: boolean): Uint8Array {
	const image = bitmap(16, 16);

	if (lit) {
		fill(image, rgb('#8fd8ea'));
		rect(image, 2, 2, 14, 14, rgb('#c8f4ff'));
		rect(image, 5, 5, 11, 11, rgb('#ffffff'));
	} else {
		fill(image, rgb('#243a44'));
		rect(image, 2, 2, 14, 14, rgb('#2f4a55'));
		rect(image, 5, 5, 11, 11, rgb('#3a5a66'));
	}

	return encodePng(image);
}

/**
 * The textures the wireless lamps wear. The glass is Engineer's Decor's own
 * warm lamp glass, copied lit and painted down for the dark state; the frame
 * is its steel pushed to copper, so the wireless fixtures are told apart from
 * the wired iron ones at a glance. The rest is drawn here.
 */
export function lampSprites(sourcesDir: string): Record<string, Uint8Array> {
	const textures = join(sourcesDir, ED, 'textures/block');
	const steel = new Uint8Array(readFileSync(join(textures, 'material/steel_texture.png')));
	const glass = new Uint8Array(readFileSync(join(textures, 'light/lamp_glass_warm_square_texture.png')));

	return {
		wireless_frame: recolour(steel, copper),
		wireless_glass_on: glass,
		wireless_glass_off: recolour(glass, unlit),
		wireless_panel_on: panelSheet(true),
		wireless_panel_off: panelSheet(false),
		wireless_block_on: blockSheet(true),
		wireless_block_off: blockSheet(false),
		wireless_coil: coilSheet(),
		wireless_emitter_on: emitterSheet(true),
		wireless_emitter_off: emitterSheet(false),
		wireless_switch_plate: framedSheet(rgb('#f4f2ec'), rgb('#d7d3cb'), 4, 12),
		wireless_switch_rocker: framedSheet(rgb('#f7f6f2'), rgb('#e3e0d9'), 5, 11),
		wireless_switch_button: framedSheet(rgb('#f7f6f2'), rgb('#e3e0d9'), 6, 10),
		wireless_switch_led_on: flatSheet(rgb('#9cf07a'), rgb('#6fd44a')),
		wireless_switch_led_off: flatSheet(rgb('#3a3f3a'), rgb('#2a2e2a')),
	};
}

/**
 * A matte plastic sheet with a one-pixel frame drawn where a box between
 * `lo` and `hi` samples it: a face takes the pixels under its own footprint,
 * so a rim at the sheet's edge would never be seen on an eight-pixel plate.
 */
function framedSheet(ground: Rgba, frame: Rgba, lo: number, hi: number): Uint8Array {
	const image = bitmap(16, 16);

	fill(image, ground);
	rect(image, lo, lo, hi, lo + 1, frame);
	rect(image, lo, hi - 1, hi, hi, frame);
	rect(image, lo, lo, lo + 1, hi, frame);
	rect(image, hi - 1, lo, hi, hi, frame);

	return encodePng(image);
}

/** A plain plastic sheet: one colour with a one-pixel darker rim, the look of a moulded plate. */
function flatSheet(ground: Rgba, rim: Rgba): Uint8Array {
	const image = bitmap(16, 16);

	fill(image, rim);
	rect(image, 1, 1, 15, 15, ground);

	return encodePng(image);
}
