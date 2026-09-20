// The medieval wave: castle and village pieces ported from four sources.
//
// - Decorative Blocks (lilypuree, MIT), already staged for the brazier and the
//   chandelier: palisades, supports, seats, beams, stone pillars, the bar
//   panel, the bonfires, the step ladder, the rope coil and rocky dirt.
// - Rustic (cadaverous_queen, MIT, read from the 1.20.1 "Rustic Renaissance"
//   jar the author co-published): lanterns, iron torches, candles, candle
//   chandeliers, gold and silver chains, the gargoyle, the barrel, the
//   cabinet, the apiary, the beehive, the brewing barrel, the crushing tub,
//   the vases, painted wood, clay walls, slate and stone columns.
// - Builder's Bounty (Diesse, CC0-1.0): the study and tavern clutter - stacks
//   of books and papers, candlesticks, a globe, ink and quill, potion bottles,
//   the scarecrow, a trophy and a pile of bones.
// - Aesthetic Frames (Alminoris, MIT): timber framing, the plaster-and-beam
//   wall of every half-timbered house. Its textures are Fusion connecting
//   strips; the first tile of each strip is the frame drawn complete, and that
//   tile is what becomes a cube here (see `frameSprites`).
//
// Every source model is authored facing north or is symmetric, except where a
// piece says otherwise. Nothing here runs on the server.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { type Bitmap, bitmap, decodePng, encodePng } from '/home/belikhun/luna-console/web/src/lib/server/imaging/png';
import { type Element, type Face, type Vec3, DISPLAY, composed, part } from './compose';
import { COLORS, COLOR_EN, COLOR_VI, WOODS, WOOD_EN, WOOD_VI, type Behaviour, type Piece, type Wood } from './catalog';

const DB = 'decorative_blocks:block';
const RUSTIC = 'rustic:block';
const BOUNTY = 'bbounty:item';

const WOODEN: Behaviour = { hardness: 1.0, tool: 'axe' };
const STONE: Behaviour = { hardness: 1.5, tool: 'pickaxe' };

/** A full cube on a reserved note block state: one texture on every face. */
function cube(id: string, texture: string, en: string, vi: string, behaviour: Behaviour): Piece {
	return {
		id,
		built: {
			textures: { t0: texture, particle: texture },
			elements: [part([0, 0, 0], [16, 16, 16], '#t0', {
				cull: ['north', 'east', 'south', 'west', 'up', 'down'],
			})],
			display: DISPLAY,
		},
		en,
		vi,
		cube: true,
		...behaviour,
	};
}

/** A full cube with its own top and bottom: a beam, a coil, a column. */
function column(id: string, side: string, end: string, en: string, vi: string, behaviour: Behaviour): Piece {
	return {
		id,
		built: {
			textures: { t0: side, t1: end, particle: side },
			elements: [part([0, 0, 0], [16, 16, 16], { all: '#t0', up: '#t1', down: '#t1' }, {
				cull: ['north', 'east', 'south', 'west', 'up', 'down'],
			})],
			display: DISPLAY,
		},
		en,
		vi,
		cube: true,
		...behaviour,
	};
}

// ---- Decorative Blocks ---------------------------------------------------

/**
 * A palisade is a fence of sharpened logs. The source's inventory model is the
 * full straight run authored east-west, which is the orientation the connect
 * cutter wants, so runs, corners and crossings come out of the same machinery
 * the trellis uses; a palisade only joins other palisades.
 */
const PALISADES: Piece[] = WOODS.filter((wood) => wood !== 'pale_oak').map((wood): Piece => ({
	id: `${wood}_palisade`,
	models: [`${DB}/${wood}_palisade_inventory`],
	en: `${WOOD_EN[wood]} Palisade`,
	vi: `Hàng Rào Cọc ${WOOD_VI[wood]}`,
	connects: 'palisade',
	collider: 'pane',
	...WOODEN,
}));

/**
 * The support is a corner brace: a post against the wall behind it, a beam
 * along the ceiling and a diagonal between them. The source model stands the
 * post on the south edge, which is the wall a placer faces, and the upside
 * down one hangs the same brace from the block above.
 */
const SUPPORTS: Piece[] = WOODS.filter((wood) => wood !== 'pale_oak').flatMap((wood): Piece[] => [
	{
		id: `${wood}_support`,
		models: [`${DB}/${wood}_support_inventory`],
		en: `${WOOD_EN[wood]} Support`,
		vi: `Giá Đỡ ${WOOD_VI[wood]}`,
		directional: true,
		collider: 'small',
		...WOODEN,
	},
	{
		id: `${wood}_hanging_support`,
		models: [`${DB}/${wood}_upside_down_support_inventory`],
		en: `Upside-Down ${WOOD_EN[wood]} Support`,
		vi: `Giá Đỡ Ngược ${WOOD_VI[wood]}`,
		directional: true,
		collider: 'small',
		...WOODEN,
	},
]);

