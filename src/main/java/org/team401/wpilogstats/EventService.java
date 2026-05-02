package org.team401.wpilogstats;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogData;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;
import org.triplehelix.wpilogmcp.tba.TbaEnrichment;

/**
 * Service that walks the logs root directory, enumerates events (top-level subdirectories
 * such as {@code 2026-dcmp}), and delegates per-log analysis to the analyzers.
 *
 * <p>Security: all file access is rooted at {@code logsRoot} and validated to sit inside
 * that directory. The underlying {@link LogManager} also enforces its own
 * {@code SecurityValidator} checks.
 */
public class EventService {
  private static final Logger logger = LoggerFactory.getLogger(EventService.class);
  private static final int MAX_LOGS_PER_EVENT = 500;
  private static final int MAX_EVENT_SUMMARY_CACHE_ENTRIES = 32;
  private static final int MAX_LOG_DETAIL_CACHE_ENTRIES = 128;

  /**
   * Returns true for logs that should be excluded from the stats UI. Sim logs
   * contain synthetic telemetry (per-module SupplyCurrentAmps, totalizer entries)
   * that pollutes real-match dashboards with labels like "TotalCurrentCalculator total"
   * or "Module2 Drive" that don't appear on the field, so we skip them entirely.
   */
  private static boolean isExcluded(Path path) {
    return path.getFileName().toString().toLowerCase().contains("_sim");
  }

  private final Path logsRoot;
  private final BatteryAnalyzer batteryAnalyzer = new BatteryAnalyzer();
  private final CurrentAnalyzer currentAnalyzer = new CurrentAnalyzer();
  private final VisionAnalyzer visionAnalyzer = new VisionAnalyzer();
  private final LoopTimingAnalyzer loopTimingAnalyzer = new LoopTimingAnalyzer();
  private final Map<String, CachedJson<EventFingerprint>> eventSummaryCache =
      new ConcurrentHashMap<>();
  private final Map<String, CachedJson<LogFingerprint>> logDetailCache =
      new ConcurrentHashMap<>();

  public EventService(Path logsRoot) {
    this.logsRoot = logsRoot.toAbsolutePath().normalize();
  }

  // ======================================================================
  // Event listing
  // ======================================================================

  public record EventInfo(String name, int logCount, long lastModifiedMillis) {}

  public List<EventInfo> listEvents() throws IOException {
    if (!Files.isDirectory(logsRoot)) {
      return List.of();
    }
    var result = new ArrayList<EventInfo>();
    try (Stream<Path> stream = Files.list(logsRoot)) {
      for (var entry : stream.toList()) {
        if (!Files.isDirectory(entry)) continue;
        String name = entry.getFileName().toString();
        if (name.startsWith(".")) continue;
        var stats = scanEventDirectory(entry);
        if (stats.logCount() == 0) continue;
        result.add(new EventInfo(name, stats.logCount(), stats.lastModifiedMillis()));
      }
    }
    result.sort(Comparator.comparing(EventInfo::name).reversed());
    return result;
  }

  private record DirStats(int logCount, long lastModifiedMillis) {}

  private DirStats scanEventDirectory(Path eventDir) {
    int count = 0;
    long newest = 0;
    try (Stream<Path> files = Files.walk(eventDir, 3)) {
      for (var p : files.toList()) {
        if (!Files.isRegularFile(p)) continue;
        if (!p.getFileName().toString().toLowerCase().endsWith(".wpilog")) continue;
        if (isExcluded(p)) continue;
        count++;
        try {
          long mod = Files.getLastModifiedTime(p).toMillis();
          if (mod > newest) newest = mod;
        } catch (IOException ignored) {
          // best effort
        }
      }
    } catch (IOException e) {
      logger.debug("Failed to scan {}: {}", eventDir, e.getMessage());
    }
    return new DirStats(count, newest);
  }

  // ======================================================================
  // Event summary (aggregate across all logs in an event)
  // ======================================================================

