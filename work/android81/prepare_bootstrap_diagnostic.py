from pathlib import Path
import shutil
import subprocess

work=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
target=work/'recovery-bootstrap-diagnostic'
if not target.exists():
    shutil.copytree(work/'recovery-ramdisk',target,symlinks=True)
    props=(target/'default.prop').read_text()
    (target/'default.prop').write_text(props+'\nro.rayo.bootstrap=1\n')
subprocess.run(['python3',str(work/'pack_trial_boot.py'),target.name,'recovery-bootstrap-diagnostic.img'],check=True)
