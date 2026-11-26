#!/usr/bin/env python3
"""Build the GabOS Rescue image for the recovery slot (run in WSL/Linux).

The rescue replaces the Android 8.1 v14 rescue system (about 530 MB of files
on userdata) with a ramdisk-only repair system on the factory kernel:

- from the v14 rescue ramdisk (local/device-kit/runtime/trial-root-v14.tar.gz):
  the static Android 8.1 init, ueventd and adbd, busybox, the SELinux policy
  and contexts, default.prop and the USB rules;
- from the Android 12 tree: static toybox, e2fsck and resize2fs, and the
  `gabos-reboot` helper (device/canon/rayos1_12_lab/rescue_reboot.c);
- from work/gabos-rescue: init.rc, the start-up script and a help picture.

It needs nothing on userdata. Root ADB over USB uses the same serial as
before. The host tools recognise it through /gabos-rescue.version.

Output: outputs/gabos-rescue/gabos-rescue.img (packed by pack-recovery.py with
the factory kernel and command line) and MANIFEST.json with its checksums.
This tool writes nothing to a device.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = 4
# Not needed without the 8.1 system image and its framework.
DROP = ['init.zygote32.rc', 'init.rk30board.rc', 'init.usb.configfs.rc', 'charger', 'drmboot.ko',
        'sbin/charger', 'sbin/watchdogd', 'sbin/rayo_hwbinder.ko', 'sbin/rayo-mount-trial.sh',
        'sbin/rayo-overlay.sh', 'sbin/rayo-wifi-caps', 'fstab.rk30board']
TOYBOX_APPLETS = ['cat', 'chmod', 'chown', 'cmp', 'cp', 'date', 'dd', 'df', 'dmesg', 'du', 'getprop', 'grep',
                  'head', 'id', 'ln', 'ls', 'md5sum', 'mkdir', 'mount', 'mv', 'ps', 'rm', 'sha256sum', 'stat',
                  'sync', 'tail', 'touch', 'truncate', 'umount', 'uname', 'wc']


def digest(path, kind='sha256'):
    h = hashlib.new(kind)
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def picture(target, updating=False):
    """1280x720 RGB565 help screen (or the update screen) in the GabOS look, English and Spanish."""
    from PIL import Image, ImageDraw, ImageFont
    font_path = ROOT/'local/fonts/Roboto-Regular.ttf'

    def font(size, weight):
        f = ImageFont.truetype(str(font_path), size)
        try:
            f.set_variation_by_axes([weight, 100, 0])
        except Exception:
            pass
        return f
    image = Image.new('RGB', (1280, 720), (13, 16, 22))
    draw = ImageDraw.Draw(image)
    draw.text((96, 90), 'GabOS', font=font(96, 100), fill=(236, 240, 245))
    draw.text((100, 214), 'Rescue  ·  Rescate', font=font(40, 300), fill=(120, 200, 230))
    if updating:
        draw.rectangle((96, 214, 1200, 270), fill=(13, 16, 22))
        draw.text((100, 214), 'Updating  ·  Actualizando', font=font(40, 300), fill=(120, 200, 230))
    lines = [
        ('Installing a GabOS update. Do not turn the projector off; it restarts by itself.', 26, (236, 240, 245)),
        ('Instalando una actualización de GabOS. No apagues el proyector; se reinicia solo.', 26, (236, 240, 245)),
    ] if updating else [
        ('Connect the projector to a computer with a USB cable to repair or update GabOS.', 26, (236, 240, 245)),
        ('Conecta el proyector a una computadora con un cable USB para reparar o actualizar GabOS.', 26, (236, 240, 245)),
        ('', 18, None),
        ('Hold the power button to turn it off. The next start opens GabOS again.', 22, (160, 170, 182)),
        ('Mantén pulsado el botón de encendido para apagarlo. Al volver a encenderlo se abre GabOS.', 22, (160, 170, 182)),
    ]
    y = 330
    for text, size, color in lines:
        if text:
            draw.text((100, y), text, font=font(size, 300), fill=color)
        y += size+22
    draw.text((100, 640), 'g2806.net', font=font(20, 300), fill=(120, 200, 230))
    raw, wide = bytearray(), bytearray()
    for r, g, b in image.getdata():
        value = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)
        raw += value.to_bytes(2, 'little')
        wide += bytes((b, g, r, 255))
    target.write_bytes(bytes(raw))
    # The same picture for a 32-bit framebuffer (little-endian ARGB, stored B, G, R, A).
    target.with_suffix('.bgra8888').write_bytes(bytes(wide))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tv-out', type=Path, default=Path.home()/'android/rayo12-tv-out')
    parser.add_argument('--output', type=Path, default=ROOT/'outputs/gabos-rescue')
    parser.add_argument('--picture', type=Path, default=ROOT/'outputs/gabos-rescue-picture.rgb565',
                        help='Help picture; render it first with --render-picture (needs Pillow, e.g. on Windows)')
    parser.add_argument('--render-picture', action='store_true', help='Only render the help picture and exit')
    args = parser.parse_args()
    if args.render_picture:
        args.picture.parent.mkdir(parents=True, exist_ok=True)
        picture(args.picture)
        picture(args.picture.with_name('gabos-updating-picture.rgb565'), updating=True)
        print(args.picture, args.picture.stat().st_size)
        return
    if not args.picture.is_file() or args.picture.stat().st_size != 1280*720*2:
        raise SystemExit('Render the help picture first: make-gabos-rescue.py --render-picture')
    inter = args.tv_out/'soong/.intermediates'
    arm = 'android_arm_armv7-a-neon_cortex-a7'
    binaries = {
        'sbin/toybox': inter/'external/toybox/toybox-static'/arm/'toybox-static32',
        'sbin/e2fsck': inter/'external/e2fsprogs/e2fsck/e2fsck_static'/arm/'e2fsck_static',
        'sbin/resize2fs': inter/'external/e2fsprogs/resize/resize2fs_ramdisk/android_ramdisk_arm_armv7-a-neon_cortex-a7/resize2fs',
        'sbin/gabos-reboot': inter/'device/canon/rayos1_12_lab/gabos-reboot'/arm/'gabos-reboot',
    }
    for name, path in binaries.items():
        if not path.is_file():
            raise SystemExit('Build the static rescue tools first (missing %s): '
                             'tools/build-android12-tv.sh gabos-reboot toybox-static e2fsck_static resize2fs_ramdisk' % name)
        if b'INTERP' in subprocess.run(['readelf', '-l', str(path)], capture_output=True).stdout:
            raise SystemExit(name+' is not static')
    base = ROOT/'local/device-kit/runtime/trial-root-v14.tar.gz'
    if not base.is_file():
        raise SystemExit('Missing the v14 rescue ramdisk archive: '+str(base))
    if args.output.exists():
        raise SystemExit('Refusing to overwrite '+str(args.output))
    stage = Path(tempfile.mkdtemp(prefix='gabos-rescue-'))
    root = stage/'root'
    root.mkdir()
    with tarfile.open(base) as archive:
        archive.extractall(root)
    for name in DROP:
        path = root/name
        if path.is_dir() and not path.is_symlink():
            shutil.rmtree(path)
        elif path.exists() or path.is_symlink():
            path.unlink()
    for name, path in binaries.items():
        shutil.copyfile(path, root/name)
        os.chmod(root/name, 0o755)
    shutil.copyfile(ROOT/'work/gabos-rescue/init.rc', root/'init.rc')
    for script in ('gabos-rescue-start.sh', 'gabos-apply-update.sh'):
        shutil.copyfile(ROOT/'work/gabos-rescue'/script, root/'sbin'/script)
        os.chmod(root/'sbin'/script, 0o750)
    (root/'res').mkdir(exist_ok=True)
    shutil.copyfile(args.picture, root/'res/gabos-rescue.rgb565')
    shutil.copyfile(args.picture.with_name('gabos-updating-picture.rgb565'), root/'res/gabos-updating.rgb565')
    bin_dir = root/'system/bin'
    bin_dir.mkdir(parents=True, exist_ok=True)
    for applet in TOYBOX_APPLETS:
        link = bin_dir/applet
        if link.exists() or link.is_symlink():
            link.unlink()
        link.symlink_to('/sbin/toybox')
    for name, target in (('toybox', '/sbin/toybox'), ('e2fsck', '/sbin/e2fsck'), ('resize2fs', '/sbin/resize2fs'),
                         ('gabos-reboot', '/sbin/gabos-reboot')):
        link = bin_dir/name
        if link.exists() or link.is_symlink():
            link.unlink()
        link.symlink_to(target)
    if not (bin_dir/'sh').exists() and not (bin_dir/'sh').is_symlink():
        (bin_dir/'sh').symlink_to('/sbin/busybox')
    (root/'gabos-rescue.version').write_text('gabos-rescue %d\n' % VERSION)
    # Identify as the rescue in getprop-style checks of older tools as well.
    with (root/'default.prop').open('a') as props:
        props.write('ro.gabos.rescue=%d\n' % VERSION)
    args.output.mkdir(parents=True)
    image = args.output/'gabos-rescue.img'
    subprocess.run(['python3', str(ROOT/'tools/pack-recovery.py'), '--ramdisk', str(root), '--output', str(image)], check=True)
    with tarfile.open(args.output/'gabos-rescue-root.tar.gz', 'w:gz') as archive:
        archive.add(root, arcname='.')
    manifest = {'version': VERSION, 'image': image.name, 'bytes': image.stat().st_size,
                'md5': digest(image, 'md5'), 'sha256': digest(image),
                'base_ramdisk_sha256': digest(base),
                'binaries': {name: digest(path) for name, path in binaries.items()},
                'device_written': False}
    (args.output/'MANIFEST.json').write_text(json.dumps(manifest, indent=2)+'\n')
    shutil.rmtree(stage)
    print(json.dumps(manifest, indent=2))


if __name__ == '__main__':
    main()
