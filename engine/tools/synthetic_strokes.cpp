#include "synthetic_strokes.h"

#include <algorithm>
#include <cmath>
#include <fstream>
#include <map>
#include <sstream>

namespace noti::tools {
namespace {

constexpr double kPi = 3.14159265358979323846;

// Minimum-jerk position profile, s(0)=0, s(1)=1, zero velocity/accel at ends.
double minjerk(double u) {
  u = std::clamp(u, 0.0, 1.0);
  return u * u * u * (10.0 + u * (-15.0 + 6.0 * u));
}

// Pressure envelope: quick attack, slow wander, release at the end.
double pressure_env(double t, double dur, double base, double wobble_phase) {
  const double attack = std::min(1.0, t / 25.0);
  const double release = std::min(1.0, std::max(0.0, (dur - t) / 35.0));
  const double wander = 0.12 * std::sin(2 * kPi * t / 300.0 + wobble_phase);
  return std::clamp((base + wander) * std::sqrt(attack) * std::sqrt(release), 0.0, 1.0);
}

struct Gen {
  std::mt19937 rng;
  double uni(double a, double b) { return std::uniform_real_distribution<double>(a, b)(rng); }
};

Stroke sample(const std::string& kind, double dur, double dt, Gen& g, const SynthConfig& cfg,
              const std::vector<TruthPoint>& truth) {
  Stroke s;
  s.kind = kind;
  s.truth_dt_ms = dt;
  s.truth = truth;
  s.t0_ns = 1'000'000'000LL;
  std::normal_distribution<double> noise(0.0, cfg.noise_px);
  const double period = 1000.0 / cfg.rate_hz;
  double prev = -1;
  for (int k = 0;; ++k) {
    double t = k * period + (k ? g.uni(-cfg.jitter_ms, cfg.jitter_ms) : 0.0);
    if (t <= prev) t = prev + 0.01;
    if (t > dur) break;
    prev = t;
    const TruthPoint tp = s.at(t);
    s.t_ns.push_back(s.t0_ns + int64_t(std::llround(t * 1e6)));
    s.x.push_back(float(tp.x + noise(g.rng)));
    s.y.push_back(float(tp.y + noise(g.rng)));
    s.p.push_back(float(tp.p));
  }
  return s;
}

Stroke make_stroke(int kind, Gen& g, const SynthConfig& cfg) {
  const double dt = 0.05;
  const double mm = cfg.px_per_mm;
  std::vector<TruthPoint> tr;
  const double cx = g.uni(400, 1600), cy = g.uni(400, 1200);
  const double pbase = g.uni(0.35, 0.8), pph = g.uni(0, 6.28);

  switch (kind) {
    case 0: {  // cursive handwriting: drifting loops, 3–6 Hz, 2–4 mm amplitude
      const double dur = g.uni(500, 1500);
      const double f = g.uni(3.0, 6.0) / 1000.0, ax = g.uni(1.5, 3.0) * mm, ay = g.uni(2.0, 4.0) * mm;
      const double drift = g.uni(15, 35) * mm / 1000.0, ph = g.uni(0, 6.28), fm = g.uni(0.2, 0.6) / 1000.0;
      for (double t = 0; t <= dur; t += dt) {
        const double ph_t = 2 * kPi * f * t + 0.6 * std::sin(2 * kPi * fm * t);
        tr.push_back({cx + drift * t + ax * std::sin(ph_t + ph), cy + ay * std::cos(ph_t), pressure_env(t, dur, pbase, pph)});
      }
      return sample("cursive", dur, dt, g, cfg, tr);
    }
    case 1: {  // straight line, min-jerk: from careful (40 mm/s) to a flick (400 mm/s peak)
      const double len = g.uni(10, 60) * mm, dur = g.uni(120, 600);
      const double ang = g.uni(0, 2 * kPi);
      for (double t = 0; t <= dur; t += dt) {
        const double s = len * minjerk(t / dur);
        tr.push_back({cx + s * std::cos(ang), cy + s * std::sin(ang), pressure_env(t, dur, pbase, pph)});
      }
      return sample("line", dur, dt, g, cfg, tr);
    }
    case 2: {  // zigzag (M, W, N): point-to-point segments that stop at each corner
      const int segs = 3 + int(g.uni(0, 4));
      const double seg_dur = g.uni(90, 220);
      const double dur = segs * seg_dur;
      double px = cx, py = cy;
      std::vector<std::pair<double, double>> pts{{px, py}};
      for (int i = 0; i < segs; ++i) {
        px += g.uni(3, 8) * mm;
        py += (i % 2 ? 1 : -1) * g.uni(5, 12) * mm;
        pts.push_back({px, py});
      }
      for (double t = 0; t <= dur; t += dt) {
        const int i = std::min(segs - 1, int(t / seg_dur));
        const double u = minjerk((t - i * seg_dur) / seg_dur);
        tr.push_back({pts[i].first + u * (pts[i + 1].first - pts[i].first),
                      pts[i].second + u * (pts[i + 1].second - pts[i].second), pressure_env(t, dur, pbase, pph)});
      }
      return sample("zigzag", dur, dt, g, cfg, tr);
    }
    case 3: {  // circle / loop, eased in and out
      const double r = g.uni(2, 8) * mm, dur = g.uni(300, 900), turns = g.uni(0.8, 1.6);
      const double dir = g.uni(0, 1) < 0.5 ? -1 : 1, ph = g.uni(0, 2 * kPi);
      for (double t = 0; t <= dur; t += dt) {
        const double a = ph + dir * 2 * kPi * turns * minjerk(t / dur);
        tr.push_back({cx + r * std::cos(a), cy + r * std::sin(a), pressure_env(t, dur, pbase, pph)});
      }
      return sample("circle", dur, dt, g, cfg, tr);
    }
    default: {  // signature-like: several superimposed frequencies, fast
      const double dur = g.uni(700, 1800);
      double f[3], a[3], phx[3], phy[3];
      for (int i = 0; i < 3; ++i) {
        f[i] = g.uni(1.5, 8.0) / 1000.0;
        a[i] = g.uni(1, 5) * mm / (1 + i);
        phx[i] = g.uni(0, 6.28);
        phy[i] = g.uni(0, 6.28);
      }
      const double drift = g.uni(30, 70) * mm / 1000.0;
      for (double t = 0; t <= dur; t += dt) {
        double x = cx + drift * t, y = cy;
        for (int i = 0; i < 3; ++i) {
          x += a[i] * std::sin(2 * kPi * f[i] * t + phx[i]);
          y += 1.3 * a[i] * std::sin(2 * kPi * f[i] * 1.3 * t + phy[i]);
        }
        tr.push_back({x, y, pressure_env(t, dur, pbase, pph)});
      }
      return sample("signature", dur, dt, g, cfg, tr);
    }
  }
}

}  // namespace

TruthPoint Stroke::at(double t_ms) const {
  if (truth.empty()) return {0, 0, 0};
  if (truth.size() == 1) return truth[0];
  const double u = std::clamp(t_ms / truth_dt_ms, 0.0, double(truth.size() - 1));
  const size_t i = std::min(size_t(u), truth.size() - 2);
  const double f = u - double(i);
  const TruthPoint& a = truth[i];
  const TruthPoint& b = truth[i + 1];
  return {a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.p + (b.p - a.p) * f};
}

std::vector<Stroke> make_dataset(int count, uint32_t seed, const SynthConfig& cfg) {
  Gen g{std::mt19937(seed)};
  std::vector<Stroke> out;
  out.reserve(count);
  for (int i = 0; i < count; ++i) out.push_back(make_stroke(i % 5, g, cfg));
  return out;
}

bool load_csv(const std::string& path, std::vector<Stroke>* out, std::string* err) {
  std::ifstream in(path);
  if (!in) {
    *err = "cannot open " + path;
    return false;
  }
  std::map<long, Stroke> strokes;
  std::string line;
  while (std::getline(in, line)) {
    if (line.empty() || !(std::isdigit(static_cast<unsigned char>(line[0])) || line[0] == '-')) continue;
    std::replace(line.begin(), line.end(), ',', ' ');
    std::istringstream ss(line);
    long id;
    int64_t t;
    double x, y, p;
    if (!(ss >> id >> t >> x >> y >> p)) continue;
    Stroke& s = strokes[id];
    s.kind = "recorded";
    s.t_ns.push_back(t);
    s.x.push_back(float(x));
    s.y.push_back(float(y));
    s.p.push_back(float(p));
  }
  for (auto& [id, s] : strokes) {
    if (s.t_ns.size() < 4) continue;
    s.t0_ns = s.t_ns.front();
    // Truth: recorded samples resampled onto a dense uniform grid.
    s.truth_dt_ms = 0.05;
    const double dur = double(s.t_ns.back() - s.t0_ns) * 1e-6;
    size_t j = 0;
    for (double t = 0; t <= dur; t += s.truth_dt_ms) {
      while (j + 2 < s.t_ns.size() && double(s.t_ns[j + 1] - s.t0_ns) * 1e-6 < t) ++j;
      const double ta = double(s.t_ns[j] - s.t0_ns) * 1e-6, tb = double(s.t_ns[j + 1] - s.t0_ns) * 1e-6;
      const double f = tb > ta ? std::clamp((t - ta) / (tb - ta), 0.0, 1.0) : 0.0;
      s.truth.push_back({s.x[j] + (s.x[j + 1] - s.x[j]) * f, s.y[j] + (s.y[j + 1] - s.y[j]) * f,
                         s.p[j] + (s.p[j + 1] - s.p[j]) * f});
    }
    out->push_back(std::move(s));
  }
  if (out->empty()) {
    *err = "no strokes with >= 4 samples in " + path;
    return false;
  }
  return true;
}

}  // namespace noti::tools
