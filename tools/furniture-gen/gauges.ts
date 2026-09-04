// The instrument catalog: the gauges that read a Nova network, and the switch
// that cuts one.
//
// A gauge is a painted face plus a live needle. The face is a texture from
// gaugeart.ts; the needle is an item display the server rotates, so a reading
// is continuous rather than sixteen baked frames. Every number that shapes a
// dial - pivot, radius, sweep - is exported to the Kotlin table below, which
// is what keeps the painted arc and the live needle agreeing about where 100%
// is.
//
// Three forms of every gauge, matching how the user builds:
// - `panel`: stuck flat on a machine, a tank or a storage block, the way a
//   real instrument is screwed to the device it reads.
// - `unit`: the free-standing field instrument, a 3D housing with a conduit
//   stub out of the back, for reading a cable run.
// - `block`: a full block whose face fills the front, so a wall of them is a
//   dashboard with no gaps; wires reach it through any other side.
//
// The layout language is the instrument industry's own, checked against
// wile's MIT-licensed "Redstone Gauges and Switches" for reference; none of
// its art is copied - every face here is painted in gaugeart.ts.

import { type Element, type Model, type Vec3, DISPLAY, composed, part } from './compose';
import {
	type Dial,
	FACE,
	dialFace,
	gaugeCommonSprites,
} from './gaugeart';
import { rgb } from './pixels';

const ZONE_GREEN = rgb('#3d8b40');
const ZONE_AMBER = rgb('#d9a521');
const ZONE_RED = rgb('#c22f2a');
const ZONE_BLUE = rgb('#2f6fc2');

/** What a gauge measures; mirrored as an enum in the Kotlin table. */
export type Metric =
	| 'energy_stored'
	| 'energy_flow'
	| 'energy_load'
	| 'energy_gen'
	| 'energy_total'
	| 'item_flow'
	| 'item_stored'
	| 'fluid_stored'
	| 'fluid_flow'
	| 'multi'
	| 'multi_flow';

export interface Gauge {
	/** Block id inside the lunasmp namespace. */
	id: string;
	en: string;
	vi: string;
	metric: Metric;
	dial: Dial;
	/** Which needle sprite swings on it; the totaliser has none. */
	needle?: 'red' | 'black' | 'orange' | 'corner_red' | 'corner_black' | 'corner_orange';
	/** Rate gauges auto-range; this window is where the range plate sits. */
	window?: [number, number];
	/** Whether the kind also ships a full-block dashboard form. */
	dashboard: boolean;
	/**
	 * How the live reading is shown: a swinging needle (the default), a
	 * climbing LED bar, or an LCD digit row. The bar and the digits are
	 * display entities exactly as the needle is - the face stays static art.
	 */
	style?: 'bar' | 'digital';
}

/** The zones a centre-zero flow dial wears: drain on the left, fill right. */
function flowZones(right: ReturnType<typeof rgb>): Dial['zones'] {
	return [
		{ from: 0, to: 0.42, colour: ZONE_RED },
		{ from: 0.58, to: 1, colour: right },
	];
}

/**
 * The corner-pivot meters: the movement of a classic panel ammeter, its
 * pivot in one corner of the face and the whole opposite half a scale arc.
 * Three corners, and the fleet's own readings on all of them - a level and a
 * flow meter for each of energy, items and fluid, plus the consumption and
 * production pair that started the family. Every layout number here was
 * checked against the arc: the emblem, the legend and the labels all live in
 * the face's free corner or on the strip past the pivot, where the scale
 * never reaches.
 */
interface CornerSeat {
	pivot: [number, number];
	startDeg: number;
	endDeg: number;
	/** Top-left of the big quantity letter; x is the right edge on 'bl'. */
	emblem: [number, number];
	legend: [number, number];
	unit: [number, number];
	plate: [number, number];
	en: string;
	vi: string;
}

const CORNER_SEATS: Record<string, CornerSeat> = {
	br: {
		pivot: [52, 52], startDeg: -90, endDeg: 0,
		emblem: [6, 6], legend: [14, 21], unit: [12, 58], plate: [32, 58],
		en: 'Right', vi: 'Góc Phải',
	},
	bl: {
		pivot: [12, 52], startDeg: 90, endDeg: 0,
		emblem: [58, 6], legend: [50, 21], unit: [52, 58], plate: [32, 58],
		en: 'Left', vi: 'Góc Trái',
	},
	tr: {
		pivot: [52, 12], startDeg: -90, endDeg: -180,
		emblem: [6, 40], legend: [14, 55], unit: [12, 9], plate: [32, 9],
		en: 'Top', vi: 'Góc Trên',
	},
};

interface CornerKind {
	key: string;
	metric: Metric;
	en: string;
	vi: string;
	emblem: string;
	legend: string;
	unit: string;
	needle: 'corner_red' | 'corner_black' | 'corner_orange';
	zones: Dial['zones'];
	flow: boolean;
}

const CORNER_KINDS: CornerKind[] = [
	{
		key: 'energy_stored', metric: 'energy_stored',
		en: 'Corner Stored Energy Gauge', vi: 'Đồng Hồ Điện Tích Trữ',
		emblem: 'J', legend: 'STORE', unit: '%', needle: 'corner_red', flow: false,
		zones: [
			{ from: 0, to: 0.15, colour: ZONE_RED },
			{ from: 0.15, to: 0.3, colour: ZONE_AMBER },
			{ from: 0.8, to: 1, colour: ZONE_GREEN },
		],
	},
	{
		key: 'item_stored', metric: 'item_stored',
		en: 'Corner Stored Items Gauge', vi: 'Đồng Hồ Vật Phẩm Lưu Trữ',
		emblem: 'N', legend: 'STORE', unit: '%', needle: 'corner_red', flow: false,
		// a filling warehouse is the warning here: amber, then red at the top
		zones: [
			{ from: 0.8, to: 0.9, colour: ZONE_AMBER },
			{ from: 0.9, to: 1, colour: ZONE_RED },
		],
	},
	{
		key: 'fluid_stored', metric: 'fluid_stored',
		en: 'Corner Stored Liquid Gauge', vi: 'Đồng Hồ Mức Chất Lỏng',
		emblem: 'MB', legend: 'LEVEL', unit: '%', needle: 'corner_red', flow: false,
		zones: [
			{ from: 0, to: 0.1, colour: ZONE_RED },
			{ from: 0.75, to: 1, colour: ZONE_BLUE },
		],
	},
	{
		key: 'energy_flow', metric: 'energy_flow',
		en: 'Corner Energy Flow Gauge', vi: 'Đồng Hồ Dòng Điện',
		emblem: 'J', legend: 'FLOW', unit: 'J/S', needle: 'corner_black', flow: true,
		zones: flowZones(ZONE_GREEN),
	},
	{
		key: 'item_flow', metric: 'item_flow',
		en: 'Corner Item Flow Gauge', vi: 'Đồng Hồ Dòng Vật Phẩm',
		emblem: 'N', legend: 'FLOW', unit: 'N/S', needle: 'corner_orange', flow: true,
		zones: flowZones(ZONE_GREEN),
	},
	{
		key: 'fluid_flow', metric: 'fluid_flow',
		en: 'Corner Liquid Flow Gauge', vi: 'Đồng Hồ Dòng Chất Lỏng',
		emblem: 'MB', legend: 'FLOW', unit: 'MB/S', needle: 'corner_black', flow: true,
		zones: flowZones(ZONE_BLUE),
	},
	{
		key: 'multi', metric: 'multi',
		en: 'Corner Multipurpose Meter', vi: 'Đồng Hồ Đa Năng',
		emblem: 'A', legend: 'MULTI', unit: 'AUTO', needle: 'corner_red', flow: false,
		zones: [
			{ from: 0, to: 0.12, colour: ZONE_AMBER },
			{ from: 0.85, to: 1, colour: ZONE_GREEN },
		],
	},
	{
		key: 'multi_flow', metric: 'multi_flow',
		en: 'Corner Multipurpose Flow Meter', vi: 'Đồng Hồ Dòng Đa Năng',
		emblem: 'A', legend: 'MULTI', unit: 'AUTO', needle: 'corner_orange', flow: true,
		zones: flowZones(ZONE_GREEN),
	},
];

