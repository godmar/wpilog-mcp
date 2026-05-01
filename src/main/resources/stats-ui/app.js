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

      // Log table. TBA Score/Links columns are appended asynchronously below
      // so a slow Blue Alliance API never blocks the initial render.
      appEl.appendChild(el("h3", {}, "Logs"));
      const table = el("table");
      const thead = el("thead");
      const headerRow = el("tr", {},
        el("th", {}, "Log"),
        el("th", {}, "Duration (s)"),
        el("th", {}, "Min V"),
        el("th", {}, "Mean V"),
        el("th", {}, "Brownouts"),
        el("th", {}, "Mean I (A)"),
        el("th", {}, "P90 I (A)"),
        el("th", {}, "Peak I (A)"),
      );
      thead.appendChild(headerRow);
      table.appendChild(thead);
      const tbody = el("tbody");
      for (const l of logs) {
        const tr = el("tr", { "data-file": l.file });
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

      loadEventTbaAsync(eventName, table);
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

      // TBA match info strip is loaded asynchronously by loadLogTbaAsync below
      // so a slow Blue Alliance API never blocks the initial render. We reserve
      // a placeholder slot here so the strip lands in the right DOM position.
      const tbaSlot = el("div", { id: "tba-slot" });
      appEl.appendChild(tbaSlot);
      loadLogTbaAsync(eventName, logName, tbaSlot);

      const battery = data.battery || {};
      const current = data.current || {};
      const vision = data.vision || {};

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
        const hasBattSmoothed = battery.smoothedSeries && battery.smoothedSeries.length > 0;
        const battDatasets = [{
          label: hasBattSmoothed ? "Battery voltage (raw)" : "Battery voltage (V)",
          data: battery.series.map(([t, v]) => ({ x: t, y: v })),
          borderColor: hasBattSmoothed ? "rgba(96, 165, 250, 0.45)" : "#60a5fa",
          backgroundColor: "transparent",
          borderWidth: 1,
          pointRadius: 0,
          tension: 0,
          order: 2,
        }];
        if (hasBattSmoothed) {
          battDatasets.push({
            label: "1 s trailing mean",
            data: battery.smoothedSeries.map(([t, v]) => ({ x: t, y: v })),
            borderColor: "#60a5fa",
            backgroundColor: "transparent",
            borderWidth: 2,
            pointRadius: 0,
            tension: 0,
            order: 1,
          });
        }
        battDatasets.push({
          label: "Brownout (6.8 V)",
          data: [
            { x: battery.series[0][0], y: 6.8 },
            { x: battery.series[battery.series.length - 1][0], y: 6.8 }
          ],
          borderColor: "#ef4444",
          borderDash: [6, 4],
          borderWidth: 1,
          pointRadius: 0,
          order: 0,
        });
        activeCharts.push(new Chart(canvas, {
          type: "line",
          data: { datasets: battDatasets },
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
          const totalOpts = timeSeriesOptions("Current (A)", phases);
          // Add current threshold reference lines
          if (!totalOpts.plugins.annotation) totalOpts.plugins.annotation = { annotations: {} };
          const thresholdLines = [
            { key: "line120", value: 120, label: "120A", color: "rgba(74, 222, 128, 0.7)" },
            { key: "line160", value: 160, label: "160A", color: "rgba(239, 68, 68, 0.7)" },
            { key: "line200", value: 200, label: "200A", color: "rgba(168, 85, 247, 0.7)" },
          ];
          for (const tl of thresholdLines) {
            totalOpts.plugins.annotation.annotations[tl.key] = {
              type: "line",
              yMin: tl.value,
              yMax: tl.value,
              borderColor: tl.color,
              borderWidth: 1.5,
              borderDash: [6, 4],
              label: {
                display: true,
                content: tl.label,
                position: "end",
                backgroundColor: tl.color,
                color: "#fff",
                font: { size: 10, weight: "bold" },
                padding: 3,
              },
            };
          }
          activeCharts.push(new Chart(totalCanvas, {
            type: "line",
            data: { datasets },
            options: totalOpts,
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

      renderVisionSection(vision);
    } catch (e) {
      showError(e);
    }
  }

  // Renders a per-camera vision-quality table from the photonvision-style
  // {accepted, rejected} pose-array streams summarized by VisionAnalyzer.
  // Skipped silently when the log doesn't carry RobotPosesAccepted/Rejected
  // entries (e.g. Limelight-only setups not yet supported here).
  function renderVisionSection(vision) {
    if (!vision || !vision.available || !vision.cameras || vision.cameras.length === 0) return;
    appEl.appendChild(el("h3", {}, "Vision / localization"));

    const strip = el("div", { class: "stats-strip" });
    strip.appendChild(stat("Accepted poses", fmtInt(vision.totalAcceptedPoses)));
    strip.appendChild(stat("Rejected poses", fmtInt(vision.totalRejectedPoses)));
    if (vision.rejectionRate != null) {
      strip.appendChild(stat("Rejection rate", `${(vision.rejectionRate * 100).toFixed(1)}%`,
        vision.rejectionRate > 0.5 ? "critical"
          : vision.rejectionRate > 0.25 ? "warn" : "good"));
    }
    appEl.appendChild(strip);

    const table = el("table");
    const thead = el("thead");
    thead.appendChild(el("tr", {},
      el("th", {}, "Camera"),
      el("th", {}, "Accepted"),
      el("th", {}, "Rejected"),
      el("th", {}, "Reject %"),
      el("th", {}, "Detections (Hz)"),
      el("th", {}, "Mean dist (m)"),
      el("th", {}, "Max dist (m)"),
      el("th", {}, "Worst gap (s)"),
    ));
    table.appendChild(thead);
    const tbody = el("tbody");
    for (const c of vision.cameras) {
      const reject = c.rejectionRate != null ? `${(c.rejectionRate * 100).toFixed(1)}%` : "—";
      const rejectClass = c.rejectionRate != null && c.rejectionRate > 0.5 ? "tba-loss"
        : c.rejectionRate != null && c.rejectionRate > 0.25 ? "warn"
        : "";
      const tr = el("tr", {},
        el("td", {}, c.name),
        el("td", { class: "num" }, fmtInt(c.acceptedPoses)),
        el("td", { class: "num" }, fmtInt(c.rejectedPoses)),
        el("td", { class: "num " + rejectClass }, reject),
        el("td", { class: "num" }, c.detectionsHz != null ? c.detectionsHz.toFixed(1) : "—"),
        el("td", { class: "num" }, c.distanceMean != null ? c.distanceMean.toFixed(2) : "—"),
        el("td", { class: "num" }, c.distanceMax != null ? c.distanceMax.toFixed(2) : "—"),
        el("td", { class: "num" }, c.distanceMaxGapSec != null ? c.distanceMaxGapSec.toFixed(1) : "—"),
      );
      tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    appEl.appendChild(table);
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
    // For each subsystem render two overlaid datasets (same color):
    //   - raw — thin and faint (no legend entry, hidden from tooltip)
    //   - 1 s trailing mean — thicker, drawn on top, owns the legend label
    const datasets = [];
    withSeries.forEach((s, i) => {
      const color = TIME_SERIES_COLORS[i % TIME_SERIES_COLORS.length];
      const hasSmoothed = s.smoothedSeries && s.smoothedSeries.length > 0;
      datasets.push({
        label: hasSmoothed ? `${s.name} (raw)` : s.name,
        data: s.series.map(([t, v]) => ({ x: t, y: v })),
        borderColor: hasSmoothed ? color + "55" : color,
        backgroundColor: "transparent",
        borderWidth: 1,
        pointRadius: 0,
        tension: 0,
        order: 2,
        hidden: hasSmoothed,
      });
      if (hasSmoothed) {
        datasets.push({
          label: s.name,
          data: s.smoothedSeries.map(([t, v]) => ({ x: t, y: v })),
          borderColor: color,
          backgroundColor: "transparent",
          borderWidth: 2,
          pointRadius: 0,
          tension: 0,
          order: 1,
        });
      }
    });
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

  // --- Async TBA loaders --------------------------------------------------
  // These fire in the background after the main page renders so a slow Blue
  // Alliance API never blocks the initial paint. If the user navigates away
  // before the response arrives, the patch step is a no-op because the target
  // element is no longer in the DOM.

  async function loadEventTbaAsync(eventName, table) {
    let data;
    try {
      data = await fetchJson(`${BASE}/api/tba/events/${encodeURIComponent(eventName)}`);
    } catch (e) {
      console.warn("TBA fetch failed for event", eventName, e);
      return;
    }
    if (!document.body.contains(table)) return; // user navigated away
    const byFile = data.byFile || {};
    if (Object.keys(byFile).length === 0) return; // nothing to add
    const headerRow = table.querySelector("thead tr");
    if (headerRow) {
      headerRow.appendChild(el("th", {}, "Score"));
      headerRow.appendChild(el("th", {}, "Links"));
    }
    for (const tr of table.querySelectorAll("tbody tr")) {
      const file = tr.getAttribute("data-file");
      const tba = byFile[file];
      tr.appendChild(tbaScoreCell(tba));
      tr.appendChild(tbaLinksCell(tba));
    }
  }

  async function loadLogTbaAsync(eventName, logName, slot) {
    let data;
    try {
      data = await fetchJson(
        `${BASE}/api/tba/logs/${encodeURIComponent(eventName)}/${encodeURIComponent(logName)}`);
    } catch (e) {
      console.warn("TBA fetch failed for log", eventName, logName, e);
      return;
    }
    if (!document.body.contains(slot)) return; // user navigated away
    const tba = data.tba;
    if (!tba) return;
    const tbaStrip = el("div", { class: "stats-strip" });
    const scoreText = tba.opponent_score != null
      ? `${tba.score}–${tba.opponent_score}` : `${tba.score}`;
    const resultCls = tba.won === true ? "win" : tba.won === false ? "loss" : "";
    const resultLabel = tba.won === true ? "Win" : tba.won === false ? "Loss" : "—";
    tbaStrip.appendChild(stat("Score", scoreText));
    tbaStrip.appendChild(stat("Result", resultLabel,
      resultCls === "win" ? "good" : resultCls === "loss" ? "critical" : ""));
    tbaStrip.appendChild(stat("Alliance", tba.alliance || "—"));
    const linksDiv = el("div", { class: "stat" },
      el("div", { class: "label" }, "LINKS"));
    const linksValue = el("div", { class: "value tba-links" });
    if (tba.match_key) {
      linksValue.appendChild(el("a", {
        href: `https://www.thebluealliance.com/match/${tba.match_key}`,
        target: "_blank", rel: "noopener", title: "View on The Blue Alliance",
      }, "TBA"));
    }
    if (tba.videos && tba.videos.length > 0) {
      for (const v of tba.videos) {
        if (linksValue.childNodes.length > 0) linksValue.appendChild(document.createTextNode(" "));
        const url = v.type === "youtube"
          ? `https://www.youtube.com/watch?v=${v.key}` : v.key;
        linksValue.appendChild(el("a", {
          href: url, target: "_blank", rel: "noopener", title: "Watch match video",
        }, "YouTube"));
      }
    }
    linksDiv.appendChild(linksValue);
    tbaStrip.appendChild(linksDiv);
    slot.replaceWith(tbaStrip);
  }

  // --- TBA helpers --------------------------------------------------------
  function tbaScoreCell(tba) {
    if (!tba) return el("td", {}, "—");
    const score = tba.score;
    const opponentScore = tba.opponent_score;
    const won = tba.won;
    const text = opponentScore != null ? `${score}–${opponentScore}` : `${score}`;
    const cls = won === true ? "tba-win" : won === false ? "tba-loss" : "";
    const td = el("td", { class: "num " + cls });
    td.appendChild(document.createTextNode(text));
    if (won === true) td.appendChild(el("span", { class: "result-tag win" }, "W"));
    else if (won === false) td.appendChild(el("span", { class: "result-tag loss" }, "L"));
    return td;
  }
  function tbaLinksCell(tba) {
    if (!tba) return el("td", {}, "");
    const td = el("td", { class: "tba-links" });
    if (tba.match_key) {
      td.appendChild(el("a", {
        href: `https://www.thebluealliance.com/match/${tba.match_key}`,
        target: "_blank",
        rel: "noopener",
        title: "View on The Blue Alliance",
      }, "TBA"));
    }
    if (tba.videos && tba.videos.length > 0) {
      for (const v of tba.videos) {
        if (td.childNodes.length > 0) td.appendChild(document.createTextNode(" "));
        const url = v.type === "youtube"
          ? `https://www.youtube.com/watch?v=${v.key}`
          : v.key;
        td.appendChild(el("a", {
          href: url,
          target: "_blank",
          rel: "noopener",
          title: "Watch match video",
        }, "YT"));
      }
    }
    return td;
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
