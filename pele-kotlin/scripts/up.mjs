// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
//
// Renders each input with the upstream bundle: one line per input.
//   node up.mjs inputs.txt
process.env.TZ = 'UTC';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadBundle } from './generate_goldens.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const { pele } = loadBundle(process.env.PELE_GLOBAL_JS ?? join(root, 'dist/pele.global.js'));
const inputs = readFileSync(process.argv[2], 'utf8').split('\n=====\n');
const lines = [];
for (const src of inputs) {
  try {
    const r = pele.render(src, { now: 1700000000000 });
    const sha = createHash('sha256').update(r.svg, 'utf8').digest('hex').slice(0, 16);
    lines.push(`OK ${r.type} ${r.width}x${r.height} ${sha}`);
  } catch (e) {
    if (e && e.name === 'PeleError') lines.push(`ERR ${e.code} ${JSON.stringify(e.message)}`);
    else lines.push(`UPSTREAM-CRASH ${JSON.stringify(String(e))}`);
  }
}
process.stdout.write(lines.join('\n') + '\n');
