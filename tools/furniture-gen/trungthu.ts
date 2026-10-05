// Tết Trung Thu: the Mid-Autumn set. Mooncakes (baked, snow skin and jelly,
// eaten from the hand or set down on a plate and cut in quarters), the
// lanterns children carry (held on a bamboo stick, hung on a wall, hung from
// a ceiling or stood on the floor, all of them glowing), paper masks, the
// lion-dance drum, the pomelo dog, a cup of tea and the fruit the cakes are
// filled with.
//
// Everything here is ours: every sheet is painted below and every model is
// composed out of boxes and planes. The shapes follow the real things -
// a five-pointed đèn ông sao is a pentagram of bamboo strips under
// cellophane, a Hội An lantern is silk on curved ribs, a mooncake is cut in
// four to show the salted yolk - and nothing traces anybody's picture.
//
// Glowing parts carry vanilla's per-element `light_emission` (read by the
// 1.21 client for block and item models alike) with shading off, so the paper
// reads lit in the hand and on a wall at night without any shader. Shader
// packs that follow LabPBR get a `_s` specular sheet beside every glowing
// texture: emission in alpha, a little subsurface scattering in blue, so the
// paper glows and lets light through the way cellophane and silk do.

import { type Element, type Face, type Model, type Vec3, composed, part } from './compose';
import { type Bitmap, type Rgba, bitmap, blend, disc, encodePng, get, put, rect, rgb, tint } from './pixels';

// ---- ids and names ------------------------------------------------------------

export interface TrungThuEntry {
	id: string;
	en: string;
	vi: string;
}

/** Which skin a mooncake is wrapped in; picks its crust colours. */
export type MooncakeSkin = 'baked' | 'snow' | 'pandan' | 'snow_durian' | 'jelly' | 'jelly_coffee' | 'pink_snow' | 'chocolate';

/** What a mooncake leaves behind besides the meal; the Kotlin side names the effect. */
export type MooncakeEffect = 'haste' | 'speed' | 'regen' | 'absorb' | null;

export interface Mooncake extends TrungThuEntry {
	skin: MooncakeSkin;
	filling: string;
	flecks: string[];
	/** The salted duck egg yolk in the middle: the "moon" of a mooncake. */
	yolk: boolean;
	/** A soft centre in place of the yolk: runny custard, or a flan. */
	core?: 'lava' | 'flan';
	nutrition: number;
	saturation: number;
	effect: MooncakeEffect;
	/** What goes into the recipe on top of the skin's own dough. */
	ingredients: string[];
	/** Round by default; the love set's cakes are hearts. */
	shape?: 'heart';
	lore: string;
}

/** The dough every skin starts from, before the filling goes in. */
const DOUGH: Record<'baked' | 'snow' | 'jelly', string[]> = {
	baked: ['minecraft:wheat', 'minecraft:wheat', 'minecraft:sugar', 'minecraft:egg'],
	snow: ['lunasmp:unmilled_rice', 'lunasmp:unmilled_rice', 'minecraft:sugar'],
	jelly: ['minecraft:dried_kelp', 'minecraft:dried_kelp', 'minecraft:sugar'],
};

function doughOf(skin: MooncakeSkin): string[] {
	if (skin === 'baked') {
		return DOUGH.baked;
	}

	if (skin === 'jelly' || skin === 'jelly_coffee') {
		return DOUGH.jelly;
	}

	if (skin === 'chocolate') {
		return DOUGH.baked;
	}

	return DOUGH.snow;
}

const THAP_CAM = '#b98a5a';
const THAP_CAM_FLECKS = ['#6d4c2e', '#efe2c0', '#c0392b', '#7cb342'];
const MUNG = '#e8c35e';
const LOTUS = '#d8c29a';
const DURIAN = '#efd477';

export const MOONCAKES: Mooncake[] = [
	{
		id: 'mooncake_thap_cam', en: 'Mixed Nut Mooncake', vi: 'Bánh Nướng Thập Cẩm', skin: 'baked',
		filling: THAP_CAM, flecks: THAP_CAM_FLECKS, yolk: true, nutrition: 8, saturation: 9.6, effect: null,
		ingredients: ['minecraft:pumpkin_seeds', 'minecraft:melon_seeds', 'minecraft:cooked_porkchop'],
		lore: 'Hạt dưa, lạp xưởng, mứt bí và một lòng đỏ trứng muối.',
	},
	{
		id: 'mooncake_ga_quay', en: 'Roast Chicken Mooncake', vi: 'Bánh Nướng Gà Quay', skin: 'baked',
		filling: '#a8744a', flecks: ['#7a4a2a', '#e3c9a0', '#b5452f'], yolk: true, nutrition: 8, saturation: 9.6, effect: null,
		ingredients: ['minecraft:cooked_chicken', 'minecraft:pumpkin_seeds'],
		lore: 'Thập cẩm gà quay: mặn, ngọt, béo, đủ cả.',
	},
	{
		id: 'mooncake_dau_xanh', en: 'Mung Bean Mooncake', vi: 'Bánh Nướng Đậu Xanh', skin: 'baked',
		filling: MUNG, flecks: ['#d9ae48'], yolk: true, nutrition: 7, saturation: 8.4, effect: null,
		ingredients: ['lunasmp:green_bean'],
		lore: 'Nhân đậu xanh mịn, ở giữa một trứng muối.',
	},
	{
		id: 'mooncake_hat_sen', en: 'Lotus Seed Mooncake', vi: 'Bánh Nướng Hạt Sen', skin: 'baked',
		filling: LOTUS, flecks: ['#c7ad80'], yolk: true, nutrition: 7, saturation: 8.4, effect: 'regen',
		ingredients: ['minecraft:lily_pad'],
		lore: 'Sen nhuyễn thơm nhẹ, ăn xong thấy lòng yên.',
	},
	{
		id: 'mooncake_khoai_mon', en: 'Taro Mooncake', vi: 'Bánh Nướng Khoai Môn', skin: 'baked',
		filling: '#9c7cc0', flecks: ['#8666ad', '#b69ad4'], yolk: true, nutrition: 7, saturation: 8.4, effect: null,
		ingredients: ['lunasmp:taro_root'],
		lore: 'Khoai môn tím, bùi và thơm.',
	},
	{
		id: 'mooncake_tra_xanh', en: 'Green Tea Mooncake', vi: 'Bánh Nướng Trà Xanh', skin: 'baked',
		filling: '#8aa85a', flecks: ['#768f48'], yolk: true, nutrition: 7, saturation: 8.4, effect: 'haste',
		ingredients: ['lunasmp:tea_leaves'],
		lore: 'Trà xanh hơi đắng, ăn xong tay làm nhanh hơn.',
	},
	{
		id: 'mooncake_dau_do', en: 'Red Bean Mooncake', vi: 'Bánh Nướng Đậu Đỏ', skin: 'baked',
		filling: '#6f302a', flecks: ['#58231f', '#8a4036'], yolk: true, nutrition: 7, saturation: 8.4, effect: null,
		ingredients: ['minecraft:cocoa_beans'],
		lore: 'Đậu đỏ sên đặc, ngọt sâu.',
	},
	{
		id: 'mooncake_sau_rieng', en: 'Durian Mooncake', vi: 'Bánh Nướng Sầu Riêng', skin: 'baked',
		filling: DURIAN, flecks: ['#e2c35d'], yolk: false, nutrition: 7, saturation: 8.4, effect: null,
		ingredients: ['lunasmp:durian'],
		lore: 'Người mê thì mê lắm, người sợ thì đứng xa.',
	},
	{
		id: 'mooncake_dua', en: 'Coconut Mooncake', vi: 'Bánh Nướng Dừa', skin: 'baked',
		filling: '#f2ecdf', flecks: ['#ffffff', '#e0d5bd'], yolk: false, nutrition: 7, saturation: 8.4, effect: null,
		ingredients: ['lunasmp:coconut'],
		lore: 'Dừa sợi sên đường, giòn giòn béo béo.',
	},
	{
		id: 'mooncake_ca_phe', en: 'Coffee Mooncake', vi: 'Bánh Nướng Cà Phê', skin: 'baked',
		filling: '#6a4633', flecks: ['#4e3223'], yolk: false, nutrition: 7, saturation: 8.4, effect: 'speed',
		ingredients: ['lunasmp:coffee_bean'],
		lore: 'Cà phê đậm, ăn một cái tỉnh cả đêm rằm.',
	},
	{
		id: 'mooncake_trung_chay', en: 'Lava Custard Mooncake', vi: 'Bánh Trung Thu Trứng Chảy', skin: 'baked',
		filling: '#f3b53f', flecks: [], yolk: false, core: 'lava', nutrition: 7, saturation: 8.4, effect: 'absorb',
		ingredients: ['minecraft:egg', 'minecraft:milk_bucket'],
		lore: 'Bẻ đôi là nhân trứng sữa chảy ra.',
	},
	{
		id: 'banh_deo_dau_xanh', en: 'Snow Skin Mung Bean Mooncake', vi: 'Bánh Dẻo Đậu Xanh', skin: 'snow',
		filling: MUNG, flecks: ['#d9ae48'], yolk: false, nutrition: 6, saturation: 6.0, effect: null,
		ingredients: ['lunasmp:green_bean'],
		lore: 'Vỏ dẻo trắng mềm, nhân đậu xanh.',
	},
	{
		id: 'banh_deo_hat_sen', en: 'Snow Skin Lotus Mooncake', vi: 'Bánh Dẻo Hạt Sen', skin: 'snow',
		filling: LOTUS, flecks: ['#c7ad80'], yolk: true, nutrition: 6, saturation: 6.0, effect: 'regen',
		ingredients: ['minecraft:lily_pad', 'minecraft:egg'],
		lore: 'Vỏ dẻo, nhân sen, một trứng muối.',
	},
	{
		id: 'banh_deo_thap_cam', en: 'Snow Skin Mixed Mooncake', vi: 'Bánh Dẻo Thập Cẩm', skin: 'snow',
		filling: THAP_CAM, flecks: THAP_CAM_FLECKS, yolk: false, nutrition: 6, saturation: 6.0, effect: null,
		ingredients: ['minecraft:pumpkin_seeds', 'minecraft:melon_seeds'],
		lore: 'Vỏ dẻo thơm hoa bưởi, nhân thập cẩm.',
	},
	{
		id: 'banh_deo_la_dua', en: 'Pandan Snow Skin Mooncake', vi: 'Bánh Dẻo Lá Dứa', skin: 'pandan',
		filling: MUNG, flecks: ['#d9ae48'], yolk: false, nutrition: 6, saturation: 6.0, effect: null,
		ingredients: ['minecraft:fern', 'lunasmp:green_bean'],
		lore: 'Vỏ xanh lá dứa, nhân đậu xanh.',
	},
	{
		id: 'banh_deo_sau_rieng', en: 'Durian Snow Skin Mooncake', vi: 'Bánh Dẻo Sầu Riêng', skin: 'snow_durian',
		filling: DURIAN, flecks: ['#e2c35d'], yolk: false, nutrition: 6, saturation: 6.0, effect: null,
		ingredients: ['lunasmp:durian'],
		lore: 'Vỏ dẻo vàng nhạt, nhân sầu riêng.',
	},
	{
		id: 'banh_rau_cau', en: 'Jelly Mooncake', vi: 'Bánh Trung Thu Rau Câu', skin: 'jelly',
		filling: '#f5c55a', flecks: [], yolk: false, core: 'flan', nutrition: 5, saturation: 4.0, effect: null,
		ingredients: ['minecraft:egg', 'minecraft:milk_bucket'],
		lore: 'Rau câu mát lạnh, giữa là một miếng flan.',
	},
	{
		id: 'banh_rau_cau_ca_phe', en: 'Coffee Jelly Mooncake', vi: 'Bánh Rau Câu Cà Phê', skin: 'jelly_coffee',
		filling: '#f4ead6', flecks: [], yolk: false, core: 'flan', nutrition: 5, saturation: 4.0, effect: 'speed',
		ingredients: ['lunasmp:coffee_bean', 'minecraft:milk_bucket'],
		lore: 'Rau câu cà phê, nhân kem sữa dừa.',
	},

	// the love set: heart-shaped cakes, cut in four like the round ones
	{
		id: 'mooncake_tim_dau', en: 'Strawberry Heart Mooncake', vi: 'Bánh Nướng Trái Tim Nhân Dâu', skin: 'baked', shape: 'heart',
		filling: '#e85a7a', flecks: ['#c43d5f', '#f7a1b5'], yolk: false, nutrition: 7, saturation: 8.4, effect: 'regen',
		ingredients: ['lunasmp:garden_strawberry', 'lunasmp:garden_strawberry'],
		lore: 'Nhân dâu tây chua ngọt, nướng thành hình trái tim.',
	},
	{
		id: 'banh_deo_tim_hong', en: 'Rose Heart Snow Skin Mooncake', vi: 'Bánh Dẻo Trái Tim Hoa Hồng', skin: 'pink_snow', shape: 'heart',
		filling: '#f4c2c2', flecks: ['#e79aa5'], yolk: false, nutrition: 6, saturation: 6.0, effect: 'regen',
		ingredients: ['minecraft:rose_bush'],
		lore: 'Vỏ dẻo hồng thơm hoa hồng, tặng người thương.',
	},
	{
		id: 'mooncake_tim_socola', en: 'Chocolate Heart Mooncake', vi: 'Bánh Nướng Trái Tim Sô-cô-la', skin: 'chocolate', shape: 'heart',
		filling: '#4a2c1c', flecks: ['#6b4230', '#8a5a3c'], yolk: false, nutrition: 7, saturation: 8.4, effect: 'speed',
		ingredients: ['minecraft:cocoa_beans', 'minecraft:cocoa_beans'],
		lore: 'Vỏ sô-cô-la, nhân sô-cô-la, ngọt đắng như tình đầu.',
	},
];

/** Every kind of lantern; each has its own body, and colours where the real ones come in several. */
export type LanternKind = 'star' | 'carp' | 'hoian' | 'red' | 'accordion' | 'rabbit' | 'butterfly' | 'lotus' | 'revolving' | 'heart' | 'lovers' | 'garland';

export interface Lantern extends TrungThuEntry {
	kind: LanternKind;
	colour?: string;
	/** The tune it plays first; a click in the air moves on to the next. */
	song: number;
	shape: string[];
	keys: Record<string, string>;
	lore: string;
}

/** Für Elise, the love letter every music box plays: what the love set starts on. */
const LOVE_SONG = 8;

/**
 * The tunes, in the order the Kotlin side plays them. Only the count matters
 * here; the melodies live in ToyTunes.kt.
 */
export const SONG_COUNT = 15;

const STAR_COLOURS: { id: string; en: string; vi: string; colour: string; dye: string }[] = [
	{ id: 'red', en: 'Red', vi: 'Đỏ', colour: '#e53935', dye: 'minecraft:red_dye' },
	{ id: 'yellow', en: 'Yellow', vi: 'Vàng', colour: '#fdd835', dye: 'minecraft:yellow_dye' },
	{ id: 'blue', en: 'Blue', vi: 'Xanh', colour: '#1e88e5', dye: 'minecraft:blue_dye' },
	{ id: 'pink', en: 'Pink', vi: 'Hồng', colour: '#ec407a', dye: 'minecraft:pink_dye' },
];

const SILK_COLOURS: { id: string; en: string; vi: string; colour: string; dye: string }[] = [
	{ id: 'red', en: 'Red', vi: 'Đỏ', colour: '#d32f2f', dye: 'minecraft:red_dye' },
	{ id: 'yellow', en: 'Yellow', vi: 'Vàng', colour: '#f9a825', dye: 'minecraft:yellow_dye' },
	{ id: 'orange', en: 'Orange', vi: 'Cam', colour: '#ef6c00', dye: 'minecraft:orange_dye' },
	{ id: 'purple', en: 'Purple', vi: 'Tím', colour: '#7b1fa2', dye: 'minecraft:purple_dye' },
	{ id: 'blue', en: 'Blue', vi: 'Xanh Dương', colour: '#1565c0', dye: 'minecraft:blue_dye' },
	{ id: 'green', en: 'Green', vi: 'Xanh Lá', colour: '#2e7d32', dye: 'minecraft:green_dye' },
];

const PAPER = 'minecraft:paper';
const CANDLE = 'minecraft:candle';
const BAMBOO = 'minecraft:bamboo';
const STRING = 'minecraft:string';

export const LANTERNS: Lantern[] = [
	...STAR_COLOURS.map((c, i): Lantern => ({
		id: `lantern_star_${c.id}`,
		en: `${c.en} Star Lantern`,
		vi: `Đèn Ông Sao ${c.vi}`,
		kind: 'star',
		colour: c.colour,
		song: i % 2 === 0 ? 1 : 0,
		shape: [' B ', 'PDP', ' C '],
		keys: { B: BAMBOO, P: PAPER, D: c.dye, C: CANDLE },
		lore: 'Sao năm cánh, cán tre dài, giấy bóng kính.',
	})),
	{
		id: 'lantern_carp', en: 'Carp Lantern', vi: 'Đèn Cá Chép', kind: 'carp', song: 0,
		shape: [' P ', 'DFD', ' C '], keys: { P: PAPER, D: 'minecraft:orange_dye', F: 'minecraft:cod', C: CANDLE },
		lore: 'Cá chép vượt vũ môn, vảy đỏ vây vàng.',
	},
	...SILK_COLOURS.map((c, i): Lantern => ({
		id: `lantern_hoian_${c.id}`,
		en: `${c.en} Hội An Lantern`,
		vi: `Đèn Lồng Hội An ${c.vi}`,
		kind: 'hoian',
		colour: c.colour,
		song: [0, 1, 3, 0, 2, 1][i]!,
		shape: ['BSB', 'SDS', ' C '],
		keys: { B: BAMBOO, S: STRING, D: c.dye, C: CANDLE },
		lore: 'Lụa căng trên khung tre uốn tròn, tua rua bên dưới.',
	})),
	{
		id: 'lantern_red', en: 'Red Paper Lantern', vi: 'Đèn Lồng Đỏ', kind: 'red', song: 0,
		shape: ['GPG', 'PDP', 'GCG'], keys: { G: 'minecraft:gold_nugget', P: PAPER, D: 'minecraft:red_dye', C: CANDLE },
		lore: 'Đèn lồng giấy đỏ, chóp vàng, treo đâu cũng thấy Tết.',
	},
	{
		id: 'lantern_accordion', en: 'Folding Paper Lantern', vi: 'Đèn Xếp Giấy', kind: 'accordion', song: 4,
		shape: ['PRP', 'PYP', 'PCP'], keys: { P: PAPER, R: 'minecraft:red_dye', Y: 'minecraft:yellow_dye', C: CANDLE },
		lore: 'Giấy bóng kính xếp nếp nhiều màu, kéo ra là thành đèn.',
	},
	{
		id: 'lantern_rabbit', en: 'Rabbit Lantern', vi: 'Đèn Con Thỏ', kind: 'rabbit', song: 3,
		shape: [' P ', 'WHW', ' C '], keys: { P: PAPER, W: 'minecraft:white_dye', H: 'minecraft:rabbit_hide', C: CANDLE },
		lore: 'Thỏ trắng mắt đỏ, có bánh xe để kéo đi.',
	},
	{
		id: 'lantern_butterfly', en: 'Butterfly Lantern', vi: 'Đèn Con Bướm', kind: 'butterfly', song: 2,
		shape: ['P P', 'YCY', 'P P'], keys: { P: PAPER, Y: 'minecraft:yellow_dye', C: CANDLE },
		lore: 'Kìa con bướm vàng, xòe đôi cánh.',
	},
	{
		id: 'lantern_lotus', en: 'Lotus Lantern', vi: 'Đèn Hoa Sen', kind: 'lotus', song: 0,
		shape: ['KPK', 'PLP', ' C '], keys: { K: 'minecraft:pink_dye', P: PAPER, L: 'minecraft:lily_pad', C: CANDLE },
		lore: 'Cánh sen hồng ôm lấy ngọn nến.',
	},
	{
		id: 'lantern_revolving', en: 'Revolving Lantern', vi: 'Đèn Kéo Quân', kind: 'revolving', song: 0,
		shape: ['BPB', 'PCP', 'BSB'], keys: { B: BAMBOO, P: PAPER, C: CANDLE, S: STRING },
		lore: 'Hơi nóng của nến kéo đoàn quân giấy chạy vòng quanh.',
	},

	// the love set
	{
		id: 'lantern_heart_red', en: 'Red Heart Lantern', vi: 'Đèn Trái Tim Đỏ', kind: 'heart', colour: '#e53950', song: LOVE_SONG,
		shape: ['P P', 'PDP', ' C '], keys: { P: PAPER, D: 'minecraft:red_dye', C: CANDLE },
		lore: 'Trái tim giấy bóng kính trên khung tre.',
	},
	{
		id: 'lantern_heart_pink', en: 'Pink Heart Lantern', vi: 'Đèn Trái Tim Hồng', kind: 'heart', colour: '#f06292', song: LOVE_SONG,
		shape: ['P P', 'PDP', ' C '], keys: { P: PAPER, D: 'minecraft:pink_dye', C: CANDLE },
		lore: 'Trái tim hồng, thắp lên là thấy thương.',
	},
	{
		id: 'lantern_lovers', en: 'Sweetheart Lantern', vi: 'Đèn Lồng Tình Nhân', kind: 'lovers', song: LOVE_SONG,
		shape: ['GPG', 'PKP', 'GCG'], keys: { G: 'minecraft:gold_nugget', P: PAPER, K: 'minecraft:pink_dye', C: CANDLE },
		lore: 'Đèn lồng đỏ in đầy trái tim hồng.',
	},
	{
		id: 'lantern_heart_garland', en: 'Heart String Lights', vi: 'Dây Đèn Trái Tim', kind: 'garland', song: LOVE_SONG,
		shape: ['SSS', 'KCK'], keys: { S: STRING, K: 'minecraft:pink_dye', C: CANDLE },
		lore: 'Một dây tim nhỏ sáng lấp lánh, treo tường hay treo trần.',
	},
];

