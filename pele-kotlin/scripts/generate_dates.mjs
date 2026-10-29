// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
//
// Writes testData/dates/dates.json.gz: Date.parse results of many strings, `new Date(y, m, ...)` results
// and local-time fields of many instants, as V8 gives them under TZ=UTC, America/Los_Angeles and Asia/Kolkata.
//
//   node pele-kotlin/scripts/generate_dates.mjs
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const ZONES = ['UTC', 'America/Los_Angeles', 'Asia/Kolkata'];

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function inputs() {
  const fixed = [
    '', ' ', '0', '1', '12', '99', '100', '2000', '202304', '2024', '2024-01', '2024-01-02', '2024-1-2', '2024-13-01',
    '2024-02-30', '2024-02-29', '2023-02-29', '2024-01-02T03:04', '2024-01-02T03:04:05', '2024-01-02T03:04:05.6',
    '2024-01-02T03:04:05.678', '2024-01-02T03:04:05.6789012345', '2024-01-02T03:04:05Z', '2024-01-02T03:04:05+05:30',
    '2024-01-02T03:04:05-0800', '2024-01-02T24:00', '2024-01-02T24:00:00.000', '2024-01-02T24:01', '2024-01-02T25:00',
    '2024-01-02 03:04', '2024-01-02 03:04:05', '2024-01-02t03:04', '2024-01-02T3:04', '+002024-01-02', '-000001-01-01',
    '-000000-01-01', '+275760-09-13T00:00:00Z', '+275760-09-13T00:00:00.001Z', '-271821-04-20T00:00:00Z',
    '-271821-04-19T23:59:59Z', '+999999-01-01', '2024-03-10T02:30', '2024-03-10 02:30', '2024-11-03T01:30',
    'March 4', 'March 4 2024', 'March 4, 2024', 'Mar 4 2024 10:30', '4 Mar 2024', '4 March 2024 10:30 PM',
    'Mon Mar 04 2024', 'Monday, March 4, 2024 10:30:00 AM', 'Mon, 04 Mar 2024 10:30:00 GMT',
    'Mon Mar 04 2024 10:30:00 GMT+0100 (CET)', 'Mon Mar 04 2024 10:30:00 GMT-08:00 (Pacific Standard Time)',
    '03-02-2025', '3/2/2025', '03/02/25', '2-10-01', '12/31/1999 11:59 PM', '12/31/1999 12:00 AM', '12/31/1999 0:00 AM',
    '1/1/2000 13:00 PM', '2024/01/02', '2024.01.02', '2024 Jan 2', 'Jan 2 2024 EST', 'Jan 2 2024 10:00 PST',
    'Jan 2 2024 10:00 UTC', 'Jan 2 2024 10:00 Z', 'Jan 2 2024 10:00 +0530', 'Jan 2 2024 10:00 -05:30',
    'Jan 2 2024 10:00 GMT+5', 'Jan 2 2024 10:00 GMT+12345', 'Jan 2 2024 10:00 UTC+999999999:', 'Jan 2 2024 10:00 UT+1:',
    '12345', 'foo 2024', 'foo2024', 'foo 2024-01-02', '2024 foo', 'foo', 'Jan', 'January', 'Janx 5', 'Septembre 5 2020',
    '(comment) 2024-01-02', '2024-01-02 (comment)', '2024-01-02 (nested (comment))', '(unclosed', 'Jan 2 2024 )',
    '2024-01-02 )', ')2024-01-02', '+2024', '-2024', '2024-', '2024--01', '2024-01-', '10:30', '10:30:45', '10:30:45.5',
    '10:30::', '2024 10:30', '1 2 3', '1 2 3 4', '31 12 99', '0 0 0', '00 00 00', '1.5', '1.5.2024', '5.5.5 5:5:5.5',
    '2024-01-02T03:04:05.', '2024-01-02T03:', '2024-01-02T', '2024-01-02Tfoo', '2024-01-02Z', '2024-01-02T03:04Zx',
    '2024-01-02 03:04', '2024-01-02 03:04', 'Jan 2 2024', '﻿2024-01-02', ' 2024-01-02',
    '2024-01-02\u0000', '2024\u000001-02', 'Jan 2 2024 \u0000 10:00', '1970-01-01', '1969-12-31T23:59:59.999Z',
    '0001-01-01', '0000-01-01', '9999-12-31T23:59:59.999Z', '10000-01-01', '1000000000', '99999999999', 'Jan 1 275760',
    'Sep 13 275760 00:00 UTC', 'Sep 13 275760', 'Apr 20 -271821', 'Jan 1 -1', 'Jan 1 0', 'Jan 1 49', 'Jan 1 50', 'Jan 1 99',
    'Jan 1 100', 'Jan 32', 'Feb 30 2024', '32 Jan', 'Jan 0', '0 Jan', 'Jan 1 2024 24:00', 'Jan 1 2024 24:01',
    'Jan 1 2024 23:59:59.999', 'Jan 1 2024 23:59:60', 'Jan 1 2024 1:60', 'Jan 1 2024 12:00 am', 'Jan 1 2024 12:00 pm',
    'Jan 1 2024 13:00 am', 'Jan 1 2024 0:30 pm', 'Jan 1 2024 am', '2024-01-02T03:04:05.123456789012', 'Jan 1 2024 1:2:3.4',
    'Jan 1 2024 1:2:3.', 'Jan 1 2024 1:2:3.x', 'Jan 1 2024 1:2:3:4', 'Jan 1 2024 1:2:3:4:5', 'Jan 1 2024 1::',
    'Jan 1 2024 1::2', '2024-01-02-03', '2024-01-02 -05:00', '2024-01-02 +05:00', 'Jan 1 2024 10:00-0500',
    'Jan 1 2024 10:00+05', 'Jan 1 2024 10:00+', 'Jan 1 2024 10:00-', 'Jan 1 2024 10:00 +-5', 'Jan 1 2024 UTC-5',
    'Jan 1 2024 GMT-0800', 'Jan 1 2024 GMT+08:00', 'Jan 1 2024 GMT+8:30', 'Jan 1 2024 GMT+830', 'Jan 1 2024 GMT+8301',
    'Jan 1 2024 GMT+08:30:15', 'Jan 1 2024 EDT', 'Jan 1 2024 CDT', 'Jan 1 2024 MST', 'Jan 1 2024 PDT', 'Jan 1 2024 CST',
    'Jan 1 2024 10:00 foo', 'Jan 1 2024 foo 10:00', 'Jan 1 2024 T 10:00', 'Jan 1 2024T10:00', 'T10:00', 'Z', 'UTC',
    '1-1-1', '1/1/1', '1/1/1 1:1', '1-1-1 1:1', '13-1-1', '1-13-1', '1-1-13', '0-0-0', '99-99-99', '2024-1', '24-1-2',
    '2024-01-02 3', '2024-01-02 3 4', '2024-01-02 03', '2024 01 02', '2024 1 2 3', 'Sunday', 'Sun 1', 'Wed Jan 3',
    '1 Jan', '1 Jan 2024 foo', '1Jan2024', '1-Jan-2024', 'Jan-1-2024', 'Jan-1', '1-Jan', 'Jan/1/2024', 'Jan.1.2024',
    '2024-Jan-01', '2024/Jan/01', 'jan 1 2024', 'JAN 1 2024', 'jAn 1 2024', 'Mayday 1 2024', 'Marchx 1 2024',
    'Thursday, 04-Jan-24 10:00:00 GMT', 'Thu, 4 Jan 2024 10:00:00 +0000', '2024-01-02T03:04:05.678+05:30',
    '2024-01-02T03:04:05.678-00:00', '2024-01-02T03:04+0530', '2024-01-02T03:04+05', '2024-01-02T03:04+5:30',
    '2024-01-02T03:04+24:00', '2024-01-02T03:04+23:59', '2024-01-02T03:04+23:60', '2024-01-02T03:04:60',
    '2024-01-02T03:60', '2024-01-02T03:04:05.1234', '2024-01-02T03:04:05,5', '2024-01-02T03.04', '1e3', '0x10',
    '0.5', '.5', '-.5', '- 5', '+ 5', '++5', '5-', '5+', '5 -', 'Jan 5 -', 'Jan 5 +', 'Jan 5 10:00 + 5', 'Jan 5 10:00 +5 5',
    '12:00 Jan 1 2024', '12:00 PM Jan 1 2024', 'PM 12:00 Jan 1 2024', '2024-01-01T00:00:00.000Z ', ' 2024-01-01T00:00:00.000Z',
    '2024-01-01 00:00:00 UTC', '2024-01-01 00:00:00 Z', '2024-01-01 00:00:00Z', '2024-01-01 00:00:00 GMT+1',
    '2024-01-01T00:00:00 GMT', '2024-01-01 PM', '2024-01-01 12 PM', 'Jan 1 2024 12 PM', 'Jan 1 2024 12:00:00 PM',
    '2023-03-12 02:30', '2023-03-12T02:30:00', 'Mar 12 2023 02:30', 'Nov 5 2023 01:30', 'Mar 31 1996 02:30', 'Oct 27 1996 01:30',
    'Jan 1 1883 12:00', 'Nov 18 1883 12:00', 'Jan 1 1900', 'Jan 1 1800', 'Jan 1 1500', 'Jan 1 3000', 'Jul 1 2100',
    'Jan 1 2038', 'Jul 1 2500 12:00', 'Jan 1 10000', 'Jan 1 99999', 'Jan 1 275761', 'Jan 1 -271822',
  ];
  const atoms = [
    '2024', '24', '2', '02', '31', '12', '1999', '0', '00', '5', '99', '1', '10', '30', '45', '001', '20240102', '202401',
    'Jan', 'February', 'mar', 'Dec', 'Sept', 'Mon', 'Tuesday', 'foo', 'x', 'am', 'PM', 'pm', 'AM', 'UTC', 'GMT', 'Z', 'z',
    'EST', 'PDT', 'T', 't', '+0530', '-0800', '+05:30', '-05', '+', '-', ':', '::', '.', ',', '/', ' ', '  ', ' ',
    '\t', '(c)', '(a(b)c)', '(', ')', '10:30', '10:30:45', '10:30:45.123', '12:00', '24:00', '2024-01-02', '2024-01',
    '+002024', '-000001', '.5', ':30', '\u0000', 'é', '中', '_', '[', '@', '#',
  ];
  const r = rng(12345);
  const out = [...fixed];
  const seen = new Set(out);
  while (out.length < 4500) {
    const n = 1 + Math.floor(r() * 7);
    let s = '';
    for (let i = 0; i < n; i++) {
      s += atoms[Math.floor(r() * atoms.length)];
      if (r() < 0.6) s += ' ';
    }
    if (!seen.has(s)) {
      seen.add(s);
      out.push(s);
    }
  }
  // Strings made of one valid date with a perturbation.
  const bases = ['2024-03-10T02:30:00', 'Mar 10 2024 02:30', '2024-11-03T01:30', '1883-11-18T12:00', '2038-01-19T03:14:07'];
  for (const b of bases) {
    for (let i = 0; i <= b.length; i++) {
      for (const ch of ['', ' ', ':', '-', 'Z', '0', 'a', '.']) {
        const s = b.slice(0, i) + ch + b.slice(i + (ch === '' ? 1 : 0));
        if (!seen.has(s)) {
          seen.add(s);
          out.push(s);
        }
      }
    }
  }
  // Every character of Latin-1 and the white space and separator ranges, before and inside a date.
  const chars = [];
  for (let c = 1; c < 256; c++) chars.push(c);
  for (let c = 0x1680; c <= 0x1681; c++) chars.push(c);
  for (let c = 0x2000; c <= 0x206f; c++) chars.push(c);
  chars.push(0x3000, 0xfeff, 0x180e, 0x4e2d, 0xff0d, 0xff1a);
  for (const c of chars) {
    const ch = String.fromCharCode(c);
    for (const s of [ch + '2024-01-02', 'Jan' + ch + '2 2024', 'Jan 2 2024 10' + ch + '30', '2024-01-02' + ch, '2024-01-02T10:30' + ch]) {
      if (!seen.has(s)) {
        seen.add(s);
        out.push(s);
      }
    }
  }
  return out;
}

