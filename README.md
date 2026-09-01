# Health Tracker

A personal health and lifestyle tracker for Android. Pulls objective metrics from
Health Connect and pairs them with the subjective and manual things it cannot
know about: fasting, hydration, waist, grip strength, blood pressure, ketones,
reps and reading.

## Screens

| Screen | What it does |
| --- | --- |
| **Today** | The day in a row of chips at the top, then everything on one timeline over 3h to 7d: meals spread into absorption curves, blood sugar, ketones, heart rate, caffeine and steps per hour, with the hours asleep shaded behind all of it — and the day's steps, sleep, calories and macros above it |
| **Log** | Every logging control in one place, so several things can be entered without changing tabs: one-tap repeats of what you log most, the last day's meals, waist and weight with the body-composition screen, grip, blood pressure, vibe/energy/focus, pages read, pushups and air squats |
| **Fuel** | Fasting plan, extended fasts, adherence and stats; hydration, caffeine, creatine, supplements; the blood sugar summary, the meals that moved it most, the macro trend and net calories |
| **Activity** | The Army Fitness Test scorecard, this week's training volume by kind, personal records and streaks, and the movement trends: steps, runs split by heart-rate zone, grip strength, pushups and air squats |
| **Wellness** | This morning against your own baseline, the day's totals, last night's sleep stages, glucose and ketones, a card for comparing any two daily metrics, and the trends for waist, weight, blood pressure, resting heart rate, blood oxygen, sleep, vibe/energy/focus and pages read |
| **Settings** | Your profile and fitness-test standard, units, step source, daily goals, blood sugar target, reference line and chart bounds, blood pressure reference, body targets, weight waypoints, meal times, backup export |

**Cards are grouped by subject, and Log is the exception that proves it.**
Anything with a subject of its own sits next to what it feeds: the caffeine
stepper is on Fuel under the curve it moves, the glucose entry is on Wellness
beside its chart. What was left over is a set of measurements sharing nothing but
the fact that they are typed by hand — waist, weight, grip, blood pressure,
vibe/energy/focus, pages, reps — and those are on Log, because they are entered
in a batch and a batch spread over four tabs is four times the walking.

So grip strength is split: the stepper on Log, the both-hands chart on Activity.
That is the cost of the arrangement and it is paid deliberately, since the
alternative is the screen Wellness used to be — half form, half dashboard, and
too long to find anything on either way.

Two things follow. Log and Wellness share one view model, so a weight typed on
one is on the other's chart without a sync in between. And the day's totals
appear on both Today and Wellness, deliberately: it is the card carrying the
refresh button, and hiding it on one of the two screens most likely to be open
when a sync is wanted would make it hard to find.

### Card order

**Every tab's cards can be reordered, and the order is remembered per tab.** Each
card carries a pair of arrows in its title row; nothing is dragged. A visible
control beats a hidden gesture here for the same reason the series switches on
the master graph are switches — a feature nobody can see is a feature nobody
uses, and it can be tested without simulating a drag.

A card added in a later update appears at the bottom rather than vanishing,
because the saved order is reconciled with the tab's built-in one rather than
replacing it. Ids the save no longer recognises are dropped the same way.

**Cards also fold.** The chevron in a card's title row collapses it to that
title and opens it again, and which cards you have folded is remembered per tab
alongside where they sit. Nothing changes until you fold something: an existing
tab comes through an update exactly as you left it.

The title row is what stays, deliberately — a folded card you cannot identify is
a row you have to open to find out what it was. Moving a folded card leaves it
folded, and folding one card does not disturb any other.

**The lists inside a card fold separately.** Water, caffeine, creatine and meals
each list what you logged so it can be corrected, and those lists get long —
water reaches back a week, which at four glasses a day is thirty rows sitting on
top of everything below them. Each now shows its newest three with a *Show all
31* underneath.

This is a different control from the card fold and both are worth having. Folding
the Hydration card takes away the buttons that log water too; folding its list
leaves everything you actually use and hides only the history. Where a list is
already three rows or shorter there is no button at all — a control that cannot
do anything is still a line of the card.

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

### The heart-rate axis

**Settings → Heart rate chart** does for the heart-rate line on Today what the
blood sugar card does for the glucose one: a floor, a ceiling, and a solid rule
wherever you want it. Neither bound clips anything — a reading outside them still
widens the axis to fit. What they change is how much of the plot the ordinary
range gets, which is the difference between a day's heart rate reading as a flat
line and reading as the shape it actually was.

