package org.team401.wpilogstats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.team401.wpilogstats.MatchPhaseDetector.MatchPhases;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;

/**
 * Photonvision-style vision quality summary. For each camera the team logs a
 * pair of arrays — {@code RobotPosesAccepted} and {@code RobotPosesRejected} —
 * and AdvantageKit emits an entry update each cycle the array changes.
 *
 * <p>The analyzer counts non-empty array updates and total poses inside the
 * match window for each camera, and joins in the per-frame
 * {@code AverageTagDistanceM} stream when present. The output is intentionally
 * raw counts — peak/mean numbers a UI can read at a glance — rather than a
 * single "vision health" score, so a reader can spot a single bad camera
 * directly.
 */
public class VisionAnalyzer {

  private static final String ACCEPTED_SUFFIX = "/robotposesaccepted";
  private static final String REJECTED_SUFFIX = "/robotposesrejected";
  private static final String DISTANCE_SUFFIX = "/averagetagdistancem";

  public JsonObject summarize(LogData log, MatchPhases phases) {
    Double matchStart = phases != null ? phases.matchStart() : null;
    Double matchEnd = phases != null ? phases.matchEnd() : null;
    double windowSeconds = (matchStart != null && matchEnd != null)
        ? Math.max(0.001, matchEnd - matchStart) : Double.NaN;

    // Index entries by lowercased name for cheap suffix lookups.
    var byCamera = new LinkedHashMap<String, CameraEntries>();
    for (var entryName : log.entries().keySet()) {
      String lower = entryName.toLowerCase();
      String camera = cameraLabelForSuffix(entryName, lower, ACCEPTED_SUFFIX);
      if (camera != null) {
        byCamera.computeIfAbsent(camera, CameraEntries::new).accepted = entryName;
        continue;
      }
      camera = cameraLabelForSuffix(entryName, lower, REJECTED_SUFFIX);
      if (camera != null) {
        byCamera.computeIfAbsent(camera, CameraEntries::new).rejected = entryName;
        continue;
      }
      camera = cameraLabelForSuffix(entryName, lower, DISTANCE_SUFFIX);
      if (camera != null) {
        byCamera.computeIfAbsent(camera, CameraEntries::new).distance = entryName;
      }
    }

    var out = new JsonObject();
    out.addProperty("available", !byCamera.isEmpty());
    if (byCamera.isEmpty()) return out;

    var perCamera = new ArrayList<CameraSummary>();
    for (var entries : byCamera.values()) {
      perCamera.add(buildCamera(log, entries, matchStart, matchEnd, windowSeconds));
    }

    perCamera.sort(Comparator.comparing(c -> c.name));

    var arr = new JsonArray();
    long totalAccepted = 0, totalRejected = 0;
    for (var cam : perCamera) {
      arr.add(cam.toJson());
      totalAccepted += cam.acceptedPoses;
      totalRejected += cam.rejectedPoses;
    }
    out.add("cameras", arr);
    out.addProperty("totalAcceptedPoses", totalAccepted);
    out.addProperty("totalRejectedPoses", totalRejected);
    long grandTotal = totalAccepted + totalRejected;
    if (grandTotal > 0) {
      out.addProperty("rejectionRate", (double) totalRejected / grandTotal);
    }
    return out;
  }

  // ------------------------------------------------------------------
  // Internals
  // ------------------------------------------------------------------

  private CameraSummary buildCamera(
      LogData log, CameraEntries e, Double matchStart, Double matchEnd, double windowSeconds) {
    var s = new CameraSummary(e.name);

    var acceptedVals = e.accepted != null ? log.values().get(e.accepted) : null;
    if (acceptedVals != null) {
      countArrayUpdates(acceptedVals, matchStart, matchEnd, s, true);
    }
    var rejectedVals = e.rejected != null ? log.values().get(e.rejected) : null;
    if (rejectedVals != null) {
      countArrayUpdates(rejectedVals, matchStart, matchEnd, s, false);
    }

    long total = s.acceptedPoses + s.rejectedPoses;
    if (total > 0) s.rejectionRate = (double) s.rejectedPoses / total;
    if (Double.isFinite(windowSeconds) && windowSeconds > 0) {
      s.detectionsHz = s.acceptedNonemptyUpdates / windowSeconds;
    }

    var distanceVals = e.distance != null ? log.values().get(e.distance) : null;
    if (distanceVals != null) {
      summarizeDistance(distanceVals, matchStart, matchEnd, s);
    }
    return s;
  }

