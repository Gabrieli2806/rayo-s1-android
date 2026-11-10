#!/usr/bin/env python3
"""Add an opt-in old-kernel FunctionFS transport to the pinned Android 12 ADB."""
import argparse
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    directory = args.source/'packages/modules/adb/daemon'
    path = directory/'usb.cpp'
    text = path.read_text()
    marker = '#include "rayo_usb_sync.h"'
    if marker not in text:
        anchor = 'using android::base::StringPrintf;\n'
        if text.count(anchor) != 1:
            raise SystemExit('Unexpected pinned ADB USB source')
        text = text.replace(anchor, anchor+'\n'+marker+'\n')
    old = '''        transport->SetConnection(std::make_unique<UsbFfsConnection>(
                std::move(control), std::move(bulk_out), std::move(bulk_in),
                std::move(destruction_notifier)));'''
    new = '''        if (android::base::GetBoolProperty("ro.rayo.adb.legacy_ffs", false)) {
            transport->SetConnection(std::make_shared<BlockingConnectionAdapter>(
                    std::make_unique<RayoUsbSyncConnection>(
                            std::move(control), std::move(bulk_out), std::move(bulk_in),
                            std::move(destruction_notifier))));
        } else {
            transport->SetConnection(std::make_unique<UsbFfsConnection>(
                    std::move(control), std::move(bulk_out), std::move(bulk_in),
                    std::move(destruction_notifier)));
        }'''
    if new not in text:
        if text.count(old) != 1:
            raise SystemExit('Unexpected pinned ADB connection construction')
        text = text.replace(old, new)
    shutil.copyfile(root/'work/android12/adb/rayo_usb_sync.h', directory/'rayo_usb_sync.h')
    path.write_text(text)
    print('Opt-in synchronous FunctionFS transport applied; upstream AIO remains the default.')


if __name__ == '__main__':
    main()
