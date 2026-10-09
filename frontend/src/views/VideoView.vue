<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AppShell from '../components/AppShell.vue'
import Icon from '../components/Icon.vue'
import { ApiError } from '../api/client'
import * as videoApi from '../api/video'
import type { Video } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { useToastStore } from '../stores/toast'
import SparkMD5 from 'spark-md5'

const router = useRouter()
const auth = useAuthStore()
const toast = useToastStore()

const busy = ref(false)
const stage = ref('')
const published = ref<Video | null>(null)

const videoInput = ref<HTMLInputElement | null>(null)
const coverInput = ref<HTMLInputElement | null>(null)

const publishForm = reactive({
  title: '',
  description: '',
  video: null as File | null,
  cover: null as File | null,
})

const preview = reactive({
  videoUrl: '',
  coverUrl: '',
})

// chunk upload progress
const uploadProgress = reactive({
  uploadedBytes: 0,
  totalBytes: 0,
  percent: 0,
})

function setPreviewVideo(file: File | null) {
  if (preview.videoUrl) URL.revokeObjectURL(preview.videoUrl)
  preview.videoUrl = file ? URL.createObjectURL(file) : ''
}

function setPreviewCover(file: File | null) {
  if (preview.coverUrl) URL.revokeObjectURL(preview.coverUrl)
  preview.coverUrl = file ? URL.createObjectURL(file) : ''
}

watch(
  () => publishForm.video,
  (f) => setPreviewVideo(f),
)

watch(
  () => publishForm.cover,
  (f) => setPreviewCover(f),
)

onUnmounted(() => {
  setPreviewVideo(null)
  setPreviewCover(null)
})

function pickVideo(e: Event) {
  const input = e.target as HTMLInputElement
  publishForm.video = input.files?.[0] ?? null
}

function pickCover(e: Event) {
  const input = e.target as HTMLInputElement
  publishForm.cover = input.files?.[0] ?? null
}

function onDropVideo(e: DragEvent) {
  const f = e.dataTransfer?.files?.[0]
  if (f) publishForm.video = f
}

function onDropCover(e: DragEvent) {
  const f = e.dataTransfer?.files?.[0]
  if (f) publishForm.cover = f
}

function openVideoPicker() {
  videoInput.value?.click()
}

function openCoverPicker() {
  coverInput.value?.click()
}

function clearVideo() {
  publishForm.video = null
  if (videoInput.value) videoInput.value.value = ''
}

function clearCover() {
  publishForm.cover = null
  if (coverInput.value) coverInput.value.value = ''
}

function resetProgress() {
  uploadProgress.uploadedBytes = 0
  uploadProgress.totalBytes = 0
  uploadProgress.percent = 0
}

