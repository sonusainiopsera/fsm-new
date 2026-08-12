/**
 * PhotoCapture — camera/file capture component for field evidence photos.
 *
 * Flow:
 * 1. User selects / captures a photo via file input (capture="environment")
 * 2. Canvas downscale to ≤1920 px longest edge and re-encode as JPEG (strips EXIF)
 * 3. Request a presigned PUT intent from the server
 * 4. PUT the resized blob directly to the presigned URL (bypasses app tier)
 * 5. Register metadata with the server
 * 6. Report the completed photo to parent via onPhotoAdded(photo)
 *
 * On presigned URL expiry (PUT returns 403/400) the component transparently
 * requests a new intent and retries once, then surfaces a retryable failure state.
 *
 * @module app/technician/components/PhotoCapture
 */

import React, { useRef, useState } from 'react';
import { apiFetch } from '../../../api/http.js';
import styles from './PhotoCapture.module.css';

const MAX_LONG_EDGE_PX = 1920;
const JPEG_QUALITY     = 0.82;
const MAX_SIZE_BYTES   = 5 * 1024 * 1024;

/**
 * Downscales an image to ≤1920 px longest edge and re-encodes as JPEG.
 * Canvas re-encode inherently strips all EXIF metadata including GPS.
 *
 * @param {File} file source image file
 * @returns {Promise<Blob>} resized JPEG blob
 */
export async function downscaleAndStrip(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img  = new Image();

    img.onload = () => {
      URL.revokeObjectURL(url);
      const { naturalWidth: w, naturalHeight: h } = img;
      const longEdge = Math.max(w, h);
      const scale    = longEdge > MAX_LONG_EDGE_PX ? MAX_LONG_EDGE_PX / longEdge : 1;
      const dstW     = Math.round(w * scale);
      const dstH     = Math.round(h * scale);

      const canvas = document.createElement('canvas');
      canvas.width  = dstW;
      canvas.height = dstH;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, dstW, dstH);

      canvas.toBlob(
        (blob) => blob ? resolve(blob) : reject(new Error('Canvas toBlob returned null')),
        'image/jpeg',
        JPEG_QUALITY,
      );
    };

    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Failed to load image for downscale'));
    };

    img.src = url;
  });
}

/**
 * PUT the blob directly to the presigned URL.
 * Returns the response status. Does NOT go through the app tier.
 *
 * @param {string} uploadUrl   presigned PUT URL
 * @param {Blob}   blob        image blob
 * @param {string} contentType MIME type
 * @param {(pct: number) => void} onProgress progress callback 0–100
 */
async function putToStorage(uploadUrl, blob, contentType, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', uploadUrl, true);
    xhr.setRequestHeader('Content-Type', contentType);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100));
    };

    xhr.onload  = () => resolve(xhr.status);
    xhr.onerror = () => reject(new Error('Network error during storage PUT'));
    xhr.send(blob);
  });
}

/**
 * @param {{
 *   workOrderId: string,
 *   category: 'ISSUE' | 'COMPLETION',
 *   onPhotoAdded: (photo: {photoId: string, category: string, capturedAt: string, thumbnailUrl: string}) => void,
 *   disabled?: boolean,
 * }} props
 */