The floor and ceiling start at 40 and 180, which is exactly where the axis was
fixed before it was adjustable, so nothing about your chart moves until you move
it. The rule starts **off**, because nothing was ever drawn there and switching a
setting on should not be how you discover a new line on your chart. If your max
heart rate is in the profile, the stepper tells you where your easy and hard zone
boundaries fall, since "where should this line go" is the part it cannot answer
for you.

There is deliberately no shaded target band to go with it. The Today graph
carries eight possible lines and dropped its own blood sugar band for that
reason: a wash behind that many series stops reading as one line's target and
starts reading as a region of the chart.

## Planks

A card on Log with a clock on it. **Start**, hold, **Stop** — and then the hold
sits there with two buttons under it, *Save this hold* and *Discard*. Nothing
reaches the database until one of them is pressed.

That pause is the whole point. The chart plots each day's **longest** hold, so a
fumbled start or a plank abandoned at ten seconds would not be noise on it — it
would be a personal best you never performed, sitting at the top of the day. A
hold of zero seconds is thrown away without asking, since a Start immediately
followed by a Stop is a mis-tap in every reading.

The trend lives on Activity with the other bodyweight work, and the goal is set
under **Settings → Daily goals → Plank hold**. It is a *hold* rather than a daily
total, unlike everything else in that card: three one-minute planks are not a
three-minute plank, and it is the second of those the Army scores. Where your
age and sex are in the profile, the stepper opens on your own AFT 60-point
requirement and prints it underneath, since that is usually the number you are
actually aiming at.

**Holds you have saved are listed under the card for a week**, newest first, each
one tappable to fix its length or its time and each with a bin. Discard only
catches the mistake you notice in the moment; the hold you saved by accident —
phone picked up mid-plank, Stop pressed late — is the one you notice on the chart
the next day, and those are the only two ways a wrong number gets in.

This matters more for a plank than for a glass of water. A stray drink inflates a
*total*, which is wrong by one glass. A stray plank becomes that day's
**longest**, which is wrong by however long it was and never averages out against
the days either side of it. Delete the only hold on a day and that day goes back
to unmeasured rather than to zero.

**A day you did not plank is a gap in the line, not a zero.** Your plank capacity
did not fall to nothing on your rest days — it went unmeasured, and the chart says
so by breaking rather than dropping to the floor. That is the opposite of the
pushup and air-squat charts beside it, where a day with no rows really does mean
no reps were done.

Training holds are kept entirely separate from the plank on your AFT scorecard.
A Tuesday morning hold is not a test result, and feeding it into that card would
report a test that never happened.

## How well did today get logged?

A card under the meal list on Log, with five levels: *Barely*, *Guessed*,
*Estimated*, *Mostly weighed*, *Weighed*. Tap one to score the day; tap the one
you are already on to clear it. The score shows as a chip on Today and goes into
the CSV export as a number from 1 to 5, so a year of days can be filtered on it —
drop everything below a 3 before drawing conclusions about what you ate.

**Only you can answer this, which is why the app does not try.** It can see
whether meals exist, whether the macros are filled in, whether the day's total
looks implausibly low — and none of that is the question. A day of restaurant
meals typed in from memory looks *complete* from the app's side: every meal
present, every figure filled, and every gram of it a guess. A derived figure
would be measuring completeness and would get read as accuracy.

Words rather than a number out of ten, because the number has to survive being
read next February in a spreadsheet, where "everything logged as it happened,
portions eyeballed" means something and a 6 does not.

**An unrated day stays unrated.** It is not the same as a badly-logged one, and
it is not treated as one — no chart drops a low-scoring day and no figure is
adjusted by it. The score is recorded and exported, and what you throw out is
your decision, made wherever you do the analysis.

## The day at a glance

A row of chips at the top of Today, above everything else: steps against your
goal, last night's sleep, the share of today your blood sugar has spent in
range, net calories, how long the current fast has run, and any streak you have
going. Tap one and it opens the tab that figure lives on.

Chips with nothing to say are simply not there. No monitor means no in-range
chip; no fast running means no fast chip; a first run shows an empty strip that
takes up no room at all. A dash in a row this small looks like something broken,
where a missing chip just looks like a thing you do not track.

Only a goal you have **met** is marked. A strip that flagged every unfinished
goal would be telling you off at nine in the morning for not having walked yet.

## Comparing two things

A card on Wellness with two pickers: choose any two of steps, sleep, resting
heart rate, weight, time in range, net calories, caffeine and pages read, and
they are drawn on one plot with a scale down each side.

The separate scales are the whole trick. Steps run to five figures and sleep to
single ones, so on one shared axis the sleep line lies flat along the bottom and
the chart says nothing.