export interface Mask extends TrungThuEntry {
	shape: string[];
	keys: Record<string, string>;
	lore: string;
}

export const MASKS: Mask[] = [
	{
		id: 'mask_ong_dia', en: 'Ông Địa Mask', vi: 'Mặt Nạ Ông Địa',
		shape: ['SPS', 'PKP', 'PPP'], keys: { S: STRING, P: PAPER, K: 'minecraft:pink_dye' },
		lore: 'Đầu tròn, bụng bự, cười không ngớt, đi trước múa lân.',
	},
	{
		id: 'mask_ngo_khong', en: 'Sun Wukong Mask', vi: 'Mặt Nạ Tôn Ngộ Không',
		shape: ['SGS', 'PRP', 'PPP'], keys: { S: STRING, G: 'minecraft:gold_nugget', P: PAPER, R: 'minecraft:red_dye' },
		lore: 'Vòng kim cô trên trán, mắt lửa ngươi vàng.',
	},
	{
		id: 'mask_bat_gioi', en: 'Zhu Bajie Mask', vi: 'Mặt Nạ Trư Bát Giới',
		shape: ['SPS', 'PKP', 'PHP'], keys: { S: STRING, P: PAPER, K: 'minecraft:pink_dye', H: 'minecraft:porkchop' },
		lore: 'Mũi to, tai to, bụng cũng to.',
	},
	{
		id: 'mask_lan', en: 'Lion Dance Head', vi: 'Đầu Lân',
		shape: ['SWS', 'RPY', 'PPP'], keys: { S: STRING, W: 'minecraft:white_wool', R: 'minecraft:red_dye', Y: 'minecraft:yellow_dye', P: PAPER },
		lore: 'Đội lên rồi đứng cạnh trống, tùng tùng cắc.',
	},
	{
		id: 'mask_rong', en: 'Dragon Dance Head', vi: 'Đầu Rồng',
		shape: ['SGS', 'YDY', 'PPP'], keys: { S: STRING, G: 'minecraft:gold_nugget', Y: 'minecraft:yellow_dye', D: 'minecraft:green_dye', P: PAPER },
		lore: 'Đầu rồng xanh sừng vàng, múa rồng rước đèn đêm rằm.',
	},
];

/**
 * The light-up plastic swords sold beside the lanterns: a battery hilt, a
 * plastic guard and a clear blade with LEDs running up it. Each is two items,
 * switched off and switched on; the blade animates while it is on.
 */
export interface Sword extends TrungThuEntry {
	/** The LED colours, cycled up the blade frame by frame. */
	colours: string[];
	pane: string;
	lore: string;
}

export const SWORDS: Sword[] = [
	{
		id: 'plastic_sword_rainbow', en: 'Rainbow Light-Up Sword', vi: 'Kiếm Nhựa Phát Sáng Cầu Vồng',
		colours: ['#ff1744', '#ff9100', '#ffea00', '#00e676', '#00b0ff', '#d500f9'],
		pane: 'minecraft:glass_pane',
		lore: 'Bấm nút là lưỡi kiếm chạy đủ bảy màu.',
	},
	{
		id: 'plastic_sword_blue', en: 'Blue Light-Up Sword', vi: 'Kiếm Nhựa Phát Sáng Xanh',
		colours: ['#00e5ff', '#2979ff', '#40c4ff', '#b3e5fc'],
		pane: 'minecraft:light_blue_stained_glass_pane',
		lore: 'Xanh như kiếm của hiệp sĩ vũ trụ, vung là kêu vù vù.',
	},
	{
		id: 'plastic_sword_pink', en: 'Pink Light-Up Sword', vi: 'Kiếm Nhựa Phát Sáng Hồng',
		colours: ['#ff4081', '#f50057', '#ff80ab', '#ffd1e1'],
		pane: 'minecraft:pink_stained_glass_pane',
		lore: 'Kiếm hồng lấp lánh cho công chúa đi rước đèn.',
	},
];

/** Blocks that are not lanterns: each needs a configs/<id>.yml for Breakable. */
export const TRUNGTHU_BLOCKS: TrungThuEntry[] = [
	{ id: 'mooncake_plate', en: 'Plate of Mooncake', vi: 'Đĩa Bánh Trung Thu' },
	{ id: 'lion_drum', en: 'Lion Dance Drum', vi: 'Trống Lân' },
	{ id: 'pomelo_dog', en: 'Pomelo Dog', vi: 'Chó Bưởi' },
	{ id: 'mam_co', en: 'Mid-Autumn Feast Tray', vi: 'Mâm Cỗ Trung Thu' },
	{ id: 'to_he', en: 'Tò He Clay Toy Stand', vi: 'Cây Tò He' },
];

/** The fruit and the tea: plain items with a sprite. */
export const FRUITS: (TrungThuEntry & { lore: string })[] = [
	{ id: 'durian', en: 'Durian', vi: 'Sầu Riêng', lore: 'Gai góc bên ngoài, thơm nồng bên trong.' },
	{ id: 'coconut', en: 'Coconut', vi: 'Quả Dừa', lore: 'Dừa xiêm, nước ngọt mát.' },
	{ id: 'pomelo', en: 'Pomelo', vi: 'Quả Bưởi', lore: 'Mâm cỗ Trung Thu nào cũng có một quả.' },
	{ id: 'tra_nong', en: 'Cup of Hot Tea', vi: 'Chén Trà Nóng', lore: 'Nhâm nhi với bánh nướng dưới trăng.' },
];

/** Everything placed as a block, for the lang file and the configs. */
export function trungThuBlockEntries(): TrungThuEntry[] {
	return [...TRUNGTHU_BLOCKS, ...LANTERNS];
}

/** Everything that is only an item. */
export function trungThuItemEntries(): TrungThuEntry[] {
	const swords = SWORDS.flatMap((sword) => [
		sword,
		{ id: `${sword.id}_on`, en: `${sword.en} (lit)`, vi: `${sword.vi} (đang sáng)` },
	]);

	return [...MOONCAKES, ...MASKS, ...FRUITS, ...swords];
}

// ---- drawing helpers ----------------------------------------------------------

const T = (name: string): string => `lunasmp:block/${name}`;

function noise(seed: number, x: number, y: number): number {
	let h = (seed * 374761393 + x * 668265263 + y * 2147483647) | 0;
	h = (h ^ (h >>> 13)) * 1274126177;
	h = h ^ (h >>> 16);

	return ((h >>> 0) % 10000) / 10000;
}

/** A colour with a faint grain of noise over a rectangle, `x2`/`y2` exclusive. */
function grain(image: Bitmap, x1: number, y1: number, x2: number, y2: number, colour: Rgba, seed: number, amount = 0.06): void {
	for (let y = y1; y < y2; y++) {
		for (let x = x1; x < x2; x++) {
			put(image, x, y, tint(colour, 1 - amount + noise(seed, x, y) * amount * 2));
		}
	}
}

/** A straight one-pixel line, by Bresenham. */
function line(image: Bitmap, x0: number, y0: number, x1: number, y1: number, colour: Rgba): void {
	let x = Math.round(x0);
	let y = Math.round(y0);
	const tx = Math.round(x1);
	const ty = Math.round(y1);
	const dx = Math.abs(tx - x);
	const dy = -Math.abs(ty - y);
	const sx = x < tx ? 1 : -1;
	const sy = y < ty ? 1 : -1;
	let err = dx + dy;

	for (;;) {
		put(image, x, y, colour);

		if (x === tx && y === ty) {
			return;
		}

		const e2 = 2 * err;

		if (e2 >= dy) {
			err += dy;
			x += sx;
		}

		if (e2 <= dx) {
			err += dx;
			y += sy;
		}
	}
}

/** Whether a pixel centre lies inside a polygon, by the even-odd rule. */
function inside(poly: Array<[number, number]>, px: number, py: number): boolean {
	let hit = false;

	for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
		const [xi, yi] = poly[i]!;
		const [xj, yj] = poly[j]!;

		if ((yi > py) !== (yj > py) && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) {
			hit = !hit;
		}
	}

	return hit;
}

/** An axis-aligned ellipse, filled. */
function ellipse(image: Bitmap, cx: number, cy: number, rx: number, ry: number, colour: Rgba): void {
	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			const dx = (x + 0.5 - cx) / rx;
			const dy = (y + 0.5 - cy) / ry;

			if (dx * dx + dy * dy <= 1) {
				put(image, x, y, colour);
			}
		}
	}
}

/**
 * Darkens every opaque pixel that touches a clear one: the hard outline
 * vanilla's item sprites have, which keeps a small sprite legible on any
 * slot background.
 */
function outline(image: Bitmap, factor = 0.62): void {
	const edge: Array<[number, number]> = [];

	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			if (get(image, x, y)[3] === 0) {
				continue;
			}

			const touches = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => get(image, x + dx!, y + dy!)[3] === 0);

			if (touches) {
				edge.push([x, y]);
			}
		}
	}

	for (const [x, y] of edge) {
		put(image, x, y, tint(get(image, x, y), factor));
	}
}

/**
 * The LabPBR specular sheet for a glowing texture: smoothness low (paper and
 * silk are matte), reflectance of a dielectric, subsurface scattering so the
 * lit paper passes light, and emission in alpha. Pixels the painter marks as
 * frame (bamboo, wood, ink) do not emit: alpha 255 means none in LabPBR.
 */
function specular(image: Bitmap, emissive: (colour: Rgba) => boolean, strength = 200): Bitmap {
	const out = bitmap(image.width, image.height);

	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			const colour = get(image, x, y);

			if (colour[3] === 0) {
				continue;
			}

			const glows = emissive(colour);
			put(out, x, y, [48, 10, glows ? 170 : 40, glows ? strength : 255]);
		}
	}

	return out;
}

/**
 * A pixel-art ramp for a colour: the dark line round a shape, the shadow
 * side, the colour itself and the lit side. Shadows lean darker and a
 * little more saturated, highlights lighter and washed toward white, which
 * is how painted plastic reads at sixteen pixels; a plain multiply of the
 * base looks muddy on the dark side and chalky on the light one.
 */
interface Ramp {
	line: Rgba;
	shadow: Rgba;
	base: Rgba;
	light: Rgba;
}

function mix(a: Rgba, b: Rgba, t: number): Rgba {
	return [
		Math.round(a[0] + (b[0] - a[0]) * t),
		Math.round(a[1] + (b[1] - a[1]) * t),
		Math.round(a[2] + (b[2] - a[2]) * t),
		255,
	];
}

/**
 * Vanilla's own contrast: a "line" is only a darker tone of the colour, not
 * ink, and the shadow and the light sit a step either side of the base, the
 * way a wool or a concrete texture is shaded.
 */
function ramp(colour: Rgba | string): Ramp {
	const base = typeof colour === 'string' ? rgb(colour) : colour;

	return {
		line: tint(base, 0.7),
		shadow: tint(base, 0.9),
		base,
		light: mix(base, [255, 255, 255, 255], 0.1),
	};
}

/**
 * A rectangle of one colour with vanilla's soft grain: most pixels the base,
 * a scatter of them a step darker or lighter. No lit edge and no outline: on
 * a texture mapped a texel to a unit, an outline lands on every block edge
 * of a big face and nowhere on a small one. `x2`/`y2` exclusive.
 */
function bevel(image: Bitmap, x1: number, y1: number, x2: number, y2: number, colour: Rgba | string, lined = true): void {
	void lined;
	const r = ramp(colour);
	const seed = r.base[0] * 7 + r.base[1] * 13 + r.base[2] * 17;

	for (let y = y1; y < y2; y++) {
		for (let x = x1; x < x2; x++) {
			const n = noise(seed, x, y);
			put(image, x, y, n < 0.18 ? mix(r.base, r.shadow, 0.7) : n > 0.84 ? mix(r.base, r.light, 0.7) : r.base);
		}
	}
}

/** The one-pixel line round a rectangle, `x2`/`y2` exclusive. */
function edge(image: Bitmap, x1: number, y1: number, x2: number, y2: number, colour: Rgba): void {
	rect(image, x1, y1, x2, y1 + 1, colour);
	rect(image, x1, y2 - 1, x2, y2, colour);
	rect(image, x1, y1, x1 + 1, y2, colour);
	rect(image, x2 - 1, y1, x2, y2, colour);
}

/** A whole sixteen-pixel sheet of one soft-grained colour: what most toy parts wear. */
function toySheet(colour: Rgba | string): Bitmap {
	const image = bitmap(16, 16);
	bevel(image, 0, 0, 16, 16, colour);

	return image;
}

/**
 * Darkens the opaque pixels along a painted shape's silhouette a little, so
 * a cutout keeps its shape against a bright sky without wearing an ink line.
 * The same idea as `outline`, gentler, for shapes that carry their colours.
 */
function inkEdges(image: Bitmap): void {
	outline(image, 0.82);
}

/**
 * A face painted at `n` texels a side and blown up to the sixteen-pixel
 * sheet, for a picture that must stretch over a box smaller than a block:
 * eight texels over an eight-unit head is vanilla's density, sixteen is
 * twice it, which is what made the toys look sharper than everything
 * round them.
 */
function coarse(nx: number, paint: (image: Bitmap) => void, ny = nx): Bitmap {
	const small = bitmap(nx, ny);
	paint(small);

	const image = bitmap(16, 16);
	const kx = 16 / nx;
	const ky = 16 / ny;

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			put(image, x, y, get(small, Math.floor(x / kx), Math.floor(y / ky)));
		}
	}

	return image;
}

/** Everything glows except the listed frame colours. */
function except(...frame: Rgba[]): (colour: Rgba) => boolean {
	return (colour) => !frame.some((f) => Math.abs(f[0] - colour[0]) + Math.abs(f[1] - colour[1]) + Math.abs(f[2] - colour[2]) < 40);
}

// ---- elements -------------------------------------------------------------------

/** Marks an element as lit from inside: full light, no side shading. */
function glow<E extends Element>(element: E, level = 15): E {
	element.light_emission = level;
	element.shade = false;

	return element;
}

/**
 * A toy's box, its faces mapped a texel to a unit like every vanilla model:
 * a two-unit paw shows two texels of its sheet, so the toys sit in the
 * world at the same grain as the blocks round them. A material sheet
 * (plastic, paper, fur, wood) is drawn to tile, so where the window lands
 * does not matter.
 */
function toy(
	from: Vec3,
	to: Vec3,
	faces: string | ({ all: string } & Partial<Record<Face, string>>),
	stretch: Face[] = [],
): Element {
	const element = part(from, to, faces);

	// a painted face (a muzzle, a snout, a wheel) wears its whole sheet,
	// which is drawn `coarse` so it still comes out at about a texel a unit
	for (const dir of stretch) {
		const face = (element.faces as Record<string, { uv: number[] }>)[dir];

		if (face) {
			face.uv = [0, 0, 16, 16];
		}
	}

	return element;
}

/** A zero-thickness plane at `z`, spanning x and y, drawn on both faces. */
function planeZ(x0: number, y0: number, x1: number, y1: number, z: number, texture: string, uv: [number, number, number, number] = [0, 0, 16, 16]): Element {
	return {
		from: [x0, y0, z],
		to: [x1, y1, z],
		faces: {
			north: { uv, texture },
			south: { uv, texture },
		},
	};
}

/** A zero-thickness plane at `x`, spanning z and y, drawn on both faces. */
function planeX(z0: number, y0: number, z1: number, y1: number, x: number, texture: string, uv: [number, number, number, number] = [0, 0, 16, 16]): Element {
	return {
		from: [x, y0, z0],
		to: [x, y1, z1],
		faces: {
			west: { uv, texture },
			east: { uv, texture },
		},
	};
}

/** A flat plane at height `y`, drawn from above and below. */
function planeY(x0: number, z0: number, x1: number, z1: number, y: number, texture: string): Element {
	const uv: [number, number, number, number] = [0, 0, 16, 16];

	return {
		from: [x0, y, z0],
		to: [x1, y, z1],
		faces: {
			up: { uv, texture },
			down: { uv, texture },
		},
	};
}

/** Textures for a round section: one for the wall, optionally others for the top and the underside. */
interface RoundFaces {
	side: string;
	up?: string;
	down?: string;
}

/**
 * One slice of a round body: a regular octagon `half` out from the axis at
 * (8, 8), from `y0` to `y1`.
 *
 * A model can only turn an element by 22.5-degree steps, so an octagon is
 * four strips: two across the axes and the same two turned 45 degrees. Each
 * strip spans the octagon between two opposite edges and is as wide as an
 * edge, which makes their union exactly the octagon; each draws only its two
 * end faces, and those are the eight sides. The tops overlap, so each strip's
 * top and bottom sit a hair apart from the next one's to keep them from
 * fighting over the same depth.
 */