export function PhotoCapture({ workOrderId, category, onPhotoAdded, disabled = false }) {
  const inputRef = useRef(null);

  const [status, setStatus]         = useState('idle'); // idle | selecting | processing | uploading | registering | error
  const [progress, setProgress]     = useState(0);
  const [errorMessage, setError]    = useState('');
  const [lastBlob, setLastBlob]     = useState(null);
  const [lastIntent, setLastIntent] = useState(null);

  async function requestIntent(sizeBytes) {
    const res = await apiFetch(`/work-orders/${workOrderId}/photos/upload-intent`, {
      method: 'POST',
      body: JSON.stringify({
        contentType:   'image/jpeg',
        contentLength: sizeBytes,
        category,
      }),
    });
    return res.data ?? res;
  }

  async function register(storageKey, capturedAt) {
    const res = await apiFetch(`/work-orders/${workOrderId}/photos`, {
      method: 'POST',
      body: JSON.stringify({ storageKey, category, capturedAt, caption: null }),
    });
    return res.data ?? res;
  }

  async function runUpload(blob, attempt = 0) {
    setStatus('uploading');
    setProgress(0);

    const intent = await requestIntent(blob.size);
    setLastIntent(intent);

    const putStatus = await putToStorage(
      intent.uploadUrl,
      blob,
      'image/jpeg',
      (pct) => setProgress(pct),
    );

    if (putStatus === 403 || putStatus === 400) {
      if (attempt === 0) {
        // Presigned URL expired — request a new intent and retry once.
        return runUpload(blob, 1);
      }
      throw new Error('Upload failed after retry. Please try again.');
    }

    if (putStatus < 200 || putStatus >= 300) {
      throw new Error('Upload to storage failed (status ' + putStatus + '). Please try again.');
    }

    setStatus('registering');
    const capturedAt = new Date().toISOString();
    const photo = await register(intent.storageKey, capturedAt);
    return photo;
  }

  async function handleFileChange(e) {
    const file = e.target.files?.[0];
    if (!file) return;

    // Reset input so the same file can be re-selected after an error
    e.target.value = '';

    if (!file.type.startsWith('image/')) {
      setError('Only image files are supported.');
      setStatus('error');
      return;
    }

    try {
      setStatus('processing');
      setError('');

      const blob = await downscaleAndStrip(file);
      setLastBlob(blob);

      if (blob.size > MAX_SIZE_BYTES) {
        setError('Image is too large even after downscaling. Please try a different photo.');
        setStatus('error');
        return;
      }

      const photo = await runUpload(blob);
      setStatus('idle');
      setProgress(0);
      onPhotoAdded(photo);
    } catch (err) {
      setError(err.message ?? 'Upload failed. Please try again.');
      setStatus('error');
    }
  }

  async function handleRetry() {
    if (!lastBlob) return;
    setError('');
    try {
      const photo = await runUpload(lastBlob);
      setStatus('idle');
      setProgress(0);
      onPhotoAdded(photo);
    } catch (err) {
      setError(err.message ?? 'Upload failed. Please try again.');
      setStatus('error');
    }
  }

  const isWorking = status === 'processing' || status === 'uploading' || status === 'registering';
  const label     = category === 'ISSUE' ? 'Add issue photo' : 'Add completion photo';

  return (
    <div className={styles.capture}>
      {status !== 'error' && (
        <button
          type="button"
          className={styles.captureBtn}
          onClick={() => inputRef.current?.click()}
          disabled={disabled || isWorking}
          aria-busy={isWorking}
        >
          {isWorking ? statusLabel(status, progress) : label}
        </button>
      )}

      {status === 'error' && (
        <div className={styles.errorState} role="alert">
          <span className={styles.errorMsg}>{errorMessage}</span>
          <button
            type="button"
            className={styles.retryBtn}
            onClick={handleRetry}
          >
            Retry
          </button>
          <button
            type="button"
            className={styles.cancelErrBtn}
            onClick={() => { setStatus('idle'); setError(''); setLastBlob(null); }}
          >
            Cancel
          </button>
        </div>
      )}

      {status === 'uploading' && (
        <div className={styles.progressBar} role="progressbar"
             aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}>
          <div className={styles.progressFill} style={{ width: progress + '%' }} />
        </div>
      )}

      <input
        ref={inputRef}
        type="file"
        accept="image/*"
        capture="environment"
        className={styles.hiddenInput}
        onChange={handleFileChange}
        aria-hidden="true"
        tabIndex={-1}
      />
    </div>
  );
}

function statusLabel(status, progress) {
  if (status === 'processing')  return 'Processing…';
  if (status === 'uploading')   return progress > 0 ? `Uploading ${progress}%…` : 'Uploading…';
  if (status === 'registering') return 'Saving…';
  return 'Working…';
}
