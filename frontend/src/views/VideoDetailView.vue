<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppShell from '../components/AppShell.vue'
import CommentDrawer from '../components/CommentDrawer.vue'
import Icon from '../components/Icon.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { ApiError } from '../api/client'
import * as likeApi from '../api/like'
import type { Video } from '../api/types'
import * as videoApi from '../api/video'
import { useAuthStore } from '../stores/auth'
import { useSocialStore } from '../stores/social'
import { useToastStore } from '../stores/toast'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const social = useSocialStore()
const toast = useToastStore()

const id = computed(() => Number(route.params.id))

const state = reactive({
  loading: false,
  error: '',
  video: null as Video | null,
  isLiked: null as boolean | null,
  busy: false,
})

const muted = ref(true)
const playing = ref(false)
const videoEl = ref<HTMLVideoElement | null>(null)
const commentsOpen = ref(false)

async function needLogin() {
  toast.error('请先登录')
  await router.push({ path: '/account', query: { redirect: route.fullPath } })
}

async function loadVideo() {
  if (!Number.isFinite(id.value) || id.value <= 0) {
    state.error = '无效的 video id'
    return
  }
  state.loading = true
  state.error = ''
  try {
    state.video = await videoApi.getDetail(id.value)
  } catch (e) {
    state.error = e instanceof ApiError ? e.message : String(e)
  } finally {
    state.loading = false
  }
}

async function loadIsLiked() {
  if (!auth.isLoggedIn) {
    state.isLiked = null
    return
  }
  try {
    const res = await likeApi.isLiked(id.value)
    state.isLiked = res.is_liked
  } catch {
    state.isLiked = null
  }
}

async function play() {
  if (!videoEl.value) return
  videoEl.value.muted = muted.value
  try {
    await videoEl.value.play()
  } catch {
    // 自动播放被拦截时保留暂停态，等待用户手势
  }
}

function toggleMute() {
  muted.value = !muted.value
  if (videoEl.value) videoEl.value.muted = muted.value
}

function togglePlayPause() {
  const v = videoEl.value
  if (!v) return
  if (v.paused) void v.play()
  else v.pause()
}

async function toggleLike() {
  if (!state.video) return
  if (!auth.isLoggedIn) return needLogin()
  if (state.busy) return

  state.busy = true
  try {
    if (state.isLiked) {
      await likeApi.unlike(id.value)
      state.isLiked = false
      state.video.likes_count = Math.max(0, state.video.likes_count - 1)
    } else {
      await likeApi.like(id.value)
      state.isLiked = true
      state.video.likes_count += 1
    }
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  } finally {
    state.busy = false
  }
}

async function toggleFollow() {
  if (!state.video) return
  if (!auth.isLoggedIn) return needLogin()
  if (state.busy) return
  if (auth.claims?.account_id && auth.claims.account_id === state.video.author_id) return

  state.busy = true
  try {
    if (social.isFollowing(state.video.author_id)) {
      await social.unfollow(state.video.author_id)
      toast.info('已取关')
    } else {
      await social.follow(state.video.author_id)
      toast.success('已关注')
    }
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  } finally {
    state.busy = false
  }
}

async function share() {
  if (!state.video) return
  const url = `${location.origin}/video/${state.video.id}`
  try {
    await navigator.clipboard.writeText(url)
    toast.success('链接已复制')
  } catch {
    window.prompt('复制链接', url)
  }
}

function coverFallback(id: number) {
  const h = (id * 47) % 360
  return `linear-gradient(160deg, hsl(${h} 26% 14%), hsl(${(h + 40) % 360} 30% 8%))`
}

function formatDate(raw: string) {
  const d = new Date(raw)
  return Number.isNaN(d.getTime()) ? raw : d.toLocaleString('zh-CN', { hour12: false })
}

function closeComments() {
  commentsOpen.value = false
}

watch(
  () => id.value,
  async () => {
    commentsOpen.value = false
    await loadVideo()
    await loadIsLiked()
    await nextTick()
    await play()
  },
)

