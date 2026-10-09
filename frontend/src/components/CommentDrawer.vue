<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import Icon from './Icon.vue'
import UserAvatar from './UserAvatar.vue'
import { ApiError } from '../api/client'
import * as commentApi from '../api/comment'
import type { Comment } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'

const props = defineProps<{ video: { id: number; title?: string } | null }>()
const emit = defineEmits<{ close: [] }>()

const route = useRoute()
const auth = useAuthStore()
const toast = useToastStore()

const drawer = reactive({
  loading: false,
  error: '',
  comments: [] as Comment[],
  content: '',
})

const textareaEl = ref<HTMLTextAreaElement | null>(null)
const panelEl = ref<HTMLElement | null>(null)

function close() {
  drawer.comments = []
  drawer.content = ''
  drawer.error = ''
  emit('close')
}

async function loadComments() {
  if (!props.video) return
  drawer.loading = true
  drawer.error = ''
  try {
    drawer.comments = await commentApi.listAll(props.video.id)
  } catch (e) {
    drawer.error = e instanceof ApiError ? e.message : String(e)
  } finally {
    drawer.loading = false
  }
}

async function publishComment() {
  if (!props.video) return
  if (!auth.isLoggedIn) {
    toast.error('请先登录')
    return
  }
  const content = drawer.content.trim()
  if (!content) return
  drawer.loading = true
  drawer.error = ''
  try {
    await commentApi.publish(props.video.id, content)
    drawer.content = ''
    await loadComments()
    toast.success('评论已发布')
  } catch (e) {
    drawer.error = e instanceof ApiError ? e.message : String(e)
    toast.error(drawer.error)
  } finally {
    drawer.loading = false
  }
}

function canDeleteComment(c: Comment) {
  const myId = auth.claims?.account_id
  return !!myId && myId === c.author_id
}

async function deleteComment(commentId: number) {
  if (!props.video) return
  if (!auth.isLoggedIn) {
    toast.error('请先登录')
    return
  }
  if (!window.confirm('删除这条评论？删除后无法恢复。')) return
  drawer.loading = true
  drawer.error = ''
  try {
    await commentApi.remove(commentId)
    await loadComments()
    toast.info('评论已删除')
  } catch (e) {
    drawer.error = e instanceof ApiError ? e.message : String(e)
    toast.error(drawer.error)
  } finally {
    drawer.loading = false
  }
}

function formatTime(raw: string) {
  const d = new Date(raw)
  return Number.isNaN(d.getTime()) ? raw : d.toLocaleString('zh-CN', { hour12: false })
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  await loadComments()
  await nextTick()
  if (auth.isLoggedIn) textareaEl.value?.focus()
  else panelEl.value?.focus()
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="backdrop" @click.self="close">
    <section
      ref="panelEl"
      class="panel"
      role="dialog"
      aria-modal="true"
      aria-label="评论"
      tabindex="-1"
    >
      <header class="head">
        <div class="head-text">
          <h2 class="head-title">评论</h2>
          <p v-if="video?.title" class="head-sub">{{ video.title }}</p>
        </div>
        <button class="btn btn--icon btn--ghost" type="button" aria-label="关闭评论" @click="close">
          <Icon name="close" :size="18" />
        </button>
      </header>

      <div class="body">
        <div v-if="drawer.loading && drawer.comments.length === 0" class="hint">加载中…</div>
        <div v-else-if="drawer.error && drawer.comments.length === 0" class="hint bad">
          <Icon name="alert" :size="18" />
          <span>{{ drawer.error }}</span>
        </div>
        <div v-else-if="drawer.comments.length === 0" class="hint">
          <Icon name="comment" :size="18" />
          <span>还没有评论，来发第一条</span>
        </div>

        <article v-for="c in drawer.comments" :key="c.id" class="comment">
          <UserAvatar :username="c.username" :size="32" />
          <div class="comment-main">
            <div class="comment-top">
              <span class="comment-user">{{ c.username }}</span>
              <span class="comment-time">{{ formatTime(c.created_at) }}</span>
            </div>
            <p class="comment-content">{{ c.content }}</p>
          </div>
          <button
            v-if="canDeleteComment(c)"
            class="comment-del"
            type="button"
            :disabled="drawer.loading"
            aria-label="删除这条评论"
            @click="deleteComment(c.id)"
          >
            <Icon name="close" :size="14" />
          </button>
        </article>
      </div>

      <footer class="foot">
        <template v-if="auth.isLoggedIn">
          <textarea
            ref="textareaEl"
            v-model="drawer.content"
            class="foot-input"
            placeholder="说点什么…"
            :disabled="drawer.loading"
            @keydown.ctrl.enter="publishComment"
          />
          <div class="foot-row">
            <span class="foot-tip">Ctrl+Enter 发送</span>
            <button
              class="btn btn--primary"
              type="button"
              :disabled="drawer.loading || !drawer.content.trim()"
              @click="publishComment"
            >
              <Icon name="send" :size="16" />
              发送
            </button>
          </div>
        </template>
        <div v-else class="foot-guest">
          <span class="muted">登录后参与评论</span>
          <RouterLink class="btn btn--primary" :to="{ path: '/account', query: { redirect: route.fullPath } }">
            去登录
          </RouterLink>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 120;
  background: var(--scrim-1);
  backdrop-filter: blur(8px);
  display: grid;
  justify-items: end;
  animation: fade-in var(--dur-quick) var(--ease-out);
}

