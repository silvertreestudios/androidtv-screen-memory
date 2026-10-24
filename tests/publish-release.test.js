const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const publish = require('../scripts/publish-release.js');
const sha = 'a'.repeat(40), tag = `sha-${sha}`, name = `screen-memory-${sha}.apk`;
const apk = Buffer.from('test APK fixture, not a built APK');
const checksum = Buffer.from(`${crypto.createHash('sha256').update(apk).digest('hex')}  ${name}\n`);
const context = {sha, eventName: 'push', ref: 'refs/heads/main', repo: {owner: 'fixture', repo: 'fixture'}};
const missing = () => { throw Object.assign(new Error('missing'), {status: 404}); };
function fixture({draft = false, noRelease = false, noTag = false, empty = false} = {}) {
  const state = {
    release: noRelease ? null : {id: 7, tag_name: tag, target_commitish: sha, draft, prerelease: false},
    ref: noTag ? null : {ref: `refs/tags/${tag}`, object: {type: 'commit', sha}},
    assets: empty ? [] : [{id: 1, name, state: 'uploaded', size: apk.length, bytes: apk},
      {id: 2, name: `${name}.sha256`, state: 'uploaded', size: checksum.length, bytes: checksum}],
    calls: [], success: false
  };
  const record = (name, args) => state.calls.push([name, args]);
  const repos = {
    getReleaseByTag: async args => { record('readRelease', args); return {data: state.release ? {...state.release} : missing()}; },
    listReleaseAssets: () => {},
    getReleaseAsset: async args => { record('download', args); return {data: state.assets.find(a => a.id === args.asset_id).bytes}; },
    createRelease: async args => { record('createRelease', args); state.release = {id: 7, ...args}; return {data: {...state.release}}; },
    uploadReleaseAsset: async args => { record('upload', args); state.assets.push({id: state.assets.length + 1, name: args.name, state: 'uploaded', size: args.data.length, bytes: args.data}); },
    updateRelease: async args => { record('publish', args); state.release.draft = args.draft; return {data: {...state.release}}; }
  };
  const git = {
    getRef: async args => { record('readTag', args); return {data: state.ref || missing()}; },
    createRef: async args => { record('createTag', args); state.ref = {ref: args.ref, object: {type: 'commit', sha: args.sha}}; return {data: state.ref}; }
  };
  const args = {github: {rest: {git, repos}, paginate: async () => { record('list'); return state.assets.map(a => ({...a})); }}, context,
    core: {info: () => { state.success = true; }}};
  return {state, args, repos, git};
}
const mutations = f => f.state.calls.filter(([c]) => ['createTag', 'createRelease', 'upload', 'publish'].includes(c));
(async () => {
  const cwd = process.cwd(), dir = fs.mkdtempSync(path.join(os.tmpdir(), 'release-test-'));
  let count = 0;
  const test = async (label, run) => { await run(); count++; console.log(`PASS ${label}`); };
  try {
    process.chdir(dir); fs.mkdirSync('release');
    fs.writeFileSync(`release/${name}`, apk); fs.writeFileSync(`release/${name}.sha256`, checksum);
    await test('published rerun downloads both assets twice without mutation', async () => {
      const f = fixture(); await publish(f.args); assert.equal(mutations(f).length, 0);
      assert.equal(f.state.calls.filter(([c]) => c === 'download').length, 4); assert(f.state.success);
    });
    await test('new tag is created and read back before draft; uploads read back before publication and after', async () => {
      const f = fixture({noRelease: true, noTag: true, empty: true}); await publish(f.args);
      assert.deepEqual(mutations(f).map(([c]) => c), ['createTag','createRelease','upload','upload','publish']);
      assert.deepEqual(mutations(f)[0][1], {...context.repo, ref: `refs/tags/${tag}`, sha});
      const calls = f.state.calls.map(([c]) => c), creation = calls.indexOf('createRelease'), publication = calls.indexOf('publish');
      assert(calls.slice(calls.indexOf('createTag') + 1, creation).includes('readTag'));
      assert.equal(calls.slice(calls.lastIndexOf('upload') + 1, publication).filter(c => c === 'download').length, 2);
      assert.equal(calls.slice(publication + 1).filter(c => c === 'download').length, 2); assert(f.state.success);
    });
    for (const noTag of [false, true]) await test(`interrupted draft resumes safely (missing tag ${noTag})`, async () => {
      const f = fixture({draft: true, noTag}); f.state.assets.pop(); await publish(f.args);
      assert.equal(mutations(f).filter(([c]) => c === 'upload').length, 1); assert(f.state.success);
    });
    for (const field of ['target_commitish', 'tag_name', 'prerelease', 'draft']) await test(`release ${field} mismatch fails before mutation`, async () => {
      const f = fixture({draft: true, noTag: true}); f.state.release[field] = 'wrong';
      await assert.rejects(publish(f.args), /identity\/state/); assert.equal(mutations(f).length, 0);
    });
    for (const change of [a => a.push({...a[0]}), a => a.push({...a[0], name: 'unknown'}), a => a[0].state = 'starter', a => a[0].size++, a => a[0].bytes = Buffer.alloc(apk.length)]) {
      await test('invalid existing asset fails closed without mutation', async () => {
        const f = fixture({draft: true}); change(f.state.assets); await assert.rejects(publish(f.args));
        assert.equal(mutations(f).length, 0); assert(!f.state.success);
      });
    }
    for (const change of [s => s.assets.pop(), s => s.assets[0].state = 'starter', s => s.assets[0].size++, s => s.assets[0].bytes = Buffer.alloc(apk.length)]) {
      await test('upload response cannot substitute for payload readback', async () => {
        const f = fixture({draft: true, empty: true}); const upload = f.repos.uploadReleaseAsset;
        f.repos.uploadReleaseAsset = async args => { await upload(args); if (args.name.endsWith('.sha256')) change(f.state); };
        await assert.rejects(publish(f.args)); assert(!f.state.calls.some(([c]) => c === 'publish')); assert(!f.state.success);
      });
    }
    for (const change of [s => s.release.target_commitish = 'wrong', s => s.release.tag_name = 'wrong', s => s.release.draft = false, s => s.ref.object.type = 'tag']) {
      await test('prepublication target/state readback failure prevents publication', async () => {
        const f = fixture({draft: true, empty: true}); const upload = f.repos.uploadReleaseAsset;
        f.repos.uploadReleaseAsset = async args => { await upload(args); if (args.name.endsWith('.sha256')) change(f.state); };
        await assert.rejects(publish(f.args)); assert(!f.state.calls.some(([c]) => c === 'publish')); assert(!f.state.success);
      });
    }
    for (const change of [s => s.release.draft = true, s => s.release.target_commitish = 'wrong', s => s.release.tag_name = 'wrong', s => s.release.id++, s => s.assets.pop(), s => s.assets[0].bytes = Buffer.alloc(apk.length), s => s.ref.object.sha = 'b'.repeat(40)]) {
      await test('postpublication readback failure prevents success', async () => {
        const f = fixture({draft: true}); const update = f.repos.updateRelease;
        f.repos.updateRelease = async args => { const result = await update(args); change(f.state); return result; };
        await assert.rejects(publish(f.args)); assert(!f.state.success);
      });
    }
    for (const change of [r => r.object.sha = 'b'.repeat(40), r => r.object.type = 'tag', r => r.ref = 'refs/heads/main']) {
      await test('wrong or annotated tag rejected', async () => {
        const f = fixture(); change(f.state.ref); await assert.rejects(publish(f.args), /Tag target/); assert.equal(mutations(f).length, 0);
      });
    }
    await test('created tag must pass independent readback', async () => {
      const f = fixture({noRelease: true, noTag: true}); f.git.createRef = async () => ({});
      await assert.rejects(publish(f.args)); assert(!f.state.calls.some(([c]) => c === 'createRelease'));
    });
    await test('incomplete published release never mutated', async () => {
      const f = fixture({empty: true}); await assert.rejects(publish(f.args), /incomplete/); assert.equal(mutations(f).length, 0);
    });
    await test('published release without tag never repaired', async () => {
      const f = fixture({noTag: true}); await assert.rejects(publish(f.args), /no matching tag/); assert.equal(mutations(f).length, 0);
    });
    await test('unauthorized events and refs rejected', async () => {
      for (const override of [{eventName:'pull_request'}, {eventName:'workflow_run'}, {eventName:'workflow_dispatch'}, {ref:'refs/heads/feature'}, {sha:'bad'}]) {
        const f = fixture(); await assert.rejects(publish({...f.args, context: {...context, ...override}}), /main push/); assert.equal(f.state.calls.length, 0);
      }
    });
    await test('local checksum mismatch rejected before API calls', async () => {
      fs.writeFileSync(`release/${name}.sha256`, 'wrong'); const f = fixture();
      await assert.rejects(publish(f.args), /Local checksum/); assert.equal(f.state.calls.length, 0);
    });
    console.log(`PASS: ${count} release regression scenarios`);
  } finally { process.chdir(cwd); fs.rmSync(dir, {recursive: true}); }
})().catch(error => { console.error(error); process.exitCode = 1; });
