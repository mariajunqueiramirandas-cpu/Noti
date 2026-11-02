// Raw pen sample as it crosses the input → render thread boundary.
#pragma once

#include <cstdint>
#include <type_traits>

namespace noti {

enum class Action : uint16_t {
  kDown = 0,
  kMove = 1,
  kUp = 2,
  kCancel = 3,       // system cancelled the stroke (palm rejection, FLAG_CANCELED)
  kHoverEnter = 4,   // pen ~1 cm above glass: pre-warm the pipeline
  kHoverMove = 5,
  kHoverExit = 6,
};

enum SampleFlags : uint16_t {
  kFlagNone = 0,
  kFlagHistorical = 1 << 0,  // came from MotionEvent.getHistorical*
  kFlagEraser = 1 << 1,      // TOOL_TYPE_ERASER
  kFlagButtonPrimary = 1 << 2,
  kFlagButtonSecondary = 1 << 3,
};

// 40 bytes, trivially copyable: moved through the SPSC ring by memcpy.
struct InkSample {
  int64_t t_ns;       // hardware event time (MotionEvent.getEventTimeNanos), CLOCK_MONOTONIC
  int64_t t_recv_ns;  // when the input handler received it (System.nanoTime)
  float x;            // view pixels
  float y;
  float pressure;     // 0..1 as reported by the platform
  float tilt;         // radians, 0 = perpendicular to the glass
  float orientation;  // radians
  Action action;
  uint16_t flags;
};

static_assert(sizeof(InkSample) == 40, "InkSample layout is part of the JNI contract");
static_assert(std::is_trivially_copyable_v<InkSample>);

}  // namespace noti
