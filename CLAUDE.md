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
| Fast gate (no Compose) | `powershell -File tools\fast-gate.ps1` |
| One test class | `.\gradlew.bat :app:testDebugUnitTest --tests "*FastingAdherenceTest"` |
| One test method | `.\gradlew.bat :app:testDebugUnitTest --tests "*FastingAdherenceTest.a feeding window crossing midnight is handled"` |
| Android lint | `.\gradlew.bat :app:lintDebug` |

**`lintDebug` is green, so a red run is now evidence you broke something.** It was red for a long
time on one error — `work/CaffeineLastCall.kt` calling `java.time.LocalDate.ofInstant`, API 34
against a `minSdk` of 26 — and that is the cost worth remembering rather than the fix: while lint
had a standing failure it could not report a new one, so the only `NewApi` error in the tree was
also the reason a second would have gone unnoticed. It now runs clean of errors, with 40 warnings
left standing.

`LocalDate.ofInstant` and `LocalTime.ofInstant` are **Java 9 methods and arrived on Android at API
34**; `LocalDateTime.ofInstant` is Java 8 and has been available since API 26, which is why the
three calls to *that* one are fine and the single call to the first was not. The distinction is
invisible on the author's own S25 and is exactly what lint is being kept green to catch. Use
`instant.atZone(zone).toLocalDate()`, the idiom the rest of the codebase already uses.

The APK lands at `app\build\outputs\apk\debug\app-debug.apk`. Test names are backticked and contain
spaces, so the `--tests` filter must be quoted as shown.

Deploying to the author's phone — a Galaxy S25, serial `RZCY520GH8F` — where `adb` is at
`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. `-r` keeps the data, which matters: that phone
holds the only copy of it.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then launch `com.prestondihle.healthtracker/.MainActivity`. `adb exec-out screencap -p > file.png`
is the way to see a UI change against real data, and there is no substitute for it — the last four
rounds of work each shipped a bug that every test passed and the phone caught in one screenshot.

**Back up the database before installing any build that bumps the schema.** The author's phone is the
only copy of this data. `HealthTracker-db-backup/` holds one directory per bump, named
`<yyyymmdd-hhmmss>-pre-v<n>`, and the practice predates these sessions:

```bash
adb exec-out run-as com.prestondihle.healthtracker cat databases/tracker_database > tracker_database
```

The `-wal` and `-shm` files go with it; a backup of the main file alone can be missing the most
recent writes.

**Take that pull through a POSIX shell, never PowerShell.** `>` in PowerShell is `Out-File`, which
applies text encoding — so `adb exec-out ... > tracker_database` writes a UTF-8 BOM in front of
`SQLite format 3` and mangles what follows. The file is still 1.1 MB and still looks like a backup;
`sqlite3` simply refuses to open it. A corrupted backup discovered at restore time is the worst
possible moment, so **check the header magic before trusting a copy** — `head -c 16 … | xxd` should
start `5351 4c69`, not `efbb bf53`. `PRAGMA integrity_check` on the pulled file is the other half.

**Then reading that backup with `sqlite3` makes the two companions disappear, and that is fine.**
Opening a database that has a `-wal` beside it checkpoints the log into the main file and removes
both companions on a clean close — so a backup directory inspected once ends up holding a single
file, which looks exactly like half of it was deleted. Nothing was: the main file now contains what
the log held, and `PRAGMA integrity_check` plus a row count against the live tables confirms it. Take
the companions anyway, because the alternative is losing those writes outright; just do not read the
absence afterwards as damage. There is no `sqlite3` on the device, so reading the data back means pulling the file
or using the app's own CSV export.

Driving the UI over adb has four traps, all of which have cost something:

- **A screenshot is shown scaled, and `input tap` takes real device pixels.** The phone is
  1080x2340; a screenshot read back at 923x2000 needs every coordinate multiplied by about 1.17
  before it is tapped. Taking the displayed y as read lands the tap a couple of hundred pixels high,
  which on Today is far enough to hit a *logging button* — one such miss wrote a stray 100 ml
  hydration entry into live data. **Check `adb shell wm size` and scale, or tap by resolved node
  rather than by eye.** This is the trap with the worst consequences, because unlike the other three
  it fails silently *and* writes. That entry has since been deleted, through the correction list the
  incident is the reason for — but note what made it expensive: 100 ml is also the ordinary dose
  logged here, so the bad row was indistinguishable from a real one and took a query over the
  backup to find at all. **A wrong tap that writes something plausible is not recoverable by
  looking at the data afterwards.**

- **A tap sent before the screen has settled lands on whatever was there before.** Reinstalling
  resets navigation to Today, and a `LazyColumn` scrolls to the top when it recomposes. Put the tap
  in its own call after the scroll, and screenshot to confirm the target's position first.
- **Samsung's Freecess freezes the app when it loses focus**, and a frozen app silently ignores
  input. If a tap appears to do nothing, check `logcat` for `freeze com.prestondihle.healthtracker`
  and bring the activity back to the front.
- **A picture-in-picture window swallows taps** in the bottom-right corner, which is where the
  Settings tab is. Drag it away rather than tapping through it. It also **hides whatever is under it
  in a screenshot**, which is the more misleading half: a waist trend read back with the PiP over
  its top-right looked like a chart drawing its rules and no data, and the trace was there the whole
  time. `input swipe` moves the window; check a chart is actually empty before believing it is.

`assembleRelease` is not usable out of the box — it reads `KEYSTORE_PATH`, `STORE_PASSWORD` and
`KEY_PASSWORD` from the environment and needs a real upload key. No signing material is in the repo.

## Vocabulary

Four nested levels: **tab → screen → card → chart part.** Worth reading before the architecture
section, because everything below uses these words precisely.

A **tab** is one of the six buttons in the bottom bar — Today, Log, Fuel, Activity, Wellness,
Settings; a **screen** is what it opens. They are one-to-one, so either names a destination
unambiguously — say *tab* only when the button itself is the subject. A **card** is one titled panel
stacked down a screen, and the title printed at its top is its name.

**"Page" is not a word for anything here.** Nothing in the app is one, and *pages read* is a tracked
metric with a daily goal (`DailyLog.bookPagesRead`), so the word already means something else. The
same collision is why the logging tab is **Log** rather than Log Book, the other half of the reason
being that two words do not fit a sixth of a phone's width.

**The six-tab move is done, and the code says what the tabs say.** Each tab opens its
own screen: **Today** the master graph, **Log** every hand-entry control (`LogScreen`), **Fuel**
fasting with the consumption and macro cards, **Activity** the movement trends
(`TrendsScreen`), **Wellness** the display cards with the vitals, mood and pages trends
(`WellnessScreen`), **Settings** the config. Activity is the *movement* trends — steps, runs, grip,
pushups, air squats — plus the AFT scorecard, which is the one thing there that is a test rather
than a trend; waist, weight, blood pressure, resting heart rate and sleep went to Wellness and
macros to Fuel, which is why `TrendsScreen` backs a tab whose name it does not share.
The cards moved a tab at a time — never unhooked before their replacement existed, on the phone
holding the only copy of this data — and one still carries a home that outgrew its name (below).

**Two ViewModels are shared across tabs, hoisted in `TrackerNavHost` so a tab switch does not spin up
a second copy — and, for Wellness, a second Health Connect sync.** `WellnessViewModel` backs
both Log (its input cards) and Wellness (its display cards); `TrendsViewModel` backs Activity,
Wellness and Fuel (the trend charts). Log reuses Wellness's card composables directly — `BodyCard`,
`MoodCard` and the rest are `internal` in `WellnessScreen.kt` for exactly that. The moved trend
cards live in `ui/trends/TrendCards.kt` and the meal cards in `ui/components/MealCards.kt` so their
new homes share one copy.

One place where the tab and the code disagree, and it will come up:

- **The chart on Today is still the *master graph*.** The screen around it is `TodayScreen`,
  `TodayViewModel` and `TodayUiState`, but the graph inside kept its own vocabulary —
  `MasterSeries`, `MasterRange`, and a card titled *Food, blood and body*. The tab moved; the chart
  did not become a different chart, and renaming its parts after the tab that happens to host them
  would be the drift this section exists to warn about rather than a cure for it.

**`WellnessScreen`, `WellnessViewModel` and `WellnessUiState` were `Dashboard*` until the tab move
settled**, and the argument against renaming them was that Log leans on the same view model, so
Wellness would only ever be half the truth. That argument lost, and it is worth knowing why: a name
naming *one* of a thing's two users is still better than a name naming neither, and `Dashboard` had
stopped matching anything on screen at all. Log sharing `WellnessViewModel` is the deliberate part —
a weight typed on Log is on Wellness's chart with no sync in between — and it is spelled out at the
hoist in `TrackerNavHost` rather than left for the name to carry. The master graph above is the
case that went the other way, and the two together are the rule: **rename a thing after what it now
is, not after the tab that happens to host it.** Wellness was the first; the chart is not the
second.

The chart words are not interchangeable, and the distinctions are enforced by the drawing code rather
than by convention — see *Drawing weight, and what gets read as data* for why each exists:

| Word | What it is |
| --- | --- |
| **plot** | the canvas itself, inside its card |
| **series** | one drawn line or set of bars (`ChartSeries`) |
| **band** | a shaded range of **values** — "was it in range" (`AxisSpec.band`) |
| **shade** | a shaded range of **time** — "what was happening then" (`ChartShade`) |
| **rule** | a single horizontal line at one value (`AxisRule`) |
| **marker** | a vertical line at one moment (`ChartMarker`) |
| **axis** / **gutter** | the numbers down a side (`AxisSpec`) |
| **legend** / **key** | the named swatches under the plot |
| **crosshair** | the hairline a tap on the plot leaves |
| **chips** | the range buttons — 3h/6h/12h/24h/48h/7d |
| **stepper** | the back/forward pair on a card that shows one day or one night |
| **switches** | the per-series on/off row on Master (`SeriesToggles`) |

**Band and shade are the pair most easily confused**, and confusing them builds the wrong thing: a
band backs one axis and asks whether a value was in range, a shade runs the full height of the plot
and asks what was happening at that time. Sleep needed the second and there was only the first, which
is why `ChartShade` exists.

**Range is the setting; window is the span it produces.** `MasterRange.DAY` is a range; the 24 hours
it covers is the window, and that window moves when the plot is dragged — which is a **pan**.

## Architecture

Compose UI → ViewModel → `TrackerRepository` → (`TrackerDao` + `HealthDataSource`). One Gradle
module, `:app`.

Two packages sit outside that chain and reach the repository directly, because neither has a screen
to hang a ViewModel off: `work/` is the hourly caffeine check, and `widget/` is the Glance home-screen
widget. Both pull the repository off `TrackerApp.container` themselves. A ViewModel there would be a
ViewModel with no lifecycle to be scoped to.

**Manual DI, no framework.** `TrackerApp` holds a `DefaultAppContainer`, which lazily builds the
Room database, the `HealthConnectDataSource`, and the single `TrackerRepository`. `MainActivity`
pulls the container off the application and hands it to `TrackerNavHost`, which creates each
screen's ViewModel through that ViewModel's own `provideFactory(repository)` companion function.
Adding a screen means: a `Screen` enum entry, a `composable` block in `TrackerNavHost`, and a
ViewModel with a `provideFactory`. A ViewModel shared across tabs is instead hoisted to the
`TrackerNavHost` body (`viewModel()` at that scope is owned by the activity, so both routes get the
one instance) and handed in as a parameter — that is how Log and Wellness share `WellnessViewModel`.
The bottom bar carries six tabs (Today, Log, Fuel, Activity,
Wellness, Settings) — Material divides the width evenly and truncates, so **new labels have to be one
short word**; "Master Graph" rendered as "Master G...". Eight characters is the most that has ever
fitted, which is why Nutrition became Fuel and Wellbeing became Wellness rather than being left to
truncate.

**ViewModels expose exactly one `StateFlow<...UiState>`**, assembled by `combine` over repository
flows and `stateIn(SharingStarted.WhileSubscribed(5_000))`. Derived values (fast duration, goal
fraction) are computed as `get()` properties on the UiState rather than stored. `WellnessViewModel`
additionally combines in a one-second `ticker` flow to drive the live fast timer. ViewModels take an
injectable `ZoneId` defaulting to `systemDefault()`, which is what makes the time maths testable.

**Window and range enums are the single source of a screen's query span.** `MasterRange`
(3h/6h/12h/24h/48h/7d), `GlucoseWindow` (3h/6h/12h/24h/48h/72h) and `TrendsRange`
(7/14/30/90/180/365 days) each drive both the chips and the query, so adding an entry widens the
fetch with no second edit — `GLUCOSE_WINDOW_HOURS` is derived as the maximum, and Wellness queries
once at that width so switching windows is a redraw rather than a round trip. Six chips no longer fit
one row of a phone at stock size, and a sideways-scrolling row would hide the widest options behind a
gesture nobody knows is there — so `ChoiceChipRow` in `CardKit.kt` lays them out at a fixed number
per row, every button the same width, over its own `CompactChoiceChip` rather than `FilterChip`.
Master takes all six on one row; `TrendsRange` takes three and three. A stock filter chip reserves
16dp inside each end of its label and that padding is not a parameter, so squeezing one ellipsises
the label instead of shrinking it — `48h` rendering as `4…` is worse than the second row it was
meant to avoid. `GlucoseWindow` is the one still on `FlowRow`, since Wellness's chips sit inside a
card rather than across the screen.

**"No second edit" held for the cached day-indexed charts and for nothing else**, which is worth
knowing before adding a seventh entry. Two cards on `TrendsRange` are not fed by a Room flow over
daily rows: the Runs chart costs a raw heart-rate read *per session*, so a year of running is around
a hundred and fifty paginated Health Connect round trips to draw a hundred and fifty bars on a
four-hundred-pixel plot, and the biggest-responses ranking reads every glucose sample in its window,
which at CGM resolution is six figures of rows pulled into memory to print five lines. Both stop at
`LIVE_READ_MAX_DAYS` (90) — deliberately the widest range that existed before 180 and 365, so every
older chip behaves exactly as it did — and both print `TrendsRange.effectiveLabel` rather than
`label`, because a card drawing ninety days under a chip that says 365 is claiming a year it never
read.

### Weekly buckets on the long ranges

**180 and 365 days aggregate to weekly buckets**, keyed on `UserSettings.weekStartsOn` like the CGM
week and the training card, so every week in the app starts on the same morning. A day has stopped
being a slot worth drawing at that width — 365 bars across a phone are a third of a pixel each, and a
year of daily weights is a band of noise with the trend somewhere inside it. `TrendsRange.weekly`
carries the flag, `TrendsUiState.buckets` is the x-slots the charts draw, and `bucketed` is the fold.

**A bucket is a mean, never a sum, and the one rule is doing two jobs.** For anything with a daily
goal — steps, sleep, calories — a mean per day is on the same scale the reference line is drawn at,
so the existing goal lines stay honest with no second axis and no seven-times-larger target; summed,
the bars would sit a decimal place above their own goal. For anything merely measured — weight,
waist, resting heart rate — the mean is what the week weighed. The two categories read as different
requirements and collapse to the same arithmetic.

**The weeks at both ends are partial, and only a mean survives that.** The range is a count of days
back from today and lands mid-week whatever day it is run on, so a summed newest bucket would shrink
through the week and reset every Monday — a year view opening on a cliff at its right-hand edge every
day except one, which is the edge being read. `TrendsBucketsTest` pins it from both directions: a
week walked at the same rate as the one before it draws at the same height even when only three days
of it have happened.

**Days with no reading are left out of the divisor rather than counted as zero**, which is ground
rule 6 arriving at the arithmetic — a week the watch synced on three days holds three days of
evidence, and dividing it by seven would draw a fortnight of illness. A week with nothing in it at
all is null and breaks the line. The distinction is already carried by whether `series()` yields null
or `0f`, so `repSeries`' deliberate zeroes average in correctly for free: a week's pushups are reps
per day, not reps per day trained. `macroBars` is the one that needed its own denominator, since it
substitutes `0f` for a missing figure at the point of use — it averages over the days that recorded
food, because a day nothing was read from draws plainly as an empty bar daily but folded into a week
at a seventh of its weight would report eating that never stopped and calories that halved.

Every day-indexed subtitle runs through `TrendsUiState.subtitle`, which appends *weekly average* at
these ranges. The subtitle already carried the unit and the unit has genuinely changed: a point is no
longer Tuesday's weight but the mean of the week Tuesday was in, and a reader comparing it against
the goal line beside it has nothing else to notice the difference by.

### The trend under a measurement

`domain/MovingAverage.kt` is the seven-day trailing mean drawn over weight, resting heart rate and
net calories. A weight read every morning moves a pound and a half on water and glycogen, several
times what a week of real effort produces, so the raw line asks to be read at its last point and
that point is mostly noise.

**Trailing, not centred, and that is where it parts company with `GlucoseSmoothing`.** A centred
kernel revises last Tuesday every time a new morning is logged — a reader who saw the line turn
upward can come back to find it never did, with nothing on the chart to say it moved. Trailing costs
a few days of lag and buys a past that stays put and a newest point that means what it appears to:
the figure you would have had that morning.

**Time-weighted for `GlucoseSmoothing`'s reason, which survives the change of kernel.** These are
daily series with holes in them, so "the last seven readings" is not "the last seven days" and on a
sparse stretch would silently reach back a fortnight. Gaussian rather than flat for that file's other
reason: a boxcar drops a reading from full weight to nothing the day it ages out, and the kink shows.

Its guarantees are the ones that let it be drawn *over* the readings rather than beside them. It
cannot overshoot — every output is a renormalised weighted mean of real readings. It does not
resample: one output per input reading, at that reading's own date, so a day nobody weighed in on
gets no averaged point either. And below `MIN_READINGS` (3, `GlucoseSmoothing`'s own floor) it emits
nothing rather than returning the reading itself — a window holding one morning would trace the raw
line exactly under a key saying *7-day avg*, which is the `Readiness` baseline refusal in a different
shape.

**`TrendsUiState.trailingAverage` returns empty at the weekly ranges**, and pads what it does return
back onto `buckets`. Empty because a weekly bucket is already a mean of seven days and a seven-day
mean of those would be a second smoothing sold as the first; padded because `MultiLineChart` maps a
point to an x by its *index*, so a shorter list draws the average stretched across the full width
with every point of it above the wrong day — wrong in the way nobody checks, since it still looks
like a plausible trend. `TrendWithAverage` falls back to a plain `LineChart` when the average is
empty, because a legend appears the moment there is more than one series and a key naming a line
nobody can find is worse than no key.

**`ChartColors.movingAverage` is the case for checking both schemes, and it failed the first check.**
The average takes the primary's own hue — it is the same quantity said more slowly, and a second hue
would claim it was a second thing — so lightness and the dash are all that separate it from the
readings. Which way the lightness goes **inverts** between the schemes: light's primary is mid-toned
so the average goes darker (Yale Blue), dark's is already pale at `#9FC6DF` so it goes deeper. The
first dark value tried was `#5B8FB5` and the two swatches in the key read as one colour — the
sodium-against-diastolic collision exactly, caught here by capturing both. Dark needs the bigger
step, because the gap is between two lifted tones rather than between a mid tone and a dark one.

