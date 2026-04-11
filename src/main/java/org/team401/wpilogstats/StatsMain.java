package org.team401.wpilogstats;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.triplehelix.wpilogmcp.log.LogDirectory;
import org.triplehelix.wpilogmcp.log.LogManager;

/**
 * Entry point for the wpilog-stats web application.
 *
 * <p>The stats app is a Team 401-specific companion to the wpilog-mcp server. It reuses
 * the core log parsing, caching, and entry access subroutines from
 * {@link org.triplehelix.wpilogmcp} but exposes an HTML + JSON API surface aimed at
 * browsers rather than at an LLM. Deployed as a third container in the team401mcp
 * namespace alongside the dufs upload container and the MCP JSON-RPC server.
 *
 * <p>Runs on a plain JDK {@code HttpServer} — no new dependencies. Bundled as part of
 * the existing shadow jar and launched via
 * {@code java -cp wpilog-mcp.jar org.team401.wpilogstats.StatsMain}.
 */
public class StatsMain {
  private static final Logger logger = LoggerFactory.getLogger(StatsMain.class);

  public static void main(String[] args) throws Exception {
    if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
    }

    String logDir = envOr("WPILOG_DIR", "/logs");
    int port = parseInt(System.getenv("STATS_PORT"), 8000);
    String bind = envOr("STATS_BIND", "0.0.0.0");
    String basePath = normalizeBasePath(envOr("STATS_BASE_PATH", "/stats"));

    logger.info("wpilog-stats starting: logDir={} bind={} port={} basePath={}",
        logDir, bind, port, basePath);

    LogDirectory.getInstance().setLogDirectory(logDir);
    LogManager.getInstance().addAllowedDirectory(logDir);

    String envTeam = System.getenv("WPILOG_TEAM");
    if (envTeam != null && !envTeam.isEmpty()) {
      try {
        LogDirectory.getInstance().setDefaultTeamNumber(Integer.parseInt(envTeam));
      } catch (NumberFormatException e) {
        logger.warn("Invalid WPILOG_TEAM: {}", envTeam);
      }
    }

    var server = new StatsServer(Path.of(logDir), bind, port, basePath);
    server.start();

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      logger.info("Shutdown requested");
      server.stop();
      LogManager.getInstance().shutdown();
    }, "stats-shutdown"));

    // Block main thread — HttpServer runs on its own executor.
    Thread.currentThread().join();
  }

  private static String envOr(String name, String fallback) {
    var v = System.getenv(name);
    return v == null || v.isEmpty() ? fallback : v;
  }

  private static int parseInt(String s, int fallback) {
    if (s == null || s.isEmpty()) return fallback;
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** Ensures the base path starts with "/" and has no trailing slash (unless it is "/"). */
  private static String normalizeBasePath(String raw) {
    if (raw == null || raw.isEmpty()) return "/stats";
    String p = raw.startsWith("/") ? raw : "/" + raw;
    while (p.length() > 1 && p.endsWith("/")) {
      p = p.substring(0, p.length() - 1);
    }
    return p;
  }
}