watch(
  () => auth.isLoggedIn,
  async () => {
    await loadIsLiked()
  },
)

onMounted(async () => {
  await loadVideo()
  await loadIsLiked()
  await nextTick()
  await play()
})
</script>

<template>
  <AppShell>
    <div class="detail">
      <div class="topbar">
        <button class="pill" type="button" aria-label="返回上一页" @click="router.back()">
          <Icon name="back" :size="16" />
          返回
        </button>
        <div class="topbar-right">
          <button
            class="pill"
            type="button"
            :aria-label="muted ? '取消静音' : '静音'"
            @click="toggleMute"
          >
            <Icon :name="muted ? 'mute' : 'volume'" :size="16" />
          </button>
        </div>
      </div>

      <div v-if="state.loading && !state.video" class="center">
        <div class="skeleton media-skeleton" />
      </div>

      <div v-else-if="state.error && !state.video" class="center">
        <div class="center-box">
          <Icon name="alert" :size="26" />
          <p>{{ state.error }}</p>
          <button class="btn" type="button" @click="loadVideo">重试</button>
        </div>
      </div>

      <div v-else-if="state.video" class="stage" @click="togglePlayPause" @dblclick.prevent="toggleLike">
        <div
          class="ambient"
          :style="{ backgroundImage: `url(${state.video.cover_url}), ${coverFallback(state.video.id)}` }"
          aria-hidden="true"
        />

        <video
          ref="videoEl"
          class="media"
          :src="state.video.play_url"
          :poster="state.video.cover_url"
          playsinline
          preload="metadata"
          loop
          @play="playing = true"
          @pause="playing = false"
        />

        <div v-if="!playing" class="paused-badge">
          <Icon name="play" :size="26" filled />
        </div>

        <div class="scrim" />

        <div class="meta">
          <RouterLink class="author" :to="`/u/${state.video.author_id}`" @click.stop>
            <UserAvatar :username="state.video.username" :id="state.video.author_id" :size="36" />
            <span class="author-name">@{{ state.video.username }}</span>
          </RouterLink>
          <h1 class="title">{{ state.video.title }}</h1>
          <p v-if="state.video.description" class="desc">{{ state.video.description }}</p>
          <p class="time">发布于 {{ formatDate(state.video.create_time) }}</p>
        </div>

        <div class="actions">
          <button
            class="action"
            :class="{ liked: !!state.isLiked }"
            type="button"
            :disabled="state.busy"
            :aria-label="state.isLiked ? '取消点赞' : '点赞'"
            @click.stop="toggleLike"
          >
            <Icon name="heart" :size="22" :filled="!!state.isLiked" />
            <span>{{ state.video.likes_count }}</span>
          </button>
          <button class="action" type="button" aria-label="评论" @click.stop="commentsOpen = true">
            <Icon name="comment" :size="22" />
            <span>评论</span>
          </button>
          <button
            v-if="!auth.claims?.account_id || auth.claims.account_id !== state.video.author_id"
            class="action"
            :class="{ following: social.isFollowing(state.video.author_id) }"
            type="button"
            :disabled="state.busy"
            :aria-label="social.isFollowing(state.video.author_id) ? '取消关注' : '关注'"
            @click.stop="toggleFollow"
          >
            <Icon :name="social.isFollowing(state.video.author_id) ? 'check' : 'userPlus'" :size="22" />
            <span>{{ social.isFollowing(state.video.author_id) ? '已关注' : '关注' }}</span>
          </button>
          <button class="action" type="button" aria-label="分享" @click.stop="share">
            <Icon name="share" :size="22" />
            <span>分享</span>
          </button>
        </div>
      </div>

      <CommentDrawer v-if="commentsOpen" :video="state.video" @close="closeComments" />
    </div>
  </AppShell>
</template>

<style scoped>
.detail {
  position: relative;
  height: 100%;
  overflow: hidden;
}

.topbar {
  position: absolute;
  top: var(--sp-4);
  left: var(--sp-5);
  right: var(--sp-5);
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  pointer-events: none;
}