**Net calories on Fuel is `dietaryCalories − totalCalories`, null unless both halves were recorded**,
and that is deliberately stricter than the day card on Today and Wellness. That card stands a zero in
for absent food, correctly: on today so far, nothing logged is nothing eaten, and the guard exists
because the differential was blank through every fasted morning. On a day that has *finished*, food
with no figure against it almost always means the day was not tracked — and counting it zero would
draw a deficit the size of the whole day's burn, a fast that never happened, on the chart most likely
to be read as evidence one did. The burn half keeps its guard on both: it comes from a watch, so
absent means unsynced.

Its rule at zero is passed as `goalLine` but is not a target, and the caption says so — *below the
line is a deficit*. The mechanism is borrowed purely because it is what folds a mark into the axis: a
run of deficit days scaled to themselves puts every point below a zero clipped off the top, which is
`ChartBoundsTest`'s failure on the one chart whose reference is the difference between losing weight
and gaining it.

### The Today summary strip

A wrapped row of chips above everything on Today: steps against goal, sleep, time in range, net
calories, the running fast, and any streak with days on it. Each opens the tab that owns its figure,
using **the same navigation options the bottom bar uses** — a second path with different flags would
leave the back stack depending on which of the two the reader tapped.

**A chip with nothing to say is absent, not blank.** Steps before the first sync, time in range on a
phone with no monitor, a fast nobody started: an em dash in a strip this small reads as a broken row,
where a missing chip reads as a metric this reader does not use. With nothing at all it takes no
height, which is the right amount of screen for it on a first run. Only a *met* goal is marked;
flagging every unmet one at nine in the morning would have the strip scolding the reader for the time
of day.

`TodayViewModel.summary` is its own flow because both of its figures are about **today** while
`uiState` is about the *window*, which is zoomed and dragged. Time in range read off the plotted
glucose would report the last three hours at the 3h chip — a figure that looks like a day's and is
not — and the fast is not on that state at all. It is measured against *the day so far*, the rule the
Fuel card settled.

**`TrendsViewModel.streaks` was split off `records` for this strip.** Today is the tab the app opens
on, and `records` reads a year of glucose to find a best day in range; folded together, a glance at
the day would have paid for a CGM archive to answer a question about supplements. The streaks read
day-indexed rows and a handful of ticks. Activity subscribes to both; Today to `streaks` alone.

The step chip prints a tenth (`6.8k / 10k`). Truncating to whole thousands reads 9,900 steps as
*9k / 10k*, a thousand short of the truth on exactly the evening somebody is deciding whether to walk
round the block again.

The streak label is **"Step goal"**, not "Steps" — partly because a streak counts days that *met the
goal* rather than days that had steps, and partly because "Steps" collides with the Steps trend card
on the same screen. `ScreenRenderTest` caught the collision as *"Expected at most 1 node but found
2"*, which is worth knowing as the shape that failure takes.

**Two shapes of infinite composition loop have now been written here, and they share a face.** Both
surface as `AppNotIdleException` on whichever test is slowest — the same face the documented
load-timeout wears — so neither points at itself, and both were misread as load first. The tell that
separates them from real load is this file's own advice: run the suspect class at a **known-green
commit**. If that passes, it is the change and not the machine. The two shapes:

- a view model constructed inside `setContent` (below), and
- a `compositionLocalOf` provided an unremembered value (see `LocalCardFold` above).

**A view model constructed inside `setContent` is an infinite composition loop.** `MasterGraphRenderTest`
gained a `TrendsViewModel` for the strip and it was first written inline in the `TodayScreen(...)`
call — so it was rebuilt on every recomposition, each copy starting its own flows and each emission
provoking the next recomposition. It presents as `AppNotIdleException` **on whichever test happens to
be slowest**, which is the same face the documented load-timeout wears, so it read as load for three
runs. What separated them was this file's own advice: the known-green-commit run *passed*, which
pointed the finger back at the change rather than the machine. Hoist the view model out of the
composable lambda, as the two beside it already were. `ScreenRenderTest` had four more of the same
shape — `CardOrderViewModel(repo, "activity")` inline in a `render { }` — which were harmless while
that view model held one flow and started looping the moment it held three. **Hoist every view model
a render test builds.**

### The compare card

Two daily series on one `DualAxisTimeChart`, on Wellness. **No new chart code** — that chart has been
dual-axis since the master graph needed it, and what is new is the pairing. A gutter each is what
makes it work: steps run to five figures and sleep to single ones, and on a shared scale the sleep
line is a flat rule along the floor.

`ComparableMetric` is a fixed menu of eight, and the limit is the point: these are the series that are
*one number per day*, so any two line up slot for slot without resampling. A run's zone breakdown or a
night's stages have no single daily value and are absent rather than flattened into one. A metric
cannot be paired with itself — two identical lines teach nothing and the second gutter repeats the
first — so each picker excludes the other's choice.

**It draws two lines and claims nothing.** No correlation figure, no fit, deliberately: a number would
turn "these moved together for three weeks" into a finding, on data with no controls, a sample of one,
and whatever else was happening those weeks.

**`compare` keys on the selection as well as the range**, which is the whole reason it is not a field
on `uiState`. Time in range needs every glucose sample in the window — six figures of rows at a year —
and loading it for everyone so that one option in a menu of eight is quick would put the cost of a CGM
archive behind a card most readers will pair steps with sleep on. Glucose and caffeine are queried
only when chosen and are `flowOf(emptyList())` otherwise, so switching *away* stops paying too.

`MetricSource` is where a metric becomes a series, and every figure with a card elsewhere is derived
**the same way there**: weight merges manual over synced, net calories wants both halves, time in
range refuses an uncovered day. Two derivations of one number is how two cards come to disagree about
one morning.

**The lag shifts the data, not the drawing.** `lagSecond` moves each second-metric point onto the
following day in the state itself, so the crosshair reads what the plot shows; shifted at the renderer
they could disagree about which day a point belongs to. The window then has to reach a day further, or
the newest shifted point is clipped — silently, and looking exactly like a metric that stops early.
The switch is labelled by direction (*Shift Sleep a day later*) rather than as "+1 day", since which
way is the entire content of the control.

One trap it walked into: `ChartSeries.label` must be the bare metric name. The legend appends the unit
from the axis the series is read against, so spelling the unit into the label renders
*Steps (steps) (steps)*.

### The usual row on Log

Log's one-tap shortcuts, three rows of chips: four fixed water sizes, then caffeine, then whatever is
left of the current supplement slot. **Nothing is stored** — no favourite to set up and none to go
stale — but only the caffeine row is still *derived*, and that split is the thing to hold on to.

**The fixed sizes are what make the row work on an empty day.** Water offers 1 oz, 4 oz, 100 ml and
500 ml, the same four the Hydration card writes; caffeine offers the 35 mg tablet, read from
`QUICK_CAFFEINE_MG`, which is `internal` in `ui/fuel/FuelScreen.kt` precisely so the two cards cannot
drift apart. None of them need history, so the card has no empty state left to show. It used to have
one — a sentence saying there was nothing to repeat yet — and that sentence is gone because the case
it covered can no longer happen.

**Caffeine is the one chip still read from disk.** It takes the *last* dose, because it is drunk in
whatever the current cup is — somebody who has moved from a 95 mg cup to a 150 mg one wants the new
one on the second day, not once the tally catches up. It sits before the tablet and drops out when it
*is* the tablet, so the row never offers 35 mg twice.

**A row per substance, and the labels abbreviated to fit.** Chips read `4 oz H2O` and `35 mg CAF`
rather than naming the substance in full: this one card writes three different things, so an amount
alone would not say which a tap lands on, and the whole word cost more width than four chips had to
spare. Water is split ounces-then-millilitres, the same split the Hydration card makes — left as one
wrapping row of four, the phone put three on the first line and stranded the fourth on a line of its
own looking like a bug.

**`UsualIntake.usualVolume` is still here, still tested, and no longer displayed.** Water took the
*mode* of the last month — a bottle is a bottle, and one odd glass should not become the suggestion
just for being most recent; a mean was never offered, since the mean of a 500 ml bottle and a 250 ml
glass is 375 ml, a quantity with no container behind it. The fixed sizes replaced it, but
`usualWaterMl` is still computed on every `usual` emission and read by nobody. Either delete it with
`usualVolume` and its tests, or put the derived chip back beside the fixed four; what is there now is
a month of hydration scanned for a number nothing shows.

`slotAt` follows the clock rather than always offering the morning — a row proposing the morning's
pills at nine at night is a row nobody taps. Boundaries are noon and five, where the words stop being
true rather than at thirds of a day.

**`usual` is its own `StateFlow` on `WellnessViewModel`, not two more sources on `uiState`**, and the
reason is the window rather than the combine's arity: `uiState` loads caffeine over a few hours
because the decay curve needs no more, and a habit read from that window would vanish whenever the
reader had not had a coffee since breakfast. `UsualIntake.HISTORY_DAYS` is 30. Load wider than you
display, with the two spans further apart here than anywhere else in the app.

**`WellnessViewModel` gained `logHydration`, making it a second writer of that table** alongside
`FuelViewModel`. That is safe for the reason `TrackerRepository` exists — both go through the one
entry point, so Fuel's card and its correction list update either way. The alternative was hoisting
`FuelViewModel` so Log could share it, which would have carried **its one-second fast ticker onto
Log**, and a ticking screen is one that cannot be scrolled in a test. That cost is already three
cards deep; do not add a fourth by hoisting a ticking view model onto a still tab.

`outstandingInSlot` intersects against the standing list rather than counting tick rows, the rule
`supplementsTakenCount` already follows, so a dose orphaned by a deleted supplement cannot make the
slot look finished. The chip names the count because it is the one control here that writes several
rows on one tap, and the reader should know how many before rather than after.

### The goal ETA

`domain/GoalProjection.kt` fits a straight line through the last 30 days of merged weight and runs it
forward to the next mark — the nearest waypoint still ahead, or the goal if none lies between.

**Most of that file is refusals, and they are the feature.** An ETA is the most confident-sounding
thing the app prints, a specific weight on a specific dated day, and it is fitted to the shakiest
input any chart here carries. It declines on: fewer than `MIN_READINGS` (5) in the window, a slope
pointing away from the target, an arrival past `MAX_HORIZON_DAYS` (730), and no goal set at all. The
slope one matters most — somebody two pounds up over the month has an arrival date somewhere in the
past or the far future depending which side of the goal they are on, and silence is the honest answer
to "when at this pace" when this pace never arrives. **A wrong slope does not look wrong; it looks
like a plan.**

**The segment starts at the fitted value, not the last reading.** A morning three pounds high on water
would otherwise put the projection's first point above every point of the trend it claims to extend.

**It is the one series in the app drawn on dates nobody has lived**, which is also the one way it can
silently corrupt the rest of the chart: `MultiLineChart` maps a point to an x by its *index*, so
adding future slots to one series and not the others slides every reading onto the wrong day, and
both versions draw a plausible chart. `weightProjectionSeries` therefore returns the **padded
readings alongside the projection**, and the moving average is stretched onto the same slots by
`padTo`. `ScreenRenderTest` asserts the two lists share their dates, which is the only cheap way to
catch it.

The drawn lead is short — `range.days / 5`, clamped to 2..21 — because a mark six months out drawn to
scale would be six times the width of the chart under it. The sentence carries the date; the line
only carries the direction. It is empty at the weekly ranges, where a slot is a week and a lead
measured in days would run months into the future; the sentence still prints there, since the fit
never saw buckets.

Its colour is `chartColors.threshold`, deliberately the goal's own family rather than the readings'.
That was considered rather than defaulted: the projection's whole meaning is *when you meet that
line*, so sharing its hue is informative, and what separates them is the dash — dotted for the model,
dashed for the target, solid for the one measurement on the plot. In the dark scheme the two reds are
close, which is acceptable here in a way `sodium` against `diastolic` was not: that was two *series*
in one colour with a legend claiming they differed, and this is a series and a rule that are about
the same thing.

### Streaks and personal records

`domain/Streaks.kt` counts days in a row. It was lifted out of `FastingStatistics`, which had the
only copy and is now one of four callers — and what made it worth extracting is not the loop but the
rule under it. **Today is allowed to be empty; yesterday is not.** A streak read at nine in the
morning is being read before the day has had a chance to happen, so counting today as a miss resets
every streak in the app overnight and restores it each evening — wrong for most of the hours anybody
looks, and wrong in the direction that makes it useless. One unfinished day is a day in progress, two
is a lapse.

**It takes the set of dates that met the bar, never the readings.** A step goal, a protein target, a
completed supplement slot and a day with any fasting on it are four different questions and only one
is a comparison against a number, so "did this day count" stays with the data. One consequence is
deliberate and is the file's single departure from null-is-not-zero: **an absent date and a failed
one are the same thing to a streak**, because an unbroken run means every day in it cleared the bar
and a day with no evidence did not.

`FastingStatistics.currentStreak`/`bestStreak` remain as methods and delegate — what a *fasting day*
is belongs with the fasting; only the run-counting generalises. `FastingStatsTest` passing unchanged
against the extracted version is what says the refactor was behaviour-preserving.

`domain/PersonalRecords.kt` is the best of each thing, and **every figure is a real performance on a
real day**. The two-mile comes from a recorded `AftAttempt`, never from `RunPace`'s projection: on a
card headed *records* a model reads as an achievement, which is exactly the confusion the AFT card
spends two sentences preventing. Only *finished* fasts can be the longest, for `FastingStats`' reason
— a running one reports its length so far, takes the record, and beats itself an hour later. A fast
is dated by the day it **ended**, since a 48-hour fast broken on Sunday is a Sunday achievement and
dating it Friday puts the record before two of the days that earned it. Grip is per hand because the
columns are nullable so one hand can be logged alone, and a best-of-both would report the dominant
figure under a label covering both.

Nothing is stored — it is a scan of a few hundred rows, and a stored best is a claim to invalidate
every time a row is edited, which on correctable data happens routinely. That is `AftScoring`'s
never-store-a-score argument arriving at another table.

**`TrendsViewModel.records` sits outside `uiState` and outside `TrendsRange`**, like `aft`: a record
that changed when a chart's range chip moved would be describing the chip. `RECORD_HISTORY_DAYS`
(365) bounds the day-indexed reads and glucose; AFT attempts and fasting sessions are read
**unbounded**, because those tables are small and a two-mile from two years ago is exactly the record
worth beating. The bound exists for glucose above all — best-day time-in-range has to be computed
from the readings, since the coverage gate is a rule about the span they occupy. It is larger than
`LIVE_READ_MAX_DAYS` because it is one cached indexed query rather than a hundred and fifty round
trips. `bestTimeInRangeByDay` groups by day **once** and scores each day against its own handful;
handing the whole year to `GlucoseAnalysis.over` 365 times re-filters the same list from the top
every call, which at CGM resolution is tens of millions of comparisons for one row of a card.

`StreakCount.available` separates *nothing kept up* from *nothing to keep up*. A step streak with no
goal set is not a zero, it is a question nobody asked, and the card drops those rows rather than
printing noughts that reproach the reader for missing targets they never set — the
`GlucoseReportState.hasAnyReadings` distinction again. The supplement streak intersects against the
standing list rather than counting tick rows, the rule `supplementsTakenCount` already follows, so a
dose orphaned by a deleted supplement cannot complete a day on its own.

