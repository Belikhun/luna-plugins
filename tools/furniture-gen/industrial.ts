// The industrial blocks: the original trio (hazard block, drain grate,
// ceiling lamp), and the steelworks wave ported from Engineer's Decor.
//
// The idea comes from NadBlocks (Nadwey/nadblocks), which the user asked for.
// Its repository carries **no licence at all**, upstream or in the fork, and a
// missing licence is the most restrictive state rather than the least - so
// nothing of its art or geometry is copied. What is taken is the list of three
// objects; the stripes, the grate and the lamp below are composed here out of
// our own geometry and our own painted textures, exactly the way flora.ts
// handles Floral Enchantment.
//
// All three are things a builder wants many of, so each is as cheap as its
// shape allows: the hazard block is a real cube on a reserved note block state
// (no display entity at all), and the other two are entity-backed only because
// they are not cube-shaped.

import { type Element, type Model, type Vec3, DISPLAY, part } from './compose';
import type { Piece } from './catalog';
import { type Rgba, bitmap, encodePng, fill, put, rgb, tint } from './pixels';

/** A tiny deterministic hash in [0, 1): a texture always paints the same. */
function noise(seed: number, x: number, y: number): number {
	let h = seed ^ Math.imul(x + 11, 374761393) ^ Math.imul(y + 7, 668265263);
	h = Math.imul(h ^ (h >>> 13), 1274126177);

	return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

const HAZARD_YELLOW = rgb('#e8b422');
const HAZARD_BLACK = rgb('#1f1f22');

/**
 * The hazard stripes: 45-degree bands four pixels wide, worn at the edges.
 *
 * The band index is `(x + y) >> 2`, whose period is eight pixels - a divisor
 * of sixteen, which is what makes a wall of these run continuously instead of
 * stepping at every block edge.
 */
export function hazardSprite(): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const band = ((x + y) >> 2) & 1;
			const base = band === 0 ? HAZARD_YELLOW : HAZARD_BLACK;

			// paint wears off the raised diagonal: a scuff of the metal
			// underneath, densest where two bands meet
			const seam = (x + y) % 4 === 0;
			const wear = noise(0x9e3779b9, x, y);

			if (seam && wear > 0.72) {
				put(image, x, y, rgb('#7d7f86'));
				continue;
			}

			const grain = noise(0x5bd1e995, x, y) * 0.14 + 0.93;

			put(image, x, y, tint(base, grain));
		}
	}

	return encodePng(image);
}

/** Painted steel: the frame of the grate and of the lamp. */
function steelSprite(seed: number, base: Rgba, speckle: number): Uint8Array {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(seed, x >> 1, y) * 0.1 + noise(seed ^ 0x165667b1, x, y) * 0.06 + 0.92;
			let colour = tint(base, grain);

			// pitting: the dark specks that stop a flat grey reading as plastic
			if (noise(seed ^ 0x27d4eb2f, x, y) > 1 - speckle) {
				colour = tint(colour, 0.74);
			}

			put(image, x, y, colour);
		}
	}

	return encodePng(image);
}

/**
 * What is down the drain: near black, with the odd wet glint.
 *
 * This is the floor of the shaft, seen through the slots between the bars, so
 * it only ever appears in narrow strips - the glints are what keep those
 * strips from reading as holes punched in the world.
 */
export function drainPitSprite(): Uint8Array {
	const image = bitmap(16, 16);

	fill(image, rgb('#0d0f12'));

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const wet = noise(0x84222325, x, y);

			if (wet > 0.955) {
				put(image, x, y, rgb('#2b3a44'));
			} else if (wet > 0.9) {
				put(image, x, y, rgb('#171d22'));
			}
		}
	}

	return encodePng(image);
}

/** The lamp's diffuser, lit and dark. */
export function lampPanelSprite(lit: boolean): Uint8Array {
	const image = bitmap(16, 16);
	const base = lit ? rgb('#fff6db') : rgb('#b9bcb6');

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			// the frosted panel is brighter in the middle, where the tube is
			const dx = (x + 0.5 - 8) / 8;
			const dy = (y + 0.5 - 8) / 8;
			const centre = 1 - Math.min(1, Math.sqrt(dx * dx + dy * dy)) * (lit ? 0.16 : 0.1);
			const grain = noise(0x2545f491, x, y) * 0.05 + 0.975;

			put(image, x, y, tint(base, centre * grain));
		}
	}

	return encodePng(image);
}

