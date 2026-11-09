#include <stdio.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <linux/fb.h>

struct binder_version_probe { int32_t protocol_version; };
#define BINDER_VERSION_PROBE _IOWR('b', 9, struct binder_version_probe)

int main(void) {
    /* Diagnostic only: no write(), no set-mode ioctl, no filesystem changes. */
    int fd = open("/dev/binder", O_RDONLY | O_CLOEXEC);
    if (fd < 0) { printf("binder_open_error=%s\n", strerror(errno)); return 1; }
    struct binder_version_probe version = {0};
    if (ioctl(fd, BINDER_VERSION_PROBE, &version) < 0) {
        printf("binder_version_error=%s\n", strerror(errno)); close(fd); return 2;
    }
    close(fd);
    printf("binder_protocol=%d\n", version.protocol_version);
    printf("userspace_pointer_bits=%zu\n", sizeof(void*)*8);
    errno=0;
    int seccomp=prctl(PR_GET_SECCOMP,0,0,0,0);
    printf("seccomp_mode=%d errno=%d\n",seccomp,errno);
    errno=0;
    int nnp=prctl(PR_GET_NO_NEW_PRIVS,0,0,0,0);
    printf("no_new_privs=%d errno=%d\n",nnp,errno);
    fd = open("/dev/graphics/fb0", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        struct fb_var_screeninfo var = {0};
        struct fb_fix_screeninfo fix = {0};
        if (ioctl(fd, FBIOGET_VSCREENINFO, &var) == 0)
            printf("framebuffer=%ux%u virtual=%ux%u bpp=%u\n",var.xres,var.yres,var.xres_virtual,var.yres_virtual,var.bits_per_pixel);
        if (ioctl(fd, FBIOGET_FSCREENINFO, &fix) == 0)
            printf("framebuffer_id=%.16s line_length=%u\n",fix.id,fix.line_length);
        close(fd);
    } else printf("framebuffer_open_error=%s\n",strerror(errno));
    return 0;
}
