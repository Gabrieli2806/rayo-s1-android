#include <binder/ProcessState.h>
#include <gui/ISurfaceComposerClient.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include <gui/Surface.h>
#include <gui/SyncScreenCaptureListener.h>
#include <cstdio>
#include <cstdint>
#include <unistd.h>

using namespace android;

static void captureSamples() {
    auto id = SurfaceComposerClient::getInternalDisplayId();
    if (!id) { std::puts("CAPTURE_NO_DISPLAY"); return; }
    sp<SyncScreenCaptureListener> listener = new SyncScreenCaptureListener();
    auto status = ScreenshotClient::captureDisplay(id->value, listener);
    if (status != NO_ERROR) { std::printf("capture request=%d\n", status); return; }
    auto result = listener->waitForResults();
    if (result.result != NO_ERROR || !result.buffer) {
        std::printf("capture result=%d\n", result.result); return;
    }
    auto buffer = result.buffer;
    void* pixels = nullptr;
    status = buffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN, &pixels);
    std::printf("capture lock=%d size=%ux%u stride=%u format=%d\n", status,
                buffer->getWidth(), buffer->getHeight(), buffer->getStride(), buffer->getPixelFormat());
    if (status == NO_ERROR && pixels) {
        if (buffer->getWidth() == 1280 && buffer->getHeight() == 720 &&
            buffer->getPixelFormat() == PIXEL_FORMAT_RGBA_8888) {
            auto* row = static_cast<uint32_t*>(pixels)+360*buffer->getStride();
            std::printf("CAPTURE_SAMPLES");
            for (int x = 80; x < 1280; x += 160) std::printf(" %08x", row[x]);
            std::putchar('\n');
        }
        buffer->unlock();
    }
    std::fflush(stdout);
}

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
    const uint32_t colors[] = {0xff0000ff, 0xff00ff00, 0xffff0000, 0xffffffff,
                                0xffffff00, 0xffff00ff, 0xff00ffff, 0xff000000};
    for (int frame = 0; frame < 60; ++frame) {
        ANativeWindow_Buffer buffer{};
        status = surface->lock(&buffer, nullptr);
        if (status != NO_ERROR) { std::printf("Surface lock=%d\n", status); return 5; }
        if (buffer.width != 1280 || buffer.height != 720 || buffer.stride < 1280 ||
            buffer.stride > 4096 || buffer.format != PIXEL_FORMAT_RGBA_8888) {
            surface->unlockAndPost();
            return 6;
        }
        for (int y = 0; y < 720; ++y) {
            auto* row = static_cast<uint32_t*>(buffer.bits)+y*buffer.stride;
            for (int x = 0; x < 1280; ++x) row[x] = colors[x/160];
            if (y > 670 && frame % 2) for (int x = 1220; x < 1260; ++x) row[x] = 0xffffffff;
        }
        status = surface->unlockAndPost();
        if (status != NO_ERROR) return 7;
        if (frame == 0) {
            std::puts("SURFACE_BUFFER_POSTED; physical projection still requires observation");
            std::fflush(stdout);
        }
        sleep(1);
        if (frame == 2) captureSamples();
    }
    return 0;
}
