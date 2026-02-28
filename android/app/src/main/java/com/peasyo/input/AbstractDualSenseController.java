package com.peasyo.input;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public abstract class AbstractDualSenseController extends AbstractController {
    private static final String TAG = "PS5HAPTIC";

    protected final UsbDevice device;
    protected final UsbDeviceConnection connection;

    private Thread inputThread;
    private boolean stopped;

    protected UsbEndpoint inEndpt, outEndpt;

    // ============================================================
    // DualSense 触觉反馈相关字段
    // ============================================================

    // 等时音频端点的最大包大小（DualSense 固定为 0x188 = 392 字节）
    // 这是识别触觉音频端点的关键特征
    private static final int ISO_EP_MAX_PACKET = 0x188;

    // 触觉音频端点信息（在 start() 中扫描 USB 接口时填充）
    private int hapticInterfaceId = -1;    // 音频接口 ID
    private int hapticAltSetting = -1;     // 接口的 alternate setting
    private byte hapticEndpointAddr = 0;   // 等时端点地址

    // 触觉发送线程（BlockingQueue + 独立线程，负责把音频数据发给 DualSense）
    private DualSenseHapticSender hapticSender;

    // 触觉模式是否已启用
    private boolean hapticEnabled = false;

    // 调试统计：用于 adb logcat 判断触觉链路是否真的在跑
    private long hapticEnqueueCount = 0;
    private long hapticEnqueueDropCount = 0;
    private long hapticEnqueueLastLogMs = 0;

    public AbstractDualSenseController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.buttonFlags =
                DSControllerPacket.CROSS_FLAG | DSControllerPacket.MOON_FLAG | DSControllerPacket.BOX_FLAG | DSControllerPacket.PYRAMID_FLAG |
                        DSControllerPacket.LEFT_FLAG | DSControllerPacket.RIGHT_FLAG | DSControllerPacket.UP_FLAG | DSControllerPacket.DOWN_FLAG |
                        DSControllerPacket.L1_FLAG | DSControllerPacket.R1_FLAG |
                        DSControllerPacket.L3_FLAG | DSControllerPacket.R3_FLAG |
                        DSControllerPacket.OPTIONS_FLAG | DSControllerPacket.SHARE_FLAG | DSControllerPacket.TOUCHPAD_FLAG | DSControllerPacket.PS_FLAG | DSControllerPacket.MUTE_FLAG;
    }

    private Thread createInputThread() {
        return new Thread() {
            public void run() {
                try {
                    // Delay for a moment before reporting the new gamepad and
                    // accepting new input. This allows time for the old InputDevice
                    // to go away before we reclaim its spot. If the old device is still
                    // around when we call notifyDeviceAdded(), we won't be able to claim
                    // the controller number used by the original InputDevice.
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }

                // Report that we're added _before_ reporting input
                notifyDeviceAdded();

                while (!isInterrupted() && !stopped) {
                    byte[] buffer = new byte[64];

                    int res;

                    //
                    // There's no way that I can tell to determine if a device has failed
                    // or if the timeout has simply expired. We'll check how long the transfer
                    // took to fail and assume the device failed if it happened before the timeout
                    // expired.
                    //

                    do {
                        // Read the next input state packet
                        long lastMillis = SystemClock.uptimeMillis();
                        res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 3000);
//                        Log.d("UsbDriverService AbstractDualSenseController.java", "bulkTransfer result:" + res);

                        // If we get a zero length response, treat it as an error
                        if (res == 0) {
                            res = -1;
                        }

                        if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                            Log.d("UsbDriverService AbstractDualSenseController.java", "Detected device I/O error");
                            AbstractDualSenseController.this.stop();
                            break;
                        }
                    } while (res == -1 && !isInterrupted() && !stopped);

                    if (res == -1 || stopped) {
                        break;
                    }

                    if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                        // Report input if handleRead() returns true
                        reportDsInput();
                    }
                }
            }
        };
    }

    private static UsbInterface findInterface(UsbDevice device) {
        int count = device.getInterfaceCount();

        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
//            Log.d("UsbDriverService AbstractDualSenseController.java", "Interface " + i +
//                    ": class=" + String.format("0x%02X", intf.getInterfaceClass()) +
//                    " subclass=" + String.format("0x%02X", intf.getInterfaceSubclass()) +
//                    " protocol=" + String.format("0x%02X", intf.getInterfaceProtocol()) +
//                    " endpoints=" + intf.getEndpointCount());
//
//            if (i == 0) {
//                for (int j = 0; j < intf.getEndpointCount(); j++) {
//                    UsbEndpoint endpoint = intf.getEndpoint(j);
//                    Log.d("UsbDriverService", "Endpoint " + j +
//                            ": address=0x" + String.format("%02X", endpoint.getAddress()) +
//                            " type=" + endpoint.getType() +
//                            " direction=" + (endpoint.getDirection() == UsbConstants.USB_DIR_OUT ? "OUT" : "IN"));
//                }
//                return intf;
//            }
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_HID) {
                Log.d("UsbDriverService", "Found HID interface: " + i);
                return intf;
            }
        }
        return null;
    }

    public boolean start() {
        Log.d("UsbDriverService AbstractDualSenseController.java", "start");

        // Force claim all interfaces
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);

            if (!connection.claimInterface(iface, true)) {
                Log.d("UsbDriverService AbstractDualSenseController.java", "Failed to claim interfaces");
                return false;
            }
        }

        Log.d("UsbDriverService AbstractDualSenseController.java", "getInterfaceCount:" + device.getInterfaceCount());

        // ============================================================
        // 扫描所有 USB 接口，同时寻找：
        // 1. HID 接口（用于手柄输入和 rumble 输出）—— 原有逻辑
        // 2. 音频等时端点（用于触觉反馈）—— 新增逻辑
        //
        // DualSense 的 USB 接口布局大致如下：
        // - Interface 0: HID (手柄输入/输出，bulk 端点)
        // - Interface 1: Audio Control (音频控制，无数据端点)
        // - Interface 2: Audio Streaming (音频流，包含等时端点)
        //   └─ Endpoint: isochronous OUT, maxPacketSize=0x188 (392字节)
        //      这就是我们要找的触觉音频端点！
        // - Interface 3: Audio Streaming (麦克风输入)
        // ============================================================
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            // 在音频类接口中寻找等时 OUT 端点
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO) {
                for (int j = 0; j < iface.getEndpointCount(); j++) {
                    UsbEndpoint ep = iface.getEndpoint(j);
                    // 三个条件同时满足才是触觉音频端点：
                    // 1. 等时传输类型（isochronous）
                    // 2. 输出方向（OUT，从主机到设备）
                    // 3. 最大包大小 = 0x188（DualSense 特征值）
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                            && ep.getDirection() == UsbConstants.USB_DIR_OUT
                            && ep.getMaxPacketSize() == ISO_EP_MAX_PACKET) {
                        hapticInterfaceId = iface.getId();
                        hapticAltSetting = iface.getAlternateSetting();
                        hapticEndpointAddr = (byte) ep.getAddress();
                        Log.i(TAG, "Found haptic audio endpoint: iface=" + hapticInterfaceId
                                + " alt=" + hapticAltSetting
                                + " ep=0x" + String.format("%02X", ep.getAddress())
                                + " maxPkt=" + ep.getMaxPacketSize());
                    }
                }
            }
        }

        // Find the endpoints
        UsbInterface iface = findInterface(device);

        if (iface == null) {
            Log.e("UsbDriverService AbstractDualSenseController.java", "Failed to find interface");
            return false;
        }

        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_OUT) {
                if (outEndpt != null) {
                    Log.d("UsbDriverService AbstractDualSenseController.java", "Found duplicate OUT endpoint");
                    return false;
                }
                outEndpt = endpt;
            } else if (endpt.getDirection() == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    Log.d("UsbDriverService AbstractDualSenseController.java", "Found duplicate IN endpoint");
                    return false;
                }
                inEndpt = endpt;
            }
        }

        Log.d("UsbDriverService AbstractDualSenseController.java", "inEndpt: " + inEndpt);
        Log.d("UsbDriverService AbstractDualSenseController.java", "outEndpt: " + outEndpt);

        // Make sure the required endpoints were present
        if (inEndpt == null || outEndpt == null) {
            Log.d("UsbDriverService AbstractDualSenseController.java", "Missing required endpoin");
            return false;
        }

        // Run the init function
        if (!doInit()) {
            return false;
        }

        // Start listening for controller input
        inputThread = createInputThread();
        inputThread.start();