/** A low slab of wood to sit on: a stool without legs, three pixels thick. */
const SEATS: Piece[] = WOODS.filter((wood) => wood !== 'pale_oak').map((wood): Piece => ({
	id: `${wood}_seat`,
	models: [`${DB}/${wood}_seat`],
	en: `${WOOD_EN[wood]} Seat`,
	vi: `Ghế Đôn ${WOOD_VI[wood]}`,
	seatHeight: 0.45,
	directional: true,
	collider: 'pedestal',
	...WOODEN,
}));

/** A squared timber beam, the full block, standing on end (the source has no bamboo beam). */
const BEAMS: Piece[] = WOODS.filter((wood) => wood !== 'pale_oak' && wood !== 'bamboo').map((wood): Piece =>
	column(`${wood}_beam`, `${DB}/${wood}_beam_side`, `${DB}/${wood}_beam_end`, `${WOOD_EN[wood]} Beam`, `Dầm ${WOOD_VI[wood]}`, WOODEN),
);

const PILLAR_STONES: Array<[string, string, string]> = [
	['stone', 'Stone', 'Đá'],
	['smooth_stone', 'Smooth Stone', 'Đá Nhẵn'],
	['sandstone', 'Sandstone', 'Sa Thạch'],
	['red_sandstone', 'Red Sandstone', 'Sa Thạch Đỏ'],
	['blackstone', 'Blackstone', 'Đá Đen'],
	['basalt', 'Basalt', 'Bazan'],
	['tuff', 'Tuff', 'Đá Tuff'],
	['mud', 'Mud', 'Bùn'],
];

/** A round-ish stone pillar, twelve pixels across; a barrier is its box. */
const PILLARS: Piece[] = PILLAR_STONES.map(([key, en, vi]): Piece => ({
	id: `${key}_pillar`,
	models: [`${DB}/${key}_pillar`],
	en: `${en} Pillar`,
	vi: `Cột ${vi}`,
	collider: 'solid',
	...STONE,
}));