/** Every texture the trio paints for itself, by the name it is written under. */
export const INDUSTRIAL_SPRITES: Record<string, () => Uint8Array> = {
	hazard_block: hazardSprite,
	drain_metal: () => steelSprite(0x7f4a7c15, rgb('#4e5359'), 0.1),
	drain_pit: drainPitSprite,
	lamp_frame: () => steelSprite(0x38495ab5, rgb('#9aa0a6'), 0.05),
	lamp_panel_on: () => lampPanelSprite(true),
	lamp_panel_off: () => lampPanelSprite(false),
};

const METAL = 'lunasmp:block/drain_metal';
const PIT = 'lunasmp:block/drain_pit';
const FRAME = 'lunasmp:block/lamp_frame';

/**
 * The drain grate: a shaft with a lid you can see down.
 *
 * The block is full height so it can be sunk into a road and walked over. Only
 * the top three pixels are the grate itself: a rim around the edge and six
 * bars across it, with the shaft floor showing through the five slots between.
 */
function drainGrate(): Model {
	const elements: Element[] = [];

	// the shaft: solid sides, and a floor that is what the slots look down on
	elements.push(part([0, 0, 0], [16, 13, 16], { all: 'minecraft:block/deepslate_tiles', up: PIT }, {
		cull: ['north', 'east', 'south', 'west', 'down'],
	}));

	const rim: Array<[Vec3, Vec3]> = [
		[[0, 13, 0], [16, 16, 1]],
		[[0, 13, 15], [16, 16, 16]],
		[[0, 13, 1], [1, 16, 15]],
		[[15, 13, 1], [16, 16, 15]],
	];

	for (const [from, to] of rim) {
		elements.push(part(from, to, METAL));
	}

	// six bars, five slots: the bars sit a pixel below the rim so the rim
	// reads as a frame the grate drops into rather than as more of the grate
	for (let index = 0; index < 6; index++) {
		const z = 1 + index * 2.4;

		elements.push(part([1, 14, z], [15, 16, z + 1.4], METAL));
	}

	return {
		textures: { t0: 'minecraft:block/deepslate_tiles', t1: PIT, t2: METAL, particle: METAL },
		elements: retexture(elements, {
			'minecraft:block/deepslate_tiles': '#t0',
			[PIT]: '#t1',
			[METAL]: '#t2',
		}),
		display: DISPLAY,
	};
}

/**
 * The ceiling lamp: a frame around a frosted panel, flush with the ceiling.
 *
 * It lives in the top three pixels of its block, which is the shape a closed
 * trapdoor has - that is the vanilla block its hitbox borrows, so the outline
 * sits on the lamp rather than in the empty air below it.
 */
function ceilingLamp(lit: boolean): Model {
	const panel = lit ? 'lunasmp:block/lamp_panel_on' : 'lunasmp:block/lamp_panel_off';
	const elements: Element[] = [];

	const rim: Array<[Vec3, Vec3]> = [
		[[0, 13, 0], [16, 16, 1]],
		[[0, 13, 15], [16, 16, 16]],
		[[0, 13, 1], [1, 16, 15]],
		[[15, 13, 1], [16, 16, 15]],
	];

	for (const [from, to] of rim) {
		elements.push(part(from, to, FRAME, { cull: ['up'] }));
	}

	// the backplate closes the recess off, so nobody sees through the lamp
	// into the block above it
	elements.push(part([1, 15, 1], [15, 16, 15], FRAME, { cull: ['up'], only: ['up', 'down'] }));

	elements.push(part([1, 13, 1], [15, 15, 15], panel, { only: ['north', 'east', 'south', 'west', 'down'] }));

	return {
		textures: { t0: FRAME, t1: panel, particle: FRAME },
		elements: retexture(elements, { [FRAME]: '#t0', [panel]: '#t1' }),
		display: DISPLAY,
	};
}

