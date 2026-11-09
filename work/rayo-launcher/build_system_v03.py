from pathlib import Path
import shutil, subprocess, hashlib
root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q')
out=root/'outputs/rayo-s1'
image=out/'system-rayo-dark-v0.3.img'
if image.exists(): raise SystemExit('Preserving existing v0.3 image')
shutil.copyfile(out/'system-rayo-dark-v0.2.img',image)
commands=root/'work/rayo-launcher/debugfs-logo-v03.txt'
commands.write_text('\n'.join([
    'rm /media/bootanimation.zip',
    f'write {out}/bootanimation-gabrieli-v0.3.zip /media/bootanimation.zip',
    'set_inode_field /media/bootanimation.zip mode 0100644',
    'set_inode_field /media/bootanimation.zip uid 0',
    'set_inode_field /media/bootanimation.zip gid 0',
    f'ea_set -f {root}/work/rayo-launcher/selinux-label.bin /media/bootanimation.zip security.selinux',
])+'\n')
subprocess.run(['debugfs','-w','-f',str(commands),str(image)],check=True)
subprocess.run(['e2fsck','-fn',str(image)],check=True)
check=root/'work/rayo-launcher/animation-v03-readback.zip'
subprocess.run(['debugfs','-R',f'dump /media/bootanimation.zip {check}',str(image)],check=True)
assert check.read_bytes()==(out/'bootanimation-gabrieli-v0.3.zip').read_bytes()
digest=hashlib.file_digest(image.open('rb'),'sha256').hexdigest()
(out/(image.name+'.sha256')).write_text(digest+'  '+image.name+'\n')
print('System v0.3 filesystem and embedded animation verified.')
