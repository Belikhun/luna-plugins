// The party treats: soda cans, candies, ice creams and snacks, every one a
// flat sixteen-pixel sprite drawn as a character grid over a palette - the
// authoring format pixels.ts exists for, because a lollipop's swirl is a
// shape somebody has to get right by eye.
//
// Everything here is ours. The cans wear the colours people know the drinks
// by, and nothing else of anybody's mark.

import { type Art, type Bitmap, type Palette, type Rgba, bitmap, encodePng, put, rect, rgb, stampCentered } from './pixels';
import type { BirthdayEntry } from './birthday';

/** What a treat is, which decides how it is eaten and what it does. */
export type TreatKind = 'soda' | 'drink' | 'candy' | 'ice_cream' | 'snack';

export interface Treat extends BirthdayEntry {
	kind: TreatKind;
}

export const TREATS: Treat[] = [
	{ id: 'soda_cola', en: 'Coca-Cola Can', vi: 'Lon Coca-Cola', kind: 'soda' },
	{ id: 'soda_pepsi', en: 'Pepsi Can', vi: 'Lon Pepsi', kind: 'soda' },
	{ id: 'soda_redbull', en: 'Red Bull Can', vi: 'Lon Red Bull', kind: 'soda' },
	{ id: 'soda_sprite', en: 'Sprite Can', vi: 'Lon Sprite', kind: 'soda' },
	{ id: 'soda_fanta', en: 'Fanta Can', vi: 'Lon Fanta', kind: 'soda' },
	{ id: 'lollipop', en: 'Lollipop', vi: 'Kẹo Mút', kind: 'candy' },
	{ id: 'wrapped_candy', en: 'Strawberry Candy', vi: 'Kẹo Dâu', kind: 'candy' },
	{ id: 'gummy_bear', en: 'Gummy Bear', vi: 'Kẹo Dẻo Gấu', kind: 'candy' },
	{ id: 'cotton_candy', en: 'Cotton Candy', vi: 'Kẹo Bông Gòn', kind: 'candy' },
	{ id: 'ice_cream_cone', en: 'Vanilla Ice Cream', vi: 'Kem Vani', kind: 'ice_cream' },
	{ id: 'strawberry_ice_cream', en: 'Strawberry Ice Cream', vi: 'Kem Dâu', kind: 'ice_cream' },
	{ id: 'chocolate_ice_cream', en: 'Chocolate Ice Cream', vi: 'Kem Sô-cô-la', kind: 'ice_cream' },
	{ id: 'popsicle', en: 'Popsicle', vi: 'Kem Que', kind: 'ice_cream' },
	{ id: 'potato_chips', en: 'Potato Chips', vi: 'Snack Khoai Tây', kind: 'snack' },
	{ id: 'popcorn', en: 'Popcorn', vi: 'Bắp Rang Bơ', kind: 'snack' },
	{ id: 'french_fries', en: 'French Fries', vi: 'Khoai Tây Chiên', kind: 'snack' },
	{ id: 'pizza_slice', en: 'Pizza Slice', vi: 'Miếng Pizza', kind: 'snack' },
	{ id: 'donut', en: 'Donut', vi: 'Bánh Donut', kind: 'snack' },
	{ id: 'cupcake', en: 'Cupcake', vi: 'Bánh Cupcake', kind: 'snack' },
	{ id: 'bubble_tea', en: 'Bubble Tea', vi: 'Trà Sữa Trân Châu', kind: 'drink' },
	{ id: 'mango_smoothie', en: 'Mango Smoothie', vi: 'Sinh Tố Xoài', kind: 'drink' },
	{ id: 'chocolate_milk', en: 'Chocolate Milk', vi: 'Sữa Sô-cô-la', kind: 'drink' },
	{ id: 'macaron', en: 'Macaron', vi: 'Bánh Macaron', kind: 'candy' },
	{ id: 'candy_cane', en: 'Candy Cane', vi: 'Kẹo Gậy', kind: 'candy' },
	{ id: 'marshmallow', en: 'Marshmallow', vi: 'Kẹo Marshmallow', kind: 'candy' },
	{ id: 'mochi', en: 'Mochi', vi: 'Bánh Mochi', kind: 'candy' },
	{ id: 'strawberry', en: 'Strawberry', vi: 'Dâu Tây', kind: 'snack' },
	{ id: 'flan', en: 'Caramel Flan', vi: 'Bánh Flan', kind: 'snack' },
	{ id: 'waffle', en: 'Waffle', vi: 'Bánh Waffle', kind: 'snack' },
];

