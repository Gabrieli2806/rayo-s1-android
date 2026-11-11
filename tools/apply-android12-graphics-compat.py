#!/usr/bin/env python3
"""Opt this board into ION client targets instead of legacy process-local HW_FB allocation."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    args = parser.parse_args()
    path = args.source/'hardware/interfaces/graphics/composer/2.1/utils/hwc2on1adapter/HWC2On1Adapter.cpp'
    text = path.read_text()
    include = '#include <cutils/properties.h>\n'
    if include not in text:
        text = text.replace('#include <hardware/hwcomposer.h>', include+'#include <hardware/hwcomposer.h>')
    before = 'if ((result == 0) && (backgroundColorSupported == 1)) {'
    after = '''if ((result == 0) && (backgroundColorSupported == 1) &&
                !property_get_bool("ro.rayo.graphics.no_hwc_background", false)) {'''
    if after not in text:
        if text.count(before) != 1:
            raise SystemExit('Unexpected pinned HWC background capability query')
        text = text.replace(before, after)
    path.write_text(text)
    path = args.source/'frameworks/native/services/surfaceflinger/DisplayHardware/FramebufferSurface.cpp'
    text = path.read_text()
    include = '#include <android-base/properties.h>\n'
    if include not in text:
        anchor = '#include "FramebufferSurface.h"\n'
        if text.count(anchor) != 1:
            raise SystemExit('Unexpected pinned FramebufferSurface includes')
        text = text.replace(anchor, anchor+'\n'+include)
    before = '''    mConsumer->setConsumerUsageBits(GRALLOC_USAGE_HW_FB |
                                       GRALLOC_USAGE_HW_RENDER |
                                       GRALLOC_USAGE_HW_COMPOSER);'''
    after = '''    // The Rayo factory HW_FB allocation path cannot supply client targets
    // through the separate allocator service. Checked ION render/composer
    // allocation works. Keep upstream behavior unless explicitly selected.
    uint64_t clientUsage = GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_COMPOSER;
    if (!android::base::GetBoolProperty("ro.rayo.graphics.ion_client_target", false)) {
        clientUsage |= GRALLOC_USAGE_HW_FB;
    }
    mConsumer->setConsumerUsageBits(clientUsage);'''
    if after not in text:
        if text.count(before) != 1:
            raise SystemExit('Unexpected pinned framebuffer client-target usage')
        text = text.replace(before, after)
    path.write_text(text)
    path = args.source/'system/linkerconfig/contents/section/legacy.cc'
    text = path.read_text()
    include = '#include <android-base/properties.h>\n'
    if include not in text:
        text = text.replace('#include "linkerconfig/sectionbuilder.h"', include+'\n#include "linkerconfig/sectionbuilder.h"')
    anchor = '  namespaces.emplace_back(BuildSystemDefaultNamespace(ctx));'
    addition = '''
  // This pre-Treble board still needs an isolated legacy graphics closure.
  if (android::base::GetBoolProperty("ro.rayo.graphics.legacy_sphal", false)) {
    namespaces.emplace_back(BuildSphalNamespace(ctx));
  }
'''
    if addition not in text:
        if text.count(anchor) != 1:
            raise SystemExit('Unexpected pinned legacy section layout')
        text = text.replace(anchor, anchor+'\n'+addition)
    path.write_text(text)
    path = args.source/'system/linkerconfig/contents/namespace/sphal.cc'
    text = path.read_text()
    include = '#include <android-base/properties.h>\n'
    if include not in text:
        text = text.replace('#include "linkerconfig/namespacebuilder.h"', include+'\n#include "linkerconfig/namespacebuilder.h"')
    anchor = '  ns.AddSearchPath("/odm/${LIB}");'
    addition = '''  // Keep the verified legacy C++ closure separate from Android 12 C++.
  if (android::base::GetBoolProperty("ro.rayo.graphics.legacy_sphal", false)) {
    ns.AddSearchPath("/vendor/${LIB}/legacy");
    ns.AddSearchPath("/vendor/${LIB}");
    ns.AddSearchPath("/vendor/${LIB}/egl");
    ns.AddSearchPath("/vendor/${LIB}/hw");
    ns.AddPermittedPath("/vendor/${LIB}");
    ns.AddPermittedPath("/system/vendor/${LIB}");
    ns.AddPermittedPath("/system/${LIB}/hw");
    ns.GetLink(ctx.GetSystemNamespaceName()).AddSharedLib(
        "libc.so:libm.so:libdl.so:libdl_android.so:libvndksupport.so");
    return ns;
  }

'''
    if addition not in text:
        if text.count(anchor) != 1:
            raise SystemExit('Unexpected pinned sphal namespace layout')
        text = text.replace(anchor, addition+anchor)
    path.write_text(text)
    print('Opt-in ION client targets applied; allocation validation and factory HAL remain unchanged.')


if __name__ == '__main__':
    main()
