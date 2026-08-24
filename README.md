# Health Tracker

A personal health and lifestyle tracker for Android. Pulls objective metrics from
Health Connect and pairs them with the subjective and manual things it cannot
know about: fasting, hydration, waist, grip strength, blood pressure, ketones,
reps and reading.

## Screens

| Screen | What it does |
| --- | --- |
| **Today** | The landing page: current fast duration, weekly fast adherence, steps, hydration, caffeine, a 3h-to-72h glucose and ketone chart, waist, grip strength, blood pressure, vibe/energy/focus, pushups and air squats, pages read |
| **Fasting** | Weekly feeding-window plan, scheduled multi-day extended fasts, the adherence score, a 14-day fasted/not-fasted timeline, and stats: totals, longest, average and streaks |
| **Master** | Everything on one timeline over 3h to 7d: meals spread into absorption curves, blood sugar, ketones, heart rate, caffeine and steps per hour |
| **Trends** | 7, 14, 30 and 90-day history for steps, waist, weight, grip strength, blood pressure, resting heart rate, sleep, stacked macros, reps, mood and reading — each against its own reference line |
| **History** | Backfill or correct any past day |
| **Settings** | Units, step source, daily goals, blood sugar target, reference line and chart bounds, blood pressure reference, body targets, weight waypoints |

## Reference lines

Every long-run chart is read against something. A bar that is taller than
yesterday's says nothing on its own; a bar that is above or below the line you
set says what you actually wanted to know. Steps, calories, sleep, pages read,
waist, weight and blood pressure each carry one, all of them set in Settings and
all drawn dashed to mark them as a target rather than a measurement.

**The chart stretches to hold its goal.** A rule outside the plot is not drawn at
the edge, it is clipped — so a weight chart scaled to a fortnight of readings
around 198 lb showed no 180 lb goal at all, and looked exactly like a chart with
no goal set. Weight and waist now scale to include theirs, so the distance left
to go is something you can see rather than something you have to know.

### Weight waypoints

Thirty pounds is a long way to read against a single line. Weight takes any
number of staged marks on the way to the goal — one every five pounds, or a
single halfway point, or none — added and removed in Settings. They are drawn
fainter and finer than the goal itself, because one of those lines is where you
are going and the rest are only on the way; five rules of equal weight would
leave you working out which of them was the point.

Blood pressure carries **two**, one per line — drawn with a systolic rule alone,
the diastolic trace had nothing to be read against at all. They start at the
published 120/80 and are adjustable because a clinician may have named different
numbers, not because the reader is free to decide what normal is. That is also
why they stay dashed while the glucose reference line is solid: one is a
published figure, the other is wherever you decided to put it, and the two should
not look alike.

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

More than one app usually writes steps -- a watch's companion app and the phone's
own health app both counting the same walk. An unfiltered aggregate sums them,
which double-counts. The app instead derives the contributing packages from the
aggregate's own data origins and re-aggregates per source, and
**Settings -> Step source** shows the per-app totals for today so one can be
pinned as the one that counts. The same pinned source drives the hourly step
bars on the Master screen, so the two screens can never disagree about how far
you walked.

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


## The master graph

One timeline carrying everything that might explain everything else: what was
eaten, how it is being absorbed, how much caffeine is still in the body, and what
blood sugar, ketones, heart rate and walking did in response. Windows run from 3
hours — about one meal, start to finish — out to 7 days, where individual meals
stop being legible but habits do not.

Vertical rules mark the hours: every hour up to a 12-hour window, every four
hours beyond it, widening again where a week's worth would arrive as forty lines
a finger-width apart. They sit on the **clock**, not on the edge of the window —
a rule at 2:47 cannot answer "how much of that rise was in the hour after
eating".

### Meals become curves

Health Connect records a meal as one lump of grams at a single instant, which
drawn literally is a vertical spike that says nothing about the hours the food is
actually acting over. Each meal is instead spread into a compartmental
absorption curve, normalised so the area under it is the grams eaten and its
height is grams per hour arriving. Carbohydrate peaks at 45 minutes, protein at
90, fat at 3.5 hours. All three are drawn dashed, because they are a model of
what the food is doing and not a measurement of it.

### Fixing what the source got wrong

A nutrition source is free to record only the *date*. Real data here arrived with
every meal stamped 10:00:00 local, three of them on one Tuesday — so every
absorption curve sat in an hour nobody ate in. No amount of arithmetic recovers a
clock time that was never written, so instead:

- A time of day **shared to the second by two different meals** is treated as a
  stamp rather than a measurement, and the meal is flagged rather than shown with
  a plausible-looking time. Real timestamps do not repeat to the second.
- Meals can be **corrected, deleted, or logged by hand** — the only Health
  Connect cache that is editable at all. Corrections survive re-syncing.
- The same source may also write one meal as several records. Records agreeing on
  timestamp, energy, all three macros and name are counted once; anything
  differing at all is kept, so a genuine second helping survives.

### Reading two units at a time

The plot has two gutters and the series carry six different units, so which two
get their numbers printed is a choice, not a fixed layout — comparing steps
against heart rate wants a different pair than comparing carbohydrate against
glucose. Unchosen units still plot, correctly shaped, against their own range
with the numbers quoted in the legend.

**The numbers in the gutter take the colour of the line they belong to**, which
on a plot carrying six units is the difference between reading a figure and
guessing at it. Only where the axis is serving exactly one visible line, though:
grams per hour is shared by carbohydrate, protein and fat, and tinting it in any
one of their colours would claim the other two are read against some other axis.
Switch two of the three off and the axis takes the survivor's colour.

