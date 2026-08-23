"use strict";

const PERCENT = new Intl.NumberFormat(undefined, {
  style: "percent",
  maximumSignificantDigits: 3
});

function node(tag, className, text) {
  const value = document.createElement(tag);
  if (className) value.className = className;
  if (text !== undefined) value.textContent = text;
  return value;
}

function sameOriginPath(value) {
  const url = new URL(value, window.location.href);
  if (url.origin !== window.location.origin) throw new Error("Cross-origin image path rejected");
  return url.href;
}

function httpsLink(value) {
  const url = new URL(value);
  if (url.protocol !== "https:") throw new Error("Non-HTTPS provenance link rejected");
  return url.href;
}

function externalLink(href, text) {
  const link = node("a", "", text);
  link.href = httpsLink(href);
  link.target = "_blank";
  link.rel = "noopener";
  return link;
}

function option(select, value, label) {
  const item = document.createElement("option");
  item.value = value;
  item.textContent = label;
  select.append(item);
}

function unique(values) {
  return [...new Set(values)].sort((left, right) => left.localeCompare(right, undefined, { numeric: true }));
}

function friendlyScenario(value) {
  return {
    "phase0-smoke": "Smoke",
    "propagation": "Propagation",
    "propagation-live": "Live propagation",
    "full": "Full suite"
  }[value] || value;
}

function friendlyRole(value) {
  return { client_a: "Client A", client_b: "Client B" }[value] || value;
}

function friendlyTier(value) {
  return {
    key: "key — included in every advisory AI review",
    all: "all — included when the advisory review covers every capture"
  }[value] || value;
}

function percent(value) {
  return typeof value === "number" && Number.isFinite(value) ? PERCENT.format(value) : "—";
}

function seconds(value) {
  return typeof value === "number" && Number.isFinite(value) ? `${value.toFixed(1)} s` : "—";
}

function regionLabel(region) {
  if (!Array.isArray(region) || region.length !== 4) return "the whole frame";
  const [left, top, right, bottom] = region;
  return `x ${percent(left)}–${percent(right)}, y ${percent(top)}–${percent(bottom)} of the frame`;
}

function factList(rows) {
  const list = node("dl", "fact-list");
  for (const [term, value, className] of rows) {
    if (value === undefined || value === null || value === "") continue;
    const definition = node("dd", className);
    if (value instanceof Node) definition.append(value);
    else definition.textContent = String(value);
    list.append(node("dt", "", term), definition);
  }
  return list;
}

function recordSection(title, lead, ...children) {
  const section = node("section", "record-section");
  section.append(node("h3", "", title));
  if (lead) section.append(node("p", "record-lead", lead));
  section.append(...children);
  return section;
}

function pixelFacts(metrics) {
  if (!metrics) return factList([["Measurements", "not published for this image"]]);
  return factList([
    ["Decoded size", `${metrics.width} × ${metrics.height}`],
    ["File SHA-256", node("span", "mono", metrics.file_sha256)],
    ["Pixel SHA-256", node("span", "mono", metrics.pixel_sha256)],
    ["Luma entropy", metrics.luma_entropy],
    ["Distinct meaningful colours", metrics.meaningful_colors],
    ["Near-black pixels", percent(metrics.dark_fraction)],
    ["Near-white pixels", percent(metrics.light_fraction)]
  ]);
}

function comparisonFacts(metrics) {
  return factList([
    ["Changed pixels", percent(metrics.changed_fraction)],
    ["Required minimum", percent(metrics.required_changed_fraction)],
    ["RMS difference", metrics.rms_difference],
    ["Measured region", regionLabel(metrics.region)]
  ]);
}

