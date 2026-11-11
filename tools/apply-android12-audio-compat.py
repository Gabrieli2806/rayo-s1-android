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
    for filename, method, parameter in (
            ('StreamIn.cpp', 'updateSinkMetadata', 'const SinkMetadata& sinkMetadata'),
            ('StreamOut.cpp', 'updateSourceMetadata', 'const SourceMetadata& sourceMetadata')):
        path = args.source/'hardware/interfaces/audio/core/all-versions/default'/filename
        text = path.read_text()
        include = '#include <android-base/properties.h>\n'
        if include not in text:
            index = text.find('#include ')
            if index < 0:
                raise SystemExit('Missing pinned audio implementation includes')
            text = text[:index]+include+text[index:]
        klass = filename.removesuffix('.cpp')
        for return_type, unsupported in (('void', 'Void()'), ('Result', 'Result::NOT_SUPPORTED')):
            anchor = f'Return<{return_type}> {klass}::{method}({parameter}) {{\n'
            addition = ('    // The factory stream ends before these optional metadata slots.\n'
                        '    if (android::base::GetBoolProperty("ro.rayo.audio.legacy_stream_abi", false)) {\n'
                        f'        return {unsupported};\n'
                        '    }\n')
            if anchor+addition not in text:
                if text.count(anchor) != 1:
                    raise SystemExit('Unexpected pinned metadata dispatch: '+method)
                text = text.replace(anchor, anchor+addition)
        path.write_text(text)
    path = args.source/'hardware/interfaces/audio/core/all-versions/default/StreamOut.cpp'
    text = path.read_text()
    anchor = 'Return<Result> StreamOut::setEventCallback(const sp<IStreamOutEventCallback>& callback) {\n'
    addition = '''    // The original stream structure predates this appended function slot.
    // Do not even read it for a pre-3.2 device; adjacent legacy memory is not
    // a capability indication and may look like a non-null function pointer.
    if (mDevice->version() < AUDIO_DEVICE_API_VERSION_3_2) return Result::NOT_SUPPORTED;
'''
    if addition not in text:
        if text.count(anchor) != 1:
            raise SystemExit('Unexpected pinned stream event callback')
        text = text.replace(anchor, anchor+addition)
    path.write_text(text)
    print('Audio HAL lookup respects the null sentinel; this does not provide an audio HAL.')


if __name__ == '__main__':
    main()
