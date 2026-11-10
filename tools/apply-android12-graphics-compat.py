#!/usr/bin/env python3
"""Opt this board into ION client targets instead of legacy process-local HW_FB allocation."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    args = parser.parse_args()
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
    print('Opt-in ION client targets applied; allocation validation and factory HAL remain unchanged.')


if __name__ == '__main__':
    main()
