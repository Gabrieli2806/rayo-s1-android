#!/usr/bin/env python3
"""Handle an absent audio HAL without walking past the upstream version sentinel."""
import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    path = args.source/'frameworks/av/media/libaudiohal/FactoryHalHidl.cpp'
    text = path.read_text()
    before = 'for (auto version = detail::sAudioHALVersions; version != nullptr; ++version) {'
    after = 'for (auto version = detail::sAudioHALVersions; *version != nullptr; ++version) {'
    if after not in text:
        if text.count(before) != 1:
            raise SystemExit('Unexpected pinned audio HAL version iteration')
        text = text.replace(before, after)
    path.write_text(text)
    print('Audio HAL lookup respects the null sentinel; this does not provide an audio HAL.')


if __name__ == '__main__':
    main()
