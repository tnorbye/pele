// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
// Writes testData/urls/urls.json.gz: generated http(s) URLs and Node's `new URL(x).href` for each
// (null where it throws), for UrlTest.
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

let state = 12345;
const rand = () => ((state = (Math.imul(state, 1664525) + 1013904223) >>> 0) / 4294967296);
const pick = (l) => l[Math.floor(rand() * l.length)];
const schemes = ['http:', 'https:', 'HTTP:', 'hTtPs:'];
const slashes = ['//', '/', '', '///', '\\\\', '/\\'];
const hostAtoms = ['a', 'B', 'example', 'com', '.', '-', '0x7f', '1', '256', '0', '08', '[::1]', '[1:2::3]', '[::ffff:1.2.3.4]', '\u0130', '\u0131', '\u017f', '\u212a', '\u212b', '\u1e9e',
  'ä', 'ß', 'É', '́', '­', '​', ' ', '中', '%41', '%2e', '%zz', ' ', '@', 'u:p@', ':80', ':443', ':', ':0080',
  ':99999', 'xn--', '_', '~', '*', '。', 'Ａ', 'Ⅰ', '﻿', '\t', '<', '^', '|', '%', '\u0000'];
const pathAtoms = ['/', 'a', '.', '..', '%2e', '%2E%2e', ' ', '"', '<', '>', '`', '{', '}', '^', '|', '\\', '?', '#', 'ä', '😀', '%',
  '%zz', "'", '\ud800', '&', '=', ';', ' ', '\u0085'];
const out = [];
for (let n = 0; n < 6000; n++) {
  let host = '';
  for (let k = 0, c = 1 + Math.floor(rand() * 4); k < c; k++) host += pick(hostAtoms);
  let rest = '';
  for (let k = 0, c = Math.floor(rand() * 6); k < c; k++) rest += pick(pathAtoms);
  const url = pick(schemes) + pick(slashes) + host + rest;
  let href = null;
  try { href = new URL(url).href; } catch {}
  out.push([url, href]);
}
const file = join(dirname(fileURLToPath(import.meta.url)), '..', 'testData', 'urls', 'urls.json.gz');
writeFileSync(file, gzipSync(JSON.stringify(out), { level: 9 }));
console.log(out.length, 'urls,', out.filter((r) => r[1] !== null).length, 'valid');
