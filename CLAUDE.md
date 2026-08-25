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

**Window and range enums are the single source of a screen's query span.** `MasterRange`
(3h/6h/12h/24h/48h/7d), `GlucoseWindow` (3h/6h/12h/24h/48h/72h) and `TrendsRange`
(7/14/30/90 days) each drive both the chips and the query, so adding an entry widens the fetch with
no second edit — `GLUCOSE_WINDOW_HOURS` is derived as the maximum, and the dashboard queries once at
that width so switching windows is a redraw rather than a round trip. Six chips no longer fit one row
of a phone, so all three chip rows are `FlowRow`; a sideways-scrolling row would hide the widest
options behind a gesture nobody knows is there.

**The master graph's window is no longer anchored to now.** `MasterGraphUiState.panOffset` is how
far back a horizontal drag has pulled the right edge, and `windowEnd` is `now - panOffset`.
Everything that draws must measure from `windowEnd`, never from `now`: the absorption and caffeine
curves are *sampled between two bounds* rather than clipped afterwards, so one still ending at `now`
does not stop short on a panned window -- it runs on past the right-hand edge, in the same ink as the
part of it that belongs there. `mealsInWindow` needs the same upper bound, since it is also what the
marker rules are built from, and a meal listed with no rule to find is worse than one left out.
`now` itself stays `now`: the "Right now" card, `rateNow`, `stepsLastHour` and every `asAgo` are
about this moment whatever the plot is showing.

Two consequences worth knowing. `advanceNow()` grows `panOffset` by exactly what the clock gained
whenever it is panned, so the ticker cannot drag a parked window along behind it -- the reader went
back to a particular evening and that evening does not move. And the series queries are keyed on an
anchor **snapped down to the hour** rather than on the raw offset: a drag emits once a frame, every
emission of a raw key would tear down six Room subscriptions and open six more, and every one of
those queries is open-ended forward, so an anchor an hour early is a superset and never a subset.

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

**A nutrition source may record the date and nothing finer.** Real data from the author's phone had
every `NutritionRecord.startTime` on one fixed time of day — 10:00:00 local, to the second, including
three separate meals on one Tuesday — so every absorption curve was anchored to an hour nobody ate
in. This is not something the app can compute its way out of; the clock time was never written. Two
things follow from it:

- `MasterGraphUiState.hasClockTime` calls a time of day **shared to the second by two different
  meals** a stamp rather than a measurement. Genuine timestamps land on a different second every
  time; a source that knows only the date lands on the same one for ever. Midnight counts
  unconditionally, since a lone meal at exactly `00:00:00` is a date too. The list says "set time"
  instead of printing a plausible-looking clock time. **Do not narrow this back to a midnight
  check** — that was the first attempt, and the phone's 10:00 stamp sails straight past it. The
  repeat is the signal; the particular hour is not. It is judged over the meals loaded, so it is
  quieter on a 3h window than a 7d one, which is the right way round.
- Meals are **editable and deletable**, uniquely among the Health Connect caches — and a meal can be
  logged by hand outright. A source that stamps the wrong time, writes a meal twice, or records one
  that was never eaten cannot be argued with, so the curves are only worth reading if the meals
  under them can be corrected. Edits survive re-syncing for free: meals are only ever inserted,
  never updated, so the unique `externalId` index makes the original a no-op the next time round.

**Deleting a synced meal hides the row rather than removing it** (`MealEntry.hidden`). Removing it
outright does not work: the next sync reads the same record from Health Connect and puts it back,
because both the `externalId` lookup and the content check search for rows that would no longer be
there. The kept row *is* the evidence that this record has already been dealt with. A hand-entered
meal has no upstream record to return from, so that one is deleted for real. `MealDeletionTest` pins
both, including the re-sync — nothing in the type system stops a later change from "tidying up" the
flag into a real delete, which would pass every other test and quietly resurrect the meal.

