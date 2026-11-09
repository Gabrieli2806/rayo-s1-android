#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/xattr.h>
#include <sys/mount.h>
int main(int argc,char** argv){
    const char* path="/vendor/bin/hw/android.hardware.wifi@1.0-service";
    uint32_t caps[5]={0};
    if(argc==2&&!strcmp(argv[1],"--apply")){
        caps[0]=0x02000001u;
        caps[1]=(1u<<12)|(1u<<13); // NET_ADMIN and NET_RAW only
        if(setxattr(path,"security.capability",caps,sizeof(caps),0)){perror("set WiFi file capabilities");return 1;}
        // The trial vendor directory inherits nosuid from userdata. Enable
        // file capabilities only on this root-owned executable's bind mount.
        if(mount(path,path,NULL,MS_BIND,NULL)){perror("bind WiFi executable");return 1;}
        if(mount(NULL,path,NULL,MS_REMOUNT|MS_BIND|MS_NODEV,NULL)){perror("WiFi executable mount flags");return 1;}
    }else if(argc!=1){return 2;}
    long n=getxattr(path,"security.capability",caps,sizeof(caps));
    if(n<0){perror("read WiFi file capabilities");return 1;}
    printf("WiFi file capabilities bytes=%ld revision=%08x permitted=%08x\n",n,caps[0],caps[1]);
    return 0;
}
