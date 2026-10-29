// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
//
// Renders every test input in the upstream repo with the upstream bundle and writes the results
// to pele-kotlin/testData/golden/<type>.jsonl.gz, one JSON record per line.
//
//   node pele-kotlin/scripts/generate_goldens.mjs [--sha256-only] [--bundle=path] [--type=pie]
//
// Run `npm ci && npm run build` at the repo root first to create dist/pele.global.js.
process.env.TZ = 'UTC';

import { createHash } from 'node:crypto';
import { stressInputs } from './stress_inputs.mjs';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { gzipSync } from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const kotlinRoot = resolve(here, '..');
const repoRoot = resolve(kotlinRoot, '..');

const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const value = (name) => args.find((a) => a.startsWith(`--${name}=`))?.slice(name.length + 3);

const shaOnly = flag('sha256-only');
const onlyType = value('type');
const bundlePath = value('bundle') ?? process.env.PELE_GLOBAL_JS ?? join(repoRoot, 'dist/pele.global.js');
const NOW = 1700000000000;
const MAX_LITERAL = 20000;

export const TYPES = [
  ['flowchart', ['flowchart'], /flowchart|graph/],
  ['sequence', ['sequence'], /sequenceDiagram/],
  ['class', ['class'], /classDiagram/],
  ['state', ['state'], /stateDiagram/],
  ['er', ['er'], /erDiagram/],
  ['pie', ['pie'], /pie/],
  ['gantt', ['gantt'], /gantt/],
  ['gitGraph', ['git'], /gitGraph/],
  ['mindmap', ['mindmap'], /mindmap/],
  ['timeline', ['timeline'], /timeline/],
  ['journey', ['journey'], /journey/],
  ['xychart', ['xychart'], /xychart/],
  ['quadrantChart', ['quadrant'], /quadrantChart/],
  ['requirement', ['requirement'], /requirement/],
  ['c4', ['c4'], /C4(?:Context|Container|Component|Dynamic|Deployment)/],
  ['sankey', ['sankey'], /sankey/],
  ['block', ['block'], /block/],
  ['packet', ['packet'], /packet/],
  ['kanban', ['kanban'], /kanban/],
  ['architecture', ['architecture'], /architecture/],
  ['radar', ['radar'], /radar-beta/],
  ['treemap', ['treemap'], /treemap/],
  ['info', ['info'], /info/],
  ['agentflow', ['agentflow'], /agentflow-beta/],
  ['usecase', ['usecase', 'usecase-parser'], /usecase-beta/],
  ['railroad', ['railroad'], /railroad-/i],
  ['treeView', ['treeview'], /treeView-beta/],
  ['venn', ['venn'], /venn-beta/],
  ['cynefin', ['cynefin'], /cynefin-beta/],
  ['wardley', ['wardley'], /wardley-beta/i],
  ['swimlane', ['swimlane'], /swimlane-beta/],
  ['ishikawa', ['ishikawa'], /ishikawa/i],
  ['eventmodeling', ['eventmodeling'], /eventmodeling/],
];

// Every global the bundle touches. A missing one silently changes behaviour (URL → about:blank).
export function loadBundle(path) {
  const code = readFileSync(path, 'utf8');
  const ctx = {
    Date, Math, Object, Array, Map, Set, WeakMap, WeakSet, RegExp, Number, String, Boolean, Symbol, Error,
    TypeError, RangeError, SyntaxError, URIError, JSON, URL, URLSearchParams, TextEncoder, TextDecoder,
    parseInt, parseFloat, isNaN, isFinite, encodeURI, encodeURIComponent, decodeURI, decodeURIComponent,
    Uint8Array, Uint16Array, Uint32Array, Int8Array, Int16Array, Int32Array, Float32Array, Float64Array,
    console,
  };
  ctx.globalThis = ctx;
  vm.runInNewContext(code, ctx, { filename: path });
  return { pele: ctx.pele, banner: code.slice(0, 200) };
}

function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path, out);
    else out.push(path);
  }
  return out;
}

// Pulls string literals out of JS/TS source. Not a full tokenizer: a regex literal containing a
// quote can throw it off, which only adds or loses a few candidate inputs.
export function stringLiterals(text) {
  const out = [];
  let i = 0;
  const n = text.length;
  while (i < n) {
    const c = text[i];
    if (c === '/' && text[i + 1] === '/') {
      const e = text.indexOf('\n', i);
      i = e === -1 ? n : e;
    } else if (c === '/' && text[i + 1] === '*') {
      const e = text.indexOf('*/', i + 2);
      i = e === -1 ? n : e + 2;
    } else if (c === '"' || c === "'" || c === '`') {
      let j = i + 1;
      let interpolated = false;
      while (j < n && text[j] !== c) {
        if (text[j] === '\\') j++;
        else if (c !== '`' && text[j] === '\n') break;
        else if (c === '`' && text[j] === '$' && text[j + 1] === '{') interpolated = true;
        j++;
      }
      if (j < n && text[j] === c && !interpolated && j - i < MAX_LITERAL) {
        try {
          const v = new Function(`return ${text.slice(i, j + 1)};`)();
          if (typeof v === 'string') out.push(v);
        } catch {}
      }
      i = j + 1;
    } else {
      i++;
    }
  }
  return out;
}

