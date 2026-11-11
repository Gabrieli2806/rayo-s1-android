#!/usr/bin/env python3
"""Apply dedicated Binder32 build fixes and measured legacy libdl ABI exports."""
from pathlib import Path
import argparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    args = parser.parse_args()
    path = args.source/'frameworks/native/libs/binder/IPCThreadState.cpp'
    text = path.read_text()
    original = text
    changes = {
        '(void*)btd->data_size': 'reinterpret_cast<void*>(static_cast<uintptr_t>(btd->data_size))',
        '(void*)btd->offsets_size': 'reinterpret_cast<void*>(static_cast<uintptr_t>(btd->offsets_size))',
        '(const void*)bwr.write_buffer': 'reinterpret_cast<const void*>(static_cast<uintptr_t>(bwr.write_buffer))',
    }
    for before, after in changes.items():
        if after in text:
            continue
        if text.count(before) != 1:
            raise SystemExit('Unexpected source context; refusing a broad replacement: '+before)
        text = text.replace(before, after)
    if text != original:
        path.write_text(text)
    path = args.source/'frameworks/native/libs/binder/Parcel.cpp'
    text = path.read_text()
    before = '(void*)OBJS[i]'
    after = 'reinterpret_cast<void*>(static_cast<uintptr_t>(OBJS[i]))'
    if after not in text:
        if text.count(before) != 1:
            raise SystemExit('Unexpected Parcel logging context')
        path.write_text(text.replace(before, after))
    # This source workspace is dedicated to the Binder32 Rayo product. Keep
    # upstream maps intact and select explicit protocol-7 export maps instead.
    binder = args.source/'frameworks/native/libs/binder'
    for suffix in ('map', 'vendor.map'):
        source = binder/('libbinder.arm32.'+suffix)
        target = binder/('libbinder.arm32.rayo32.'+suffix)
        mapped = source.read_text()
        replacements = {
            '_ZN7android14IPCThreadState10freeBufferEPNS_6ParcelEPKhjPKyj;':
            '_ZN7android14IPCThreadState10freeBufferEPNS_6ParcelEPKhjPKjj;',
            '_ZN7android6Parcel19ipcSetDataReferenceEPKhjPKyjPFvPS0_S2_jS4_jE;':
            '_ZN7android6Parcel19ipcSetDataReferenceEPKhjPKjjPFvPS0_S2_jS4_jE;',
            # The uint64 offset logging instantiation does not exist with
            # Binder32; the uint32 (Ij) instantiation is already exported.
            '    _ZN7androidlsIyEERNS_10TextOutputES2_RKT_;\n': '',
        }
        for before, after in replacements.items():
            if mapped.count(before) != 1:
                raise SystemExit('Unexpected Binder export map: '+str(source))
            mapped = mapped.replace(before, after)
        if not target.exists() or target.read_text() != mapped:
            target.write_text(mapped)
    bp = binder/'Android.bp'
    text = bp.read_text()
    original = text
    for suffix in ('map', 'vendor.map'):
        text = text.replace('"libbinder.arm32.'+suffix+'"',
                            '"libbinder.arm32.rayo32.'+suffix+'"')
    if text != original:
        bp.write_text(text)
    libdl = args.source/'bionic/libdl'
    path = libdl/'libdl.cpp'
    text = path.read_text()
    marker = '// Rayo legacy graphics namespace ABI compatibility.'
    if marker not in text:
        end = '} // extern "C"'
        if text.count(end) != 1:
            raise SystemExit('Unexpected libdl source context')
        compat = '''// Rayo legacy graphics namespace ABI compatibility.
__attribute__((__weak__, visibility("default")))
struct android_namespace_t* __loader_android_get_exported_namespace(const char* name);
__attribute__((__weak__, visibility("default")))
struct android_namespace_t* __loader_android_create_namespace(
    const char*, const char*, const char*, uint64_t, const char*,
    struct android_namespace_t*, const void*);

__attribute__((__weak__))
struct android_namespace_t* android_get_exported_namespace(const char* name) {
  return __loader_android_get_exported_namespace(name);
}
__attribute__((__weak__))
struct android_namespace_t* android_create_namespace(
    const char* name, const char* ld_library_path, const char* default_library_path,
    uint64_t type, const char* permitted_when_isolated_path, struct android_namespace_t* parent) {
  return __loader_android_create_namespace(name, ld_library_path, default_library_path,
      type, permitted_when_isolated_path, parent, __builtin_return_address(0));
}

'''
        path.write_text(text.replace(end, compat+end))
    text = path.read_text()
    if 'bool android_link_namespaces(' not in text:
        end = '} // extern "C"'
        extra = '''// Additional measured legacy libnativeloader namespace imports.
__attribute__((__weak__, visibility("default")))
bool __loader_android_init_anonymous_namespace(const char*, const char*);
__attribute__((__weak__, visibility("default")))
bool __loader_android_link_namespaces(struct android_namespace_t*, struct android_namespace_t*, const char*);
__attribute__((__weak__))
bool android_init_anonymous_namespace(const char* shared_libs, const char* search_path) {
  return __loader_android_init_anonymous_namespace(shared_libs, search_path);
}
__attribute__((__weak__))
bool android_link_namespaces(struct android_namespace_t* from, struct android_namespace_t* to,
                            const char* shared_libs) {
  return __loader_android_link_namespaces(from, to, shared_libs);
}

'''
        path.write_text(text.replace(end, extra+end))
    path = libdl/'libdl.map.txt'
    text = path.read_text()
    before = 'LIBC_PLATFORM {\n  global:\n'
    previous = before+'    android_create_namespace;\n    android_get_exported_namespace;\n'
    after = previous+'    android_init_anonymous_namespace;\n    android_link_namespaces;\n'
    if after not in text:
        if text.count(before) != 1:
            raise SystemExit('Unexpected libdl export map')
        path.write_text(text.replace(previous if previous in text else before, after))
    print('Dedicated Binder32 casts/export maps checked. Device protocol remains 7.')
    print('Four LIBC_PLATFORM namespace proxies restored for measured legacy graphics imports.')
    # This is an init bootstrap, and belongs to init's package. That package
    # already has upstream permission to use non-FIPS static crypto for boot.
    path = args.source/'system/core/init/Android.bp'
    text = path.read_text()
    if 'name: "rayo12_early_init_bootstrap"' not in text:
        path.write_text(text+'''
// Rayo stage-one diagnostic bootstrap. No installed image is generated here.
cc_binary {
    name: "rayo12_early_init_bootstrap",
    srcs: [":rayo12_early_init_source"],
    static_executable: true,
    stl: "none",
    cflags: ["-Wall", "-Wextra", "-Werror"],
    static_libs: ["libcrypto_static", "libc", "libm", "libdl"],
}
''')
    text = path.read_text()
    corrected = text.replace('"//device/canon/rayos1_12_lab:rayo12_early_init_source"', '":rayo12_early_init_source"')
    corrected = corrected.replace('static_libs: ["libcrypto_static"],', 'static_libs: ["libcrypto_static", "libc", "libm", "libdl"],')
    if corrected != text:
        path.write_text(corrected)
    path = args.source/'system/core/init/reboot_utils.cpp'
    framework_module = '''
// Dedicated bounded framework trial, with a private file-backed /data.
cc_binary {
    name: "rayo12_framework_init_bootstrap",
    srcs: [":rayo12_framework_init_source"],
    static_executable: true,
    stl: "none",
    cflags: ["-Wall", "-Wextra", "-Werror"],
    static_libs: ["libcrypto_static", "libc", "libm", "libdl"],
}
'''
    init_bp = args.source/'system/core/init/Android.bp'
    init_text = init_bp.read_text()
    if 'name: "rayo12_framework_init_bootstrap"' not in init_text:
        init_bp.write_text(init_text+framework_module)
    text = path.read_text()
    before = 'start_pos += sizeof(kRebootTargetStringPattern) - 1;'
    after = 'start_pos += kRebootTargetStringPattern.size();'
    if after not in text:
        if text.count(before) != 1:
            raise SystemExit('Unexpected init fatal-reboot parameter parser')
        path.write_text(text.replace(before, after))
    text = path.read_text()
    # This unit's bootloader replaces the image-header command line. The
    # diagnostic cannot rely on a header argument to prevent Loader entry.
    old_target = 'static std::string init_fatal_reboot_target = "bootloader";'
    new_target = 'static std::string init_fatal_reboot_target = "normal";'
    if new_target not in text:
        if text.count(old_target) != 1:
            raise SystemExit('Unexpected init fatal-reboot default')
        path.write_text(text.replace(old_target, new_target))


if __name__ == '__main__':
    main()
