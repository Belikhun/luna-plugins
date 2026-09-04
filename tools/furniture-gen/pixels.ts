// Pixel-art machinery: the drawing the composed models cannot borrow from
// vanilla, and the format the drawings are authored in.
//
// A pictogram is written as a grid of characters, one per pixel, over a
// palette that names what each character means. That is the only authoring
// format here that a person can read and correct: a road sign's arrow is a
// shape somebody has to get right by eye, and a screenful of `putPixel` calls
// is not something anybody can look at and see an arrow in.
//
// Everything below is ours. Nothing traces or samples anybody else's file,
// which is what lets these ship in the pack under our own licence; the shapes
// themselves are the standard public pictograms (ISO 7010, the Vienna
// Convention road signs), which are conventions rather than artwork.

import { type Bitmap, bitmap, encodePng } from '/home/belikhun/luna-console/web/src/lib/server/imaging/png';

export type Rgba = [number, number, number, number];

/** A grid of characters, one per pixel, top row first. */
export type Art = string[];

/** What each character in a grid paints; a character with no entry is left clear. */
export type Palette = Record<string, Rgba>;

/** Fully transparent, which is what an unmapped character comes out as. */
export const CLEAR: Rgba = [0, 0, 0, 0];

/** Turns `#rrggbb` (or `#rrggbbaa`) into a colour. */
export function rgb(hex: string, alpha = 255): Rgba {
	return [
		parseInt(hex.slice(1, 3), 16),
		parseInt(hex.slice(3, 5), 16),
		parseInt(hex.slice(5, 7), 16),
		hex.length > 7 ? parseInt(hex.slice(7, 9), 16) : alpha,
	];
}

/** Scales a colour's channels, keeping its alpha: the shading a bevel needs. */
export function tint(colour: Rgba, factor: number): Rgba {
	return [
		Math.max(0, Math.min(255, Math.round(colour[0] * factor))),
		Math.max(0, Math.min(255, Math.round(colour[1] * factor))),
		Math.max(0, Math.min(255, Math.round(colour[2] * factor))),
		colour[3],
	];
}

/** Writes one pixel, ignoring anything off the edge. */
export function put(image: Bitmap, x: number, y: number, colour: Rgba): void {
	if (x < 0 || y < 0 || x >= image.width || y >= image.height) {
		return;
	}

	const at = (y * image.width + x) * 4;
	image.data[at] = colour[0];
	image.data[at + 1] = colour[1];
	image.data[at + 2] = colour[2];
	image.data[at + 3] = colour[3];
}

/** Reads one pixel; off the edge reads as clear. */
export function get(image: Bitmap, x: number, y: number): Rgba {
	if (x < 0 || y < 0 || x >= image.width || y >= image.height) {
		return CLEAR;
	}

	const at = (y * image.width + x) * 4;

	return [image.data[at]!, image.data[at + 1]!, image.data[at + 2]!, image.data[at + 3]!];
}

/**
 * Lays a colour over what is already there, which is how a symbol goes onto a
 * painted background without punching its transparent margin through it.
 */
export function blend(image: Bitmap, x: number, y: number, colour: Rgba): void {
	if (colour[3] === 0) {
		return;
	}

	if (colour[3] === 255) {
		put(image, x, y, colour);
		return;
	}

	const under = get(image, x, y);
	const a = colour[3] / 255;
	const b = (under[3] / 255) * (1 - a);
	const out = a + b;

	if (out === 0) {
		put(image, x, y, CLEAR);
		return;
	}

	put(image, x, y, [
		Math.round((colour[0] * a + under[0] * b) / out),
		Math.round((colour[1] * a + under[1] * b) / out),
		Math.round((colour[2] * a + under[2] * b) / out),
		Math.round(out * 255),
	]);
}

/** A filled rectangle, `x2`/`y2` exclusive. */
export function rect(image: Bitmap, x1: number, y1: number, x2: number, y2: number, colour: Rgba): void {
	for (let y = y1; y < y2; y++) {
		for (let x = x1; x < x2; x++) {
			put(image, x, y, colour);
		}
	}
}

/** Fills the whole image. */
export function fill(image: Bitmap, colour: Rgba): void {
	rect(image, 0, 0, image.width, image.height, colour);
}

