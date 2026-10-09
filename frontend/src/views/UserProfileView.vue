<script setup lang="ts">
import { computed, onMounted, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppShell from '../components/AppShell.vue'
import Icon from '../components/Icon.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { ApiError } from '../api/client'
import * as accountApi from '../api/account'
import * as socialApi from '../api/social'
import type { Account, Video } from '../api/types'
import * as videoApi from '../api/video'
import { useAuthStore } from '../stores/auth'
import { useSocialStore } from '../stores/social'
import { useToastStore } from '../stores/toast'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const social = useSocialStore()
const toast = useToastStore()

const userId = computed(() => Number(route.params.id))
const myId = computed(() => auth.claims?.account_id ?? 0)
const isMe = computed(() => myId.value > 0 && myId.value === userId.value)

const state = reactive({
  loading: false,
  error: '',
  user: null as Account | null,
  videos: [] as Video[],
  followers: [] as Account[],
  vloggers: [] as Account[],
  socialLoading: false,
  socialError: '',
})

const isFollowing = computed(() => (auth.isLoggedIn ? social.isFollowing(userId.value) : false))

async function loadProfile() {
  if (!Number.isFinite(userId.value) || userId.value <= 0) {
    state.error = '无效的用户 id'
    return
  }

  state.loading = true
  state.error = ''
  try {
    const [u, vids] = await Promise.all([accountApi.findById(userId.value), videoApi.listByAuthorId(userId.value)])
    state.user = u
    state.videos = vids
  } catch (e) {
    state.error = e instanceof ApiError ? e.message : String(e)
    state.user = null
    state.videos = []
  } finally {
    state.loading = false
  }

  await loadSocialCounts()
}

async function loadSocialCounts() {
  state.socialError = ''
  state.followers = []
  state.vloggers = []

  if (!auth.isLoggedIn) return
  if (!Number.isFinite(userId.value) || userId.value <= 0) return

  state.socialLoading = true
  try {
    const [followersRes, vloggersRes] = await Promise.all([
      socialApi.getAllFollowers(userId.value),
      socialApi.getAllVloggers(userId.value),
    ])
    state.followers = followersRes.followers
    state.vloggers = vloggersRes.vloggers
  } catch (e) {
    state.socialError = e instanceof ApiError ? e.message : String(e)
  } finally {
    state.socialLoading = false
  }
}

async function toggleFollow() {
  if (isMe.value) return
  if (!auth.isLoggedIn) {
    toast.error('请先登录')
    await router.push('/account')
    return
  }

  try {
    if (isFollowing.value) {
      await social.unfollow(userId.value)
      toast.info('已取关')
    } else {
      await social.follow(userId.value)
      toast.success('已关注')
    }
    await loadSocialCounts()
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  }
}

async function goMessage() {
  if (isMe.value) return
  if (!auth.isLoggedIn) {
    toast.error('请先登录')
    await router.push({ path: '/account', query: { redirect: route.fullPath } })
    return
  }
  await router.push(`/messages/${userId.value}`)
}

type ListTab = 'followers' | 'following'
const drawer = reactive({
  open: false,
  tab: 'followers' as ListTab,
})

function openFollowers() {
  drawer.tab = 'followers'
  drawer.open = true
}

function openFollowing() {
  drawer.tab = 'following'
  drawer.open = true
}

function closeDrawer() {
  drawer.open = false
}

const listTitle = computed(() => (drawer.tab === 'followers' ? '粉丝' : '关注'))
const listItems = computed(() => (drawer.tab === 'followers' ? state.followers : state.vloggers))

async function goUser(id: number) {
  drawer.open = false
  await router.push(`/u/${id}`)
}

async function goVideo(videoId: number) {
  await router.push(`/video/${videoId}`)
}

function coverFallback(id: number) {
  const h = (id * 47) % 360
  return `linear-gradient(160deg, hsl(${h} 26% 14%), hsl(${(h + 40) % 360} 30% 8%))`
}

function formatDate(raw: string) {
  const d = new Date(raw)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString('zh-CN')
}

watch(
  () => route.params.id,
  async () => {
    drawer.open = false
    await loadProfile()
  },
)

watch(
  () => auth.isLoggedIn,
  async () => {
    await loadSocialCounts()
  },
)

onMounted(loadProfile)
</script>

<template>
  <AppShell>
    <div class="page">
      <div class="page-inner" style="--page-max: 880px">
        <header class="profile">
          <div class="who">
            <UserAvatar :username="state.user?.username ?? 'User'" :id="state.user?.id ?? userId" :size="72" />
            <div class="who-text">
              <h1 class="who-name">@{{ state.user?.username ?? '…' }}</h1>
              <div v-if="state.loading" class="skeleton" style="width: 120px; height: 14px" />
            </div>
          </div>

          <div class="who-actions">
            <template v-if="state.user && !state.loading">
              <template v-if="isMe">
                <button class="btn" type="button" @click="router.push('/account')">我的账号</button>
              </template>
              <template v-else>
                <button class="btn" type="button" @click="goMessage">
                  <Icon name="mail" :size="16" />
                  私信
                </button>
                <button
                  class="btn"
                  :class="isFollowing ? '' : 'btn--primary'"
                  type="button"
                  @click="toggleFollow"
                >
                  <Icon :name="isFollowing ? 'check' : 'userPlus'" :size="16" />
                  {{ isFollowing ? '已关注' : '关注' }}
                </button>
              </template>
            </template>
          </div>
        </header>

        <div v-if="state.error && !state.user" class="hint">
          <Icon name="alert" :size="20" />
          <p>{{ state.error }}</p>
          <button class="btn" type="button" @click="loadProfile">重试</button>
        </div>

        <div v-else class="stats">
          <button class="stat" type="button" :disabled="!auth.isLoggedIn || state.socialLoading" @click="openFollowers">
            <span class="stat-num">{{ auth.isLoggedIn ? (state.socialLoading ? '…' : state.followers.length) : '—' }}</span>
            <span class="stat-label">粉丝</span>
          </button>
          <span class="stat-sep" aria-hidden="true" />
          <button class="stat" type="button" :disabled="!auth.isLoggedIn || state.socialLoading" @click="openFollowing">
            <span class="stat-num">{{ auth.isLoggedIn ? (state.socialLoading ? '…' : state.vloggers.length) : '—' }}</span>
            <span class="stat-label">关注</span>
          </button>
          <span class="stat-sep" aria-hidden="true" />
          <div class="stat static">
            <span class="stat-num">{{ state.videos.length }}</span>
            <span class="stat-label">作品</span>
          </div>
          <span v-if="!auth.isLoggedIn" class="stat-hint">登录后可查看粉丝与关注</span>
          <span v-else-if="state.socialError" class="stat-hint bad">{{ state.socialError }}</span>
        </div>

        <section class="works">
          <h2 class="works-title">作品</h2>

          <div v-if="state.loading && state.videos.length === 0" class="grid">
            <div v-for="i in 4" :key="i" class="grid-skeleton">
              <div class="skeleton grid-skeleton-cover" />
              <div class="skeleton" style="width: 70%; height: 15px" />
            </div>
          </div>

          <div v-else-if="state.videos.length === 0" class="empty">
            <Icon name="film" :size="22" />
            <p>{{ isMe ? '还没有发布作品，去发布第一条吧' : 'TA 还没有发布作品' }}</p>
            <RouterLink v-if="isMe" class="btn btn--primary" to="/video">去发布</RouterLink>
          </div>

          <div v-else class="grid">
            <button v-for="v in state.videos" :key="v.id" class="work" type="button" @click="goVideo(v.id)">
              <img
                class="work-cover"
                :src="v.cover_url"
                alt=""
                loading="lazy"
                :style="{ backgroundImage: coverFallback(v.id) }"
              />
              <span class="work-title">{{ v.title }}</span>
              <span class="work-sub">
                <Icon name="heart" :size="14" />
                {{ v.likes_count }}
                <template v-if="formatDate(v.create_time)"> · {{ formatDate(v.create_time) }}</template>
              </span>
            </button>
          </div>
        </section>
      </div>

      <div v-if="drawer.open" class="backdrop" @click.self="closeDrawer">
        <section class="panel" role="dialog" aria-modal="true" :aria-label="listTitle">
          <header class="panel-head">
            <h2 class="panel-title">{{ listTitle }}</h2>
            <button class="btn btn--icon btn--ghost" type="button" aria-label="关闭" @click="closeDrawer">
              <Icon name="close" :size="18" />
            </button>
          </header>
          <div class="panel-body">
            <div v-if="state.socialLoading" class="panel-hint">加载中…</div>
            <div v-else-if="state.socialError" class="panel-hint bad">{{ state.socialError }}</div>
            <div v-else-if="listItems.length === 0" class="panel-hint">还没有人</div>

            <button v-for="u in listItems" :key="u.id" class="user-row" type="button" @click="goUser(u.id)">
              <UserAvatar :username="u.username" :id="u.id" :size="38" />
              <span class="user-name">@{{ u.username }}</span>
              <Icon name="chevronDown" :size="16" class="user-arrow" />
            </button>
          </div>
        </section>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.profile {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}

.who {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  min-width: 0;
}

.who-name {
  font-size: var(--text-heading);
  font-weight: 800;
  letter-spacing: -0.01em;
}

.who-actions {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}

.stats {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-5);
  flex-wrap: wrap;
}

