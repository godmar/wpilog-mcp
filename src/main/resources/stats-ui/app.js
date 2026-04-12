/* global Chart */
(function () {
  "use strict";

  // Register the annotation plugin if it loaded successfully. If the CDN
  // is blocked we'll still render the charts, just without phase markers.
  if (typeof window !== "undefined" && window["chartjs-plugin-annotation"]) {
    Chart.register(window["chartjs-plugin-annotation"]);
  }

  // --- Base path discovery ----------------------------------------------
  // The server templates the configured base path into <body data-base-path="...">
  // at render time so we don't have to guess from window.location.
  const BASE = (document.body.dataset.basePath || "/stats").replace(/\/+$/, "");

  function route() {
    let pathname = window.location.pathname;
    // Strip the base path so only the logical route remains.
    if (pathname === BASE || pathname === BASE + "/") {
      return { view: "home" };
    }
    if (pathname.startsWith(BASE + "/")) {
      pathname = pathname.substring(BASE.length + 1);
    }
    const segments = pathname.split("/").filter(Boolean);
    if (segments.length === 0 || segments[0] === "ui") {
      return { view: "home" };
    }
    if (segments.length === 1) {
      return { view: "event", event: decodeURIComponent(segments[0]) };
    }
    return {
      view: "log",
      event: decodeURIComponent(segments[0]),
      log: decodeURIComponent(segments.slice(1).join("/")),
    };
  }

  const appEl = document.getElementById("app");
  const crumbsEl = document.getElementById("breadcrumbs");
  const logsRootEl = document.getElementById("logs-root");

  let activeCharts = [];
  function destroyCharts() {
    for (const c of activeCharts) c.destroy();
    activeCharts = [];
  }

  // --- Utilities ---------------------------------------------------------
  function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === "class") node.className = v;
      else if (k === "html") node.innerHTML = v;
      else if (k.startsWith("on")) node.addEventListener(k.substring(2), v);
      else node.setAttribute(k, v);
    }
    for (const child of children) {
      if (child == null) continue;
      node.appendChild(typeof child === "string" ? document.createTextNode(child) : child);
    }
    return node;
  }
  function fmt(n, digits = 2) {
    if (n === null || n === undefined || Number.isNaN(n)) return "—";
    return Number(n).toFixed(digits);
  }
  function fmtInt(n) {
    if (n === null || n === undefined) return "—";
    return Math.round(n).toLocaleString();
  }
  function fmtDate(ms) {
    if (!ms) return "—";
    return new Date(ms).toLocaleString();
  }
  async function fetchJson(url) {
    const resp = await fetch(url);
    if (!resp.ok) {
      const text = await resp.text().catch(() => "");
      throw new Error(`${resp.status} ${resp.statusText}: ${text}`);
    }
    return resp.json();
  }

  function setCrumbs(parts) {
    crumbsEl.innerHTML = "";
    parts.forEach((part, i) => {
      if (i > 0) crumbsEl.appendChild(document.createTextNode(" / "));
      if (part.href) {
        const a = document.createElement("a");
        a.href = part.href;
        a.textContent = part.label;
        crumbsEl.appendChild(a);
      } else {
        crumbsEl.appendChild(document.createTextNode(part.label));
      }
    });
  }

  function loading(msg = "Loading…") {
    destroyCharts();
    appEl.innerHTML = "";
    appEl.appendChild(el("p", { class: "loading" }, msg));
  }

  function showError(err) {
    destroyCharts();
    appEl.innerHTML = "";
    const box = el("div", { class: "error" },
      el("strong", {}, "Something went wrong"),
      el("p", {}, err.message || String(err))
    );
    appEl.appendChild(box);
  }

  // --- Views -------------------------------------------------------------
  async function renderHome() {
    loading("Listing events…");
    setCrumbs([{ label: "Events" }]);
    try {
      const data = await fetchJson(`${BASE}/api/events`);
      if (logsRootEl) logsRootEl.textContent = data.logsRoot || "";
      appEl.innerHTML = "";
      appEl.appendChild(el("h2", {}, "Events"));
      if (!data.events || data.events.length === 0) {
        appEl.appendChild(el("p", {}, "No events found. Upload logs to the team401 dufs container first."));
        return;
      }
      const grid = el("div", { class: "grid" });
      for (const ev of data.events) {
        const card = el("div", { class: "card" },
          el("a", { href: `${BASE}/${encodeURIComponent(ev.name)}/` }, ev.name),
          el("div", { class: "meta" },
            `${ev.logCount} log${ev.logCount === 1 ? "" : "s"} · last updated ${fmtDate(ev.lastModified)}`)
        );
        grid.appendChild(card);
      }
      appEl.appendChild(grid);
    } catch (e) {
      showError(e);
    }
  }

  async function renderEvent(eventName) {
    loading(`Analyzing ${eventName}…`);
    setCrumbs([
      { label: "Events", href: `${BASE}/` },
      { label: eventName },
    ]);
    try {
      const data = await fetchJson(`${BASE}/api/events/${encodeURIComponent(eventName)}`);
      appEl.innerHTML = "";
      appEl.appendChild(el("h2", {}, `${eventName} — ${data.logCount} logs`));

      // Aggregate stat strip
      const agg = data.aggregate || {};
      const battAgg = agg.battery || {};
      const currAgg = agg.current || {};
      const strip = el("div", { class: "stats-strip" });
      strip.appendChild(stat("Matches analyzed", fmtInt(battAgg.matchesAnalyzed)));
      strip.appendChild(stat("Avg min voltage", battAgg.matchesAnalyzed ? `${fmt(battAgg.avgOfMinVoltage)} V` : "—",
        battAgg.avgOfMinVoltage !== undefined && battAgg.avgOfMinVoltage < 7.5 ? "warn" : ""));
      strip.appendChild(stat("Lowest voltage", battAgg.overallMinVoltage !== undefined ? `${fmt(battAgg.overallMinVoltage)} V` : "—",
        battAgg.overallMinVoltage !== undefined && battAgg.overallMinVoltage < 6.8 ? "critical" : ""));
      strip.appendChild(stat("Brownout samples", fmtInt(battAgg.totalBrownoutSamples || 0)));
      strip.appendChild(stat("Avg in-match mean current", currAgg.matchesAnalyzed ? `${fmt(currAgg.avgMeanTotalCurrent)} A` : "—"));
      strip.appendChild(stat("Avg in-match P90 current", currAgg.matchesAnalyzed ? `${fmt(currAgg.avgP90TotalCurrent)} A` : "—"));
      strip.appendChild(stat("Peak total current", currAgg.matchesAnalyzed ? `${fmt(currAgg.peakTotalCurrent)} A` : "—"));
      appEl.appendChild(strip);

      // Per-log bar charts: min voltage + mean current
      appEl.appendChild(el("h3", {}, "Per-match battery voltage"));
      const batteryChart = el("div", { class: "chart-container" });
      const batteryCanvas = el("canvas");
      batteryChart.appendChild(batteryCanvas);
      appEl.appendChild(batteryChart);

      appEl.appendChild(el("h3", {}, "Per-match current draw (in-match: mean, P90, peak)"));
      const currentChart = el("div", { class: "chart-container" });
      const currentCanvas = el("canvas");
      currentChart.appendChild(currentCanvas);
      appEl.appendChild(currentChart);

      const logs = data.logs || [];
      const labels = logs.map(l => l.displayName || l.file);
      const minVolt = logs.map(l => l.battery && l.battery.available ? l.battery.minVoltage : null);
      const meanVolt = logs.map(l => l.battery && l.battery.available ? l.battery.meanVoltage : null);
      const meanCur = logs.map(l => l.current && l.current.available ? l.current.meanTotalCurrent : null);
      const p90Cur = logs.map(l => l.current && l.current.available ? l.current.p90TotalCurrent : null);
      const peakCur = logs.map(l => l.current && l.current.available ? l.current.peakTotalCurrent : null);

      activeCharts.push(new Chart(batteryCanvas, {
        type: "bar",
        data: {
          labels,
          datasets: [
            { label: "Min voltage (V)", data: minVolt, backgroundColor: "#ef4444" },
            { label: "Mean voltage (V)", data: meanVolt, backgroundColor: "#60a5fa" },
          ],
        },
        options: sharedChartOptions("Voltage (V)"),
      }));

      activeCharts.push(new Chart(currentCanvas, {
        type: "bar",
        data: {
          labels,
          datasets: [
            { label: "Peak total (A)", data: peakCur, backgroundColor: "#f97316" },
            { label: "P90 total (A)", data: p90Cur, backgroundColor: "#facc15" },
            { label: "Mean total (A)", data: meanCur, backgroundColor: "#10b981" },
          ],
        },
        options: sharedChartOptions("Current (A)"),
      }));

      // Log table
      appEl.appendChild(el("h3", {}, "Logs"));
      const table = el("table");
      const thead = el("thead");
      thead.appendChild(el("tr", {},
        el("th", {}, "Log"),
        el("th", {}, "Duration (s)"),
        el("th", {}, "Min V"),
        el("th", {}, "Mean V"),
        el("th", {}, "Brownouts"),
        el("th", {}, "Mean I (A)"),
        el("th", {}, "P90 I (A)"),
        el("th", {}, "Peak I (A)"),
      ));
      table.appendChild(thead);
      const tbody = el("tbody");
      for (const l of logs) {
        const tr = el("tr");
        const nameCell = el("td");
        nameCell.appendChild(el("a", {
          href: `${BASE}/${encodeURIComponent(eventName)}/${encodeURIComponent(lastComponent(l.file))}`,
        }, l.displayName || l.file));
        tr.appendChild(nameCell);
        tr.appendChild(el("td", { class: "num" }, fmt(l.duration, 1)));
        tr.appendChild(el("td", { class: "num" }, l.battery && l.battery.available ? fmt(l.battery.minVoltage) : "—"));
        tr.appendChild(el("td", { class: "num" }, l.battery && l.battery.available ? fmt(l.battery.meanVoltage) : "—"));
        tr.appendChild(el("td", { class: "num" }, l.battery && l.battery.available ? fmtInt(l.battery.brownoutSamples) : "—"));
        tr.appendChild(el("td", { class: "num" }, l.current && l.current.available ? fmt(l.current.meanTotalCurrent) : "—"));
        tr.appendChild(el("td", { class: "num" }, l.current && l.current.available ? fmt(l.current.p90TotalCurrent) : "—"));
        tr.appendChild(el("td", { class: "num" }, l.current && l.current.available ? fmt(l.current.peakTotalCurrent) : "—"));
        tbody.appendChild(tr);
      }
      table.appendChild(tbody);
      appEl.appendChild(table);
    } catch (e) {
      showError(e);
    }
  }

  async function renderLog(eventName, logName) {
    loading(`Analyzing ${eventName}/${logName}…`);
    setCrumbs([
      { label: "Events", href: `${BASE}/` },
      { label: eventName, href: `${BASE}/${encodeURIComponent(eventName)}/` },
      { label: logName },
    ]);
    try {
      const data = await fetchJson(
        `${BASE}/api/logs/${encodeURIComponent(eventName)}/${encodeURIComponent(logName)}`);
      appEl.innerHTML = "";
      appEl.appendChild(el("h2", {}, data.displayName || logName));
      const metaParts = [
        `Duration ${fmt(data.duration, 1)} s`,
        `${fmtInt(data.entryCount)} entries`,
        `file ${data.file}`,
      ];
      const phases = data.phases || null;
      if (phases) {
        if (phases.autoStart != null && phases.autoEnd != null) {
          metaParts.push(`auto ${fmt(phases.autoEnd - phases.autoStart, 1)} s`);
        }
        if (phases.teleopStart != null && phases.teleopEnd != null) {
          metaParts.push(`teleop ${fmt(phases.teleopEnd - phases.teleopStart, 1)} s`);
        }
      }
      appEl.appendChild(el("p", { class: "meta" }, metaParts.join(" · ")));

      const battery = data.battery || {};
      const current = data.current || {};

      // Battery stats
      const strip = el("div", { class: "stats-strip" });
      if (battery.available) {
        strip.appendChild(stat("Min voltage", `${fmt(battery.minVoltage)} V`,
          battery.minVoltage < 6.8 ? "critical" : battery.minVoltage < 9.0 ? "warn" : ""));
        strip.appendChild(stat("Mean voltage", `${fmt(battery.meanVoltage)} V`));
        strip.appendChild(stat("Max voltage", `${fmt(battery.maxVoltage)} V`));
        strip.appendChild(stat("Std dev", `${fmt(battery.stdDevVoltage)} V`));
        strip.appendChild(stat("Brownout (≤6.8)", fmtInt(battery.brownoutSamples),
          battery.brownoutSamples > 0 ? "critical" : ""));
        strip.appendChild(stat("Warn (≤9.0)", fmtInt(battery.warningSamples),
          battery.warningSamples > 0 ? "warn" : ""));
      } else {
        strip.appendChild(stat("Battery", "no data"));
      }
      appEl.appendChild(strip);

      // Battery chart
      if (battery.available && battery.series && battery.series.length > 0) {
        appEl.appendChild(el("h3", {}, `Battery voltage — ${battery.entry}`));
        const box = el("div", { class: "chart-container" });
        const canvas = el("canvas");
        box.appendChild(canvas);
        appEl.appendChild(box);
        activeCharts.push(new Chart(canvas, {
          type: "line",
          data: {
            datasets: [{
              label: "Battery voltage (V)",
              data: battery.series.map(([t, v]) => ({ x: t, y: v })),
              borderColor: "#60a5fa",
              borderWidth: 1.3,
              pointRadius: 0,
              tension: 0,
            }, {
              label: "Brownout (6.8 V)",
              data: [
                { x: battery.series[0][0], y: 6.8 },
                { x: battery.series[battery.series.length - 1][0], y: 6.8 }
              ],
              borderColor: "#ef4444",
              borderDash: [6, 4],
              borderWidth: 1,
              pointRadius: 0,
            }],
          },
          options: timeSeriesOptions("Voltage (V)", phases),
        }));
      }

      // Aligned total current over time. This is the single series whose
      // stats drive the "Peak/Mean/P90 total" numbers — built by merging
      // every subsystem onto a common time grid with zero-order hold and
      // summing. See the Help page for the full algorithm.
      if (current.available) {
        const totalStrip = el("div", { class: "stats-strip" });
        totalStrip.appendChild(stat("Peak total current", `${fmt(current.peakTotalCurrent)} A`));
        totalStrip.appendChild(stat("P90 total current", `${fmt(current.p90TotalCurrent)} A`));
        totalStrip.appendChild(stat("Mean total current", `${fmt(current.meanTotalCurrent)} A`));
        appEl.appendChild(totalStrip);

        if (current.totalSeries && current.totalSeries.length > 0) {
          appEl.appendChild(el("h3", {}, "Total current over time (aligned)"));
          const totalBox = el("div", { class: "chart-container" });
          const totalCanvas = el("canvas");
          totalBox.appendChild(totalCanvas);
          appEl.appendChild(totalBox);
          // Two overlaid datasets on the same axes:
          //   - raw aligned total — thin, faint orange with a light fill
          //     so spikes are visible but don't shout over the smoothed line
          //   - 1 s time-weighted trailing mean — thicker amber line,
          //     drawn on top (`order: 1` < raw's `order: 2`)
          const datasets = [
            {
              label: "Total (raw)",
              data: current.totalSeries.map(([t, v]) => ({ x: t, y: v })),
              borderColor: "rgba(249, 115, 22, 0.55)",
              backgroundColor: "rgba(249, 115, 22, 0.10)",
              borderWidth: 1,
              pointRadius: 0,
              tension: 0,
              fill: true,
              order: 2,
            },
          ];
          if (current.totalSmoothedSeries && current.totalSmoothedSeries.length > 0) {
            datasets.push({
              label: "1 s trailing mean",
              data: current.totalSmoothedSeries.map(([t, v]) => ({ x: t, y: v })),
              borderColor: "#fbbf24",
              backgroundColor: "transparent",
              borderWidth: 2,
              pointRadius: 0,
              tension: 0,
              fill: false,
              order: 1,
            });
          }
          activeCharts.push(new Chart(totalCanvas, {
            type: "line",
            data: { datasets },
            options: timeSeriesOptions("Current (A)", phases),
          }));
        }
      }

      // Current subsystems
      if (current.available && current.subsystems && current.subsystems.length > 0) {
        appEl.appendChild(el("h3", {}, "Current draw by subsystem"));

        // Bar chart of mean/peak
        const meanBox = el("div", { class: "chart-container" });
        const meanCanvas = el("canvas");
        meanBox.appendChild(meanCanvas);
        appEl.appendChild(meanBox);
        activeCharts.push(new Chart(meanCanvas, {
          type: "bar",
          data: {
            labels: current.subsystems.map(s => s.name),
            datasets: [
              { label: "Peak (A)", data: current.subsystems.map(s => s.stats.peak), backgroundColor: "#f97316" },
              { label: "Mean (A)", data: current.subsystems.map(s => s.stats.mean), backgroundColor: "#10b981" },
            ],
          },
          options: sharedChartOptions("Current (A)"),
        }));

        // Table with details
        const table = el("table");
        const thead = el("thead");
        thead.appendChild(el("tr", {},
          el("th", {}, "Subsystem"),
          el("th", {}, "Source"),
          el("th", {}, "Mean (A)"),
          el("th", {}, "RMS (A)"),
          el("th", {}, "P90 (A)"),
          el("th", {}, "Peak (A)"),
          el("th", {}, "Samples"),
        ));
        table.appendChild(thead);
        const tbody = el("tbody");
        for (const s of current.subsystems) {
          const tr = el("tr");
          tr.appendChild(el("td", {}, s.name));
          const srcCell = el("td");
          const pill = el("span", { class: "pill " + (s.source === "swerve" ? "swerve" : "direct") },
            s.source === "swerve" ? "computed" : "direct");
          srcCell.appendChild(pill);
          tr.appendChild(srcCell);
          tr.appendChild(el("td", { class: "num" }, fmt(s.stats.mean)));
          tr.appendChild(el("td", { class: "num" }, fmt(s.stats.rms)));
          tr.appendChild(el("td", { class: "num" }, fmt(s.stats.p90)));
          tr.appendChild(el("td", { class: "num" }, fmt(s.stats.peak)));
          tr.appendChild(el("td", { class: "num" }, fmtInt(s.stats.sampleCount)));
          tbody.appendChild(tr);
        }
        table.appendChild(tbody);
        appEl.appendChild(table);

        // Time series — split into swerve modules vs. everything else so
        // neither chart gets overwhelmed by the other.
        const swerveSubs = current.subsystems.filter(s => s.source === "swerve");
        const otherSubs = current.subsystems.filter(s => s.source !== "swerve");
        renderCurrentTimeSeries("Swerve module current over time", swerveSubs, phases);
        renderCurrentTimeSeries("Subsystem current over time", otherSubs, phases);
      } else {
        appEl.appendChild(el("p", {}, "No current entries detected in this log."));
      }
    } catch (e) {
      showError(e);
    }
  }

  // Renders a single "current over time" chart for a filtered subset of
  // subsystems. Appends a heading + canvas; skips entirely if no series.
  const TIME_SERIES_COLORS = [
    "#60a5fa", "#f97316", "#10b981", "#a78bfa", "#f472b6",
    "#facc15", "#22d3ee", "#fb7185", "#4ade80", "#e879f9",
  ];
  function renderCurrentTimeSeries(title, subsystems, phases) {
    const withSeries = subsystems.filter(s => s.series && s.series.length > 0);
    if (withSeries.length === 0) return;
    appEl.appendChild(el("h3", {}, title));
    const box = el("div", { class: "chart-container" });
    const canvas = el("canvas");
    box.appendChild(canvas);
    appEl.appendChild(box);
    const datasets = withSeries.map((s, i) => ({
      label: s.name,
      data: s.series.map(([t, v]) => ({ x: t, y: v })),
      borderColor: TIME_SERIES_COLORS[i % TIME_SERIES_COLORS.length],
      backgroundColor: "transparent",
      borderWidth: 1,
      pointRadius: 0,
      tension: 0,
    }));
    activeCharts.push(new Chart(canvas, {
      type: "line",
      data: { datasets },
      options: timeSeriesOptions("Current (A)", phases),
    }));
  }

  // Builds Chart.js annotation plugin config from a match-phases object.
  // Draws a colored vertical line + label at each phase boundary we know about.
  function phaseAnnotations(phases) {
    if (!phases) return null;
    const lines = [];
    function add(key, x, label, color) {
      if (x == null || !Number.isFinite(x)) return;
      lines.push({
        type: "line",
        xMin: x,
        xMax: x,
        borderColor: color,
        borderWidth: 1.5,
        borderDash: [5, 4],
        label: {
          display: true,
          content: label,
          position: "start",
          backgroundColor: color,
          color: "#0f172a",
          font: { size: 10, weight: "bold" },
          padding: 3,
        },
      });
    }
    add("matchStart", phases.matchStart, "Match start", "#facc15");
    add("autoStart", phases.autoStart, "Auto", "#10b981");
    add("teleopStart", phases.teleopStart, "Teleop", "#60a5fa");
    add("matchEnd", phases.matchEnd, "Match end", "#f87171");
    if (lines.length === 0) return null;
    const annotations = {};
    lines.forEach((l, i) => { annotations["phase" + i] = l; });
    return { annotations };
  }

  // --- Chart helpers -----------------------------------------------------
  function sharedChartOptions(yLabel) {
    return {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { labels: { color: "#cbd5e1" } },
      },
      scales: {
        x: { ticks: { color: "#94a3b8", maxRotation: 60, minRotation: 45 },
             grid: { color: "#1e293b" } },
        y: { ticks: { color: "#94a3b8" }, grid: { color: "#1e293b" },
             title: { display: true, text: yLabel, color: "#cbd5e1" } },
      },
    };
  }
  function timeSeriesOptions(yLabel, phases) {
    const plugins = { legend: { labels: { color: "#cbd5e1" } } };
    const ann = phaseAnnotations(phases);
    if (ann) plugins.annotation = ann;
    // Clip the visible x range to the match window (± 5 s) so pre-match
    // setup and post-match idle don't dominate the chart. If we don't have
    // a match start we leave the axis auto-ranged.
    const xScale = {
      type: "linear",
      ticks: { color: "#94a3b8" },
      title: { display: true, text: "Time (s)", color: "#cbd5e1" },
      grid: { color: "#1e293b" },
    };
    if (phases && phases.matchStart != null && Number.isFinite(phases.matchStart)) {
      xScale.min = phases.matchStart - 5;
      if (phases.matchEnd != null && Number.isFinite(phases.matchEnd)) {
        xScale.max = phases.matchEnd + 5;
      }
    }
    return {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      plugins,
      scales: {
        x: xScale,
        y: { ticks: { color: "#94a3b8" }, grid: { color: "#1e293b" },
             title: { display: true, text: yLabel, color: "#cbd5e1" } },
      },
    };
  }
  function stat(label, value, cls = "") {
    return el("div", { class: "stat " + cls },
      el("div", { class: "label" }, label),
      el("div", { class: "value" }, value)
    );
  }
  function lastComponent(relPath) {
    const parts = relPath.split("/");
    return parts[parts.length - 1];
  }

  // --- Router ------------------------------------------------------------
  function render() {
    destroyCharts();
    const r = route();
    if (r.view === "home") return renderHome();
    if (r.view === "event") return renderEvent(r.event);
    if (r.view === "log") return renderLog(r.event, r.log);
    showError(new Error("Unknown route"));
  }

  // Intercept internal links so the SPA router handles them.
  document.addEventListener("click", function (e) {
    const a = e.target.closest("a");
    if (!a) return;
    const href = a.getAttribute("href");
    if (!href || href.startsWith("http") || href.startsWith("#")) return;
    if (!href.startsWith(BASE)) return;
    e.preventDefault();
    if (window.location.pathname !== href) {
      window.history.pushState({}, "", href);
    }
    render();
  });
  window.addEventListener("popstate", render);

  render();
})();
