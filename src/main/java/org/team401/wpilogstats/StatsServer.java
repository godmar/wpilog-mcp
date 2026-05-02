package org.team401.wpilogstats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP surface for wpilog-stats.
 *
 * <p>All routes are mounted under {@code basePath} (default {@code /stats}). The server
 * exposes a static HTML UI plus a small JSON API:
 *
 * <pre>
 *   GET  {base}/                        — redirect to {base}/ui/
 *   GET  {base}/ui/                     — index SPA (lists events)
 *   GET  {base}/ui/&lt;static&gt;             — static assets (html/css/js)
 *   GET  {base}/{event}/                — SPA entry for a specific event
 *   GET  {base}/{event}/{logfile}       — SPA entry for a specific log
 *   GET  {base}/api/events              — JSON list of event directories
 *   GET  {base}/api/events/{event}      — JSON summary for one event
 *   GET  {base}/api/logs/{event}/{name} — JSON detail for one log
 *   GET  {base}/health                  — liveness probe
 * </pre>
 *
 * <p>Event names are directories directly under the configured logs root (e.g.
 * {@code 2026-dcmp}). Log paths passed in JSON responses are always relative to the
 * logs root so the UI can build download URLs to the dufs container.
 */
public class StatsServer {
  private static final Logger logger = LoggerFactory.getLogger(StatsServer.class);
  private static final String STATIC_PREFIX = "stats-ui/";

  private final Path logsRoot;
  private final String bindAddress;
  private final int port;
  private final String basePath;
  private final Gson gson;
  private final EventService eventService;

  private HttpServer server;
  private ExecutorService executor;

  public StatsServer(Path logsRoot, String bindAddress, int port, String basePath) {
    this.logsRoot = logsRoot.toAbsolutePath().normalize();
    this.bindAddress = bindAddress;
    this.port = port;
    this.basePath = basePath;
    this.gson = new GsonBuilder().serializeNulls().create();
    this.eventService = new EventService(this.logsRoot);
  }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
    executor = Executors.newFixedThreadPool(
        Math.max(4, Runtime.getRuntime().availableProcessors()));
    server.setExecutor(executor);

