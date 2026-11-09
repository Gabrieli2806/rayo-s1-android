#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/xattr.h>
#include <sys/mount.h>
int main(int argc,char** argv){
    int bluetooth=argc==2&&!strcmp(argv[1],"--apply-bluetooth");
    const char* path=bluetooth?"/vendor/bin/hw/android.hardware.bluetooth@1.0-service":"/vendor/bin/hw/android.hardware.wifi@1.0-service";
    uint32_t caps[5]={0};
    if(argc==2&&(!strcmp(argv[1],"--apply")||bluetooth)){
        caps[0]=0x02000001u;
        caps[1]=(1u<<12)|(bluetooth?0:(1u<<13)); // BT NET_ADMIN; Wi-Fi NET_ADMIN/NET_RAW
        if(setxattr(path,"security.capability",caps,sizeof(caps),0)){perror("set radio file capabilities");return 1;}
        // The trial vendor directory inherits nosuid from userdata. Enable
        // file capabilities only on this root-owned executable's bind mount.
        if(mount(path,path,NULL,MS_BIND,NULL)){perror("bind radio executable");return 1;}
        if(mount(NULL,path,NULL,MS_REMOUNT|MS_BIND|MS_NODEV,NULL)){perror("radio executable mount flags");return 1;}
    }else if(argc!=1){return 2;}
    long n=getxattr(path,"security.capability",caps,sizeof(caps));
    if(n<0){perror("read WiFi file capabilities");return 1;}
    printf("%s file capabilities bytes=%ld revision=%08x permitted=%08x\n",bluetooth?"Bluetooth":"WiFi",n,caps[0],caps[1]);
    return 0;
}