function captureCard(frame, open) {
  const figure = node("figure", "capture-card");
  const trigger = node("button", "capture-open");
  trigger.type = "button";
  trigger.setAttribute(
    "aria-label",
    `Open the validation record for ${frame.title}, Minecraft ${frame.version}, ${frame.loader_name}, ${friendlyRole(frame.role)}`
  );
  const image = document.createElement("img");
  image.src = sameOriginPath(frame.image);
  image.alt = frame.alt;
  image.width = frame.width;
  image.height = frame.height;
  image.loading = "lazy";
  image.decoding = "async";
  trigger.append(image);
  trigger.addEventListener("click", () => open(frame));

  const caption = document.createElement("figcaption");
  const titleRow = node("div", "capture-title-row");
  titleRow.append(node("h3", "", frame.title), node("span", "verified-badge", "Passed"));
  const metadata = node("div", "capture-meta");
  for (const text of [frame.version, frame.loader_name, friendlyScenario(frame.scenario), friendlyRole(frame.role)]) {
    metadata.append(node("span", "", text));
  }
  const details = document.createElement("details");
  const summary = document.createElement("summary");
  summary.textContent = "What this validates";
  details.append(summary, node("p", "", frame.expectation));
  const record = node("button", "record-button", "Full validation record");
  record.type = "button";
  record.addEventListener("click", () => open(frame));
  const provenance = node("div", "provenance-line");
  if (frame.source_run_url === frame.target_run_url) {
    provenance.append(
      externalLink(frame.target_run_url, "tested & publishing run ↗"),
      node("span", "", frame.target_sha.slice(0, 12))
    );
  } else {
    provenance.append(
      externalLink(frame.source_run_url, "tested source run ↗"),
      node("span", "", frame.source_sha.slice(0, 12)),
      externalLink(frame.target_run_url, "publishing target run ↗"),
      node("span", "", frame.target_sha.slice(0, 12))
    );
  }
  caption.append(titleRow, metadata, details, record, provenance);
  figure.append(trigger, caption);
  return figure;
}

function missingCard(label) {
  const card = node("div", "missing-card");
  const copy = node("div");
  copy.append(node("h3", "", label), node("p", "", "No validated capture was published for this exact cell."));
  card.append(copy);
  return card;
}

function notApplicableCard(label, loader) {
  const card = node("div", "missing-card");
  const copy = node("div");
  copy.append(
    node("h3", "", label),
    node("p", "", `Not applicable — ${loader} is not supported by this Minecraft version.`)
  );
  card.append(copy);
  return card;
}

function compatibilityShot(record, label, alt) {
  const figure = node("figure", "compatibility-shot");
  const image = document.createElement("img");
  image.src = sameOriginPath(record.image);
  image.alt = alt;
  image.width = record.width;
  image.height = record.height;
  image.loading = "lazy";
  image.decoding = "async";
  const caption = document.createElement("figcaption");
  caption.append(node("strong", "", label));
  const imageLink = node("a", "", "Open image ↗");
  imageLink.href = sameOriginPath(record.image);
  imageLink.target = "_blank";
  imageLink.rel = "noopener";
  caption.append(imageLink);
  figure.append(image, caption);
  return figure;
}

function compatibilityCheckpoint(frame, lane) {
  const checkpoint = node("article", "compatibility-checkpoint");
  const heading = node("div", "compatibility-checkpoint-heading");
  heading.append(node("h4", "", frame.title));
  const badges = node("div", "compatibility-badges");
  badges.append(
    node("span", "verified-badge", "Runtime passed"),
    node("span", "verified-badge", "AI clean")
  );
  heading.append(badges);

  const pair = node("div", "compatibility-pair");
  pair.append(
    compatibilityShot(
      frame.reference,
      "Clean reference",
      `${frame.title} clean E2E reference for Minecraft ${lane.version}, ${lane.loader_name}`
    ),
    compatibilityShot(
      frame.candidate,
      `${lane.mod_name} installed`,
      `${frame.title} with ${lane.mod_name} ${lane.mod_version} installed on Minecraft ${lane.version}, ${lane.loader_name}`
    )
  );

  const details = document.createElement("details");
  const summary = document.createElement("summary");
  summary.textContent = "Validation details";
  details.append(
    summary,
    node("p", "record-prose", frame.expectation),
    factList([
      ["Deterministic assertion", node("span", "mono", frame.runtime_evidence)],
      ["Semantic pixels changed", percent(frame.semantic_changed_fraction)],
      ["Perceptual delta", percent(frame.perceptual_delta)],
      ["Candidate semantic SHA-256", node("span", "mono", frame.candidate_semantic_sha256)],
      ["Reference semantic SHA-256", node("span", "mono", frame.reference_semantic_sha256)]
    ])
  );
  checkpoint.append(heading, pair, details);
  return checkpoint;
}

