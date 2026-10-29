// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
// Generated inputs cannot be recovered by extracting string literals from upstream tests.
export function stressInputs(type) {
  const inputs = [];
  for (const size of [1000, 10000, 40000]) {
    const text = 'a'.repeat(size);
    if (type === 'railroad') inputs.push({ src: `railroad-ebnf-beta\nr = "${text}";`, options: {} });
    if (type === 'pie') {
      inputs.push({ src: `pie\n"${text}": 1`, options: {} });
      inputs.push({ src: `%%{init: {"theme":"${text}"}}%%\npie\n"A": 1`, options: {} });
    }
  }
  if (type === 'journey') {
    for (const count of [500, 1000, 3000]) {
      const src = 'journey\nsection A\n' + 'Task: 5: A\n'.repeat(count);
      inputs.push({ src, options: {} }, { src, options: { maxWidth: 320 } });
    }
  }
  return inputs;
}
