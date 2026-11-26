#!/sbin/busybox sh
# GabOS Rescue: apply or roll back a wireless update staged by GabOS.
#
# GabOS (gabos-update-stage) leaves in /realdata/gabos-update:
#   plan.txt              line-based plan written from the signed manifest
#   gabos-system.img.gz   public system image (factory files zeroed)
#   gabos-boot.img        public boot image (factory kernel and second zeroed)
#   PENDING               request to apply
# The factory files are copied back from the installed system image, and the
# factory kernel and second stage from the current boot partition. Every piece
# and both final images are checked against the plan. On any mismatch nothing
# is replaced. After a successful install the previous system and boot stay
# beside the new ones until GabOS completes a boot (it then removes BOOTING);
# if the boot watchdog brings the projector back here first, they are restored.
BB=/sbin/busybox
TB=/sbin/toybox
U=/realdata/gabos-update
BASE=/dev/block/platform/1021c000.rksdmmc/by-name
SYSTEM=/realdata/local/tmp/rayo12-framework-system.raw.img
NEW=$U/system.new.img
OLD=$U/system.previous.img
log(){ $BB echo "GABOS_UPDATE: $*"; $BB echo "$*" >> $U/update.log; }
sha(){ $TB sha256sum "$1" | $TB cut -d' ' -f1; }
show(){ $BB test -f "/res/$1.rgb565" && $BB dd if="/res/$1.rgb565" of=/dev/graphics/fb0 bs=2560 count=720 2>/dev/null; }
plan(){ $BB grep "^$1 " $U/plan.txt | $BB head -1 | $TB cut -d' ' -f2-; }

fail(){
    log "failed: $*"
    $BB rm -f $NEW $U/boot.new.img $U/PENDING
    $BB echo "$*" > $U/FAILED
    show gabos-rescue
    $BB umount /tmp/gabos-old 2>/dev/null
    $BB losetup -d /dev/block/loop1 2>/dev/null
    $BB sync
    /sbin/gabos-reboot
    exit 1
}

rollback(){
    log 'GabOS did not finish booting the update; restoring the previous version'
    show gabos-updating
    if $BB test -f $OLD && $BB test -f $U/boot.previous.img; then
        $TB mv -f $OLD $SYSTEM
        $BB dd if=$U/boot.previous.img of=$BASE/boot bs=4096 2>/dev/null
        $BB sync
        log 'previous version restored'
        $BB echo 'rolled back' > $U/FAILED
    fi
    $BB rm -f $U/BOOTING $U/boot.previous.img
    $BB sync
    /sbin/gabos-reboot
    exit 0
}

$BB test -d $U || exit 0
$BB test -f $U/BOOTING && rollback
$BB test -f $U/PENDING || exit 0
$BB rm -f $U/FAILED
log "applying $(plan version)"
show gabos-updating

# 1. Downloaded pieces.
$BB test "$(sha $U/gabos-system.img.gz)" = "$(plan system_gz_sha256)" || fail 'system download damaged'
$BB test "$(sha $U/gabos-boot.img)" = "$(plan boot_public_sha256)" || fail 'boot download damaged'
$BB test "$($TB stat -c %s /realdata/rayo12-framework-data/userdata.ext4)" = "$(plan data_bytes)" \
    || fail 'private data size differs; update from a computer'

# 2. Public system image, then the factory files from the installed system.
$BB gunzip -c $U/gabos-system.img.gz > $NEW || fail 'not enough space to unpack'
$BB test "$($TB stat -c %s $NEW)" = "$(plan system_bytes)" || fail 'unpacked system has the wrong size'
$BB mkdir -p /tmp/gabos-old
$BB test -b /dev/block/loop1 || $BB mknod /dev/block/loop1 b 7 1
$BB losetup -r /dev/block/loop1 $SYSTEM || fail 'installed system not readable'
$BB mount -t ext4 -o ro /dev/block/loop1 /tmp/gabos-old || fail 'installed system not mountable'
FILE=
while read kind a b c; do
    case "$kind" in
        blob)
            FILE="/tmp/gabos-old/$c"; OFFSET=0
            $BB test "$(sha "$FILE")" = "$a" || fail "installed $c differs; update from a computer" ;;
        run)
            $BB dd if="$FILE" of=$NEW bs=4096 skip=$OFFSET seek=$a count=$b conv=notrunc 2>/dev/null || fail "restore $FILE"
            OFFSET=$((OFFSET+b)) ;;
    esac
done < $U/plan.txt
$BB umount /tmp/gabos-old
$BB losetup -d /dev/block/loop1
$BB test "$(sha $NEW)" = "$(plan system_sha256)" || fail 'rebuilt system does not match the release'

# 3. Boot image with the factory kernel and second stage from the current boot.
$TB cp $U/gabos-boot.img $U/boot.new.img
PAGE=$(plan page)
for part in kernel second; do
    set -- $(plan $part)
    OFF=$1; LEN=$2; WANT=$3
    # Parts start on a page boundary; copy whole pages and cut to the length.
    $BB dd if=$BASE/boot bs=$PAGE skip=$((OFF/PAGE)) count=$(((LEN+PAGE-1)/PAGE)) 2>/dev/null | $BB head -c $LEN > /tmp/part
    $BB test "$(sha /tmp/part)" = "$WANT" || fail "current $part differs; update from a computer"
    $BB dd if=/tmp/part of=$U/boot.new.img bs=$PAGE seek=$((OFF/PAGE)) conv=notrunc 2>/dev/null
    $BB rm -f /tmp/part
done
$BB test "$(sha $U/boot.new.img)" = "$(plan boot_sha256)" || fail 'rebuilt boot does not match the release'

# 4. Install: keep the previous version until GabOS completes a boot.
$BB dd if=$BASE/boot of=$U/boot.previous.img bs=4096 2>/dev/null || fail 'cannot save the current boot'
$TB mv -f $SYSTEM $OLD
$TB mv -f $NEW $SYSTEM
$BB chmod 0444 $SYSTEM
$BB dd if=$U/boot.new.img of=$BASE/boot bs=4096 2>/dev/null
$BB sync
if ! $BB test "$($TB md5sum $BASE/boot | $TB cut -d' ' -f1)" = "$(plan boot_md5)"; then
    $BB dd if=$U/boot.previous.img of=$BASE/boot bs=4096 2>/dev/null
    $TB mv -f $SYSTEM $NEW; $TB mv -f $OLD $SYSTEM
    fail 'boot partition did not verify; previous version kept'
fi
$BB rm -f $U/boot.new.img $U/gabos-system.img.gz $U/gabos-boot.img $U/PENDING
$TB touch $U/BOOTING
$BB sync
log "installed $(plan version); starting GabOS"
/sbin/gabos-reboot
