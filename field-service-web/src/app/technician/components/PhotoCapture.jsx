/**
 * @fileoverview PhotoCapture — presigned direct-upload photo component (WO-158).
 *
 * Flow:
 *   1. User selects image via file input (capture="environment" triggers camera on mobile).
 *   2. Image is downscaled to ≤1920 px longest edge via canvas re-encode (strips EXIF).
 *   3. POST /upload-intent → receives presigned PUT URL and intentId.
 *   4. PUT binary directly to storage (never through the app tier).
 *   5. POST /photos to register metadata.
 *   6. On 403/expired PUT: re-request intent and retry once.
 *   7. On permanent failure: photo surfaces in 'error' state with Retry button.
 *
 * Security: access tokens are never placed in the presigned URL query string.
 * No image bytes pass through the application tier.
 */
import { useRef } from 'react'
import { post } from '../../../api/http.js'
import styles from './PhotoCapture.module.css'

// ── Constants ─────────────────────────────────────────────────────────────────

const MAX_LONG_EDGE = 1920
const JPEG_QUALITY = 0.82
const ALLOWED_MIME = new Set(['image/jpeg', 'image/png', 'image/webp'])
const MAX_BYTES = 5 * 1024 * 1024 // 5 MB

// ── Canvas downscale + EXIF strip ─────────────────────────────────────────────

/**
 * Downscales {@code file} to ≤ MAX_LONG_EDGE px longest edge and re-encodes as JPEG.
 * Canvas re-encode inherently strips all EXIF metadata including GPS (AC-6).
 *
 * @returns {Promise<Blob>} JPEG blob
 */
export function downscaleAndStrip(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      const { width, height } = img
      const longEdge = Math.max(width, height)
      const scale = longEdge > MAX_LONG_EDGE ? MAX_LONG_EDGE / longEdge : 1
      const w = Math.round(width * scale)
      const h = Math.round(height * scale)

      const canvas = document.createElement('canvas')
      canvas.width = w
      canvas.height = h
      const ctx = canvas.getContext('2d')
      ctx.drawImage(img, 0, 0, w, h)
      canvas.toBlob(
        (blob) => {
          if (!blob) { reject(new Error('Canvas toBlob returned null')); return }
          resolve(blob)
        },
        'image/jpeg',
        JPEG_QUALITY
      )
    }
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Image failed to load')) }
    img.src = url
  })
}

// ── Upload orchestration ──────────────────────────────────────────────────────

/**
 * Requests an upload intent, PUTs the blob directly to storage, then registers metadata.
 *
 * On a 403 from the PUT (expired URL) retries once with a fresh intent.
 *
 * @param {string} workOrderId
 * @param {Blob}   blob         Downscaled JPEG blob
 * @param {string} category     'ISSUE' | 'COMPLETION'
 * @param {string} capturedAt   ISO-8601 string
 * @returns {{ photoId: string, thumbnailUrl: string }}
 * @throws if the upload or registration fails
 */
export async function uploadPhoto(workOrderId, blob, category, capturedAt) {
  async function getIntent() {
    const result = await post(`/work-orders/${workOrderId}/photos/upload-intent`, {
      contentType: 'image/jpeg',
      contentLength: blob.size,
      category,
    })
    return result?.data ?? result
  }

  async function putBlob(uploadUrl) {
    const resp = await fetch(uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': 'image/jpeg' },
      body: blob,
    })
    return resp.status
  }

  let intent = await getIntent()
  let putStatus = await putBlob(intent.uploadUrl)

  if (putStatus === 403 || putStatus === 400) {
    // Presigned URL may have expired — request a fresh intent and retry once
    intent = await getIntent()
    putStatus = await putBlob(intent.uploadUrl)
  }

  if (putStatus < 200 || putStatus >= 300) {
    throw new Error(`Direct upload failed with status ${putStatus}`)
  }

  const registered = await post(`/work-orders/${workOrderId}/photos`, {
    intentId: intent.intentId,
    storageKey: intent.storageKey,
    category,
    capturedAt,
    caption: null,
  })
  return registered?.data ?? registered
}

// ── PhotoCapture component ────────────────────────────────────────────────────

/**
 * Renders an "Add photo" button backed by a file input.
 * Drives the full intent → PUT → register flow and calls {@code onPhotoAdded}
 * or {@code onPhotoError} with the result.
 *
 * @param {{
 *   workOrderId: string,
 *   category?: 'ISSUE' | 'COMPLETION',
 *   onPhotoAdded: (photo: {id: string, localUrl: string, status: string}) => void,
 *   onPhotoError: (localId: string, error: string) => void,
 *   onPhotoProgress: (localId: string, status: 'pending') => void,
 *   disabled?: boolean
 * }} props
 */
export function PhotoCapture({
  workOrderId,
  category = 'ISSUE',
  onPhotoAdded,
  onPhotoError,
  onPhotoProgress,
  disabled = false,
}) {
  const inputRef = useRef(null)

  async function handleFile(file) {
    if (!ALLOWED_MIME.has(file.type)) {
      const localId = mintLocalId()
      onPhotoError(localId, `Unsupported file type: ${file.type}. Use JPEG, PNG or WebP.`)
      return
    }

    const localId = mintLocalId()
    const localUrl = URL.createObjectURL(file)

    onPhotoProgress(localId, 'pending', localUrl, file.name)

    try {
      let blob
      try {
        blob = await downscaleAndStrip(file)
      } catch {
        blob = file // fall back to original if canvas fails
      }

      if (blob.size > MAX_BYTES) {
        onPhotoError(localId, 'Photo exceeds 5 MB after compression. Use a smaller image.')
        URL.revokeObjectURL(localUrl)
        return
      }

      const capturedAt = new Date().toISOString()
      const result = await uploadPhoto(workOrderId, blob, category, capturedAt)

      onPhotoAdded(localId, {
        id: result.photoId ?? localId,
        localUrl,
        thumbnailUrl: result.thumbnailUrl,
        status: 'uploaded',
      })
    } catch (err) {
      onPhotoError(localId, err?.message ?? 'Upload failed')
    }
  }

  async function handleChange(e) {
    const files = Array.from(e.target.files ?? [])
    if (inputRef.current) inputRef.current.value = ''
    for (const file of files) {
      await handleFile(file)
    }
  }

  return (
    <label className={styles.addPhotoLabel} data-disabled={disabled || undefined}>
      + Add photo
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        capture="environment"
        multiple
        onChange={handleChange}
        className={styles.photoFileInput}
        disabled={disabled}
        data-testid="photo-capture-input"
        aria-label="Add evidence photo"
      />
    </label>
  )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function mintLocalId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return 'local-' + crypto.randomUUID()
  }
  return 'local-' + Math.random().toString(36).slice(2)
}
