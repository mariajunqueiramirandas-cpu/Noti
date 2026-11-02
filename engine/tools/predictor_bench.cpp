// Offline predictor harness (plan Phase 2).
//
//   predictor_bench                       compare models × horizons on synthetic strokes
//   predictor_bench --csv strokes.csv     ... on strokes recorded on the Pad 7
//   predictor_bench --tune 300            random-search Kalman+gating params
//   predictor_bench --gate                CI: fail if the default predictor regresses
//
// For each sample k the predictor sees samples 0..k and predicts where the
// nib will be H ms later (H = the time until that ink is visible). Error is
// measured against ground truth at t_k + H. Gated predictions that stop
// short count as lag, so error is the visible gap between ink and nib.
//
// "Overshoot" is the part of the error that lies *ahead* of the real nib along
// its direction of travel: ink that appears where the pen never went. The plan's
// rule is to drive overshoot to ~0 first and extend the horizon second.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "noti/clock.h"
#include "noti/predictor.h"
#include "synthetic_strokes.h"

using namespace noti;
using namespace noti::tools;

namespace {

// Overshoot beyond this many px counts as visible (~0.3 mm at 345 ppi; the
// nib itself, ~1.5 mm wide, hides part of that region, so this is conservative).
double g_visible_px = 4.0;

struct Metrics {
  double mean = 0, p50 = 0, p95 = 0, p99 = 0;
  double base_mean = 0;  // no prediction (pure lag)
  double over_p99 = 0, over_rate = 0;  // overshoot p99 (px) and rate of > g_visible_px
  double mean_h = 0;     // mean effective horizon (ms)
  size_t n = 0;
};

double pct(std::vector<double>& v, double p) {
  if (v.empty()) return 0;
  const size_t k = std::min(v.size() - 1, size_t(p / 100.0 * double(v.size())));
  std::nth_element(v.begin(), v.begin() + long(k), v.end());
  return v[k];
}

Metrics evaluate(const std::vector<Stroke>& strokes, PredictorConfig cfg, double H) {
  cfg.horizon_ms = float(H);
  StrokePredictor pr(cfg);
  Prediction pred;
  std::vector<double> err, over;
  double base = 0, hsum = 0;
  size_t over_n = 0;
  for (const Stroke& s : strokes) {
    pr.reset();
    const double dur = s.duration_ms();
    for (size_t k = 0; k < s.size(); ++k) {
      pr.add(s.t_ns[k], s.x[k], s.y[k], s.p[k]);
      pr.predict(&pred);
      const double tk = double(s.t_ns[k] - s.t0_ns) * 1e-6;
      if (tk + H > dur) break;  // no ground truth past pen-up
      const TruthPoint truth = s.at(tk + H);
      double px = s.x[k], py = s.y[k];
      if (pred.count) {
        px = pred.points[pred.count - 1].x;
        py = pred.points[pred.count - 1].y;
      }
      const double ex = px - truth.x, ey = py - truth.y;
      err.push_back(std::hypot(ex, ey));
      base += std::hypot(s.x[k] - truth.x, s.y[k] - truth.y);
      hsum += pred.horizon_ms;

      // Overshoot along the true direction of travel from the current sample.
      const TruthPoint now = s.at(tk);
      const double mx = truth.x - now.x, my = truth.y - now.y, ml = std::hypot(mx, my);
      double o;
      if (ml < 0.5) {
        o = std::hypot(px - s.x[k], py - s.y[k]);  // nib ~still: any motion is overshoot
      } else {
        o = std::max(0.0, (ex * mx + ey * my) / ml);
      }
      over.push_back(o);
      if (o > g_visible_px) ++over_n;
    }
  }
  Metrics m;
  m.n = err.size();
  if (!m.n) return m;
  double sum = 0;
  for (double e : err) sum += e;
  m.mean = sum / double(m.n);
  m.base_mean = base / double(m.n);
  m.mean_h = hsum / double(m.n);
  m.p50 = pct(err, 50);
  m.p95 = pct(err, 95);
  m.p99 = pct(err, 99);
  m.over_p99 = pct(over, 99);
  m.over_rate = double(over_n) / double(m.n);
  return m;
}

double ns_per_sample(const std::vector<Stroke>& strokes, const PredictorConfig& cfg) {
  StrokePredictor pr(cfg);
  Prediction pred;
  size_t n = 0;
  double best = 1e30;
  for (int rep = 0; rep < 3; ++rep) {
    n = 0;
    const int64_t t0 = now_ns();
    for (const Stroke& s : strokes) {
      pr.reset();
      for (size_t k = 0; k < s.size(); ++k) {
        pr.add(s.t_ns[k], s.x[k], s.y[k], s.p[k]);
        pr.predict(&pred);
        ++n;
      }
    }
    best = std::min(best, double(now_ns() - t0) / double(n));
  }
  return best;
}

// The tuner minimises the mean visible gap subject to overshoot constraints
// (soft, but steep): visible overshoot in < 1 % of frames, p99 overshoot
// within 1.5× the visibility threshold. A wrong prediction is worse than lag.
double objective(const Metrics& m) {
  return m.mean + 4.0 * std::max(0.0, m.over_p99 - 1.5 * g_visible_px) + 400.0 * std::max(0.0, m.over_rate - 0.01);
}
double objective(const std::vector<Stroke>& s, const PredictorConfig& c) {
  return 0.5 * (objective(evaluate(s, c, 8.0)) + objective(evaluate(s, c, 12.0)));
}

void print_header() {
  std::printf("%-8s %5s | %7s %7s %7s %7s | %7s %5s | %8s %7s | %6s   (px; over%% = frames with > %.1f px overshoot)\n",
              "model", "H ms", "mean", "p50", "p95", "p99", "no-pred", "gain", "over p99", "over%", "h_eff", g_visible_px);
}
void print_row(const char* name, double H, const Metrics& m) {
  std::printf("%-8s %5.1f | %7.2f %7.2f %7.2f %7.2f | %7.2f %4.0f%% | %8.2f %6.2f%% | %6.2f\n", name, H, m.mean,
              m.p50, m.p95, m.p99, m.base_mean, 100.0 * (1.0 - m.mean / m.base_mean), m.over_p99,
              100.0 * m.over_rate, m.mean_h);
}

PredictorConfig tune(const std::vector<Stroke>& train, double H, int iters, uint32_t seed) {
  std::mt19937 rng(seed);
  auto lu = [&](double lo, double hi) {  // log-uniform
    return std::exp(std::uniform_real_distribution<double>(std::log(lo), std::log(hi))(rng));
  };
  auto u = [&](double lo, double hi) { return std::uniform_real_distribution<double>(lo, hi)(rng); };
  (void)H;
  PredictorConfig best;
  double best_obj = objective(train, best);
  std::printf("tune: start objective %.3f\n", best_obj);
  for (int i = 0; i < iters; ++i) {
    PredictorConfig c = best;
    // Half the time perturb the incumbent, half the time sample fresh.
    const bool local = i % 2 == 0;
    auto pick_log = [&](double cur, double lo, double hi) {
      return local ? std::clamp(cur * lu(0.6, 1.66), lo, hi) : lu(lo, hi);
    };
    auto pick = [&](double cur, double lo, double hi, double step) {
      return local ? std::clamp(cur + u(-step, step), lo, hi) : u(lo, hi);
    };
    c.kalman_jerk_q = pick_log(c.kalman_jerk_q, 1e-6, 1e-1);
    c.kalman_meas_r = pick_log(c.kalman_meas_r, 1e-3, 4.0);
    c.accel_gain = float(pick(c.accel_gain, 0.0, 1.0, 0.15));
    c.max_turn_rad = float(pick(c.max_turn_rad, 0.15, 1.5, 0.15));
    c.stop_fraction = float(pick(c.stop_fraction, 0.2, 1.0, 0.1));
    c.min_speed = float(pick(c.min_speed, 0.0, 0.2, 0.02));
    c.full_speed = float(std::max<double>(c.min_speed + 0.01, pick(c.full_speed, 0.05, 1.0, 0.1)));
    c.maneuver_nis = float(pick(c.maneuver_nis, 1.0, 200.0, 5.0));
    c.jerk_error_px = float(pick(c.jerk_error_px, 0.3, 12.0, 1.0));
    const double obj = objective(train, c);
    if (obj < best_obj) {
      best_obj = obj;
      best = c;
      std::printf("tune: iter %4d objective %.3f  q=%.3g r=%.3g ag=%.2f turn=%.2f stop=%.2f vmin=%.3f vfull=%.3f nis=%.1f jerk=%.2f\n",
                  i, obj, c.kalman_jerk_q, c.kalman_meas_r, c.accel_gain, c.max_turn_rad, c.stop_fraction,
                  c.min_speed, c.full_speed, c.maneuver_nis, c.jerk_error_px);
    }
  }
  return best;
}

}  // namespace

