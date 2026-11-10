#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/loop.h>
#include <openssl/sha.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/utsname.h>
#include <unistd.h>

/* Stage-one boot experiment only: readonly physical userdata/system and
 * disposable tmpfs data. This is not the full Android/Google TV root. */
static const char image_path[] = "/realdata/local/tmp/rayo12-stage1-system.raw.img";
static const unsigned char unit_uuid[16] = {
    0x57,0xf8,0xf4,0xbc,0xab,0xf4,0x65,0x5f,0xbf,0x67,0x94,0x6f,0xc0,0xf9,0xf2,0x5b
};

static __attribute__((noreturn)) void fatal(const char* action) {
    fprintf(stderr, "RAYO12_STAGE1: %s: %s\n", action, strerror(errno));
    /* Never exit PID 1 into a kernel panic. The independent child watchdog
     * remains responsible for requesting normal reboot. */
    for (;;) sleep(60);
}

static void directory(const char* path, mode_t mode) {
    if (mkdir(path, mode) != 0 && errno != EEXIST) fatal(path);
}

static void node(const char* path, mode_t mode, unsigned major, unsigned minor) {
    if (mknod(path, mode, makedev(major, minor)) != 0 && errno != EEXIST) fatal(path);
}

static void mount_checked(const char* source, const char* target, const char* type,
                          unsigned long flags, const char* data) {
    if (mount(source, target, type, flags, data) != 0) fatal(target);
}

static int read_expected(char digest[65], unsigned long long* bytes) {
    FILE* config = fopen("/rayo12-stage1.cfg", "re");
    if (!config) return -1;
    char trailing;
    int fields = fscanf(config, "system_sha256=%64[0-9a-f]\nsystem_bytes=%llu %c", digest, bytes, &trailing);
    fclose(config);
    if (fields != 2 || strlen(digest) != 64 || *bytes < 16777216 || *bytes > 2147483648ULL) return -1;
    return 0;
}