**It draws the two lines and leaves the reading to you.** There is no correlation
number, on purpose. A figure would turn "these two moved together for three
weeks" into a finding, and this is a sample of one with no controls and a
fortnight of everything else going on at the same time.

The switch underneath shifts the second metric a day later, so a cause sits under
its effect — last night's caffeine under this morning's sleep, rather than the
two plotted on the days they happened and appearing to have nothing to do with
each other.

## The usual, in one tap

At the top of Log: a chip for your usual glass of water, one for your last
caffeine dose, and one for whatever is left of the current supplement slot. The
widget made the case — water, caffeine and the stack are the things logged while
doing something else, and Log should not be slower than a home screen for them.

Nothing is set up and nothing is stored. The chips are read from what you have
actually been logging over the last month, so they follow your habits without
being told about them, and a chip with no habit behind it simply is not there.

The two are guessed differently on purpose. **Caffeine repeats your last dose**,
because coffee is drunk in whatever the current cup is — change cup and the chip
changes with you the next day. **Water offers the volume you log most often**,
because a bottle is a bottle and one odd glass should not become the suggestion
just for being the most recent. Neither ever offers an average: the average of a
bottle and a glass is a quantity you own no container for.

The supplement chip follows the clock rather than always offering the morning,
and says how many it will tick — it is the one button here that writes several
rows at once.

## Weight against everything else

A card on Wellness with a measurement on **both** axes — the only chart here that
drops time entirely. Grams lost per day up the side, and whatever you think might
explain it along the bottom: calories eaten, net calories, calories burned,
protein, average or resting heart rate, average blood glucose, steps, sleep, body
weight. Both axes carry their zero line, so the plot reads as four quadrants and
you can see which of them your weeks are landing in.

**The crossing is what it is for.** Put calories eaten along the bottom and the
fitted line crosses zero at the intake where your weight holds — your own
maintenance, measured from your data instead of predicted by a formula built on
somebody else's. Put *net* calories there and it answers a different question
just as usefully: if your watch's burn estimate were right the crossing would be
at zero, so wherever it actually lands is roughly how far the watch is out.

**That figure is maintenance, not BMR, and the card says so every time it prints
one.** Basal metabolic rate is what you would burn lying still all day. This
number includes every step and every session on the days it was measured over,
and the two differ by hundreds of calories.

**Weekly is the default grouping and it matters.** A day's weight moves about
700 g on water and glycogen; a day's 500-calorie deficit weighs about 65. Daily
points are noise ten times the size of the signal, and a line through them is
fitting the water. Weight is smoothed before any difference is taken, for the
same reason. Daily is still there — it is the honest way to *see* that noise.

**Most windows get no number, and the card says why.** A line needs at least five
points; it has to slope the right way; it has to fit well enough to read
something off; and its crossing has to land somewhere a human diet actually goes.
Fail any of those and the line is still drawn — seeing it lie flat through a
scattered cloud is how you learn not to trust it — but the figure is withheld.

A week you did not weigh yourself is left out rather than plotted at zero. So is
the week straight after it, because the first morning back on the scale is not
enough to smooth against yet, and closing the gap would credit a fortnight of
loss to a single week.

## Records and streaks

On Activity: the best of each thing you have logged, with the day you did it —
grip per hand, quickest two-mile, heaviest deadlift, longest finished fast, and
the best day your blood sugar spent in range.

Every one is something that actually happened. The two-mile is one you ran in a
recorded test, not the projection the AFT card makes from ordinary runs; that
figure has its own place, where it says twice that it is a projection. Only a
fast that has **finished** can be the longest, or a fast still running would take
the record and then beat itself an hour later. A fast is dated by the day it
ended, since one broken on Sunday lunchtime is a Sunday achievement.

Records you have not set yet are left out rather than shown blank. A column of
dashes is a list of things you have not done, which is not what a personal-bests
card is for.

Above them, the streaks: days in a row hitting your step goal, your protein
target, and taking your whole supplement stack. **Today is allowed to be empty.**
Checked at nine in the morning, before the day has happened, a streak that
counted today as a miss would read zero every morning and come back every
evening. An empty yesterday does break it — one unfinished day is a day in
progress, two is a lapse.

A streak with nothing to measure against does not appear at all. Never set a
protein target, and there is no protein streak to have broken.

## How far back the trends reach

Chips above the charts pick the window: **7, 14, 30, 90, 180 or 365 days**. A
week is what a change made on Monday has had time to show up in; a quarter is
where a body measurement's real slope separates from the noise of weighing
yourself every morning.

