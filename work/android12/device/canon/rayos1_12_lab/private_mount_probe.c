#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <unistd.h>

/* NDK static ARM startup requires a TLS segment aligned for Bionic's TCB. */
__asm__(".pushsection .tdata,\"awT\",%progbits\n.p2align 5\n.byte 0\n.popsection\n");

/* Disposable child mount view only. Never change the parent's mount namespace. */
int main(int argc, char** argv) {
    char root[PATH_MAX], legacy[PATH_MAX], libs[PATH_MAX], linker[PATH_MAX], probe[PATH_MAX], vendor_hw[PATH_MAX];
    if (argc != 2 || getuid() != 0 || !realpath(argv[1], root) ||
        strncmp(root, "/data/local/tmp/rayo12-graphics-", 32) != 0 ||
        strchr(root + 32, '/')) {
        fprintf(stderr, "Expected the guarded private graphics directory and root child\n");
        return 2;
    }
    if (strlen(root) > PATH_MAX - 64) return 2;
    /* The alarm survives exec and bounds the disposable graphics process. */
    alarm(20);
    snprintf(legacy, sizeof(legacy), "%s/legacy", root);
    snprintf(libs, sizeof(libs), "%s/lib", root);
    snprintf(linker, sizeof(linker), "%s/bin/linker", root);
    snprintf(probe, sizeof(probe), "%s/bin/rayo12-gpu-probe", root);
    snprintf(vendor_hw, sizeof(vendor_hw), "%s/vendor-hw", root);
    if (unshare(CLONE_NEWNS) != 0) { perror("unshare mount namespace"); return 3; }
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
        perror("isolate mount propagation"); return 4;
    }
    if (mount("/system/lib", legacy, NULL, MS_BIND, NULL) != 0 ||
        mount(NULL, legacy, NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL) != 0 ||
        mount(libs, "/system/lib", NULL, MS_BIND, NULL) != 0 ||
        mount(NULL, "/system/lib", NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL) != 0) {
        perror("private read-only library bind"); return 5;
    }
    struct stat vendor_info;
    if (stat(vendor_hw, &vendor_info) == 0 && S_ISDIR(vendor_info.st_mode)) {
        if (mount(vendor_hw, "/vendor/lib/hw", NULL, MS_BIND, NULL) != 0 ||
            mount(NULL, "/vendor/lib/hw", NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL) != 0) {
            perror("private vendor HAL directory bind"); return 5;
        }
    }
    char* const child[] = {linker, probe, NULL};
    execv(linker, child);
    perror("exec private Android 12 linker");
    return 6;
}
