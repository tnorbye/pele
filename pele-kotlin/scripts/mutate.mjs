// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
//
// Writes mutated diagram sources for differential fuzzing, separated by "\n=====\n".
//   node mutate.mjs <seed> <perType> [type,type,...] > inputs.txt
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const golden = join(dirname(fileURLToPath(import.meta.url)), '..', 'testData', 'golden');
const [seedArg = '1', perArg = '200', typesArg] = process.argv.slice(2);
let state = Number(seedArg) >>> 0 || 1;
const rand = () => {
  state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
  return state / 4294967296;
};
const pick = (list) => list[Math.floor(rand() * list.length)];

const SNIPPETS = ['1.5.2', '0x1F', '1e400', '-0', '.5', '5.', 'NaN', 'Infinity', ' ', '﻿', ' ', '\u0085', '\u0131', '\u0130', '\u017f', '\u212a', '\u212b', '\u1e9e', '\u00c9',
  '\r\n', '\t', '😀', '𝔸', '́', '中文', '&amp;', '&#x1F600;', '&lt;b&gt;', '<b>', '<br/>', '"', "'", '`', '\\', '%%',
  ';', ':::', '#', '$', '$&', '$1', '{', '}', '[', ']', '(', ')', '-->', 'javascript:alert(1)', 'https://a.b/c?d=e&f',
  '1e-7', '12345678.5', '999999999999999999999', ' ', '\n', 'A', 'x y', '_', '*', '~', '|', ',', ':', '@', '--', '==>'];
const NUMBERS = ['1.5.2', '0x10', '1e3', '.5', '5.', '-0', '1e-7', '12345678.5', '0', '1e21', '3.14159265358979', '-5', '100000'];

function mutate(src) {
  let s = src;
  const count = 1 + Math.floor(rand() * 3);
  for (let k = 0; k < count; k++) {
    const at = Math.floor(rand() * (s.length + 1));
    const kind = Math.floor(rand() * 4);
    if (kind === 0) {
      s = s.slice(0, at) + pick(SNIPPETS) + s.slice(at);
    } else if (kind === 1) {
      s = s.slice(0, at) + s.slice(at + 1 + Math.floor(rand() * 4));
    } else if (kind === 2) {
      const nums = [...s.matchAll(/\d+(?:\.\d+)?/g)];
      if (nums.length > 0) {
        const m = pick(nums);
        s = s.slice(0, m.index) + pick(NUMBERS) + s.slice(m.index + m[0].length);
      } else {
        s = s.slice(0, at) + pick(NUMBERS) + s.slice(at);
      }
    } else {
      const lines = s.split('\n');
      const i = Math.floor(rand() * lines.length);
      lines.splice(i, 0, lines[i]);
      s = lines.join('\n');
    }
  }
  return s;
}

const wanted = typesArg ? typesArg.split(',') : null;
const out = [];
for (const file of readdirSync(golden).sort()) {
  const type = file.replace(/\.jsonl\.gz$/, '');
  if (wanted && !wanted.includes(type)) continue;
  const sources = [...new Set(gunzipSync(readFileSync(join(golden, file))).toString('utf8').split('\n')
    .filter(Boolean).map((l) => JSON.parse(l)).filter((r) => r.ok).map((r) => r.src))];
  if (sources.length === 0) continue;
  for (let n = 0; n < Number(perArg); n++) out.push(mutate(pick(sources)).replaceAll('\n=====\n', '\n====\n'));
}
process.stdout.write(out.join('\n=====\n'));
