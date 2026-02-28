package com.peasyo.input;

import java.nio.ByteBuffer;

/**
 * HapticNative - DualSense 触觉反馈的 JNI 桥接类
 *
 * 这个类连接 Java 层和 native 层（haptic_usb.cpp），
 * 提供 4 个核心方法来控制 DualSense 的触觉音频等时传输。
 *
 * 调用顺序：
 * 1. nativeConnectHaptics() - 初始化 libusb，打开等时端点
 * 2. nativeEnableHaptics()  - 启用数据发送
 * 3. nativeSendHapticFeedback() - 循环发送触觉音频帧（每帧约1ms）
 * 4. nativeCleanupHaptics() - 断开时清理资源
 *
 * 为什么用 static 方法？
 * 因为 native 层用全局变量管理状态（同一时间只有一个 DualSense 连接），
 * 不需要实例化对象。
 */
public class HapticNative {

    // 加载包含 haptic_usb.cpp 的 native 库
    // chiaki-jni 已经包含了 haptic_usb.cpp（在 CMakeLists.txt 中配置）
    static {
        System.loadLibrary("chiaki-jni");
    }

    /**
     * 初始化 libusb 并连接到 DualSense 的等时音频端点
     *
     * @param fd         USB 文件描述符，从 UsbDeviceConnection.getFileDescriptor() 获取
     * @param ifaceId    音频接口的 ID（包含 maxPacketSize=0x188 等时端点的接口）
     * @param altSetting 该接口的 alternate setting 值
     * @param epAddr     等时 OUT 端点的地址
     * @return true 表示连接成功
     */
    public static native boolean nativeConnectHaptics(int fd, int ifaceId, int altSetting, byte epAddr);

    /**
     * 启用触觉数据发送
     * 必须在 nativeConnectHaptics 成功后调用
     *
     * @return true 表示启用成功
     */
    public static native boolean nativeEnableHaptics();

    /**
     * 发送一帧触觉音频数据到 DualSense
     * 通过 USB 等时传输发送，保证实时性（不重传，不等待）
     *
     * @param buffer DirectByteBuffer，包含 PS5 发来的原始 PCM 触觉音频数据
     * @param length 数据长度（字节数）
     * @return true 表示发送成功
     */
    public static native boolean nativeSendHapticFeedback(ByteBuffer buffer, int length);

    /**
     * 清理所有 libusb 资源
     * 在 DualSense 断开连接或停止触觉反馈时调用
     */
    public static native void nativeCleanupHaptics();
}
