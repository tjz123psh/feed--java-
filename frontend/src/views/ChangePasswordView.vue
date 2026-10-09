<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppShell from '../components/AppShell.vue'
import Icon from '../components/Icon.vue'
import { ApiError } from '../api/client'
import * as accountApi from '../api/account'
import { useToastStore } from '../stores/toast'

const router = useRouter()
const toast = useToastStore()

const busy = ref(false)
const form = reactive({ username: '', oldPassword: '', newPassword: '' })

async function submit() {
  if (busy.value) return
  const username = form.username.trim()
  const oldPassword = form.oldPassword.trim()
  const newPassword = form.newPassword.trim()
  if (!username || !oldPassword || !newPassword) {
    toast.error('请把信息填完整')
    return
  }

  busy.value = true
  try {
    await accountApi.changePassword(username, oldPassword, newPassword)
    toast.success('密码已修改，请重新登录')
    await router.push('/account')
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppShell>
    <div class="page">
      <div class="page-inner" style="--page-max: 440px">
        <header class="auth-head">
          <span class="brand-dot"><Icon name="lock" :size="17" /></span>
          <h1 class="page-title">修改密码</h1>
          <p class="page-sub">无需登录，填入用户名与原密码即可修改。</p>
        </header>

        <div class="form">
          <div class="field">
            <label for="cp-username">用户名</label>
            <input id="cp-username" v-model.trim="form.username" autocomplete="username" />
          </div>
          <div class="field">
            <label for="cp-old">原密码</label>
            <input id="cp-old" v-model.trim="form.oldPassword" type="password" autocomplete="current-password" />
          </div>
          <div class="field">
            <label for="cp-new">新密码</label>
            <input id="cp-new" v-model.trim="form.newPassword" type="password" autocomplete="new-password" />
          </div>
          <button class="btn btn--primary btn--lg btn--block" type="button" :disabled="busy" @click="submit">
            提交
          </button>
        </div>

        <p class="form-note">修改成功后后端会让旧 token 失效；请在「账号」页重新登录。</p>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
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

.form-note {
  margin-top: var(--sp-5);
  font-size: var(--text-meta);
  color: var(--text-3);
  text-align: center;
}
</style>