  private static void countArrayUpdates(
      List<TimestampedValue> values, Double matchStart, Double matchEnd,
      CameraSummary s, boolean accepted) {
    long updates = 0, nonempty = 0, poses = 0;
    for (var tv : values) {
      if (!inMatch(tv.timestamp(), matchStart, matchEnd)) continue;
      updates++;
      if (tv.value() instanceof List<?> list && !list.isEmpty()) {
        nonempty++;
        poses += list.size();
      }
    }
    if (accepted) {
      s.acceptedUpdates = updates;
      s.acceptedNonemptyUpdates = nonempty;
      s.acceptedPoses = poses;
    } else {
      s.rejectedUpdates = updates;
      s.rejectedNonemptyUpdates = nonempty;
      s.rejectedPoses = poses;
    }
  }

  private static void summarizeDistance(
      List<TimestampedValue> values, Double matchStart, Double matchEnd, CameraSummary s) {
    int n = 0;
    double sum = 0, max = Double.NEGATIVE_INFINITY;
    Double prevT = null;
    double maxGap = 0;
    for (var tv : values) {
      if (!inMatch(tv.timestamp(), matchStart, matchEnd)) continue;
      Double v = toDouble(tv.value());
      if (v == null || !Double.isFinite(v)) continue;
      n++;
      sum += v;
      if (v > max) max = v;
      if (prevT != null) {
        double gap = tv.timestamp() - prevT;
        if (gap > maxGap) maxGap = gap;
      }
      prevT = tv.timestamp();
    }
    if (n > 0) {
      s.distanceCount = n;
      s.distanceMean = sum / n;
      s.distanceMax = max;
      s.distanceMaxGap = maxGap;
    }
  }

  private static boolean inMatch(double t, Double lo, Double hi) {
    if (lo != null && t < lo) return false;
    if (hi != null && t > hi) return false;
    return true;
  }

  private static Double toDouble(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    return null;
  }

  /**
   * If {@code lower} ends with {@code suffix}, returns the parent path's last
   * segment as a camera label; otherwise returns null. The parent segment is
   * extracted from the original (mixed-case) entry name so the label keeps
   * its original casing (e.g. {@code Camera0}, not {@code camera0}).
   */
  private static String cameraLabelForSuffix(String entryName, String lower, String suffix) {
    if (!lower.endsWith(suffix)) return null;
    int slash = entryName.lastIndexOf('/');
    if (slash <= 0) return null;
    String parent = entryName.substring(0, slash);
    int parentSlash = parent.lastIndexOf('/');
    String label = parent.substring(parentSlash + 1);
    if (label.isEmpty()) return null;
    // Some teams publish an aggregated "Summary" / "All" pseudo-camera whose
    // accepted/rejected arrays are unions of the per-camera streams. Skip
    // them so the per-camera table and totals don't double-count.
    String low = label.toLowerCase();
    if (low.equals("summary") || low.equals("all") || low.equals("combined")) return null;
    return label;
  }

  // ------------------------------------------------------------------
  // Data carriers
  // ------------------------------------------------------------------

  private static final class CameraEntries {
    final String name;
    String accepted;
    String rejected;
    String distance;
    CameraEntries(String name) { this.name = name; }
  }

  private static final class CameraSummary {
    final String name;
    long acceptedUpdates, acceptedNonemptyUpdates, acceptedPoses;
    long rejectedUpdates, rejectedNonemptyUpdates, rejectedPoses;
    Double rejectionRate;
    Double detectionsHz;
    int distanceCount;
    Double distanceMean;
    Double distanceMax;
    Double distanceMaxGap;

    CameraSummary(String name) { this.name = name; }

    JsonObject toJson() {
      var o = new JsonObject();
      o.addProperty("name", name);
      o.addProperty("acceptedUpdates", acceptedUpdates);
      o.addProperty("acceptedNonemptyUpdates", acceptedNonemptyUpdates);
      o.addProperty("acceptedPoses", acceptedPoses);
      o.addProperty("rejectedUpdates", rejectedUpdates);
      o.addProperty("rejectedNonemptyUpdates", rejectedNonemptyUpdates);
      o.addProperty("rejectedPoses", rejectedPoses);
      if (rejectionRate != null) o.addProperty("rejectionRate", rejectionRate);
      if (detectionsHz != null) o.addProperty("detectionsHz", detectionsHz);
      if (distanceMean != null) {
        o.addProperty("distanceCount", distanceCount);
        o.addProperty("distanceMean", distanceMean);
        o.addProperty("distanceMax", distanceMax);
        o.addProperty("distanceMaxGapSec", distanceMaxGap);
      }
      return o;
    }
  }
}
