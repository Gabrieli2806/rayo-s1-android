"""Find damaged generated files after the WSL interruption; no source edits."""
from pathlib import Path
import json
import sys
import zipfile

root=Path('/var/tmp/codex-rayo-android81/work/out').resolve()
report=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81/damaged-build-files.json')
bad=[]
quarantine=root/'rayo-quarantine'
extensions={'.jar','.apk','.jack','.dex','.o','.so'}
for path in root.rglob('*'):
    if path.is_relative_to(quarantine): continue
    if path.is_symlink() or path.suffix not in extensions or not path.is_file(): continue
    with path.open('rb') as stream: magic=stream.read(8)
    if path.suffix in ('.jar','.apk','.jack'):
        valid=magic.startswith((b'PK\x03\x04',b'PK\x05\x06'))
        if valid:
            try:
                with zipfile.ZipFile(path) as archive:
                    valid=archive.testzip() is None
                    for entry in archive.infolist():
                        if path.suffix != '.jack' and '/' not in entry.filename and entry.filename.startswith('classes') and entry.filename.endswith('.dex'):
                            with archive.open(entry) as member:
                                valid=valid and member.read(4)==b'dex\n'
            except (zipfile.BadZipFile,EOFError,OSError): valid=False
    elif path.suffix=='.dex':
        valid=magic.startswith((b'dex\n',b'PK\x03\x04'))
    else: valid=magic.startswith(b'\x7fELF')
    if not valid: bad.append({'path':str(path.relative_to(root)),'bytes':path.stat().st_size,'prefix':magic.hex()})
report.write_text(json.dumps(bad,indent=2))
print('Damaged generated artifacts:',len(bad))
for item in bad[:20]: print(item)
if '--repair' in sys.argv:
    for item in bad:
        source=(root/item['path']).resolve()
        assert source.is_relative_to(root)
        target=quarantine/item['path']
        target.parent.mkdir(parents=True,exist_ok=True)
        if target.exists(): raise RuntimeError('Already quarantined: '+str(target))
        source.rename(target)
    print('Moved damaged outputs aside; build will regenerate them.')