function compatibilityLaneCard(lane) {
  const card = node("section", "compatibility-lane");
  const header = node("header", "compatibility-lane-heading");
  const title = node("div");
  title.append(
    node("p", "eyebrow", `Minecraft ${lane.version} · ${lane.loader_name}`),
    node("h3", "", `${lane.mod_name} ${lane.mod_version}`)
  );
  const reviewed = node(
    "span",
    "review-count",
    `${lane.reviewed_frame_count}/${lane.reviewed_frame_count} reviewed frames clean`
  );
  header.append(title, reviewed);

  const metadata = node("div", "capture-meta");
  for (const value of [lane.artifact_node, lane.mod_version_id, lane.lane_id]) {
    metadata.append(node("span", "mono", value));
  }
  const checkpoints = node("div", "compatibility-checkpoints");
  for (const frame of lane.frames) checkpoints.append(compatibilityCheckpoint(frame, lane));

  const proof = document.createElement("details");
  proof.className = "compatibility-proof";
  const proofSummary = document.createElement("summary");
  proofSummary.textContent = "Authenticated review identity";
  proof.append(
    proofSummary,
    factList([
      ["Review manifest SHA-256", node("span", "mono", lane.review_manifest_sha256)],
      ["Curation proof SHA-256", node("span", "mono", lane.curation_proof_sha256)],
      ["Normalized report SHA-256", node("span", "mono", lane.review_report_sha256)]
    ])
  );

  const provenance = node("div", "compatibility-provenance");
  provenance.append(
    externalLink(lane.base_run_url, "clean reference run ↗"),
    externalLink(lane.compatibility_run_url, "compatibility runtime run ↗"),
    externalLink(lane.review_run_url, "complete AI review ↗"),
    externalLink(lane.publication_run_url, "publication run ↗")
  );
  card.append(header, metadata, checkpoints, proof, provenance);
  return card;
}

class Gallery {
  constructor(data) {
    this.data = data;
    this.activeTab = 0;
    this.tabs = data.releases.map((release) => ({
        id: `v-${release.version.replaceAll(".", "-")}`,
        label: `Minecraft ${release.version}`,
        version: release.version
      })).concat([{ id: "all", label: "All versions", version: null }]);
    this.tabButtons = [];
    this.panels = [];
    this.dialog = document.querySelector("#capture-dialog");
    this.openCapture = (frame) => this.showRecord(frame);
    this.releaseByVersion = new Map(data.releases.map((release) => [release.version, release]));
    this.laneById = new Map(data.lanes.map((lane) => [lane.lane_id, lane]));
    this.frameById = new Map(data.frames.map((frame) => [frame.frame_id, frame]));
    this.comparisonsByFrame = new Map();
    for (const comparison of data.comparisons) {
      for (const frameId of [comparison.first_frame_id, comparison.second_frame_id]) {
        if (!this.comparisonsByFrame.has(frameId)) this.comparisonsByFrame.set(frameId, []);
        this.comparisonsByFrame.get(frameId).push(comparison);
      }
    }
  }

  start() {
    this.renderSummary();
    this.populateFilters();
    this.populateCompatibilityFilters();
    this.createTabs();
    this.bindFilters();
    this.bindViewSwitch();
    this.bindDialog();
    this.renderComparison();
    this.renderCompatibility();
    this.renderGallery();
  }

