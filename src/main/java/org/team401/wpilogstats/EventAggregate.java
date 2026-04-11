package org.team401.wpilogstats;

import com.google.gson.JsonObject;

/**
 * Rolls per-log battery and current statistics into event-level aggregates.
 *
 * <p>Aggregates are intentionally simple — min, max, mean of per-log values — so the
 * browser can still show richer detail on the per-log view. Pre-computing higher-order
 * stats here would hide information.
 */
class EventAggregate {

  // Battery — per-log mins (how low did voltage go in each match) and per-log means.
  private int batteryLogs = 0;
  private double sumMinVoltage = 0.0;
  private double overallMin = Double.POSITIVE_INFINITY;
  private double overallMax = Double.NEGATIVE_INFINITY;
  private int totalBrownoutSamples = 0;

  // Current — per-log total current averages.
  private int currentLogs = 0;
  private double sumMeanTotalCurrent = 0.0;
  private double maxPeakTotalCurrent = 0.0;

  void accept(BatteryAnalyzer.BatterySummary battery, CurrentAnalyzer.CurrentSummary current) {
    if (battery != null && battery.hasData()) {
      batteryLogs++;
      sumMinVoltage += battery.minVoltage();
      overallMin = Math.min(overallMin, battery.minVoltage());
      overallMax = Math.max(overallMax, battery.maxVoltage());
      totalBrownoutSamples += battery.brownoutSamples();
    }
    if (current != null && current.hasData()) {
      currentLogs++;
      sumMeanTotalCurrent += current.meanTotalCurrent();
      maxPeakTotalCurrent = Math.max(maxPeakTotalCurrent, current.peakTotalCurrent());
    }
  }

  JsonObject toJson() {
    var result = new JsonObject();
    var battery = new JsonObject();
    battery.addProperty("matchesAnalyzed", batteryLogs);
    if (batteryLogs > 0) {
      battery.addProperty("avgOfMinVoltage", sumMinVoltage / batteryLogs);
      battery.addProperty("overallMinVoltage", overallMin);
      battery.addProperty("overallMaxVoltage", overallMax);
      battery.addProperty("totalBrownoutSamples", totalBrownoutSamples);
    }
    result.add("battery", battery);

    var current = new JsonObject();
    current.addProperty("matchesAnalyzed", currentLogs);
    if (currentLogs > 0) {
      current.addProperty("avgMeanTotalCurrent", sumMeanTotalCurrent / currentLogs);
      current.addProperty("peakTotalCurrent", maxPeakTotalCurrent);
    }
    result.add("current", current);
    return result;
  }
}
