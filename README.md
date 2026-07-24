# Health Tracker

A personal health and lifestyle tracker for Android. Pulls objective metrics from
Health Connect and pairs them with the subjective and manual things it cannot
know about: fasting, hydration, waist, blood pressure, ketones, reps and reading.

## Screens

| Screen | What it does |
| --- | --- |
| **Today** | The landing page: current fast duration, weekly fast adherence, steps, hydration, caffeine, 24-hour glucose and ketone chart, waist, blood pressure, vibe/energy/focus, pushups and air squats, pages read |
| **Fasting** | Weekly feeding-window plan, scheduled multi-day extended fasts, the adherence score, a 14-day fasted/not-fasted timeline, and stats: totals, longest, average and streaks |
| **Trends** | 14-day and 90-day history for steps, waist, weight, resting heart rate, sleep, stacked macros, reps, mood and reading |
| **History** | Backfill or correct any past day |
| **Settings** | Units, daily goals, body targets |

## Health Connect

The app is **read-only** against Health Connect. It never writes, so no
`WRITE_*` permissions are requested. It reads:

- Steps
- Heart rate and resting heart rate
- Sleep sessions
- Total and active calories burned
- Nutrition: energy eaten, protein, carbohydrate, fat
- Weight
- Blood glucose (CGM)
- Exercise sessions, distance and speed, for mile pace

Each metric is fetched independently and failures degrade to a blank field, so
granting only some permissions still produces a working dashboard. Results are
cached per day in `HealthDaySnapshot` so trends and history survive offline.

### Steps and the source picker

More than one app usually writes steps — a watch's companion app and the phone's
own health app both counting the same walk. Health Connect's aggregate sums
them, which double-counts. The app instead reads raw step records grouped by the
app that wrote them, and **Settings → Step source** shows the per-app totals for
today so one can be pinned as the one that counts.

### Calories

Two different numbers, kept apart because they are easy to confuse, plus the
difference between them:

- **Eaten** comes from nutrition records.
- **Burned** comes from total and active calories burned.
- **Net** is eaten minus burned — green in deficit, red in surplus. It reads
  blank unless both halves are known, since substituting zero for a missing one
  would show a deficit the size of whichever figure happened to sync.

The Trends macro chart stacks protein, carbohydrate and fat **by calories**
(4/4/9 kcal per gram) rather than by grams, so bar height is total energy and
each band is its true share. Fat is a small share of the grams and often half
the calories.

### Best mile time

Health Connect has no mile-split concept. The app takes every running session of
at least a mile, divides elapsed time by distance and normalises to one mile,
then keeps the fastest. On a long run that includes a warmup this reads slower
than a true mile PR, so it is labelled *average pace*, not a personal best.

## Fast adherence

Adherence is the share of **elapsed** planned fasting time that was actually
fasted:

```
score = overlap(planned fast, logged fast) / planned fast   (0-100)
```

Only time up to now is scored. Future planned time is excluded, otherwise a week
that is going perfectly would read near zero on Monday morning.

- Each weekday has a feeding window; everything outside it is a planned fast.
- A window whose end is at or before its start wraps past midnight.
- A day switched off is unplanned and scored in neither direction.
- A scheduled extended fast overrides the daily windows for the span it covers.

The interval algebra behind this lives in `domain/Interval.kt`; the scoring is in
`domain/FastingAdherence.kt` and is covered by `FastingAdherenceTest`.

Fasts logged late can be corrected after the fact: the running fast's start can
be moved, it can be stopped at a past time, and the most recently finished fast
can have either end adjusted.

### Timeline and stats

The Fasting screen draws one row per day for the last fortnight, midnight to
midnight, with fasted stretches filled — so a late first meal or a fast broken
and restarted is visible as a shape rather than a number. Alongside it sit
totals for today, seven days and thirty days, the longest and average completed
fast, and the current and best streak.

Every total is computed with interval set algebra, so two sessions that overlap
— or one left open and started again — never count the same minute twice. Only
finished fasts contribute to longest and average; a running one would report its
length so far and beat itself an hour later.

## Caffeine

Caffeine is eliminated first-order with a **5-hour half-life**, so each dose
decays on its own and doses add together:

```
level(t) = Σ dose_i × 0.5 ^ ((t − t_i) / 5h)
```

The chart samples that curve every 10 minutes over a rolling 24 hours, which is
what gives it a smooth exponential shape rather than straight lines between
doses. Doses from before the window are still loaded, because one taken last
night is still in the body this morning. The maths is in `domain/Caffeine.kt`
and covered by `CaffeineTest`.

## Units

Everything is **stored in metric** to match Health Connect, and converted at the
display boundary. Waist steps in exact quarter-inches and defaults to 42".

## Colors

| Role | Name | Hex |
| --- | --- | --- |
| Primary | Baltic Blue | `#2F6690` |
| Secondary | Olive Bark | `#625834` |
| Background | Alabaster Grey | `#D9DCD6` |
| Accent | Yale Blue | `#16425B` |
| Accent | Inferno | `#A30000` |

The scheme is light-only and dynamic color is disabled — leaving it on would let
Android 12+ derive the palette from the user's wallpaper and discard these
entirely.

## Building

```bash
./gradlew :app:assembleDebug
```

The Gradle wrapper is checked in, so no local Gradle install is needed. Debug
builds use AGP's default debug keystore, generated on demand at
`~/.android/debug.keystore` — there is nothing to set up before the first build.

Requires a JDK 17+ (Android Studio's bundled JBR works) and Android SDK platform
36. Point at the SDK with a `local.properties` containing `sdk.dir=...`, or set
`ANDROID_HOME`. Minimum SDK 26, target 36.

Release builds are **not** configured out of the box: `assembleRelease` reads
`KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD` from the environment and
needs a real upload key. No signing material is stored in this repo.

### Tests

```bash
./gradlew :app:testDebugUnitTest
```

`FastingAdherenceTest` is pure JVM and covers the adherence maths, including the
midnight-wrapping feeding window, extended fasts overriding the daily plan,
disabled days, and the rule that future planned time is not scored.

### Verified toolchain

Last built green against Gradle 9.6.1, AGP 9.1.1, Kotlin 2.2.10, JDK 21,
Android SDK platform 36.1, build-tools 36.0.0.
