# Gradle Isolated Projects benchmark (ROS-100)

Measured on a Cloud Agent VM: JDK 17, Android SDK 37, Gradle 9.7.0, 8 workers.
Six Gradle projects (`:app`, `:shared`, `:desktopApp`, `:cmonet`, `:baseBenchmarks`, `:macrobenchmark`).

Script: `scripts/benchmark-isolated-projects.sh`.
Compare `--no-isolated-projects` (baseline) vs `--isolated-projects`.
Configuration Cache, `org.gradle.parallel`, and `org.gradle.tooling.parallel` stay on in both modes.

## Results

Wall times. First run after `--stop` includes daemon start; later **miss** runs delete only the configuration-cache directory.

| Scenario | Baseline | Isolated Projects | Delta |
|---|---|---|---|
| `help` miss (daemon start) | 7.8s | 6.8s | −13% |
| `help` miss (steady) | 1.06s / 1.02s | 0.95s / 0.97s | −8% |
| `help` hit | 0.43–0.49s | 0.43–0.48s | ~0 |
| `:app:assembleDebug --dry-run` miss (daemon start, warmed deps) | 9.2s | 8.3s | −10% |
| `:app:assembleDebug --dry-run` miss (steady) | 1.62s / 1.51s | 1.65s / 1.38s | ~0 to −9% |
| `:app:assembleDebug` miss (after a successful assemble) | 10.5s | 9.3s | −11% |
| `:app:assembleDebug` hit | 0.81s | 0.84s | ~0 |

First-ever `:app:assembleDebug --dry-run` on this VM was **58s**. That run paid for AGP/Android first configuration and artifact download, not Isolated Projects. Do not use it as a before/after.

Warmup `:app:assembleDebug` (baseline, first compile) was **1m 35s** (87 tasks). Later assemble miss/hit numbers above are configuration + mostly UP-TO-DATE execution.

## Reading

Isolated Projects helps **configuration-cache miss** (configure projects again). Hits skip configuration, so they stay the same.

On this 6-module tree the miss saving is about **8–13%**, hundreds of milliseconds to ~1s. That matches the Gradle/Android write-up: the large Sync wins are for hundreds-to-thousands of modules.

IDE Sync is not timed here (no Android Studio on the agent). `org.gradle.isolated-projects=true` must live in `gradle.properties` for Studio to pick it up.

## Enablement notes

- `compose-stability-analyzer` **0.12.0** failed Isolated Projects (`Project ':app' cannot access the tasks in the task graph that were created by other projects`).
- **0.13.0** is Isolated Projects compatible and matches Kotlin 2.4.10.
- Isolated Projects is still incubating in Gradle 9.7.
- Plugin / CMP / KMP version audit: `docs/gradle-isolated-projects-compat.md`.
