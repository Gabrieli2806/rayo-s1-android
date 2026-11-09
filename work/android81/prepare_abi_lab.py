"""Stage an isolated Oreo userspace laboratory; never create a flash image."""
from pathlib import Path
import shutil
import subprocess

workspace = Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
product = Path('/var/tmp/codex-rayo-android81/work/out/target/product/rayos1')
lab = workspace / 'abi-lab'
for folder in ('system/bin', 'system/lib', 'system/etc', 'vendor/lib/egl', 'vendor/lib/hw', 'dev', 'proc', 'tmp'):
    (lab / folder).mkdir(parents=True, exist_ok=True)
for filename in ('linker', 'rayo_abi_probe', 'rayo_gpu_probe'):
    source = product / 'system/bin' / filename
    if not source.is_file():
        raise SystemExit(f'Build required: {source}')
    shutil.copy2(source, lab / 'system/bin' / filename)
for source in (product / 'system/lib').glob('*.so'):
    shutil.copy2(source, lab / 'system/lib' / source.name)
for relative in ('lib/egl/libGLES_mali.so', 'lib/hw/gralloc.rk30board.so', 'lib/hw/hwcomposer.rk30board.so'):
    source = workspace / 'stock-system' / relative
    destination = lab / 'vendor/lib' / Path(relative).relative_to('lib')
    shutil.copy2(source, destination)
# This proprietary driver reopens its own original absolute pathname.
(lab / 'system/lib/egl').mkdir(exist_ok=True)
alias = lab / 'system/lib/egl/libGLES_mali.so'
if not alias.is_symlink():
    alias.symlink_to('/vendor/lib/egl/libGLES_mali.so')
# Rockchip extends the old libion interface with ion_get_phys. This is a
# vendor-specific support library, not an Oreo framework-library substitute.
shutil.copy2(workspace / 'stock-system/lib/libion.so', lab / 'system/lib/libion.so')
shutil.copy2(workspace / 'stock-system/vendor/lib/libbt-vendor.so', lab / 'vendor/lib/libbt-vendor.so')
(lab / 'system/build.prop').write_text('ro.hardware=rk30board\nro.build.version.sdk=27\n')
(lab / 'README.txt').write_text('Not a ROM; do not flash. ABI test supplies only urandom and read-only proc; no HAL open callbacks. GPU pbuffer test additionally supplies mali and ion, never framebuffer/Binder devices. Stock Rockchip libion is deliberately retained; other staged system libraries are built from Oreo. Stock graphics and Bluetooth blobs live under vendor/lib.\n')
subprocess.run(['tar', '-czf', str(workspace / 'abi-lab.tar.gz'), '-C', str(lab), '.'], check=True)
print('Staged', lab)
