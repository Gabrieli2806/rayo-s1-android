#include <ui/GraphicBuffer.h>
#include <cstdio>
#include <cstring>
#include <unistd.h>

/* Exercise the Android 12 client API and its public allocator transport.
 * No Surface, display, composer device, or service registration is used. */
int main() {
    alarm(15);
    constexpr uint64_t usage = GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN;
    android::sp<android::GraphicBuffer> buffer = new android::GraphicBuffer(
        64, 64, android::PIXEL_FORMAT_RGBA_8888, 1, usage, "Rayo12TransportProbe");
    const auto status = buffer->initCheck();
    std::printf("GraphicBuffer init=%d stride=%u\n", status, buffer->getStride());
    if (status != android::NO_ERROR) return 2;
    void* address = nullptr;
    const auto locked = buffer->lock(static_cast<uint32_t>(usage), &address);
    if (locked != android::NO_ERROR || !address) return 3;
    const unsigned char expected[4] = {71, 19, 203, 255};
    std::memcpy(address, expected, sizeof(expected));
    const bool matched = std::memcmp(address, expected, sizeof(expected)) == 0;
    const auto unlocked = buffer->unlock();
    buffer.clear();
    const bool pass = matched && unlocked == android::NO_ERROR;
    std::puts(pass ? "GRAPHICBUFFER_TRANSPORT_PASS" : "GRAPHICBUFFER_TRANSPORT_FAIL");
    return pass ? 0 : 4;
}