/** One corner meter: a kind seated in a corner, every label out of the arc. */
function cornerGauge(id: string, kind: CornerKind, seat: CornerSeat, en: string, vi: string): Gauge {
	const emblemScale = 2;
	const emblemWidth = (kind.emblem.length * 4 - 1) * emblemScale;
	const emblemX = seat.emblem[0] > 32 ? seat.emblem[0] - emblemWidth : seat.emblem[0];

	return {
		id,
		en,
		vi,
		metric: kind.metric,
		needle: kind.needle,
		dashboard: true,
		window: kind.flow ? seat.plate : undefined,
		dial: {
			shape: 'square',
			pivot: seat.pivot,
			radius: 34,
			startDeg: seat.startDeg,
			endDeg: seat.endDeg,
			zones: kind.zones,
			majors: 4,
			numerals: kind.flow
				? [
					{ at: 0, text: '-' },
					{ at: 0.5, text: '0' },
					{ at: 1, text: '+' },
				]
				: [
					{ at: 0, text: '0' },
					{ at: 0.5, text: '50' },
					{ at: 1, text: '100' },
				],
			unit: kind.unit,
			legend: kind.legend,
			unitPos: seat.unit,
			legendPos: seat.legend,
			emblem: { text: kind.emblem, at: [emblemX, seat.emblem[1]], scale: emblemScale },
		},
	};
}

/** The whole corner fleet: the capacity/flow matrix, plus the load/gen pair. */
function cornerGauges(): Gauge[] {
	const out: Gauge[] = [];

	// the original three keep their ids: the world and the shop already hold
	// them. The energy level meter in the top corner doubles as that slot of
	// the matrix, so the loop below skips it.
	out.push(cornerGauge('gauge_energy_load_corner', {
		key: 'energy_load', metric: 'energy_load',
		en: 'Corner Consumption Gauge', vi: 'Đồng Hồ Tiêu Thụ Góc',
		emblem: 'J', legend: 'LOAD', unit: 'J/S', needle: 'corner_black', flow: true,
		zones: [
			{ from: 0.72, to: 0.9, colour: ZONE_AMBER },
			{ from: 0.9, to: 1, colour: ZONE_RED },
		],
	}, CORNER_SEATS.br!, 'Corner Consumption Gauge', 'Đồng Hồ Tiêu Thụ Góc'));

	out.push(cornerGauge('gauge_energy_gen_corner', {
		key: 'energy_gen', metric: 'energy_gen',
		en: 'Corner Production Gauge', vi: 'Đồng Hồ Điện Phát Góc',
		emblem: 'J', legend: 'GEN', unit: 'J/S', needle: 'corner_black', flow: true,
		zones: [
			{ from: 0, to: 0.06, colour: ZONE_RED },
			{ from: 0.35, to: 1, colour: ZONE_GREEN },
		],
	}, CORNER_SEATS.bl!, 'Corner Production Gauge', 'Đồng Hồ Điện Phát Góc'));

	const stored = CORNER_KINDS.find((kind) => kind.key === 'energy_stored')!;

	out.push(cornerGauge('gauge_energy_stored_corner', stored, CORNER_SEATS.tr!,
		'Corner Storage Gauge', 'Đồng Hồ Tích Trữ Góc'));

	for (const kind of CORNER_KINDS) {
		for (const [corner, seat] of Object.entries(CORNER_SEATS)) {
			if (kind.key === 'energy_stored' && corner === 'tr') {
				continue;
			}

			out.push(cornerGauge(
				`gauge_corner_${kind.key}_${corner}`,
				kind,
				seat,
				`${kind.en} (${seat.en})`,
				`${kind.vi} (${seat.vi})`,
			));
		}
	}

	return out;
}