/** The keepsakes: not eaten, only given. */
export const GIFTS: BirthdayEntry[] = [
	{ id: 'teddy_bear', en: 'Teddy Bear', vi: 'Gấu Bông' },
	{ id: 'bouquet', en: 'Bouquet', vi: 'Bó Hoa' },
	{ id: 'love_letter', en: 'Love Letter', vi: 'Thư Tay' },
	{ id: 'perfume', en: 'Perfume', vi: 'Nước Hoa' },
	{ id: 'heart_balloon', en: 'Heart Balloon', vi: 'Bóng Bay Trái Tim' },
];

const OUTLINE = rgb('#2b2320');
const WHITE = rgb('#f6f6f6');
const SILVER = rgb('#c8ccd4');
const STICK = rgb('#f1e4c8');
const STICK_DARK = rgb('#d3c3a0');

/** A grid stamped onto a fresh sixteen-pixel sheet. */
function sprite(art: Art, palette: Palette): Bitmap {
	const image = bitmap(16, 16);
	stampCentered(image, art, palette);

	return image;
}

// ---- soda cans ------------------------------------------------------------------------

/** The can every soda shares: a lid, a body with a highlight, a band with the mark on it. */
const CAN: Art = [
	'......kkkk......',
	'.....kssssk.....',
	'....kssssssk....',
	'....kaahaaak....',
	'....kaahaaak....',
	'....kaahaaak....',
	'....kbbbbbbk....',
	'....kbwbbwbk....',
	'....kbbbbbbk....',
	'....kaahaaak....',
	'....kaahaaak....',
	'....kaahaaak....',
	'....kaahaaak....',
	'....kssssssk....',
	'.....kssssk.....',
	'......kkkk......',
];

/** The Red Bull sold here: a short, wide gold can with a red band, nothing like the tall blue one. */
const SHORT_CAN: Art = [
	'................',
	'................',
	'.....kkkkkk.....',
	'....kssssssk....',
	'...kgghgggggk...',
	'...kgghgggggk...',
	'...kgrrrrrrgk...',
	'...kgrwrrwrgk...',
	'...kgrrrrrrgk...',
	'...kgghgggggk...',
	'...kgghgggggk...',
	'...kgghgggggk...',
	'...kssssssssk...',
	'....kkkkkkkk....',
	'................',
	'................',
];

function shortCan(): Bitmap {
	return sprite(SHORT_CAN, {
		k: OUTLINE,
		s: SILVER,
		g: rgb('#e8b923'),
		h: rgb('#f5d76e'),
		r: rgb('#d8232a'),
		w: rgb('#ffe680'),
	});
}

function can(body: string, highlight: string, band: string, mark: string): Bitmap {
	return sprite(CAN, {
		k: OUTLINE,
		s: SILVER,
		a: rgb(body),
		h: rgb(highlight),
		b: rgb(band),
		w: rgb(mark),
	});
}

// ---- candies -----------------------------------------------------------------------------

const LOLLIPOP: Art = [
	'.....kkkkkk.....',
	'....kppwwppk....',
	'...kpwwppwwpk...',
	'..kppwppppwppk..',
	'..kpwpppwwpwpk..',
	'..kpwppwppwwpk..',
	'..kppwpwppwppk..',
	'..kppwwppwpppk..',
	'...kppppwwppk...',
	'....kppwwppk....',
	'.....kkkkkk.....',
	'.......tt.......',
	'.......tt.......',
	'.......tt.......',
	'.......tt.......',
	'.......td.......',
];

const GUMMY_BEAR: Art = [
	'................',
	'...kk......kk...',
	'..kggk....kggk..',
	'..kgggkkkkgggk..',
	'...kggggggggk...',
	'...kgdggggdgk...',
	'...kggggggggk...',
	'...kgggkkgggk...',
	'....kggggggk....',
	'..kkkggggggkkk..',
	'.kggggggggggggk.',
	'.kggkggggggkggk.',
	'..kk.kggggk.kk..',
	'.....kggggk.....',
	'....kgggkgggk...',
	'....kkkk.kkkk...',
];

