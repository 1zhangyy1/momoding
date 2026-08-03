#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK_VERSION="28.2.13676358"
NDK_ROOT="$ANDROID_HOME/ndk/$NDK_VERSION"
if [[ ! -d "$NDK_ROOT" && -n "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_ROOT="$ANDROID_NDK_HOME"
fi
if [[ ! -d "$NDK_ROOT" && -d "/opt/homebrew/share/android-commandlinetools/ndk/$NDK_VERSION" ]]; then
  NDK_ROOT="/opt/homebrew/share/android-commandlinetools/ndk/$NDK_VERSION"
fi
[[ -d "$NDK_ROOT" ]] || {
  echo "Android NDK $NDK_VERSION is required. Install it with sdkmanager --sdk_root=\"$ANDROID_HOME\" 'ndk;$NDK_VERSION'." >&2
  exit 1
}
NDK_ACTUAL_VERSION="$(awk -F ' *= *' '$1 == "Pkg.Revision" { print $2 }' "$NDK_ROOT/source.properties" 2>/dev/null || true)"
[[ "$NDK_ACTUAL_VERSION" == "$NDK_VERSION" ]] || {
  echo "Android NDK mismatch: expected $NDK_VERSION, got ${NDK_ACTUAL_VERSION:-unknown} at $NDK_ROOT." >&2
  exit 1
}

case "$(uname -s)-$(uname -m)" in
  Darwin-*) PREBUILT="darwin-x86_64" ;;
  Linux-x86_64) PREBUILT="linux-x86_64" ;;
  *) echo "Unsupported build host: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/$PREBUILT/bin"
CC="$TOOLCHAIN/aarch64-linux-android30-clang"
AR="$TOOLCHAIN/llvm-ar"
RANLIB="$TOOLCHAIN/llvm-ranlib"
READELF="$TOOLCHAIN/llvm-readelf"
STRIP="$TOOLCHAIN/llvm-strip"
OBJCOPY="$TOOLCHAIN/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/llvm-objdump"
for tool in "$CC" "$AR" "$RANLIB" "$READELF" "$STRIP" "$OBJCOPY" "$OBJDUMP"; do
  [[ -x "$tool" ]] || { echo "Missing NDK tool: $tool" >&2; exit 1; }
done

PROOT_VERSION="5.1.107.86"
PROOT_SHA256="692da7f952ac390eb65c4117d360cad23a052525eea4eb110ae42f8a4a7d7bb8"
PROOT_URL="https://github.com/termux/proot/archive/refs/tags/v$PROOT_VERSION.zip"
TALLOC_VERSION="2.4.3"
TALLOC_SHA256="dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
ALPINE_VERSION="3.23.5"
ALPINE_SHA256="d9a77cb31f715c56afa4f0a5aa42c04cfde813b70ad74a64725902b09c29a6cc"
ALPINE_NAME="alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"
ALPINE_ASSET_NAME="alpine-minirootfs-$ALPINE_VERSION-aarch64.tgz"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/$ALPINE_NAME"

# Samba's bundled Waf still writes some generated files as latin-1, so its build path must stay
# ASCII-only even when the checkout path contains Chinese characters.
BUILD_ROOT="${MOMODING_LINUX_RUNTIME_BUILD_ROOT:-${TMPDIR:-/tmp}/momoding-linux-runtime-build}"
DOWNLOADS="$BUILD_ROOT/downloads"
SOURCES="$BUILD_ROOT/sources"
TALLOC_PREFIX="$BUILD_ROOT/talloc-prefix"
GENERATED="$PROJECT_ROOT/android-app/app/build/generated/phone-local-linux-runtime"
JNI_OUT="$GENERATED/jniLibs/arm64-v8a"
ASSET_OUT="$GENERATED/assets/phone-local-runtime"
mkdir -p "$DOWNLOADS" "$SOURCES" "$TALLOC_PREFIX" "$JNI_OUT" "$ASSET_OUT"

verify_sha256() {
  local expected="$1"
  local file="$2"
  local actual
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || {
    echo "SHA-256 mismatch for $file: expected $expected, got $actual" >&2
    exit 1
  }
}

fetch() {
  local url="$1"
  local sha="$2"
  local destination="$3"
  if [[ ! -f "$destination" ]]; then
    curl --fail --location --retry 3 --output "$destination" "$url"
  fi
  verify_sha256 "$sha" "$destination"
}

PROOT_ARCHIVE="$DOWNLOADS/proot-$PROOT_VERSION.zip"
TALLOC_ARCHIVE="$DOWNLOADS/talloc-$TALLOC_VERSION.tar.gz"
ALPINE_ARCHIVE="$DOWNLOADS/$ALPINE_NAME"
fetch "$PROOT_URL" "$PROOT_SHA256" "$PROOT_ARCHIVE"
fetch "$TALLOC_URL" "$TALLOC_SHA256" "$TALLOC_ARCHIVE"
fetch "$ALPINE_URL" "$ALPINE_SHA256" "$ALPINE_ARCHIVE"

PROOT_SOURCE="$SOURCES/proot-$PROOT_VERSION"
PATCH_FILE="$PROJECT_ROOT/third_party/patches/proot-5.1.107.86-android-ndk.patch"
PATCH_SHA256="$(shasum -a 256 "$PATCH_FILE" | awk '{print $1}')"
rm -rf "$PROOT_SOURCE"
unzip -q "$PROOT_ARCHIVE" -d "$SOURCES"
patch -d "$PROOT_SOURCE" -p1 < "$PATCH_FILE"

