#include <binder/ProcessState.h>
#include <gui/ISurfaceComposerClient.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include <gui/Surface.h>
#include <cstdio>
#include <cstdint>
#include <unistd.h>

using namespace android;

int main() {
    // Native SurfaceFlinger composition test, not a launcher/framework UI.
    // A real queued buffer is required to leave SurfaceFlinger's BOOTLOADER
    // stage. Bufferless effect layers alone never trigger that transition.
    alarm(90);
    ProcessState::self()->startThreadPool();
    sp<SurfaceComposerClient> client = new SurfaceComposerClient;
    if (client->initCheck() != NO_ERROR) return 2;
    auto control = client->createSurface(String8("Rayo12GraphicsBufferTest"), 1280, 720,
                                         PIXEL_FORMAT_RGBA_8888, 0);
    if (!control || !control->isValid()) return 3;
    auto status = SurfaceComposerClient::Transaction{}.setLayer(control, 1000).show(control).apply(true);
    if (status != NO_ERROR) return 4;
    sp<Surface> surface = control->getSurface();
    ANativeWindow_Buffer buffer{};
    status = surface->lock(&buffer, nullptr);
    std::printf("Surface lock=%d size=%dx%d stride=%d format=%d\n", status,
                buffer.width, buffer.height, buffer.stride, buffer.format);
    if (status != NO_ERROR) return 5;
    if (buffer.width != 1280 || buffer.height != 720 || buffer.stride < 1280 ||
        buffer.stride > 4096 || buffer.format != PIXEL_FORMAT_RGBA_8888) {
        surface->unlockAndPost();
        return 6;
    }
    const uint32_t colors[] = {0xff0000ff, 0xff00ff00, 0xffff0000, 0xffffffff,
                                0xffffff00, 0xffff00ff, 0xff00ffff, 0xff000000};
    for (int y = 0; y < 720; ++y) {
        auto* row = static_cast<uint32_t*>(buffer.bits)+y*buffer.stride;
        for (int x = 0; x < 1280; ++x) row[x] = colors[x/160];
    }
    status = surface->unlockAndPost();
    std::printf("Surface unlockAndPost=%d\n", status);
    if (status != NO_ERROR) return 7;
    std::puts("SURFACE_BUFFER_POSTED; physical projection still requires observation");
    std::fflush(stdout);
    sleep(60);
    return 0;
}
