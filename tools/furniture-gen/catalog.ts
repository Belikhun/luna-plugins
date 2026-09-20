// The furniture catalog: what luna-smp ships, and where each piece comes from.
//
// Every entry names a source model in one of the MIT-licensed packs staged by
// fetch-sources.sh, or one of the models authored beside this file under
// assets/luna. The generator resolves the parent chain, merges the pieces
// listed in `models`, rewrites any bundled texture into the lunasmp namespace
// and emits the block model, its Nova config and both language files.
//
// Adding a piece is an entry here, never a hand-written registration.
//
// A few pieces name no source at all and carry a `built` model instead: they
// are composed here out of our own geometry over vanilla textures, which is how
// a design can be taken from a pack we may not copy from.

import { type Element, type Model, type Vec3, CROWN_DISPLAY, DISPLAY, box, composed, cross, garland, model, part } from './compose';
import { INDUSTRIAL, STEELWORKS } from './industrial';

/** Vanilla wood types every wooden piece is generated for. */
export const WOODS = [
	'oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak',
	'mangrove', 'cherry', 'bamboo', 'crimson', 'warped', 'pale_oak',
] as const;

/** Dye colours the upholstered and painted pieces are generated for. */
export const COLORS = [
	'white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
	'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black',
] as const;

export type Wood = (typeof WOODS)[number];
export type Color = (typeof COLORS)[number];

/**
 * A piece that can be lit and put out.
 *
 * The lit model is the piece's own `models`; the unlit one is either a second
 * set of source models or the lit one with its flames dropped.
 */
export interface Lamp {
	/** Source models for the unlit state, when the pack ships a separate one. */
	offModels?: string[];
	/**
	 * Otherwise: drop the flat planes drawn with a texture whose name contains
	 * one of these, which is how a torch loses its flame but keeps its stick.
	 */
	offDrop?: string[];
	/** Light level while lit, 0 to 15. */
	level: number;
	/**
	 * Where the light block sits relative to the piece. A ceiling piece needs
	 * -1: a light block buried in the ceiling lights nothing.
	 */
	offset: number;
	/** A flame needs flint and steel to light; a lamp switches by hand. */
	ignite: 'hand' | 'flint';
	/** Whether it is already burning when placed. */
	litByDefault?: boolean;
	/** The unlit model, when the piece is composed here rather than merged. */
	offBuilt?: Model;
}

/** How a piece behaves in world; consumed by the Kotlin generator. */
export interface Behaviour {
	/** Right-click seats the player at this height, in blocks. */
	seatHeight?: number;
	/** Always-on light through a vanilla light block at this offset. */
	light?: { offset: number; level: number };
	/**
	 * A wireless fixture: lit only while a power node in reach pays for it.
	 * The draw and light level live in lamps.ts (STREET_LIGHTS), keyed by id.
	 */
	wireless?: boolean;
	/**
	 * The vanilla block behind the model, which is what the game collides with
	 * and what the outline is drawn around. `solid` is a full cube (a barrier);
	 * `small` is a 6x6x6 box in the middle of the block with no collider at
	 * all, for small decor and anything hanging from a ceiling. The rest borrow
	 * a visible vanilla block's shape, skinned invisible by the hitbox base
	 * pack: `pane` a glass pane's post-and-arms (panels), `pedestal` a heavy
	 * core's 8x8x8 block in the middle (seats and boards), `pot` an empty
	 * flower pot's little box on the floor (planters; the hanging state falls
	 * back to `small`, since nothing pot-shaped hangs).
	 *
	 * A borrowed block must never be one that occludes: an occluding block
	 * culls the face of whatever it sits on, and since ours is skinned
	 * invisible that culled face becomes a hole to look through. Both a block
	 * declared `noOcclusion` (the pane) and a shape inset from every face (the
	 * pedestal, the pot) are safe.
	 */
	collider?: 'solid' | 'small' | 'pane' | 'pedestal' | 'pot' | 'door' | 'flat' | 'ceiling' | 'trapdoor' | 'plate' | 'post';
	/**
	 * A directional piece that faces away from its placer instead of back at
	 * them: a railing belongs on the edge you are looking past.
	 */
	faceAway?: boolean;
	/** A wall clock: the tile spins two live hands over the painted face. */
	clock?: boolean;
	/** Faces the placer; the model is rotated with the block state. */
	directional?: boolean;
	/**
	 * Placed against a ceiling, the piece hangs from generated ropes instead
	 * of standing. Only the head-skinned pots take this: their hanging model
	 * is the same cube with rope straps grown up to the ceiling, where a
	 * piece with authored hanging art names it through `hangingModels`.
	 */
	hangable?: boolean;
	/**
	 * The piece is a real full cube, so it goes on a reserved note block state
	 * rather than behind a display entity: it lights, occludes and culls like
	 * the block it looks like, and a wall of them costs nothing to render.
	 */
	cube?: boolean;
	/** Hardness passed to Breakable. */
	hardness?: number;
	/** Which tool class mines it fastest. */
	tool?: 'axe' | 'pickaxe' | 'shovel' | 'hoe';
}

export interface Piece extends Behaviour {
	/** Block id inside the lunasmp namespace. */
	id: string;
	/**
	 * Source models merged, in draw order, as `<sourceKey>:<path>`. A piece has
	 * either this, `head` or `built`: the pots are drawn from a skin, not from
	 * a pack, and the composed pieces are drawn from nothing but geometry.
	 */
	models?: string[];
	/** Joins neighbouring copies of itself: middle legs and skirts vanish. */
	legs?: boolean;
	/**
	 * A walkway that joins neighbouring copies of itself the way a table does,
	 * but with railings instead of legs: the value names a source model that
	 * is the piece's own `models` plus a railing along its north edge, and the
	 * generator grows that railing on every side no neighbour touches.
	 */
	railings?: string;
	/**
	 * Counts as a member of this connecting family for the panels around it,
	 * without being a panel itself: a gate in a run of mesh fence, which the
	 * fence on either side reaches out to as if it were more fence.
	 */
	joins?: string;
	/** The closed top-half model of a two-half piece such as a trapdoor. */
	topModel?: Model;
	/**
	 * The closed top-half model is the closed model itself lifted this many
	 * pixels, for a merged source hatch whose leaf lies at the bottom.
	 */
	topLift?: number;
	/**
	 * Translate the merged model by these pixels after its bakes: a source
	 * door authored down the middle of its block slid onto the edge where the
	 * borrowed door collider actually stands.
	 */
	shift?: [number, number, number];
	/**
	 * The piece is a head-shaped pot cut from this Mojang skin texture, named by
	 * its texture id. Gardener's Dream draws its pots as player heads, so the
	 * art is a skin rather than a model: the generator downloads it and builds
	 * the head cube and its overlay against the skin's own unwrapped net, which
	 * is what turns a head into a real block with a real hitbox.
	 */
	head?: string;
	/** English name. */
	en: string;
	/** Vietnamese name. */
	vi: string;
	/** Rotate the model -90 degrees about X (wall-flat source to free-standing). */
	bakeUpright?: boolean;
	/** Where an upright-baked piece settles in its block. */
	anchor?: 'center' | 'south';
	/** Re-centre the model's depth in its block (a source authored edge-flush). */
	center?: boolean;
	/**
	 * Bake this many degrees of clockwise Y rotation, for sources authored
	 * facing a direction other than north (Adorn's shelf and sofa face east).
	 */
	rotateY?: 90 | 180 | 270;
	/** Source models for a hanging variant; placing under a ceiling selects it. */
	hangingModels?: string[];
	/**
	 * The piece opens and closes on a click: blinds roll up, a door swings
	 * aside. The open shape is a second model, from a source or composed here,
	 * and an open piece is walked through - the hitbox follows the state.
	 */
	openable?: {
		models?: string[];
		built?: Model;
		sound: 'door' | 'wood' | 'shutter' | 'tap';
		/**
		 * Extra clockwise quarter turns baked into the open model only, on top
		 * of the piece's own: a hinged source door ships one leaf and lets the
		 * game turn it, so its open shape is the closed leaf swung a quarter.
		 */
		rotateY?: 90 | 180 | 270;
	};
	/**
	 * Another piece beside this one that opens with it: the tap fills the tub
	 * it stands at the end of, and the tub's own click turns the tap.
	 */
	openPartner?: string;
	/** Fish swim about inside it. */
	aquarium?: boolean;
	/** The upper half of a two-block piece, which is what its shape follows. */
	upper?: boolean;
	/**
	 * The panel joins up with the pieces around it the way a fence does: the
	 * model is cut into arms, and the block state says which are drawn, so
	 * corners and crossings come out of that on their own. The value names the
	 * family it joins, and a panel only reaches out to its own kind.
	 */
	connects?: string;
	/** Items can be set down on top of the piece: how many, spread over its top. */
	surface?: number;
	/** Items on the surface stand upright instead of lying flat (a shelf). */
	surfaceUpright?: boolean;
	/** Lift the whole model this many pixels: a piece placed on a stand. */
	raise?: number;
	/** Extra models merged under the raised piece: the stand itself. */
	standModels?: string[];
	/** Right-click opens this many rows of storage. */
	storage?: number;
	/** Holds one item, shown under glass in the middle of the block. */
	showcase?: boolean;
	/** Holds one plant, standing in the pot as its own block model. */
	pot?: boolean;
	/**
	 * Texture variables to define on the built model, for a source that leaves
	 * one to its own loader to fill.
	 */
	textures?: Record<string, string>;
	/** Where each plant a pot holds stands, in block units, facing north. */
	plantSlots?: Array<[number, number, number]>;
	/**
	 * The model, composed here rather than merged out of a source pack. A piece
	 * has exactly one of `models`, `head` and this.
	 */
	built?: Model;
	/** Lights up and goes out. */
	lamp?: Lamp;
}