function ctorCases() {
  const r = rng(777);
  const cases = [];
  const years = [-1, 0, 1, 49, 50, 99, 100, 1582, 1883, 1900, 1970, 1996, 2000, 2023, 2024, 2038, 2100, 2500, 9999, 10000, 275760, 275761, -271821];
  for (let i = 0; i < 1200; i++) {
    const y = r() < 0.7 ? years[Math.floor(r() * years.length)] : Math.floor(r() * 4000) - 500;
    const pick = (n, extra) => (r() < 0.8 ? Math.floor(r() * n) : extra[Math.floor(r() * extra.length)]);
    cases.push([y, pick(12, [-1, 12, 13, 25, -13]), pick(31, [0, -1, 31, 32, 60, -40]) + 1, pick(24, [24, 25, -1, 48]), pick(60, [60, -1]), pick(60, [60, -1]), pick(1000, [1000, -1, 5000])]);
  }
  // DST edges in the common zones.
  for (const [y, m, d] of [[2024, 2, 10], [2024, 10, 3], [2023, 2, 12], [2023, 10, 5], [1996, 2, 31], [1996, 9, 27], [2024, 2, 31], [2024, 9, 27]]) {
    for (let h = 0; h < 5; h++) for (const mi of [0, 30, 59]) cases.push([y, m, d, h, mi, 0, 0]);
  }
  return cases;
}

