/**
 * haptic_usb.cpp - DualSense 触觉反馈 USB 等时传输层
 *
 * 为什么需要这个文件？
 * Android 的 Java USB API 只支持 bulk/control/interrupt 三种传输方式，
 * 但 DualSense 的触觉音频端点是 isochronous（等时）端点，
 * 必须通过 libusb 在 native 层直接操作才能发送数据。
 *
 * 工作原理：
 * 1. Java 层通过 Android USB API 打开设备，拿到文件描述符(fd)
 * 2. 这里用 libusb_wrap_sys_device() 把 fd 包装成 libusb 设备句柄
 * 3. 然后就可以用 libusb 的等时传输 API 发送触觉音频数据了
 *
 * JNI 方法对应关系（Java 类: com.peasyo.input.HapticNative）：
 * - nativeConnectHaptics  → 初始化 libusb，打开等时端点
 * - nativeEnableHaptics   → 启用触觉数据发送
 * - nativeSendHapticFeedback → 发送一帧触觉音频数据（等时传输）
 * - nativeCleanupHaptics  → 释放资源
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <vector>
#include <ctime>

#define TAG "PS5HAPTIC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef HAVE_LIBUSB
#include "libusb.h"
#endif

namespace {

// PS5 触觉 PCM: 3kHz, stereo, 16-bit, interleaved.
// DualSense USB 触觉流目标: 48kHz, 4ch, 16-bit.
constexpr int kInputChannels = 2;
constexpr int kOutputChannels = 4;
constexpr int kBytesPerInputFrame = kInputChannels * (int)sizeof(int16_t);  // 4 bytes
constexpr int kUpsampleFactor = 16;  // 3k -> 48k

// DualSense 等时端点打包参数（参考实现）：10 个包，每包 392 字节。
constexpr int kIsoPacketCount = 10;
constexpr int kIsoPacketSize = 392;

inline int16_t clamp_i16(int value)
{
    if (value > 32767) return 32767;
    if (value < -32768) return -32768;
    return (int16_t)value;
}

uint64_t monotonic_ms()
{
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000ULL);
}

// 参考 app 中的通道映射：quad 的前两路置 0，后两路放 L/R。
// out layout: [0, 0, L, R]
void expand_stereo_to_quad(const int16_t *input, int frame_count, int16_t *output)
{
    for (int i = 0; i < frame_count; ++i) {
        const int16_t left = input[i * 2];
        const int16_t right = input[i * 2 + 1];
        output[i * 4] = 0;
        output[i * 4 + 1] = 0;
        output[i * 4 + 2] = left;
        output[i * 4 + 3] = right;
    }
}

// 线性上采样：3kHz -> 48kHz（x16），4 通道一起插值。
void linear_upsample_3k_to_48k(const int16_t *input, int input_frames, int16_t *output)
{
    if (input_frames <= 0) return;

    const int last = input_frames - 1;
    const int output_frames = input_frames * kUpsampleFactor;

    for (int out_idx = 0; out_idx < output_frames; ++out_idx) {
        const int src_idx = out_idx / kUpsampleFactor;
        const int phase = out_idx % kUpsampleFactor;
        const int next_idx = (src_idx < last) ? (src_idx + 1) : src_idx;

        for (int ch = 0; ch < kOutputChannels; ++ch) {
            const int s0 = input[src_idx * kOutputChannels + ch];
            const int s1 = input[next_idx * kOutputChannels + ch];
            const int interpolated = ((kUpsampleFactor - phase) * s0 + phase * s1) / kUpsampleFactor;
            output[out_idx * kOutputChannels + ch] = clamp_i16(interpolated);
        }
    }
}

} // namespace

// ========== 全局状态 ==========
// 用互斥锁保护，因为 Java 层可能从不同线程调用这些方法
static std::mutex g_mutex;

#ifdef HAVE_LIBUSB
static libusb_context *g_ctx = nullptr;      // libusb 上下文
static libusb_device_handle *g_handle = nullptr; // USB 设备句柄
static int g_iface_id = -1;                  // 音频接口 ID
static uint8_t g_ep_addr = 0;               // 等时端点地址
static bool g_enabled = false;               // 是否已启用发送
static uint64_t g_send_ok_count = 0;
static uint64_t g_send_fail_count = 0;
static uint64_t g_send_last_log_ms = 0;
#endif

extern "C" {

// ========== 连接触觉端点 ==========
// 参数：
//   fd         - Android USB 文件描述符（从 UsbDeviceConnection.getFileDescriptor() 获取）
//   ifaceId    - 音频接口的 ID（包含等时端点的那个接口）
//   altSetting - 接口的 alternate setting
//   epAddr     - 等时 OUT 端点的地址（通常是 0x01 之类的值）
JNIEXPORT jboolean JNICALL
Java_com_peasyo_input_HapticNative_nativeConnectHaptics(
        JNIEnv *env, jclass, jint fd, jint ifaceId, jint altSetting, jbyte epAddr) {
#ifdef HAVE_LIBUSB
    std::lock_guard<std::mutex> lock(g_mutex);

    // 告诉 libusb 不要自己扫描设备，我们会直接给它文件描述符
    int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_set_option failed: %s", libusb_error_name(rc));
    }

    // 初始化 libusb
    rc = libusb_init(&g_ctx);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_init failed: %s", libusb_error_name(rc));
        return JNI_FALSE;
    }

    // 用 Android 的文件描述符包装成 libusb 设备句柄
    // 这是 Android 上使用 libusb 的关键技巧：
    // Android 不允许直接访问 USB 设备文件，但 Java 层拿到权限后
    // 可以通过 UsbDeviceConnection 获取 fd，再传给 libusb
    rc = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &g_handle);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_wrap_sys_device failed: %s", libusb_error_name(rc));
        libusb_exit(g_ctx);
        g_ctx = nullptr;
        return JNI_FALSE;
    }

    // 如果内核驱动占用了这个接口，先把它踢掉
    if (libusb_kernel_driver_active(g_handle, ifaceId) == 1) {
        libusb_detach_kernel_driver(g_handle, ifaceId);
    }

    // 声明接口所有权（Java 层已经 claim 过了，这里再 claim 一次
    // 让 libusb 内部状态同步，同一个 fd 重复 claim 在 Linux 上是安全的）
    rc = libusb_claim_interface(g_handle, ifaceId);
    if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_BUSY) {
        LOGE("libusb_claim_interface(%d) failed: %s", ifaceId, libusb_error_name(rc));
        libusb_close(g_handle);
        g_handle = nullptr;
        libusb_exit(g_ctx);
        g_ctx = nullptr;
        return JNI_FALSE;
    }

    // 设置接口的 alternate setting
    // DualSense 的音频接口有多个 alt setting，
    // 我们需要选择包含等时端点的那个
    rc = libusb_set_interface_alt_setting(g_handle, ifaceId, altSetting);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_set_interface_alt_setting(%d, %d) failed: %s",
             ifaceId, altSetting, libusb_error_name(rc));
        libusb_release_interface(g_handle, ifaceId);
        libusb_close(g_handle);
        g_handle = nullptr;
        libusb_exit(g_ctx);
        g_ctx = nullptr;
        return JNI_FALSE;
    }

    g_iface_id = ifaceId;
    g_ep_addr = (uint8_t)epAddr;
    g_enabled = false;
    g_send_ok_count = 0;
    g_send_fail_count = 0;
    g_send_last_log_ms = monotonic_ms();

    LOGI("[NATIVE] connected: fd=%d iface=%d alt=%d ep=0x%02X", fd, ifaceId, altSetting, g_ep_addr);
    return JNI_TRUE;
#else
    LOGE("libusb not available - cannot connect haptics");
    return JNI_FALSE;
#endif
}

// ========== 启用触觉发送 ==========
JNIEXPORT jboolean JNICALL
Java_com_peasyo_input_HapticNative_nativeEnableHaptics(
        JNIEnv *, jclass) {
#ifdef HAVE_LIBUSB
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_handle) {
        LOGE("[NATIVE] nativeEnableHaptics failed: not connected");
        return JNI_FALSE;
    }
    g_enabled = true;
    g_send_ok_count = 0;
    g_send_fail_count = 0;
    g_send_last_log_ms = monotonic_ms();
    LOGI("[NATIVE] haptics enabled");
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// ========== 发送一帧触觉音频数据 ==========
// 这是最核心的方法，每毫秒调用一次
// 参数：
//   buffer - DirectByteBuffer，包含一帧 PCM 音频数据
//   length - 数据长度（通常是 120 字节，PS5 发来的帧大小）
//
// 等时传输 vs 批量传输的区别：
// - 批量传输(bulk)：保证数据完整，但延迟不确定（会重传）
// - 等时传输(iso)：保证时间精确，但不重传（丢了就丢了）
// 对于实时触觉反馈，时间精确比数据完整更重要，所以用等时传输
JNIEXPORT jboolean JNICALL
Java_com_peasyo_input_HapticNative_nativeSendHapticFeedback(
        JNIEnv *env, jclass, jobject buffer, jint length) {
#ifdef HAVE_LIBUSB
    if (!g_enabled || !g_handle) {
        g_send_fail_count++;
        if (g_send_fail_count == 1 || (g_send_fail_count % 100) == 0) {
            LOGE("[NATIVE] send ignored: enabled=%d handle=%p", g_enabled ? 1 : 0, g_handle);
        }
        return JNI_FALSE;
    }

    // 从 DirectByteBuffer 获取原始内存指针（零拷贝）
    auto *data = (uint8_t *)env->GetDirectBufferAddress(buffer);
    if (!data || length <= 0) {
        g_send_fail_count++;
        LOGE("[NATIVE] invalid send buffer: data=%p len=%d", data, length);
        return JNI_FALSE;
    }

    // 输入是 stereo 16-bit interleaved
    const int input_frames = length / kBytesPerInputFrame;
    if (input_frames <= 0) {
        g_send_fail_count++;
        LOGE("[NATIVE] invalid frame count from len=%d", length);
        return JNI_FALSE;
    }

    // 1) stereo -> quad（0,0,L,R）
    std::vector<int16_t> quad((size_t)input_frames * kOutputChannels);
    expand_stereo_to_quad((const int16_t *)data, input_frames, quad.data());

    // 2) 3k -> 48k 线性上采样
    const int upsampled_frames = input_frames * kUpsampleFactor;
    std::vector<int16_t> upsampled((size_t)upsampled_frames * kOutputChannels);
    linear_upsample_3k_to_48k(quad.data(), input_frames, upsampled.data());

    // 3) 参考 pxplay: 发送连续音频缓冲，并把每个 ISO 包长度设为 <=392
    //    对 120B 输入，最终是 3840B 音频，对应分包 392*9 + 312。
    const int actual_audio_bytes = upsampled_frames * kOutputChannels * (int)sizeof(int16_t);
    const int transfer_len = actual_audio_bytes;
    auto *packet_buf = new uint8_t[transfer_len];
    memcpy(packet_buf, (const uint8_t *)upsampled.data(), (size_t)transfer_len);

    // 分配一个等时传输对象（10 个 ISO 包）
    struct libusb_transfer *xfr = libusb_alloc_transfer(kIsoPacketCount);
    if (!xfr) {
        LOGE("Failed to allocate transfer");
        delete[] packet_buf;
        return JNI_FALSE;
    }

    // 填充等时传输参数
    // 回调函数在传输完成后释放缓冲区和传输对象
    libusb_fill_iso_transfer(xfr, g_handle, g_ep_addr,
                             packet_buf, transfer_len, kIsoPacketCount,
                             [](struct libusb_transfer *t) {
                                 // 传输完成回调：清理内存
                                 delete[] t->buffer;
                                 libusb_free_transfer(t);
                             },
                             nullptr, 1000);  // 1000ms 超时

    // 参考 pxplay 的分包策略：每包最多 392，最后一包通常更短（例如 312）
    int remaining = transfer_len;
    for (int i = 0; i < kIsoPacketCount; ++i) {
        int chunk = remaining > kIsoPacketSize ? kIsoPacketSize : remaining;
        if (chunk < 0) chunk = 0;
        xfr->iso_packet_desc[i].length = (unsigned int)chunk;
        remaining -= chunk;
    }

    // 提交传输（异步，不会阻塞）
    int rc = libusb_submit_transfer(xfr);
    if (rc != LIBUSB_SUCCESS) {
        g_send_fail_count++;
        LOGE("[NATIVE] libusb_submit_transfer failed: %s", libusb_error_name(rc));
        delete[] packet_buf;
        libusb_free_transfer(xfr);
        return JNI_FALSE;
    }

    g_send_ok_count++;
    uint64_t now_ms = monotonic_ms();
    if (g_send_ok_count == 1 || now_ms - g_send_last_log_ms >= 1000) {
        LOGI("[NATIVE] send stats: ok=%llu fail=%llu inLen=%d inFrames=%d upFrames=%d audioBytes=%d transferBytes=%d pkt0=%u pkt9=%u",
             (unsigned long long)g_send_ok_count,
             (unsigned long long)g_send_fail_count,
             length,
             input_frames,
             upsampled_frames,
             actual_audio_bytes,
             transfer_len,
             xfr->iso_packet_desc[0].length,
             xfr->iso_packet_desc[kIsoPacketCount - 1].length);
        g_send_last_log_ms = now_ms;
    }

    // 处理 USB 事件（让 libusb 有机会完成传输）
    // 1ms 超时，不会长时间阻塞
    struct timeval tv = {0, 1000};
    libusb_handle_events_timeout(g_ctx, &tv);

    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// ========== 清理资源 ==========
JNIEXPORT void JNICALL
Java_com_peasyo_input_HapticNative_nativeCleanupHaptics(
        JNIEnv *env, jclass) {
#ifdef HAVE_LIBUSB
    std::lock_guard<std::mutex> lock(g_mutex);
    g_enabled = false;

    if (g_handle) {
        if (g_iface_id >= 0) {
            libusb_release_interface(g_handle, g_iface_id);
        }
        libusb_close(g_handle);
        g_handle = nullptr;
    }
    if (g_ctx) {
        libusb_exit(g_ctx);
        g_ctx = nullptr;
    }
    g_iface_id = -1;
    g_ep_addr = 0;
    LOGI("[NATIVE] haptics cleaned up");
#endif
}

} // extern "C"
