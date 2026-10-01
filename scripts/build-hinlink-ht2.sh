#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="${WORK_DIR:-$ROOT_DIR/work}"
ANDROID_DIR="${ANDROID_DIR:-$WORK_DIR/android}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/out}"
JOBS="${JOBS:-$(nproc)}"
SYNC_JOBS="${SYNC_JOBS:-8}"

# RK3528's newest public, reproducible Radxa Android SDK at the time this
# builder was added. Override these variables to point at a newer compatible
# Rockchip/Radxa manifest without changing this script.
MANIFEST_URL="${MANIFEST_URL:-https://github.com/radxa/manifests.git}"
MANIFEST_BRANCH="${MANIFEST_BRANCH:-Android13_RK3528}"
MANIFEST_FILE="${MANIFEST_FILE:-radxa.xml}"
LUNCH_TARGET="${LUNCH_TARGET:-rk3528_rock_2a-userdebug}"
BUILD_FLAGS="${BUILD_FLAGS:--UACKu}"

HT2_DTS="${HT2_DTS:-$ROOT_DIR/rk3528-hinlink-ht2.dts}"
BASE_DTS_NAME="${BASE_DTS_NAME:-rk3528-rock-2a.dts}"
HT2_DTS_NAME="rk3528-hinlink-ht2.dts"

log() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

usage() {
  cat <<'EOF'
Build Android firmware for Hinlink HT2 (RK3528).

Usage: scripts/build-hinlink-ht2.sh [command]

Commands:
  all       sync SDK, install HT2 DTS, build and collect images (default)
  sync      initialize/sync Android SDK only
  prepare   install HT2 DTS into an already synced SDK
  build     build an already prepared SDK and collect images
  clean     remove Android build output (keeps downloaded source)
  distclean remove work/ and out/

Useful environment variables:
  WORK_DIR=/path          SDK workspace (default: ./work)
  ANDROID_DIR=/path       Android source tree (default: $WORK_DIR/android)
  OUT_DIR=/path           collected firmware output (default: ./out)
  JOBS=N                  build parallelism
  SYNC_JOBS=N             repo sync parallelism
  MANIFEST_URL=URL        Android manifest repository
  MANIFEST_BRANCH=BRANCH  default: Android13_RK3528
  MANIFEST_FILE=FILE      default: radxa.xml
  LUNCH_TARGET=TARGET     default: rk3528_rock_2a-userdebug
  BUILD_FLAGS=FLAGS       default: -UACKu
  BASE_DTS_NAME=FILE      reference board DTS replaced by the HT2 DTS
EOF
}

check_host() {
  need git
  need python3
  need curl
  need sha256sum
  need find
  mkdir -p "$WORK_DIR" "$OUT_DIR"

  if ! command -v repo >/dev/null 2>&1; then
    log "Installing repo launcher into $WORK_DIR/bin"
    mkdir -p "$WORK_DIR/bin"
    curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o "$WORK_DIR/bin/repo"
    chmod +x "$WORK_DIR/bin/repo"
    export PATH="$WORK_DIR/bin:$PATH"
  fi
}

sync_sdk() {
  check_host
  mkdir -p "$ANDROID_DIR"
  cd "$ANDROID_DIR"
  if [[ ! -d .repo ]]; then
    log "Initializing $MANIFEST_BRANCH ($MANIFEST_FILE)"
    repo init -u "$MANIFEST_URL" -b "$MANIFEST_BRANCH" -m "$MANIFEST_FILE" --depth=1
  fi
  log "Synchronizing Android SDK with $SYNC_JOBS jobs"
  repo sync -c --no-tags --no-clone-bundle -j"$SYNC_JOBS"
}

find_kernel_dts_dir() {
  local candidate
  for candidate in \
    "$ANDROID_DIR/kernel-5.10/arch/arm64/boot/dts/rockchip" \
    "$ANDROID_DIR/kernel/arch/arm64/boot/dts/rockchip"; do
    [[ -f "$candidate/rk3528.dtsi" ]] && { printf '%s\n' "$candidate"; return 0; }
  done
  candidate="$(find "$ANDROID_DIR" -type f -path '*/arch/arm64/boot/dts/rockchip/rk3528.dtsi' -printf '%h\n' -quit 2>/dev/null || true)"
  [[ -n "$candidate" ]] || return 1
  printf '%s\n' "$candidate"
}

