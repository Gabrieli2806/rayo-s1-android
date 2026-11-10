#include <ui/GraphicBuffer.h>
#include <cstdio>
#include <cstring>
#include <unistd.h>
#include <sys/system_properties.h>

/* Exercise the Android 12 client API and its public allocator transport.
 * No Surface, display, composer device, or service registration is used. */
int main(int argc, char** argv) {
    alarm(15);
    uint64_t usage = GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN;
    int width = 64, height = 64;
    android::PixelFormat format = android::PIXEL_FORMAT_RGBA_8888;
    bool hardware = argc == 2;
    if (argc > 2) return 64;
    if (hardware) {
        char marker[PROP_VALUE_MAX] = {0};
        __system_property_get("ro.rayo.trial", marker);
        if (std::strcmp(marker, "12-stage1")) return 64;
        width = 1280; height = 720;
        usage = GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE | GRALLOC_USAGE_HW_COMPOSER | GRALLOC_USAGE_HW_FB;
        if (!std::strcmp(argv[1], "--hw-bgra")) format = android::PIXEL_FORMAT_BGRA_8888;
        else if (!std::strcmp(argv[1], "--hw-rgba-no-fb")) usage &= ~GRALLOC_USAGE_HW_FB;
        else if (std::strcmp(argv[1], "--hw-rgba")) return 64;
    }
    android::sp<android::GraphicBuffer> buffer = new android::GraphicBuffer(
        width, height, format, 1, usage, "Rayo12TransportProbe");
    const auto status = buffer->initCheck();
    std::printf("GraphicBuffer init=%d stride=%u\n", status, buffer->getStride());
    if (status != android::NO_ERROR) return 2;
    if (hardware) {
        std::printf("HARDWARE_BUFFER_ALLOCATION_PASS format=%d usage=%llx\n", format, static_cast<unsigned long long>(usage));
        return 0;
    }
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
