// Maximum-performance CPU setup for the ink thread. Power and thermals are
// explicitly NOT a constraint (product decision): every knob is set for the
// lowest and most consistent wake-up and execution latency.
//
//   * nice −10 (above THREAD_PRIORITY_URGENT_DISPLAY)
//   * pinned to the fastest core (highest cpu_capacity, e.g. the Cortex-X4)
//   * CORE HEATER: a SCHED_IDLE thread pinned to the same core spins whenever
//     the ink thread sleeps. The core never enters an idle state (no idle-exit
//     latency) and schedutil sees 100 % utilisation (max clock, no ramp-up).
//     A pen event wakes the ink thread, which preempts SCHED_IDLE at once.
//   * ADPF session told it is always over budget, so it holds the maximum
//     boost instead of backing off because our batches take only a few µs.
//   * API 35: power-efficiency mode explicitly off.
#pragma once

#include <atomic>
#include <cstdint>
#include <thread>

struct APerformanceHintSession;

namespace noti::android {

// Fastest CPU by cpu_capacity (EAS) or cpuinfo_max_freq; -1 if unknown.
int fastest_cpu();

class PerfSession {
 public:
  // Call on the thread to boost.
  bool init(int64_t target_ns, bool pin_and_heat);
  void close();
  // Called after every batch and every keep-alive tick.
  void report(int64_t actual_ns);
  int core() const { return core_; }

 private:
  APerformanceHintSession* session_ = nullptr;
  int64_t target_ns_ = 0;
  int core_ = -1;
  std::atomic<bool> heater_stop_{false};
  std::thread heater_;
};

}  // namespace noti::android