export const WOOD_EN: Record<Wood, string> = {
	oak: 'Oak', spruce: 'Spruce', birch: 'Birch', jungle: 'Jungle',
	acacia: 'Acacia', dark_oak: 'Dark Oak', mangrove: 'Mangrove',
	cherry: 'Cherry', bamboo: 'Bamboo', crimson: 'Crimson',
	warped: 'Warped', pale_oak: 'Pale Oak',
};

export const WOOD_VI: Record<Wood, string> = {
	oak: 'Gỗ Sồi', spruce: 'Gỗ Vân Sam', birch: 'Gỗ Bạch Dương',
	jungle: 'Gỗ Rừng Rậm', acacia: 'Gỗ Keo', dark_oak: 'Gỗ Sồi Đen',
	mangrove: 'Gỗ Đước', cherry: 'Gỗ Anh Đào', bamboo: 'Tre',
	crimson: 'Gỗ Đỏ Thẫm', warped: 'Gỗ Cong Vênh', pale_oak: 'Gỗ Sồi Nhạt',
};

export const COLOR_EN: Record<Color, string> = {
	white: 'White', orange: 'Orange', magenta: 'Magenta', light_blue: 'Light Blue',
	yellow: 'Yellow', lime: 'Lime', pink: 'Pink', gray: 'Gray',
	light_gray: 'Light Gray', cyan: 'Cyan', purple: 'Purple', blue: 'Blue',
	brown: 'Brown', green: 'Green', red: 'Red', black: 'Black',
};

export const COLOR_VI: Record<Color, string> = {
	white: 'Trắng', orange: 'Cam', magenta: 'Đỏ Tươi', light_blue: 'Xanh Nhạt',
	yellow: 'Vàng', lime: 'Xanh Lá Mạ', pink: 'Hồng', gray: 'Xám',
	light_gray: 'Xám Nhạt', cyan: 'Xanh Ngọc', purple: 'Tím', blue: 'Xanh Dương',
	brown: 'Nâu', green: 'Xanh Lá', red: 'Đỏ', black: 'Đen',
};

/** Bamboo's planks are `bamboo_planks` but its "log" is `bamboo_block`. */
function woodPath(wood: Wood): string {
	return wood;
}

const WOODEN: Behaviour = { hardness: 1.0, tool: 'axe' };

/** Seating from Tables & Chairs 2: a refined chair per wood. */
const CHAIRS: Piece[] = WOODS.map((wood) => ({
	id: `${wood}_chair`,
	models: [`tac:chair/refined/${woodPath(wood)}_refined_chair`],
	en: `${WOOD_EN[wood]} Chair`,
	vi: `Ghế ${WOOD_VI[wood]}`,
	seatHeight: 0.6,
	directional: true,
	collider: 'pedestal',
	...WOODEN,
}));

const BENCHES: Piece[] = WOODS.map((wood) => ({
	id: `${wood}_bench`,
	models: [`tac:bench/basic/${woodPath(wood)}/${woodPath(wood)}_basic_bench_full`],
	en: `${WOOD_EN[wood]} Bench`,
	vi: `Băng Ghế ${WOOD_VI[wood]}`,
	seatHeight: 0.55,
	directional: true,
	collider: 'pedestal',
	...WOODEN,
}));

/** The dining table is two source models: the top, and the whole leg set. */
const TABLES: Piece[] = WOODS.map((wood) => ({
	id: `${wood}_table`,
	models: [
		`tac:table/basic/${woodPath(wood)}/top_table_basic`,
		`tac:table/basic/${woodPath(wood)}/legs_whole_basic`,
	],
	en: `${WOOD_EN[wood]} Table`,
	vi: `Bàn ${WOOD_VI[wood]}`,
	legs: true,
	surface: 4,
	...WOODEN,
}));

/** Adorn predates pale oak, so its pieces are generated for the woods it has. */
const ADORN_WOODS = WOODS.filter((wood) => wood !== 'pale_oak');

const COFFEE_TABLES: Piece[] = ADORN_WOODS.map((wood) => ({
	id: `${wood}_coffee_table`,
	models: [`adorn:block/${woodPath(wood)}_coffee_table`],
	legs: true,
	en: `${WOOD_EN[wood]} Coffee Table`,
	vi: `Bàn Trà ${WOOD_VI[wood]}`,
	surface: 2,
	directional: true,
	...WOODEN,
}));

const SHELVES: Piece[] = ADORN_WOODS.map((wood) => ({
	id: `${wood}_shelf`,
	models: [`adorn:block/${woodPath(wood)}_shelf`],
	en: `${WOOD_EN[wood]} Shelf`,
	vi: `Kệ ${WOOD_VI[wood]}`,
	// Adorn authors the shelf facing east (its blockstate turns north by 270)
	rotateY: 270,
	directional: true,
	surface: 3,
	surfaceUpright: true,
	collider: 'pedestal',
	...WOODEN,
}));

const DRAWERS: Piece[] = ADORN_WOODS.map((wood) => ({
	id: `${wood}_drawer`,
	models: [`adorn:block/${woodPath(wood)}_drawer`],
	en: `${WOOD_EN[wood]} Drawer`,
	vi: `Tủ Ngăn Kéo ${WOOD_VI[wood]}`,
	directional: true,
	storage: 3,
	...WOODEN,
}));

/**
 * Adorn's sofas are wool over a frame. A sofa with no neighbours renders as
 * centre plus both arms in the source's multipart, so the standalone piece
 * merges all three; like the shelf, it is authored facing east.
 */
const SOFAS: Piece[] = COLORS.map((color) => ({
	id: `${color}_sofa`,
	models: [
		`adorn:block/${color}_sofa_center`,
		`adorn:block/${color}_sofa_arm_left`,
		`adorn:block/${color}_sofa_arm_right`,
	],
	en: `${COLOR_EN[color]} Sofa`,
	vi: `Ghế Sofa ${COLOR_VI[color]}`,
	seatHeight: 0.5,
	rotateY: 270,
	directional: true,
	collider: 'pedestal',
	hardness: 0.8,
	tool: 'axe',
}));

/**
 * Adorn's lamp is one model with no unlit variant, so the bulb under the shade
 * is ours: the lit lamp has it, the dark one does not.
 */
const TABLE_LAMPS: Piece[] = COLORS.map((color) => ({
	id: `${color}_table_lamp`,
	wireless: true,
	models: [`adorn:block/${color}_table_lamp`, 'luna:block/lamp_bulb'],
	en: `${COLOR_EN[color]} Table Lamp`,
	vi: `Đèn Bàn ${COLOR_VI[color]}`,
	collider: 'small',
	directional: true,
	lamp: {
		offModels: [`adorn:block/${color}_table_lamp`],
		level: 13,
		offset: 1,
		ignite: 'hand',
		litByDefault: true,
	},
	hardness: 0.5,
	tool: 'axe',
}));

/** Flowering pieces from Beautify: the plant names its piece. */
const FLOWERS: Array<{ key: string; en: string; vi: string }> = [
	{ key: 'rose', en: 'Rose', vi: 'Hoa Hồng' },
	{ key: 'lilac', en: 'Lilac', vi: 'Tử Đinh Hương' },
	{ key: 'peony', en: 'Peony', vi: 'Mẫu Đơn' },
	{ key: 'sunflower', en: 'Sunflower', vi: 'Hướng Dương' },
	{ key: 'vine', en: 'Vine', vi: 'Dây Leo' },
];

