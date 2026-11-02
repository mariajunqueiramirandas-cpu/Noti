#include "perf_session.h"

#include <android/log.h>
#include <android/performance_hint.h>
#include <dlfcn.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>

#include "noti/config.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "noti-ink", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "noti-ink", __VA_ARGS__)

namespace noti::android {
namespace {

long read_long(const char* path) {
  FILE* f = std::fopen(path, "r");
  if (!f) return -1;
  long v = -1;
  if (std::fscanf(f, "%ld", &v) != 1) v = -1;
  std::fclose(f);
  return v;
}

bool pin_to(int core) {
  cpu_set_t set;
  CPU_ZERO(&set);
  CPU_SET(core, &set);
  return sched_setaffinity(gettid(), sizeof(set), &set) == 0;
}

}  // namespace

int fastest_cpu() {
  int best = -1;
  long best_score = -1;
  for (int cpu = 0; cpu < 16; ++cpu) {
    char path[128];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
    long score = read_long(path);
    if (score < 0) {
      std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
      score = read_long(path);
    }
    if (score >= 0 && score >= best_score) {  // ties → higher index (prime core is last on Snapdragon)
      best_score = score;
      best = cpu;
    }
  }
  return best;
}

bool PerfSession::init(int64_t target_ns, bool pin_and_heat) {
  target_ns_ = target_ns;
  const pid_t tid = gettid();
  if (setpriority(PRIO_PROCESS, tid, -10) != 0 && setpriority(PRIO_PROCESS, tid, -8) != 0)
    LOGW("setpriority failed");

  if (pin_and_heat) {
    core_ = fastest_cpu();
    if (core_ >= 0 && pin_to(core_)) {
      heater_stop_.store(false, std::memory_order_relaxed);
      const int core = core_;
      heater_ = std::thread([this, core] {
        pin_to(core);
        sched_param p{};
        p.sched_priority = 0;
        if (sched_setscheduler(0, SCHED_IDLE, &p) != 0) {
          // Without SCHED_IDLE the heater would compete with the ink thread: don't run it.
          LOGW("SCHED_IDLE refused: core heater disabled");
          return;
        }
        while (!heater_stop_.load(std::memory_order_relaxed)) {
          for (int i = 0; i < 64; ++i) cpu_relax();
        }
      });
      LOGI("ink thread pinned to cpu%d with core heater", core_);
    } else {
      LOGW("could not pin ink thread (cpu %d)", core_);
      core_ = -1;
    }
  }

  APerformanceHintManager* mgr = APerformanceHint_getManager();
  if (!mgr) {
    LOGW("ADPF unavailable");
    return false;
  }
  const int32_t tids[1] = {tid};
  session_ = APerformanceHint_createSession(mgr, tids, 1, target_ns);
  if (!session_) {
    LOGW("ADPF createSession failed");
    return false;
  }
  // API 35 symbol, resolved at runtime so minSdk can stay at 33.
  if (auto f = reinterpret_cast<int (*)(APerformanceHintSession*, bool)>(
          dlsym(RTLD_DEFAULT, "APerformanceHint_setPreferPowerEfficiency"))) {
    f(session_, false);
  }
  report(0);
  return true;
}

void PerfSession::close() {
  if (heater_.joinable()) {
    heater_stop_.store(true, std::memory_order_relaxed);
    heater_.join();
  }
  if (session_) APerformanceHint_closeSession(session_);
  session_ = nullptr;
}

// Always report "over budget": ADPF then raises (and holds) the boost to the
// maximum instead of lowering it because a batch took 5 µs of a 1 ms target.
void PerfSession::report(int64_t actual_ns) {
  if (session_) APerformanceHint_reportActualWorkDuration(session_, std::max(actual_ns, 4 * target_ns_));
}

}  // namespace noti::android
