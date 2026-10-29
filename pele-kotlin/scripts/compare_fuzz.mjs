// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function compare(upstream, kotlin) {
  const lines = (text) => text === '' ? [] : text.replace(/\r?\n$/, '').split(/\r?\n/);
  const expected = lines(upstream);
  const actual = lines(kotlin);
  const report = [];
  let same = 0;
  let differences = 0;
  for (let i = 0; i < Math.max(expected.length, actual.length); i++) {
    if (expected[i] !== undefined && expected[i] === actual[i] && expected[i] !== '') same++;
    else {
      differences++;
      report.push(`DIFF #${i}\n  up: ${expected[i] ?? '<missing>'}\n  kt: ${actual[i] ?? '<missing>'}`);
    }
  }
  if (expected.length === 0 && actual.length === 0) {
    differences++;
    report.push('DIFF: no records were compared');
  }
  const summary = `${same} same, ${differences} DIFF (${expected.length} upstream, ${actual.length} Kotlin records)`;
  return { differences, summary, report: [...report, summary].join('\n') + '\n' };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [upstream, kotlin, destination] = process.argv.slice(2);
  const result = compare(readFileSync(upstream, 'utf8'), readFileSync(kotlin, 'utf8'));
  writeFileSync(destination, result.report);
  console.log(result.summary);
  if (result.differences) {
    console.error(result.report.split('\n').slice(0, 40).join('\n'));
    process.exitCode = 1;
  }
}
