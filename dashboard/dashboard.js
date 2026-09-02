(() => {
  "use strict";
  const data = window.SHARE_SCAN_DATA || { rows: [], counts: {}, errors: {} };
  const rows = Array.isArray(data.rows) ? data.rows : [];
  const $ = (id) => document.getElementById(id);
  const text = (value, fallback = "—") => value === null || value === undefined || value === "" ? fallback : String(value);
  const number = (value) => {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  };
  const compact = (value) => {
    const parsed = number(value);
    return parsed === null ? "—" : new Intl.NumberFormat("en-IN", { notation: "compact", maximumFractionDigits: 2 }).format(parsed);
  };
  const decimal = (value, digits = 2) => {
    const parsed = number(value);
    return parsed === null ? "—" : parsed.toLocaleString("en-IN", { maximumFractionDigits: digits });
  };
  const escape = (value) => text(value, "").replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" }[char]));
  const stateOf = (row) => row.ghost_state || row.cr360_state || row.state || row.mtf_state || row.ghost_signal || "—";
  const errorEntries = (source, prefix = "") => {
    if (!source || typeof source !== "object") return source ? [[prefix || "त्रुटि", String(source)]] : [];
    return Object.entries(source).flatMap(([key, value]) => {
      const label = prefix ? `${prefix} / ${key}` : key;
      return value && typeof value === "object" ? errorEntries(value, label) : value ? [[label, String(value)]] : [];
    });
  };

  $("report-title").textContent = data.title || "Share_scan परिणाम";
  const generated = data.generated_at ? new Date(data.generated_at) : null;
  $("report-meta").textContent = [data.mode, generated && !Number.isNaN(generated.valueOf()) ? generated.toLocaleString("hi-IN") : null].filter(Boolean).join(" • ") || "ऑफ़लाइन स्कैन रिपोर्ट";

  const counts = data.counts && typeof data.counts === "object" ? data.counts : {};
  const summary = [
    ["दिखाए गए परिणाम", rows.length],
    ["Market-cap पास", counts.market_cap ?? data.market_cap_stats?.eligible_rows],
    ["360CR पास", counts.cr360 ?? data.execution?.cr360?.successful],
    ["अंतिम पास", counts.final_pass ?? rows.length],
  ];
  $("summary-cards").innerHTML = summary.map(([label, value]) => `<article class="summary-card"><span>${escape(label)}</span><strong>${escape(text(value))}</strong></article>`).join("");

  const pipelineItems = Object.entries(counts).filter(([, value]) => number(value) !== null);
  const maxCount = Math.max(1, ...pipelineItems.map(([, value]) => number(value)));
  const labels = { daily_usable: "Daily data", market_cap: "Market cap", cr360_collected: "360CR collected", cr360: "360CR पास", volume: "Volume पास", ghost_attempted: "Ghost प्रयास", ghost_analysed: "Ghost analysis", stage4_ready_confirmed: "Stage 4 READY", stage5_ghost_score: "Stage 5 Ghost", timeframes: "Timeframes", false_breakout: "False-breakout", valid_entry: "Valid entry", final_pass: "Final pass" };
  $("pipeline").innerHTML = pipelineItems.length ? pipelineItems.map(([key, value]) => `<div class="pipeline-row"><span class="pipeline-name">${escape(labels[key] || key)}</span><strong class="pipeline-count">${escape(value)}</strong><div class="bar"><span style="width:${Math.max(1, number(value) / maxCount * 100)}%"></span></div></div>`).join("") : `<p class="meta">इस स्कैन में चरणों की अलग गणना उपलब्ध नहीं है।</p>`;

  const errors = errorEntries(data.errors);
  $("error-badge").textContent = `${errors.length} त्रुटियाँ`;
  $("error-badge").classList.toggle("has-errors", errors.length > 0);
  $("errors-panel").hidden = errors.length === 0;
  $("errors-list").innerHTML = errors.length ? `<div class="error-group"><ul>${errors.map(([key, value]) => `<li><strong>${escape(key)}:</strong> ${escape(value)}</li>`).join("")}</ul></div>` : "";

  const states = [...new Set(rows.map(stateOf).filter((state) => state !== "—"))].sort();
  $("state-filter").insertAdjacentHTML("beforeend", states.map((state) => `<option value="${escape(state)}">${escape(state)}</option>`).join(""));

  let visibleRows = [];
  function render() {
    const query = $("search").value.trim().toUpperCase();
    const selectedState = $("state-filter").value;
    const sortBy = $("sort-by").value;
    visibleRows = rows.filter((row) => (!query || String(row.symbol || "").toUpperCase().includes(query)) && (!selectedState || stateOf(row) === selectedState));
    visibleRows.sort((a, b) => {
      if (sortBy === "rank") return (number(a.rank ?? a.volume_rank) ?? 1e9) - (number(b.rank ?? b.volume_rank) ?? 1e9);
      const av = number(a[sortBy]); const bv = number(b[sortBy]);
      if (sortBy === "false_breakout_risk") return (av ?? 1e9) - (bv ?? 1e9);
      return (bv ?? -1) - (av ?? -1);
    });
    $("results-body").innerHTML = visibleRows.map((row, index) => {
      const risk = number(row.false_breakout_risk);
      const riskClass = risk !== null && risk > 35 ? "risk-high" : risk !== null && risk > 20 ? "risk-mid" : "";
      return `<tr><td>${escape(text(row.rank ?? row.volume_rank ?? index + 1))}</td><td class="symbol">${escape(row.symbol)}</td><td>${decimal(row.price ?? row.close)}</td><td>${decimal(row.market_cap_cr)}</td><td>${compact(row.volume ?? row.current_volume)}</td><td>${decimal(row.rvol20 ?? row.rvol_daily ?? row.rvol)}</td><td class="score">${decimal(row.cr360_score)}</td><td class="score">${decimal(row.ghost_score)}</td><td>${escape(stateOf(row))}</td><td class="${riskClass}">${decimal(risk)}</td><td>${decimal(row.entry)}</td><td>${decimal(row.stop)}</td><td>${decimal(row.target1)}</td></tr>`;
    }).join("");
    $("visible-count").textContent = `${visibleRows.length} / ${rows.length} शेयर`;
    $("empty-state").hidden = visibleRows.length !== 0;
  }

  ["search", "state-filter", "sort-by"].forEach((id) => $(id).addEventListener(id === "search" ? "input" : "change", render));
  $("theme-toggle").addEventListener("click", () => {
    document.body.classList.toggle("light");
    $("theme-toggle").textContent = document.body.classList.contains("light") ? "गहरा रंग" : "हल्का रंग";
  });
  $("csv-download").addEventListener("click", () => {
    if (!visibleRows.length) return;
    const keys = [...new Set(visibleRows.flatMap(Object.keys))].filter((key) => !["ghost_details", "cr360_evidence", "cr360_sections", "cr360_metadata"].includes(key));
    const cell = (value) => `"${String(value ?? "").replaceAll('"', '""')}"`;
    const csv = [keys.map(cell).join(","), ...visibleRows.map((row) => keys.map((key) => cell(typeof row[key] === "object" ? JSON.stringify(row[key]) : row[key])).join(","))].join("\n");
    const link = document.createElement("a");
    link.href = URL.createObjectURL(new Blob(["\ufeff", csv], { type: "text/csv;charset=utf-8" }));
    link.download = "share_scan_visible_results.csv";
    link.click();
    URL.revokeObjectURL(link.href);
  });
  render();
})();