  renderSummary() {
    const summary = document.querySelector("#release-summary");
    const total = this.data.frames.length;
    summary.append(node("span", "summary-item", `${this.data.releases.length} Minecraft versions`));
    summary.append(node("span", "summary-item", `${total} validated captures`));
    summary.append(
      node(
        "span",
        "summary-item",
        `${this.data.compatibility.lanes.length} published mod-compatibility lane${this.data.compatibility.lanes.length === 1 ? "" : "s"}`
      )
    );
    for (const release of this.data.releases) {
      const item = node("span", "summary-item");
      const strong = document.createElement("strong");
      strong.textContent = release.version;
      item.append(strong, document.createTextNode(` · ${release.loader_names.join(" + ")}`));
      summary.append(item);
    }
  }

  populateFilters() {
    const loaders = unique(this.data.frames.map((frame) => frame.loader));
    const scenarios = unique(this.data.frames.map((frame) => frame.scenario));
    const roles = unique(this.data.frames.map((frame) => frame.role));
    for (const loader of loaders) {
      const label = this.data.frames.find((frame) => frame.loader === loader).loader_name;
      option(document.querySelector("#loader-filter"), loader, label);
      option(document.querySelector("#compare-loader"), loader, label);
    }
    for (const scenario of scenarios) option(document.querySelector("#scenario-filter"), scenario, friendlyScenario(scenario));
    for (const role of roles) option(document.querySelector("#role-filter"), role, friendlyRole(role));

    const captures = new Map();
    for (const frame of this.data.frames) {
      if (!captures.has(frame.capture_id)) {
        captures.set(frame.capture_id, `${frame.title} · ${friendlyScenario(frame.scenario)} · ${friendlyRole(frame.role)}`);
      }
    }
    const compare = document.querySelector("#capture-compare");
    for (const [captureId, label] of [...captures].sort((left, right) => left[1].localeCompare(right[1]))) {
      option(compare, captureId, label);
    }
  }

  populateCompatibilityFilters() {
    const evidence = this.data.compatibility;
    const rows = evidence.lanes.concat(evidence.not_applicable);
    const versionSelect = document.querySelector("#compatibility-version");
    for (const version of unique(rows.map((row) => row.version))) {
      option(versionSelect, version, `Minecraft ${version}`);
    }
    const loaderSelect = document.querySelector("#compatibility-loader");
    for (const loader of unique(rows.map((row) => row.loader))) {
      const row = rows.find((item) => item.loader === loader);
      option(loaderSelect, loader, row.loader_name || loader);
    }
    const mods = new Map();
    for (const row of rows) mods.set(row.mod, row.mod_name);
    const modSelect = document.querySelector("#compatibility-mod");
    for (const [mod, name] of [...mods].sort((left, right) => left[1].localeCompare(right[1]))) {
      option(modSelect, mod, name);
    }
  }

  createTabs() {
    const tablist = document.querySelector("#version-tabs");
    const panels = document.querySelector("#version-panels");
    this.tabs.forEach((tab, index) => {
      const button = node("button", "version-tab", tab.label);
      button.type = "button";
      button.id = `tab-${tab.id}`;
      button.setAttribute("role", "tab");
      button.setAttribute("aria-controls", `panel-${tab.id}`);
      button.setAttribute("aria-selected", index === 0 ? "true" : "false");
      button.tabIndex = index === 0 ? 0 : -1;
      button.addEventListener("click", () => this.activateTab(index, false));
      button.addEventListener("keydown", (event) => this.handleTabKey(event, index));
      tablist.append(button);
      this.tabButtons.push(button);

      const panel = node("section", "version-panel");
      panel.id = `panel-${tab.id}`;
      panel.setAttribute("role", "tabpanel");
      panel.setAttribute("aria-labelledby", button.id);
      panel.tabIndex = 0;
      panel.hidden = index !== 0;
      panels.append(panel);
      this.panels.push(panel);
    });
  }

  handleTabKey(event, index) {
    let target = null;
    if (event.key === "ArrowRight") target = (index + 1) % this.tabs.length;
    if (event.key === "ArrowLeft") target = (index - 1 + this.tabs.length) % this.tabs.length;
    if (event.key === "Home") target = 0;
    if (event.key === "End") target = this.tabs.length - 1;
    if (target !== null) {
      event.preventDefault();
      this.activateTab(target, true);
    }
  }

