/* Android 12 native userspace check. No mounts, sysctls, or persistent writes. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/random.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <time.h>
#include <unistd.h>

static void* thread_check(void* value) {
    void* memory = malloc(128);
    if (!memory) return NULL;
    memset(memory, 0x5a, 128);
    free(memory);
    return value;
}

static int binder_protocol(const char* path) {
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) return -1;
    struct { int protocol_version; } version = {0};
    int result = ioctl(fd, _IOWR('b', 9, version), &version);
    close(fd);
    return result ? -1 : version.protocol_version;
}

int main(void) {
    struct utsname identity;
    if (uname(&identity)) return 1;
    unsigned char random_bytes[32];
    errno = 0;
    int entropy = getentropy(random_bytes, sizeof(random_bytes));
    int entropy_errno = entropy ? errno : 0;
    errno = 0;
    long random_syscall = syscall(__NR_getrandom, random_bytes, 0, 1);
    int random_errno = random_syscall < 0 ? errno : 0;
    pthread_t thread;
    uintptr_t token = 0x3128;
    void* result = NULL;
    int thread_error = pthread_create(&thread, NULL, thread_check, (void*)token);
    if (!thread_error) thread_error = pthread_join(thread, &result);
    int thread_ok = !thread_error && (uintptr_t)result == token;
    struct timespec now;
    int clock_ok = clock_gettime(CLOCK_MONOTONIC, &now) == 0;
    void* math = dlopen("libm.so", RTLD_NOW | RTLD_LOCAL);
    int math_ok = math && dlsym(math, "cos") != NULL;
    if (math) dlclose(math);
    Dl_info libc_info = {0};
    int libc_identified = dladdr((void*)getentropy, &libc_info) != 0 && libc_info.dli_fname;
    printf("{\n  \"probe\": \"aosp12-native-bootstrap\",\n"
           "  \"kernel\": \"%s\",\n  \"pointer_bits\": %zu,\n"
           "  \"getentropy_result\": %d,\n  \"getentropy_errno\": %d,\n"
           "  \"getrandom_syscall\": %ld,\n  \"getrandom_errno\": %d,\n"
           "  \"thread_heap_ok\": %s,\n  \"thread_error\": %d,\n"
           "  \"monotonic_clock_ok\": %s,\n  \"libm_load_ok\": %s,\n"
           "  \"libc_object\": \"%s\",\n"
           "  \"binder_protocol\": %d,\n  \"hwbinder_protocol\": %d\n}\n",
           identity.release, sizeof(void*) * 8, entropy, entropy_errno,
           random_syscall, random_errno, thread_ok ? "true" : "false", thread_error,
           clock_ok ? "true" : "false", math_ok ? "true" : "false",
           libc_identified ? libc_info.dli_fname : "unidentified",
           binder_protocol("/dev/binder"), binder_protocol("/dev/hwbinder"));
    return entropy || !thread_ok || !clock_ok || !math_ok || !libc_identified;
}