Reads split accordingly: `getMealsBetween` (screens and curves) excludes hidden rows;
`getMealsInRange` (the sync's content check) includes them, so a deleted meal also keeps out the
upstream duplicate of itself arriving later under a different record id.

**The same source may also write one meal as several records.** One day carried six records that were
two meals repeated three times, each with a Health Connect id of its own — so the unique index saw
six legitimately distinct rows and trebled that day's carbohydrate. `domain/MealDuplicates.kt`
collapses records agreeing on timestamp, energy, all three macros *and* name; anything differing at
all is kept, so a genuine second helping survives. Nulls take part in the comparison, because "no
protein recorded" and "zero protein" are different statements. It runs on both sides: on insert to
stop the table accumulating, and on read to fix days already stored. **Nothing deletes a row** — the
duplicates stay on disk and are simply not counted twice, which leaves the decision reversible. The
screen says how many it merged, since a total that moved without explanation is worse than the
duplicate it corrected.

`MealEntry`, `HeartRateBucket` and `StepBucket` are the other three time series, added for the master
graph, and they follow the same cache rules. `MealEntry` deliberately duplicates nutrition that is
*also* rolled up on the snapshot — that is not redundancy to clean up: a daily macro total cannot say
*when* the food was eaten, and an absorption curve has to start somewhere. It dedupes on `externalId`
like glucose. `HeartRateBucket` averages raw samples into five-minute windows keyed on the bucket's
start time, which is what makes a re-sync idempotent without an external id; a watch writes a beat
rate every few seconds, which is more resolution than any chart here draws and enough rows to
dominate the database. `StepBucket` is the same idea at hourly resolution, and the same argument
against the snapshot's daily total: it cannot say *when* the walking happened.

`StepBucket` is the one cache that is **deleted before it is rewritten**. An hour's step count can
legitimately fall to zero between syncs — the pinned source changes in Settings, or a duplicate walk
is removed upstream — and an upsert has no way to express "this hour no longer holds what it did".
The delete is bounded by what the read actually returned, so a failed read leaves the cache alone
rather than emptying it.

Raw heart rate is re-read over at most `HEART_RATE_SYNC_HORIZON` (48 h) however wide the window being
drawn is, because a week of raw samples is hundreds of thousands of records fetched only to be
averaged into five-minute buckets. That is a cap on the *sync*, not on the chart: wider windows draw
from buckets already cached by earlier syncs. Meals and steps have no such cap — both are cheap.

`GripStrengthEntry` is manual, one row per day, with a nullable column per hand so one hand can be
logged without blanking the other — which is why the repository read-modify-writes it instead of
upserting a whole row. Stored in kilograms and shown in pounds: Health Connect has no grip strength
record, so nothing outside forces the unit, but a second storage unit is how rounding error gets in.

Weight exists on both sides and is merged at read time, not at write time: `WeightEntry` is the
manual table, `HealthDaySnapshot.weightKg` is the synced value, and `TrendsUiState.weightByDay`
prefers the manual entry on any day that has both. A sync must never overwrite a hand-typed weight.

**Calories are two different numbers.** `dietaryCalories` is food eaten (`NutritionRecord`);
`totalCalories`/`activeCalories` are energy burned. They sit together on one row with a signed
`netCalories` (eaten − burned, green under, red over), which is null unless *both* halves are known —
substituting zero for a missing half would render a fake deficit the size of whichever figure synced.
Grouping burn figures next to protein/carbs/fat is what originally made the dashboard read as intake.

### Notifications and the widget

`work/CaffeineLastCall.kt` warns when *one more* ordinary cup would leave the reader over their
bedtime limit at 9 PM. **The question is about the next dose, not the current level**, and that is
the whole feature: told after the fact that bedtime caffeine is too high there is nothing left to do
about it. `Caffeine.lastCallReached` answers it with the existing decay model and one hypothetical
dose added at the front — no new maths, and testable without a worker. It returns false once the
projection is *already* over, because at that point every remaining choice is equally too late and a
warning is only scolding.

The check is **periodic rather than fired on a log**, because the interesting moment usually arrives
with nothing being logged at all: caffeine already drunk keeps decaying and the afternoon crosses the
threshold on its own. Hourly, idempotent, and uniquely named, so a deferred or coalesced run costs
nothing.

`POST_NOTIFICATIONS` is the app's **first runtime permission**. It is asked for once at launch and
never insisted on: the worker checks before posting, so a refusal means the warning never appears and
nothing else changes. The channel and the work are registered in `MainActivity`, *not*
`Application.onCreate` — WorkManager initialises through an `androidx.startup` provider that does not
run under Robolectric, and scheduling from the Application made every test that constructs it throw.

`widget/TrackerWidget.kt` is a Glance widget for water, caffeine and the fast: the three entries made
while doing something else, each of which otherwise costs unlocking the phone and finding a card. The
fast button reads the goal from the plan exactly as the Today card does — a widget that started every
fast at a fixed length would quietly score the week's adherence against the wrong target. Each action
calls `update` explicitly rather than trusting a flow, because a widget is not composed while the
home screen is idle and nothing is collecting.

### Backup

`data/CsvBackup.kt` writes every table to a zip of CSVs, handed to the share sheet through a
`FileProvider`. Everything this app knows lives in one SQLite file in one app's private storage, and
none of it -- fasting history, hand-typed weights, blood sugar, the stack -- exists anywhere else.

**The table list comes from `sqlite_master`, not from a list in the source.** A hand-maintained list
is correct on the day it is written and quietly incomplete from the next migration onward, which is
the one thing a backup cannot be: nobody looks at one until they need it. That is also why the DAO
has a single `@RawQuery` returning a `Cursor` rather than fifteen `SELECT *` methods -- and why that
one method is deliberately *not* `suspend`, since Room will not build a suspending raw query
returning a cursor it cannot know when to close. The caller closes it and moves the work to IO.

`domain/Csv.kt` holds the escaping, separately and tested, because it is the part that fails
silently: a supplement called `Vitamin D3, 5000 IU` written unquoted becomes two columns and shifts
every later column on that row. A backup that loads without complaint and is wrong is worse than one
that fails outright. Values are written in their stored form -- epoch millis stay epoch millis --
rather than formatted, which would be a second date format to keep in step with `Converters`.

The provider is scoped to `cache/exports/` rather than the whole cache, so nothing else the app
writes is reachable through a `content://` URI, and the share carries
`FLAG_GRANT_READ_URI_PERMISSION` so the receiving app gets that one file for that one send.

### Supplements

Two tables, and the split is the same one the weight waypoints made: `Supplement` is a **standing
list** -- name, dose, and which of morning/midday/evening -- and `SupplementDose` is one row per
supplement per day it was actually taken. Folding the second into the first would mean a `takenToday`
column that something has to clear at midnight, and nothing in this app runs at midnight.

**`SupplementDose` has no `taken` column. The row's existence is the fact.** "Not taken" and "not
answered yet" are the same state for something that resets daily, and a boolean would force a
distinction the data cannot support -- leaving every past day looking actively missed rather than
simply over. Its primary key is the pair, so ticking twice is absorbed rather than counted, and it
carries its own index on `date` because the primary key indexes the *pair* and cannot answer "what
was taken today".

**The dose is free text**, and deliberately: IU, mcg, mg, grams, capsules, softgels, drops and
millilitres all appear on one shelf, half of them printed per serving rather than per pill. Nothing
does arithmetic on it, so parsing could only ever reject something somebody actually takes. This also
makes the add dialog the **only free-text entry in the app** -- everything else is a quantity with a
known unit and gets a stepper.

`Supplement` is unique on **name and slot together**. The same thing morning and evening is two rows,
which is what makes it tickable twice a day; the same thing twice in one morning is one row, because
that is one dose split across two capsules. Inserts use `IGNORE` rather than `REPLACE`: replacing
would hand the row a new id and silently orphan every tick already logged against the old one.

**Deleting a supplement clears its doses in the repository**, in that order. There are no foreign
keys anywhere in this schema, so nothing cascades on the app's behalf, and ticks left behind would be
keyed on an id nothing can resolve. `DashboardUiState.supplementsTakenCount` intersects rather than
counting tick rows for the same reason -- a stray dose must not read as "3 of 2 taken today".

### Health Connect

Read-only. The manifest declares only `READ_*` permissions and no `WRITE_*`, and it must stay that
way unless explicitly asked. Every field on `HealthDay` is nullable and every metric is fetched
independently with failures swallowed to null — a user who grants steps but denies nutrition must
get a blank macro card, not an empty dashboard. `HealthPermissionState.GRANTED` means *at least one*
requested permission was granted, not all of them.

The manifest also needs the `<queries>` entry for `com.google.android.apps.healthdata` (package
visibility on Android 13 and below) and the exported `ViewPermissionUsageActivity` alias (the
Android 14+ rationale entry point the platform launches from the system permission screen).

**Steps are attributed per writing app, not summed blindly.** Several apps commonly write steps at
once (a watch's companion app plus the phone's own health app), and an unfiltered aggregate sums them
all — counting the same walk twice. `stepsByPackage` derives the contributing packages from the
combined aggregate's own `dataOrigins` and re-aggregates per source, so
`UserSettings.preferredStepsPackage` can pin one, with Settings showing the per-app breakdown. Doing
this by reading raw `StepsRecord`s is not survivable: the client validates every record as it
converts it, a zero-count step record is rejected outright, and one such record from any installed
app throws away the entire page. Aggregates never construct records, so they are immune. Raw reads
elsewhere are paginated; `readAllRecords` loops `pageToken` because a single day from a watch exceeds
one page and taking only the first would silently undercount.

`readStepsByHour` slices the same aggregate with `AggregateGroupByDurationRequest` and honours the
same pinned source — hourly bars that summed every app while the daily total trusted one would be two
different step counts on two screens. **The window's start is snapped down to the hour in the local
zone before slicing**, because the slicer counts forward from whatever instant it is handed: a sync
begun at 14:37 would otherwise produce buckets running :37 to :37, which no later sync lines up with
and `StepBucket`'s primary key could never overwrite. Health Connect omits a slice entirely when it
has no records in it, so an hour with no walking arrives as a *hole*, not a zero — which is why a bar
series has to declare its own `barWidth` rather than infer one from the spacing.

Health Connect has no mile-split concept. `bestMileSeconds` is elapsed time divided by distance,
normalised to a mile, over runs of at least a mile — so it is *average pace*, not a PR, and is
labelled as such in the UI.

**Glucose is cached a calendar day at a time and only *today* is ever re-read**, which is right for a
finished day and wrong for one that was never finished properly: a monitor out of Bluetooth range
writes its readings to Health Connect hours late, by which time nothing asks about the day they
belong to and the hole is permanent. `domain/GlucoseGaps.kt` turns the holes themselves into the
query — `TrackerRepository.backfillGlucoseGaps` finds the stretches of the last 72 hours with nothing
in them and re-reads only those, with the `externalId` index throwing away what came back already
known. **The window end counts as an edge**: a monitor that stopped an hour ago leaves its gap where
no later reading bounds it, and that is the freshest and most fillable gap there is.

Its threshold is **fixed at 45 minutes**, deliberately unlike `SeriesGaps`, which judges a break
against the series' own cadence. The two are answering different questions: `SeriesGaps` decides
whether to *draw* a line, where a fingerstick user's five-hour spacing is not a dropout; this decides
whether to spend a query, where judging by that same cadence would leave a continuous monitor's
four-hour outage looking unremarkable. More holes than `MAX_SPANS` collapse into one sweep — a trace
that broken is not worth six round trips to discover. It does re-ask about a genuinely empty span on
every refresh forever, which is the price of keeping no record of what has been given up on; the
count of what was recovered is reported on the Today card rather than absorbed silently.

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

Version 12, `exportSchema = false`. **Write a real `Migration` for any schema change** — there is
live data on the author's phone, so a version bump that falls through to the destructive path
destroys real fasting history and body measurements. `MIGRATION_2_3` is the worked example for adding
columns (three nullable `ALTER TABLE ADD COLUMN` statements); `MIGRATION_3_4` is the one for adding
tables; `MIGRATION_5_6` does both at once; `MIGRATION_6_7` adds a non-null one. All are covered by
`MigrationSchemaTest` — note that a table built by one migration and altered by a later one has to be
checked across **all** of them, which is why the MealEntry test replays 3-to-4 and then 6-to-7, and
the UserGoals one replays 5-to-6, 7-to-8 and 8-to-9.
`fallbackToDestructiveMigration` is still registered, but only covers the v1 schema, which kept
steps, sleep, macros and rep counts on `DailyLog` and has no sensible column-wise mapping to today's
tables.

An added column may carry a SQLite `DEFAULT` even where the entity declares none — `MIGRATION_5_6`
seeds the glucose target that way, so an upgrading user does not find blank a setting that ships
pre-filled. `MIGRATION_8_9` is the case where this matters most: the glucose plot bounds and the
blood pressure rules were **hard-coded before they were settings**, so a column arriving NULL would
read as "no line" and visibly change an existing user's charts — which is the one thing turning a
constant into a setting must not do. Its defaults are exactly the figures those charts were fixed at.

`MIGRATION_11_12` adds `UserGoals.caffeineBedtimeLimitMg` and is the one added column here that
**deliberately carries no SQLite default**, the opposite of `MIGRATION_5_6` and `MIGRATION_8_9`.
Those seeded values because the column drove something already on screen and a NULL would visibly
change an existing user's charts. This one drives a *notification*: a default would mean upgrading
and then being interrupted by something never asked for. NULL means "say nothing", and the reader
turns it on. It joins the UserGoals replay in `MigrationSchemaTest` rather than taking a test of its
own, since that table is now altered four separate times and only the full replay catches a gap.

`MIGRATION_10_11` adds the two supplement tables. Both are new, so their DDL is diffed directly --
there is no `ALTER TABLE`-added column carrying a SQLite default that Room's `CREATE TABLE` omits.
The parts worth pinning are the unique index on name-and-slot and `SupplementDose`'s **composite**
primary key: get the latter wrong and every stray tap on a checkbox is another row saying the same
thing.

`MIGRATION_9_10` adds `WeightSubGoal`, a **table rather than more `UserGoals` columns**, because
there is no right number of staged weights: thirty pounds to lose may want one every five or a
single halfway mark, and a fixed set of columns has to guess. Unique on `kg`, so staging the same
mark twice is absorbed rather than drawn as two rules at one height. Room only compares a column's default when the entity spells one out with
`@ColumnInfo(defaultValue = …)`, so this is invisible to schema validation. It does mean an
`ALTER TABLE`-added column **cannot** be checked by diffing DDL text the way a new table is: the
migration's text says `DEFAULT 70` and Room's `CREATE TABLE` does not. `MigrationSchemaTest` therefore
compares `PRAGMA table_info` — name, type, nullability, primary-key position — for those, which is
exactly the set Room validates.

`Converters` stores `LocalDate` as epoch day, `Instant` as epoch millis, `LocalTime` as
second-of-day, and enums by `name`.

### Units and theme

Everything is **stored in metric** to match Health Connect and converted at the display boundary by
`domain/Units.kt` — converting once keeps rounding error out of stored data. Waist is stored in cm
but presented in exact quarter-inches (`roundToQuarter`, `formatInches` renders `42 1/4"`). Grip
strength follows the same rule: kilograms in the database, pounds on every screen.

`domain/Glucose.kt` and `domain/Ketones.kt` own their axes for the same reason: the entry stepper,
the dashboard chart, the master graph and the settings target all have to agree on what the scale
means, and four copies of the numbers drift. The glucose plot is **60–180 mg/dL**, not 60–200 —
the top fifth of a 200 ceiling is never reached and spending it flattens the 30 mg/dL swing around a
meal into a wiggle. Both charts still widen an axis to fit an outlier, so a 210 reading plots; it is
simply not budgeted for.

The theme follows the system light/dark setting, with **no in-app override** — a per-app switch is a
setting to maintain and a state to get out of step with the phone. Dynamic color is deliberately
absent in both: leaving it on would let Android 12+ derive the palette from the user's wallpaper and
discard the brand colors entirely. Palette: Baltic Blue `#2F6690` (primary), Olive Bark `#625834`
(secondary), Alabaster Grey `#D9DCD6` (background), Yale Blue `#16425B`, Inferno `#A30000` (error).

**The dark scheme is not the light one inverted.** The brand's own tones *are* the dark ones — Yale
Blue and Olive Bark exist to be read on alabaster — so reusing them as foreground colours puts
near-black on near-black. The primary lifts to a tint bright enough to carry dark text, and cards
stay *above* the background, which on a dark ground means lighter rather than darker.

**Chart series colours had to stop being top-level constants**, which is the part of this that was
not a drive-by. A series colour depends on what it is drawn *on*, so `ui/theme/ChartColors.kt` holds
the whole set twice and the theme provides one through `LocalChartColors`. Each dark value keeps its
hue and gains lightness, so a line is recognisably itself between themes and every separation the
light set was chosen for survives — caffeine still occupies the stretch of the wheel nothing else
does, the macro stack's two blues are still kept apart by the olive between them.

The set is **passed explicitly** rather than read at the point of use, because what needs a colour is
often not a composable: `MasterSeries.colorIn(colors)` is an extension on an enum and the plot's
drawing happens in a `DrawScope`. That is also why the reference-rule colour is now a parameter of
`drawChart` instead of the hardcoded `#A30000` it was — that value on a near-black surface is a rule
nobody can see.

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

The sampling step is derived from the window rather than fixed (`CURVE_SAMPLES`, clamped to 1–10
minutes). A fixed ten minutes drew a 45-minute carbohydrate peak from four samples on the 3 h window,
and four times more points than the plot can render on the 7 d one.

### Drawing weight, and what gets read as data

Meal markers on the master graph are `subdued`, which draws them hairline in the gridline grey
instead of full-weight in the axis colour. That is not decoration. At full weight they were
near-black full-height rules, one per meal, each captioned with that meal's carbohydrate grams — and
they were read as a carbohydrate spike. Worse, they went on being read as one after the carbohydrate
curve was switched off, because a marker never belonged to a series at all: they vanish only when
*every* series is off and the plot stops drawing entirely. **Anything that marks context rather than
reporting a measurement has to be lighter than the data**, or it becomes data.

The same rule sorts out the other three chart primitives:

- **Bars** (`SeriesKind.BAR`) are for a quantity accumulated *over* an interval, not measured *at* an
  instant. Steps per hour joined into a line would claim a walking rate at moments when nothing was
  counted. They declare their own `barWidth`, because the gaps between points cannot be trusted to
  reveal it: an aggregator reports nothing for an interval it has no records in, so a night of no
  walking arrives as a hole rather than as zeroes. Downsampling sums them; averaging would halve a
  fortnight of steps.
- **Gaps** (`ChartSeries.breakOnGaps`, `SeriesGaps`) break a *measured* line where it stopped being
  measured. Joining across a hole draws a straight run through hours that were never recorded, in the
  same ink as the readings either side — a watch taken off overnight produced an eight-hour diagonal
  that looked exactly like data. **The threshold is derived from each series' own cadence** (four
  times its median spacing), because a fixed one is wrong for somebody: twenty minutes of silence is
  a dropout for a monitor writing every five minutes and completely normal for three fingersticks a
  day, and both arrive as "blood sugar". Splitting happens *before* downsampling, or thinning would
  widen every spacing equally and leave a real dropout looking ordinary. Modelled curves must leave
  this off: they are continuous functions sampled evenly, so there is nothing to find.