/** Placed against the underside of a block, a planter hangs from a rope. */
const PLANTERS: Piece[] = FLOWERS.map((f) => ({
	id: `${f.key}_planter`,
	models: [`beautify:block/pots/${f.key}/standing_${f.key}_pot`],
	hangingModels: [`beautify:block/pots/${f.key}/${f.key}_pot`],
	en: `${f.en} Planter`,
	vi: `Chậu ${f.vi}`,
	collider: 'pot',
	directional: true,
	hardness: 0.6,
	tool: 'pickaxe',
}));

/**
 * The trellis is authored flat against a wall; bake it upright to stand. It
 * connects like a fence, so the model is cut into a post and four arms and a
 * corner is a state rather than a separate piece.
 */
const TRELLISES: Piece[] = FLOWERS.map((f) => ({
	id: `${f.key}_trellis`,
	models: [`beautify:block/trellis/oak/open_trellis_${f.key}`],
	en: `${f.en} Trellis`,
	vi: `Giàn ${f.vi}`,
	bakeUpright: true,
	connects: 'trellis',
	collider: 'pane',
	...WOODEN,
}));

/**
 * Gardener's Dream's pots, ported from the datapack.
 *
 * The datapack draws each pot as a player head carrying a custom skin, which
 * is how it ships sixty pots without a resource pack; every one of them is
 * therefore a head-shaped block whose whole look lives in a 64x64 skin. Here
 * they are real blocks instead: the generator cuts the head cube out of the
 * skin, and the head's own 8x8x8 shape is what the player collides with.
 *
 * Every one of them holds a plant, which is the point of the datapack: any
 * plant at all, not the thirty vanilla lets you pot.
 */
const POT_TEXTURES: Array<[string, string, string, string]> = [
	// the first set: plain glazed pots with a rolled rim
	['36352109c9b9317b9a5194b776ffdedec07fd06860fabdb410a2a131e2f72fe5', 'blue_pot', 'Blue Pot', 'Chậu Xanh Dương'],
	['6ac742902b93b7de898ddcb445e8199c906e2aa6a3c84326bb4707a0abcf48ae', 'green_pot', 'Green Pot', 'Chậu Xanh Lá'],
	['654f73ea0c448e8480673083f53949fd6d33efc2d22c21c9927904c4103ec05a', 'purple_pot', 'Purple Pot', 'Chậu Tím'],
	['f3ccb2c66f500abcef33a81498012826d5e366ef4fde0880260eff3c905b4e61', 'red_pot', 'Red Pot', 'Chậu Đỏ'],
	['43dac8bc0da4eecabace0ede5fa6ab7573f2b6209b5ac88ac50943e57d8b170a', 'clay_pot', 'Clay Pot', 'Chậu Đất Nung'],

	// the second set: shaped vessels
	['68c600736ed9021a7fe722988d0a418177a2d230bef4380e3016e575c7f7dac6', 'dark_pot', 'Dark Pot', 'Chậu Tối Màu'],
	['1b2f6faf1b99ac38d52b1c7bc49eb791c67054337d44d83d66adecb50f375', 'light_pot', 'Light Pot', 'Chậu Sáng Màu'],
	['91c9efabd99832e9837beebfb6cd5555805f02fe7e98b4d868096417778b8b04', 'modern_pot', 'Modern Pot', 'Chậu Hiện Đại'],
	['ceddeccb73e3794b97011fc0f64311add0ff3f0014b2399b3a4608163b6053be', 'teacup_pot', 'Teacup Pot', 'Chậu Tách Trà'],
	['4181f9f7c985476b997a1eb67d898f18eafb7c19505029ce1bd5272c1d31', 'pink_vase', 'Pink Vase', 'Bình Hồng'],
	['d4e8e8e821853efea18ce956804a6f8447273b755b7660493c30afbc3323c1', 'cursed_urn', 'Cursed Urn', 'Hũ Nguyền Rủa'],
	['ee6e451d45dccd5aa5d068e80acf64567d5ad1e77ede912c0f4b05f91e1f675f', 'luxury_pot', 'Luxury Pot', 'Chậu Sang Trọng'],
	['8d639fd2bd65526335a309f2c268243aa5162d3aa7d1684b26fa312123f9796d', 'bright_pot', 'Bright Pot', 'Chậu Rực Rỡ'],
	['bcdb2f398303025424bfb2db0e2cc053c127878e573fcc37c800e5839a343fee', 'bright_blue_pot', 'Bright Blue Pot', 'Chậu Xanh Rực Rỡ'],
	['67f4cfd173f987df5c7a6259ab7c3f4db96a42aab20460dc91d103c2616cd207', 'sage_pot', 'Sage Pot', 'Chậu Xanh Xám'],
	['4bbbb4b40e93cbdc2c29622e3e6797f9ea05388db5f700e06589627d0282d850', 'olive_pot', 'Olive Pot', 'Chậu Ô Liu'],
	['b03b92e7e2c07c93288eea3531275dd3674a800b573e172de5e4e48de088be3a', 'plain_pot', 'Plain Pot', 'Chậu Trơn'],
	['a6257e5a054262a8193c06a87f609f401c01e21bb80209698cbedef486821a9b', 'trimmed_pot', 'Trimmed Pot', 'Chậu Viền'],
	['2c24ab92ba9d9235afec046e3b1ade19097b788f764e974386fbc32f94c6db4f', 'heart_pot', 'Heart Pot', 'Chậu Trái Tim'],
	['85e49a6f82016a17f771c43a7053b853e7a5f4e1db3179068e3745f11b8656d3', 'prize_pot', 'Prize Pot', 'Chậu Giải Thưởng'],
	['a21bfd055236d6d2c764c1f9ba8267e19ce453643e15f791dcd3272d34d66175', 'basin_pot', 'Basin Pot', 'Chậu Thau'],
	['e5ff921d1c2ad5f8a8c2756e2dc14d4bd0868d50535202318e04bdf098dbbd6a', 'boot_planter', 'Boot Planter', 'Chậu Giày'],
	['e3a6d43c5e2ed86ea8e1b4e3dff5951abc64bcd34d785eb5862c504a2e6b8bcb', 'fresh_pot', 'Fresh Pot', 'Chậu Tươi Mát'],
	['6a77aebe68ecc78cdfffaeffc04fa97e2339941e3aff41cdaa1ebc5ba44628a1', 'totem_pot', 'Totem Pot', 'Chậu Totem'],
	['cac6a1415a9c73a4a74c17150b4227a898ac95e48a5e29f9240c12440463a0b7', 'clean_red_pot', 'Clean Red Pot', 'Chậu Đỏ Trơn'],
	['539ecec3112b4f542a2eb21f8917c235a76ae0b4b34b8eb7e8339298ed1b6291', 'clean_white_pot', 'Clean White Pot', 'Chậu Trắng Trơn'],
	['7eba2978dc8187f5f6f5b35e317edb9d8d5e749c6394f39bb7e3b80f25e5f436', 'patterned_pot', 'Patterned Pot', 'Chậu Hoa Văn'],
	['b83ccd1e501fa9bef499a43788dd7b40ece7472e318e20676bca3406a1a4c6e8', 'petal_pot', 'Petal Pot', 'Chậu Cánh Hoa'],

	// the third set: the vessels that copy something else in the world
	['4ef99d9ec4db4f188093742fed90051bedc16f3acb4586e98d609aa239b705a2', 'azalea_pot', 'Azalea Pot', 'Chậu Đỗ Quyên'],
	['084a742d8884a216aeaba45a96fae541e329162bc40893a63bfbdccd775bf', 'bloom_pot', 'Bloom Pot', 'Chậu Nở Hoa'],
	['9fafb14949946de823849468bab7def22422b2e0813df45ff0e864271ab72c3b', 'cauldron_pot', 'Cauldron Pot', 'Chậu Vạc'],
	['c0b76987e4246f8c7a50e2c1ac2b7e64e6a483dea0ea6da9d4d23c2e61cea7e', 'wave_pot', 'Wave Pot', 'Chậu Sóng'],
	['d56e791e2f97370b9f891bcc0f966e3eb29f38a37c702b8442aeb60e504ba397', 'composter_pot', 'Composter Pot', 'Chậu Ủ Phân'],
	['3a726a3f4056dc29bd4cd2904d6ad4cf7e251e86f852e3e20548d78ed8d7f', 'glass_pot', 'Glass Pot', 'Chậu Thủy Tinh'],
	['4b129035004e74fea39d16077db910737dad4deb862d967dacb4682e3aeea822', 'paper_pot', 'Paper Pot', 'Chậu Giấy'],
	['5f45055fa648cdbb2c620ba5650b29ec03c03026ba4fa88aff0552974737b2b7', 'porcelain_pot', 'Porcelain Pot', 'Chậu Sứ'],
	['7a40b762dc84f89c3eefa7243745c2293970a61445c6476f75ff06e0dec502c8', 'rustic_pot', 'Rustic Pot', 'Chậu Mộc'],
	['99e923f12bda51052879fd4f53218faa5fc11cca6062d2ad34edbd2119ef8b5b', 'barrel_planter', 'Barrel Planter', 'Chậu Thùng Gỗ'],
	['31385a7afb3552faf71c2c5a8e6a5b1d2e672c786e8074433eb5839ace83b4', 'basket_planter', 'Basket Planter', 'Chậu Giỏ'],
	['b2118cd747f730db9fe988ba7bb3b5beda9ba5999f751a84c993cf720fd2b86b', 'sapling_pot', 'Sapling Pot', 'Chậu Cây Non'],
	['19c40af7d8effa3b49d23abba2dda9bda345c9bd58b8f18c8b2e6c075f4a4425', 'black_pot', 'Black Pot', 'Chậu Đen'],
	['3a28b8f951a407699faefa2e648ba00a930bf1e65b07ce3dd0c73340099c1339', 'bucket_planter', 'Bucket Planter', 'Chậu Xô'],
	['e129ec25f266dc1ecb6e75f5992e636dc458afe7c3742ec139952daa9a9d41', 'bronze_pot', 'Bronze Pot', 'Chậu Đồng'],
	['7a7a11208eda74331e46c8443d70f9f350c34b8c2519521c978c5cc57cfa4f24', 'gold_pot', 'Gold Pot', 'Chậu Vàng'],
];

