<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppShell from '../components/AppShell.vue'
import Icon from '../components/Icon.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { ApiError } from '../api/client'
import * as accountApi from '../api/account'
import * as messageApi from '../api/message'
import type { Account, DirectMessage } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useSocialStore } from '../stores/social'
import { useToastStore } from '../stores/toast'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const social = useSocialStore()
const toast = useToastStore()

const peerId = computed(() => {
  const raw = route.params.peerId
  return typeof raw === 'string' ? Number(raw) : 0
})
const myId = computed(() => auth.claims?.account_id ?? 0)
const hasPeer = computed(() => Number.isFinite(peerId.value) && peerId.value > 0)
const listEl = ref<HTMLDivElement | null>(null)
const content = ref('')

const state = reactive({
  loading: false,
  sending: false,
  error: '',
  peer: null as Account | null,
  messages: [] as DirectMessage[],
})

const orderedMessages = computed(() => [...state.messages].sort((a, b) => new Date(a.created_at).getTime() - new Date(b.created_at).getTime()))
const canSend = computed(() => content.value.trim().length > 0 && !state.sending && !!state.peer && peerId.value > 0)
const contactItems = computed(() => {
  const map = new Map<number, Account>()
  for (const user of social.vloggers) map.set(user.id, user)
  for (const user of social.followers) map.set(user.id, user)
  return [...map.values()].filter((user) => user.id !== myId.value)
})
const contactLoading = computed(() => social.vloggersLoading || social.followersLoading)
const contactError = computed(() => social.vloggersError || social.followersError)

function formatTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString([], { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

async function scrollToBottom() {
  await nextTick()
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}

async function loadChat() {
  if (!auth.isLoggedIn) {
    await router.push({ path: '/account', query: { redirect: route.fullPath } })
    return
  }
  if (!hasPeer.value) {
    await social.refreshMine()
    state.loading = false
    state.error = ''
    state.peer = null
    state.messages = []
    return
  }
  if (!Number.isFinite(peerId.value) || peerId.value <= 0) {
    state.error = '无效的用户 id'
    return
  }
  if (peerId.value === myId.value) {
    state.error = '请选择其他用户发送私信'
    return
  }

  state.loading = true
  state.error = ''
  try {
    const [peer, res] = await Promise.all([accountApi.findById(peerId.value), messageApi.listMessages(peerId.value)])
    state.peer = peer
    state.messages = res.messages ?? []
    await scrollToBottom()
  } catch (e) {
    state.error = e instanceof ApiError ? e.message : String(e)
    state.peer = null
    state.messages = []
  } finally {
    state.loading = false
  }
}

async function send() {
  const text = content.value.trim()
  if (!text || state.sending || !state.peer) return

  state.sending = true
  try {
    const msg = await messageApi.sendMessage(peerId.value, text)
    state.messages = [msg, ...state.messages]
    content.value = ''
    await scrollToBottom()
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  } finally {
    state.sending = false
  }
}

async function goPeerProfile() {
  if (state.peer) await router.push(`/u/${state.peer.id}`)
}

async function openChat(userId: number) {
  await router.push(`/messages/${userId}`)
}

watch(
  () => route.params.peerId,
  () => {
    state.peer = null
    state.messages = []
    content.value = ''
    void loadChat()
  },
)

onMounted(loadChat)
</script>

<template>
  <AppShell>
    <div class="page page--messages">
      <div class="page-inner" style="--page-max: 880px">
        <div class="chats" :class="{ 'has-peer': hasPeer }">
          <aside class="convo-list" aria-label="会话列表">
            <header class="list-head">
              <div class="list-head-main">
                <h1 class="page-title">私信</h1>
                <button
                  class="btn btn--ghost btn--icon"
                  type="button"
                  aria-label="刷新会话列表"
                  :disabled="contactLoading"
                  @click="social.refreshMine"
                >
                  <Icon name="refresh" :size="16" />
                </button>
              </div>
              <p class="page-sub">选择关注或粉丝里的用户开始聊天。</p>
            </header>

            <div class="list-body">
              <p v-if="contactLoading" class="list-hint">加载中…</p>
              <p v-else-if="contactError" class="list-hint bad">
                <Icon name="alert" :size="14" />
                <span>{{ contactError }}</span>
              </p>
              <div v-else-if="contactItems.length === 0" class="list-empty">
                <p class="list-empty-title">暂无可聊天用户</p>
                <p class="list-empty-text">关注别人或拥有粉丝后，可以从这里开始私信。</p>
              </div>
              <ul v-else class="convo-items">
                <li v-for="user in contactItems" :key="user.id">
                  <button
                    class="convo"
                    :class="{ on: hasPeer && user.id === peerId }"
                    type="button"
                    :aria-current="hasPeer && user.id === peerId ? 'true' : undefined"
                    @click="openChat(user.id)"
                  >
                    <UserAvatar :username="user.username" :id="user.id" :size="40" />
                    <span class="convo-name">@{{ user.username }}</span>
                  </button>
                </li>
              </ul>
            </div>
          </aside>

          <section class="convo-panel" aria-label="私信会话">
            <template v-if="hasPeer">
              <header class="chat-head">
                <button
                  class="btn btn--ghost btn--icon chat-back"
                  type="button"
                  aria-label="返回会话列表"
                  @click="router.back()"
                >
                  <Icon name="back" :size="18" />
                </button>
                <button
                  class="peer"
                  type="button"
                  :disabled="!state.peer"
                  :aria-label="`查看 @${state.peer?.username ?? ''} 的主页`"
                  @click="goPeerProfile"
                >
                  <UserAvatar :username="state.peer?.username ?? 'User'" :id="state.peer?.id ?? peerId" :size="40" />
                  <span class="peer-name">@{{ state.peer?.username ?? '加载中' }}</span>
                </button>
                <button class="btn btn--ghost chat-refresh" type="button" :disabled="state.loading" @click="loadChat">
                  <Icon name="refresh" :size="16" />
                  <span>刷新</span>
                </button>
              </header>

              <div ref="listEl" class="message-list">
                <p v-if="state.loading" class="chat-hint">加载中…</p>
                <p v-else-if="state.error" class="chat-hint bad">
                  <Icon name="alert" :size="14" />
                  <span>{{ state.error }}</span>
                </p>
                <div v-else-if="orderedMessages.length === 0" class="chat-empty">
                  <p class="chat-empty-title">开始聊天</p>
                  <p class="chat-empty-text">给 @{{ state.peer?.username ?? '对方' }} 发第一条消息。</p>
                </div>

                <div
                  v-for="msg in orderedMessages"
                  :key="msg.id"
                  class="bubble-row"
                  :class="{ mine: msg.from_id === myId }"
                >
                  <UserAvatar
                    v-if="msg.from_id !== myId"
                    :username="state.peer?.username ?? 'User'"
                    :id="state.peer?.id ?? msg.from_id"
                    :size="32"
                  />
                  <div class="bubble-wrap">
                    <div class="bubble">{{ msg.content }}</div>
                    <div class="bubble-time">{{ formatTime(msg.created_at) }}</div>
                  </div>
                </div>
              </div>

              <footer class="composer">
                <textarea
                  v-model="content"
                  class="composer-input"
                  aria-label="私信内容"
                  placeholder="输入私信内容"
                  :disabled="!!state.error || state.loading || state.sending"
                  @keydown.enter.exact.prevent="send"
                />
                <button class="btn btn--primary send-btn" type="button" :disabled="!canSend" @click="send">
                  <Icon name="send" :size="16" />
                  <span>{{ state.sending ? '发送中' : '发送' }}</span>
                </button>
              </footer>
            </template>

            <div v-else class="panel-empty">
              <Icon name="mail" :size="28" />
              <p class="panel-empty-title">选择一位用户开始聊天</p>
              <p class="panel-empty-text">关注与粉丝会出现在左侧列表里。</p>
            </div>
          </section>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.page--messages {
  overflow: hidden;
}

.page--messages .page-inner {
  height: 100%;
}

.chats {
  height: 100%;
  min-height: 0;
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
}

/* —— 左：会话列表 —— */
.convo-list {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border-right: 1px solid var(--line);
}

.list-head {
  padding: var(--sp-5) var(--sp-5) var(--sp-4);
}

.list-head-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
}

.list-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.list-hint {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-5);
  font-size: var(--text-small);
  color: var(--text-2);
}