- **Target bands** (`AxisSpec.band`) shade a range behind the data. A filled area answers "was it in
  range" at a glance where two threshold rules leave the reader working out which side of each the
  trace is on.
  Single values get `AxisSpec.rules` instead, which answer "above or below" rather than "in
  range". `AxisRule.dashed` keeps the two kinds apart: dashed for a published clinical figure
  (the blood pressure chart), solid for one the reader chose in Settings (the glucose reference).
  Drawing both the same way quietly lends one the authority of the other. It is a *list* because
  blood pressure is two numbers — drawn with a systolic rule alone, the diastolic line had nothing
  to be read against at all.
- **Time gridlines** (`DualAxisTimeChart(verticalGridlines = true)`, `TimeGridlines`) are hourly up
  to a 12-hour window and four-hourly beyond it, then widened through `1, 2, 3, 4, 6, 12, 24` until
  the rules are at least 14dp apart — a week at four hours is 42 lines a finger-width apart. Every
  interval divides a day evenly, so the rules sit on the same clock times each day instead of
  drifting through it. They are aligned to the **local hour**, deliberately *not* to the tick labels
  below, which step back from *now* and land wherever the window happens to end: a rule at 2:47
  cannot answer "how much of that rise was in the hour after eating". The hours are walked one at a
  time rather than added to, so a daylight-saving change does not put every rule after it an hour
  off the clock. On the master graph only — the tick labels say enough on a chart with one line.