function inputsFor(type, dirs, keyword, seeds) {
  const list = [];
  const add = (src, origin, strict) => list.push({ src, origin, strict });
  for (const dir of dirs) {
    const docs = join(repoRoot, 'tests/corpus', `${dir}-docs.json`);
    if (existsSync(docs)) for (const s of JSON.parse(readFileSync(docs, 'utf8'))) add(s, `corpus/${dir}-docs`, true);
  }
  if (type === 'agentflow') {
    const ref = JSON.parse(readFileSync(join(repoRoot, 'tests/corpus/agentflow-reference.json'), 'utf8'));
    for (const e of ref) add(e.src, 'corpus/agentflow-reference', true);
  }
  const files = [];
  for (const dir of dirs) {
    files.push(...walk(join(repoRoot, 'tests/compat', dir)).filter((f) => /\.(spec|test)\.(js|ts)$|\.mmd$/.test(f)));
    files.push(...walk(join(repoRoot, 'tests', dir)).filter((f) => /\.(js|ts|mmd)$/.test(f)));
    for (const suffix of ['', '-security']) {
      const f = join(repoRoot, 'tests/render', `${dir}${suffix}.test.ts`);
      if (existsSync(f)) files.push(f);
    }
  }
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    const origin = relative(repoRoot, f);
    if (f.endsWith('.mmd')) add(text, origin, true);
    else for (const s of stringLiterals(text)) if (keyword.test(s)) add(s, origin, false);
  }
  for (const s of seeds.get(type) ?? []) add(s.src, s.origin, true);
  return list;
}

function linksOf(res) {
  return res.links.map((l) => ({ id: l.id, href: l.href, internal: l.internal }));
}

function main() {
  if (!existsSync(bundlePath)) {
    console.error(`Bundle not found: ${bundlePath}\nRun \`npm ci && npm run build\` at the repo root.`);
    process.exit(1);
  }
  const { pele, banner } = loadBundle(bundlePath);
  const pkg = JSON.parse(readFileSync(join(repoRoot, 'package.json'), 'utf8')).version;
  const bannerVersion = /^\/\*! Pele (\S+) \|/.exec(banner)?.[1];
  const peleKt = join(kotlinRoot, 'src/main/kotlin/pele/Pele.kt');
  let ktVersion = pkg;
  let ktCommit;
  if (existsSync(peleKt)) {
    const kt = readFileSync(peleKt, 'utf8');
    ktVersion = /PELE_VERSION\s*=\s*"([^"]+)"/.exec(kt)?.[1];
    ktCommit = /PELE_UPSTREAM_COMMIT\s*=\s*"([^"]+)"/.exec(kt)?.[1];
  }
  if (bannerVersion !== pkg || ktVersion !== pkg) {
    console.error(`Version mismatch: bundle ${bannerVersion}, package.json ${pkg}, Pele.kt ${ktVersion}`);
    process.exit(1);
  }
  if (ktCommit) {
    try {
      execFileSync('git', ['diff', '--quiet', ktCommit, '--', 'src'], { cwd: repoRoot });
    } catch {
      console.warn(`warning: src/ differs from the ported commit ${ktCommit}; the goldens may not match the port.`);
    }
  }

  const seeds = new Map();
  const seedDir = join(kotlinRoot, 'testData/seeds');
  for (const f of walk(seedDir).filter((f) => f.endsWith('.mmd'))) {
    const src = readFileSync(f, 'utf8');
    let type = null;
    try {
      type = pele.detectType(src);
    } catch {}
    if (type === null) continue;
    if (!seeds.has(type)) seeds.set(type, []);
    seeds.get(type).push({ src, origin: relative(repoRoot, f) });
  }

  const outDir = join(kotlinRoot, 'testData/golden');
  mkdirSync(outDir, { recursive: true });
  let total = 0;
  let errors = 0;
  const crashes = [];
  for (const [type, dirs, keyword] of TYPES) {
    if (onlyType && onlyType !== type) continue;
    const seen = new Set();
    const records = [];
    let valid = 0;
    const run = (src, options) => {
      const key = JSON.stringify([src, options]);
      if (seen.has(key)) return null;
      seen.add(key);
      try {
        const res = pele.render(src, { now: NOW, ...options });
        if (res.type !== type) return null;
        const rec = {
          src, options, ok: true, type: res.type, mermaidType: res.mermaidType, width: res.width, height: res.height,
          links: linksOf(res), sha256: sha256(res.svg),
        };
        if (!shaOnly) rec.svg = res.svg;
        records.push(rec);
        return res;
      } catch (e) {
        if (e && e.name === 'PeleError') {
          records.push({ src, options, ok: false, errorCode: e.code, errorMessage: e.message });
          return null;
        }
        crashes.push({ type, src, options, error: String(e && e.stack ? e.stack.split('\n').slice(0, 3).join(' | ') : e) });
        return null;
      }
    };
    for (const { src, strict } of inputsFor(type, dirs, keyword, seeds)) {
      let detected;
      try {
        detected = pele.detectType(src);
      } catch {
        continue;
      }
      if (detected !== type && !(detected === null && strict)) continue;
      const res = run(src, {});
      if (res === null) continue;
      valid++;
      if (valid % 5 === 0) run(src, { maxWidth: 400 });
      if (res.links.length > 0 || res.svg.includes('<image') || res.svg.includes('href')) {
        run(src, { links: false, images: false });
      }
    }
    for (const { src, options } of stressInputs(type)) run(src, options);
    const body = records.map((r) => JSON.stringify(r)).join('\n') + '\n';
    writeFileSync(join(outDir, `${type}.jsonl.gz`), gzipSync(body, { level: 9 }));
    const bad = records.filter((r) => !r.ok).length;
    total += records.length;
    errors += bad;
    console.log(`${type.padEnd(14)} ${String(records.length).padStart(5)} records (${bad} errors)`);
  }
  console.log(`total          ${String(total).padStart(5)} records (${errors} errors)`);
  if (crashes.length > 0) {
    console.warn(`\n${crashes.length} inputs threw something other than PeleError (not recorded):`);
    for (const c of crashes) console.warn(`  [${c.type}] ${JSON.stringify(c.src).slice(0, 120)} ${JSON.stringify(c.options)}: ${c.error}`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
