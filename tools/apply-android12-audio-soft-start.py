#!/usr/bin/env python3
"""Opt-in PCM-only fade at each legacy stream startup; preserve frame counts/timestamps."""
import argparse
from pathlib import Path
import shutil
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--source',type=Path,required=True);args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
base=args.source/'hardware/interfaces/audio/core/all-versions/default'
shutil.copyfile(root/'work/android12/audio/RayoPcmSoftStart.h',base/'RayoPcmSoftStart.h')
path=base/'include/core/default/StreamOut.h';text=path.read_text()
anchor='    std::atomic<bool> mStopWriteThread;'
addition='\n    std::atomic<uint32_t> mLegacyRampGeneration{0};'
if anchor+addition not in text:
    if text.count(anchor)!=1:raise SystemExit('Pinned output stream fields differ')
    text=text.replace(anchor,anchor+addition)
path.write_text(text)
path=base/'StreamOut.cpp';text=path.read_text()
if '#include "RayoPcmSoftStart.h"' not in text:text='#include "RayoPcmSoftStart.h"\n'+text
if 'mRampGeneration(rampGeneration)' not in text:
    before='StreamOut::StatusMQ* statusMQ, EventFlag* efGroup)'
    if text.count(before)!=1:raise SystemExit('Pinned write-thread signature differs')
    text=text.replace(before,'StreamOut::StatusMQ* statusMQ, EventFlag* efGroup, std::atomic<uint32_t>* rampGeneration)')
    text=text.replace('          mBuffer(nullptr) {}','          mBuffer(nullptr), mRampGeneration(rampGeneration) {}')
    before='    std::unique_ptr<uint8_t[]> mBuffer;'
    if text.count(before)!=1:raise SystemExit('Pinned write-thread buffer differs')
    text=text.replace(before,before+'''
    std::atomic<uint32_t>* mRampGeneration;
    uint32_t mSeenRampGeneration=UINT32_MAX;
    uint64_t mRampFrames=0;
    const bool mSoftStart=android::base::GetBoolProperty("ro.rayo.audio.pcm_soft_start", false);
''')
    before='        ssize_t writeResult = mStream->write(mStream, &mBuffer[0], availToRead);'
    after='''        const bool pcmRamp=mSoftStart &&
                mStream->common.get_format(&mStream->common)==AUDIO_FORMAT_PCM_16_BIT;
        unsigned channels=0;
        if(pcmRamp){
            const uint32_t generation=mRampGeneration->load(std::memory_order_relaxed);
            if(generation!=mSeenRampGeneration){mSeenRampGeneration=generation;mRampFrames=0;}
            channels=audio_channel_count_from_out_mask(mStream->common.get_channels(&mStream->common));
            rayoPcm16SoftStart(&mBuffer[0],availToRead,channels,
                              mStream->common.get_sample_rate(&mStream->common),mRampFrames);
        }
        ssize_t writeResult = mStream->write(mStream, &mBuffer[0], availToRead);
        if(pcmRamp && channels && writeResult>0){
            mRampFrames+=static_cast<size_t>(writeResult)/(channels*sizeof(int16_t));
        }'''
    if text.count(before)!=1:raise SystemExit('Pinned hardware write differs')
    text=text.replace(before,after)
    before='tempStatusMQ.get(), tempElfGroup.get());'
    if text.count(before)!=1:raise SystemExit('Pinned write-thread creation differs')
    text=text.replace(before,'tempStatusMQ.get(), tempElfGroup.get(), &mLegacyRampGeneration);')
    before='Return<Result> StreamOut::standby() {\n'
    text=text.replace(before,before+'    mLegacyRampGeneration.fetch_add(1, std::memory_order_relaxed);\n')
path.write_text(text)
print('Board-scoped PCM16 100 ms soft start; no extra frames, latency or timestamp offset.')