const DECORATIVE_SINGLES: Piece[] = [
	// a trapdoor of iron bars: the closed leaf lies on the floor, the open one
	// stands on the south edge exactly where vanilla's own trapdoor swings
	// to, so the borrowed trapdoor's prediction matches the toggle
	{
		id: 'iron_bar_panel',
		models: [`${DB}/bar_panel_bottom`],
		openable: { models: [`${DB}/bar_panel_open`], sound: 'door' },
		topLift: 13,
		en: 'Iron Bar Panel',
		vi: 'Tấm Song Sắt',
		directional: true,
		collider: 'trapdoor',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	// the bonfire spreads past its block on every side: one block of logs
	// with a flame two blocks tall, drawn from an animated sheet
	{
		id: 'bonfire',
		models: [`${DB}/bonfire`],
		en: 'Bonfire',
		vi: 'Lửa Trại Lớn',
		light: { offset: 1, level: 15 },
		collider: 'small',
		hardness: 1.0,
		tool: 'axe',
	},
	{
		id: 'soul_bonfire',
		models: [`${DB}/soul_bonfire`],
		en: 'Soul Bonfire',
		vi: 'Lửa Trại Linh Hồn',
		light: { offset: 1, level: 10 },
		collider: 'small',
		hardness: 1.0,
		tool: 'axe',
	},
	{
		id: 'step_ladder',
		models: [`${DB}/step_ladder`],
		en: 'Step Ladder',
		vi: 'Thang Gấp',
		directional: true,
		collider: 'small',
		...WOODEN,
	},
	column('rope_coil', `${DB}/rope_coil_side`, `${DB}/rope_coil_end`, 'Rope Coil', 'Cuộn Thừng', { hardness: 0.5, tool: 'axe' }),
	cube('rocky_dirt', `${DB}/rocky_dirt`, 'Rocky Dirt', 'Đất Sỏi', { hardness: 0.6, tool: 'shovel' }),
];

// ---- Rustic ---------------------------------------------------------------
//
// The 1.20.1 jar keeps three habits of its 1.12 origins that the generator
// refuses: faces naming a `#0` no model defines (the candle, the chandeliers,
// the chains), texture paths in the old `minecraft:blocks/...` layout (the
// lanterns' glass and flame, the barrel's planks), and `block/cross` parents
// with no elements of their own (the gold, silver and double candles). The
// first two are filled per piece through `textures`; the crossed candles are
// composed here out of the same sprite the source draws them from.

/**
 * A lantern's glass and flame, which the source names in the 1.12 layout, and
 * its particle, which the merge would otherwise take from that same glass.
 */
function lanternTextures(suffix: string): Record<string, string> {
	return {
		'2': 'minecraft:block/glass',
		'3': 'minecraft:block/torch',
		particle: `${RUSTIC}/lantern_side${suffix}`,
	};
}

const METALS: Array<[string, string, string, string]> = [
	['iron', '', 'Iron', 'Sắt'],
	['gold', '_gold', 'Gold', 'Vàng'],
	['silver', '_silver', 'Silver', 'Bạc'],
];

/**
 * Three mountings per lantern, each its own block: standing lights the block
 * above it, hanging lights the block below, and the wall bracket is a flat
 * piece against the wall the placer faces.
 */
const LANTERNS: Piece[] = METALS.flatMap(([key, suffix, en, vi]): Piece[] => [
	{
		id: `${key}_lantern`,
		wireless: true,
		models: [`${RUSTIC}/lantern_up${suffix}`],
		textures: lanternTextures(suffix),
		en: `${en} Lantern`,
		vi: `Đèn Lồng ${vi}`,
		light: { offset: 1, level: 14 },
		collider: 'small',
		...STONE,
	},
	{
		id: `hanging_${key}_lantern`,
		wireless: true,
		models: [`${RUSTIC}/lantern_down${suffix}`],
		textures: lanternTextures(suffix),
		en: `Hanging ${en} Lantern`,
		vi: `Đèn Lồng ${vi} Treo`,
		light: { offset: -1, level: 14 },
		collider: 'small',
		...STONE,
	},
	{
		id: `${key}_wall_lantern`,
		wireless: true,
		models: [`${RUSTIC}/lantern_wall${suffix}`],
		textures: lanternTextures(suffix),
		en: `${en} Wall Lantern`,
		vi: `Đèn Lồng ${vi} Gắn Tường`,
		light: { offset: 1, level: 14 },
		directional: true,
		collider: 'flat',
		...STONE,
	},
]);

/**
 * The wrought hoops: a ring at the foot of the block with four chains rising
 * out of it, which is what the source draws - there are no candles in the
 * model, so there are none in the name either. Decorative Blocks' chandelier,
 * already in the catalog, is the one with flames.
 */
const CHANDELIERS: Piece[] = METALS.map(([key, suffix, en, vi]): Piece => ({
	id: `${key}_chandelier`,
	models: [`${RUSTIC}/chandelier${suffix}`],
	// the source leaves a `#0` slot its own loader never fills; every face
	// using it is zero-area, so it only has to resolve to something real
	textures: { '0': `${RUSTIC}/chandelier${suffix}` },
	en: `${en} Chandelier`,
	vi: `Đèn Chùm ${vi}`,
	light: { offset: -1, level: 15 },
	collider: 'small',
	...STONE,
}));

/**
 * The candles are one model wearing three atlases.
 *
 * Only the iron candle has a model in the 1.20.1 source: its gold, silver and
 * double siblings are `block/cross` shells, and their texture is not a
 * billboard but an atlas laid out for that model - the flame in one corner,
 * the wax and the base in bands along the bottom - so drawing it as crossed
 * planes smears the whole sheet across two quads. Every window the model
 * samples lies in the band the three sheets share, so the gold and silver
 * candles are that model with the texture swapped. The doubles are not here:
 * their sheet carries a second candle drawn for a model the port dropped, and
 * a second candle cannot be recovered from a sheet alone.
 */
function candle(id: string, texture: string, en: string, vi: string): Piece {
	const sheet = `${RUSTIC}/${texture}`;

	return {
		id,
		models: [`${RUSTIC}/candle`],
		textures: { candle: sheet, '0': sheet, particle: sheet },
		en,
		vi,
		light: { offset: 1, level: 6 },
		collider: 'small',
		hardness: 0.5,
		tool: 'pickaxe',
	};
}

const CANDLES: Piece[] = [
	candle('iron_candle', 'candle', 'Iron Candle', 'Nến Chân Sắt'),
	candle('gold_candle', 'candle_gold', 'Gold Candle', 'Nến Chân Vàng'),
	candle('silver_candle', 'candle_silver', 'Silver Candle', 'Nến Chân Bạc'),
];

/** Vanilla has the iron chain; these are the gold and silver ones beside it. */
const CHAINS: Piece[] = METALS.filter(([key]) => key !== 'iron').map(([key, suffix, en, vi]): Piece => ({
	id: `${key}_chain`,
	models: [`${RUSTIC}/chain${suffix}`],
	textures: { '0': `${RUSTIC}/chain${suffix}` },
	en: `${en} Chain`,
	vi: `Xích ${vi}`,
	collider: 'small',
	hardness: 1.0,
	tool: 'pickaxe',
}));

const VASE_GLAZES: Array<[string, string, string]> = [
	['clay', 'Clay Vase', 'Bình Gốm Đất'],
	['patterned', 'Patterned Vase', 'Bình Gốm Hoa Văn'],
	['striped', 'Blue Striped Vase', 'Bình Gốm Sọc Xanh'],
	['jade', 'Jade Vase', 'Bình Gốm Ngọc'],
	['dark', 'Dark Vase', 'Bình Gốm Đen'],
	['painted', 'Painted Vase', 'Bình Gốm Vẽ Ngựa'],
];

/**
 * The vases, the iron barrel and the crushing tub, rebuilt as geometry.
 *
 * All three are round, and their source paints them the way a 1.12 mod could:
 * one side-on sprite on a full cube, the silhouette carried in the sprite's
 * alpha. On a cube that is a hole - you see the drawing on the near faces and
 * the sky through the gaps - which is what these blocks looked like when the
 * wave first shipped. A sprite drawn side-on to a round object is a lathe
 * profile, so the shape is read back out of it here: each run of sprite rows
 * sharing a width becomes one box, and the sprite maps onto those boxes the
 * same way it mapped onto the cube. Nothing is guessed; the profile is
 * measured off the pixels every time the generator runs.
 */
type Window = [number, number, number, number];

/** A run of sprite rows sharing an opaque span: one step of the lathe. */
interface Ring {
	/** First sprite row of the run, counting down from the top. */
	top: number;
	/** Row after the run. */
	bottom: number;
	x1: number;
	x2: number;
}

function rusticSprite(sourcesDir: string, name: string): Bitmap {
	const file = join(sourcesDir, 'rustic/assets/rustic/textures/block', `${name}.png`);

	return decodePng(new Uint8Array(readFileSync(file)));
}

/** Where a sprite has pixels at all, as a uv window. */
function opaqueBox(image: Bitmap): Window {
	let x1 = image.width;
	let y1 = image.height;
	let x2 = 0;
	let y2 = 0;

	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			if (image.data[(y * image.width + x) * 4 + 3]! >= 16) {
				x1 = Math.min(x1, x);
				y1 = Math.min(y1, y);
				x2 = Math.max(x2, x + 1);
				y2 = Math.max(y2, y + 1);
			}
		}
	}

	const scale = 16 / image.width;

	return [x1 * scale, y1 * scale, x2 * scale, y2 * scale];
}

