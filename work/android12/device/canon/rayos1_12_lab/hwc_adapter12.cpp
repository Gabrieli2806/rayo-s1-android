#include <dlfcn.h>
#include <vndksupport/linker.h>

/* Keep the verified factory ABI translation intact. Only route the factory
 * dependency graph to the isolated legacy namespace instead of the new C++
 * namespace. The portable build entry point copies the versioned 8.1 source. */
static void* rayo12_load_factory(const char* path, int flags) {
    return android_load_sphal_library(path, flags);
}
#define dlopen rayo12_load_factory
#include "hwc_adapter_legacy.cpp"
#undef dlopen