- **Goal lines are part of the scale, not an annotation on it.** `chartBounds` folds `goalLine` and
  `subGoalLines` into the range a day-indexed chart covers, because a rule outside the plot is not
  drawn at the edge — it is *clipped*, and nothing appears. A weight chart scaled to a fortnight of
  readings around 198 lb simply did not show a 180 lb goal, and looked identical to one with no goal
  set. An explicit `minY`/`maxY` still wins: that is a caller stating the scale deliberately, as the
  mood chart does with 1 to 10, and a goal must not be able to stretch an axis whose bounds are the
  point. `niceTicks` only ever floors and ceils, so a mark inside the input bounds stays inside the
  snapped axis. **Waypoints** (`subGoalLines`) are drawn first, hairline, finer-dashed and at
  `SUB_GOAL_ALPHA` — same colour family as the goal because they are the same kind of thing, never
  the same weight, because one of those lines is where you are going and the rest are on the way.
- **Smoothing** (`domain/GlucoseSmoothing.kt`) is a Gaussian-weighted moving average in *time*, not
  in sample index — index weighting would treat two fingersticks a week apart as neighbours and
  average them together. It never resamples or interpolates: one output per input reading at that
  reading's own timestamp, so the line still ends at *now*. Because it is a weighted mean of real
  readings, it cannot overshoot their range. It defaults to **off**, is stored in `UserSettings` so
  both charts agree, and relabels the series "Glucose (smoothed)" while on — every other line here is
  either a measurement or dashed to say it is a model, and a solid line quietly differing from the
  readings under it would break that rule without saying so.

