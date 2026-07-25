/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.benchmark.macro.base.util

/**
 * Package of the app under test for Baseline Profile generation and Macrobenchmark.
 *
 * Matches `:app` release / benchmark applicationId (`me.rosuh.easywatermark`).
 * Debug builds use `me.rosuh.easywatermark.debug` and are **not** this lane's target
 * (H1: measure near-release, not debug suffix).
 */
const val TARGET_PACKAGE = "me.rosuh.easywatermark"

/** Default Macrobenchmark iterations (observational — not an H3 SLO). */
const val DEFAULT_ITERATIONS = 10