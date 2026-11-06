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
//
// Pages: every stroke belongs to a page. Capsules are stored twice: in surface
// px (what the renderers draw, written on the hot path) and, from close(), in
// page units (the canonical copy, see PageMap). remap_page() rewrites a page's
// surface capsules from the canonical copy, so zooming or panning any number of
// times never accumulates rounding error.
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "noti/geometry.h"

namespace noti {

struct StrokeRecord {
  uint32_t first_capsule;
  uint32_t capsule_count;
  RectF bounds;
  float color[4];  // premultiplied
  int64_t t_begin_ns;
  int64_t t_end_ns;
  uint32_t page;
  bool hidden = false;
  bool on_screen = true;
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
  // Seals the open stroke on `page` (whose current placement is `map`); returns
  // its id, or UINT32_MAX if out of stroke slots.
  uint32_t close(const RectF& bounds, const float color[4], int64_t t_begin_ns, int64_t t_end_ns,
                 uint32_t page = 0, const PageMap& map = {}) noexcept;
  // Re-places every stroke of `page` on the surface (off the hot path).
  void remap_page(uint32_t page, const PageMap& map) noexcept;
  void abort() noexcept { capsule_count_ = open_first_; }

  // ---- committed strokes ----
  uint32_t stroke_count() const noexcept { return stroke_count_; }
  uint32_t capsule_count() const noexcept { return capsule_count_; }
  const StrokeRecord& stroke(uint32_t id) const noexcept { return strokes_[id]; }
  const Capsule* capsules(uint32_t id) const noexcept { return &capsules_[strokes_[id].first_capsule]; }

  void set_hidden(uint32_t id, bool hidden) noexcept { strokes_[id].hidden = hidden; }
  void set_on_screen(uint32_t page, bool on) noexcept;
  void translate(uint32_t id, float dx, float dy, const PageMap& map) noexcept;
  const Capsule* canonical(uint32_t id) const noexcept { return &canon_[strokes_[id].first_capsule]; }
  bool can_copy_page(uint32_t page) const noexcept;
  uint32_t copy_stroke(uint32_t id, uint32_t page) noexcept;
  // Room for `strokes` more strokes holding `capsules` capsules in total?
  bool can_append(uint64_t strokes, uint64_t capsules) const noexcept {
    return strokes <= max_strokes_ - stroke_count_ && capsules <= max_capsules_ - capsule_count_;
  }
  // Appends a sealed stroke given in page units (canonical, copied exactly), placed on
  // the surface by `map`. No stroke may be open. Returns its id, or UINT32_MAX if full.
  uint32_t append(uint32_t page, const float color[4], const Capsule* canon, uint32_t n, const PageMap& map,
                  bool on_screen) noexcept;
  void insert_page(uint32_t page) noexcept;
  void delete_page(uint32_t page) noexcept;
  std::vector<uint8_t> encode() const;
  bool decode(const uint8_t* bytes, size_t size);

 private:
  uint32_t max_strokes_, max_capsules_;
  uint32_t stroke_count_ = 0, capsule_count_ = 0, open_first_ = 0;
  std::unique_ptr<StrokeRecord[]> strokes_;
  std::unique_ptr<Capsule[]> capsules_;
  std::unique_ptr<Capsule[]> canon_;  // page units, for committed strokes
};

}  // namespace noti