export const GAUGES: Gauge[] = [
	{
		id: 'gauge_energy_stored',
		en: 'Stored Energy Gauge',
		vi: 'Đồng Hồ Điện Tích Trữ',
		metric: 'energy_stored',
		needle: 'red',
		dashboard: true,
		// the classic 240-degree level gauge: red where the batteries are
		// nearly flat, green where a base wants to live
		dial: {
			shape: 'round',
			pivot: [32, 32],
			radius: 22,
			startDeg: -120,
			endDeg: 120,
			zones: [
				{ from: 0, to: 0.15, colour: ZONE_RED },
				{ from: 0.15, to: 0.3, colour: ZONE_AMBER },
				{ from: 0.8, to: 1, colour: ZONE_GREEN },
			],
			majors: 4,
			numerals: [
				{ at: 0, text: '0' },
				{ at: 0.5, text: '50' },
				{ at: 1, text: '100' },
			],
			unit: '%',
			legend: 'STORE',
			unitPos: [32, 51],
			legendPos: [32, 44],
		},
	},
	{
		id: 'gauge_energy_flow',
		en: 'Energy Flow Gauge',
		vi: 'Đồng Hồ Dòng Điện',
		metric: 'energy_flow',
		needle: 'black',
		dashboard: true,
		window: [42, 53],
		// a centre-zero galvanometer: needle straight up at rest, red to the
		// left when the batteries drain, green to the right when they charge
		dial: {
			shape: 'round',
			pivot: [32, 42],
			radius: 26,
			startDeg: -40,
			endDeg: 40,
			zones: flowZones(ZONE_GREEN),
			majors: 4,
			numerals: [
				{ at: 0, text: '-' },
				{ at: 0.5, text: '0' },
				{ at: 1, text: '+' },
			],
			unit: 'J/S',
			legend: 'FLOW',
			unitPos: [23, 53],
			legendPos: [32, 9],
		},
	},
	{
		id: 'gauge_energy_load',
		en: 'Energy Consumption Gauge',
		vi: 'Đồng Hồ Điện Tiêu Thụ',
		metric: 'energy_load',
		needle: 'black',
		dashboard: true,
		window: [44, 53],
		// a DIN panel meter: square face, pivot low, the arc across the top,
		// amber then red as the load approaches the range
		dial: {
			shape: 'square',
			pivot: [32, 50],
			radius: 34,
			startDeg: -45,
			endDeg: 45,
			zones: [
				{ from: 0.72, to: 0.9, colour: ZONE_AMBER },
				{ from: 0.9, to: 1, colour: ZONE_RED },
			],
			majors: 5,
			numerals: [
				{ at: 0, text: '0' },
				{ at: 1, text: '100' },
			],
			unit: 'J/S',
			legend: 'LOAD',
			unitPos: [13, 55],
			legendPos: [32, 38],
		},
	},
	{
		id: 'gauge_energy_gen',
		en: 'Energy Production Gauge',
		vi: 'Đồng Hồ Điện Phát',
		metric: 'energy_gen',
		needle: 'black',
		dashboard: true,
		window: [44, 53],
		dial: {
			shape: 'square',
			pivot: [32, 50],
			radius: 34,
			startDeg: -45,
			endDeg: 45,
			zones: [
				{ from: 0, to: 0.06, colour: ZONE_RED },
				{ from: 0.35, to: 1, colour: ZONE_GREEN },
			],
			majors: 5,
			numerals: [
				{ at: 0, text: '0' },
				{ at: 1, text: '100' },
			],
			unit: 'J/S',
			legend: 'GEN',
			unitPos: [13, 55],
			legendPos: [32, 38],
		},
	},
	{
		id: 'gauge_item_flow',
		en: 'Item Flow Gauge',
		vi: 'Đồng Hồ Dòng Vật Phẩm',
		metric: 'item_flow',
		needle: 'orange',
		dashboard: true,
		window: [42, 53],
		dial: {
			shape: 'round',
			pivot: [32, 42],
			radius: 26,
			startDeg: -50,
			endDeg: 50,
			zones: flowZones(ZONE_GREEN),
			majors: 4,
			numerals: [
				{ at: 0, text: '-' },
				{ at: 0.5, text: '0' },
				{ at: 1, text: '+' },
			],
			unit: 'N/S',
			legend: 'ITEMS',
			unitPos: [23, 53],
			legendPos: [32, 9],
		},
	},
	{
		id: 'gauge_fluid_stored',
		en: 'Stored Liquid Gauge',
		vi: 'Đồng Hồ Mức Chất Lỏng',
		metric: 'fluid_stored',
		needle: 'red',
		dashboard: true,
		// the 270-degree sweep of a tank level gauge, blue at the full end
		dial: {
			shape: 'round',
			pivot: [32, 32],
			radius: 21,
			startDeg: -135,
			endDeg: 135,
			zones: [
				{ from: 0, to: 0.1, colour: ZONE_RED },
				{ from: 0.75, to: 1, colour: ZONE_BLUE },
			],
			majors: 4,
			numerals: [
				{ at: 0, text: '0' },
				{ at: 0.5, text: '50' },
				{ at: 1, text: '100' },
			],
			unit: '%',
			legend: 'LEVEL',
			unitPos: [32, 51],
			legendPos: [32, 44],
		},
	},
	{
		id: 'gauge_fluid_flow',
		en: 'Liquid Flow Gauge',
		vi: 'Đồng Hồ Dòng Chất Lỏng',
		metric: 'fluid_flow',
		needle: 'black',
		dashboard: true,
		window: [42, 53],
		dial: {
			shape: 'round',
			pivot: [32, 42],
			radius: 26,
			startDeg: -55,
			endDeg: 55,
			zones: flowZones(ZONE_BLUE),
			majors: 4,
			numerals: [
				{ at: 0, text: '-' },
				{ at: 0.5, text: '0' },
				{ at: 1, text: '+' },
			],
			unit: 'MB/S',
			legend: 'FLOW',
			unitPos: [23, 53],
			legendPos: [32, 9],
		},
	},
	{
		id: 'gauge_energy_meter',
		en: 'Electrical Meter',
		vi: 'Công Tơ Điện',
		metric: 'energy_total',
		dashboard: true,
		window: [32, 32],
		// the Ferraris meter: dark housing, a digit window where the drums
		// roll, and nothing else - the digits are a text display
		dial: {
			shape: 'square',
			pivot: [32, 46],
			radius: 0,
			startDeg: 0,
			endDeg: 0,
			zones: [],
			majors: 0,
			numerals: [],
			unit: 'KJ',
			legend: 'TOTAL',
			unitPos: [32, 44],
			legendPos: [32, 20],
			darkFace: true,
			window: [10, 26, 44, 12],
		},
	},
	{
		id: 'gauge_multi',
		en: 'Multipurpose Meter',
		vi: 'Đồng Hồ Đa Năng',
		metric: 'multi',
		needle: 'red',
		dashboard: true,
		window: [32, 54],
		// the chameleon: on a battery or tank it reads the level, on a machine
		// it reads the draw, on a storage it reads the fill. Its block form is
		// the probe: it reads the blocks touching it, never the wire
		dial: {
			shape: 'round',
			pivot: [32, 32],
			radius: 22,
			startDeg: -120,
			endDeg: 120,
			zones: [
				{ from: 0, to: 0.12, colour: ZONE_AMBER },
				{ from: 0.85, to: 1, colour: ZONE_GREEN },
			],
			majors: 4,
			numerals: [
				{ at: 0, text: '0' },
				{ at: 0.5, text: '50' },
				{ at: 1, text: '100' },
			],
			unit: 'AUTO',
			legend: 'MULTI',
			unitPos: [32, 51],
			legendPos: [32, 44],
		},
	},
	{
		id: 'gauge_multi_square',
		en: 'Square Multipurpose Meter',
		vi: 'Đồng Hồ Đa Năng Vuông',
		metric: 'multi',
		needle: 'red',
		dashboard: true,
		window: [44, 53],
		// the chameleon on a DIN face: same square bezel and low pivot as the
		// consumption meter, so a mixed instrument wall reads as one series
		dial: {
			shape: 'square',
			pivot: [32, 50],
			radius: 34,
			startDeg: -45,
			endDeg: 45,
			zones: [
				{ from: 0, to: 0.12, colour: ZONE_AMBER },
				{ from: 0.85, to: 1, colour: ZONE_GREEN },
			],
			majors: 5,
			numerals: [
				{ at: 0, text: '0' },
				{ at: 1, text: '100' },
			],
			unit: 'AUTO',
			legend: 'MULTI',
			unitPos: [13, 55],
			legendPos: [32, 38],
		},
	},
	{
		id: 'gauge_multi_flow_square',
		en: 'Square Multipurpose Flow Meter',
		vi: 'Đồng Hồ Dòng Đa Năng Vuông',
		metric: 'multi_flow',
		needle: 'orange',
		dashboard: true,
		window: [44, 53],
		dial: {
			shape: 'square',
			pivot: [32, 50],
			radius: 34,
			startDeg: -45,
			endDeg: 45,
			zones: flowZones(ZONE_GREEN),
			majors: 5,
			numerals: [
				{ at: 0, text: '-' },
				{ at: 0.5, text: '0' },
				{ at: 1, text: '+' },
			],
			unit: 'AUTO',
			legend: 'MULTI',
			unitPos: [13, 55],
			legendPos: [32, 38],
		},
	},
	...cornerGauges(),
	{
		id: 'gauge_energy_bar',
		en: 'Energy Level Column',
		vi: 'Cột Mức Điện',
		metric: 'energy_stored',
		style: 'bar',
		dashboard: true,
		// the LED ladder of a substation panel: dark housing, a slot the bar
		// climbs in, the zone band on its left flank
		dial: {
			shape: 'column',
			pivot: [32, 54],
			radius: 45,
			startDeg: 0,
			endDeg: 0,
			zones: [
				{ from: 0, to: 0.15, colour: ZONE_RED },
				{ from: 0.15, to: 0.3, colour: ZONE_AMBER },
				{ from: 0.8, to: 1, colour: ZONE_GREEN },
			],
			majors: 0,
			numerals: [
				{ at: 0, text: '0' },
				{ at: 0.5, text: '50' },
				{ at: 1, text: '100' },
			],
			unit: '%',
			legend: 'STORE',
			unitPos: [14, 12],
			legendPos: [32, 7],
			darkFace: true,
			window: [26, 10, 12, 44],
		},
	},
	{
		id: 'gauge_fluid_bar',
		en: 'Liquid Level Column',
		vi: 'Cột Mức Chất Lỏng',
		metric: 'fluid_stored',
		style: 'bar',
		dashboard: true,
		dial: {
			shape: 'column',
			pivot: [32, 54],
			radius: 45,
			startDeg: 0,
			endDeg: 0,
			zones: [
				{ from: 0, to: 0.1, colour: ZONE_RED },
				{ from: 0.75, to: 1, colour: ZONE_BLUE },
			],
			majors: 0,
			numerals: [
				{ at: 0, text: '0' },
				{ at: 0.5, text: '50' },
				{ at: 1, text: '100' },
			],
			unit: '%',
			legend: 'LEVEL',
			unitPos: [14, 12],
			legendPos: [32, 7],
			darkFace: true,
			window: [26, 10, 12, 44],
		},
	},
	{
		id: 'gauge_digital',
		en: 'Digital Multimeter',
		vi: 'Đồng Hồ Số',
		metric: 'multi',
		style: 'digital',
		dashboard: false,
		window: [32, 31],
		// one wide LCD window and nothing else: the digits are live text,
		// green on near-black, the way every panel LCD reads
		dial: {
			shape: 'lcd',
			pivot: [32, 31],
			radius: 0,
			startDeg: 0,
			endDeg: 0,
			zones: [],
			majors: 0,
			numerals: [],
			unit: 'AUTO',
			legend: 'MULTI',
			unitPos: [32, 48],
			legendPos: [32, 12],
			darkFace: true,
			window: [8, 22, 48, 18],
		},
	},
	{
		id: 'gauge_digital_flow',
		en: 'Digital Flow Meter',
		vi: 'Đồng Hồ Dòng Số',
		metric: 'multi_flow',
		style: 'digital',
		dashboard: false,
		window: [32, 31],
		dial: {
			shape: 'lcd',
			pivot: [32, 31],
			radius: 0,
			startDeg: 0,
			endDeg: 0,
			zones: [],
			majors: 0,
			numerals: [],
			unit: 'AUTO',
			legend: 'FLOW',
			unitPos: [32, 48],
			legendPos: [32, 12],
			darkFace: true,
			window: [8, 22, 48, 18],
		},
	},
	{
		id: 'gauge_multi_flow',
		en: 'Multipurpose Flow Meter',
		vi: 'Đồng Hồ Dòng Đa Năng',
		metric: 'multi_flow',
		needle: 'orange',
		dashboard: true,
		window: [42, 53],
		dial: {
			shape: 'round',
			pivot: [32, 42],
			radius: 26,
			startDeg: -50,
			endDeg: 50,
			zones: flowZones(ZONE_GREEN),
			majors: 4,
			numerals: [
				{ at: 0, text: '-' },
				{ at: 0.5, text: '0' },
				{ at: 1, text: '+' },
			],
			unit: 'AUTO',
			legend: 'MULTI',
			unitPos: [23, 53],
			legendPos: [32, 9],
		},
	},
];

