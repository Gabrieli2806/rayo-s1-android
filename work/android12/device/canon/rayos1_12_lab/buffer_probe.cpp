#define LOG_TAG "Rayo12BufferProbe"
#include <allocator-passthrough/2.0/GrallocLoader.h>
#include <mapper-passthrough/2.0/GrallocLoader.h>
#include <cstdio>
#include <cstring>
#include <unistd.h>

namespace M = android::hardware::graphics::mapper::V2_0;
namespace A = android::hardware::graphics::allocator::V2_0;
namespace C = android::hardware::graphics::common::V1_0;
using android::hardware::hidl_handle;

/* Local passthrough calls only: no HAL registration or display submission. */
int main() {
    alarm(15);
    android::sp<M::IMapper> mapper = M::passthrough::GrallocLoader::load();
    android::sp<A::IAllocator> allocator = A::passthrough::GrallocLoader::load();
    if (!mapper || !allocator) { std::fprintf(stderr, "GRALLOC_LOAD_FAILED\n"); return 2; }
    M::IMapper::BufferDescriptorInfo info{};
    info.width = 64;
    info.height = 64;
    info.layerCount = 1;
    info.format = C::PixelFormat::RGBA_8888;
    info.usage = static_cast<uint64_t>(C::BufferUsage::CPU_READ_OFTEN) |
                 static_cast<uint64_t>(C::BufferUsage::CPU_WRITE_OFTEN);
    M::BufferDescriptor descriptor;
    M::Error error = M::Error::NO_RESOURCES;
    auto made = mapper->createDescriptor(info, [&](M::Error e, const auto& d) { error = e; descriptor = d; });
    if (!made.isOk() || error != M::Error::NONE) return 3;
    void* imported = nullptr;
    uint32_t stride = 0;
    auto allocated = allocator->allocate(descriptor, 1, [&](M::Error e, uint32_t s, const auto& handles) {
        error = e;
        stride = s;
        if (e != M::Error::NONE || handles.size() != 1) return;
        auto result = mapper->importBuffer(handles[0], [&](M::Error ie, void* buffer) { error = ie; imported = buffer; });
        if (!result.isOk()) error = M::Error::NO_RESOURCES;
    });
    if (!allocated.isOk() || error != M::Error::NONE || !imported || stride < 64) return 4;
    M::IMapper::Rect rect{0, 0, 64, 64};
    bool matched = false;
    auto locked = mapper->lock(imported, info.usage, rect, hidl_handle{}, [&](M::Error e, void* data) {
        error = e;
        if (e == M::Error::NONE && data) {
            auto* pixels = static_cast<unsigned char*>(data);
            // Touch one pixel only; this does not assume an undocumented size.
            const unsigned char value[4] = {17, 201, 63, 255};
            std::memcpy(pixels, value, sizeof(value));
            matched = std::memcmp(pixels, value, sizeof(value)) == 0;
        }
    });
    const bool lock_ok = locked.isOk() && error == M::Error::NONE;
    bool unlock_ok = false;
    if (lock_ok) {
        auto unlocked = mapper->unlock(imported, [&](M::Error e, const hidl_handle&) { unlock_ok = e == M::Error::NONE; });
        unlock_ok = unlock_ok && unlocked.isOk();
    }
    auto freed = mapper->freeBuffer(imported);
    const bool free_ok = freed.isOk() && static_cast<M::Error>(freed) == M::Error::NONE;
    std::printf("stride=%u lock=%d pixel=%d unlock=%d free=%d\n", stride, lock_ok, matched, unlock_ok, free_ok);
    const bool pass = lock_ok && matched && unlock_ok && free_ok;
    std::puts(pass ? "GRALLOC_BUFFER_PASS" : "GRALLOC_BUFFER_FAIL");
    return pass ? 0 : 5;
}
