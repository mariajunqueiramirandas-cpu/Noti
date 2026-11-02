// Raw stroke recorder (plan Phase 2: "stroke recorder and dataset").
// Records every real sample the predictor sees (surface px, hardware time) into
// a ring preallocated when recording is switched on (never during a stroke),
// and exports CSV in the format `predictor_bench --csv` reads:
//   stroke_id,t_ns,x,y,pressure
#pragma once

#include <cstdint>
#include <memory>

#include "noti/config.h"

namespace noti {

class StrokeRecorder {
 public:
  struct Record {
    int64_t t_ns;
    uint32_t stroke;
    float x, y, p;
  };

  explicit StrokeRecorder(uint32_t capacity_pow2 = 1u << 18) : cap_(capacity_pow2) {}

  void enable(bool on);  // allocates on first enable
  bool enabled() const noexcept { return on_; }

  NOTI_INLINE void begin_stroke() noexcept { ++stroke_; }
  NOTI_INLINE void add(int64_t t_ns, float x, float y, float p) noexcept {
    if (!on_) return;
    buf_[n_ & (cap_ - 1)] = Record{t_ns, stroke_, x, y, p};
    ++n_;
  }

  uint64_t count() const noexcept { return n_ < cap_ ? n_ : cap_; }
  // Writes the retained samples (oldest first). Returns samples written, -1 on error.
  int64_t export_csv(const char* path) const;
  void clear() noexcept { n_ = 0; }

 private:
  uint32_t cap_;
  bool on_ = false;
  uint32_t stroke_ = 0;
  uint64_t n_ = 0;
  std::unique_ptr<Record[]> buf_;
};

}  // namespace noti