// ---- geometry --------------------------------------------------------------

/**
 * The transforms an instrument is shown with: face-on in a menu, like the
 * signs, because a flat dial seen at the isometric angle is an ellipse.
 */
const GAUGE_DISPLAY = {
	gui: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.9, 0.9, 0.9] },
	ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.35, 0.35, 0.35] },
	fixed: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.8, 0.8, 0.8] },
	thirdperson_righthand: { rotation: [0, 180, 0], translation: [0, 3.5, 0], scale: [0.4, 0.4, 0.4] },
	thirdperson_lefthand: { rotation: [0, 180, 0], translation: [0, 3.5, 0], scale: [0.4, 0.4, 0.4] },
	firstperson_righthand: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
	firstperson_lefthand: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
};

const CASING = 'lunasmp:block/gauge_casing';

/**
 * Where each form draws its face, in pixels, north-authored. The Kotlin side
 * carries the same three numbers per form: the needle hangs off them.
 */
export const PANEL_FACE = { z: 14.4, span: 12, low: 2 };
export const UNIT_FACE = { z: 4.9, span: 10, low: 3 };
export const BLOCK_FACE = { z: 0.4, span: 16, low: 0 };

/** The face plane: alpha corners show the casing behind them. */
function facePlane(id: string, z: number, low: number, size: number): Element {
	const from = (16 - size) / 2;

	return {
		from: [from, low, z],
		to: [from + size, low + size, z + 0.1],
		faces: {
			north: { uv: [0, 0, 16, 16], texture: '#face' },
		},
	};
}

/** Stuck flat on the machine it reads: a plate, and the dial on it. */
function panelModel(gauge: Gauge): Model {
	return {
		textures: { face: `lunasmp:block/${gauge.id}`, t0: CASING, particle: CASING },
		elements: retexture([
			part([2, 2, 14.6], [14, 14, 16], CASING),
			facePlane(gauge.id, PANEL_FACE.z, PANEL_FACE.low, PANEL_FACE.span),
		]),
		display: GAUGE_DISPLAY,
	};
}

/**
 * The free-standing field instrument: a housing centred in its block, feet
 * under it, the dial on the front. The old conduit stub is gone: the tile
 * grows a live coupling toward each connected wire instead (the joint item
 * below), so the box only sprouts fittings where a cable actually is.
 */
function unitModel(gauge: Gauge): Model {
	return {
		textures: { face: `lunasmp:block/${gauge.id}`, t0: CASING, particle: CASING },
		elements: retexture([
			part([3, 3, 5], [13, 13, 11], CASING),
			// the feet: it stands on a floor, and reads as a bracket on a wall
			part([4, 0, 6], [6, 3, 10], CASING),
			part([10, 0, 6], [12, 3, 10], CASING),
			facePlane(gauge.id, UNIT_FACE.z, UNIT_FACE.low, UNIT_FACE.span),
		]),
		display: GAUGE_DISPLAY,
	};
}

