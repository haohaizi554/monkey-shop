/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const readStyle = (name: string) =>
  readFileSync(resolve(process.cwd(), 'src', 'styles', name), 'utf8')

describe('commerce design token contract', () => {
  const tokens = readStyle('tokens.css')
  const base = readStyle('base.css')
  const components = readStyle('components.css')
  const styles = `${tokens}\n${base}\n${components}`

  it('publishes the approved light-theme semantic palette and radii', () => {
    expect(tokens).toContain('--color-canvas: #f6f7f9')
    expect(tokens).toContain('--color-surface: #ffffff')
    expect(tokens).toContain('--color-ink: #182230')
    expect(tokens).toContain('--color-muted: #667085')
    expect(tokens).toContain('--color-line: #d8e0e8')
    expect(tokens).toContain('--color-primary: #0b6e61')
    expect(tokens).toContain('--color-primary-soft: #ddf6f0')
    expect(tokens).toContain('--color-cobalt: #2f61d5')
    expect(tokens).toContain('--color-coral: #c94355')
    expect(tokens).toContain('--color-honey: #9f5900')
    expect(tokens).toContain('--color-danger: #b42318')
    expect(tokens).toContain('--radius-control: 6px')
    expect(tokens).toContain('--radius-surface: 8px')
  })

  it('defines focus, motion, elevation, and stacking semantics', () => {
    expect(tokens).toMatch(/--focus-ring:/)
    expect(tokens).toMatch(/--motion-fast:/)
    expect(tokens).toMatch(/--shadow-surface:/)
    expect(tokens).toContain('--z-header:')
    expect(tokens).toContain('--z-overlay:')
    expect(styles).toContain('@media (prefers-reduced-motion: reduce)')
  })

  it('keeps commercial numerals stable and removes disallowed decoration', () => {
    expect(base).toContain('font-variant-numeric: tabular-nums')
    expect(styles).not.toMatch(/linear-gradient|radial-gradient/)
    expect(styles).not.toMatch(/letter-spacing:\s*-/)
    expect(styles).not.toMatch(/border-radius:\s*(1[2-9]|[2-9]\d)px/)
  })
})