.stat {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--radius-sm);
  transition: background var(--dur-quick) var(--ease-out);
}

.stat:hover:not(.static) {
  background: rgba(255, 255, 255, 0.06);
}

.stat:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.stat-num {
  font-size: var(--text-lead);
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: var(--text-meta);
  color: var(--text-2);
}

.stat-sep {
  width: 1px;
  height: 18px;
  background: var(--line-strong);
}

.stat-hint {
  font-size: var(--text-meta);
  color: var(--text-3);
  margin-left: var(--sp-2);
}

.stat-hint.bad {
  color: var(--danger);
}

.hint {
  display: grid;
  justify-items: center;
  gap: var(--sp-3);
  padding: var(--sp-10) 0;
  color: var(--text-2);
  font-size: var(--text-small);
}

.works {
  margin-top: var(--sp-8);
}

.works-title {
  font-size: var(--text-title);
  font-weight: 800;
  letter-spacing: -0.01em;
  margin-bottom: var(--sp-4);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--sp-4);
}

.grid-skeleton {
  display: grid;
  gap: var(--sp-2);
}

.grid-skeleton-cover {
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-md);
}

.work {
  display: grid;
  gap: var(--sp-2);
  text-align: left;
  border-radius: var(--radius-md);
  transition: transform var(--dur-quick) var(--ease-out);
}

