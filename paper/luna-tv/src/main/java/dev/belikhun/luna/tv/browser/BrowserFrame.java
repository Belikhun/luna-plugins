package dev.belikhun.luna.tv.browser;

/**
 * One decoded frame.
 *
 * The array is always a full viewport buffer, but a frame may describe a
 * smaller rectangle than the viewport: Chromium sends undersized frames while
 * a page is still coming up. {@code stride} is therefore the array's row
 * length, not the frame's, and a consumer must read rows with it.
 *
 * @param pixels row-major ARGB, {@code stride * viewport height} long
 * @param width painted width in pixels
 * @param height painted height in pixels
 * @param stride row length of the backing array
 */
public record BrowserFrame(int[] pixels, int width, int height, int stride) {}
