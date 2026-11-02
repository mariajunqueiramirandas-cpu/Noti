#include "noti/predictor.h"

#include <algorithm>
#include <cmath>

namespace noti {

const char* to_string(PredictorModel m) noexcept {
  switch (m) {
    case PredictorModel::kNone: return "none";
    case PredictorModel::kLinear: return "linear";
    case PredictorModel::kPolynomial: return "poly";
    case PredictorModel::kKalman: return "kalman";
  }
  return "?";
}

StrokePredictor::StrokePredictor(const PredictorConfig& cfg) noexcept { configure(cfg); }

void StrokePredictor::configure(const PredictorConfig& cfg) noexcept {
  cfg_ = cfg;
  cfg_.tail_points = std::clamp(cfg_.tail_points, 1, Prediction::kMaxTailPoints);
  cfg_.poly_window = std::clamp(cfg_.poly_window, 3, kMaxPolyWindow);
  cfg_.warmup_samples = std::max(cfg_.warmup_samples, 2);
  reset();
}

void StrokePredictor::reset() noexcept {
  n_ = 0;
  vx_ = vy_ = ax_ = ay_ = 0;
  prev_ax_ = prev_ay_ = 0;
  jerk_ = 0;
  dpdt_ = 0;
  nis_ = 0;
  ring_pos_ = 0;
  conf_ = 0;
  euro_init_ = false;
}

// ---------------------------------------------------------------------------
// Kalman filter, constant-acceleration model, white-jerk process noise.
// Both axes share dt, Q and R, therefore they share P and the gain K: the
// covariance is propagated once per sample for both axes.
// ---------------------------------------------------------------------------
void StrokePredictor::update_kalman(double h, double zx, double zy) noexcept {
  double* P = P_;
  if (h > 0) {
    const double g = 0.5 * h * h;
    // x <- F x
    for (double* k : {kx_, ky_}) {
      k[0] += h * k[1] + g * k[2];
      k[1] += h * k[2];
    }
    // P <- F P F^T + Q   (expanded for symmetric P)
    const double A00 = P[0] + h * P[1] + g * P[2];
    const double A01 = P[1] + h * P[3] + g * P[4];
    const double A02 = P[2] + h * P[4] + g * P[5];
    const double A11 = P[3] + h * P[4];
    const double A12 = P[4] + h * P[5];
    const double A22 = P[5];
    const double q = cfg_.kalman_jerk_q;
    const double h2 = h * h, h3 = h2 * h;
    P[0] = A00 + h * A01 + g * A02 + q * h3 * h2 / 20.0;
    P[1] = A01 + h * A02 + q * h2 * h2 / 8.0;
    P[2] = A02 + q * h3 / 6.0;
    P[3] = A11 + h * A12 + q * h3 / 3.0;
    P[4] = A12 + q * h2 / 2.0;
    P[5] = A22 + q * h;
  }
  // Update with H = [1 0 0].
  const double S = P[0] + cfg_.kalman_meas_r;
  const double inv_s = 1.0 / S;
  const double K0 = P[0] * inv_s, K1 = P[1] * inv_s, K2 = P[2] * inv_s;
  const double ix = zx - kx_[0], iy = zy - ky_[0];
  kx_[0] += K0 * ix; kx_[1] += K1 * ix; kx_[2] += K2 * ix;
  ky_[0] += K0 * iy; ky_[1] += K1 * iy; ky_[2] += K2 * iy;
  const double p0 = P[0], p1 = P[1], p2 = P[2];
  P[0] -= K0 * p0; P[1] -= K0 * p1; P[2] -= K0 * p2;
  P[3] -= K1 * p1; P[4] -= K1 * p2;
  P[5] -= K2 * p2;

  const double nis = 0.5 * (ix * ix + iy * iy) * inv_s;  // per-axis mean
  nis_ += 0.35 * (nis - nis_);
  vx_ = kx_[1]; vy_ = ky_[1];
  ax_ = kx_[2]; ay_ = ky_[2];
}

// ---------------------------------------------------------------------------
// Exponentially weighted least-squares quadratic over the last `poly_window`
// samples, time measured backwards from the newest sample. Solved in closed
// form (3×3 symmetric normal equations, Cramer's rule); both axes share the
// matrix.
// ---------------------------------------------------------------------------
void StrokePredictor::update_poly() noexcept {
  const uint32_t win = static_cast<uint32_t>(cfg_.poly_window);
  const uint32_t m = n_ < win ? n_ : win;
  double s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0;
  double bx0 = 0, bx1 = 0, bx2 = 0, by0 = 0, by1 = 0, by2 = 0;
  double w = 1.0;
  for (uint32_t i = 0; i < m; ++i) {
    const uint32_t idx = (ring_pos_ + kMaxPolyWindow - 1 - i) % kMaxPolyWindow;
    const double t = ring_t_[idx] - t_ms_;  // <= 0
    const double x = ring_x_[idx] - last_x_, y = ring_y_[idx] - last_y_;
    const double t2 = t * t;
    s0 += w; s1 += w * t; s2 += w * t2; s3 += w * t2 * t; s4 += w * t2 * t2;
    bx0 += w * x; bx1 += w * x * t; bx2 += w * x * t2;
    by0 += w * y; by1 += w * y * t; by2 += w * y * t2;
    w *= cfg_.poly_decay;
  }
  // | s0 s1 s2 | |c0|   |b0|
  // | s1 s2 s3 | |c1| = |b1|
  // | s2 s3 s4 | |c2|   |b2|
  const double c00 = s2 * s4 - s3 * s3;
  const double c01 = s1 * s4 - s3 * s2;
  const double c02 = s1 * s3 - s2 * s2;
  const double det = s0 * c00 - s1 * c01 + s2 * c02;
  const double scale = s0 * s2 * s4;
  if (m < 3 || !(std::fabs(det) > 1e-9 * std::max(scale, 1e-300))) {
    // Ill-conditioned (too few or clustered samples): linear fallback.
    const double dt = t_ms_ - prev_t_ms_;
    if (dt > 1e-3) {
      vx_ = (last_x_ - prev_x_) / dt;
      vy_ = (last_y_ - prev_y_) / dt;
    }
    ax_ = ay_ = 0;
    return;
  }
  const double inv = 1.0 / det;
  auto solve = [&](double b0, double b1, double b2, double* c1, double* c2) {
    // Cramer's rule for c1 and c2.
    const double d1 = s0 * (b1 * s4 - s3 * b2) - b0 * (s1 * s4 - s3 * s2) + s2 * (s1 * b2 - b1 * s2);
    const double d2 = s0 * (s2 * b2 - b1 * s3) - s1 * (s1 * b2 - b1 * s2) + b0 * (s1 * s3 - s2 * s2);
    *c1 = d1 * inv;
    *c2 = d2 * inv;
  };
  double c1, c2;
  solve(bx0, bx1, bx2, &c1, &c2);
  vx_ = c1; ax_ = 2.0 * c2;
  solve(by0, by1, by2, &c1, &c2);
  vy_ = c1; ay_ = 2.0 * c2;
}

void StrokePredictor::add(int64_t t_ns, float x, float y, float pressure) noexcept {
  if (n_ == 0) {
    t0_ns_ = t_ns;
    t_ms_ = 0;
    last_x_ = prev_x_ = x;
    last_y_ = prev_y_ = y;
    last_p_ = prev_p_ = pressure;
    prev_t_ms_ = 0;
    conf_t_ = 0;
    kx_[0] = x; kx_[1] = 0; kx_[2] = 0;
    ky_[0] = y; ky_[1] = 0; ky_[2] = 0;
    P_[0] = cfg_.kalman_meas_r; P_[1] = 0; P_[2] = 0;
    P_[3] = 1.0;   // (px/ms)^2: we know nothing about the initial velocity
    P_[4] = 0;
    P_[5] = 0.01;  // (px/ms^2)^2
    ring_t_[0] = 0; ring_x_[0] = x; ring_y_[0] = y;
    ring_pos_ = 1;
    n_ = 1;
    return;
  }
  prev_t_ms_ = t_ms_;
  prev_x_ = last_x_;
  prev_y_ = last_y_;
  prev_p_ = last_p_;
  t_ms_ = double(t_ns - t0_ns_) * 1e-6;
  last_x_ = x;
  last_y_ = y;
  last_p_ = pressure;
  const double dt = t_ms_ - prev_t_ms_;
  ++n_;

  if (dt > 1e-3) dpdt_ += 0.5 * ((double(pressure) - prev_p_) / dt - dpdt_);

  ring_t_[ring_pos_] = t_ms_;
  ring_x_[ring_pos_] = x;
  ring_y_[ring_pos_] = y;
  ring_pos_ = (ring_pos_ + 1) % kMaxPolyWindow;

  switch (cfg_.model) {
    case PredictorModel::kKalman:
      update_kalman(dt > 0 ? dt : 0, x, y);
      break;
    case PredictorModel::kPolynomial:
      update_poly();
      break;
    case PredictorModel::kLinear:
      if (dt > 1e-3) {
        vx_ = (last_x_ - prev_x_) / dt;
        vy_ = (last_y_ - prev_y_) / dt;
      }
      ax_ = ay_ = 0;
      break;
    case PredictorModel::kNone:
      break;
  }

  if (dt > 1e-3) {
    const double jx = (ax_ - prev_ax_) / dt, jy = (ay_ - prev_ay_) / dt;
    jerk_ += cfg_.jerk_smoothing * (std::sqrt(jx * jx + jy * jy) - jerk_);
  }
  prev_ax_ = ax_;
  prev_ay_ = ay_;
}

float StrokePredictor::gate_horizon(float target) noexcept {
  float h = 0.0f;
  const double speed = std::sqrt(vx_ * vx_ + vy_ * vy_);
  if (n_ >= uint32_t(cfg_.warmup_samples) && cfg_.model != PredictorModel::kNone &&
      speed >= cfg_.min_speed && target > 0.0f) {
    const double span = std::max(1e-6, double(cfg_.full_speed - cfg_.min_speed));
    double hd = target * std::min(1.0, (speed - cfg_.min_speed) / span);
    const double g = cfg_.accel_gain;
    const double ax = ax_ * g, ay = ay_ * g;

    // Turn cap: heading of the extrapolated path changes at ω = |v×a|/|v|².
    const double omega = std::fabs(vx_ * ay - vy_ * ax) / (speed * speed);
    if (omega * hd > cfg_.max_turn_rad) hd = cfg_.max_turn_rad / omega;

    // Braking cap: never extrapolate past where the pen would stop.
    const double a_along = (vx_ * ax + vy_ * ay) / speed;
    if (a_along < 0) hd = std::min(hd, cfg_.stop_fraction * speed / -a_along);

    // Pen-lift approach: low and falling pressure.
    if (last_p_ < cfg_.lift_pressure && dpdt_ < 0)
      hd = std::min(hd, cfg_.lift_fraction * double(last_p_) / -dpdt_);

    // Jerk cap: |j|·h³/6 ≤ budget (unmodelled third-order term).
    if (cfg_.jerk_error_px > 0 && jerk_ > 0) {
      const double hj = std::cbrt(6.0 * cfg_.jerk_error_px / jerk_);
      if (hj < hd) hd = hj;
    }

    // Manoeuvre detection: the filter is surprised, trust it less.
    if (cfg_.model == PredictorModel::kKalman && nis_ > cfg_.maneuver_nis)
      hd *= cfg_.maneuver_nis / nis_;

    // Hard distance cap (two fixed-point steps are plenty: d(h) is ~linear).
    for (int it = 0; it < 2; ++it) {
      const double dx = vx_ * hd + 0.5 * ax * hd * hd;
      const double dy = vy_ * hd + 0.5 * ay * hd * hd;
      const double d = std::sqrt(dx * dx + dy * dy);
      if (d <= cfg_.max_distance_px) break;
      hd *= cfg_.max_distance_px / d;
    }
    h = float(std::max(0.0, hd));
  }

  // Confidence = gated / requested horizon. It drops instantly (fast attack)
  // and recovers at a bounded rate (slow release), so the tail never flickers.
  // Smoothing the *ratio* (not the horizon) lets the requested horizon change
  // every frame (scan-out-aware timing) without fighting the smoother; at a
  // constant horizon it is identical to smoothing the horizon itself.
  const float c = target > 0.0f ? h / target : 0.0f;
  const double elapsed = std::max(0.0, t_ms_ - conf_t_);
  conf_t_ = t_ms_;
  if (c <= conf_) {
    conf_ = c;
  } else {
    const double nominal = std::max(1e-3f, cfg_.horizon_ms);
    conf_ = std::min(c, conf_ + float(cfg_.horizon_rise_rate * elapsed / nominal));
  }
  return target * conf_;
}

uint32_t StrokePredictor::predict(Prediction* out) noexcept { return predict(out, cfg_.horizon_ms); }

uint32_t StrokePredictor::predict(Prediction* out, float horizon_ms) noexcept {
  const float h = gate_horizon(horizon_ms);
  out->horizon_ms = h;
  out->confidence = horizon_ms > 0 ? h / horizon_ms : 0.0f;
  if (h < 0.25f) {
    out->count = 0;
    euro_init_ = false;
    return 0;
  }
  const double g = cfg_.accel_gain;
  const double ax = ax_ * g, ay = ay_ * g;
  const double hd = h;
  double dx = vx_ * hd + 0.5 * ax * hd * hd;
  double dy = vy_ * hd + 0.5 * ay * hd * hd;

  // One-Euro on the end-point offset; the correction is distributed linearly
  // along the tail so the tail still starts exactly at the newest sample.
  double cx = 0, cy = 0;
  if (cfg_.smooth_tail) {
    auto alpha = [](double cutoff_hz, double dt_s) {
      const double tau = 1.0 / (2.0 * 3.14159265358979 * cutoff_hz);
      return 1.0 / (1.0 + tau / dt_s);
    };
    if (!euro_init_) {
      euro_init_ = true;
      euro_x_ = float(dx); euro_y_ = float(dy);
      euro_dx_ = euro_dy_ = 0;
      euro_t_ = t_ms_;
    } else {
      const double dt_s = std::max(1e-4, (t_ms_ - euro_t_) * 1e-3);
      euro_t_ = t_ms_;
      const double ad = alpha(cfg_.euro_d_cutoff_hz, dt_s);
      euro_dx_ += float(ad * ((dx - euro_x_) / dt_s - euro_dx_));
      euro_dy_ += float(ad * ((dy - euro_y_) / dt_s - euro_dy_));
      const double dspeed = std::sqrt(double(euro_dx_) * euro_dx_ + double(euro_dy_) * euro_dy_);
      const double a = alpha(cfg_.euro_min_cutoff_hz + cfg_.euro_beta * dspeed, dt_s);
      euro_x_ += float(a * (dx - euro_x_));
      euro_y_ += float(a * (dy - euro_y_));
    }
    cx = euro_x_ - dx;
    cy = euro_y_ - dy;
  }

  const int n = cfg_.tail_points;
  const double inv_n = 1.0 / n;
  const float p_min = 0.5f * last_p_;
  for (int i = 0; i < n; ++i) {
    const double f = double(i + 1) * inv_n;
    const double t = hd * f;
    PredictedPoint& pt = out->points[i];
    pt.x = float(last_x_ + vx_ * t + 0.5 * ax * t * t + cx * f);
    pt.y = float(last_y_ + vy_ * t + 0.5 * ay * t * t + cy * f);
    // Hold pressure; taper (to at most half) when the pen is lifting.
    float p = last_p_;
    if (dpdt_ < 0) p = std::max(p_min, float(last_p_ + dpdt_ * t));
    pt.pressure = p;
    pt.dt_ms = float(t);
  }
  out->count = uint32_t(n);
  return out->count;
}

}  // namespace noti
