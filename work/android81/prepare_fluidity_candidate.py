from pathlib import Path
import shutil

work=Path('/mnt/c/Users/gabri/Documents/Codex/2026-11-08/q/work/android81')
target=work/'fluidity-v01'
target.mkdir(exist_ok=True)
device=target/'device/canon/rayos1_fluidity'
device.mkdir(parents=True,exist_ok=True)
baseline=work/'device/canon/rayos1'
text=(baseline/'hwc_adapter.cpp').read_text()
old='''        if(fresh||!p->lists[i]||p->lists[i]->numHwLayers!=a[i]->numHwLayers){'''
new='''        const size_t layerCount=a[i]->numHwLayers;
        const bool allocate=!p->lists[i]||p->lists[i]->numHwLayers!=layerCount;
        if(allocate){'''
assert text.count(old)==1
text=text.replace(old,new)
old='''            p->lists[i]=r;r->numHwLayers=a[i]->numHwLayers;
            for(size_t j=0;j<r->numHwLayers;j++){'''
new='''            p->lists[i]=r;
        }
        if(fresh||allocate){
            auto* r=p->lists[i];
            // Preserve the old per-prepare initialization without reallocating.
            memset(r,0,sizeof(*r)+layerCount*sizeof(rk_hwc_layer_1_t));
            r->numHwLayers=layerCount;
            for(size_t j=0;j<r->numHwLayers;j++){'''
assert text.count(old)==1
text=text.replace(old,new)
old='''static int attributes(hwc_composer_device_1_t* d,int display,uint32_t c,const uint32_t* a,int32_t* v){auto* p=self(d);return p->factory->getDisplayAttributes(p->factory,display,c,a,v);}'''
new='''static int attributes(hwc_composer_device_1_t* d,int display,uint32_t c,const uint32_t* a,int32_t* v){
    auto* p=self(d);int e=p->factory->getDisplayAttributes(p->factory,display,c,a,v);
    if(!e&&a&&v){
        for(size_t i=0;i<32&&a[i]!=HWC_DISPLAY_NO_ATTRIBUTE;i++){
            if((a[i]==HWC_DISPLAY_DPI_X||a[i]==HWC_DISPLAY_DPI_Y)&&
               (v[i]<40000||v[i]>1000000))v[i]=160000;
        }
    }
    return e;
}'''
assert text.count(old)==1
text=text.replace(old,new)
(device/'hwc_adapter.cpp').write_text(text)
shutil.copy2(baseline/'rk_hwc_abi.h',device/'rk_hwc_abi.h')
(device/'Android.mk').write_text('''LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := hwcomposer.rayo_fluidity
LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_SRC_FILES := hwc_adapter.cpp
LOCAL_SHARED_LIBRARIES := libhardware liblog libdl
LOCAL_CPPFLAGS := -std=c++14 -DTARGET_BOARD_PLATFORM_RK312X
include $(BUILD_SHARED_LIBRARY)
''')
shutil.copytree(work/'input-keymaps-oreo',target/'keylayout',dirs_exist_ok=True)
shutil.copy2(work/'input-keymap-compatibility.json',target/'keymap-compatibility.json')
print('Prepared independent offline candidate:',target)
