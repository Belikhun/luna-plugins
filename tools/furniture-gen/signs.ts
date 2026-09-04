// The sign catalog: road signs, work safety signs, electrical signs, street
// name plates and blackboards.
//
// Two kinds of sign, and the difference is where the words live.
//
// A *pictogram* sign says what it says in its texture: a shape, a field colour
// and a symbol, all painted in signart.ts. Those are fixed, so they cost the
// pack one 32x32 texture each and the server nothing at all.
//
// A *text* sign says whatever it is written on, so its words cannot be a
// texture: the board is blank art, and a text display entity floats a hair in
// front of it carrying what somebody typed. The blackboard goes one further -
// lay a solid rectangle of them and they merge into a single board, with one
// text display spanning the lot.
//
// Every sign is one flat plane wearing its face, which is what lets a triangle
// be a triangle: the corners of the texture are transparent and there is no
// geometry behind them to fill the hole in. The plane is two-sided, showing a
// plain grey silhouette of the same shape from behind.
//
// The mods the user pointed at (ClickSigns, LandOfSignals, Ultimate Road Signs)
// were read for what a sign set needs to contain, not for art: LandOfSignals is
// All Rights Reserved outright, and nothing is copied from the other two either.
// Every pixel here is drawn in signart.ts.

import { type Element, type Model, DISPLAY, part } from './compose';
import {
	type Face,
	type Shape,
	INK,
	PAPER,
	SIGN_BLUE,
	SIGN_GREEN,
	SIGN_RED,
	SIGN_WHITE,
	SIGN_YELLOW,
	signBack,
	signFace,
} from './signart';
import { bitmap, encodePng, put, rgb, tint } from './pixels';

/** How a sign is written on, when it is. */
export interface TextArea {
	/** How wide the writable area is, in blocks. */
	width: number;
	/** How tall it is, in blocks. */
	height: number;
	/** Characters that fit across one block of board at the chosen size. */
	across: number;
	/** The colour unwritten and written text takes, as `0xRRGGBB`. */
	colour: number;
	/** Whether the text is drawn with a drop shadow. */
	shadow: boolean;
	/** Adjacent boards in the same plane merge into one: the blackboard. */
	merges: boolean;
}

export interface Sign {
	/** Block id inside the lunasmp namespace. */
	id: string;
	en: string;
	vi: string;
	/** The painted face, or a painter for the ones a shape cannot describe. */
	face?: Face | (() => Uint8Array);
	/** The silhouette the back takes; the face's own shape by default. */
	shape?: Shape;
	/** Present when the sign carries a text display. */
	text?: TextArea;
	/**
	 * The entry is not a sign at all but the bare pole a sign stands on, which
	 * is what makes the height a builder's choice rather than ours: stack as
	 * many as the sign wants to be tall and put the sign on top. It carries no
	 * face, no facing and no mounting - a pole looks the same from everywhere.
	 */
	post?: boolean;
	hardness: number;
	/** Which tool class mines it fastest. */
	tool: 'pickaxe' | 'axe';
}

// ---- the blackboard's own face -------------------------------------------

/** A tiny deterministic hash in [0, 1). */
function noise(seed: number, x: number, y: number): number {
	let h = seed ^ Math.imul(x + 11, 374761393) ^ Math.imul(y + 7, 668265263);
	h = Math.imul(h ^ (h >>> 13), 1274126177);

	return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

/**
 * Slate, painted so it tiles.
 *
 * A merged blackboard is a grid of these side by side, so anything that reads
 * as a mark - a smudge, a highlight, a border - would repeat once per block
 * and give the join away. What is left is a very fine grain, which averages
 * out at any distance a player reads a board from.
 */
function slateFace(): Uint8Array {
	const image = bitmap(32, 32);
	const base = rgb('#243029');

	for (let y = 0; y < 32; y++) {
		for (let x = 0; x < 32; x++) {
			const grain = noise(0x7f4a7c15, x, y) * 0.13 + 0.94;

			put(image, x, y, tint(base, grain));
		}
	}

	return encodePng(image);
}

/** Galvanised steel: the post a sign stands on and the bracket it hangs by. */
function postSprite(): Uint8Array {
	const image = bitmap(16, 16);
	const base = rgb('#767c84');

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const grain = noise(0x2545f491, x >> 1, y) * 0.12 + 0.93;

			put(image, x, y, tint(base, grain));
		}
	}

	return encodePng(image);
}

