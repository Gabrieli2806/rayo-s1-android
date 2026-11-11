#!/usr/bin/env python3
"""Allow a board-scoped, explicitly non-FIPS crypto diagnostic/backend build."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    path = args.source/'external/boringssl/Android.bp'
    text = path.read_text()
    anchor = 'name: "libcrypto_static",\n    visibility: [\n'
    entry = '        "//device/canon/rayos1_12_lab", // Legacy-kernel non-FIPS board backend.\n'
    if anchor+entry not in text:
        if text.count(anchor) != 1:
            raise SystemExit('Unexpected pinned static BoringSSL visibility')
        text = text.replace(anchor, anchor+entry)
    path.write_text(text)
    print('Explicit board permission for upstream non-FIPS crypto; platform FIPS module unchanged.')


if __name__ == '__main__':
    main()
