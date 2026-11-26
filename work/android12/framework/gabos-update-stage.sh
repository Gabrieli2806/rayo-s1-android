#!/system/bin/sh
# GabOS wireless update, stage 2 (root). GabOS Settings has downloaded the
# release into its private files and checked the manifest signature and every
# file's SHA-256; it then sets sys.gabos.update.stage=1, which starts this
# script. It moves the files to /realdata/gabos-update (outside the private
# data image, which is too small to also hold the unpacked system), checks them
# again, and restarts into GabOS Rescue, which rebuilds and installs the update
# (/sbin/gabos-apply-update.sh in the rescue ramdisk).
set -u
SRC=/data/data/local.rayo.launcher/files/update
U=/realdata/gabos-update
RESERVE=$((768*1024*1024))
state(){ setprop sys.gabos.update.state "$1"; log -t GabOSUpdate "$1"; }
plan(){ grep "^$1 " "$SRC/plan.txt" | head -1 | cut -d' ' -f2-; }
sha(){ sha256sum "$1" | cut -d' ' -f1; }
fail(){ state "error:$1"; setprop sys.gabos.update.stage 0; exit 1; }

state staging
for f in plan.txt gabos-system.img.gz gabos-boot.img; do
    test -f "$SRC/$f" || fail "missing $f"
done
test "$(sha "$SRC/gabos-system.img.gz")" = "$(plan system_gz_sha256)" || fail 'system download damaged'
test "$(sha "$SRC/gabos-boot.img")" = "$(plan boot_public_sha256)" || fail 'boot download damaged'
test "$(stat -c %s /realdata/rayo12-framework-data/userdata.ext4)" = "$(plan data_bytes)" || fail 'data size differs'

# Space on userdata: the download, the unpacked new system (the installed one
# stays until the new one has booted) and the 768 MiB reserve.
mkdir -p $U && chmod 0700 $U
rm -f $U/system.new.img $U/PENDING $U/FAILED
set -- $(df -k /realdata | tail -1)
FREE=$(( $4 * 1024 ))
NEED=$(( $(stat -c %s "$SRC/gabos-system.img.gz") + $(stat -c %s "$SRC/gabos-boot.img") + $(plan system_bytes) + RESERVE ))
test $FREE -ge $NEED || fail "not enough space ($((FREE/1048576)) of $((NEED/1048576)) MiB)"

for f in plan.txt gabos-system.img.gz gabos-boot.img; do
    cp "$SRC/$f" "$U/$f.part" && mv "$U/$f.part" "$U/$f" || fail "copy $f"
done
test "$(sha $U/gabos-system.img.gz)" = "$(plan system_gz_sha256)" || fail 'copy damaged'
rm -f "$SRC/gabos-system.img.gz" "$SRC/gabos-boot.img"
touch $U/PENDING
sync
state "restarting:$(plan version)"
sleep 2
setprop sys.powerctl reboot,recovery
