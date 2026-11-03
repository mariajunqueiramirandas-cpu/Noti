#include "noti/stroke_store.h"

#include <cstring>

namespace noti {

// `new T[n]` on trivially-constructible types does not touch the memory; large
// blocks come straight from mmap, so pages become resident only when written.
StrokeStore::StrokeStore(uint32_t max_strokes, uint32_t max_capsules)
    : max_strokes_(max_strokes),
      max_capsules_(max_capsules),
      strokes_(new StrokeRecord[max_strokes]),
      capsules_(new Capsule[max_capsules]),
      canon_(new Capsule[max_capsules]) {}

uint32_t StrokeStore::close(const RectF& bounds, const float color[4], int64_t t_begin_ns, int64_t t_end_ns,
                            uint32_t page, const PageMap& map) noexcept {
  if (stroke_count_ >= max_strokes_) return UINT32_MAX;
  StrokeRecord& r = strokes_[stroke_count_];
  r.first_capsule = open_first_;
  r.capsule_count = capsule_count_ - open_first_;
  r.bounds = bounds;
  std::memcpy(r.color, color, sizeof(r.color));
  r.t_begin_ns = t_begin_ns;
  r.t_end_ns = t_end_ns;
  r.page = page;
  for (uint32_t i = open_first_; i < capsule_count_; ++i) canon_[i] = map.to_page(capsules_[i]);
  open_first_ = capsule_count_;
  return stroke_count_++;
}

void StrokeStore::remap_page(uint32_t page, const PageMap& map) noexcept {
  for (uint32_t id = 0; id < stroke_count_; ++id) {
    StrokeRecord& r = strokes_[id];
    if (r.page != page) continue;
    RectF b;
    for (uint32_t i = r.first_capsule; i < r.first_capsule + r.capsule_count; ++i) {
      capsules_[i] = map.to_surface(canon_[i]);
      b.add(capsules_[i]);
    }
    r.bounds = b;
  }
}

}  // namespace noti