One limitation worth knowing: **the standing supplement list is not versioned**, so old days are
judged against today's shelf. Adding a supplement resets the adherence streak, which is arguably
right — you are not completing your current stack — but there is no history that could answer it
otherwise.

**The master graph's window is no longer anchored to now.** `TodayUiState.panOffset` is how
far back a horizontal drag has pulled the right edge, and `windowEnd` is `now - panOffset`.
Everything that draws must measure from `windowEnd`, never from `now`: the absorption and caffeine
curves are *sampled between two bounds* rather than clipped afterwards, so one still ending at `now`
does not stop short on a panned window -- it runs on past the right-hand edge, in the same ink as the
part of it that belongs there. `mealsInWindow` needs the same upper bound, since it is also what the
marker rules are built from, and a meal listed with no rule to find is worse than one left out.
`now` itself stays `now`: the Activity card's totals are *today's* however far back the plot has been
dragged, `syncHealthData` is called for `today` rather than for the window, and
`backfillGlucoseGaps` is anchored at this moment rather than at the window's edge. Panning is a look
at history; it does not move the day.

Two consequences worth knowing. `advanceNow()` grows `panOffset` by exactly what the clock gained
whenever it is panned, so the ticker cannot drag a parked window along behind it -- the reader went
back to a particular evening and that evening does not move. And the series queries are keyed on an
anchor **snapped down to the hour** rather than on the raw offset: a drag emits once a frame, every
emission of a raw key would tear down six Room subscriptions and open six more, and every one of
those queries is open-ended forward, so an anchor an hour early is a superset and never a subset.

**`TrackerRepository` is the only data entry point** and owns the conversion between the domain's
`Instant`/`LocalDate` vocabulary and the epoch-millisecond bounds the DAO queries expect
(`startOfDayMillis` / `endOfDayMillis`). Callers never do that arithmetic themselves.

### Card order and the profile

**Every tab's cards can be reordered, and the order is saved per tab.** `ui/reorder/CardReorder.kt`
holds it: `reorderableCards` is a `LazyListScope` extension that takes the cards in their built-in
order — each a `ReorderableCard(id) { … }` — and draws them in the saved order under a pair of move
arrows. A tab declares its cards once, in the order it wants out of the box, and `effectiveCardOrder`
reconciles that with whatever the reader has saved: ids the save no longer knows are dropped, ids the
tab gained since are appended, so a card added in an update shows up at the bottom rather than
vanishing. `CardOrderViewModel` (one per tab, keyed by route in `TrackerNavHost`) reads and rewrites
`CardOrderEntry`. Cards keyed by id so Compose keeps each card's own state as they swap places.

Up/down arrows rather than drag — chosen deliberately — so the control is visible and testable
without a gesture, in the same spirit as `SeriesToggles`. Two tabs took work to fit the model:
**Settings** is config rather than a content dashboard but reorders all the same, and **Fuel**'s
extended-fast entries had to move *inside* the "Extended fasts" card (they were separate cards below
it) so the scheduler and its list are one reorderable unit and nothing is left pinned.

**Every card also folds to its title row**, saved per tab and card beside the position.
`MIGRATION_20_21` adds `CardOrderEntry.collapsed` in the `MIGRATION_5_6` shape — `NOT NULL DEFAULT 0`,
because every row on disk was written by a build that could not fold anything, so `0` is the true
statement about all of them and an upgrading reader sees nothing change until they fold something.
There is no third state: a card is open or shut, and "not known whether it is shut" is not a way a
card can be.

**Position and fold share a row, so they must be written together.** `setCardState` takes both and
there is no way to express half of it — the write is a whole-row upsert, so passing only the order
would rewrite every row with `collapsed` back at its default and moving one card would silently
unfold the entire tab. Worse, it would read as the *fold* having failed to save rather than the
reorder having cleared it, so the wrong control would get the blame. `CardFoldTest` pins it from four
directions, including that folds do not leak between tabs — the reason the primary key is the pair.
`toggleCollapse` writes the whole effective order alongside the fold, which is what makes it work on
a tab nobody has ever reordered: there are no rows yet, so a fold has nowhere to live until the
positions exist to hang it on.

**The title has to be drawn by the card, not by the wrapper.** `reorderableCards` owns the move
arrows and the item; the title lives *inside* `TrackerCard` / `TrendCard`. Folding what the wrapper
owns would take the title with it and leave a row of chevrons over nothing — so the fold reaches the
card through `LocalCardFold` and the card itself decides to stop after its title row. That is why a
composition local is right here and wrong for `ChartColors`: this consumer *is* a composable, where
a series colour is needed by enums and draw scopes that cannot read a local at all. Threading it
explicitly would have meant a title and a fold parameter on all ~56 card call sites.

**The `CardFold` handed to that local must be `remember`ed.** `LocalCardFold` is a
`compositionLocalOf`, which invalidates every reader when its value stops comparing equal — and a
`CardFold` built inline carries a fresh lambda each time, which compares by identity and so never
equals the last one. Each recomposition provokes the next: an infinite loop, presenting as
`AppNotIdleException` on whichever render test happens to be slowest and pointing nowhere near here.
This is the second time that failure has worn that disguise; see the note under the Today strip.

A card's own `action` (the refresh button on Today) goes away with the body it acts on — a sync
whose result cannot be seen is a button worth removing while folded.

The **profile** on `UserSettings` — `maxHeartRateBpm`, `ageYears`, `sex`, `heightCm` — is the "You"
card at the top of Settings. Max HR is the one with teeth: it zones the runs chart. Height is stored
in cm like everything else and stepped in inches or centimetres by the unit setting.

### The metabolic scatter

`ui/components/ScatterChart.kt` is the **only chart here with a measurement on both axes**. Every
other one is indexed by time and answers *what did this do*; this answers *do these two move
together*, which a pair of lines can only hint at. Nothing is reusable from the day-indexed charts —
their x axis is a list of dates and the tick maths assumes it, which is why `niceTicks` is duplicated
rather than shared.

`MetabolicMetric` is the menu (twelve, `ComparableMetric`'s rule: figures that are one number per
day, so any two line up with no resampling), `MetabolicSource` turns rows into points, and
`domain/EnergyBalance.kt` fits the line.

**The crossing is the whole feature.** With grams lost on y and calories eaten on x, the fitted line
crosses `y = 0` at the intake where the weight holds — this reader's own maintenance, measured rather
than predicted from a population equation. Against *net* calories it answers a different question
equally well: a watch whose burn estimate were right would put that crossing at zero, so wherever it
lands is how far the watch is out.

**It says maintenance and never BMR, and that distinction is not pedantry.** Basal metabolic rate is
what a body burns at complete rest; this figure includes every step walked and every session trained
on the days that went into it. They differ by hundreds of calories. Printing one under the other's
name would make this the most confidently wrong number in the app, which is exactly the shape
`GoalProjection` exists to warn about — **a wrong figure here does not look wrong, it looks like a
plan.**

`EnergyBalance` is therefore mostly refusals, and each catches a different failure:

- **Fewer than `MIN_POINTS` (5)**, `GoalProjection`'s figure. Two points fit a line perfectly and say
  nothing.
- **Zero variance in x** — a column of points at one intake has no slope, and the arithmetic divides
  by zero to find that out.
- **A slope pointing the wrong way.** More food has to mean less lost. A positive slope is a window
  in which something else moved, and its crossing would be a maintenance figure below everything
  actually eaten. Only safe to apply where x is an intake, which is what `isIntake` gates.
- **`rSquared` below `MIN_R_SQUARED` (0.33).** The line is still *drawn* — seeing it lie flat through
  a scatter is how a reader learns not to trust it — and only the number is withheld.
- **A crossing outside `PLAUSIBLE_MAINTENANCE` (1,000–6,000).** Arithmetically fine and about
  something other than energy balance. This is the one a correlation threshold cannot catch.

**Weekly is the default bucket and daily is the option needing justification.** The scale moves ~700 g
on water and glycogen where a 500 kcal deficit weighs about 65, so a day's points are noise an order
of magnitude larger than the signal and a line through them fits the water. Weight is also **smoothed
before differencing**, never taken raw, for the same reason.

Three quieter rules, each of which would still draw a plausible chart if reversed:

- **Losing is positive**, because the axis says *weight lost*. Inverted, every point lands in the
  wrong quadrant and the slope flips — which the wrong-way refusal then catches, turning a working
  card into a permanently silent one.
- **A weekly bucket reports grams per *day*.** Per week differs by a factor of seven and looks just
  as plausible; per day is what lets the two bucket settings be read against each other.
- **A bucket missing a smoothed weight at either end is dropped, never plotted at zero.** A row of
  points along `y = 0` is a run of weeks that "held steady" and never happened, and it would drag the
  line flat and put the crossing wherever those weeks sat on x. `MetabolicScatterTest` pins the
  consequence too: the week *after* a gap in weighing is also dropped, because its left edge is the
  first morning back on the scale and the trailing window holds one reading there. Closing the bucket
  over the nearest available day instead would attribute a fortnight's loss to one week.

**Weight is read a `MovingAverage.WINDOW_DAYS` lead-in earlier than the window drawn.** Without it the
earliest bucket has no smoothed value at its left edge and vanishes — silently, looking exactly like
a window that starts later, and on the short ranges that is a material share of the points.

`AVG_HEART_RATE` is the **whole day's** mean, sleep included, and is labelled so. A daytime-only
average would be the more useful figure and cannot be built: it would come from `HeartRateBucket`,
which is only synced `HEART_RATE_SYNC_HORIZON` (48 h) back, so the series would be empty for all but
the last two days of any window worth fitting. A chart silently drawing two points under a chip
saying ninety days is worse than one that says plainly what it has.

`boundsIncludingZero` is what makes the plot *four-quadrant* rather than merely scattered: a cloud
sitting entirely in deficit would otherwise be drawn on an axis with no zero line anywhere on it, and
the crossing is the one landmark the chart has. It does not force the origin onto an axis genuinely
far from it — padding 2,000-to-3,000 calories down to zero would squash every point into the top
third for a gridline nobody reads.

`MetabolicSource` is `internal` rather than private, unlike `MetricSource`, for `SleepCard`'s reason:
its arithmetic has three ways of being wrong that all still draw a plausible chart, and reaching it
through the flow would mean testing them against a live clock.

### The plank timer

`PlankSession` is one hold, timed on Log and plotted on Activity. `UserGoals.plankHoldSecondsGoal`
is the target it is drawn against.

**Its own table, not an `ExerciseSet` with a `movement` of PLANK.** That table's quantity column is
`reps`, and a held time living in a column named for repetitions is the kind of thing that reads fine
for a year and then gets summed by something that trusted the name.

**Separate from `AftAttempt.plankSeconds` as well, and that is not duplication.** An attempt is one
event of a formal test taken twice a year; these are training. Feeding a Tuesday morning hold into
the scorecard would report a test that never happened, on the card whose entire value is that its
figures were earned under test conditions.

**Two rules carry the trend, and both look arbitrary until the wrong one is drawn.**

- **The day's figure is its longest hold, never the sum.** Three one-minute planks are not a
  three-minute plank; the single longest is what the AFT scores and what the goal is set against.
  Summed, a day of easy repeats outranks a day that set a record — the exercise measured backwards.
- **A day with no plank is null, not zero — the opposite of `repSeries` beside it.** The difference
  is what the number *is*. A rep count is a quantity accumulated, so no rows means none were done and
  zero is true. A longest hold is a measurement of *capacity*, and nobody's plank capacity fell to
  zero on the days they did not train; it went unmeasured. This is ground rule 6 landing where two
  neighbouring charts need opposite answers. One wanted consequence follows: at the weekly ranges a
  bucket is the mean of the days that *were* measured, so a week with one hard hold reports that hold
  rather than a seventh of it.

It is a line rather than the rep counters' bars for the same reason: bars are a quantity accumulated
over an interval, this is a capacity measured at a moment — the grip-strength shape.

**The middle state of the timer is the feature.** Start, Stop, then *Save this hold* or *Discard*,
with nothing written until one of those is pressed. A hold that went straight to the database on Stop
would put every fumbled start and every plank abandoned at ten seconds onto the chart — and since the
chart plots the day's *best*, a stray row is not noise, it is a personal best nobody performed. A
zero-second hold is dropped without being offered: that is a Start immediately followed by a Stop,
and offering to save it puts a decision in front of somebody who has already said twice that they are
not planking.

**Discard is only half the correction, and the list under the card is the other half.** Discard
catches the mistake noticed in the moment; a hold saved by accident — the phone picked up mid-plank,
a Stop pressed late — is noticed on the chart a day later, and those are the only two ways a wrong
maximum gets in. So the card carries the same correction list every other hand-logged intake here
has: the last `PLANK_EDITABLE_DAYS` (7) of holds, newest first, each tappable to fix a length or a
time and each with a bin. The week is `HYDRATION_EDITABLE_DAYS`' figure and its argument — a wrong
entry is spotted a day or two later from a figure that looks too high.

**It matters more here than for a drink, and that is worth being precise about.** A stray hydration
row inflates a *total*, which is wrong by one glass and dilutes as the day goes on. A stray plank
becomes that day's **maximum**, which is wrong by however long it was and never averages away with
the days either side of it. That asymmetry is the whole case for the list existing.

It reuses `IntakeEntryDialog` rather than growing a dialog of its own: a plank is an amount and a
time exactly as a drink is, and the traps are the ones that dialog already owns — clamping a saved
time to now, refusing a future date, rebuilding the `Instant` in the right zone. The seconds range
stops at 3,600, the point at which a figure is a mis-entry rather than an achievement.

`updatePlank` rewrites the row **in place, keyed on its id**, rather than deleting and re-inserting.
Both would work, which is why it is spelled out: on a chart that plots a maximum, a correction that
briefly leaves two rows in the table is a maximum nobody held. Deletes are for real rather than
hidden, `HydrationEntry`'s rule — a plank is hand-timed end to end, so there is no upstream record to
arrive again and nothing for a `hidden` flag to keep out.

Deleting a day's only hold returns it to **null, not zero**: it goes back to being a day nobody
planked, which is what the trend has to break on rather than draw at the floor.

**The timer state lives in `WellnessViewModel`, and its ticker runs only while a plank is running.**
Held in the card it would not survive a tab switch, which is the one moment this control cannot
afford to lose. But an unconditional ticker would make Log the third permanently un-idle screen in
this app, and this file records what that costs — a screen that never reaches idle cannot be scrolled
in a test, which is why the sleep and mood cards are composed on their own. `plank` is therefore its
own flow, `flatMapLatest` over the start instant: it emits once and stops whenever nothing is
running, so Log stays idle for every test that is not deliberately holding a plank, and none is.
`flatMapLatest` rather than a flag-guarded loop so a second Start cancels the first ticker instead of
leaving it running behind the new one.

That state is `PlankCardState` and **was `PlankTimerState` for one commit**, renamed when the
correction list arrived and it stopped being only a timer. This file's own rule: name a thing after
what it now is, not after what it was when it was written — `WellnessViewModel` is the precedent.

`Units.formatHold` exists because `formatDuration` floors to whole minutes and renders every hold
between one and two minutes as `1m`. It is deliberately **not** shared with `formatPace`, whose
output is identical today: a pace and a hold are different quantities that happen to agree on a
format, and the first of them to want an hours field would silently reformat the other.

The Settings stepper opens on the reader's **own AFT 60-point row** where the profile can supply one
(`AftScoring.minimumFor`), which is `AftCard`'s argument for its own steppers, and prints that figure
underneath. The goal itself is null until set, for `heartRateReferenceBpm`'s reason: the figure worth
aiming at depends on an age and a sex this app is not always told, so it says theirs rather than
seeding a guess.

`MIGRATION_24_25` is `MIGRATION_5_6`'s combination — a new table *and* an `ALTER TABLE` column — and
that is what makes it easy to get wrong: **it reads as a "new table" migration and alters an existing
one on its third line.** The table's DDL is diffed directly; the goal column joins the `UserGoals`
replay in both of that table's existing tests, rather than taking one of its own.

### Scoring the food logging

`domain/FoodLogConfidence.kt` is five named levels, 1 to 5, stored as an integer on
`DailyLog.foodLogConfidence`. Set on Log under the meal list, echoed as a chip on the Today strip,
and carried into the CSV export.

**Self-rated, never derived, and the refusal is the design** — `Readiness`' argument arriving at a
different table. The app can see whether meals exist, whether their macros are filled in and whether
the day's intake is implausibly low, and none of that answers the question asked. A day of restaurant
meals entered from memory is *complete*: every meal present, every macro filled, nothing the app
holds says otherwise, and every gram of it is a guess. Only the person who ate it knows, so only they
can score it. A derived figure would measure completeness and be read as accuracy.

It is also deliberately **not** blended with anything. That keeps it on the right side of the
composite-score rule: this is one person's answer to one question, stored as given, rather than
several things measured differently combined under weights nobody published.