function formatMB(bytes: number) {
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

// Compute file md5 by reading in 2MB chunks
async function computeFileMD5(file: File): Promise<string> {
  const chunkSize = 2 << 20
  const spark = new SparkMD5.ArrayBuffer()
  for (let offset = 0; offset < file.size; offset += chunkSize) {
    const end = Math.min(offset + chunkSize, file.size)
    const buf = await file.slice(offset, end).arrayBuffer()
    spark.append(buf)
  }
  return spark.end()
}

// Compute md5 for a single chunk blob
async function computeChunkMD5(blob: Blob): Promise<string> {
  const buf = await blob.arrayBuffer()
  const spark = new SparkMD5.ArrayBuffer()
  spark.append(buf)
  return spark.end()
}

const CHUNK_SIZE = 5 << 20 // 5 MB
const MAX_CONCURRENT = 3
const MAX_RETRIES = 3

async function uploadVideoChunked(file: File): Promise<videoApi.UploadResponse> {
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE)
  const fileHash = await computeFileMD5(file)

  stage.value = '初始化上传'
  const initRes = await videoApi.initChunkUpload({
    filename: file.name,
    file_size: file.size,
    chunk_size: CHUNK_SIZE,
    total_chunks: totalChunks,
    file_hash: fileHash,
  })

  const uploadId = initRes.upload_id
  const uploadedSet = new Set(initRes.uploaded_chunks)

  uploadProgress.totalBytes = file.size
  uploadProgress.uploadedBytes = uploadedSet.size * CHUNK_SIZE
  // Last chunk might be smaller
  if (uploadedSet.has(totalChunks - 1)) {
    uploadProgress.uploadedBytes -= CHUNK_SIZE
    uploadProgress.uploadedBytes += file.size - (totalChunks - 1) * CHUNK_SIZE
  }
  uploadProgress.percent = uploadProgress.totalBytes > 0
    ? Math.round((uploadProgress.uploadedBytes / uploadProgress.totalBytes) * 100)
    : 0

  // Build list of chunks that still need uploading
  const pending: number[] = []
  for (let i = 0; i < totalChunks; i++) {
    if (!uploadedSet.has(i)) {
      pending.push(i)
    }
  }

  if (pending.length === 0) {
    stage.value = '合并文件'
    return videoApi.completeChunkUpload(uploadId)
  }

  stage.value = '上传视频'

  // Upload chunks with concurrency limit
  let idx = 0
  const advanceProgress = (chunkIndex: number) => {
    const chunkBytes = chunkIndex === totalChunks - 1
      ? file.size - chunkIndex * CHUNK_SIZE
      : CHUNK_SIZE
    uploadProgress.uploadedBytes += chunkBytes
    uploadProgress.percent = Math.round((uploadProgress.uploadedBytes / uploadProgress.totalBytes) * 100)
  }

  const uploadOne = async (chunkIndex: number): Promise<void> => {
    const start = chunkIndex * CHUNK_SIZE
    const end = Math.min(start + CHUNK_SIZE, file.size)
    const blob = file.slice(start, end)
    const chunkHash = await computeChunkMD5(blob)

    let lastErr: unknown
    for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
      try {
        await videoApi.uploadChunk(uploadId, chunkIndex, chunkHash, blob)
        advanceProgress(chunkIndex)
        return
      } catch (e) {
        lastErr = e
      }
    }
    throw lastErr
  }

  await new Promise<void>((resolve, reject) => {
    let active = 0
    let done = false

    const next = () => {
      if (done) return
      if (idx >= pending.length && active === 0) {
        resolve()
        return
      }
      while (active < MAX_CONCURRENT && idx < pending.length) {
        const ci = pending[idx++] as number
        active++
        uploadOne(ci)
          .then(() => { active--; next() })
          .catch((e) => { done = true; reject(e) })
      }
    }
    next()
  })

  stage.value = '合并文件'
  return videoApi.completeChunkUpload(uploadId)
}

async function onPublish() {
  if (busy.value) return
  if (!auth.isLoggedIn) {
    toast.error('请先登录')
    await router.push('/account')
    return
  }

  const title = publishForm.title.trim()
  const description = publishForm.description.trim()
  if (!title) {
    toast.error('请输入视频标题')
    return
  }
  if (!publishForm.video) {
    toast.error('请选择要上传的视频文件（.mp4）')
    return
  }
  if (!publishForm.cover) {
    toast.error('请选择封面图片（jpg / png / webp）')
    return
  }

  busy.value = true
  stage.value = ''
  published.value = null
  resetProgress()
  try {
    const videoRes = await uploadVideoChunked(publishForm.video!)

    stage.value = '上传封面'
    const coverRes = await videoApi.uploadCover(publishForm.cover!)

    const coverUrl = coverRes.url || coverRes.cover_url || ''
    const playUrl = videoRes.url || videoRes.play_url || ''
    if (!coverUrl || !playUrl) {
      toast.error('上传成功但缺少 url')
      return
    }

    stage.value = '发布视频'
    const res = await videoApi.publishVideo({ title, description, play_url: playUrl, cover_url: coverUrl })

    published.value = res
    toast.success('已发布')

    publishForm.title = ''
    publishForm.description = ''
    clearVideo()
    clearCover()
  } catch (e) {
    const msg = e instanceof ApiError ? e.message : String(e)
    toast.error(msg)
  } finally {
    busy.value = false
    stage.value = ''
    resetProgress()
  }
}
</script>