int main(int argc, char** argv) {
  int n_strokes = 1500;
  uint32_t seed = 7;
  std::string csv;
  int tune_iters = 0;
  bool gate = false;
  bool by_kind = false;
  std::vector<double> horizons = {4, 8, 12, 16};
  for (int i = 1; i < argc; ++i) {
    std::string a = argv[i];
    auto next = [&]() -> const char* { return i + 1 < argc ? argv[++i] : ""; };
    if (a == "--strokes") n_strokes = std::atoi(next());
    else if (a == "--seed") seed = uint32_t(std::atoi(next()));
    else if (a == "--csv") csv = next();
    else if (a == "--tune") tune_iters = std::atoi(next());
    else if (a == "--gate") gate = true;
    else if (a == "--by-kind") by_kind = true;
    else if (a == "--visible-px") g_visible_px = std::atof(next());
    else if (a == "--horizons") {
      horizons.clear();
      std::string list = next();
      for (char* tok = std::strtok(list.data(), ","); tok; tok = std::strtok(nullptr, ",")) horizons.push_back(std::atof(tok));
    } else {
      std::fprintf(stderr, "usage: %s [--strokes N] [--seed S] [--csv file] [--horizons 4,8,12] [--tune iters] [--visible-px 4] [--by-kind] [--gate]\n", argv[0]);
      return 2;
    }
  }

  std::vector<Stroke> strokes;
  if (!csv.empty()) {
    std::string err;
    if (!load_csv(csv, &strokes, &err)) {
      std::fprintf(stderr, "%s\n", err.c_str());
      return 1;
    }
  } else {
    strokes = make_dataset(n_strokes, seed);
  }
  size_t samples = 0;
  for (auto& s : strokes) samples += s.size();
  std::printf("dataset: %zu strokes, %zu samples%s\n\n", strokes.size(), samples, csv.empty() ? " (synthetic, 240 Hz, σ=0.2 px)" : "");

  if (tune_iters > 0) {
    const auto valid = csv.empty() ? make_dataset(n_strokes, seed + 1000) : strokes;
    PredictorConfig best = tune(strokes, 12.0, tune_iters, seed);
    std::printf("\nvalidation (held-out seed):\n");
    print_header();
    for (double H : horizons) print_row("tuned", H, evaluate(valid, best, H));
    print_row("default", 12.0, evaluate(valid, PredictorConfig{}, 12.0));
    return 0;
  }

  if (gate) {
    // CI gate for the shipped defaults at the plan's nominal 12 ms horizon.
    const Metrics m = evaluate(strokes, PredictorConfig{}, 12.0);
    print_header();
    print_row("kalman", 12.0, m);
    const double gain = 1.0 - m.mean / m.base_mean;
    const double ns = ns_per_sample(strokes, PredictorConfig{});
    std::printf("\ncost: %.0f ns per sample (add + predict)\n", ns);
    bool ok = true;
    // Regression guards around the tuned defaults (80 % gain, 2.7 px, 0.15 %).
    if (gain < 0.75) { std::printf("GATE FAIL: gain %.0f%% < 75%%\n", gain * 100); ok = false; }
    if (m.over_p99 > 3.5) { std::printf("GATE FAIL: overshoot p99 %.2f px > 3.5 px\n", m.over_p99); ok = false; }
    if (m.over_rate > 0.01) { std::printf("GATE FAIL: visible overshoot in %.2f%% of frames > 1%%\n", m.over_rate * 100); ok = false; }
    if (ns > 2000) { std::printf("GATE FAIL: %.0f ns per sample > 2000 ns\n", ns); ok = false; }
    if (ok) std::printf("GATE PASS\n");
    return ok ? 0 : 1;
  }

  if (by_kind) {
    print_header();
    for (const char* kind : {"cursive", "line", "zigzag", "circle", "signature", "recorded"}) {
      std::vector<Stroke> sub;
      for (const Stroke& s : strokes)
        if (s.kind == kind) sub.push_back(s);
      if (sub.empty()) continue;
      for (double H : horizons) print_row(kind, H, evaluate(sub, PredictorConfig{}, H));
    }
    return 0;
  }

  print_header();
  const PredictorModel models[] = {PredictorModel::kLinear, PredictorModel::kPolynomial, PredictorModel::kKalman};
  for (double H : horizons) {
    for (PredictorModel model : models) {
      PredictorConfig c;
      c.model = model;
      print_row(to_string(model), H, evaluate(strokes, c, H));
    }
    PredictorConfig c;
    c.smooth_tail = true;
    print_row("kal+1€", H, evaluate(strokes, c, H));
    std::printf("\n");
  }
  std::printf("cost per sample (add + predict, best of 3):\n");
  for (PredictorModel model : models) {
    PredictorConfig c;
    c.model = model;
    std::printf("  %-8s %6.0f ns\n", to_string(model), ns_per_sample(strokes, c));
  }
  return 0;
}
