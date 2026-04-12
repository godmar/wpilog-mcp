package org.team401.wpilogstats;

import java.util.List;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.TimestampedValue;
import org.triplehelix.wpilogmcp.tools.ToolUtils;

/**
 * Derives FRC match phase boundaries (match start, auto start/end, teleop start/end)
 * from DriverStation mode entries in a parsed WPILOG.
 *
 * <p>This is a trimmed version of the logic inside {@code GetMatchPhasesTool} in the
 * MCP tool package — the stats server can't invoke an MCP tool directly, so we
 * re-derive the boundaries locally. We intentionally skip endgame detection and
 * game-knowledge-base fallbacks; the UI only needs the three phase markers.
 *
 * <p>Handles the FMS quirks the MCP tool handles:
 * <ul>
 *   <li>The FMS sets {@code Autonomous=true} before {@code Enabled=true} during the
 *       pre-match countdown. We count the auto phase as starting only once both are
 *       true simultaneously.
 *   <li>Between auto and teleop the FMS imposes a ~2s disabled gap. Teleop start is
 *       detected as the first re-enable after auto ends, or at auto end if the robot
 *       stayed enabled (practice mode, no FMS).
 * </ul>
 */
public final class MatchPhaseDetector {

  /** All timestamps are in seconds (the same units as LogData time series). */
  public record MatchPhases(
      Double matchStart,   // first Enabled=true
      Double matchEnd,     // last Enabled=false after first enable
      Double autoStart,
      Double autoEnd,
      Double teleopStart,
      Double teleopEnd) {

    public boolean isEmpty() {
      return matchStart == null && autoStart == null && teleopStart == null;
    }
  }

  private MatchPhaseDetector() {}

  public static MatchPhases detect(LogData log) {
    String enabledEntry = null;
    String autoEntry = null;
    for (var name : log.entries().keySet()) {
      String lower = name.toLowerCase();
      if (lower.contains("driverstation") && lower.contains("enabled") && enabledEntry == null) {
        enabledEntry = name;
      }
      if (lower.contains("driverstation")
          && (lower.contains("autonomous") || lower.contains("auto"))
          && !lower.contains("command")
          && autoEntry == null) {
        autoEntry = name;
      }
    }

    List<TimestampedValue> enabledValues =
        enabledEntry != null ? log.values().get(enabledEntry) : null;
    List<TimestampedValue> autoValues =
        autoEntry != null ? log.values().get(autoEntry) : null;

    // Match start from the first enable transition. (matchEnd is computed
    // later, after we know teleopStart, so the auto→teleop disable gap
    // doesn't get mistaken for the end of the match.)
    Double firstEnable = null;
    if (enabledValues != null) {
      for (var tv : enabledValues) {
        if (tv.value() instanceof Boolean en && en) {
          firstEnable = tv.timestamp();
          break;
        }
      }
    }

    // Auto start / end from DS autonomous entry.
    Double autoStart = null;
    Double autoEnd = null;
    if (autoValues != null) {
      Boolean lastAuto = null;
      for (var tv : autoValues) {
        if (!(tv.value() instanceof Boolean isAuto)) continue;
        if (isAuto && (lastAuto == null || !lastAuto)) {
          // Autonomous flag went true. Only count as match auto-start once the
          // robot is also enabled (otherwise this is the FMS pre-match setup).
          if (ToolUtils.isEnabledAt(enabledValues, tv.timestamp())) {
            if (autoStart == null) autoStart = tv.timestamp();
          } else if (autoStart == null && enabledValues != null) {
            // Find the first Enabled=true after the auto flag went high.
            for (var ev : enabledValues) {
              if (ev.timestamp() > tv.timestamp()
                  && ev.value() instanceof Boolean en && en) {
                autoStart = ev.timestamp();
                break;
              }
            }
          }
        }
        if (!isAuto && lastAuto != null && lastAuto && autoEnd == null) {
          autoEnd = tv.timestamp();
        }
        lastAuto = isAuto;
      }
    }

    // Teleop start: first disable→enable transition after auto ends (FMS gap)
    // or auto end itself if the robot stayed enabled.
    Double teleopStart = null;
    if (autoEnd != null && enabledValues != null) {
      Boolean stateAtAutoEnd = null;
      Boolean lastState = null;
      for (var tv : enabledValues) {
        if (!(tv.value() instanceof Boolean en)) continue;
        if (tv.timestamp() <= autoEnd) stateAtAutoEnd = en;
        if (tv.timestamp() >= autoEnd && en && lastState != null && !lastState) {
          teleopStart = tv.timestamp();
          break;
        }
        lastState = en;
      }
      if (teleopStart == null && Boolean.TRUE.equals(stateAtAutoEnd)) {
        teleopStart = autoEnd;
      }
    }
    if (teleopStart == null && autoEnd != null) {
      teleopStart = autoEnd;
    }

    // matchEnd / teleopEnd = first Enabled=false strictly after teleopStart.
    // If the robot stays enabled through the end of the log (no final
    // disable), fall back to the log's max timestamp so the chart still has
    // a right edge. Any earlier disable (e.g. a brief mid-auto glitch) is NOT
    // a valid "match end" — we explicitly avoid picking those.
    Double matchEnd = null;
    if (enabledValues != null && teleopStart != null) {
      for (var tv : enabledValues) {
        if (!(tv.value() instanceof Boolean en)) continue;
        if (!en && tv.timestamp() > teleopStart) {
          matchEnd = tv.timestamp();
          break;
        }
      }
      if (matchEnd == null) {
        double maxTs = log.maxTimestamp();
        if (maxTs > teleopStart) matchEnd = maxTs;
      }
    }
    Double teleopEnd = matchEnd;
    // Sanity: teleopEnd must be after teleopStart, else treat as unknown.
    if (teleopStart != null && teleopEnd != null && teleopEnd <= teleopStart) {
      teleopEnd = null;
    }

    return new MatchPhases(firstEnable, matchEnd, autoStart, autoEnd, teleopStart, teleopEnd);
  }
}