.work:hover {
  transform: translateY(-2px);
}

.work-cover {
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  border-radius: var(--radius-md);
  background: var(--surface-2);
}

.work-title {
  font-size: var(--text-small);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.work-sub {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--text-caption);
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.empty {
  display: grid;
  justify-items: center;
  gap: var(--sp-3);
  padding: var(--sp-12) 0;
  color: var(--text-2);
  font-size: var(--text-small);
}

/* 粉丝/关注抽屉：与评论抽屉同构 */
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 120;
  background: var(--scrim-1);
  backdrop-filter: blur(8px);
  display: grid;
  justify-items: end;
}

.panel {
  width: min(380px, calc(100vw - 16px));
  height: 100%;
  display: grid;
  grid-template-rows: auto 1fr;
  background: var(--surface);
  border-left: 1px solid var(--line-strong);
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

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--sp-4);
  border-bottom: 1px solid var(--line);
}

.panel-title {
  font-size: var(--text-title);
  font-weight: 800;
}

.panel-body {
  overflow-y: auto;
  padding: var(--sp-2) var(--sp-4);
}

.panel-hint {
  display: grid;
  place-items: center;
  padding: var(--sp-10) 0;
  color: var(--text-2);
  font-size: var(--text-small);
}

.panel-hint.bad {
  color: var(--danger);
}

.user-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  padding: var(--sp-3) 0;
  border-bottom: 1px solid var(--line);
  text-align: left;
}

.user-row:hover .user-name {
  color: var(--brand);
}

.user-name {
  flex: 1;
  min-width: 0;
  font-size: var(--text-small);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color var(--dur-quick) var(--ease-out);
}

.user-arrow {
  color: var(--text-3);
  transform: rotate(-90deg);
}

@media (max-width: 767px) {
  .backdrop {
    justify-items: center;
    align-items: end;
  }

  .panel {
    width: 100%;
    height: min(72vh, 560px);
    border-left: none;
    border-top: 1px solid var(--line-strong);
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  }

  @keyframes slide-in {
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
