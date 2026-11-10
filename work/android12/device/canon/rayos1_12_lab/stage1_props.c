#include <stdio.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <sys/system_properties.h>
#include <sys/utsname.h>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc == 2 && strcmp(argv[1], "--inspect-userdata") == 0) {
        int fd = open("/dev/block/mmcblk0p14", O_RDONLY);
        if (fd < 0) { perror("readonly userdata open"); return 2; }
        unsigned char data[1024] = {0};
        ssize_t count = pread(fd, data, sizeof(data), 1024);
        printf("pread_count=%zd errno=%d magic=%02x%02x uuid=", count, errno, data[56], data[57]);
        for (unsigned i = 104; i < 120; ++i) printf("%02x", data[i]);
        putchar('\n');
        memset(data, 0, sizeof(data));
        if (lseek(fd, 1024, SEEK_SET) < 0) { perror("readonly seek"); close(fd); return 3; }
        count = read(fd, data, sizeof(data));
        printf("read_count=%zd errno=%d magic=%02x%02x uuid=", count, errno, data[56], data[57]);
        for (unsigned i = 104; i < 120; ++i) printf("%02x", data[i]);
        putchar('\n');
        close(fd);
        return count == (ssize_t)sizeof(data) ? 0 : 4;
    }
    if (argc != 1) return 64;
    const char* keys[] = {"ro.build.version.release", "ro.build.version.sdk", "ro.rayo.trial", "sys.rayo12.stage"};
    char value[PROP_VALUE_MAX];
    for (unsigned i = 0; i < sizeof(keys)/sizeof(keys[0]); ++i) {
        value[0] = 0;
        __system_property_get(keys[i], value);
        printf("%s=%s\n", keys[i], value);
    }
    char path[256];
    ssize_t length = readlink("/proc/1/exe", path, sizeof(path)-1);
    if (length >= 0) { path[length] = 0; printf("init_exe=%s\n", path); }
    struct utsname kernel;
    if (uname(&kernel) == 0) printf("kernel=%s\n", kernel.release);
    printf("uid=%u\n", (unsigned)getuid());
    return 0;
}