<template>
  <AppShell>
    <div class="page">
      <div class="page-inner" style="--page-max: 640px">
        <header class="head">
          <h1 class="page-title">发布视频</h1>
          <p class="page-sub">上传本地视频与封面，分块续传，发布到推荐流</p>
        </header>

        <div v-if="published" class="done" role="status">
          <span class="done-icon"><Icon name="check" :size="18" /></span>
          <div class="done-text">
            <p class="done-title">「{{ published.title }}」已发布</p>
            <p class="done-sub">视频已进入推荐流</p>
          </div>
          <div class="done-actions">
            <RouterLink class="btn btn--primary" :to="`/video/${published.id}`">去播放</RouterLink>
            <button class="btn btn--ghost" type="button" @click="published = null">继续发布</button>
          </div>
        </div>

        <div class="form">
          <div class="field">
            <label for="pub-title">标题</label>
            <input id="pub-title" v-model.trim="publishForm.title" type="text" placeholder="一句话描述你的视频" :disabled="busy" />
          </div>

          <div class="field">
            <label for="pub-desc">简介<span class="opt">（可选）</span></label>
            <textarea id="pub-desc" v-model.trim="publishForm.description" placeholder="补充说明，支持话题 #tag" :disabled="busy" />
          </div>

          <div class="field">
            <span class="field-label">视频文件</span>
            <input ref="videoInput" class="sr-only" type="file" accept="video/mp4" :disabled="busy" @change="pickVideo" />
            <div
              class="dropzone"
              :class="{ filled: !!publishForm.video, disabled: busy }"
              role="button"
              tabindex="0"
              aria-label="选择视频文件"
              @click="!busy && openVideoPicker()"
              @keydown.enter.prevent="!busy && openVideoPicker()"
              @keydown.space.prevent="!busy && openVideoPicker()"
              @dragover.prevent
              @drop.prevent="!busy && onDropVideo($event)"
            >
              <template v-if="!publishForm.video">
                <Icon name="upload" :size="22" />
                <span class="drop-title">点击选择或拖入视频</span>
                <span class="drop-sub">.mp4，最大 200 MB</span>
              </template>
              <template v-else>
                <Icon name="film" :size="22" />
                <span class="drop-title">{{ publishForm.video.name }}</span>
                <span class="drop-sub">{{ formatMB(publishForm.video.size) }}</span>
              </template>
            </div>
            <button v-if="publishForm.video && !busy" class="link-btn" type="button" @click="clearVideo">移除视频</button>
          </div>

          <div class="field">
            <span class="field-label">封面</span>
            <input ref="coverInput" class="sr-only" type="file" accept="image/jpeg,image/png,image/webp" :disabled="busy" @change="pickCover" />
            <div
              class="dropzone"
              :class="{ filled: !!publishForm.cover, disabled: busy }"
              role="button"
              tabindex="0"
              aria-label="选择封面图片"
              @click="!busy && openCoverPicker()"
              @keydown.enter.prevent="!busy && openCoverPicker()"
              @keydown.space.prevent="!busy && openCoverPicker()"
              @dragover.prevent
              @drop.prevent="!busy && onDropCover($event)"
            >
              <template v-if="!publishForm.cover">
                <Icon name="image" :size="22" />
                <span class="drop-title">点击选择或拖入封面图</span>
                <span class="drop-sub">jpg / png / webp，最大 10 MB</span>
              </template>
              <template v-else>
                <img v-if="preview.coverUrl" class="drop-thumb" :src="preview.coverUrl" alt="封面预览" />
                <span class="drop-title">{{ publishForm.cover.name }}</span>
              </template>
            </div>
            <button v-if="publishForm.cover && !busy" class="link-btn" type="button" @click="clearCover">移除封面</button>
          </div>

          <div v-if="preview.videoUrl" class="preview">
            <span class="field-label">视频预览</span>
            <video class="preview-video" :src="preview.videoUrl" controls playsinline preload="metadata" />
          </div>

          <div v-if="busy && uploadProgress.totalBytes > 0" class="progress" role="status">
            <div class="progress-top">
              <span>{{ stage || '处理中' }}</span>
              <span class="mono">{{ uploadProgress.percent }}%</span>
            </div>
            <div class="progress-bar">
              <div
                class="progress-fill"
                :style="{ transform: `scaleX(${uploadProgress.percent / 100})` }"
              />
            </div>
            <p class="progress-sub">
              {{ formatMB(uploadProgress.uploadedBytes) }} / {{ formatMB(uploadProgress.totalBytes) }}
            </p>
          </div>

          <div class="submit">
            <button class="btn btn--primary btn--lg btn--block" type="button" :disabled="busy" @click="onPublish">
              {{ busy ? `${stage || '处理中'}…` : '发布' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.head {
  margin-bottom: var(--sp-6);
}

.done {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
  margin-bottom: var(--sp-6);
  border-radius: var(--radius-md);
  border: 1px solid rgba(52, 211, 153, 0.35);
  background: rgba(52, 211, 153, 0.08);
}

.done-icon {
  flex: none;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-full);
  background: rgba(52, 211, 153, 0.16);
  color: var(--success);
}

.done-text {
  flex: 1;
  min-width: 0;
}

.done-title {
  font-size: var(--text-small);
  font-weight: 700;
}

.done-sub {
  margin-top: 2px;
  font-size: var(--text-meta);
  color: var(--text-2);
}

.done-actions {
  display: flex;
  gap: var(--sp-2);
}

.form {
  display: grid;
  gap: var(--sp-5);
}

.field {
  display: grid;
  gap: var(--sp-2);
}

label,
.field-label {
  font-size: var(--text-meta);
  font-weight: 600;
  color: var(--text-2);
}

.opt {
  font-weight: 400;
  color: var(--text-3);
}

.dropzone {
  display: grid;
  justify-items: center;
  gap: var(--sp-2);
  padding: var(--sp-6);
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--text-2);
  cursor: pointer;
  transition:
    border-color var(--dur-quick) var(--ease-out),
    background var(--dur-quick) var(--ease-out),
    color var(--dur-quick) var(--ease-out);
}

