<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import AppShell from '../components/AppShell.vue'
import Icon from '../components/Icon.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { ApiError } from '../api/client'
import * as accountApi from '../api/account'
import * as likeApi from '../api/like'
import type { Video } from '../api/types'
import * as videoApi from '../api/video'
import { useAuthStore } from '../stores/auth'
import { useSocialStore } from '../stores/social'
import { useToastStore } from '../stores/toast'

const router = useRouter()
const auth = useAuthStore()
const social = useSocialStore()
const toast = useToastStore()

const busy = ref(false)
const loginForm = reactive({ username: '', password: '' })

const me = computed(() => ({
  id: auth.claims?.account_id ?? 0,
  username: auth.claims?.username ?? '',
}))

const myVideos = reactive({
  loading: false,
  error: '',
  items: [] as Video[],
})

type VideoTab = 'works' | 'likes'
const videoTab = ref<VideoTab>('works')

let myVideosReq = 0
async function loadMyVideos() {
  const id = me.value.id
  if (!auth.isLoggedIn || !id) {
    myVideos.items = []
    myVideos.error = ''
    myVideos.loading = false
    return
  }
  if (myVideos.loading) return

  const req = ++myVideosReq
  myVideos.loading = true
  myVideos.error = ''
  try {
    const vids = await videoApi.listByAuthorId(id)
    if (req !== myVideosReq) return
    myVideos.items = vids
  } catch (e) {
    if (req !== myVideosReq) return
    myVideos.error = e instanceof ApiError ? e.message : String(e)
    myVideos.items = []
  } finally {
    if (req === myVideosReq) myVideos.loading = false
  }
}

const likedVideos = reactive({
  loading: false,
  loaded: false,
  error: '',
  items: [] as Video[],
})

let likedVideosReq = 0
async function loadLikedVideos() {
  if (!auth.isLoggedIn || !me.value.id) {
    likedVideosReq += 1
    likedVideos.loading = false
    likedVideos.loaded = false
    likedVideos.error = ''
    likedVideos.items = []
    return
  }
  if (likedVideos.loading) return

  const req = ++likedVideosReq
  likedVideos.loading = true
  likedVideos.error = ''
  try {
    const vids = await likeApi.listMyLikedVideos()
    if (req !== likedVideosReq) return
    likedVideos.items = vids
    likedVideos.loaded = true
  } catch (e) {
    if (req !== likedVideosReq) return
    likedVideos.error = e instanceof ApiError ? e.message : String(e)
    likedVideos.items = []
    likedVideos.loaded = true
  } finally {
    if (req === likedVideosReq) likedVideos.loading = false
  }
}

async function goVideo(id: number) {
  await router.push(`/video/${id}`)
}

function openWorksVideos() {
  videoTab.value = 'works'
  void loadMyVideos()
}

function openLikedVideos() {
  videoTab.value = 'likes'
  void loadLikedVideos()
}

async function onLogin() {
  if (busy.value) return
  const username = loginForm.username.trim()
  const password = loginForm.password.trim()
  if (!username || !password) {
    toast.error('请输入用户名和密码')
    return
  }

  busy.value = true
  try {
    const res = await accountApi.login(username, password)
    auth.setTokens(res.token, res.refresh_token ?? '')
    toast.success('登录成功')
    await social.refreshMine()
    await loadMyVideos()
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  } finally {
    busy.value = false
  }
}

async function goRegister() {
  await router.push('/account/register')
}

async function goChangePassword() {
  await router.push('/account/change-password')
}

