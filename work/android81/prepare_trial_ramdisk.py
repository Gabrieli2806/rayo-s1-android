from pathlib import Path
import shutil
import sys

workspace=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
source=Path('/var/tmp/codex-rayo-android81/work/out/target/product/rayos1_oreo/root')
target=workspace/(sys.argv[1] if len(sys.argv)>1 else 'trial-root-v01')
assert target.resolve().is_relative_to(workspace.resolve())
if target.exists(): raise SystemExit('Refusing to mix ramdisk versions: '+str(target))
assert (source/'init').read_bytes()[:4]==b'\x7fELF'
assert (source/'sepolicy').stat().st_size>100000
shutil.copytree(source,target,symlinks=True)
(target/'sbin').mkdir(exist_ok=True)
shutil.copy2(workspace/'stock-system/bin/busybox',target/'sbin/busybox')
shutil.copy2(workspace/'recovery-ramdisk/sbin/adbd',target/'sbin/adbd')
shutil.copy2(workspace/'hwbinder-module/rayo_hwbinder.ko',target/'sbin/rayo_hwbinder.ko')
shutil.copy2(workspace/'drmboot-original.ko',target/'drmboot.ko')
for name in ('init.rk30board.rc','fstab.rk30board'):
    shutil.copy2(workspace/'trial-ramdisk'/name,target/name)
shutil.copy2(workspace/'trial-ramdisk/rayo-mount-trial.sh',target/'sbin/rayo-mount-trial.sh')
for name in ('busybox','adbd','rayo-mount-trial.sh'):
    (target/'sbin'/name).chmod(0o755)

# The recovery adbd and a static shell remain usable if /system cannot mount.
(target/'system/bin').mkdir(parents=True,exist_ok=True)
(target/'system/bin/sh').symlink_to('/sbin/busybox')
usb=(target/'init.usb.rc').read_text()
usb=usb.replace('/system/bin/adbd --root_seclabel=u:r:su:s0',
                '/sbin/adbd --root_seclabel=u:r:su:s0 --device_banner=recovery')
usb=usb.replace('idProduct 4EE7','idProduct D001')
(target/'init.usb.rc').write_text(usb)
uevent=(workspace/'stock-ramdisk/ueventd.rk30board.rc').read_text()
uevent+='\n/dev/hwbinder 0660 system system\n/dev/vndbinder 0660 system system\n'
(target/'ueventd.rk30board.rc').write_text(uevent)
props=(target/'default.prop').read_text()
props=props.replace('ro.secure=1','ro.secure=0').replace('ro.adb.secure=1','ro.adb.secure=0')
if 'ro.adb.secure=' not in props: props+='\nro.adb.secure=0\n'
props+='\npersist.sys.usb.config=adb\nro.rayo.trial=1\n'
(target/'default.prop').write_text(props)
print('Prepared experimental recovery-slot ramdisk:',target)