.dropzone:hover,
.dropzone:focus-visible {
  border-color: var(--brand);
  background: var(--brand-dim);
  color: var(--text);
}

.dropzone.filled {
  border-style: solid;
}

.dropzone.disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.drop-title {
  font-size: var(--text-small);
  font-weight: 600;
  color: var(--text);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drop-sub {
  font-size: var(--text-caption);
  color: var(--text-3);
}

.drop-thumb {
  width: 160px;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  border-radius: var(--radius-sm);
}

.link-btn {
  justify-self: start;
  font-size: var(--text-meta);
  color: var(--text-2);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.link-btn:hover {
  color: var(--text);
}

.preview {
  display: grid;
  gap: var(--sp-2);
}

.preview-video {
  width: 100%;
  border-radius: var(--radius-md);
  border: 1px solid var(--line);
  background: #000000;
}

.progress {
  display: grid;
  gap: var(--sp-2);
}

.progress-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--text-small);
  color: var(--text);
}

.progress-bar {
  height: 6px;
  border-radius: var(--radius-full);
  background: rgba(255, 255, 255, 0.1);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  width: 100%;
  background: var(--brand);
  transform-origin: left center;
  transition: transform var(--dur-quick) var(--ease-out);
}

.progress-sub {
  font-size: var(--text-caption);
  color: var(--text-3);
}

.submit {
  padding-top: var(--sp-2);
}
</style>