  activateTab(index, focus) {
    this.activeTab = index;
    this.tabButtons.forEach((button, buttonIndex) => {
      const active = buttonIndex === index;
      button.setAttribute("aria-selected", active ? "true" : "false");
      button.tabIndex = active ? 0 : -1;
      this.panels[buttonIndex].hidden = !active;
    });
    if (focus) this.tabButtons[index].focus();
    this.renderGallery();
  }

  bindFilters() {
    document.querySelector("#gallery-filters").addEventListener("submit", (event) => event.preventDefault());
    for (const selector of ["#loader-filter", "#scenario-filter", "#role-filter"]) {
      document.querySelector(selector).addEventListener("change", () => this.renderGallery());
    }
    document.querySelector("#capture-search").addEventListener("input", () => this.renderGallery());
    document.querySelector("#capture-compare").addEventListener("change", () => this.renderComparison());
    document.querySelector("#compare-loader").addEventListener("change", () => this.renderComparison());
    document.querySelector("#compatibility-filters").addEventListener("submit", (event) => event.preventDefault());
    for (const selector of ["#compatibility-version", "#compatibility-loader", "#compatibility-mod"]) {
      document.querySelector(selector).addEventListener("change", () => this.renderCompatibility());
    }
  }

  bindViewSwitch() {
    const galleryButton = document.querySelector("#gallery-view-button");
    const compareButton = document.querySelector("#compare-view-button");
    const compatibilityButton = document.querySelector("#compatibility-view-button");
    const views = {
      gallery: [galleryButton, document.querySelector("#gallery-view"), () => this.renderGallery()],
      compare: [compareButton, document.querySelector("#compare-view"), () => this.renderComparison()],
      compatibility: [compatibilityButton, document.querySelector("#compatibility-view"), () => this.renderCompatibility()]
    };
    const setView = (selected) => {
      for (const [name, [button, view]] of Object.entries(views)) {
        const active = name === selected;
        view.hidden = !active;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", active ? "true" : "false");
      }
      views[selected][2]();
    };
    galleryButton.addEventListener("click", () => setView("gallery"));
    compareButton.addEventListener("click", () => setView("compare"));
    compatibilityButton.addEventListener("click", () => setView("compatibility"));
  }

  bindDialog() {
    this.dialog.addEventListener("click", (event) => {
      if (event.target === this.dialog) this.dialog.close();
    });
    this.dialog.addEventListener("close", () => {
      document.querySelector("#capture-dialog-body").replaceChildren();
    });
  }

  filteredFrames() {
    const tab = this.tabs[this.activeTab];
    const loader = document.querySelector("#loader-filter").value;
    const scenario = document.querySelector("#scenario-filter").value;
    const role = document.querySelector("#role-filter").value;
    const search = document.querySelector("#capture-search").value.trim().toLocaleLowerCase();
    return this.data.frames.filter((frame) => {
      if (tab.version && frame.version !== tab.version) return false;
      if (loader !== "all" && frame.loader !== loader) return false;
      if (scenario !== "all" && frame.scenario !== scenario) return false;
      if (role !== "all" && frame.role !== role) return false;
      if (search && !`${frame.title} ${frame.expectation} ${frame.capture_id}`.toLocaleLowerCase().includes(search)) return false;
      return true;
    });
  }

  renderGallery() {
    const panel = this.panels[this.activeTab];
    panel.replaceChildren();
    const frames = this.filteredFrames();
    if (!frames.length) {
      panel.append(node("div", "empty-state", "No validated captures match these filters."));
    } else {
      const grid = node("div", "capture-grid");
      for (const frame of frames) grid.append(captureCard(frame, this.openCapture));
      panel.append(grid);
    }
    document.querySelector("#gallery-status").textContent = `${frames.length} validated capture${frames.length === 1 ? "" : "s"} shown`;
  }