TALLOC_SOURCE="$SOURCES/talloc-$TALLOC_VERSION"
rm -rf "$TALLOC_SOURCE" "$TALLOC_PREFIX"
mkdir -p "$TALLOC_PREFIX"
tar -xzf "$TALLOC_ARCHIVE" -C "$SOURCES"
printf '%s\n' \
    'Checking uname sysname type: "Linux"' \
    'Checking uname machine type: "dontcare"' \
    'Checking uname release type: "dontcare"' \
    'Checking uname version type: "dontcare"' \
    'Checking simple C program: OK' \
    'building library support: OK' \
    'Checking for large file support: OK' \
    'Checking for -D_FILE_OFFSET_BITS=64: OK' \
    'Checking for WORDS_BIGENDIAN: OK' \
    'Checking for C99 vsnprintf: OK' \
    'Checking for HAVE_SECURE_MKSTEMP: OK' \
    'rpath library support: OK' \
    '-Wl,--version-script support: FAIL' \
    'Checking correct behavior of strtoll: OK' \
    'Checking correct behavior of strptime: OK' \
    'Checking for HAVE_IFACE_GETIFADDRS: OK' \
    'Checking for HAVE_IFACE_IFCONF: OK' \
    'Checking for HAVE_IFACE_IFREQ: OK' \
    'Checking getconf LFS_CFLAGS: OK' \
    'Checking for large file support without additional flags: OK' \
    'Checking for working strptime: OK' \
    'Checking for HAVE_SHARED_MMAP: OK' \
    'Checking for HAVE_MREMAP: OK' \
    'Checking for HAVE_INCOHERENT_MMAP: OK' \
    'Checking getconf large file support flags work: OK' \
  > "$TALLOC_SOURCE/cross-answers.txt"
(
  cd "$TALLOC_SOURCE"
  CC="$CC" AR="$AR" RANLIB="$RANLIB" \
    ./configure \
      --prefix="$TALLOC_PREFIX" \
      --disable-rpath \
      --disable-python \
      --cross-compile \
      --cross-answers=cross-answers.txt >/dev/null
  CC="$CC" AR="$AR" RANLIB="$RANLIB" make -s -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
  CC="$CC" AR="$AR" RANLIB="$RANLIB" make -s install
  "$AR" rcs "$TALLOC_PREFIX/lib/libtalloc.a" bin/default/talloc*.o
  "$RANLIB" "$TALLOC_PREFIX/lib/libtalloc.a"
)

(
  cd "$PROOT_SOURCE/src"
  make clean >/dev/null 2>&1 || true
  make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)" \
    CC="$CC" \
    LD="$CC" \
    STRIP="$STRIP" \
    READELF="$READELF" \
    OBJCOPY="$OBJCOPY" \
    OBJDUMP="$OBJDUMP" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$PROOT_SOURCE/src -I$TALLOC_PREFIX/include -DVERSION=\\\"$PROOT_VERSION-momoding\\\"" \
    LDFLAGS="$TALLOC_PREFIX/lib/libtalloc.a -ldl -Wl,-z,noexecstack" \
    PROOT_UNBUNDLE_LOADER=/unused
)

cp "$PROOT_SOURCE/src/proot" "$JNI_OUT/libmomoding_proot.so"
cp "$PROOT_SOURCE/src/loader/loader" "$JNI_OUT/libmomoding_proot_loader.so"
"$STRIP" "$JNI_OUT/libmomoding_proot.so"
"$STRIP" "$JNI_OUT/libmomoding_proot_loader.so"
rm -f "$ASSET_OUT/alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"
cp "$ALPINE_ARCHIVE" "$ASSET_OUT/$ALPINE_ASSET_NAME"

PROOT_OUTPUT_SHA="$(shasum -a 256 "$JNI_OUT/libmomoding_proot.so" | awk '{print $1}')"
LOADER_OUTPUT_SHA="$(shasum -a 256 "$JNI_OUT/libmomoding_proot_loader.so" | awk '{print $1}')"
cat > "$GENERATED/runtime-manifest.json" <<EOF
{
  "architecture": "arm64-v8a",
  "androidApi": 30,
  "ndkVersion": "$NDK_VERSION",
  "prootVersion": "$PROOT_VERSION",
  "prootSourceSha256": "$PROOT_SHA256",
  "prootPatchSha256": "$PATCH_SHA256",
  "prootBinarySha256": "$PROOT_OUTPUT_SHA",
  "prootLoaderSha256": "$LOADER_OUTPUT_SHA",
  "tallocVersion": "$TALLOC_VERSION",
  "tallocSourceSha256": "$TALLOC_SHA256",
  "alpineVersion": "$ALPINE_VERSION",
  "alpineSha256": "$ALPINE_SHA256"
}
EOF

"$READELF" -d "$JNI_OUT/libmomoding_proot.so" | grep -q 'Shared library: \[libc.so\]'
if "$READELF" -d "$JNI_OUT/libmomoding_proot.so" | grep -q 'libtalloc'; then
  echo "PRoot unexpectedly depends on dynamic libtalloc" >&2
  exit 1
fi
echo "Prepared phone-local Linux runtime at $GENERATED"