**On `DailyLog` rather than `HealthDaySnapshot`, and the two-tables rule decides it** rather than
taste. It is a judgement *about* the nutrition cache, not a figure read from Health Connect, so it
has to survive that cache being deleted and re-synced — which is precisely what the snapshot promises
not to do. Subjective, hand-entered, one per day: `vibe`'s shape, in `vibe`'s table.

**Null is unrated, never "badly logged".** A day nobody scored and a day scored 1 are different
statements, and collapsing them would drop every day predating the column to the bottom of a scale
whose whole purpose is being filtered on. That is also why the chips clear: tapping the level already
selected returns the day to unrated, which is the only way back once one has been touched.

**Five named levels rather than a 1-10 slider**, which is why this card does not reuse
`LabeledSlider` like the mood scores beside it. A vibe of 7 is read back the same day by the person
who set it; this figure is read in a spreadsheet next February, where *"everything logged as it
happened, portions eyeballed"* survives and a bare 3 does not. Five is as many distinctions as anyone
can honestly make about their own logging.

**Stored as the integer, not the enum name**, deliberately against `Converters`' usual habit of
writing enums by `name`. The question the column exists for is *drop everything below a 3*, which is
a comparison in a spreadsheet where a name would be a lookup table the reader has to reconstruct.

**Nothing is filtered by it** — the owner's call, and it is the scope boundary worth knowing before
"finishing" this. No chart drops a low-scoring day, no window excludes one; the figure is recorded
and exported, and the throwing-out happens downstream. Adding a cutoff later means deciding what a
filtered-out day *draws*, which is a null and not a zero (ground rule 6), and every nutrition chart
would need to agree about it.

The CSV export needed no work at all, which is `CsvBackup` earning its design: it reads tables from
`sqlite_master` and columns off the cursor, so a migration's new column appears in the export with
nothing told about it.

### Folding the entry lists

`CardKit.EntryList` draws the newest `ENTRY_PREVIEW_ROWS` (3) of a correctable list and hides the
rest behind a *Show all N*. Four lists use it: hydration, caffeine, creatine and the meal list.

**This is not the card fold and neither replaces the other.** `LocalCardFold` takes a whole card down
to its title row — which also takes away the buttons that log a drink, the day's figure and the
chart. The entry list is the one part of these cards that nobody needs open, so folding it leaves
everything that is actually used in place. A reader who folds the Hydration card wants it out of the
way; a reader who folds its list still wants to log water.

The hydration list is what forced it. It reaches back a **week** deliberately — a stray 100 ml is
spotted a day or two later from a figure looking too high, and a list ending at midnight would offer
the fix only while nobody knew it was needed — but somebody drinking four logged glasses a day is
then handed thirty rows above everything below them on the tab, for a correction made about once a
month. Three rows rather than one because the row this list exists for is nearly always the newest or
within a couple of it; one would put the common correction behind a tap.

**The count rides in the button** — *Show all 31*, not *Show all*. The number of hidden rows is
exactly what the reader is deciding on, and a bare label makes them open it to find out how much they
are opening. Where everything already fits the control is **absent rather than disabled**: these
lists are on the two longest tabs in the app, and a button that cannot do anything is still a line of
the card, which is the height this is trying to give back.

**A header stays above the fold.** Hydration's *Last 7 days* is the sentence that stops three visible
rows being read as today's drinks, next to a figure that *is* today's — so it is drawn by `EntryList`
rather than by the caller above it, where folding the rows would have taken it off exactly the screen
it exists to correct.

State is `rememberSaveable`, not a column. Which way a list is folded is a glance-by-glance
convenience costing one tap to change, unlike the card fold, which is a standing decision about a
tab's shape and is worth `CardOrderEntry.collapsed` and a migration. It survives rotation and process
death, which is as far as it is worth carrying.

### The two-tables rule for daily data

This is the most important invariant to preserve:

- `DailyLog` — purely manual, subjective fields (vibe, energy, focus, sleep quality, pages read).
- `HealthDaySnapshot` — a **cache** of everything read from Health Connect. Safe to delete and
  re-sync; never the source of truth.

Steps, sleep, calories and macros deliberately live only on the snapshot. Do not add a manual
column for anything Health Connect supplies — the split exists to prevent two writable sources of
truth for the same number.

**Steps are the one figure on the snapshot that is not read as a daily total.** `HealthDaySnapshot.steps`
is the sum of that day's `StepBucket` rows, written in the same pass — see *Health Connect* below.
That is not a violation of the rule above but the same rule one level down: there was more than one
source of truth for the day's steps, and this collapses them to one.

Glucose is the exception to the snapshot pattern: it is a time series, not a daily total, so samples
are inserted as `BloodSugarReading` rows. Re-sync safety comes from the unique index on
`externalId` (SQLite treats NULLs as distinct, so manual readings are unaffected).

**A nutrition source may record the date and nothing finer.** Real data from the author's phone had
every `NutritionRecord.startTime` on one fixed time of day — 10:00:00 local, to the second, including
three separate meals on one Tuesday — so every absorption curve was anchored to an hour nobody ate
in. This is not something the app can compute its way out of; the clock time was never written. Two
things follow from it:

- `WellnessUiState.hasClockTime` calls a time of day **shared to the second by two different
  meals** a stamp rather than a measurement. (The meal list is the Log tab's now — its window is a
  fixed last-24-hours, no longer the master graph's; `TodayUiState` keeps only what the graph's meal
  markers need.) Genuine timestamps land on a different second every time; a source that knows only
  the date lands on the same one for ever. Midnight counts unconditionally, since a lone meal at
  exactly `00:00:00` is a date too. The list says "set time" instead of printing a plausible-looking
  clock time. **Do not narrow this back to a midnight check** — that was the first attempt, and the
  phone's 10:00 stamp sails straight past it. The repeat is the signal; the particular hour is not.
- **A stamped meal is corrected with one tap, from three preset chips.** `UserSettings` holds the
  reader's own breakfast, lunch and dinner times (`mealPresets` sorts them through the day and drops
  a repeat), and `MealEntryDialog` offers them **above** the time field — under the 250dp
  `TimePicker` dial this dialog used to carry they were below the fold, which is where the series
  switches were when the legend briefly replaced them. The dial is gone (see *The time field*
  below) and the chips would fit under it now; they stay above it because the order is also the
  order the question is asked in — which meal was this, and only then exactly when. A chip **saves
  as well as sets**: the meal was opened because its time is wrong, and a chip that only moved the
  clock hands would leave the reader a Save button away from the thing they had already said.

  Two rules keep it from being a shortcut to a wrong answer. The chips appear **only where
  `hasClockTime` is false** — on a meal whose time was genuinely recorded they would offer to
  replace a measurement with a habit, which is the wrong direction for every correction here. And a
  preset later than now is **disabled rather than clamped**: Save quietly pulls a future time back to
  this moment, which is right for a picker somebody just dragged and wrong for a chip, where tapping
  "6:30 PM" at lunchtime would log a meal at 12:31 — a wrong write that looks exactly like a right
  one, which is the hydration-tap failure again. `MealEntryDialog` therefore takes `now` as a
  parameter, injected like the view models' `ZoneId` and for the same reason: a dialog reading the
  wall clock can only be tested at the hours it happens to agree with.
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

**Hydration and caffeine are the other side of that rule, and are deleted for real.** Both are
hand-entered end to end with no upstream record to arrive again, so a hidden flag would have nothing
to keep out and would only leave the row to be counted by something that forgot to filter.
`HydrationEditTest` pins the delete *and* a following sync, which is the same guard
`MealDeletionTest` provides from the opposite direction: the two tables must not be made consistent
with each other, and the test is what says so.

**The correction list reaches back a week while the card's total stops at midnight**, and the pair
is the point. A stray tap logs 100 ml, which is also the ordinary dose, so it is spotted a day or
two later from a figure that looks too high — a list ending at midnight would offer the fix only
while nobody yet knew it was needed. Counting those older rows into today's figure would be the same
bug pointing the other way, so `hydrationMl` filters to today exactly as `caffeineTodayMg` does. The
card says "Last 7 days" over the list, because a reader who has just read *Today 17 oz* will take
what follows for today unless told otherwise.

`ui/components/IntakeEntryDialog.kt` serves both. What differs between a dose and a drink is a
title, a step and a unit; what they share is the part with the traps in it — clamping a saved time
to now, refusing a date in the future, rebuilding an `Instant` from the picker in the right zone.
Two copies of that would be two things to fix each time one turned out to be wrong.

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
dominate the database. `StepBucket` is the same idea, and the same argument against the snapshot's
daily total: it cannot say *when* the walking happened.

**`StepBucket` is cached at fifteen minutes — the finest the master graph ever draws — and summed up
for display.** `StepBucket.BUCKET_MINUTES` is the stored resolution; `TodayUiState.stepBucketMinutes`
picks the *display* bucket from the window (fifteen minutes at 3h and 6h, thirty at 12h and 24h, an
hour at 48h and beyond, where a fifteen-minute bar is a hair too thin to see across two days), and
`displaySteps` groups the stored quarters into it. Fifteen divides thirty and sixty, so every display
bar is whole stored buckets and the cache is read once at the finest resolution rather than re-queried
on zoom. The column keeps its original name `hourStartMillis` via `@ColumnInfo` so the rename to
`bucketStartMillis` needed no migration — a fifteen-minute bucket keyed by `hourStartMillis` would
read as a bug. The chart's `barWidth` and its `steps/15m | steps/30m | steps/h` axis label follow the
same figure, so the bar and the rate it quotes never disagree.

`StepBucket` is the one cache that is **deleted before it is rewritten**. An hour's step count can
legitimately fall to zero between syncs — the pinned source changes in Settings, or a duplicate walk
is removed upstream — and an upsert has no way to express "this hour no longer holds what it did".
The delete is bounded by what the read actually returned, so a failed read leaves the cache alone
rather than emptying it.

**It has two writers, and they are no longer allowed to disagree.** `syncTimeSeries` writes it over
the rolling window the master graph draws; `syncHealthData` writes it over one calendar day and then
totals the table into `HealthDaySnapshot.steps`. Both go through the same merged `readStepsByHour`,
so identical slices carry identical values and the overlap is harmless by construction. Before that
it was harmless only when the two happened to have run in the same minute — which is exactly what
made the drift invisible on the days it mattered.

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
Grouping burn figures next to protein/carbs/fat is what originally made Wellness read as intake.

### The time field

`ui/components/TimeField.kt` is the one time control in the app, and it is Material's **typed**
variant (`TimeInput`) rather than the clock dial (`TimePicker`). Five dialogs use it: the two intake
dialogs, the meal dialog, the feeding-window picker on Fuel and the meal presets in Settings.

**The dial cost about 250dp before its AM/PM column**, which is most of a phone dialog, and the bill
had already been paid twice. The meal-preset chips had to be placed above it because underneath they
fell below the fold; and every entry dialog that also carries a date row, an amount stepper and a
delete button was reduced to scrolling to reach its own Save button. Typing the time is about a third
of the height, so the rest of the dialog stays visible while the time is being set.

It is also fewer gestures for what these dialogs are actually for. **Every caller is *correcting* a
time that is already close to right** — a meal the source stamped at 10:00, a drink logged an hour
after it was drunk — so the reader arrives already knowing the four digits they want. The dial costs
two drags and a mode switch to say what typing says outright. That would be the wrong trade for a
control used to *explore* a time; none here is.

Shared rather than copied to the five sites for `IntakeEntryDialog`'s reason: the traps in a time
control are the same everywhere, and five copies are five things to fix each time one of them turns
out to be wrong. `InstantPickerDialog` still splits date from time across two steps — that is about
the `DatePicker`, which is unchanged and genuinely does not fit beside anything.

### Sleep

`HealthDaySnapshot.sleepMinutes` stays exactly where it is and goes on driving the Trends chart and
the sleep goal. `SleepSessionEntry` and `SleepStageEntry` are the *other* question — when, and in
which stage — and they exist for the same reason `StepBucket` sits beside the snapshot's daily step
total: **a daily figure cannot say when.** Adding stage columns to the snapshot would have been the
wrong shape twice over, since a night is not a day and does not belong to one.

**The card walks nights, not dates, and that is why `getSleepNight` takes a count rather than a
date.** `SleepSessionEntry` holds the nights the watch actually wrote, and a weekend on the charger
is simply not rows — so a stepper keyed to dates would spend taps on blank cards to get between the
two nights being compared. `DayStepper` on the Activity card is the other way round for the same
reason: a *date* always exists, whether or not anything was recorded on it, and stepping by rows
there would skip the days the reader is asking about. The two look identical and are indexed
differently on purpose.

The heart rate under the hypnogram follows the night rather than the clock (`getHeartRateBetween`,
padded by a bucket either side). The open-ended `getHeartRateSince` is right for a window anchored
on now and would, on a night three weeks back, fetch every bucket since to draw eight hours of them.

**`SLEEP_DURATION_TOTAL` is the only aggregate `SleepSessionRecord` offers.** There is no per-stage
aggregate, so stage detail can only come from a raw `readRecords`. That is the whole reason this is
a cache of sessions rather than four more numbers on the snapshot.

**Garmin does populate the stages**, which was an open question until the first build reached the
phone — it writes REM, light and deep, and the three add to exactly the duration the aggregate
reports. Two things about the real data are worth knowing before reading a night on screen:

- **Garmin bounds a session to the sleep itself, not to the time in bed.** The first real night
  carried no `AWAKE` stretches at all, so time asleep and time in bed came out identical and the
  trace never reached the top of the hypnogram. That is not the card failing to find the waking; it
  is the source not reporting any. **Do not "fix" the axis by dropping the awake level** on the
  strength of it — a different source, or a worse night, will use it, and the level is what makes the
  other three readable as a scale.
- One night is one night. Neither of these is a law about Garmin, only what the only data available
  actually contained.

**Health Connect matches a session against the filter by its own span**, so a night beginning at
23:00 is not found by a window opening at midnight — asking for today alone returns the second half
of last night as a session that apparently started at 00:00. `readSleepSessions` widens its filter
back by `SLEEP_SESSION_OVERLAP` (18 h) and then filters on genuine overlap, so the widening costs at
most one extra night and never loses the near half of one. `TodayViewModel` widens its query
the same way and for the same reason: at 3h zoom no night is ever *wholly* inside the window, and a
containment test would shade nothing at all on exactly the zoom where knowing you were asleep
explains most of what the other lines are doing.

**Both screens that show sleep have to sync it themselves.** `syncHealthData` fills the snapshot's
`sleepMinutes` and nothing else; the sessions, the stages and the heart rate drawn under them are
written only by `syncTimeSeries`. The Today card was built without that and shipped to the phone
reading "No sleep recorded yet" while the Activity card directly above it displayed 5h 34m for the
very night it claimed not to have — every test passed, because every test seeded `syncTimeSeries` by
hand. `WellnessViewModel.refreshHealth` now calls it over `SLEEP_HEART_RATE_HISTORY`, **the same
span the card queries**: syncing a narrower window than the chart reads leaves the early hours of a
night permanently blank rather than merely late.

**Sleep is the one cache whose upstream revises itself.** A tracker scores a night when it ends and
re-scores it after the morning's processing, and the second scoring routinely has *fewer* stretches
than the first. So stages are deleted per night before being rewritten — the `StepBucket` argument,
biting harder. Scoped to the night rather than to the window, so a failed read of one night cannot
empty another. The session row itself is upserted on its start, which is what makes a re-sync
idempotent.

`SleepStageEntry`'s primary key is the **pair** of session start and stage start. Keyed on the stage
start alone, two overlapping nights — a watch and a phone both recording the same hours — would
silently overwrite each other's stretches and produce one mangled night that looked entirely
plausible.

**Five stages, not four.** `STAGE_TYPE_SLEEPING` is asleep with *no stage named*, which is a
different statement from light sleep; it maps to `SleepStage.ASLEEP`, counts toward total sleep, and
has **no `level`**, so it is never drawn. There is no height on a hypnogram that means "asleep, stage
unknown" without also meaning one of the three named stages. The card reports it as a separate figure
whenever it is non-zero, so the drawn trace and the printed totals can never silently disagree.
`STAGE_TYPE_UNKNOWN` maps to null and is dropped outright: calling it awake would deflate the night
with time nobody said was spent awake, and calling it asleep would inflate it the same way. Dropped,
it belongs to neither total and still sits inside time in bed, which is the only figure that can
honestly account for it. The three awake constants (awake, awake in bed, out of bed) collapse to one,
since the difference between them is about where the body was and nothing here asks that.

**Time asleep is not time in bed**, and the card leads with the former. A night bounded 23:00 to
07:30 with forty minutes of waking in it is a seven-fifty night; reporting the eight and a half would
be flattering rather than accurate, which is the whole reason the stages are worth reading.

**The hypnogram needs no step primitive.** `Sleep.hypnogram` emits *two* points per stretch, one at
each end at that stretch's level, so consecutive stretches share an x and the ordinary line renderer
draws the riser between them. Deep sits at the floor and awake at the ceiling, the way every
published hypnogram is drawn — the trace falling means sleep deepening, and a reader who has seen one
before should not have to check the axis to know which way is down. `SleepTest` pins that ordering,
because nothing else in the app would notice it being reversed.

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