/**
 * The glazed terracotta set, one per dye colour.
 *
 * Purple is drawn from the same skin as cyan; that is how the datapack ships
 * it, and there is no purple art to point at instead. It stays in the set
 * rather than leaving a hole in a sixteen-colour row.
 */
const TERRACOTTA_TEXTURES: Record<Color, string> = {
	white: '62c618db76cd067c0e5f75a3f742594b1b9a062f26dc7326ee547fa5cb331',
	orange: '71c47ba77739a3d7265284938d6a33a42b1601bdb8b9c808813a7747848d333',
	magenta: '2ece434c3de27663e7f84c596807595ad1614b022903f39c7e521bd894813a3',
	light_blue: 'e68476c421f6c04960103dfc501e1d5ab5356cceb67abee9e7d2a1b4546cd0cc',
	yellow: '63f89f36c83df4994ec173aa8974b12ac629c609b78f6da825a2109e7da739',
	lime: 'bf53387a4891e180c67df1b662cdaf89b658a888e1e722b339e159fc3a',
	pink: '625a8dd1b7a915a4b739e22e72938d94cddbeaf57e5c22d3b566de9511773',
	gray: '71ce7ee653e1472daa66f728ae22519b5d74b0e1509d5de8ff2eaca58bfb5',
	light_gray: '4e159107698dd972f73a0c3634b6c41d8bd2f66e1a15070824ce93beb6e',
	cyan: 'c8b3e292f9980f8a5ddc4db30e785249cac51c4d41e55476a41de8424ee68',
	purple: 'c8b3e292f9980f8a5ddc4db30e785249cac51c4d41e55476a41de8424ee68',
	blue: '26fedb63782e6a797c88c49b4111986d28687fc85ae4a41d9530315fe2467d',
	brown: '89cd31b5ae8d807d158c35d6f21f4bc0f1b7e4cec28eeeb4c3b481825b347dc',
	green: 'e993ab4452477d928af9df36f81ff15171fcc492ff2d82f4bb888fa9547e7e',
	red: 'd463476ab895df62a68d57c31cce2a6626aa5c34059c423672aede833821cd',
	black: '18b26c9aff7d19944b04aa4dec95aef48ee3fa58f57157886133216e749e85',
};

/**
 * A pot is a head cube with a plant standing in it. The pedestal hitbox is the
 * heavy core's 8x8x8 block in the middle, which is the head's own shape to the
 * pixel, and it faces the placer so a decorated front can be turned to the room.
 */
const POT: Behaviour = {
	collider: 'pedestal',
	directional: true,
	hangable: true,
	hardness: 0.6,
	tool: 'pickaxe',
};

const POTS: Piece[] = [
	...POT_TEXTURES.map(([head, id, en, vi]) => ({ id, head, en, vi, pot: true, ...POT })),
	...COLORS.map((color) => ({
		id: `${color}_terracotta_pot`,
		head: TERRACOTTA_TEXTURES[color],
		en: `${COLOR_EN[color]} Terracotta Pot`,
		vi: `Chậu Gốm ${COLOR_VI[color]}`,
		pot: true,
		...POT,
	})),
];

/** One-off lighting and decor. */
const SINGLES: Piece[] = [
	{
		id: 'display_case',
		models: ['luna:block/display_case'],
		en: 'Display Case',
		vi: 'Tủ Trưng Bày',
		showcase: true,
		hardness: 1.0,
		tool: 'axe',
	},
	{
		id: 'jar_lamp',
		wireless: true,
		models: ['beautify:block/lamps/standing_lamp_jar', 'luna:block/jar_glow'],
		en: 'Standing Jar Lamp',
		vi: 'Đèn Lồng Thủy Tinh',
		collider: 'small',
		directional: true,
		// the jar sits on a pedestal so its body fills the structure void's own
		// 6x6x6 box in the middle of the block, which is where the outline is
		raise: 5,
		standModels: ['luna:block/jar_stand'],
		lamp: {
			offModels: ['beautify:block/lamps/standing_lamp_jar'],
			level: 14,
			offset: 1,
			ignite: 'hand',
			litByDefault: true,
		},
		hardness: 0.5,
		tool: 'pickaxe',
	},
	{
		id: 'bamboo_lamp',
		wireless: true,
		models: ['beautify:block/lamps/standing_lamp_bamboo'],
		en: 'Standing Bamboo Lamp',
		vi: 'Đèn Tre',
		collider: 'small',
		directional: true,
		lamp: {
			offModels: ['beautify:block/lamps/standing_lamp_bamboo_off'],
			level: 14,
			offset: 1,
			ignite: 'hand',
			litByDefault: true,
		},
		hardness: 0.5,
		tool: 'axe',
	},
	{
		id: 'light_bulb_lamp',
		wireless: true,
		models: ['beautify:block/lamps/standing_light_bulb'],
		en: 'Standing Light Bulb',
		vi: 'Đèn Bóng Tròn',
		collider: 'small',
		directional: true,
		lamp: {
			offModels: ['beautify:block/lamps/standing_light_bulb_off'],
			level: 15,
			offset: 1,
			ignite: 'hand',
			litByDefault: true,
		},
		hardness: 0.5,
		tool: 'pickaxe',
	},
	{
		id: 'red_candelabra',
		models: ['beautify:block/lamps/candelabras/standing_lamp_candelabra_red'],
		en: 'Standing Candelabra',
		vi: 'Đèn Nến Đứng',
		collider: 'small',
		directional: true,
		lamp: {
			offModels: ['beautify:block/lamps/candelabras/standing_lamp_candelabra_off_red'],
			level: 12,
			offset: 1,
			ignite: 'flint',
		},
		hardness: 0.8,
		tool: 'pickaxe',
	},
	{
		id: 'brazier',
		models: ['decorative_blocks:block/brazier'],
		en: 'Brazier',
		vi: 'Lư Than',
		lamp: {
			offModels: ['decorative_blocks:block/brazier_extinguished'],
			level: 15,
			offset: 1,
			ignite: 'flint',
		},
		hardness: 1.5,
		tool: 'pickaxe',
	},
	{
		id: 'soul_brazier',
		models: ['decorative_blocks:block/soul_brazier'],
		en: 'Soul Brazier',
		vi: 'Lư Than Linh Hồn',
		lamp: {
			offModels: ['decorative_blocks:block/brazier_extinguished'],
			level: 12,
			offset: 1,
			ignite: 'flint',
		},
		hardness: 1.5,
		tool: 'pickaxe',
	},
	{
		// always lit: the source draws its torches as crossed planes, so there
		// is no honest way to derive an unlit model from it
		id: 'chandelier',
		models: ['decorative_blocks:block/chandelier'],
		en: 'Chandelier',
		vi: 'Đèn Chùm',
		light: { offset: -1, level: 15 },
		directional: true,
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'soul_chandelier',
		models: ['decorative_blocks:block/soul_chandelier'],
		en: 'Soul Chandelier',
		vi: 'Đèn Chùm Linh Hồn',
		light: { offset: -1, level: 12 },
		directional: true,
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'lattice',
		models: ['decorative_blocks:block/lattice_open'],
		en: 'Lattice',
		vi: 'Giàn Lưới',
		// the screen is diagonal bars rather than slats, and a bar can be cut
		// along its own length: the arms come out of the same cutter, so a
		// lattice runs, turns and crosses like the trellis does
		connects: 'lattice',
		collider: 'pane',
		...WOODEN,
	},
];