async function goSettings() {
  await router.push('/settings')
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
const listItems = computed(() => (drawer.tab === 'followers' ? social.followers : social.vloggers))
const drawerLoading = computed(() => (drawer.tab === 'followers' ? social.followersLoading : social.vloggersLoading))
const drawerError = computed(() => (drawer.tab === 'followers' ? social.followersError : social.vloggersError))
const socialErrorHint = computed(() => social.followersError || social.vloggersError)

async function goUser(id: number) {
  drawer.open = false
  await router.push(`/u/${id}`)
}

watch(
  () => auth.isLoggedIn,
  (v) => {
    if (!v) {
      drawer.open = false
      myVideosReq += 1
      myVideos.loading = false
      myVideos.items = []
      myVideos.error = ''

      likedVideosReq += 1
      likedVideos.loading = false
      likedVideos.loaded = false
      likedVideos.items = []
      likedVideos.error = ''

      videoTab.value = 'works'
    }
  },
)

watch(
  () => me.value.id,
  (id) => {
    if (auth.isLoggedIn && id) {
      void loadMyVideos()
      if (videoTab.value === 'likes') void loadLikedVideos()
    }
  },
  { immediate: true },
)
</script>

<template>
  <AppShell>
    <div v-if="!auth.isLoggedIn" class="page">
      <div class="page-inner" style="--page-max: 440px">
        <header class="auth-head">
          <span class="brand-dot"><Icon name="play" :size="16" filled /></span>
          <h1 class="page-title">登录</h1>
          <p class="page-sub">登录后可以点赞、评论、关注和上传作品</p>
        </header>

        <div class="form">
          <div class="field">
            <label for="login-username">用户名</label>
            <input id="login-username" v-model.trim="loginForm.username" autocomplete="username" />
          </div>
          <div class="field">
            <label for="login-password">密码</label>
            <input
              id="login-password"
              v-model.trim="loginForm.password"
              type="password"
              autocomplete="current-password"
              @keydown.enter="onLogin"
            />
          </div>
          <button class="btn btn--primary btn--lg btn--block" type="button" :disabled="busy" @click="onLogin">
            登录
          </button>
        </div>

        <div class="auth-alt">
          <button class="btn btn--ghost" type="button" :disabled="busy" @click="goRegister">注册账号</button>
          <button class="btn btn--ghost" type="button" :disabled="busy" @click="goChangePassword">修改密码</button>
        </div>
      </div>
    </div>

    <div v-else class="page">
      <div class="page-inner">
        <header class="profile">
          <UserAvatar :username="me.username" :id="me.id" :size="64" />
          <h1 class="page-title profile-name">@{{ me.username }}</h1>
          <button class="btn profile-settings" type="button" aria-label="账号设置" @click="goSettings">
            <Icon name="sliders" :size="16" />
            <span>设置</span>
          </button>
        </header>

        <div class="stats" role="group" aria-label="账号数据">
          <button class="stat" type="button" :disabled="social.followersLoading" @click="openFollowers">
            <span class="stat-num">{{ social.followersLoading ? '…' : social.followerCount }}</span>
            <span class="stat-label">粉丝</span>
          </button>
          <button class="stat" type="button" :disabled="social.vloggersLoading" @click="openFollowing">
            <span class="stat-num">{{ social.vloggersLoading ? '…' : social.followingCount }}</span>
            <span class="stat-label">关注</span>
          </button>
          <button class="stat" type="button" :class="{ on: videoTab === 'works' }" @click="openWorksVideos">
            <span class="stat-num">{{ myVideos.loading ? '…' : myVideos.items.length }}</span>
            <span class="stat-label">作品</span>
          </button>
          <button class="stat" type="button" :class="{ on: videoTab === 'likes' }" @click="openLikedVideos">
            <span class="stat-num">{{ likedVideos.loading ? '…' : likedVideos.loaded ? likedVideos.items.length : '—' }}</span>
            <span class="stat-label">点赞</span>
          </button>
        </div>

        <p v-if="socialErrorHint" class="error-line">
          <Icon name="alert" :size="14" />
          <span>社交信息加载失败：{{ socialErrorHint }}</span>
        </p>

        <section class="works" aria-labelledby="works-title">
          <header class="works-head">
            <h2 id="works-title" class="section-title">{{ videoTab === 'works' ? '作品' : '点赞视频' }}</h2>
          </header>

          <template v-if="videoTab === 'works'">
            <p v-if="myVideos.loading" class="hint">加载中…</p>
            <p v-else-if="myVideos.error" class="hint bad">
              <Icon name="alert" :size="14" />
              <span>{{ myVideos.error }}</span>
            </p>
            <div v-else-if="myVideos.items.length === 0" class="empty">
              <Icon name="film" :size="24" />
              <p>暂无作品</p>
              <RouterLink class="btn" to="/video">去发布第一支视频</RouterLink>
            </div>
            <ul v-else class="works-grid">
              <li v-for="v in myVideos.items" :key="v.id">
                <button class="work" type="button" @click="goVideo(v.id)">
                  <img class="work-cover" :src="v.cover_url" :alt="v.title" loading="lazy" />
                  <span class="work-title">{{ v.title }}</span>
                  <span class="work-meta">
                    <Icon name="heart" :size="13" filled />
                    <span>{{ v.likes_count }}</span>
                    <span class="work-sep">·</span>
                    <span>{{ new Date(v.create_time).toLocaleDateString() }}</span>
                  </span>
                </button>
              </li>
            </ul>
          </template>

          <template v-else>
            <p v-if="likedVideos.loading" class="hint">加载中…</p>
            <p v-else-if="likedVideos.error" class="hint bad">
              <Icon name="alert" :size="14" />
              <span>{{ likedVideos.error }}</span>
            </p>
            <div v-else-if="likedVideos.items.length === 0" class="empty">
              <Icon name="film" :size="24" />
              <p>暂无点赞视频</p>
              <RouterLink class="btn" to="/">去看看推荐流</RouterLink>
            </div>
            <ul v-else class="works-grid">
              <li v-for="v in likedVideos.items" :key="v.id">
                <button class="work" type="button" @click="goVideo(v.id)">
                  <img class="work-cover" :src="v.cover_url" :alt="v.title" loading="lazy" />
                  <span class="work-title">{{ v.title }}</span>
                  <span class="work-meta">
                    <Icon name="heart" :size="13" filled />
                    <span>{{ v.likes_count }}</span>
                    <span class="work-sep">·</span>
                    <span>{{ new Date(v.create_time).toLocaleDateString() }}</span>
                  </span>
                </button>
              </li>
            </ul>
          </template>
        </section>
      </div>
    </div>

    <div
      v-if="drawer.open"
      class="drawer-backdrop"
      role="dialog"
      aria-modal="true"
      :aria-label="listTitle"
      @click.self="closeDrawer"
    >
      <div class="drawer">
        <header class="drawer-head">
          <h2 class="drawer-title">{{ listTitle }}</h2>
          <button class="btn btn--ghost btn--icon" type="button" aria-label="关闭" @click="closeDrawer">
            <Icon name="close" :size="16" />
          </button>
        </header>
        <div class="drawer-body">
          <p v-if="drawerLoading" class="drawer-hint">加载中…</p>
          <p v-else-if="drawerError" class="drawer-hint bad">
            <Icon name="alert" :size="14" />
            <span>{{ drawerError }}</span>
          </p>
          <p v-else-if="listItems.length === 0" class="drawer-hint">暂无{{ listTitle }}</p>
          <ul v-else class="user-list">
            <li v-for="u in listItems" :key="u.id">
              <button class="user-row" type="button" @click="goUser(u.id)">
                <UserAvatar :username="u.username" :id="u.id" :size="40" />
                <span class="user-name">@{{ u.username }}</span>
              </button>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
/* —— 登录表单 —— */
.auth-head {
  display: grid;
  justify-items: center;
  gap: var(--sp-3);
  text-align: center;
  padding-top: var(--sp-8);
}

.brand-dot {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  background: var(--brand);
  color: var(--text);
  box-shadow: 0 6px 18px var(--brand-dim);
}

.form {
  display: grid;
  gap: var(--sp-5);
  margin-top: var(--sp-8);
}

.field {
  display: grid;
  gap: var(--sp-2);
}

.field label {
  font-size: var(--text-meta);
  font-weight: 600;
  color: var(--text-2);
}

.auth-alt {
  display: flex;
  justify-content: space-between;
  gap: var(--sp-3);
  margin-top: var(--sp-5);
}

/* —— 已登录：身份与数据 —— */
.profile {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
}

.profile-name {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}

.profile-settings {
  flex: none;
}

.stats {
  display: flex;
  gap: var(--sp-8);
  margin-top: var(--sp-6);
  padding-top: var(--sp-5);
  border-top: 1px solid var(--line);
}

.stat {
  position: relative;
  display: grid;
  gap: var(--sp-1);
  justify-items: start;
  padding-bottom: var(--sp-2);
}

.stat-num {
  font-size: var(--text-title);
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: var(--leading-tight);
}

.stat-label {
  font-size: var(--text-caption);
  color: var(--text-2);
}

.stat:hover .stat-num {
  color: var(--text);
}

.stat.on::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: 0;
  width: 24px;
  height: 2px;
  border-radius: var(--radius-full);
  background: var(--brand);
}