**At 180 and 365 days a point is a week, not a day.** A year of daily weights is
a band of noise with the trend somewhere inside it, and 365 bars across a phone
are a third of a pixel each — so those two ranges average each week into one
point, on the week start you chose in Settings. Every subtitle says *weekly
average* while they do, because the number under your finger has changed meaning:
it is no longer Tuesday's weight but the mean of the week Tuesday was in.

**A week is an average, not a total**, which is what lets the goal lines keep
working. Your step goal is a daily figure, so a weekly bar has to be a daily
figure too — totalled, a good week would draw seven times above its own target
and the line would stop telling you anything. It also keeps the newest bar
honest: a year always ends partway through a week, and a total would shrink every
Monday and climb back by Sunday, drawing you a collapse at the very edge of the
chart you are actually reading.

Days you did not record are left out of the average rather than counted as zero.
A week the watch synced on three days is the average of those three — dividing it
by seven would draw four days of illness that never happened. A week with nothing
in it at all breaks the line instead of touching the floor.

### The trend under the noise

Weight, resting heart rate and net calories each carry a **dashed 7-day
average** over the readings. A weight read every morning moves a pound and a
half on water and what time you last ate, which is several times what a week of
real effort shows — so the raw line asks to be read at its last point, and that
point is mostly noise.

It is a **trailing** average: each point uses that day and the six before it, and
nothing after. So it lags a few days behind a real change, and in exchange the
past never moves. A centred average would redraw last Tuesday every time you
weighed in, and a turn you noticed one week could be gone the next with nothing
saying it had changed.

It starts a couple of days into the chart rather than at the left edge, because
until there are three readings behind it there is no average to draw — one
morning under a label saying "7-day avg" is just the morning again.

The weekly ranges carry no average, and neither does the key. A point there is
already a week, and averaging weeks into weeks would be a second smoothing
wearing the first one's name.

### When you get there

Under the weight chart, when there is enough to say it: *185 lb by 25 Sep at
current pace.* A straight line through the last month of weighing, run forward
to the next waypoint — or to the goal, if you have not staged any — and drawn as
a short dotted segment past the right-hand edge.

It says nothing at all rather than guess. No date appears if you have weighed in
fewer than five times in the last month, if the line points away from your goal,
or if the pace is so slight that arriving takes more than two years. A made-up
date is worse than none, because there is no way to look at one and see how
little was behind it.

The line starts from where the trend says you are, not from this morning's
number — a morning three pounds up on water would otherwise launch the whole
projection from a point the trend never went through.

### Net calories

On Fuel under the macros: what you ate minus what you burned, one point a day,
below the line for a deficit. The macros card is what went in, and on its own it
cannot say whether it was a lot — two thousand calories is a deficit on a day
with a long ruck in it and a surplus on a day at a desk.

A day only appears when **both** halves were recorded. The day's own card on
Today counts absent food as zero, which is right while the day is still running:
a fasted morning really has eaten nothing. On a day that has finished it usually
means you did not track it, and counting it as zero would draw a deficit the size
of the whole day's burn — a fast that never happened, on the one chart most
likely to be taken as proof one did.

**Two cards stop at 90 days whatever chip you pick**, and say so in their own
subtitle. The runs chart reads the heart-rate trace of every session separately,
so a year of running is a few minutes of waiting to draw a hundred and fifty bars
too thin to tell apart; the biggest-responses ranking reads every blood sugar
sample in its window, which over a year is a six-figure number of readings to
print five lines. A dinner from last spring is also not a thing to act on.

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

### Steps, and the two ways of getting them wrong

More than one app usually writes steps -- a watch's companion app and the phone's
own tracking, often a mapping or fitness app as well. Both of the obvious ways to
read that are wrong, in opposite directions:

- **Adding them up** counts the same walk twice. A watch and a phone in one
  pocket see the same legs, and the combined figure approaches double.
- **Trusting one app** loses whatever that app did not see. A watch's companion
  app writes minute-by-minute step records all day and writes **none at all for a
  tracked activity** -- so an evening run reaches Health Connect only under the
  phone's own name, and an app pinned to the watch cannot see it. On the day this
  was diagnosed that was seven and a half thousand steps missing from a
  twelve-thousand-step day.

So the app **merges**: for each quarter hour it takes the highest figure any app
reported. Two apps watching the same walk agree closely, so the larger of them is
not a second walk; an app that saw a stretch nothing else did carries that
stretch alone. The one thing it can still under-read is a quarter hour in which
two apps recorded genuinely *different* walking, which is accepted -- every rule
for recovering that double-counts the ordinary case to rescue the rare one.