/** The profile of a side-on sprite: its opaque span, run by run of rows. */
function silhouette(image: Bitmap): Ring[] {
	const rings: Ring[] = [];

	for (let y = 0; y < image.height; y++) {
		let lo = -1;
		let hi = -1;

		for (let x = 0; x < image.width; x++) {
			if (image.data[(y * image.width + x) * 4 + 3]! >= 16) {
				if (lo < 0) {
					lo = x;
				}

				hi = x + 1;
			}
		}

		if (lo < 0) {
			continue;
		}

		const last = rings[rings.length - 1];

		if (last && last.bottom === y && last.x1 === lo && last.x2 === hi) {
			last.bottom = y + 1;
			continue;
		}

		rings.push({ top: y, bottom: y + 1, x1: lo, x2: hi });
	}

	return rings;
}

/** A box whose every drawn face names its own window of one sprite. */
function panel(from: Vec3, to: Vec3, texture: string, faces: Partial<Record<Face, Window>>): Element {
	const drawn: Record<string, unknown> = {};

	for (const [dir, uv] of Object.entries(faces)) {
		drawn[dir] = { uv, texture };
	}

	return { from, to, faces: drawn };
}

/**
 * Turns a side-on sprite into a stack of boxes following its silhouette.
 *
 * A box gets a top face only where nothing sits on it and a bottom face only
 * where it does not sit on something, so the rings between steps are drawn
 * once and no two quads land on the same plane. Where the source ships a plan
 * view (the vase's mouth, the barrel's lid) those faces take the whole of its
 * drawing, since a plan view is drawn for the top of the object rather than
 * for one step of it.
 */