.stat:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.error-line {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-4);
  font-size: var(--text-meta);
  color: var(--danger);
}

/* —— 作品 / 点赞列表 —— */
.works {
  margin-top: var(--sp-8);
}

.works-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-4);
  padding-bottom: var(--sp-4);
  border-bottom: 1px solid var(--line);
}

.section-title {
  font-size: var(--text-title);
  font-weight: 700;
  letter-spacing: -0.01em;
}

.works-hint {
  font-size: var(--text-meta);
  color: var(--text-3);
}

.hint {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-5);
  font-size: var(--text-small);
  color: var(--text-2);
}

.hint.bad {
  color: var(--danger);
}

.empty {
  display: grid;
  justify-items: center;
  gap: var(--sp-3);
  padding: var(--sp-10) 0;
  color: var(--text-3);
  text-align: center;
}

.empty p {
  font-size: var(--text-small);
  color: var(--text-2);
}

.works-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--sp-5) var(--sp-4);
  list-style: none;
  margin: var(--sp-5) 0 0;
  padding: 0;
}

.works-grid li {
  min-width: 0;
}

.work {
  display: grid;
  gap: var(--sp-2);
  width: 100%;
  text-align: left;
}

.work-cover {
  width: 100%;
  aspect-ratio: 3 / 4;
  object-fit: cover;
  border-radius: var(--radius-md);
  background: var(--surface-2);
  transition: opacity var(--dur-quick) var(--ease-out);
}

