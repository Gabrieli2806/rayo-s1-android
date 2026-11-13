// Android 12 C++ facade over the factory OMX C interface. No legacy C++ objects cross namespaces.
#define LOG_TAG "RayoOMXBridge"
#include <android-base/properties.h>
#include <media/hardware/OMXPluginBase.h>
#include <utils/Log.h>
#include <vndksupport/linker.h>
#include <dlfcn.h>
#include <cstring>
#include <cstdlib>
#include <mutex>

namespace android {
namespace {
// The factory decoder clears a 128-byte scratch buffer (decoder private + 616)
// in Rkvpu_Dec_Terminate on the Idle/Loaded transition. On Android 12 that
// pointer is already NULL by then, and the codec service crashed on every
// release. Re-provide the buffer before state changes; ComponentDeInit frees it.
constexpr size_t kScratchOffset=616, kScratchSize=128, kSubOffset=108;
struct Wrapped { OMX_COMPONENTTYPE* component; decltype(OMX_COMPONENTTYPE::SendCommand) send; };
std::mutex gWrappedLock;
Wrapped gWrapped[32];

void ensureScratch(OMX_HANDLETYPE handle){
    auto* component=static_cast<OMX_COMPONENTTYPE*>(handle);
    char* priv=static_cast<char*>(component->pComponentPrivate);
    char* sub=priv?*reinterpret_cast<char**>(priv+kSubOffset):nullptr;
    if(!sub)return;
    void** scratch=reinterpret_cast<void**>(sub+kScratchOffset);
    if(!*scratch){
        *scratch=calloc(1,kScratchSize);
        ALOGW("restored decoder scratch buffer for %p",handle);
    }
}

OMX_ERRORTYPE guardedSendCommand(OMX_HANDLETYPE handle,OMX_COMMANDTYPE command,OMX_U32 param,OMX_PTR data){
    decltype(OMX_COMPONENTTYPE::SendCommand) send=nullptr;
    {
        std::lock_guard<std::mutex> lock(gWrappedLock);
        for(auto& w:gWrapped)if(w.component==handle){send=w.send;break;}
    }
    if(!send)return OMX_ErrorInvalidComponent;
    if(command==OMX_CommandStateSet)ensureScratch(handle);
    return send(handle,command,param,data);
}
// Factory decoders exposed to Android 12, in the factory declaration order.
// HEVC also needs the factory HEVC library at a path outside this namespace and
// is therefore not listed until that is provided.
constexpr const char* kDecoders[]={"OMX.rk.video_decoder.avc","OMX.rk.video_decoder.vp8",
    "OMX.rk.video_decoder.m4v","OMX.rk.video_decoder.m2v","OMX.rk.video_decoder.h263"};
constexpr size_t kMaxDecoders=sizeof(kDecoders)/sizeof(kDecoders[0]);
class LegacyPlugin final : public OMXPluginBase {
    void* library_=nullptr;
    OMX_ERRORTYPE (*init_)()=nullptr;
    OMX_ERRORTYPE (*deinit_)()=nullptr;
    OMX_ERRORTYPE (*get_)(OMX_HANDLETYPE*,OMX_STRING,OMX_PTR,OMX_CALLBACKTYPE*)=nullptr;
    OMX_ERRORTYPE (*free_)(OMX_HANDLETYPE)=nullptr;
    OMX_ERRORTYPE (*enumerate_)(OMX_STRING,OMX_U32,OMX_U32)=nullptr;
    OMX_ERRORTYPE (*roles_)(OMX_STRING,OMX_U32*,OMX_U8**)=nullptr;
    bool initialized_=false, ready_=false;
    const char* present_[kMaxDecoders]={};
    size_t count_=0;
    bool exposed(const char* name) const {for(size_t i=0;i<count_;i++)if(!strcmp(name,present_[i]))return true;return false;}
public:
    LegacyPlugin() {
        library_=android_load_sphal_library("libOMX_Core.so",RTLD_NOW);
        if(!library_) return;
        init_=reinterpret_cast<decltype(init_)>(dlsym(library_,"RKOMX_Init"));
        deinit_=reinterpret_cast<decltype(deinit_)>(dlsym(library_,"RKOMX_DeInit"));
        get_=reinterpret_cast<decltype(get_)>(dlsym(library_,"RKOMX_GetHandle"));
        free_=reinterpret_cast<decltype(free_)>(dlsym(library_,"RKOMX_FreeHandle"));
        enumerate_=reinterpret_cast<decltype(enumerate_)>(dlsym(library_,"RKOMX_ComponentNameEnum"));
        roles_=reinterpret_cast<decltype(roles_)>(dlsym(library_,"RKOMX_GetRolesOfComponent"));
        initialized_=init_ && deinit_ && get_ && free_ && enumerate_ && roles_ && init_()==OMX_ErrorNone;
        if(initialized_){
            bool found[kMaxDecoders]={};
            for(OMX_U32 i=0;i<128;i++){
                char name[128]={};
                if(enumerate_(name,sizeof(name),i)!=OMX_ErrorNone)break;
                if(!memchr(name,0,sizeof(name)))continue;
                for(size_t k=0;k<kMaxDecoders;k++)if(!strcmp(name,kDecoders[k]))found[k]=true;
            }
            for(size_t k=0;k<kMaxDecoders;k++)if(found[k])present_[count_++]=kDecoders[k];
            ready_=count_>0;
        }
        ALOGI("Factory OMX C bridge ready=%d decoders=%zu",ready_,count_);
    }
    ~LegacyPlugin() override { if(initialized_) deinit_(); if(library_)android_unload_sphal_library(library_); }
    OMX_ERRORTYPE makeComponentInstance(const char* name,const OMX_CALLBACKTYPE* callbacks,OMX_PTR data,OMX_COMPONENTTYPE** component) override {
        if(!component || !name || !callbacks)return OMX_ErrorBadParameter;
        *component=nullptr;
        if(!ready_ || !exposed(name))return OMX_ErrorInvalidComponentName;
        OMX_HANDLETYPE handle=nullptr;
        OMX_ERRORTYPE status=get_(&handle,const_cast<char*>(name),data,const_cast<OMX_CALLBACKTYPE*>(callbacks));
        if(status==OMX_ErrorNone){
            *component=static_cast<OMX_COMPONENTTYPE*>(handle);
            std::lock_guard<std::mutex> lock(gWrappedLock);
            for(auto& w:gWrapped)if(!w.component){
                w.component=*component;w.send=(*component)->SendCommand;
                (*component)->SendCommand=guardedSendCommand;break;
            }
        }
        return status;
    }
    OMX_ERRORTYPE destroyComponentInstance(OMX_COMPONENTTYPE* component) override {
        if(!ready_ || !component)return OMX_ErrorBadParameter;
        // ComponentDeInit frees the scratch buffer; make sure it exists to free.
        ensureScratch(component);
        OMX_ERRORTYPE status=free_(component);
        std::lock_guard<std::mutex> lock(gWrappedLock);
        for(auto& w:gWrapped)if(w.component==component){w.component=nullptr;w.send=nullptr;}
        return status;
    }
    OMX_ERRORTYPE enumerateComponents(OMX_STRING name,size_t size,OMX_U32 index) override {
        if(!ready_ || index>=count_)return OMX_ErrorNoMore;
        if(!name || size<=strlen(present_[index]))return OMX_ErrorBadParameter;
        memcpy(name,present_[index],strlen(present_[index])+1);return OMX_ErrorNone;
    }
    OMX_ERRORTYPE getRolesOfComponent(const char* name,Vector<String8>* roles) override {
        if(!roles || !name)return OMX_ErrorBadParameter;
        roles->clear();
        if(!ready_ || !exposed(name))return OMX_ErrorInvalidComponentName;
        OMX_U32 count=0;
        OMX_ERRORTYPE status=roles_(const_cast<char*>(name),&count,nullptr);
        if(status!=OMX_ErrorNone)return status;
        if(!count || count>16)return OMX_ErrorUndefined;
        OMX_U8 storage[16][128]={}; OMX_U8* values[16]={};
        for(OMX_U32 i=0;i<count;i++)values[i]=storage[i];
        OMX_U32 capacity=count;
        status=roles_(const_cast<char*>(name),&count,values);
        if(status!=OMX_ErrorNone || count>capacity)return OMX_ErrorUndefined;
        for(OMX_U32 i=0;i<count;i++){
            if(!memchr(storage[i],0,sizeof(storage[i])))return OMX_ErrorUndefined;
            roles->push(String8(reinterpret_cast<const char*>(storage[i])));
        }
        return OMX_ErrorNone;
    }
};
}
}
extern "C" android::OMXPluginBase* createOMXPlugin() {
    if(!android::base::GetBoolProperty("ro.rayo.media.legacy_omx",false))return nullptr;
    return new android::LegacyPlugin();
}
