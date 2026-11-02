// Monotonic clock in nanoseconds. Same base as MotionEvent.getEventTimeNanos()
// and System.nanoTime() (CLOCK_MONOTONIC), so engine and input timestamps can be
// subtracted directly. clock_gettime is a vDSO call (~20–40 ns on arm64).
#pragma once

#include <cstdint>
#include <ctime>

#include "noti/config.h"

namespace noti {

NOTI_INLINE int64_t now_ns() noexcept {
  timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;
}

}  // namespace noti
