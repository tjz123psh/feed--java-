<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppShell from '../components/AppShell.vue'
import CommentDrawer from '../components/CommentDrawer.vue'
import Icon from '../components/Icon.vue'
import UserAvatar from '../components/UserAvatar.vue'
import type { FeedVideoItem } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useSocialStore } from '../stores/social'
import { useToastStore } from '../stores/toast'
import { useVideoFeed } from '../composables/useVideoFeed'
import { useVideoPlayer } from '../composables/useVideoPlayer'
import { useLikeFollow } from '../composables/useLikeFollow'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const social = useSocialStore()
const toast = useToastStore()

const { tab, currentState, loadRecommend, loadHot, loadFollowing, ensureTabLoaded, loadMoreIfNeeded } =
  useVideoFeed()
const scroller = ref<HTMLDivElement | null>(null)
const {
  muted,
  activeIndex,
  paused,
  videoMap,
  setVideoRef,
  scrollToIndex,
  onScroll,
  playActive,
  toggleMute,
  togglePlayPause,
} = useVideoPlayer(scroller)

async function needLogin() {
  toast.error('请先登录')
  await router.push({ path: '/account', query: { redirect: route.fullPath } })
}

const { likeBusy, followBusy, toggleLike, toggleFollow, share } = useLikeFollow(needLogin)

const tabs = [
  { key: 'recommend' as const, label: '推荐' },
  { key: 'following' as const, label: '关注' },
  { key: 'hot' as const, label: '点赞榜' },
]

const drawerVideo = ref<FeedVideoItem | null>(null)
const drawerOpen = ref(false)
function openComments(item: FeedVideoItem) {
  drawerVideo.value = item
  drawerOpen.value = true
}
function closeDrawer() {
  drawerOpen.value = false
  drawerVideo.value = null
}

/* —— 本地过滤（标题 / 作者）—— */
const searchOpen = ref(false)
const searchInput = ref<HTMLInputElement | null>(null)
const q = computed(() => (typeof route.query.q === 'string' ? route.query.q.trim().toLowerCase() : ''))
const filteredItems = computed(() => {
  const items = currentState.value.items
  if (!q.value) return items
  return items.filter(
    (v) => v.title.toLowerCase().includes(q.value) || v.author.username.toLowerCase().includes(q.value),
  )
})
function submitSearch(value: string) {
  void router.push({ path: '/', query: value.trim() ? { q: value.trim() } : {} })
}
function onSearchEnter(e: KeyboardEvent) {
  submitSearch((e.target as HTMLInputElement).value)
}
function clearSearch() {
  void router.push({ path: '/' })
}
function toggleSearch() {
  searchOpen.value = !searchOpen.value
  if (searchOpen.value) void nextTick(() => searchInput.value?.focus())
}

const hintsOpen = ref(false)

/* —— 封面兜底：封面 URL 无效时由元素自身的背景渐层兜住视觉 —— */
function fallbackBg(id: number) {
  const h = (id * 47) % 360
  return `linear-gradient(160deg, hsl(${h} 26% 14%), hsl(${(h + 40) % 360} 30% 8%))`
}
function ambientStyle(item: FeedVideoItem) {
  return { backgroundImage: `url(${item.cover_url}), ${fallbackBg(item.id)}` }
}

const activeItem = computed(() => filteredItems.value[activeIndex.value] ?? null)
const endReached = computed(
  () =>
    filteredItems.value.length > 0 &&
    !currentState.value.hasMore &&
    !currentState.value.loading &&
    activeIndex.value === filteredItems.value.length - 1,
)

watch(activeItem, async () => {
  await nextTick()
  await playActive(activeItem.value?.id)
  await loadMoreIfNeeded(activeIndex.value)
})

watch(
  () => tab.value,
  async () => {
    activeIndex.value = 0
    videoMap.clear()
    if (scroller.value) scroller.value.scrollTop = 0
    await ensureTabLoaded()
    await nextTick()
    await playActive(activeItem.value?.id)
  },
)

watch(
  () => q.value,
  async () => {
    activeIndex.value = 0
    if (scroller.value) scroller.value.scrollTop = 0
    await nextTick()
    await playActive(activeItem.value?.id)
  },
)

watch(
  () => filteredItems.value.length,
  (len) => {
    if (len === 0) activeIndex.value = 0
    else if (activeIndex.value > len - 1) activeIndex.value = len - 1
  },
)

watch(
  () => auth.isLoggedIn,
  async (v) => {
    if (tab.value === 'following' && v) await loadFollowing(true)
    if (tab.value === 'following' && !v) {
      activeIndex.value = 0
      await nextTick()
      await playActive(activeItem.value?.id)
    }
  },
)

