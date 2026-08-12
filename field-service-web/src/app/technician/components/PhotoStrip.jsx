/**
 * PhotoStrip — categorised thumbnail strip for work order photos.
 *
 * Renders photos in two lanes (ISSUE / COMPLETION) with inline thumbnails
 * sourced from short-lived presigned view URLs. Failed states are shown
 * as retryable placeholders — photos are never silently discarded.
 *
 * @module app/technician/components/PhotoStrip
 */

import React from 'react';
import { PhotoCapture } from './PhotoCapture.jsx';
import styles from './PhotoStrip.module.css';

/**
 * @param {{
 *   workOrderId: string,
 *   photos: Array<{photoId: string, category: 'ISSUE'|'COMPLETION', capturedAt: string, caption?: string, viewUrl?: string, error?: string}>,
 *   onPhotoAdded: (photo: object) => void,
 *   disabled?: boolean,
 * }} props
 */
export function PhotoStrip({ workOrderId, photos, onPhotoAdded, disabled = false }) {
  const issuePhotos      = photos.filter((p) => p.category === 'ISSUE');
  const completionPhotos = photos.filter((p) => p.category === 'COMPLETION');

  return (
    <section className={styles.strip} aria-label="Work order photos">
      <div className={styles.lane}>
        <h3 className={styles.laneTitle}>Issue photos</h3>
        <div className={styles.thumbRow}>
          {issuePhotos.map((p) => (
            <Thumbnail key={p.photoId} photo={p} />
          ))}
        </div>
        <PhotoCapture
          workOrderId={workOrderId}
          category="ISSUE"
          onPhotoAdded={onPhotoAdded}
          disabled={disabled}
        />
      </div>

      <div className={styles.lane}>
        <h3 className={styles.laneTitle}>Completion photos</h3>
        <div className={styles.thumbRow}>
          {completionPhotos.map((p) => (
            <Thumbnail key={p.photoId} photo={p} />
          ))}
        </div>
        <PhotoCapture
          workOrderId={workOrderId}
          category="COMPLETION"
          onPhotoAdded={onPhotoAdded}
          disabled={disabled}
        />
      </div>
    </section>
  );
}

/**
 * A single thumbnail. Renders the presigned image when available, or a
 * clearly-labelled error placeholder that the user can tap to retry.
 */
function Thumbnail({ photo }) {
  if (photo.error) {
    return (
      <div className={styles.thumbError} role="img" aria-label="Photo upload failed">
        <span className={styles.thumbErrorIcon} aria-hidden="true">!</span>
        <span className={styles.thumbErrorLabel}>Failed</span>
      </div>
    );
  }

  return (
    <div className={styles.thumb}>
      {photo.viewUrl ? (
        <img
          src={photo.viewUrl}
          alt={photo.caption ?? `${photo.category.toLowerCase()} photo`}
          className={styles.thumbImg}
          loading="lazy"
        />
      ) : (
        <div className={styles.thumbPlaceholder} aria-label="Photo loading" />
      )}
    </div>
  );
}