.pill {
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  height: 36px;
  padding: 0 var(--sp-3);
  border-radius: var(--radius-full);
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid var(--line);
  backdrop-filter: blur(16px);
  color: var(--text-2);
  font-size: var(--text-small);
  font-weight: 600;
  transition: color var(--dur-quick) var(--ease-out);
}

.pill:hover {
  color: var(--text);
}

.topbar-right {
  display: flex;
  gap: var(--sp-2);
}

.center {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
}

.media-skeleton {
  width: min(72%, 760px);
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-md);
}

.center-box {
  display: grid;
  justify-items: center;
  gap: var(--sp-3);
  color: var(--text-2);
  font-size: var(--text-small);
}

/* 舞台：与 HomeView 保持一致（env 辉光 + contain 视频 + 底部渐层） */
.stage {
  position: absolute;
  inset: 0;
  cursor: pointer;
  overflow: hidden;
}

.ambient {
  position: absolute;
  inset: -12%;
  background-size: cover;
  background-position: center;
  filter: blur(64px) saturate(1.45) brightness(0.5);
  opacity: 0.55;
  animation: ambient-breathe 9s ease-in-out infinite;
  pointer-events: none;
}

@keyframes ambient-breathe {
  0%,
  100% {
    transform: scale(1.02);
  }
  50% {
    transform: scale(1.08);
  }
}

.media {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.paused-badge {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 64px;
  height: 64px;
  border-radius: var(--radius-full);
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.45);
  border: 1px solid var(--line-strong);
  color: rgba(255, 255, 255, 0.92);
  pointer-events: none;
}

.scrim {
  position: absolute;
  inset: auto 0 0 0;
  height: 46%;
  background: linear-gradient(to top, rgba(0, 0, 0, 0.74), transparent);
  pointer-events: none;
}

.meta {
  position: absolute;
  left: clamp(16px, 4vw, 48px);
  bottom: clamp(28px, 7vh, 56px);
  max-width: min(58ch, 56%);
  display: grid;
  gap: var(--sp-2);
}

.author {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  width: fit-content;
}

.author-name {
  font-size: var(--text-small);
  font-weight: 700;
}

.title {
  font-size: var(--text-heading);
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: 1.25;
}

.desc {
  font-size: var(--text-small);
  line-height: var(--leading-normal);
  color: rgba(255, 255, 255, 0.78);
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.time {
  font-size: var(--text-caption);
  color: rgba(255, 255, 255, 0.55);
}

.actions {
  position: absolute;
  right: clamp(12px, 3vw, 40px);
  bottom: clamp(28px, 7vh, 56px);
  display: grid;
  gap: var(--sp-2);
}

.action {
  display: grid;
  justify-items: center;
  gap: 4px;
  width: 56px;
  padding: 8px 0;
  border-radius: var(--radius-md);
  color: var(--text);
  font-size: var(--text-micro);
  line-height: 1.2;
  transition: background var(--dur-quick) var(--ease-out);
}

.action:hover {
  background: rgba(255, 255, 255, 0.1);
}

.action:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.action.liked {
  color: var(--brand);
}

.action.liked :deep(.icon) {
  animation: like-pop var(--dur-quick) var(--ease-out);
}

@keyframes like-pop {
  0% {
    transform: scale(1);
  }
  45% {
    transform: scale(1.28);
  }
  100% {
    transform: scale(1);
  }
}

.action.following {
  color: var(--text-2);
}

@media (max-width: 767px) {
  .topbar {
    top: var(--sp-3);
    left: var(--sp-3);
    right: var(--sp-3);
  }

  .meta {
    left: var(--sp-4);
    right: 84px;
    bottom: calc(72px + env(safe-area-inset-bottom));
    max-width: none;
  }

  .title {
    font-size: var(--text-title);
  }

  .actions {
    right: var(--sp-3);
    bottom: calc(76px + env(safe-area-inset-bottom));
    gap: 0;
  }

  .action {
    width: 48px;
    padding: 6px 0;
  }
}
</style>
