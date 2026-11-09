"""Prepare the next isolated trial, without accessing the device."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

work = Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
target = work / 'trial-system-v02'
if target.exists():
    raise SystemExit('Staging directory already exists')
shutil.copytree(work / 'trial-system-v01', target, symlinks=True)
shutil.copytree(work / 'live-graphics-libs', target, dirs_exist_ok=True)
# Mali reopens its vendor path. Both names must resolve to the SAME ELF object.
mali = target / 'lib/egl/libGLES_mali.so'
mali.unlink()
mali.symlink_to('../../vendor/lib/egl/libGLES_mali.so')
# Use AOSP's framebuffer fallback until the proprietary HWC is adapted.
hwc = target / 'lib/hw/hwcomposer.rk30board.so'
hwc.rename(hwc.with_suffix('.so.disabled'))
# Go omits some optional native APIs. Do not preload absent libraries.
public = target / 'etc/public.libraries.txt'
names = public.read_text().splitlines()
missing = [name for name in names if name and not (target / 'lib' / name).is_file()]
public.write_text(''.join(name + '\n' for name in names if name not in missing))
print('Optional public libraries absent from Go image:', missing)
manifest = ET.parse(work / 'trial-vendor-manifest.xml')
for name, version, interface in (
    ('audio', '2.0', 'IDevicesFactory'),
    ('audio.effect', '2.0', 'IEffectsFactory'),
    ('bluetooth', '1.0', 'IBluetoothHci'),
    ('wifi', '1.0', 'IWifi'),
    ('wifi.supplicant', '1.0', 'ISupplicant'),
):
    hal = ET.SubElement(manifest.getroot(), 'hal', {'format': 'hidl'})
    ET.SubElement(hal, 'name').text = 'android.hardware.' + name
    ET.SubElement(hal, 'transport').text = 'hwbinder'
    ET.SubElement(hal, 'version').text = version
    iface = ET.SubElement(hal, 'interface')
    ET.SubElement(iface, 'name').text = interface
    ET.SubElement(iface, 'instance').text = 'default'
ET.indent(manifest)
manifest.write(target / 'vendor/manifest.xml', encoding='unicode')
(target / 'vendor/etc/vintf').mkdir(parents=True, exist_ok=True)
shutil.copy2(target / 'vendor/manifest.xml', target / 'vendor/etc/vintf/manifest.xml')
print('Prepared', target)
