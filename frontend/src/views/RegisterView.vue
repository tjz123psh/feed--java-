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
const form = reactive({ username: '', password: '' })

async function submit() {
  if (busy.value) return
  const username = form.username.trim()
  const password = form.password.trim()
  if (!username || !password) {
    toast.error('请输入用户名和密码')
    return
  }

  busy.value = true
  try {
    await accountApi.register(username, password)
    toast.success('注册成功，请登录')
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
          <span class="brand-dot"><Icon name="play" :size="16" filled /></span>
          <h1 class="page-title">注册</h1>
          <p class="page-sub">创建一个新账号</p>
        </header>

        <div class="form">
          <div class="field">
            <label for="register-username">用户名</label>
            <input id="register-username" v-model.trim="form.username" autocomplete="username" />
          </div>
          <div class="field">
            <label for="register-password">密码</label>
            <input id="register-password" v-model.trim="form.password" type="password" autocomplete="new-password" />
          </div>
          <button class="btn btn--primary btn--lg btn--block" type="button" :disabled="busy" @click="submit">
            注册
          </button>
        </div>

        <p class="form-note">注册成功后会跳回「账号」页进行登录。</p>
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
