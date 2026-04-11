package org.team401.wpilogstats;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Computes battery-voltage statistics and time series from a parsed wpilog.
 *
 * <p>Looks for the first entry whose name matches one of a short ordered list of known
 * patterns (RobotController/BatteryVoltage, battery_voltage, PDH voltage, etc.). The
 * matched entry is used for both the aggregate summary ({@link BatterySummary}) and the
 * per-log detail view, which additionally returns a downsampled time series suitable
 * for a chart.
 *
 * <p>Brownout thresholds follow WPILib conventions: 6.8 V for roboRIO 1, 6.3 V for
 * roboRIO 2. The analyzer reports both — the UI shows the rio1 threshold by default
 * because Team 401 is on rio1.
 */
public class BatteryAnalyzer {

  /** Brownout threshold for roboRIO 1 (V). */
  public static final double BROWNOUT_V_RIO1 = 6.8;
  /** Brownout threshold for roboRIO 2 (V). */
  public static final double BROWNOUT_V_RIO2 = 6.3;
  /** Warning threshold below which voltage sag is concerning (V). */
  public static final double WARNING_V = 9.0;

  private static final int TIME_SERIES_MAX_POINTS = 1500;

  private static final List<String> VOLTAGE_ENTRY_PATTERNS = List.of(
      "robotcontroller/batteryvoltage",
      "batteryvoltage",
      "battery_voltage",
      "input_voltage",
      "inputvoltage",
      "pdh/voltage",
      "pdp/voltage",
      "/voltage"
  );

  // ======================================================================
  // Public entry points
  // ======================================================================

  public BatterySummary summarize(LogData log) {
    String entry = findBatteryEntry(log);
    if (entry == null) return BatterySummary.empty();
    var values = log.values().get(entry);
    if (values == null || values.isEmpty()) return BatterySummary.empty();
    return computeSummary(entry, values);
  }

  public BatteryDetail detail(LogData log) {
    String entry = findBatteryEntry(log);
    if (entry == null) return BatteryDetail.empty();
    var values = log.values().get(entry);
    if (values == null || values.isEmpty()) return BatteryDetail.empty();
    var summary = computeSummary(entry, values);
    var series = downsample(values, TIME_SERIES_MAX_POINTS);
    return new BatteryDetail(summary, series);
  }

  // ======================================================================
  // Core computation
  // ======================================================================

  private BatterySummary computeSummary(String entry, List<TimestampedValue> values) {
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double sum = 0.0;
    int count = 0;
    int brownout1 = 0;
    int brownout2 = 0;
    int warning = 0;

    for (var tv : values) {
      Double v = toDouble(tv.value());
      if (v == null || !Double.isFinite(v)) continue;
      count++;
      sum += v;
      if (v < min) min = v;
      if (v > max) max = v;
      if (v <= BROWNOUT_V_RIO1) brownout1++;
      if (v <= BROWNOUT_V_RIO2) brownout2++;
      if (v <= WARNING_V) warning++;
    }

    if (count == 0) return BatterySummary.empty();

    double mean = sum / count;

    // Second pass for std deviation (Bessel's correction).
    double varSum = 0.0;
    for (var tv : values) {
      Double v = toDouble(tv.value());
      if (v == null || !Double.isFinite(v)) continue;
      double d = v - mean;
      varSum += d * d;
    }
    double stdDev = count > 1 ? Math.sqrt(varSum / (count - 1)) : 0.0;

    return new BatterySummary(entry, count, min, max, mean, stdDev, brownout1, brownout2, warning);
  }

  private String findBatteryEntry(LogData log) {
    var names = log.entries().keySet();
    for (String pattern : VOLTAGE_ENTRY_PATTERNS) {
      for (String name : names) {
        if (name.toLowerCase().contains(pattern)) {
          return name;
        }
      }
    }
    return null;
  }

  /**
   * Evenly downsamples a time-series to at most {@code maxPoints} samples, preserving
   * the min and max in each bucket so charts still show voltage dips clearly.
   */
  static JsonArray downsample(List<TimestampedValue> values, int maxPoints) {
    var out = new JsonArray();
    int n = values.size();
    if (n == 0) return out;
    if (n <= maxPoints) {
      for (var tv : values) {
        Double v = toDouble(tv.value());
        if (v == null || !Double.isFinite(v)) continue;
        var pt = new JsonArray();
        pt.add(tv.timestamp());
        pt.add(v);
        out.add(pt);
      }
      return out;
    }
    // Bucket into roughly maxPoints/2 buckets, emit min+max per bucket so dips survive.
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
      boolean any = false;
      for (int i = start; i < end; i++) {
        var tv = values.get(i);
        Double v = toDouble(tv.value());
        if (v == null || !Double.isFinite(v)) continue;
        any = true;
        if (v < minV) { minV = v; tMin = tv.timestamp(); }
        if (v > maxV) { maxV = v; tMax = tv.timestamp(); }
      }
      if (!any) continue;
      // Emit in timestamp order.
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

  private static Double toDouble(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    if (value instanceof Boolean b) return b ? 1.0 : 0.0;
    return null;
  }

  // ======================================================================
  // Data carriers
  // ======================================================================

  public record BatterySummary(
      String entry,
      int sampleCount,
      double minVoltage,
      double maxVoltage,
      double meanVoltage,
      double stdDevVoltage,
      int brownoutSamples,
      int brownoutSamplesRio2,
      int warningSamples) {

    public static BatterySummary empty() {
      return new BatterySummary(null, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public boolean hasData() {
      return sampleCount > 0;
    }

    public JsonObject toJson() {
      var o = new JsonObject();
      if (!hasData()) {
        o.addProperty("available", false);
        o.add("entry", JsonNull.INSTANCE);
        return o;
      }
      o.addProperty("available", true);
      o.addProperty("entry", entry);
      o.addProperty("sampleCount", sampleCount);
      o.addProperty("minVoltage", minVoltage);
      o.addProperty("maxVoltage", maxVoltage);
      o.addProperty("meanVoltage", meanVoltage);
      o.addProperty("stdDevVoltage", stdDevVoltage);
      o.addProperty("brownoutSamples", brownoutSamples);
      o.addProperty("brownoutSamplesRio2", brownoutSamplesRio2);
      o.addProperty("warningSamples", warningSamples);
      o.addProperty("brownoutThreshold", BROWNOUT_V_RIO1);
      o.addProperty("warningThreshold", WARNING_V);
      return o;
    }
  }

  public record BatteryDetail(BatterySummary summary, JsonArray series) {
    public static BatteryDetail empty() {
      return new BatteryDetail(BatterySummary.empty(), new JsonArray());
    }

    public JsonObject toJson() {
      var o = summary.toJson();
      o.add("series", series);
      return o;
    }
  }
}