// ---- the kitchen ---------------------------------------------------------

const KITCHEN_COUNTERS: Piece[] = ADORN_WOODS.map((wood) => ({
	id: `${wood}_kitchen_counter`,
	models: [`adorn:block/${woodPath(wood)}_kitchen_counter`],
	en: `${WOOD_EN[wood]} Kitchen Counter`,
	vi: `Tủ Bếp ${WOOD_VI[wood]}`,
	directional: true,
	storage: 3,
	...WOODEN,
}));

const KITCHEN_SINKS: Piece[] = ADORN_WOODS.map((wood) => ({
	id: `${wood}_kitchen_sink`,
	models: [`adorn:block/${woodPath(wood)}_kitchen_sink`],
	en: `${WOOD_EN[wood]} Kitchen Sink`,
	vi: `Bồn Rửa ${WOOD_VI[wood]}`,
	directional: true,
	storage: 3,
	...WOODEN,
}));

/**
 * A cupboard built here: a full-size cabinet with two doors on the front.
 *
 * Composed rather than borrowed, because neither source had the right shape:
 * adorn's "kitchen cupboard" is a counter with a door on it, and skniro's
 * wall cabinet is a five-pixel slab that read as squashed. The carcass fills
 * the whole block, the face frame and the two door leaves sit on the front
 * with a shadow gap between them, and the handles meet at the centre stile.
 */
function cupboard(wood: Wood): Model {
	const planks = `minecraft:block/${woodPath(wood)}_planks`;
	// STEEL is declared further down and this runs at module init
	const handle = 'minecraft:block/iron_block';

	const elements: Element[] = [
		part([0, 0, 1], [16, 16, 16], planks),

		// the face frame around the doors
		part([0, 14.5, 0], [16, 16, 1], planks),
		part([0, 0, 0], [16, 1.5, 1], planks),
		part([0, 1.5, 0], [1, 14.5, 1], planks),
		part([15, 1.5, 0], [16, 14.5, 1], planks),

		// two door leaves, set back a hair so the seams read as depth
		part([1.25, 1.75, 0.25], [7.75, 14.25, 1], planks),
		part([8.25, 1.75, 0.25], [14.75, 14.25, 1], planks),

		// handles beside the centre seam
		part([6.75, 6.5, -0.5], [7.5, 9.5, 0.25], handle),
		part([8.5, 6.5, -0.5], [9.25, 9.5, 0.25], handle),
	];

	return composed(elements, planks);
}

const CUPBOARDS: Piece[] = ADORN_WOODS.map((wood) => ({
	id: `${wood}_cupboard`,
	built: cupboard(wood),
	en: `${WOOD_EN[wood]} Cupboard`,
	vi: `Tủ ${WOOD_VI[wood]}`,
	directional: true,
	storage: 3,
	...WOODEN,
}));

// ---- blinds --------------------------------------------------------------

/** The woods Beautify draws blinds for; iron is one of them and is not a wood. */
const BLIND_WOODS: Array<[string, string, string]> = [
	['oak', 'Oak', 'Gỗ Sồi'],
	['spruce', 'Spruce', 'Gỗ Vân Sam'],
	['birch', 'Birch', 'Gỗ Bạch Dương'],
	['jungle', 'Jungle', 'Gỗ Nhiệt Đới'],
	['acacia', 'Acacia', 'Gỗ Keo'],
	['dark_oak', 'Dark Oak', 'Gỗ Sồi Đen'],
	['mangrove', 'Mangrove', 'Gỗ Đước'],
	['cherry', 'Cherry', 'Gỗ Anh Đào'],
	['crimson', 'Crimson', 'Gỗ Đỏ Thẫm'],
	['warped', 'Warped', 'Gỗ Vặn Xoắn'],
	['iron', 'Iron', 'Sắt'],
];

/**
 * Window blinds, which are the one piece here that is placed to be looked
 * through: they hang from the top of their block and roll up on a click.
 * Beautify calls the rolled-up state open, and so does ours - what a click
 * changes is whether the window is covered.
 */
const BLINDS: Piece[] = BLIND_WOODS.map(([key, en, vi]) => ({
	id: `${key}_blinds`,
	models: [`beautify:block/blinds/${key}_blinds_down`],
	openable: { models: [`beautify:block/blinds/${key}_blinds_up`], sound: 'shutter' as const },
	en: `${en} Blinds`,
	vi: `Rèm ${vi}`,
	directional: true,
	collider: 'flat',
	hardness: 0.5,
	tool: key === 'iron' ? 'pickaxe' as const : 'axe' as const,
}));

// ---- the small things ----------------------------------------------------

const BOOK_STACKS: Piece[] = [0, 1, 2, 3, 4, 5, 6].map((n) => ({
	id: `book_stack_${n + 1}`,
	models: [`beautify:block/bookstacks/bookstack_${n}`],
	en: `Book Stack ${n + 1}`,
	vi: `Chồng Sách ${n + 1}`,
	directional: true,
	collider: 'small',
	hardness: 0.5,
	tool: 'axe' as const,
}));

/** The paintings Beautify frames, by the vanilla motif each one carries. */
const PICTURES: Array<[string, string, string]> = [
	['alban', 'Alban', 'Ngôi Nhà'],
	['aztec', 'Aztec', 'Aztec'],
	['aztec2', 'Aztec II', 'Aztec II'],
	['beach', 'Beach', 'Bãi Biển'],
	['bomb', 'Bomb', 'Vụ Nổ'],
	['flower', 'Flower', 'Bông Hoa'],
	['frog', 'Frog', 'Ếch'],
	['kebab', 'Kebab', 'Xiên Nướng'],
	['plant', 'Plant', 'Chậu Cây'],
	['river', 'River', 'Dòng Sông'],
	['shroom', 'Shroom', 'Nấm'],
	['sunset', 'Sunset', 'Hoàng Hôn'],
	['wasteland', 'Wasteland', 'Hoang Mạc'],
];

const PICTURE_FRAMES: Piece[] = PICTURES.map(([key, en, vi]) => ({
	id: `${key}_picture`,
	models: [`beautify:block/pictureframes/oak_${key}_picture_frame`],
	en: `${en} Picture`,
	vi: `Tranh ${vi}`,
	directional: true,
	collider: 'flat',
	hardness: 0.5,
	tool: 'axe' as const,
}));

/** What a crate holds, which is only ever what is painted on its front. */
const CRATES: Array<[string, string, string]> = [
	['crate', 'Crate', 'Thùng Gỗ'],
	['apple_crate', 'Apple Crate', 'Thùng Táo'],
	['carrot_crate', 'Carrot Crate', 'Thùng Cà Rốt'],
	['potato_crate', 'Potato Crate', 'Thùng Khoai Tây'],
	['wheat_crate', 'Wheat Crate', 'Thùng Lúa Mì'],
	['egg_crate', 'Egg Crate', 'Thùng Trứng'],
	['honeycomb_crate', 'Honeycomb Crate', 'Thùng Sáp Ong'],
	['sweet_berry_crate', 'Berry Crate', 'Thùng Quả Mọng'],
];

/**
 * A crate is a plain cube, which Adorn draws by parenting onto vanilla's own
 * `cube_column` - a model this generator has no vanilla tree to resolve. The
 * cube is three lines to compose, so the crates are built here over Adorn's
 * own painted sides, which is the part that carries the art.
 */
const CRATE_PIECES: Piece[] = CRATES.map(([key, en, vi]): Piece => ({
	id: key,
	built: composed(
		[part([0, 0, 0], [16, 16, 16], { all: `adorn:block/${key}`, up: 'adorn:block/crate', down: 'adorn:block/crate' }, { cull: ['north', 'east', 'south', 'west', 'up', 'down'] })],
		`adorn:block/${key}`,
	),
	en,
	vi,
	storage: 3,
	...WOODEN,
}));

// ---- composed planters ---------------------------------------------------

