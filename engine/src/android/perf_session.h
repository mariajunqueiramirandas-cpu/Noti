// Keeps the ink render thread on fast cores at high clocks while the pen is
// near the glass (plan §4.2, §4.6):
//   * nice −8 (THREAD_PRIORITY_URGENT_DISPLAY)
//   * ADPF PerformanceHintManager session (API 33) with a ~1 ms target,
//     reporting the real work duration of every batch
//   * API 35: power-efficiency mode while the pen is away
#pragma once

#include <cstdint>

struct APerformanceHintSession;

namespace noti::android {

class PerfSession {
 public:
  // Call on the thread to boost.
  bool init(int64_t target_ns);
  void close();
  void report(int64_t actual_ns);
  // Pen near: full speed. Pen away: let the scheduler save power (API 35+).
  void set_pen_near(bool near);

 private:
  APerformanceHintSession* session_ = nullptr;
  int (*prefer_power_efficiency_)(APerformanceHintSession*, bool) = nullptr;
  bool near_ = false;
};

}  // namespace noti::android
