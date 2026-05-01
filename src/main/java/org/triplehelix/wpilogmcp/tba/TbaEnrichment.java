package org.triplehelix.wpilogmcp.tba;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogDirectory.LogFileInfo;

/**
 * Service for enriching log file information with TBA data.
 */
public class TbaEnrichment {
  private static final Logger logger = LoggerFactory.getLogger(TbaEnrichment.class);
  private static final java.util.regex.Pattern YEAR_PATTERN =
      java.util.regex.Pattern.compile("(\\d{4})");
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("h:mm a");

  /** Singleton instance. */
  private static TbaEnrichment instance;

  /** TBA client for API access. */
  private final TbaClient client;

  /** Private constructor for singleton pattern. */
  private TbaEnrichment() {
    this.client = TbaClient.getInstance();
  }

  /**
   * Gets the singleton instance.
   *
   * @return The singleton instance
   */
  public static synchronized TbaEnrichment getInstance() {
    if (instance == null) {
      instance = new TbaEnrichment();
    }
    return instance;
  }

  /**
   * Checks if a log file is eligible for TBA enrichment.
   */
  public boolean isEligibleForEnrichment(LogFileInfo logInfo) {
    if (logInfo.eventName() == null || logInfo.eventName().isEmpty()) {
      return false;
    }

    var matchType = logInfo.matchType();
    if (matchType == null) {
      return false;
    }

    var lower = matchType.toLowerCase();
    if (lower.contains("simulation") || lower.contains("sim")) {
      return false;
    }

    if (lower.contains("practice")) {
      return false;
    }

    if (lower.contains("replay")) {
      return false;
    }

    if (logInfo.matchNumber() == null) {
      return false;
    }

    if (logInfo.teamNumber() == null) {
      return false;
    }

    return lower.contains("qualification") || lower.contains("qual")
        || lower.contains("semifinal") || lower.contains("semi")
        || lower.contains("final") || lower.contains("elimination")
        || lower.contains("quarterfinal");
  }

  /**
   * Extracts the year from a log file for TBA queries.
   */
  public int extractYear(LogFileInfo logInfo) {
    var eventName = logInfo.eventName();
    if (eventName != null && eventName.length() >= 4) {
      try {
        var matcher = YEAR_PATTERN.matcher(eventName);
        if (matcher.find()) {
          int year = Integer.parseInt(matcher.group(1));
          if (year >= 1992 && year <= 2100) {
            return year;
          }
        }
      } catch (NumberFormatException ignored) {
      }
    }

    long modTime = logInfo.lastModified();
    if (modTime > 0) {
      var date = Instant.ofEpochMilli(modTime).atZone(ZoneId.systemDefault()).toLocalDate();
      return date.getYear();
    }

    return LocalDate.now().getYear();
  }

  private String normalizeEventCode(String eventCode) {
    if (eventCode == null) return null;
    return eventCode.replaceAll("^\\d{4}", "").toLowerCase();
  }

  /**
   * Gets the timezone for an event from TBA API.
   * Falls back to America/New_York if not available (most FRC events are in Eastern US).
   */
  private ZoneId getEventTimezone(int year, String eventCode) {
    try {
      var eventOpt = client.getEvent(year, eventCode);
      if (eventOpt.isPresent()) {
        var event = eventOpt.get();
        if (event.has("timezone") && !event.get("timezone").isJsonNull()) {
          var tz = event.get("timezone").getAsString();
          return ZoneId.of(tz);
        }
      }
    } catch (Exception e) {
      logger.debug("Failed to get event timezone for {}{}: {}", year, eventCode, e.getMessage());
    }
    // Default to Eastern time for most US FRC events
    return ZoneId.of("America/New_York");
  }

  /**
   * Formats an epoch timestamp as a human-readable local time string.
   */
  private String formatMatchTime(Long epochSeconds, ZoneId timezone) {
    if (epochSeconds == null) return null;
    var zdt = Instant.ofEpochSecond(epochSeconds).atZone(timezone);
    return zdt.format(TIME_FORMAT);
  }

