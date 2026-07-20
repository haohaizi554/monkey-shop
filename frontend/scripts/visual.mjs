import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const extraArgs = process.argv.slice(2)
const require = createRequire(import.meta.url)
const playwrightCli = require.resolve('@playwright/test/cli')

function runPlaywright() {
  const child = spawn(
    process.execPath,
    [
      playwrightCli,
      'test',
      'tests/a11y-routes.spec.ts',
      '--grep',
      'visual baseline:',
      '--workers=1',
      '--reporter=list',
      ...extraArgs,
    ],
    {
      cwd: root,
      env: {
        ...process.env,
        RUN_VISUAL_BASELINES: '1',
      },
      stdio: 'inherit',
    },
  )

  return new Promise((resolve, reject) => {
    child.on('error', reject)
    child.on('exit', (code, signal) => {
      if (signal) {
        resolve(1)
        return
      }
      resolve(code ?? 1)
    })
  })
}

process.exitCode = await runPlaywright()
