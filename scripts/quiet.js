// Runs a command; prints its full output ONLY when it exits non-zero (ralph verify fingerprints every output line).
const { spawnSync } = require('child_process');
const cmd = process.argv.slice(2).join(' ');
const r = spawnSync(cmd, { shell: true, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
if (r.status !== 0) {
  process.stdout.write(r.stdout || ''); process.stderr.write(r.stderr || '');
  console.error(`FAILED (exit ${r.status}): ${cmd}`);
}
process.exit(r.status ?? 1);