**`SeriesToggles` is the control; the legend is a shortcut.** This was briefly the other way round --
the switch row deleted, names in the legend made the only way to choose what was drawn -- and it
failed for a reason worth keeping written down. The legend sits at the foot of a 300dp card, so on a
phone the line explaining that it had become the switch fell below the fold, and the feature read as
*removed*. **A control has to be visible from where the reader is standing**, and a caption that
becomes a control without looking like one is not visible in the sense that matters.

So the legend tap only ever *hides* a line. That is not a half-measure, it is what a legend can
honestly support: a legend lists what is drawn, so there is no row left to tap once a line is off,
and the way back has to come from a control that shows every line whether or not it is on the plot.
Legend rows are therefore `Modifier.clickable` with an `onClickLabel`, not `toggleable` -- announcing
a switch would have a screen reader offer to turn back on a row that disappears the moment it is off.
The switches keep `toggleable`, which also makes them the only toggleable nodes on the screen and so
the thing a test can count. The tap is keyed on `ChartSeries.label` rather than on the caption: the
caption carries the unit, which moves as the axis selection does, and a control keyed on something
that moves is a control that stops working. `MasterSeries.color` remains the single source for the
plot, the key swatch, the switch and the axis tint.

One trap this left in the tests. Eight switches wrap onto three rows and the chart card no longer
fits the screen, so the last row is below the fold -- and a click on an off-screen node is clamped
into view and lands on nothing, silently. `performScrollTo()` before each `performClick()` is what
makes that deterministic; without it a test that switches everything off leaves two series drawn and
passes anyway.