function lathe(sourcesDir: string, side: string, caps: { top?: string; bottom?: string } = {}): Model {
	const sprite = rusticSprite(sourcesDir, side);
	const rings = silhouette(sprite);

	if (rings.length === 0) {
		throw new Error(`sprite ${side} is empty`);
	}

	const sideRef = `${RUSTIC}/${side}`;
	const topRef = caps.top ? `${RUSTIC}/${caps.top}` : sideRef;
	const bottomRef = caps.bottom ? `${RUSTIC}/${caps.bottom}` : sideRef;
	const topWindow = caps.top ? opaqueBox(rusticSprite(sourcesDir, caps.top)) : undefined;
	const bottomWindow = caps.bottom ? opaqueBox(rusticSprite(sourcesDir, caps.bottom)) : undefined;

	// the sprite reads top-down and the model is built bottom-up
	const stack = [...rings].reverse();
	const elements: Element[] = [];

	stack.forEach((ring, index) => {
		const width = ring.x2 - ring.x1;
		const below = stack[index - 1];
		const above = stack[index + 1];
		const from: Vec3 = [ring.x1, 16 - ring.bottom, ring.x1];
		const to: Vec3 = [ring.x2, 16 - ring.top, ring.x2];
		const flank: Window = [ring.x1, ring.top, ring.x2, ring.bottom];
		const faces: Partial<Record<Face, Window>> = {
			north: [16 - ring.x2, ring.top, 16 - ring.x1, ring.bottom],
			east: [16 - ring.x2, ring.top, 16 - ring.x1, ring.bottom],
			south: flank,
			west: flank,
		};

		elements.push(panel(from, to, sideRef, faces));

		if (above === undefined || width > above.x2 - above.x1) {
			elements.push(panel(from, to, topRef, { up: topWindow ?? [ring.x1, ring.top, ring.x2, ring.top + width] }));
		}

		if (below === undefined || width > below.x2 - below.x1) {
			elements.push(panel(from, to, bottomRef, { down: bottomWindow ?? [ring.x1, ring.bottom - width, ring.x2, ring.bottom] }));
		}
	});

	return composed(elements, sideRef);
}

/**
 * The crushing tub, opened out.
 *
 * Its sprite is a nine-pixel band of hooped staves at the bottom of an
 * otherwise empty tile, so a lathe of it is a closed box and the source's own
 * cube floats a half-transparent square where the tub has no lid at all. It
 * is a vat, so it is built as one: four hooped walls around a floor, with the
 * surfaces the sprite never draws taking its plain staves.
 */
function crushingTub(): Model {
	// our own sheet, not Rustic's directly: see tubSprite - the source's top
	// seven rows are transparent, and the atlas mipmaps bled that into every
	// face sampled near them, which is what made the rim and the bowl look
	// broken in game while the renderer (no mipmaps) showed them whole
	const tub = 'lunasmp:block/crushing_tub';
	// the sheet's bands: seven rows of plain stave (ours), then the source's
	// stave tops, the top hoop, three rows of stave, a second hoop, one more
	const outer = (u1: number, u2: number): Window => [u1, 7, u2, 16];
	// the inside of a tub is bare staves, no hoops: the plain rows, one and a
	// bit stretched onto the nine-pixel wall
	const inner = (u1: number, u2: number): Window => [u1, 0, u2, 7];
	const wood: Window = [0, 0, 16, 7];
	// the rim is one hoop row and nothing else, on every wall: a sixteen-wide
	// window squeezed onto the two-pixel top of a short wall came out as
	// stripes, and a plain iron band is what a tub has there anyway
	const rimLong: Window = [0, 8, 16, 9];
	const rimShort: Window = [0, 8, 2, 9];

	return composed([
		panel([0, 0, 0], [16, 2, 16], tub, { down: wood }),
		// the floor of the bowl is planking, not a stretched strip of stave
		{
			from: [2, 1.98, 2],
			to: [14, 2, 14],
			faces: { up: { uv: [2, 2, 14, 14], texture: 'minecraft:block/oak_planks' } },
		},
		panel([0, 0, 0], [16, 9, 2], tub, { north: outer(0, 16), south: inner(0, 16), up: rimLong, west: outer(0, 2), east: outer(14, 16) }),
		panel([0, 0, 14], [16, 9, 16], tub, { south: outer(0, 16), north: inner(0, 16), up: rimLong, west: outer(14, 16), east: outer(0, 2) }),
		panel([0, 0, 2], [2, 9, 14], tub, { west: outer(2, 14), east: inner(2, 14), up: rimShort }),
		panel([14, 0, 2], [16, 9, 14], tub, { east: outer(2, 14), west: inner(2, 14), up: rimShort }),
	], tub);
}

/** The pieces whose shape is read out of a sprite rather than a source model. */
function turnedPieces(sourcesDir: string): Piece[] {
	return [
		...VASE_GLAZES.map(([key, en, vi], index): Piece => ({
			id: `${key}_vase`,
			built: lathe(sourcesDir, `vase_side_${index}`, { top: `vase_top_${index}`, bottom: `vase_bottom_${index}` }),
			en,
			vi,
			// the vase stands the full height of its block, so the barrier is
			// the closest of the shapes a Nova block can borrow
			collider: 'solid',
			hardness: 0.8,
			tool: 'pickaxe',
		})),
		{
			id: 'iron_barrel',
			built: lathe(sourcesDir, 'iron_barrel', { top: 'iron_barrel_base', bottom: 'iron_barrel_base' }),
			en: 'Iron Barrel',
			vi: 'Thùng Phuy Sắt',
			collider: 'solid',
			...STONE,
		},
		{
			id: 'crushing_tub',
			built: crushingTub(),
			en: 'Crushing Tub',
			vi: 'Bồn Nghiền',
			// nine pixels tall: the heavy core's box stops one pixel under the
			// rim, which is a better floor to stand on than a full cube's lid
			collider: 'pedestal',
			...WOODEN,
		},
	];
}

