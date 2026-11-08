// M6 contract test for the console's lossless variant editing (audit batches
// 1-3, probe name: editTitleOmitsUnchangedVariants).
//
// Extracts the PURE LOGIC block verbatim from src/main/resources/static/
// index.html, evaluates it, and asserts computeVariantsField's contract:
// unchanged variants (incl. routing rules) -> undefined (PATCH omits the
// field, backend treats absent as untouched); any real change -> the full
// edited set with every untouched variant's matchRule preserved.
//
// Run: node scripts/ui-contract-test.mjs   (exit 0 = pass, non-zero = fail)
import assert from 'node:assert';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(repoRoot, 'src/main/resources/static/index.html'), 'utf8');

const BEGIN = '// == PURE LOGIC BEGIN ==';
const END = '// == PURE LOGIC END ==';
const beginIdx = html.indexOf(BEGIN);
const endIdx = html.indexOf(END);
assert.notStrictEqual(beginIdx, -1, 'PURE LOGIC BEGIN marker missing from index.html');
assert.notStrictEqual(endIdx, -1, 'PURE LOGIC END marker missing from index.html');
assert.ok(beginIdx < endIdx, 'PURE LOGIC markers out of order');
const block = html.slice(beginIdx + BEGIN.length, endIdx);

const { computeVariantsField } = new Function(
  block + '\nreturn { computeVariantsField };'
)();
assert.strictEqual(typeof computeVariantsField, 'function', 'computeVariantsField not defined by the extracted block');

// Detail API shape: matchRule is a JSON string (ShortLinkDetail.VariantView),
// weight a number. The edit form hands back weight as a string.
const RULE_A = '{"devices":["mobile"],"os":["ios","android"]}';
const RULE_B = '{"languages":["zh"],"timeWindows":[{"from":"09:00","to":"18:00","tz":"Asia/Shanghai","weekdays":[1,2,3,4,5]}]}';
const original = [
  { key: 'a', url: 'https://a.example.com/', weight: 50, matchRule: RULE_A },
  { key: 'b', url: 'https://b.example.com/', weight: 50, matchRule: RULE_B },
];
const asFormRows = (variants) => variants.map(v => ({
  key: v.key, url: v.url, weight: String(v.weight), matchRule: v.matchRule,
}));

// (1) Identical variants, rules included -> undefined (field omitted).
assert.strictEqual(
  computeVariantsField(original, asFormRows(original)), undefined,
  '(1) identical variants must omit the variants field');

// (2) Title-only save: the form submits exactly what was loaded -> undefined,
//     so the PATCH body carries no "variants" key at all.
{
  const edited = asFormRows(original);
  const field = computeVariantsField(original, edited);
  const patchBody = { title: 'renamed' };
  if (field !== undefined) patchBody.variants = field;
  assert.ok(!('variants' in patchBody), '(2) title-only PATCH body must not contain a variants key');
}

// (3) Weight changed on variant a -> full set returned; variant b's rule is
//     deep-preserved (parses back to the identical rule object).
{
  const edited = asFormRows(original);
  edited[0] = { ...edited[0], weight: '80' };
  const field = computeVariantsField(original, edited);
  assert.ok(Array.isArray(field), '(3) a weight change must submit the full set');
  assert.strictEqual(field.length, 2);
  assert.strictEqual(field[0].weight, 80, '(3) weight must be submitted as a Number');
  assert.deepStrictEqual(JSON.parse(field[1].matchRule), JSON.parse(RULE_B),
    '(3) the untouched variant\'s matchRule must be deep-preserved');
  assert.deepStrictEqual(JSON.parse(field[0].matchRule), JSON.parse(RULE_A),
    '(3) the edited variant keeps its own matchRule too');
}

// (4) Same rule, different JSON key order (and weight as string) -> not a change.
{
  const reordered = '{"os":["ios","android"],"devices":["mobile"]}';
  const edited = asFormRows(original);
  edited[0] = { ...edited[0], matchRule: reordered };
  assert.strictEqual(computeVariantsField(original, edited), undefined,
    '(4) matchRule key order must not count as a change');
}

// (5) A variant removed -> array without it, survivor's rule intact.
{
  const edited = asFormRows(original).slice(0, 1);
  const field = computeVariantsField(original, edited);
  assert.ok(Array.isArray(field), '(5) removing a variant must submit the set');
  assert.strictEqual(field.length, 1);
  assert.strictEqual(field[0].key, 'a');
  assert.deepStrictEqual(JSON.parse(field[0].matchRule), JSON.parse(RULE_A));
}

// (6) Explicitly clearing a rule (matchRule: null where an object was) is a
//     real change -> array returned, that entry carrying null, the other intact.
{
  const edited = asFormRows(original);
  edited[1] = { ...edited[1], matchRule: null };
  const field = computeVariantsField(original, edited);
  assert.ok(Array.isArray(field), '(6) explicit rule clear must submit the set');
  assert.strictEqual(field[1].matchRule, null, '(6) the cleared rule must be explicit null');
  assert.deepStrictEqual(JSON.parse(field[0].matchRule), JSON.parse(RULE_A),
    '(6) the other variant\'s rule must survive a sibling clear');
}

// Bonus invariants the save path relies on:
// - clearing ALL rows differs from a non-empty original -> [] (explicit clear-all);
// - no variants before, none after -> undefined (omit, like the old contract);
// - a rule supplied as an object (not a string) is serialized for the API.
assert.deepStrictEqual(computeVariantsField(original, []), [],
  'clear-all must submit an empty array');
assert.strictEqual(computeVariantsField([], []), undefined,
  'empty-to-empty must omit the field');
{
  const edited = [{ key: 'a', url: 'https://a.example.com/', weight: 50, matchRule: { devices: ['mobile'] } }];
  const field = computeVariantsField([], edited);
  assert.strictEqual(field[0].matchRule, '{"devices":["mobile"]}',
    'an object matchRule must be serialized to the API\'s string shape');
}

console.log('ui-contract-test: all assertions passed (editTitleOmitsUnchangedVariants)');