**Tapping the plot drops a crosshair**, with every visible line's value at that moment listed under
it. `selectedTime` lives in `DualAxisTimeChart` via `remember` -- no ViewModel is involved, so every
chart in the app has it. Three things about it are load-bearing:

- The readout is a compose `FlowRow` under the plot, **never text painted on the canvas**. Anywhere
  a bubble could go on a plot carrying eight series is on top of one of them.
- The hairline is the only new ink on the plot: solid, 1dp, in the label grey -- heavier than the
  meal rules, which are dashed gridline grey and mark context, and lighter than any series. Nothing
  is drawn *on* the lines being read. A ring at each matched point would be fresh ink in the data's
  own colours, which is exactly how the meal markers came to be read as a carbohydrate spike.
- A **bar** answers for the column that *contains* the moment, not for its nearest point. A bar's
  timestamp is the start of an interval, so on hourly step buckets the nearest start to a moment
  halfway through the hour is half an hour away -- an em dash printed under a column plainly visible
  beneath the crosshair. Lines keep the nearest-within-tolerance rule, and a line with nothing near
  enough genuinely prints the dash: quoting a heart rate from the far side of an eight-hour hole
  invents a measurement.

Tap and drag are read by **two separate detectors** on the same Box. `detectTapGestures` gives up as
soon as the finger travels and `detectHorizontalDragGestures` waits for *horizontal* slop, so a
vertical swipe reaches neither and the LazyColumn underneath goes on scrolling. Both read the window
and the points through `rememberUpdatedState`: the handlers are launched once and never restarted, so
a plain local read inside one is the value the chart had when it first composed, for ever. That is a
real bug that was written and caught here -- the crosshair would not dismiss, because the "is there
one standing?" check was reading `null` from the first composition.

