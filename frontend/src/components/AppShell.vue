<script setup lang="ts">
import { computed, watch } from 'vue'
import { RouterLink } from 'vue-router'

import Icon from './Icon.vue'
import Toaster from './Toaster.vue'
import UserAvatar from './UserAvatar.vue'
import { useAuthStore } from '../stores/auth'
import { useSocialStore } from '../stores/social'

const auth = useAuthStore()
const social = useSocialStore()

const accountId = computed(() => auth.claims?.account_id ?? 0)
const username = computed(() => auth.claims?.username ?? '')

watch(
  () => auth.isLoggedIn,
  (v) => {
    if (v) void social.refreshMine()
    else social.clear()
  },
  { immediate: true },
)

const navItems = computed(() => [
  { to: '/', icon: 'home', label: '推荐' },
  { to: '/hot', icon: 'fire', label: '热榜' },
  { to: '/video', icon: 'plus', label: '发布' },
  { to: '/messages', icon: 'mail', label: '私信' },
  { to: auth.isLoggedIn ? `/u/${accountId.value}` : '/account', icon: 'user', label: '我的' },
])
</script>

<template>
  <div class="shell">
    <aside class="rail" aria-label="主导航">
      <RouterLink class="brand" to="/" aria-label="ShortVideo 首页">
        <span class="brand-mark"><Icon name="play" :size="15" filled /></span>
      </RouterLink>

      <nav class="rail-nav">
        <RouterLink v-for="item in navItems" :key="item.label" class="rail-link" :to="item.to">
          <Icon :name="item.icon" :size="22" />
          <span class="rail-label">{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="rail-foot">
        <RouterLink class="rail-link rail-link--mini" to="/settings" aria-label="设置">
          <Icon name="sliders" :size="20" />
        </RouterLink>
        <RouterLink
          v-if="auth.isLoggedIn"
          class="rail-user"
          :to="`/u/${accountId}`"
          :aria-label="`我的主页 ${username}`"
        >
          <UserAvatar :username="username" :id="accountId" :size="30" />
        </RouterLink>
        <RouterLink v-else class="rail-login" to="/account">登录</RouterLink>
      </div>
    </aside>

    <main class="main">
      <slot />
    </main>

    <nav class="tabbar" aria-label="主导航">
      <RouterLink v-for="item in navItems" :key="item.label" class="tab-link" :to="item.to">
        <Icon :name="item.icon" :size="22" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <Toaster />
  </div>
</template>

<style scoped>
.shell {
  height: 100%;
  display: flex;
}

/* —— 桌面左轨 —— */
.rail {
  width: 76px;
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-5);
  padding: var(--sp-4) 0 var(--sp-4);
  border-right: 1px solid var(--line);
  background: rgba(11, 14, 19, 0.72);
  backdrop-filter: blur(20px);
  z-index: 20;
}

.brand {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-md);
  background: var(--brand);
  color: #ffffff;
  display: grid;
  place-items: center;
  box-shadow: 0 6px 18px rgba(255, 59, 107, 0.35);
}

.rail-nav {
  display: grid;
  gap: var(--sp-1);
  width: 100%;
  padding: 0 var(--sp-2);
}

.rail-link {
  display: grid;
  justify-items: center;
  gap: 3px;
  padding: var(--sp-2) 0;
  border-radius: var(--radius-md);
  color: var(--text-3);
  font-size: var(--text-micro);
  line-height: 1.2;
  transition:
    color var(--dur-quick) var(--ease-out),
    background var(--dur-quick) var(--ease-out);
}

.rail-link:hover {
  color: var(--text-2);
  background: rgba(255, 255, 255, 0.05);
}

.rail-link.router-link-active {
  color: var(--text);
  background: rgba(255, 255, 255, 0.06);
}

.rail-link.router-link-active :deep(.icon) {
  color: var(--brand);
}

.rail-link--mini {
  width: 44px;
  margin: 0 auto var(--sp-2);
  padding: var(--sp-3) 0;
}

.rail-foot {
  margin-top: auto;
  display: grid;
  justify-items: center;
  gap: var(--sp-3);
}

.rail-user {
  display: grid;
  place-items: center;
  border-radius: var(--radius-full);
  transition: transform var(--dur-quick) var(--ease-out);
}

.rail-user:hover {
  transform: scale(1.06);
}

.rail-login {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-full);
  border: 1px solid var(--line-strong);
  color: var(--text-2);
  font-size: var(--text-caption);
  font-weight: 600;
  transition:
    color var(--dur-quick) var(--ease-out),
    border-color var(--dur-quick) var(--ease-out);
}

.rail-login:hover {
  color: var(--text);
  border-color: var(--brand);
}

/* —— 主区 —— */
.main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* —— 窄屏底部 Tab —— */
.tabbar {
  display: none;
}

@media (max-width: 767px) {
  .rail {
    display: none;
  }

  .shell {
    display: block;
  }

  .main {
    height: 100%;
  }

  .tabbar {
    position: fixed;
    inset-inline: 0;
    bottom: 0;
    z-index: 30;
    height: calc(56px + env(safe-area-inset-bottom));
    padding-bottom: env(safe-area-inset-bottom);
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: 1fr;
    background: rgba(11, 14, 19, 0.9);
    backdrop-filter: blur(20px);
    border-top: 1px solid var(--line);
  }

  .tab-link {
    display: grid;
    justify-items: center;
    align-content: center;
    gap: 2px;
    color: var(--text-3);
    font-size: var(--text-micro);
    line-height: 1.2;
  }

  .tab-link.router-link-active {
    color: var(--text);
  }

  .tab-link.router-link-active :deep(.icon) {
    color: var(--brand);
  }
}
</style>