/** A wall of terracotta with soil in it, given a floor, walls and a lip. */
function trough(
	floor: [Vec3, Vec3],
	walls: Array<[Vec3, Vec3]>,
	soil: [Vec3, Vec3],
	shell: string,
): Model {
	const elements: Element[] = [box(floor[0], floor[1], '#shell')];

	for (const [from, to] of walls) {
		elements.push(box(from, to, '#shell'));
	}

	elements.push(box(soil[0], soil[1], '#soil'));

	return {
		textures: { shell: `minecraft:block/${shell}`, soil: 'minecraft:block/coarse_dirt', particle: `minecraft:block/${shell}` },
		elements,
		display: DISPLAY,
	};
}

/**
 * The long planter and the wide basket: the two shapes the small pots cannot
 * make, which is a run of plants along a windowsill and a spread of them on a
 * table. Both hold several plants at once, filled front to back and emptied
 * back to front, so neither needs the click to be aimed at a particular slot.
 */
const COMPOSED_PLANTERS: Piece[] = [
	{
		id: 'long_planter',
		built: trough(
			[[0, 0, 4], [16, 2, 12]],
			[
				[[0, 2, 4], [16, 9, 5]],
				[[0, 2, 11], [16, 9, 12]],
				[[0, 2, 5], [1, 9, 11]],
				[[15, 2, 5], [16, 9, 11]],
			],
			[[1, 2, 5], [15, 7, 11]],
			'terracotta',
		),
		en: 'Long Planter',
		vi: 'Chậu Dài',
		pot: true,
		plantSlots: [[0.28, 0.4375, 0.5], [0.72, 0.4375, 0.5]],
		directional: true,
		collider: 'pedestal',
		hardness: 0.6,
		tool: 'pickaxe',
	},
	{
		id: 'flower_basket',
		built: trough(
			[[1, 0, 1], [15, 2, 15]],
			[
				[[0, 0, 0], [16, 6, 1]],
				[[0, 0, 15], [16, 6, 16]],
				[[0, 0, 1], [1, 6, 15]],
				[[15, 0, 1], [16, 6, 15]],
			],
			[[1, 2, 1], [15, 5, 15]],
			'bamboo_mosaic',
		),
		en: 'Flower Basket',
		vi: 'Giỏ Hoa',
		pot: true,
		plantSlots: [[0.3, 0.3125, 0.35], [0.7, 0.3125, 0.4], [0.5, 0.3125, 0.72]],
		directional: true,
		collider: 'pedestal',
		hardness: 0.4,
		tool: 'axe',
	},
];

// ---- flower crowns -------------------------------------------------------

/** A wearable crown: which flowers ring it, and what it is called. */
interface Crown {
	id: string;
	en: string;
	vi: string;
	/** The `minecraft:block/` sprites the ring cycles through. */
	flowers: string[];
}

const CROWN_SPECS: Crown[] = [
	{ id: 'red_crown', en: 'Red Flower Crown', vi: 'Vương Miện Hoa Đỏ', flowers: ['poppy', 'red_tulip'] },
	{ id: 'yellow_crown', en: 'Yellow Flower Crown', vi: 'Vương Miện Hoa Vàng', flowers: ['dandelion', 'wildflowers'] },
	{ id: 'blue_crown', en: 'Blue Flower Crown', vi: 'Vương Miện Hoa Xanh', flowers: ['cornflower', 'blue_orchid'] },
	{ id: 'white_crown', en: 'White Flower Crown', vi: 'Vương Miện Hoa Trắng', flowers: ['oxeye_daisy', 'lily_of_the_valley'] },
	{ id: 'pink_crown', en: 'Pink Flower Crown', vi: 'Vương Miện Hoa Hồng', flowers: ['pink_tulip', 'peony_top'] },
	{ id: 'black_crown', en: 'Wither Flower Crown', vi: 'Vương Miện Hoa Wither', flowers: ['wither_rose'] },
	{ id: 'tulip_crown', en: 'Tulip Crown', vi: 'Vương Miện Tulip', flowers: ['red_tulip', 'orange_tulip', 'white_tulip', 'pink_tulip'] },
];

/** A crown as the generator writes it: an id, both names and one item model. */
export interface CrownEntry {
	id: string;
	en: string;
	vi: string;
	model: Model;
}

/**
 * The wearable flower crowns.
 *
 * A head item with no equipment asset renders its own model on the head, which
 * is what [FurnitureItems.VONG_HOA] already relies on, so a crown is nothing
 * but a ring of flowers authored around where a head is.
 */
export const CROWNS: CrownEntry[] = CROWN_SPECS.map((crown) => ({
	id: crown.id,
	en: crown.en,
	vi: crown.vi,
	model: {
		textures: Object.fromEntries([
			...crown.flowers.map((flower, index) => [`f${index}`, `minecraft:block/${flower}`]),
			['leaf', 'minecraft:block/azalea_leaves'],
			['particle', `minecraft:block/${crown.flowers[0]}`],
		]),
		elements: garland(crown.flowers),
		display: CROWN_DISPLAY,
	},
}));

/** Everything luna-smp registers, in catalog order. */
// ---- the household -------------------------------------------------------
//
// Nothing below comes out of a pack. These are the pieces a house needs and
// no source we may copy from has, so they are built here out of our own boxes
// over vanilla textures, the same way the flora is: the client already has
// every texture named, so nothing is redistributed.

const WHITE = 'minecraft:block/quartz_block_side';
const WHITE_TOP = 'minecraft:block/quartz_block_top';
const STEEL = 'minecraft:block/iron_block';

/**
 * A bed, in two halves.
 *
 * A bed is two blocks long and everything here is one block, so it is placed
 * as a pair: the head carries the pillow and the headboard, the foot carries
 * the blanket's other end. Both are solid, so the pair collides the way the
 * sofas do, and either half stands on its own for a bunk or a daybed.
 */
function bedHalf(color: Color, head: boolean): Model {
	const wool = `minecraft:block/${color}_wool`;
	const elements: Element[] = [];

	// the legs, at the outer corners of whichever half this is
	const legZ: [number, number] = head ? [1, 3] : [13, 15];

	for (const x of [[1, 3], [13, 15]] as Array<[number, number]>) {
		elements.push(part([x[0], 0, legZ[0]], [x[1], 3, legZ[1]], 'minecraft:block/stripped_oak_log'));
	}

	if (head) {
		elements.push(part([0, 3, 0], [16, 15, 1], 'minecraft:block/oak_planks'));
		elements.push(part([0, 3, 1], [16, 6, 16], 'minecraft:block/oak_planks'));
		elements.push(part([1, 6, 1], [15, 9, 16], 'minecraft:block/white_wool'));
		elements.push(part([2, 9, 2], [14, 12, 7], 'minecraft:block/white_wool'));
		elements.push(part([1, 9, 7], [15, 10, 16], wool));
	} else {
		elements.push(part([0, 3, 15], [16, 11, 16], 'minecraft:block/oak_planks'));
		elements.push(part([0, 3, 0], [16, 6, 15], 'minecraft:block/oak_planks'));
		elements.push(part([1, 6, 0], [15, 9, 15], 'minecraft:block/white_wool'));
		elements.push(part([1, 9, 0], [15, 10, 15], wool));
	}

	return composed(elements, wool);
}

const BEDS: Piece[] = COLORS.flatMap((color): Piece[] => [
	{
		id: `${color}_bed_head`,
		built: bedHalf(color, true),
		en: `${COLOR_EN[color]} Bed Head`,
		vi: `Đầu Giường ${COLOR_VI[color]}`,
		directional: true,
		seatHeight: 0.5625,
		hardness: 0.5,
		tool: 'axe',
	},
	{
		id: `${color}_bed_foot`,
		built: bedHalf(color, false),
		en: `${COLOR_EN[color]} Bed Foot`,
		vi: `Chân Giường ${COLOR_VI[color]}`,
		directional: true,
		seatHeight: 0.5625,
		hardness: 0.5,
		tool: 'axe',
	},
]);

/** A fridge: a steel box with two doors and a handle on each. */
function refrigerator(): Model {
	const door = { all: STEEL, north: 'minecraft:block/smooth_stone' };

	return {
		textures: { particle: STEEL },
		elements: [
			part([1, 0, 1], [15, 16, 15], STEEL),
			part([0.5, 0.5, 0.5], [15.5, 9, 2], door),
			part([0.5, 9.5, 0.5], [15.5, 15.5, 2], door),
			part([12.5, 3, 0], [13.5, 7, 0.5], STEEL),
			part([12.5, 10.5, 0], [13.5, 14, 0.5], STEEL),
		],
		display: DISPLAY,
	};
}