prepare_ht2() {
  [[ -f "$HT2_DTS" ]] || die "HT2 DTS not found: $HT2_DTS"
  [[ -d "$ANDROID_DIR" ]] || die "Android tree not found: $ANDROID_DIR (run sync first)"

  local dts_dir base_dts ht2_dts
  dts_dir="$(find_kernel_dts_dir)" || die "cannot locate RK3528 kernel DTS directory"
  base_dts="$dts_dir/$BASE_DTS_NAME"
  ht2_dts="$dts_dir/$HT2_DTS_NAME"
  [[ -f "$base_dts" ]] || die "reference DTS not found: $base_dts. Set BASE_DTS_NAME to the DTS selected by your lunch target."

  log "Installing Hinlink HT2 DTS into $dts_dir"
  cp -f "$HT2_DTS" "$ht2_dts"

  # The public RK3528 Android product selects the Rock 2A board DTS. Keep the
  # Android product/partition/vendor configuration but compile our HT2 hardware
  # description by replacing that selected DTS. Save the original for auditing.
  if [[ ! -f "$base_dts.android-build.orig" ]]; then
    cp -f "$base_dts" "$base_dts.android-build.orig"
  fi
  cp -f "$HT2_DTS" "$base_dts"

  printf '%s  %s\n' "$(sha256sum "$HT2_DTS" | awk '{print $1}')" "$HT2_DTS_NAME" > "$ANDROID_DIR/.hinlink-ht2-dts.sha256"
  log "HT2 DTS prepared (base target: $BASE_DTS_NAME)"
}

collect_images() {
  local stamp dest
  stamp="$(date -u +%Y%m%d-%H%M%S)"
  dest="$OUT_DIR/hinlink-ht2-$stamp"
  mkdir -p "$dest"

  log "Collecting firmware images"
  if [[ -d "$ANDROID_DIR/rockdev" ]]; then
    find "$ANDROID_DIR/rockdev" -maxdepth 2 -type f \
      \( -name '*.img' -o -name '*.bin' -o -name '*.txt' -o -name '*.cfg' \) \
      -exec cp -f {} "$dest/" \;
  fi
  if [[ -d "$ANDROID_DIR/out/target/product" ]]; then
    find "$ANDROID_DIR/out/target/product" -maxdepth 3 -type f \
      \( -name 'update.img' -o -name 'boot.img' -o -name 'vendor_boot.img' -o -name 'dtbo.img' \) \
      -exec cp -f {} "$dest/" \;
  fi

  [[ -n "$(find "$dest" -type f -print -quit)" ]] || die "build finished but no firmware images were found"
  (cd "$dest" && sha256sum * > SHA256SUMS)
  log "Firmware collected at: $dest"
}

build_android() {
  [[ -f "$ANDROID_DIR/build/envsetup.sh" ]] || die "Android SDK is incomplete; run sync first"
  cd "$ANDROID_DIR"
  # envsetup defines shell functions used by lunch/build.sh.
  set +u
  source build/envsetup.sh
  lunch "$LUNCH_TARGET"
  set -u
  export BUILD_JOBS="$JOBS"
  log "Building $LUNCH_TARGET with flags: $BUILD_FLAGS"
  ./build.sh $BUILD_FLAGS -j"$JOBS"
  collect_images
}

clean_android() {
  [[ -d "$ANDROID_DIR" ]] || return 0
  rm -rf "$ANDROID_DIR/out" "$ANDROID_DIR/rockdev"
}

cmd="${1:-all}"
case "$cmd" in
  all) sync_sdk; prepare_ht2; build_android ;;
  sync) sync_sdk ;;
  prepare) prepare_ht2 ;;
  build) prepare_ht2; build_android ;;
  clean) clean_android ;;
  distclean) rm -rf "$WORK_DIR" "$OUT_DIR" ;;
  -h|--help|help) usage ;;
  *) usage >&2; die "unknown command: $cmd" ;;
esac
