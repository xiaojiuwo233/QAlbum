#!/usr/bin/env node
// QQ 9.2.50 cache-path candidates. This tool never writes or removes cache files.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const folders = ['chatimg', 'chatraw', 'chatthumb'];
const polynomial = BigInt.asIntN(64, 0x95ac9329ac4bc9b5n);
const table = Array.from({ length: 256 }, (_, index) => {
  let value = BigInt(index);
  for (let bit = 0; bit < 8; bit++) {
    value = (value >> 1n) ^ ((value & 1n) ? polynomial : 0n);
  }
  return value;
});

export function qqCrc64(text) {
  if (!text) return 0n;
  let value = -1n;
  for (let index = 0; index < text.length; index++) {
    value = (value >> 8n) ^ table[Number((BigInt(text.charCodeAt(index)) ^ value) & 255n)];
  }
  return BigInt.asIntN(64, value);
}

const seed = '0'.repeat(32);
const deltas = Object.fromEntries(folders.map(folder => [
  folder, qqCrc64(`chatimg:${seed}`) ^ qqCrc64(`${folder}:${seed}`),
]));

export function cacheName(md5, folder) {
  if (!/^[0-9a-fA-F]{32}$/.test(md5)) throw new Error('Expected a 32-character MD5.');
  if (!folders.includes(folder)) throw new Error('Unknown cache folder.');
  return `Cache_${qqCrc64(`${folder}:${md5.toUpperCase()}`).toString(16)}`;
}

export function counterpartName(name, from, to) {
  if (!folders.includes(from) || !folders.includes(to)) throw new Error('Unknown cache folder.');
  const match = /^Cache_(-?[0-9a-f]{1,16})(_hd)?$/.exec(name);
  if (!match || (match[2] && from !== 'chatthumb')) return null;
  const digits = match[1];
  const signed = digits.startsWith('-') ? -BigInt(`0x${digits.slice(1)}`) : BigInt(`0x${digits}`);
  if (BigInt.asIntN(64, signed) !== signed || signed.toString(16) !== digits) return null;
  return `Cache_${BigInt.asIntN(64, signed ^ deltas[from] ^ deltas[to]).toString(16)}`;
}

export function relativePaths(md5) {
  const result = Object.fromEntries(folders.map(folder => {
    const name = cacheName(md5, folder);
    return [folder, `${folder}/${name.slice(-3)}/${name}`];
  }));
  result.chatthumbHd = `${result.chatthumb}_hd`;
  return result;
}

function selfTest() {
  const signs = { positive: 0, negative: 0 };
  for (let index = 0; index < 10000; index++) {
    const md5 = createHash('md5').update(`qq-cache-synthetic-${index}`).digest('hex');
    for (const from of folders) {
      const name = cacheName(md5, from);
      signs[name.startsWith('Cache_-') ? 'negative' : 'positive']++;
      for (const to of folders) {
        assert.equal(counterpartName(name, from, to), cacheName(md5, to));
      }
      if (from === 'chatthumb') {
        assert.equal(counterpartName(`${name}_hd`, from, 'chatimg'), cacheName(md5, 'chatimg'));
      }
    }
  }
  for (const invalid of ['Cache_', 'Cache_-0', 'Cache_01', 'Cache_ffffffffffffffff', 'Cache_1_dp']) {
    assert.equal(counterpartName(invalid, 'chatimg', 'chatraw'), null);
  }
  assert.equal(counterpartName('Cache_1_hd', 'chatimg', 'chatraw'), null);
  assert.equal(qqCrc64(''), 0n);
  assert.ok(signs.positive > 0 && signs.negative > 0);
  return { syntheticMd5s: 10000, crossFolderChecks: 90000, signs, passed: true };
}

async function inventory(root) {
  const groups = new Map();
  const counts = Object.fromEntries(folders.map(folder => [folder, 0]));
  const unavailableFolders = [];
  let skippedEntries = 0;
  for (const folder of folders) {
    let buckets;
    try {
      buckets = await readdir(path.join(root, folder), { withFileTypes: true });
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      unavailableFolders.push(folder);
      continue;
    }
    for (const bucket of buckets) {
      if (!bucket.isDirectory() || !/^[0-9a-f]{3}$/.test(bucket.name)) {
        skippedEntries++;
        continue;
      }
      for (const file of await readdir(path.join(root, folder, bucket.name), { withFileTypes: true })) {
        const key = file.isFile() ? counterpartName(file.name, folder, 'chatimg') : null;
        const base = file.name.endsWith('_hd') ? file.name.slice(0, -3) : file.name;
        if (!key || base.slice(-3) !== bucket.name) {
          skippedEntries++;
          continue;
        }
        counts[folder]++;
        if (!groups.has(key)) groups.set(key, new Set());
        groups.get(key).add(folder);
      }
    }
  }
  const families = { imgAndRaw: 0, imgWithoutRaw: 0, rawWithoutImg: 0, thumbOnly: 0 };
  for (const group of groups.values()) {
    if (group.has('chatimg') && group.has('chatraw')) families.imgAndRaw++;
    else if (group.has('chatimg')) families.imgWithoutRaw++;
    else if (group.has('chatraw')) families.rawWithoutImg++;
    else families.thumbOnly++;
  }
  return {
    mode: 'read-only',
    rule: 'CRC candidates for folder:UPPERCASE_MD5 with a 32-character MD5',
    warning: 'Candidates are not proof of message references or safe deletion. UUID/import/VFS paths may differ.',
    counts, candidateFamilies: groups.size, families, unavailableFolders, skippedEntries,
  };
}

async function main(args) {
  if (args.length === 1 && args[0] === '--self-test') return selfTest();
  if (args.length === 2 && args[0] === '--md5') return relativePaths(args[1]);
  if (args.length === 2 && args[0] === '--inventory') {
    const root = path.resolve(args[1]);
    const entries = await readdir(root, { withFileTypes: true });
    if (!entries.some(entry => entry.isDirectory() && folders.includes(entry.name))) {
      throw new Error('Expected a chatpic root containing chatimg, chatraw, or chatthumb.');
    }
    return inventory(root);
  }
  throw new Error('Usage: node tools/qq_cache_inventory.mjs --self-test | --md5 <md5> | --inventory <chatpic>');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).then(result => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`))
    .catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
