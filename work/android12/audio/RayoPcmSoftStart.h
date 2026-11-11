// Scope: signed PCM16, one/two channels, first 100 ms only. No added frames or gain above unity.
#pragma once
#include <cstddef>
#include <cstdint>
inline void rayoPcm16SoftStart(void* data,size_t bytes,unsigned channels,unsigned rate,uint64_t start){
    if(!data || !channels || channels>2 || rate<8000 || rate>48000 || bytes%(channels*sizeof(int16_t)))return;
    const uint64_t ramp=rate/10;
    if(start>=ramp)return;
    auto samples=static_cast<int16_t*>(data);
    const size_t frames=bytes/(channels*sizeof(int16_t));
    for(size_t frame=0;frame<frames && start+frame<ramp;frame++){
        for(unsigned channel=0;channel<channels;channel++){
            const size_t index=frame*channels+channel;
            samples[index]=static_cast<int16_t>(static_cast<int32_t>(samples[index])*
                                               static_cast<int32_t>(start+frame)/static_cast<int32_t>(ramp));
        }
    }
}
