# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

The JDK is Android Studio's bundled JBR. On Windows, set it first:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

| Task | Command |
| --- | --- |
| Build debug APK | `.\gradlew.bat :app:assembleDebug` |
| Unit tests | `.\gradlew.bat :app:testDebugUnitTest` |
| One test class | `.\gradlew.bat :app:testDebugUnitTest --tests "*FastingAdherenceTest"` |
| One test method | `.\gradlew.bat :app:testDebugUnitTest --tests "*FastingAdherenceTest.a feeding window crossing midnight is handled"` |
| Android lint | `.\gradlew.bat :app:lintDebug` |

The APK lands at `app\build\outputs\apk\debug\app-debug.apk`. Test names are backticked and contain
spaces, so the `--tests` filter must be quoted as shown.

Deploying to a connected device (`adb` is at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then launch `com.prestondihle.healthtracker/.MainActivity`. `adb shell screencap` plus `adb pull`
is the way to see a UI change against real data.

`assembleRelease` is not usable out of the box — it reads `KEYSTORE_PATH`, `STORE_PASSWORD` and
`KEY_PASSWORD` from the environment and needs a real upload key. No signing material is in the repo.

## Architecture

Compose UI → ViewModel → `TrackerRepository` → (`TrackerDao` + `HealthDataSource`). One Gradle
module, `:app`.

**Manual DI, no framework.** `TrackerApp` holds a `DefaultAppContainer`, which lazily builds the
Room database, the `HealthConnectDataSource`, and the single `TrackerRepository`. `MainActivity`
pulls the container off the application and hands it to `TrackerNavHost`, which creates each
screen's ViewModel through that ViewModel's own `provideFactory(repository)` companion function.
Adding a screen means: a `Screen` enum entry, a `composable` block in `TrackerNavHost`, and a
ViewModel with a `provideFactory`. The bottom bar now carries six tabs (Today, Fasting, Master,
Trends, History, Settings) — Material divides the width evenly and truncates, so **new labels have to
be one short word**; "Master Graph" renders as "Master G...".

**ViewModels expose exactly one `StateFlow<...UiState>`**, assembled by `combine` over repository
flows and `stateIn(SharingStarted.WhileSubscribed(5_000))`. Derived values (fast duration, goal
fraction) are computed as `get()` properties on the UiState rather than stored. `DashboardViewModel`
additionally combines in a one-second `ticker` flow to drive the live fast timer. ViewModels take an
injectable `ZoneId` defaulting to `systemDefault()`, which is what makes the time maths testable.

**`TrackerRepository` is the only data entry point** and owns the conversion between the domain's
`Instant`/`LocalDate` vocabulary and the epoch-millisecond bounds the DAO queries expect
(`startOfDayMillis` / `endOfDayMillis`). Callers never do that arithmetic themselves.

### The two-tables rule for daily data

This is the most important invariant to preserve:

- `DailyLog` — purely manual, subjective fields (vibe, energy, focus, sleep quality, pages read).
- `HealthDaySnapshot` — a **cache** of everything read from Health Connect. Safe to delete and
  re-sync; never the source of truth.

Steps, sleep, calories and macros deliberately live only on the snapshot. Do not add a manual
column for anything Health Connect supplies — the split exists to prevent two writable sources of
truth for the same number.

Glucose is the exception to the snapshot pattern: it is a time series, not a daily total, so samples
are inserted as `BloodSugarReading` rows. Re-sync safety comes from the unique index on
`externalId` (SQLite treats NULLs as distinct, so manual readings are unaffected).

`MealEntry` and `HeartRateBucket` are the other two time series, added for the master graph, and they
follow the same cache rules. `MealEntry` deliberately duplicates nutrition that is *also* rolled up on
the snapshot — that is not redundancy to clean up: a daily macro total cannot say *when* the food was
eaten, and an absorption curve has to start somewhere. It dedupes on `externalId` like glucose.
`HeartRateBucket` averages raw samples into five-minute windows keyed on the bucket's start time,
which is what makes a re-sync idempotent without an external id; a watch writes a beat rate every few
seconds, which is more resolution than any chart here draws and enough rows to dominate the database.

Weight exists on both sides and is merged at read time, not at write time: `WeightEntry` is the
manual table, `HealthDaySnapshot.weightKg` is the synced value, and `TrendsUiState.weightByDay`
prefers the manual entry on any day that has both. A sync must never overwrite a hand-typed weight.

**Calories are two different numbers.** `dietaryCalories` is food eaten (`NutritionRecord`);
`totalCalories`/`activeCalories` are energy burned. They sit together on one row with a signed
`netCalories` (eaten − burned, green under, red over), which is null unless *both* halves are known —
substituting zero for a missing half would render a fake deficit the size of whichever figure synced.
Grouping burn figures next to protein/carbs/fat is what originally made the dashboard read as intake.

### Health Connect

Read-only. The manifest declares only `READ_*` permissions and no `WRITE_*`, and it must stay that
way unless explicitly asked. Every field on `HealthDay` is nullable and every metric is fetched
independently with failures swallowed to null — a user who grants steps but denies nutrition must
get a blank macro card, not an empty dashboard. `HealthPermissionState.GRANTED` means *at least one*
requested permission was granted, not all of them.

The manifest also needs the `<queries>` entry for `com.google.android.apps.healthdata` (package
visibility on Android 13 and below) and the exported `ViewPermissionUsageActivity` alias (the
Android 14+ rationale entry point the platform launches from the system permission screen).

**Steps are read from raw records, not `COUNT_TOTAL`.** Several apps commonly write steps at once (a
watch's companion app plus the phone's own health app), and the aggregate sums them all — counting
the same walk twice. `stepsByPackage` groups raw `StepsRecord`s by `metadata.dataOrigin.packageName`
so `UserSettings.preferredStepsPackage` can pin one source, with Settings showing the per-app
breakdown. Raw reads are paginated; `readAllRecords` loops `pageToken` because a single day from a
watch exceeds one page and taking only the first would silently undercount.

Health Connect has no mile-split concept. `bestMileSeconds` is elapsed time divided by distance,
normalised to a mile, over runs of at least a mile — so it is *average pace*, not a PR, and is
labelled as such in the UI.

### Fasting adherence

`domain/Interval.kt` is half-open `[start, end)` interval algebra — `normalized`, `intersectWith`,
`minusIntervals`. Half-open is load-bearing: a feeding window ending at 20:00 and a fast starting at
20:00 must not overlap by one instant.

`domain/FastingAdherence.kt` scores `overlap(planned fast, logged fast) / planned fast`, and only
over time up to now — future planned time is excluded, otherwise a perfect week reads near zero on
Monday morning. The rules that the tests pin down:

- Everything outside a day's feeding window is a planned fast.
- A feeding window whose end is at or before its start wraps past midnight.
- `hasFeedingWindow = false` means no eating at all: the full 24 hours are a planned fast. (The
  column is still named `enabled` in SQLite via `@ColumnInfo` so the rename needed no migration.)
- A day with *no plan row at all* is unscored in both directions — a defensive case, since the plan
  is seeded with all seven days on first run.
- A `PlannedExtendedFast` overrides the daily windows for the span it covers.
- A `score` of `null` means nothing was planned; `0` means planned and entirely missed.

### Room

Version 4, `exportSchema = false`. **Write a real `Migration` for any schema change** — there is
live data on the author's phone, so a version bump that falls through to the destructive path
destroys real fasting history and body measurements. `MIGRATION_2_3` is the worked example for adding
columns (three nullable `ALTER TABLE ADD COLUMN` statements); `MIGRATION_3_4` is the one for adding
tables, and is covered by `MigrationSchemaTest`. `fallbackToDestructiveMigration` is still registered,
but only covers the v1 schema, which kept steps, sleep, macros and rep counts on `DailyLog` and has
no sensible column-wise mapping to today's tables.

`Converters` stores `LocalDate` as epoch day, `Instant` as epoch millis, `LocalTime` as
second-of-day, and enums by `name`.

### Units and theme

Everything is **stored in metric** to match Health Connect and converted at the display boundary by
`domain/Units.kt` — converting once keeps rounding error out of stored data. Waist is stored in cm
but presented in exact quarter-inches (`roundToQuarter`, `formatInches` renders `42 1/4"`).

The theme is light-only and dynamic color is deliberately absent — leaving it on would let Android
12+ derive the palette from the user's wallpaper and discard the brand colors entirely. Palette:
Baltic Blue `#2F6690` (primary), Olive Bark `#625834` (secondary), Alabaster Grey `#D9DCD6`
(background), Yale Blue `#16425B`, Inferno `#A30000` (error).

`domain/FastingStats.kt` aggregates logged sessions for the Fasting screen — per-day segments for the
timeline, plus totals, longest, average and streaks. It works off `Interval` set algebra rather than
summing session durations, so overlapping or restarted sessions cannot double-count a minute.
Segments are emitted as `0f..1f` fractions of the day because the timeline draws proportions and
would otherwise redo time arithmetic every frame. Longest and average count only *finished* fasts —
a running one would report its length so far and beat itself an hour later. A streak tolerates an
empty today (checked before the day's fast is logged) but not an empty yesterday.

### Caffeine

`domain/Caffeine.kt` models caffeine remaining in the body with a 5-hour half-life. Elimination is
first-order, so doses decay independently and simply add — which is why an afternoon coffee on top
of a morning one reads much higher than either alone. `curve()` samples the level every 10 minutes
rather than plotting one point per dose: the decay between doses is exponential, and joining dose
points directly would draw it as a straight ramp. The dashboard loads doses from further back than
it plots (`RELEVANT_HISTORY_HOURS`), because a dose from before the window is still decaying inside
it.

### Macro absorption

`domain/MacroAbsorption.kt` spreads a meal over the hours it is actually reaching the blood, so food
can be plotted on the same axis as glucose, ketones and heart rate. Health Connect records a meal as
one lump of grams at one timestamp; drawn as that it is a vertical spike that says nothing about the
lag between eating and the body's response, which is the whole reason the master graph exists.

The curve is a chain of `n` serial first-order transfers sharing a rate constant — the same
compartmental family as the published meal-appearance models (Dalla Man, Hovorka) — evaluated in
closed form as `grams·kⁿ·t^(n−1)·e^(−k·t)/(n−1)!` with `k = (n−1)/timeToPeak`. It is normalised so
integrating over all time returns the grams eaten: **the area under a plotted curve is the meal, and
its height is grams per hour arriving.** Curves add, because each is an independent bolus.

Two parameters per macro come from the literature — time to peak, and compartment count — plus a
gastric lag. Carbs peak at 45 min, protein at 90, fat at 3.5 h. **The compartment count sets the tail
and is not a curve-fitting fudge:** fat uses four rather than two because dietary fat reaches plasma
through more sequential steps (lumen, enterocyte, chylomicron assembly, lymph, thoracic duct), and a
two-compartment fat curve is still running at a third of its peak ten hours out — nothing like the
measured return to baseline by 6-8 h. `MacroAbsorptionTest` pins that completion time, so changing
the count without changing the doc comment fails.

These are population averages for mixed meals and individual gastric emptying varies severalfold,
which is why the master graph draws all three dashed and says so on the screen. Like caffeine, meals
are loaded from further back than the window plots (`RELEVANT_HISTORY_HOURS`), because a meal eaten
before the left edge is still being absorbed inside it.

## Testing

`FastingAdherenceTest`, `FastingStatsTest`, `CaffeineTest` and `MacroAbsorptionTest` are the pure-JVM
suites. Adherence covers the midnight-wrapping window, extended fasts overriding the daily plan,
no-eating days, and the future-time exclusion. Stats covers overlap de-duplication, midnight splits,
streak rules and open sessions. Caffeine covers half-life decay, dose accumulation and curve shape.
Absorption covers the gastric lag, per-macro peak ordering, the normalisation that makes the area
under a curve equal the grams eaten, and the documented completion times. New adherence, interval,
stats, decay or absorption behaviour belongs there. `ExampleUnitTest` and `ExampleRobolectricTest`
are scaffolding.

`MigrationSchemaTest` diffs the hand-written migration SQL against the schema Room generates from the
entities. This matters more than it looks: `exportSchema = false` rules out Room's own
`MigrationTestHelper`, and a mismatched `CREATE TABLE` does not fail the build — it throws on the
next launch for anyone upgrading. Any new migration should be added to it, which means keeping its
statements in a `val` (see `AppDatabase.migration3To4Statements`) rather than inline in `migrate()`.

Unit tests run with `isIncludeAndroidResources = true`, so Robolectric tests can read resources.
`MasterGraphRenderTest` and `ScreenRenderTest` compose whole screens against an in-memory Room
database and `MockHealthDataSource`, asserting on the rendered tree and capturing images through
Roborazzi. The chart canvas does a lot of arithmetic that only runs under a real layout pass, so
these catch empty-list and divide-by-zero crashes no pure-JVM test reaches. Record images with:

```
./gradlew :app:recordRoborazziDebug
```

They land in `app/build/screenshots/`. Nothing is compared against a golden — they exist to be looked
at. Seeding matters: the subjective 1-10 scores are hand-logged, so `syncHealthData` alone leaves the
combined mood chart empty and its three line styles unexercised.

## Conventions

`.gitattributes` pins `gradlew` to LF and `gradlew.bat` to CRLF — a CRLF `gradlew` fails with a bad
interpreter error on macOS and Linux. Don't let an editor normalise those.

Comments in this codebase explain *why* a non-obvious choice was made, not what the code does. Match
that when adding code — a comment restating the line above it is out of place here.