/** A full block of dashboard: face fills the front, wires reach the rest. */
function blockModel(gauge: Gauge): Model {
	return {
		textures: { face: `lunasmp:block/${gauge.id}`, t0: CASING, particle: CASING },
		elements: retexture([
			part([0, 0, 0.5], [16, 16, 16], CASING, {
				cull: ['east', 'south', 'west', 'up', 'down'],
			}),
			facePlane(gauge.id, BLOCK_FACE.z, BLOCK_FACE.low, BLOCK_FACE.span),
		]),
		display: GAUGE_DISPLAY,
	};
}

/**
 * The wire coupling: a socket arm from the housing to the block edge with a
 * union flange where the cable crosses in, sized so a Logistics cable's
 * 2.5-pixel arm plugs into the 4-pixel socket. One item model, spawned per
 * connected face and rotated at runtime exactly like a cable's attachments.
 * Authored pointing south: a display entity renders a model's north side
 * facing south, so at identity rotation the arm points north, which is the
 * convention the cable attachment rotation math expects.
 */
export function jointModel(): Model {
	return {
		textures: { t0: CASING, particle: CASING },
		elements: retexture([
			part([6, 6, 8], [10, 10, 16], CASING),
			part([5, 5, 13], [11, 11, 16], CASING),
		]),
	} as unknown as Model;
}

/**
 * The couplings, prebaked one per render direction. Runtime quaternions for
 * the couplings are retired: two shipped attempts at the vertical pitch sign
 * rendered up/down couplings inverted, because nothing on a horizontal wire
 * (pitch 0) can ever falsify the sign. The one orientation verified in game
 * since round 5 is the identity render (south-authored geometry pointing
 * NORTH through the display entity's own 180° yaw frame); every other
 * direction is baked from it here by plain coordinate swaps, conjugated
 * through that frame - geometry only, no rotation conventions left.
 */
const JOINT_SEATS: Record<string, (p: [number, number, number]) => [number, number, number]> = {
	north: ([x, y, z]) => [x, y, z],
	south: ([x, y, z]) => [16 - x, y, 16 - z],
	east: ([x, y, z]) => [16 - z, y, x],
	west: ([x, y, z]) => [z, y, 16 - x],
	up: ([x, y, z]) => [x, z, 16 - y],
	down: ([x, y, z]) => [x, 16 - z, y],
};

/** Bakes a south-authored coupling's boxes onto every direction. */
function seatJoints(boxes: [number, number, number][][]): Record<string, Model> {
	const out: Record<string, Model> = {};

	for (const [dir, seat] of Object.entries(JOINT_SEATS)) {
		const elements = boxes.map(([from, to]) => {
			const a = seat(from as [number, number, number]);
			const b = seat(to as [number, number, number]);
			const lo = a.map((v, i) => Math.min(v, b[i]!)) as [number, number, number];
			const hi = a.map((v, i) => Math.max(v, b[i]!)) as [number, number, number];

			return part(lo, hi, CASING);
		});

		out[dir] = {
			textures: { t0: CASING, particle: CASING },
			elements: retexture(elements),
		} as unknown as Model;
	}

	return out;
}

/** The six directional couplings: gauge_joint_<dir>. */
export function directionalJointModels(): Record<string, Model> {
	const out: Record<string, Model> = {};
	const seated = seatJoints([
		[[6, 6, 8], [10, 10, 16]],
		[[5, 5, 13], [11, 11, 16]],
	]);

	for (const [dir, model] of Object.entries(seated)) {
		out[`gauge_joint_${dir}`] = model;
	}

	return out;
}

/** The six directional dead-line sleeves: gauge_joint_long_<dir>. */
export function directionalJointLongModels(): Record<string, Model> {
	const out: Record<string, Model> = {};
	const seated = seatJoints([
		[[6.75, 6.75, 8], [9.25, 9.25, 22]],
		[[5, 5, 13], [11, 11, 19]],
	]);

	for (const [dir, model] of Object.entries(seated)) {
		out[`gauge_joint_long_${dir}`] = model;
	}

	return out;
}

/**
 * The dead-line sleeve: when the isolator is thrown open its bridge leaves
 * the network graph, so the neighbouring cable retracts its arm - correct
 * electrically, ugly visually. This longer joint reaches from the housing
 * through the border into the neighbour's block, all the way to the cable's
 * own 4-pixel hub, as a dark unpowered conduit: the run still reads as one
 * line, and the missing green arm says exactly why nothing flows.
 */
export function jointLongModel(): Model {
	return {
		textures: { t0: CASING, particle: CASING },
		elements: retexture([
			part([6.75, 6.75, 8], [9.25, 9.25, 22], CASING),
			part([5, 5, 13], [11, 11, 19], CASING),
		]),
	} as unknown as Model;
}

/**
 * The needle item models: a half-pixel blade the display entity spins.
 *
 * Not item/generated: an extruded sprite pivots on the sprite's centre line
 * at x=8, but a single-pixel blade lives in column 7, whose middle is half a
 * pixel to the left of that - the swing visibly orbited the hub instead of
 * turning on it. An elements model puts the blade at x 7.5..8.5, exactly
 * astride the pivot axis. The sprite's rows map one to one: blade rows 1..7
 * above the pivot, the tinted hub pixel at row 8, counterweight at row 9.
 */
export function needleModels(): Record<string, Model> {
	const out: Record<string, Model> = {};

	for (const style of ['red', 'black', 'orange']) {
		const texture = `lunasmp:block/gauge_needle_${style}`;

		out[`gauge_needle_${style}`] = {
			textures: { needle: texture, particle: texture },
			elements: [
				{
					from: [7.75, 6, 7.75],
					to: [8.25, 15, 8.25],
					faces: {
						north: { uv: [7, 1, 8, 10], texture: '#needle' },
						south: { uv: [8, 1, 7, 10], texture: '#needle' },
						east: { uv: [7, 1, 8, 10], texture: '#needle' },
						west: { uv: [7, 1, 8, 10], texture: '#needle' },
						up: { uv: [7, 1, 8, 2], texture: '#needle' },
						down: { uv: [7, 9, 8, 10], texture: '#needle' },
					},
				},
			],
		} as unknown as Model;

		// the corner blade: pivot to tip and nothing past the hub, because a
		// corner pivot has no face behind it for a counterweight to sweep
		const cornerTexture = `lunasmp:block/gauge_needle_corner_${style}`;

		out[`gauge_needle_corner_${style}`] = {
			textures: { needle: cornerTexture, particle: cornerTexture },
			elements: [
				{
					from: [7.75, 8, 7.75],
					to: [8.25, 15, 8.25],
					faces: {
						north: { uv: [7, 1, 8, 8], texture: '#needle' },
						south: { uv: [8, 1, 7, 8], texture: '#needle' },
						east: { uv: [7, 1, 8, 8], texture: '#needle' },
						west: { uv: [7, 1, 8, 8], texture: '#needle' },
						up: { uv: [7, 1, 8, 2], texture: '#needle' },
						down: { uv: [7, 7, 8, 8], texture: '#needle' },
					},
				},
			],
		} as unknown as Model;
	}

	return out;
}

// ---- the network switch ------------------------------------------------------

/**
 * The isolator: a cable segment with a rotary handle, in the O/I language of
 * a real disconnect switch. On, it bridges energy, items and fluids along its
 * own axis like any cable; off, it is removed from the network graph and the
 * two sides fall apart into separate networks - which is exactly what
 * breaking a cable does, held on a handle instead.
 */
