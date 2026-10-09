<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  username: string
  id?: number
  size?: number
  src?: string
}>()

function hashToHue(input: string) {
  let h = 0
  for (let i = 0; i < input.length; i += 1) {
    h = (h * 31 + input.charCodeAt(i)) >>> 0
  }
  return h % 360
}

const initial = computed(() => {
  const s = (props.username ?? '').trim()
  if (!s) return '?'
  return s.slice(0, 1).toUpperCase()
})

const sizePx = computed(() => `${props.size ?? 40}px`)
const fontSizePx = computed(() => `${Math.round((props.size ?? 40) * 0.42)}px`)

const bg = computed(() => {
  const seed = typeof props.id === 'number' ? String(props.id) : props.username
  const hue = hashToHue(seed || '0')
  const h1 = hue
  const h2 = (hue + 40) % 360
  return `linear-gradient(135deg, hsl(${h1} 80% 52%), hsl(${h2} 72% 42%))`
})
</script>

<template>
  <img v-if="src" :src="src" class="avatar" :style="{ width: sizePx, height: sizePx }" alt="" />
  <div
    v-else
    class="avatar"
    :style="{ width: sizePx, height: sizePx, backgroundImage: bg, fontSize: fontSizePx }"
    aria-hidden="true"
  >
    {{ initial }}
  </div>
</template>

<style scoped>
.avatar {
  display: grid;
  place-items: center;
  border-radius: var(--radius-full);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.94);
  font-weight: 800;
  user-select: none;
  object-fit: cover;
}
</style>