function instants() {
  const r = rng(4242);
  const out = [0, -1, 1, 8.64e15, -8.64e15, 1700000000000, 1710054000000, 1710057600000, 1730620800000, 1730624400000, -2717640000000, -2717650000000];
  while (out.length < 1500) {
    const k = r();
    out.push(k < 0.5 ? Math.floor((r() - 0.3) * 4e12) : Math.floor((r() * 2 - 1) * 8.64e15));
  }
  return out;
}

function worker() {
  const strings = inputs();
  const parse = strings.map((s) => Date.parse(s));
  const ctor = ctorCases().map((c) => new Date(c[0], c[1], c[2], c[3], c[4], c[5], c[6]).getTime());
  const fields = instants().map((t) => {
    const d = new Date(t);
    return [d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds(), d.getMilliseconds(), d.getDay(), d.getTimezoneOffset()];
  });
  process.stdout.write(JSON.stringify({ parse, ctor, fields }, (k, v) => (typeof v === 'number' && !Number.isFinite(v) ? 'NaN' : v)));
}

if (process.argv.includes('--worker')) {
  worker();
} else {
  const here = dirname(fileURLToPath(import.meta.url));
  const out = { strings: inputs(), ctor: ctorCases(), instants: instants(), zones: {} };
  for (const tz of ZONES) {
    const res = spawnSync(process.execPath, [fileURLToPath(import.meta.url), '--worker'], { env: { ...process.env, TZ: tz }, maxBuffer: 1 << 28 });
    if (res.status !== 0) throw new Error(res.stderr.toString());
    out.zones[tz] = JSON.parse(res.stdout.toString());
  }
  const dir = resolve(here, '../testData/dates');
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'dates.json.gz'), gzipSync(JSON.stringify(out)));
  console.log(`${out.strings.length} strings, ${out.ctor.length} constructor cases, ${out.instants.length} instants`);
}