export interface Switch {
	id: string;
	en: string;
	vi: string;
	/**
	 * What the handle actually cuts. 'all' is the plain isolator; 'fluid' and
	 * 'energy' toggle only their own network type and keep bridging the other
	 * two, so a valve in a combined line stops the liquid without blacking
	 * out the machines behind it.
	 */
	toggles: 'all' | 'fluid' | 'energy';
	/** A pulse device: closes for this many seconds, then reopens itself. */
	momentary?: number;
	/** Follows redstone power instead of clicks: a contactor, not a handle. */
	redstone?: boolean;
	/** Which pair of noises the state change makes. */
	sound: 'switch' | 'knife' | 'button' | 'relay' | 'wheel' | 'breaker';
	/** The state a freshly placed one is in. */
	defaultOn: boolean;
	model: (on: boolean) => Model;
}

export const SWITCHES: Switch[] = [
	{
		id: 'network_switch',
		en: 'Network Switch',
		vi: 'Cầu Dao Mạng Lưới',
		toggles: 'all',
		sound: 'switch',
		defaultOn: true,
		model: switchModel,
	},
	{
		id: 'network_lever',
		en: 'Knife Switch',
		vi: 'Cầu Dao Hở',
		toggles: 'all',
		sound: 'knife',
		defaultOn: true,
		model: knifeModel,
	},
	{
		id: 'network_button',
		en: 'Network Pulse Button',
		vi: 'Nút Nối Mạch',
		toggles: 'all',
		momentary: 3,
		sound: 'button',
		defaultOn: false,
		model: buttonModel,
	},
	{
		id: 'network_relay',
		en: 'Network Contactor',
		vi: 'Công Tắc Tơ',
		toggles: 'all',
		redstone: true,
		sound: 'relay',
		defaultOn: false,
		model: relayModel,
	},
	{
		id: 'network_valve',
		en: 'Fluid Valve',
		vi: 'Van Chất Lỏng',
		toggles: 'fluid',
		sound: 'wheel',
		defaultOn: true,
		model: valveModel,
	},
	{
		id: 'network_breaker',
		en: 'Energy Breaker',
		vi: 'Aptomat Điện',
		toggles: 'energy',
		sound: 'breaker',
		defaultOn: true,
		model: breakerModel,
	},
];

/** The one-way bridge: not a switch, but it dresses from the same shelf. */
export const DIODE = {
	id: 'network_diode',
	en: 'One-Way Bridge',
	vi: 'Van Một Chiều',
};

/** The indicator, the alarm and the light panel: each its own tile. */
export const DEVICES = [
	{ id: 'network_led', en: 'Network Activity Indicator', vi: 'Đèn Báo Hoạt Động' },
	{ id: 'network_led_block', en: 'Network Activity Indicator Block', vi: 'Khối Đèn Báo Hoạt Động' },
	{ id: 'alarm_light', en: 'Alarm Beacon', vi: 'Đèn Báo Động' },
	{ id: 'alarm_light_block', en: 'Alarm Beacon Block', vi: 'Khối Đèn Báo Động' },
	{ id: 'light_panel', en: 'Powered Light Panel', vi: 'Tấm Đèn Điện' },
];

/** The switch model: conduit through, core, top plate and the handle. */
function switchModel(on: boolean): Model {
	const top = on ? 'lunasmp:block/gauge_switch_top_on' : 'lunasmp:block/gauge_switch_top_off';

	const elements: Element[] = [
		// the conduit runs along the facing axis, cable-sized, so a run of
		// cable with a switch in it reads as one line
		part([6, 6, 0], [10, 10, 5], CASING),
		part([6, 6, 11], [10, 10, 16], CASING),
		part([5, 5, 5], [11, 11, 11], CASING),
		part([4, 11, 4], [12, 12, 12], { all: CASING, up: top }),
	];

	// the handle: along the conduit when closed, across it when open, the way
	// a real isolator shows its state from across a room
	if (on) {
		elements.push(part([7, 12, 4], [9, 14, 12], 'lunasmp:block/gauge_switch_handle'));
	} else {
		elements.push(part([4, 12, 7], [12, 14, 9], 'lunasmp:block/gauge_switch_handle'));
	}

	return {
		textures: {
			t0: CASING,
			top,
			handle: 'lunasmp:block/gauge_switch_handle',
			particle: CASING,
		},
		elements: retexture(elements),
		display: DISPLAY,
	};
}

const CERAMIC = 'lunasmp:block/gauge_ceramic';
const COPPER = 'lunasmp:block/gauge_copper';
const WHEEL = 'lunasmp:block/gauge_wheel';
const BUTTON_BOX = 'lunasmp:block/gauge_button_box';
const BUTTON_CAP = 'lunasmp:block/gauge_button_cap';
const BREAKER = 'lunasmp:block/gauge_breaker';
const HANDLE = 'lunasmp:block/gauge_switch_handle';
const DIODE_SIDE = 'lunasmp:block/gauge_diode_side';
const DIODE_OUT = 'lunasmp:block/gauge_diode_out';

/** The cable-sized conduit and the junction core every line device shares. */
function lineBody(): Element[] {
	return [
		part([6, 6, 0], [10, 10, 5], CASING),
		part([6, 6, 11], [10, 10, 16], CASING),
		part([5, 5, 5], [11, 11, 11], CASING),
	];
}

/** A composed device model wearing the shared display transforms. */
function device(elements: Element[]): Model {
	const model = composed(elements, CASING);

	model.display = DISPLAY;

	return model;
}

/**
 * The knife switch: a porcelain base over the conduit, copper jaws at the
 * near end, the hinge at the far one, and the blade lying in the jaws or
 * thrown up at forty-five degrees - state legible from across a room, which
 * is the whole reason knife switches look the way they do.
 */
function knifeModel(on: boolean): Model {
	const elements = lineBody();

	elements.push(part([3.5, 11, 3], [12.5, 12, 13], CERAMIC));
	elements.push(part([6.5, 12, 10], [9.5, 13.5, 12.5], CERAMIC));
	elements.push(part([6, 12, 3.5], [7, 14, 5], COPPER));
	elements.push(part([9, 12, 3.5], [10, 14, 5], COPPER));

	const blade = part([7.25, 12.4, 4], [8.75, 13.2, 11.25], COPPER);
	const knob = part([6.75, 12, 3.2], [9.25, 14, 4.4], HANDLE);

	if (!on) {
		const hinge = { origin: [8, 12.8, 11] as Vec3, axis: 'x', angle: 45 };

		blade.rotation = hinge;
		knob.rotation = hinge;
	}

	elements.push(blade, knob);

	return device(elements);
}

/**
 * The pulse button: a signal-yellow box with a red mushroom cap. Pressing it
 * closes the line for a few seconds and the cap sits sunk until the timer
 * lets go - a batching device, not a switch that stays where it is put.
 */
function buttonModel(on: boolean): Model {
	const elements = lineBody();

	elements.push(part([5, 11, 5], [11, 14.5, 11], BUTTON_BOX));
	elements.push(part([6, 14.5, 6], [10, on ? 15.2 : 16, 10], BUTTON_CAP));

	return device(elements);
}

