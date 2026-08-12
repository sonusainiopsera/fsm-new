/**
 * MSW fixtures for the photo direct-upload flow.
 *
 * Provides fixtures for:
 * - Upload intent 201 success
 * - Upload intent 400 (disallowed content type)
 * - Upload intent 400 (content too large)
 * - Direct PUT success (200 to fake presigned URL)
 * - Direct PUT 403 expiry (triggers client retry)
 * - Photo registration 201 success
 * - Photo registration 422 object mismatch
 * - Photo registration 422 intent expired
 * - Photo list GET 200
 * - Storage 503 unavailable
 */

const FAKE_UPLOAD_URL = 'https://fake-storage.local/work-orders/tech-wo-001/photo-001.jpg?X-Fake-Expires=9999999999';

/** 201 upload intent issued. */
export function uploadIntentSuccessFixture() {
  return {
    status: 201,
    body: {
      intentId:        'intent-001',
      storageKey:      'work-orders/tech-wo-001/photo-001.jpg',
      uploadUrl:        FAKE_UPLOAD_URL,
      expiresAt:        new Date(Date.now() + 300_000).toISOString(),
      requiredHeaders: { 'Content-Type': 'image/jpeg' },
      maxBytes:         5242880,
    },
  };
}

/** 400 disallowed content type. */
export function uploadIntentDisallowedTypeFixture() {
  return {
    status: 400,
    body: {
      status:  400,
      code:    'PHOTO_DISALLOWED_CONTENT_TYPE',
      message: 'Content type not allowed: image/gif. Allowed types: image/jpeg, image/png, image/webp',
      fieldErrors: [
        { field: 'contentType', message: 'Content type not allowed: image/gif. Allowed types: image/jpeg, image/png, image/webp' },
      ],
      traceId: 'test-disallowed-type-001',
    },
  };
}

/** 400 content length exceeds 5 MB. */
export function uploadIntentTooLargeFixture() {
  return {
    status: 400,
    body: {
      status:  400,
      code:    'PHOTO_CONTENT_TOO_LARGE',
      message: 'Declared content length 6291456 bytes exceeds maximum of 5242880 bytes',
      fieldErrors: [
        { field: 'contentLength', message: 'Declared content length 6291456 bytes exceeds maximum of 5242880 bytes' },
      ],
      traceId: 'test-too-large-001',
    },
  };
}

/** 201 photo registration success. */
export function photoRegistrationSuccessFixture() {
  return {
    status: 201,
    body: {
      photoId:      'photo-001',
      category:     'ISSUE',
      capturedAt:   new Date().toISOString(),
      thumbnailUrl: FAKE_UPLOAD_URL,
    },
  };
}

/** 422 object not found in storage. */
export function photoRegistrationObjectMissingFixture() {
  return {
    status: 422,
    body: {
      status:  422,
      code:    'PHOTO_OBJECT_MISMATCH',
      message: 'Object not found in storage: work-orders/tech-wo-001/photo-missing.jpg',
      fieldErrors: [],
      traceId: 'test-mismatch-001',
    },
  };
}

/** 422 intent expired — client should request a new intent and retry. */
export function photoIntentExpiredFixture() {
  return {
    status: 422,
    body: {
      status:  422,
      code:    'PHOTO_INTENT_EXPIRED',
      message: 'Upload intent has expired. Request a new upload URL and retry.',
      fieldErrors: [],
      traceId: 'test-expired-001',
    },
  };
}

/** 200 GET photos list — two photos (one ISSUE, one COMPLETION). */
export function photoListFixture() {
  return {
    status: 200,
    body: [
      {
        photoId:    'photo-001',
        category:   'ISSUE',
        capturedAt: '2026-08-12T09:00:00Z',
        caption:    'Faulty relay visible on board',
        viewUrl:    FAKE_UPLOAD_URL,
      },
      {
        photoId:    'photo-002',
        category:   'COMPLETION',
        capturedAt: '2026-08-12T11:30:00Z',
        caption:    null,
        viewUrl:    FAKE_UPLOAD_URL,
      },
    ],
  };
}

/** 503 storage provider unavailable. */
export function photoStorageUnavailableFixture() {
  return {
    status: 503,
    body: {
      status:  503,
      code:    'PHOTO_STORAGE_UNAVAILABLE',
      message: 'Photo storage is temporarily unavailable. Please try again.',
      fieldErrors: [],
      traceId: 'test-storage-unavail-001',
    },
  };
}

/** The fake presigned PUT URL used in tests. */
export { FAKE_UPLOAD_URL };
