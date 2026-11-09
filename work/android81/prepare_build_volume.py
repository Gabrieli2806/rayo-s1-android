from pathlib import Path
import os, subprocess, sys

root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
image=root/'build-volume.ext4'
mount=root/'build-root'
mount.mkdir(exist_ok=True)
if not image.exists():
    with image.open('wb') as stream: stream.truncate(256*1024**3)
    subprocess.run(['mkfs.ext4','-F','-m','0','-E','lazy_itable_init=1,lazy_journal_init=1,nodiscard',str(image)],check=True)
if subprocess.run(['mountpoint','-q',str(mount)]).returncode != 0:
    subprocess.run(['mount','-o','loop',str(image),str(mount)],check=True)
os.chown(mount,1000,1000)
print('Isolated case-sensitive build filesystem ready:',mount)
subprocess.run(['df','-h',str(mount)],check=True)
if '--init' in sys.argv:
    command=['runuser','-u','gabrieli2806','--','bash',str(root/'init_aosp.sh')]
    if '--sync' in sys.argv:command.append('--sync')
    subprocess.run(command,check=True)
