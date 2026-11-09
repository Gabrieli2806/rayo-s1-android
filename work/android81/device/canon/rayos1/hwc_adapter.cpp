#define LOG_TAG "RayoHwcAdapter"
#include <hardware/hwcomposer.h>
#include <log/log.h>
#include <dlfcn.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include "rk_hwc_abi.h"

static_assert(sizeof(void*)==4,"ARM32 factory ABI only");
static_assert(sizeof(rk_hwc_layer_1_t)==208,"Factory layer stride");
static_assert(offsetof(rk_hwc_display_contents_1_t,numHwLayers)==20,"Factory list count");
static_assert(offsetof(rk_hwc_display_contents_1_t,hwLayers)==24,"Factory list header");
static_assert(offsetof(rk_hwc_composer_device_1_t,eventControl)==0x58,"Factory eventControl");
static_assert(offsetof(rk_hwc_composer_device_1_t,query)==0x60,"Factory query");
static_assert(offsetof(rk_hwc_composer_device_1_t,registerProcs)==0x64,"Factory callbacks");
static_assert(offsetof(rk_hwc_composer_device_1_t,getDisplayAttributes)==0x70,"Factory attributes");

struct Adapter {
    hwc_composer_device_1_t api;
    rk_hwc_composer_device_1_t* factory;
    void* library;
    rk_hwc_display_contents_1_t* lists[3];
};
static Adapter* self(hwc_composer_device_1_t* d){return reinterpret_cast<Adapter*>(d);}
static void inputLayer(rk_hwc_layer_1_t& r,const hwc_layer_1_t& a){
    r.compositionType=a.compositionType;r.hints=a.hints;r.flags=a.flags;
    r.handle=a.handle;r.transform=a.transform;r.blending=a.blending;
    r.sourceCropf=a.sourceCropf;r.displayFrame=a.displayFrame;
    r.visibleRegionScreen=a.visibleRegionScreen;r.acquireFenceFd=a.acquireFenceFd;
    r.releaseFenceFd=a.releaseFenceFd;r.planeAlpha=a.planeAlpha;
    r.surfaceDamage=a.surfaceDamage;
}
static int translate(Adapter* p,size_t n,hwc_display_contents_1_t** a,bool fresh){
    if(n>3)return -EINVAL;
    for(size_t i=0;i<3;i++){
        if(i>=n||!a[i]){free(p->lists[i]);p->lists[i]=nullptr;continue;}
        if(a[i]->numHwLayers>128)return -EINVAL;
        if(fresh||!p->lists[i]||p->lists[i]->numHwLayers!=a[i]->numHwLayers){
            free(p->lists[i]);p->lists[i]=nullptr;
            auto* r=static_cast<rk_hwc_display_contents_1_t*>(calloc(1,sizeof(rk_hwc_display_contents_1_t)+a[i]->numHwLayers*sizeof(rk_hwc_layer_1_t)));
            if(!r)return -ENOMEM;
            p->lists[i]=r;r->numHwLayers=a[i]->numHwLayers;
            for(size_t j=0;j<r->numHwLayers;j++){
                strcpy(r->hwLayers[j].LayerName,"Rayo Android 8.1");
                r->hwLayers[j].bufferCount=3;r->hwLayers[j].bufferUpdate=1;
                r->hwLayers[j].bufferChange=1;r->hwLayers[j].direct_fd=static_cast<unsigned>(-1);
            }
        }
        auto* r=p->lists[i];r->retireFenceFd=a[i]->retireFenceFd;
        r->outbuf=a[i]->outbuf;r->outbufAcquireFenceFd=a[i]->outbufAcquireFenceFd;
        r->flags=a[i]->flags;r->skipflag=0;
        for(size_t j=0;j<r->numHwLayers;j++)inputLayer(r->hwLayers[j],a[i]->hwLayers[j]);
    }
    return 0;
}
static void output(Adapter* p,size_t n,hwc_display_contents_1_t** a,bool fences){
    for(size_t i=0;i<n;i++)if(a[i]&&p->lists[i]){
        auto* r=p->lists[i];
        for(size_t j=0;j<r->numHwLayers;j++){
            a[i]->hwLayers[j].compositionType=r->hwLayers[j].compositionType;
            a[i]->hwLayers[j].hints=r->hwLayers[j].hints;
            if(fences)a[i]->hwLayers[j].releaseFenceFd=r->hwLayers[j].releaseFenceFd;
        }
        if(fences)a[i]->retireFenceFd=r->retireFenceFd;
    }
}
static int prepare(hwc_composer_device_1_t* d,size_t n,hwc_display_contents_1_t** a){
    auto* p=self(d);int e=translate(p,n,a,true);if(e)return e;
    e=p->factory->prepare(p->factory,n,p->lists);output(p,n,a,false);return e;
}
static int commit(hwc_composer_device_1_t* d,size_t n,hwc_display_contents_1_t** a){
    auto* p=self(d);int e=translate(p,n,a,false);if(e)return e;
    e=p->factory->set(p->factory,n,p->lists);output(p,n,a,true);return e;
}
static int event(hwc_composer_device_1_t* d,int display,int what,int enabled){auto* p=self(d);return p->factory->eventControl(p->factory,display,what,enabled);}
static int blank(hwc_composer_device_1_t* d,int display,int value){auto* p=self(d);return p->factory->blank(p->factory,display,value);}
static int query(hwc_composer_device_1_t* d,int what,int* value){auto* p=self(d);return value?p->factory->query(p->factory,what,value):-EINVAL;}
static void callbacks(hwc_composer_device_1_t* d,const hwc_procs_t* procs){auto* p=self(d);p->factory->registerProcs(p->factory,reinterpret_cast<const rk_hwc_procs_t*>(procs));}
static void dump(hwc_composer_device_1_t* d,char* b,int n){auto* p=self(d);if(b&&n>0&&p->factory->dump)p->factory->dump(p->factory,b,n);}
static int configs(hwc_composer_device_1_t* d,int display,uint32_t* c,size_t* n){auto* p=self(d);return p->factory->getDisplayConfigs(p->factory,display,c,n);}
static int attributes(hwc_composer_device_1_t* d,int display,uint32_t c,const uint32_t* a,int32_t* v){auto* p=self(d);return p->factory->getDisplayAttributes(p->factory,display,c,a,v);}
static int closeDevice(hw_device_t* d){auto* p=reinterpret_cast<Adapter*>(d);int e=p->factory->common.close(&p->factory->common);for(auto* l:p->lists)free(l);free(p);return e;}
static int openDevice(const hw_module_t* module,const char* name,hw_device_t** out){
    if(strcmp(name,HWC_HARDWARE_COMPOSER))return -EINVAL;
    auto* p=static_cast<Adapter*>(calloc(1,sizeof(Adapter)));if(!p)return -ENOMEM;
    p->library=dlopen("/system/lib/hw/hwcomposer.rk30board.factory.so",RTLD_NOW|RTLD_LOCAL);
    if(!p->library){ALOGE("Factory library: %s",dlerror());free(p);return -ENOENT;}
    auto* m=static_cast<hw_module_t*>(dlsym(p->library,HAL_MODULE_INFO_SYM_AS_STR));
    if(!m||!m->methods||!m->methods->open){free(p);return -EINVAL;}
    int e=m->methods->open(m,name,reinterpret_cast<hw_device_t**>(&p->factory));
    if(e||!p->factory){free(p);return e?e:-ENODEV;}
    if(p->factory->common.version!=HWC_DEVICE_API_VERSION_1_3){ALOGE("Unsupported factory ABI %x",p->factory->common.version);return -EINVAL;}
    p->api.common=p->factory->common;p->api.common.module=const_cast<hw_module_t*>(module);p->api.common.close=closeDevice;
    p->api.prepare=prepare;p->api.set=commit;p->api.eventControl=event;p->api.blank=blank;
    p->api.query=query;p->api.registerProcs=callbacks;p->api.dump=dump;
    p->api.getDisplayConfigs=configs;p->api.getDisplayAttributes=attributes;
    *out=&p->api.common;ALOGI("Factory HWC 1.3 adapted: device callbacks +16 bytes, layer stride 208");return 0;
}
static hw_module_methods_t methods={openDevice};
extern "C" hwc_module_t HAL_MODULE_INFO_SYM={
    {HARDWARE_MODULE_TAG,HWC_MODULE_API_VERSION_0_1,HARDWARE_HAL_API_VERSION,
     HWC_HARDWARE_MODULE_ID,"Rayo factory HWC ABI adapter","Rayo trial",&methods,nullptr,{0}}
};
