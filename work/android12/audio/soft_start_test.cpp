#include "RayoPcmSoftStart.h"
#include <cassert>
#include <vector>
int main(){
    std::vector<int16_t> stereo(4800*2,32000);
    rayoPcm16SoftStart(stereo.data(),stereo.size()*2,2,48000,0);
    assert(stereo[0]==0 && stereo[1]==0);
    assert(stereo[4800]==16000 && stereo[4801]==16000);
    for(size_t i=0;i<stereo.size();i+=2){assert(stereo[i]>=0 && stereo[i]<=32000 && stereo[i]==stereo[i+1]);}
    int16_t negative[2]={-32768,-32768};rayoPcm16SoftStart(negative,sizeof(negative),2,48000,2400);
    assert(negative[0]==-16384 && negative[1]==-16384);
    int16_t final[2]={32767,-32768};rayoPcm16SoftStart(final,sizeof(final),2,48000,4800);
    assert(final[0]==32767 && final[1]==-32768);
    rayoPcm16SoftStart(final,3,2,48000,0);assert(final[0]==32767 && final[1]==-32768);
    rayoPcm16SoftStart(final,sizeof(final),3,48000,0);assert(final[0]==32767);
    rayoPcm16SoftStart(final,sizeof(final),2,96000,0);assert(final[0]==32767);
    rayoPcm16SoftStart(nullptr,0,2,48000,0);
}
