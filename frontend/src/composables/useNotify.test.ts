import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import { i18n } from '@/locales'
import { clearFeedback, feedbackItems, useNotify } from './useNotify'

describe('useNotify', () => {
  beforeEach(() => {
    clearFeedback()
    i18n.global.locale.value = 'zh'
  })

  afterEach(() => {
    clearFeedback()
  })

  it('groups repeated feedback by semantic key', () => {
    const notify = useNotify()

    const firstId = notify.error('\u8bf7\u7a0d\u540e\u91cd\u8bd5', { key: 'rate-limit' })
    const secondId = notify.error('\u8bf7\u7a0d\u540e\u91cd\u8bd5', { key: 'rate-limit' })

    expect(secondId).toBe(firstId)
    expect(feedbackItems).toHaveLength(1)
    expect(feedbackItems[0]?.count).toBe(2)
  })

  it('caps the visible queue at three items', () => {
    const notify = useNotify()

    const firstId = notify.info('one', { key: 'one', duration: 0 })
    notify.info('two', { key: 'two', duration: 0 })
    notify.info('three', { key: 'three', duration: 0 })
    notify.info('four', { key: 'four', duration: 0 })

    expect(feedbackItems).toHaveLength(3)
    expect(feedbackItems.some((item) => item.id === firstId)).toBe(false)
  })

  it('dismisses a selected item', () => {
    vi.useFakeTimers()
    const notify = useNotify()
    const id = notify.success('saved', { duration: 100 })

    notify.dismiss(id)

    expect(feedbackItems).toHaveLength(0)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('clearFeedback removes items and their dismissal timers', () => {
    vi.useFakeTimers()
    const notify = useNotify()
    notify.info('queued', { duration: 100 })

    expect(vi.getTimerCount()).toBe(1)
    clearFeedback()

    expect(feedbackItems).toHaveLength(0)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('refreshes the dismissal timer when a grouped item repeats', async () => {
    vi.useFakeTimers()
    const notify = useNotify()
    notify.warning('wait', { key: 'wait', duration: 100 })

    await vi.advanceTimersByTimeAsync(75)
    notify.warning('wait', { key: 'wait', duration: 100 })
    await vi.advanceTimersByTimeAsync(50)

    expect(feedbackItems).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(51)
    expect(feedbackItems).toHaveLength(0)
  })

  it('maps a 429 ApiError to recoverable Chinese copy', () => {
    const notify = useNotify()

    notify.fromApiError(
      new ApiError('Too many requests', 429, 'trace-rate-limit'),
      '\u8bf7\u6c42\u5931\u8d25',
    )

    expect(feedbackItems[0]?.message).toContain('\u64cd\u4f5c\u592a\u9891\u7e41')
    expect(feedbackItems[0]?.message).not.toContain('Too many requests')
    expect(feedbackItems[0]?.traceId).toBe('trace-rate-limit')
  })

  it('normalizes raw provider rate-limit and permission messages', () => {
    const notify = useNotify()

    expect(notify.normalize('Too many requests')).toBe('操作太频繁了，请稍后再试。')
    expect(notify.normalize('Operation is not permitted')).toBe(
      '当前账号没有权限执行这个操作。',
    )
  })

  it.each([
    [401, '\u767b\u5f55'],
    [403, '\u6743\u9650'],
  ])('maps status %s to actionable copy', (status, expectedCopy) => {
    const notify = useNotify()

    notify.fromApiError(
      new ApiError('Operation is not permitted', status, `trace-${status}`),
      '\u8bf7\u6c42\u5931\u8d25',
    )

    expect(feedbackItems[0]?.message).toContain(expectedCopy)
    expect(feedbackItems[0]?.message).not.toContain('Operation is not permitted')
  })

  it('uses a registered fallback key for an unknown error', () => {
    const notify = useNotify()

    notify.fromApiError(new Error('internal SQL detail'), 'common.unableToLoadCatalog')

    expect(feedbackItems[0]?.message).toBe('\u65e0\u6cd5\u52a0\u8f7d\u5546\u54c1\u5217\u8868')
    expect(feedbackItems[0]?.message).not.toContain('SQL')
  })

  it('rejects an unregistered fallback string', () => {
    const notify = useNotify()

    notify.fromApiError(new Error('internal SQL detail'), 'another backend detail')

    expect(feedbackItems[0]?.message).toBe(
      '\u8bf7\u6c42\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002',
    )
    expect(feedbackItems[0]?.message).not.toContain('backend')
  })

  it('keeps the compatibility notify entry point', () => {
    const notify = useNotify()

    notify.notify('info', 'compatible', { duration: 0 })

    expect(feedbackItems[0]).toMatchObject({ level: 'info', message: 'compatible' })
  })

  it('returns true when confirmation succeeds and false when it is cancelled', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValueOnce({} as never)
    const notify = useNotify()

    await expect(notify.confirm({ content: 'continue?' })).resolves.toBe(true)
    expect(confirm).toHaveBeenCalledOnce()

    confirm.mockRejectedValueOnce(new Error('cancelled'))
    await expect(notify.confirm({ content: 'continue?' })).resolves.toBe(false)
  })
})