/**
 * Rewrites the paths a part named into the model's own variables.
 *
 * A face's texture has to be a `#variable`; a bare path renders as the missing
 * texture. `composed` does this by collecting the paths itself, but these
 * models want their variables named, so the map is given.
 */
function retexture(elements: Element[], map: Record<string, string>): Element[] {
	for (const element of elements) {
		const faces = element.faces as Record<string, Record<string, unknown>>;

		for (const face of Object.values(faces)) {
			const path = face.texture as string;

			face.texture = map[path] ?? path;
		}
	}

	return elements;
}

// ---- the steelworks --------------------------------------------------------
//
// Ported from Engineer's Decor (stfwi/engineers-decor), MIT over the whole
// tree - the same author and the same licence as the rsgauges the instrument
// line was checked against, which is what makes the two families read as one
// product range. The cubes are that pack's texture0 variant on a reserved
// note block state; the shaped pieces merge its own element models.

const ED = 'engineersdecor:block';

/** A full-cube port: one source texture on a note-block-backed cube. */
function edCube(id: string, texture: string, en: string, vi: string, tool: 'pickaxe' | 'axe' | 'shovel', hardness: number): Piece {
	const path = `${ED}/${texture}`;

	return {
		id,
		built: {
			textures: { t0: path, particle: path },
			elements: [part([0, 0, 0], [16, 16, 16], '#t0', {
				cull: ['north', 'east', 'south', 'west', 'up', 'down'],
			})],
			display: DISPLAY,
		},
		en,
		vi,
		cube: true,
		hardness,
		tool,
	};
}