  public JsonObject summarizeEvent(String eventName) throws IOException {
    Path eventDir = resolveEvent(eventName);
    var logFiles = findLogs(eventDir);
    if (logFiles.isEmpty()) {
      throw new IllegalArgumentException("No .wpilog files under event: " + eventName);
    }

    var fingerprint = eventFingerprint(logFiles);
    var cached = eventSummaryCache.get(eventName);
    if (cached != null && cached.fingerprint().equals(fingerprint)) {
      logger.debug("Event summary cache hit for {}", eventName);
      return cached.json().deepCopy();
    }

    logger.debug("Event summary cache miss for {}", eventName);
    var summary = buildEventSummary(eventName, logFiles);
    putCacheEntry(eventSummaryCache, eventName, new CachedJson<>(fingerprint, summary),
        MAX_EVENT_SUMMARY_CACHE_ENTRIES);
    return summary.deepCopy();
  }

  private JsonObject buildEventSummary(String eventName, List<Path> logFiles) {
    var summary = new JsonObject();
    summary.addProperty("event", eventName);
    summary.addProperty("logCount", logFiles.size());

    var perLog = new JsonArray();
    var aggregate = new EventAggregate();
    var metadataByPath = loadMetadataIndex();

    int analyzed = 0;
    for (var path : logFiles) {
      try {
        LogData log = LogManager.getInstance().loadLog(path.toString());
        var phases = MatchPhaseDetector.detect(log);

        var batteryStats = batteryAnalyzer.summarize(log);
        var currentSummary = currentAnalyzer.summarize(log, phases);

        var entry = new JsonObject();
        entry.addProperty("file", relativeName(path));
        entry.addProperty("displayName", displayName(path, metadataByPath));
        entry.addProperty("duration", log.duration());
        entry.add("battery", batteryStats.toJson());
        entry.add("current", currentSummary.toJson());

        // TBA enrichment is loaded asynchronously via /api/tba/events/{event} so a
        // slow Blue Alliance API never blocks the initial page render.

        perLog.add(entry);

        aggregate.accept(batteryStats, currentSummary);
        analyzed++;
      } catch (Exception e) {
        logger.warn("Skipping {}: {}", path, e.getMessage());
        var entry = new JsonObject();
        entry.addProperty("file", relativeName(path));
        entry.addProperty("error", e.getMessage());
        perLog.add(entry);
      }
    }

    summary.addProperty("analyzedCount", analyzed);
    summary.add("logs", perLog);
    summary.add("aggregate", aggregate.toJson());
    return summary;
  }

  // ======================================================================
  // Per-log detail (time series + stats)
  // ======================================================================

  public String logDetailEtag(String eventName, String logName) throws IOException {
    Path logPath = resolveLogPath(eventName, logName, true);
    return logFingerprint(logPath).etag();
  }

  public JsonObject detailLog(String eventName, String logName) throws IOException {
    Path logPath = resolveLogPath(eventName, logName, true);

    var fingerprint = logFingerprint(logPath);
    String cacheKey = logPath.toString();
    var cached = logDetailCache.get(cacheKey);
    if (cached != null && cached.fingerprint().equals(fingerprint)) {
      logger.debug("Log detail cache hit for {}", logPath);
      return cached.json().deepCopy();
    }

    logger.debug("Log detail cache miss for {}", logPath);
    var result = buildLogDetail(eventName, logPath);
    putCacheEntry(logDetailCache, cacheKey, new CachedJson<>(fingerprint, result),
        MAX_LOG_DETAIL_CACHE_ENTRIES);
    return result.deepCopy();
  }