The plot takes an optional `contentDescription` naming its *purpose*, and always appends a
`spokenSummary` describing what is on it -- the window, then each drawn series with its range and its
latest value. A Canvas is a blank to TalkBack, so without this a chart is an empty rectangle and
every number on it is unreachable. The summary is **derived from the series rather than written by
the caller**, because a hand-written description is right on the day it is typed and wrong every day
after. It is a summary and not a reading-out: eight series at CGM resolution is thousands of points,
and a screen reader reciting them is worse than one saying nothing -- "what was it at 4 PM" is the
crosshair's question, and the crosshair is reached by tapping the very element this describes. The
description is also the only handle the render tests have on the plot, since nothing inside it
carries text.

`DualAxisTimeChart` clips every series to the window **once**, up front, and every axis, legend
caption and empty check is computed from the clipped points. This matters because series here are
routinely queried wider than they are drawn — meals reach back an absorption window, a heart rate or
step bucket survives from a previous sync at a wider setting — and a point that is not on the chart
setting the chart's ceiling flattens every point that is. The legend runs the same expansion, since
for a series carrying its own `AxisSpec.scale` the caption *is* its axis: quoting the configured
range there would print a ceiling the plot stopped using the moment anything exceeded it.

**Which two units get printed down the sides is a reading decision.** `AxisMetric` groups the master
graph series by unit -- glucose, macros, heart rate, ketones, steps, caffeine -- and `labelledAxes`
holds the chosen pair in order, first left then right. Everything unchosen still plots, against its
own `ChartSeries.scale`, with its range quoted in the legend. **A series takes a labelled axis or a
scale of its own, never both**: `scale` overrides `axis`, so a unit that has been given a gutter must
pass `scale = null`, or it goes on being drawn to its private range while the numbers printed beside
it describe something else. Picking a third drops the oldest rather than refusing the tap; the last
one cannot be removed, because the plot has to be drawn against something. One consequence worth
knowing: the glucose target band and reference rule ride on the glucose `AxisSpec`, so they are only
drawn while glucose is one of the labelled pair.

**An axis takes its line's colour only when it is serving exactly one.** `AxisSpec.color` tints the
numbers in the gutter, and `axisColorFor` sets it from the single *visible* series carrying that
unit. Where several share it there is no honest answer — tinting g/h in the carbohydrate colour
claims the protein and fat curves are read against some other axis — so it stays the ordinary label
grey. Switching two of the three macros off hands the axis to the survivor, which is emergent rather
than special-cased. `MasterSeries.color` is the single source for all three uses: plot, key, axis.

## Testing

`FastingAdherenceTest`, `FastingStatsTest`, `CaffeineTest`, `MacroAbsorptionTest`,
`GlucoseSmoothingTest`, `MealDuplicatesTest`, `SeriesGapsTest`, `AxisSelectionTest`,
`GlucoseGapsTest`, `TimeGridlinesTest`, `ChartBoundsTest`, `WaypointSeedTest` and `PanWindowTest`
`CsvTest` and `CaffeineLastCallTest` are the pure-JVM suites. `CsvBackupTest` and `SupplementsTest` are Robolectric
repository suites alongside
`MealDeletionTest`, pinning the behaviour that lives between two tables with no foreign key: the same
thing added twice is one entry, the same thing in two slots is two, a tick belongs to one day only,
and removing a supplement takes its ticks with it. Adherence
covers the midnight-wrapping window,
extended fasts overriding the daily plan, no-eating days, and the future-time exclusion. Stats covers
overlap de-duplication, midnight splits, streak rules and open sessions. Caffeine covers half-life
decay, dose accumulation and curve shape. Absorption covers the gastric lag, per-macro peak ordering,
the normalisation that makes the area under a curve equal the grams eaten, and the documented
completion times. Smoothing pins what the filter may do to a reading: timestamps preserved, no
overshoot beyond the readings' own range, no lag on a rise, and isolated readings returned untouched.
Duplicates pins the line between one meal written twice and two similar meals, in both directions,
and `MealTimeStampTest` pins the one between a measured meal time and a stamped one — including that
three copies of a single meal must *not* make its own timestamp look invented, which only holds
because the collapse runs first.
`GlucoseGapsTest` pins both failure modes of the backfill at once — missing a real hole leaves the
chart permanently wrong about hours that *were* recorded, and finding one in every sensor stutter
spends a query on every refresh forever — which is why it carries a fixture for an ordinary
fifteen-minute stutter alongside the four-hour outage. `TimeGridlinesTest` pins that every interval
divides a day evenly (otherwise the rules drift through the day), that the density guard is about the
screen and not the clock, and that a spring-forward day keeps every rule on the hour.
`ChartBoundsTest` pins the silent failure: a goal outside the readings is clipped rather than drawn
small, so a chart missing its goal looks exactly like a chart that has none.
`WaypointSeedTest` pins where a control *opens*, which is not behaviour any other test would notice
and is the difference between one tap and a hundred. `PanWindowTest` pins the quiet half of panning:
that the curves stop at `windowEnd` rather than running past it, that a meal beyond the right edge is
neither listed nor marked, and that a drag cannot put the window in the future or leave it three
minutes short of live -- a window three minutes short of now looks exactly like a live one and is not.
New adherence, interval, stats, decay, absorption, smoothing, duplicate, gap, gridline or axis-range
behaviour belongs there. `ExampleUnitTest` and `ExampleRobolectricTest` are scaffolding.

