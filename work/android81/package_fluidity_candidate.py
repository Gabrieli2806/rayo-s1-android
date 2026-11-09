from pathlib import Path
import shutil
import difflib
import hashlib
import zipfile
import json

root=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q')
work=root/'work/android81'
candidate=work/'fluidity-v01'
stage=work/'fluidity-delivery-v01'
stage.mkdir(exist_ok=True)
def copy(source,relative):
    dest=stage/relative
    dest.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(source,dest)
copy(root/'outputs/rayo-s1/android81/mejoras-fluidez-pendientes.md','LEEME.md')
copy(candidate/'hwcomposer.rayo_fluidity.so','bin/hwcomposer.rayo_fluidity.so')
for name in ('Generic.kl','rk29-keypad.kl'):
    copy(candidate/'keylayout'/name,'keylayout/'+name)
for name in ('hwc_adapter.cpp','rk_hwc_abi.h','Android.mk'):
    copy(candidate/'device/canon/rayos1_fluidity'/name,'sources/'+name)
# Preserve the source header's original copyright/license notice.
reference=(work/'hwcomposer-rockchip-nougat.h').read_text()
notice=reference[:reference.index('#ifndef')]
abi=stage/'sources/rk_hwc_abi.h'
abi.write_text(notice+abi.read_text())
copy(work/'hwcomposer-rockchip-nougat.h','references/hwcomposer-rockchip-nougat.h')
copy(work/'hwc-nougat-reference-commit.txt','references/commit.txt')
copy(work/'profile-fluidity-settings.sh','scripts/profile-fluidity-settings.sh')
copy(work/'measure-fluidity.sh','scripts/measure-fluidity.sh')
for source,dest in (
    ('fluidity-original-keymap-validation.txt','validation/original-keymap.txt'),
    ('fluidity-candidate-keymap-validation.txt','validation/candidate-keymaps.txt'),
    ('build-fluidity.log','validation/build.txt')):
    copy(work/source,dest)
audit=json.loads((work/'input-keymap-compatibility.json').read_text())
(stage/'validation/keymap-compatibility.json').write_text(json.dumps(
    [row for row in audit if row['file'] in ('Generic.kl','rk29-keypad.kl')],indent=2))
before=(work/'device/canon/rayos1/hwc_adapter.cpp').read_text().splitlines(True)
after=(candidate/'device/canon/rayos1_fluidity/hwc_adapter.cpp').read_text().splitlines(True)
(stage/'sources/graphics-changes.diff').write_text(''.join(difflib.unified_diff(
    before,after,fromfile='baseline/hwc_adapter.cpp',tofile='candidate/hwc_adapter.cpp')))
manifest=[]
for file in sorted(stage.rglob('*')):
    if file.is_file() and file.name!='SHA256SUMS.txt':
        manifest.append(hashlib.sha256(file.read_bytes()).hexdigest()+'  '+file.relative_to(stage).as_posix())
(stage/'SHA256SUMS.txt').write_text('\n'.join(manifest)+'\n')
output=root/'outputs/rayo-s1/android81/Rayo-Android81-Fluidez-v0.1.zip'
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(stage.rglob('*')):
        if file.is_file(): archive.write(file,file.relative_to(stage))
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
    assert len([n for n in archive.namelist() if n.startswith('keylayout/')])==2
    for line in archive.read('SHA256SUMS.txt').decode().splitlines():
        digest,name=line.split('  ',1)
        assert hashlib.sha256(archive.read(name)).hexdigest()==digest,name
print('Verified delivery package:',output,'bytes:',output.stat().st_size)
