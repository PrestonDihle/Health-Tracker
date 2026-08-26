# Health Tracker

A personal health and lifestyle tracker for Android. Pulls objective metrics from
Health Connect and pairs them with the subjective and manual things it cannot
know about: fasting, hydration, waist, grip strength, blood pressure, ketones,
reps and reading.

## Screens

| Screen | What it does |
| --- | --- |
| **Today** | The landing page: current fast duration, weekly fast adherence, steps, last night's sleep stages, hydration, caffeine, creatine, supplements, a 3h-to-72h glucose and ketone chart, waist, grip strength, blood pressure, vibe/energy/focus, pushups and air squats, pages read |
| **Fasting** | Weekly feeding-window plan, scheduled multi-day extended fasts, the adherence score, a 14-day fasted/not-fasted timeline, and stats: totals, longest, average and streaks |
| **Master** | Everything on one timeline over 3h to 7d: meals spread into absorption curves, blood sugar, ketones, heart rate, caffeine and steps per hour, with the hours asleep shaded behind all of it |
| **Trends** | 7, 14, 30 and 90-day history for steps, waist, weight, grip strength, blood pressure, resting heart rate, sleep, stacked macros, reps, mood and reading — each against its own reference line |
| **History** | Backfill or correct any past day |
| **Settings** | Units, step source, daily goals, blood sugar target, reference line and chart bounds, blood pressure reference, body targets, weight waypoints, backup export |

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

The stepper for adding one opens at the weight you are actually at, not at the
goal: a waypoint goes somewhere between the two, so the goal is the one figure it
is never set to. Once a mark is staged it opens halfway between the lightest of
them and the goal, which is where the next one usually goes.

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
- Sleep sessions, and the stages within them
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

### Sleep

Health Connect offers exactly one aggregate for a sleep session — its duration —
and Garmin, like most trackers, also publishes the stages underneath it. Today
shows both: when the night started and ended, how much of it was actually spent
asleep, and how that split between REM, light and deep.

**Time asleep is not time in bed**, and the card leads with the former. A night
bounded 23:00 to 07:30 with forty minutes of waking in it is a seven-fifty night.
Reporting the eight and a half would be flattering rather than accurate, which is
the whole reason the stages are worth having — time in bed is quoted underneath,
so the difference between the two is visible rather than hidden.

The two read the same on a night whose source recorded no waking, which is what
Garmin does when it bounds a session to the sleep itself rather than to the whole
time in bed. Identical figures mean no waking was reported, not that the two are
the same measurement.

The chart is a hypnogram with the night's heart rate drawn over it. The two are
on one plot rather than two because the reason to look at either is the other: a
heart rate that stays high through the first two cycles is what a night of little
deep sleep looks like from the other side, and on separate charts that has to be
held in the head across a scroll. Deep sits at the bottom and awake at the top,
the way every published hypnogram is drawn, so the trace falling means sleep
deepening.

A source is allowed to record a stretch as *asleep* without saying which stage.
That is counted in the total and deliberately not drawn — there is no height on
the chart that means "asleep, stage unknown" without also meaning one of the
three named stages. Where a night contains any, the card says how much, so the
drawn trace and the printed totals can never quietly disagree.

On the Master screen the same nights appear as a shaded band behind everything
else rather than as a ninth line. Sleep is not a quantity to read off an axis
there; it is the answer to *why* for most of what the other lines do overnight —
the heart rate floor, the flat blood sugar, the steps that stop. A change of
ground says that at a glance where a line would need a scale and a legend row.
The **blood sugar target band has been removed from that screen** as a result: two
overlapping washes left the ground saying two things at once, and a band that
backs one series was already being read as a region of a plot carrying eight. The
reference line stays, and Today keeps its band.

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

### Reading the plot

The chart is a control as well as a picture.

**Tap it** and a hairline drops at the nearest sampled moment, with every visible
line's value at that moment listed underneath — glucose, each macro's arrival
rate, heart rate, caffeine, the hour's steps. A line with nothing recorded near
enough says so with a dash rather than quoting a reading from the far side of a
hole. The values are printed under the plot rather than in a bubble on it:
anywhere a bubble could go on a chart carrying eight series is on top of one of
them. Tap the same place again, or tap outside the plot, to put it away.

**Drag it sideways** and the window comes off the clock, so yesterday evening can
be read at 3h zoom instead of only as a seventh of a week. The stretch being
viewed is then spelled out above the chart with a *Back to now* chip beside it —
a panned chart that still looked live would be a lie. Vertical swipes are left
alone, so the screen goes on scrolling. Panning shows what is already on the
phone; the refresh button re-syncs whatever window is on screen when it is
pressed.

Charts also describe themselves to a screen reader: the window, then each drawn
line with its range and its latest value. A Canvas is otherwise a blank
rectangle to TalkBack, with every number on it out of reach. It is a summary
rather than a reading-out — the crosshair is what answers "what was it at 4 PM",
and it is reached by tapping the same thing that speaks.

**A switch per line** sits under the chart, coloured to match it, and that is the
control: it shows every line whether or not it is currently drawn. **Tapping a
name in the key** is the shortcut — it puts that line away without leaving the
plot, and only ever that way, because a key lists what is drawn and the row is
gone the moment the line is. A line that is off is genuinely not on the plot; it
stops stretching the axis it shares, which is the point of switching it off in
the first place.

## Blood sugar

