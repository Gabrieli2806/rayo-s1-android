"""Stage a private legacy graphics closure for the init-only Android 12 root."""
import hashlib
import json
import posixpath
import re
import shutil
import subprocess
import tarfile


def augment(system, product, native, root, work, copy, readelf, services=False, surfaceflinger=False, audio=False, legacy_media=False):
    legacy = system/'vendor/lib/legacy'
    legacy.mkdir(parents=True)
    archive_path = root/'local/device-kit/runtime/rayo81-live-overlay.tar.gz'
    if legacy_media:
        kit = json.loads((root/'local/device-kit/MANIFEST.json').read_text())
        expected = next(item['sha256'] for item in kit['files']
                        if item['path'] == 'runtime/rayo81-live-overlay.tar.gz')
        if hashlib.file_digest(archive_path.open('rb'), 'sha256').hexdigest() != expected:
            raise SystemExit('Legacy media archive differs from the pinned kit')
    archive = tarfile.open(archive_path)
    members = {m.name.rstrip('/'): m for m in archive}
    records = []
    core = {'libc.so', 'libm.so', 'libdl.so', 'libdl_android.so', 'libvndksupport.so'}

    def original(path, seen=None):
        seen = set() if seen is None else seen
        if path in seen:
            raise SystemExit('Cyclic legacy archive link: '+path)
        seen.add(path)
        member = members.get(path)
        if member is not None and (member.issym() or member.islnk()):
            target = member.linkname
            if target.startswith('/system/'):
                target = 'rayo-live/'+target[len('/system/'):]
            elif target.startswith('/vendor/'):
                target = 'rayo-live/vendor/'+target[len('/vendor/'):]
            elif not member.islnk():
                target = posixpath.normpath(posixpath.join(posixpath.dirname(path), target))
            if not target.startswith('rayo-live/'):
                raise SystemExit('Legacy link escapes the source archive: '+target)
            return original(target, seen)
        if member is not None and member.isfile():
            return archive.extractfile(member).read(), path
        # Only named readonly factory library dumps; never mount or change it.
        relative = path.removeprefix('rayo-live/')
        target = work/('factory-'+relative.replace('/', '_'))
        subprocess.run(['debugfs', '-R', 'dump /'+relative+' '+str(target),
                        str(root/'local/device-kit/original/system.img')],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if not target.is_file():
            raise SystemExit('Missing recorded legacy dependency: '+relative)
        return target.read_bytes(), 'factory-system:'+relative

    def needed(path):
        dynamic = subprocess.check_output([str(readelf), '-d', str(path)], text=True)
        return re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic)

    queue = [
        ('rayo-live/vendor/lib/egl/libGLES_mali.so', system/'vendor/lib/egl/libGLES_mali.so'),
        ('rayo-live/lib/hw/gralloc.rk30board.so', system/'vendor/lib/hw/gralloc.rk30board.so'),
        ('rayo-live/lib/hw/hwcomposer.rk30board.factory.so', system/'lib/hw/hwcomposer.rk30board.factory.so'),
    ]
    if audio:
        queue.append(('rayo-live/lib/hw/audio.primary.rk30board.so',
                      system/'vendor/lib/hw/audio.primary.rk30board.so'))
    if legacy_media:
        # libomxvpu_dec.so dlopen()s librk_vpuapi.so when a component starts; it
        # is not a NEEDED entry, and without it every real decode fails with
        # OMX_ErrorUndefined (owner-reported SmartTube hang, Nov 13 2026).
        for name in ('libOMX_Core.so', 'libomxvpu_dec.so', 'librk_vpuapi.so'):
            queue.append(('rayo-live/lib/'+name, legacy/name))
    staged = set()
    while queue:
        source, destination = queue.pop()
        key = destination.relative_to(system).as_posix()
        if key in staged:
            continue
        data, provenance = original(source)
        if data[:6] != b'\x7fELF\x01\x01' or int.from_bytes(data[18:20], 'little') != 40:
            raise SystemExit('Expected a real ARM32 legacy library: '+provenance)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        destination.chmod(0o644)
        staged.add(key)
        records.append({'path': key, 'source': provenance, 'sha256': hashlib.sha256(data).hexdigest()})
        for name in needed(destination):
            if name not in core:
                if '/' in name:
                    raise SystemExit('Unexpected absolute legacy dependency: '+name)
                queue.append(('rayo-live/lib/'+name, legacy/name))
    archive.close()
    # Both OEM paths must resolve to the one actual Mali inode.
    (system/'lib/egl').symlink_to('../vendor/lib/egl')
    default = [copy('lib/'+name) for name in
               ('libEGL.so', 'libGLESv1_CM.so', 'libGLESv2.so', 'libvndksupport.so')]
    if services:
        default.extend(copy('bin/'+name) for name in ('servicemanager', 'hwservicemanager', 'getprop', 'setprop', 'lshal'))
        default.append(copy('bin/rayo12-graphicbuffer-probe', native/'system/bin/rayo12_graphicbuffer_probe'))
        default.append(copy('bin/rayo12-composer-probe', native/'system/bin/rayo12_composer_probe'))
        for name in ('android.hardware.graphics.allocator@2.0-service',
                     'android.hardware.graphics.composer@2.1-service'):
            default.append(copy('bin/'+name, product/'system/vendor/bin/hw'/name))
        for name in ('android.hardware.graphics.allocator@2.0-impl.so',
                     'android.hardware.graphics.mapper@2.0-impl.so'):
            default.append(copy('lib/hw/'+name, product/'system/vendor/lib/hw'/name))
        default.append(copy('lib/hw/hwcomposer.rayo12.so', product/'system/vendor/lib/hw/hwcomposer.rayo12.so'))
        (system/'etc/vintf').mkdir(parents=True, exist_ok=True)
        (system/'vendor/etc/vintf').mkdir(parents=True, exist_ok=True)
        (system/'vendor/etc/vintf/manifest.xml').write_text('''<manifest version="1.0" type="device" target-level="1">
    <hal format="hidl"><name>android.hardware.graphics.allocator</name><transport>hwbinder</transport><version>2.0</version><interface><name>IAllocator</name><instance>default</instance></interface></hal>
    <hal format="hidl"><name>android.hardware.graphics.composer</name><transport>hwbinder</transport><version>2.1</version><interface><name>IComposer</name><instance>default</instance></interface></hal>
</manifest>
''')
        (system/'etc/init').mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root/'work/android12/stage1/init.rayo12.graphics.rc', system/'etc/init/rayo12.graphics.rc')
        if surfaceflinger:
            if b'ro.rayo.graphics.ion_client_target' not in (product/'system/bin/surfaceflinger').read_bytes():
                raise SystemExit('Rebuild SurfaceFlinger with the opt-in ION client-target adaptation')
            default.extend(copy('bin/'+name) for name in ('surfaceflinger', 'logd', 'logcat', 'dumpsys'))
            default.append(copy('bin/rayo12-surface-probe', native/'system/bin/rayo12_surface_probe'))
            shutil.copyfile(root/'work/android12/stage1/init.rayo12.surface.rc', system/'etc/init/rayo12.surface.rc')
            logger = system/'lib/librayo12_diaglog.so'
            shutil.copyfile(root/'outputs/android12-probes/librayo12_diaglog.so', logger)
            default.append(logger)
    probe = root/'outputs/android12-probes/rayo12-gpu-namespace-probe'
    if probe.read_bytes()[:6] != b'\x7fELF\x01\x01':
        raise SystemExit('Build the recorded ARM32 graphics probe first')
    shutil.copyfile(probe, system/'bin/rayo12-gpu-probe')
    (system/'bin/rayo12-gpu-probe').chmod(0o755)
    (system/'etc').mkdir(exist_ok=True)
    (system/'etc/ld.config.rayo12.txt').write_text('''dir.rayo = /system/bin
[rayo]
additional.namespaces = sphal
namespace.default.isolated = true
namespace.default.search.paths = /system/lib/bootstrap:/system/lib
namespace.default.permitted.paths = /system
namespace.sphal.isolated = true
namespace.sphal.visible = true
namespace.sphal.search.paths = /vendor/lib/legacy:/vendor/lib
namespace.sphal.permitted.paths = /vendor:/system/vendor:/system/lib/hw:/system/lib/egl
namespace.sphal.links = default
namespace.sphal.link.default.shared_libs = libc.so:libm.so:libdl.so:libdl_android.so:libvndksupport.so
''')
    return default, {'mode': 'Explicit HAL service startup; no framework' if services else 'Offscreen EGL only; no framebuffer/HWC open or framework',
                     'services': services,
                     'surfaceflinger': surfaceflinger,
                     'legacy': records, 'probe_sha256': hashlib.sha256(probe.read_bytes()).hexdigest()}
