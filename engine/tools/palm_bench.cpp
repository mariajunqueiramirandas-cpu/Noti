// Palm rejection harness.
//
//   palm_bench                         all synthetic scenarios × seeds × hands × size reporting
//   palm_bench --seeds 50 -v           more seeds, list every failure
//   palm_bench --csv a.csv [b.csv]     replay sessions recorded on the device (Settings → Palm lab)
//   palm_bench --csv a.csv --finger-ink
//   palm_bench --gate                  CI: fail on any regression (see below)
//
// Reported per group:
//   false accepts  palms that became a gesture or kept finger ink      (gate: 0)
//   false rejects  deliberate touches or finger strokes that were lost (gate: 0 when the
//                  screen reports contact size; ≤ 5 % when it doesn't)
//   transient ink  palms that drew tentative ink which was rolled back (reported only)
//   latency        touch-down → confirmed deliberate touch, p50 / p95  (gate: p95 ≤ 60 ms
//                  with contact size)
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "noti/clock.h"
#include "noti/palm_filter.h"
#include "synthetic_touches.h"

using namespace noti;
using namespace noti::tools;

namespace {

double pct(std::vector<double> v, double p) {
  if (v.empty()) return 0;
  std::sort(v.begin(), v.end());
  const size_t k = std::min(v.size() - 1, size_t(p / 100.0 * double(v.size())));
  return v[k];
}

void print_row(const char* name, const Score& s) {
  std::printf("  %-40s palms %4d  touches %4d  strokes %3d | FA %3d  FR %3d  flash %3d | latency p50 %5.1f p95 %5.1f ms\n",
              name, s.palms, s.touches, s.inks, s.false_accepts, s.false_rejects, s.transient_ink,
              pct(s.touch_latency_ms, 50), pct(s.touch_latency_ms, 95));
}

void merge(Score* a, const Score& b) {
  a->palms += b.palms;
  a->touches += b.touches;
  a->inks += b.inks;
  a->false_accepts += b.false_accepts;
  a->false_rejects += b.false_rejects;
  a->transient_ink += b.transient_ink;
  a->touch_latency_ms.insert(a->touch_latency_ms.end(), b.touch_latency_ms.begin(), b.touch_latency_ms.end());
  a->failures.insert(a->failures.end(), b.failures.begin(), b.failures.end());
}

const char* verdict_name(Verdict v) {
  return v == Verdict::kPalm ? "palm" : v == Verdict::kTouch ? "touch" : "undecided";
}

int run_csv(const std::vector<std::string>& files, bool finger_ink) {
  int rc = 0;
  for (const std::string& path : files) {
    Scenario s;
    std::string err;
    if (!load_touch_csv(path, finger_ink, &s, &err)) {
      std::fprintf(stderr, "%s\n", err.c_str());
      rc = 1;
      continue;
    }
    PalmFilter f;
    const ReplayOutput out = replay(s, f);
    Score sc;
    score(s, out, &sc);
    std::printf("%s: %zu events, %zu contacts\n", path.c_str(), s.events.size(), out.reports.size());
    std::printf("  serial  id      verdict    gesture  ink        decided(ms)  dur(ms)  peak   final\n");
    for (const ContactReport& r : out.reports) {
      std::printf("  %6u  %-6u  %-9s  %-7s  %-9s  %11.1f  %7.1f  %5.2f  %5.2f%s\n", r.serial, r.id,
                  verdict_name(r.verdict), r.gesture ? (r.gesture_cancelled ? "cancel" : "yes") : "-",
                  r.ink_committed ? "kept" : r.ink_started ? "rolled-back" : "-",
                  double(r.t_decided_ns - r.t_down_ns) * 1e-6, double(r.t_up_ns - r.t_down_ns) * 1e-6,
                  r.peak_score, r.final_score, r.system_palm ? "  (system)" : "");
    }
    print_row("total", sc);
    for (const std::string& fl : sc.failures) std::printf("    %s\n", fl.c_str());
  }
  return rc;
}

}  // namespace