  /**
   * Enriches a log with TBA match data.
   */
  public Optional<JsonObject> enrichLog(LogFileInfo logInfo) {
    if (!client.isAvailable()) {
      return Optional.empty();
    }

    if (!isEligibleForEnrichment(logInfo)) {
      logger.trace("Log {} not eligible for TBA enrichment", logInfo.filename());
      return Optional.empty();
    }

    int year = extractYear(logInfo);
    var eventCode = normalizeEventCode(logInfo.eventName());
    var matchType = logInfo.matchType();
    int matchNumber = logInfo.matchNumber();
    int teamNumber = logInfo.teamNumber();

    logger.debug("Requesting TBA enrichment for {}: year={}, event={}, match={}, team={}",
        logInfo.filename(), year, eventCode, (matchType + " " + matchNumber), teamNumber);

    // Resolve the full TBA match object (handles both direct and smart elimination lookup)
    var logTimestamp = logInfo.getBestTimestamp();
    var matchOpt = client.resolveMatchObject(
        year, eventCode, matchType, matchNumber, teamNumber, logTimestamp);

    if (matchOpt.isEmpty()) {
      logger.info("No TBA data found for {} (year={}, event={}, type={}, match={}, team={})",
          logInfo.filename(), year, eventCode, matchType, matchNumber, teamNumber);
      return Optional.empty();
    }

    var match = matchOpt.get();
    var teamKey = "frc" + teamNumber;

    // Find team's alliance in the match
    String teamAlliance = null;
    var alliances = match.getAsJsonObject("alliances");
    if (alliances != null) {
      for (var alliance : new String[]{"red", "blue"}) {
        var allianceData = alliances.getAsJsonObject(alliance);
        if (allianceData == null) continue;
        var teamKeys = allianceData.getAsJsonArray("team_keys");
        if (teamKeys == null) continue;
        for (var t : teamKeys) {
          if (teamKey.equals(t.getAsString())) {
            teamAlliance = alliance;
            break;
          }
        }
        if (teamAlliance != null) break;
      }
    }

    if (teamAlliance == null) {
      logger.debug("Team {} not found in match alliances", teamNumber);
      return Optional.empty();
    }

    logger.debug("Enriched {} with TBA data: alliance={}", logInfo.filename(), teamAlliance);

    // Get event timezone for formatting times
    var eventTimezone = getEventTimezone(year, eventCode);

    var tba = new JsonObject();
    tba.addProperty("team_number", teamNumber);
    tba.addProperty("alliance", teamAlliance);

    // Score
    var teamAllianceData = alliances.getAsJsonObject(teamAlliance);
    if (teamAllianceData != null && teamAllianceData.has("score")) {
      tba.addProperty("score", teamAllianceData.get("score").getAsInt());
    }

    // Win/loss
    var winningAlliance = match.has("winning_alliance")
        ? match.get("winning_alliance").getAsString() : null;
    if (winningAlliance != null && !winningAlliance.isEmpty()) {
      tba.addProperty("won", teamAlliance.equals(winningAlliance));
    }

    // Opponent score
    var opponentAlliance = "red".equals(teamAlliance) ? "blue" : "red";
    var opponentData = alliances.getAsJsonObject(opponentAlliance);
    if (opponentData != null && opponentData.has("score")) {
      tba.addProperty("opponent_score", opponentData.get("score").getAsInt());
    }

    // Timestamps
    if (match.has("actual_time") && !match.get("actual_time").isJsonNull()) {
      long actualTime = match.get("actual_time").getAsLong();
      tba.addProperty("actual_time", actualTime);
      var formatted = formatMatchTime(actualTime, eventTimezone);
      if (formatted != null) tba.addProperty("actual_time_local", formatted);
    }
    if (match.has("time") && !match.get("time").isJsonNull()) {
      long scheduledTime = match.get("time").getAsLong();
      tba.addProperty("scheduled_time", scheduledTime);
      var formatted = formatMatchTime(scheduledTime, eventTimezone);
      if (formatted != null) tba.addProperty("scheduled_time_local", formatted);
    }

    // TBA match key for linking to the match page
    if (match.has("key") && !match.get("key").isJsonNull()) {
      tba.addProperty("match_key", match.get("key").getAsString());
    }

    // Video links (typically YouTube streams)
    if (match.has("videos") && match.get("videos").isJsonArray()) {
      var videos = new JsonArray();
      for (var videoEl : match.getAsJsonArray("videos")) {
        if (!videoEl.isJsonObject()) continue;
        var video = videoEl.getAsJsonObject();
        var type = video.has("type") ? video.get("type").getAsString() : "";
        var key = video.has("key") ? video.get("key").getAsString() : "";
        if (!key.isEmpty()) {
          var v = new JsonObject();
          v.addProperty("type", type);
          v.addProperty("key", key);
          videos.add(v);
        }
      }
      if (videos.size() > 0) {
        tba.add("videos", videos);
      }
    }

    return Optional.of(tba);
  }

  /**
   * Gets the corrected match start time from TBA.
   */
  public Optional<Long> getMatchStartTime(LogFileInfo logInfo) {
    if (!client.isAvailable() || !isEligibleForEnrichment(logInfo)) {
      return Optional.empty();
    }

    int year = extractYear(logInfo);
    var eventCode = normalizeEventCode(logInfo.eventName());
    var matchType = logInfo.matchType();
    int matchNumber = logInfo.matchNumber();

    var matchOpt = client.getMatch(year, eventCode, matchType, matchNumber);
    if (matchOpt.isEmpty()) {
      return Optional.empty();
    }

    var match = matchOpt.get();

    if (match.has("actual_time") && !match.get("actual_time").isJsonNull()) {
      return Optional.of(match.get("actual_time").getAsLong());
    }
    if (match.has("time") && !match.get("time").isJsonNull()) {
      return Optional.of(match.get("time").getAsLong());
    }

    return Optional.empty();
  }
}
