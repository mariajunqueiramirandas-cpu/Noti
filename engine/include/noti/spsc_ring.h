// Bounded, wait-free single-producer / single-consumer ring.
//
//  * Indices are free-running 64-bit counters (never wrap in practice) masked
//    by a power-of-two capacity, so full/empty need no extra slot or flag.
//  * Producer and consumer indices live on separate cache lines, and each side
//    keeps a private cached copy of the other side's index. In steady state a
//    push touches only producer-owned lines: no cache-line ping-pong unless the
//    ring looks full (producer) or empty (consumer).
//  * Batch push/pop publish once per batch (one release store), so a
//    MotionEvent carrying N historical samples costs one cross-core handoff.
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <type_traits>

#include "noti/config.h"

namespace noti {

template <typename T, std::size_t Capacity>
class SpscRing {
  static_assert(std::is_trivially_copyable_v<T>, "ring moves elements with memcpy");
  static_assert(Capacity >= 2 && (Capacity & (Capacity - 1)) == 0, "capacity must be a power of two");
  static constexpr std::size_t kMask = Capacity - 1;

 public:
  SpscRing() = default;
  SpscRing(const SpscRing&) = delete;
  SpscRing& operator=(const SpscRing&) = delete;

  static constexpr std::size_t capacity() noexcept { return Capacity; }

  // ---- producer ------------------------------------------------------------

  // Pushes up to n items; returns how many were pushed (fewer only if full).
  NOTI_HOT std::size_t try_push(const T* NOTI_RESTRICT items, std::size_t n) noexcept {
    const std::size_t tail = tail_.load(std::memory_order_relaxed);
    std::size_t free_slots = Capacity - (tail - head_cache_);
    if (NOTI_UNLIKELY(free_slots < n)) {
      head_cache_ = head_.load(std::memory_order_acquire);
      free_slots = Capacity - (tail - head_cache_);
      if (n > free_slots) n = free_slots;
      if (n == 0) return 0;
    }
    copy_in(tail, items, n);
    tail_.store(tail + n, std::memory_order_release);
    return n;
  }

  NOTI_HOT bool try_push(const T& item) noexcept { return try_push(&item, 1) == 1; }

  // ---- consumer ------------------------------------------------------------

  // Pops up to max items into out; returns how many were popped.
  NOTI_HOT std::size_t pop(T* NOTI_RESTRICT out, std::size_t max) noexcept {
    const std::size_t head = head_.load(std::memory_order_relaxed);
    std::size_t avail = tail_cache_ - head;
    if (avail < max) {
      tail_cache_ = tail_.load(std::memory_order_acquire);
      avail = tail_cache_ - head;
      if (avail == 0) return 0;
    }
    const std::size_t n = avail < max ? avail : max;
    copy_out(head, out, n);
    head_.store(head + n, std::memory_order_release);
    return n;
  }

  // Consumer-side check. Uses a fresh acquire load (needed by the sleep/wake
  // protocol, which must observe the producer's latest publication).
  bool empty_acquire() noexcept {
    tail_cache_ = tail_.load(std::memory_order_acquire);
    return tail_cache_ == head_.load(std::memory_order_relaxed);
  }

  // Approximate size; safe from any thread, exact only when both sides are idle.
  std::size_t size_approx() const noexcept {
    return tail_.load(std::memory_order_acquire) - head_.load(std::memory_order_acquire);
  }

 private:
  NOTI_INLINE void copy_in(std::size_t pos, const T* items, std::size_t n) noexcept {
    const std::size_t idx = pos & kMask;
    const std::size_t first = (Capacity - idx) < n ? (Capacity - idx) : n;
    std::memcpy(&buf_[idx], items, first * sizeof(T));
    if (first < n) std::memcpy(&buf_[0], items + first, (n - first) * sizeof(T));
  }
  NOTI_INLINE void copy_out(std::size_t pos, T* out, std::size_t n) noexcept {
    const std::size_t idx = pos & kMask;
    const std::size_t first = (Capacity - idx) < n ? (Capacity - idx) : n;
    std::memcpy(out, &buf_[idx], first * sizeof(T));
    if (first < n) std::memcpy(out + first, &buf_[0], (n - first) * sizeof(T));
  }

  // Consumer-owned line.
  alignas(kCacheLine) std::atomic<std::size_t> head_{0};
  std::size_t tail_cache_ = 0;
  // Producer-owned line.
  alignas(kCacheLine) std::atomic<std::size_t> tail_{0};
  std::size_t head_cache_ = 0;
  // Payload.
  alignas(kCacheLine) T buf_[Capacity];
};

}  // namespace noti
