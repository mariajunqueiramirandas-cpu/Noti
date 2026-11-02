// Built-in latency probe (plan §5.1). Lock-free, allocation-free histograms
// written by the render thread and read at any time by the UI/debug overlay.
//
// Log-linear buckets (16 per power of two, ≤ 6.25 % relative error) from
// 64 ns to ~137 s. Each bucket has one writer, so updates are plain relaxed
// load+store (no atomic read-modify-write on the hot path); readers see a
// consistent-enough snapshot for p50/p99.
#pragma once

#include <array>
#include <atomic>
#include <cstdint>

#include "noti/config.h"

namespace noti {

class Histogram {
 public:
  static constexpr int kSubBits = 4;
  static constexpr int kSub = 1 << kSubBits;
  static constexpr int kMinExp = 6;  // values < 64 go to bucket 0
  static constexpr int kMaxExp = 37;
  static constexpr int kBuckets = 1 + (kMaxExp - kMinExp + 1) * kSub;

  NOTI_INLINE static int bucket_of(uint64_t v) noexcept {
    if (v < (1ull << kMinExp)) return 0;
    int e = 63 - __builtin_clzll(v);
    if (e > kMaxExp) return kBuckets - 1;
    const int sub = int((v >> (e - kSubBits)) & (kSub - 1));
    return 1 + (e - kMinExp) * kSub + sub;
  }
  // Representative (mid-point) value of a bucket.
  static uint64_t bucket_value(int b) noexcept;

  // Single writer.
  NOTI_INLINE void record(uint64_t v) noexcept {
    auto& c = counts_[bucket_of(v)];
    c.store(c.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
    total_.store(total_.load(std::memory_order_relaxed) + 1, std::memory_order_relaxed);
    if (v > max_.load(std::memory_order_relaxed)) max_.store(v, std::memory_order_relaxed);
  }
  NOTI_INLINE void record_signed(int64_t v) noexcept { record(v < 0 ? 0 : uint64_t(v)); }

  // Any thread.
  uint64_t count() const noexcept { return total_.load(std::memory_order_relaxed); }
  uint64_t max() const noexcept { return max_.load(std::memory_order_relaxed); }
  uint64_t percentile(double p) const noexcept;  // p in [0, 100]

  // Writer thread only (or when the writer is quiescent).
  void reset() noexcept;

 private:
  std::array<std::atomic<uint32_t>, kBuckets> counts_{};
  std::atomic<uint64_t> total_{0};
  std::atomic<uint64_t> max_{0};
};

// Everything the debug overlay shows (all durations in ns).
struct LatencyProbe {
  Histogram in_to_recv;       // t_recv − t_in      kernel → app dispatch (stages 2–3)
  Histogram recv_to_start;    // t_start − t_recv   input thread → render thread hand-off
  Histogram recv_to_submit;   // t_submit − t_recv  engine time (CI-gated, plan §5.2)
  Histogram in_to_submit;     // t_submit − t_in    hardware sample → GPU submit
  Histogram submit_to_gpu;    // GPU fence signal − t_submit (filled by the GL renderer)
  Histogram frame_cpu;        // CPU time per render batch
  Histogram pred_error_cpx;   // prediction end-point error, in 1/100 px
  std::atomic<uint64_t> dropped_samples{0};

  void reset() noexcept;
};

}  // namespace noti