/** A toilet: bowl, seat and cistern, in quartz because porcelain is not a block. */
function toilet(): Model {
	return {
		textures: { particle: WHITE },
		elements: [
			part([4, 0, 5], [12, 7, 13], { all: WHITE, up: WHITE_TOP, down: WHITE_TOP }),
			part([3, 7, 4], [13, 8.5, 14], { all: WHITE, up: WHITE_TOP, down: WHITE_TOP }),
			part([3, 0, 0], [13, 16, 5], { all: WHITE, up: WHITE_TOP, down: WHITE_TOP }),
			part([6.5, 16, 1.5], [9.5, 16.5, 3.5], STEEL),
		],
		display: DISPLAY,
	};
}

/** A fish tank: gravel, water and a little kelp behind glass. */
function aquarium(): Model {
	const glass = 'minecraft:block/glass';
	const water = 'minecraft:block/light_blue_stained_glass';
	const frame = 'minecraft:block/dark_oak_planks';

	return composed([
			part([0, 0, 0], [16, 2, 16], frame),
			part([1, 2, 1], [15, 4, 15], 'minecraft:block/gravel'),
			part([1, 4, 1], [15, 13, 15], water),
			...cross('minecraft:block/kelp_plant', 5, 6, 4, 9, 6),
			...cross('minecraft:block/kelp_plant', 11, 10, 4, 8, 5),
			part([0, 2, 0], [16, 15, 1], glass),
			part([0, 2, 15], [16, 15, 16], glass),
			part([0, 2, 1], [1, 15, 15], glass),
			part([15, 2, 1], [16, 15, 15], glass),
			part([0, 15, 0], [16, 16, 2], frame),
			part([0, 15, 14], [16, 16, 16], frame),
			part([0, 15, 2], [2, 16, 14], frame),
			part([14, 15, 2], [16, 16, 14], frame),
	], frame);
}

/**
 * The wall clock's live hands: two dark blades a tile spins with the world's
 * own time, hour short and wide, minute long and slim, authored pointing at
 * twelve with a one-pixel tail past the pivot - item models, because a
 * display entity can only wear a model an item owns, like the gauge needles.
 */
export function clockHandModels(): Record<string, Model> {
	const hand = 'minecraft:block/coal_block';
	const out: Record<string, Model> = {};

	// '#t0' vars, not bare paths: these are written verbatim as item models,
	// with no adoptAll pass to rewrite raw texture references (the diode
	// learned this the hard way)
	out.clock_hand_hour = {
		textures: { t0: hand, particle: hand },
		elements: [part([7.3, 7, 7.75], [8.7, 12, 8.25], '#t0')],
	} as unknown as Model;

	out.clock_hand_minute = {
		textures: { t0: hand, particle: hand },
		elements: [part([7.55, 7, 7.75], [8.45, 13.8, 8.25], '#t0')],
	} as unknown as Model;

	return out;
}

/** A wall clock: a framed face with two hands, hung facing the room. */
function wallClock(): Model {
	const frame = 'minecraft:block/dark_oak_planks';
	const hand = 'minecraft:block/coal_block';

	return {
		textures: { particle: frame },
		elements: [
			part([3, 3, 13], [13, 13, 15], frame),
			part([4, 4, 12.5], [12, 12, 13], { all: WHITE, north: WHITE_TOP }),
			part([7.5, 8, 12], [8.5, 11.5, 12.5], hand),
			part([8, 7.5, 12], [10.5, 8.5, 12.5], hand),
		],
		display: DISPLAY,
	};
}

/**
 * A vanity: a chest of drawers with a mirror standing on it.
 *
 * The mirror runs up out of the block it is placed in, which is the only way
 * a dressing table is the right height without being two blocks to place.
 */
function vanity(wood: Wood): Model {
	const planks = `minecraft:block/${woodPath(wood)}_planks`;
	const glass = 'minecraft:block/light_gray_concrete';
	const elements: Element[] = [
		part([1, 0, 3], [15, 12, 14], planks),
		part([0, 12, 2], [16, 13, 15], planks),
	];

	// three drawer fronts, each proud of the body, with a handle across it
	for (const y of [1, 4.5, 8]) {
		elements.push(part([2, y, 2.25], [14, y + 3, 3], planks));
		elements.push(part([5.5, y + 1.1, 1.75], [10.5, y + 1.9, 2.25], STEEL));
	}

	// the mirror stands on the top at the back: a frame on all four sides
	// with the glass set into it, rather than the bare board the first cut
	// left sticking up out of the block
	elements.push(part([3, 13, 12], [4.5, 26, 13], planks));
	elements.push(part([11.5, 13, 12], [13, 26, 13], planks));
	elements.push(part([4.5, 24.5, 12], [11.5, 26, 13], planks));
	elements.push(part([4.5, 13, 12], [11.5, 14.5, 13], planks));
	elements.push(part([4.5, 14.5, 12.2], [11.5, 24.5, 12.8], glass));

	return composed(elements, planks);
}

const VANITIES: Piece[] = WOODS.map((wood): Piece => ({
	id: `${wood}_vanity`,
	built: vanity(wood),
	en: `${WOOD_EN[wood]} Vanity`,
	vi: `Bàn Trang Điểm ${WOOD_VI[wood]}`,
	directional: true,
	storage: 3,
	...WOODEN,
}));

/**
 * A glass door: a block wide and two tall, so it is placed as a pair like the
 * beds and the fridges, and either half swings both.
 *
 * Closed, the leaf stands across the middle of its block; open, it lies along
 * the west edge, a quarter turn about its hinge. The hitbox is a real door's,
 * so the doorway is only blocked where the leaf actually is.
 */
// The leaf hugs the block edge exactly the way the warped-door hitbox behind
// it does, so the outline and the glass agree. Closed, the vanilla panel for
// our facing sits on the facing-side edge (model north, z 0..3); open on the
// left hinge it stands against the east edge (x 13..16), hinge at the
// north-east corner. The leaf is two pixels inside the three-pixel hitbox.
function glassDoor(open: boolean, upper: boolean): Model {
	const glass = 'minecraft:block/glass';
	const elements: Element[] = [];

	// the handle is a vertical pull bar through the leaf, on the edge away
	// from the hinge, straddling the seam between the halves: the lower block
	// carries the bottom of the bar, the upper block the top, and together
	// they read as one handle at hand height on a two-block door
	if (open) {
		elements.push(part([14, 0, 0], [16, 16, 2], STEEL));
		elements.push(part([14, 0, 14], [16, 16, 16], STEEL));
		elements.push(part([14.5, 0, 2], [15.5, 16, 14], glass));

		if (upper) {
			elements.push(part([14, 14, 2], [16, 16, 14], STEEL));
			elements.push(part([13.25, 0, 11.5], [16.75, 5, 12.5], STEEL));
		} else {
			elements.push(part([14, 0, 2], [16, 2, 14], STEEL));
			elements.push(part([13.25, 11, 11.5], [16.75, 16, 12.5], STEEL));
		}
	} else {
		elements.push(part([0, 0, 0], [2, 16, 2], STEEL));
		elements.push(part([14, 0, 0], [16, 16, 2], STEEL));
		elements.push(part([2, 0, 0.5], [14, 16, 1.5], glass));

		if (upper) {
			elements.push(part([2, 14, 0], [14, 16, 2], STEEL));
			elements.push(part([3.5, 0, -0.75], [4.5, 5, 2.75], STEEL));
		} else {
			elements.push(part([2, 0, 0], [14, 2, 2], STEEL));
			elements.push(part([3.5, 11, -0.75], [4.5, 16, 2.75], STEEL));
		}
	}

	return composed(elements, STEEL);
}

// The glass hatch: a framed pane lying in the bottom three pixels; open, it
// stands against the far edge, which is where the backing trapdoor's own
// swing puts its outline for our facing.
function glassTrapdoor(open: boolean, top = false): Model {
	const glass = 'minecraft:block/glass';
	const elements: Element[] = [];

	if (open) {
		elements.push(part([0, 0, 14], [2, 16, 16], STEEL));
		elements.push(part([14, 0, 14], [16, 16, 16], STEEL));
		elements.push(part([2, 0, 14], [14, 2, 16], STEEL));
		elements.push(part([2, 14, 14], [14, 16, 16], STEEL));
		elements.push(part([2, 2, 14.5], [14, 14, 15.5], glass));
	} else {
		// the closed leaf lies in the bottom two pixels, or the top two for
		// the upper half - lifted whole, the way a vanilla trapdoor's is
		const lift = top ? 14 : 0;

		elements.push(part([0, lift, 0], [16, lift + 2, 2], STEEL));
		elements.push(part([0, lift, 14], [16, lift + 2, 16], STEEL));
		elements.push(part([0, lift, 2], [2, lift + 2, 14], STEEL));
		elements.push(part([14, lift, 2], [16, lift + 2, 14], STEEL));
		elements.push(part([2, lift + 0.5, 2], [14, lift + 1.5, 14], glass));
	}

	return composed(elements, STEEL);
}