int main(int argc, char** argv) {
  int seeds = 20;
  bool gate = false, verbose = false, finger_ink = false;
  std::vector<std::string> csv;
  for (int i = 1; i < argc; ++i) {
    if (!std::strcmp(argv[i], "--seeds") && i + 1 < argc) seeds = std::atoi(argv[++i]);
    else if (!std::strcmp(argv[i], "--gate")) gate = true;
    else if (!std::strcmp(argv[i], "-v")) verbose = true;
    else if (!std::strcmp(argv[i], "--finger-ink")) finger_ink = true;
    else if (!std::strcmp(argv[i], "--csv")) {
      while (i + 1 < argc && argv[i + 1][0] != '-') csv.push_back(argv[++i]);
    } else {
      std::fprintf(stderr, "usage: palm_bench [--seeds N] [--gate] [-v] [--csv file... [--finger-ink]]\n");
      return 2;
    }
  }
  if (!csv.empty()) return run_csv(csv, finger_ink);

  Score with_size, no_size;
  std::map<std::string, Score> by_scenario;
  int64_t replay_ns = 0;
  size_t events = 0;
  for (int seed = 1; seed <= seeds; ++seed) {
    for (int left = 0; left < 2; ++left) {
      for (int size = 0; size < 2; ++size) {
        TouchSynthConfig cfg;
        cfg.seed = uint32_t(seed);
        cfg.left_handed = left;
        cfg.report_size = size == 0;
        // One filter per session: the model learns across a session's scenarios,
        // as it would on the device.
        PalmFilter f;
        int64_t clock = 100'000'000'000;
        for (const Scenario& s : make_touch_scenarios(cfg)) {
          const int64_t t0 = now_ns();
          const ReplayOutput out = replay(s, f, clock);
          replay_ns += now_ns() - t0;
          events += s.events.size();
          clock += scenario_end(s) + 30'000'000'000;  // 30 s between scenarios
          Score sc;
          score(s, out, &sc);
          const std::string base = s.name.substr(0, s.name.find('/')) + (cfg.report_size ? "" : " (no size)");
          merge(&by_scenario[base], sc);
          merge(cfg.report_size ? &with_size : &no_size, sc);
        }
      }
    }
  }

  std::printf("Palm rejection on synthetic sessions: %d seeds × 2 hands\n\n", seeds);
  for (const auto& [name, sc] : by_scenario) print_row(name.c_str(), sc);
  std::printf("\n");
  print_row("TOTAL (screen reports contact size)", with_size);
  print_row("TOTAL (no contact size)", no_size);
  // Upper bound: includes the harness (timer simulation, copying outputs, scoring streams).
  std::printf("\n  cost: %.0f ns per input event, harness included (%zu events)\n",
              events ? double(replay_ns) / double(events) : 0.0, events);

  auto list = [&](const Score& s, const char* title) {
    if (s.failures.empty()) return;
    std::printf("\n%s (%zu):\n", title, s.failures.size());
    const size_t n = verbose ? s.failures.size() : std::min<size_t>(s.failures.size(), 12);
    for (size_t i = 0; i < n; ++i) std::printf("  %s\n", s.failures[i].c_str());
    if (n < s.failures.size()) std::printf("  ... (-v for all)\n");
  };
  list(with_size, "Failures with contact size");
  list(no_size, "Failures without contact size");

  if (!gate) return 0;
  bool ok = true;
  auto fail = [&](const char* why) {
    std::printf("GATE FAILED: %s\n", why);
    ok = false;
  };
  if (with_size.false_accepts || no_size.false_accepts) fail("a palm was accepted");
  if (with_size.false_rejects) fail("a deliberate touch was lost (contact size reported)");
  const int no_size_total = no_size.touches + no_size.inks;
  if (no_size_total && no_size.false_rejects * 20 > no_size_total) fail("more than 5% of touches lost (no contact size)");
  if (pct(with_size.touch_latency_ms, 95) > 60.0) fail("touch confirmation p95 > 60 ms");
  for (const auto& [name, sc] : by_scenario) {
    (void)name;
    if (sc.failures.size() && std::any_of(sc.failures.begin(), sc.failures.end(),
                                          [](const std::string& f) { return f.find("malformed") != std::string::npos; }))
      fail("malformed gesture or ink stream");
  }
  if (ok) std::printf("\ngate passed\n");
  return ok ? 0 : 1;
}