const COTTON_CANDY: Art = [
	'.....kkkkk......',
	'...kkpppppkk....',
	'..kppplpppppk...',
	'.kpplppppplppk..',
	'.kppppplppppppk.',
	'kpplppppppplpppk',
	'kppppplpppppppk.',
	'.kpppppppplppk..',
	'..kkpplpppppk...',
	'....kkppppkk....',
	'......kttk......',
	'.......tt.......',
	'.......tt.......',
	'.......tt.......',
	'.......tt.......',
	'.......td.......',
];

/** A wrapped sweet: a striped body with a twist of wrapper either end. */
function wrappedCandy(): Bitmap {
	const image = bitmap(16, 16);
	const body = rgb('#ff5f8f');
	const stripe = rgb('#ffe3ec');
	const wrapper = rgb('#ffd1df');
	const dark = rgb('#c73a66');

	rect(image, 4, 5, 12, 11, body);

	for (const x of [6, 7, 10, 11]) {
		rect(image, x, 5, x + 1, 11, stripe);
	}

	// outline the body and shade its foot
	for (let x = 4; x < 12; x++) {
		put(image, x, 4, dark);
		put(image, x, 11, dark);
	}

	for (let y = 5; y < 11; y++) {
		put(image, 3, y, dark);
		put(image, 12, y, dark);
	}

	// the twists: a pinch at the body, flaring out to a fluted end
	for (const [x0, step] of [[2, -1], [13, 1]] as Array<[number, number]>) {
		rect(image, x0, 7, x0 + 1, 9, wrapper);
		rect(image, x0 + step, 6, x0 + step + 1, 10, wrapper);
		put(image, x0 + step * 2, 5, wrapper);
		put(image, x0 + step * 2, 10, wrapper);
		put(image, x0 + step * 2, 7, dark);
		put(image, x0 + step * 2, 8, dark);
	}

	return image;
}

// ---- ice creams ------------------------------------------------------------------------

const CONE: Art = [
	'......kkkk......',
	'....kkvvvvkk....',
	'...kvvvvhvvvk...',
	'..kvvvvvvvvvvk..',
	'..kvvhvvvvvvvk..',
	'..kvvvvvvvvvvk..',
	'...kvvvvvvvvk...',
	'..kkccwcccwcckk.',
	'...kcwcccwcck...',
	'...kccwcccwck...',
	'....kcccwccck...',
	'....kcwcccwk....',
	'.....kccwck.....',
	'.....kcwcck.....',
	'......kcck......',
	'.......kk.......',
];

function cone(scoop: string, highlight: string): Bitmap {
	return sprite(CONE, {
		k: OUTLINE,
		v: rgb(scoop),
		h: rgb(highlight),
		c: rgb('#d29a54'),
		w: rgb('#a86f33'),
	});
}

const POPSICLE: Art = [
	'....kkkkkkkk....',
	'...kbbbbbbbbk...',
	'...kbhbbbbbbk...',
	'...kbhbbbbbbk...',
	'...kbbbbbbbbk...',
	'...kbbbbbbbbk...',
	'...kbbbbbbbbk...',
	'...kbbbbbbbbk...',
	'...kbbbbbbbbk...',
	'...kbbbbbbbbk...',
	'....kkkkkkkk....',
	'......kttk......',
	'......kttk......',
	'......kttk......',
	'......kttk......',
	'.......kk.......',
];

// ---- snacks ------------------------------------------------------------------------------

const CHIPS: Art = [
	'..kkkkkkkkkkkk..',
	'..kyyyyyyyyyyk..',
	'..kyyyyyyyyyyk..',
	'..kyyrrrrrryyk..',
	'..kyrrwwwwrryk..',
	'..kyrwccccwryk..',
	'..kyrwccccwryk..',
	'..kyrrwwwwrryk..',
	'..kyyrrrrrryyk..',
	'..kyyyyyyyyyyk..',
	'..kyyyyyyyyyyk..',
	'..kyyyyyyyyyyk..',
	'..kyyyyyyyyyyk..',
	'..kkkkkkkkkkkk..',
];

