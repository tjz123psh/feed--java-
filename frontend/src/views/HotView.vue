<script setup lang="ts">
import { onMounted, reactive } from 'vue'

import { ApiError } from '../api/client'
import * as feedApi from '../api/feed'
import * as likeApi from '../api/like'
import type { FeedVideoItem } from '../api/types'
import AppShell from '../components/AppShell.vue'
import Icon from '../components/Icon.vue'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'

const auth = useAuthStore()
const toast = useToastStore()

const LIMIT = 10

const state = reactive({
  loading: false,
  error: '',
  items: [] as FeedVideoItem[],
  hasMore: false,
  asOf: 0,
  nextOffset: 0,
})

const likeBusy = reactive<Record<string, boolean>>({})

function coverFallback(id: number) {
  const h = (id * 47) % 360
  return `linear-gradient(160deg, hsl(${h} 26% 14%), hsl(${(h + 40) % 360} 30% 8%))`
}

async function loadHot(reset: boolean) {
  if (state.loading) return
  state.loading = true
  state.error = ''
  try {
    const res = await feedApi.listByPopularity({
      limit: LIMIT,
      as_of: reset ? 0 : state.asOf,
      offset: reset ? 0 : state.nextOffset,
    })
    state.hasMore = res.has_more
    state.asOf = res.as_of
    state.nextOffset = res.next_offset
    state.items = reset ? res.video_list : state.items.concat(res.video_list)
  } catch (e) {
    state.error = e instanceof ApiError ? e.message : String(e)
  } finally {
    state.loading = false
  }
}

async function toggleLike(item: FeedVideoItem) {
  if (!auth.isLoggedIn) {
    toast.error('请先登录')
    return
  }
  const key = String(item.id)
  if (likeBusy[key]) return
  likeBusy[key] = true
  try {
    if (item.is_liked) await likeApi.unlike(item.id)
    else await likeApi.like(item.id)
    item.is_liked = !item.is_liked
    item.likes_count = Math.max(0, item.likes_count + (item.is_liked ? 1 : -1))
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  } finally {
    likeBusy[key] = false
  }
}

onMounted(async () => {
  await loadHot(true)
})
</script>

<template>
  <AppShell>
    <div class="page">
      <div class="page-inner" style="--page-max: 760px">
        <header class="head">
          <div>
            <h1 class="page-title">热榜</h1>
            <p class="page-sub">按热度排序，每分钟更新</p>
          </div>
          <button
            class="btn btn--icon"
            type="button"
            aria-label="刷新"
            :disabled="state.loading"
            @click="loadHot(true)"
          >
            <Icon name="refresh" :size="18" />
          </button>
        </header>

        <div v-if="state.error && state.items.length === 0" class="hint-box">
          <Icon name="alert" :size="20" />
          <p>{{ state.error }}</p>
          <button class="btn" type="button" @click="loadHot(true)">重试</button>
        </div>

        <div v-else-if="state.loading && state.items.length === 0" class="skeleton-list">
          <div v-for="i in 5" :key="i" class="skeleton-row">
            <div class="skeleton" style="width: 28px; height: 28px" />
            <div class="skeleton" style="width: 96px; height: 54px; border-radius: var(--radius-md)" />
            <div class="skeleton-col">
              <div class="skeleton" style="width: 60%; height: 16px" />
              <div class="skeleton" style="width: 30%; height: 13px" />
            </div>
          </div>
        </div>

        <div v-else-if="state.items.length === 0" class="hint-box">
          <Icon name="fire" :size="20" />
          <p>这一分钟还没有上榜内容</p>
        </div>

        <ol v-else class="rank">
          <li v-for="(item, idx) in state.items" :key="`hot-${item.id}`" class="row">
            <span class="rank-num" :class="{ top: idx < 3 }">{{ idx + 1 }}</span>
            <RouterLink class="row-main" :to="`/video/${item.id}`">
              <img
                class="thumb"
                :src="item.cover_url"
                alt=""
                loading="lazy"
                :style="{ backgroundImage: coverFallback(item.id) }"
              />
              <span class="row-text">
                <span class="row-title">{{ item.title }}</span>
                <span class="row-sub">@{{ item.author.username }}</span>
              </span>
            </RouterLink>
            <button
              class="like"
              :class="{ on: item.is_liked }"
              type="button"
              :disabled="!!likeBusy[String(item.id)]"
              :aria-label="item.is_liked ? '取消点赞' : '点赞'"
              @click="toggleLike(item)"
            >
              <Icon name="heart" :size="18" :filled="item.is_liked" />
              <span>{{ item.likes_count }}</span>
            </button>
          </li>
        </ol>

        <div v-if="state.items.length" class="more">
          <button
            v-if="state.hasMore"
            class="btn btn--block"
            type="button"
            :disabled="state.loading"
            @click="loadHot(false)"
          >
            {{ state.loading ? '加载中…' : '加载更多' }}
          </button>
          <p v-else class="end">已经到底了</p>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-6);
}

.hint-box {
  display: grid;
  justify-items: center;
  gap: var(--sp-3);
  padding: var(--sp-12) 0;
  color: var(--text-2);
  font-size: var(--text-small);
}

.rank {
  list-style: none;
  margin: 0;
  padding: 0;
}

.row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  padding: var(--sp-3) 0;
  border-bottom: 1px solid var(--line);
}

.rank-num {
  flex: none;
  width: 28px;
  text-align: center;
  font-size: var(--text-title);
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  color: var(--text-3);
}

.rank-num.top {
  color: var(--brand);
}

.row-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--sp-4);
}

.thumb {
  flex: none;
  width: 96px;
  height: 54px;
  border-radius: var(--radius-md);
  object-fit: cover;
  background-size: cover;
}

.row-text {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.row-title {
  font-size: var(--text-body);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-sub {
  font-size: var(--text-meta);
  color: var(--text-2);
}

.like {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  color: var(--text-2);
  font-size: var(--text-meta);
  font-variant-numeric: tabular-nums;
  transition:
    color var(--dur-quick) var(--ease-out),
    background var(--dur-quick) var(--ease-out);
}

.like:hover {
  background: rgba(255, 255, 255, 0.06);
  color: var(--text);
}

.like.on {
  color: var(--brand);
}

.like:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.more {
  margin-top: var(--sp-6);
}

.end {
  text-align: center;
  font-size: var(--text-meta);
  color: var(--text-3);
  padding: var(--sp-3) 0;
}

.skeleton-list {
  display: grid;
}

.skeleton-row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  padding: var(--sp-3) 0;
  border-bottom: 1px solid var(--line);
}

.skeleton-col {
  flex: 1;
  display: grid;
  gap: var(--sp-2);
}

@media (max-width: 767px) {
  .row {
    gap: var(--sp-3);
  }

  .thumb {
    width: 72px;
    height: 41px;
  }

  .rank-num {
    width: 20px;
    font-size: var(--text-lead);
  }

  .row-title {
    font-size: var(--text-small);
  }
}
</style>