function octagon(y0: number, y1: number, half: number, faces: RoundFaces, lit = false): Element[] {
	const edge = half * Math.tan(Math.PI / 8);
	const out: Element[] = [];

	for (let i = 0; i < 4; i++) {
		const alongX = i % 2 === 0;
		const turned = i >= 2;
		const eps = i * 0.01;
		const from: Vec3 = alongX ? [8 - half, y0 + eps, 8 - edge] : [8 - edge, y0 + eps, 8 - half];
		const to: Vec3 = alongX ? [8 + half, y1 - eps, 8 + edge] : [8 + edge, y1 - eps, 8 + half];
		const ends: Array<'north' | 'east' | 'south' | 'west' | 'up' | 'down'> = alongX ? ['east', 'west'] : ['north', 'south'];

		if (faces.up) {
			ends.push('up');
		}

		if (faces.down) {
			ends.push('down');
		}

		const element = part(from, to, { all: faces.side, up: faces.up ?? faces.side, down: faces.down ?? faces.side }, { only: ends });

		if (turned) {
			element.rotation = { origin: [8, (y0 + y1) / 2, 8], axis: 'y', angle: 45 };
		}

		out.push(lit ? glow(element) : element);
	}

	return out;
}

/** A stack of octagon slices, bottom up: each entry is a slice's height and its half-width. */
function lathe(y: number, profile: Array<[number, number]>, faces: RoundFaces, lit: boolean): { elements: Element[]; top: number } {
	const elements: Element[] = [];
	let at = y;

	for (const [height, half] of profile) {
		elements.push(...octagon(at, at + height, half, faces, lit));
		at += height;
	}

	return { elements, top: at };
}

/** Moves every element, rotation origins included. */
function shifted(elements: Element[], dx: number, dy: number, dz: number): Element[] {
	return elements.map((element) => {
		const copy = JSON.parse(JSON.stringify(element)) as Element;
		const from = copy.from as Vec3;
		const to = copy.to as Vec3;
		copy.from = [from[0] + dx, from[1] + dy, from[2] + dz];
		copy.to = [to[0] + dx, to[1] + dy, to[2] + dz];

		const rotation = copy.rotation as { origin: Vec3 } | undefined;

		if (rotation) {
			rotation.origin = [rotation.origin[0] + dx, rotation.origin[1] + dy, rotation.origin[2] + dz];
		}

		return copy;
	});
}

/** A tassel hanging from `y`: a knot and a bundle of threads `length` long. */
function tassel(x: number, z: number, y: number, length: number, texture: string): Element[] {
	return [
		toy([x - 0.75, y - 1, z - 0.75], [x + 0.75, y, z + 0.75], T('tt_gold')),
		toy([x - 0.5, y - 1 - length, z - 0.5], [x + 0.5, y - 1, z + 0.5], texture),
	];
}

// ---- palette ------------------------------------------------------------------

const BAMBOO_C = rgb('#c9b77a');
const BAMBOO_DARK = rgb('#9e8c52');
const BAMBOO_NODE = rgb('#7d6c3a');
const WOOD = rgb('#5d3a1f');
const WOOD_DARK = rgb('#43291a');
const GOLD = rgb('#d4a73a');
const GOLD_LIGHT = rgb('#f3d27a');
const GOLD_DARK = rgb('#a47c22');
const IRON = rgb('#3d3d3d');
const IRON_LIGHT = rgb('#5a5a5a');
const THREAD_RED = rgb('#c62828');
const INK = rgb('#1b1b1b');
const WHITE = rgb('#ffffff');
const YOLK = rgb('#f08c1e');
const YOLK_LIGHT = rgb('#f7b046');
const PORCELAIN = rgb('#f7f5ef');
const PORCELAIN_SHADE = rgb('#e6e1d6');
const COBALT = rgb('#2a52be');
const COBALT_LIGHT = rgb('#5b7fd6');

/** The crust colours of each skin: top, its highlight, the moulded pattern, the side, the side's foot. */
const SKINS: Record<MooncakeSkin, { top: Rgba; hi: Rgba; pattern: Rgba; side: Rgba; foot: Rgba }> = {
	baked: { top: rgb('#d3913f'), hi: rgb('#e8b060'), pattern: rgb('#a8672a'), side: rgb('#b8732f'), foot: rgb('#8f531d') },
	snow: { top: rgb('#f6f3ec'), hi: rgb('#ffffff'), pattern: rgb('#dcd6ca'), side: rgb('#ebe6dc'), foot: rgb('#d6cfc2') },
	pandan: { top: rgb('#a5d06b'), hi: rgb('#bfe08a'), pattern: rgb('#86b04f'), side: rgb('#93c05c'), foot: rgb('#76a043') },
	snow_durian: { top: rgb('#f7e7a2'), hi: rgb('#fff3c4'), pattern: rgb('#e3cf7e'), side: rgb('#ecd98e'), foot: rgb('#d6c173') },
	jelly: { top: rgb('#f07a9b'), hi: rgb('#ffb3c7'), pattern: rgb('#d75a7e'), side: rgb('#e46b8e'), foot: rgb('#c24f72') },
	jelly_coffee: { top: rgb('#8a5a3c'), hi: rgb('#b07c58'), pattern: rgb('#6d4329'), side: rgb('#7c4f33'), foot: rgb('#5e3a24') },
	pink_snow: { top: rgb('#f8c3d4'), hi: rgb('#fde2ea'), pattern: rgb('#ec9fb8'), side: rgb('#f3b3c7'), foot: rgb('#e59ab1') },
	chocolate: { top: rgb('#6b4230'), hi: rgb('#8a5a3c'), pattern: rgb('#4a2c1c'), side: rgb('#5a3625'), foot: rgb('#3f2517') },
};

// ---- mooncake sheets ------------------------------------------------------------------

/**
 * The moulded top: a raised rim, a ring, and a four-petal flower in the
 * middle with a dot at its heart, the pattern a wooden mooncake mould
 * presses. Jelly tops are glossy instead and carry a highlight streak.
 */
function mooncakeTop(skin: MooncakeSkin): Bitmap {
	const s = SKINS[skin];
	const glossy = skin === 'jelly' || skin === 'jelly_coffee';

	// eight texels across the eight-unit cake: vanilla's grain, so the
	// mould's relief is a checked rim, four petals and a knob
	return coarse(8, (image) => {
		rect(image, 0, 0, 8, 8, s.top);

		for (let i = 0; i < 8; i += 2) {
			put(image, i, 0, s.pattern);
			put(image, 7, i, s.pattern);
			put(image, 7 - i, 7, s.pattern);
			put(image, 0, 7 - i, s.pattern);
		}

		for (const [x, y] of [[3, 1], [4, 1], [1, 3], [1, 4], [6, 3], [6, 4], [3, 6], [4, 6]]) {
			put(image, x!, y!, s.pattern);
		}

		rect(image, 3, 3, 5, 5, s.pattern);
		put(image, 3, 3, s.hi);
		put(image, 2, 2, s.hi);
		put(image, 5, 2, s.hi);

		if (glossy) {
			put(image, 1, 1, s.hi);
			put(image, 2, 1, s.hi);
		}
	});
}

/** The side band: the mould's fluting, and a browned foot on a baked cake. */
function mooncakeSide(skin: MooncakeSkin): Bitmap {
	const s = SKINS[skin];

	// a flute every other unit, the top row lit, the foot browned
	return coarse(8, (image) => {
		rect(image, 0, 0, 8, 8, s.side);

		for (let x = 0; x < 8; x += 4) {
			rect(image, x, 0, x + 1, 8, s.foot);
			rect(image, x + 2, 0, x + 3, 8, s.hi);
		}

		rect(image, 0, 0, 8, 1, s.hi);
		rect(image, 0, 7, 8, 8, s.foot);
	});
}

/**
 * A mooncake cut straight through the middle: crust round the edge, the
 * filling with its flecks of nut and fruit, and the yolk (or the soft
 * centre) in the middle. The cut face of every quarter shows its half of
 * this, the half nearest the middle carrying the half of the yolk.
 */
function mooncakeCut(cake: Mooncake): Bitmap {
	const s = SKINS[cake.skin];
	const filling = rgb(cake.filling);

	// the cut face is eight units across and four high, so it is painted
	// eight texels by four: a texel a unit either way
	return coarse(8, (image) => {
		grain(image, 0, 0, 8, 4, filling, cake.id.length * 13, 0.05);

		cake.flecks.forEach((fleck, i) => {
			for (let y = 1; y < 3; y++) {
				for (let x = 1; x < 7; x++) {
					if (noise(97 + i * 31, x, y) < 0.16) {
						put(image, x, y, rgb(fleck));
					}
				}
			}
		});

		if (cake.yolk) {
			rect(image, 3, 1, 5, 3, YOLK);
			put(image, 3, 1, YOLK_LIGHT);
		}

		if (cake.core === 'lava') {
			rect(image, 2, 1, 6, 3, rgb('#f7a21b'));
			rect(image, 3, 1, 5, 2, rgb('#ffd166'));
		}

		if (cake.core === 'flan') {
			rect(image, 2, 1, 6, 3, rgb('#f5c55a'));
			rect(image, 2, 1, 6, 2, rgb('#8d5a1e'));
		}

		rect(image, 0, 0, 8, 1, s.side);
		rect(image, 0, 3, 8, 4, s.foot);
		rect(image, 0, 0, 1, 4, s.side);
		rect(image, 7, 0, 8, 4, s.side);
	}, 4);
}

/**
 * The slot sprite: the cake seen from above and in front, one quarter taken
 * out of the front right so the filling and the yolk show. That cut is what
 * tells one mooncake from another; the crusts of the baked ones are all the
 * same colour, just as they are on a real tray.
 */
function mooncakeSprite(cake: Mooncake): Bitmap {
	const s = SKINS[cake.skin];
	const image = bitmap(16, 16);
	const filling = rgb(cake.filling);
	const topCy = 5.5;
	const botCy = 9.5;
	const cx = 7.5;
	const rx = 6.2;
	const ry = 3.1;

	const inTop = (x: number, y: number): boolean => ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - topCy) / ry) ** 2 <= 1;
	const inBottom = (x: number, y: number): boolean => ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - botCy) / ry) ** 2 <= 1;
	const inBody = (x: number, y: number): boolean =>
		inTop(x, y) || inBottom(x, y) || (Math.abs(x + 0.5 - cx) <= rx && y + 0.5 >= topCy && y + 0.5 <= botCy);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			if (!inBody(x, y)) {
				continue;
			}

			const cutZone = x + 0.5 > cx;
			const front = y + 0.5 > topCy;

			if (cutZone && front) {
				// the quarter that was taken: the cut face shows from the top
				// edge down as far as the side is tall, and nothing below it
				if (y + 0.5 <= topCy + (botCy - topCy)) {
					put(image, x, y, tint(filling, 0.96 + noise(5, x, y) * 0.08));
				}

				continue;
			}

			if (inTop(x, y)) {
				put(image, x, y, tint(s.top, 0.95 + noise(3, x, y) * 0.1));
			} else {
				put(image, x, y, y >= 11 ? s.foot : s.side);
			}
		}
	}

	// the moulded ring on the uncut part of the top
	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const d = ((x + 0.5 - cx) / 3.6) ** 2 + ((y + 0.5 - topCy) / 1.8) ** 2;

			if (d > 0.7 && d < 1.25 && get(image, x, y)[3] > 0 && !(x + 0.5 > cx && y + 0.5 > topCy)) {
				put(image, x, y, s.pattern);
			}
		}
	}

	put(image, 4, 4, s.hi);
	put(image, 5, 3, s.hi);

	// the cut face's crust: a line along its top and bottom and down its far end
	for (let x = 8; x < 14; x++) {
		if (get(image, x, 5)[3] > 0) {
			put(image, x, 5, s.side);
		}

		if (get(image, x, 9)[3] > 0) {
			put(image, x, 9, s.foot);
		}
	}

	cake.flecks.forEach((fleck, i) => {
		for (let y = 6; y < 9; y++) {
			for (let x = 9; x < 13; x++) {
				if (noise(41 + i * 7, x, y) < 0.18) {
					put(image, x, y, rgb(fleck));
				}
			}
		}
	});

	if (cake.yolk) {
		rect(image, 8, 6, 10, 9, YOLK);
		put(image, 8, 6, YOLK_LIGHT);
	}

	if (cake.core === 'lava') {
		rect(image, 8, 6, 11, 9, rgb('#f7a21b'));
		// the custard running down where the side was cut away
		put(image, 9, 9, rgb('#f7a21b'));
		put(image, 9, 10, rgb('#f7a21b'));
		put(image, 10, 9, rgb('#f7a21b'));
	}

	if (cake.core === 'flan') {
		rect(image, 8, 6, 11, 9, rgb('#f5c55a'));
		rect(image, 8, 6, 11, 7, rgb('#8d5a1e'));
	}

	outline(image);

	return image;
}

/** The porcelain plate: white with the cobalt double ring of Bát Tràng ware. */
/** The porcelain plate: white with a bevelled rim and the cobalt double ring of Bát Tràng ware. */
function plateSheet(): Bitmap {
	const image = bitmap(16, 16);
	rect(image, 0, 0, 16, 16, PORCELAIN);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const d = Math.hypot(x + 0.5 - 8, y + 0.5 - 8);

			if (d > 6.2 && d < 7.2) {
				put(image, x, y, COBALT);
			} else if (d > 5 && d < 5.6) {
				put(image, x, y, COBALT_LIGHT);
			} else if (d > 7.2) {
				put(image, x, y, PORCELAIN_SHADE);
			}
		}
	}

	rect(image, 0, 0, 16, 1, WHITE);
	rect(image, 0, 0, 1, 16, WHITE);
	rect(image, 0, 15, 16, 16, rgb('#c9c2b4'));
	rect(image, 15, 0, 16, 16, rgb('#c9c2b4'));

	return image;
}

/** The plate's rim as seen from the side: a lip, white above, its shadow under. */
function plateRimSheet(): Bitmap {
	const image = bitmap(16, 16);
	rect(image, 0, 0, 16, 16, PORCELAIN_SHADE);
	rect(image, 0, 0, 16, 6, WHITE);
	rect(image, 0, 6, 16, 8, COBALT_LIGHT);
	rect(image, 0, 14, 16, 16, rgb('#c9c2b4'));

	return image;
}

// ---- lantern sheets -----------------------------------------------------------------

/** Bamboo: a lit strip down one side, the node rings, the dark line round it. */
function bambooSheet(): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp(BAMBOO_C);
	rect(image, 0, 0, 16, 16, r.base);
	rect(image, 2, 0, 5, 16, r.light);
	rect(image, 11, 0, 14, 16, r.shadow);
	rect(image, 0, 7, 16, 8, BAMBOO_NODE);
	rect(image, 0, 8, 16, 9, r.light);

	return image;
}

/** A plain toy colour: flat with its bevel and line. */
function solidSheet(colour: Rgba, seed = 0, amount = 0): Bitmap {
	void seed;
	void amount;

	return toySheet(colour);
}

/** Gold trim: the colour banded light and dark the way a turned brass cap catches light. */
function goldSheet(): Bitmap {
	const image = bitmap(16, 16);
	rect(image, 0, 0, 16, 16, GOLD);
	rect(image, 0, 2, 16, 5, GOLD_LIGHT);
	rect(image, 0, 9, 16, 11, GOLD_DARK);
	rect(image, 0, 13, 16, 14, GOLD_LIGHT);

	return image;
}

/** Dark lacquered wood: a lit grain line, a dark one, the outline. */
function woodSheet(): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp(WOOD);
	rect(image, 0, 0, 16, 16, r.base);
	rect(image, 0, 3, 16, 4, r.light);
	rect(image, 0, 9, 16, 10, WOOD_DARK);
	rect(image, 0, 12, 16, 13, r.light);

	return image;
}

/** Tassel threads: strands in three shades, the outer ones dark so the bundle reads as many. */
function tasselSheet(colour: Rgba): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp(colour);

	for (let x = 0; x < 16; x++) {
		const shade = x % 4 === 0 ? r.shadow : x % 4 === 2 ? r.light : r.base;
		rect(image, x, 0, x + 1, 16, shade);
	}

	return image;
}

/**
 * The star lantern: a five-pointed star of cellophane over a pentagram of
 * bamboo strips, which is how the real one is built. Each point is drawn as
 * two facets, lit on the side toward the light and shaded on the other, so
 * the paper reads as folded over its frame rather than flat; the frame's
 * strips and the star's own edge are darkened a step, and gold tinsel
 * fringes the two lower points. Sixteen pixels on a sixteen-unit plane.
 */
function starSheet(hex: string): Bitmap {
	const size = 16;
	const image = bitmap(size, size);
	const r = ramp(hex);
	const cx = 8;
	const cy = 7.25;
	const outer = 7;
	const innerR = outer * 0.4;
	const points: Array<[number, number]> = [];
	const star: Array<[number, number]> = [];

	for (let i = 0; i < 5; i++) {
		const a = -Math.PI / 2 + (i * 2 * Math.PI) / 5;
		points.push([cx + Math.cos(a) * outer, cy + Math.sin(a) * outer]);
		star.push([cx + Math.cos(a) * outer, cy + Math.sin(a) * outer]);

		const b = a + Math.PI / 5;
		star.push([cx + Math.cos(b) * innerR, cy + Math.sin(b) * innerR]);
	}

	for (let y = 0; y < size; y++) {
		for (let x = 0; x < size; x++) {
			const px = x + 0.5;
			const py = y + 0.5;

			if (!inside(star, px, py)) {
				continue;
			}

			// which point the pixel belongs to, and which half of it: the
			// halves alternate lit and shaded round the star
			const angle = Math.atan2(py - cy, px - cx) + Math.PI / 2 + Math.PI * 2;
			const sector = Math.floor((angle / (Math.PI / 5)) % 10);
			const facet = sector % 2 === 0 ? r.light : r.base;
			const d = Math.hypot(px - cx, py - cy) / outer;
			put(image, x, y, d < 0.3 ? mix(facet, WHITE, 0.35) : facet);
		}
	}

	// the frame: the pentagram strips show through the paper as a lighter
	// fold rather than as bamboo, or at sixteen pixels they would be most
	// of the star; the star's own edge darkens a step
	for (let i = 0; i < 5; i++) {
		const [x0, y0] = points[i]!;
		const [x1, y1] = points[(i + 2) % 5]!;
		line(image, x0, y0, x1, y1, mix(r.light, WHITE, 0.25));
	}

	inkEdges(image);

	// gold tinsel off the two lower points
	for (const index of [2, 3]) {
		const [px, py] = points[index]!;

		for (let dx = -1; dx <= 1; dx++) {
			const length = 2 + ((dx + 3) % 2);

			for (let dy = 1; dy <= length; dy++) {
				put(image, Math.round(px) + dx, Math.round(py) + dy, (dx + dy) % 2 === 0 ? GOLD_LIGHT : GOLD);
			}
		}
	}

	return image;
}

/** Hội An silk: the colour, a lit band where the lantern bulges, and the dark ribs of the bamboo frame under it. */
function silkSheet(hex: string): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp(hex);
	rect(image, 0, 0, 16, 16, r.base);
	rect(image, 0, 5, 16, 11, r.light);
	rect(image, 0, 0, 16, 2, r.shadow);
	rect(image, 0, 14, 16, 16, r.shadow);

	for (let x = 1; x < 16; x += 4) {
		rect(image, x, 0, x + 1, 16, r.line);
		rect(image, x + 1, 0, x + 2, 16, r.shadow);
	}

	return image;
}