// ---- the catalog ---------------------------------------------------------

const ROAD: Sign[] = [
	{
		id: 'sign_stop',
		en: 'Stop Sign',
		vi: 'Biển Dừng Lại',
		shape: 'octagon',
		face: {
			shape: 'octagon',
			field: SIGN_RED,
			border: { colour: SIGN_WHITE, width: 2 },
			text: { word: 'STOP', scale: 1, colour: SIGN_WHITE },
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_give_way',
		en: 'Give Way Sign',
		vi: 'Biển Nhường Đường',
		shape: 'triangle_down',
		face: {
			shape: 'triangle_down',
			field: SIGN_WHITE,
			border: { colour: SIGN_RED, width: 4 },
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_no_entry',
		en: 'No Entry Sign',
		vi: 'Biển Cấm Đi Vào',
		shape: 'disc',
		face: {
			shape: 'disc',
			field: SIGN_RED,
			border: { colour: SIGN_WHITE, width: 2 },
			bar: { colour: SIGN_WHITE, height: 8 },
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	...([30, 50, 80] as const).map((limit): Sign => ({
		id: `sign_speed_${limit}`,
		en: `Speed Limit ${limit}`,
		vi: `Biển Tốc Độ ${limit}`,
		shape: 'disc',
		face: {
			shape: 'disc',
			field: SIGN_WHITE,
			border: { colour: SIGN_WHITE, width: 1 },
			inner: { colour: SIGN_RED, width: 4 },
			text: { word: String(limit), scale: 2, colour: INK },
		},
		hardness: 1.0,
		tool: 'pickaxe',
	})),
	{
		id: 'sign_no_parking',
		en: 'No Parking Sign',
		vi: 'Biển Cấm Đỗ Xe',
		shape: 'disc',
		// the standard sign is the blue field, the red ring and one stroke;
		// an earlier cut put a white P under the stroke and the two fought
		face: {
			shape: 'disc',
			field: SIGN_BLUE,
			border: { colour: SIGN_RED, width: 4 },
			slash: SIGN_RED,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_parking',
		en: 'Parking Sign',
		vi: 'Biển Bãi Đỗ Xe',
		shape: 'square',
		face: {
			shape: 'square',
			field: SIGN_BLUE,
			border: { colour: SIGN_WHITE, width: 2 },
			text: { word: 'P', scale: 3, colour: SIGN_WHITE },
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_crossing',
		en: 'Pedestrian Crossing Sign',
		vi: 'Biển Đường Người Đi Bộ',
		shape: 'square',
		face: {
			shape: 'square',
			field: SIGN_BLUE,
			border: { colour: SIGN_WHITE, width: 2 },
			symbol: 'pedestrian',
			symbolColour: SIGN_WHITE,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_no_pedestrians',
		en: 'No Pedestrians Sign',
		vi: 'Biển Cấm Người Đi Bộ',
		shape: 'disc',
		face: {
			shape: 'disc',
			field: SIGN_WHITE,
			border: { colour: SIGN_RED, width: 4 },
			symbol: 'pedestrian',
			symbolColour: INK,
			slash: SIGN_RED,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_roadworks',
		en: 'Roadworks Sign',
		vi: 'Biển Công Trường',
		shape: 'triangle',
		face: {
			shape: 'triangle',
			field: SIGN_WHITE,
			border: { colour: SIGN_RED, width: 4 },
			symbol: 'digger',
			symbolColour: INK,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_priority_road',
		en: 'Priority Road Sign',
		vi: 'Biển Đường Ưu Tiên',
		shape: 'diamond',
		face: {
			shape: 'diamond',
			field: SIGN_YELLOW,
			border: { colour: SIGN_WHITE, width: 3 },
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_one_way',
		en: 'One Way Sign',
		vi: 'Biển Đường Một Chiều',
		shape: 'square',
		face: {
			shape: 'square',
			field: SIGN_BLUE,
			border: { colour: SIGN_WHITE, width: 2 },
			arrow: SIGN_WHITE,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
];

const SAFETY: Sign[] = [
	{
		id: 'sign_hard_hat',
		en: 'Hard Hat Sign',
		vi: 'Biển Bắt Buộc Đội Mũ',
		shape: 'disc',
		face: { shape: 'disc', field: SIGN_BLUE, symbol: 'helmet', symbolColour: SIGN_WHITE },
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_ear_protection',
		en: 'Ear Protection Sign',
		vi: 'Biển Bắt Buộc Bịt Tai',
		shape: 'disc',
		face: { shape: 'disc', field: SIGN_BLUE, symbol: 'earmuffs', symbolColour: SIGN_WHITE },
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_eye_protection',
		en: 'Eye Protection Sign',
		vi: 'Biển Bắt Buộc Kính Bảo Hộ',
		shape: 'disc',
		face: { shape: 'disc', field: SIGN_BLUE, symbol: 'goggles', symbolColour: SIGN_WHITE },
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_no_smoking',
		en: 'No Smoking Sign',
		vi: 'Biển Cấm Hút Thuốc',
		shape: 'disc',
		face: {
			shape: 'disc',
			field: SIGN_WHITE,
			border: { colour: SIGN_RED, width: 4 },
			symbol: 'cigarette',
			symbolColour: INK,
			slash: SIGN_RED,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_no_fire',
		en: 'No Open Flame Sign',
		vi: 'Biển Cấm Lửa',
		shape: 'disc',
		face: {
			shape: 'disc',
			field: SIGN_WHITE,
			border: { colour: SIGN_RED, width: 4 },
			symbol: 'flame',
			symbolColour: INK,
			slash: SIGN_RED,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_slippery',
		en: 'Slippery Floor Sign',
		vi: 'Biển Sàn Trơn Trượt',
		shape: 'triangle',
		face: {
			shape: 'triangle',
			field: SIGN_YELLOW,
			border: { colour: INK, width: 3 },
			symbol: 'slip',
			symbolColour: INK,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_falling_objects',
		en: 'Falling Objects Sign',
		vi: 'Biển Vật Rơi',
		shape: 'triangle',
		face: {
			shape: 'triangle',
			field: SIGN_YELLOW,
			border: { colour: INK, width: 3 },
			symbol: 'falling',
			symbolColour: INK,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_first_aid',
		en: 'First Aid Sign',
		vi: 'Biển Sơ Cứu',
		shape: 'square',
		face: {
			shape: 'square',
			field: SIGN_GREEN,
			border: { colour: SIGN_WHITE, width: 2 },
			symbol: 'cross',
			symbolColour: SIGN_WHITE,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_emergency_exit',
		en: 'Emergency Exit Sign',
		vi: 'Biển Lối Thoát Hiểm',
		shape: 'square',
		face: {
			shape: 'square',
			field: SIGN_GREEN,
			border: { colour: SIGN_WHITE, width: 2 },
			symbol: 'runner',
			symbolColour: SIGN_WHITE,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_fire_extinguisher',
		en: 'Fire Extinguisher Sign',
		vi: 'Biển Bình Chữa Cháy',
		shape: 'square',
		face: {
			shape: 'square',
			field: SIGN_RED,
			border: { colour: SIGN_WHITE, width: 2 },
			symbol: 'extinguisher',
			symbolColour: SIGN_WHITE,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
];

const ELECTRICAL: Sign[] = [
	{
		id: 'sign_high_voltage',
		en: 'High Voltage Sign',
		vi: 'Biển Điện Cao Thế',
		shape: 'triangle',
		face: {
			shape: 'triangle',
			field: SIGN_YELLOW,
			border: { colour: INK, width: 3 },
			symbol: 'bolt',
			symbolColour: INK,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_danger_of_death',
		en: 'Danger of Death Sign',
		vi: 'Biển Nguy Hiểm Chết Người',
		shape: 'triangle',
		face: {
			shape: 'triangle',
			field: SIGN_YELLOW,
			border: { colour: INK, width: 3 },
			symbol: 'skull',
			symbolColour: INK,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_earth_point',
		en: 'Earthing Point Sign',
		vi: 'Biển Điểm Nối Đất',
		shape: 'square',
		face: {
			shape: 'square',
			field: SIGN_YELLOW,
			border: { colour: INK, width: 2 },
			symbol: 'earth',
			symbolColour: INK,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'sign_no_water',
		en: 'No Water Sign',
		vi: 'Biển Cấm Dùng Nước',
		shape: 'disc',
		face: {
			shape: 'disc',
			field: SIGN_WHITE,
			border: { colour: SIGN_RED, width: 4 },
			symbol: 'droplet',
			symbolColour: SIGN_BLUE,
			slash: SIGN_RED,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
];

const WRITTEN: Sign[] = [
	{
		id: 'street_sign',
		en: 'Street Name Sign',
		vi: 'Biển Tên Đường',
		shape: 'plate',
		face: {
			shape: 'plate',
			field: SIGN_GREEN,
			border: { colour: SIGN_WHITE, width: 2 },
		},
		text: {
			width: 0.82,
			height: 0.44,
			across: 14,
			colour: 0xffffff,
			shadow: false,
			merges: false,
		},
		hardness: 1.0,
		tool: 'pickaxe',
	},
	{
		id: 'notice_board',
		en: 'Notice Board',
		vi: 'Bảng Thông Báo',
		shape: 'square',
		face: {
			shape: 'square',
			field: PAPER,
			border: { colour: rgb('#6b4a2a'), width: 3 },
		},
		text: {
			width: 0.72,
			height: 0.72,
			across: 16,
			colour: 0x2a2622,
			shadow: false,
			merges: false,
		},
		hardness: 1.0,
		tool: 'axe',
	},
	{
		id: 'blackboard',
		en: 'Blackboard',
		vi: 'Bảng Đen',
		shape: 'square',
		face: slateFace,
		text: {
			// edge to edge, because a merged board has no margin between its
			// blocks to give one back
			width: 0.94,
			height: 0.94,
			across: 16,
			colour: 0xf2f0e6,
			shadow: false,
			merges: true,
		},
		hardness: 1.2,
		tool: 'pickaxe',
	},
];

/**
 * The pole on its own.
 *
 * A sign block is one block tall and its pole fills only that block, so a sign
 * placed on the ground sits at ankle height. Rather than fixing every sign at
 * some particular height, the height is a block: stack two of these and put a
 * stop sign on top for a road sign, stack four for a gantry. The geometry is
 * the same column the posted signs use, so a stack of them and the sign above
 * are one continuous pole with no seam.
 */
const SIGN_POST: Sign = {
	id: 'sign_post',
	en: 'Sign Post',
	vi: 'Cột Biển Báo',
	post: true,
	hardness: 1.0,
	tool: 'pickaxe',
};

export const SIGNS: Sign[] = [...ROAD, ...SAFETY, ...ELECTRICAL, ...WRITTEN, SIGN_POST];

// ---- geometry ------------------------------------------------------------

/**
 * The transforms a sign is shown with. A sign is a flat plane facing north, so
 * the menu looks straight at its front rather than at the isometric three-
 * quarter view a cube wants; anything else shows a hairline on edge.
 */
const SIGN_DISPLAY = {
	gui: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.8, 0.8, 0.8] },
	ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.35, 0.35, 0.35] },
	fixed: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.8, 0.8, 0.8] },
	thirdperson_righthand: { rotation: [0, 180, 0], translation: [0, 3.5, 0], scale: [0.4, 0.4, 0.4] },
	thirdperson_lefthand: { rotation: [0, 180, 0], translation: [0, 3.5, 0], scale: [0.4, 0.4, 0.4] },
	firstperson_righthand: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
	firstperson_lefthand: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
};

const POST = 'lunasmp:block/sign_post';

/** Where a wall-hung sign's face sits, in pixels from the block's north edge. */
export const WALL_FACE_Z = 14.4;

/**
 * Where a post-mounted sign's face sits.
 *
 * The pole itself runs dead through the middle of the block (x and z 7..9),
 * which is two things at once: it is exactly the shape of a glass pane with no
 * connections, so the borrowed hitbox lands on the pole rather than beside it,
 * and it is the same column [SIGN_POST] occupies, so a sign stood on a stack of
 * poles reads as one unbroken post. The panel hangs off the front of it.
 */
export const POST_FACE_Z = 6.9;

/**
 * The board a face is painted on: the front, and the silhouette behind it.
 *
 * The two are a tenth of a pixel apart rather than coincident. The client would
 * cope either way - it culls whichever quad faces away - but a pair of quads in
 * exactly the same plane is the classic z-fighting setup, and a tenth of a pixel
 * is both free and invisible. Only those two faces are drawn: the sliver of edge
 * between them is thinner than anything that could be seen from the side.
 */
function plane(z: number, low: number, high: number, left = 0.5, right = 15.5): Element {
	return {
		from: [left, low, z],
		to: [right, high, z + 0.1],
		faces: {
			north: { uv: [0, 0, 16, 16], texture: '#face' },
			south: { uv: [0, 0, 16, 16], texture: '#back' },
		},
	};
}

/** The pole: two pixels square, through the middle of the block, full height. */
function pole(): Element {
	return part([7, 0, 7], [9, 16, 9], POST);
}

/** A sign hung flat on a wall: the plane, and the bracket holding it there. */
function wallModel(sign: Sign): Model {
	// a board that merges runs edge to edge, so a rectangle of them is one
	// surface; anything else keeps a half-pixel margin off the block's own
	// edges, which is what stops two signs on adjacent blocks touching
	const merging = sign.text?.merges === true;
	const elements: Element[] = [
		plane(WALL_FACE_Z, merging ? 0 : 0.5, merging ? 16 : 15.5, merging ? 0 : 0.5, merging ? 16 : 15.5),
	];

	// a merging board gets no bracket either - a stud repeating every block is
	// exactly the thing that would give the join away
	if (!merging) {
		elements.push(part([6.5, 6.5, WALL_FACE_Z + 0.4], [9.5, 9.5, 16], POST, { cull: ['south'] }));
	}

	return {
		textures: {
			face: `lunasmp:block/${sign.id}`,
			back: `lunasmp:block/sign_back_${sign.shape}`,
			t0: POST,
			particle: `lunasmp:block/${sign.id}`,
		},
		elements: retexture(elements),
		display: SIGN_DISPLAY,
	};
}

/** The same sign on a post, for a road rather than a wall. */
function postModel(sign: Sign): Model {
	const elements: Element[] = [
		pole(),
		plane(POST_FACE_Z, 1, 16),
	];

	return {
		textures: {
			face: `lunasmp:block/${sign.id}`,
			back: `lunasmp:block/sign_back_${sign.shape}`,
			t0: POST,
			particle: POST,
		},
		elements: retexture(elements),
		display: SIGN_DISPLAY,
	};
}

/** Rewrites the one bare path a `part` names into the model's own variable. */
function retexture(elements: Element[]): Element[] {
	for (const element of elements) {
		const faces = element.faces as Record<string, Record<string, unknown>>;

		for (const face of Object.values(faces)) {
			if (face.texture === POST) {
				face.texture = '#t0';
			}
		}
	}

	return elements;
}

/** The bare pole's own model: no face, no mounting, nothing to turn. */
function poleModel(): Model {
	return {
		textures: { t0: POST, particle: POST },
		elements: retexture([pole()]),
		display: DISPLAY,
	};
}

/** Every model a sign contributes, by the name it is written under. */
export function signModels(sign: Sign): Record<string, Model> {
	if (sign.post) {
		return { [sign.id]: poleModel() };
	}

	return {
		[sign.id]: wallModel(sign),
		[`${sign.id}_post`]: postModel(sign),
	};
}

/** Every texture the signs paint for themselves, by the name written. */
export function signSprites(): Record<string, Uint8Array> {
	const out: Record<string, Uint8Array> = { sign_post: postSprite() };

	for (const sign of SIGNS) {
		if (!sign.face || !sign.shape) {
			continue;
		}

		out[sign.id] = typeof sign.face === 'function' ? sign.face() : signFace(sign.face);
		out[`sign_back_${sign.shape}`] = signBack(sign.shape);
	}

	return out;
}
