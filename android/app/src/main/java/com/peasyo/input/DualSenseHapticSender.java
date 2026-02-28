package com.peasyo.input;

import android.util.Log;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * DualSenseHapticSender - DualSense 触觉音频发送线程
 *
 * 工作原理：
 * 这是一个生产者-消费者模型：
 * - 生产者：PS5 串流数据通过 C 层解码后，调用 enqueue() 把触觉音频帧放入队列
 * - 消费者：内部的发送线程不断从队列取数据，通过 libusb 等时传输发给 DualSense
 *
 * 为什么用队列+独立线程？
 * 1. PS5 发数据的速率可能有波动，队列起到缓冲作用
 * 2. USB 等时传输需要精确的时间控制，独立线程避免被其他逻辑阻塞
 * 3. 如果队列满了（1000帧），新数据会被丢弃而不是阻塞生产者
 *
 * 生命周期：
 * start() → enqueue() × N → stop()
 */
public class DualSenseHapticSender {

    private static final String TAG = "PS5HAPTIC";

    // 队列容量：1000帧 ≈ 1秒的缓冲（每帧约1ms）
    private static final int QUEUE_CAPACITY = 1000;

    // DirectByteBuffer 大小：1024字节，足够容纳任何单帧数据
    private static final int BUFFER_SIZE = 1024;

    // 初始延迟：DualSense 切换到触觉模式后需要一点时间稳定
    private static final long INITIAL_DELAY_MS = 1500;

    // 阻塞队列：线程安全的生产者-消费者队列
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    // volatile 保证多线程可见性
    private volatile boolean running = false;
    private Thread thread = null;

    /**
     * 启动发送线程
     * 会先等待 1.5 秒让 DualSense 稳定，然后开始消费队列中的数据
     */
    public void start() {
        if (running) return;
        running = true;
        queue.clear();

        thread = new Thread(() -> {
            Log.i(TAG, "[SENDER] thread started, wait=" + INITIAL_DELAY_MS + "ms");
            try {
                // 等待 DualSense 从 rumble 模式切换到触觉模式
                Thread.sleep(INITIAL_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
            // 清空等待期间积累的旧数据
            queue.clear();

            // 分配 DirectByteBuffer（堆外内存，native 层可以直接访问，零拷贝）
            ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            Log.i(TAG, "[SENDER] now sending haptic data");

            long sentOk = 0;
            long sentFail = 0;
            long lastStatsMs = SystemClock.uptimeMillis();

            while (running) {
                try {
                    // take() 会阻塞直到有数据可用
                    byte[] data = queue.take();
                    if (data.length == 0 || data.length > BUFFER_SIZE) continue;

                    // 把数据拷贝到 DirectByteBuffer
                    buffer.clear();
                    buffer.put(data);
                    buffer.flip();

                    // 通过 libusb 等时传输发送到 DualSense
                    boolean ok = HapticNative.nativeSendHapticFeedback(buffer, data.length);
                    if (ok) {
                        sentOk++;
                    } else {
                        sentFail++;
                        Log.w(TAG, "[SENDER] nativeSendHapticFeedback failed, len=" + data.length);
                    }

                    long now = SystemClock.uptimeMillis();
                    if (sentOk == 1 || now - lastStatsMs >= 1000) {
                        Log.i(TAG, "[SENDER] send stats: ok=" + sentOk
                                + " fail=" + sentFail
                                + " queueSize=" + queue.size()
                                + " lastLen=" + data.length);
                        lastStatsMs = now;
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            queue.clear();
            Log.i(TAG, "[SENDER] thread stopped");
        }, "DualSense-Haptics");

        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 停止发送线程并清理队列
     */
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        queue.clear();
    }

    /**
     * 将一帧触觉音频数据放入发送队列
     * 非阻塞：如果队列满了，数据会被丢弃（对实时音频来说，丢帧比延迟好）
     *
     * @param frame PS5 发来的原始 PCM 触觉音频数据
     * @return true 表示成功入队，false 表示队列满了被丢弃
     */
    public boolean enqueue(byte[] frame) {
        return queue.offer(frame);
    }

    public boolean isRunning() {
        return running;
    }
}
