#!/usr/bin/env python3
"""Opt the Rayo Bluetooth vendor C ABI into the isolated legacy namespace."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    directory = args.source/'hardware/interfaces/bluetooth/1.0/default'
    path = directory/'vendor_interface.cc'
    text = path.read_text()
    include = '#include <cutils/properties.h>\n#include <vndksupport/linker.h>\n'
    if include not in text:
        anchor = '#include "vendor_interface.h"\n'
        if text.count(anchor) != 1:
            raise SystemExit('Unexpected pinned Bluetooth includes')
        text = text.replace(anchor, anchor+'\n'+include)
    before = '  lib_handle_ = dlopen(VENDOR_LIBRARY_NAME, RTLD_NOW);'
    after = '''  // Keep factory C++ dependencies separate from the Android 12 HAL runtime.
  if (property_get_bool("ro.rayo.bluetooth.legacy_vendor_sphal", false)) {
    lib_handle_ = android_load_sphal_library("/vendor/lib/libbt-vendor.so", RTLD_NOW);
  } else {
    lib_handle_ = dlopen(VENDOR_LIBRARY_NAME, RTLD_NOW);
  }'''
    if after not in text:
        if text.count(before) != 1:
            raise SystemExit('Unexpected pinned Bluetooth vendor loader')
        text = text.replace(before, after)
    path.write_text(text)
    path = directory/'Android.bp'
    text = path.read_text()
    anchor = '    name: "android.hardware.bluetooth@1.0-impl",'
    start = text.index(anchor)
    end = text.index('\n}\n', start)
    block = text[start:end]
    if '"libvndksupport"' not in block:
        before = '        "libutils",'
        if block.count(before) != 1:
            raise SystemExit('Unexpected Bluetooth implementation dependencies')
        block = block.replace(before, before+'\n        "libvndksupport",')
        text = text[:start]+block+text[end:]
    path.write_text(text)
    print('Opt-in legacy Bluetooth vendor namespace prepared; default behavior unchanged.')


if __name__ == '__main__':
    main()
