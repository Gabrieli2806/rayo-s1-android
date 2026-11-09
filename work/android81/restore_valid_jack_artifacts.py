from pathlib import Path
import zipfile

root=Path('/var/tmp/codex-rayo-android81/work/out')
quarantine=root/'rayo-quarantine'
count=0
for source in quarantine.rglob('*.jack'):
    if source.stat().st_size == 0: continue
    try:
        with zipfile.ZipFile(source) as archive:
            if archive.testzip() is not None: continue
    except (zipfile.BadZipFile,EOFError,OSError): continue
    target=root/source.relative_to(quarantine)
    if target.exists(): raise RuntimeError('Refusing overwrite: '+str(target))
    source.rename(target)
    count+=1
print('Restored valid Jack libraries (internal dex fragments are compressed):',count)
for path in (root/'.ninja_log',root/'soong/.ninja_log'):
    if not path.exists(): continue
    data=path.read_bytes()
    if b'\0' not in data: continue
    backup=path.with_name(path.name+'.interrupted-backup')
    if not backup.exists(): backup.write_bytes(data)
    clean=b''.join(line for line in data.splitlines(keepends=True) if b'\0' not in line)
    path.write_bytes(clean)
    print('Removed damaged log records from:',path)
