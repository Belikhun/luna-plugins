package dev.belikhun.luna.tv.client.render;

import dev.belikhun.luna.tv.client.screen.TvScreen;

/**
 * Where a screen's picture sits in the world, and which way round it is.
 *
 * Kept apart from the drawing so the geometry can be reasoned about on its own:
 * the corners the server sends are block positions, and turning those into four
 * points with the picture the right way up is the fiddly half.
 *
 * The plane is the face of the corner blocks pointing along the screen's facing,
 * and the horizontal axis is chosen so that the picture reads left to right for
 * somebody standing in front of it. That direction is the viewer's right, and a
 * viewer of this wall looks along the *opposite* of its facing: a screen facing
 * east is read by somebody looking west, whose right hand points north. Taking
 * the facing's own right instead mirrors every page, so each of the four is
 * written out rather than derived cleverly.
 */
public final class ScreenQuad {

	/** Corner positions in world space, clockwise from the top left. */
	private final double[] corners = new double[12];

	private final double normalX;
	private final double normalY;
	private final double normalZ;

	public ScreenQuad(TvScreen screen) {
		double minX = screen.minX();
		double minY = screen.minY();
		double minZ = screen.minZ();
		double maxX = screen.maxX() + 1.0;
		double maxY = screen.maxY() + 1.0;
		double maxZ = screen.maxZ() + 1.0;

		switch (screen.facing()) {
			case "EAST" -> {
				// The plane is the wall's surface, not the far side of the corner
				// blocks: the corners name the frame's own block, whose picture
				// hangs against the wall behind it, so for an east-facing screen
				// that is the block's low-X boundary and not its high one.
				set(minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ, minX, minY, maxZ);
				normalX = 1;
				normalY = 0;
				normalZ = 0;
			}

			case "WEST" -> {
				// looking east, the viewer's right is south, which is +Z
				set(maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, maxX, minY, minZ);
				normalX = -1;
				normalY = 0;
				normalZ = 0;
			}

			case "SOUTH" -> {
				// looking north, the viewer's right is east, which is +X
				set(minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, minX, minY, minZ);
				normalX = 0;
				normalY = 0;
				normalZ = 1;
			}

			case "NORTH" -> {
				// looking south, the viewer's right is west, which is -X
				set(maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ, maxX, minY, maxZ);
				normalX = 0;
				normalY = 0;
				normalZ = -1;
			}

			case "UP" -> {
				set(minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
				normalX = 0;
				normalY = 1;
				normalZ = 0;
			}

			default -> {
				set(minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ);
				normalX = 0;
				normalY = -1;
				normalZ = 0;
			}
		}
	}

	private void set(
		double x0, double y0, double z0,
		double x1, double y1, double z1,
		double x2, double y2, double z2,
		double x3, double y3, double z3
	) {
		corners[0] = x0;
		corners[1] = y0;
		corners[2] = z0;
		corners[3] = x1;
		corners[4] = y1;
		corners[5] = z1;
		corners[6] = x2;
		corners[7] = y2;
		corners[8] = z2;
		corners[9] = x3;
		corners[10] = y3;
		corners[11] = z3;
	}

	/**
	 * One corner, as x, y, z.
	 *
	 * @param index 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left
	 * @param axis 0 for x, 1 for y, 2 for z
	 * @return the coordinate
	 */
	public double corner(int index, int axis) {
		return corners[index * 3 + axis];
	}

	public double normalX() {
		return normalX;
	}

	public double normalY() {
		return normalY;
	}

	public double normalZ() {
		return normalZ;
	}

	/** How wide the picture is in blocks, along its own horizontal axis. */
	public double width() {
		return distance(0, 1);
	}

	/** How tall the picture is in blocks. */
	public double height() {
		return distance(0, 3);
	}

	private double distance(int from, int to) {
		double dx = corner(to, 0) - corner(from, 0);
		double dy = corner(to, 1) - corner(from, 1);
		double dz = corner(to, 2) - corner(from, 2);

		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Where a ray crosses this screen's plane.
	 *
	 * No bounds are applied: u and v may fall outside 0..1, which is what a drag
	 * that has wandered off the edge looks like and is the caller's to judge.
	 *
	 * @param eyeX where the ray starts
	 * @param eyeY where the ray starts
	 * @param eyeZ where the ray starts
	 * @param dirX the ray's direction, normalised
	 * @param dirY the ray's direction, normalised
	 * @param dirZ the ray's direction, normalised
	 * @return distance, u and v, or null when the ray runs parallel or backwards
	 */
	public double[] project(
		double eyeX,
		double eyeY,
		double eyeZ,
		double dirX,
		double dirY,
		double dirZ
	) {
		double facing = normalX * dirX + normalY * dirY + normalZ * dirZ;

		// Only from the front. A ray coming at the back of a screen is somebody
		// standing behind the wall, and their aim is not a cursor.
		if (facing > -1.0e-6) {
			return null;
		}

		double toPlaneX = corner(0, 0) - eyeX;
		double toPlaneY = corner(0, 1) - eyeY;
		double toPlaneZ = corner(0, 2) - eyeZ;
		double distance = (normalX * toPlaneX + normalY * toPlaneY + normalZ * toPlaneZ) / facing;

		if (distance <= 0.0) {
			return null;
		}

		double hitX = eyeX + dirX * distance - corner(0, 0);
		double hitY = eyeY + dirY * distance - corner(0, 1);
		double hitZ = eyeZ + dirZ * distance - corner(0, 2);

		double acrossX = corner(1, 0) - corner(0, 0);
		double acrossY = corner(1, 1) - corner(0, 1);
		double acrossZ = corner(1, 2) - corner(0, 2);
		double downX = corner(3, 0) - corner(0, 0);
		double downY = corner(3, 1) - corner(0, 1);
		double downZ = corner(3, 2) - corner(0, 2);

		double acrossLength = acrossX * acrossX + acrossY * acrossY + acrossZ * acrossZ;
		double downLength = downX * downX + downY * downY + downZ * downZ;

		if (acrossLength <= 0.0 || downLength <= 0.0) {
			return null;
		}

		return new double[] {
			distance,
			(hitX * acrossX + hitY * acrossY + hitZ * acrossZ) / acrossLength,
			(hitX * downX + hitY * downY + hitZ * downZ) / downLength
		};
	}

	/**
	 * Where a point on the picture is in the world.
	 *
	 * @param u across, 0 at the left edge and 1 at the right
	 * @param v down, 0 at the top edge and 1 at the bottom
	 * @param axis 0 for x, 1 for y, 2 for z
	 * @return the coordinate of that point
	 */
	public double at(double u, double v, int axis) {
		double top = corner(0, axis) + (corner(1, axis) - corner(0, axis)) * u;
		double bottom = corner(3, axis) + (corner(2, axis) - corner(3, axis)) * u;

		return top + (bottom - top) * v;
	}
}