.list-hint.bad {
  color: var(--danger);
}

.list-empty {
  display: grid;
  gap: var(--sp-2);
  padding: var(--sp-8) var(--sp-5);
}

.list-empty-title {
  font-size: var(--text-small);
  font-weight: 700;
}

.list-empty-text {
  font-size: var(--text-meta);
  color: var(--text-2);
}

.convo-items {
  list-style: none;
  margin: 0;
  padding: 0;
}

.convo {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  padding: var(--sp-3) var(--sp-5);
  text-align: left;
  border-bottom: 1px solid var(--line);
  transition: background var(--dur-quick) var(--ease-out);
}

.convo:hover {
  background: rgba(255, 255, 255, 0.04);
}

.convo.on {
  background: rgba(255, 255, 255, 0.04);
  box-shadow: inset 2px 0 0 var(--brand);
}

.convo-name {
  min-width: 0;
  font-size: var(--text-small);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* —— 右：会话 —— */
.convo-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.chat-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--line);
}

.chat-back {
  display: none;
}

.peer {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-1) var(--sp-2);
  border-radius: var(--radius-md);
  text-align: left;
  transition: background var(--dur-quick) var(--ease-out);
}

.peer:hover {
  background: rgba(255, 255, 255, 0.05);
}

.peer:disabled {
  opacity: 0.6;
  cursor: default;
}

.peer-name {
  min-width: 0;
  font-size: var(--text-small);
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-refresh {
  flex: none;
}

.message-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--sp-5);
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.chat-hint {
  margin: auto;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--text-small);
  color: var(--text-2);
}

.chat-hint.bad {
  color: var(--danger);
}

.chat-empty {
  margin: auto;
  display: grid;
  justify-items: center;
  gap: var(--sp-2);
  text-align: center;
}

.chat-empty-title {
  font-weight: 700;
}

.chat-empty-text {
  font-size: var(--text-meta);
  color: var(--text-2);
}

.bubble-row {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-3);
}

.bubble-row.mine {
  justify-content: flex-end;
}

.bubble-wrap {
  max-width: min(68%, 520px);
  display: grid;
  gap: var(--sp-1);
}

.bubble {
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--radius-md);
  background: var(--surface-2);
  border: 1px solid var(--line);
  font-size: var(--text-small);
  line-height: var(--leading-normal);
  white-space: pre-wrap;
  word-break: break-word;
}

.mine .bubble {
  background: var(--brand-dim);
  border-color: transparent;
}

.bubble-time {
  font-size: var(--text-caption);
  color: var(--text-3);
}

.mine .bubble-time {
  text-align: right;
}

.composer {
  display: flex;
  align-items: flex-end;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4) var(--sp-4);
  border-top: 1px solid var(--line);
}

.composer-input {
  flex: 1;
  min-height: 44px;
  max-height: 132px;
  resize: vertical;
}

.send-btn {
  flex: none;
}

.panel-empty {
  flex: 1;
  display: grid;
  place-items: center;
  align-content: center;
  gap: var(--sp-3);
  padding: var(--sp-8);
  text-align: center;
  color: var(--text-3);
}

.panel-empty-title {
  font-size: var(--text-small);
  font-weight: 600;
  color: var(--text-2);
}

.panel-empty-text {
  font-size: var(--text-meta);
}

/* —— 窄屏：单栏，进入会话时隐藏列表 —— */
@media (max-width: 767px) {
  .page--messages .page-inner {
    padding-bottom: calc(56px + env(safe-area-inset-bottom) + var(--sp-3));
  }

  .chats {
    grid-template-columns: minmax(0, 1fr);
  }

  .chats.has-peer .convo-list {
    display: none;
  }

  .chats:not(.has-peer) .convo-panel {
    display: none;
  }

  .chat-back {
    display: inline-flex;
  }

  .chat-head {
    padding: var(--sp-2) var(--sp-3);
  }

  .message-list {
    padding: var(--sp-4) var(--sp-3);
  }

  .composer {
    padding: var(--sp-3);
  }

  .bubble-wrap {
    max-width: 84%;
  }
}
</style>
