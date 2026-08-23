"use strict";

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function safeHref(value) {
  const url = new URL(value, window.location.href);
  if (url.protocol !== "https:" && url.origin !== window.location.origin) {
    throw new Error("Unsupported link protocol");
  }
  return url.href;
}

function linkCard(title, description, href) {
  const link = element("a", "link-card");
  link.href = safeHref(href);
  const copy = element("div");
  copy.append(element("h3", "", title), element("p", "", description));
  link.append(copy, element("span", "card-arrow", "↗"));
  return link;
}

function releaseCard(release) {
  const link = element("a", "release-card");
  link.href = safeHref(release.source_run_url);
  link.setAttribute(
    "aria-label",
    `Minecraft ${release.version}, Verified, ${release.loader_names.join(" and ")}, ${release.frame_count} validated captures, commit ${release.short_sha}; open packaged E2E run on GitHub Actions`
  );

  const header = element("header");
  header.append(
    element("span", "version-number", release.version),
    element("span", "verified-badge", "Verified")
  );
  const body = element("div");
  body.append(element("p", "", `${release.frame_count} validated captures`));
  const badges = element("div", "release-meta");
  for (const loader of release.loader_names) badges.append(element("span", "meta-badge", loader));
  badges.append(element("span", "meta-badge", `commit ${release.short_sha}`));
  body.append(badges);
  link.append(header, body);
  return link;
}

async function start() {
  const status = document.querySelector("#site-status");
  try {
    const response = await fetch("site-data.json", { credentials: "same-origin" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const data = await response.json();
    if (data.schema_version !== 1 || !Array.isArray(data.releases)) {
      throw new Error("Unsupported site inventory");
    }

    document.querySelector("#project-description").textContent = data.project.description;
    const destinations = document.querySelector("#destination-grid");
    for (const item of data.project.links) {
      destinations.append(linkCard(item.title, item.description, item.url));
    }
    destinations.append(
      linkCard(
        "Verified E2E",
        "Browse packaged-Minecraft screenshots across every supported version, including paired optional-mod compatibility evidence.",
        data.gallery_url
      )
    );

    const releases = document.querySelector("#release-grid");
    for (const release of data.releases) releases.append(releaseCard(release));
    const captures = data.releases.reduce((total, release) => total + release.frame_count, 0);
    status.textContent = `${data.releases.length} versions · ${captures} validated captures · provenance linked to GitHub Actions`;
  } catch (error) {
    status.dataset.error = "true";
    status.textContent = `The verified inventory could not be loaded: ${error.message}`;
  }
}

start();