Steps are drawn as hourly **bars** rather than a line: a step count belongs to
the hour it accumulated over, and joining the hours would claim a walking rate at
instants when nothing was counted. Caffeine is drawn **dashed**, alongside the
macro curves and for the same reason: what was measured is the dose and the
minute it was drunk, and everything between two doses is a half-life model of
what became of it.

## Blood sugar

The glucose axis runs 60–180 mg/dL by default, and **both bounds are settable**:
a trace that lives between 80 and 120 is a flat line on a wide axis and a legible
swing on a narrow one, and which of those is right depends on whose blood sugar
it is. Neither figure clips anything — outliers expand the axis rather than being
cut off, so a 210 reading still plots; it is simply not budgeted for.

Three things can be drawn on it, and they are deliberately distinct:

- A **grey band** for the target range, set in Settings. A filled region answers
  "was it in range" at a glance.
- A **solid rule** at a single reference value, also set in Settings and
  defaulting to 100 mg/dL. This answers "above or below". It is solid where the
  blood pressure chart's 120 rule is dashed: that one is a published clinical
  figure, this one is wherever you decided to put it, and the two should not look
  alike.
- An optional **smoothed line**, off by default. A Gaussian-weighted moving
  average in *time* rather than in sample index, so it works on a dense monitor
  trace and a handful of hand-typed fingersticks alike. It never resamples or
  interpolates — one output per reading, at that reading's own timestamp — and
  being a weighted mean of real readings it cannot overshoot their range. The
  series is relabelled while it is on, because everything else on these charts is
  either a measurement or dashed to say it is a model.

### Gaps stay gaps

A line joining the last reading before a gap to the first one after draws a
straight run through hours that were never recorded, in the same ink as the
readings either side — a watch taken off overnight produced an eight-hour
diagonal that looked exactly like data. Measured series break instead.

The threshold comes from each series' own cadence (four times its median
spacing) rather than being fixed, because a fixed one is wrong for somebody:
twenty minutes of silence is a dropout for a monitor writing every five minutes
and an ordinary afternoon for three fingersticks a day. An isolated reading is
drawn as a dot rather than dropped.

### Holes get a second look

Not every gap is real. Glucose is cached a calendar day at a time and only
*today* is re-read on an ordinary refresh, so a monitor that was out of Bluetooth
range and uploaded its readings hours later writes them to a day nothing asks
about any more. The hole is then permanent, and looks exactly like a sensor that
was genuinely not reporting.

So the holes themselves become the query. Every refresh looks over the last 72
hours, and anywhere the trace stops for **45 minutes or more** the source is
asked about that stretch again — including the stretch at the right-hand end,
where a monitor that stopped an hour ago leaves the freshest and most fillable
gap of all. Whatever comes back that is already held is discarded on its record
id. If anything was recovered, the Today card says how many readings, because a
line that grows a new hour in it without explanation is harder to trust than one
that says where the hour came from.

This is a different threshold from the one above, on purpose. Breaking a line
asks "was this measured"; going back to the source asks "is it worth a query",
and a reader taking three fingersticks a day has hours of genuine emptiness that
no re-read will ever fill.

## Grip strength

Dominant and non-dominant, logged in pounds and stored in kilograms like every
other body measurement. Dominant and non-dominant rather than left and right
because the pair is read as a ratio — a dominant hand normally squeezes about a
tenth harder — and that comparison survives a reader who does not know which hand
you write with. Both hands are tracked separately on the Trends chart, since a
gap that widens over months says something neither line says alone.
## Caffeine

Caffeine is eliminated first-order with a **5-hour half-life**, so each dose
decays on its own and doses add together.

A dose is not treated as arriving all at once. Nobody downs a coffee in a single
instant, and a vertical step in the curve implies a precision the logged time
does not have. Each dose is instead spread evenly over the **30 minutes ending
at its logged time** and that steady intake is integrated against the decay,
which rounds the jump into a short climb. A dose reads about 97% of its amount at
the moment it is logged, since its earliest part has already begun to clear. The
model collapses back to a plain instantaneous dose as that ramp shrinks to zero.

The chart samples the curve every 10 minutes over a rolling 24 hours, which is
what gives it a smooth exponential shape rather than straight lines between
doses. Doses from before the window are still loaded, because one taken last
night is still in the body this morning.

It then projects **six hours forward** — a little over one half-life, which is
the horizon that answers whether what is in the body now will have cleared by
bedtime. The projection is drawn dashed, past a dotted rule at the current time,
so it is never mistaken for a measurement.

The maths is in `domain/Caffeine.kt` and covered by `CaffeineTest`.

## Units

Everything is **stored in metric** to match Health Connect, and converted at the
display boundary. Waist steps in exact quarter-inches and defaults to 42"; grip
strength is stored in kilograms and shown in pounds. Health Connect has no grip
record, so nothing outside forces that unit -- but a second storage unit in the
same database is how rounding error gets in.

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

The pure-JVM suites cover the maths: fasting adherence and stats, caffeine decay,
macro absorption, glucose smoothing, meal de-duplication, stamped-time detection,
series gap-splitting, axis selection, gap backfill, gridline spacing and axis
range. `MasterGraphRenderTest` and
`ScreenRenderTest` compose whole screens against an in-memory database and
capture images with Roborazzi, which is what catches the empty-list and
divide-by-zero cases the chart canvas only reaches under a real layout pass.
`MigrationSchemaTest` diffs every hand-written migration against the schema Room
generates from the entities -- a mismatch there does not fail a build, it throws
on the next launch for anyone upgrading.

### Verified toolchain

Last built green against Gradle 9.6.1, AGP 9.1.1, Kotlin 2.2.10, JDK 21,
Android SDK platform 36.1, build-tools 36.0.0.