export const STEELWORKS: Piece[] = [
	edCube('clinker_bricks', 'clinker_brick/clinker_brick_texture0', 'Clinker Bricks', 'Gạch Clinker', 'pickaxe', 3.0),
	edCube('stained_clinker_bricks', 'clinker_brick/clinker_brick_stained_texture0', 'Stained Clinker Bricks', 'Gạch Clinker Ám Màu', 'pickaxe', 3.0),
	edCube('slag_bricks', 'slag_brick/slag_brick_texture0', 'Slag Bricks', 'Gạch Xỉ Lò', 'pickaxe', 3.0),
	edCube('rebar_concrete', 'concrete/rebar_concrete_texture0', 'Rebar Concrete', 'Bê Tông Cốt Thép', 'pickaxe', 4.0),
	edCube('rebar_concrete_tiles', 'concrete/rebar_concrete_tile_texture0', 'Rebar Concrete Tiles', 'Gạch Lát Bê Tông', 'pickaxe', 4.0),
	edCube('industrial_planks', 'material/industrial_planks_texture0', 'Old Industrial Planks', 'Ván Gỗ Công Nghiệp', 'axe', 1.5),
	edCube('grit_dirt', 'soil/dense_grit_dirt_texture0', 'Dense Grit Dirt', 'Đất Sỏi Nện', 'shovel', 0.6),
	edCube('grit_sand', 'soil/dense_grit_sand_texture0', 'Dense Grit Sand', 'Cát Sỏi Nện', 'shovel', 0.6),
	{
		id: 'steel_floor_grating',
		models: [`${ED}/furniture/steel_floor_grating_model`],
		en: 'Steel Floor Grating',
		vi: 'Sàn Lưới Thép',
		collider: 'solid',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	{
		id: 'steel_table',
		models: [`${ED}/furniture/steel_table_model`],
		en: 'Steel Table',
		vi: 'Bàn Thép',
		surface: 4,
		collider: 'solid',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	{
		id: 'treated_wood_table',
		models: [`${ED}/furniture/treated_wood_table_model`],
		en: 'Treated Wood Table',
		vi: 'Bàn Gỗ Tẩm Dầu',
		surface: 4,
		collider: 'solid',
		hardness: 1.5,
		tool: 'axe',
	},
	{
		id: 'treated_wood_stool',
		models: [`${ED}/furniture/treated_wood_stool_model`],
		en: 'Treated Wood Stool',
		vi: 'Ghế Đẩu Gỗ Tẩm Dầu',
		seatHeight: 0.55,
		collider: 'pedestal',
		hardness: 1.5,
		tool: 'axe',
	},
	// the poles are authored along the block's own north-south axis, the way
	// a pipe is; standing them up is the same quarter-turn the wall-flat
	// sources take
	{
		id: 'treated_wood_pole',
		models: [`${ED}/pole/straight_pole_model`],
		en: 'Treated Wood Pole',
		vi: 'Cột Gỗ Tẩm Dầu',
		bakeUpright: true,
		collider: 'post',
		hardness: 1.5,
		tool: 'axe',
	},
	{
		id: 'thin_steel_pole',
		models: [`${ED}/pole/straight_thin_metal_pole_model`],
		en: 'Thin Steel Pole',
		vi: 'Cột Thép Mảnh',
		bakeUpright: true,
		collider: 'post',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	{
		id: 'thick_steel_pole',
		models: [`${ED}/pole/straight_thick_metal_pole_model`],
		en: 'Thick Steel Pole',
		vi: 'Cột Thép Dày',
		bakeUpright: true,
		collider: 'post',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	{
		id: 'steel_railing',
		models: [`${ED}/furniture/steel_railing_n_model`],
		en: 'Steel Railing',
		vi: 'Lan Can Thép',
		directional: true,
		// the bar hugs the model's north edge, so with faceAway the railing
		// lands on the edge the placer is looking past - the drop side of a
		// catwalk - and the borrowed closed door puts a real full-height slab
		// of collision exactly under it
		faceAway: true,
		collider: 'door',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	// the source's straight panel runs north-south; the connect cutter wants
	// it east-west, and then runs, turns and crossings come out of the same
	// machinery the trellis and the lattice use
	{
		id: 'steel_mesh_fence',
		models: [`${ED}/fence/steel_mesh_fence_inventory`],
		en: 'Steel Mesh Fence',
		vi: 'Hàng Rào Lưới Thép',
		rotateY: 90,
		connects: 'mesh',
		collider: 'pane',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	{
		id: 'iron_bulb_light',
		models: [`${ED}/light/bulb_light_model`],
		en: 'Iron Bulb Light',
		vi: 'Đèn Lồng Sắt',
		rotateY: 180,
		directional: true,
		collider: 'flat',
		light: { offset: 0, level: 14 },
		hardness: 0.6,
		tool: 'pickaxe',
	},
	{
		id: 'iron_inset_light',
		models: [`${ED}/light/inset_light_model`],
		en: 'Iron Inset Light',
		vi: 'Đèn Âm Tường',
		rotateY: 180,
		directional: true,
		collider: 'flat',
		light: { offset: 0, level: 12 },
		hardness: 0.6,
		tool: 'pickaxe',
	},
	// the edge lights are authored along the north edge like the inset light,
	// and take the same half turn to hug the wall behind the placer
	{
		id: 'ceiling_edge_light',
		models: [`${ED}/light/ceiling_edge_light_model`],
		en: 'Ceiling Edge Light',
		vi: 'Đèn Viền Trần',
		rotateY: 180,
		directional: true,
		collider: 'flat',
		light: { offset: 0, level: 12 },
		hardness: 0.6,
		tool: 'pickaxe',
	},
	{
		id: 'floor_edge_light',
		models: [`${ED}/light/floor_edge_light_model`],
		en: 'Floor Edge Light',
		vi: 'Đèn Viền Sàn',
		rotateY: 180,
		directional: true,
		collider: 'flat',
		light: { offset: 0, level: 10 },
		hardness: 0.6,
		tool: 'pickaxe',
	},

	// ---- the second steelworks wave: doors, hatch, gate, walkway, glass ----

	// the sliding door is authored down the middle of its block; the borrowed
	// iron-door collider stands on the facing edge, so the leaf slides onto it.
	// Its open shape folds the panels into the east corner, where the open
	// door slab is, so the mirrored leaf folds west
	{
		id: 'metal_sliding_door',
		models: [`${ED}/door/metal_sliding_door_model_bottom_closed`],
		openable: { models: [`${ED}/door/metal_sliding_door_model_bottom_open`], sound: 'door' },
		shift: [0, 0, -6],
		en: 'Metal Sliding Door',
		vi: 'Cửa Trượt Kim Loại',
		directional: true,
		collider: 'door',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	{
		id: 'metal_sliding_door_top',
		models: [`${ED}/door/metal_sliding_door_model_top_closed`],
		openable: { models: [`${ED}/door/metal_sliding_door_model_top_open`], sound: 'door' },
		shift: [0, 0, -6],
		en: 'Metal Sliding Door Top',
		vi: 'Cửa Trượt Kim Loại (Trên)',
		directional: true,
		collider: 'door',
		upper: true,
		hardness: 2.0,
		tool: 'pickaxe',
	},
	// a vanilla-style door: one leaf on the west edge that the game turns.
	// A quarter turn lays it on the facing edge, closed; the open shape is
	// the same leaf turned once more onto the east edge, which is where the
	// door slab swings. The right-hinge leaf is the one whose straps land on
	// that east end - the unmirrored hinge - and the mirror flips them west
	{
		id: 'industrial_wood_door',
		models: [`${ED}/door/old_industrial_wood_door_model_bottom_rh`],
		openable: { models: [`${ED}/door/old_industrial_wood_door_model_bottom_rh`], sound: 'wood', rotateY: 90 },
		rotateY: 90,
		en: 'Old Industrial Wood Door',
		vi: 'Cửa Gỗ Công Nghiệp',
		directional: true,
		collider: 'door',
		hardness: 1.5,
		tool: 'axe',
	},
	{
		id: 'industrial_wood_door_top',
		models: [`${ED}/door/old_industrial_wood_door_model_top_rh`],
		openable: { models: [`${ED}/door/old_industrial_wood_door_model_top_rh`], sound: 'wood', rotateY: 90 },
		rotateY: 90,
		en: 'Old Industrial Wood Door Top',
		vi: 'Cửa Gỗ Công Nghiệp (Trên)',
		directional: true,
		collider: 'door',
		upper: true,
		hardness: 1.5,
		tool: 'axe',
	},
	// the hatch opens against the north face of its block; the glass trapdoor
	// it borrows its state machine from opens against the south, so it takes
	// the half turn, and its top half is the closed plate lifted to the ceiling
	{
		id: 'iron_hatch',
		models: [`${ED}/trapdoor/iron_hatch_model_closed`],
		openable: { models: [`${ED}/trapdoor/iron_hatch_model_open`], sound: 'door' },
		rotateY: 180,
		topLift: 13,
		en: 'Iron Hatch',
		vi: 'Cửa Sập Sắt',
		directional: true,
		collider: 'trapdoor',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	// one block of gate in a run of mesh fence: the fence on either side
	// reaches out to it as if it were more fence (its posts sit on the pane
	// line the fence arms end at), and it swings on the door state machine.
	// The door slab it collides as stands on the facing edge while the mesh
	// hangs mid-block - the outline is a few pixels off the mesh, the way
	// through is blocked and opened exactly the same
	{
		id: 'steel_mesh_gate',
		models: [`${ED}/fence/steel_mesh_fence_gate_bottom_model`],
		openable: { models: [`${ED}/fence/steel_mesh_fence_gate_bottom_model_open`], sound: 'door' },
		joins: 'mesh',
		en: 'Steel Mesh Gate',
		vi: 'Cổng Lưới Thép',
		directional: true,
		collider: 'door',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	// the catwalk is a two-pixel plate that grows a railing on every edge no
	// other catwalk touches; a closed IRON trapdoor is exactly that plate's
	// shape and nothing a hand click moves, and placing it against a ceiling
	// lifts it there like the source's own top-aligned variant
	{
		id: 'steel_catwalk',
		models: [`${ED}/furniture/steel_catwalk_model`],
		railings: `${ED}/furniture/steel_catwalk_n_model`,
		topLift: 14,
		en: 'Steel Catwalk',
		vi: 'Lối Đi Thép',
		collider: 'plate',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	// a full-block window panel down the middle, cut into arms like the mesh
	// fence so a wall of them runs, turns and crosses
	{
		id: 'steel_framed_window',
		models: [`${ED}/furniture/steel_framed_window_model`],
		en: 'Steel Framed Window',
		vi: 'Cửa Sổ Khung Thép',
		connects: 'window',
		collider: 'pane',
		hardness: 1.5,
		tool: 'pickaxe',
	},
	// armoured glass: a translucent cube, so it stays entity-backed on a
	// barrier - a note-block cube occludes, and an occluding glass block
	// would cull the faces of everything around it into holes
	{
		id: 'panzerglass',
		built: {
			textures: { t0: `${ED}/glass/panzerglass_block_texture0`, particle: `${ED}/glass/panzerglass_block_texture0` },
			elements: [part([0, 0, 0], [16, 16, 16], '#t0')],
			display: DISPLAY,
		},
		en: 'Panzer Glass',
		vi: 'Kính Chống Đạn',
		collider: 'solid',
		hardness: 3.0,
		tool: 'pickaxe',
	},
	// a double-T beam under the ceiling, running away from the placer
	{
		id: 'steel_double_t_support',
		models: [`${ED}/hsupport/steel_double_t_support_model`],
		en: 'Steel Double-T Support',
		vi: 'Dầm Thép Chữ I',
		directional: true,
		collider: 'ceiling',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	// the pole caps: authored along z like the poles, so the upright bake
	// puts the head plate at the top
	{
		id: 'treated_wood_pole_head',
		models: [`${ED}/pole/straight_pole_head_model`],
		en: 'Treated Wood Pole Head',
		vi: 'Đầu Cột Gỗ Tẩm Dầu',
		bakeUpright: true,
		collider: 'post',
		hardness: 1.5,
		tool: 'axe',
	},
	{
		id: 'treated_wood_pole_support',
		models: [`${ED}/pole/straight_pole_support_model`],
		en: 'Treated Wood Pole Support',
		vi: 'Giá Đỡ Cột Gỗ Tẩm Dầu',
		bakeUpright: true,
		collider: 'post',
		hardness: 1.5,
		tool: 'axe',
	},
	{
		id: 'thin_steel_pole_head',
		models: [`${ED}/pole/thin_steel_pole_head_model`],
		en: 'Thin Steel Pole Head',
		vi: 'Đầu Cột Thép Mảnh',
		bakeUpright: true,
		collider: 'post',
		hardness: 2.0,
		tool: 'pickaxe',
	},
	{
		id: 'thick_steel_pole_head',
		models: [`${ED}/pole/thick_steel_pole_head_model`],
		en: 'Thick Steel Pole Head',
		vi: 'Đầu Cột Thép Dày',
		bakeUpright: true,
		collider: 'post',
		hardness: 2.0,
		tool: 'pickaxe',
	},
];

export const INDUSTRIAL: Piece[] = [
	{
		id: 'hazard_block',
		built: {
			textures: { t0: 'lunasmp:block/hazard_block', particle: 'lunasmp:block/hazard_block' },
			elements: [part([0, 0, 0], [16, 16, 16], '#t0', {
				cull: ['north', 'east', 'south', 'west', 'up', 'down'],
			})],
			display: DISPLAY,
		},
		en: 'Hazard Block',
		vi: 'Khối Cảnh Báo',
		// a real cube, so it goes on a reserved note block state: a wall of
		// these lights, occludes and culls like the block it looks like, and
		// costs no display entities at all
		cube: true,
		hardness: 1.5,
		tool: 'pickaxe',
	},
	{
		id: 'drain_grate',
		built: drainGrate(),
		en: 'Drain Grate',
		vi: 'Nắp Cống',
		collider: 'solid',
		hardness: 3.0,
		tool: 'pickaxe',
	},
	{
		id: 'ceiling_lamp',
		built: ceilingLamp(true),
		en: 'Ceiling Lamp',
		vi: 'Đèn Trần',
		collider: 'ceiling',
		lamp: {
			offBuilt: ceilingLamp(false),
			level: 15,
			// a light block buried in the ceiling lights nothing: it goes in
			// the air below the lamp
			offset: -1,
			ignite: 'hand',
			litByDefault: true,
		},
		hardness: 0.6,
		tool: 'pickaxe',
	},
];