**Settings -> Step source** shows what merging comes to today next to each app's
own total, so the choice can be made against real numbers, and one app can still
be pinned deliberately. The daily figure is the sum of the same quarter-hour
buckets the timeline draws, so the card and the chart under it cannot disagree
about how far you walked.

It will not match your watch app's own total exactly, and it does not pretend to:
that number is not published to Health Connect and there is no local API to ask
for it. Expect a few percent.

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

On the master graph the same nights appear as a shaded band behind everything
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

### Runs, split by heart-rate zone

Activity draws one bar per running session, stacked by the minutes it spent in
each zone — Easy below 60% of maximum heart rate, Moderate to 75%, Hard to 90%,
Intense at or above it.

**The bar's height is minutes, not distance.** That is the choice that makes a
short hard interval session and a long easy one look as different as they felt;
by distance they are two bars of a similar height saying nothing about effort.

Zones need a maximum heart rate, which is **entered rather than derived** — the
"You" card in Settings, alongside age, sex and height. 220-minus-age is a
population average, and anyone who has watched their own on a hard effort knows
it better than the formula does. Left unset it falls back to 220-minus-age, then
to 190.

Nothing is stored: the zones are computed from the session and its own heart-rate
trace each time the chart is drawn. Cached figures would be wrong the moment the
maximum heart rate is edited, and there would be a table to re-key every time it
was. A run the watch recorded without a heart-rate trace draws nothing rather
than drawing a guess.

### Fiber, sugar, saturated fat and sodium

Where the logging app records them, the day's macro row gains a second line:
fiber, sugar, saturated fat and sodium. They are also stored per meal, so a
later question — do the meals with fiber in them settle faster? — has both
numbers on the same row to compare.

**These are parts of the macros above, not extra ones.** Fiber and sugar are
inside the carbohydrate figure and saturated fat is inside the fat, so the app
labels them *of carbs* and *of fat* and never adds them to the total. On the
first real day of data that was 8 g of fiber and 37 g of sugar within 91 g of
carbohydrate — adding them would have reported a day that ate more than it ate.

Sodium also appears on the blood pressure chart, on a scale of its own, as
context rather than as a cause: it is a daily total set against readings taken
at a moment, and one day's salt does not move one morning's pressure.

Days recorded before the app read these stay blank rather than being filled with
zeroes, and per-meal figures appear on meals synced from now on — the ones
already stored are not rewritten.

### This morning: two facts, not a score

Wellness opens the day with two statements and deliberately no readiness number:
how today's resting heart rate compares with **your own** trailing 30-day median,
and how last night's sleep compares with your goal.

A single blended score cannot say which half of it moved. Told "readiness 61"
there is nothing to check; told "resting heart rate 6 bpm over baseline, 5h 40m
asleep" you know what happened and whether you agree. The two are independently
reported, so a night the watch missed still leaves the other one standing.

The baseline is a **median of your own past mornings, excluding today** — a mean
would carry one illness into the baseline for a month, and including today would
pull the comparison toward the very morning being judged. Under ten mornings
there is no baseline at all: the reading is shown on its own rather than measured
against something too thin to mean anything.

None of it costs a sync. It is read from days already stored, so the line is
there the moment the tab opens.

### Blood oxygen (read, but dormant)

Wellness will chart the nightly average where a watch records overnight SpO2 —
and on the setup this was built against, none arrives. Garmin Connect does not
write blood oxygen to Health Connect: its permission list asks for two vitals,
heart rate and resting heart rate, and Health Connect has no oxygen-saturation
data at all. Turning Pulse Ox on at the watch does not change that, because the
gap is in what Garmin exports rather than in what the watch measures.

So SpO2 sits with HRV status, Body Battery, stress and training load: things this
app would happily show and cannot get. The chart simply does not draw, and will
start on its own if that ever changes.

It has its own Health Connect permission, and because it arrived in a later
version the app asks for it again rather than assuming — Wellness shows
"Not yet allowed to read: oxygen saturation" with a Grant button until you do.
Days recorded before it was granted stay blank rather than being backfilled with
a plausible number.

### Training this week

Running is not the only thing the watch records, and until recently it was the
only thing this app read — an hour under a loaded pack arrived as nothing at all
while a twenty-minute jog got its own bar on the chart. **Training this week**
now lists every session by kind: runs, rucks, walks, strength, cycling, swimming,
HIIT and anything else, with the sessions and minutes for each and a distance
where the watch recorded one. The week starts on the day set in Settings and ends
at now, so the figure is what has been done rather than what a full week holds.

