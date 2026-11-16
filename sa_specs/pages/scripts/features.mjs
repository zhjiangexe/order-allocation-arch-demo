// Parse the business-readable Gherkin subset used by this documentation snapshot.
// Reject unsupported constructs so future feature changes cannot silently lose steps.
export function parseFeature(text, file) {
  const feature = { file, title: '', description: [], tags: [], background: [], scenarios: [] };
  let current, lastStep, caseId, pendingTags = [];
  for (const [index, raw] of text.split(/\r?\n/).entries()) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#')) { const match = line.match(/^# case-id:\s*(\S+)/); if (match) caseId = match[1]; continue; }
    if (line.startsWith('@')) { pendingTags.push(...line.split(/\s+/)); continue; }
    if (line.startsWith('Feature:')) { feature.title = line.slice(8).trim(); feature.tags = pendingTags; pendingTags = []; continue; }
    if (line.startsWith('Background:')) { current = feature.background; lastStep = undefined; continue; }
    if (line.startsWith('Scenario:')) {
      if (!caseId) throw Error(`${file}:${index + 1}: Scenario requires a case-id`);
      const scenario = { id: caseId, title: line.slice(9).trim(), tags: pendingTags, steps: [] };
      feature.scenarios.push(scenario); current = scenario.steps; caseId = undefined; pendingTags = []; lastStep = undefined; continue;
    }
    const step = line.match(/^(Given|When|Then|And|But)\s+(.+)$/);
    if (step && current) { lastStep = { keyword: step[1], text: step[2], rows: [] }; current.push(lastStep); continue; }
    if (line.startsWith('|') && lastStep) { lastStep.rows.push(line); continue; }
    if (/^(Scenario Outline:|Examples:|Rule:|"""|```)/.test(line) || current || !feature.title) throw Error(`${file}:${index + 1}: Unsupported Gherkin content: ${line}`);
    feature.description.push(line);
  }
  if (!feature.title || !feature.scenarios.length) throw Error(`${file}: Missing feature or scenarios`);
  return feature;
}
function stepsMarkdown(steps) {
  return steps.map(step => {
    let result = `**${step.keyword}** ${step.text}\n\n`;
    if (step.rows.length) {
      const columns = step.rows[0].split('|').length - 2;
      result += [step.rows[0], `| ${Array(columns).fill('---').join(' | ')} |`, ...step.rows.slice(1)].join('\n') + '\n\n';
    }
    return result;
  }).join('');
}
export function featureMarkdown(feature, chapter) {
  let result = feature.description.join('\n') + '\n\n';
  if (feature.tags.length) result += `標籤：${feature.tags.map(tag => `\`${tag}\``).join(' ')}\n\n`;
  if (feature.background.length) result += '**共通前提（Background）**\n\n' + stepsMarkdown(feature.background);
  for (const [index, scenario] of feature.scenarios.entries()) {
    result += `<a id="${scenario.id}"></a>\n\n### ${chapter}-${index + 1} ${scenario.title}\n\n案例：\`${scenario.id}\`\n\n`;
    if (scenario.tags.length) result += `標籤：${scenario.tags.map(tag => `\`${tag}\``).join(' ')}\n\n`;
    result += stepsMarkdown(scenario.steps);
  }
  return result;
}
