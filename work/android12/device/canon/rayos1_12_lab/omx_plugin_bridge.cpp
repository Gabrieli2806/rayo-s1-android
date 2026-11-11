// Android 12 C++ facade over the factory OMX C interface. No legacy C++ objects cross namespaces.
#define LOG_TAG "RayoOMXBridge"
#include <android-base/properties.h>
#include <media/hardware/OMXPluginBase.h>
#include <utils/Log.h>
#include <vndksupport/linker.h>
#include <dlfcn.h>
#include <cstring>

namespace android {
namespace {
constexpr const char* kDecoder="OMX.rk.video_decoder.avc";
class LegacyPlugin final : public OMXPluginBase {
    void* library_=nullptr;
    OMX_ERRORTYPE (*init_)()=nullptr;
    OMX_ERRORTYPE (*deinit_)()=nullptr;
    OMX_ERRORTYPE (*get_)(OMX_HANDLETYPE*,OMX_STRING,OMX_PTR,OMX_CALLBACKTYPE*)=nullptr;
    OMX_ERRORTYPE (*free_)(OMX_HANDLETYPE)=nullptr;
    OMX_ERRORTYPE (*enumerate_)(OMX_STRING,OMX_U32,OMX_U32)=nullptr;
    OMX_ERRORTYPE (*roles_)(OMX_STRING,OMX_U32*,OMX_U8**)=nullptr;
    bool initialized_=false, ready_=false;
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
            for(OMX_U32 i=0;i<128;i++){
                char name[128]={};
                if(enumerate_(name,sizeof(name),i)!=OMX_ErrorNone)break;
                if(memchr(name,0,sizeof(name)) && !strcmp(name,kDecoder)){ready_=true;break;}
            }
        }
        ALOGI("Factory OMX C bridge ready=%d",ready_);
    }
    ~LegacyPlugin() override { if(initialized_) deinit_(); if(library_)android_unload_sphal_library(library_); }
    OMX_ERRORTYPE makeComponentInstance(const char* name,const OMX_CALLBACKTYPE* callbacks,OMX_PTR data,OMX_COMPONENTTYPE** component) override {
        if(!component || !name || !callbacks)return OMX_ErrorBadParameter;
        *component=nullptr;
        if(!ready_ || strcmp(name,kDecoder))return OMX_ErrorInvalidComponentName;
        OMX_HANDLETYPE handle=nullptr;
        OMX_ERRORTYPE status=get_(&handle,const_cast<char*>(name),data,const_cast<OMX_CALLBACKTYPE*>(callbacks));
        if(status==OMX_ErrorNone)*component=static_cast<OMX_COMPONENTTYPE*>(handle);
        return status;
    }
    OMX_ERRORTYPE destroyComponentInstance(OMX_COMPONENTTYPE* component) override {
        return ready_ && component ? free_(component) : OMX_ErrorBadParameter;
    }
    OMX_ERRORTYPE enumerateComponents(OMX_STRING name,size_t size,OMX_U32 index) override {
        if(!ready_ || index)return OMX_ErrorNoMore;
        if(!name || size<=strlen(kDecoder))return OMX_ErrorBadParameter;
        memcpy(name,kDecoder,strlen(kDecoder)+1);return OMX_ErrorNone;
    }
    OMX_ERRORTYPE getRolesOfComponent(const char* name,Vector<String8>* roles) override {
        if(!roles || !name)return OMX_ErrorBadParameter;
        roles->clear();
        if(!ready_ || strcmp(name,kDecoder))return OMX_ErrorInvalidComponentName;
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