  renderComparison() {
    const captureId = document.querySelector("#capture-compare").value;
    const selectedLoader = document.querySelector("#compare-loader").value;
    const grid = document.querySelector("#comparison-grid");
    grid.replaceChildren();
    if (!captureId) return;
    let cells = 0;
    for (const release of this.data.releases) {
      const loaders = selectedLoader === "all" ? release.loaders : [selectedLoader];
      for (const loader of loaders) {
        const label = `${release.version} · ${release.loader_names[release.loaders.indexOf(loader)] || loader}`;
        const cell = node("section", "comparison-cell");
        const cellLabel = node("span", "compare-column-label", label);
        cellLabel.id = `compare-cell-${cells}`;
        cell.setAttribute("aria-labelledby", cellLabel.id);
        cell.append(cellLabel);
        if (!release.loaders.includes(loader)) {
          cell.append(notApplicableCard(label, loader));
          grid.append(cell);
          cells += 1;
          continue;
        }
        const frame = this.data.frames.find(
          (item) => item.version === release.version && item.loader === loader && item.capture_id === captureId
        );
        cell.append(frame ? captureCard(frame, this.openCapture) : missingCard(label));
        grid.append(cell);
        cells += 1;
      }
    }
    document.querySelector("#gallery-status").textContent = `${cells} version/loader cell${cells === 1 ? "" : "s"} aligned by semantic checkpoint`;
  }

  compatibilityMatches(row) {
    const version = document.querySelector("#compatibility-version").value;
    const loader = document.querySelector("#compatibility-loader").value;
    const mod = document.querySelector("#compatibility-mod").value;
    return (version === "all" || row.version === version)
      && (loader === "all" || row.loader === loader)
      && (mod === "all" || row.mod === mod);
  }

  renderCompatibility() {
    const grid = document.querySelector("#compatibility-grid");
    const na = document.querySelector("#compatibility-not-applicable");
    grid.replaceChildren();
    na.replaceChildren();
    const lanes = this.data.compatibility.lanes.filter((lane) => this.compatibilityMatches(lane));
    const notApplicable = this.data.compatibility.not_applicable.filter((row) => this.compatibilityMatches(row));
    if (!lanes.length) {
      grid.append(
        node(
          "div",
          "empty-state",
          this.data.compatibility.available
            ? "No published compatibility lane matches these filters."
            : "No compact mod-compatibility evidence has been published yet."
        )
      );
    } else {
      for (const lane of lanes) grid.append(compatibilityLaneCard(lane));
    }
    if (notApplicable.length) {
      const details = document.createElement("details");
      const summary = document.createElement("summary");
      summary.textContent = `${notApplicable.length} explicitly not-applicable combination${notApplicable.length === 1 ? "" : "s"}`;
      const list = node("ul", "compatibility-na-list");
      for (const row of notApplicable) {
        list.append(
          node(
            "li",
            "",
            `Minecraft ${row.version} · ${row.loader_name} · ${row.mod_name}: ${row.reason}`
          )
        );
      }
      details.append(summary, list);
      na.append(details);
    }
    document.querySelector("#gallery-status").textContent = `${lanes.length} published compatibility lane${lanes.length === 1 ? "" : "s"} shown`;
  }

  recordHeader(frame) {
    const header = node("header", "record-header");
    const titleRow = node("div", "capture-title-row");
    const title = node("h2", "", frame.title);
    title.id = "capture-dialog-title";
    titleRow.append(title, node("span", "verified-badge", "Gate passed"));
    const metadata = node("div", "capture-meta");
    for (const text of [
      `Minecraft ${frame.version}`,
      frame.loader_name,
      friendlyScenario(frame.scenario),
      friendlyRole(frame.role),
      `step ${frame.step}`
    ]) {
      metadata.append(node("span", "", text));
    }
    header.append(titleRow, metadata);
    return header;
  }

  recordFigure(frame) {
    const figure = node("figure", "record-figure");
    const image = document.createElement("img");
    image.src = sameOriginPath(frame.image);
    image.alt = frame.alt;
    image.width = frame.width;
    image.height = frame.height;
    image.decoding = "async";
    const caption = document.createElement("figcaption");
    const link = node("a", "", `Open the published ${frame.published_format.toUpperCase()} ↗`);
    link.href = sameOriginPath(frame.image);
    link.target = "_blank";
    link.rel = "noopener";
    caption.append(link);
    figure.append(image, caption);
    return figure;
  }

