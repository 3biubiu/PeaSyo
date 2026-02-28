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
