<script setup lang="ts">
import Icon from './Icon.vue'
import { useToastStore } from '../stores/toast'

const toast = useToastStore()
</script>

<template>
  <div class="toast-wrap" aria-live="polite" aria-relevant="additions removals">
    <div v-for="t in toast.toasts" :key="t.id" class="toast" :class="t.type">
      <span v-if="t.type === 'success'" class="toast-icon ok"><Icon name="check" :size="16" /></span>
      <span v-else-if="t.type === 'error'" class="toast-icon bad"><Icon name="alert" :size="16" /></span>
      <span class="toast-msg">{{ t.message }}</span>
      <button class="toast-x" type="button" aria-label="关闭" @click="toast.remove(t.id)">
        <Icon name="close" :size="14" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.toast-wrap {
  position: fixed;
  left: var(--sp-6);
  bottom: var(--sp-6);
  z-index: 200;
  display: grid;
  gap: var(--sp-2);
  width: min(380px, calc(100vw - 32px));
  pointer-events: none;
}

.toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--surface-2);
  border: 1px solid var(--line-strong);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
  animation: toast-in var(--dur-panel) var(--ease-out);
}

@keyframes toast-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.toast-icon {
  flex: none;
  display: grid;
  place-items: center;
}

.toast-icon.ok {
  color: var(--success);
}

.toast-icon.bad {
  color: var(--danger);
}

.toast-msg {
  flex: 1;
  font-size: var(--text-small);
  line-height: 1.4;
  color: var(--text);
  word-break: break-word;
}

.toast-x {
  flex: none;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: var(--radius-sm);
  color: var(--text-3);
  transition: color var(--dur-quick) var(--ease-out);
}

.toast-x:hover {
  color: var(--text);
}

@media (max-width: 767px) {
  .toast-wrap {
    left: 50%;
    transform: translateX(-50%);
    bottom: calc(56px + env(safe-area-inset-bottom) + 12px);
    width: calc(100vw - 24px);
    max-width: 420px;
  }
}
</style>