/** The red paper lantern: bright red with the thin horizontal ribs of its folded frame. */
function redLanternSheet(): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp('#e53935');
	rect(image, 0, 0, 16, 16, r.base);
	rect(image, 0, 5, 16, 11, r.light);

	for (let y = 1; y < 16; y += 3) {
		rect(image, 0, y, 16, y + 1, r.line);
		rect(image, 0, y + 1, 16, y + 2, y < 5 || y > 10 ? r.shadow : r.base);
	}

	return image;
}

/** One pleat colour of the folding lantern: cellophane, lit along the fold's crest, dark in its valley. */
function pleatSheet(hex: string): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp(hex);
	rect(image, 0, 0, 16, 16, r.base);
	rect(image, 0, 0, 16, 5, r.light);
	rect(image, 0, 12, 16, 16, r.shadow);
	rect(image, 0, 15, 16, 16, r.line);

	return image;
}

const PLEATS = ['#e53935', '#fdd835', '#43a047', '#1e88e5', '#ec407a', '#ff9800'];

const CARP = ramp('#ff6d2e');
const CARP_BELLY = rgb('#ffd8a8');

/**
 * The carp's scales: rows of arcs, each scale a lit crescent over its
 * shadow, staggered row to row, over orange that pales to a cream belly.
 */
function carpBodySheet(): Bitmap {
	const image = bitmap(16, 16);
	rect(image, 0, 0, 16, 16, CARP.base);
	rect(image, 0, 0, 16, 3, CARP.shadow);
	rect(image, 0, 11, 16, 16, mix(CARP.base, CARP_BELLY, 0.5));
	rect(image, 0, 13, 16, 16, CARP_BELLY);

	for (let y = 1; y < 12; y += 3) {
		for (let x = ((y / 3) % 2) * 2; x < 18; x += 4) {
			put(image, x, y, CARP.light);
			put(image, x + 1, y, CARP.light);
			put(image, x - 1, y + 1, CARP.line);
			put(image, x + 2, y + 1, CARP.line);
			put(image, x, y + 2, CARP.line);
			put(image, x + 1, y + 2, CARP.line);
		}
	}

	return image;
}

/** The carp's head sides: the scales give way to smooth cheek, with the gill line at the back. */
function carpHeadSheet(): Bitmap {
	const image = bitmap(16, 16);
	rect(image, 0, 0, 16, 16, CARP.base);
	rect(image, 0, 0, 16, 3, CARP.shadow);
	rect(image, 0, 3, 16, 5, CARP.light);
	rect(image, 0, 11, 16, 12, mix(CARP.base, CARP_BELLY, 0.5));
	rect(image, 0, 12, 16, 16, CARP_BELLY);

	// the gill, a curve at the back of the cheek
	for (let y = 2; y < 14; y++) {
		put(image, 1 + Math.round(Math.sin(((y - 2) / 11) * Math.PI) * 1.5), y, CARP.line);
	}

	return image;
}

/** A fin: a fan of rays from one corner, orange shading to gold at the edge, inked round; clear outside it. */
function finSheet(): Bitmap {
	const size = 16;
	const image = bitmap(size, size);

	for (let y = 0; y < size; y++) {
		for (let x = 0; x < size; x++) {
			// a fan opening from the middle of the right edge toward the left
			const dx = size - (x + 0.5);
			const dy = y + 0.5 - size / 2;
			const r = Math.hypot(dx, dy);
			const angle = Math.atan2(dy, dx);

			if (r > 15 || Math.abs(angle) > 1.05) {
				continue;
			}

			// the forked tail: a notch cut into the middle of the fan's edge
			if (r > 11 && Math.abs(angle) < 0.25) {
				continue;
			}

			const ray = Math.abs(Math.sin(angle * 7)) < 0.2;
			const colour = r > 10 ? rgb('#ffd54f') : CARP.base;
			put(image, x, y, ray ? CARP.line : r > 10 ? colour : mix(colour, WHITE, 0.15 * (1 - r / 10)));
		}
	}

	inkEdges(image);

	return image;
}

/** An eye standing proud of the head, four texels across: gold ring, black pupil, a glint. */
function carpEyeSheet(): Bitmap {
	return coarse(4, (image) => {
		rect(image, 0, 0, 4, 4, rgb('#ffe082'));
		rect(image, 1, 1, 3, 3, INK);
		put(image, 1, 1, WHITE);
	});
}

/** The carp's front, four texels across: the round mouth, open. */
function carpMouthSheet(): Bitmap {
	return coarse(4, (image) => {
		rect(image, 0, 0, 4, 4, CARP.base);
		rect(image, 0, 3, 4, 4, CARP_BELLY);
		rect(image, 1, 1, 3, 3, rgb('#7f1d1d'));
		put(image, 1, 1, rgb('#c62828'));
	});
}

const FUR = ramp('#fbf8f4');
const FUR_PINK = rgb('#f8bbd0');
const EYE_PINK = rgb('#d81b60');

/**
 * The rabbit's white plastic, with a painted band of pink and red where
 * the body meets its cart. The body sits at units 3.5 to 8.5, so on a
 * sheet mapped a texel to a unit the band goes at rows 11 and 12.
 */
function rabbitBodySheet(): Bitmap {
	const image = toySheet(FUR.base);
	rect(image, 0, 11, 16, 12, FUR_PINK);
	rect(image, 0, 12, 16, 13, rgb('#e53935'));

	for (let x = 1; x < 16; x += 4) {
		put(image, x, 12, GOLD_LIGHT);
	}

	return image;
}

/** The rabbit's face at eight texels across its eight-unit head: two dark eyes, a pink nose, blushing cheeks. */
function rabbitFaceSheet(): Bitmap {
	return coarse(8, (image) => {
		bevel(image, 0, 0, 8, 8, FUR.base);
		rect(image, 2, 2, 3, 4, EYE_PINK);
		rect(image, 5, 2, 6, 4, EYE_PINK);
		put(image, 2, 2, rgb('#880e4f'));
		put(image, 5, 2, rgb('#880e4f'));
		rect(image, 3, 4, 5, 5, EYE_PINK);
		put(image, 1, 5, FUR_PINK);
		put(image, 6, 5, FUR_PINK);
		put(image, 3, 5, FUR.shadow);
		put(image, 4, 5, FUR.shadow);
	});
}

/** A plain toy-white sheet for the ears' outsides. */
function rabbitEarSheet(): Bitmap {
	return toySheet(FUR.base);
}

/** The ear's inside: pink, two units wide, so a plain pink sheet. */
function rabbitEarInnerSheet(): Bitmap {
	return toySheet(FUR_PINK);
}

/** A cart wheel, four texels across its three units: a dark tyre, a red disc, a gold hub; clear at the corners. */
function wheelSheet(): Bitmap {
	return coarse(4, (image) => {
		rect(image, 1, 0, 3, 4, rgb('#8d2a1d'));
		rect(image, 0, 1, 4, 3, rgb('#8d2a1d'));
		rect(image, 1, 1, 3, 3, rgb('#e53935'));
		put(image, 1, 1, GOLD);
	});
}

/**
 * A pair of butterfly wings on one side: the big fore wing up and the
 * smaller hind wing below it, gold shading to orange, brown veins, a blue
 * eye spot on each and a darkened edge. Clear outside.
 */
function wingSheet(): Bitmap {
	const size = 16;
	const image = bitmap(size, size);
	const fore: Array<[number, number]> = [[0, 7], [2, 1.5], [7, 0], [13.5, 0.5], [15.5, 3], [12, 7], [0, 8]];
	const hind: Array<[number, number]> = [[0, 7.5], [8, 7.5], [12, 9], [11, 13.5], [7, 15.5], [2.5, 14], [0, 10]];

	for (let y = 0; y < size; y++) {
		for (let x = 0; x < size; x++) {
			const px = x + 0.5;
			const py = y + 0.5;
			const inFore = inside(fore, px, py);
			const inHind = inside(hind, px, py);

			if (!inFore && !inHind) {
				continue;
			}

			const r = Math.hypot(px, py - 7.5) / 15;
			put(image, x, y, tint(r > 0.55 ? rgb('#ff8f00') : rgb('#ffc107'), 1.15 - r * 0.3));
		}
	}

	// the veins fan from the body
	const vein = rgb('#7a3e00');

	for (const [x, y] of [[13, 1], [15, 3], [10, 5], [10, 11], [7, 14], [3, 13]]) {
		line(image, 0, 7, x!, y!, vein);
	}

	disc(image, 8.5, 10.75, 1.2, rgb('#1565c0'));
	put(image, 8, 10, WHITE);
	inkEdges(image);

	return image;
}

/** A lotus petal: a pointed oval, pale at its foot and deepening to pink at the tip; clear outside. */
function petalSheet(light: boolean): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			// a vesica: the overlap of two circles, its point up and its foot
			// cut off by the bottom of the sheet, so the petal is broad
			const inLeft = Math.hypot(x + 0.5 - 13.5, y + 0.5 - 13) < 12;
			const inRight = Math.hypot(x + 0.5 - 2.5, y + 0.5 - 13) < 12;

			if (!inLeft || !inRight) {
				continue;
			}

			const t = 1 - y / 15;
			const tip = light ? rgb('#f8bbd0') : rgb('#ec407a');
			const foot = light ? rgb('#fff5f8') : rgb('#fce4ec');
			put(image, x, y, [
				Math.round(foot[0] + (tip[0] - foot[0]) * t),
				Math.round(foot[1] + (tip[1] - foot[1]) * t),
				Math.round(foot[2] + (tip[2] - foot[2]) * t),
				255,
			]);
		}
	}

	line(image, 8, 3, 8, 15, light ? rgb('#f48fb1') : rgb('#d81b60'));
	inkEdges(image);

	return image;
}

/** The lotus leaf the lantern sits on: a green disc with a notch and veins; clear outside. */
function leafSheet(): Bitmap {
	const image = bitmap(16, 16);
	disc(image, 8, 8, 8, rgb('#43a047'));
	disc(image, 8, 8, 5, rgb('#4caf50'));

	for (let i = 0; i < 8; i++) {
		const a = (i * Math.PI) / 4;
		line(image, 8, 8, 8 + Math.cos(a) * 7, 8 + Math.sin(a) * 7, rgb('#2e7d32'));
	}

	for (let y = 8; y < 16; y++) {
		for (let x = 7; x < 9; x++) {
			put(image, x, y, [0, 0, 0, 0]);
		}
	}

	inkEdges(image);

	return image;
}

/**
 * A panel of the revolving lantern: warm paper with a horseman in shadow,
 * the troops (quân) the candle's rising air pulls round. Clear nowhere; the
 * whole panel is paper.
 */
function revolvingSheet(): Bitmap {
	const size = 16;
	const image = bitmap(size, size);

	const paper = ramp('#ffe7a0');
	rect(image, 0, 0, size, size, paper.base);
	rect(image, 0, 4, size, 12, paper.light);

	const shadow = rgb('#3b2410');
	// the horse: body, neck and head, four legs, a tail
	rect(image, 4, 9, 11, 11, shadow);
	rect(image, 9, 6, 11, 9, shadow);
	rect(image, 10, 5, 13, 7, shadow);

	for (const x of [5, 6, 9, 10]) {
		rect(image, x, 11, x + 1, 14, shadow);
	}

	line(image, 4, 9, 3, 11, shadow);
	// the rider: body, head, and a spear held high
	rect(image, 6, 6, 8, 9, shadow);
	disc(image, 7, 4.5, 1, shadow);
	line(image, 5, 10, 9, 2, shadow);

	rect(image, 0, 0, size, 1, rgb('#b71c1c'));
	rect(image, 0, 15, size, 16, rgb('#b71c1c'));
	edge(image, 0, 0, size, size, ramp(WOOD).line);

	return image;
}

// ---- the love set's sheets ---------------------------------------------------------

/**
 * Whether a point lies inside a heart centred on (cx, cy), `size` pixels
 * across, point down: the classic implicit curve (x² + y² - 1)³ ≤ x²y³.
 */
function inHeart(px: number, py: number, cx: number, cy: number, size: number): boolean {
	const x = ((px - cx) / size) * 2.3;
	const y = -((py - cy) / size) * 2.3 + 0.15;
	const a = x * x + y * y - 1;

	return a * a * a - x * x * y * y * y <= 0;
}

/**
 * A heart lantern: cellophane in a heart, its bamboo frame following the
 * outline, a glow at the middle and a paper tassel off the point. Clear
 * outside the heart; sixteen pixels on a sixteen-unit plane.
 */
function heartLanternSheet(hex: string): Bitmap {
	const size = 16;
	const image = bitmap(size, size);
	const base = rgb(hex);
	const cx = 8;
	const cy = 6.25;

	for (let y = 0; y < size; y++) {
		for (let x = 0; x < size; x++) {
			if (!inHeart(x + 0.5, y + 0.5, cx, cy, 13)) {
				continue;
			}

			const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy) / 6.5;
			put(image, x, y, tint(base, 1.2 - d * 0.35 + (noise(131, x, y) - 0.5) * 0.06));
		}
	}

	// the frame: every heart pixel that touches the outside
	const frame: Array<[number, number]> = [];

	for (let y = 0; y < size; y++) {
		for (let x = 0; x < size; x++) {
			if (get(image, x, y)[3] > 0 && [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => get(image, x + dx!, y + dy!)[3] === 0)) {
				frame.push([x, y]);
			}
		}
	}

	for (const [x, y] of frame) {
		put(image, x, y, BAMBOO_DARK);
	}

	for (const [x, y] of [[5, 3], [6, 3], [10, 4]]) {
		blend(image, x!, y!, rgb('#ffffff', 150));
	}

	// the tassel off the point
	let tip = 0;

	for (let y = size - 1; y >= 0; y--) {
		if (get(image, 8, y)[3] > 0 || get(image, 7, y)[3] > 0) {
			tip = y;
			break;
		}
	}

	for (let dy = 1; dy <= 3; dy++) {
		for (let dx = -1; dx <= 1; dx++) {
			put(image, 8 + dx, tip + dy, dy === 1 ? GOLD : (dx + dy) % 2 === 0 ? rgb('#ffd54f') : rgb('#ff8f00'));
		}
	}

	return image;
}

/** A small glowing heart for the string lights; clear outside it. */
function smallHeartSheet(): Bitmap {
	const image = bitmap(16, 16);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			if (inHeart(x + 0.5, y + 0.5, 8, 7.5, 15)) {
				const d = Math.hypot(x + 0.5 - 7, y + 0.5 - 6) / 8;
				put(image, x, y, tint(rgb('#ff4f7b'), 1.3 - d * 0.45));
			}
		}
	}

	put(image, 4, 4, rgb('#ffe0e8'));
	put(image, 5, 4, rgb('#ffe0e8'));
	inkEdges(image);

	return image;
}

/** The sweetheart lantern's paper: red with its folded ribs, scattered with pink hearts. */
function loversSheet(): Bitmap {
	const image = redLanternSheet();
	const heart: Array<[number, number]> = [[0, 0], [2, 0], [0, 1], [1, 1], [2, 1], [1, 2]];

	for (const [ox, oy] of [[2, 2], [10, 5], [5, 10], [12, 12]]) {
		for (const [dx, dy] of heart) {
			put(image, ox! + dx, oy! + dy, rgb('#ffb3c7'));
		}
	}

	return image;
}

/** A heart cake's top: the crust with a raised heart pressed into the middle and a scalloped edge. */
function heartTop(skin: MooncakeSkin): Bitmap {
	const s = SKINS[skin];

	// the cake's point is north, where the sheet's v starts: the pressed
	// heart is drawn point up to match it, by hand at eight texels
	const pressed = [
		'........',
		'...##...',
		'..#..#..',
		'.#....#.',
		'#......#',
		'#..##..#',
		'.##..##.',
		'........',
	];

	return coarse(8, (image) => {
		grain(image, 0, 0, 8, 8, s.top, 19, skin === 'baked' || skin === 'chocolate' ? 0.06 : 0.03);

		pressed.forEach((row, y) => {
			for (let x = 0; x < 8; x++) {
				if (row[x] === '#') {
					put(image, x, y, s.pattern);
				}
			}
		});

		put(image, 2, 5, s.hi);
		put(image, 3, 4, s.hi);
	});
}

/** A heart drawn by hand for the slot: at sixteen pixels a curve loses the notch that makes it a heart. */
const SLOT_HEART: string[] = [
	'..###....###..',
	'.#####..#####.',
	'##############',
	'##############',
	'##############',
	'.############.',
	'..##########..',
	'...########...',
	'....######....',
	'.....####.....',
	'......##......',
];

/** A heart mooncake's slot sprite: the whole heart seen from above and in front, its moulded heart on top. */
function heartMooncakeSprite(cake: Mooncake): Bitmap {
	const s = SKINS[cake.skin];
	const image = bitmap(16, 16);
	const inTop = (x: number, y: number): boolean => SLOT_HEART[y - 1]?.[x - 1] === '#';

	// the side is the same heart two rows lower, drawn first so the top covers it
	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			if (inTop(x, y - 2)) {
				put(image, x, y, y > 11 ? s.foot : s.side);
			}
		}
	}

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			if (inTop(x, y)) {
				put(image, x, y, tint(s.top, 0.95 + noise(23, x, y) * 0.1));
			}
		}
	}

	// the pressed heart: the outline of a smaller one in the middle
	const small: Array<[number, number]> = [[5, 4], [6, 4], [9, 4], [10, 4], [4, 5], [7, 5], [8, 5], [11, 5], [4, 6], [11, 6], [5, 7], [10, 7], [6, 8], [9, 8], [7, 9], [8, 9]];

	for (const [x, y] of small) {
		put(image, x, y, s.pattern);
	}

	put(image, 3, 3, s.hi);
	put(image, 4, 2, s.hi);
	outline(image);

	return image;
}

// ---- the pig and the dragon ------------------------------------------------------

/** The pig's face on the front of the helmet: little eyes, rosy cheeks, a grin; the snout is its own box. */
function pigFaceSheet(): Bitmap {
	return maskSheet((image) => {
		inkedEllipse(image, 3.5, 10.5, 1.5, 1, '#f48fb1');
		inkedEllipse(image, 12.5, 10.5, 1.5, 1, '#f48fb1');
		rect(image, 4, 5, 6, 7, INK);
		rect(image, 10, 5, 12, 7, INK);
		put(image, 4, 5, WHITE);
		put(image, 10, 5, WHITE);
		rect(image, 3, 3, 6, 4, rgb('#ad5f7d'));
		rect(image, 10, 3, 13, 4, rgb('#ad5f7d'));

		// the grin under where the snout sits
		for (let x = 0; x < 5; x++) {
			put(image, 6 + x, 13 + Math.round(Math.sin((x / 4) * Math.PI) * 0.8), rgb('#8e1b1b'));
		}
	}, '#f8bbd0');
}