const POPCORN: Art = [
	'...pp.qpp.pp....',
	'..pqpppppqppp...',
	'.ppppqppppppqp..',
	'..pppppqpppppp..',
	'.kkkkkkkkkkkkkk.',
	'.krwrwrwrwrwrwk.',
	'.krwrwrwrwrwrwk.',
	'.krwrwrwrwrwrwk.',
	'..krwrwrwrwrwk..',
	'..krwrwrwrwrwk..',
	'..krwrwrwrwrwk..',
	'...krwrwrwrwk...',
	'...krwrwrwrwk...',
	'...krwrwrwrwk...',
	'....kkkkkkkk....',
];

const FRIES: Art = [
	'....f.f..f.f....',
	'...ff.ffgf.ff...',
	'...ffffgfffgf...',
	'..fgffffffffgf..',
	'..ffffgfffffff..',
	'.kkkkkkkkkkkkkk.',
	'.krrrrrrrrrrrrk.',
	'.krrrryyyyrrrrk.',
	'..krrryyyyrrrk..',
	'..krrrrrrrrrrk..',
	'..krrrrrrrrrrk..',
	'...krrrrrrrrk...',
	'...krrrrrrrrk...',
	'...krrrrrrrrk...',
	'....kkkkkkkk....',
];

const PIZZA: Art = [
	'kkkkkkkkkkkkkkkk',
	'kcccccccccccccck',
	'kcyyyyyyyyyyyyck',
	'.kyyrryyyyrryyk.',
	'.kyyrryyyyrryyk.',
	'.kyyyyyyyyyyyyk.',
	'..kyyyggyyyyyk..',
	'..kyyyggyrryyk..',
	'..kyyyyyyrryk...',
	'...kyyyyyyyyk...',
	'...kyrryyyyk....',
	'....kyrryyk.....',
	'....kyyyyk......',
	'.....kyyk.......',
	'.....kyk........',
	'......k.........',
];

const DONUT: Art = [
	'.....kkkkkk.....',
	'...kkppppppkk...',
	'..kppsppppsppk..',
	'.kpppppkkppppsk.',
	'.kppspkddkppppk.',
	'kppppkd..dkpppk.',
	'kpsppkd..dksppk.',
	'kppppkd..dkpppk.',
	'.kppppkddkpsppk.',
	'.kpsppppkkppppk.',
	'..kppppspppppk..',
	'...kkppppspkk...',
	'.....kkkkkk.....',
];

const CUPCAKE: Art = [
	'......krrk......',
	'.....kkppkk.....',
	'....kppwpppk....',
	'...kpppppwppk...',
	'..kppwpppppppk..',
	'..kpppppppwppk..',
	'.kkkkkkkkkkkkkk.',
	'.kbwbwbwbwbwbwk.',
	'.kbwbwbwbwbwbwk.',
	'..kbwbwbwbwbwk..',
	'..kbwbwbwbwbwk..',
	'...kbwbwbwbwk...',
	'...kbwbwbwbwk...',
	'....kkkkkkkk....',
];

// ---- more treats -------------------------------------------------------------------------

const BUBBLE_TEA: Art = [
	'.........kk.....',
	'........ktk.....',
	'...kkkkkktkkk...',
	'..kmmmmmtmmmmk..',
	'..kmmmmmtmmmmk..',
	'..kmmmmmtmmmmk..',
	'..kmmmmmtmmmmk..',
	'..kmmmhmtmmmmk..',
	'...kmmmmtmmmk...',
	'...kmmmmtmmmk...',
	'...kmpmpmpmpk...',
	'...kpmpmpmpmk...',
	'...kmpmpmpmpk...',
	'....kkkkkkkk....',
	'................',
	'................',
];

const SMOOTHIE: Art = [
	'.......kk.......',
	'.......kk.......',
	'..kkkkkkkkkkk...',
	'..kwwwwwwwwwk...',
	'..kmmmmmmmmmk...',
	'..kmmhmmmmmmk...',
	'..kmmhmmmmmmk...',
	'..kmmmmmmmmmk...',
	'...kmmmmmmmk....',
	'...kmmmmmmmk....',
	'...kmmmmmmmk....',
	'....kmmmmmk.....',
	'....kmmmmmk.....',
	'.....kkkkk......',
	'................',
	'................',
];

const CARTON: Art = [
	'................',
	'.....kkkkkk.....',
	'....kccccccck...',
	'...kcccccccck...',
	'...kaaaaaaaak...',
	'...kaaaaaaaak...',
	'...kaawwwwaak...',
	'...kaawmmwaak...',
	'...kaawmmwaak...',
	'...kaawwwwaak...',
	'...kaaaaaaaak...',
	'...kaaaaaaaak...',
	'...kaaaaaaaak...',
	'...kkkkkkkkkk...',
	'................',
	'................',
];

