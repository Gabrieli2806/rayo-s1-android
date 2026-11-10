/*
 * Copyright (C) 2026 Rayo S1 port contributors
 * Licensed under the Apache License, Version 2.0.
 *
 * Diagnostic compatibility transport for this board's FunctionFS driver.
 * Synchronous endpoint I/O and CLEAR_HALT/dup2 cancellation follow AOSP
 * system/core adb/usb_linux_client.cpp, android-7.1.2_r39 (Apache-2.0).
 * Android 12 packet dispatch remains in BlockingConnectionAdapter.
 * Selected only by the explicit ro.rayo.adb.legacy_ffs property.
 */
#pragma once

#include <atomic>
#include <fcntl.h>

struct RayoUsbSyncConnection final : public BlockingConnection {
    RayoUsbSyncConnection(unique_fd control, unique_fd input, unique_fd output,
                          std::promise<void> notifier)
        : control_(std::move(control)), input_(std::move(input)), output_(std::move(output)),
          dummy_(adb_open("/dev/null", O_RDWR | O_CLOEXEC)), notifier_(std::move(notifier)) {
        CHECK_NE(dummy_.get(), -1);
        LOG(INFO) << "Rayo synchronous FunctionFS transport selected";
    }

    ~RayoUsbSyncConnection() override {
        Close();
        notifier_.set_value();
    }

    bool Read(apacket* packet) override {
        if (!Transfer(input_.get(), &packet->msg, sizeof(packet->msg), false)) return false;
        if (packet->msg.magic != (packet->msg.command ^ 0xffffffffU) ||
            packet->msg.data_length > MAX_PAYLOAD) return false;
        packet->payload.resize(packet->msg.data_length);
        return Transfer(input_.get(), packet->payload.data(), packet->payload.size(), false);
    }

    bool Write(apacket* packet) override {
        return Transfer(output_.get(), &packet->msg, sizeof(packet->msg), true) &&
               Transfer(output_.get(), packet->payload.data(), packet->payload.size(), true);
    }

    bool DoTlsHandshake(RSA*, std::string*) override { return false; }

    void Close() override {
        if (closed_.exchange(true)) return;
        // Preserve the cancellation sequence used by the board's old daemon.
        ioctl(input_.get(), FUNCTIONFS_CLEAR_HALT);
        ioctl(output_.get(), FUNCTIONFS_CLEAR_HALT);
        TEMP_FAILURE_RETRY(dup2(dummy_.get(), input_.get()));
        TEMP_FAILURE_RETRY(dup2(dummy_.get(), output_.get()));
    }

    void Reset() override { Close(); }

  private:
    bool Transfer(int fd, void* data, size_t size, bool writing) {
        auto* bytes = static_cast<char*>(data);
        while (size && !closed_.load()) {
            size_t chunk = std::min(size, size_t{16384});
            ssize_t count = writing ? TEMP_FAILURE_RETRY(adb_write(fd, bytes, chunk))
                                    : TEMP_FAILURE_RETRY(adb_read(fd, bytes, chunk));
            if (count <= 0) return false;
            bytes += count;
            size -= static_cast<size_t>(count);
        }
        return size == 0 && !closed_.load();
    }

    unique_fd control_, input_, output_, dummy_;
    std::promise<void> notifier_;
    std::atomic<bool> closed_{false};
};
