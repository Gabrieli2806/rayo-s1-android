#!/usr/bin/env bash
# Install the actual legacy ABI; never alias libncurses6 to libncurses5.
set -euo pipefail
test "$(id -u)" -eq 0 || { echo 'Run with administrative package-install privileges.' >&2; exit 1; }
test "$(dpkg --print-architecture)" = amd64 || { echo 'This host compatibility setup is amd64-only.' >&2; exit 1; }
rayo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rayo_state=/var/tmp/rayo-android12-ncurses5-apt
rayo_report="$rayo_root/local/setup/ncurses5-apt"
mkdir -p "$rayo_state/lists/partial" "$rayo_state/archives/partial"
printf '%s\n' 'deb [arch=amd64 signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] https://security.ubuntu.com/ubuntu jammy-security main universe' > "$rayo_state/sources.list"
rayo_options=(
  -o "Dir::Etc::sourcelist=$rayo_state/sources.list"
  -o Dir::Etc::sourceparts=-
  -o "Dir::State::lists=$rayo_state/lists"
  -o "Dir::Cache::archives=$rayo_state/archives"
  -o Acquire::ForceIPv4=true
  -o Acquire::Languages=none
  -o Acquire::Retries=2
  -o Acquire::https::Timeout=30
)
apt-get "${rayo_options[@]}" update
apt-get "${rayo_options[@]}" --simulate --no-install-recommends install libncurses5 libtinfo5 > "$rayo_state/plan.txt"
cat "$rayo_state/plan.txt"
# This compatibility step must not remove/downgrade the host's other packages.
awk '/^Remv / {exit 1} /^Inst / && $2 !~ /^(libncurses5|libtinfo5)(:amd64)?$/ {exit 1}' "$rayo_state/plan.txt" || {
  echo 'Unexpected package changes; refusing to install.' >&2; exit 1;
}
DEBIAN_FRONTEND=noninteractive apt-get "${rayo_options[@]}" --yes --no-install-recommends install libncurses5 libtinfo5
dpkg-query -W -f='${Package}\t${Version}\t${Architecture}\n' libncurses5 libtinfo5 > "$rayo_state/installed.tsv"
cat "$rayo_state/installed.tsv"
mkdir -p "$rayo_report"
cp "$rayo_state/sources.list" "$rayo_state/plan.txt" "$rayo_state/installed.tsv" "$rayo_report/"
echo 'Official signed legacy ABI libraries installed alongside the current ABI. Default APT sources unchanged.'