  contractSection(frame, release) {
    const rows = [
      ["Scenario", `${friendlyScenario(frame.scenario)} (${frame.scenario})`],
      ["Client role", `${friendlyRole(frame.role)} (${frame.role})`],
      ["Report step", node("span", "mono", frame.step)],
      ["Capture id", node("span", "mono", frame.capture_id)],
      ["Contract order", frame.capture_order],
      ["Advisory review tier", friendlyTier(frame.review_tier)]
    ];
    if (release) {
      rows.push(["Contract SHA-256", node("span", "mono", release.contract_sha256)]);
      rows.push([
        "Contract source",
        externalLink(release.contract_url, "scenario-contract.json at this commit ↗")
      ]);
    }
    return recordSection(
      "Checkpoint contract",
      "The versioned scenario contract is the only authored source for this checkpoint. Its expectation, ordering and review tier are fixed before the run.",
      node("p", "record-prose", frame.expectation),
      factList(rows)
    );
  }

  assertionSection(frame) {
    const lead = "The packaged Minecraft client emitted this message when the checkpoint's mandatory assertion passed. A failed, empty or missing assertion stops the run before any screenshot can be published.";
    if (typeof frame.runtime_evidence !== "string" || !frame.runtime_evidence) {
      return recordSection(
        "Deterministic assertion",
        lead,
        node(
          "p",
          "record-lead",
          "This capture comes from evidence published before the assertion message was carried into the public bundle. The assertion still gated the run; it is not part of this bundle. It appears once this version republishes its evidence."
        )
      );
    }
    return recordSection(
      "Deterministic assertion",
      lead,
      node("p", "evidence-quote mono", frame.runtime_evidence)
    );
  }

  integritySection(frame) {
    const columns = node("div", "record-columns");
    const source = node("div");
    source.append(
      node("h4", "", "Original capture (PNG)"),
      node("p", "record-lead", "Written by the packaged client, decoded and measured by the gate. Its bytes never leave the short-lived run artifact."),
      pixelFacts(frame.source_pixel_validation)
    );
    const published = node("div");
    published.append(
      node("h4", "", `Published image (${frame.published_format.toUpperCase()})`),
      node("p", "record-lead", "Re-decoded and re-measured by protected code before deployment. The file name is the SHA-256 of the exact bytes served to you."),
      pixelFacts(frame.published_pixel_validation)
    );
    columns.append(source, published);
    return recordSection(
      "Image integrity",
      "Both images are decoded and rejected if they are corrupt, implausibly sized or effectively blank.",
      columns
    );
  }

  comparisonSection(frame) {
    const comparisons = this.comparisonsByFrame.get(frame.frame_id) || [];
    if (!comparisons.length) {
      return recordSection(
        "Pixel comparisons",
        "This checkpoint is not part of a required pixel-change pair; its proof is the assertion plus the image integrity checks above."
      );
    }
    const children = [];
    for (const comparison of comparisons) {
      const first = this.frameById.get(comparison.first_frame_id);
      const second = this.frameById.get(comparison.second_frame_id);
      const pair = node("article", "comparison-record");
      const role = comparison.first_frame_id === frame.frame_id ? "before" : "after";
      pair.append(
        node("h4", "", `${first ? first.title : comparison.first_frame_id} → ${second ? second.title : comparison.second_frame_id}`),
        node("p", "record-lead", `This capture is the ${role} frame of the pair. The run fails unless the measured change reaches the contract minimum.`)
      );
      const columns = node("div", "record-columns");
      const original = node("div");
      original.append(node("h5", "", "Measured on the original PNGs"), comparisonFacts(comparison.source_pixel_validation));
      columns.append(original);
      if (comparison.published_pixel_validation) {
        const derived = node("div");
        derived.append(
          node("h5", "", "Re-measured on the published images"),
          comparisonFacts(comparison.published_pixel_validation)
        );
        columns.append(derived);
      }
      pair.append(columns);
      children.push(pair);
    }
    return recordSection(
      "Pixel comparisons",
      "Directed comparisons prove the interface actually changed between two checkpoints instead of repeating one frame.",
      ...children
    );
  }

