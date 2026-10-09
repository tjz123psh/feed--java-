<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppShell from '../components/AppShell.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { ApiError } from '../api/client'
import * as accountApi from '../api/account'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'

const router = useRouter()
const auth = useAuthStore()
const toast = useToastStore()

const busy = ref(false)

const me = computed(() => ({
  id: auth.claims?.account_id ?? 0,
  username: auth.claims?.username ?? '',
}))

const rename = reactive({
  open: false,
  newUsername: '',
})

async function openRename() {
  if (!auth.isLoggedIn) return
  rename.open = true
  rename.newUsername = me.value.username
  await nextTick()
}

async function submitRename() {
  if (!auth.isLoggedIn) return
  if (busy.value) return
  const newUsername = rename.newUsername.trim()
  if (!newUsername) {
    toast.error('请输入新用户名')
    return
  }

  busy.value = true
  try {
    const res = await accountApi.rename(newUsername)
    auth.setToken(res.token)
    rename.open = false
    toast.success('改名成功（已刷新 token）')
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  } finally {
    busy.value = false
  }
}

async function goLogin() {
  await router.push('/account')
}

async function goChangePassword() {
  await router.push('/account/change-password')
}

async function onLogout() {
  if (!auth.isLoggedIn) return
  if (busy.value) return
  if (!window.confirm('确认退出登录？')) return

  busy.value = true
  try {
    await accountApi.logout()
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(`登出失败：${msg}`)
  } finally {
    auth.clearTokens()
    rename.open = false
    toast.info('已退出登录')
    busy.value = false
    await router.push('/')
  }
}
</script>

<template>
  <AppShell>
    <div class="page">
      <div class="page-inner" style="--page-max: 560px">
        <header class="head">
          <h1 class="page-title">设置</h1>
          <p class="page-sub">管理账号信息与登录状态</p>
        </header>

        <template v-if="!auth.isLoggedIn">
          <p class="notice">需要先登录后才能进行改名、修改密码与退出登录。</p>
          <p class="notice-sub">登录入口在「账号」页。</p>
          <button class="btn btn--primary btn--lg" type="button" @click="goLogin">去登录</button>
        </template>

        <template v-else>
          <section class="section">
            <h2 class="section-title">账号</h2>
            <div class="row">
              <UserAvatar :username="me.username" :id="me.id" :size="48" />
              <div class="identity">
                <p class="identity-name">@{{ me.username }}</p>
                <p class="identity-sub">当前用户名</p>
              </div>
              <button class="btn" type="button" :disabled="busy" @click="openRename">改名</button>
            </div>

            <div v-if="rename.open" class="rename">
              <div class="field">
                <label for="rename-username">新用户名</label>
                <input
                  id="rename-username"
                  v-model.trim="rename.newUsername"
                  @keydown.enter="submitRename"
                />
              </div>
              <div class="rename-actions">
                <button class="btn" type="button" :disabled="busy" @click="rename.open = false">取消</button>
                <button class="btn btn--primary" type="button" :disabled="busy" @click="submitRename">提交</button>
              </div>
            </div>
          </section>

          <section class="section">
            <h2 class="section-title">账号安全</h2>
            <div class="actions">
              <button class="btn" type="button" :disabled="busy" @click="goChangePassword">修改密码</button>
              <button class="btn btn--danger" type="button" :disabled="busy" @click="onLogout">退出登录</button>
            </div>
          </section>

          <section class="section">
            <h2 class="section-title">说明</h2>
            <ul class="notes">
              <li>改名后会返回新 token，旧 token 立即失效</li>
              <li>退出登录会清空本地 token</li>
              <li>修改密码无需登录，但成功后会让旧 token 失效</li>
            </ul>
          </section>
        </template>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.head {
  padding-bottom: var(--sp-6);
}

.section {
  padding: var(--sp-6) 0;
  border-top: 1px solid var(--line);
}

.section-title {
  font-size: var(--text-title);
  font-weight: 700;
  letter-spacing: -0.01em;
}

.notice {
  font-size: var(--text-small);
  color: var(--text-2);
}

.notice-sub {
  margin-top: var(--sp-2);
  font-size: var(--text-meta);
  color: var(--text-3);
}

.notice-sub + .btn {
  margin-top: var(--sp-6);
}

.row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  margin-top: var(--sp-5);
}

.identity {
  flex: 1;
  min-width: 0;
}

.identity-name {
  font-size: var(--text-lead);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.identity-sub {
  margin-top: var(--sp-1);
  font-size: var(--text-meta);
  color: var(--text-3);
}

.rename {
  display: grid;
  gap: var(--sp-3);
  margin-top: var(--sp-5);
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

.rename-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-3);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
  margin-top: var(--sp-5);
}

.notes {
  display: grid;
  gap: var(--sp-3);
  list-style: none;
  margin: var(--sp-5) 0 0;
  padding: 0;
}

.notes li {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  font-size: var(--text-small);
  color: var(--text-2);
}

.notes li::before {
  content: '';
  flex: none;
  width: 4px;
  height: 4px;
  border-radius: var(--radius-full);
  background: var(--text-3);
  transform: translateY(-2px);
}
</style>
