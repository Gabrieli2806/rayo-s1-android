from pathlib import Path
import hashlib
import shutil
import json

work = Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
product = Path('/var/tmp/codex-rayo-android81/work/out/target/product/rayos1_oreo/system')
original = work / 'trial-system-v01'
delta = work / 'live-graphics-libs'
changed = []
for subtree in ('lib', 'vendor'):
    for source in (product / subtree).rglob('*'):
        if not source.is_file() or source.is_symlink():
            continue
        relative = source.relative_to(product)
        previous = original / relative
        if previous.is_file() and hashlib.sha256(source.read_bytes()).digest() == hashlib.sha256(previous.read_bytes()).digest():
            continue
        # Preserve the selected factory drivers and board configuration.
        if str(relative) in ('lib/libion.so', 'vendor/lib/libbt-vendor.so'):
            continue
        destination = delta / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        changed.append(str(relative))
print(json.dumps(changed, indent=2))
