# Noti

The lowest-latency PDF annotator and note-taking app for the **Xiaomi Pad 7 + Xiaomi Focus Pen**. It should feel like writing on paper.

- 📄 [Low-latency ink engine plan](docs/LOW_LATENCY_PLAN.md): hardware analysis, latency budget, engine architecture, measurement lab, and roadmap.
- ⚙️ [Native ink engine + predictor (C++20)](engine/README.md): lock-free input path, front-buffer GL renderer, tuned Kalman predictor, latency probe, benchmarks.
