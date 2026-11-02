#include "perf_session.h"

#include <android/log.h>
#include <android/performance_hint.h>
#include <dlfcn.h>
#include <sys/resource.h>
#include <unistd.h>

#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "noti-ink", __VA_ARGS__)

namespace noti::android {

bool PerfSession::init(int64_t target_ns) {
  const pid_t tid = gettid();
  if (setpriority(PRIO_PROCESS, tid, -8) != 0) LOGW("setpriority(-8) failed");

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
  prefer_power_efficiency_ = reinterpret_cast<int (*)(APerformanceHintSession*, bool)>(
      dlsym(RTLD_DEFAULT, "APerformanceHint_setPreferPowerEfficiency"));
  set_pen_near(false);
  return true;
}

void PerfSession::close() {
  if (session_) APerformanceHint_closeSession(session_);
  session_ = nullptr;
}

void PerfSession::report(int64_t actual_ns) {
  if (session_ && actual_ns > 0) APerformanceHint_reportActualWorkDuration(session_, actual_ns);
}

void PerfSession::set_pen_near(bool near) {
  near_ = near;
  if (session_ && prefer_power_efficiency_) prefer_power_efficiency_(session_, !near);
}

}  // namespace noti::android
