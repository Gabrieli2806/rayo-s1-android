#!/usr/bin/env python3
"""Select a source OMX facade only under the explicit legacy-media board property."""
import argparse
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source',type=Path,required=True)
args=parser.parse_args()
path=args.source/'frameworks/av/media/libstagefright/omx/OMXStore.cpp'
text=path.read_text()
before='    void *libHandle = android_load_sphal_library(libname, RTLD_NOW);'
after='''    const bool legacyFacade = !strcmp(libname, "libstagefrighthw.so") &&
            ::android::base::GetBoolProperty("ro.rayo.media.legacy_omx", false);
    void *libHandle = legacyFacade
            ? dlopen("/system/lib/librayo12_omx_plugin.so", RTLD_NOW)
            : android_load_sphal_library(libname, RTLD_NOW);'''
if after not in text:
    if text.count(before)!=1:raise SystemExit('Pinned OMXStore loader differs')
    text=text.replace(before,after)
path.write_text(text)
print('Opt-in source OMX C facade selected; default plugin loader unchanged.')