    server.createContext("/", new Router());
    server.start();
    logger.info("wpilog-stats listening on {}:{}{}", bindAddress, port, basePath);
  }

  public void stop() {
    if (server != null) {
      server.stop(2);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }

  // ======================================================================
  // Request routing
  // ======================================================================

  private class Router implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        if (!"GET".equals(exchange.getRequestMethod())
            && !"HEAD".equals(exchange.getRequestMethod())) {
          sendText(exchange, 405, "Method Not Allowed");
          return;
        }

        if (path.equals("/health")) {
          sendJson(exchange, 200, Map.of("status", "ok", "logsRoot", logsRoot.toString()));
          return;
        }

        // Only handle paths under basePath.
        if (!path.equals(basePath) && !path.startsWith(basePath + "/")) {
          sendText(exchange, 404, "Not Found: " + path);
          return;
        }

        String rel = path.equals(basePath) ? "" : path.substring(basePath.length() + 1);

        // Redirect bare base path to the UI index.
        if (rel.isEmpty()) {
          exchange.getResponseHeaders().set("Location", basePath + "/ui/");
          exchange.sendResponseHeaders(302, -1);
          return;
        }

        // JSON API routes.
        if (rel.equals("api/events")) {
          handleListEvents(exchange);
          return;
        }
        if (rel.startsWith("api/events/")) {
          String event = decode(rel.substring("api/events/".length()));
          handleEventSummary(exchange, event);
          return;
        }
        if (rel.startsWith("api/logs/")) {
          String remainder = rel.substring("api/logs/".length());
          int slash = remainder.indexOf('/');
          if (slash < 0) {
            sendText(exchange, 400, "Expected /api/logs/<event>/<logfile>");
            return;
          }
          String event = decode(remainder.substring(0, slash));
          String logName = decode(remainder.substring(slash + 1));
          handleLogDetail(exchange, event, logName);
          return;
        }
        if (rel.startsWith("api/tba/events/")) {
          String event = decode(rel.substring("api/tba/events/".length()));
          handleEventTba(exchange, event);
          return;
        }
        if (rel.startsWith("api/tba/logs/")) {
          String remainder = rel.substring("api/tba/logs/".length());
          int slash = remainder.indexOf('/');
          if (slash < 0) {
            sendText(exchange, 400, "Expected /api/tba/logs/<event>/<logfile>");
            return;
          }
          String event = decode(remainder.substring(0, slash));
          String logName = decode(remainder.substring(slash + 1));
          handleLogTba(exchange, event, logName);
          return;
        }

        // Static UI files (served under {base}/ui/).
        if (rel.equals("ui") || rel.equals("ui/")) {
          serveStatic(exchange, "index.html");
          return;
        }
        if (rel.startsWith("ui/")) {
          serveStatic(exchange, rel.substring("ui/".length()));
          return;
        }

        // Friendly URLs: {base}/{event}/ and {base}/{event}/{log}
        // Both render the same SPA; the client JS reads window.location to decide.
        serveStatic(exchange, "index.html");
      } catch (Exception e) {
        logger.error("Unhandled error for {} : {}", exchange.getRequestURI(), e.toString(), e);
        try {
          sendText(exchange, 500, "Internal error: " + e.getMessage());
        } catch (IOException ignored) {
          // connection likely closed
        }
      }
    }
  }

  // ======================================================================
  // JSON endpoints
  // ======================================================================

  private void handleListEvents(HttpExchange exchange) throws IOException {
    var events = eventService.listEvents();
    var result = new JsonObject();
    var arr = new com.google.gson.JsonArray();
    for (var e : events) {
      var o = new JsonObject();
      o.addProperty("name", e.name());
      o.addProperty("logCount", e.logCount());
      o.addProperty("lastModified", e.lastModifiedMillis());
      arr.add(o);
    }
    result.add("events", arr);
    result.addProperty("logsRoot", logsRoot.toString());
    result.addProperty("basePath", basePath);
    sendJson(exchange, 200, result);
  }

  private void handleEventSummary(HttpExchange exchange, String event) throws IOException {
    try {
      var summary = eventService.summarizeEvent(event);
      sendJson(exchange, 200, summary);
    } catch (IllegalArgumentException e) {
      sendText(exchange, 404, e.getMessage());
    } catch (Exception e) {
      logger.warn("Event summary failed for {}: {}", event, e.toString(), e);
      sendText(exchange, 500, "Failed to summarize event: " + e.getMessage());
    }
  }

  private void handleLogDetail(HttpExchange exchange, String event, String logName)
      throws IOException {
    try {
      String etag = eventService.logDetailEtag(event, logName);
      if (etagMatches(exchange.getRequestHeaders().getFirst("If-None-Match"), etag)) {
        sendNotModified(exchange, etag);
        return;
      }
      var detail = eventService.detailLog(event, logName);
      sendJson(exchange, 200, detail, "private, no-cache", etag);
    } catch (IllegalArgumentException e) {
      sendText(exchange, 404, e.getMessage());
    } catch (Exception e) {
      logger.warn("Log detail failed for {}/{}: {}", event, logName, e.toString(), e);
      sendText(exchange, 500, "Failed to analyze log: " + e.getMessage());
    }
  }

  private void handleEventTba(HttpExchange exchange, String event) throws IOException {
    try {
      var tba = eventService.tbaForEvent(event);
      sendJson(exchange, 200, tba);
    } catch (IllegalArgumentException e) {
      sendText(exchange, 404, e.getMessage());
    } catch (Exception e) {
      logger.warn("Event TBA failed for {}: {}", event, e.toString(), e);
      sendText(exchange, 500, "Failed to load TBA data: " + e.getMessage());
    }
  }

  private void handleLogTba(HttpExchange exchange, String event, String logName)
      throws IOException {
    try {
      var tba = eventService.tbaForLog(event, logName);
      sendJson(exchange, 200, tba);
    } catch (IllegalArgumentException e) {
      sendText(exchange, 404, e.getMessage());
    } catch (Exception e) {
      logger.warn("Log TBA failed for {}/{}: {}", event, logName, e.toString(), e);
      sendText(exchange, 500, "Failed to load TBA data: " + e.getMessage());
    }
  }

  // ======================================================================
  // Static content
  // ======================================================================

  private void serveStatic(HttpExchange exchange, String relative) throws IOException {
    // Normalise and guard against path traversal in the classpath namespace.
    if (relative.contains("..") || relative.startsWith("/")) {
      sendText(exchange, 400, "Invalid path");
      return;
    }
    if (relative.isEmpty()) {
      relative = "index.html";
    }
    String resource = STATIC_PREFIX + relative;
    try (InputStream in = StatsServer.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        // SPA fallback: unknown URL under {base}/ — serve index.html so the client can route.
        serveIndexHtml(exchange);
        return;
      }
      byte[] bytes;
      if (relative.toLowerCase().endsWith(".html")) {
        // Substitute the configured base path so asset URLs work under any mount point.
        // Applied to every html file (index.html, help.html, …) so static pages can
        // reference stylesheets and cross-links without hard-coding the prefix.
        String html = new String(in.readAllBytes(), StandardCharsets.UTF_8)
            .replace("__BASE__", basePath);
        bytes = html.getBytes(StandardCharsets.UTF_8);
      } else {
        bytes = in.readAllBytes();
      }
      exchange.getResponseHeaders().set("Content-Type", contentType(relative));
      exchange.getResponseHeaders().set("Cache-Control", "public, max-age=60");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
  }

  private void serveIndexHtml(HttpExchange exchange) throws IOException {
    try (InputStream in =
        StatsServer.class.getClassLoader().getResourceAsStream(STATIC_PREFIX + "index.html")) {
      if (in == null) {
        sendText(exchange, 404, "index.html missing from classpath");
        return;
      }
      String html = new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .replace("__BASE__", basePath);
      byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
  }

  private static String contentType(String name) {
    String lower = name.toLowerCase();
    if (lower.endsWith(".html")) return "text/html; charset=utf-8";
    if (lower.endsWith(".css")) return "text/css; charset=utf-8";
    if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".svg")) return "image/svg+xml";
    if (lower.endsWith(".png")) return "image/png";
    return "application/octet-stream";
  }

  // ======================================================================
  // Response helpers
  // ======================================================================

  private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
    sendJson(exchange, status, body, "no-store", null);
  }

  private void sendJson(
      HttpExchange exchange, int status, Object body, String cacheControl, String etag)
      throws IOException {
    byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", cacheControl);
    if (etag != null) {
      exchange.getResponseHeaders().set("ETag", etag);
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void sendText(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private void sendNotModified(HttpExchange exchange, String etag) throws IOException {
    exchange.getResponseHeaders().set("Cache-Control", "private, no-cache");
    exchange.getResponseHeaders().set("ETag", etag);
    exchange.sendResponseHeaders(304, -1);
  }

  private static boolean etagMatches(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
    for (String part : ifNoneMatch.split(",")) {
      String candidate = part.trim();
      if (candidate.equals("*") || candidate.equals(etag)) return true;
    }
    return false;
  }

  private static String decode(String s) {
    return URLDecoder.decode(s, StandardCharsets.UTF_8);
  }
}