/**
 * The contactor: a moulded cabinet over the line, its state flag and coil
 * pilot on both flanks. No handle anywhere on it, because nothing on it is
 * for hands - it closes while the block sits in redstone power and drops
 * out when the power goes.
 */
function relayModel(on: boolean): Model {
	const face = on ? 'lunasmp:block/gauge_relay_on' : 'lunasmp:block/gauge_relay_off';
	const elements = lineBody();

	elements.push(part([4.5, 11, 4], [11.5, 16, 12], { all: CASING, east: face, west: face }));

	return device(elements);
}

/**
 * The gate valve: flanged pipe, bonnet, and the red handwheel - straight,
 * stem risen when open; turned a half-spoke with the stem sunk when closed.
 */
function valveModel(on: boolean): Model {
	const elements: Element[] = [
		part([6, 6, 0], [10, 10, 16], CASING),
		part([5.5, 5.5, 1.5], [10.5, 10.5, 3.5], CASING),
		part([5.5, 5.5, 12.5], [10.5, 10.5, 14.5], CASING),
		part([6.75, 10, 6.75], [9.25, 12.5, 9.25], CASING),
		part([7.6, 12.5, 7.6], [8.4, on ? 14 : 13.4, 8.4], CASING),
	];

	const wheelY = on ? 13.5 : 12.9;
	const wheel: Element[] = [
		part([4.75, wheelY, 4.75], [11.25, wheelY + 1, 5.75], WHEEL),
		part([4.75, wheelY, 10.25], [11.25, wheelY + 1, 11.25], WHEEL),
		part([4.75, wheelY, 5.75], [5.75, wheelY + 1, 10.25], WHEEL),
		part([10.25, wheelY, 5.75], [11.25, wheelY + 1, 10.25], WHEEL),
		part([7.6, wheelY, 5.75], [8.4, wheelY + 1, 10.25], WHEEL),
		part([5.75, wheelY, 7.6], [10.25, wheelY + 1, 8.4], WHEEL),
	];

	if (!on) {
		for (const element of wheel) {
			element.rotation = { origin: [8, wheelY + 0.5, 8] as Vec3, axis: 'y', angle: 45 };
		}
	}

	elements.push(...wheel);

	return device(elements);
}

/**
 * The breaker: a moulded black case standing over the line, the toggle in a
 * slot on its top plate - up the line, closed; back towards the near end,
 * tripped. The plate's I and O light with the state like the isolator's.
 */
function breakerModel(on: boolean): Model {
	const top = on ? 'lunasmp:block/gauge_breaker_top_on' : 'lunasmp:block/gauge_breaker_top_off';
	const elements = lineBody();

	elements.push(part([5, 11, 3.5], [11, 16, 12.5], { all: BREAKER, up: top }));

	if (on) {
		elements.push(part([7, 16, 4.6], [9, 17.4, 6.6], BREAKER));
	} else {
		elements.push(part([7, 16, 9.4], [9, 17.4, 11.4], BREAKER));
	}

	return device(elements);
}

/**
 * The one-way bridge: a check valve in the line. Flanged pipe straight
 * through, a bulged body wearing the flow arrows, and the outlet flange in
 * clearance green - the arrows and the green end both point the way things
 * are allowed to go, which is out of the block's back.
 */
export function diodeModel(): Model {
	const elements: Element[] = [
		part([6, 6, 0], [10, 10, 16], '#t0'),
		part([5, 5, 1], [11, 11, 3], '#t0'),
		part([5, 5, 13], [11, 11, 15], '#t1'),
	];

	// the body wears the arrow on its four long sides; each face's uv walks
	// the sprite so the arrow's shaft-to-head direction lands on the block's
	// inlet-to-outlet axis
	elements.push({
		from: [4.5, 4.5, 4],
		to: [11.5, 11.5, 12],
		faces: {
			north: { uv: [4, 4, 12, 12], texture: '#body' },
			south: { uv: [4, 4, 12, 12], texture: '#body' },
			east: { uv: [16, 4, 0, 12], texture: '#arrow' },
			west: { uv: [0, 4, 16, 12], texture: '#arrow' },
			up: { uv: [0, 4, 16, 12], texture: '#arrow', rotation: 90 },
			down: { uv: [0, 4, 16, 12], texture: '#arrow', rotation: 270 },
		},
	});

	return {
		textures: {
			t0: CASING,
			t1: DIODE_OUT,
			body: CASING,
			arrow: DIODE_SIDE,
			particle: CASING,
		},
		elements,
		display: DISPLAY,
	};
}

/**
 * The live LED bars the level columns climb: item models like the needles,
 * because a display entity can only wear a model an item owns. The blade
 * stands on the model's own middle - the display is anchored at the slot's
 * floor and scaled upward, so the bottom of the bar never moves.
 */
export function barModels(): Record<string, Model> {
	const out: Record<string, Model> = {};

	for (const style of ['amber', 'blue']) {
		const texture = `lunasmp:block/gauge_bar_${style}`;

		out[`gauge_bar_${style}`] = {
			textures: { bar: texture, particle: texture },
			elements: [
				{
					from: [7, 8, 7.6],
					to: [9, 16, 8.4],
					shade: false,
					faces: {
						north: { uv: [7, 0, 9, 8], texture: '#bar' },
						south: { uv: [9, 0, 7, 8], texture: '#bar' },
						east: { uv: [7, 0, 9, 8], texture: '#bar' },
						west: { uv: [7, 0, 9, 8], texture: '#bar' },
						up: { uv: [7, 0, 9, 1], texture: '#bar' },
					},
				},
			],
		} as unknown as Model;
	}

	return out;
}

// ---- the indicator, the alarm and the light panel ---------------------------

/** Where the LED plate draws its face; the tile positions the chips off it. */
export const LED_FACE = { z: 14.5, span: 8, low: 4 };

/**
 * The activity indicator: an eight-pixel plate screwed flat to a wall, a
 * cable or a machine, three LED wells down its face. The chips themselves
 * are display entities the tile lights and blinks; the plate is static art.
 */
function ledPanelModel(): Model {
	return {
		textures: { face: 'lunasmp:block/network_led', t0: CASING, particle: CASING },
		elements: [
			part([4, 4, 14.6], [12, 12, 16], '#t0'),
			{
				from: [4, 4, 14.5],
				to: [12, 12, 14.6],
				faces: {
					north: { uv: [0, 0, 16, 16], texture: '#face' },
				},
			},
		],
		display: GAUGE_DISPLAY,
	};
}

/**
 * The indicator as a full block: an industrial housing cube wearing the same
 * LED face across its whole front, for status walls read from across a hall.
 * The chips scale up with the face; the tile knows the block's geometry.
 */
function ledBlockModel(): Model {
	return {
		textures: { face: 'lunasmp:block/network_led', t0: CASING, particle: CASING },
		elements: [
			part([0, 0, 0.2], [16, 16, 16], '#t0'),
			{
				from: [0, 0, 0],
				to: [16, 16, 0.2],
				faces: {
					north: { uv: [0, 0, 16, 16], texture: '#face' },
				},
			},
		],
		display: DISPLAY,
	};
}

