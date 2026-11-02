#include "noti/engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "noti/clock.h"

namespace noti {

InkEngine::InkEngine(const EngineConfig& cfg, InkRenderer& renderer)
    : cfg_(cfg),
      renderer_(renderer),
      predictor_(cfg.predictor),
      surface_w_(cfg.surface_w),
      surface_h_(cfg.surface_h),
      store_(cfg.store_max_strokes, cfg.store_max_capsules) {
  scale_ = cfg_.input_to_surface.scale();
  renderer_.set_probe(cfg_.probe ? &probe_ : nullptr);
}

InkEngine::~InkEngine() { stop(); }

bool InkEngine::start() {
  if (thread_.joinable()) return true;
  stop_.store(false, std::memory_order_relaxed);
  thread_ = std::thread([this] { thread_main(); });
  return true;
}

void InkEngine::stop() {
  if (!thread_.joinable()) return;
  stop_.store(true, std::memory_order_release);
  wake_.notify();
  thread_.join();
}

// ---------------------------------------------------------------------------
// Producer side
// ---------------------------------------------------------------------------

uint32_t InkEngine::push(const InkSample* s, uint32_t n) noexcept {
  const auto k = static_cast<uint32_t>(samples_.try_push(s, n));
  if (NOTI_UNLIKELY(k < n)) probe_.dropped_samples.fetch_add(n - k, std::memory_order_relaxed);
  wake_.notify();
  return k;
}

bool InkEngine::post(const Command& c) noexcept {
  const bool ok = commands_.try_push(c);
  if (ok) ++posted_;
  wake_.notify();
  return ok;
}

void InkEngine::post_sync(const Command& c) noexcept {
  if (!running()) {
    while (!commands_.try_push(c)) pump();
    ++posted_;
    pump();
    return;
  }
  while (!commands_.try_push(c)) std::this_thread::yield();
  const uint32_t ticket = ++posted_;
  wake_.notify();
  // Commands execute in order and each one bumps sync_done_, so ours is done
  // once sync_done_ reaches its ticket (wrap-safe comparison).
  uint32_t done;
  while (int32_t((done = sync_done_.load(std::memory_order_acquire)) - ticket) < 0) {
    sync_done_.wait(done, std::memory_order_acquire);
  }
}

// ---------------------------------------------------------------------------
// Render thread
// ---------------------------------------------------------------------------

bool InkEngine::has_pending() noexcept {
  return !samples_.empty_acquire() || !commands_.empty_acquire();
}

void InkEngine::thread_main() {
  renderer_.thread_init();
  auto spin_until = [this](int64_t deadline) {
    for (uint32_t i = 0;; ++i) {
      if (has_pending()) return true;
      cpu_relax();
      if ((i & 63) == 63 && now_ns() >= deadline) return false;
    }
  };

  while (!stop_.load(std::memory_order_acquire)) {
    if (pump()) continue;

    // Short spin: catches samples that arrive back to back.
    if (cfg_.spin_ns > 0 && spin_until(now_ns() + cfg_.spin_ns)) {
      stats_.spin_hits.fetch_add(1, std::memory_order_relaxed);
      continue;
    }

    const int64_t idle_timeout = renderer_.idle();
    wake_.prepare_wait();
    if (has_pending() || stop_.load(std::memory_order_acquire)) {
      wake_.cancel_wait();
      continue;
    }

    if (active_ && cfg_.predictive_wake && sample_period_ns_ > 0 && dispatch_floor_ns_ != INT64_MAX) {
      // Anchor on the digitizer clock (regular), not on arrival times (jittery).
      const int64_t due = last_t_ + sample_period_ns_ + dispatch_floor_ns_;
      const int64_t sleep_ns = due - cfg_.wake_lead_ns - now_ns();
      if (sleep_ns > 0 && wake_.wait_for(sleep_ns)) {
        stats_.wakeups.fetch_add(1, std::memory_order_relaxed);  // producer woke us early
        continue;
      }
      wake_.cancel_wait();
      if (spin_until(due + cfg_.wake_slack_ns)) {
        stats_.predicted_hits.fetch_add(1, std::memory_order_relaxed);
        continue;
      }
      // The sample is late (pen paused or lifted); sleep until woken.
      wake_.prepare_wait();
      if (has_pending() || stop_.load(std::memory_order_acquire)) {
        wake_.cancel_wait();
        continue;
      }
    }
    if (idle_timeout >= 0) {
      if (!wake_.wait_for(idle_timeout)) {
        wake_.cancel_wait();  // renderer housekeeping timer, not input
        continue;
      }
    } else {
      wake_.wait();
    }
    stats_.wakeups.fetch_add(1, std::memory_order_relaxed);
  }
  renderer_.thread_shutdown();
}

bool InkEngine::pump() noexcept {
  bool did = false;
  Command cmd;
  while (commands_.pop(&cmd, 1) == 1) {
    handle_command(cmd);
    did = true;
  }
  for (;;) {
    const auto n = static_cast<uint32_t>(samples_.pop(scratch_, kMaxBatch));
    if (n == 0) break;
    did = true;
    process_samples(scratch_, n, now_ns());
  }
  return did;
}

void InkEngine::handle_command(const Command& c) noexcept {
  switch (c.type) {
    case CommandType::kSetColor:
      std::memcpy(brush_.color, c.f, sizeof(brush_.color));
      break;
    case CommandType::kSetWidth:
      brush_.width_px = c.f[0];
      break;
    case CommandType::kSetPressureGamma:
      brush_.curve.set_gamma(c.f[0], c.f[1]);
      break;
    case CommandType::kSetHorizon:
      cfg_.predictor.horizon_ms = c.f[0];
      predictor_.set_horizon_ms(c.f[0]);
      break;
    case CommandType::kSetPredictor:
      cfg_.predictor.model = static_cast<PredictorModel>(c.i0);
      if (!active_) predictor_.configure(cfg_.predictor);  // else applied at next pen-down
      break;
    case CommandType::kSetTransform:
      cfg_.input_to_surface = {c.f[0], c.f[1], c.f[2], c.f[3], c.f[4], c.f[5]};
      scale_ = cfg_.input_to_surface.scale();
      break;
    case CommandType::kSurfaceChanged:
      surface_w_ = c.i0;
      surface_h_ = c.i1;
      renderer_.surface_changed(c.ptr, c.i0, c.i1);
      break;
    case CommandType::kSurfaceDestroyed:
      if (active_) cancel_stroke();
      renderer_.surface_destroyed();
      surface_w_ = surface_h_ = 0;
      break;
    case CommandType::kResetProbe:
      probe_.reset();
      break;
  }
  // Every command completes a ticket; post_sync() waits for its own.
  sync_done_.fetch_add(1, std::memory_order_release);
  sync_done_.notify_all();
}

void InkEngine::process_samples(const InkSample* s, uint32_t n, int64_t t_start) noexcept {
  batch_t_start_ = t_start;
  bool dirty = false;
  for (uint32_t i = 0; i < n; ++i) {
    const InkSample& smp = s[i];
    switch (smp.action) {
      case Action::kHoverEnter:
        renderer_.hover(true);
        continue;
      case Action::kHoverExit:
        renderer_.hover(false);
        continue;
      case Action::kHoverMove:
        continue;
      case Action::kDown:
        if (active_) finish_stroke(last_t_);  // missed UP: never lose ink
        note_sample(smp);
        begin_stroke(smp);
        dirty = true;
        continue;
      case Action::kMove:
        if (!active_) continue;  // stray move (e.g. after cancel)
        note_sample(smp);
        extend_stroke(smp);
        dirty = true;
        continue;
      case Action::kUp:
        if (!active_) continue;
        note_sample(smp);
        extend_stroke(smp);
        finish_stroke(smp.t_ns);
        dirty = false;
        continue;
      case Action::kCancel:
        if (active_) cancel_stroke();
        dirty = false;
        continue;
    }
  }
  stats_.samples.fetch_add(n, std::memory_order_relaxed);
  if (active_) {
    // Track the dispatch-delay floor: follow decreases at once, creep up
    // slowly (1/16 per batch) so one fast outlier doesn't stick forever.
    const int64_t d = t_start - last_t_;
    if (d >= 0 && d < 50'000'000) {
      if (dispatch_floor_ns_ == INT64_MAX || d < dispatch_floor_ns_) dispatch_floor_ns_ = d;
      else dispatch_floor_ns_ += (d - dispatch_floor_ns_) / 16;
    }
  }
  if (dirty && active_) flush_frame(true);

  const int64_t cpu = now_ns() - t_start;
  if (cfg_.probe) probe_.frame_cpu.record_signed(cpu);
  renderer_.batch_done(cpu);
}

// Remember a sample's timestamps; the probe records them once its ink is submitted.
void InkEngine::note_sample(const InkSample& s) noexcept {
  if (cfg_.probe && frame_samples_ < kMaxBatch) {
    frame_t_in_[frame_samples_] = s.t_ns;
    frame_t_recv_[frame_samples_] = s.t_recv_ns;
    ++frame_samples_;
  }
}

void InkEngine::begin_stroke(const InkSample& s) noexcept {
  active_ = true;
  const float a = brush_.color[3];
  stroke_color_[0] = brush_.color[0] * a;
  stroke_color_[1] = brush_.color[1] * a;
  stroke_color_[2] = brush_.color[2] * a;
  stroke_color_[3] = a;
  if (predictor_.config().model != cfg_.predictor.model) predictor_.configure(cfg_.predictor);  // resets too
  else predictor_.reset();
  pending_head_ = pending_tail_ = 0;
  store_.open();
  frame_first_ = 0;
  stroke_bounds_ = RectF{};
  stroke_t0_ = s.t_ns;

  float x, y;
  cfg_.input_to_surface.apply(s.x, s.y, &x, &y);
  const float r = brush_.radius(s.pressure) * scale_;
  const Capsule dot{x, y, r, x, y, r};
  if (NOTI_LIKELY(store_.push(dot))) stroke_bounds_.add(dot);
  else stats_.store_full.fetch_add(1, std::memory_order_relaxed);
  last_x_ = x; last_y_ = y; last_r_ = r; last_t_ = s.t_ns;
  predictor_.add(s.t_ns, x, y, s.pressure);
}

void InkEngine::extend_stroke(const InkSample& s) noexcept {
  float x, y;
  cfg_.input_to_surface.apply(s.x, s.y, &x, &y);
  const float r = brush_.radius(s.pressure) * scale_;
  const Capsule c{last_x_, last_y_, last_r_, x, y, r};
  const int64_t dt = s.t_ns - last_t_;
  if (dt > 1'000'000 && dt < 20'000'000) {  // ignore duplicates and pauses
    sample_period_ns_ = sample_period_ns_ ? sample_period_ns_ + (dt - sample_period_ns_) / 8 : dt;
  }
  if (NOTI_LIKELY(store_.push(c))) {
    stroke_bounds_.add(c);
  } else {
    stats_.store_full.fetch_add(1, std::memory_order_relaxed);
    return;  // store full: drop the segment (counted), never crash or corrupt
  }
  if (cfg_.probe && pending_head_ != pending_tail_)
    track_prediction_error(last_t_, last_x_, last_y_, s.t_ns, x, y);
  last_x_ = x; last_y_ = y; last_r_ = r; last_t_ = s.t_ns;
  predictor_.add(s.t_ns, x, y, s.pressure);
}

void InkEngine::flush_frame(bool with_prediction) noexcept {
  FrameUpdate f;
  f.color = stroke_color_;
  const uint32_t stroke_n = store_.open_count();
  f.ink = store_.open_capsules() + frame_first_;
  f.ink_count = stroke_n - frame_first_;
  if (f.ink_count) {
    RectF b;
    for (uint32_t i = 0; i < f.ink_count; ++i) b.add(f.ink[i]);
    f.ink_rect = pixel_bounds(b, surface_w_, surface_h_);
  }

  pred_.count = 0;
  if (with_prediction && predictor_.predict(&pred_) > 0) {
    RectF b;
    float px = last_x_, py = last_y_, pr = last_r_;
    for (uint32_t i = 0; i < pred_.count; ++i) {
      const PredictedPoint& p = pred_.points[i];
      const float r = brush_.radius(p.pressure) * scale_;
      pred_caps_[i] = {px, py, pr, p.x, p.y, r};
      b.add(pred_caps_[i]);
      px = p.x; py = p.y; pr = r;
    }
    f.pred = pred_caps_;
    f.pred_count = pred_.count;
    f.pred_rect = pixel_bounds(b, surface_w_, surface_h_);
    if (cfg_.probe) {
      const PredictedPoint& end = pred_.points[pred_.count - 1];
      pending_[pending_tail_ % kPending] = {last_t_ + int64_t(double(end.dt_ms) * 1e6), end.x, end.y};
      ++pending_tail_;
      if (pending_tail_ - pending_head_ > kPending) pending_head_ = pending_tail_ - kPending;
    }
  }
  f.restore_rect = prev_pred_rect_;

  if (f.ink_count || f.pred_count || !f.restore_rect.empty()) {
    renderer_.render(f);
    stats_.frames.fetch_add(1, std::memory_order_relaxed);
  }
  prev_pred_rect_ = f.pred_rect;
  frame_first_ = stroke_n;

  if (cfg_.probe && frame_samples_) {
    const int64_t t_submit = now_ns();
    for (uint32_t i = 0; i < frame_samples_; ++i) {
      probe_.in_to_recv.record_signed(frame_t_recv_[i] - frame_t_in_[i]);
      probe_.recv_to_start.record_signed(batch_t_start_ - frame_t_recv_[i]);
      probe_.recv_to_submit.record_signed(t_submit - frame_t_recv_[i]);
      probe_.in_to_submit.record_signed(t_submit - frame_t_in_[i]);
    }
    frame_samples_ = 0;
  }
}

void InkEngine::finish_stroke(int64_t t_end) noexcept {
  flush_frame(false);  // last real segments; erases the predicted tail
  // Zero-copy commit: the capsules are already in the store.
  const uint32_t id = store_.close(stroke_bounds_, stroke_color_, stroke_t0_, t_end);
  if (NOTI_LIKELY(id != UINT32_MAX)) {
    renderer_.commit(store_, id, pixel_bounds(stroke_bounds_, surface_w_, surface_h_));
  }
  // If the store is out of stroke slots the ink stays on the wet layer rather
  // than being lost.
  active_ = false;
  stats_.strokes.fetch_add(1, std::memory_order_relaxed);
}

void InkEngine::cancel_stroke() noexcept {
  RectI r = pixel_bounds(stroke_bounds_, surface_w_, surface_h_).united(prev_pred_rect_);
  renderer_.cancel(r);
  prev_pred_rect_ = {};
  active_ = false;
  store_.abort();
  frame_first_ = 0;
  frame_samples_ = 0;
}

void InkEngine::track_prediction_error(int64_t t0, float x0, float y0, int64_t t1, float x1, float y1) noexcept {
  while (pending_head_ != pending_tail_) {
    const PendingPrediction& p = pending_[pending_head_ % kPending];
    if (p.target_ns > t1) break;
    if (p.target_ns >= t0 && t1 > t0) {
      const float f = float(double(p.target_ns - t0) / double(t1 - t0));
      const float tx = x0 + (x1 - x0) * f, ty = y0 + (y1 - y0) * f;
      const float e = std::sqrt((p.x - tx) * (p.x - tx) + (p.y - ty) * (p.y - ty));
      probe_.pred_error_cpx.record(uint64_t(e * 100.0f + 0.5f));
    }
    ++pending_head_;
  }
}

}  // namespace noti
