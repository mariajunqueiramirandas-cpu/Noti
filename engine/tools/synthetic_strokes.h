// Synthetic pen strokes with analytic ground truth, for the predictor harness,
// the engine benchmark and the tests. Real strokes recorded on the Pad 7 are
// loaded from CSV with the same structure (truth = the recorded samples).
#pragma once

#include <cstdint>
#include <random>
#include <string>
#include <vector>

namespace noti::tools {

struct TruthPoint { double x, y, p; };

struct Stroke {
  std::string kind;
  // Dense ground truth at `truth_dt_ms` spacing (noise-free).
  double truth_dt_ms = 0.05;
  std::vector<TruthPoint> truth;
  // What the digitizer reports: 240 Hz, jittered timestamps, noisy positions.
  std::vector<int64_t> t_ns;
  std::vector<float> x, y, p;
  int64_t t0_ns = 0;

  double duration_ms() const { return truth.empty() ? 0 : (truth.size() - 1) * truth_dt_ms; }
  // Ground truth at a time relative to the stroke start (linear interpolation).
  TruthPoint at(double t_ms) const;
  size_t size() const { return t_ns.size(); }
};

struct SynthConfig {
  double rate_hz = 240.0;
  double jitter_ms = 0.15;   // uniform ± timestamp jitter
  double noise_px = 0.2;     // Gaussian position noise σ
  double px_per_mm = 13.6;   // Xiaomi Pad 7: ~345 ppi
};

// Generates `count` strokes cycling through all stroke kinds.
std::vector<Stroke> make_dataset(int count, uint32_t seed, const SynthConfig& cfg = {});

// CSV: stroke_id,t_ns,x,y,pressure  (header optional). Truth = samples.
bool load_csv(const std::string& path, std::vector<Stroke>* out, std::string* err);

}  // namespace noti::tools