`checkNow` enqueues a one-time run alongside it, fired whenever the limit is set or changed.
WorkManager **will not run periodic work early** — a forced run answers "executed before schedule"
and reschedules — so the hourly job cannot cover the moment the setting is switched on, which is
exactly when the answer is wanted: the limit gets turned on in the afternoon, the only part of the
day the warning is about. It also makes the feature testable at all, which is how the gap was found.

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
keyed on an id nothing can resolve. `WellnessUiState.supplementsTakenCount` intersects rather than
counting tick rows for the same reason -- a stray dose must not read as "3 of 2 taken today".

### Health Connect

Read-only. The manifest declares only `READ_*` permissions and no `WRITE_*`, and it must stay that
way unless explicitly asked. Every field on `HealthDay` is nullable and every metric is fetched
independently with failures swallowed to null — a user who grants steps but denies nutrition must
get a blank macro card, not an empty screen. `HealthPermissionState.GRANTED` means *at least one*
requested permission was granted, not all of them.

The manifest also needs the `<queries>` entry for `com.google.android.apps.healthdata` (package
visibility on Android 13 and below) and the exported `ViewPermissionUsageActivity` alias (the
Android 14+ rationale entry point the platform launches from the system permission screen).

**Steps are merged across writing apps, never summed and never pinned by default.** Several apps
write steps at once — on the author's phone a watch's companion app, the phone's own tracking, and
AllTrails and a fitness app both holding Health Connect access and able to start at any time. Both
obvious readings are wrong, in opposite directions and by thousands:

- **Summing** double-counts. A watch and a phone in the same pocket record the same walk, and an
  unfiltered aggregate adds them. 31 August 2026 came to 13,265 combined against the watch app's own
  12,656, on a day the phone mostly sat on a desk; carried all day it approaches 2×.
- **Pinning** one app drops what only the others saw. **Garmin Connect writes per-minute monitoring
  steps to Health Connect and writes no step records at all for a tracked activity.** Verified
  directly: that same 31 August has a Garmin-written `ExerciseSessionRecord` (running, 22:41–23:16
  CEST) and *zero* Garmin-origin step records inside it, while the phone's own tracking counted
  roughly 7,600 steps through the same window. Pinned to Garmin the app reported 5,607 for a 12,656
  day — and the bitter part is that the Runs card reads the very session whose steps the step count
  is missing.

`domain/StepMerge.kt` is the answer: **for each quarter-hour slice, the maximum across origins.** Two
apps watching the same legs report near-identical figures for a slice, so the larger never
double-counts; an app that saw a stretch nothing else did carries that slice alone, so nothing is
dropped. **Its known under-read is documented and accepted**: a quarter hour in which two origins
recorded *different* walking reports the larger rather than the total. Recovering that would mean
deciding which overlaps are the same walk from two counts and a timestamp, and every rule for doing
so double-counts the ordinary case to rescue the rare one.

**Exactness with the watch app's own total is not reachable and must not be claimed.** Garmin exposes
no local API and does not put its daily total into Health Connect. The goal is the most honest figure
Health Connect can support, clearly derived — expect a few percent, and let the UI say *from Health
Connect* rather than pretend otherwise.

`stepsByPackage` derives the contributing packages from the combined aggregate's own `dataOrigins`
and re-aggregates per source, which is what both the merge and the Settings breakdown are built on,
and `UserSettings.preferredStepsPackage` can still pin one deliberately. Doing any of this by reading
raw `StepsRecord`s is not survivable: the client validates every record as it converts it, a
zero-count step record is rejected outright, and one such record from any installed app throws away
the entire page. Aggregates never construct records, so they are immune. Raw reads elsewhere are
paginated; `readAllRecords` loops `pageToken` because a single day from a watch exceeds one page and
taking only the first would silently undercount.

`readStepsByHour` (named for what it once did — it now slices at fifteen minutes) is **the only step
read in the app.** It slices with `AggregateGroupByDurationRequest` once per contributing origin and
merges the results; one origin short-circuits to the plain combined read. **The window's start is
snapped down to the hour in the local zone before slicing**, because the slicer counts forward from
whatever instant it is handed — an hour boundary is also a fifteen-minute one, so the quarter-hour
slices still land on :00/:15/:30/:45, where a sync begun at 14:37 would otherwise produce buckets no
later sync lines up with and `StepBucket`'s primary key could never overwrite. Health Connect omits a
slice entirely when it has no records in it, so a quarter-hour with no walking arrives as a *hole*,
not a zero — which is why a bar series has to declare its own `barWidth` rather than infer one from
the spacing.

**There is no day-level step aggregate any more, and reintroducing one would be a subtle mistake.**
`HealthDay` carries no `steps` field and `readDay` takes no pinned package. A day-level *max* picks
one origin's entire day — `max(5607, 7658) = 7658` — when the truth is the watch's daytime plus the
phone's evening run. The merge has to happen at the slice and the day has to be summed from the
slices, which is what `syncHealthData` does.

**A pinned source that wrote nothing falls through to the merged series, never to the sum.** That
fallback-to-sum was the app's second documented way of disagreeing with Garmin: on a day the pinned
app was silent it quietly reported every app added together, double-counting every walk two of them
saw, and it looked exactly like an ordinary figure. It is gone from both the daily total and the
sliced read.

Health Connect has no mile-split concept. `bestMileSeconds` is elapsed time divided by distance,
normalised to a mile, over runs of at least a mile — so it is *average pace*, not a PR, and is
labelled as such in the UI.

**A finished day is read again once, and until it was the app undercounted every past day it had.**
`syncHealthData` is keyed to a calendar day and was only ever called with *today*, from the two
screens that sync — so a day's snapshot held whatever Health Connect had at the last moment the app
happened to be open on it, and nothing asked again. On the author's phone that was every one of
thirty-one cached days, written on average **two hours before its own midnight**, with step counts
thousands under what Garmin Connect reported for the same dates. It can only undercount, and it is
silent: a frozen figure looks exactly like a figure.

It was also **half-visible on one screen**. The Activity card reads the snapshot; the chart under it
is drawn from `StepBucket`, which `syncTimeSeries` re-reads over its rolling window — so the two
halves of Today were answering the same question with different numbers, and the backup bears it out
(29 Aug: snapshot 1,819, buckets 9,319).

`TrackerRepository.syncFinishedDay` re-reads a date **until it has been read at least
`FINISHED_DAY_SETTLE` (48 h) past that date's own end**, or whenever nothing is cached at all — a day
nobody opened the app on has no row, which draws as a hole rather than as the day it was.
`resyncFinishedDays` walks the last seven. The guard is the whole design: past the settle window a
day is finished with permanently, and every later refresh costs one indexed row read and stops.
Without it this would be a week of Health Connect round trips on every refresh. Steady-state cost is
**two extra day reads per refresh** — yesterday and the day before — bounded and constant.

**It was read-once-ever for one release, and that was the third fault behind the step mismatch.** A
re-read stamped `syncedAt` past the day's end, so whatever Health Connect happened to hold at that
one moment became the day's figure for the rest of time. A watch that syncs its evening after the
app's first post-midnight open, or a companion app flushing on its own schedule, lands after the
stamp and is invisible for ever; 31 August was re-read at 15:47 the following afternoon, and anything
arriving later that day would never have been seen. The only recovery was the walk-back refresh,
which the reader should not have to know exists. **The Changes API is the right long-term mechanism
and is deliberately out of scope** — token persistence, expiry resweeps and delete handling for a
failure the settle window already covers. Revisit only if writes later than 48 h are ever actually
observed.

`FinishedDaySyncTest` pins both sides of the window — a day read an hour after it ended still
qualifies a day later, one read 49 hours after never qualifies again — plus the steady-state count of
two. The recovered count is reported on the Activity card for `backfillGlucoseGaps`' reason: a figure
that grew by four thousand between two glances, with nothing on screen to say why, is
indistinguishable from one that had been wrong all along.

**Everything older than that week was frozen for ever, and `deepResyncStaleDays` is the catch-up.**
The sweep walks seven days; before this, nothing ever asked about day eight again, so those snapshots
held whatever the last same-day sync happened to see — and every figure fed by the daily cache reads
them: the steps trend, the step-goal streak, the records, the metabolic scatter, the compare card. On
the author's phone the worst was 19 August at 2,043 against 11,083 in its own step buckets.

It **walks dates, not rows**, which is the part that is easy to get wrong: a day the app was never
opened on has no row at all, and a row-driven walk skips exactly the days that are missing. Each date
goes through the same `syncFinishedDay`, so there is one staleness rule rather than two that could
disagree. The **floor** is the oldest date anything says was lived — `min` of the earliest `DailyLog`,
`HealthDaySnapshot` and `StepBucket` — capped at `DEEP_RESYNC_MAX_DAYS` (90), because Health Connect
returns nothing from before thirty days prior to the first permission grant and a walk into that void
is round trips for guaranteed nulls.

**`DEEP_RESYNC_BUDGET` (10) bounds the reads, not the walk**, and the difference is the trap. Giving
up after a run of settled dates looks like the obvious optimisation and *cannot converge*: the walk
heals a prefix of exactly the budget's length per call, so from the second call onward the front of
the walk is a settled run of precisely that length and every later call stops in the same place —
with the guard that makes healed days drop out becoming the thing that keeps the rest frozen. Skipping
the steady-state walk properly wants a persisted cursor, which is a schema change; a hundred indexed
row reads on a background refresh is not worth one.

**A read that comes back empty must not blank a row that has something in it.** Past Health Connect's
horizon every read is nothing, and writing that over a stale-but-real snapshot turns a wrong number
into no number — strictly worse. `HealthDaySnapshot.isBlank` exists for that one rule and should not
be reused as a general "is this day interesting" predicate. **The stamp still advances**: keeping the
values and skipping the write outright would leave `syncedAt` where it was, so the date would qualify
for ever and the catch-up would never converge past the horizon.

`TodayViewModel.refresh` calls it after `resyncFinishedDays` — after, so the days the sweep heals have
already dropped out of the walk — and adds the two counts into the one `daysRecovered` figure. The
reader is being told one thing, how many past days moved under them, and which mechanism found each
is not a distinction they have any use for.

**Changing the step source does not re-open days that have already settled, and that is a decision,
not an omission.** `setPreferredStepsPackage` re-syncs *today*, on the reasoning that the cached
figure came from the old preference — but that reasoning covers every cached day equally, and the
settle window is what stops it being acted on. On the phone this landed on, the sweep and the deep
walk healed 15 August through 29 August while the Garmin pin was still set, so those days settled on
pinned figures. Checked against Garmin Connect's own screen afterwards: every one of them matched it
**exactly** except the two that carried a tracked activity, where the pinned read was 24% and 47%
short (25 Aug 11,446 against 8,659; 24 Aug 6,762 against 3,559). The merged days either side sit
within 3.5% — which is the pattern that confirms the merge from outside: exact agreement wherever no
activity ran, a small honest gap wherever one did.

Invalidating history on a pin change was proposed and **the owner declined it (2026-09-01) — do not
implement it without asking again.** The cost side of the call: one tap in Settings would spend a
bounded burst of Health Connect reads, for an event that in practice hit one reader on exactly two
days. The remedy is the walk-back refresh, which re-reads the shown day unguarded — it was applied to
both days the same evening (24 Aug 3,643 → 6,846, 25 Aug 8,659 → 12,182, each equal to its bucket
sum), so no settled day still carries a pinned undercount. Widening `FINISHED_DAY_SETTLE` to cover
the case stays rejected for the reason above: it would charge every refresh, for ever, for something
that happens when a setting changes.

**The step count and the chart under it can no longer disagree, and that is by construction rather
than by timing.** `syncHealthData` reads the day's merged slices, writes them to `StepBucket`, and
sets `HealthDaySnapshot.steps` to the sum of what the table then holds — one read feeding both. It
was two pipelines with two refresh policies (a day-level aggregate for the card, the rolling
time-series sync for the bars) and they drifted exactly as far apart as the gap between those
policies allowed: 2,043 against 11,083 on 19 August. It also closes a gap the same diagnosis
exposed — `syncFinishedDay` used to refresh a day's snapshot but not its chart, so healing a frozen
day left the bars under it as they were.

**The total is summed from the stored buckets, not from the slice list just read**, and that decides
the read-failed case correctly. An empty read leaves the table alone (the delete is bounded by what
came back), so totalling the read would blank the card over a chart still drawing the very walk it
could not report — the same split, arriving from the one direction the new pipeline could still
produce it.

**Runs are the one Activity chart read live rather than cached.** The Runs card stacks each running
session by the minutes it spent in each heart-rate zone — Easy below 60% of max, Moderate to 75%,
Hard to 90%, Intense at or above it (`domain/RunZones.kt`, boundaries closed at the bottom so a
reading exactly on one lands in the harder zone). The bar's height is minutes, not distance, which is
the choice that lets a short hard interval and a long easy one look as different as they felt.
`TrackerRepository.getRunBreakdowns` reads each `ExerciseSessionRecord` of type running plus its own
heart-rate trace and computes the zones on demand; `TrendsViewModel.runs` re-runs it whenever the
window *or* the max heart rate changes. Cached zones would be wrong the moment the reader edits their
max HR in Settings, and there is no table to migrate or re-key when they do. A sample holds its zone
until the next one, capped at three minutes so a watch paused at a light does not credit the zone it
stopped in; a run with no heart-rate trace simply comes back empty rather than wrong. Max HR falls
back to 220-age, then to 190, when the profile has set none.

**The session read is no longer filtered to running, and that filter was hiding training.** It used
to select `EXERCISE_TYPE_RUNNING` at the source, so an hour under a loaded pack reached the app as
*nothing at all* while a twenty-minute jog got its own bar on the chart. `readExerciseSessions`
(was `readRuns`) now returns every session with its kind, and the two run-specific callers —
`getRunBreakdowns` and `getBestTwoMileSeconds` — filter on `TrainingType.RUN` themselves. One read
rather than two: the alternative was a second query over the same records, and a second thing to keep
in step.

`domain/TrainingVolume.kt` groups a week of them for the Activity card, and
`health/ExerciseTypeMapping.kt` is the Health Connect type to `TrainingType` mapping. The mapping
lives in `health/` because it is the one part that has to know Health Connect's constants and the
domain package is deliberately free of `androidx` imports; it is still a plain function over an `Int`,
so it is unit-tested without a device. **A mis-mapped type is invisible on screen** — the session
still appears, with a correct duration, under the wrong heading, and the row it belonged on is quietly
short. That is why the mapping has a test at all.

**Hiking is rucking here, and the app does not pretend to know that.** Health Connect has no rucking
type and nothing on the record says whether weight was carried, so a ruck arrives as a hike and the
label `Rucks` is the author's reading of their own data rather than something the source said.
Anything unmapped falls through to `OTHER` rather than being dropped: an hour of training that
happened and is not shown is a worse error than an hour shown under a vague heading.

Three rules the card depends on. **Distance is null, never zero, when nothing in a group recorded
one** — a strength session genuinely has no distance, and `0.0 mi` beside real figures is a
measurement nobody made; a group is likewise not credited with a distance because one of its five
sessions had a GPS lock. **Pace is the group's whole time over its whole distance**, not a mean of
per-session paces, or a twenty-minute stroll would weigh as much as a two-hour ruck in the figure
meant to describe the ruck. And **pace is withheld off foot**: minutes per mile is how walking,
running and rucking are compared, but quoting it for cycling invites reading a 4:00 "pace" as a run
and for swimming the unit is wrong twice over. Those types still show distance — only the derived
figure is withheld.

Rows are sorted by time and types with no sessions are absent, because the question is "where did
this week go" and eight rows of nothing would bury the three that happened.

**Glucose is cached a calendar day at a time, and the day sync mostly asks about today**, which is
right for a finished day and wrong for one that was never finished properly: a monitor out of
Bluetooth range writes its readings to Health Connect hours late, by which time nothing asks about
the day they belong to and the hole is permanent. (`resyncFinishedDays` re-reads a finished day once,
which fills a week of these for free — but only once each, and a late write can arrive after it.)
`domain/GlucoseGaps.kt` turns the holes themselves into the
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

### Army Fitness Test

`domain/AftScoring.kt` scores the five AFT events against the Army's published conversion tables, and
`domain/AftTables.kt` is those tables. Source is HQDA EXORD 218-25 (CC) Annex B, effective 1 June
2025, mirrored on goarmy.com — `army.mil` serves the same PDF but blocks everything that is not a
browser, so the goarmy copy is the fetchable one. ATP 7-22.01 carries the standards and the combat
MOS list but explicitly *not* the tables.

**There is no separate combat table, because the published scales do not have one.** Every table has
one `M | C` column and one `F` column per age band: the combat standard is sex-neutral, and the
column it is neutral *to* is the male one. The two lanes therefore differ in exactly two things —
the total required (350 against 300) and which column a woman reads — and never in what a given
performance is worth. Anyone looking for a third set of numbers will not find one.

**It is a step lookup and must never interpolate.** The tables list a minimum performance for each
reachable point value and say nothing about what falls between two steps, so 335 lb earns what 330
earns. Interpolating would award scores the Army has no row for and no scorecard could be checked
against. `higherIsBetter` is per event and genuinely splits both ways — more weight and more reps
are better, a faster sprint-drag-carry and run are better, and a *longer* plank is better even
though it is timed, so reading the direction off the unit gets the plank backwards.

