#include <errno.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

int main(int argc, char **argv) {
    int write_mode = argc == 2 && strcmp(argv[1], "--rgba") == 0;
    if (argc > 1 && !write_mode) return 2;
    int fd = open("/dev/graphics/fb0", (write_mode ? O_RDWR : O_RDONLY) | O_CLOEXEC);
    struct fb_var_screeninfo v;
    struct fb_fix_screeninfo f;
    if (fd < 0 || ioctl(fd, FBIOGET_VSCREENINFO, &v) || ioctl(fd, FBIOGET_FSCREENINFO, &f)) {
        perror("read framebuffer parameters"); return 1;
    }
    printf("visible=%ux%u virtual=%ux%u offset=%u,%u bpp=%u nonstd=0x%x grayscale=0x%x stride=%u rotate=%u\n",
        v.xres,v.yres,v.xres_virtual,v.yres_virtual,v.xoffset,v.yoffset,
        v.bits_per_pixel,v.nonstd,v.grayscale,f.line_length,v.rotate);
    printf("R=%u:%u G=%u:%u B=%u:%u A=%u:%u\n",v.red.offset,v.red.length,
        v.green.offset,v.green.length,v.blue.offset,v.blue.length,v.transp.offset,v.transp.length);
    printf("timing pixclock=%u margins=%u,%u,%u,%u sync=%u,%u flags=%u vmode=%u activate=%u reserved=%u,%u,%u,%u\n",
        v.pixclock,v.left_margin,v.right_margin,v.upper_margin,v.lower_margin,
        v.hsync_len,v.vsync_len,v.sync,v.vmode,v.activate,
        v.reserved[0],v.reserved[1],v.reserved[2],v.reserved[3]);
    if (write_mode) {
        if (v.xres != 1280 || v.yres != 720 || v.xres_virtual != 1280 ||
            v.bits_per_pixel != 32 || f.line_length != 5120) {
            fprintf(stderr,"Unexpected framebuffer; refusing change\n");return 3;
        }
        // Rockchip nonstd low byte is the Android HAL pixel-format number.
        // Only change the format; preserve timings, size and screen position.
        v.nonstd = (v.nonstd & ~0xffu) | 1u;
        v.activate = FB_ACTIVATE_NOW | FB_ACTIVATE_FORCE;
        if (ioctl(fd, FBIOPUT_VSCREENINFO, &v)) {perror("set RGBA format");return 4;}
        v.reserved[3] &= ~1u;
        if (ioctl(fd, FBIOPAN_DISPLAY, &v)) {perror("commit framebuffer view");return 5;}
        puts("RGBA framebuffer format applied; no partition writes");
    }
    close(fd); return 0;
}
