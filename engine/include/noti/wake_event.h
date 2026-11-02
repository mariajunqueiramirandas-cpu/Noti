// Sleep/wake handshake between the input producer and the render thread.
//
// The producer pays for a syscall only when the consumer is actually asleep:
// an awake (or spinning) render thread costs the producer one fence + one load.
// Lost wake-ups are prevented with the classic Dekker pattern: both sides
// store-then-fence(seq_cst)-then-load, so at least one of them sees the other.
//
// Linux/Android use a raw futex (no mutex, no condvar, no allocation). Other
// platforms fall back to C++20 atomic wait/notify.
#pragma once

#include <atomic>
#include <cstdint>
#include <ctime>

#include "noti/config.h"

#if defined(__linux__)
#include <linux/futex.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace noti {

class WakeEvent {
 public:
  // ---- consumer ----
  // Announce intent to sleep. Caller MUST re-check its queues after this and
  // call cancel_wait() if work appeared, else wait().
  NOTI_INLINE void prepare_wait() noexcept {
    state_.store(kSleeping, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_seq_cst);
  }
  NOTI_INLINE void cancel_wait() noexcept { state_.store(kAwake, std::memory_order_relaxed); }

  // Blocks until notify() (or a spurious wake). Returns with state = awake.
  void wait() noexcept {
    while (state_.load(std::memory_order_acquire) == kSleeping) {
#if defined(__linux__)
      syscall(SYS_futex, reinterpret_cast<uint32_t*>(&state_), FUTEX_WAIT_PRIVATE, kSleeping,
              nullptr, nullptr, 0);
#else
      state_.wait(kSleeping, std::memory_order_acquire);
#endif
    }
  }

  // Like wait(), but gives up after timeout_ns. Returns false on timeout; the
  // caller must then cancel_wait() before doing anything else.
  bool wait_for(int64_t timeout_ns) noexcept {
#if defined(__linux__)
    if (state_.load(std::memory_order_acquire) != kSleeping) return true;
    timespec ts{static_cast<time_t>(timeout_ns / 1'000'000'000), static_cast<long>(timeout_ns % 1'000'000'000)};
    syscall(SYS_futex, reinterpret_cast<uint32_t*>(&state_), FUTEX_WAIT_PRIVATE, kSleeping, &ts, nullptr, 0);
    return state_.load(std::memory_order_acquire) != kSleeping;
#else
    (void)timeout_ns;
    wait();
    return true;
#endif
  }

  // ---- producer ----
  // Call after publishing work (e.g. after SpscRing::try_push).
  NOTI_INLINE void notify() noexcept {
    std::atomic_thread_fence(std::memory_order_seq_cst);
    if (NOTI_UNLIKELY(state_.load(std::memory_order_relaxed) == kSleeping)) wake_slow();
  }

 private:
  static constexpr uint32_t kAwake = 0;
  static constexpr uint32_t kSleeping = 1;

  NOTI_NOINLINE void wake_slow() noexcept {
    if (state_.exchange(kAwake, std::memory_order_acq_rel) == kSleeping) {
#if defined(__linux__)
      syscall(SYS_futex, reinterpret_cast<uint32_t*>(&state_), FUTEX_WAKE_PRIVATE, 1, nullptr,
              nullptr, 0);
#else
      state_.notify_one();
#endif
    }
  }

  static_assert(sizeof(std::atomic<uint32_t>) == sizeof(uint32_t));
  alignas(kCacheLine) std::atomic<uint32_t> state_{kAwake};
};

}  // namespace noti