/** One LED chip: a two-pixel lens the tile parks in a well. */
export function ledChipModels(): Record<string, Model> {
	const out: Record<string, Model> = {};

	for (const colour of ['amber', 'green', 'blue']) {
		const texture = `lunasmp:block/gauge_led_${colour}`;

		out[`gauge_led_${colour}`] = {
			textures: { led: texture, particle: texture },
			elements: [
				{
					from: [7, 7, 7.9],
					to: [9, 9, 8.1],
					shade: false,
					faces: {
						north: { uv: [0, 0, 16, 16], texture: '#led' },
						south: { uv: [16, 0, 0, 16], texture: '#led' },
					},
				},
			],
		} as unknown as Model;
	}

	return out;
}

/**
 * The alarm beacon: a squat casing base with a red dome in a two-post cage.
 * Authored against the model's NORTH face (base on the south wall, dome
 * reaching north), so lineRotated() mounts it on whatever face the placer
 * clicked: up on a floor, out of a wall, hanging under a ceiling. The
 * rotating beam is a display entity the tile spins while the alarm is live.
 */
function alarmModel(): Model {
	const elements: Element[] = [
		part([4.5, 4.5, 14], [11.5, 11.5, 16], CASING),
		part([5.5, 5.5, 8.5], [10.5, 10.5, 14], 'lunasmp:block/alarm_dome'),
		part([4.5, 4.5, 7.5], [11.5, 11.5, 8.5], CASING),
		part([4.5, 4.5, 8.5], [5.5, 5.5, 14], CASING),
		part([10.5, 10.5, 8.5], [11.5, 11.5, 14], CASING),
	];

	return device(elements);
}

/**
 * The alarm beacon as a full block: the base fills the block, and the dome
 * assembly rides the model's NORTH face - lineRotated() then lands it on
 * whatever face the placer clicked, floor, wall or ceiling. Proportions are
 * the floor beacon's own, folded onto the face; negative z is legal in a
 * display model, so the cage reaches out of the block.
 */
function alarmBlockModel(): Model {
	const elements: Element[] = [
		part([0, 0, 0], [16, 16, 16], CASING),
		// the collar the dome stands on, then the dome in its two-post cage
		part([4.5, 4.5, -1], [11.5, 11.5, 0], CASING),
		part([5.5, 5.5, -6.5], [10.5, 10.5, -1], 'lunasmp:block/alarm_dome'),
		part([4.5, 4.5, -7.5], [11.5, 11.5, -6.5], CASING),
		part([4.5, 4.5, -6.5], [5.5, 5.5, -1], CASING),
		part([10.5, 10.5, -6.5], [11.5, 11.5, -1], CASING),
	];

	return device(elements);
}

/**
 * The sweeping light: two cones tip to tip through the lamp, the way a
 * rotating beacon actually throws its light - stepped boxes widening away
 * from the hub, each wearing a slice of the beam gradient so the glow fades
 * as it spreads. Authored along x, spinning about y; the tile pre-rotates
 * the whole thing onto the dome's own axis.
 */
export function alarmBeamModel(): Model {
	const texture = 'lunasmp:block/alarm_beam';

	// [xFrom, xTo, halfWidth, uvFrom, uvTo]: one step of the right-hand cone;
	// the left cone mirrors it through the hub
	const steps: [number, number, number, number, number][] = [
		[8, 11, 1.0, 8, 10],
		[11, 14.5, 1.7, 10, 13],
		[14.5, 18.5, 2.5, 12, 15],
		[18.5, 23, 3.5, 14, 16],
	];

	const elements = [] as unknown[];

	for (const [xFrom, xTo, half, uvFrom, uvTo] of steps) {
		for (const [from, to, ua, ub] of [
			[xFrom, xTo, uvFrom, uvTo],
			[16 - xTo, 16 - xFrom, uvTo, uvFrom],
		] as [number, number, number, number][]) {
			elements.push({
				from: [from, 8 - half, 8 - half],
				to: [to, 8 + half, 8 + half],
				shade: false,
				faces: {
					north: { uv: [ua, 0, ub, 16], texture: '#beam' },
					south: { uv: [ub, 0, ua, 16], texture: '#beam' },
					up: { uv: [ua, 0, ub, 16], texture: '#beam' },
					down: { uv: [ua, 0, ub, 16], texture: '#beam' },
					east: { uv: [7, 0, 9, 16], texture: '#beam' },
					west: { uv: [7, 0, 9, 16], texture: '#beam' },
				},
			});
		}
	}

	return {
		textures: { beam: texture, particle: texture },
		elements,
	} as unknown as Model;
}

/**
 * The light panel: a full framed diffuser cube. Lit it is its own light
 * source - the backing block is a copper bulb carrying the light level - and
 * it bridges energy on every face, so a run of panels laid side by side
 * feeds itself from one cable at the end of the row.
 */
function lightPanelModel(lit: boolean): Model {
	const texture = lit ? 'lunasmp:block/light_panel_on' : 'lunasmp:block/light_panel_off';

	return {
		textures: { t0: texture, particle: texture },
		elements: [
			part([0, 0, 0], [16, 16, 16], '#t0', {
				cull: ['north', 'east', 'south', 'west', 'up', 'down'],
				shade: !lit ? undefined : false,
			}),
		],
		display: DISPLAY,
	};
}

/** Rewrites bare paths into the models' own texture variables. */
function retexture(elements: Element[]): Element[] {
	for (const element of elements) {
		const faces = element.faces as Record<string, Record<string, unknown>>;

		for (const face of Object.values(faces)) {
			const path = face.texture as string;

			if (path === CASING) {
				face.texture = '#t0';
			} else if (typeof path === 'string' && path.includes('switch_top')) {
				face.texture = '#top';
			} else if (typeof path === 'string' && path.includes('switch_handle')) {
				face.texture = '#handle';
			}
		}
	}

	return elements;
}

/** Every block model the instruments contribute, by written name. */
export function gaugeModels(): Record<string, Model> {
	const out: Record<string, Model> = {};

	for (const gauge of GAUGES) {
		out[gauge.id] = panelModel(gauge);
		out[`${gauge.id}_unit`] = unitModel(gauge);

		if (gauge.dashboard) {
			out[`${gauge.id}_block`] = blockModel(gauge);
		}
	}

	for (const sw of SWITCHES) {
		out[sw.id] = sw.model(true);
		out[`${sw.id}_off`] = sw.model(false);
	}

	out.network_diode = diodeModel();
	out.network_led = ledPanelModel();
	out.network_led_block = ledBlockModel();
	out.alarm_light = alarmModel();
	out.alarm_light_block = alarmBlockModel();
	out.light_panel = lightPanelModel(true);
	out.light_panel_off = lightPanelModel(false);

	return out;
}

/** Every texture the instruments paint, by written name. */
export function gaugeSprites(): Record<string, Uint8Array> {
	const out: Record<string, Uint8Array> = { ...gaugeCommonSprites() };

	for (const gauge of GAUGES) {
		out[gauge.id] = dialFace(gauge.dial);
	}

	return out;
}
