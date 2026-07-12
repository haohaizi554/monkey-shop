import { spawn } from 'node:child_process'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function runPlaywright() {
  const isWindows = process.platform === 'win32'
  const command = isWindows ? 'cmd.exe' : 'npx'
  const args = isWindows
    ? [
        '/d',
        '/s',
        '/c',
        'npx.cmd playwright test tests/a11y.spec.ts tests/a11y-routes.spec.ts --workers=1 --reporter=list',
      ]
    : [
        'playwright',
        'test',
        'tests/a11y.spec.ts',
        'tests/a11y-routes.spec.ts',
        '--workers=1',
        '--reporter=list',
      ]
  const child = spawn(command, args, {
    cwd: root,
    env: process.env,
    stdio: 'inherit',
  })

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
