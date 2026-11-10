/* Failed diagnostic experiment, retained as evidence only. Do not deploy.
 * Selected integration uses libvndksupport and separate linker namespaces. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>

struct android_namespace_t;
typedef struct android_namespace_t* (*namespace_query)(const char*);

struct android_namespace_t* android_get_exported_namespace(const char* name) {
    void* handle = dlopen("libdl_android.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) return NULL;
    namespace_query query = (namespace_query)dlvsym(
        handle, "android_get_exported_namespace", "LIBDL_ANDROID");
    return query ? query(name) : NULL;
}