  private JsonObject buildLogDetail(String eventName, Path logPath) throws IOException {
    LogData log;
    try {
      log = LogManager.getInstance().loadLog(logPath.toString());
    } catch (IOException e) {
      throw new IOException("Failed to load log: " + e.getMessage(), e);
    }

    var result = new JsonObject();
    result.addProperty("event", eventName);
    result.addProperty("file", relativeName(logPath));
    result.addProperty("displayName", displayName(logPath, loadMetadataIndex()));
    result.addProperty("duration", log.duration());
    result.addProperty("minTimestamp", log.minTimestamp());
    result.addProperty("maxTimestamp", log.maxTimestamp());
    result.addProperty("entryCount", log.entryCount());

    var phases = MatchPhaseDetector.detect(log);
    result.add("battery", batteryAnalyzer.detail(log).toJson());
    result.add("loopTiming", loopTimingAnalyzer.detail(log).toJson());
    result.add("current", currentAnalyzer.detail(log, phases).toJson());
    result.add("vision", visionAnalyzer.summarize(log, phases));
    result.add("phases", phasesJson(phases));

    // TBA enrichment is loaded asynchronously via /api/tba/logs/{event}/{name} so a
    // slow Blue Alliance API never blocks the initial page render.

    return result;
  }

  // ======================================================================
  // TBA enrichment (loaded asynchronously by the UI)
  // ======================================================================

  /**
   * Per-log TBA data for every log in an event. Returned as a map keyed by the same
   * relative {@code file} string used elsewhere so the UI can patch rows in place.
   * Logs without TBA data are simply omitted from the map.
   */
  public JsonObject tbaForEvent(String eventName) throws IOException {
    Path eventDir = resolveEvent(eventName);
    var logFiles = findLogs(eventDir);
    var metadataByPath = loadMetadataIndex();

    var result = new JsonObject();
    result.addProperty("event", eventName);
    var byFile = new JsonObject();
    for (var path : logFiles) {
      String absKey = path.toAbsolutePath().normalize().toString();
      var logInfo = metadataByPath.get(absKey);
      if (logInfo == null) continue;
      try {
        TbaEnrichment.getInstance().enrichLog(logInfo)
            .ifPresent(tba -> byFile.add(relativeName(path), tba));
      } catch (Exception e) {
        logger.debug("TBA enrichment failed for {}: {}", path, e.getMessage());
      }
    }
    result.add("byFile", byFile);
    return result;
  }

  /**
   * TBA data for a single log, or an empty {@code tba} key when none is available.
   */
  public JsonObject tbaForLog(String eventName, String logName) throws IOException {
    Path logPath = resolveLogPath(eventName, logName, false);

    var result = new JsonObject();
    result.addProperty("event", eventName);
    result.addProperty("file", relativeName(logPath));

    var metadataByPath = loadMetadataIndex();
    String absKey = logPath.toAbsolutePath().normalize().toString();
    var logInfo = metadataByPath.get(absKey);
    if (logInfo != null) {
      try {
        TbaEnrichment.getInstance().enrichLog(logInfo)
            .ifPresent(tba -> result.add("tba", tba));
      } catch (Exception e) {
        logger.debug("TBA enrichment failed for {}: {}", logPath, e.getMessage());
      }
    }
    return result;
  }

  private static JsonObject phasesJson(MatchPhaseDetector.MatchPhases p) {
    var o = new JsonObject();
    if (p.matchStart() != null) o.addProperty("matchStart", p.matchStart());
    if (p.matchEnd() != null) o.addProperty("matchEnd", p.matchEnd());
    if (p.autoStart() != null) o.addProperty("autoStart", p.autoStart());
    if (p.autoEnd() != null) o.addProperty("autoEnd", p.autoEnd());
    if (p.teleopStart() != null) o.addProperty("teleopStart", p.teleopStart());
    if (p.teleopEnd() != null) o.addProperty("teleopEnd", p.teleopEnd());
    return o;
  }

  // ======================================================================
  // Helpers
  // ======================================================================

  private record CachedJson<T>(T fingerprint, JsonObject json) {}

  private record EventFingerprint(List<LogFingerprint> logs) {}

  private record LogFingerprint(String relativeName, long size, long lastModifiedMillis) {
    String etag() {
      return "\"log-v4-"
          + Integer.toUnsignedString(relativeName.hashCode(), 16)
          + "-"
          + Long.toHexString(size)
          + "-"
          + Long.toHexString(lastModifiedMillis)
          + "\"";
    }
  }

  private EventFingerprint eventFingerprint(List<Path> logFiles) throws IOException {
    var stamps = new ArrayList<LogFingerprint>(logFiles.size());
    for (var path : logFiles) {
      stamps.add(logFingerprint(path));
    }
    return new EventFingerprint(List.copyOf(stamps));
  }

