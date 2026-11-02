#include "noti/stroke_recorder.h"

#include <cstdio>

namespace noti {

void StrokeRecorder::enable(bool on) {
  if (on && !buf_) buf_.reset(new Record[cap_]);
  on_ = on;
}

int64_t StrokeRecorder::export_csv(const char* path) const {
  if (!buf_) return 0;
  FILE* f = std::fopen(path, "w");
  if (!f) return -1;
  std::fputs("stroke_id,t_ns,x,y,pressure\n", f);
  const uint64_t n = count();
  const uint64_t first = n_ - n;
  for (uint64_t i = first; i < n_; ++i) {
    const Record& r = buf_[i & (cap_ - 1)];
    std::fprintf(f, "%u,%lld,%.3f,%.3f,%.5f\n", r.stroke, static_cast<long long>(r.t_ns), r.x, r.y, r.p);
  }
  const bool ok = std::fclose(f) == 0;
  return ok ? int64_t(n) : -1;
}

}  // namespace noti