/** A dragon's face: green scales, huge golden eyes under fiery brows, red cheeks. */
function dragonFaceSheet(): Bitmap {
	return maskSheet((image) => {
		const scales = scaleSheet('#2e9e4f', '#1b7a3a');

		for (let y = 0; y < 16; y++) {
			for (let x = 0; x < 16; x++) {
				put(image, x, y, get(scales, x, y));
			}
		}

		// the brows: flames of gold and red sweeping up and out
		for (let i = 0; i < 5; i++) {
			const lift = Math.floor(i / 2);
			rect(image, 1 + i, 4 - lift, 2 + i, 5 - lift, i % 2 === 0 ? GOLD : rgb('#e53935'));
			rect(image, 14 - i, 4 - lift, 15 - i, 5 - lift, i % 2 === 0 ? GOLD : rgb('#e53935'));
		}

		inkedEllipse(image, 4.5, 7.5, 2.5, 2.25, WHITE);
		inkedEllipse(image, 11.5, 7.5, 2.5, 2.25, WHITE);
		disc(image, 4.75, 7.75, 1.5, rgb('#ffb300'));
		disc(image, 11.25, 7.75, 1.5, rgb('#ffb300'));
		rect(image, 4, 7, 5, 9, INK);
		rect(image, 11, 7, 12, 9, INK);
		put(image, 4, 6, WHITE);
		put(image, 11, 6, WHITE);
		inkedEllipse(image, 2.5, 12, 1.5, 1, '#e53935');
		inkedEllipse(image, 13.5, 12, 1.5, 1, '#e53935');

		// a pearl on the brow
		inkedEllipse(image, 8, 2.5, 1, 1, '#fff3c4');
		put(image, 7, 2, WHITE);
	});
}

/** The front of the dragon's snout, eight texels across its ten units: two nostrils, a gold lip, teeth on red. */
function dragonSnoutSheet(): Bitmap {
	return coarse(8, (image) => {
		bevel(image, 0, 0, 8, 8, '#2e9e4f');
		rect(image, 1, 1, 3, 3, rgb('#0d3b1c'));
		rect(image, 5, 1, 7, 3, rgb('#0d3b1c'));
		rect(image, 0, 4, 8, 5, GOLD);
		rect(image, 0, 5, 8, 8, rgb('#b71c1c'));

		for (let x = 0; x < 8; x += 2) {
			put(image, x, 5, WHITE);
		}
	});
}

/** The dragon's lower jaw, eight texels across: red inside, a row of teeth along its front edge. */
function dragonMouthSheet(): Bitmap {
	return coarse(8, (image) => {
		bevel(image, 0, 0, 8, 8, '#b71c1c');

		for (let x = 1; x < 8; x += 2) {
			put(image, x, 0, WHITE);
		}
	});
}

function scaleSheet(hex: string, dark: string): Bitmap {
	const image = bitmap(16, 16);

	const r = ramp(hex);

	for (let y = 0; y < 16; y++) {
		for (let x = 0; x < 16; x++) {
			const row = Math.floor(y / 4);
			const sx = (x + (row % 2) * 2) % 4;
			const sy = y % 4;
			const scale = sy === 3 || (sy === 2 && (sx === 0 || sx === 3));
			put(image, x, y, scale ? rgb(dark) : sy === 0 ? r.light : r.base);
		}
	}

	return image;
}

// ---- toy plastic: the carrying handle and the swords --------------------------------

const TOY_ORANGE = rgb('#ff8f00');
const TOY_ORANGE_DARK = rgb('#e65100');

/**
 * The battery handle: orange plastic with the battery cover's seam across
 * it. The sheet tiles, so the seam shows wherever the handle's window falls.
 */
function toyHandleSheet(): Bitmap {
	const image = toySheet(TOY_ORANGE);
	const r = ramp(TOY_ORANGE);
	rect(image, 0, 11, 16, 12, r.line);
	rect(image, 0, 12, 16, 13, r.light);

	return image;
}

/** The handle's ends: the darker cap the rod plugs into. */
function toyHandleEndSheet(): Bitmap {
	return toySheet(TOY_ORANGE_DARK);
}

/** A light-up sword's hilt: a black grip ring every four units on orange plastic. */
function swordHiltSheet(): Bitmap {
	const image = toySheet(TOY_ORANGE);

	for (let y = 2; y < 16; y += 4) {
		rect(image, 0, y, 16, y + 1, rgb('#212121'));
	}

	return image;
}

/** The guard: plain yellow plastic; its red gem is a box of its own. */
function swordGuardSheet(): Bitmap {
	return toySheet('#ffd600');
}

/** How many frames a blade's LED run takes. */
const BLADE_FRAMES = 12;

/**
 * A lit blade, one frame per sixteen rows: bands of the LED colours climbing
 * the blade a step per frame, a bright core down the middle and a softer
 * glow at the edges, the way a clear plastic blade carries its LEDs' light.
 */
function bladeSheet(colours: string[]): Bitmap {
	const image = bitmap(16, 16 * BLADE_FRAMES);
	const band = 16 / colours.length;

	for (let f = 0; f < BLADE_FRAMES; f++) {
		for (let y = 0; y < 16; y++) {
			const shift = (y + (f * 16) / BLADE_FRAMES) % 16;
			const colour = rgb(colours[Math.floor(shift / band) % colours.length]!);

			for (let x = 0; x < 16; x++) {
				const edge = Math.abs(x - 7.5) / 7.5;
				const lift = 1.35 - edge * 0.55;
				put(image, x, f * 16 + (15 - y), tint(colour, lift));
			}
		}
	}

	return image;
}

/** A blade switched off: clear plastic, pale with a glint down one side. */
function bladeOffSheet(): Bitmap {
	const image = toySheet('#dfe7ec');
	rect(image, 3, 1, 5, 15, WHITE);
	rect(image, 12, 1, 15, 15, rgb('#b8c4cc'));

	return image;
}

/**
 * The slot sprite of a sword: the vanilla sword's diagonal, hilt bottom left,
 * blade to the top right; lit ones animate with the blade.
 */
function swordSprite(colours: string[] | null): Bitmap {
	const frames = colours ? BLADE_FRAMES : 1;
	const image = bitmap(16, 16 * frames);

	for (let f = 0; f < frames; f++) {
		const o = f * 16;

		// the blade, two pixels wide along the diagonal
		for (let i = 0; i < 10; i++) {
			const x = 5 + i;
			const y = 10 - i;
			const colour = colours
				? tint(rgb(colours[Math.floor((i + f) / 2) % colours.length]!), 1.2)
				: rgb('#dfe7ec');

			put(image, x, o + y, colour);
			put(image, x, o + y + 1, colours ? tint(colour, 0.85) : rgb('#b8c4cc'));
		}

		// the guard across the diagonal, the hilt and the pommel
		for (const [x, y] of [[2, 9], [3, 10], [4, 11], [5, 12], [6, 13]]) {
			put(image, x!, o + y!, rgb('#ffd600'));
		}

		for (const [x, y] of [[3, 12], [2, 13], [1, 14]]) {
			put(image, x!, o + y!, TOY_ORANGE);
		}

		put(image, 2, o + 12, rgb('#212121'));
		put(image, 0, o + 15, TOY_ORANGE_DARK);
	}

	return image;
}

/** A sword in the hand: pommel, grip, guard and a long blade, authored standing up like a torch. */
function swordElements(sword: Sword, lit: boolean): Element[] {
	const hilt = T('tt_sword_hilt');
	const guard = T('tt_sword_guard');
	const blade = lit ? T(`${sword.id}_blade`) : T('tt_sword_blade_off');
	const uv: [number, number, number, number] = [0, 0, 16, 16];

	const bladeBox: Element = {
		from: [7.25, 6.25, 7.6],
		to: [8.75, 23, 8.4],
		faces: {
			north: { uv, texture: blade },
			south: { uv, texture: blade },
			east: { uv, texture: blade },
			west: { uv, texture: blade },
			up: { uv, texture: blade },
		},
	};

	const tip: Element = {
		from: [7.6, 23, 7.75],
		to: [8.4, 24.25, 8.25],
		faces: {
			north: { uv, texture: blade },
			south: { uv, texture: blade },
			east: { uv, texture: blade },
			west: { uv, texture: blade },
			up: { uv, texture: blade },
		},
	};

	return [
		toy([7, -2, 7], [9, -0.75, 9], hilt),
		toy([7.4, -0.75, 7.4], [8.6, 5, 8.6], hilt),
		toy([4.5, 5, 7.25], [11.5, 6.25, 8.75], guard),
		// the red plastic gem pressed into the guard
		toy([7.5, 5.25, 7], [8.5, 6, 9], T('tt_string_red')),
		lit ? glow(bladeBox) : bladeBox,
		lit ? glow(tip) : tip,
	];
}

/** Vanilla's hand-held transforms, a little smaller: the sword is longer than a vanilla one. */
const SWORD_DISPLAY = {
	thirdperson_righthand: { rotation: [0, -90, 55], translation: [0, 4, 0.5], scale: [0.7, 0.7, 0.7] },
	thirdperson_lefthand: { rotation: [0, 90, -55], translation: [0, 4, 0.5], scale: [0.7, 0.7, 0.7] },
	firstperson_righthand: { rotation: [0, -90, 25], translation: [1.13, 3.2, 1.13], scale: [0.6, 0.6, 0.6] },
	firstperson_lefthand: { rotation: [0, 90, -25], translation: [1.13, 3.2, 1.13], scale: [0.6, 0.6, 0.6] },
};

/** The animation every lit blade sheet and its companions carry. */
const BLADE_MCMETA = { animation: { frametime: 2, interpolate: true } };

// ---- lantern models -------------------------------------------------------------

/** A lantern's body, standing on the floor with its foot at y 0, and where its top ends. */
interface Body {
	elements: Element[];
	top: number;
	/** Whether the body is a flat cut-out (star, butterfly): it faces forward rather than turning with a 3/4 view. */
	flat: boolean;
}

function starBody(id: string): Body {
	const sheet = T(`${id}_paper`);

	// the sprite's top point is one sprite pixel down, half a unit
	return {
		elements: [
			glow(planeZ(0, 0, 16, 16, 7.4, sheet)),
			glow(planeZ(0, 0, 16, 16, 8.6, sheet)),
		],
		top: 15.5,
		flat: true,
	};
}

/**
 * The carp lantern: a long fish, its body a spindle of slabs that swells
 * behind the head and tapers to the tail, the head a step narrower than the
 * body's widest with a short snout and the round open mouth, the eyes big
 * discs standing proud of the head, the tail a fan on two crossed planes,
 * a dorsal fin along the back and a pectoral fin either side.
 */
function carpBody(): Body {
	const body = T('tt_carp_body');
	const head = T('tt_carp_head');
	const mouth = T('tt_carp_mouth');
	const fin = T('tt_carp_fin');
	const eye = T('tt_carp_eye');

	const elements: Element[] = [
		// the tail root, thin
		glow(toy([1.5, 4.5, 7], [4, 7.5, 9], body)),
		// the body, three slabs, widest in the middle
		glow(toy([4, 3.75, 6.25], [10, 4.75, 9.75], body)),
		glow(toy([3.5, 4.75, 5.5], [10.5, 8.25, 10.5], body)),
		glow(toy([4, 8.25, 6.25], [10, 9.25, 9.75], body)),
		// the head, a step in from the flanks, its sides smooth cheek
		glow(toy([10.5, 4.25, 6], [13.5, 8.75, 10], { all: body, north: head, south: head, east: body })),
		// the snout, and the mouth on its front
		glow(toy([13.5, 5, 6.75], [15, 8, 9.25], { all: body, east: mouth }, ['east'])),
		// the eyes, discs standing proud of the head
		glow(toy([11.25, 5.75, 5.25], [13.25, 7.75, 6], eye, ['north'])),
		glow(toy([11.25, 5.75, 10], [13.25, 7.75, 10.75], eye, ['south'])),
		// the tail fans out behind, on two crossed planes
		glow(planeZ(-2.5, 0.5, 3, 12, 8, fin)),
		glow(planeX(2.25, 0.5, 7.75, 12, 0.5, fin)),
		// the dorsal fin along the back, and a pectoral fin on each flank
		glow(planeZ(4.5, 9.25, 10, 12.75, 8, fin, [4, 0, 16, 9])),
		glow(planeZ(8, 3, 11.5, 5.5, 5.5, fin, [8, 8, 16, 16])),
		glow(planeZ(8, 3, 11.5, 5.5, 10.5, fin, [8, 8, 16, 16])),
	];

	return { elements, top: 12.75, flat: false };
}

function hoianBody(id: string): Body {
	const silk = T(`${id}_silk`);
	const wood = T('tt_wood');
	const profile: Array<[number, number]> = [[1, 2.75], [1, 3.75], [1.25, 4.5], [1.5, 5], [1.5, 5], [1.25, 4.5], [1, 3.75], [1, 2.75]];

	const foot = octagon(0, 1, 2.5, { side: wood, up: wood, down: wood });
	const bodyStack = lathe(1, profile, { side: silk, up: silk, down: silk }, true);
	const cap = octagon(bodyStack.top, bodyStack.top + 1, 2.5, { side: wood, up: wood, down: wood });
	const knob = toy([7.5, bodyStack.top + 1, 7.5], [8.5, bodyStack.top + 1.75, 8.5], T('tt_gold'));

	return { elements: [...foot, ...bodyStack.elements, ...cap, knob], top: bodyStack.top + 1.75, flat: false };
}

function redBody(sheet = 'tt_red_paper'): Body {
	const paper = T(sheet);
	const gold = T('tt_gold');
	const profile: Array<[number, number]> = [[1, 3.75], [1.5, 5], [3, 5.75], [1.5, 5], [1, 3.75]];

	const foot = octagon(0, 1.25, 3, { side: gold, up: gold, down: gold });
	const bodyStack = lathe(1.25, profile, { side: paper, up: paper, down: paper }, true);
	const cap = octagon(bodyStack.top, bodyStack.top + 1.25, 3, { side: gold, up: gold, down: gold });

	return { elements: [...foot, ...bodyStack.elements, ...cap], top: bodyStack.top + 1.25, flat: false };
}

function accordionBody(): Body {
	const rim = T('tt_wood');
	const elements: Element[] = [...octagon(0, 1, 3.75, { side: rim, up: rim, down: rim })];
	let at = 1;

	// the pleats bulge and pinch in turn, each fold its own colour
	for (let i = 0; i < 8; i++) {
		const half = i % 2 === 0 ? 4.5 : 4;
		const texture = T(`tt_pleat_${i % PLEATS.length}`);
		elements.push(...octagon(at, at + 1.25, half, { side: texture, up: texture, down: texture }, true));
		at += 1.25;
	}

	elements.push(...octagon(at, at + 1, 3.75, { side: rim, up: rim, down: rim }));

	return { elements, top: at + 1, flat: false };
}

/**
 * The rabbit lantern: a chibi white rabbit on a little wheeled cart, the way
 * they are pulled along the street on a string. The head is bigger than the
 * body, the way a toy's is, with the face filling its front; the body is a
 * stubby loaf behind and under it, the paws two nubs in front, the ears
 * long with their pink insides forward, and a round tail behind.
 */
function rabbitBody(): Body {
	const body = T('tt_rabbit_body');
	const face = T('tt_rabbit_face');
	const ear = T('tt_rabbit_ear');
	const inner = T('tt_rabbit_ear_inner');
	const wood = T('tt_wood');
	const wheel = T('tt_wheel');

	const elements: Element[] = [
		toy([3.5, 2.5, 3], [12.5, 3.5, 13.5], wood),
		// the body: a loaf, its back a step lower than the head
		glow(toy([5, 3.5, 6.5], [11, 8.5, 12.5], body)),
		glow(toy([5.5, 8.5, 7.5], [10.5, 9.25, 11.5], body)),
		// the paws in front of the body
		glow(toy([5, 3.5, 4.5], [7, 5.25, 6.5], body)),
		glow(toy([9, 3.5, 4.5], [11, 5.25, 6.5], body)),
		// the head, big, its face on the front
		glow(toy([4, 5.5, 2.5], [12, 12.5, 9.5], { all: body, north: face }, ['north'])),
		// the ears, long, leaning a little apart
		glow(toy([4.75, 12.5, 5], [6.75, 18.5, 6.25], { all: ear, north: inner })),
		glow(toy([9.25, 12.5, 5], [11.25, 18.5, 6.25], { all: ear, north: inner })),
		// the tail
		glow(toy([7, 5, 12.5], [9, 7, 13.75], body)),
	];

	for (const [x, z] of [[2.5, 4], [2.5, 10.5], [12.5, 4], [12.5, 10.5]] as const) {
		elements.push(toy([x, 0, z], [x + 1, 3, z + 3], { all: wood, east: wheel, west: wheel }, ['east', 'west']));
	}

	return { elements, top: 18.5, flat: false };
}

function butterflyBody(): Body {
	const wing = T('tt_butterfly_wing');
	const dark = T('tt_wood');

	const right = glow(planeZ(8, 2, 16, 14, 8, wing, [0, 0, 16, 16]));
	right.rotation = { origin: [8, 8, 8], axis: 'y', angle: -22.5 };
	const left = glow(planeZ(0, 2, 8, 14, 8, wing, [16, 0, 0, 16]));
	left.rotation = { origin: [8, 8, 8], axis: 'y', angle: 22.5 };

	const antennaL = part([6.75, 12, 7.75], [7.25, 15, 8.25], dark);
	antennaL.rotation = { origin: [7, 12, 8], axis: 'z', angle: 22.5 };
	const antennaR = part([8.75, 12, 7.75], [9.25, 15, 8.25], dark);
	antennaR.rotation = { origin: [9, 12, 8], axis: 'z', angle: -22.5 };

	return {
		elements: [
			right,
			left,
			toy([7.5, 3, 7.5], [8.5, 11, 8.5], dark),
			toy([7.25, 11, 7.25], [8.75, 12.5, 8.75], dark),
			antennaL,
			antennaR,
		],
		top: 14.5,
		flat: true,
	};
}

function lotusBody(): Body {
	const petal = T('tt_lotus_petal');
	const innerPetal = T('tt_lotus_petal_inner');
	const pod = T('tt_lotus_pod');
	const leaf = T('tt_lotus_leaf');

	const north = glow(planeZ(2.5, 0.5, 13.5, 11, 4, petal));
	north.rotation = { origin: [8, 0.5, 4], axis: 'x', angle: -22.5 };
	const south = glow(planeZ(2.5, 0.5, 13.5, 11, 12, petal));
	south.rotation = { origin: [8, 0.5, 12], axis: 'x', angle: 22.5 };
	const west = glow(planeX(2.5, 0.5, 13.5, 11, 4, petal));
	west.rotation = { origin: [4, 0.5, 8], axis: 'z', angle: 22.5 };
	const east = glow(planeX(2.5, 0.5, 13.5, 11, 12, petal));
	east.rotation = { origin: [12, 0.5, 8], axis: 'z', angle: -22.5 };

	const inner: Element[] = [];

	for (const z of [5.5, 10.5]) {
		const p = glow(planeZ(3.5, 0.75, 12.5, 12, z, innerPetal));
		p.rotation = { origin: [8, 5, 8], axis: 'y', angle: 45 };
		inner.push(p);

		const q = glow(planeX(3.5, 0.75, 12.5, 12, z, innerPetal));
		q.rotation = { origin: [8, 5, 8], axis: 'y', angle: 45 };
		inner.push(q);
	}

	return {
		elements: [
			planeY(-1, -1, 17, 17, 0.25, leaf),
			north,
			south,
			west,
			east,
			...inner,
			glow(toy([6.5, 0.5, 6.5], [9.5, 4, 9.5], pod)),
		],
		top: 12,
		flat: false,
	};
}

