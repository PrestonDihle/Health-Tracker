# Health Tracker

A personal health and lifestyle tracker for Android. Pulls objective metrics from
Health Connect and pairs them with the subjective and manual things it cannot
know about: fasting, hydration, waist, blood pressure, ketones, reps and reading.

## Screens

| Screen | What it does |
| --- | --- |
| **Today** | The landing page: current fast duration, weekly fast adherence, steps, hydration, 24-hour glucose and ketone chart, waist, blood pressure, vibe/energy/focus, pushups and air squats, pages read |
| **Fasting** | Weekly feeding-window plan plus scheduled multi-day extended fasts, and the adherence score |
| **Trends** | 14-day and 90-day history for steps, waist, weight, resting heart rate, sleep, protein, reps, mood and reading |
| **History** | Backfill or correct any past day |
| **Settings** | Units, daily goals, body targets |

## Health Connect

The app is **read-only** against Health Connect. It never writes, so no
`WRITE_*` permissions are requested. It reads:

- Steps
- Heart rate and resting heart rate
- Sleep sessions
- Total and active calories
- Nutrition: protein, carbohydrate, fat
- Blood glucose (CGM)
- Exercise sessions, distance and speed, for mile pace

Each metric is fetched independently and failures degrade to a blank field, so
granting only some permissions still produces a working dashboard. Results are
cached per day in `HealthDaySnapshot` so trends and history survive offline.

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

Requires Android Studio and an Android SDK. Open the project directory and let
Gradle sync.

There is no Gradle wrapper checked in; Android Studio provisions Gradle on
import. To add one, run `gradle wrapper` with a local Gradle install.

Minimum SDK 26, target 36.
