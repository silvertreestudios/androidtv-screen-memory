// Executed only by the official GitHub Actions github-script integration.
module.exports = async ({github, context, core}) => {
  const fs = require('fs');
  const crypto = require('crypto');
  const sha = context.sha;
  if (context.eventName !== 'push' || context.ref !== 'refs/heads/main' || !/^[0-9a-f]{40}$/.test(sha)) {
    throw new Error('Release requires exact main push SHA');
  }
  const tag = `sha-${sha}`;
  const name = `screen-memory-${sha}.apk`;
  const apk = fs.readFileSync(`release/${name}`);
  const digest = crypto.createHash('sha256').update(apk).digest('hex');
  const checksum = Buffer.from(`${digest}  ${name}\n`);
  if (!fs.readFileSync(`release/${name}.sha256`).equals(checksum)) throw new Error('Local checksum mismatch');
  const repo = context.repo;
  let release;
  try {
    release = (await github.rest.repos.getReleaseByTag({...repo, tag})).data;
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  const validateRelease = (value, draft) => {
    if (value.tag_name !== tag || value.target_commitish !== sha ||
        typeof value.draft !== 'boolean' || value.prerelease !== false ||
        (draft !== undefined && value.draft !== draft)) throw new Error('Release identity/state mismatch');
  };
  if (release) validateRelease(release);
  const verifyTag = async () => {
    const ref = (await github.rest.git.getRef({...repo, ref: `tags/${tag}`})).data;
    if (ref.ref !== `refs/tags/${tag}` || ref.object.type !== 'commit' || ref.object.sha !== sha) throw new Error('Tag target mismatch');
  };
  try {
    await verifyTag();
  } catch (error) {
    if (error.status !== 404) throw error;
    if (release && !release.draft) throw new Error('Published release has no matching tag');
    await github.rest.git.createRef({...repo, ref: `refs/tags/${tag}`, sha});
    await verifyTag();
  }
  if (!release) {
    release = (await github.rest.repos.createRelease({...repo, tag_name: tag,
      target_commitish: sha, name: tag, draft: true, prerelease: false, make_latest: 'false',
      body: `Commit ${sha}. Tested projector: Valerion VisionMaster Max; firmware dependent. Debuggable APK signed with a dedicated release identity; cannot update differently signed installations. No automatic uninstall or migration.`})).data;
    validateRelease(release, true);
  }
  const releaseId = release.id;
  const payloads = [[name, apk, 'application/vnd.android.package-archive'], [`${name}.sha256`, checksum, 'text/plain']];
  const listAssets = () => github.paginate(github.rest.repos.listReleaseAssets, {...repo, release_id: releaseId});
  const validateAssets = async (assets, complete) => {
    if (assets.some(a => !payloads.some(([filename]) => filename === a.name))) throw new Error('Unexpected release assets');
    for (const [filename, data] of payloads) {
      const matches = assets.filter(a => a.name === filename);
      if (matches.length > 1) throw new Error('Duplicate release asset');
      if (!matches.length) {
        if (complete) throw new Error('Release assets incomplete');
        continue;
      }
      if (matches[0].state !== 'uploaded' || matches[0].size !== data.length) throw new Error('Asset state/size mismatch');
      const response = await github.rest.repos.getReleaseAsset({...repo, asset_id: matches[0].id, headers: {accept: 'application/octet-stream'}});
      if (!Buffer.from(response.data).equals(data)) throw new Error('Existing asset differs; refusing overwrite');
    }
  };
  const readRelease = async draft => {
    const current = (await github.rest.repos.getReleaseByTag({...repo, tag})).data;
    validateRelease(current, draft);
    if (current.id !== releaseId) throw new Error('Release ID mismatch');
  };
  const assets = await listAssets();
  await validateAssets(assets, !release.draft);
  if (release.draft) {
    for (const [filename, data, type] of payloads) {
      if (!assets.some(a => a.name === filename)) {
        await github.rest.repos.uploadReleaseAsset({...repo, release_id: releaseId, name: filename, data, headers: {'content-type': type, 'content-length': data.length}});
      }
    }
    // Read back both uploaded payloads and exact target before exposing the release.
    await readRelease(true);
    await verifyTag();
    await validateAssets(await listAssets(), true);
    await github.rest.repos.updateRelease({...repo, release_id: releaseId, draft: false, make_latest: 'false'});
  }
  await readRelease(false);
  await verifyTag();
  await validateAssets(await listAssets(), true);
  core.info(`Verified ${tag} assets`);
};
