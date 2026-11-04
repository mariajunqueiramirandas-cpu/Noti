#include "noti/engine.h"

#include <algorithm>
#include <cmath>

namespace noti {
namespace {
float point_segment(float x, float y, float ax, float ay, float bx, float by) {
  const float dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
  const float t = len > 0 ? std::clamp(((x - ax) * dx + (y - ay) * dy) / len, 0.f, 1.f) : 0;
  return std::hypot(x - ax - t * dx, y - ay - t * dy);
}
bool hit_segment(float ax, float ay, float bx, float by, const Capsule& c, float radius) {
  const float ux = bx - ax, uy = by - ay, vx = c.x1 - c.x0, vy = c.y1 - c.y0;
  const float det = ux * vy - uy * vx;
  if (std::fabs(det) > 1e-6f) {
    const float qx = c.x0 - ax, qy = c.y0 - ay;
    const float t = (qx * vy - qy * vx) / det, u = (qx * uy - qy * ux) / det;
    if (t >= 0 && t <= 1 && u >= 0 && u <= 1) return true;
  }
  const float d = std::min({point_segment(ax, ay, c.x0, c.y0, c.x1, c.y1),
    point_segment(bx, by, c.x0, c.y0, c.x1, c.y1),
    point_segment(c.x0, c.y0, ax, ay, bx, by), point_segment(c.x1, c.y1, ax, ay, bx, by)});
  return d <= radius + std::max(c.r0, c.r1);
}
}

void InkEngine::remember_edit(EditAction action) {
  if (action.ids.empty()) return;
  redo_.clear();
  undo_.push_back(std::move(action));
  if (undo_.size() > 256) undo_.erase(undo_.begin());
  ++revision_;
}

void InkEngine::redraw_document() {
  renderer_.redraw(store_, document_mode_ ? UINT32_MAX : page_);
}

void InkEngine::set_pages(const std::vector<VisiblePage>& pages) {
  if (document_mode_ && !layout_dirty_ && pages.size() == visible_pages_.size() &&
      std::equal(pages.begin(), pages.end(), visible_pages_.begin(), [](const auto& a, const auto& b) {
        return a.page == b.page && a.x == b.x && a.y == b.y && a.width == b.width && a.height == b.height;
      })) return;
  if (active_) finish_stroke(last_t_);
  if (editing_) end_edit(true);
  document_mode_ = true;
  layout_dirty_ = false;
  visible_pages_ = pages;
  store_.set_on_screen(UINT32_MAX, false);
  for (const auto& p : pages) {
    if (!(p.width > 0) || !(p.height > 0)) continue;
    PageMap m; cfg_.input_to_surface.apply(p.x, p.y, &m.ox, &m.oy);
    m.s = p.width * scale_ / PageMap::kPageUnits;
    store_.remap_page(p.page, m);
    store_.set_on_screen(p.page, true);
  }
  redraw_document();
}

bool InkEngine::pick_page(float x, float y) noexcept {
  if (!document_mode_) return true;
  for (const auto& p : visible_pages_) {
    if (x < p.x || x > p.x + p.width || y < p.y || y > p.y + p.height) continue;
    page_ = p.page;
    cfg_.input_to_surface.apply(p.x, p.y, &page_map_.ox, &page_map_.oy);
    page_map_.s = p.width * scale_ / PageMap::kPageUnits;
    return true;
  }
  return false;
}

void InkEngine::set_tool(int tool) noexcept {
  if (tool_ == tool) return;
  if (active_) finish_stroke(last_t_);
  if (editing_) end_edit(true);
  selected_.clear();
  tool_ = tool;
}

RectF InkEngine::selection_bounds() const noexcept {
  RectF out;
  for (auto id : selected_) if (!store_.stroke(id).hidden && store_.stroke(id).on_screen) out.add(store_.stroke(id).bounds);
  return out;
}

bool InkEngine::edit_sample(const InkSample& s) {
  const bool contact = s.action == Action::kDown || s.action == Action::kMove || s.action == Action::kUp || s.action == Action::kCancel;
  if (!contact) return false;
  if (s.action == Action::kDown) {
    if (editing_) end_edit(false);
    gesture_tool_ = (s.flags & (kFlagEraser | kFlagButtonPrimary)) ? 2 : (s.flags & kFlagButtonSecondary) ? 3 : tool_;
    if (gesture_tool_ < 2) return false;
    if (active_) finish_stroke(last_t_);
    if (!pick_page(s.x, s.y)) return true;
    editing_ = true; polygon_.clear(); edit_ = {};
    cfg_.input_to_surface.apply(s.x, s.y, &edit_x_, &edit_y_);
    const auto bounds = selection_bounds();
    moving_selection_ = gesture_tool_ == 3 && !bounds.empty() && edit_x_ >= bounds.x0 - 12 && edit_x_ <= bounds.x1 + 12 && edit_y_ >= bounds.y0 - 12 && edit_y_ <= bounds.y1 + 12;
    if (moving_selection_) { edit_.kind = 2; edit_.ids = selected_; }
    else { selected_.clear(); edit_.kind = 1; }
    edit_bounds_ = {};
    polygon_.push_back({edit_x_, edit_y_});
  }
  if (!editing_) return gesture_tool_ >= 2;
  if (s.action == Action::kCancel) { end_edit(true); return true; }
  float x, y; cfg_.input_to_surface.apply(s.x, s.y, &x, &y);
  if (gesture_tool_ == 2) {
    const float radius = 18.f * scale_;
    bool changed = false;
    for (uint32_t id = 0; id < store_.stroke_count(); ++id) {
      const auto& r = store_.stroke(id);
      if (r.hidden || !r.on_screen || r.page != page_) continue;
      if (r.bounds.x1 < std::min(x, edit_x_) - radius || r.bounds.x0 > std::max(x, edit_x_) + radius || r.bounds.y1 < std::min(y, edit_y_) - radius || r.bounds.y0 > std::max(y, edit_y_) + radius) continue;
      for (uint32_t j = 0; j < r.capsule_count; ++j) if (hit_segment(edit_x_, edit_y_, x, y, store_.capsules(id)[j], radius)) {
        store_.set_hidden(id, true); edit_.ids.push_back(id); changed = true; break;
      }
    }
    if (changed) redraw_document();
  } else if (moving_selection_) {
    float dx = x - edit_x_, dy = y - edit_y_;
    if (document_mode_) {
      const auto bounds = selection_bounds();
      for (const auto& p : visible_pages_) if (p.page == page_) {
        const float w = p.width * scale_, h = p.height * scale_;
        if (bounds.x1 - bounds.x0 <= w) dx = std::clamp(dx, page_map_.ox - bounds.x0, page_map_.ox + w - bounds.x1);
        if (bounds.y1 - bounds.y0 <= h) dy = std::clamp(dy, page_map_.oy - bounds.y0, page_map_.oy + h - bounds.y1);
        break;
      }
    }
    dx /= page_map_.s; dy /= page_map_.s;
    for (auto id : selected_) store_.translate(id, dx, dy, page_map_);
    edit_.dx += dx; edit_.dy += dy;
    redraw_document();
  } else {
    Capsule c{edit_x_, edit_y_, 1.5f, x, y, 1.5f};
    edit_bounds_.add(c);
    const float blue[4] = {0.15f, 0.35f, 0.9f, 1.f};
    FrameUpdate f; f.ink = &c; f.ink_count = 1; f.color = blue; RectF b; b.add(c);
    f.ink_rect = pixel_bounds(b, surface_w_, surface_h_); renderer_.render(f);
    polygon_.push_back({x, y});
  }
  edit_x_ = x; edit_y_ = y;
  if (s.action == Action::kUp) end_edit(false);
  return true;
}

void InkEngine::end_edit(bool cancel) {
  editing_ = false;
  if (gesture_tool_ == 3 && !moving_selection_) {
    renderer_.cancel(pixel_bounds(edit_bounds_, surface_w_, surface_h_));
    if (!cancel && polygon_.size() >= 3) {
      auto inside = [&](float x, float y) {
        bool in = false;
        for (size_t a = 0, b = polygon_.size() - 1; a < polygon_.size(); b = a++) {
          const auto p = polygon_[a], q = polygon_[b];
          if ((p.y > y) != (q.y > y) && x < (q.x - p.x) * (y - p.y) / (q.y - p.y) + p.x) in = !in;
        }
        return in;
      };
      for (uint32_t id = 0; id < store_.stroke_count(); ++id) {
        const auto& r = store_.stroke(id);
        if (r.hidden || !r.on_screen || r.page != page_) continue;
        bool contained = true;
        for (uint32_t j = 0; j < r.capsule_count; ++j) {
          const auto& c = store_.capsules(id)[j];
          if (!inside(c.x0, c.y0) || !inside(c.x1, c.y1)) { contained = false; break; }
        }
        if (contained) selected_.push_back(id);
      }
    }
  } else if (!edit_.ids.empty()) {
    if (cancel) { apply_edit(false, edit_); redraw_document(); }
    else if (edit_.kind != 2 || edit_.dx != 0 || edit_.dy != 0) remember_edit(std::move(edit_));
  }
  polygon_.clear(); edit_ = {}; moving_selection_ = false;
}

void InkEngine::apply_edit(bool forward, const EditAction& action) {
  for (auto id : action.ids) {
    if (action.kind == 2) {
      PageMap map = page_map_;
      for (const auto& p : visible_pages_) if (p.page == store_.stroke(id).page) {
        cfg_.input_to_surface.apply(p.x, p.y, &map.ox, &map.oy); map.s = p.width * scale_ / PageMap::kPageUnits;
      }
      const float sign = forward ? 1.f : -1.f;
      store_.translate(id, sign * action.dx, sign * action.dy, map);
    } else store_.set_hidden(id, action.kind == 0 ? !forward : forward);
  }
}

void InkEngine::undo() {
  if (active_) finish_stroke(last_t_);
  if (editing_) end_edit(true);
  if (undo_.empty()) return;
  selected_.clear();
  apply_edit(false, undo_.back()); redo_.push_back(std::move(undo_.back())); undo_.pop_back(); ++revision_; redraw_document();
}
void InkEngine::redo() {
  if (active_) finish_stroke(last_t_);
  if (editing_) end_edit(true);
  if (redo_.empty()) return;
  selected_.clear();
  apply_edit(true, redo_.back()); undo_.push_back(std::move(redo_.back())); redo_.pop_back(); ++revision_; redraw_document();
}

bool InkEngine::edit_page(int operation, uint32_t page) {
  if (operation < 0 || operation > 2) return false;
  if (active_) finish_stroke(last_t_);
  if (editing_) end_edit(true);
  if (operation == 1 && !store_.can_copy_page(page)) return false;
  selected_.clear(); undo_.clear(); redo_.clear();
  layout_dirty_ = true;
  if (operation == 0) store_.insert_page(page);
  else if (operation == 1) {
    store_.insert_page(page + 1);
    const auto count = store_.stroke_count();
    for (uint32_t id = 0; id < count; ++id) if (!store_.stroke(id).hidden && store_.stroke(id).page == page) store_.copy_stroke(id, page + 1);
  } else if (operation == 2) store_.delete_page(page);
  ++revision_;
  // Geometry will be remapped by the next viewport layout.
  redraw_document();
  return true;
}

void InkEngine::finish_input() {
  if (active_) finish_stroke(last_t_);
  if (editing_) end_edit(false);
}

std::vector<uint8_t> InkEngine::snapshot() {
  return store_.encode();
}
bool InkEngine::restore(const uint8_t* bytes, size_t size) {
  if (active_) cancel_stroke();
  if (editing_) end_edit(true);
  if (!store_.decode(bytes, size)) return false;
  undo_.clear(); redo_.clear(); selected_.clear(); ++revision_; layout_dirty_ = true;
  if (document_mode_) { const auto pages = visible_pages_; set_pages(pages); }
  else { store_.remap_page(page_, page_map_); redraw_document(); }
  return true;
}
}