  laneSection(frame) {
    const lane = this.laneById.get(frame.lane_id);
    if (!lane) {
      return recordSection("Packaged lane", "No lane record was published for this capture.");
    }
    return recordSection(
      "Packaged lane",
      "The capture comes from a real headless Minecraft run of the packaged production JAR, not a development launch.",
      factList([
        ["Lane", node("span", "mono", lane.lane_id)],
        ["Artifact node", node("span", "mono", lane.artifact_node)],
        ["Loader", `${lane.loader_name} · Minecraft ${lane.version}`],
        ["Clients in this lane", lane.roles.map(friendlyRole).join(", ")],
        ["Mod JAR SHA-256", node("span", "mono", lane.jar_sha256)],
        ["Lane result", lane.status === "pass" ? "pass" : lane.status],
        ["Lane wall time", seconds(lane.elapsed_s)]
      ])
    );
  }

  provenanceSection(frame) {
    const rows = [
      ["Tested run", externalLink(frame.source_run_url, "GitHub Actions run ↗")],
      ["Tested branch", node("span", "mono", frame.source_branch)],
      ["Tested commit", node("span", "mono", frame.source_sha)],
      ["Tested at", frame.source_created_at]
    ];
    if (frame.source_run_url !== frame.target_run_url || frame.source_sha !== frame.target_sha) {
      rows.push(
        ["Publishing run", externalLink(frame.target_run_url, "GitHub Actions run ↗")],
        ["Release branch", node("span", "mono", frame.target_branch)],
        ["Published commit", node("span", "mono", frame.target_sha)],
        ["Published at", frame.target_created_at]
      );
    }
    return recordSection(
      "Provenance",
      "Evidence is only published when its recorded runs and commits still match the current release-branch head.",
      factList(rows)
    );
  }

  rawSection(frame) {
    const details = document.createElement("details");
    details.className = "record-raw";
    const summary = document.createElement("summary");
    summary.textContent = "Machine-readable record";
    const payload = {
      frame,
      lane: this.laneById.get(frame.lane_id) || null,
      comparisons: this.comparisonsByFrame.get(frame.frame_id) || []
    };
    const block = document.createElement("pre");
    block.textContent = JSON.stringify(payload, null, 2);
    details.append(summary, block);
    return details;
  }

  showRecord(frame) {
    const body = document.querySelector("#capture-dialog-body");
    const release = this.releaseByVersion.get(frame.version);
    body.replaceChildren(
      this.recordHeader(frame),
      this.recordFigure(frame),
      this.contractSection(frame, release),
      this.assertionSection(frame),
      this.integritySection(frame),
      this.comparisonSection(frame),
      this.laneSection(frame),
      this.provenanceSection(frame),
      this.rawSection(frame)
    );
    if (typeof this.dialog.showModal === "function") this.dialog.showModal();
    else this.dialog.setAttribute("open", "");
  }
}

async function startGallery() {
  const status = document.querySelector("#gallery-status");
  try {
    const response = await fetch("gallery-data.json", { credentials: "same-origin" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    if (
      data.schema_version !== 3
      || !Array.isArray(data.frames)
      || !data.frames.length
      || !Array.isArray(data.releases)
      || !Array.isArray(data.lanes)
      || !Array.isArray(data.comparisons)
      || !data.compatibility
      || typeof data.compatibility.available !== "boolean"
      || !Array.isArray(data.compatibility.releases)
      || !Array.isArray(data.compatibility.lanes)
      || !Array.isArray(data.compatibility.not_applicable)
    ) {
      throw new Error("Unsupported or empty gallery inventory");
    }
    new Gallery(data).start();
  } catch (error) {
    status.dataset.error = "true";
    status.textContent = `The evidence gallery could not be loaded: ${error.message}`;
  }
}

startGallery();