const PAINTED_WOOD: Piece[] = COLORS.map((color) =>
	cube(`${color}_painted_wood`, `${RUSTIC}/painted_wood_${color === 'light_gray' ? 'silver' : color}`, `${COLOR_EN[color]} Painted Wood`, `Gỗ Sơn ${COLOR_VI[color]}`, WOODEN),
);

const RUSTIC_CUBES: Piece[] = [
	cube('clay_wall', `${RUSTIC}/clay_wall`, 'Clay Wall', 'Tường Đất Sét', STONE),
	cube('crossed_clay_wall', `${RUSTIC}/clay_wall_cross`, 'Cross-Braced Clay Wall', 'Tường Đất Sét Giằng Chéo', STONE),
	cube('diagonal_clay_wall', `${RUSTIC}/clay_wall_diag`, 'Diagonal-Braced Clay Wall', 'Tường Đất Sét Giằng Xiên', STONE),
	cube('slate', `${RUSTIC}/slate`, 'Slate', 'Đá Phiến', STONE),
	cube('slate_bricks', `${RUSTIC}/slate_brick`, 'Slate Bricks', 'Gạch Đá Phiến', STONE),
	cube('chiseled_slate', `${RUSTIC}/slate_chiseled`, 'Chiseled Slate', 'Đá Phiến Chạm Khắc', STONE),
	column('slate_pillar', `${RUSTIC}/slate_pillar`, `${RUSTIC}/slate_tile`, 'Slate Pillar', 'Cột Đá Phiến', STONE),
	cube('slate_roof', `${RUSTIC}/slate_roof`, 'Slate Roof', 'Mái Đá Phiến', STONE),
	cube('slate_tiles', `${RUSTIC}/slate_tile`, 'Slate Tiles', 'Ngói Đá Phiến', STONE),
	column('stone_column', `${RUSTIC}/pillar_stone_side`, `${RUSTIC}/pillar_stone_top`, 'Stone Column', 'Trụ Đá', STONE),
	column('andesite_column', `${RUSTIC}/pillar_andesite_side`, `${RUSTIC}/pillar_stone_top`, 'Andesite Column', 'Trụ Andesit', STONE),
	column('diorite_column', `${RUSTIC}/pillar_diorite_side`, `${RUSTIC}/pillar_stone_top`, 'Diorite Column', 'Trụ Diorit', STONE),
	column('granite_column', `${RUSTIC}/pillar_granite_side`, `${RUSTIC}/pillar_stone_top`, 'Granite Column', 'Trụ Granit', STONE),
];

const RUSTIC_SINGLES: Piece[] = [
	{
		id: 'wooden_barrel',
		models: [`${RUSTIC}/barrel`],
		// the source names its planks and its particle in the 1.12 layout
		// (`blocks/planks_oak`), which resolves to nothing on a modern client
		textures: { '0': 'minecraft:block/oak_planks', particle: 'minecraft:block/oak_planks' },
		en: 'Wooden Barrel',
		vi: 'Thùng Rượu Gỗ',
		storage: 3,
		...WOODEN,
	},
	{
		id: 'cabinet',
		models: [`${RUSTIC}/cabinet`],
		en: 'Cabinet',
		vi: 'Tủ Đựng Đồ',
		storage: 3,
		directional: true,
		...WOODEN,
	},
	{
		id: 'apiary',
		models: [`${RUSTIC}/apiary`],
		en: 'Apiary',
		vi: 'Hộp Nuôi Ong',
		directional: true,
		...WOODEN,
	},
	{
		id: 'straw_beehive',
		models: [`${RUSTIC}/beehive`],
		en: 'Straw Beehive',
		vi: 'Tổ Ong Rơm',
		directional: true,
		hardness: 0.6,
		tool: 'axe',
	},
	{
		id: 'brewing_barrel',
		models: [`${RUSTIC}/brewing_barrel`],
		en: 'Brewing Barrel',
		vi: 'Thùng Ủ Rượu',
		directional: true,
		...WOODEN,
	},
	{
		id: 'gargoyle',
		models: [`${RUSTIC}/gargoyle`],
		en: 'Gargoyle',
		vi: 'Tượng Gargoyle',
		directional: true,
		collider: 'solid',
		...STONE,
	},
	{
		id: 'iron_torch',
		wireless: true,
		models: [`${RUSTIC}/iron_torch`],
		en: 'Iron Torch',
		vi: 'Đuốc Sắt',
		light: { offset: 1, level: 14 },
		collider: 'small',
		hardness: 0.5,
		tool: 'pickaxe',
	},
	// the source hangs its wall torch off the west edge; a quarter turn the
	// other way brings the bracket to the south edge, which is the wall a
	// placer faces
	{
		id: 'iron_wall_torch',
		wireless: true,
		models: [`${RUSTIC}/iron_torch_wall`],
		rotateY: 270,
		en: 'Iron Wall Torch',
		vi: 'Đuốc Sắt Gắn Tường',
		light: { offset: 1, level: 14 },
		directional: true,
		collider: 'flat',
		hardness: 0.5,
		tool: 'pickaxe',
	},
	{
		id: 'wooden_lantern',
		wireless: true,
		models: [`${RUSTIC}/lantern_wood`],
		en: 'Wooden Lantern',
		vi: 'Đèn Lồng Gỗ',
		light: { offset: 1, level: 14 },
		collider: 'small',
		...WOODEN,
	},
	{
		id: 'wooden_wall_lantern',
		wireless: true,
		models: [`${RUSTIC}/lantern_wood_wall`],
		en: 'Wooden Wall Lantern',
		vi: 'Đèn Lồng Gỗ Gắn Tường',
		light: { offset: 1, level: 14 },
		directional: true,
		collider: 'flat',
		...WOODEN,
	},
];

