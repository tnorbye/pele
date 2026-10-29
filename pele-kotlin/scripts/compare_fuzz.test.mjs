// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { compare } from './compare_fuzz.mjs';

test('compares every record, including missing and empty output', () => {
  assert.equal(compare('OK a\nOK b\n', 'OK a\nOK b\n').differences, 0);
  assert.equal(compare('OK a\nOK b\n', 'OK a\nERR b\n').differences, 1);
  assert.equal(compare('OK a\nOK b\n', 'OK a\n').differences, 1);
  assert.equal(compare('OK a\n', 'OK a\nOK b\n').differences, 1);
  assert.equal(compare('', '').differences, 1);
  assert.equal(compare('\n', '\n').differences, 1);
});
