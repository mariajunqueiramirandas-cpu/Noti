// Noti ink engine — compiler/platform helpers shared by every translation unit.
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

#if defined(__GNUC__) || defined(__clang__)
#define NOTI_LIKELY(x) __builtin_expect(!!(x), 1)
#define NOTI_UNLIKELY(x) __builtin_expect(!!(x), 0)
#define NOTI_INLINE inline __attribute__((always_inline))
#define NOTI_NOINLINE __attribute__((noinline))
#define NOTI_HOT __attribute__((hot))
#define NOTI_COLD __attribute__((cold))
#define NOTI_RESTRICT __restrict__
#else
#define NOTI_LIKELY(x) (x)
#define NOTI_UNLIKELY(x) (x)
#define NOTI_INLINE inline
#define NOTI_NOINLINE
#define NOTI_HOT
#define NOTI_COLD
#define NOTI_RESTRICT
#endif

namespace noti {

// Cortex-X4 / A720 / A520 (Snapdragon 7+ Gen 3) and x86-64 all use 64-byte lines.
inline constexpr std::size_t kCacheLine = 64;

// Spin-wait hint. On AArch64 `yield` is a NOP on most cores, so like Rust's
// spin_loop() we use `isb`, which actually stalls for a few dozen cycles and
// frees the pipeline for the SMT sibling / lowers power while spinning.
NOTI_INLINE void cpu_relax() noexcept {
#if defined(__aarch64__)
  asm volatile("isb" ::: "memory");
#elif defined(__arm__)
  asm volatile("yield" ::: "memory");
#elif defined(__x86_64__) || defined(__i386__)
  __builtin_ia32_pause();
#else
  std::atomic_signal_fence(std::memory_order_seq_cst);
#endif
}

}  // namespace noti