// ---- Builder's Bounty -----------------------------------------------------
//
// Its models live under the vanilla namespace with `item/...` textures, which
// the generator would otherwise take for the client's own; each piece names
// its textures under the `bbounty` root so they are copied like any other.

interface Clutter {
	id: string;
	model: string;
	textures: Record<string, string>;
	en: string;
	vi: string;
	collider: Behaviour['collider'];
	light?: Behaviour['light'];
	directional?: boolean;
}

const CLUTTER: Clutter[] = [
	{ id: 'book_stack', model: 'book_column', textures: { '0': 'book_column' }, en: 'Stack of Books', vi: 'Chồng Sách', collider: 'pedestal' },
	{ id: 'paper_stack', model: 'paper_column', textures: { '1': 'paper_column' }, en: 'Stack of Papers', vi: 'Chồng Giấy', collider: 'pedestal' },
	{ id: 'candlestick', model: 'candlestick', textures: { '1': 'candle_flame', '2': 'candlestick' }, en: 'Candlestick', vi: 'Giá Nến', collider: 'pedestal', light: { offset: 1, level: 9 } },
	{ id: 'gold_candlestick', model: 'gold_candlestick', textures: { '1': 'candle_flame', '2': 'gold_candlestick' }, en: 'Gold Candlestick', vi: 'Giá Nến Vàng', collider: 'pedestal', light: { offset: 1, level: 9 } },
	{ id: 'globe', model: 'globe', textures: { '0': 'globe' }, en: 'Globe', vi: 'Quả Địa Cầu', collider: 'pedestal', directional: true },
	{ id: 'ink_and_quill', model: 'ink', textures: { '0': 'ink' }, en: 'Ink and Quill', vi: 'Lọ Mực Và Bút Lông', collider: 'small', directional: true },
	{ id: 'potion_bottles', model: 'potions', textures: { '2': 'potions' }, en: 'Potion Bottles', vi: 'Lọ Thuốc', collider: 'pedestal', directional: true },
	{ id: 'scarecrow', model: 'scarecrow', textures: { '0': 'scarecrow' }, en: 'Scarecrow', vi: 'Bù Nhìn', collider: 'solid', directional: true },
	{ id: 'bone_pile', model: 'skull_floor', textures: { '0': 'skull_floor' }, en: 'Skull and Bones', vi: 'Đầu Lâu Và Xương', collider: 'small', directional: true },
	{ id: 'trophy', model: 'trophy', textures: { '0': 'trophy' }, en: 'Trophy', vi: 'Cúp Vàng', collider: 'pedestal', directional: true },
];

const BOUNTY_PIECES: Piece[] = CLUTTER.map((piece): Piece => {
	const textures: Record<string, string> = {};

	for (const [key, name] of Object.entries(piece.textures)) {
		textures[key] = `${BOUNTY}/${name}`;
	}

	const [first] = Object.values(textures);
	textures.particle = first!;

	return {
		id: piece.id,
		models: [`bbounty:item/${piece.model}`],
		textures,
		en: piece.en,
		vi: piece.vi,
		collider: piece.collider,
		light: piece.light,
		directional: piece.directional,
		hardness: 0.5,
		tool: 'axe',
	};
});

// ---- Aesthetic Frames -----------------------------------------------------

const FRAME_WOODS: Wood[] = WOODS.filter((wood) => wood !== 'pale_oak');