const MACARON: Art = [
	'................',
	'................',
	'................',
	'.....kkkkkk.....',
	'...kkppppppkk...',
	'..kpppphpppppk..',
	'..kppppppppppk..',
	'...kcccccccck...',
	'..kppppppppppk..',
	'..kppppppppppk..',
	'...kkppppppkk...',
	'.....kkkkkk.....',
	'................',
	'................',
	'................',
	'................',
];

const CANDY_CANE: Art = [
	'.....kkkk.......',
	'....kwwrrk......',
	'...kwkkrrwk.....',
	'...krk.kwrk.....',
	'...krk..kwk.....',
	'...kwk..krk.....',
	'...kwk..kk......',
	'...krk..........',
	'...krk..........',
	'...kwk..........',
	'...kwk..........',
	'...krk..........',
	'...krk..........',
	'...kwk..........',
	'...kwk..........',
	'....k...........',
];

const MARSHMALLOW: Art = [
	'................',
	'................',
	'....kkkkkkkk....',
	'...kwwwwwwwwk...',
	'..kwwwwwwwwwwk..',
	'..kwwhwwwwwwwk..',
	'..kwwhwwwwwwwk..',
	'..kwwwwwwwwwwk..',
	'..kwwwwwwwwwwk..',
	'..kwwwwwwwwwwk..',
	'..kppppppppppk..',
	'..kppppppppppk..',
	'...kppppppppk...',
	'....kkkkkkkk....',
	'................',
	'................',
];

const MOCHI: Art = [
	'................',
	'................',
	'................',
	'.......kk.......',
	'.....kkggkk.....',
	'....kppppppk....',
	'...kpphpppppk...',
	'..kppppppppppk..',
	'..kppppppppppk..',
	'..kppppppppppk..',
	'..kppppppppppk..',
	'...kppppppppk...',
	'....kkkkkkkk....',
	'................',
	'................',
	'................',
];

const STRAWBERRY: Art = [
	'.......gg.......',
	'.....ggggkg.....',
	'....kgkggkgk....',
	'...krrrkkrrrk...',
	'..krrsrrrrsrrk..',
	'..krrrrrsrrrrk..',
	'..krsrrrrrrsrk..',
	'..krrrrsrrrrrk..',
	'..krrsrrrrsrrk..',
	'...krrrrsrrrk...',
	'...krsrrrrrrk...',
	'....krrrsrrk....',
	'.....krrrrk.....',
	'......krrk......',
	'.......kk.......',
	'................',
];

const FLAN: Art = [
	'................',
	'................',
	'................',
	'....kkkkkkkk....',
	'...kccccccccck..',
	'..kcccccccccck..',
	'..kyyyyyyyyyyk..',
	'..kyyhyyyyyyyk..',
	'..kyyyyyyyyyyk..',
	'..kyyyyyyyyyyk..',
	'..kyyyyyyyyyyk..',
	'.kkkkkkkkkkkkkk.',
	'.kwwwwwwwwwwwwk.',
	'..kkkkkkkkkkkk..',
	'................',
	'................',
];

const WAFFLE: Art = [
	'................',
	'................',
	'.kkkkkkkkkkkkkk.',
	'.kwdwwdwwdwwdwk.',
	'.kdddddddddddkk.',
	'.kwdwwdwwdwwdwk.',
	'.kwdwwdwwdwwdwk.',
	'.kdddddddddddkk.',
	'.kwdwwdwwdwwdwk.',
	'.kwdwwdwwdwwdwk.',
	'.kdddddddddddkk.',
	'.kwdwwdwwdwwdwk.',
	'.kkkkkkkkkkkkkk.',
	'................',
	'................',
	'................',
];

// ---- gifts ---------------------------------------------------------------------------------

const TEDDY: Art = [
	'..kk........kk..',
	'.kbbk......kbbk.',
	'.kbbkkkkkkkkbbk.',
	'..kbbbbbbbbbbk..',
	'..kbbdbbbbdbbk..',
	'..kbbbbbbbbbbk..',
	'..kbbbbkkbbbbk..',
	'...kbbbbbbbbk...',
	'..kkkbbbbbbkkk..',
	'.kbbkbbccbbkbbk.',
	'.kbbkbccccbkbbk.',
	'..kkkbbccbbkkk..',
	'....kbbbbbbk....',
	'...kbbbkkbbbk...',
	'...kbbbk.kbbbk..',
	'....kkk...kkk...',
];

