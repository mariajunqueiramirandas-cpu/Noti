#include "noti/latency_probe.h"

namespace noti {

uint64_t Histogram::bucket_value(int b) noexcept {
  if (b <= 0) return (1ull << kMinExp) / 2;
  const int e = (b - 1) / kSub + kMinExp;
  const int sub = (b - 1) % kSub;
  const uint64_t lo = (1ull << e) + (uint64_t(sub) << (e - kSubBits));
  const uint64_t width = 1ull << (e - kSubBits);
  return lo + width / 2;
}

uint64_t Histogram::percentile(double p) const noexcept {
  // Snapshot first, then compute against the snapshot's own total.
  uint32_t snap[kBuckets];
  uint64_t total = 0;
  for (int i = 0; i < kBuckets; ++i) {
    snap[i] = counts_[i].load(std::memory_order_relaxed);
    total += snap[i];
  }
  if (total == 0) return 0;
  if (p >= 100.0) return max();
  uint64_t rank = uint64_t(p / 100.0 * double(total));
  if (rank >= total) rank = total - 1;
  uint64_t acc = 0;
  for (int i = 0; i < kBuckets; ++i) {
    acc += snap[i];
    if (acc > rank) {
      const uint64_t v = bucket_value(i);
      const uint64_t mx = max();
      return v < mx ? v : mx;
    }
  }
  return max();
}

void Histogram::reset() noexcept {
  for (auto& c : counts_) c.store(0, std::memory_order_relaxed);
  total_.store(0, std::memory_order_relaxed);
  max_.store(0, std::memory_order_relaxed);
}

void LatencyProbe::reset() noexcept {
  in_to_recv.reset();
  recv_to_start.reset();
  recv_to_submit.reset();
  in_to_submit.reset();
  submit_to_gpu.reset();
  frame_cpu.reset();
  pred_error_cpx.reset();
  horizon_us.reset();
  dropped_samples.store(0, std::memory_order_relaxed);
}

}  // namespace noti
