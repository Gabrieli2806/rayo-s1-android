/* Narrow diagnostic for the old recovery adbd/new property-service mismatch. */
#include <sys/socket.h>
#include <sys/un.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

__thread volatile unsigned char rayo_tls_alignment __attribute__((aligned(64)));
static int send_all(int fd,const void *buffer,size_t size) {
    const char *bytes=buffer;
    while(size) { ssize_t n=write(fd,bytes,size); if(n<0 && errno==EINTR)continue;
        if(n<=0)return -1; bytes+=n;size-=n; }
    return 0;
}
int main(int argc,char **argv) {
    rayo_tls_alignment=1;
    printf("Rayo diagnostic uid=%u tls=%u\n",(unsigned)getuid(),rayo_tls_alignment);
    if(argc==1)return 0;
    if(argc!=3 || strcmp(argv[1],"sys.powerctl") || strcmp(argv[2],"reboot"))return 2;
    int fd=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
    struct sockaddr_un address={.sun_family=AF_UNIX};
    strcpy(address.sun_path,"/dev/socket/property_service");
    if(fd<0 || connect(fd,(struct sockaddr*)&address,sizeof(address))) {perror("property socket");return 3;}
    uint32_t command=0x00020001,name_length=strlen(argv[1]),value_length=strlen(argv[2]);
    if(send_all(fd,&command,4) || send_all(fd,&name_length,4) || send_all(fd,argv[1],name_length) ||
       send_all(fd,&value_length,4) || send_all(fd,argv[2],value_length)) {perror("property send");return 4;}
    uint32_t result=0xffffffff;
    ssize_t count=read(fd,&result,4);
    printf("Property result=0x%x bytes=%zd\n",result,count);
    close(fd);
    return count==4 && result==0 ? 0 : 5;
}