/**
 * A disc centred on the image, drawn by testing each pixel's centre against
 * the radius. No anti-aliasing: a soft edge on a 32-pixel sign reads as a
 * smudge next to vanilla's hard pixels.
 */
export function disc(image: Bitmap, cx: number, cy: number, radius: number, colour: Rgba): void {
	const r2 = radius * radius;

	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			const dx = x + 0.5 - cx;
			const dy = y + 0.5 - cy;

			if (dx * dx + dy * dy <= r2) {
				put(image, x, y, colour);
			}
		}
	}
}

/**
 * A filled triangle from three corners, by the half-plane test.
 *
 * The corners are given in image coordinates and may be in either winding
 * order: the sign of the area decides which side "inside" is on.
 */
export function triangle(
	image: Bitmap,
	a: [number, number],
	b: [number, number],
	c: [number, number],
	colour: Rgba,
): void {
	const edge = (p: [number, number], q: [number, number], x: number, y: number): number =>
		(q[0] - p[0]) * (y - p[1]) - (q[1] - p[1]) * (x - p[0]);

	const area = edge(a, b, c[0], c[1]);
	const sign = area < 0 ? -1 : 1;

	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			const px = x + 0.5;
			const py = y + 0.5;

			const w0 = edge(a, b, px, py) * sign;
			const w1 = edge(b, c, px, py) * sign;
			const w2 = edge(c, a, px, py) * sign;

			if (w0 >= 0 && w1 >= 0 && w2 >= 0) {
				put(image, x, y, colour);
			}
		}
	}
}

/**
 * The set of pixels a shape covers, as a mask the same size as the image.
 *
 * A sign's border is drawn by taking the shape, shrinking it and subtracting:
 * that gives a ring that follows a triangle's slopes and a disc's curve alike,
 * which stroking the outline pixel by pixel would not.
 */
export function maskOf(image: Bitmap, draw: (target: Bitmap) => void): boolean[] {
	const scratch = bitmap(image.width, image.height);

	draw(scratch);

	const mask: boolean[] = [];

	for (let i = 0; i < scratch.width * scratch.height; i++) {
		mask.push(scratch.data[i * 4 + 3]! > 0);
	}

	return mask;
}

/** Paints every pixel a mask covers. */
export function paintMask(image: Bitmap, mask: boolean[], colour: Rgba): void {
	for (let y = 0; y < image.height; y++) {
		for (let x = 0; x < image.width; x++) {
			if (mask[y * image.width + x]) {
				put(image, x, y, colour);
			}
		}
	}
}

/** Everything in `outer` that `inner` does not cover: the ring between two shapes. */
export function maskDifference(outer: boolean[], inner: boolean[]): boolean[] {
	return outer.map((on, i) => on && !inner[i]);
}

/**
 * Blits a character grid onto an image at a scale.
 *
 * The grid is authored at its own small size and enlarged by whole pixels, so
 * a 16-wide symbol on a 32-wide sign comes out as 2x2 blocks: chunky on
 * purpose, because that is what everything around it looks like.
 */
export function stamp(
	image: Bitmap,
	art: Art,
	palette: Palette,
	x: number,
	y: number,
	scale = 1,
): void {
	for (let row = 0; row < art.length; row++) {
		const line = art[row]!;

		for (let column = 0; column < line.length; column++) {
			const colour = palette[line[column]!];

			if (!colour || colour[3] === 0) {
				continue;
			}

			for (let dy = 0; dy < scale; dy++) {
				for (let dx = 0; dx < scale; dx++) {
					blend(image, x + column * scale + dx, y + row * scale + dy, colour);
				}
			}
		}
	}
}

/** How wide and tall a grid is, in its own pixels. */
export function artSize(art: Art): [number, number] {
	return [Math.max(0, ...art.map((line) => line.length)), art.length];
}

/** Blits a grid centred in the image. */
export function stampCentered(image: Bitmap, art: Art, palette: Palette, scale = 1): void {
	const [width, height] = artSize(art);

	stamp(
		image,
		art,
		palette,
		Math.round((image.width - width * scale) / 2),
		Math.round((image.height - height * scale) / 2),
		scale,
	);
}

export { bitmap, encodePng, type Bitmap };