Awkwardly, the duplicate-collapse cannot be reached through the repository any more: the sync rejects
duplicates on the way in, so a render test that needs rows in the state a *previous* version left
them has to plant them through the DAO. `MasterGraphRenderTest` keeps a `dao` field for that, and its
`DateOnlyDuplicatedMeals` reproduces the source behaviour by delegating `HealthDataSource` and
overriding only `readMeals` — which keeps the oddity in the test rather than in the shared mock.

A note on writing smoothing tests: assert peak *timing* against a trace that is symmetric about its
peak. On an asymmetric one the two samples either side of a near-plateau come out within a tenth of
each other, and which of them wins is the input's shape rather than the filter's.

`MealDeletionTest` is a Robolectric test with no UI in it, exercising the repository against an
in-memory database and a stubborn data source that keeps re-offering the meal that was deleted. Any
behaviour that only shows up *across* a sync belongs there rather than in a render test.

`MigrationSchemaTest` diffs the hand-written migration SQL against the schema Room generates from the
entities. This matters more than it looks: `exportSchema = false` rules out Room's own
`MigrationTestHelper`, and a mismatched `CREATE TABLE` does not fail the build — it throws on the
next launch for anyone upgrading. Any new migration should be added to it, which means keeping its
statements in a `val` (see `AppDatabase.migration3To4Statements`) rather than inline in `migrate()`.

Unit tests run with `isIncludeAndroidResources = true`, so Robolectric tests can read resources.
`MasterGraphRenderTest` and `ScreenRenderTest` compose whole screens against an in-memory Room
database and `MockHealthDataSource`, asserting on the rendered tree and capturing images through
Roborazzi. The chart canvas does a lot of arithmetic that only runs under a real layout pass, so
these catch empty-list and divide-by-zero crashes no pure-JVM test reaches.

**The dashboard cannot be scrolled in a test.** `DashboardViewModel` runs a one-second ticker for the
live fast timer, so the screen never reaches the idle state `performScrollToNode` waits on — it
retries, times out after a minute, and throws `AppNotIdleException`. Anything below the fold there
has to be asserted on a screen that does not tick, which is why the glucose smoothing and target band
are covered in `MasterGraphRenderTest` even though they also appear on the dashboard. The drawing
code is shared, so covering it once covers both.

**Waiting on `waitForIdle` alone is not enough for anything a query drives.** Both render suites
select a window by clicking its chip and then `waitUntil` that chip comes up `isSelected` — without
it the assertions run against the *previous* range and a capture silently records the old window
under the new window's filename. The master suite waits for the not-connected prompt to disappear for
the same reason: the first sync runs off the composition. Record images with:

```
./gradlew :app:recordRoborazziDebug
```

They land in `app/build/screenshots/`. Nothing is compared against a golden — they exist to be looked
at. Seeding matters: the subjective 1-10 scores and grip strength are hand-logged, so
`syncHealthData` alone leaves the combined mood chart empty and its three line styles unexercised,
and the grip trend blank. Glucose is a third case on the master graph: that screen syncs only meals,
heart rate and steps, so a test that wants a blood sugar line has to insert the readings itself.

## Conventions

`.gitattributes` pins `gradlew` to LF and `gradlew.bat` to CRLF — a CRLF `gradlew` fails with a bad
interpreter error on macOS and Linux. Don't let an editor normalise those.

Comments in this codebase explain *why* a non-obvious choice was made, not what the code does. Match
that when adding code — a comment restating the line above it is out of place here.
