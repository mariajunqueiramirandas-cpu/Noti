// Append-only store of strokes. Capacity is reserved up front; untouched pages
// are never made resident, so a large reservation is free until used, and
// appending never reallocates or moves data (renderers keep pointers into it).
//
// The *active* stroke is written straight into the arena (open → push… →
// close), so committing a stroke copies nothing and a stroke of any length is
// never split. Cancelling rolls the arena back.
//
// Phase 3 replaces the in-memory arena with the mmap'd write-ahead log; the
// interface (append-only, stable ids, stable pointers) stays the same.
#pragma once

#include <cstdint>
#include <memory>

#include "noti/geometry.h"

namespace noti {

struct StrokeRecord {
  uint32_t first_capsule;
  uint32_t capsule_count;
  RectF bounds;
  float color[4];  // premultiplied
  int64_t t_begin_ns;
  int64_t t_end_ns;
};

class StrokeStore {
 public:
  StrokeStore(uint32_t max_strokes, uint32_t max_capsules);

  // ---- active stroke (render thread) ----
  void open() noexcept { open_first_ = capsule_count_; }
  // Pointer to the open stroke's first capsule (stable until close/abort).
  NOTI_INLINE Capsule* open_capsules() noexcept { return &capsules_[open_first_]; }
  NOTI_INLINE uint32_t open_count() const noexcept { return capsule_count_ - open_first_; }
  NOTI_INLINE bool push(const Capsule& c) noexcept {
    if (NOTI_UNLIKELY(capsule_count_ >= max_capsules_)) return false;
    capsules_[capsule_count_++] = c;
    return true;
  }
  // Seals the open stroke; returns its id, or UINT32_MAX if out of stroke slots.
  uint32_t close(const RectF& bounds, const float color[4], int64_t t_begin_ns, int64_t t_end_ns) noexcept;
  void abort() noexcept { capsule_count_ = open_first_; }

  // ---- committed strokes ----
  uint32_t stroke_count() const noexcept { return stroke_count_; }
  uint32_t capsule_count() const noexcept { return capsule_count_; }
  const StrokeRecord& stroke(uint32_t id) const noexcept { return strokes_[id]; }
  const Capsule* capsules(uint32_t id) const noexcept { return &capsules_[strokes_[id].first_capsule]; }

 private:
  uint32_t max_strokes_, max_capsules_;
  uint32_t stroke_count_ = 0, capsule_count_ = 0, open_first_ = 0;
  std::unique_ptr<StrokeRecord[]> strokes_;
  std::unique_ptr<Capsule[]> capsules_;
};

}  // namespace noti
