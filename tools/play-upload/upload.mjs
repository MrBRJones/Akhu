#!/usr/bin/env node
// Uploads an Akhu app bundle to a Google Play track with the Play Developer API.
//
//   node upload.mjs --check                       test the key: open an edit, list tracks, discard it
//   node upload.mjs --track internal              upload app/build/outputs/bundle/release/app-release.aab
//   node upload.mjs --track internal --aab X.aab --notes "What's new" --draft
//
// The service-account key never goes in git. It's read from PLAY_SERVICE_ACCOUNT_KEY, or by default
// from B:/Wallpaper/akhu-keys/play-service-account.json (next to the upload keystore).
import { androidpublisher, auth } from '@googleapis/androidpublisher';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.MrBRos.akhu';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '..', '..');
const DEFAULT_KEY = 'B:/Wallpaper/akhu-keys/play-service-account.json';
const DEFAULT_AAB = path.join(ROOT, 'app', 'build', 'outputs', 'bundle', 'release', 'app-release.aab');
const TRACKS = ['internal', 'alpha', 'beta', 'production'];

function args() {
  const a = process.argv.slice(2);
  const out = { check: false, draft: false, track: null, aab: DEFAULT_AAB, notes: null };
  for (let i = 0; i < a.length; i++) {
    switch (a[i]) {
      case '--check': out.check = true; break;
      case '--draft': out.draft = true; break;
      case '--track': out.track = a[++i]; break;
      case '--aab': out.aab = path.resolve(a[++i]); break;
      case '--notes': out.notes = a[++i]; break;
      default: fail(`Unknown option ${a[i]}`);
    }
  }
  if (!out.check && !TRACKS.includes(out.track)) fail(`--track must be one of ${TRACKS.join(', ')}`);
  return out;
}

function fail(msg) {
  console.error(`✖ ${msg}`);
  process.exit(1);
}

async function client() {
  const keyFile = process.env.PLAY_SERVICE_ACCOUNT_KEY || DEFAULT_KEY;
  if (!fs.existsSync(keyFile)) fail(`No service-account key at ${keyFile}`);
  const key = JSON.parse(fs.readFileSync(keyFile, 'utf8'));
  const a = new auth.JWT({
    email: key.client_email,
    key: key.private_key,
    scopes: ['https://www.googleapis.com/auth/androidpublisher'],
  });
  return androidpublisher({ version: 'v3', auth: a });
}

async function main() {
  const opt = args();
  const play = await client();
  const { data: edit } = await play.edits.insert({ packageName: PACKAGE });
  const editId = edit.id;
  let committed = false;
  try {
    if (opt.check) {
      const { data } = await play.edits.tracks.list({ packageName: PACKAGE, editId });
      console.log(`✔ Key works for ${PACKAGE}. Tracks:`);
      for (const t of data.tracks ?? []) {
        const codes = (t.releases ?? []).flatMap((r) => (r.versionCodes ?? []).map((v) => `${v} (${r.status})`));
        console.log(`  ${t.track}: ${codes.join(', ') || 'no releases'}`);
      }
      return;
    }

    if (!fs.existsSync(opt.aab)) fail(`No bundle at ${opt.aab}`);
    const size = (fs.statSync(opt.aab).size / 1048576).toFixed(1);
    console.log(`Uploading ${path.basename(opt.aab)} (${size} MB)…`);
    const { data: bundle } = await play.edits.bundles.upload({
      packageName: PACKAGE,
      editId,
      media: { mimeType: 'application/octet-stream', body: fs.createReadStream(opt.aab) },
    });
    const versionCode = String(bundle.versionCode);
    console.log(`  uploaded version code ${versionCode}`);

    const release = { versionCodes: [versionCode], status: opt.draft ? 'draft' : 'completed' };
    if (opt.notes) release.releaseNotes = [{ language: 'en-US', text: opt.notes.slice(0, 500) }];
    await play.edits.tracks.update({
      packageName: PACKAGE,
      editId,
      track: opt.track,
      requestBody: { track: opt.track, releases: [release] },
    });
    await play.edits.commit({ packageName: PACKAGE, editId });
    committed = true;
    console.log(`✔ Version ${versionCode} is ${opt.draft ? 'saved as a draft' : 'rolled out'} on the ${opt.track} track.`);
  } finally {
    // A check, or a failure before commit, leaves nothing behind.
    if (!committed) {
      await play.edits.delete({ packageName: PACKAGE, editId }).catch(() => {});
    }
  }
}

main().catch((e) => {
  const msg = e?.response?.data?.error?.message || e.message;
  fail(msg);
});