const BOUQUET: Art = [
	'....rr..yy..pp..',
	'...rrrryyyyppp..',
	'...rrrryyyyppp..',
	'.pp.rr.gyy.rr.yy',
	'pppp.gg.gg.g.yyy',
	'pppp.g.gg.gg.yyy',
	'.pp..ggggggg.yy.',
	'......ggggg.....',
	'.....kwwwwwk....',
	'.....kwwwwwk....',
	'......kwwwk.....',
	'......kwwwk.....',
	'......kwwwk.....',
	'.......kwk......',
	'.......kwk......',
	'........k.......',
];

const LETTER: Art = [
	'................',
	'................',
	'..kkkkkkkkkkkk..',
	'..kwwwwwwwwwwk..',
	'..kwkwwwwwwkwk..',
	'..kwwkwwwwkwwk..',
	'..kwwwkwwkwwwk..',
	'..kwwwwkkwwwwk..',
	'..kwwwwrrwwwwk..',
	'..kwwwrrrrwwwk..',
	'..kwwwwrrwwwwk..',
	'..kwwwwwwwwwwk..',
	'..kwwwwwwwwwwk..',
	'..kkkkkkkkkkkk..',
	'................',
	'................',
];

const PERFUME: Art = [
	'......kkkk......',
	'.....kggggk.....',
	'.....kggggk.....',
	'......kkkk......',
	'......kssk......',
	'....kkkssskkk...',
	'...kppppppppk...',
	'..kpphpppppppk..',
	'..kpphpppppppk..',
	'..kpppppppppppk.',
	'..kpppppppppppk.',
	'..kpppwwwppppk..',
	'..kpppwrwppppk..',
	'...kppwwwpppk...',
	'....kkkkkkkk....',
	'................',
];

const BALLOON: Art = [
	'...kkk....kkk...',
	'..krrrk..krrrk..',
	'.krhrrrkkrrrrrk.',
	'.krhrrrrrrrrrrk.',
	'.krrrrrrrrrrrrk.',
	'.krrrrrrrrrrrrk.',
	'..krrrrrrrrrrk..',
	'...krrrrrrrrk...',
	'....krrrrrrk....',
	'.....krrrrk.....',
	'......krrk......',
	'.......kk.......',
	'.......ww.......',
	'........w.......',
	'.......w........',
	'........w.......',
];