const HOUSEHOLD: Piece[] = [
	{
		id: 'aquarium',
		built: aquarium(),
		en: 'Aquarium',
		vi: 'Bể Cá',
		aquarium: true,
		directional: true,
		light: { offset: 1, level: 6 },
		hardness: 0.8,
		tool: 'pickaxe',
	},
	{
		id: 'glass_door',
		built: glassDoor(false, false),
		openable: { built: glassDoor(true, false), sound: 'door' },
		en: 'Glass Door',
		vi: 'Cửa Kính',
		directional: true,
		collider: 'door',
		hardness: 1.5,
		tool: 'pickaxe',
	},
	{
		id: 'glass_door_top',
		built: glassDoor(false, true),
		openable: { built: glassDoor(true, true), sound: 'door' },
		en: 'Glass Door Top',
		vi: 'Cửa Kính (Trên)',
		directional: true,
		collider: 'door',
		upper: true,
		hardness: 1.5,
		tool: 'pickaxe',
	},
	{
		id: 'glass_trapdoor',
		built: glassTrapdoor(false),
		topModel: glassTrapdoor(false, true),
		openable: { built: glassTrapdoor(true), sound: 'door' },
		en: 'Glass Trapdoor',
		vi: 'Cửa Sập Kính',
		directional: true,
		collider: 'trapdoor',
		hardness: 1.5,
		tool: 'pickaxe',
	},
];

// ---- the bathroom and the appliances -------------------------------------
//
// Three sources beyond the original four, each read for its own art and each
// checked at the repository rather than at the download page: Lucky's Cozy
// Home (CC0-1.0), skniro's Furniture (MIT) and Furnies (MIT). All three carry
// one licence over the whole tree with no clause holding the art back, which
// is the thing Floral Enchantment taught us to look for.

const BATHROOM: Piece[] = [
	{
		// Furnies', not Cozy Home's: Cozy Home ships one toilet texture and
		// its template's uv net does not land on it, so most of the model
		// samples empty space and the thing renders as a blank slab
		id: 'toilet',
		models: ['furnies:block/toilet/light_toilet_closed'],
		openable: { models: ['furnies:block/toilet/light_toilet_open'], sound: 'shutter' },
		en: 'Toilet',
		vi: 'Bồn Cầu',
		directional: true,
		seatHeight: 0.5,
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		// the water is our own geometry over our own painted texture: vanilla's
		// water is grey until a biome tints it, and a display entity is never
		// asked what biome it is in
		id: 'bathtub',
		models: ['cozyhome:block/andesite_bathtub'],
		openable: { models: ['cozyhome:block/andesite_bathtub', 'luna:block/bathtub_water'], sound: 'tap' },
		openPartner: 'bathtub_tap',
		en: 'Bathtub',
		vi: 'Bồn Tắm',
		directional: true,
		seatHeight: 0.4,
		hardness: 1.5,
		tool: 'pickaxe',
	},
	{
		// a click turns the tap: the source draws the running state by turning
		// the handle through 45 degrees, and that is the whole difference
		id: 'bathtub_tap',
		models: ['cozyhome:block/andesite_bathtub_back'],
		openable: { models: ['cozyhome:block/andesite_bathtub_back_on', 'luna:block/bathtub_tap_water'], sound: 'tap' },
		openPartner: 'bathtub',
		en: 'Bathtub Tap End',
		vi: 'Đầu Vòi Bồn Tắm',
		directional: true,
		hardness: 1.5,
		tool: 'pickaxe',
	},
	{
		// the tap is part of the sink template already - merging Cozy Home's
		// separate faucet block on top of it drew every face twice. What the
		// template does not carry is a definition for its own `missing`
		// variable, which is the drain, so that is supplied here.
		id: 'sink',
		models: ['cozyhome:block/andesite_sink'],
		openable: { models: ['cozyhome:block/andesite_sink_on', 'luna:block/sink_water'], sound: 'tap' },
		textures: { missing: 'cozyhome:block/faucet/iron_faucet' },
		en: 'Sink',
		vi: 'Bồn Rửa Mặt',
		directional: true,
		hardness: 1.2,
		tool: 'pickaxe',
	},
];

const MIRRORS: Piece[] = ADORN_WOODS.map((wood): Piece => ({
	id: `${wood}_mirror`,
	models: [`cozyhome:block/${woodPath(wood)}_wall_mirror`],
	en: `${WOOD_EN[wood]} Mirror`,
	vi: `Gương ${WOOD_VI[wood]}`,
	directional: true,
	collider: 'flat',
	hardness: 0.6,
	tool: 'axe',
}));

/**
 * The clock is Analog's (CC0), and it is the whole reason that mod was read:
 * Cozy Home's clocks look better and cannot be ported at all, because they
 * are drawn by that mod's own block-entity renderer and their model files are
 * empty shells. A model with no elements is an invisible block, which is what
 * the generator now refuses outright.
 */
const CLOCKS: Piece[] = [
	{
		id: 'wall_clock',
		models: ['analogclock:block/analog_clock'],
		en: 'Wall Clock',
		vi: 'Đồng Hồ Treo Tường',
		clock: true,
		directional: true,
		collider: 'flat',
		hardness: 0.8,
		tool: 'axe',
	},
];

/** The fridges skniro paints, which is a concrete colour and a terracotta trim. */
const FRIDGE_COLORS: Color[] = ['white', 'light_gray', 'gray', 'black', 'red', 'light_blue', 'yellow', 'lime'];

const APPLIANCES: Piece[] = [
	// a fridge is a block wide and two tall, so it is placed as a pair the way
	// the beds are: skniro's one-block `fridge` is the little under-counter
	// model and reads as a cupboard next to anything full height
	...FRIDGE_COLORS.flatMap((color): Piece[] => [
		{
			id: `${color}_refrigerator`,
			models: [`skniro:block/${color}_fridge_bottom`],
			en: `${COLOR_EN[color]} Refrigerator`,
			vi: `Tủ Lạnh ${COLOR_VI[color]}`,
			directional: true,
			storage: 3,
			hardness: 1.5,
			tool: 'pickaxe',
		},
		{
			id: `${color}_refrigerator_top`,
			models: [`skniro:block/${color}_fridge_top`],
			en: `${COLOR_EN[color]} Refrigerator Top`,
			vi: `Ngăn Đá Tủ Lạnh ${COLOR_VI[color]}`,
			directional: true,
			storage: 3,
			hardness: 1.5,
			tool: 'pickaxe',
		},
	]),

	...ADORN_WOODS.map((wood): Piece => ({
		id: `${wood}_oven`,
		models: [`skniro:block/${woodPath(wood)}_planks_oven`],
		en: `${WOOD_EN[wood]} Oven`,
		vi: `Lò Nướng ${WOOD_VI[wood]}`,
		directional: true,
		storage: 3,
		hardness: 1.5,
		tool: 'pickaxe',
	})),

	...ADORN_WOODS.map((wood): Piece => ({
		id: `${wood}_tv`,
		models: [`skniro:block/${woodPath(wood)}_planks_tv`],
		en: `${WOOD_EN[wood]} Television`,
		vi: `TV ${WOOD_VI[wood]}`,
		directional: true,
		hardness: 1.0,
		tool: 'axe',
	})),

	...ADORN_WOODS.map((wood): Piece => ({
		id: `${wood}_tv_stand`,
		models: [`skniro:block/${woodPath(wood)}_planks_tv_stand`],
		en: `${WOOD_EN[wood]} TV Stand`,
		vi: `Kệ TV ${WOOD_VI[wood]}`,
		directional: true,
		storage: 3,
		...WOODEN,
	})),
];

export const CATALOG: Piece[] = [
	...CHAIRS,
	...BENCHES,
	...TABLES,
	...COFFEE_TABLES,
	...SHELVES,
	...DRAWERS,
	...SOFAS,
	...TABLE_LAMPS,
	...PLANTERS,
	...TRELLISES,
	...POTS,
	...COMPOSED_PLANTERS,
	...SINGLES,
	...KITCHEN_COUNTERS,
	...KITCHEN_SINKS,
	...CUPBOARDS,
	...BLINDS,
	...BOOK_STACKS,
	...PICTURE_FRAMES,
	...CRATE_PIECES,
	...BEDS,
	...VANITIES,
	...HOUSEHOLD,
	...BATHROOM,
	...MIRRORS,
	...CLOCKS,
	...APPLIANCES,
	...INDUSTRIAL,
	...STEELWORKS,
];
