"""Stage the pinned core ART APEX set for a readonly, RAM-data runtime diagnostic."""
import hashlib
import json
import re
import shutil
import subprocess


def augment(system, product, root, work, copy, readelf, host):
    packages = ('com.android.art.debug', 'com.android.runtime', 'com.android.i18n',
                'com.android.tzdata', 'com.android.vndk.current')
    apex_files = []
    for name in packages:
        source = product/'system/apex'/name
        if not (source/'apex_manifest.pb').is_file():
            raise SystemExit('Missing source-built flattened APEX: '+name)
        destination = system/'apex'/name
        shutil.copytree(source, destination, symlinks=True)
        apex_files.extend(p for p in destination.rglob('*') if p.is_file() and not p.is_symlink())
    provided = {p.name for p in apex_files if p.name.endswith('.so')}
    core = {'libc.so', 'libm.so', 'libdl.so', 'libdl_android.so'}
    needed = set()
    for path in apex_files:
        with path.open('rb') as stream:
            header = stream.read(20)
        if header[:4] != b'\x7fELF':
            continue
        if header[4:6] != b'\x01\x01' or int.from_bytes(header[18:20], 'little') != 40:
            raise SystemExit('Expected Cortex-A7 ARM32 APEX content: '+str(path))
        if path.is_relative_to(system/'apex/com.android.vndk.current'):
            continue  # Provider package; no VNDK graphics/media code is invoked by this probe.
        dynamic = subprocess.check_output([str(readelf), '-d', str(path)], text=True)
        needed.update(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
    defaults = [copy('bin/bootstrap/linkerconfig'), copy('bin/getprop'), copy('bin/setprop'),
                copy('lib/libartpalette-system.so')]
    for name in sorted(needed-provided-core):
        if '/' in name:
            raise SystemExit('Unexpected APEX dependency path: '+name)
        source = product/'system/lib'/name
        if not source.is_file():
            candidates = [p for p in (product/'system/apex').glob('*/lib/'+name) if p.is_file()]
            if not candidates or len({hashlib.sha256(p.read_bytes()).hexdigest() for p in candidates}) != 1:
                raise SystemExit('Missing or ambiguous source-built APEX dependency: '+name)
            source = candidates[0]
        defaults.append(copy('lib/'+name, source))
    (system/'framework').mkdir(exist_ok=True)
    probe = root/'outputs/android12-art-probe/runtime-probe.jar'
    shutil.copyfile(probe, system/'framework/rayo-runtime-probe.jar')
    (system/'etc/init').mkdir(parents=True, exist_ok=True)
    for directory in ('product', 'system_ext', 'vendor'):
        (system/directory).mkdir(exist_ok=True)
    (system/'etc/public.libraries.txt').write_text(
        'libc.so\nlibdl.so\nlibm.so\nliblog.so\nlibz.so\nlibnativehelper.so\n')
    shutil.copyfile(product/'system/etc/sanitizer.libraries.txt', system/'etc/sanitizer.libraries.txt')
    logger = system/'lib/librayo12_diaglog.so'
    shutil.copyfile(root/'outputs/android12-probes/librayo12_diaglog.so', logger)
    defaults.append(logger)
    (system/'bin/linker').unlink()
    (system/'bin/linker').symlink_to('/apex/com.android.runtime/bin/linker')
    # Match the product's normal Bionic aliases. NativeLoader preloads public
    # libraries through the system namespace, not the bootstrap search path.
    for name in ('libc.so', 'libm.so', 'libdl.so', 'libdl_android.so'):
        (system/'lib'/name).symlink_to('/apex/com.android.runtime/lib/bionic/'+name)
    shutil.copyfile(root/'work/android12/art/init.rayo12.art.rc', system/'etc/init/rayo12.art.rc')
    return defaults, {'packages': list(packages), 'system_bytes': 192*1024*1024,
        'probe_sha256': hashlib.sha256(probe.read_bytes()).hexdigest(),
        'dalvikvm_md5': hashlib.md5((system/'apex/com.android.art.debug/bin/dalvikvm32').read_bytes()).hexdigest(),
        'scope': 'Core ART/Java only; readonly physical userdata and RAM-backed data; no framework/apps'}


def finish(system, work, host):
    # A partial diagnostic root needs a partial provider inventory. Full product
    # system linker config is normally installed by the disabled image target.
    names = {p.name for p in (system/'lib').rglob('*.so') if p.is_file()}
    config = {'provideLibs': sorted(names), 'requireLibs': [
        'libandroidicu.so', 'libdexfile.so', 'libdexfiled.so', 'libicu.so',
        'libjdwp.so', 'libnativebridge.so', 'libnativehelper.so', 'libnativeloader.so',
        'libsigchain.so', 'libicui18n.so', 'libicuuc.so']}
    source = work/'art-linker.config.json'
    source.write_text(json.dumps(config, indent=2)+'\n')
    subprocess.run([str(host/'conv_linker_config'), 'proto', '--source', str(source),
                    '--output', str(system/'etc/linker.config.pb')], check=True)