.work:hover .work-cover {
  opacity: 0.85;
}

.work-title {
  font-size: var(--text-small);
  font-weight: 600;
  line-height: var(--leading-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.work-meta {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  font-size: var(--text-caption);
  color: var(--text-3);
}

.work-sep {
  padding: 0 2px;
}

/* —— 粉丝 / 关注抽屉 —— */
.drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 120;
  display: grid;
  place-items: center;
  padding: var(--sp-4);
  background: var(--scrim-2);
  backdrop-filter: blur(8px);
}

.drawer {
  display: flex;
  flex-direction: column;
  width: min(480px, 100%);
  max-height: min(78vh, 720px);
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-lg);
  box-shadow: 0 24px 70px var(--scrim-3);
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
  border-bottom: 1px solid var(--line);
}

.drawer-title {
  font-size: var(--text-title);
  font-weight: 700;
}

.drawer-body {
  min-height: 0;
  overflow-y: auto;
  padding-bottom: var(--sp-2);
}

.drawer-hint {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-5);
  font-size: var(--text-small);
  color: var(--text-2);
}

.drawer-hint.bad {
  color: var(--danger);
}

.user-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.user-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  padding: var(--sp-3) var(--sp-5);
  text-align: left;
  border-bottom: 1px solid var(--line);
  transition: background var(--dur-quick) var(--ease-out);
}

.user-row:hover {
  background: rgba(255, 255, 255, 0.04);
}

.user-list li:last-child .user-row {
  border-bottom: 0;
}

.user-name {
  min-width: 0;
  font-size: var(--text-small);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* —— 窄屏 —— */
@media (max-width: 767px) {
  .stats {
    gap: var(--sp-6);
  }

  .profile-name {
    font-size: var(--text-heading);
  }

  .works-grid {
    grid-template-columns: repeat(auto-fill, minmax(132px, 1fr));
  }
}
</style>
