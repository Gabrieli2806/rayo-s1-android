#include <android/hardware/graphics/composer/2.1/IComposer.h>
#include <android/hardware/graphics/composer/2.1/IComposerCallback.h>
#include <android/hardware/graphics/composer/2.1/IComposerClient.h>
#include <hidl/HidlTransportSupport.h>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <unistd.h>

using namespace android::hardware;
using namespace android::hardware::graphics::composer::V2_1;
using android::sp;

struct Callback : public IComposerCallback {
    std::mutex mutex;
    std::condition_variable changed;
    bool connected = false;
    uint64_t display = 0;
    Return<void> onHotplug(uint64_t id, Connection state) override {
        std::lock_guard<std::mutex> lock(mutex);
        if (state == Connection::CONNECTED) {
            display = id;
            connected = true;
            changed.notify_all();
        }
        return Void();
    }
    Return<void> onRefresh(uint64_t) override { return Void(); }
    Return<void> onVsync(uint64_t, int64_t) override { return Void(); }
};

int main() {
    // Query only. No layer, buffer submission, power or display-mode changes.
    alarm(18);
    configureRpcThreadpool(2, false);
    sp<IComposer> service = IComposer::getService();
    if (!service) { std::puts("No composer service"); return 2; }
    sp<IComposerClient> client;
    auto created = service->createClient([&](Error error, const sp<IComposerClient>& value) {
        if (error == Error::NONE) client = value;
        std::printf("createClient=%d\n", static_cast<int>(error));
    });
    if (!created.isOk() || !client) return 3;
    sp<Callback> callback = new Callback();
    if (!client->registerCallback(callback).isOk()) return 4;
    uint64_t display;
    {
        std::unique_lock<std::mutex> lock(callback->mutex);
        if (!callback->changed.wait_for(lock, std::chrono::seconds(5), [&] { return callback->connected; })) return 5;
        display = callback->display;
    }
    uint32_t config = 0;
    bool have_config = false;
    auto configs = client->getDisplayConfigs(display, [&](Error error, const hidl_vec<uint32_t>& values) {
        if (error == Error::NONE && values.size() > 0) { config = values[0]; have_config = true; }
    });
    if (!configs.isOk() || !have_config) return 6;
    int32_t width = 0, height = 0;
    auto w = client->getDisplayAttribute(display, config, IComposerClient::Attribute::WIDTH,
        [&](Error error, int32_t value) { if (error == Error::NONE) width = value; });
    auto h = client->getDisplayAttribute(display, config, IComposerClient::Attribute::HEIGHT,
        [&](Error error, int32_t value) { if (error == Error::NONE) height = value; });
    std::printf("display=%llu config=%u size=%dx%d\n", static_cast<unsigned long long>(display), config, width, height);
    if (!w.isOk() || !h.isOk() || width != 1280 || height != 720) return 7;
    std::puts("COMPOSER_QUERY_PASS");
    return 0;
}