/** Source strip prefix, our id suffix, and the words for it. */
const FRAME_PATTERNS: Array<[string, string, string, string]> = [
	['frame', 'timber_frame', 'Timber Frame', 'Khung'],
	['horizontal_frame', 'timber_frame_beam', 'Timber Frame with Beam', 'Khung Có Dầm'],
	['vertical_frame', 'timber_frame_post', 'Timber Frame with Post', 'Khung Có Cột'],
	['perpendicular_frame', 'timber_frame_cross', 'Timber Frame with Cross', 'Khung Chữ Thập'],
	['crest_frame', 'braced_timber_frame', 'Braced Timber Frame', 'Khung Giằng Chéo'],
	['horizontal_crest_frame', 'braced_timber_frame_beam', 'Braced Timber Frame with Beam', 'Khung Giằng Chéo Có Dầm'],
	['vertical_crest_frame', 'braced_timber_frame_post', 'Braced Timber Frame with Post', 'Khung Giằng Chéo Có Cột'],
	['perpendicular_crest_frame', 'braced_timber_frame_cross', 'Braced Timber Frame with Cross', 'Khung Giằng Chéo Chữ Thập'],
];

function frameId(wood: Wood, suffix: string): string {
	return `${wood}_${suffix}`;
}

/**
 * Aesthetic Frames draws each frame as a Fusion connecting strip: five 16x16
 * tiles side by side, the first of which is the frame with no neighbours,
 * every post and beam drawn. Without that mod's connecting loader a cube can
 * only wear one tile, and the complete frame is the one that reads as a
 * framed panel wherever it is placed. This cuts that tile out of every strip
 * and hands the sprites to the generator to write under our namespace.
 */
/**
 * Rustic's crushing tub sheet made opaque. The source is a full-block
 * texture whose top seven rows are transparent (its own model is a plain
 * cube that never shows them); ours is an open bowl cut from the same
 * sheet, and the client's mipmaps averaged those clear pixels into the
 * rows beside them, so every face sampled near the top of the staves went
 * ragged at any distance. The clear rows are filled with the plain stave
 * rows, which is also exactly the texture the inside of the bowl wants.
 */
function tubSprite(sourcesDir: string): Uint8Array {
	const source = decodePng(new Uint8Array(readFileSync(join(sourcesDir, 'rustic/assets/rustic/textures/block/crushing_tub.png'))));
	const sheet = bitmap(16, 16);
	const staves = [10, 11, 12, 10, 11, 12, 10];

	for (let y = 0; y < 16; y++) {
		const from = (y < 7 ? staves[y]! : y) * source.width * 4;

		sheet.data.set(source.data.subarray(from, from + 16 * 4), y * 16 * 4);
	}

	return encodePng(sheet);
}

export function frameSprites(sourcesDir: string): Record<string, Uint8Array> {
	const root = join(sourcesDir, 'aframes/src/main/resources/assets/aestheticframes/textures/block');
	const sprites: Record<string, Uint8Array> = { crushing_tub: tubSprite(sourcesDir) };

	for (const wood of FRAME_WOODS) {
		for (const [prefix, suffix] of FRAME_PATTERNS) {
			const strip = decodePng(new Uint8Array(readFileSync(join(root, `${prefix}_${wood}.png`))));
			const tile = bitmap(16, 16);

			for (let y = 0; y < 16; y++) {
				const from = y * strip.width * 4;
				tile.data.set(strip.data.subarray(from, from + 16 * 4), y * 16 * 4);
			}

			sprites[frameId(wood, suffix)] = new Uint8Array(encodePng(tile));
		}
	}

	return sprites;
}

const TIMBER_FRAMES: Piece[] = FRAME_WOODS.flatMap((wood): Piece[] =>
	FRAME_PATTERNS.map(([, suffix, en, vi]): Piece =>
		cube(frameId(wood, suffix), `lunasmp:block/${frameId(wood, suffix)}`, `${WOOD_EN[wood]} ${en}`, `${vi} ${WOOD_VI[wood]}`, WOODEN),
	),
);

/**
 * Every medieval piece. It takes the staged sources because three of its
 * families are read out of sprites rather than out of source models: the
 * timber frames' connecting strips and the turned pieces' silhouettes.
 */
export function medievalPieces(sourcesDir: string): Piece[] {
	return [
	...PALISADES,
	...SUPPORTS,
	...SEATS,
	...BEAMS,
	...PILLARS,
	...DECORATIVE_SINGLES,
	...LANTERNS,
	...CHANDELIERS,
	...CANDLES,
	...CHAINS,
	...turnedPieces(sourcesDir),
	...PAINTED_WOOD,
	...RUSTIC_CUBES,
	...RUSTIC_SINGLES,
	...BOUNTY_PIECES,
	...TIMBER_FRAMES,
	];
}