function retry() {
  if (tab.value === 'hot') void loadHot(true)
  else if (tab.value === 'following') void loadFollowing(true)
  else void loadRecommend(true)
}

async function onKeydown(e: KeyboardEvent) {
  const t = e.target as HTMLElement | null
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) {
    if (e.key === 'Escape' && searchOpen.value) searchOpen.value = false
    return
  }
  if (drawerOpen.value) {
    if (e.key === 'Escape') closeDrawer()
    return
  }
  if (e.key === 'Escape') {
    hintsOpen.value = false
    searchOpen.value = false
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    scrollToIndex(activeIndex.value + 1, filteredItems.value.length)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    scrollToIndex(activeIndex.value - 1, filteredItems.value.length)
  } else if (e.key === ' ') {
    e.preventDefault()
    togglePlayPause(activeItem.value?.id)
  } else if (e.key.toLowerCase() === 'm') {
    e.preventDefault()
    toggleMute()
  } else if (e.key.toLowerCase() === 'c') {
    if (activeItem.value) {
      e.preventDefault()
      openComments(activeItem.value)
    }
  } else if (e.key === '/') {
    e.preventDefault()
    if (!searchOpen.value) toggleSearch()
    else searchInput.value?.focus()
  }
}

onMounted(async () => {
  await ensureTabLoaded()
  await nextTick()
  await playActive(activeItem.value?.id)
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <AppShell>
    <div class="feed">
      <header class="feed-top">
        <div class="tabs" role="tablist" aria-label="内容流">
          <button
            v-for="t in tabs"
            :key="t.key"
            class="tab"
            :class="{ on: tab === t.key }"
            role="tab"
            :aria-selected="tab === t.key"
            type="button"
            @click="tab = t.key"
          >
            {{ t.label }}
          </button>
        </div>
        <div class="tools">
          <button class="tool" type="button" aria-label="过滤内容" @click="toggleSearch">
            <Icon name="search" :size="18" />
          </button>
          <button
            class="tool"
            type="button"
            :aria-label="muted ? '取消静音' : '静音'"
            @click="toggleMute"
          >
            <Icon :name="muted ? 'mute' : 'volume'" :size="18" />
          </button>
          <button class="tool tool--hints" type="button" aria-label="快捷键" @click="hintsOpen = !hintsOpen">
            <Icon name="clipboard" :size="18" />
          </button>
        </div>
      </header>

      <Transition name="drop">
        <div v-if="searchOpen" class="searchbar">
          <Icon name="search" :size="16" class="searchbar-icon" />
          <input
            ref="searchInput"
            class="searchbar-input"
            type="text"
            placeholder="按标题或作者过滤（本地）"
            :value="typeof route.query.q === 'string' ? route.query.q : ''"
            @keydown.enter="onSearchEnter"
          />
          <button v-if="q" class="searchbar-btn" type="button" @click="clearSearch">清除</button>
          <button class="searchbar-btn" type="button" aria-label="收起搜索" @click="searchOpen = false">
            <Icon name="close" :size="14" />
          </button>
        </div>
      </Transition>

      <div v-if="hintsOpen" class="hints" role="dialog" aria-label="快捷键">
        <div class="hints-row"><span class="kbd">↑</span><span class="kbd">↓</span><span class="hints-label">上一条 / 下一条</span></div>
        <div class="hints-row"><span class="kbd">空格</span><span class="hints-label">暂停 / 播放</span></div>
        <div class="hints-row"><span class="kbd">M</span><span class="hints-label">静音切换</span></div>
        <div class="hints-row"><span class="kbd">C</span><span class="hints-label">打开评论</span></div>
        <div class="hints-row"><span class="kbd">/</span><span class="hints-label">过滤内容</span></div>
      </div>

      <div ref="scroller" class="feed-scroll" @scroll="onScroll">
        <div v-if="currentState.loading && currentState.items.length === 0" class="slide">
          <div class="stage-skeleton">
            <div class="skeleton stage-media" />
            <div class="stage-meta">
              <div class="skeleton" style="width: 150px; height: 16px" />
              <div class="skeleton" style="width: 280px; height: 22px" />
              <div class="skeleton" style="width: 210px; height: 14px" />
            </div>
          </div>
        </div>

        <div v-else-if="currentState.error && currentState.items.length === 0" class="slide">
          <div class="stage-empty">
            <Icon name="alert" :size="28" />
            <p>{{ currentState.error }}</p>
            <button class="btn" type="button" @click="retry">重试</button>
          </div>
        </div>

        <div v-else-if="filteredItems.length === 0" class="slide">
          <div class="stage-empty">
            <Icon name="film" :size="28" />
            <p v-if="q">没有匹配「{{ route.query.q }}」的内容</p>
            <p v-else-if="tab === 'following'">关注一些创作者，这里就会出现他们的视频</p>
            <p v-else>这里还没有内容</p>
            <button v-if="q" class="btn" type="button" @click="clearSearch">清除过滤</button>
          </div>
        </div>

        <template v-else>
          <section
            v-for="(item, idx) in filteredItems"
            :key="`${tab}-${item.id}`"
            class="slide"
            :class="{ active: idx === activeIndex }"
            :aria-hidden="idx !== activeIndex"
          >
          <div class="stage" @click="togglePlayPause(item.id)" @dblclick.prevent="toggleLike(item)">
            <div
              v-if="Math.abs(idx - activeIndex) <= 1"
              class="ambient"
              :class="{ on: idx === activeIndex }"
              :style="ambientStyle(item)"
            />

            <video
              v-if="Math.abs(idx - activeIndex) <= 1"
              :ref="(el) => setVideoRef(item.id, el as HTMLVideoElement | null)"
              class="media"
              :src="item.play_url"
              :poster="item.cover_url"
              playsinline
              preload="metadata"
              loop
              muted
            />
            <img
              v-else
              class="media media--poster"
              :src="item.cover_url"
              alt=""
              loading="lazy"
              :style="{ backgroundImage: fallbackBg(item.id) }"
            />

            <div v-if="idx === activeIndex && paused" class="paused-badge">
              <Icon name="play" :size="26" filled />
            </div>

            <div class="scrim" />

            <div class="meta">
              <RouterLink class="author" :to="`/u/${item.author.id}`" @click.stop>
                <UserAvatar :username="item.author.username" :id="item.author.id" :size="36" />
                <span class="author-name">@{{ item.author.username }}</span>
              </RouterLink>
              <h1 class="title">{{ item.title }}</h1>
              <p v-if="item.description" class="desc">{{ item.description }}</p>
            </div>

            <div class="actions">
              <button
                class="action"
                :class="{ liked: item.is_liked }"
                type="button"
                :disabled="!!likeBusy[String(item.id)]"
                :aria-label="item.is_liked ? '取消点赞' : '点赞'"
                @click.stop="toggleLike(item)"
              >
                <Icon name="heart" :size="22" :filled="item.is_liked" />
                <span>{{ item.likes_count }}</span>
              </button>
              <button class="action" type="button" aria-label="评论" @click.stop="openComments(item)">
                <Icon name="comment" :size="22" />
                <span>评论</span>
              </button>
              <button
                v-if="!auth.claims?.account_id || auth.claims.account_id !== item.author.id"
                class="action"
                :class="{ following: social.isFollowing(item.author.id) }"
                type="button"
                :disabled="!!followBusy[String(item.author.id)]"
                :aria-label="social.isFollowing(item.author.id) ? '取消关注' : '关注'"
                @click.stop="toggleFollow(item.author.id)"
              >
                <Icon :name="social.isFollowing(item.author.id) ? 'check' : 'userPlus'" :size="22" />
                <span>{{ social.isFollowing(item.author.id) ? '已关注' : '关注' }}</span>
              </button>
              <button class="action" type="button" aria-label="分享" @click.stop="share(item)">
                <Icon name="share" :size="22" />
                <span>分享</span>
              </button>
            </div>
          </div>
        </section>
        </template>
      </div>

      <div v-if="endReached" class="end-hint">已经到底了</div>

      <CommentDrawer v-if="drawerOpen" :video="drawerVideo" @close="closeDrawer" />
    </div>
  </AppShell>
</template>

<style scoped>
.feed {
  position: relative;
  height: 100%;
  overflow: hidden;
}

/* —— 顶部浮层 —— */
.feed-top {
  position: absolute;
  top: var(--sp-4);
  left: 0;
  right: 0;
  z-index: 5;
  display: flex;
  justify-content: center;
  pointer-events: none;
}

.tabs {
  pointer-events: auto;
  display: flex;
  gap: 2px;
  padding: 4px;
  border-radius: var(--radius-full);
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid var(--line);
  backdrop-filter: blur(16px);
}

.tab {
  padding: 7px 16px;
  border-radius: var(--radius-full);
  font-size: var(--text-small);
  font-weight: 600;
  color: var(--text-2);
  transition:
    background var(--dur-quick) var(--ease-out),
    color var(--dur-quick) var(--ease-out);
}

.tab:hover {
  color: var(--text);
}

.tab.on {
  background: rgba(255, 255, 255, 0.92);
  color: var(--ink);
}

.tools {
  pointer-events: auto;
  position: absolute;
  right: var(--sp-5);
  top: 0;
  display: flex;
  gap: var(--sp-2);
}

.tool {
  width: 38px;
  height: 38px;
  border-radius: var(--radius-full);
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid var(--line);
  backdrop-filter: blur(16px);
  color: var(--text-2);
  transition: color var(--dur-quick) var(--ease-out);
}

.tool:hover {
  color: var(--text);
}

/* —— 搜索条 / 快捷键 —— */
.searchbar {
  position: absolute;
  top: 64px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 6;
  width: min(520px, calc(100% - 32px));
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 8px 10px;
  border-radius: var(--radius-md);
  background: rgba(11, 14, 19, 0.94);
  border: 1px solid var(--line-strong);
  backdrop-filter: blur(20px);
}

.searchbar-icon {
  color: var(--text-3);
  flex: none;
}

.searchbar-input {
  flex: 1;
  background: transparent;
  border: 0;
  padding: 6px 4px;
}

.searchbar-input:focus {
  border-color: transparent;
  box-shadow: none;
}

.searchbar-btn {
  flex: none;
  display: grid;
  place-items: center;
  height: 28px;
  min-width: 28px;
  padding: 0 6px;
  border-radius: var(--radius-sm);
  color: var(--text-2);
  font-size: var(--text-caption);
  transition: color var(--dur-quick) var(--ease-out);
}

.searchbar-btn:hover {
  color: var(--text);
}

.drop-enter-active,
.drop-leave-active {
  transition:
    opacity var(--dur-quick) var(--ease-out),
    transform var(--dur-quick) var(--ease-out);
}

.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translate(-50%, -6px);
}

.hints {
  position: absolute;
  top: 64px;
  right: var(--sp-5);
  z-index: 6;
  display: grid;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-4);
  border-radius: var(--radius-md);
  background: rgba(11, 14, 19, 0.94);
  border: 1px solid var(--line-strong);
  backdrop-filter: blur(20px);
  font-size: var(--text-small);
}

