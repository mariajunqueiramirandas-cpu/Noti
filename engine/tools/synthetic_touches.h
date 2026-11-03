// Synthetic pen + multi-touch sessions with ground-truth labels, for the palm
// filter's tests and palm_bench. Each scenario is a hard case for palm
// rejection (the palm lands before the pen, palm + pinky tapping twice, the
// other hand pinching while you write, ...). Recorded sessions from the device
// load into the same structure (labels from the recording's scenario).
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

#include "noti/palm_filter.h"
#include "noti/sample.h"

namespace noti::tools {

enum class Expect : uint8_t {
  kPalm,     // must never become a gesture or leave ink
  kGesture,  // must reach the gesture stream (and not be cancelled)
  kInk,      // must be kept as finger ink
  kAny,      // unlabeled (recorded with label "?")
};

struct TimelineEvent {
  int64_t t;
  bool pen;
  InkSample ink;      // when pen
  TouchSample touch;  // otherwise
};

struct Scenario {
  std::string name;
  bool finger_ink = false;
  float px_per_mm = 13.5f;
  float view_w = 3000.0f, view_h = 2000.0f;
  std::vector<TimelineEvent> events;  // sorted by time
  std::map<uint32_t, Expect> expect;  // per contact id
};

struct TouchSynthConfig {
  uint32_t seed = 1;
  bool report_size = true;   // false: the touchscreen reports no contact size (worst case)
  bool left_handed = false;
};

// All scenarios for one seed.
std::vector<Scenario> make_touch_scenarios(const TouchSynthConfig& cfg);

// Feeds a scenario through a filter, simulating the ink thread's timer.
struct ReplayOutput {
  std::vector<GestureEvent> gestures;
  std::vector<InkSample> ink;
  std::vector<ContactReport> reports;
  bool streams_well_formed = true;  // every Down ends in Up/Cancel, no orphans
  std::string stream_error;
};
// `t_offset_ns` shifts every timestamp, so several scenarios can run through
// one filter (which learns across them) with a monotonic clock.
ReplayOutput replay(const Scenario& s, PalmFilter& f, int64_t t_offset_ns = 100'000'000'000);
// Last timestamp of a scenario (for chaining offsets).
int64_t scenario_end(const Scenario& s);

struct Score {
  int palms = 0, touches = 0, inks = 0;
  int false_accepts = 0;      // a palm became a gesture or kept ink
  int transient_ink = 0;      // a palm drew tentative ink that was rolled back
  int false_rejects = 0;      // a deliberate touch/stroke was lost
  std::vector<double> touch_latency_ms;  // touch-down → confirmed (gestures)
  std::vector<std::string> failures;
};
void score(const Scenario& s, const ReplayOutput& out, Score* acc);

// Loads a CSV written by PalmFilter::export_recording.
bool load_touch_csv(const std::string& path, bool finger_ink, Scenario* out, std::string* err);

}  // namespace noti::tools
