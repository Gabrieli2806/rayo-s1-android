// Enumeration only: this probe does not instantiate or decode a component.
#include <media/hardware/OMXPluginBase.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
static OMX_ERRORTYPE event(OMX_HANDLETYPE,OMX_PTR,OMX_EVENTTYPE,OMX_U32,OMX_U32,OMX_PTR){return OMX_ErrorNone;}
static OMX_ERRORTYPE buffer(OMX_HANDLETYPE,OMX_PTR,OMX_BUFFERHEADERTYPE*){return OMX_ErrorNone;}
int main(int argc,char** argv){
    void* library=dlopen(argc>=2?argv[1]:"/system/lib/librayo12_omx_plugin.so",RTLD_NOW);
    if(!library){fprintf(stderr,"Facade load failed: %s\n",dlerror());return 1;}
    auto create=reinterpret_cast<android::OMXPluginBase*(*)()>(dlsym(library,"createOMXPlugin"));
    auto plugin=create?create():nullptr;
    if(!plugin){fprintf(stderr,"Facade disabled or missing\n");dlclose(library);return 2;}
    char name[128]={}; android::Vector<android::String8> roles;
    bool ok=plugin->enumerateComponents(name,sizeof(name),0)==OMX_ErrorNone &&
        !strcmp(name,"OMX.rk.video_decoder.avc") && plugin->getRolesOfComponent(name,&roles)==OMX_ErrorNone;
    bool avc=false;
    for(size_t i=0;i<roles.size();i++)avc|=!strcmp(roles[i].c_str(),"video_decoder.avc");
    printf("factory_enumeration=%s avc_role=%s\n",ok?"pass":"fail",avc?"pass":"fail");
    if(ok && avc && argc==3 && !strcmp(argv[2],"--create")){
        OMX_CALLBACKTYPE callbacks={event,buffer,buffer}; OMX_COMPONENTTYPE* component=nullptr; int context=0;
        OMX_ERRORTYPE status=plugin->makeComponentInstance(name,&callbacks,&context,&component);
        printf("component_create=0x%08x\n",static_cast<unsigned>(status));
        ok=status==OMX_ErrorNone && component && component->nSize==sizeof(OMX_COMPONENTTYPE);
        if(component){
            printf("component_size=%u expected=%zu\n",component->nSize,sizeof(OMX_COMPONENTTYPE));
            if(ok){
                OMX_PORT_PARAM_TYPE ports={}; ports.nSize=sizeof(ports);
                ports.nVersion.s.nVersionMajor=1; ports.nVersion.s.nVersionMinor=0;
                status=component->GetParameter(component,OMX_IndexParamVideoInit,&ports);
                printf("video_ports_status=0x%08x count=%u start=%u\n",static_cast<unsigned>(status),ports.nPorts,ports.nStartPortNumber);
                ok=status==OMX_ErrorNone && ports.nPorts==2;
            }
            OMX_ERRORTYPE freed=plugin->destroyComponentInstance(component);
            printf("component_free=0x%08x\n",static_cast<unsigned>(freed));ok=ok&&freed==OMX_ErrorNone;
        }
    }
    delete plugin; dlclose(library);return ok&&avc?0:3;
}