The glucose axis runs 60–180 mg/dL by default, and **both bounds are settable**:
a trace that lives between 80 and 120 is a flat line on a wide axis and a legible
swing on a narrow one, and which of those is right depends on whose blood sugar
it is. Neither figure clips anything — outliers expand the axis rather than being
cut off, so a 210 reading still plots; it is simply not budgeted for.

Three things can be drawn on it, and they are deliberately distinct:

- A **grey band** for the target range, set in Settings. A filled region answers
  "was it in range" at a glance. **On the Today chart only** — the Master screen
  drew it behind eight other series, where it stopped reading as the blood sugar
  target and started reading as a region of the plot.
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

## Dark mode

Follows the system setting, with no in-app switch — nobody wants this app light
while everything else is dark. The dark scheme is not the light one inverted:
the brand's own tones *are* dark ones, so they lift rather than flip, and cards
stay above the background, which on a dark ground means lighter.

Every chart line has a dark variant that keeps its hue and gains lightness, so a
line is recognisably itself in either theme and the separations the palette was
built for survive — caffeine still sits where nothing else does, the two blues
of the macro stack are still kept apart by the olive between them.

## Caffeine last call

Set a bedtime limit in Settings and the app warns when **one more cup** would
leave you over it at 9 PM. The warning is about the next dose rather than the one
already drunk, because that is the only one still worth a decision — told after
the fact, there is nothing to be done.

It checks hourly rather than when you log something, since the moment usually
arrives with nothing logged at all: what is already in you keeps decaying and the
afternoon crosses the line on its own — plus once immediately whenever you change
the limit, so setting it answers straight away instead of at the top of the next
hour. Off until you switch it on, and a refused notification permission simply
means it never appears.

## Widget

Water, caffeine and the fast, on the home screen. Those are the three entries
made while doing something else, and each otherwise costs unlocking the phone
and finding a card — which is why they go unlogged, and an unlogged glass is
worse than a roughly-logged one because the chart then says zero. The fast
button takes its goal from the plan, exactly as the Today card does.

## Backup

Everything the app knows is one SQLite file in one app's private storage. An
uninstall, a lost handset or a corrupted page takes fasting history, hand-typed
weights and waists, blood sugar and the stack with it, and none of that exists
anywhere else. **Settings → Backup** writes every table to a zip of CSV files and
hands it to the share sheet; where it goes from there is your business, and the
app has no network permission to have an opinion about it.

The tables come from the schema rather than from a list in the source, so the
export keeps covering the whole database as that database grows. CSV rather than
a copy of the file, because the point is that it opens in something that is not
this app, on a day this app may no longer install.

## Creatine

A running daily total with quick +5 g and +1 g buttons, and every dose removable
— a loading week is four doses a day and a maintenance week is one, so what
matters is *how much*, not whether. The table for this existed from the first
commit and nothing was ever wired to it; it was in the database and not on the
phone.

## Supplements

A standing stack rather than a log typed out each morning. Add a name, a dose and
one of **morning, midday or evening**, and it comes back every day with a box to
tick; the card says how many of the stack are done. Something taken twice a day is
added twice, once per slot, which is what makes it tickable twice.

The dose is **free text**, and has to be: IU, mcg, mg, grams, capsules, softgels,
drops and millilitres all turn up on one shelf, and half of them are printed per
serving rather than per pill. Nothing does arithmetic on it, so demanding a
number could only ever reject something you actually take. It is also optional —
plenty of things are simply "one capsule", and saying so adds nothing.

Ticks are stored per day, so a day that was never ticked and a day that was
missed look the same, which is honest: there is nothing running at midnight to
tell them apart. Removing a supplement also clears the record of the days it was
taken, and says so before it does it.

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

The master graph's eight lines are picked to be told apart from each other rather
than to stay inside those five: glucose steel-blue, ketones olive, heart rate
brick, steps sage, carbohydrate gold, protein teal, fat purple, caffeine a deep
berry. Caffeine was a shade of the glucose blue until the two were watched
crossing on a real phone, where nothing but the dash pattern separated them.
Roast brown is the obvious colour for coffee and is the one that could not be
used: it lands on heart rate.

Every one of them has a dark counterpart that keeps its hue and gains lightness,
so a line is recognisably itself in either theme and these separations survive
the switch. Dynamic color is disabled in both — leaving it on would let Android
12+ derive the palette from the user's wallpaper and discard these entirely.

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
series gap-splitting, axis selection, gap backfill, gridline spacing, axis range,
where the waypoint stepper opens, and the panned window's own arithmetic --
whether the curves stop at the right edge, and whether a meal past it is still
listed. `MealDeletionTest` and `SupplementsTest` drive the repository against an
in-memory database for the behaviour that only appears across a sync, or between
two tables with no foreign key holding them together.
`MasterGraphRenderTest` and
`ScreenRenderTest` compose whole screens against an in-memory database and
capture images with Roborazzi, which is what catches the empty-list and
divide-by-zero cases the chart canvas only reaches under a real layout pass.
`MigrationSchemaTest` diffs every hand-written migration against the schema Room
generates from the entities -- a mismatch there does not fail a build, it throws
on the next launch for anyone upgrading.

### Verified toolchain

Last built green against Gradle 9.6.1, AGP 9.1.1, Kotlin 2.2.10, JDK 21,
Android SDK platform 36.1, build-tools 36.0.0.