//        rumble((short)65535, (short)65535);

        return true;
    }

    public void stop() {
        if (stopped) {
            return;
        }

        stopped = true;

        // 停止触觉反馈（必须在关闭 USB 连接之前）
        stopHaptics();

        // Cancel any rumble effects
        rumble((short)0, (short)0);

        // Stop the input thread
        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }

        // Close the USB connection
        connection.close();

        // Report the device removed
        notifyDeviceRemoved();
    }

    protected abstract boolean handleRead(ByteBuffer buffer);
    protected abstract boolean doInit();

    // ============================================================
    // DualSense 触觉反馈 API
    // ============================================================

    /**
     * 初始化并启动触觉反馈
     *
     * 完整流程：
     * 1. 检查是否找到了等时音频端点
     * 2. 发送 HID 初始化报告（配置控制器参数）
     * 3. 发送 HID 启用触觉报告（让 DualSense 从 rumble 模式切换到音频触觉模式）
     * 4. 用 libusb 连接等时端点（因为 Android Java API 不支持等时传输）
     * 5. 启动发送线程
     *
     * @return true 表示触觉反馈启动成功
     */
    public boolean startHaptics() {
        if (hapticEnabled) {
            Log.w(TAG, "[CTRL] startHaptics ignored: already enabled");
            return true;
        }

        // 检查是否在 start() 中找到了等时端点
        if (hapticInterfaceId < 0) {
            Log.w(TAG, "[CTRL] startHaptics failed: no haptic audio endpoint");
            return false;
        }

        Log.i(TAG, "[CTRL] startHaptics begin, iface=" + hapticInterfaceId
                + " alt=" + hapticAltSetting
                + " ep=0x" + String.format("%02X", hapticEndpointAddr));

        // 步骤1: 发送初始化配置报告
        // 这个报告设置控制器的一些基本参数（自适应扳机等）
        byte[] initReport = new byte[48];
        initReport[0] = 0x02;   // Report ID（DualSense USB 输出报告固定为 0x02）
        initReport[1] = 0x0D;   // 功能标志位
        initReport[2] = 0x17;   // 功能标志位
        initReport[11] = 0x05;  // 右扳机效果参数
        initReport[22] = 0x05;  // 左扳机效果参数
        initReport[44] = 0x02;  // 电源控制
        sendCommand(initReport);
        Log.d(TAG, "[CTRL] init report sent");

        // 步骤2: 发送启用触觉模式报告
        // 这是关键！这个报告让 DualSense 从传统 rumble 马达模式
        // 切换到音频驱动的触觉模式（LRA 线性谐振致动器）
        byte[] enableReport = new byte[48];
        enableReport[0] = 0x02;          // Report ID
        enableReport[1] = (byte) 0x80;   // 标志：启用触觉音频
        enableReport[2] = 0x40;          // 标志
        enableReport[8] = 0x0C;          // 音频控制：启用触觉通道
        sendCommand(enableReport);
        Log.d(TAG, "[CTRL] enable-haptics report sent");

        // 步骤3: 通过 libusb 连接等时端点
        // 这里把 Android 的 USB 文件描述符传给 native 层的 libusb
        int fd = connection.getFileDescriptor();
        boolean connected = HapticNative.nativeConnectHaptics(
                fd, hapticInterfaceId, hapticAltSetting, hapticEndpointAddr);
        if (!connected) {
            Log.e(TAG, "[CTRL] nativeConnectHaptics failed");
            return false;
        }

        // 步骤4: 启用 native 层的数据发送
        boolean enabled = HapticNative.nativeEnableHaptics();
        if (!enabled) {
            Log.e(TAG, "[CTRL] nativeEnableHaptics failed");
            HapticNative.nativeCleanupHaptics();
            return false;
        }

        // 步骤5: 启动发送线程
        hapticSender = new DualSenseHapticSender();
        hapticSender.start();

        hapticEnqueueCount = 0;
        hapticEnqueueDropCount = 0;
        hapticEnqueueLastLogMs = SystemClock.uptimeMillis();

        hapticEnabled = true;
        Log.i(TAG, "[CTRL] haptics started successfully");
        return true;
    }

    /**
     * 停止触觉反馈，恢复到传统 rumble 模式
     */
    public void stopHaptics() {
        if (!hapticEnabled) return;

        Log.i(TAG, "[CTRL] stopping haptics");
        hapticEnabled = false;

        // 停止发送线程
        if (hapticSender != null) {
            hapticSender.stop();
            hapticSender = null;
        }

        // 清理 native 层 libusb 资源
        HapticNative.nativeCleanupHaptics();

        // 发送禁用触觉模式报告，让 DualSense 恢复到 rumble 模式
        byte[] disableReport = new byte[48];
        disableReport[0] = 0x02;
        disableReport[1] = 0x0D;
        disableReport[2] = 0x17;
        disableReport[47] = (byte) 0xFF;
        sendCommand(disableReport);
        Log.d(TAG, "[CTRL] disable-haptics report sent");
        Log.i(TAG, "[CTRL] haptics stopped");
    }

    /**
     * 将一帧触觉音频数据放入发送队列
     * 由上层（StreamSession）在收到 PS5 的触觉音频数据时调用
     *
     * @param frame PS5 发来的原始 PCM 触觉音频数据
     */
    public void enqueueHapticData(byte[] frame) {
        if (hapticEnabled && hapticSender != null) {
            boolean queued = hapticSender.enqueue(frame);
            hapticEnqueueCount++;
            if (!queued) {
                hapticEnqueueDropCount++;
                Log.w(TAG, "[CTRL] enqueue drop, len=" + frame.length + ", queue is full");
            }

            long now = SystemClock.uptimeMillis();
            if (hapticEnqueueCount == 1 || now - hapticEnqueueLastLogMs >= 1000) {
                Log.i(TAG, "[CTRL] enqueue stats: frames=" + hapticEnqueueCount
                        + " drops=" + hapticEnqueueDropCount
                        + " lastLen=" + frame.length
                        + " senderRunning=" + hapticSender.isRunning());
                hapticEnqueueLastLogMs = now;
            }
        } else {
            Log.d(TAG, "[CTRL] enqueue ignored: hapticEnabled=" + hapticEnabled
                    + " sender=" + (hapticSender != null));
        }
    }

    /**
     * 触觉反馈是否已启用
     */
    public boolean isHapticEnabled() {
        return hapticEnabled;
    }

    /**
     * 是否检测到了触觉音频端点（即 DualSense 是否支持触觉反馈）
     */
    public boolean hasHapticEndpoint() {
        return hapticInterfaceId >= 0;
    }
}
