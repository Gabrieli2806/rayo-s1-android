"""Assemble an experimental system tree; never touch the connected device."""
from pathlib import Path
import json
import shutil
import sys
import zipfile

workspace=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q')
work=workspace/'work/android81'
product=Path('/var/tmp/codex-rayo-android81/work/out/target/product/rayos1_oreo')
target=work/(sys.argv[1] if len(sys.argv)>1 else 'trial-system-v01')
assert target.resolve().is_relative_to(work.resolve())
if target.exists(): raise SystemExit('Choose a new staging version: '+str(target))
for relative in ('priv-app/SystemUI/SystemUI.apk','priv-app/Settings/Settings.apk','framework/framework.jar'):
    source=product/'system'/relative
    if not source.is_file() or not zipfile.is_zipfile(source): raise SystemExit('Build incomplete: '+str(source))
shutil.copytree(product/'system',target,symlinks=True)
stock=work/'stock-system'
blobs=(
    'lib/egl/libGLES_mali.so','lib/hw/gralloc.rk30board.so',
    'lib/hw/hwcomposer.rk30board.so','lib/libion.so',
    'lib/hw/audio.primary.rk30board.so','lib/libaudioroute.so',
    'vendor/lib/libbt-vendor.so',
)
for relative in blobs:
    destination=target/relative
    destination.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(stock/relative,destination)
    destination.chmod(0o644)
for relative in ('etc/bluetooth','usr/keylayout','usr/keychars','usr/idc'):
    if (stock/relative).is_dir(): shutil.copytree(stock/relative,target/relative,dirs_exist_ok=True,symlinks=True)
for filename in ('fw_bcm4330.bin','fw_bcm4330_apsta.bin','nvram_AP6330.txt','nvram_4330.txt','nvram_4330_oob.txt','BCM4330B1.hcd','AP6330'):
    source=stock/'etc/firmware'/filename
    destination=target/'etc/firmware'/filename
    destination.parent.mkdir(parents=True,exist_ok=True)
    if source.is_dir(): shutil.copytree(source,destination,symlinks=True)
    elif source.is_file(): shutil.copy2(source,destination)
for filename in ('audio_policy.conf','audio_effects.conf','tiny_hw.xml','mixer_paths.xml'):
    if (stock/'etc'/filename).is_file(): shutil.copy2(stock/'etc'/filename,target/'etc'/filename)

launcher=target/'app/RayoDark'
launcher.mkdir(parents=True)
shutil.copy2(workspace/'outputs/rayo-s1/Rayo-Dark-TV-v0.2.apk',launcher/'RayoDark.apk')
(launcher/'RayoDark.apk').chmod(0o644)
(target/'media').mkdir(exist_ok=True)
shutil.copy2(workspace/'outputs/rayo-s1/bootanimation-gabrieli-v0.3.zip',target/'media/bootanimation.zip')

# The proprietary driver reopens this original path; the vendor copy is kept too.
(target/'lib/egl').mkdir(exist_ok=True)
(target/'lib/egl/libGLES_mali.so').chmod(0o644)
(target/'vendor/lib/egl').mkdir(parents=True,exist_ok=True)
shutil.copy2(stock/'lib/egl/libGLES_mali.so',target/'vendor/lib/egl/libGLES_mali.so')

props=(target/'build.prop').read_text()
assert 'ro.build.version.release=8.1.0' in props
props+='\n# Rayo S1 isolated recovery-slot experiment\nro.rayo.trial=1\n'
(target/'build.prop').write_text(props)
manifest={'aosp_tag':'android-8.1.0_r81','blobs':list(blobs),
          'limits':['No stock system-partition replacement','Private trial userdata','Wi-Fi/Bluetooth/audio not yet validated in the complete OS','Legacy hardware video decoding not integrated']}
(work/'trial-system-manifest.json').write_text(json.dumps(manifest,indent=2))
print('Staged Android 8.1 trial tree:',target)
