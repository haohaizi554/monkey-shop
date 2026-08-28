/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const sourceRoot = resolve(process.cwd(), 'src')
const stylesRoot = resolve(sourceRoot, 'styles')

const readStyle = (name: string) => readFileSync(resolve(stylesRoot, name), 'utf8')

function findFiles(directory: string, extension: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name)
    if (entry.isDirectory()) {
      return findFiles(path, extension)
    }
    return entry.isFile() && entry.name.endsWith(extension) ? [path] : []
  })
}

describe('commerce design token contract', () => {
  const tokens = readStyle('tokens.css')
  const base = readStyle('base.css')
  const components = readStyle('components.css')
  const styles = `${tokens}\n${base}\n${components}`

  it('publishes the approved light-theme semantic palette and radii', () => {
    expect(tokens).toContain('--color-canvas: #f3f1ea')
    expect(tokens).toContain('--color-surface: #fffcf6')
    expect(tokens).toContain('--color-surface-subtle: #eaede7')
    expect(tokens).toContain('--color-surface-raised: #ffffff')
    expect(tokens).toContain('--color-ink: #17312a')
    expect(tokens).toContain('--color-muted: #66736e')
    expect(tokens).toContain('--color-line: #d7d9d2')
    expect(tokens).toContain('--color-line-strong: #aab3ac')
    expect(tokens).toContain('--color-primary: #126b5b')
    expect(tokens).toContain('--color-primary-strong: #0b4f43')
    expect(tokens).toContain('--color-primary-soft: #dcede7')
    expect(tokens).toContain('--color-accent: #a8691f')
    expect(tokens).toContain('--color-accent-soft: #f6e7c9')
    expect(tokens).toContain('--color-info: #315f9c')
    expect(tokens).toContain('--color-danger: #b4493d')
    expect(tokens).toContain('--radius-control: 8px')
    expect(tokens).toContain('--radius-surface: 12px')
    expect(tokens).toContain('--radius-overlay: 16px')
    expect(tokens).toContain('--content-consumer: 1320px')
    expect(tokens).toContain('--content-detail: 1440px')
    expect(tokens).toContain('--admin-sidebar-width: 248px')
    expect(tokens).toMatch(/--font-sans:/)
  })

  it('derives consumer, auth, and admin area roles from the shared registry', () => {
    expect(tokens).toContain('--consumer-canvas: var(--color-canvas)')
    expect(tokens).toContain('--consumer-primary: var(--color-primary)')
    expect(tokens).toContain('--auth-surface: var(--color-surface)')
    expect(tokens).toContain('--auth-primary: var(--color-primary)')
    expect(tokens).toContain('--admin-canvas: var(--color-canvas)')
    expect(tokens).toContain('--admin-chrome: var(--color-admin-chrome)')
    expect(tokens).toContain('--admin-accent: var(--color-accent)')
    expect(tokens).toContain('html.dark')
    expect(tokens).toContain('--dark-color-canvas: #0e1512')
    expect(tokens).toContain('--dark-color-surface: #16201c')
    expect(tokens).toContain('--dark-color-surface-raised: #1e2a25')
    expect(tokens).toContain('--dark-color-ink: #eff5f1')
    expect(tokens).toContain('--dark-color-muted: #a8b5ae')
    expect(tokens).toContain('--dark-color-line: #35453d')
    expect(tokens).toContain('--dark-color-primary: #78cbb3')
    expect(tokens).toContain('--dark-color-accent: #e2b56a')
  })

  it('defines focus, motion, elevation, and stacking semantics', () => {
    expect(tokens).toMatch(/--focus-ring:/)
    expect(tokens).toMatch(/--motion-fast:/)
    expect(tokens).toMatch(/--motion-structure:/)
    expect(tokens).toMatch(/--shadow-surface:/)
    expect(tokens).toContain('--z-header:')
    expect(tokens).toContain('--z-overlay:')
    expect(styles).toContain('@media (prefers-reduced-motion: reduce)')
  })

  it('keeps commercial numerals stable and removes disallowed decoration', () => {
    expect(base).toContain('font-variant-numeric: tabular-nums')
    expect(base).toContain('text-wrap: pretty')
    expect(styles).not.toMatch(/linear-gradient|radial-gradient/)
    expect(styles).not.toMatch(/letter-spacing:\s*-/)
    expect(styles).not.toMatch(/border-radius:\s*(1[2-9]|[2-9]\d)px/)
  })

  it('keeps raw color literals inside the semantic token registry', () => {
    const cssSources = findFiles(stylesRoot, '.css')
      .filter((path) => !path.endsWith('tokens.css'))
      .map((path) => ({ path, content: readFileSync(path, 'utf8') }))
    const vueStyleSources = findFiles(sourceRoot, '.vue').flatMap((path) => {
      const content = readFileSync(path, 'utf8')
      return [...content.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/g)].map((match) => ({
        path,
        content: match[1],
      }))
    })
    const rawColorLiteral = /#[0-9a-f]{3,8}\b|rgba?\(/i
    const offenders = [...cssSources, ...vueStyleSources]
      .filter(({ content }) => rawColorLiteral.test(content))
      .map(({ path }) => path.slice(sourceRoot.length + 1).replaceAll('\\', '/'))

    expect(offenders).toEqual([])
  })
})
