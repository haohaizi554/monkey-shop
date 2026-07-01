import { spawn } from 'node:child_process'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { createServer } from 'vite'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const server = await createServer({
  root,
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
  },
})

function runPlaywright() {
  const isWindows = process.platform === 'win32'
  const command = isWindows ? 'cmd.exe' : 'npx'
  const args = isWindows
    ? ['/d', '/s', '/c', 'npx.cmd playwright test --reporter=list']
    : ['playwright', 'test', '--reporter=list']
  const child = spawn(command, args, {
    cwd: root,
    env: {
      ...process.env,
      PLAYWRIGHT_SKIP_WEBSERVER: '1',
    },
    stdio: 'inherit',
  })

  return new Promise((resolve) => {
    child.on('exit', (code, signal) => {
      if (signal) {
        resolve(1)
        return
      }
      resolve(code ?? 1)
    })
  })
}

await server.listen()
try {
  const exitCode = await runPlaywright()
  process.exitCode = exitCode
} finally {
  await server.close()
}