int main(int argc, char** argv) {
    (void)argv;
    /* Running this binary through ADB must never mount anything or reboot. */
    if (argc != 1 || getpid() != 1 || geteuid() != 0) {
        fputs("RAYO12_STAGE1_REFUSED: requires initial root PID 1\n", stderr);
        return 64;
    }
    pid_t watchdog = fork();
    if (watchdog < 0) {
        sync();
        reboot(RB_AUTOBOOT);
        fatal("watchdog fork");
    }
    if (watchdog == 0) {
        /* First diagnostic has no disarm path. Normal boot remains v14.
         * Reboot selection/return must be checked before any recovery write. */
        sleep(180);
        sync();
        for (;;) { reboot(RB_AUTOBOOT); sleep(10); }
    }
    /* Match upstream first-stage setup; every sensitive path has an explicit
     * restrictive mode rather than relying on an inherited umask. */
    umask(0);
    struct utsname kernel;
    if (uname(&kernel) != 0 || strcmp(kernel.release, "3.10.104") != 0) fatal("kernel identity");
    char expected_digest[65] = {0};
    unsigned long long expected_bytes = 0;
    if (read_expected(expected_digest, &expected_bytes) != 0) fatal("stage-one image manifest");
    directory("/dev", 0755);
    directory("/proc", 0755);
    directory("/sys", 0755);
    directory("/realdata", 0700);
    directory("/system", 0755);
    directory("/data", 0771);
    directory("/cache", 0770);
    mount_checked("tmpfs", "/dev", "tmpfs", MS_NOSUID, "mode=0755");
    directory("/dev/pts", 0755);
    directory("/dev/socket", 0755);
    directory("/dev/dm-user", 0755);
    mount_checked("devpts", "/dev/pts", "devpts", 0, NULL);
    node("/dev/null", S_IFCHR|0666, 1, 3);
    node("/dev/zero", S_IFCHR|0666, 1, 5);
    node("/dev/random", S_IFCHR|0666, 1, 8);
    node("/dev/urandom", S_IFCHR|0666, 1, 9);
    node("/dev/kmsg", S_IFCHR|0600, 1, 11);
    node("/dev/ptmx", S_IFCHR|0666, 5, 2);
    int log = open("/dev/kmsg", O_WRONLY|O_CLOEXEC);
    if (log >= 0) { dup2(log, STDERR_FILENO); close(log); }
    mount_checked("proc", "/proc", "proc", MS_NODEV|MS_NOSUID|MS_NOEXEC, NULL);
    mount_checked("sysfs", "/sys", "sysfs", MS_NODEV|MS_NOSUID|MS_NOEXEC, NULL);
    directory("/sys/fs/selinux", 0755);
    mount_checked("selinuxfs", "/sys/fs/selinux", "selinuxfs", MS_NOSUID|MS_NOEXEC, NULL);
    directory("/mnt", 0755);
    mount_checked("tmpfs", "/mnt", "tmpfs", MS_NOEXEC|MS_NOSUID|MS_NODEV, "mode=0755,uid=0,gid=1000");
    directory("/mnt/vendor", 0755);
    directory("/mnt/product", 0755);
    directory("/debug_ramdisk", 0755);
    directory("/second_stage_resources", 0755);
    mount_checked("tmpfs", "/debug_ramdisk", "tmpfs", MS_NOEXEC|MS_NOSUID|MS_NODEV, "mode=0755,uid=0,gid=0");
    mount_checked("tmpfs", "/second_stage_resources", "tmpfs", MS_NOEXEC|MS_NOSUID|MS_NODEV, "mode=0755,uid=0,gid=0");
    /* Recovery enumerates the parameter partition differently from normal
     * boot. The measured Linux start is 0x5f4000; Rockchip Loader addresses
     * use 0x5f2000 relative to the additional 0x2000-sector prefix. */
    unsigned block_major = 0, block_minor = 0;
    for (unsigned attempt = 0; attempt < 100; ++attempt) {
        unsigned matches = 0;
        for (unsigned partition = 1; partition < 32; ++partition) {
            char path[128];
            unsigned long long start = 0, sectors = 0;
            snprintf(path, sizeof(path), "/sys/class/block/mmcblk0p%u/start", partition);
            FILE* field = fopen(path, "re");
            if (!field) continue;
            int count = fscanf(field, "%llu", &start);
            fclose(field);
            if (count != 1 || start != 0x5f4000ULL) continue;
            snprintf(path, sizeof(path), "/sys/class/block/mmcblk0p%u/size", partition);
            field = fopen(path, "re");
            if (!field) continue;
            count = fscanf(field, "%llu", &sectors);
            fclose(field);
            if (count != 1 || sectors < 8388608ULL || sectors > 16777216ULL) continue;
            snprintf(path, sizeof(path), "/sys/class/block/mmcblk0p%u/dev", partition);
            field = fopen(path, "re");
            if (!field) continue;
            count = fscanf(field, "%u:%u", &block_major, &block_minor);
            fclose(field);
            if (count == 2) ++matches;
        }
        if (matches > 1) fatal("ambiguous userdata partition LBA");
        if (matches == 1) break;
        usleep(100000);
    }
    if (block_major != 179 || block_minor == 0 || block_minor >= 32) fatal("userdata block identity");
    fprintf(stderr, "RAYO12_STAGE1: userdata Linux LBA=0x5f4000 device=%u:%u\n", block_major, block_minor);
    directory("/dev/block", 0755);
    node("/dev/block/rayo12-userdata", S_IFBLK|0600, block_major, block_minor);
    int userdata = open("/dev/block/rayo12-userdata", O_RDONLY|O_CLOEXEC);
    unsigned char superblock[1024];
    if (userdata < 0 || pread(userdata, superblock, sizeof(superblock), 1024) != (ssize_t)sizeof(superblock)) fatal("read userdata identity");
    close(userdata);
    char identity[160];
    int used = snprintf(identity, sizeof(identity), "RAYO12_STAGE1_ID: magic=%02x%02x uuid=", superblock[56], superblock[57]);
    for (unsigned i = 0; i < 16; ++i) used += snprintf(identity+used, sizeof(identity)-(size_t)used, "%02x", superblock[104+i]);
    fprintf(stderr, "%s\n", identity);
    if (superblock[56] != 0x53 || superblock[57] != 0xef || memcmp(superblock+104, unit_uuid, 16) != 0) fatal("unit filesystem UUID");
    /* noload also prevents journal replay writes to the protected baseline. */
    mount_checked("/dev/block/rayo12-userdata", "/realdata", "ext4",
                  MS_RDONLY|MS_NODEV|MS_NOSUID|MS_NOATIME, "noload");
    int image = open(image_path, O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    struct stat info;
    if (image < 0 || fstat(image, &info) != 0 || !S_ISREG(info.st_mode) ||
        (unsigned long long)info.st_size != expected_bytes) fatal("system image size/path");
    SHA256_CTX hash;
    SHA256_Init(&hash);
    unsigned char buffer[65536], digest[SHA256_DIGEST_LENGTH];
    ssize_t count;
    while ((count = read(image, buffer, sizeof(buffer))) > 0) SHA256_Update(&hash, buffer, (size_t)count);
    if (count < 0) fatal("system image read");
    SHA256_Final(digest, &hash);
    char actual[65];
    for (unsigned i = 0; i < sizeof(digest); ++i) snprintf(actual+i*2, 3, "%02x", digest[i]);
    if (strcmp(actual, expected_digest) != 0) fatal("system image checksum");
    node("/dev/block/loop7", S_IFBLK|0600, 7, 7);
    int loop = open("/dev/block/loop7", O_RDONLY|O_CLOEXEC);
    struct loop_info64 previous = {0};
    if (loop < 0 || ioctl(loop, LOOP_GET_STATUS64, &previous) == 0 || errno != ENXIO) fatal("loop device must be unused");
    if (ioctl(loop, LOOP_SET_FD, image) != 0) fatal("attach readonly system loop");
    struct loop_info64 status = {0};
    status.lo_flags = LO_FLAGS_READ_ONLY;
    if (ioctl(loop, LOOP_SET_STATUS64, &status) != 0) fatal("readonly loop flags");
    close(image);
    mount_checked("/dev/block/loop7", "/system", "ext4", MS_RDONLY|MS_NOATIME, "noload");
    close(loop);
    mount_checked("tmpfs", "/data", "tmpfs", MS_NODEV|MS_NOSUID, "size=64m,mode=0771,uid=1000,gid=1000");
    directory("/data/cache", 0770);
    mount_checked("tmpfs", "/cache", "tmpfs", MS_NODEV|MS_NOSUID, "size=16m,mode=0770,uid=1000,gid=2001");
    fprintf(stderr, "RAYO12_STAGE1: verified readonly system; entering Android 12 SELinux/init setup\n");
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("LD_CONFIG_FILE");
    setenv("ANDROID_ROOT", "/system", 1);
    setenv("ANDROID_DATA", "/data", 1);
    char* const arguments[] = {"/system/bin/init", "selinux_setup", NULL};
    execv(arguments[0], arguments);
    fatal("exec Android 12 init");
}
