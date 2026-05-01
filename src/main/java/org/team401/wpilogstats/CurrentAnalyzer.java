package org.team401.wpilogstats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.team401.wpilogstats.MatchPhaseDetector.MatchPhases;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Computes current-draw analyses for a parsed wpilog.
 *
 * <p>Two flavors of current entry are handled:
 *
 * <ul>
 *   <li><b>Direct:</b> entries whose name ends in {@code supplyCurrentAmps} (case-insensitive)
 *       are taken at face value — these are the supply currents logged by most non-swerve
 *       subsystems (elevator, arm, intake, etc.).
 *   <li><b>Computed swerve:</b> swerve modules do not log supply current directly.
 *       Instead we look for sets of entries that share a common prefix and include
 *       {@code driveAppliedVolts}, {@code driveCurrentAmps}, {@code turnAppliedVolts},
 *       {@code turnCurrentAmps}. For each such module we compute
 *       <pre>
 *       supplyCurrentAmps =
 *           (|driveAppliedVolts| * driveCurrentAmps
 *            + |turnAppliedVolts * turnCurrentAmps|) / batteryVoltage
 *       </pre>
 *       sampled at the timestamps of the drive stator current.
 * </ul>
 *
 * <p>All time series are downsampled (via {@link BatteryAnalyzer#downsample}) before
 * being returned so browser payloads stay reasonable regardless of log length.
 */
public class CurrentAnalyzer {

  private static final int TIME_SERIES_MAX_POINTS = 1500;
  // Window used for the "smoothed" overlay on the total-current chart.
  // 1 s hides single-shot / single-acceleration spikes while still showing
  // match-cadence trends (drive bursts, endgame pull). Kept out of the
  // stats: peak/mean/p90 are still computed on the raw aligned series.
  private static final double TOTAL_SMOOTHING_WINDOW_SECONDS = 1.0;

  // Entry suffixes used for swerve module detection.
  private static final String DRIVE_VOLT_SUFFIX = "driveappliedvolts";
  private static final String DRIVE_AMP_SUFFIX = "drivecurrentamps";
  private static final String TURN_VOLT_SUFFIX = "turnappliedvolts";
  private static final String TURN_AMP_SUFFIX = "turncurrentamps";
  private static final String SUPPLY_SUFFIX = "supplycurrentamps";

  // ======================================================================
  // Public entry points
  // ======================================================================

  public CurrentSummary summarize(LogData log, MatchPhases phases) {
    return analyze(log, phases, false).summary();
  }

  public CurrentDetail detail(LogData log, MatchPhases phases) {
    return analyze(log, phases, true);
  }

  // ======================================================================
  // Core
  // ======================================================================

  private CurrentDetail analyze(LogData log, MatchPhases phases, boolean includeSeries) {
    List<Subsystem> subsystems = new ArrayList<>();
    // Parallel to `subsystems`: the match-filtered sample list we used to compute
    // each subsystem's stats. We hold onto these so we can align them onto a
    // common time grid below to compute *true* instantaneous totals.
    List<List<Sample>> matchSampleLists = new ArrayList<>();

    var battery = findBatteryEntry(log);

    // --- Swerve modules (computed) ---
    var modules = detectSwerveModules(log);
    for (var module : modules) {
      var samples = computeSwerveSupplyCurrent(log, module, battery);
      if (samples.isEmpty()) continue;
      var inMatch = filterToMatch(samples, phases);
      var stats = stats(inMatch);
      JsonArray series = includeSeries ? downsampleSamples(samples, TIME_SERIES_MAX_POINTS) : null;
      JsonArray smoothedSeries = includeSeries ? smoothedSeries(samples) : null;
      subsystems.add(new Subsystem(
          "Swerve " + module.label(),
          module.prefix() + "/(computed)",
          "swerve",
          stats, series, smoothedSeries));
      matchSampleLists.add(inMatch);
    }

    // --- Direct supplyCurrentAmps entries ---
    for (var name : log.entries().keySet()) {
      String lower = name.toLowerCase();
      if (!lower.endsWith(SUPPLY_SUFFIX)) continue;
      // Skip anything that looks like it belongs to a swerve module we already handled.
      if (belongsToKnownModule(name, modules)) continue;
      var values = log.values().get(name);
      if (values == null || values.isEmpty()) continue;
      var samples = new ArrayList<Sample>(values.size());
      for (var tv : values) {
        Double v = toDouble(tv.value());
        if (v == null || !Double.isFinite(v)) continue;
        samples.add(new Sample(tv.timestamp(), v));
      }
      if (samples.isEmpty()) continue;
      var inMatch = filterToMatch(samples, phases);
      var stats = stats(inMatch);
      JsonArray series = includeSeries ? downsampleSamples(samples, TIME_SERIES_MAX_POINTS) : null;
      JsonArray smoothedSeries = includeSeries ? smoothedSeries(samples) : null;
      subsystems.add(new Subsystem(
          friendlyName(name),
          name,
          "direct",
          stats, series, smoothedSeries));
      matchSampleLists.add(inMatch);
    }

    // Build the instantaneous total-current time series by aligning every
    // subsystem onto a common grid (the union of their timestamps) with
    // zero-order hold between samples. This yields the *physically meaningful*
    // peak/mean/p90 of what the battery actually saw — rather than a sum of
    // independent per-subsystem extremes (which would overestimate, since
    // subsystem peaks almost never land at the same instant).
    var totalSamples = computeAlignedTotal(matchSampleLists);
    var totalStats = stats(totalSamples);
    // On the detail path we also surface the series itself so the UI can
    // draw a "total current over time" chart using the exact same number
    // the summary's peak/mean/p90 are computed from. The smoothed overlay
    // is a time-weighted trailing mean — *only* for visualization, never
    // fed back into the stats.
    JsonArray totalSeries = null;
    JsonArray totalSmoothedSeries = null;
    if (includeSeries) {
      totalSeries = downsampleSamples(totalSamples, TIME_SERIES_MAX_POINTS);
      var smoothed = rollingMeanByTime(totalSamples, TOTAL_SMOOTHING_WINDOW_SECONDS);
      if (!smoothed.isEmpty()) {
        totalSmoothedSeries = downsampleSamples(smoothed, TIME_SERIES_MAX_POINTS);
      }
    }

    subsystems.sort(Comparator.comparingDouble((Subsystem s) -> s.stats.mean()).reversed());

    var summary = new CurrentSummary(subsystems.size(),
        totalStats.mean(), totalStats.peak(), totalStats.p90(),
        Collections.unmodifiableList(subsystems), totalSeries, totalSmoothedSeries);
    return new CurrentDetail(summary);
  }

  /**
   * Computes a 1 s time-weighted trailing mean of {@code samples} and downsamples
   * it for transport. Returns {@code null} if the input is empty so callers can
   * skip the JSON field entirely.
   */
  private static JsonArray smoothedSeries(List<Sample> samples) {
    if (samples.isEmpty()) return null;
    var smoothed = rollingMeanByTime(samples, TOTAL_SMOOTHING_WINDOW_SECONDS);
    if (smoothed.isEmpty()) return null;
    return downsampleSamples(smoothed, TIME_SERIES_MAX_POINTS);
  }

  /**
   * Time-weighted trailing rolling mean of a piecewise-constant (zero-order
   * hold) signal. For each input sample at timestamp {@code t_i}, returns a
   * sample whose value is the time-weighted average of the signal over the
   * interval {@code [max(t_0, t_i - W), t_i]}.
   *
   * <p>This is the right formulation for our irregularly-sampled aligned
   * total series: a naive "trailing N samples" mean would over-weight
   * moments where many subsystems happen to update simultaneously. Here
   * every millisecond of match time contributes equally, regardless of
   * sampling density.
   *
   * <p>Implementation uses a precomputed prefix-integral of the ZOH
   * signal plus a forward-only cursor for the window's left edge, giving
   * O(n) total work.
   */
  private static List<Sample> rollingMeanByTime(List<Sample> samples, double windowSeconds) {
    int n = samples.size();
    if (n == 0) return List.of();
    if (n == 1) return List.of(samples.get(0));

    // integral[k] = ∫_{t_0}^{t_k} value(u) du under ZOH interpretation.
    // Since value on [t_{k-1}, t_k) = v_{k-1}, the recurrence is:
    //   integral[k] = integral[k-1] + v_{k-1} · (t_k − t_{k-1}).
    double[] integral = new double[n];
    for (int k = 1; k < n; k++) {
      integral[k] = integral[k - 1]
          + samples.get(k - 1).v * (samples.get(k).t - samples.get(k - 1).t);
    }

    double t0 = samples.get(0).t;
    var result = new ArrayList<Sample>(n);
    // cursor = largest index j such that samples[j].t <= windowStart.
    // Advances monotonically as windowStart advances with i.
    int cursor = 0;
    for (int i = 0; i < n; i++) {
      double ti = samples.get(i).t;
      double windowStart = Math.max(t0, ti - windowSeconds);
      while (cursor + 1 < n && samples.get(cursor + 1).t <= windowStart) cursor++;

      // value(windowStart) is the value held on the interval that
      // includes windowStart, i.e. samples[cursor].v.
      double integralAtWindowStart = integral[cursor]
          + samples.get(cursor).v * (windowStart - samples.get(cursor).t);
      double integralAtTi = integral[i];
      double duration = ti - windowStart;
      double mean = duration > 0
          ? (integralAtTi - integralAtWindowStart) / duration
          : samples.get(i).v;
      result.add(new Sample(ti, mean));
    }
    return result;
  }

  /**
   * K-way merge of per-subsystem sample streams into a single aligned
   * total-current series. Every distinct timestamp across all streams becomes
   * one output sample whose value is the sum of the latest-observed value from
   * each stream (zero-order hold). Streams that have not yet produced any
   * sample at a given time contribute 0, which is the honest choice — we have
   * no evidence that subsystem was drawing anything.
   *
   * <p>Input lists must be sorted ascending by timestamp, which they already
   * are (wpilog values come out in timestamp order, and {@link #filterToMatch}
   * preserves order).
   */
  private static List<Sample> computeAlignedTotal(List<List<Sample>> sources) {
    var lists = new ArrayList<List<Sample>>(sources.size());
    for (var l : sources) if (!l.isEmpty()) lists.add(l);
    if (lists.isEmpty()) return List.of();

    int k = lists.size();
    int[] cursor = new int[k];
    double[] held = new double[k]; // Java default 0.0 = "nothing observed yet"
    int totalSize = 0;
    for (var l : lists) totalSize += l.size();
    var result = new ArrayList<Sample>(totalSize);

    while (true) {
      // Find the smallest next timestamp across all streams that still have
      // samples remaining. Linear scan is fine — k is the subsystem count,
      // typically < 20.
      double nextT = Double.POSITIVE_INFINITY;
      for (int i = 0; i < k; i++) {
        if (cursor[i] < lists.get(i).size()) {
          double t = lists.get(i).get(cursor[i]).t;
          if (t < nextT) nextT = t;
        }
      }
      if (nextT == Double.POSITIVE_INFINITY) break;

      // Advance every stream whose next sample lives at this exact timestamp.
      // A single stream with duplicate timestamps collapses to the last value.
      for (int i = 0; i < k; i++) {
        var l = lists.get(i);
        while (cursor[i] < l.size() && l.get(cursor[i]).t == nextT) {
          held[i] = l.get(cursor[i]).v;
          cursor[i]++;
        }
      }

      double sum = 0.0;
      for (int i = 0; i < k; i++) sum += held[i];
      result.add(new Sample(nextT, sum));
    }
    return result;
  }

  /**
   * Returns the sublist of {@code samples} whose timestamps fall within the match
   * window {@code [matchStart, matchEnd]}. Falls back to the original list if the
   * phases object is unavailable, has no bounds, or the filter would return empty
   * (e.g. a subsystem that only logs outside the match).
   */
  private static List<Sample> filterToMatch(List<Sample> samples, MatchPhases phases) {
    if (phases == null) return samples;
    Double lo = phases.matchStart();
    Double hi = phases.matchEnd();
    if (lo == null && hi == null) return samples;
    double loV = lo != null ? lo : Double.NEGATIVE_INFINITY;
    double hiV = hi != null ? hi : Double.POSITIVE_INFINITY;
    var result = new ArrayList<Sample>(samples.size());
    for (var s : samples) {
      if (s.t >= loV && s.t <= hiV) result.add(s);
    }
    return result.isEmpty() ? samples : result;
  }

  // ======================================================================
  // Swerve module detection & computation
  // ======================================================================

  private record ModuleGroup(String prefix, String label,
                             String driveVoltEntry, String driveAmpEntry,
                             String turnVoltEntry, String turnAmpEntry) {}

  private List<ModuleGroup> detectSwerveModules(LogData log) {
    // Map prefix -> available suffix entries.
    var byPrefix = new LinkedHashMap<String, Map<String, String>>();
    for (var entryName : log.entries().keySet()) {
      String lower = entryName.toLowerCase();
      String suffix;
      if (lower.endsWith(DRIVE_VOLT_SUFFIX)) suffix = DRIVE_VOLT_SUFFIX;
      else if (lower.endsWith(DRIVE_AMP_SUFFIX)) suffix = DRIVE_AMP_SUFFIX;
      else if (lower.endsWith(TURN_VOLT_SUFFIX)) suffix = TURN_VOLT_SUFFIX;
      else if (lower.endsWith(TURN_AMP_SUFFIX)) suffix = TURN_AMP_SUFFIX;
      else continue;

      String prefix = entryName.substring(0, entryName.length() - suffix.length());
      // Trim a trailing separator so two different suffix casings land in the same prefix.
      if (prefix.endsWith("/") || prefix.endsWith(".") || prefix.endsWith("_")) {
        prefix = prefix.substring(0, prefix.length() - 1);
      }
      byPrefix.computeIfAbsent(prefix, k -> new LinkedHashMap<>()).put(suffix, entryName);
    }

    var modules = new ArrayList<ModuleGroup>();
    for (var e : byPrefix.entrySet()) {
      var map = e.getValue();
      if (map.size() < 4) continue;
      String dv = map.get(DRIVE_VOLT_SUFFIX);
      String da = map.get(DRIVE_AMP_SUFFIX);
      String tv = map.get(TURN_VOLT_SUFFIX);
      String ta = map.get(TURN_AMP_SUFFIX);
      if (dv == null || da == null || tv == null || ta == null) continue;
      modules.add(new ModuleGroup(e.getKey(), labelFor(e.getKey()), dv, da, tv, ta));
    }
    return modules;
  }

  private static String labelFor(String prefix) {
    String p = prefix.toLowerCase();
    if (p.contains("frontleft") || p.endsWith("/0") || p.contains("module0")) return "Front Left";
    if (p.contains("frontright") || p.endsWith("/1") || p.contains("module1")) return "Front Right";
    if (p.contains("backleft") || p.endsWith("/2") || p.contains("module2")) return "Back Left";
    if (p.contains("backright") || p.endsWith("/3") || p.contains("module3")) return "Back Right";
    int slash = prefix.lastIndexOf('/');
    return slash >= 0 ? prefix.substring(slash + 1) : prefix;
  }

  private boolean belongsToKnownModule(String entry, List<ModuleGroup> modules) {
    for (var m : modules) {
      if (entry.equalsIgnoreCase(m.driveVoltEntry)
          || entry.equalsIgnoreCase(m.driveAmpEntry)
          || entry.equalsIgnoreCase(m.turnVoltEntry)
          || entry.equalsIgnoreCase(m.turnAmpEntry)) {
        return true;
      }
    }
    return false;
  }

  private List<Sample> computeSwerveSupplyCurrent(
      LogData log, ModuleGroup module, String batteryEntry) {
    var driveVolts = log.values().get(module.driveVoltEntry);
    var driveAmps = log.values().get(module.driveAmpEntry);
    var turnVolts = log.values().get(module.turnVoltEntry);
    var turnAmps = log.values().get(module.turnAmpEntry);
    var battery = batteryEntry != null ? log.values().get(batteryEntry) : null;

    if (driveVolts == null || driveAmps == null || turnVolts == null || turnAmps == null) {
      return List.of();
    }

    // Use drive current timestamps as the master clock — that's the field the formula
    // multiplies directly, so we naturally align the "hot" variable.
    var result = new ArrayList<Sample>(driveAmps.size());
    var dvCursor = new Cursor();
    var tvCursor = new Cursor();
    var taCursor = new Cursor();
    var batCursor = new Cursor();

    for (var tv : driveAmps) {
      double t = tv.timestamp();
      Double driveAmpV = toDouble(tv.value());
      if (driveAmpV == null || !Double.isFinite(driveAmpV)) continue;

      Double driveVoltV = holdAt(driveVolts, t, dvCursor);
      Double turnVoltV = holdAt(turnVolts, t, tvCursor);
      Double turnAmpV = holdAt(turnAmps, t, taCursor);
      Double batteryV = battery != null ? holdAt(battery, t, batCursor) : null;
      if (driveVoltV == null || turnVoltV == null || turnAmpV == null) continue;
      if (batteryV == null || batteryV <= 0.5) continue; // unplausible — skip

      double supply = (Math.abs(driveVoltV) * driveAmpV
          + Math.abs(turnVoltV * turnAmpV)) / batteryV;
      if (!Double.isFinite(supply)) continue;
      // Reject obvious glitches (negative is unusual; tolerate small dips to zero).
      if (supply < -5 || supply > 500) continue;
      result.add(new Sample(t, supply));
    }
    return result;
  }

  /** A stateful cursor into a sorted-by-time {@code TimestampedValue} list. */
  private static final class Cursor {
    int index = 0;
  }

  /** Returns the most recent numeric value at-or-before {@code t}, or null if unavailable. */
  private static Double holdAt(List<TimestampedValue> values, double t, Cursor cursor) {
    if (values == null || values.isEmpty()) return null;
    int i = cursor.index;
    // Advance while the next sample is still in the past.
    while (i + 1 < values.size() && values.get(i + 1).timestamp() <= t) {
      i++;
    }
    cursor.index = i;
    var tv = values.get(i);
    if (tv.timestamp() > t) return null; // before the first sample
    return toDouble(tv.value());
  }

  // ======================================================================
  // Stats + downsampling
  // ======================================================================

  private record Sample(double t, double v) {}

  private static Stats stats(List<Sample> samples) {
    if (samples.isEmpty()) return Stats.empty();
    double sum = 0.0;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double sumSq = 0.0;
    for (var s : samples) {
      sum += s.v;
      sumSq += s.v * s.v;
      if (s.v < min) min = s.v;
      if (s.v > max) max = s.v;
    }
    int n = samples.size();
    double mean = sum / n;
    double variance = n > 1 ? (sumSq - n * mean * mean) / (n - 1) : 0.0;
    double rms = Math.sqrt(sumSq / n);
    double stdDev = Math.sqrt(Math.max(0, variance));
    double p90 = percentile(samples, 0.90);
    return new Stats(n, min, max, mean, rms, stdDev, p90);
  }

  /**
   * Linear-interpolated percentile (same method as numpy's default). Returns 0 for
   * an empty input and the sole value for a single-sample input.
   */
  private static double percentile(List<Sample> samples, double p) {
    int n = samples.size();
    if (n == 0) return 0.0;
    if (n == 1) return samples.get(0).v;
    double[] values = new double[n];
    for (int i = 0; i < n; i++) values[i] = samples.get(i).v;
    Arrays.sort(values);
    double rank = p * (n - 1);
    int lo = (int) Math.floor(rank);
    int hi = (int) Math.ceil(rank);
    if (lo == hi) return values[lo];
    double frac = rank - lo;
    return values[lo] + frac * (values[hi] - values[lo]);
  }

  private record Stats(int count, double min, double peak, double mean,
                       double rms, double stdDev, double p90) {
    static Stats empty() { return new Stats(0, 0, 0, 0, 0, 0, 0); }

    JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("sampleCount", count);
      if (count == 0) return o;
      o.addProperty("min", min);
      o.addProperty("peak", peak);
      o.addProperty("mean", mean);
      o.addProperty("rms", rms);
      o.addProperty("stdDev", stdDev);
      o.addProperty("p90", p90);
      return o;
    }
  }

  private static JsonArray downsampleSamples(List<Sample> samples, int maxPoints) {
    var out = new JsonArray();
    int n = samples.size();
    if (n == 0) return out;
    if (n <= maxPoints) {
      for (var s : samples) {
        var pt = new JsonArray();
        pt.add(s.t);
        pt.add(s.v);
        out.add(pt);
      }
      return out;
    }
    int buckets = Math.max(1, maxPoints / 2);
    double bucketSize = (double) n / buckets;
    for (int b = 0; b < buckets; b++) {
      int start = (int) Math.floor(b * bucketSize);
      int end = (int) Math.floor((b + 1) * bucketSize);
      if (end <= start) end = start + 1;
      if (end > n) end = n;
      double minV = Double.POSITIVE_INFINITY;
      double maxV = Double.NEGATIVE_INFINITY;
      double tMin = 0, tMax = 0;
      for (int i = start; i < end; i++) {
        var s = samples.get(i);
        if (s.v < minV) { minV = s.v; tMin = s.t; }
        if (s.v > maxV) { maxV = s.v; tMax = s.t; }
      }
      if (tMin <= tMax) {
        out.add(point(tMin, minV));
        if (tMin != tMax || minV != maxV) out.add(point(tMax, maxV));
      } else {
        out.add(point(tMax, maxV));
        out.add(point(tMin, minV));
      }
    }
    return out;
  }

  private static JsonArray point(double t, double v) {
    var pt = new JsonArray();
    pt.add(t);
    pt.add(v);
    return pt;
  }

  // ======================================================================
  // Helpers (duplicated with BatteryAnalyzer to keep packages independent)
  // ======================================================================

  private String findBatteryEntry(LogData log) {
    var names = log.entries().keySet();
    for (String pattern : List.of(
        "robotcontroller/batteryvoltage", "batteryvoltage",
        "battery_voltage", "input_voltage", "inputvoltage",
        "pdh/voltage", "pdp/voltage", "/voltage")) {
      for (String name : names) {
        if (name.toLowerCase().contains(pattern)) return name;
      }
    }
    return null;
  }

  private static Double toDouble(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    if (value instanceof Boolean b) return b ? 1.0 : 0.0;
    return null;
  }

  // Wrapper segments commonly introduced by AdvantageKit and similar IO framing layers.
  // These carry no subsystem meaning and should be stripped from display labels.
  private static final java.util.Set<String> WRAPPER_SEGMENTS = java.util.Set.of(
      "inputs", "io", "outputs", "autologged", "realoutputs", "autolog",
      "raw", "values", "state");

  // Camel-case suffixes to strip from otherwise meaningful segments
  // (e.g. "LeadMotorInputs" -> "LeadMotor").
  private static final String[] WRAPPER_CAMEL_SUFFIXES =
      {"Inputs", "Outputs", "AutoLogged", "IOInputs"};

  /**
   * Turns a raw log entry path into a human-readable subsystem label.
   *
   * <p>AdvantageKit logs look like {@code /Hood/inputs/SupplyCurrentAmps} or
   * {@code /Shooter/LeadMotorInputs/SupplyCurrentAmps}. The goal is to drop the
   * bookkeeping wrappers and surface the actual subsystem name(s).
   */
  private static String friendlyName(String entry) {
    var segs = new ArrayList<String>();
    for (String s : entry.split("/")) {
      if (!s.isEmpty()) segs.add(s);
    }
    if (segs.isEmpty()) return entry;

    // Handle the last segment: peel off the "supplyCurrentAmps" tail.
    String last = segs.remove(segs.size() - 1);
    String lastLower = last.toLowerCase();
    if (!lastLower.equals(SUPPLY_SUFFIX)) {
      if (lastLower.endsWith(SUPPLY_SUFFIX)) {
        String head = last.substring(0, last.length() - SUPPLY_SUFFIX.length());
        // trim any leftover separator characters
        while (!head.isEmpty()) {
          char c = head.charAt(head.length() - 1);
          if (c == '_' || c == '.' || c == '-') head = head.substring(0, head.length() - 1);
          else break;
        }
        if (!head.isEmpty()) segs.add(head);
      } else {
        segs.add(last);
      }
    }

    // Drop wrapper segments; strip camel-case wrapper suffixes from the rest.
    var kept = new ArrayList<String>();
    for (String seg : segs) {
      if (WRAPPER_SEGMENTS.contains(seg.toLowerCase())) continue;
      String trimmed = seg;
      for (String suffix : WRAPPER_CAMEL_SUFFIXES) {
        if (trimmed.length() > suffix.length() && trimmed.endsWith(suffix)) {
          trimmed = trimmed.substring(0, trimmed.length() - suffix.length());
          break;
        }
      }
      if (!trimmed.isEmpty()) kept.add(trimmed);
    }

    // Dedupe consecutive duplicates caused by stripping ("Climber/ClimberInputs" → "Climber").
    var deduped = new ArrayList<String>();
    for (String s : kept) {
      if (deduped.isEmpty() || !deduped.get(deduped.size() - 1).equalsIgnoreCase(s)) {
        deduped.add(s);
      }
    }

    if (deduped.isEmpty()) return entry;

    // Join the last up to two surviving segments for a compact label.
    int start = Math.max(0, deduped.size() - 2);
    return String.join(" ", deduped.subList(start, deduped.size()));
  }

  // ======================================================================
  // Data carriers (package-private so EventService can build JSON from them)
  // ======================================================================

  static final class Subsystem {
    final String name;
    final String entry;
    final String source; // "direct" or "swerve"
    final Stats stats;
    final JsonArray series; // nullable
    final JsonArray smoothedSeries; // nullable — 1 s trailing mean overlay

    Subsystem(String name, String entry, String source, Stats stats,
              JsonArray series, JsonArray smoothedSeries) {
      this.name = name;
      this.entry = entry;
      this.source = source;
      this.stats = stats;
      this.series = series;
      this.smoothedSeries = smoothedSeries;
    }

    JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("name", name);
      o.addProperty("entry", entry);
      o.addProperty("source", source);
      o.add("stats", stats.toJson());
      if (series != null) o.add("series", series);
      if (smoothedSeries != null) o.add("smoothedSeries", smoothedSeries);
      return o;
    }
  }

  public static final class CurrentSummary {
    private final int subsystemCount;
    private final double meanTotalCurrent;
    private final double peakTotalCurrent;
    private final double p90TotalCurrent;
    private final List<Subsystem> subsystems;
    // Downsampled aligned total-current series (one (t, amps) point per
    // bucket). Nullable — only populated on the detail path where the UI
    // actually draws a chart from it.
    private final JsonArray totalSeries;
    // 1-second time-weighted trailing mean of the total series, downsampled
    // the same way. Visualization overlay only; never used for stats.
    private final JsonArray totalSmoothedSeries;

    CurrentSummary(int subsystemCount, double meanTotalCurrent, double peakTotalCurrent,
                   double p90TotalCurrent, List<Subsystem> subsystems,
                   JsonArray totalSeries, JsonArray totalSmoothedSeries) {
      this.subsystemCount = subsystemCount;
      this.meanTotalCurrent = meanTotalCurrent;
      this.peakTotalCurrent = peakTotalCurrent;
      this.p90TotalCurrent = p90TotalCurrent;
      this.subsystems = subsystems;
      this.totalSeries = totalSeries;
      this.totalSmoothedSeries = totalSmoothedSeries;
    }

    public boolean hasData() { return subsystemCount > 0; }
    public double meanTotalCurrent() { return meanTotalCurrent; }
    public double peakTotalCurrent() { return peakTotalCurrent; }
    public double p90TotalCurrent() { return p90TotalCurrent; }

    public JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("available", subsystemCount > 0);
      o.addProperty("subsystemCount", subsystemCount);
      o.addProperty("meanTotalCurrent", meanTotalCurrent);
      o.addProperty("peakTotalCurrent", peakTotalCurrent);
      o.addProperty("p90TotalCurrent", p90TotalCurrent);
      if (totalSeries != null) o.add("totalSeries", totalSeries);
      if (totalSmoothedSeries != null) o.add("totalSmoothedSeries", totalSmoothedSeries);
      var arr = new JsonArray();
      for (var s : subsystems) arr.add(s.toJson());
      o.add("subsystems", arr);
      return o;
    }
  }

  public static final class CurrentDetail {
    private final CurrentSummary summary;

    CurrentDetail(CurrentSummary summary) {
      this.summary = summary;
    }

    public CurrentSummary summary() { return summary; }

    public JsonObject toJson() {
      return summary.toJson();
    }
  }
}