function revolvingBody(): Body {
	const panel = T('tt_revolving_panel');
	const wood = T('tt_wood');
	const roof = T('tt_red_paper');
	const tasselTex = T('tt_tassel_red');

	const elements: Element[] = [
		toy([3, 0, 3], [13, 1, 13], wood),
		toy([3, 11, 3], [13, 12, 13], wood),
	];

	for (const [x, z] of [[3, 3], [12, 3], [3, 12], [12, 12]] as const) {
		elements.push(toy([x, 1, z], [x + 1, 11, z + 1], wood));
	}

	elements.push(
		glow(planeZ(4, 1, 12, 11, 3.5, panel)),
		glow(planeZ(4, 1, 12, 11, 12.5, panel)),
		glow(planeX(4, 1, 12, 11, 3.5, panel)),
		glow(planeX(4, 1, 12, 11, 12.5, panel)),
		toy([2, 12, 2], [14, 13, 14], roof),
		toy([4, 13, 4], [12, 14, 12], roof),
		toy([7, 14, 7], [9, 15, 9], T('tt_gold')),
	);

	for (const [x, z] of [[2.25, 2.25], [13.75, 2.25], [2.25, 13.75], [13.75, 13.75]] as const) {
		elements.push(part([x - 0.5, 8.5, z - 0.5], [x + 0.5, 12, z + 0.5], tasselTex));
	}

	return { elements, top: 15, flat: false };
}

/** A heart lantern: the heart cut-out on two planes, like the star. */
function heartBody(id: string): Body {
	const sheet = T(`${id}_paper`);

	return {
		elements: [
			glow(planeZ(0, 0, 16, 16, 7.4, sheet)),
			glow(planeZ(0, 0, 16, 16, 8.6, sheet)),
		],
		top: 14.5,
		flat: true,
	};
}

/** The string lights set down: one heart standing on its point. */
function garlandBody(): Body {
	const heart = T('tt_heart_small');

	return {
		elements: [
			glow(planeZ(3, 0, 13, 10, 7.5, heart)),
			glow(planeZ(3, 0, 13, 10, 8.5, heart)),
		],
		top: 9.5,
		flat: true,
	};
}

/** Along a wall: a thread across the wall with four little hearts hanging from it. */
function garlandWall(): Element[] {
	const heart = T('tt_heart_small');
	const thread = T('tt_string_red');
	const elements: Element[] = [part([0, 12.5, 14.75], [16, 12.75, 15], thread)];
	const drops = [8.5, 7, 8, 6.5];

	[2, 6, 10, 14].forEach((x, i) => {
		const top = drops[i]! + 3.5;
		elements.push(part([x - 0.1, top, 14.8], [x + 0.1, 12.5, 14.95], thread));
		elements.push(glow(planeZ(x - 1.75, drops[i]!, x + 1.75, top, 14.6, heart)));
	});

	return elements;
}

/** From a ceiling: a strand hanging straight down with three hearts along it, turned both ways. */
function garlandCeiling(): Element[] {
	const heart = T('tt_heart_small');
	const elements: Element[] = [part([7.9, 1, 7.9], [8.1, 16, 8.1], T('tt_string_red'))];

	for (const y of [1, 5.5, 10]) {
		elements.push(glow(planeZ(6.25, y, 9.75, y + 3.5, 8, heart)));
		elements.push(glow(planeX(6.25, y, 9.75, y + 3.5, 8, heart)));
	}

	return elements;
}

function bodyOf(lantern: Lantern): Body {
	switch (lantern.kind) {
		case 'star':
			return starBody(lantern.id);
		case 'carp':
			return carpBody();
		case 'hoian':
			return hoianBody(lantern.id);
		case 'red':
			return redBody();
		case 'accordion':
			return accordionBody();
		case 'rabbit':
			return rabbitBody();
		case 'butterfly':
			return butterflyBody();
		case 'lotus':
			return lotusBody();
		case 'revolving':
			return revolvingBody();
		case 'heart':
			return heartBody(lantern.id);
		case 'lovers':
			return redBody('tt_lovers_paper');
		case 'garland':
			return garlandBody();
	}
}

/** Where a hung lantern's top ends up: just under the hook or the ceiling's string. */
const HANG_TOP = 13;

/** A hanging tassel for the round lanterns, in the colour that goes with the silk. */
function tasselFor(lantern: Lantern): Element[] {
	if (lantern.kind === 'hoian') {
		const colour = lantern.id.endsWith('_red') || lantern.id.endsWith('_orange') ? 'yellow' : 'red';
		return tassel(8, 8, 0, 3, T(`tt_tassel_${colour}`));
	}

	if (lantern.kind === 'red' || lantern.kind === 'lovers') {
		return tassel(8, 8, 0, 4, T('tt_tassel_yellow'));
	}

	return [];
}

/** The body lowered so its top meets the suspension, plus its tassel. */
function hung(lantern: Lantern): { elements: Element[]; top: number } {
	const body = bodyOf(lantern);
	const drop = HANG_TOP - body.top;

	return {
		elements: [...shifted(body.elements, 0, drop, 0), ...shifted(tasselFor(lantern), 0, drop, 0)],
		top: HANG_TOP,
	};
}

/** Hung from a ceiling: a red thread from the top of the block down to the lantern. */
function ceilingModel(lantern: Lantern): Element[] {
	if (lantern.kind === 'garland') {
		return garlandCeiling();
	}

	const { elements, top } = hung(lantern);

	return [...elements, part([7.75, top, 7.75], [8.25, 16, 8.25], T('tt_string_red'))];
}

/**
 * Hung on a wall: an iron bracket out of the wall behind (the south face, as
 * every wall piece here is authored), a thread down from its end.
 */
function wallModel(lantern: Lantern): Element[] {
	if (lantern.kind === 'garland') {
		return garlandWall();
	}

	const { elements, top } = hung(lantern);
	const iron = T('tt_iron');

	return [
		...elements,
		part([7.25, 12.5, 15], [8.75, 16, 16], iron),
		part([7.5, 14.5, 8], [8.5, 15.5, 15], iron),
		part([7.5, 13.5, 7.5], [8.5, 14.5, 8.5], iron),
		part([7.75, top, 7.75], [8.25, 13.5, 8.25], T('tt_string_red')),
	];
}

/** How big a carried lantern is next to a placed one: the toys on sticks are small. */
const CARRIED_SCALE = 0.62;

/** Scales elements by `k` about `from` and moves that point to `to`, rotation origins included. */
function rescaled(elements: Element[], k: number, from: Vec3, to: Vec3): Element[] {
	const map = (p: Vec3): Vec3 => [
		to[0] + (p[0] - from[0]) * k,
		to[1] + (p[1] - from[1]) * k,
		to[2] + (p[2] - from[2]) * k,
	];

	return elements.map((element) => {
		const copy = JSON.parse(JSON.stringify(element)) as Element;
		copy.from = map(copy.from as Vec3);
		copy.to = map(copy.to as Vec3);

		const rotation = copy.rotation as { origin: Vec3 } | undefined;

		if (rotation) {
			rotation.origin = map(rotation.origin);
		}

		return copy;
	});
}

/**
 * The same elements a quarter turn about the vertical axis through (8, 8):
 * what faced north faces east. Boxes stay boxes; each face takes the name of
 * the side it now points at, and an element's own tilt moves to the matching
 * axis (a tilt about x becomes one about z, a tilt about z one about x with
 * its sign flipped, since +z turns into -x).
 */
function quarterTurned(elements: Element[]): Element[] {
	const map = (p: Vec3): Vec3 => [16 - p[2], p[1], p[0]];
	const faceName: Record<string, string> = { north: 'east', east: 'south', south: 'west', west: 'north', up: 'up', down: 'down' };

	return elements.map((element) => {
		const copy = JSON.parse(JSON.stringify(element)) as Element;
		const a = map(copy.from as Vec3);
		const b = map(copy.to as Vec3);
		copy.from = [Math.min(a[0], b[0]), a[1], Math.min(a[2], b[2])];
		copy.to = [Math.max(a[0], b[0]), b[1], Math.max(a[2], b[2])];

		const faces = copy.faces as Record<string, unknown>;
		const turned: Record<string, unknown> = {};

		for (const [name, face] of Object.entries(faces)) {
			turned[faceName[name]!] = face;
		}

		copy.faces = turned;

		const rotation = copy.rotation as { origin: Vec3; axis: string; angle: number } | undefined;

		if (rotation) {
			rotation.origin = map(rotation.origin);

			if (rotation.axis === 'x') {
				rotation.axis = 'z';
			} else if (rotation.axis === 'z') {
				rotation.axis = 'x';
				rotation.angle = -rotation.angle;
			}
		}

		return copy;
	});
}

/**
 * Carried, the way the toys are sold at the market: an orange plastic
 * handle, which is the battery box, with its switch on the side; a thin
 * white rod rising from it at a slant; and the lantern hanging off a short
 * wire at the rod's tip, smaller than a placed one.
 *
 * Authored like the fishing rod's sprite, in the x-y plane with the grip low
 * on the left and the tip high on the right, and held with the fishing rod's
 * turn so the tip points ahead of the player. The rod is a staircase of
 * small boxes rather than one turned box: a turned element's direction is
 * the one thing this toolchain's previewer and the game disagree on.
 */
function heldModel(lantern: Lantern): Element[] {
	const body = bodyOf(lantern);
	const handle = T('tt_toy_handle');
	const rod = T('tt_toy_rod');

	const elements: Element[] = [
		toy([2, -4, 7], [4.5, 5, 9], { all: handle, up: T('tt_toy_handle_end'), down: T('tt_toy_handle_end') }),
		toy([4.5, 1.5, 7.5], [5, 3, 8.5], T('tt_toy_switch')),
	];

	let x = 2.75;
	let y = 5;

	for (let i = 0; i < 12; i++) {
		elements.push(part([x, y, 7.6], [x + 1, y + 1.1, 8.4], rod));
		x += 0.8;
		y += 0.95;
	}

	// the tip's hook, and the wire the lantern hangs from
	const tipX = x + 0.5;
	const tipY = y + 0.4;
	const wireTop = tipY;
	const wireBottom = tipY - 2.5;

	elements.push(part([tipX - 0.5, tipY - 0.2, 7.6], [tipX + 0.5, tipY + 0.6, 8.4], rod));
	elements.push(part([tipX - 0.1, wireBottom, 7.9], [tipX + 0.1, wireTop, 8.1], T('tt_iron')));

	// the rod points ahead of the player, so the lantern is turned to face
	// along it: a star seen from behind the handle is face on, not edge on,
	// and a carp's length runs away from the rod instead of into it
	const hanging = quarterTurned([...body.elements, ...tasselFor(lantern)]);
	elements.push(...rescaled(hanging, CARRIED_SCALE, [8, body.top, 8], [tipX, wireBottom, 8]));

	return elements;
}

/**
 * The fishing rod's own hold, without its downward tilt: the rod points
 * ahead and the model's up stays up, so the lantern hangs straight down off
 * the tip in third person and in first.
 */
const HELD_DISPLAY = {
	thirdperson_righthand: { rotation: [0, 90, 0], translation: [0, 5, 0.5], scale: [0.7, 0.7, 0.7] },
	thirdperson_lefthand: { rotation: [0, -90, 0], translation: [0, 5, 0.5], scale: [0.7, 0.7, 0.7] },
	firstperson_righthand: { rotation: [0, 90, 10], translation: [1.13, 3.5, 1.13], scale: [0.55, 0.55, 0.55] },
	firstperson_lefthand: { rotation: [0, -90, -10], translation: [1.13, 3.5, 1.13], scale: [0.55, 0.55, 0.55] },
	head: { rotation: [0, 0, 0], translation: [0, 10, 0], scale: [0.6, 0.6, 0.6] },
};

/** The slot, the ground and the frame show the lantern alone, turned three-quarters unless it is a flat cut-out. */
function bodyDisplay(flat: boolean): Record<string, unknown> {
	const gui = flat
		? { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.9, 0.9, 0.9] }
		: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.66, 0.66, 0.66] };

	return {
		gui,
		ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.3, 0.3, 0.3] },
		fixed: { rotation: [0, flat ? 0 : 180, 0], translation: [0, 0, 0], scale: [0.6, 0.6, 0.6] },
		thirdperson_righthand: { rotation: [0, 0, 0], translation: [0, 3, 1], scale: [0.4, 0.4, 0.4] },
		thirdperson_lefthand: { rotation: [0, 0, 0], translation: [0, 3, 1], scale: [0.4, 0.4, 0.4] },
		firstperson_righthand: { rotation: [0, -25, 0], translation: [1, 2, 1], scale: [0.4, 0.4, 0.4] },
		firstperson_lefthand: { rotation: [0, 25, 0], translation: [1, 2, 1], scale: [0.4, 0.4, 0.4] },
	};
}

// ---- mooncake models -------------------------------------------------------------

/** Where the cake sits on its plate. */
const CAKE_FROM = 4;
const CAKE_TO = 12;
const CAKE_Y0 = 1;
const CAKE_Y1 = 5;

/** A box's uv across the cake: sixteen texels from one edge of the cake to the other. */
function across(a: number, b: number): [number, number] {
	return [((a - CAKE_FROM) * 16) / (CAKE_TO - CAKE_FROM), ((b - CAKE_FROM) * 16) / (CAKE_TO - CAKE_FROM)];
}

/**
 * The quarters in the order they are eaten: the two toward the person who
 * set the plate down first (the model faces north), then round.
 */
const QUARTERS: Array<{ x: [number, number]; z: [number, number] }> = [
	{ x: [8, 12], z: [4, 8] },
	{ x: [4, 8], z: [4, 8] },
	{ x: [4, 8], z: [8, 12] },
	{ x: [8, 12], z: [8, 12] },
];

/** One quarter of the cake, its cut faces wearing the cross-section where a neighbour is gone. */
function quarter(q: { x: [number, number]; z: [number, number] }, remaining: boolean[], id: string, skin: MooncakeSkin): Element {
	const [x0, x1] = q.x;
	const [z0, z1] = q.z;
	const top = T(`mc_${skin}_top`);
	const side = T(`mc_${skin}_side`);
	const cut = T(`${id}_cut`);
	const [u0, u1] = across(x0, x1);
	const [w0, w1] = across(z0, z1);
	const neighbourGone = (dx: number, dz: number): boolean => {
		const nx = dx === 0 ? x0 : dx < 0 ? x0 - 4 : x1;
		const nz = dz === 0 ? z0 : dz < 0 ? z0 - 4 : z1;
		const index = QUARTERS.findIndex((other) => other.x[0] === nx && other.z[0] === nz);

		return index >= 0 && !remaining[index];
	};

	const faces: Record<string, unknown> = {
		up: { uv: [u0, w0, u1, w1], texture: top },
	};

	// outer faces show the fluted side; an inner face shows the cut only
	// when the quarter beside it has been eaten, and nothing otherwise
	const sideFace = (dir: 'north' | 'south' | 'east' | 'west', outer: boolean, a: number, b: number, dx: number, dz: number): void => {
		if (outer) {
			faces[dir] = { uv: [a, 0, b, 16], texture: side };
		} else if (neighbourGone(dx, dz)) {
			faces[dir] = { uv: [a, 0, b, 16], texture: cut };
		}
	};

	sideFace('north', z0 === CAKE_FROM, u0, u1, 0, -1);
	sideFace('south', z1 === CAKE_TO, u0, u1, 0, 1);
	sideFace('west', x0 === CAKE_FROM, w0, w1, -1, 0);
	sideFace('east', x1 === CAKE_TO, w0, w1, 1, 0);

	return { from: [x0, CAKE_Y0, z0], to: [x1, CAKE_Y1, z1], faces };
}

/** The plate: a flat octagon of porcelain. */
function plateElements(): Element[] {
	return octagon(0, 1, 5.75, { side: T('tt_plate_rim'), up: T('tt_plate'), down: T('tt_plate') });
}

/**
 * The heart cake seen from above, as rectangles (x0, x1, z0, z1): the point
 * toward whoever set the plate down (north), the two lobes away from them.
 * No edge sits on x 8 or z 8, the lines the cake is cut along, so every cut
 * face has cake of the same width on both sides of it.
 */
const HEART_RECTS: Array<[number, number, number, number]> = [
	[7, 9, 3.5, 5],
	[6, 10, 5, 6.5],
	[5, 11, 6.5, 8.5],
	[4, 12, 8.5, 10.75],
	[4, 7.5, 10.75, 12],
	[8.5, 12, 10.75, 12],
	[4.75, 7, 12, 12.75],
	[9, 11.25, 12, 12.75],
];

const HEART_Z0 = 3.5;
const HEART_Z1 = 12.75;

/**
 * A heart cake after `bites` quarters: every rectangle clipped to each
 * uneaten quarter. A face on a cut line wears the cross-section when the
 * quarter across it is gone and is left out while it is there; every other
 * side is crust. Faces between two rectangles of the same quarter are inside
 * the cake and never seen.
 */
function heartCakeElements(cake: Mooncake, bites: number): Element[] {
	const top = T(`mc_${cake.skin}_heart_top`);
	const side = T(`mc_${cake.skin}_side`);
	const cut = T(`${cake.id}_cut`);
	const remaining = QUARTERS.map((_, i) => i >= bites);
	const elements: Element[] = [];

	// the quarters by which half of each cut line they sit on
	const quarterOf = (east: boolean, south: boolean): number =>
		QUARTERS.findIndex((q) => (q.x[0] === 8) === east && (q.z[0] === 8) === south);

	for (const [rx0, rx1, rz0, rz1] of HEART_RECTS) {
		for (const east of [false, true]) {
			for (const south of [false, true]) {
				const index = quarterOf(east, south);

				if (!remaining[index]) {
					continue;
				}

				const x0 = Math.max(rx0, east ? 8 : -99);
				const x1 = Math.min(rx1, east ? 99 : 8);
				const z0 = Math.max(rz0, south ? 8 : -99);
				const z1 = Math.min(rz1, south ? 99 : 8);

				if (x1 - x0 <= 0 || z1 - z0 <= 0) {
					continue;
				}

				const u = (x: number): number => ((x - CAKE_FROM) * 16) / (CAKE_TO - CAKE_FROM);
				const v = (z: number): number => ((z - HEART_Z0) * 16) / (HEART_Z1 - HEART_Z0);
				const faces: Record<string, unknown> = {
					up: { uv: [u(x0), v(z0), u(x1), v(z1)], texture: top },
				};

				const face = (dir: string, onCut: boolean, across: number, a: number, b: number): void => {
					if (!onCut) {
						faces[dir] = { uv: [a, 0, b, 16], texture: side };
					} else if (!remaining[across]) {
						faces[dir] = { uv: [a, 0, b, 16], texture: cut };
					}
				};

				face('west', east && x0 === 8, quarterOf(false, south), v(z0), v(z1));
				face('east', !east && x1 === 8, quarterOf(true, south), v(z0), v(z1));
				face('north', south && z0 === 8, quarterOf(east, false), u(x0), u(x1));
				face('south', !south && z1 === 8, quarterOf(east, true), u(x0), u(x1));

				elements.push({ from: [x0, CAKE_Y0, z0], to: [x1, CAKE_Y1, z1], faces });
			}
		}
	}

	return elements;
}

function mooncakeElements(cake: Mooncake, bites: number): Element[] {
	const elements = plateElements();

	if (cake.shape === 'heart') {
		return [...elements, ...heartCakeElements(cake, bites)];
	}

	if (bites === 0) {
		const top = T(`mc_${cake.skin}_top`);
		const side = T(`mc_${cake.skin}_side`);
		const uv: [number, number, number, number] = [0, 0, 16, 16];

		elements.push({
			from: [CAKE_FROM, CAKE_Y0, CAKE_FROM],
			to: [CAKE_TO, CAKE_Y1, CAKE_TO],
			faces: {
				up: { uv, texture: top },
				north: { uv, texture: side },
				south: { uv, texture: side },
				east: { uv, texture: side },
				west: { uv, texture: side },
			},
		});

		return elements;
	}

	const remaining = QUARTERS.map((_, i) => i >= bites);

	QUARTERS.forEach((q, i) => {
		if (remaining[i]) {
			elements.push(quarter(q, remaining, cake.id, cake.skin));
		}
	});

	return elements;
}

