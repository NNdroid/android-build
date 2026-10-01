#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

bash -n scripts/build-hinlink-ht2.sh
[[ -s rk3528-hinlink-ht2.dts ]]
grep -q 'model = "Hinlink HT2"' rk3528-hinlink-ht2.dts
grep -q 'compatible = "hinlink,ht2", "rockchip,rk3528"' rk3528-hinlink-ht2.dts
grep -q 'Android13_RK3528' scripts/build-hinlink-ht2.sh
grep -q 'rk3528_rock_2a-userdebug' scripts/build-hinlink-ht2.sh
scripts/build-hinlink-ht2.sh --help >/dev/null

echo "HT2 builder checks passed"
