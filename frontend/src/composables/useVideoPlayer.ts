import { ref, type Ref } from 'vue'

/**
 * Feed 播放器：滚动几何 / 自动播放 / 静音 / 暂停态。
 * 关键修复（2026-10-08）：slide 不再用 v-show 移出布局——
 * 所有 slide 都在文档流里（滚动高度 = N×屏高），video 元素只挂载 active±1。
 */
export function useVideoPlayer(scrollerRef: Ref<HTMLDivElement | null>) {
  const muted = ref(true)
  const activeIndex = ref(0)
  const activeId = ref<number | undefined>(undefined)
  const paused = ref(true)
  const videoMap = new Map<number, HTMLVideoElement>()

  function getScrollerHeight() {
    return scrollerRef.value?.clientHeight ?? 0
  }

  function setVideoRef(id: number, el: HTMLVideoElement | null) {
    if (el) {
      el.muted = muted.value
      el.addEventListener('play', () => {
        if (activeId.value === id) paused.value = false
      })
      el.addEventListener('pause', () => {
        if (activeId.value === id) paused.value = true
      })
      videoMap.set(id, el)
    } else {
      videoMap.delete(id)
    }
  }

  function prefersReducedMotion() {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  }

  function scrollToIndex(idx: number, totalItems: number) {
    const el = scrollerRef.value
    if (!el) return
    const h = getScrollerHeight()
    if (!h) return
    const next = Math.max(0, Math.min(idx, Math.max(0, totalItems - 1)))
    el.scrollTo({ top: next * h, behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
  }

  let scrollRaf = 0
  function onScroll() {
    if (!scrollerRef.value) return
    if (scrollRaf) return
    scrollRaf = window.requestAnimationFrame(() => {
      scrollRaf = 0
      const el = scrollerRef.value
      if (!el) return
      const h = el.clientHeight
      if (!h) return
      const idx = Math.round(el.scrollTop / h)
      if (idx !== activeIndex.value) activeIndex.value = idx
    })
  }

  async function playActive(activeItemId: number | undefined) {
    activeId.value = activeItemId
    if (!activeItemId) {
      paused.value = true
      return
    }
    for (const [id, v] of videoMap.entries()) {
      if (id === activeItemId) continue
      v.pause()
    }
    const video = videoMap.get(activeItemId)
    if (!video) {
      paused.value = true
      return
    }
    video.muted = muted.value
    try {
      await video.play()
      paused.value = false
    } catch {
      paused.value = true
    }
  }

  function toggleMute() {
    muted.value = !muted.value
    for (const v of videoMap.values()) v.muted = muted.value
  }

  function togglePlayPause(activeItemId: number | undefined) {
    const video = videoMap.get(activeItemId ?? activeId.value ?? -1)
    if (!video) return
    if (video.paused) void video.play()
    else video.pause()
  }

  return {
    muted,
    activeIndex,
    activeId,
    paused,
    videoMap,
    setVideoRef,
    scrollToIndex,
    onScroll,
    playActive,
    toggleMute,
    togglePlayPause,
  }
}
