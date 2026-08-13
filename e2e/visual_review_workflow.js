// Reusable AI visual-review workflow for Quick Skin E2E screenshots.
//
// Run with:  Workflow({ scriptPath: "<repo>/e2e/visual_review_workflow.js", args: <manifest> })
// where <manifest> is the JSON array produced by `python3 e2e/visual_review.py --all` in paired
// `--reference-evidence-root/--reference-branch/--reference-artifact-node` mode
// (items: {path, reference_path, reference_label, label, capture_id, kind, expectation,
// runtime_evidence}).
// Expectations come from the canonical scenario-contract.json and are passed through per item —
// this script does not duplicate them.
//
// One vision agent per screenshot pair opens the candidate and its authenticated lossless 1.20.1
// reference
// and checks both against the expectation; any frame it flags (mismatch or anomaly) is re-examined
// by an independent skeptic before it is reported, to suppress false positives. Returns the same
// exact verdict array accepted by
// check_visual_review.py:
// {label, semantic_valid, matches_reference, visible, anomalies, defect} for every input frame.

export const meta = {
  name: 'e2e-visual-review',
  description: 'AI vision pass over E2E screenshot pairs: compare every candidate with its 1.20.1 anchor and adversarially re-check anomalies',
  phases: [
    { title: 'Review', detail: 'one vision agent per candidate/reference pair' },
    { title: 'Verify', detail: 'skeptical re-check of any flagged frame' },
  ],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    visible: { type: 'string', description: 'What you actually see in the image, 1-2 sentences.' },
    semanticValid: { type: 'boolean', description: 'Does the candidate satisfy the expectation on its own?' },
    matchesReference: { type: 'boolean', description: 'Does the candidate semantically match the 1.20.1 reference?' },
    anomalies: { type: 'array', items: { type: 'string' }, description: 'Real visual problems (wrong/garbled texture, cape clipping through elytra, transparency or background-compositing artifacts, missing element, black/empty frame). Empty if none.' },
    confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
  },
  required: ['visible', 'semanticValid', 'matchesReference', 'anomalies', 'confidence'],
}

const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    semanticValid: { type: 'boolean', description: 'Does the candidate satisfy the expectation on its own?' },
    matchesReference: { type: 'boolean', description: 'Does it semantically match the 1.20.1 reference?' },
    note: { type: 'string' },
  },
  required: ['semanticValid', 'matchesReference', 'note'],
}

let items = args
if (typeof items === 'string') items = JSON.parse(items)
if (!Array.isArray(items)) throw new Error('args is not an array (got ' + typeof items + ')')
if (!items.every(it => it && typeof it.path === 'string' &&
    typeof it.reference_path === 'string' && typeof it.runtime_evidence === 'string')) {
  throw new Error('every manifest entry must contain candidate/reference paths and runtime evidence')
}
log(`reviewing ${items.length} screenshot pairs`)

const results = await pipeline(
  items,
  (it) => agent(
    `You are visually QA-reviewing one Minecraft E2E screenshot.\n` +
    `Use the Read tool to OPEN and LOOK AT BOTH images at these exact paths:\n` +
    `Candidate: ${it.path}\nReference (${it.reference_label || 'Minecraft 1.20.1'}): ${it.reference_path}\n\n` +
    `It SHOULD show: ${it.expectation || '(describe what is shown)'}\n\n` +
    `Passed deterministic runtime evidence: ${it.runtime_evidence}\n\n` +
    `Report what you actually see, whether the candidate satisfies the expectation independently, ` +
    `whether it semantically matches the reference, and any visual anomalies (garbled/wrong textures, ` +
    `a cape clipping through an elytra, transparency or background-compositing artifacts, missing elements, ` +
    `or a black/empty/crashed frame). Custom panels, outlines, grids, labels, textures and controls ` +
    `must remain as crisp as the reference; only the world behind an overlay may be intentionally blurred. ` +
    `Minor cross-version framing/lighting or Vanilla chrome differences are fine — only flag real rendering problems. Note the camera is usually ` +
    `behind the player (3rd-person back), so a front-only feature (e.g. a face patch) not being visible is NOT a defect.`,
    { label: `review:${it.label}`, phase: 'Review', schema: REVIEW_SCHEMA }
  ).then(v => ({ it, v })),
  (r) => {
    if (!r || !r.v) return r
    const flagged = r.v.semanticValid === false || r.v.matchesReference === false ||
      (r.v.anomalies && r.v.anomalies.length > 0)
    if (!flagged) return r
    return agent(
      `Independently re-examine a Minecraft screenshot a first reviewer flagged.\n` +
      `Use the Read tool to open the candidate ${r.it.path} and reference ${r.it.reference_path}.\n\n` +
      `It SHOULD show: ${r.it.expectation || ''}\n` +
      `Passed deterministic runtime evidence: ${r.it.runtime_evidence}\n` +
      `First reviewer said: semanticValid=${r.v.semanticValid}; ` +
      `matchesReference=${r.v.matchesReference}; anomalies=${JSON.stringify(r.v.anomalies)}.\n\n` +
      `Decide if this is a GENUINE rendering bug or acceptable. Default to NOT a real problem unless the ` +
      `rendering is clearly wrong against the expectation. If useful, inspect the full-resolution pixels of ` +
      `both curated images before deciding.`,
      { label: `verify:${r.it.label}`, phase: 'Verify', schema: VERIFY_SCHEMA }
    ).then(vr => ({ ...r, verify: vr }))
  }
)

const verdicts = []
for (const r of results) {
  if (!r || !r.v) throw new Error(`no review returned for ${r && r.it ? r.it.label : '?'}`)
  const semanticValid = r.verify ? r.verify.semanticValid : r.v.semanticValid
  const matchesReference = r.verify ? r.verify.matchesReference : r.v.matchesReference
  const defect = semanticValid === false || matchesReference === false
  const anomalies = defect ? [...r.v.anomalies] : []
  if (defect && anomalies.length === 0) {
    anomalies.push(r.verify && r.verify.note ? r.verify.note : 'The frame did not match its expectation.')
  }
  verdicts.push({
    label: r.it.label,
    semantic_valid: semanticValid,
    matches_reference: matchesReference,
    visible: r.v.visible,
    anomalies,
    defect,
  })
}

log(`clean: ${verdicts.filter(v => !v.defect).length} | flagged: ${verdicts.filter(v => v.defect).length}`)
return verdicts
