#include <dlfcn.h>
#include <stdio.h>

/* Load/resolve only: do not call vendor module or component entry points. */
int main(int argc, char **argv) {
    if (argc < 2) return 2;
    int failures = 0;
    for (int i = 1; i < argc; ++i) {
        void *library = dlopen(argv[i], RTLD_NOW | RTLD_LOCAL);
        if (library) printf("LOAD_OK %s\n", argv[i]);
        else { printf("LOAD_FAILED %s: %s\n", argv[i], dlerror()); failures++; }
    }
    return failures ? 1 : 0;
}
