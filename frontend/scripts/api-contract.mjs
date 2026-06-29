import fs from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sourceFiles = [
  'src/api/admin.ts',
  'src/api/auth.ts',
  'src/api/catalog.ts',
  'src/api/http.ts',
  'src/api/orders.ts',
  'src/api/user.ts',
  'tests/a11y.spec.ts',
  'scripts/lighthouse.mjs',
]

const sources = Object.fromEntries(
  await Promise.all(
    sourceFiles.map(async (file) => [file, await fs.readFile(path.join(root, file), 'utf8')]),
  ),
)
const apiSource = Object.entries(sources)
  .filter(([file]) => file.startsWith('src/api/'))
  .map(([, source]) => source)
  .join('\n')

const failures = []

function requireIncludes(file, snippet, label = snippet) {
  if (!sources[file].includes(snippet)) {
    failures.push(`${file}: missing ${label}`)
  }
}

function forbidIn(label, source, pattern, description) {
  if (pattern.test(source)) {
    failures.push(`${label}: contains legacy ${description}`)
  }
}

requireIncludes('src/api/http.ts', "baseURL: '/api/v1'", 'versioned API base URL')
requireIncludes('src/api/http.ts', "'X-Trace-Id'", 'trace id request header')
requireIncludes('src/api/http.ts', 'crypto.randomUUID', 'browser trace id generation')
requireIncludes('src/api/http.ts', 'crypto.getRandomValues', 'crypto-backed trace id fallback')
requireIncludes('src/api/auth.ts', "'/api/v1/auth/captcha'", 'auth captcha endpoint')
requireIncludes('src/api/auth.ts', "'/api/v1/users/captcha'", 'user captcha endpoint')
requireIncludes('src/api/orders.ts', "'Idempotency-Key'", 'order idempotency header')
requireIncludes('tests/a11y.spec.ts', '**/api/v1/users/me', 'versioned user mock')
requireIncludes('tests/a11y.spec.ts', '**/api/v1/monkeys', 'versioned product mock')
requireIncludes('scripts/lighthouse.mjs', "'/api/v1/users/me'", 'versioned Lighthouse user mock')
requireIncludes('scripts/lighthouse.mjs', "'/api/v1/monkeys'", 'versioned Lighthouse product mock')

forbidIn('src/api', apiSource, /baseURL:\s*['"`]\/api['"`]/, 'unversioned API base URL')
forbidIn('src/api', apiSource, /\/api\/(?!v1(?:\/|['"`]|$))/, 'raw /api URL outside /api/v1')
forbidIn('src/api', apiSource, /url:\s*['"`]\/user(?:\/|['"`])/, 'singular user URL')
forbidIn('src/api', apiSource, /url:\s*['"`]\/address(?:\/|['"`])/, 'singular address URL')
forbidIn('src/api', apiSource, /url:\s*['"`]\/upload(?:\/|['"`])/, 'singular upload URL')
forbidIn('src/api', apiSource, /Math\.random/, 'Math.random trace/id generation')

if (failures.length > 0) {
  console.error(
    `API contract check failed:\n${failures.map((failure) => `- ${failure}`).join('\n')}`,
  )
  process.exit(1)
}

console.log('API contract check passed')
