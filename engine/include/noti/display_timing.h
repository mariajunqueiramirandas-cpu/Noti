// Scan-out-aware prediction horizon.
//
// With a front buffer, ink becomes visible when the panel's scan line next
// passes the ink's row, not at a vsync boundary. That wait swings by a full
// refresh period (0–6.9 ms at 144 Hz) from frame to frame, so a fixed horizon
// is wrong by up to ±3.5 ms every frame. On simulated 240 Hz handwriting
// (predictor_bench --display-sim) a fixed horizon overshoots visibly in ~10 %
// of frames; computing the horizon per frame from the real timing cuts that to
// ~0.5 % and the mean ink-to-nib gap by ~28 %.
//
//   horizon = (now − t_sample)                 time already spent (exact)
//           + gpu                               submit → pixels written
//           + wait for scan line at ink row     from vsync phase + panel geometry
//           + panel response                    LCD settle (feel-calibrated)
#pragma once

#include <algorithm>
#include <cstdint>

#include "noti/config.h"

namespace noti {

enum class ScanAxis : int32_t {
  kUnknown = 0,  // phase known, row mapping unknown: use the average wait (period / 2)
  kY = 1,        // panel scans along surface y (rows)
  kX = 2,        // panel scans along surface x (rotated panel)
};

struct DisplayTiming {
  ScanAxis axis = ScanAxis::kUnknown;
  bool reversed = false;          // scan runs from y = H (or x = W) towards 0
  float active_fraction = 0.95f;  // part of the period spent scanning (rest = vertical blank)
  float gpu_ms = 0.35f;           // submit → pixels in memory (probe: submit_to_gpu)
  float panel_ms = 5.0f;          // LCD response; the feel-calibration slider adjusts this
  float max_horizon_ms = 25.0f;
  int64_t stale_ns = 500'000'000; // vsync info older than this is ignored
};

// ns from `t_ready` until the scan line reaches `row01` (0 = first scanned row).
NOTI_INLINE int64_t scan_wait_ns(int64_t t_ready, int64_t vsync_ns, int64_t period_ns, float active_fraction,
                                 float row01) noexcept {
  const int64_t row_t = int64_t(double(active_fraction) * double(period_ns) * double(std::clamp(row01, 0.0f, 1.0f)));
  int64_t phase = (t_ready - vsync_ns) % period_ns;
  if (phase < 0) phase += period_ns;
  return phase <= row_t ? row_t - phase : period_ns - phase + row_t;
}

// Horizon (ms past the sample's hardware time) at which the ink drawn now will
// be visible. Returns `fallback_ms` when no fresh vsync timing is known.
NOTI_INLINE float visible_horizon_ms(const DisplayTiming& d, int64_t now_ns, int64_t t_sample_ns, int64_t vsync_ns,
                                     int64_t period_ns, float ink_x, float ink_y, int32_t surface_w,
                                     int32_t surface_h, float fallback_ms) noexcept {
  if (period_ns <= 0 || now_ns - vsync_ns > d.stale_ns) return fallback_ms;
  const int64_t t_ready = now_ns + int64_t(double(d.gpu_ms) * 1e6);
  int64_t wait;
  if (d.axis == ScanAxis::kUnknown || surface_w <= 0 || surface_h <= 0) {
    wait = period_ns / 2;
  } else {
    float row = d.axis == ScanAxis::kY ? ink_y / float(surface_h) : ink_x / float(surface_w);
    if (d.reversed) row = 1.0f - row;
    wait = scan_wait_ns(t_ready, vsync_ns, period_ns, d.active_fraction, row);
  }
  const double h = double(t_ready + wait - t_sample_ns) * 1e-6 + double(d.panel_ms);
  return float(std::clamp(h, 0.0, double(d.max_horizon_ms)));
}

}  // namespace noti
