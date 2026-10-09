<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    name: string
    size?: number
    stroke?: number
    filled?: boolean
  }>(),
  { size: 20, stroke: 1.8, filled: false },
)

const paths: Record<string, string[]> = {
  home: ['M3 9.7 12 3l9 6.7V20a1 1 0 0 1-1 1h-5.2v-6.4H9.2V21H4a1 1 0 0 1-1-1z'],
  fire: [
    'M12 21c3.9 0 6.4-2.5 6.4-6 0-2.6-1.6-4.4-3-6-1.2-1.3-2.1-2.5-2.3-4.8-2.7 1.6-4.4 3.9-4.6 6.3-.9-.8-1.4-1.9-1.6-3-1.3 1.7-2.3 4-2.3 6.3C4.6 18.6 7.7 21 12 21z',
  ],
  plus: ['M12 5v14', 'M5 12h14'],
  mail: ['M3.5 6.5h17a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-17a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z', 'm3 7.5 9 6 9-6'],
  user: ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M4.5 20.5c.8-3.6 3.9-6 7.5-6s6.7 2.4 7.5 6'],
  sliders: ['M4 7h9', 'M17 7h3', 'M4 17h3', 'M11 17h9', 'M13 7a2 2 0 1 0 4 0 2 2 0 0 0-4 0z', 'M7 17a2 2 0 1 0 4 0 2 2 0 0 0-4 0z'],
  search: ['M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z', 'm21 21-4.3-4.3'],
  heart: ['M20.6 5a5.1 5.1 0 0 0-7.3 0L12 6.3 10.7 5A5.1 5.1 0 0 0 3.4 12.3l8.6 8.5 8.6-8.5a5.1 5.1 0 0 0 0-7.3z'],
  comment: ['M21 11.6a8.4 8.4 0 0 1-8.5 8.3 9 9 0 0 1-4-.9L3.5 20l1.2-4.8a8.2 8.2 0 0 1-1.2-3.6A8.4 8.4 0 0 1 12 3.3a8.4 8.4 0 0 1 9 8.3z'],
  share: ['M4.5 12.5v6.5a1 1 0 0 0 1 1h13a1 1 0 0 0 1-1v-6.5', 'M16 6.5 12 2.5l-4 4', 'M12 2.5V14'],
  close: ['M6 6l12 12', 'M18 6 6 18'],
  play: ['M8 5.4v13.2L18.6 12z'],
  pause: ['M9 5v14', 'M15 5v14'],
  volume: ['M11 5.5 6.5 9H3v6h3.5L11 18.5z', 'M15.5 9a4.2 4.2 0 0 1 0 6', 'M18 6.8a7.5 7.5 0 0 1 0 10.4'],
  mute: ['M11 5.5 6.5 9H3v6h3.5L11 18.5z', 'm16 10 5 4', 'm21 10-5 4'],
  refresh: ['M20.5 12a8.5 8.5 0 1 1-2.4-5.9', 'M20.5 3.5v6h-6'],
  back: ['M19.5 12h-15', 'm11 18-6-6 6-6'],
  send: ['M21.5 2.5 11 13', 'M21.5 2.5 15 21l-4-8-8-4z'],
  check: ['M20 6.5 9.5 17 4 11.5'],
  eye: ['M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  lock: ['M6.5 10.5h11a1 1 0 0 1 1 1V20a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-8.5a1 1 0 0 1 1-1z', 'M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5'],
  userPlus: ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M4.5 20.5c.8-3.6 3.9-6 7.5-6', 'M18 14v6', 'M15 17h6'],
  film: ['M3.5 5.5h17a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1h-17a1 1 0 0 1-1-1v-11a1 1 0 0 1 1-1z', 'M7.5 5.5v13', 'M16.5 5.5v13', 'M2.5 9.5h5', 'M2.5 14.5h5', 'M16.5 9.5h5', 'M16.5 14.5h5'],
  clipboard: ['M9 4.5h6', 'M9.5 3h5a1 1 0 0 1 1 1v1.5h-7V4a1 1 0 0 1 1-1z', 'M7 5.5H5.5a1 1 0 0 0-1 1V20a1 1 0 0 0 1 1h13a1 1 0 0 0 1-1V6.5a1 1 0 0 0-1-1H17'],
  chevronDown: ['m6 9.5 6 6 6-6'],
  image: ['M4 5.5h16a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-11a1 1 0 0 1 1-1z', 'M8.5 11a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z', 'm3.5 17 5-5 4 4 3-3 5 5'],
  upload: ['M12 15.5V4', 'm7 8.5 5-5 5 5', 'M4 20h16'],
  alert: ['M12 8.5v5', 'M12 17.2v.3', 'M10.3 3.9 2.7 17.1a2 2 0 0 0 1.7 3h15.2a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z'],
}

const d = computed(() => paths[props.name] ?? [])
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    :fill="filled ? 'currentColor' : 'none'"
    :stroke="filled ? 'none' : 'currentColor'"
    :stroke-width="stroke"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
    class="icon"
  >
    <path v-for="(p, i) in d" :key="i" :d="p" />
  </svg>
</template>

<style scoped>
.icon {
  display: block;
  flex: none;
}
</style>
