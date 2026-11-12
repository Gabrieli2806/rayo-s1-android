#!/usr/bin/env python3
"""Skip the unconditional one-second HIDL lookup delay under a board property.

Products below the full-Treble shipping level do not enforce the VINTF
manifest, and libhidl then sleeps one second in every getService() call before
looking a HAL up. Device run 20261111-211628 showed about seven such lookups
(configstore, mapper 4.0/3.0/2.0, ...) on the first frame of every graphical
process, i.e. the multi-second cold starts. With ro.rayo.hidl.no_legacy_wait
the lookup itself is unchanged; only the fixed delay is removed. Every HAL that
is actually registered on the board must be declared in the packaged manifest so
that late-starting services are still awaited by the normal hwbinder path.
"""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source', type=Path, required=True)
args = parser.parse_args()
path = args.source/'system/libhidl/transport/ServiceManagement.cpp'
text = path.read_text()
before = '''              "condition is possible here.");
        sleep(1);
    }
'''
after = '''              "condition is possible here.");
        static const bool rayoNoLegacyWait =
                ::android::base::GetBoolProperty("ro.rayo.hidl.no_legacy_wait", false);
        if (!rayoNoLegacyWait) sleep(1);
    }
'''
if after not in text:
    if text.count(before) != 1:
        raise SystemExit('Pinned HIDL service lookup differs')
    text = text.replace(before, after)
if '#include <android-base/properties.h>' not in text:
    raise SystemExit('Expected libbase properties in the HIDL transport')
path.write_text(text)
print('Opt-in removal of the fixed HIDL lookup delay applied; default behavior unchanged.')
