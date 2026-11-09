#include <stdio.h>
#include <dlfcn.h>
#include <link.h>
#include <hardware/hardware.h>

static int describe_library(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    (void)data;
    printf("MAPPED %s\n", info->dlpi_name);
    return 0;
}

int main(int argc, char **argv) {
    int failures = 0;
    printf("Rayo native ABI probe, built from Android 8.1 sources.\n");
    if (argc < 2) {
        fprintf(stderr, "Pass the candidate HAL library paths. No hardware open callbacks are invoked.\n");
        return 2;
    }
    for (int i = 1; i < argc; i++) {
        void *handle = dlopen(argv[i], RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            fprintf(stderr, "LOAD_FAILED %s: %s\n", argv[i], dlerror());
            failures++;
            continue;
        }
        printf("LOAD_OK %s\n", argv[i]);
        const struct hw_module_t *module = dlsym(handle, HAL_MODULE_INFO_SYM_AS_STR);
        if (module) {
            printf("HAL id=%s module_api=0x%04x hal_api=0x%04x\n",
                   module->id ? module->id : "?", module->module_api_version, module->hal_api_version);
        }
        /* Keep the library mapped until process exit; do not open hardware. */
    }
    dl_iterate_phdr(describe_library, NULL);
    return failures ? 1 : 0;
}
