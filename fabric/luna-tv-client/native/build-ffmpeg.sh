#!/usr/bin/env bash
#
# Builds the minimal ffmpeg the TV client uses to decode its H.264 stream.
#
# The mod never links ffmpeg: it spawns it as a child process, feeds Annex-B
# H.264 to its stdin and reads raw frames from its stdout. That keeps a decoder
# fault to a dead child rather than a crashed game, and keeps the mod free of
# JNI entirely.
#
# Everything ffmpeg can otherwise do is switched off, so the binary carries one
# decoder, one parser, one demuxer, one muxer and the two pipe protocols. That
# is what takes a ~70 MB general-purpose build down to a few megabytes, small
# enough to ship inside the jar.
#
# Windows x64 only. It is the one platform with no ffmpeg a player is likely to
# already have on PATH, and shipping a binary per platform is what would make
# the jar too big to be worth it. Everywhere else the mod looks for ffmpeg on
# PATH and falls back to the MJPEG stream when there is none.
#
# Output: dist/lunatv/native/windows-x86_64/ffmpeg.exe
#
# Requires: nasm and mingw-w64.
#
set -euo pipefail

VERSION="8.1.2"
TARBALL_SHA256="464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${LUNA_FFMPEG_WORK:-/mnt/shulker/build/ffmpeg}"
DIST="$HERE/dist/lunatv/native"
SOURCE="$WORK/ffmpeg-$VERSION"

mkdir -p "$WORK" "$DIST"

# ---------------------------------------------------------------- source

if [ ! -d "$SOURCE" ]; then
	tarball="$WORK/ffmpeg-$VERSION.tar.xz"

	if [ ! -f "$tarball" ]; then
		echo "==> fetching ffmpeg $VERSION"
		curl -sL -o "$tarball" "https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.xz"
	fi

	echo "$TARBALL_SHA256  $tarball" | sha256sum -c -
	tar xf "$tarball" -C "$WORK"
fi

# ---------------------------------------------------------------- flags

# One decoder in, raw frames out. `scale` is what performs the pixel format
# conversion the CLI asks for with -pix_fmt; buffer/buffersink are the ends of
# the filtergraph ffmpeg builds whether or not a filter was requested, so they
# are not optional even though nothing here filters anything.
COMPONENTS=(
	--disable-everything
	--enable-decoder=h264
	--enable-encoder=rawvideo
	--enable-parser=h264
	--enable-demuxer=h264
	--enable-muxer=rawvideo
	--enable-protocol=pipe
	--enable-protocol=file
	--enable-filter=scale
	--enable-filter=format
	--enable-filter=null
	--enable-bsf=h264_mp4toannexb
	--enable-bsf=extract_extradata
)

# GPU decode, through Windows' own APIs and nothing else. d3d11va and dxva2 are
# system-header code (autodetect-class, so --disable-autodetect turned them off)
# and link no DLL beyond what Windows ships; h264_d3d11va2 is the variant the
# CLI's -hwaccel d3d11va actually drives. NVDEC and friends stay out: they need
# vendor headers and runtime DLLs, and the point of this binary is having none.
HWACCEL=(
	--enable-d3d11va
	--enable-dxva2
	--enable-hwaccel=h264_d3d11va
	--enable-hwaccel=h264_d3d11va2
	--enable-hwaccel=h264_dxva2
)

# --disable-autodetect is the one that matters for reproducibility: without it
# configure links whatever happens to be installed on the build host, and the
# binary then refuses to start on a machine that lacks it.
TRIMMINGS=(
	--disable-autodetect
	--disable-network
	--disable-avdevice
	--disable-doc
	--disable-htmlpages
	--disable-manpages
	--disable-podpages
	--disable-txtpages
	--disable-debug
	--disable-ffplay
	--disable-ffprobe
)

build() {
	local platform="$1"
	shift

	local out="$WORK/build-$platform"
	echo
	echo "================ $platform ================"

	rm -rf "$out"
	mkdir -p "$out"
	cd "$out"

	"$SOURCE/configure" \
		"${COMPONENTS[@]}" \
		"${TRIMMINGS[@]}" \
		--extra-cflags="-O2 -ffunction-sections -fdata-sections" \
		--extra-ldflags="-static -Wl,--gc-sections" \
		"$@" > configure.log 2>&1 || { tail -30 configure.log; exit 1; }

	make -j"$(nproc)" > build.log 2>&1 || { tail -40 build.log; exit 1; }

	mkdir -p "$DIST/$platform"

	if [ -f ffmpeg.exe ]; then
		x86_64-w64-mingw32-strip -s ffmpeg.exe
		cp ffmpeg.exe "$DIST/$platform/ffmpeg.exe"
	else
		strip -s ffmpeg
		cp ffmpeg "$DIST/$platform/ffmpeg"
	fi
}

build windows-x86_64 \
	"${HWACCEL[@]}" \
	--target-os=mingw32 \
	--arch=x86_64 \
	--cross-prefix=x86_64-w64-mingw32- \
	--extra-ldflags="-static -static-libgcc -Wl,--gc-sections"

echo
echo "==> built"
ls -la "$DIST"/*/*