**Two rows of the published run tables are out of order**, and the lookup takes the *highest* point
value a performance qualifies for rather than the first row it matches. Female 47-51 lists 21:45 at
71 points against 21:40 at 70; female 52-56 lists 24:01 at 61 against 24:00 at 60. Both look like an
adjacent pair transposed at the source. Scanning for the best qualifying row is the only reading
that cannot take points away from someone for running faster — and the second erratum straddles the
pass mark, so 24:01 scores 61 and passes. That is what the table says; correcting it here would fail
a Soldier the Army's own scorecard passes. `AftScoringTest` pins both, so a reissued table is
noticed rather than absorbed.

**A null score means the profile cannot place the Soldier, not that they scored nothing.** The
general lane needs a sex and there is no column without one; the combat lane is sex-neutral and
scores an unset profile fine. Age is required either way. Bands clamp at both ends — the table
starts at 17 and its top band is open at 62.

The tables are **split one object per column** because all ten in one initialiser is roughly 13,000
pushed integers and the JVM caps `<clinit>` at 64KB like any other method. Kotlin reports that as
"Method too large" with nothing to say the data caused it.

`AftScoringTest`'s anchors are transcribed **by hand** from the published scale, deliberately not
generated alongside the tables: a test built from the same extraction as the thing it checks agrees
with itself however wrong both are. One anchor per event at 100, at the 60-point pass mark, and mid
-scale, since a table shifted by a row still gets 100 right and one read from the wrong column still
gets the shape right.

**Nothing about a score is ever stored.** `AftAttempt` holds only the five raw results and the date;
every point value, total and verdict is recomputed on read by `AftScoring.scorecard`. The profile it
is scored through moves — a birthday changes the age band, sex may be filled in after the first
attempt was logged, and the lane is a setting the reader can flip — so a stored score is a claim
about a profile that has since changed, and nothing on screen could tell a stale one from a current
one. Flipping the lane in Settings re-scores every past attempt in place, which is the behaviour
that makes the card worth trusting. The recomputation is a scan of a few dozen integers.

The five raw columns are **all nullable**, because the events are done in order over two hours and a
test can genuinely stop halfway. That is why `AftScorecard.passes` is a `Boolean?`: an attempt three
events in is unfinished, not failed, and rendering the two the same would be the card's worst
possible lie. `isComplete` is what separates them, and only complete attempts are plotted on the
trend — a part-logged day would otherwise draw as a collapse in fitness.

**The deadlift is stored in kilograms and scored in pounds.** Storage stays metric like every other
weight here; the table is published in pounds and is entirely in tens of them, so the entry stepper
moves in 10 lb steps and the kilogram value always converts back to a clean multiple of ten. A
stepper in kilograms would land between two published rows and quietly score the row below.

**The card is on Activity and its trend ignores `TrendsRange`.** A record test happens about twice a
year, so a 7-to-90-day window would show one attempt or none; the span the chart needs is the
attempts themselves, which is why `TrendsViewModel.aft` is its own flow rather than part of
`uiState`. Its axis floor is 250 rather than 0 for the same reason the glucose plot stops at 180: a
finished test is five events at 60 or better, so the bottom half of a 0-500 axis is space no point
ever occupies, and spending it flattens the range that actually moves. The 300 and 350 rules are
dashed because they are published figures, the same rule that keeps 120/80 dashed and a self-chosen
glucose reference solid. Both lanes' rules are drawn even though only one applies, since the
distance to the other is exactly what somebody changing lanes is asking about.

**The projected two-mile score is the one figure on the card nobody performed**, and it says so
twice. `domain/RunPace.kt` normalises a run's elapsed time to two miles and
`TrackerRepository.getBestTwoMileSeconds` takes the quickest over the last 90 days; the card prints
it as a projection with the reason underneath. Health Connect records a session's total distance and
its bounds and nothing about the pace inside, so this is an average over a whole run rather than a
two-mile effort — a run with a warmup reads slower than the runner is, an interval session slower
still. It is deliberately **not** drawn in the column of scored events, where it would read as a
sixth result.

**A run shorter than two miles projects nothing rather than being extrapolated up.** That is the
rule the whole thing hangs on: a hard mile scaled to two produces a confident score for a distance
nobody ran, and on this card it would be indistinguishable from one that was earned. Runs with no
recorded distance are the same — null, not zero. Read live and never cached, for the reason the runs
chart is: a cached best from the spring is not a projection, it is a memory, and it would sit there
looking exactly like the real thing.

The entry steppers open on **that event's own 60-point requirement** rather than on zero
(`AftScoring.minimumFor`). The run is over a thousand seconds and the deadlift over a hundred
pounds, so zero is a long way from anywhere useful, and the pass mark is the figure being aimed at
anyway. Each stepper shows the points its current value would earn as it moves, which is the only
question being asked while entering a plank time.

### CGM summary metrics

`domain/GlucoseMetrics.kt` computes the standard continuous-monitoring figures over a window:
time in/below/above range, mean, standard deviation, GMI (`3.31 + 0.02392 × mean`) and coefficient
of variation. All of them are read-time computations over the cached `BloodSugarReading` rows —
nothing is stored, because nothing here is expensive and a stored summary is one that has to be
invalidated when the target band moves.

**Every one of these is a proportion, and that is what makes the coverage gate the important part.**
A fragment of a day produces a perfectly well-formed time-in-range that is simply about a different
span than the one asked for, and nothing on screen can tell the two apart — a morning of readings
after a sensor change would otherwise report a figure for the whole day. `GlucoseAnalysis.over`
returns **null** below `MIN_COVERAGE` (70%, the consensus minimum for calling a summary
representative), which is ground rule 6 in its most literal form: a day without coverage has no
time-in-range, and null is not zero.

**Coverage is judged on the span the readings occupy, not on counting them against an assumed
cadence.** Sensors differ, warm up, and are read by hand as well, so a count-based gate would be a
gate on owning a particular device. Four fingersticks spread across a day have genuinely sampled it
and are reported; two hundred readings crammed into one morning are not and are refused.

Two details that decide real numbers. The band's own edges **count as in range** — an exclusive
comparison would report a reading exactly on target as an excursion. And the standard deviation is
the **population** one, not the sample: these are all the readings there are for the window, not a
sample drawn from more of them.

`coefficientOfVariation` is reported beside the mean rather than instead of it because it answers a
question the mean actively hides. A trace swinging 55 to 165 averages a respectable 110 with a CV of
fifty per cent and **not one reading in range** — the mean is a number that never occurred. The
consensus ceiling for a stable trace is 36%, on `GlucoseMetrics.STABLE_CV_PERCENT`.

GMI is an estimate from sensor data and is presented as a model: close enough to plan against, not
close enough to argue with a lab result about.

**Both windows on the Fuel card end at *now*, not at the end of the day or week**, and that is what
makes the coverage gate usable rather than merely correct. Measured against a whole day a monitored
morning covers a third of it and would report nothing until evening; measured against the day *so
far* it says what it can honestly say and still refuses once the sensor has genuinely been off. The
week's first day comes from `UserSettings.weekStartsOn`, which until this card was a setting nothing
read.

`GlucoseReportState.hasAnyReadings` separates the two ways the card can be empty: "the sensor has
not covered enough of today" and "there is no CGM here at all". They want different sentences, and
the first is temporary while the second is not.

The card shows four figures rather than one because each hides what the others show — a good mean
can be the average of a trace that was never once in range, and a good time-in-range can still swing.
The below/above line appears only when something was outside the band; on a clean window it would be
two zeroes taking a row to report that nothing happened.

### Per-meal glucose response

`domain/MealResponse.kt` scores what one meal did to the blood sugar: baseline, peak rise, incremental
area and time back to baseline. Read-time only, over rows already on disk — a stored response would
have to be invalidated every time a meal's time is corrected, which on this data happens routinely.

**The baseline is a median, not a mean**, over the half hour before the meal. One compression low or
a stray fingerstick in that window would drag a mean down and inflate *everything* measured against
it at once — the rise, the area and the apparent return. Six readings shrug off one outlier.

**The area is incremental, and negatives are clamped rather than subtracted.** Only what stands above
the baseline counts, so a meal is scored on what it added rather than on how high the trace already
was — eating at 140 would otherwise outscore the same meal eaten at 90 for nothing the meal did. A
dip below baseline contributes zero instead of cancelling an earlier rise: a meal that spiked and
then undershot has still spiked.

**The reading taken exactly at the meal is both the last pre-meal value and the first point of the
trapezoid.** That is not double-counting — it is the level the meal started from, which is what both
uses are about.

**The ranking sorts on area, not peak rise**, and the author's own data is the argument: the biggest
peak in the last fortnight (+56, a 187 g carbohydrate dinner) ranks *second* on area behind a +51
that stayed up longer. A sharp spike that clears in forty minutes and a smaller rise that sits there
for two hours can share a peak, and the second is usually the one worth finding. Both figures are
shown so neither has to stand for the other.

**Unscored meals are absent from the ranking rather than sorted to the bottom.** A ranking lists
things that were measured, and "no CGM cover" is not a small response.

`observedFor` sits beside `returnToBaseline` so a null there can be read correctly. "Still up after
three hours" and "the sensor stopped after forty minutes" are different statements, and with only the
null they would print the same sentence — so the meal row drops the return clause entirely when the
sensor stopped inside the cap, the same rule that stops a line being drawn across a gap.

**A stamped meal is unscored, and the window the stamp is judged over is load-bearing.** This is the
one thing here that was got wrong first and caught on the phone. `hasClockTime` is a *repeat*
detector, so it only sees a repeat inside the meals it is handed — and the source stamps 10:00:00
about once a day. Judged over the 24 hours the Log list displays, the single 10:00 meal in that span
had nothing to repeat against, was read as a measurement, and printed a rise and a return measured
from an hour nobody ate in. `MEAL_STAMP_HISTORY_DAYS` (14) is the fix: **load wider than you display,
because the judgement needs more than the picture does** — the same shape as the absorption curves
reaching past the left edge. The list still stops at `MEAL_WINDOW_HOURS`, and so does the merged-
records count printed under it, which had to be re-scoped to the window when the load widened or it
would have claimed credit for merges the reader cannot see.

`domain/MealClockTimes.kt` holds that rule now, because two screens ask it over different windows and
two copies would eventually disagree — and the disagreement would look like a scoring bug rather than
a definition drifting.

### The finer nutrition figures

Fiber, sugar, saturated fat and sodium are read from `NutritionRecord` onto **both**
`HealthDaySnapshot` (day totals) and `MealEntry` (per meal). Per-meal as well as per-day is not
redundancy, and it is the same argument `MealEntry`'s macros already make: a daily fiber total cannot
say *which* meal carried it, and the question this exists for is whether the meals with fiber in them
are the ones with the flatter response — a comparison that needs both numbers on one row.

**They are components of the macros, not additions to them.** Fiber and sugar are part of
`carbGrams`; saturated fat is part of `fatGrams`. Never sum them with the three and never stack them
beside them on a chart — either counts the same grams twice. The author's own first day of real data
is the check: fiber 8.4 g plus sugar 37.0 g against 91.8 g of carbohydrate, and saturated fat 37.3 g
inside 105.5 g of fat. Both fit, and a design that added them would have reported a day that ate more
carbohydrate than it ate. The screen says so out loud — the sugar and saturated-fat cells are
captioned *of carbs* and *of fat*, because a reader who has just read "Carbs 91g" will otherwise add
them.

Sodium is stored in **milligrams**, not grams: every label and every guideline uses mg, and grams
would put every real figure between 0.002 and 0.004 and make the column unreadable in a CSV export.

`MIGRATION_19_20` adds all eight columns in one migration across two tables, like `MIGRATION_5_6`.
They are the same four figures read from the same record in the same sync, and splitting them across
two versions would leave a release where a meal knows its fiber and the day does not. **None carries
a default** — the `MIGRATION_11_12` shape — because every row already on disk was synced before these
were read, so NULL is the true statement about all of them. A zero would claim the food contained
none, which is a different thing and would average into any window figure computed later.

One consequence worth knowing: **the per-meal columns fill in going forward, not retroactively.**
Meals are only ever inserted, never updated, so the meals already on disk keep their NULLs and only
newly synced ones carry the new figures. The day totals have no such limit — the snapshot is a cache
and is rewritten on every sync, so today's row gained all four the moment the build landed.

**Sodium rides the blood-pressure chart on a scale of its own**, and the caption says it is context
rather than a cause: it is a daily total against readings taken at a moment, and one day's salt does
not move one morning's pressure. Days with no figure are dropped rather than plotted at zero — a day
nobody logged food on did not contain no salt.

That series is also where a colour bug got caught on the phone. It was first drawn in
`chartColors.carbStack`, which in the **dark scheme is the same hex as `diastolic`** — two series on
one plot in one colour, with a legend claiming they were different things. `ChartColors` now carries
its own `sodium`. **Reusing a series colour is only safe if you have checked both schemes**, since
the dark set collapses several hues the light set keeps apart.

### Morning readiness, and blood oxygen

`domain/Readiness.kt` is **two facts, never a score**, and the refusal is the design. Every wearable
ships a composite readiness number that blends things measured in different units, on different
confidences, with weights nobody publishes — and the result cannot be argued with. Told "readiness
61" there is nothing to check and nothing to do; told "resting heart rate 6 bpm over baseline, 5h 40m
asleep" the reader knows which half moved and whether they believe it.

**The two facts are independently absent**, which a single score could not manage: a night with no
sleep still reports the heart rate, and a phone with sleep but no resting heart rate still reports the
sleep. `hasAnything` keys on the raw readings rather than on the deltas, and that distinction is a
bug this caught: keyed on `restingDeltaBpm` the card claims nothing was recorded on exactly the
mornings where something was, for the first ten days of use, because the baseline is not there yet.

**The baseline excludes today.** Comparing a morning against a median it is itself inside pulls the
baseline toward it, so a genuinely high morning reads as less high than it is — and on a short window
the effect is big enough to hide the thing the line exists to show. It is a **median** rather than a
mean, because resting heart rate has outliers with nothing to do with fitness (an illness, a late
meal, a night the watch sat badly) and a mean carries one into the baseline for a month afterwards.
Below `MIN_BASELINE_DAYS` (10) there is **no baseline at all** rather than a confident-looking one —
a median of two mornings is two mornings, and the comparison would read exactly like a comparison
against a month.

The reading is still printed when the baseline is missing: it is a measurement, and what is absent is
the thing to compare it with. Only the wrong direction is coloured, because a green line every
ordinary morning turns the colour into decoration and then the one morning it means something reads
as decoration too.

It costs **no sync**. Everything comes off snapshots the daily read has been writing all along plus
one settings row, which is what lets the line be there the moment the screen opens. Its window is a
fixed thirty days and deliberately outside `TrendsRange`: at the 7-day chip there would not be enough
history to have a baseline, and a line that vanished when the reader moved a chart's range chip would
look broken rather than principled.

**Sleep is attributed to the wake day**, which is what the rest of the app already assumes and is
confirmed by the real data: a session running 00:45 to 06:41 lands wholly on that date's snapshot, so
`sleepByDay[today]` is last night. Only the post-midnight portion of a night that starts before
midnight counts toward the earlier day, and it never dominates.

**SpO2 is read, and on this phone it never arrives. Do not spend time trying to make it.** The read
was added on the belief that Garmin syncs blood oxygen where it does not sync HRV status, Body
Battery, stress or VO2 max. That belief was wrong and was checked properly only after the code
landed: Health Connect's own data browser has **no oxygen-saturation type at all**, and Garmin
Connect's write permissions read *Vitals: 2 of 2 selected* — everything it asks for is granted, and
blood oxygen is not among what it asks for. Those two agree, so SpO2 belongs on the same list as Body
Battery rather than apart from it.

**Enabling Pulse Ox on the watch does not fix it**, which is the trap worth writing down: even with
the watch recording, Garmin Connect would still have to request the Health Connect write permission,
and it does not. There is nothing on this side of the boundary to change.

The code stays because it costs nothing while the column is NULL — the chart simply does not draw,
and it will light up on its own if Garmin ever adds the permission. Treat it as dormant rather than
broken, and **do not "fix" it by seeding a default or by inventing a fallback source.**

`readMeanSpo2` reads raw records and averages them because Health Connect defines **no aggregate
metric** for `OxygenSaturationRecord`, unlike heart rate or steps. That would have been cheap in
practice, since a watch writes a handful of spot readings a night rather than a sample every few
seconds.

`MIGRATION_18_19` adds `HealthDaySnapshot.spo2Percent` with **no SQLite default** — the
`MIGRATION_11_12` shape, not the `MIGRATION_5_6` one. A default would write a health measurement
nobody took onto every day already on disk, which is worse than a blank chart: a chart of identical
values looks like a working sensor reporting a very stable night. It is REAL rather than INTEGER
because it is a mean, and rounding 95.4 to 95 at the storage boundary throws away the only resolution
a slow drift would show up in. The chart is bounded 90-100 for the reason the glucose plot stops at
180: below ninety is a medical event rather than a trend, and nine tenths of a full-range axis is
space no point occupies.