Rucks are the reason it exists. The watch has no rucking activity, so a ruck is
logged as a hike — the app calls that row **Rucks** because that is what these
are, not because the source said so, and it cannot know whether weight was
carried.

A pace is shown only for things done on foot. Minutes per mile is how walking,
running and rucking compare; a cycling "pace" in the same unit reads like a very
fast run, and a pool distance makes it wrong twice over. Those still show their
distance. Strength sessions show none at all rather than `0.0 mi` — no distance
was recorded, and a zero next to real figures is a measurement nobody made.

## Army Fitness Test

Activity carries an AFT scorecard: enter the five raw results — three-rep max
deadlift, hand-release push-ups, sprint-drag-carry, plank and two-mile run — and
it scores each out of 100 against the Army's published tables, totals them out of
500, and says whether that passes.

Scoring comes from HQDA EXORD 218-25 Annex B, effective 1 June 2025. It is a
**step lookup, never an interpolation**: the tables give a minimum performance
for each reachable point value and say nothing about the gaps, so 335 lb earns
what 330 earns. Awarding a 98 the Army has no row for would be worse than useless
on a figure whose whole purpose is to match a scorecard someone else is holding.

**Which standard applies is a setting**, on the "You" card under Settings. The
general standard is normed by age and sex and needs 300 overall; the combat
standard is sex-neutral, still age-normed, and needs 350. There is no third set
of numbers — every published table has one `M | C` column and one `F` column per
age band, so "sex-neutral" means a woman in a combat specialty reads the male
column. The two standards differ in the total required and in which column she
reads, never in what a given performance is worth.

**Sixty in every event is required either way**, and that is not implied by the
total: 500 points with a 59 in one event is a failure. The card names the event
closest to the floor and by how much, since that is the one another session
actually moves the verdict on.

Nothing about a score is stored — only the five raw results and the date. Every
point value is recomputed when the card is read, so a birthday, a filled-in
profile or a change of standard re-scores every past test in place. A stored
score would be a claim about a profile that has since moved on, and nothing on
screen could tell that apart from a current one.

A test can be logged as it goes. All five events are optional, and one left out
stays out: an attempt three events in reads as unfinished rather than as a
failing 180, and only finished tests are plotted on the trend below. That chart
spans the tests themselves rather than the tab's 7-to-90-day window, because a
record test happens about twice a year.

### Projected two-mile score

Between record tests the card also reads your ordinary runs and says what the
two-mile event would score. It is labelled a projection wherever it appears,
because it is one: Health Connect records a run's total distance and its start
and end and nothing about the pace inside, so this is an **average over a whole
run**, not a two-mile effort. A run with a warmup in it reads slower than you
are, and an interval session slower still.

**A run shorter than two miles projects nothing at all.** Scaling a fast mile up
would produce a confident score for a distance nobody ran, and on a card full of
real results it would be impossible to tell apart from one. Runs with no recorded
distance are dropped for the same reason.

It uses the quickest qualifying run of the last 90 days — long enough that
somebody who races rarely still has something to go on, short enough that it is
about the shape you are in now rather than the shape you were in last year.

## Body composition

The body card works out your **waist-to-height ratio**, the military body
composition screen since 1 January 2026. It is one division: waist over height,
and it has to come to **less than 0.55**. Height and weight tables are no longer
used, so there is no table to look yourself up in — and no age bracket or sex
column either, which makes this the one scored thing here that works whatever
your profile says.

The tape goes at the midpoint between your last palpable rib and the top of the
iliac crest, usually at or just above the navel — not where a trouser size is
measured.

Two details decide close calls, and the app follows both:

- **Each measurement is recorded in inches and rounded *down* to the nearest half
  inch** — the waist and the height. That is not always in your favour: rounding
  the height down makes the ratio larger.
- **The limit is strictly under 0.55.** Exactly 0.55 is over it.

The waist trend carries the limit as a fine dashed line, drawn as a waist rather
than as a ratio — at a fixed height it is just a horizontal line, and one you can
read a tape against beats a number you have to divide. The card names the largest
waist that still passes at your height: at 6'3" that is 41", because 41½" divides
to 0.553 and fails.

The ratio is the whole assessment — there is no tape test or body-fat
calculation behind it.

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

**It opens on three lines: blood sugar, heart rate and steps.** The other five —
the three macro curves, ketones and caffeine — are one tap away on the switch
row, and nothing about how the switches work has changed. All eight at once is a
legible chart of nothing in particular: each of the five is answering a question
you did not ask by opening the app, and together they bury the two you probably
did — why is the heart rate up, and what moved the blood sugar.

