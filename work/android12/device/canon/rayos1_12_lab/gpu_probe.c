#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <string.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#define LOAD(name) __typeof__(&name) fn_##name = dlsym(#name[0] == 'g' ? gles : driver, #name); \
    if (!fn_##name) { fprintf(stderr, "Missing %s\n", #name); return 2; }

int main(void) {
    void *support = dlopen("libvndksupport.so", RTLD_NOW | RTLD_LOCAL);
    if (!support) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    void* (*load_sphal)(const char*, int) = dlsym(support, "android_load_sphal_library");
    if (!load_sphal) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    if (getenv("RAYO_HWC_LOAD_ONLY")) {
        void* factory = load_sphal("/system/lib/hw/hwcomposer.rk30board.factory.so", RTLD_NOW | RTLD_LOCAL);
        if (!factory || !dlsym(factory, "HMI")) { fprintf(stderr, "%s\n", dlerror()); return 2; }
        void* adapter = dlopen("hwcomposer.rayo12.so", RTLD_NOW | RTLD_LOCAL);
        if (!adapter || !dlsym(adapter, "HMI")) { fprintf(stderr, "%s\n", dlerror()); return 2; }
        /* Intentionally do not open the HWC device, register callbacks,
         * prepare/set layers, change a mode, or submit a framebuffer. */
        puts("FACTORY_HWC_LOAD_PASS");
        return 0;
    }
    const int platform = getenv("RAYO_USE_PLATFORM_EGL") != NULL;
    void *driver = platform ? dlopen("/system/lib/libEGL.so", RTLD_NOW | RTLD_LOCAL) :
        load_sphal("/vendor/lib/egl/libGLES_mali.so", RTLD_NOW | RTLD_LOCAL);
    if (!driver) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    void *gles = platform ? dlopen("/system/lib/libGLESv2.so", RTLD_NOW | RTLD_LOCAL) : driver;
    if (!gles) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    LOAD(eglGetDisplay); LOAD(eglInitialize); LOAD(eglGetError);
    LOAD(eglChooseConfig); LOAD(eglCreatePbufferSurface); LOAD(eglCreateContext);
    LOAD(eglMakeCurrent); LOAD(eglDestroyContext); LOAD(eglDestroySurface);
    LOAD(eglTerminate); LOAD(eglQueryString);
    LOAD(glClearColor); LOAD(glClear); LOAD(glReadPixels); LOAD(glGetString);
    EGLDisplay display = fn_eglGetDisplay(EGL_DEFAULT_DISPLAY);
    printf("EGL display=%p platform=%d\n", (void*)display, platform);
    EGLint major = 0, minor = 0;
    if (!fn_eglInitialize(display, &major, &minor)) {
        fprintf(stderr, "EGL_INIT_FAILED error=0x%x\n", fn_eglGetError());
        FILE* maps = fopen("/proc/self/maps", "r");
        if (maps) { char line[1024]; while (fgets(line, sizeof(line), maps)) {
            if (strstr(line, "libEGL.so") || strstr(line, "libGLES_mali.so")) fputs(line, stdout);
        } fclose(maps); }
        return 3;
    }
    printf("EGL %d.%d vendor=%s\n", major, minor, fn_eglQueryString(display, EGL_VENDOR));
    const EGLint attributes[] = {EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,
        EGL_BLUE_SIZE,8,EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLContext context = EGL_NO_CONTEXT;
    int result = 4;
    if (!fn_eglChooseConfig(display, attributes, &config, 1, &count) || count < 1) goto cleanup;
    const EGLint dimensions[] = {EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
    const EGLint version[] = {EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    surface = fn_eglCreatePbufferSurface(display, config, dimensions);
    context = fn_eglCreateContext(display, config, EGL_NO_CONTEXT, version);
    if (surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT ||
        !fn_eglMakeCurrent(display, surface, surface, context)) goto cleanup;
    printf("GL renderer=%s version=%s\n", fn_glGetString(GL_RENDERER), fn_glGetString(GL_VERSION));
    fn_glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
    fn_glClear(GL_COLOR_BUFFER_BIT);
    unsigned char pixel[4] = {0};
    fn_glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixel);
    printf("PIXEL rgba=%u,%u,%u,%u\n", pixel[0],pixel[1],pixel[2],pixel[3]);
    result = pixel[0] < 5 && pixel[1] > 250 && pixel[2] < 5 ? 0 : 5;
    printf("GPU_PBUFFER_%s\n", result == 0 ? "PASS" : "FAIL");
cleanup:
    if (result == 4) fprintf(stderr,"EGL_SETUP_FAILED error=0x%x\n",fn_eglGetError());
    fn_eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    if (context != EGL_NO_CONTEXT) fn_eglDestroyContext(display,context);
    if (surface != EGL_NO_SURFACE) fn_eglDestroySurface(display,surface);
    fn_eglTerminate(display);
    FILE* maps = fopen("/proc/self/maps", "r");
    if (maps) {
        char line[1024];
        while (fgets(line, sizeof(line), maps)) {
            if (strstr(line, "libc++.so") || strstr(line, "libGLES_mali.so") ||
                strstr(line, "libEGL.so") || strstr(line, "libvndksupport.so")) fputs(line, stdout);
        }
        fclose(maps);
    }
    return result;
}
