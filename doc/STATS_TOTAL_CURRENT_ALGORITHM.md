# Total-current statistics: alignment algorithm

This document describes how the stats web app (`org.team401.wpilogstats`)
computes the event- and log-level **total current** statistics (mean, peak,
P90) that appear on the summary and per-log pages. The relevant code lives in
`src/main/java/org/team401/wpilogstats/CurrentAnalyzer.java`.

## Why this needs care

A robot's total current draw at any instant is the sum of every subsystem's
instantaneous draw at that same instant. Each subsystem, however, is logged
independently:

- Direct `supplyCurrentAmps` entries (AdvantageKit-style) only log when the
  value changes, so their sample timestamps are sparse and irregular.
- Swerve modules don't log supply current directly. We derive it per module
  from `driveAppliedVolts`, `driveCurrentAmps`, `turnAppliedVolts`,
  `turnCurrentAmps`, and the battery voltage, sampled at the drive-current
  timestamps. Those timestamps are dense (40+ Hz) but differ from one module
  to another.

Two naive approaches both fail:

1. **Sum of per-subsystem statistics.** Add up each subsystem's independent
   mean and peak to get a "total." This is what the code did originally. It
   is wrong for peak (and for P90) because each subsystem's peak occurred at
   a *different* moment. Adding them overestimates — often dramatically. In
   one real log this produced ~1034 A of "peak total current," which exceeds
   what an FRC battery can physically supply.
2. **Compare samples positionally.** Zip subsystem sample lists by index and
   sum them. This is meaningless — sample `k` of one subsystem and sample
   `k` of another happened at unrelated times.

The fix is to align all subsystems onto a common time grid, sum them at each
grid point, and compute statistics over the resulting aligned total-current
time series. That series is a real physical quantity: at each timestamp it
answers "what was the robot drawing from the battery right then?"

## The algorithm

Inputs: a list of per-subsystem sample streams. Each stream is a list of
`(timestamp, value)` pairs in ascending timestamp order, already filtered to
the match window (`[matchStart, matchEnd]`) so pre-match idle doesn't dilute
the stats.

Output: a single aligned time series of `(timestamp, totalCurrent)` samples,
one per distinct timestamp across all input streams.

### Step 1 — union grid with zero-order hold

We use the **union of all input timestamps** as the output grid. Every
timestamp at which *any* subsystem produced a sample becomes one output
sample. Between updates, each subsystem is held at its most recently observed
value — standard zero-order hold.

This is both exact (no interpolation artifacts) and efficient: if a
subsystem's value doesn't change for 500 ms, we don't generate 25 redundant
samples for it — we just hold its value across whichever of the *other*
subsystems' timestamps fall in that interval.

A subsystem that has not yet produced any sample at a given time contributes
0. This is the honest choice: we have no evidence it was drawing current
before its first reported value. Since samples are already match-filtered,
this only matters for subsystems whose first recorded sample falls strictly
inside the match window — and treating their pre-first-sample contribution
as 0 is more defensible than silently carrying a value back from before the
match or throwing out the subsystem entirely.

### Step 2 — k-way merge

The merge is implemented as a straightforward k-way scan:

```
cursor[i] = 0            // next unread sample index in stream i
held[i]   = 0.0          // current zero-order-hold value for stream i

while true:
    nextT = +∞
    for each stream i with cursor[i] < len(stream[i]):
        t = stream[i][cursor[i]].timestamp
        if t < nextT:
            nextT = t
    if nextT == +∞:
        break

    # Advance every stream whose next sample sits at this timestamp.
    # Collapse duplicate-timestamp samples within a single stream to the
    # last value (they shouldn't happen normally, but it's cheap to be
    # safe).
    for each stream i:
        while cursor[i] < len(stream[i]) and
              stream[i][cursor[i]].timestamp == nextT:
            held[i] = stream[i][cursor[i]].value
            cursor[i] += 1

    total = sum(held)
    output.append((nextT, total))
```

Complexity: `O(N · k)` where `N` is the total number of input samples and
`k` is the number of subsystems. With typical `k ≈ 10–20` and `N ≈ 50k` per
log this is under a million operations — negligible. A heap-based merge
would bring it to `O(N log k)` but is unnecessary at these sizes and adds
code complexity.

The implementation also relies on the streams already being in timestamp
order. WPILOG values come out of `LogData` in order, and `filterToMatch`
preserves order, so no pre-sort is needed.

### Step 3 — statistics over the aligned series

The aligned series is passed to the same `stats()` helper the analyzer uses
for individual subsystems, producing `mean`, `peak`, `rms`, `stdDev`, and
`p90`. Those become the `meanTotalCurrent`, `peakTotalCurrent`, and
`p90TotalCurrent` fields of `CurrentSummary`.

These are now **physically meaningful statistics of the actual total draw**,
not aggregations of unrelated per-subsystem extremes:

- `peakTotalCurrent` — the single largest instantaneous total draw the robot
  actually exhibited during the match window. This is comparable to battery
  peak-current limits.
- `meanTotalCurrent` — the unweighted mean of the aligned series. Close in
  value to the old "sum of per-subsystem means" because sum-of-means is
  algebraically the same as mean-of-sum when sampling is uniform. Differs
  slightly now because the aligned series gives equal weight to every
  distinct timestamp rather than sampling-rate-weighted.
- `p90TotalCurrent` — the 90th percentile of the aligned series, linearly
  interpolated between the neighboring ranks (same method numpy uses by
  default). Useful as a "realistic sustained draw" figure — far more
  conservative than the peak, and not skewed by a single outlier spike.

## Caveats & future work

- **Sample-weighted, not time-weighted.** The mean and percentiles above
  treat each aligned sample equally, regardless of how much time it
  represents. If one subsystem is logged at 40 Hz and another only changes
  five times per match, the 40 Hz subsystem dominates the sample count. For
  the per-subsystem streams this is already how `stats()` behaves, so the
  aligned total is consistent, but a strictly more correct formulation would
  integrate `value × Δt` across intervals and divide by total duration. That
  would give a true time-weighted mean. It is not done here because it adds
  complexity and (in practice) the answer moves by only a few percent for
  well-behaved logs.
- **ZOH-initial zero.** As noted in step 1, a subsystem contributes 0 until
  its first in-window sample is seen. If a subsystem has its first reported
  value late in the match, the early portion of the aligned series will be a
  small underestimate. This is the desired behavior — overestimating from
  missing data is worse — but it's worth knowing when interpreting results.
- **Pre-match filtering.** All of the above operates on sample lists
  pre-filtered to `[matchStart, matchEnd]` using the boundaries returned by
  `MatchPhaseDetector`. If phase detection fails for a log, the filter falls
  back to the full sample list (see `filterToMatch`) and the resulting
  "totals" will reflect whatever span the log covers — typically including
  pre-match idle. That's generally OK for direct subsystems (AdvantageKit's
  on-change logging already keeps samples clustered around activity) but
  will pull swerve means downward. The summary UI shows the match boundaries
  alongside these stats so an informed user can tell the difference.