The numbers down the two sides start as blood sugar and heart rate to match,
since a gutter labelled for curves that are switched off is worse than no
gutter — it still looks like something you could read a value off.

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
- Fixing a flagged meal takes **one tap**. Your own breakfast, lunch and dinner
  times sit above the clock face as chips, and tapping one saves the meal at it;
  the full picker is still there for a meal eaten at some other hour. A chip for
  a time that has not come round yet is greyed out rather than quietly logging
  the meal at this moment. Set the three under **Settings → Meal times** — they
  start at 6:30, 12:00 and 18:30, and they are only offered on a meal whose time
  was stamped, never on one that was genuinely recorded.
- The same source may also write one meal as several records. Records agreeing on
  timestamp, energy, all three macros and name are counted once; anything
  differing at all is kept, so a genuine second helping survives.

### What each meal did to the blood sugar

Every meal with a real clock time is scored against the trace around it: the
baseline it started from (the median of the half hour before), how far it rose,
how long the rise took, the area it spent above baseline, and how long until it
came back. Nothing is stored — it is all recomputed from readings already on the
phone, so correcting a meal's time immediately corrects its score.

The Log rows carry the short version (`+29 mg/dL · back in 2h 31m`), and **Fuel →
Biggest responses** ranks the meals that moved it most over the chosen window.

The ranking sorts on **area above baseline, not peak height**, and real data
shows why: the biggest single peak in a fortnight here was a 187 g carbohydrate
dinner at +56 mg/dL, and it still came *second* to a +51 that stayed up longer.
A sharp spike that clears quickly and a smaller one that sits there for two hours
can share a peak. Both figures are shown, so neither stands in for the other.

Four things stop a meal being scored, and all four leave it blank rather than
guessed:

- The time is a stamp rather than a measurement — the score would be about an
  hour nobody ate in. Fix the time on Log and it fills in.
- The sensor did not cover the two hours after it. A peak may have happened and
  gone unrecorded, and the smaller area left behind reads exactly like a flatter
  meal.
- There is no reading before the meal, so there is no baseline to measure from.
- Eating high does not count as a response: only what stands *above* where the
  trace already was is counted, and a dip below baseline contributes nothing
  rather than cancelling out the rise before it.

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

Fuel carries a **summary card**: for today and for the week so far, the share of
readings in range, the mean, an estimated GMI and the coefficient of variation.

Four figures rather than one, because each hides what the others show. A good
mean can be the average of a trace that was never once in range — swinging 55 to
165 averages a respectable 110 and spends the whole day outside the band. A good
time-in-range can still be a trace that swings, which is what the variation
figure is for; the consensus target is 36% or under, and it is called out beside
the number.

**Nothing is reported for a window the sensor did not cover.** Every one of those
figures is a proportion, so a monitored morning produces a perfectly well-formed
time-in-range that is really about a third of a day — and on screen it would look
exactly like a figure for the whole of it. Under 70% coverage the card says so
instead of guessing. Coverage is judged on the span the readings occupy, not on
counting them, so four fingersticks spread across a day still count and two
hundred readings crammed into one morning do not.

Both windows run to *now* rather than to the end of the day or week, so a
half-finished day reports on the half that happened. The week starts on whichever
day is set in Settings.

The glucose axis runs 60–180 mg/dL by default, and **both bounds are settable**:
a trace that lives between 80 and 120 is a flat line on a wide axis and a legible
swing on a narrow one, and which of those is right depends on whose blood sugar
it is. Neither figure clips anything — outliers expand the axis rather than being
cut off, so a 210 reading still plots; it is simply not budgeted for.

Three things can be drawn on it, and they are deliberately distinct:

- A **grey band** for the target range, set in Settings. A filled region answers
  "was it in range" at a glance. **On the glucose and ketone chart only** — the
  master graph drew it behind eight other series, where it stopped reading as the
  blood sugar target and started reading as a region of the plot.
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

### Finished days get a second look too

A day's totals used to be whatever was true at the last moment the app happened
to be open on it -- so a phone opened at breakfast reported a morning as a whole
day, permanently, on every chart drawn from the daily cache. Now a finished day
is re-read, and it stays open to re-reading for **two days after it ends** rather
than being closed on the first look: a watch syncs its evening when it next has a
phone and a charger, and under a read-it-once rule anything arriving after that
one moment was invisible for good.

Past that window a day is settled and costs nothing to skip, which is what keeps
this affordable on every refresh. The days older than the last week -- which no
sweep was ever going to reach -- are healed a few at a time in the background,
walking back as far as there is any evidence you were using the app, and no
further than ninety days. Where the source has genuinely forgotten a date, the
figure already stored is kept rather than replaced with nothing.