**A metric added in a later version needs its permission asked for again**, and this is the case
`missingPermissions` exists for. On the author's phone the new read landed as *"Not yet allowed to
read: oxygen saturation. Those metrics stay blank until granted"* with a Grant button on Wellness —
which is the whole point of tracking missing permissions separately from `GRANTED`, since the app was
already connected and would otherwise have reported itself healthy while the column stayed empty for
ever. Granting it cleared the banner immediately, so that half is verified — **a granted permission
and an arriving metric are two different things, and this is the case that separates them.**

### Body composition

`domain/BodyComposition.kt` is the military body composition screen, and it is **waist over height,
under 0.55** — nothing else. Source is the Secretary of War's 30 September 2025 direction on
fitness standards and the Under Secretary's follow-up memorandum of 18 December 2025 setting
1 January 2026 as the implementation date across the Joint Force.

**Height and weight tables are retired, not supplemented.** Anything here that reaches for an
AR 600-9 screening table is reaching for a standard that no longer exists. There is no table, no age
bracket and no sex column — which is why this is the one scored thing in the app that works on a
profile that has declined to give a sex, and why it needs only two tape measurements.

Two rules decide real pass/fails and are easy to lose:

- **Both measurements are recorded in inches, floored to the half.** Down, not to nearest, and
  applied to *both* — which pulls the ratio in opposite directions, since flooring the waist shrinks
  the numerator and flooring the height shrinks the denominator. Rounding either the convenient way,
  or flooring only the waist because that is the measurement people think of as needing it, moves
  verdicts.
- **The limit is strictly less than 0.55.** Exactly 0.55 is over. A `<=` passes somebody the
  standard fails, on precisely the value a reader is most likely to check by hand.

`recordedInches` carries a thousandth-of-an-inch tolerance into its floor, and it is not a second
rounding. Everything is stored in centimetres, so an exact 42.5 inches comes back out of `Float` as
42.4999988 and floors to 42.0 — half an inch lost to arithmetic, in the direction that flatters the
reader. Snapping to a quarter first was the first attempt and is wrong for a different reason: it
*rounds*, so a genuine 75.4 comes back 75.5, which is the one thing "rounded down" rules out. The
test that caught that is the one worth keeping.

The screen is shown as a **waist rather than as a ratio** wherever a chart is involved: at a fixed
height the limit is a horizontal line, and a line the tape can be read against beats a number that
has to be divided. `maxPassingWaistInches` is the largest half inch strictly under `0.55 × height`
— 41.0 at a height of 75, because 41.5 divides to 0.5533 and fails. Quoting the raw threshold of
41.25 would name a measurement no tape is ever read to. On the waist trend it is drawn as a
`subGoalLine`, hairline and finer-dashed, because the reader's chosen waist goal above it is where
they are going and this is only where the standard stops.

**The ratio is the whole assessment.** Earlier drafts of this section described a body-fat tape test
following a failed screen; there is no such follow-on, and nothing here should grow one. The screen
is two measurements and a comparison, start to finish.

### Room

Version 25, `exportSchema = false`. **Write a real `Migration` for any schema change** — there is
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

`MIGRATION_12_13` adds the two sleep tables, and is the worked example of the trap the schema test
exists for: the first draft wrote `` `startMillis` INTEGER PRIMARY KEY NOT NULL `` inline, and Room
generates the constraint form `PRIMARY KEY(`startMillis`)` even for a single-column key. Room
compares the two schemas as *text*, so that would not have failed a test — it would have thrown on
the next launch for anyone upgrading. **Write the table-constraint form for every primary key**,
single-column ones included.

`MIGRATION_13_14` adds the personal profile to `UserSettings` — `maxHeartRateBpm`, `ageYears`,
`heightCm` (all nullable) and `sex` (non-null, `DEFAULT 'UNSPECIFIED'`, the same `NOT NULL`-with-
seeded-default shape `smoothGlucose` used). Max heart rate is the load-bearing one: it zones the runs
chart on Activity, and is entered rather than derived from age because 220-minus-age is a population
average and anyone who has seen their own on a hard effort knows it better. The nullable three read
as an unset profile — no made-up figure on an upgrading user.

`MIGRATION_14_15` adds `CardOrderEntry`, a new table keyed on the pair `(tab, cardId)` holding a
saved card position per tab. New table, so the DDL is Room's own `CREATE TABLE` for the composite
key — no `ALTER TABLE` default to keep in step. Empty means every tab in its built-in order, so an
upgrading user sees no change until they move something.

`MIGRATION_15_16` adds `AftAttempt`, one row per Army Fitness Test. New table, so the DDL is Room's
own. Two things about its shape are decisions rather than defaults. **Every event column is
nullable**, because a test day is five events over two hours and may be logged as it goes or stopped
partway — a missing event has to stay distinguishable from a zero, which is a real score. And the
**index on `date` is deliberately not unique**: a retest is a second attempt rather than a correction
of the first, so a unique index would refuse the insert on exactly the day it matters. The row is
keyed on a generated id for the same reason — keyed on the date, a retest would overwrite the
morning's attempt and a record of progress that overwrites itself is not one.

The deadlift is stored in kilograms like every other weight here even though the event and its
scoring table are both in pounds, and `Units.kgToWholeLbs` is the one-way door back. It rounds
rather than truncates for the reason `mlToWholeOz` does, with more riding on it: 150 lb round-trips
through `Float` as 149.99999, and truncating would read the pass mark as a failure. `AftAttemptTest`
walks every ten-pound step of the published table through storage and back to prove it.

`MIGRATION_16_17` adds `UserSettings.aftLane`, the fifth alteration to that table and the third
`NOT NULL`-with-a-seeded-default on it, after `smoothGlucose` and `sex`. It seeds `'GENERAL'`
because there is no such thing as being on neither standard, and because that is the safer of the
two to guess wrong: it scores a combat-MOS Soldier a little generously on the total, where guessing
the other way would tell everyone else they had failed a test they passed. It joins the UserSettings
replay in `MigrationSchemaTest` rather than taking a test of its own — that table is altered five
times now and only the full replay catches a gap.

`MIGRATION_17_18` adds the three meal-time presets to `UserSettings`, the sixth alteration to that
table. They are **INTEGER columns, not TEXT**: `Converters` stores a `LocalTime` as its second of
day, so the seeded defaults are `23400`, `43200` and `66600` rather than anything that reads as a
clock time. Writing them as TEXT is the exact mismatch the schema replay exists to catch, and it
would not fail a build — it would throw on the next launch for anyone upgrading.

They carry SQLite defaults for the `MIGRATION_5_6` reason rather than the `MIGRATION_11_12` one, and
the pair is worth holding together because the two look identical from the migration alone. The
bedtime caffeine limit could arrive NULL because it drives a *notification*, and a default there
means interrupting an upgrading user with something never asked for. These drive something drawn on
screen that ships pre-filled, so a NULL would render three chips with no times on them — a feature
that looks broken on exactly the phones with data worth migrating. **Ask what a NULL would do on
screen, not whether the column is new.**

`MIGRATION_18_19` adds `HealthDaySnapshot.spo2Percent` and is the same question answered the other
way — see *Morning readiness, and blood oxygen* for why it carries no default. It is the **first
alteration to `HealthDaySnapshot` since `MIGRATION_2_3`**, whose statements are inline rather than in
a `val`, so its schema test builds a hand-written v18 version of that table and replays 18-to-19
against it rather than replaying from v2. What that pins is the column *type*: declared INTEGER
against Room's REAL, it passes every other test in the suite and throws on the next launch.

`MIGRATION_20_21` adds `CardOrderEntry.collapsed`, and is the case where the *existing* schema test
had to change rather than a new one being added: that table is created by `MIGRATION_14_15` and
altered here, so diffing the `CREATE TABLE` alone would go on passing while an upgrading reader's app
refused to open. It now replays 14-to-15 then 20-to-21 and compares `PRAGMA table_info`, the MealEntry
shape. **A new migration that alters an existing table means finding that table's existing test, not
writing a fresh one.**

`MIGRATION_21_22` adds `UserSettings.themeMode`, the **seventh** alteration to that table and the
fourth `NOT NULL`-with-a-seeded-default on it after `smoothGlucose`, `sex` and `aftLane`. TEXT,
because `Converters` stores an enum by `name` — the meal presets are the same trap pointing the
other way, where a column that reads as a time is an INTEGER. It joins the UserSettings replay in
`MigrationSchemaTest`, in **both** of that table's tests, rather than taking one of its own.

Its seed is `'SYSTEM'`, and the `MIGRATION_17_18` question — *what would a NULL do on screen* —
answers it in one step. This column decides the colours of the first frame, so there is no reading of
NULL that draws nothing; whatever it meant would still have to be a scheme. Seeding it with the
behaviour that shipped before the column existed is the only value that leaves an upgrading reader's
app looking exactly as they left it.

`MIGRATION_23_24` adds `DailyLog.foodLogConfidence`, and is the **first alteration `DailyLog` has
ever had** — that table was rebuilt wholesale by the v1-to-v2 destructive fallback and untouched
since. So unlike every other recent migration, there was no existing schema test to add a replay to;
it needed one written. **A table with no schema test is not a table that cannot break** — it is one
whose breakage first appears as an app that will not open.

Nullable with no default, the `MIGRATION_11_12` shape, and the sharpest case for it in this file. A
seeded value would be a judgement nobody made, written onto every day already on disk — and this is
the column that exists to be *filtered on*, so seeding it would push every historical day either into
or out of the reader's next analysis on the strength of a number the app picked for them. INTEGER
rather than the enum's usual TEXT; see *Scoring the food logging*.

`MIGRATION_22_23` adds the heart-rate axis to `UserGoals` — the fifth alteration to that table — and
is the one migration here carrying **both default policies at once**. The two plot bounds are seeded
with the exact figures the axis was hard-coded at; the reference rule carries none. It is the
clearest worked example of *ask what a NULL would do on screen, not whether the column is new*,
because the same migration answers that question both ways in three lines. See *Units and theme* for
the reasoning, and for why there is no target band to go with it.

`MIGRATION_19_20` adds the four finer nutrition figures to `HealthDaySnapshot` *and* `MealEntry` — see
*The finer nutrition figures*. Because it alters `MealEntry`, **both** of that table's schema tests
had to gain it: the column diff and the `hidden`-column test each rebuild the table from its own
starting point and replay forward, and a migration missing from either is a gap that only shows on a
reader's phone. The second now also asserts that `hidden` arrives seeded to 0 while `fiberGrams`
arrives NULL, which is the two default policies sitting side by side in one row.

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

`domain/Glucose.kt`, `domain/Ketones.kt` and `domain/HeartRate.kt` own their axes for the same
reason: the entry stepper, the Wellness chart, the master graph and the settings target all have to
agree on what the scale means, and four copies of the numbers drift. The glucose plot is
**60–180 mg/dL**, not 60–200 —
the top fifth of a 200 ceiling is never reached and spending it flattens the 30 mg/dL swing around a
meal into a wiggle. Both charts still widen an axis to fit an outlier, so a 210 reading plots; it is
simply not budgeted for.

**`HeartRate` is `Glucose`'s shape applied to the master graph's other configurable axis**, and it
stops one step short of it. Floor, ceiling and a solid reference rule are settings; there is
deliberately **no target band**, because heart rate's only plot is the master graph and that plot
removed its own glucose band for a reason that would recur here exactly — a band backs one series,
this plot carries eight, and shaded behind carbohydrate curves and step columns it stops reading as a
target and starts reading as a region of the chart. The rule is the half that survives on a busy
plot.

Its two defaults follow **different policies in one migration**, and both come from
`MIGRATION_17_18`'s question rather than from whether the column is new. The plot bounds are seeded
40 and 180, which are *exactly the figures that axis was hard-coded at* — `MIGRATION_8_9` repeating,
where a NULL would visibly rescale an existing reader's chart, and changing what a chart looks like
is the one thing turning a constant into a setting must not do. The reference rule carries **no**
default, the `MIGRATION_11_12` shape, because nothing was ever drawn on that axis: NULL draws what is
drawn today, and a seeded value would put a line on the chart of every reader who never asked for
one. `MIN_PLOT_SPAN` is 30 rather than glucose's 20, since the quantity moves further — a heart rate
covers 50 bpm between sitting and a brisk walk.

**The master graph opens on three series, not eight** (`DEFAULT_VISIBLE_SERIES`: glucose, heart rate,
steps). The switches are unchanged and still reach the rest in one tap; what moved is which end the
reader starts from. Eight series at once is a legible chart of nothing in particular — the macro
curves, the caffeine decay and the ketone trace each answer a question that was not asked on opening
the app, and together they bury the two that were: why is the heart rate up, and what moved the
glucose.

**`labelledAxes` had to move with it**, and this is the part that would have been missed: the default
pairing was glucose against macros, which was right while all eight were drawn and prints a g/h
gutter beside three switched-off curves once they are not. An axis labelling nothing on the plot is
worse than an unlabelled one, because it still looks like a reading. `AxisSelectionTest` now asserts
the general rule — *every labelled axis has a visible line under it by default* — rather than the
particular pair, so the next change to either list is caught by the one that is wrong.

Three render tests assumed all eight started on and had to be told otherwise; two of them switch
caffeine on first, and the switch-everything-off test now turns off **what is on** rather than
clicking all eight, which under the new default would have switched five *on*.

The theme follows the system light/dark setting **by default**, and `UserSettings.themeMode` can
override it. Dynamic color is deliberately absent in both: leaving it on would let Android 12+ derive
the palette from the user's wallpaper and discard the brand colors entirely. Palette: Baltic Blue
`#2F6690` (primary), Olive Bark `#625834` (secondary), Alabaster Grey `#D9DCD6` (background), Yale
Blue `#16425B`, Inferno `#A30000` (error).

**The override is three-state, and that is the whole of why it was allowed to exist.** This file
said for a long time that there would be no in-app switch, because a per-app switch is a second place
for the theme to live and a state that can get out of step with the phone — and that argument is
answered only by keeping *follow the phone* reachable. `ThemeMode.SYSTEM` is the default and is one
of the three chips, so it is never a state the reader can be stranded outside of. A two-state toggle
could not have promised that: the first tap would make the phone's own setting unreachable for ever,
and every later "why is this app light" would have no way back.

What earns the override is that **the charts are the reason**. Every series here carries two
hand-picked values, one per scheme, and the separations they were chosen for genuinely differ between
the two — so reading a plot in the scheme it is legible in is a real reason to differ from the phone
for a few minutes. That is not true of a text app, which is why this is not general advice.

`MainActivity` resolves the mode through `ThemeMode?.resolvedDark()` and hands
`HealthTrackerTheme` a plain `Boolean`. Two things about that are load-bearing. The parameter stays a
`Boolean` because the render tests pass a *scheme* directly to capture both, and threading a settings
enum through them would make those calls say something about a preference when what they are choosing
is a ground. And **`null` — settings not yet read off disk — resolves exactly as `SYSTEM` does**: the
row arrives a frame or two after the window, and any other reading paints one scheme and repaints in
the other on every launch, worst for precisely the reader whose choice differs from their phone.
`ScreenRenderTest` pins that equality rather than the two obvious cases.

**The home-screen widget is deliberately outside all of this.** It draws through `GlanceTheme` on the
launcher's own surface, and a widget disagreeing with every other widget beside it would be reading
this setting somewhere it does not apply.

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
points directly would draw it as a straight ramp. Fuel loads doses from further back than
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
- **Time shades** (`ChartShade`) are the horizontal counterpart of a target band, and the distinction
  is worth keeping: a band shades a range of *values* and asks "was it in range"; a shade covers a
  range of *moments* and asks "what was happening then". Sleep is the case it was built for — a heart
  rate of fifty says one thing at four in the morning and quite another at four in the afternoon, and
  the chart cannot show that difference by drawing another line. Deliberately **not** a
  `ChartSeries`: it has no values, no axis and nothing to read off it, so it takes no legend row and
  no gutter. Drawn before everything, target bands included, at `SHADE_ALPHA` (0.10) — lighter than a
  band because a band backs one axis while a shade runs full height under every series at once, and
  at band weight the asleep hours read as a block the lines were drawn *on top of* rather than a
  ground they pass through. It carries a `label` purely for `spokenSummary`: a shade is the one thing
  on a plot that changes how every line under it should be read, and it is completely invisible to a
  reader who cannot see that the ground went darker, so its hours are spoken even when no series is
  left to describe.
- **Target bands** (`AxisSpec.band`) shade a range behind the data. A filled area answers "was it in
  range" at a glance where two threshold rules leave the reader working out which side of each the
  trace is on. **The master graph no longer draws one**, though the Today chart still does and
  `UserGoals` still holds the figures. A band backs *one* series and that plot carries eight: behind
  carbohydrate curves, step columns and a heart rate trace it stopped reading as the glucose target
  and started reading as a region of the chart — and with the sleep shade underneath it, two
  overlapping washes left the ground saying two things at once. The glucose reference rule stays
  there: a single line at one value cannot be mistaken for a region.
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

