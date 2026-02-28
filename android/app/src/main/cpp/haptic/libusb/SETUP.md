# libusb Setup

This directory should contain the libusb source code for building the native haptic USB library.

## Steps

1. Download libusb source from https://github.com/libusb/libusb/releases (v1.0.27+)
2. Extract so that `libusb/libusb.h` exists at `app/src/main/cpp/libusb/libusb/libusb.h`
3. Create `app/src/main/cpp/libusb/android/config.h` with the content below

## config.h content

```c
#ifndef LIBUSB_CONFIG_H
#define LIBUSB_CONFIG_H

#define ENABLE_LOGGING 1
#define DEFAULT_VISIBILITY __attribute__((visibility("default")))
#define HAVE_CLOCK_GETTIME 1
#define HAVE_EVENTFD 1
#define HAVE_TIMERFD 1
#define HAVE_PIPE2 1
#define PRINTF_FORMAT(a, b) __attribute__((__format__(__printf__, a, b)))
#define PLATFORM_POSIX 1

#endif
```

## Without libusb

The app will build without libusb but native isochronous transfers won't work.
The USB device detection and HID control reports (enable/disable haptics) still
work through Android's standard UsbDeviceConnection API.