**The Activity card says how many past days changed**, for the same reason the
glucose backfill does: a total that grew by four thousand between two glances,
with nothing on screen to explain it, is harder to trust than one that says what
happened.

## Light and dark

Follows your phone by default, and **Settings → Theme** overrides it: *System*,
*Light*, *Dark*.

Three options rather than a switch, and that is what makes the override
worth having at all. This app deliberately had no theme setting for a long time,
because a per-app switch is a second place for the theme to live and a state that
can drift out of step with the phone. Keeping *System* on the list — and as the
default — answers that: following the phone is never somewhere you can get
stranded outside of. A plain on/off toggle could not have said the same, because
the first tap would put the phone's own setting permanently out of reach.

What earns it is the charts. Every line here has a colour chosen separately for
each scheme, and the separations they were picked for are not identical between
the two, so reading a plot in whichever scheme it is clearest in is a real
reason to differ from your phone for a few minutes.

The home-screen widget is not covered. It sits on the launcher next to every
other widget and follows the phone with them.

The dark scheme is not the light one inverted:
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

## Fixing what was logged by mistake

Water, caffeine and planks are all listed under their cards, newest first, and
every row can be tapped to correct its amount or its time or binned outright. The
rows are deleted for real, unlike a synced meal: nothing upstream has ever heard
of them, so there is no record waiting to arrive again and nothing for a hidden
flag to keep out.

**The list reaches back further than the total above it** — a week for water.
This is not tidiness. Water is logged by tapping *+100 ml* several times in a
row, so a stray tap writes something identical to a real entry and is only ever
noticed later, from a day's figure looking too high. A list that ended at
midnight would offer the correction only while nobody yet knew they needed it.
The day's figure still stops at midnight, so an older row shows in the list
without being counted into today.

This exists because of a specific incident. An automated tap, scaled wrongly from
a screenshot, landed on a logging button and wrote 100 ml of water that had never
been drunk — and for a long time no screen in the app could remove it.

**Setting a time is typed, not dialled.** Every dialog that asks for a time — the
two intake dialogs, a meal, a feeding window, the meal presets in Settings — uses
a pair of number fields rather than a clock face. The clock face was about 250dp
tall, most of a phone dialog, which pushed the Save button of anything carrying a
date row and an amount stepper below the fold. It is also the wrong control for
the job: every one of these is *correcting* a time you already know — a meal your
food app stamped 10:00, a drink logged an hour late — so you arrive knowing the
four digits, and dialling them costs two drags and a mode switch to say what
typing says outright.

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

### Lint

```bash
./gradlew :app:lintDebug
```

Clean of errors, and worth keeping that way rather than baselined. The app's
minimum is API 26 and it is developed on a phone several versions past that, so
calling something the platform only gained later compiles, passes every test and
crashes on nobody's device but a user's. Lint is the only check that looks.

### Tests

```bash
./gradlew :app:testDebugUnitTest
```

The pure-JVM suites cover the maths: fasting adherence and stats, caffeine decay,
macro absorption, glucose smoothing, meal de-duplication, stamped-time detection,
per-meal glucose response and the four ways it refuses to score one,
weekly training volume and the exercise-type mapping behind it,
the readiness baseline and the several ways it declines to be one,
series gap-splitting, axis selection, gap backfill, gridline spacing, axis range,
heart-rate zone boundaries, where the waypoint stepper opens, and the panned
window's own arithmetic -- whether the curves stop at the right edge, and whether
a meal past it is still listed. `MealDeletionTest` and `SupplementsTest` drive the repository against an
in-memory database for the behaviour that only appears across a sync, or between
two tables with no foreign key holding them together.
`MasterGraphRenderTest` and
`ScreenRenderTest` compose whole screens against an in-memory database and
capture images with Roborazzi, which is what catches the empty-list and
divide-by-zero cases the chart canvas only reaches under a real layout pass.
A few cards are composed on their own rather than through their screen -- sleep,
the mood chart, the meal list. All three sit on a screen with a running timer,
and a list that never stops changing cannot be scrolled by a test, so through the
screen those cards are simply never built and their drawing goes unexercised
while the screenshot above them still looks right.
`MigrationSchemaTest` diffs every hand-written migration against the schema Room
generates from the entities -- a mismatch there does not fail a build, it throws
on the next launch for anyone upgrading.

### Verified toolchain

Last built green against Gradle 9.6.1, AGP 9.1.1, Kotlin 2.2.10, JDK 21,
Android SDK platform 36.1, build-tools 36.0.0.