// ---- masks, drum, pomelo dog ---------------------------------------------------

/**
 * A mask face at sixteen pixels, a texel a unit on the head it fronts, the
 * grain of a vanilla mob's face: the skin first, then `paint` draws the
 * features.
 */
function maskSheet(paint: (image: Bitmap) => void, skin?: Rgba | string): Bitmap {
	const image = bitmap(16, 16);

	if (skin) {
		bevel(image, 0, 0, 16, 16, skin);
	}

	paint(image);

	return image;
}

/** A filled ellipse with a darker rim: how a feature sits on a painted face. */
function inkedEllipse(image: Bitmap, cx: number, cy: number, rx: number, ry: number, colour: Rgba | string): void {
	const r = ramp(colour);
	ellipse(image, cx, cy, rx + 1, ry + 1, r.line);
	ellipse(image, cx, cy, rx, ry, r.base);
}

/** Ông Địa: a round pink face laughing with his whole head, eyes shut in crescents, cheeks rosy. */
function ongDiaSheet(): Bitmap {
	return maskSheet((image) => {
		inkedEllipse(image, 3.5, 10, 1.6, 1.1, '#f48a8a');
		inkedEllipse(image, 12.5, 10, 1.6, 1.1, '#f48a8a');

		// the brows, then the eyes shut in upturned crescents
		rect(image, 2, 4, 6, 5, INK);
		rect(image, 10, 4, 14, 5, INK);

		for (let x = 0; x < 4; x++) {
			const dip = Math.round(Math.sin((x / 3) * Math.PI) * 1.2);
			put(image, 2 + x, 7 - dip, INK);
			put(image, 10 + x, 7 - dip, INK);
		}

		// the nose, and the big open laugh
		inkedEllipse(image, 8, 9, 1.2, 0.9, '#e89a85');
		inkedEllipse(image, 8, 12.25, 4, 2.25, '#8e1b1b');
		rect(image, 4, 10, 12, 11, rgb('#f5b7a0'));
		rect(image, 5, 11, 11, 12, WHITE);
		ellipse(image, 8, 13.5, 2, 0.75, rgb('#e57373'));
	}, '#f5b7a0');
}

/** Tôn Ngộ Không: an opera monkey face, gold circlet, a red heart round the golden eyes, fur at the edges. */
function ngoKhongSheet(): Bitmap {
	return maskSheet((image) => {
		inkedEllipse(image, 8, 9, 6.5, 6, '#f4e6c8');
		inkedEllipse(image, 5, 8, 2.7, 3, '#d32f2f');
		inkedEllipse(image, 11, 8, 2.7, 3, '#d32f2f');
		inkedEllipse(image, 5, 8, 1.3, 1.1, '#ffd54f');
		inkedEllipse(image, 11, 8, 1.3, 1.1, '#ffd54f');
		rect(image, 5, 7, 6, 9, INK);
		rect(image, 11, 7, 12, 9, INK);
		put(image, 4, 7, WHITE);
		put(image, 10, 7, WHITE);
		rect(image, 7, 10, 9, 12, INK);
		inkedEllipse(image, 8, 13.25, 2.5, 1, '#8e1b1b');

		// the circlet across the brow
		rect(image, 0, 2, 16, 4, GOLD);
		rect(image, 0, 2, 16, 3, GOLD_LIGHT);
		rect(image, 7, 2, 9, 4, rgb('#e53935'));
	}, '#c8a060');
}

/** The pig's snout, eight texels across its seven units: two nostrils on pink. */
function snoutSheet(): Bitmap {
	return coarse(8, (image) => {
		bevel(image, 0, 0, 8, 8, '#f48fb1');
		rect(image, 2, 3, 3, 5, rgb('#8e3a59'));
		rect(image, 5, 3, 6, 5, rgb('#8e3a59'));
	});
}

/** The lion's face: red and gold, huge lashed eyes, the mirror on its brow, a wide mouth. */
function lanSheet(): Bitmap {
	return maskSheet((image) => {
		for (let y = 1; y < 15; y++) {
			for (let x = 1; x < 15; x++) {
				if ((x + y) % 6 === 0) {
					put(image, x, y, rgb('#ffca28'));
				}
			}
		}

		inkedEllipse(image, 4.5, 7, 3, 2.5, WHITE);
		inkedEllipse(image, 11.5, 7, 3, 2.5, WHITE);
		disc(image, 4.75, 7.25, 1.6, INK);
		disc(image, 11.25, 7.25, 1.6, INK);
		put(image, 4, 6, WHITE);
		put(image, 10, 6, WHITE);
		rect(image, 1, 3, 7, 4, INK);
		rect(image, 9, 3, 15, 4, INK);
		inkedEllipse(image, 8, 2.5, 1.25, 1, '#b3e5fc');
		put(image, 7, 2, WHITE);

		// the mouth: a dark cave with a row of teeth
		rect(image, 1, 11, 15, 15, ramp('#8e1b1b').line);
		rect(image, 2, 12, 14, 14, rgb('#8e1b1b'));

		for (let x = 2; x < 14; x += 2) {
			put(image, x, 12, WHITE);
		}

		inkedEllipse(image, 8, 9.5, 2, 0.9, '#ffca28');
	}, '#e53935');
}

/** White fur trim: strands in three shades, lined round. */
function furSheet(): Bitmap {
	return tasselSheet(rgb('#f5f5f5'));
}

/** The drum's shell: red lacquer, lit along its bulge, with a brass band at each end. */
function drumBodySheet(): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp('#c62828');
	rect(image, 0, 0, 16, 16, r.base);
	rect(image, 0, 5, 16, 10, r.light);
	rect(image, 0, 0, 16, 2, GOLD_DARK);
	rect(image, 0, 14, 16, 16, GOLD_DARK);
	rect(image, 0, 1, 16, 2, GOLD);
	rect(image, 0, 14, 16, 15, GOLD);

	return image;
}

/** The drum's head: stretched hide, its bevelled rim, a red disc painted in the middle. */
function drumHeadSheet(): Bitmap {
	const image = toySheet('#efe0c0');
	inkedEllipse(image, 8, 8, 4, 4, '#c62828');
	put(image, 6, 6, rgb('#e57373'));

	return image;
}

/** A brass stud, for the ring of them round each head: under a unit across, so plain brass. */
function studSheet(): Bitmap {
	return toySheet(GOLD);
}

/** Pomelo flesh: pale pink segments, each a lit sac with the membrane dark between. */
function pomeloFleshSheet(): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp('#fbe3d9');
	rect(image, 0, 0, 16, 16, r.base);

	for (let x = 0; x < 16; x += 4) {
		rect(image, x, 0, x + 1, 16, rgb('#e8b8a8'));
		rect(image, x + 1, 0, x + 2, 16, r.light);
	}

	return image;
}

/** The dog's face, eight texels across its eight-unit head: two clove eyes and a clove nose over the flesh. */
function pomeloFaceSheet(): Bitmap {
	const r = ramp('#fbe3d9');

	return coarse(8, (image) => {
		rect(image, 0, 0, 8, 8, r.base);

		for (let x = 0; x < 8; x += 4) {
			rect(image, x, 0, x + 1, 8, rgb('#e8b8a8'));
			rect(image, x + 1, 0, x + 2, 8, r.light);
		}

		put(image, 2, 2, rgb('#2b1a10'));
		put(image, 5, 2, rgb('#2b1a10'));
		rect(image, 3, 4, 5, 5, rgb('#2b1a10'));
		rect(image, 3, 5, 5, 6, rgb('#e57373'));
	});
}

/**
 * The lion drum: a fat barrel on four legs, a hide head with a ring of
 * brass studs round it, and the two sticks laid across the top.
 */
function drumElements(): Element[] {
	const body = T('tt_drum_body');
	const head = T('tt_drum_head');
	const wood = T('tt_wood');
	const stud = T('tt_stud');
	const elements: Element[] = [];

	for (const [x, z] of [[2.5, 7.25], [12, 7.25], [7.25, 2.5], [7.25, 12]] as const) {
		elements.push(toy([x, 0, z], [x + 1.5, 4.5, z + 1.5], wood));
	}

	elements.push(...octagon(4, 6.5, 5.5, { side: body, down: wood }));
	elements.push(...octagon(6.5, 10.5, 6.25, { side: body }));
	elements.push(...octagon(10.5, 13, 5.5, { side: body, up: head }));

	// the studs, eight round the top head
	for (let i = 0; i < 8; i++) {
		const a = (i * Math.PI) / 4 + Math.PI / 8;
		const x = 8 + Math.cos(a) * 4.6;
		const z = 8 + Math.sin(a) * 4.6;
		elements.push(toy([x - 0.4, 13, z - 0.4], [x + 0.4, 13.5, z + 0.4], stud));
	}

	const stickA = toy([3, 13.5, 7], [13, 14.25, 7.75], wood);
	stickA.rotation = { origin: [8, 13.5, 8], axis: 'y', angle: 22.5 };
	const stickB = toy([3, 14.25, 8.5], [13, 15, 9.25], wood);
	stickB.rotation = { origin: [8, 13.5, 8], axis: 'y', angle: -22.5 };
	elements.push(stickA, stickB);

	return elements;
}

/**
 * The pomelo dog: a fat body of pomelo segments on four stubby legs, a
 * big head at the front wearing the clove face, two peel ears flopping down,
 * a peel tail up behind, and a red ribbon round its neck.
 */
function pomeloDogElements(): Element[] {
	const flesh = T('tt_pomelo_flesh');
	const face = T('tt_pomelo_face');
	const peel = T('tt_pomelo_peel');
	const elements: Element[] = [
		toy([4.5, 2, 5], [11.5, 8, 13], flesh),
		toy([4, 5, 1], [12, 11.5, 6], { all: flesh, north: face }, ['north']),
		// the ears hang down the sides of the head
		toy([3, 6, 2], [4, 10.5, 5], peel),
		toy([12, 6, 2], [13, 10.5, 5], peel),
		// the tail
		toy([7.25, 7, 13], [8.75, 10.5, 14.25], peel),
		// a red ribbon round the neck, with its bow on top
		toy([4.25, 6.5, 5.25], [11.75, 7.5, 6.5], T('tt_string_red')),
		toy([6.5, 7.5, 5], [9.5, 9, 6.5], T('tt_string_red')),
	];

	for (const [x, z] of [[5, 5.5], [9.5, 5.5], [5, 10.5], [9.5, 10.5]] as const) {
		elements.push(toy([x, 0, z], [x + 1.5, 2, z + 1.5], flesh));
	}

	return elements;
}

/**
 * Mâm cỗ Trung Thu: a round red lacquer tray with a gold rim, the pomelo in
 * the middle with its leaf, two baked mooncakes beside it, a bunch of grapes,
 * a hand of bananas and two persimmons round the edge.
 */
function mamCoElements(): Element[] {
	const lacquer = T('tt_lacquer');
	const gold = T('tt_gold');
	const peel = T('tt_pomelo_peel');
	const top = T('tt_mamco_cake_top');
	const side = T('tt_mamco_cake_side');
	const elements: Element[] = [
		toy([1.5, 0, 2.5], [14.5, 1.5, 13.5], lacquer),
		toy([2.5, 0, 1.5], [13.5, 1.5, 14.5], lacquer),
		// the gold rim
		toy([1.5, 1.5, 2.5], [14.5, 2, 3], gold),
		toy([1.5, 1.5, 13], [14.5, 2, 13.5], gold),
		toy([1.5, 1.5, 3], [2, 2, 13], gold),
		toy([14, 1.5, 3], [14.5, 2, 13], gold),
		// the pomelo, peeled open at the top, and its leaf
		toy([6, 1.5, 6], [10.5, 6, 10.5], peel),
		toy([6.75, 6, 6.75], [9.75, 6.75, 9.75], T('tt_pomelo_flesh')),
		toy([8, 6.75, 7.5], [10.5, 7, 8.5], T('tt_lotus_leaf')),
		// two mooncakes
		toy([2.5, 1.5, 4], [5.5, 3, 7], { all: side, up: top }, ['up']),
		toy([10.75, 1.5, 9.5], [13.75, 3, 12.5], { all: side, up: top }, ['up']),
		// persimmons
		toy([11, 1.5, 4], [13, 3.5, 6], T('tt_persimmon')),
		toy([3, 1.5, 10], [5, 3.5, 12], T('tt_persimmon')),
		// a hand of bananas
		toy([6, 1.5, 11.25], [10.5, 2.75, 12.5], T('tt_banana')),
		toy([6.5, 2.75, 11.5], [10, 3.75, 12.25], T('tt_banana')),
		// grapes
		toy([6.25, 1.5, 3], [9.25, 3, 5], T('tt_grape')),
		toy([6.75, 3, 3.5], [8.75, 4, 4.5], T('tt_grape')),
	];

	return elements;
}

/**
 * A tò he stand: the straw post a street seller carries, bristling with
 * bamboo sticks, each topped with a little glutinous-rice clay figure in a
 * bright colour.
 */
function toHeElements(): Element[] {
	const straw = T('tt_straw');
	const stick = T('tt_bamboo');
	const elements: Element[] = [
		toy([6.5, 0, 6.5], [9.5, 6, 9.5], T('tt_wood')),
		toy([5.5, 6, 5.5], [10.5, 11, 10.5], straw),
		toy([6, 11, 6], [10, 12, 10], straw),
	];
	const COLOURS = ['red', 'yellow', 'green', 'blue', 'pink', 'white', 'orange', 'purple'];
	const spots: Array<[number, number, number, number]> = [
		[4.5, 8, 5, -25], [11.5, 9, 6, 25], [5, 10, 11, -20], [11, 7.5, 11, 20],
		[8, 12, 4, 0], [8, 12, 12, 0], [3.5, 9.5, 8, -30], [12.5, 10, 8.5, 30],
	];

	spots.forEach(([x, y, z, tilt], i) => {
		const rod = toy([x - 0.25, y - 3, z - 0.25], [x + 0.25, y + 2, z + 0.25], stick);
		rod.rotation = { origin: [x, y - 3, z], axis: x === 8 ? 'x' : 'z', angle: (tilt < 0 ? -22.5 : tilt > 0 ? 22.5 : 0) as 0 };
		elements.push(rod);
		const clay = T(`tt_clay_${COLOURS[i]}`);
		const body = toy([x - 1, y + 2, z - 1], [x + 1, y + 4, z + 1], clay);
		body.rotation = rod.rotation;
		const head = toy([x - 0.75, y + 4, z - 0.75], [x + 0.75, y + 5.25, z + 0.75], T('tt_clay_skin'));
		head.rotation = rod.rotation;
		elements.push(body, head);
	});

	return elements;
}

function maskElements(id: string): Element[] {
	const face = T(id);
	const back = T('tt_wood');

	switch (id) {
		case 'mask_ong_dia': {
			// a whole head, bigger than the wearer's: the face on the front,
			// his bald pink crown everywhere else
			const skin = T('tt_ong_dia_skin');

			return [
				{
					from: [-1, -0.5, -1],
					to: [17, 17.5, 17],
					faces: {
						north: { uv: [0, 0, 16, 16], texture: face },
						south: { uv: [0, 0, 16, 16], texture: skin },
						east: { uv: [0, 0, 16, 16], texture: skin },
						west: { uv: [0, 0, 16, 16], texture: skin },
						up: { uv: [0, 0, 16, 16], texture: skin },
					},
				},
			];
		}

		case 'mask_bat_gioi': {
			// a whole pig's head like Ông Địa's, so the snout and the ears have
			// something solid to grow out of instead of floating off a plate
			const skin = T('tt_bat_gioi_skin');
			const ear = T('tt_pig_ear_inner');

			return [
				helmet(face, skin),
				toy([4.5, 3.5, -3.5], [11.5, 8.5, -1], { all: skin, north: T('tt_snout') }, ['north']),
				// the ears: a broad flap and a narrower tip over it, stepped like a triangle
				toy([0, 16.5, 3], [5, 18, 5], { all: skin, north: ear }),
				toy([1, 18, 3], [4, 19.5, 5], { all: skin, north: ear }),
				toy([1.75, 19.5, 3], [3.25, 20.5, 5], skin),
				toy([11, 16.5, 3], [16, 18, 5], { all: skin, north: ear }),
				toy([12, 18, 3], [15, 19.5, 5], { all: skin, north: ear }),
				toy([12.75, 19.5, 3], [14.25, 20.5, 5], skin),
			];
		}

		case 'mask_rong': {
			const scale = T('tt_dragon_scale');
			const gold = T('tt_gold');
			const fur = T('tt_fur_red');
			const mouth = T('tt_dragon_mouth');
			const beard = T('tt_fur_white');

			return [
				helmet(face, scale),
				// the long snout with its nostrils and teeth, the jaw hanging open under it
				toy([3, 2, -6], [13, 8, -1], { all: scale, north: T('tt_dragon_snout') }, ['north']),
				toy([4, -1.5, -5], [12, 1, -1], { all: scale, up: mouth, north: mouth }, ['up', 'north']),
				toy([3.5, -3, -4], [12.5, -1.5, -1], beard),
				// antlers, a main beam and a tine each, rising off the back of the head
				toy([2.5, 17, 7], [4.5, 23.5, 9], gold),
				toy([0.5, 20, 7], [2.5, 21.5, 9], gold),
				toy([11.5, 17, 7], [13.5, 23.5, 9], gold),
				toy([13.5, 20, 7], [15.5, 21.5, 9], gold),
				// whiskers trailing from the snout
				part([-5, 5.5, -5], [3, 6, -4.5], gold),
				part([13, 5.5, -5], [21, 6, -4.5], gold),
				// the red mane down the back and over the crown
				part([-1.5, 17, 9], [17.5, 18.5, 17.5], fur),
				part([-2, 4, 9], [-1, 17, 17.5], fur),
				part([17, 4, 9], [18, 17, 17.5], fur),
				part([5, 17, 0], [11, 19, 6], fur),
			];
		}

		case 'mask_lan': {
			const fur = T('tt_fur_white');

			return [
				planeMask(face, back, -2, 18, -1, 18),
				toy([7, 18, -0.5], [9, 21, 1.5], T('tt_gold')),
				toy([-2.5, 17.5, -1.5], [18.5, 19, 0.5], fur),
				toy([-3, 3, -1.5], [-1.5, 18, 0.5], fur),
				toy([17.5, 3, -1.5], [19, 18, 0.5], fur),
				...strap(),
			];
		}

		default:
			return [planeMask(face, back, 0.5, 15.5, 1, 15.5), ...strap()];
	}
}

/** A whole head a size up from the wearer's: the face on the front, skin everywhere else. */
function helmet(face: string, skin: string): Element {
	const uv: [number, number, number, number] = [0, 0, 16, 16];

	return {
		from: [-0.5, -0.5, -0.5],
		to: [16.5, 16.5, 16.5],
		faces: {
			north: { uv, texture: face },
			south: { uv, texture: skin },
			east: { uv, texture: skin },
			west: { uv, texture: skin },
			up: { uv, texture: skin },
		},
	};
}

/** A face plate one unit thick in front of the head: the art on the front, the plain back inside. */
function planeMask(face: string, back: string, x0: number, x1: number, y0: number, y1: number): Element {
	return {
		from: [x0, y0, -1],
		to: [x1, y1, 0],
		faces: {
			north: { uv: [0, 0, 16, 16], texture: face },
			south: { uv: [0, 0, 16, 16], texture: back },
		},
	};
}

