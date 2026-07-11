<script setup lang="ts">
import { CircleCheck, CircleClose, Close, InfoFilled, WarningFilled } from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  feedbackItems,
  type FeedbackItem,
  type FeedbackLevel,
  useNotify,
} from '@/composables/useNotify'

const { t } = useI18n()
const notify = useNotify()

const levelIcons: Record<FeedbackLevel, Component> = {
  success: CircleCheck,
  info: InfoFilled,
  warning: WarningFilled,
  error: CircleClose,
}

function itemRole(level: FeedbackLevel): 'status' | 'alert' {
  return level === 'warning' || level === 'error' ? 'alert' : 'status'
}

function title(item: FeedbackItem): string {
  if (item.title) {
    return item.title
  }
  const titleKeys: Record<FeedbackLevel, string> = {
    success: 'feedback.titleSuccess',
    info: 'feedback.titleInfo',
    warning: 'feedback.titleWarning',
    error: 'feedback.titleError',
  }
  return t(titleKeys[item.level])
}
</script>

<template>
  <Teleport to="body">
    <section
      v-if="feedbackItems.length"
      class="app-feedback-host"
      :aria-label="$t('feedback.region')"
    >
      <TransitionGroup name="feedback">
        <article
          v-for="item in feedbackItems"
          :key="item.id"
          class="app-feedback-item"
          :class="`app-feedback-item--${item.level}`"
          :role="itemRole(item.level)"
          aria-atomic="true"
        >
          <el-icon class="app-feedback-item__icon" aria-hidden="true">
            <component :is="levelIcons[item.level]" />
          </el-icon>

          <div class="app-feedback-item__content">
            <div class="app-feedback-item__heading">
              <strong>{{ title(item) }}</strong>
              <span
                v-if="item.count > 1"
                class="app-feedback-item__count"
                :aria-label="$t('feedback.repeated', { count: item.count })"
              >
                ×{{ item.count }}
              </span>
            </div>
            <p>{{ item.message }}</p>
            <details v-if="item.traceId" class="app-feedback-item__details">
              <summary>{{ $t('common.errorDetails') }}</summary>
              <span>{{ $t('common.traceId') }}: {{ item.traceId }}</span>
            </details>
          </div>

          <button
            class="app-feedback-item__dismiss"
            type="button"
            :aria-label="$t('common.dismiss')"
            @click="notify.dismiss(item.id)"
          >
            <Close aria-hidden="true" />
          </button>
        </article>
      </TransitionGroup>
    </section>
  </Teleport>
</template>

<style scoped>
.app-feedback-host {
  position: fixed;
  top: var(--space-4);
  right: var(--space-4);
  z-index: 3000;
  display: grid;
  gap: var(--space-2);
  width: min(360px, calc(100vw - 32px));
  pointer-events: none;
}

.app-feedback-item {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) 36px;
  gap: var(--space-3);
  align-items: start;
  min-height: 76px;
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  color: var(--color-text);
  background: var(--color-surface);
  box-shadow: var(--shadow-overlay);
  pointer-events: auto;
}

.app-feedback-item__icon {
  width: 24px;
  height: 24px;
  margin-top: 1px;
  font-size: 22px;
}

.app-feedback-item--success .app-feedback-item__icon {
  color: var(--color-success);
}

.app-feedback-item--info .app-feedback-item__icon {
  color: var(--color-info);
}

.app-feedback-item--warning .app-feedback-item__icon {
  color: var(--color-warning);
}

.app-feedback-item--error .app-feedback-item__icon {
  color: var(--color-danger);
}

.app-feedback-item__content {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}

.app-feedback-item__heading {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.app-feedback-item__heading strong,
.app-feedback-item__content p,
.app-feedback-item__details span {
  overflow-wrap: anywhere;
}

.app-feedback-item__heading strong {
  font-size: var(--text-sm);
  line-height: var(--leading-tight);
}

.app-feedback-item__content p {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-normal);
}

.app-feedback-item__count {
  display: inline-grid;
  place-items: center;
  min-width: 24px;
  min-height: 20px;
  border-radius: var(--radius-pill);
  padding: 0 var(--space-2);
  color: var(--color-text-muted);
  background: var(--color-surface-subtle);
  font-size: var(--text-xs);
}

.app-feedback-item__details {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.app-feedback-item__details summary {
  width: fit-content;
  cursor: pointer;
}

.app-feedback-item__details span {
  display: block;
  margin-top: var(--space-1);
  font-family: ui-monospace, monospace;
}

.app-feedback-item__dismiss {
  display: inline-grid;
  place-items: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-control);
  color: var(--color-text-muted);
  background: transparent;
  cursor: pointer;
}

.app-feedback-item__dismiss:hover {
  color: var(--color-text);
  background: var(--color-surface-subtle);
}

.app-feedback-item__dismiss svg {
  width: 18px;
  height: 18px;
}

.feedback-enter-active,
.feedback-leave-active,
.feedback-move {
  transition:
    opacity var(--motion-fast),
    transform var(--motion-structure);
}

.feedback-enter-from,
.feedback-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

@media (max-width: 640px) {
  .app-feedback-host {
    top: var(--space-2);
    right: var(--space-2);
    width: calc(100vw - 16px);
  }

  .app-feedback-item {
    grid-template-columns: 24px minmax(0, 1fr) 44px;
  }

  .app-feedback-item__dismiss {
    width: 44px;
    height: 44px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .feedback-enter-active,
  .feedback-leave-active,
  .feedback-move {
    transition: none;
  }
}
</style>
