package org.team401.wpilogstats;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Computes robot loop timing statistics and chart data for the per-match stats page.
 */
public class LoopTimingAnalyzer {
  private static final int TIME_SERIES_MAX_POINTS = 1500;
  private static final double DEFAULT_THRESHOLD_MS = 20.0;

  private static final List<String> LOOP_ENTRY_PATTERNS = List.of(
      "fullcyclems",
      "looptimems",
      "loop_time_ms",
      "looptime",
      "loop time"
  );

  public LoopTimingDetail detail(LogData log) {
    String entry = findLoopTimingEntry(log);
    if (entry == null) return LoopTimingDetail.empty();
    var values = log.values().get(entry);
    if (values == null || values.isEmpty()) return LoopTimingDetail.empty();

    var samples = normalizedSamples(values, detectConversionFactor(values));
    if (samples.isEmpty()) return LoopTimingDetail.empty();

    return new LoopTimingDetail(computeSummary(entry, samples), downsample(samples));
  }

  private String findLoopTimingEntry(LogData log) {
    var names = log.entries().keySet();
    for (String pattern : LOOP_ENTRY_PATTERNS) {
      for (String name : names) {
        if (name.toLowerCase().contains(pattern)) {
          return name;
        }
      }
    }
    for (String name : names) {
      var lower = name.toLowerCase();
      if (lower.contains("loop") && lower.contains("time")) {
        return name;
      }
    }
    return null;
  }

  private static double detectConversionFactor(List<TimestampedValue> values) {
    var raw = new ArrayList<Double>();
    for (var tv : values) {
      Double v = toDouble(tv.value());
      if (v != null && Double.isFinite(v)) raw.add(v);
    }
    if (raw.isEmpty()) return 1.0;
    raw.sort(Double::compare);
    int n = raw.size();
    double median = n % 2 == 1
        ? raw.get(n / 2)
        : (raw.get(n / 2 - 1) + raw.get(n / 2)) / 2.0;
    if (median >= 0.001 && median < 1.0) return 1000.0;
    if (median > 500.0) return 1.0 / 1000.0;
    return 1.0;
  }

  private static List<Sample> normalizedSamples(
      List<TimestampedValue> values, double conversionFactor) {
    var samples = new ArrayList<Sample>(values.size());
    for (var tv : values) {
      Double v = toDouble(tv.value());
      if (v == null || !Double.isFinite(v)) continue;
      samples.add(new Sample(tv.timestamp(), v * conversionFactor));
    }
    return samples;
  }

  private static LoopTimingSummary computeSummary(String entry, List<Sample> samples) {
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double sum = 0.0;
    int violationCount = 0;

    var sorted = new double[samples.size()];
    for (int i = 0; i < samples.size(); i++) {
      double v = samples.get(i).valueMs();
      sorted[i] = v;
      sum += v;
      if (v < min) min = v;
      if (v > max) max = v;
      if (v > DEFAULT_THRESHOLD_MS) violationCount++;
    }
    java.util.Arrays.sort(sorted);
    double mean = sum / samples.size();
    return new LoopTimingSummary(
        entry,
        samples.size(),
        min,
        max,
        mean,
        percentile(sorted, 0.95),
        percentile(sorted, 0.99),
        DEFAULT_THRESHOLD_MS,
        violationCount);
  }

  private static JsonArray downsample(List<Sample> samples) {
    var out = new JsonArray();
    int n = samples.size();
    if (n == 0) return out;
    if (n <= TIME_SERIES_MAX_POINTS) {
      for (var sample : samples) out.add(point(sample.timestamp(), sample.valueMs()));
      return out;
    }

    int buckets = Math.max(1, TIME_SERIES_MAX_POINTS / 2);
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
        var sample = samples.get(i);
        if (sample.valueMs() < minV) {
          minV = sample.valueMs();
          tMin = sample.timestamp();
        }
        if (sample.valueMs() > maxV) {
          maxV = sample.valueMs();
          tMax = sample.timestamp();
        }
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

  private static double percentile(double[] sorted, double p) {
    if (sorted.length == 0) return 0.0;
    if (sorted.length == 1) return sorted[0];
    double pos = p * (sorted.length - 1);
    int lower = (int) Math.floor(pos);
    int upper = (int) Math.ceil(pos);
    if (lower == upper) return sorted[lower];
    double weight = pos - lower;
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight;
  }

  private static Double toDouble(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    return null;
  }

  private static JsonArray point(double t, double v) {
    var pt = new JsonArray();
    pt.add(t);
    pt.add(v);
    return pt;
  }

  private record Sample(double timestamp, double valueMs) {}

  public record LoopTimingSummary(
      String entry,
      int sampleCount,
      double minMs,
      double maxMs,
      double meanMs,
      double p95Ms,
      double p99Ms,
      double thresholdMs,
      int violationCount) {

    public static LoopTimingSummary empty() {
      return new LoopTimingSummary(null, 0, 0, 0, 0, 0, 0, DEFAULT_THRESHOLD_MS, 0);
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
      o.addProperty("minMs", minMs);
      o.addProperty("maxMs", maxMs);
      o.addProperty("meanMs", meanMs);
      o.addProperty("p95Ms", p95Ms);
      o.addProperty("p99Ms", p99Ms);
      o.addProperty("thresholdMs", thresholdMs);
      o.addProperty("violationCount", violationCount);
      return o;
    }
  }

  public record LoopTimingDetail(LoopTimingSummary summary, JsonArray series) {
    public static LoopTimingDetail empty() {
      return new LoopTimingDetail(LoopTimingSummary.empty(), new JsonArray());
    }

    public JsonObject toJson() {
      var o = summary.toJson();
      o.add("series", series);
      return o;
    }
  }
}