.hints-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.hints-label {
  color: var(--text-2);
}

/* —— 滚动舞台 —— */
.feed-scroll {
  height: 100%;
  overflow-y: auto;
  scroll-snap-type: y mandatory;
  scrollbar-width: none;
}

.feed-scroll::-webkit-scrollbar {
  display: none;
}

.slide {
  height: 100%;
  scroll-snap-align: start;
  position: relative;
}

.stage {
  position: absolute;
  inset: 0;
  cursor: pointer;
  overflow: hidden;
}

/* 环境辉光：内容驱动的背景画布 */
.ambient {
  position: absolute;
  inset: -12%;
  background-size: cover;
  background-position: center;
  filter: blur(64px) saturate(1.45) brightness(0.5);
  opacity: 0;
  transform: scale(1.02);
  transition: opacity var(--dur-panel) var(--ease-out);
  pointer-events: none;
}

.ambient.on {
  opacity: 0.55;
  animation: ambient-breathe 9s ease-in-out infinite;
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

.media--fallback {
  background-size: cover;
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

/* —— 元数据与动作 —— */
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

/* —— 骨架 / 空态 / 到底 —— */
.stage-skeleton {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
}

.stage-media {
  width: min(72%, 760px);
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-md);
}

.stage-meta {
  position: absolute;
  left: clamp(16px, 4vw, 48px);
  bottom: clamp(28px, 7vh, 56px);
  display: grid;
  gap: var(--sp-3);
}

.stage-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  align-content: center;
  gap: var(--sp-4);
  color: var(--text-2);
}

.stage-empty p {
  font-size: var(--text-small);
}

.end-hint {
  position: absolute;
  left: 50%;
  bottom: var(--sp-6);
  transform: translateX(-50%);
  padding: 6px 14px;
  border-radius: var(--radius-full);
  background: rgba(255, 255, 255, 0.92);
  color: var(--ink);
  font-size: var(--text-caption);
  font-weight: 600;
}

/* —— 窄屏重组 —— */
@media (max-width: 767px) {
  .feed-top {
    top: var(--sp-3);
  }

  .tools {
    right: var(--sp-3);
  }

  .tool {
    width: 36px;
    height: 36px;
  }

  .tab {
    padding: 6px 11px;
    font-size: var(--text-meta);
  }

  /* 触屏没有键盘，快捷键入口与浮层在窄屏隐藏 */
  .tool--hints,
  .hints {
    display: none;
  }

  .searchbar {
    top: 56px;
  }

  .hints {
    top: 56px;
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

  .end-hint {
    bottom: calc(72px + env(safe-area-inset-bottom));
  }
}
</style>
