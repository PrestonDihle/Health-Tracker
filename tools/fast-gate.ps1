# The fast test gate: every test class that renders no Compose.
#
# CLAUDE.md's fallback for when this machine cannot run the render suites -- the
# two Activity-scrolling tests in ScreenRenderTest (the AFT card and the grip
# trend) cross the 60-second idle limit under sustained load and fail every run,
# looking exactly like a real defect. These classes cannot hit that, because
# AppNotIdleException needs a composition to time out.
#
# Gradle has no "exclude these two classes", so the list is named explicitly.
# Keep it in step when a test class is added: a class missing from here is a
# class that silently stops being gated on.
#
#   powershell -File tools\fast-gate.ps1
#
# Before believing a failure in the two excluded classes, run the suspect one at
# a known-good commit in a throwaway worktree -- that is what separates "my
# change did this" from "this machine cannot run this today":
#
#   git worktree add ../greencheck <last-good-sha>
#   copy local.properties ..\greencheck\
#   cd ../greencheck; .\gradlew.bat :app:testDebugUnitTest --tests "*TheOneTest"
#   git worktree remove --force ../greencheck
#
# (If Windows refuses on "filename too long", mirror an empty directory over the
# worktree with `robocopy <empty> <worktree> /MIR` first, then remove it.)

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Set-Location $PSScriptRoot\..

$classes = @(
  "FastingAdherenceTest", "FastingStatsTest", "CaffeineTest", "MacroAbsorptionTest",
  "GlucoseSmoothingTest", "MealDuplicatesTest", "SeriesGapsTest", "AxisSelectionTest",
  "GlucoseGapsTest", "TimeGridlinesTest", "ChartBoundsTest", "WaypointSeedTest",
  "PanWindowTest", "SleepTest", "CsvTest", "RunZonesTest", "RunPaceTest",
  "AftScoringTest", "BodyCompositionTest", "GlucoseMetricsTest", "MealResponseTest",
  "TrainingVolumeTest", "ReadinessTest", "TrendsBucketsTest", "MovingAverageTest",
  "StreaksTest", "PersonalRecordsTest", "GoalProjectionTest", "UsualIntakeTest",
  "CaffeineLastCallTest", "CsvBackupTest", "SupplementsTest", "HydrationEditTest",
  "AftAttemptTest", "RunProjectionTest", "CardFoldTest", "SleepSyncTest",
  "MealDeletionTest", "MealTimeStampTest", "MigrationSchemaTest", "PlotRangeTest",
  "FoodLogConfidenceTest", "PlankTest", "EnergyBalanceTest", "MetabolicScatterTest",
  "FinishedDaySyncTest",
  "ExampleUnitTest", "ExampleRobolectricTest"
)

$gradleArgs = @(":app:testDebugUnitTest", "--console=plain", "-q")
foreach ($c in $classes) { $gradleArgs += "--tests"; $gradleArgs += "*$c" }

& .\gradlew.bat @gradleArgs
$code = $LASTEXITCODE

$xml = Get-ChildItem "app\build\test-results\testDebugUnitTest\*.xml" -ErrorAction SilentlyContinue
$total = ($xml | ForEach-Object {
  ([xml](Get-Content $_.FullName)).testsuite.tests -as [int]
} | Measure-Object -Sum).Sum

"$($xml.Count) classes, $total tests"
"EXIT: $code"
exit $code
