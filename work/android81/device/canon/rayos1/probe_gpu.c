#include <stdio.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#define LOAD(name) __typeof__(&name) fn_##name = dlsym(driver, #name); \
    if (!fn_##name) { fprintf(stderr, "Missing %s\n", #name); return 2; }

int main(void) {
    void *driver = dlopen("/vendor/lib/egl/libGLES_mali.so", RTLD_NOW | RTLD_LOCAL);
    if (!driver) { fprintf(stderr, "%s\n", dlerror()); return 2; }
    LOAD(eglGetDisplay); LOAD(eglInitialize); LOAD(eglGetError);
    LOAD(eglChooseConfig); LOAD(eglCreatePbufferSurface); LOAD(eglCreateContext);
    LOAD(eglMakeCurrent); LOAD(eglDestroyContext); LOAD(eglDestroySurface);
    LOAD(eglTerminate); LOAD(eglQueryString);
    LOAD(glClearColor); LOAD(glClear); LOAD(glReadPixels); LOAD(glGetString);
    EGLDisplay display = fn_eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major = 0, minor = 0;
    if (!fn_eglInitialize(display, &major, &minor)) {
        fprintf(stderr, "EGL_INIT_FAILED error=0x%x\n", fn_eglGetError()); return 3;
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
    return result;
}