  private LogFingerprint logFingerprint(Path logPath) throws IOException {
    return new LogFingerprint(
        relativeName(logPath),
        Files.size(logPath),
        Files.getLastModifiedTime(logPath).toMillis());
  }

  private static <T> void putCacheEntry(
      Map<String, CachedJson<T>> cache, String key, CachedJson<T> value, int maxEntries) {
    if (cache.size() >= maxEntries && !cache.containsKey(key)) {
      cache.clear();
    }
    cache.put(key, value);
  }

  private Path resolveEvent(String eventName) {
    if (eventName.isEmpty() || eventName.contains("..") || eventName.contains("/")
        || eventName.contains("\\")) {
      throw new IllegalArgumentException("Invalid event name: " + eventName);
    }
    Path eventDir = logsRoot.resolve(eventName).toAbsolutePath().normalize();
    if (!eventDir.startsWith(logsRoot)) {
      throw new IllegalArgumentException("Event escapes logs root: " + eventName);
    }
    if (!Files.isDirectory(eventDir)) {
      throw new IllegalArgumentException("Event directory not found: " + eventName);
    }
    return eventDir;
  }

  private Path resolveLogPath(String eventName, String logName, boolean rejectExcluded)
      throws IOException {
    Path eventDir = resolveEvent(eventName);
    Path logPath = eventDir.resolve(logName).toAbsolutePath().normalize();
    if (!logPath.startsWith(eventDir)) {
      throw new IllegalArgumentException("Log path escapes event directory: " + logName);
    }
    if (!Files.isRegularFile(logPath)) {
      // Fall back to a depth-walk in case the caller passed a nested name.
      logPath = findNested(eventDir, logName);
      if (logPath == null) {
        throw new IllegalArgumentException("Log not found: " + eventName + "/" + logName);
      }
    }
    if (rejectExcluded && isExcluded(logPath)) {
      throw new IllegalArgumentException("Log excluded: " + logName);
    }
    return logPath;
  }

  private List<Path> findLogs(Path eventDir) throws IOException {
    try (Stream<Path> stream = Files.walk(eventDir, 3)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".wpilog"))
          .filter(p -> !isExcluded(p))
          .sorted(Comparator.comparing(Path::getFileName))
          .limit(MAX_LOGS_PER_EVENT)
          .toList();
    }
  }

  private Path findNested(Path eventDir, String logName) throws IOException {
    try (Stream<Path> stream = Files.walk(eventDir, 3)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().equals(logName))
          .findFirst()
          .orElse(null);
    }
  }

  private String relativeName(Path logPath) {
    return logsRoot.relativize(logPath).toString().replace('\\', '/');
  }

  /**
   * Builds a lookup of absolute-path → metadata for every log under the configured logs
   * root. Delegates to {@link LogDirectory#listAvailableLogs()} which has its own
   * last-modified–keyed cache, so repeated calls are cheap after the first scan.
   */
  private Map<String, LogDirectory.LogFileInfo> loadMetadataIndex() {
    var index = new HashMap<String, LogDirectory.LogFileInfo>();
    try {
      for (var info : LogDirectory.getInstance().listAvailableLogs()) {
        index.put(Path.of(info.path()).toAbsolutePath().normalize().toString(), info);
      }
    } catch (Exception e) {
      logger.debug("Metadata index unavailable: {}", e.getMessage());
    }
    return index;
  }

  /**
   * Produces a friendly label for a log. Falls back to the filename (minus extension)
   * when {@link LogDirectory} could not extract structured metadata.
   */
  private String displayName(Path logPath, Map<String, LogDirectory.LogFileInfo> index) {
    String key = logPath.toAbsolutePath().normalize().toString();
    var info = index.get(key);
    if (info != null) {
      String friendly = info.friendlyName();
      if (friendly != null && !friendly.isBlank()) return friendly;
    }
    return logPath.getFileName().toString().replaceFirst("\\.wpilog$", "");
  }
}
