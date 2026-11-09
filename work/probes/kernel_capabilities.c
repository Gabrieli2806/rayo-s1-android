/* Read-only queries and temporary anonymous objects; no mounts or persistent writes. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/bpf.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <unistd.h>

static void result(const char *name, long value, int error, int comma) {
    printf("  \"%s\": {\"result\": %ld, \"errno\": %d}%s\n",
           name, value, error, comma ? "," : "");
}

static void binder_version(const char *path, int comma) {
    int descriptor = open(path, O_RDWR | O_CLOEXEC);
    int error = descriptor < 0 ? errno : 0;
    struct { int protocol_version; } version = {0};
    long value = -1;
    if (descriptor >= 0) {
        errno = 0;
        int status = ioctl(descriptor, _IOWR('b', 9, version), &version);
        error = status < 0 ? errno : 0;
        if (status >= 0) value = version.protocol_version;
        close(descriptor);
    }
    result(path, value, error, comma);
}

int main(void) {
    struct utsname name;
    if (uname(&name)) return 1;
    printf("{\n  \"kernel\": \"%s\",\n  \"userspace_pointer_bits\": %zu,\n",
           name.release, sizeof(void *) * 8);
    errno = 0;
    long value = prctl(PR_GET_SECCOMP, 0, 0, 0, 0);
    int error = value < 0 ? errno : 0;
    result("pr_get_seccomp", value, error, 1);
    errno = 0;
    value = prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0);
    error = value < 0 ? errno : 0;
    result("pr_get_no_new_privs", value, error, 1);
    errno = 0;
    char unused;
    value = syscall(__NR_getrandom, &unused, 0, 1);
    error = value < 0 ? errno : 0;
    result("getrandom_zero_length", value, error, 1);
    errno = 0;
    value = syscall(__NR_memfd_create, "rayo-capability-probe", 1);
    error = value < 0 ? errno : 0;
    if (value >= 0) close((int)value);
    result("memfd_create", value >= 0 ? 0 : value, error, 1);
    union bpf_attr attribute;
    memset(&attribute, 0, sizeof(attribute));
    attribute.map_type = BPF_MAP_TYPE_ARRAY;
    attribute.key_size = sizeof(int);
    attribute.value_size = sizeof(int);
    attribute.max_entries = 1;
    errno = 0;
    value = syscall(__NR_bpf, BPF_MAP_CREATE, &attribute, sizeof(attribute));
    error = value < 0 ? errno : 0;
    if (value >= 0) close((int)value);
    result("bpf_array_map_create", value >= 0 ? 0 : value, error, 1);
    binder_version("/dev/binder", 1);
    binder_version("/dev/hwbinder", 1);
    binder_version("/dev/vndbinder", 0);
    printf("}\n");
    return 0;
}
