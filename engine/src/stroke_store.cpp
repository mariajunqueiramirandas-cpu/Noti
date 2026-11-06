#include "noti/stroke_store.h"

#include <cstring>
#include <cmath>

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
  if (stroke_count_ >= max_strokes_ || capsule_count_ == open_first_) return UINT32_MAX;
  StrokeRecord& r = strokes_[stroke_count_];
  r.first_capsule = open_first_;
  r.capsule_count = capsule_count_ - open_first_;
  r.bounds = bounds;
  std::memcpy(r.color, color, sizeof(r.color));
  r.t_begin_ns = t_begin_ns;
  r.t_end_ns = t_end_ns;
  r.page = page;
  r.hidden = false;
  r.on_screen = true;
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

void StrokeStore::set_on_screen(uint32_t page, bool on) noexcept {
  for (uint32_t id = 0; id < stroke_count_; ++id)
    if (page == UINT32_MAX || strokes_[id].page == page) strokes_[id].on_screen = on;
}

void StrokeStore::translate(uint32_t id, float dx, float dy, const PageMap& map) noexcept {
  auto& r = strokes_[id];
  RectF b;
  for (uint32_t i = r.first_capsule; i < r.first_capsule + r.capsule_count; ++i) {
    auto& c = canon_[i];
    c.x0 += dx; c.x1 += dx; c.y0 += dy; c.y1 += dy;
    capsules_[i] = map.to_surface(c);
    b.add(capsules_[i]);
  }
  r.bounds = b;
}

bool StrokeStore::can_copy_page(uint32_t page) const noexcept {
  uint64_t strokes = 0, capsules = 0;
  for (uint32_t id = 0; id < stroke_count_; ++id) if (!strokes_[id].hidden && strokes_[id].page == page) {
    ++strokes; capsules += strokes_[id].capsule_count;
  }
  return strokes <= max_strokes_ - stroke_count_ && capsules <= max_capsules_ - capsule_count_;
}

uint32_t StrokeStore::copy_stroke(uint32_t id, uint32_t page) noexcept {
  const auto src = strokes_[id];
  if (stroke_count_ >= max_strokes_ || src.capsule_count > max_capsules_ - capsule_count_) return UINT32_MAX;
  auto& dst = strokes_[stroke_count_];
  dst = src; dst.page = page; dst.first_capsule = capsule_count_;
  std::memcpy(canon_.get() + capsule_count_, canon_.get() + src.first_capsule, src.capsule_count * sizeof(Capsule));
  std::memcpy(capsules_.get() + capsule_count_, capsules_.get() + src.first_capsule, src.capsule_count * sizeof(Capsule));
  capsule_count_ += src.capsule_count; open_first_ = capsule_count_;
  return stroke_count_++;
}

uint32_t StrokeStore::append(uint32_t page, const float color[4], const Capsule* canon, uint32_t n,
                             const PageMap& map, bool on_screen) noexcept {
  if (!n || !can_append(1, n)) return UINT32_MAX;
  StrokeRecord& r = strokes_[stroke_count_];
  r = StrokeRecord{};
  r.first_capsule = capsule_count_;
  r.capsule_count = n;
  std::memcpy(r.color, color, sizeof(r.color));
  r.page = page;
  r.on_screen = on_screen;
  RectF b;
  for (uint32_t i = 0; i < n; ++i) {
    canon_[capsule_count_ + i] = canon[i];
    capsules_[capsule_count_ + i] = map.to_surface(canon[i]);
    b.add(capsules_[capsule_count_ + i]);
  }
  r.bounds = b;
  capsule_count_ += n; open_first_ = capsule_count_;
  return stroke_count_++;
}

void StrokeStore::insert_page(uint32_t page) noexcept {
  for (uint32_t id = 0; id < stroke_count_; ++id) if (strokes_[id].page != UINT32_MAX && strokes_[id].page >= page) ++strokes_[id].page;
}

void StrokeStore::delete_page(uint32_t page) noexcept {
  for (uint32_t id = 0; id < stroke_count_; ++id) {
    auto& r = strokes_[id];
    if (r.page == page) { r.hidden = true; r.page = UINT32_MAX; r.on_screen = false; }
    else if (r.page != UINT32_MAX && r.page > page) --r.page;
  }
}

// Versioned little-endian canonical ink. No C++ struct padding crosses the file boundary.
std::vector<uint8_t> StrokeStore::encode() const {
  std::vector<uint8_t> out;
  auto word = [&](uint32_t v) { for (int j = 0; j < 4; ++j) out.push_back(uint8_t(v >> (j * 8))); };
  auto real = [&](float f) { uint32_t v; std::memcpy(&v, &f, 4); word(v); };
  uint32_t count = 0;
  for (uint32_t id = 0; id < stroke_count_; ++id) count += !strokes_[id].hidden;
  word(0x3149544e); word(1); word(count);
  for (uint32_t id = 0; id < stroke_count_; ++id) {
    const auto& r = strokes_[id];
    if (r.hidden) continue;
    word(r.page); word(r.capsule_count);
    for (float c : r.color) real(c);
    for (uint32_t j = 0; j < r.capsule_count; ++j) {
      const auto& c = canonical(id)[j];
      real(c.x0); real(c.y0); real(c.r0); real(c.x1); real(c.y1); real(c.r1);
    }
  }
  return out;
}

bool StrokeStore::decode(const uint8_t* bytes, size_t size) {
  if (size < 12) return false;
  size_t pos = 0;
  auto word = [&]() { uint32_t v = 0; for (int j = 0; j < 4; ++j) v |= uint32_t(bytes[pos++]) << (j * 8); return v; };
  auto real = [&]() { const uint32_t v = word(); float f; std::memcpy(&f, &v, 4); return f; };
  if (word() != 0x3149544e || word() != 1) return false;
  const uint32_t count = word();
  if (count > max_strokes_) return false;
  // Validate the entire archive before touching the current document.
  uint32_t total = 0;
  for (uint32_t id = 0; id < count; ++id) {
    if (size - pos < 24) return false;
    if (word() > 1000000u) return false;
    const uint32_t n = word();
    if (!n || n > max_capsules_ - total || n > (size - pos - 16) / 24) return false;
    total += n;
    for (int j = 0; j < 4; ++j) { float f = real(); if (!std::isfinite(f) || f < 0 || f > 1) return false; }
    for (uint32_t j = 0; j < n * 6; ++j) { float f = real(); if (!std::isfinite(f) || std::fabs(f) > 1e7f || (j % 3 == 2 && f < 0)) return false; }
  }
  if (pos != size) return false;
  stroke_count_ = capsule_count_ = open_first_ = 0;
  pos = 12;
  for (uint32_t id = 0; id < count; ++id) {
    const uint32_t page = word(), n = word();
    float color[4]; for (float& c : color) c = real();
    open(); RectF bounds;
    for (uint32_t j = 0; j < n; ++j) {
      Capsule c; c.x0 = real(); c.y0 = real(); c.r0 = real(); c.x1 = real(); c.y1 = real(); c.r1 = real();
      push(c); bounds.add(c);
    }
    close(bounds, color, 0, 0, page);
  }
  return true;
}

}  // namespace noti