/** The elastic round the back of the head. */
function strap(): Element[] {
	const thread = T('tt_string_red');

	return [
		part([-0.5, 8.5, 0], [0, 9, 16.5], thread, { only: ['west', 'east', 'up', 'down'] }),
		part([16, 8.5, 0], [16.5, 9, 16.5], thread, { only: ['west', 'east', 'up', 'down'] }),
		part([0, 8.5, 16], [16, 9, 16.5], thread, { only: ['north', 'south', 'up', 'down'] }),
	];
}

/** Masks are worn at the head's own size; the slot shows the face straight on. */
const MASK_DISPLAY = {
	head: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
	gui: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.62, 0.62, 0.62] },
	ground: { rotation: [0, 0, 0], translation: [0, 2, 0], scale: [0.3, 0.3, 0.3] },
	fixed: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.55, 0.55, 0.55] },
	thirdperson_righthand: { rotation: [0, 180, 0], translation: [0, 3, 1], scale: [0.35, 0.35, 0.35] },
	thirdperson_lefthand: { rotation: [0, 180, 0], translation: [0, 3, 1], scale: [0.35, 0.35, 0.35] },
	firstperson_righthand: { rotation: [0, 160, 0], translation: [0, 3, 0], scale: [0.4, 0.4, 0.4] },
	firstperson_lefthand: { rotation: [0, 200, 0], translation: [0, 3, 0], scale: [0.4, 0.4, 0.4] },
};

// ---- item sprites -----------------------------------------------------------------

function durianSprite(): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp('#9aa843');
	ellipse(image, 8, 9, 6, 6.2, r.base);
	ellipse(image, 6.5, 7.5, 3, 3, r.light);
	ellipse(image, 9.5, 11, 3.5, 3, r.shadow);

	for (let y = 3; y < 16; y++) {
		for (let x = 2; x < 15; x++) {
			if (get(image, x, y)[3] > 0 && (x + y * 2) % 3 === 0) {
				put(image, x, y, r.line);
			}
		}
	}

	rect(image, 7, 1, 9, 4, rgb('#6d4c2e'));
	outline(image);

	return image;
}

function coconutSprite(): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp('#6aa84f');
	ellipse(image, 8, 9, 6, 6, r.base);
	ellipse(image, 6.5, 7.5, 3, 3, r.light);
	ellipse(image, 10, 11.5, 3, 2.5, r.shadow);
	rect(image, 6, 2, 10, 4, rgb('#4e7d33'));
	rect(image, 7, 1, 9, 2, rgb('#8d6e63'));
	outline(image);

	return image;
}

function pomeloSprite(): Bitmap {
	const image = bitmap(16, 16);
	const r = ramp('#b5d15c');
	ellipse(image, 8, 9, 6.8, 6.4, r.base);
	ellipse(image, 6, 7, 3, 2.8, r.light);
	ellipse(image, 10, 12, 3.5, 2.5, r.shadow);
	rect(image, 7, 1, 9, 3, rgb('#6d4c2e'));
	rect(image, 9, 1, 12, 2, rgb('#558b2f'));
	outline(image);

	return image;
}

function teaSprite(): Bitmap {
	const image = bitmap(16, 16);
	ellipse(image, 8, 10, 6, 4.5, PORCELAIN);
	rect(image, 2, 6, 15, 10, [0, 0, 0, 0]);
	ellipse(image, 8, 8, 6, 2, PORCELAIN_SHADE);
	ellipse(image, 8, 8, 5, 1.5, rgb('#9c7a2b'));
	rect(image, 4, 11, 12, 12, COBALT);

	for (const [x, y] of [[6, 3], [7, 2], [9, 4], [10, 3]]) {
		put(image, x!, y!, rgb('#ffffff', 140));
	}

	outline(image);

	return image;
}

// ---- exports ------------------------------------------------------------------------

/** Every painted sheet under `textures/block`, with the LabPBR `_s` sheets beside the glowing ones. */
export function trungThuSprites(): Record<string, Uint8Array> {
	const sheets: Record<string, Bitmap> = {
		tt_plate: plateSheet(),
		tt_plate_rim: plateRimSheet(),
		tt_bamboo: bambooSheet(),
		tt_gold: goldSheet(),
		tt_wood: woodSheet(),
		tt_iron: solidSheet(IRON, 37, 0.1),
		tt_string_red: solidSheet(THREAD_RED, 41, 0.08),
		tt_tassel_red: tasselSheet(THREAD_RED),
		tt_tassel_yellow: tasselSheet(rgb('#f9a825')),
		tt_carp_body: carpBodySheet(),
		tt_carp_head: carpHeadSheet(),
		tt_carp_fin: finSheet(),
		tt_carp_mouth: carpMouthSheet(),
		tt_carp_eye: carpEyeSheet(),
		tt_red_paper: redLanternSheet(),
		tt_rabbit_body: rabbitBodySheet(),
		tt_rabbit_face: rabbitFaceSheet(),
		tt_rabbit_ear: rabbitEarSheet(),
		tt_rabbit_ear_inner: rabbitEarInnerSheet(),
		tt_wheel: wheelSheet(),
		tt_butterfly_wing: wingSheet(),
		tt_lotus_petal: petalSheet(false),
		tt_lotus_petal_inner: petalSheet(true),
		tt_lotus_pod: solidSheet(rgb('#dce775'), 49, 0.08),
		tt_lotus_leaf: leafSheet(),
		tt_revolving_panel: revolvingSheet(),
		tt_ong_dia_skin: solidSheet(rgb('#f5b7a0'), 103, 0.03),
		tt_bat_gioi_skin: solidSheet(rgb('#f8bbd0'), 107, 0.03),
		tt_snout: snoutSheet(),
		tt_fur_white: furSheet(),
		tt_drum_body: drumBodySheet(),
		tt_drum_head: drumHeadSheet(),
		tt_stud: studSheet(),
		tt_pomelo_flesh: pomeloFleshSheet(),
		tt_pomelo_face: pomeloFaceSheet(),
		tt_pomelo_peel: solidSheet(rgb('#7cb342'), 109, 0.08),
		tt_clove: solidSheet(rgb('#2b1a10'), 113, 0.05),
		tt_lacquer: solidSheet(rgb('#b71c1c'), 151, 0.05),
		tt_mamco_cake_top: mooncakeTop('baked'),
		tt_mamco_cake_side: mooncakeSide('baked'),
		tt_persimmon: solidSheet(rgb('#ef6c00'), 157, 0.05),
		tt_banana: solidSheet(rgb('#fdd835'), 163, 0.05),
		tt_grape: solidSheet(rgb('#6a1b9a'), 167, 0.05),
		tt_straw: solidSheet(rgb('#d4b35a'), 173, 0.08),
		tt_clay_skin: solidSheet(rgb('#ffe0b2'), 179, 0.03),
		tt_clay_red: solidSheet(rgb('#e53935'), 181, 0.03),
		tt_clay_yellow: solidSheet(rgb('#fdd835'), 183, 0.03),
		tt_clay_green: solidSheet(rgb('#43a047'), 185, 0.03),
		tt_clay_blue: solidSheet(rgb('#1e88e5'), 187, 0.03),
		tt_clay_pink: solidSheet(rgb('#f06292'), 189, 0.03),
		tt_clay_white: solidSheet(rgb('#fafafa'), 191, 0.03),
		tt_clay_orange: solidSheet(rgb('#fb8c00'), 193, 0.03),
		tt_clay_purple: solidSheet(rgb('#8e24aa'), 197, 0.03),
		mask_ong_dia: ongDiaSheet(),
		mask_ngo_khong: ngoKhongSheet(),
		mask_bat_gioi: pigFaceSheet(),
		mask_lan: lanSheet(),
		mask_rong: dragonFaceSheet(),
		tt_pig_ear_inner: solidSheet(rgb('#f48fb1'), 149, 0.04),
		tt_dragon_scale: scaleSheet('#2e9e4f', '#1b7a3a'),
		tt_dragon_snout: dragonSnoutSheet(),
		tt_dragon_mouth: dragonMouthSheet(),
		tt_fur_red: tasselSheet(rgb('#e53935')),
		tt_lovers_paper: loversSheet(),
		tt_heart_small: smallHeartSheet(),
		tt_toy_handle: toyHandleSheet(),
		tt_toy_handle_end: toyHandleEndSheet(),
		tt_toy_rod: toySheet('#f5f5f5'),
		tt_toy_switch: toySheet('#ffd600'),
		tt_sword_hilt: swordHiltSheet(),
		tt_sword_guard: swordGuardSheet(),
		tt_sword_blade_off: bladeOffSheet(),
	};

	// each lit blade, its LabPBR sheet and the `_e` emissive layer ETF and
	// OptiFine read; all three animate in step, so all three get the mcmeta
	for (const sword of SWORDS) {
		const blade = bladeSheet(sword.colours);
		sheets[`${sword.id}_blade`] = blade;
		sheets[`${sword.id}_blade_s`] = specular(blade, () => true, 240);
		sheets[`${sword.id}_blade_e`] = blade;
	}

	PLEATS.forEach((hex, i) => {
		sheets[`tt_pleat_${i}`] = pleatSheet(hex);
	});

	for (const lantern of LANTERNS) {
		if (lantern.kind === 'star') {
			sheets[`${lantern.id}_paper`] = starSheet(lantern.colour!);
		}

		if (lantern.kind === 'hoian') {
			sheets[`${lantern.id}_silk`] = silkSheet(lantern.colour!);
		}

		if (lantern.kind === 'heart') {
			sheets[`${lantern.id}_paper`] = heartLanternSheet(lantern.colour!);
		}
	}

	for (const skin of Object.keys(SKINS) as MooncakeSkin[]) {
		sheets[`mc_${skin}_top`] = mooncakeTop(skin);
		sheets[`mc_${skin}_side`] = mooncakeSide(skin);
		sheets[`mc_${skin}_heart_top`] = heartTop(skin);
	}

	for (const cake of MOONCAKES) {
		sheets[`${cake.id}_cut`] = mooncakeCut(cake);
	}

	// the glowing sheets get their specular maps: frame strips, ink and wood
	// stay dark, everything else is lit paper
	const frame = except(BAMBOO_DARK, WOOD_DARK, INK, rgb('#3b2410'), rgb('#7f1d1d'), rgb('#c62828'));
	const glowing = [
		...LANTERNS.filter((l) => l.kind === 'star').map((l) => `${l.id}_paper`),
		...LANTERNS.filter((l) => l.kind === 'hoian').map((l) => `${l.id}_silk`),
		...LANTERNS.filter((l) => l.kind === 'heart').map((l) => `${l.id}_paper`),
		'tt_lovers_paper', 'tt_heart_small',
		...PLEATS.map((_, i) => `tt_pleat_${i}`),
		'tt_carp_body', 'tt_carp_head', 'tt_carp_fin', 'tt_red_paper', 'tt_rabbit_body', 'tt_rabbit_face',
		'tt_rabbit_ear', 'tt_rabbit_ear_inner', 'tt_butterfly_wing', 'tt_lotus_petal', 'tt_lotus_petal_inner',
		'tt_lotus_pod', 'tt_revolving_panel',
	];

	for (const name of glowing) {
		sheets[`${name}_s`] = specular(sheets[name]!, frame);
	}

	return Object.fromEntries(Object.entries(sheets).map(([name, image]) => [name, new Uint8Array(encodePng(image))]));
}

/** The flat sprites under `textures/item`. */
export function trungThuItemSprites(): Record<string, Uint8Array> {
	const sheets: Record<string, Bitmap> = {
		durian: durianSprite(),
		coconut: coconutSprite(),
		pomelo: pomeloSprite(),
		tra_nong: teaSprite(),
	};

	for (const cake of MOONCAKES) {
		sheets[cake.id] = cake.shape === 'heart' ? heartMooncakeSprite(cake) : mooncakeSprite(cake);
	}

	for (const sword of SWORDS) {
		sheets[sword.id] = swordSprite(null);
		sheets[`${sword.id}_on`] = swordSprite(sword.colours);
	}

	return Object.fromEntries(Object.entries(sheets).map(([name, image]) => [name, new Uint8Array(encodePng(image))]));
}

/** Every block model of the set, by file name. */
export function trungThuModels(): Record<string, Model> {
	const models: Record<string, Model> = {};

	for (const lantern of LANTERNS) {
		const body = bodyOf(lantern);
		const floor = composed(body.elements, T('tt_gold'));
		floor.display = bodyDisplay(body.flat);
		models[lantern.id] = floor;
		models[`${lantern.id}_wall`] = composed(wallModel(lantern), T('tt_gold'));
		models[`${lantern.id}_ceiling`] = composed(ceilingModel(lantern), T('tt_gold'));
	}

	for (const cake of MOONCAKES) {
		for (let bites = 0; bites < 4; bites++) {
			models[`mooncake_plate_${cake.id}_${bites}`] = composed(mooncakeElements(cake, bites), T('tt_plate'));
		}
	}

	// the plate block's own default: never drawn, but Nova resolves it
	models['mooncake_plate'] = composed(mooncakeElements(MOONCAKES[0]!, 0), T('tt_plate'));
	models['lion_drum'] = composed(drumElements(), T('tt_drum_body'));
	models['pomelo_dog'] = composed(pomeloDogElements(), T('tt_pomelo_flesh'));
	models['mam_co'] = composed(mamCoElements(), T('tt_lacquer'));
	models['to_he'] = composed(toHeElements(), T('tt_straw'));

	return models;
}

/** The item models that are not a block's: the carried lanterns and the masks. */
export function trungThuItemModels(): Record<string, Model> {
	const models: Record<string, Model> = {};

	for (const lantern of LANTERNS) {
		const held = composed(heldModel(lantern), T('tt_bamboo'));
		held.display = HELD_DISPLAY;
		models[`${lantern.id}_held`] = held;
	}

	for (const mask of MASKS) {
		const model = composed(maskElements(mask.id), T(mask.id));
		model.display = MASK_DISPLAY;
		models[mask.id] = model;
	}

	for (const sword of SWORDS) {
		for (const lit of [false, true]) {
			const model = composed(swordElements(sword, lit), T('tt_sword_hilt'));
			model.display = SWORD_DISPLAY;
			models[lit ? `${sword.id}_on` : sword.id] = model;
		}
	}

	return models;
}

/**
 * Animation metadata, by texture name and folder: the lit blades (with their
 * specular and emissive companions) and the lit swords' slot sprites.
 */
export function trungThuMcmeta(): { block: Record<string, unknown>; item: Record<string, unknown> } {
	const block: Record<string, unknown> = {};
	const item: Record<string, unknown> = {};

	for (const sword of SWORDS) {
		for (const suffix of ['', '_s', '_e']) {
			block[`${sword.id}_blade${suffix}`] = BLADE_MCMETA;
		}

		item[`${sword.id}_on`] = BLADE_MCMETA;
	}

	return { block, item };
}

/** One Nova recipe file, by path under `recipes/minecraft/`. */
export function trungThuRecipes(): Record<string, unknown> {
	const recipes: Record<string, unknown> = {};

	for (const cake of MOONCAKES) {
		const counts = new Map<string, number>();

		for (const item of [...doughOf(cake.skin), ...cake.ingredients]) {
			counts.set(item, (counts.get(item) ?? 0) + 1);
		}

		recipes[`shapeless/${cake.id}.json`] = {
			category: 'misc',
			result: `lunasmp:${cake.id}`,
			ingredients: [...counts.entries()].map(([item, amount]) => ({ item, amount })),
		};
	}

	for (const entry of [...LANTERNS, ...MASKS]) {
		recipes[`shaped/${entry.id}.json`] = {
			category: 'misc',
			result: `lunasmp:${entry.id}`,
			shape: entry.shape,
			ingredients: entry.keys,
		};
	}

	for (const sword of SWORDS) {
		recipes[`shaped/${sword.id}.json`] = {
			category: 'misc',
			result: `lunasmp:${sword.id}`,
			shape: ['  G', ' R ', 'O  '],
			ingredients: { G: sword.pane, R: 'minecraft:redstone', O: 'minecraft:orange_dye' },
		};
	}

	recipes['shaped/lion_drum.json'] = {
		category: 'misc',
		result: 'lunasmp:lion_drum',
		shape: ['SLS', 'RNR', 'WWW'],
		ingredients: {
			S: 'minecraft:stick',
			L: 'minecraft:leather',
			R: 'minecraft:red_dye',
			N: 'minecraft:note_block',
			W: 'minecraft:oak_planks',
		},
	};

	recipes['shapeless/pomelo_dog.json'] = {
		category: 'misc',
		result: 'lunasmp:pomelo_dog',
		ingredients: [
			{ item: 'lunasmp:pomelo', amount: 2 },
			{ item: 'minecraft:black_dye', amount: 1 },
		],
	};

	recipes['shapeless/tra_nong.json'] = {
		category: 'misc',
		result: 'lunasmp:tra_nong',
		ingredients: [
			{ item: 'lunasmp:tea_leaves', amount: 2 },
			{ item: 'minecraft:water_bucket', amount: 1 },
			{ item: 'minecraft:bowl', amount: 1 },
		],
	};

	return recipes;
}

/** Escapes a string for a Kotlin literal. */
function kt(text: string): string {
	return `"${text.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$')}"`;
}

/** The Kotlin table the hand-written trungthu package registers from, so the ids never drift from the art. */
export function trungThuKotlin(): string {
	const cakes = MOONCAKES.map((c) =>
		`\t\tMooncake(${kt(c.id)}, ${c.nutrition}, ${c.saturation.toFixed(1)}f, ${c.effect ? kt(c.effect) : 'null'}, ${kt(c.lore)}),`,
	).join('\n');

	const lanterns = LANTERNS.map((l) =>
		`\t\tLantern(${kt(l.id)}, ${kt(l.kind)}, ${l.song}, ${kt(l.lore)}),`,
	).join('\n');

	const masks = MASKS.map((m) => `\t\tMask(${kt(m.id)}, ${kt(m.lore)}),`).join('\n');
	const fruits = FRUITS.map((f) => `\t\tFruit(${kt(f.id)}, ${kt(f.lore)}),`).join('\n');
	const swords = SWORDS.map((w) => `\t\tSword(${kt(w.id)}, ${kt(w.lore)}),`).join('\n');

	return `// Generated by tools/furniture-gen/generate.ts from trungthu.ts; do not edit by hand.
package dev.belikhun.luna.smp.trungthu

/**
 * The Mid-Autumn set's ids and the facts the behaviour needs about each one.
 * The models, sheets, recipes and names are generated from the same table.
 */
object TrungThuTable {

	data class Mooncake(val id: String, val nutrition: Int, val saturation: Float, val effect: String?, val lore: String)

	data class Lantern(val id: String, val kind: String, val song: Int, val lore: String)

	data class Mask(val id: String, val lore: String)

	data class Fruit(val id: String, val lore: String)

	data class Sword(val id: String, val lore: String)

	/** How many tunes the lanterns know; must match ToyTunes.SONGS. */
	const val SONG_COUNT = ${SONG_COUNT}

	val MOONCAKES: List<Mooncake> = listOf(
${cakes}
	)

	val LANTERNS: List<Lantern> = listOf(
${lanterns}
	)

	val MASKS: List<Mask> = listOf(
${masks}
	)

	val FRUITS: List<Fruit> = listOf(
${fruits}
	)

	val SWORDS: List<Sword> = listOf(
${swords}
	)
}
`;
}