`StepMergeTest`, `FastingAdherenceTest`, `FastingStatsTest`, `CaffeineTest`, `MacroAbsorptionTest`,
`GlucoseSmoothingTest`, `MealDuplicatesTest`, `SeriesGapsTest`, `AxisSelectionTest`,
`GlucoseGapsTest`, `TimeGridlinesTest`, `ChartBoundsTest`, `WaypointSeedTest`, `PanWindowTest`,
`SleepTest`, `CsvTest`, `RunZonesTest`, `RunPaceTest`, `AftScoringTest`, `BodyCompositionTest`,
`GlucoseMetricsTest`, `MealResponseTest`, `TrainingVolumeTest`, `ReadinessTest`,
`TrendsBucketsTest`, `MovingAverageTest`, `StreaksTest`, `PersonalRecordsTest`,
`GoalProjectionTest`, `UsualIntakeTest` and
`CaffeineLastCallTest` are the pure-JVM suites. `CsvBackupTest`, `SupplementsTest`, `HydrationEditTest`, `AftAttemptTest`, `RunProjectionTest`, `CardFoldTest`, `SleepSyncTest` and `StepPipelineTest`
are Robolectric
repository suites alongside
`MealDeletionTest` and `FinishedDaySyncTest`, pinning the behaviour that lives between two tables
with no foreign key: the same
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
because the collapse runs first. It also pins the meal presets, which are ordinary enough to look
untestable: that they read through the day whatever order the three fields were set in, that two set
alike are offered once, and what the shipped defaults are — the last being the figure
`MIGRATION_17_18` seeds, so the two cannot drift apart silently.

`MealResponseTest` pins the refusals as hard as the arithmetic, because they are the half that
protects the reader: a stamped meal unscored even when the trace around it is a textbook response, a
window the sensor barely covered unscored rather than scored small, and no pre-meal reading meaning no
baseline and so no figure. The two it pins hardest are the ones a plausible implementation gets
wrong — that eating high does not outscore the same rise from a lower start, and that a dip below
baseline does not net off the rise before it. `MealTimeStampTest` carries the companion case found on
the phone: a stamp whose only repeat is older than the list still counts as a stamp.

**The preset tests pin their clock rather than reading it, and they have to.** A chip disables itself
for a time that has not come round yet, so a fixture hung off `Instant.now()` would pass in the
evening and fail before breakfast — the `NightEndedHoursAgo` problem arriving from the other
direction. `MasterGraphRenderTest` pins both `WellnessUiState.now` and the dialog's own `now` to a
fixed afternoon last May, which puts one preset either side of that moment, so a single render
answers both halves of the rule: the earlier chip is tappable and saves noon *on the day the source
recorded* — not noon today, since the stamp got the date right and only the time wrong — and the
later one cannot be tapped at all.
`GlucoseGapsTest` pins both failure modes of the backfill at once — missing a real hole leaves the
chart permanently wrong about hours that *were* recorded, and finding one in every sensor stutter
spends a query on every refresh forever — which is why it carries a fixture for an ordinary
fifteen-minute stutter alongside the four-hour outage. `TimeGridlinesTest` pins that every interval
divides a day evenly (otherwise the rules drift through the day), that the density guard is about the
screen and not the clock, and that a spring-forward day keeps every rule on the hour.
`ChartBoundsTest` pins the silent failure: a goal outside the readings is clipped rather than drawn
small, so a chart missing its goal looks exactly like a chart that has none.
`TrendsBucketsTest` pins what a slot means once it has stopped being a day. A sum and a mean look
equally plausible in a screenshot and disagree by a factor of seven, in the one place a daily goal
line is drawn alongside for comparison — so the wrong one does not look wrong, it looks like a week
of extraordinary effort above a target that has quietly stopped meaning anything. The cases worth
most are the partial weeks at either end, which summed would open the year view on a cliff at its
right-hand edge every day but one. `ScreenRenderTest` carries the drawing half: a year of weight
composed on its own, with the goal eighteen pounds below anything weighed and four waypoints between
them, so `chartBounds` has to hold all five rules inside an axis scaled to weekly means or the
picture loses them silently.
`MovingAverageTest` pins the ways a trend line can be wrong while still looking like a trend: it may
not overshoot the readings it averaged, it may not *lead* them — a step is followed and never
anticipated, which is what separates trailing from centred — and a window holding one reading after a
gap is dropped rather than returned raw. The gap case is the one that catches index weighting: three
mornings, a fortnight off the scale, three more at a lower weight, and a filter weighting by position
would still hold the old level up across a stretch nobody weighed anything at all.
`ScreenRenderTest` renders the overlay in **both schemes** and the year view without it, which is the
only way the colour collision above was found; `render` takes a `dark` flag for that, since
`HealthTrackerTheme` accepts the choice directly and the qualifier would otherwise have to change.
`StreaksTest` pins the tolerance rule from both sides — an empty today does not break a run, an empty
yesterday does — and that `best` will not weld two runs together across a gap, which is what a
sort-and-count implementation does and which reads as a plausible longer streak.
`PersonalRecordsTest` pins the ways a number nobody performed could reach that card: a running fast
taken as the longest, a two-mile read as a maximum rather than a minimum, and one hand's grip filled
in from the other.
`GoalProjectionTest` is mostly refusals by design — a slope pointing the wrong way, four readings
instead of five, a crawl that arrives in the next decade — plus the one that catches a fit reading
too much history: a steep loss last winter followed by a flat month must produce *nothing*, not the
winter's rate. It also pins that the segment leaves from the fitted value rather than from a morning
that happened to be high.
`UsualIntakeTest` pins the asymmetry between the two intakes in both directions — caffeine following
the newer cup while the count still favours the old one, water holding to the bottle when the last
entry was a glass — because either rule looks reasonable applied to the wrong one. Only the caffeine
half still reaches a chip, and it is the half where a wrong suggestion writes into live data on a
single tap with no dialog in between; the water half now guards a function no screen calls, and is
listed under *The usual row on Log* as something to settle rather than to keep testing forever.
`SleepTest` pins the two ways a night can be reported wrongly while looking entirely plausible on the
card: counting waking time as sleep, which flatters every night by however long was spent staring at
the ceiling, and drawing an unstaged stretch at a named stage's height, which reports a measurement
the source never made. It also pins the stage *ordering* on the plot and the two-points-per-stretch
pairing the step shape depends on — a hypnogram drawn upside down says the opposite of what happened,
and neither that nor a collapsed riser is behaviour any other test would notice.
`WaypointSeedTest` pins where a control *opens*, which is not behaviour any other test would notice
and is the difference between one tap and a hundred. `PanWindowTest` pins the quiet half of panning:
that the curves stop at `windowEnd` rather than running past it, that a meal beyond the right edge is
neither listed nor marked, and that a drag cannot put the window in the future or leave it three
minutes short of live -- a window three minutes short of now looks exactly like a live one and is not.
New adherence, interval, stats, decay, absorption, smoothing, duplicate, gap, gridline or axis-range
behaviour belongs there. `ExampleUnitTest` and `ExampleRobolectricTest` are scaffolding.

Awkwardly, the duplicate-collapse cannot be reached through a sync at all any more: the sync rejects
duplicates on the way in, so a test that needs rows in the state a *previous* version of the app left
them has to build them itself. `MasterGraphRenderTest` now hands four `MealEntry` rows straight to a
`WellnessUiState` and composes `MealListCard` on them — the collapse and the stamped-time judgement
both live on the state, so nothing is lost by skipping the repository, and the fake data source that
used to plant them through the DAO is gone with it. `stampedTime` is what survives and is the part
worth keeping: a round hour a couple back, **deliberately not midnight**, because midnight has a rule
of its own and stamping there would let a broken shared-time-of-day check pass.

A note on writing smoothing tests: assert peak *timing* against a trace that is symmetric about its
peak. On an asymmetric one the two samples either side of a near-plateau come out within a tenth of
each other, and which of them wins is the input's shape rather than the filter's.

`MealDeletionTest` is a Robolectric test with no UI in it, exercising the repository against an
in-memory database and a stubborn data source that keeps re-offering the meal that was deleted. Any
behaviour that only shows up *across* a sync belongs there rather than in a render test.

`HydrationEditTest` is the counterpart for the manual side: that a deleted drink stays deleted
across a sync, that an edit rewrites the row rather than adding a second one, and that the week-wide
list and the today-only total do not borrow each other's window. All four are things that would look
right on the card for as long as nobody checked the figure.

`SleepSyncTest` is the same shape for the other cache that changes under you: a source whose night
the test re-scores between syncs. It pins that a re-scored night *replaces* its stages rather than
accumulating them — the case the per-night delete exists for, where the second scoring has fewer
stretches than the first and an upsert alone leaves the vanished ones on disk to be counted twice.

`StepMergeTest` is pure and carries the day the merge was diagnosed on as a named case, because the
useful assertion is not a target figure — Garmin's total is not reachable, and asserting it would be
the claim this file says not to make — but the **ordering**: merged has to land strictly between the
pinned figure and the sum, which is the one statement that says it fixed something rather than traded
one error for another. Beside it: two origins on one slice take the maximum and not the total,
disjoint stretches are a union, three origins fold rather than two, and a slice every origin reports
as zero survives *as* a zero, since dropping it would leave the cache unable to overwrite a stale
figure for that quarter hour.

`StepPipelineTest` is the repository half, and its first test is the two-sources-of-truth regression
pin: `HealthDaySnapshot.steps` equals the sum of that day's stored buckets after a sync. It also
holds the three settings against each other on a fixture where the two mock origins overlap all day
and diverge in the evening — pinned answers for one app, merged beats it, and merged stays under the
sum of both. The last two are the failure modes: a source returning nothing leaves the steps **null**
rather than zero, and a read that fails after a good one leaves both the buckets and the card as they
were.

**The sleep card is composed directly, not through Wellness.** A `LazyColumn` builds only what
is on screen and this screen cannot be scrolled in a test, so the third card down is never
constructed at all — the hypnogram's canvas arithmetic would go entirely unexercised while the
Wellness screenshot still looked fine. `SleepCard` is `internal` for exactly this, and
`ScreenRenderTest` renders it on its own with a night seeded through the real sync.

**This is the pattern the six-tab move keeps needing, and it is now three cards deep.** A card that
moves onto a ticking screen loses its scroll-to test on arrival — the ticker means the screen never
reaches idle, so `performScrollToNode` times out rather than finding anything. `MoodTrendCard` came
off Activity that way and follows `SleepCard`: `internal`, composed on its own in `ScreenRenderTest`
against a `WellnessUiState` whose `logHistory` is read back out of the seeded repository, so the
state under test is the shape the view model would have assembled. `MealListCard` is the third, in
`MasterGraphRenderTest`. **Prefer this to widening a scroll timeout** — the timeout is not the
problem, and a card composed directly is also the only way its own drawing gets a real layout pass.

`MasterGraphRenderTest` checks the sleep shade through the plot's **spoken description** rather than
by looking at pixels, which is the only handle available: a wash at a tenth opacity on a canvas with
no text in it is not something a screenshot comparison reliably catches. It asserts both directions —
present on a window covering the night, absent on the last three hours — because a shade appearing
where nobody slept is worse than one missing, since it would have the reader explaining an evening
heart rate by sleep that never happened.

**That test seeds its night relative to now, and must go on doing so.** It originally leaned on the
shared mock, which seeds every night 23:00 to about 07:50 *in its own zone* — and `MockHealthDataSource`
keeps `systemDefault()` while the repository and view model in this suite are both pinned to UTC.
West of Greenwich that puts the night at 06:00 to 14:50 UTC, so whether the live 3h window fell
inside one was decided by the hour the suite ran at: green every evening, red before breakfast, on
code nobody had touched. Aligning the zone is not the fix on its own — it only moves the broken hours
onto the evenings this repository is actually worked on. `NightEndedHoursAgo` puts the night a fixed
distance behind `now` instead, which is inside the day window and outside the last three hours at
every hour of every day.

`CardFoldTest` pins that a fold survives a reorder, that folding one card does not unfold another,
and that folds do not leak across tabs — the three ways one shared row could go wrong.
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

**A screen carrying the fast timer cannot be scrolled in a test.** The one-second ticker that drives
it means the screen never reaches the idle state `performScrollToNode` waits on — it retries, times
out after a minute, and throws `AppNotIdleException`. Anything below the fold there has to be
asserted on a screen that does not tick, which is why the glucose smoothing and target band are
covered in `MasterGraphRenderTest`. The drawing code is shared, so covering it once covers both.

**That ticker now belongs to `FuelViewModel`**, which followed the fast card off Wellness, and
Fuel is the longest *ticking* tab in the app at fourteen cards — so it has the problem worse
than Wellness ever did. (The count keeps moving: thirteen before the extended-fast entries moved
inside their own card, eleven after, twelve with the blood sugar summary, thirteen again with the
biggest-responses ranking, fourteen with net calories. Wellness and Settings tie for second at
thirteen, and only Wellness ticks. The problem does not move, so do not read the figure as the
point.) Wellness still ticks too, which is what the mood card's direct
composition is for. Today is the screen that gained by the swap: its `minuteTicker` looks like a
ticker but is only ever advanced by `refresh()`, with no loop behind it, which is why the master
graph's suite can scroll and wait on idle at all.

**`MasterGraphRenderTest` times out under load, and it is not a real failure.** Compose's idling
strategy gives up after 60 seconds with `AppNotIdleException` and a message suggesting an infinite
composition loop. On a busy machine this suite has taken anything from 40 seconds to four minutes for
identical code, and the timeout has hit a *different* test each time while the class passed in
isolation immediately after. **An infinite loop fails the same test every run** — that is the
distinction worth checking before going looking for one. Re-run the class alone before believing it.

**That rule only holds while the load is intermittent, and it is worth knowing how it fails.** Under
*sustained* starvation — this machine has 7.8 GB and a session of its own can leave barely 1 GB free
— the slowest tests cross the 60-second threshold every single run, so the failing set stays stable
and looks exactly like a real defect. Three runs here gave three, two and three failures with the
same two always among them, which by the rule above should have meant a composition loop. It did
not: the class fails the same way on a commit that had passed all 199 an hour earlier, and took
**42 minutes** where it is documented at 211 seconds.

**The check that actually settles it is running the suspect class at a known-green commit** —
`git stash push -u`, run, `git stash pop`. Cheap, and it separates "my change did this" from "this
machine cannot run this suite today" in one go, which neither the failing set nor a re-run can.

Two corroborating signals seen on 2026-08-29, when a commit that had passed the full suite an hour
earlier failed twice in isolation with an unchanged tree. **The first tests to go are the two that
`performScrollToNode` on Activity** — the AFT card and the grip trend — because scrolling waits on
idle and Activity is the densest tab. And **`lintDebug` stretched from about a minute to nine**, which
is the clearest tell that the machine and not the code is the problem: lint composes nothing. When
both appear together, gate on the named non-Compose classes and say so in the commit.

When the render suites cannot be trusted, the other classes run in well under a minute and are
unaffected — they render no Compose, so `AppNotIdleException` cannot reach them. **`tools/fast-gate.ps1`
is that gate**, and it names every class explicitly because Gradle has no "exclude these two". It
prints its own class and test counts at the end, so the figure quoted in a commit is one the run
produced rather than one remembered — currently **50 classes, 439 tests**. Keep the list in step when
a test class is added: a class missing from it silently stops being gated on. Say plainly in the
commit which of the two was verified; a test count that quietly means something narrower than usual
is worse than no count.

**The check that settles a render failure is a known-good commit in a throwaway worktree**, and the
script's header carries the exact incantation. Run on 2026-08-30 it answered *the machine* — the AFT
test failed at `27197cb`, which predated every change being blamed for it, and a single test took
three minutes.

**A click on an off-screen node is clamped into view and lands on nothing, silently.** It does not
throw. Eight series switches wrap onto three rows and the chart card no longer fits the screen, so
the suite that switches every series off was leaving two of them drawn and asserting nothing about
it. `performScrollTo()` before each `performClick()` is what makes it deterministic.

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
heart rate, steps and sleep, so a test that wants a blood sugar line has to insert the readings
itself. Sleep is a fourth: it arrives through `syncTimeSeries` rather than `syncHealthData`, so a
screen seeded only by the daily sync renders the sleep card's empty branch and proves nothing.

## Conventions

`.gitattributes` pins `gradlew` to LF and `gradlew.bat` to CRLF — a CRLF `gradlew` fails with a bad
interpreter error on macOS and Linux. Don't let an editor normalise those.

Comments in this codebase explain *why* a non-obvious choice was made, not what the code does. Match
that when adding code — a comment restating the line above it is out of place here.

**`master-graph` is a fast-forward mirror of `main`.** After pushing `main`, bring it along:

```bash
git fetch . main:master-graph && git push origin master-graph
```

It carries no work of its own and is never merged into. Forgetting it leaves the two remotes silently
disagreeing, which is invisible until something reads the wrong one.

Commit messages are **long-form prose explaining why**, in the shape the existing history uses:
wrapped near 72 columns, several paragraphs, each one naming a decision and the failure it avoids
rather than the files it touched. They end with the test count and

```
Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

`CLAUDE.md` and `README.md` are **part of a behaviour change, not a follow-up to one** — a commit
that changes what the app does and leaves the docs describing the old behaviour is an incomplete
commit. That includes deleting claims that have stopped being true, which is the half most easily
missed: this file said the theme was light-only for one commit after it stopped being.