@keyframes fade-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.panel {
  width: min(420px, calc(100vw - 16px));
  height: 100%;
  display: grid;
  grid-template-rows: auto 1fr auto;
  background: var(--surface);
  border-left: 1px solid var(--line-strong);
  outline: none;
  animation: slide-in var(--dur-panel) var(--ease-out);
}

@keyframes slide-in {
  from {
    transform: translateX(24px);
    opacity: 0;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-4);
  border-bottom: 1px solid var(--line);
}

.head-text {
  min-width: 0;
}

.head-title {
  font-size: var(--text-title);
  font-weight: 800;
  letter-spacing: -0.01em;
}

.head-sub {
  margin-top: 2px;
  font-size: var(--text-meta);
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.body {
  overflow-y: auto;
  padding: var(--sp-2) var(--sp-4);
}

.hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-2);
  padding: var(--sp-10) 0;
  color: var(--text-2);
  font-size: var(--text-small);
}

.hint.bad {
  color: var(--danger);
}

.comment {
  display: flex;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
  border-bottom: 1px solid var(--line);
}

.comment-main {
  flex: 1;
  min-width: 0;
}

.comment-top {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
}

.comment-user {
  font-size: var(--text-small);
  font-weight: 700;
}

.comment-time {
  font-size: var(--text-caption);
  color: var(--text-3);
}

.comment-content {
  margin-top: 4px;
  font-size: var(--text-small);
  line-height: var(--leading-normal);
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.comment-del {
  flex: none;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-sm);
  color: var(--text-3);
  transition: color var(--dur-quick) var(--ease-out);
}

.comment-del:hover {
  color: var(--danger);
}

.foot {
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
  border-top: 1px solid var(--line);
}

.foot-input {
  min-height: 64px;
  resize: none;
}

.foot-row {
  margin-top: var(--sp-2);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.foot-tip {
  font-size: var(--text-caption);
  color: var(--text-3);
}

.foot-guest {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  font-size: var(--text-small);
}

@media (max-width: 767px) {
  .backdrop {
    justify-items: center;
    align-items: end;
  }

  .panel {
    width: 100%;
    height: min(78vh, 640px);
    border-left: none;
    border-top: 1px solid var(--line-strong);
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    animation: slide-up var(--dur-panel) var(--ease-out);
  }

  @keyframes slide-up {
    from {
      transform: translateY(24px);
      opacity: 0;
    }
    to {
      transform: none;
      opacity: 1;
    }
  }
}
</style>
