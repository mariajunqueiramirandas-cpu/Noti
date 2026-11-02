// Stroke predictor: draws ink where the nib *will be* to hide the digitizer,
// scan-out and LCD stages that software cannot remove (plan §4.3).
//
// Pipeline per sample (all fixed-size, no allocation, ~100–200 ns):
//
//   motion model ──► {v, a} at the newest sample
//        │             (constant-acceleration Kalman, weighted quadratic
//        │              least squares, or 2-point linear)
//        ▼
//   confidence gating ──► effective horizon h ≤ configured horizon
//        │   speed ramp · turn-angle cap · time-to-stop cap · pen-lift cap ·
//        │   manoeuvre (innovation) cap · max distance · fast-attack/slow-release
//        ▼
//   tail = raw newest point + v·t + ½·a·t²  for t in (0, h]   (curved tail)
//        │
//        ▼
//   optional One-Euro filter on the tail's end-point offset
//
// Rule from the plan: a wrong prediction is worse than latency. Every gate
// only ever *shortens* the horizon.
#pragma once

#include <cstdint>

#include "noti/config.h"

namespace noti {

enum class PredictorModel : uint8_t {
  kNone = 0,
  kLinear = 1,      // velocity from the last two samples
  kPolynomial = 2,  // exponentially weighted least-squares quadratic
  kKalman = 3,      // constant-acceleration Kalman filter, white-jerk process noise
};

const char* to_string(PredictorModel m) noexcept;

// Units: time in milliseconds, distance in surface pixels.
// Defaults come from `predictor_bench --tune` on synthetic 240 Hz handwriting
// (minimise mean ink-to-nib gap s.t. < 1 % frames with > 4 px overshoot).
// Re-tune on strokes recorded on the Pad 7 (`--csv`) before shipping.
struct PredictorConfig {
  PredictorModel model = PredictorModel::kKalman;
  float horizon_ms = 12.0f;  // measured software latency + feel-calibrated offset (§5.1, §5.4)
  int tail_points = 4;       // capsule joints along the predicted tail, 1..kMaxTailPoints

  // Kalman (shared covariance for x and y; same dt, Q and R for both axes).
  double kalman_jerk_q = 1.7e-4;  // white-jerk spectral density, px²/ms⁵
  double kalman_meas_r = 0.15;    // measurement variance, px²

  // Polynomial fit.
  int poly_window = 6;       // samples, 3..kMaxPolyWindow
  double poly_decay = 0.7;   // weight multiplier per sample of age

  // Acceleration term gain (1 = full constant-acceleration extrapolation).
  float accel_gain = 0.75f;

  // ---- confidence gating ----
  int warmup_samples = 3;          // samples before any prediction
  float min_speed = 0.135f;        // px/ms (≈1 cm/s); below this lag is < 2 px anyway: no prediction
  float full_speed = 0.34f;        // px/ms; horizon ramps linearly from min to full speed
  float max_turn_rad = 0.95f;      // max heading change over the predicted tail
  float stop_fraction = 0.9f;      // braking: h ≤ fraction × time-to-stop
  float lift_pressure = 0.30f;     // below this, falling pressure means pen-lift approach
  float lift_fraction = 0.5f;      // h ≤ fraction × estimated time-to-lift
  float maneuver_nis = 18.0f;      // innovation NIS above which the horizon shrinks (Kalman)
  // Jerk cap: constant-acceleration extrapolation ignores jerk, which costs
  // ≈ |j|·h³/6 of position error. Cap h so that error stays within budget;
  // prediction shortens exactly when motion stops being smooth (signatures,
  // scribbles) and stays long on smooth handwriting.
  float jerk_error_px = 0.72f;     // 0 disables
  float jerk_smoothing = 0.3f;     // EMA factor for the jerk estimate
  float max_distance_px = 90.0f;   // hard cap on tail length (~6.6 mm at 345 ppi)
  float horizon_rise_rate = 1.0f;  // ms of horizon regained per ms of stroke (shrinks instantly)

  // One-Euro filter on the tail end-point offset (§4.8: smoothing only on the tail).
  bool smooth_tail = false;
  float euro_min_cutoff_hz = 10.0f;
  float euro_beta = 0.02f;
  float euro_d_cutoff_hz = 10.0f;
};

struct PredictedPoint {
  float x, y, pressure;
  float dt_ms;  // time ahead of the newest real sample
};

struct Prediction {
  static constexpr int kMaxTailPoints = 8;
  PredictedPoint points[kMaxTailPoints];
  uint32_t count = 0;        // 0 = no prediction this frame
  float horizon_ms = 0.0f;   // effective horizon after gating
  float confidence = 0.0f;   // horizon_ms / configured horizon
};

class StrokePredictor {
 public:
  static constexpr int kMaxPolyWindow = 16;

  explicit StrokePredictor(const PredictorConfig& cfg = {}) noexcept;

  void configure(const PredictorConfig& cfg) noexcept;
  const PredictorConfig& config() const noexcept { return cfg_; }
  void set_horizon_ms(float h) noexcept { cfg_.horizon_ms = h; }

  // Start a new stroke (pen down).
  void reset() noexcept;

  // Feed one real sample (every sample, including historical ones).
  NOTI_HOT void add(int64_t t_ns, float x, float y, float pressure) noexcept;

  // Predict the tail from the newest sample. Returns out->count.
  NOTI_HOT uint32_t predict(Prediction* out) noexcept;

  uint32_t samples() const noexcept { return n_; }

  // Motion estimate at the newest sample (px/ms, px/ms²); valid after warm-up.
  float vx() const noexcept { return float(vx_); }
  float vy() const noexcept { return float(vy_); }
  float ax() const noexcept { return float(ax_); }
  float ay() const noexcept { return float(ay_); }

 private:
  void update_kalman(double dt, double x, double y) noexcept;
  void update_poly() noexcept;
  float gate_horizon(double dt_since_prev) noexcept;

  PredictorConfig cfg_;

  // Stroke state.
  uint32_t n_ = 0;
  int64_t t0_ns_ = 0;
  double t_ms_ = 0;  // newest sample time relative to t0
  double last_x_ = 0, last_y_ = 0;
  float last_p_ = 0;
  double prev_t_ms_ = 0, prev_x_ = 0, prev_y_ = 0;
  float prev_p_ = 0;
  double dpdt_ = 0;  // smoothed pressure rate, 1/ms

  // Motion estimate.
  double vx_ = 0, vy_ = 0, ax_ = 0, ay_ = 0;
  double prev_ax_ = 0, prev_ay_ = 0;
  double jerk_ = 0;  // smoothed |da/dt|, px/ms³

  // Kalman: state per axis [p, v, a]; covariance shared, symmetric 3×3.
  double kx_[3] = {}, ky_[3] = {};
  double P_[6] = {};  // P00 P01 P02 P11 P12 P22
  double nis_ = 0;    // smoothed normalised innovation squared

  // Polynomial ring of recent samples.
  double ring_t_[kMaxPolyWindow] = {}, ring_x_[kMaxPolyWindow] = {}, ring_y_[kMaxPolyWindow] = {};
  uint32_t ring_pos_ = 0;

  // Gated horizon state (fast attack, slow release).
  float h_state_ = 0;
  double h_state_t_ = 0;

  // One-Euro state on tail end offset.
  bool euro_init_ = false;
  double euro_t_ = 0;
  float euro_x_ = 0, euro_y_ = 0, euro_dx_ = 0, euro_dy_ = 0;
};

}  // namespace noti