/** Every treat's slot sprite, by item id, for `textures/item`. */
export function treatSprites(): Record<string, Uint8Array> {
	const pink: Rgba = rgb('#ff4f8b');
	const sweets: Palette = { k: OUTLINE, p: pink, w: WHITE, t: STICK, d: STICK_DARK };

	const sheets: Record<string, Bitmap> = {
		soda_cola: can('#d9232e', '#ee5560', '#f4f4f4', '#d9232e'),
		soda_pepsi: can('#1d4fa8', '#3f74cf', '#e01e37', '#f4f4f4'),
		soda_redbull: shortCan(),
		soda_sprite: can('#1aa64b', '#4cc576', '#f4f4f4', '#ffe14a'),
		soda_fanta: can('#ff7a00', '#ffa040', '#f4f4f4', '#1d4fa8'),
		lollipop: sprite(LOLLIPOP, sweets),
		wrapped_candy: wrappedCandy(),
		gummy_bear: sprite(GUMMY_BEAR, { k: rgb('#7a3a10'), g: rgb('#ff9a2e'), d: rgb('#c8641a') }),
		cotton_candy: sprite(COTTON_CANDY, { k: rgb('#e07aa6'), p: rgb('#ffb3d1'), l: rgb('#ffd6e6'), t: WHITE, d: rgb('#d9d9d9') }),
		ice_cream_cone: cone('#fff3d6', '#ffffff'),
		strawberry_ice_cream: cone('#ffa3c2', '#ffd1e0'),
		chocolate_ice_cream: cone('#7a4a2e', '#9a6446'),
		popsicle: sprite(POPSICLE, { k: OUTLINE, b: rgb('#5fc6ff'), h: rgb('#b8e6ff'), t: STICK }),
		potato_chips: sprite(CHIPS, { k: OUTLINE, y: rgb('#ffd23c'), r: rgb('#e02f2f'), w: WHITE, c: rgb('#f0c070') }),
		popcorn: sprite(POPCORN, { k: OUTLINE, p: rgb('#fff4c8'), q: rgb('#ffe08a'), r: rgb('#e02f2f'), w: WHITE }),
		french_fries: sprite(FRIES, { k: OUTLINE, f: rgb('#ffd94a'), g: rgb('#e0b030'), r: rgb('#e02f2f'), y: rgb('#ffd94a') }),
		pizza_slice: sprite(PIZZA, { k: OUTLINE, c: rgb('#d29a54'), y: rgb('#ffd76b'), r: rgb('#c62a3c'), g: rgb('#4caf50') }),
		donut: sprite(DONUT, { k: OUTLINE, p: rgb('#ff8fb8'), s: WHITE, d: rgb('#d9a15e') }),
		cupcake: sprite(CUPCAKE, { k: OUTLINE, p: rgb('#ff9ecf'), w: WHITE, b: rgb('#5ec8f2'), r: rgb('#c62a3c') }),
		bubble_tea: sprite(BUBBLE_TEA, { k: OUTLINE, m: rgb('#d9b48f'), h: rgb('#ecd2b5'), t: rgb('#ff8fb8'), p: rgb('#3a2418') }),
		mango_smoothie: sprite(SMOOTHIE, { k: OUTLINE, m: rgb('#ffb300'), h: rgb('#ffd54f'), w: rgb('#fff3d6') }),
		chocolate_milk: sprite(CARTON, { k: OUTLINE, c: rgb('#8a5a3a'), a: rgb('#c8956a'), w: WHITE, m: rgb('#5b3a26') }),
		macaron: sprite(MACARON, { k: rgb('#a8506e'), p: rgb('#ff9ecf'), h: rgb('#ffd1e6'), c: rgb('#fff1e3') }),
		candy_cane: sprite(CANDY_CANE, { k: OUTLINE, w: WHITE, r: rgb('#e02f2f') }),
		marshmallow: sprite(MARSHMALLOW, { k: rgb('#c9a2b3'), w: rgb('#fff6f8'), h: WHITE, p: rgb('#ffc6dc') }),
		mochi: sprite(MOCHI, { k: rgb('#7aa36e'), p: rgb('#c5e8b0'), h: rgb('#e6f6dc'), g: rgb('#4caf50') }),
		strawberry: sprite(STRAWBERRY, { k: rgb('#8a1c2c'), r: rgb('#e8304f'), s: rgb('#ffd9a0'), g: rgb('#4caf50') }),
		flan: sprite(FLAN, { k: rgb('#6b3d14'), c: rgb('#a0551c'), y: rgb('#ffd75e'), h: rgb('#fff0a0'), w: rgb('#f7f3ec') }),
		waffle: sprite(WAFFLE, { k: rgb('#7a4a1c'), w: rgb('#e8a84a'), d: rgb('#b8762a') }),
		teddy_bear: sprite(TEDDY, { k: rgb('#5a3a1c'), b: rgb('#b8865a'), d: rgb('#2b2320'), c: rgb('#e8c9a0') }),
		bouquet: sprite(BOUQUET, { k: OUTLINE, r: rgb('#e8304f'), y: rgb('#ffd94a'), p: rgb('#ff8fb8'), g: rgb('#4caf50'), w: rgb('#f1e4c8') }),
		love_letter: sprite(LETTER, { k: rgb('#c9b8a0'), w: rgb('#fff8ec'), r: rgb('#e8304f') }),
		perfume: sprite(PERFUME, { k: OUTLINE, g: rgb('#d9b45a'), s: SILVER, p: rgb('#ffb3d1'), h: rgb('#ffd6e6'), w: WHITE, r: rgb('#e8304f') }),
		heart_balloon: sprite(BALLOON, { k: rgb('#8a1c2c'), r: rgb('#ff4f6d'), h: rgb('#ff9aa8'), w: rgb('#f1e4c8') }),
	};

	return Object.fromEntries(Object.entries(sheets).map(([name, image]) => [name, new Uint8Array(encodePng(image))]));
}
