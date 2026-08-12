/**
 * PhotoCapture
 *
 * Technician-facing component for the photo capture → AI analysis → description flow.
 *
 * Flow:
 *   1. Capture / select photo (file input)
 *   2. Upload directly to object storage (presigned PUT — binary never transits the API tier)
 *   3. Register the photo metadata
 *   4. Trigger AI analysis (when ai.photo-analysis.enabled = true)
 *   5. Show editable pre-filled description or plain field (on degraded)
 *
 * Rendering safety:
 *   - Suggestion is only ever set as a controlled textarea value.
 *   - No HTML rendering of model output.
 *   - No auto-submit.
 *
 * Touch target: every interactive control is min 44×44 px at 360 px viewport.
 *
 * @module features/photoanalysis/PhotoCapture
 */

import { useState, useCallback, useRef } from 'react';
import { useAnalyzePhoto } from './useAnalyzePhoto.js';
import { SuggestedDescriptionField } from './SuggestedDescriptionField.jsx';
import {
  IDLE,
  UPLOADING,
  ANALYZING,
  SUGGESTED,
  EDITING,
  DISCARDED,
  DEGRADED,
  SUBMITTED,
} from './photoAnalysisStates.js';

const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_BYTES     = 5 * 1024 * 1024;

/**
 * @param {object}   props
 * @param {string}   props.workOrderId
 * @param {Function} props.onDescriptionSubmit   called with { description, photoId, interactionId }
 * @param {Function} [props.onPhotoRegistered]   called when photo is registered without analysis
 * @param {boolean}  [props.analysisEnabled]     feature flag passed from server config
 */
export function PhotoCapture({
  workOrderId,
  onDescriptionSubmit,
  onPhotoRegistered,
  analysisEnabled = false,
}) {
  const [captureState, setCaptureState]       = useState(IDLE);
  const [description, setDescription]         = useState('');
  const [suggestion, setSuggestion]           = useState(null);
  const [interactionId, setInteractionId]     = useState(null);
  const [photoId, setPhotoId]                 = useState(null);
  const [uploadProgress, setUploadProgress]   = useState(0);
  const [errorMessage, setErrorMessage]       = useState(null);

  const fileInputRef = useRef(null);

  const { mutateAsync: analyzePhoto } = useAnalyzePhoto();

  const handleFileSelected = useCallback(
    async (e) => {
      const file = e.target.files?.[0];
      if (!file) return;

      setErrorMessage(null);

      if (!ALLOWED_TYPES.includes(file.type)) {
        setErrorMessage('Only JPEG, PNG or WebP images are accepted.');
        return;
      }

      if (file.size > MAX_BYTES) {
        setErrorMessage('Image exceeds 5 MB limit. Please choose a smaller file.');
        return;
      }

      setCaptureState(UPLOADING);
      setUploadProgress(0);

      try {
        // 1. Get presigned PUT URL
        const intentRes = await fetch(`/api/v1/work-orders/${workOrderId}/photos/upload-intent`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            contentType: file.type,
            contentLength: file.size,
            category: 'ISSUE',
          }),
        });

        if (!intentRes.ok) {
          const body = await intentRes.json().catch(() => ({}));
          throw new Error(body.message || 'Upload intent failed');
        }

        const intent = await intentRes.json();
        setUploadProgress(20);

        // 2. PUT directly to object storage
        const putRes = await fetch(intent.uploadUrl, {
          method: 'PUT',
          headers: { 'Content-Type': file.type },
          body: file,
        });

        if (!putRes.ok) {
          throw new Error('Upload to storage failed');
        }

        setUploadProgress(70);

        // 3. Register photo metadata
        const regRes = await fetch(`/api/v1/work-orders/${workOrderId}/photos`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            storageKey: intent.storageKey,
            category: 'ISSUE',
            capturedAt: new Date().toISOString(),
          }),
        });

        if (!regRes.ok) {
          throw new Error('Photo registration failed');
        }

        const reg = await regRes.json();
        const registeredPhotoId = reg.photoId;
        setPhotoId(registeredPhotoId);
        setUploadProgress(100);

        if (onPhotoRegistered) {
          onPhotoRegistered(registeredPhotoId);
        }

        // 4. Trigger analysis (optional; non-fatal)
        if (analysisEnabled) {
          setCaptureState(ANALYZING);

          const analysisData = await analyzePhoto({
            workOrderId,
            photoId: registeredPhotoId,
            includeFaultContext: false,
          });

          if (analysisData?.degraded) {
            setCaptureState(DEGRADED);
          } else if (analysisData?.suggestedDescription) {
            setSuggestion(analysisData.suggestedDescription);
            setDescription(analysisData.suggestedDescription);
            setInteractionId(analysisData.interactionId);
            setCaptureState(SUGGESTED);
          } else {
            setCaptureState(DEGRADED);
          }
        } else {
          setCaptureState(DEGRADED); // flag off → plain description field
        }
      } catch (err) {
        setErrorMessage(err.message || 'An error occurred. Please try again.');
        setCaptureState(DEGRADED);
      }
    },
    [workOrderId, analysisEnabled, analyzePhoto, onPhotoRegistered]
  );

  const handleDescriptionChange = useCallback(
    (val) => {
      setDescription(val);
      if (captureState === SUGGESTED && val !== suggestion) {
        setCaptureState(EDITING);
      }
    },
    [captureState, suggestion]
  );

  const handleClearSuggestion = useCallback(() => {
    setDescription('');
    setSuggestion(null);
    setCaptureState(DISCARDED);
  }, []);

  const handleSubmit = useCallback(
    (e) => {
      e.preventDefault();
      if (onDescriptionSubmit) {
        onDescriptionSubmit({ description, photoId, interactionId });
      }
      setCaptureState(SUBMITTED);
    },
    [description, photoId, interactionId, onDescriptionSubmit]
  );

  // ── Render ──────────────────────────────────────────────────────────────────

  if (captureState === IDLE) {
    return (
      <div>
        {errorMessage && (
          <p role="alert" style={{ color: 'var(--color-error, #EF4444)', margin: '0 0 8px' }}>
            {errorMessage}
          </p>
        )}
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          style={captureButtonStyle}
          aria-label="Capture or select a photo"
        >
          📷 Add photo
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept={ALLOWED_TYPES.join(',')}
          onChange={handleFileSelected}
          style={{ display: 'none' }}
          aria-hidden="true"
        />
      </div>
    );
  }

  if (captureState === UPLOADING) {
    return (
      <div role="status" aria-live="polite">
        <p style={{ margin: 0 }}>Uploading… {uploadProgress}%</p>
        <progress value={uploadProgress} max={100} style={{ width: '100%', height: '8px' }} />
      </div>
    );
  }

  if (captureState === ANALYZING) {
    return (
      <div role="status" aria-live="polite">
        <p style={{ margin: 0 }}>Analysing photo…</p>
        <span aria-label="analysing" style={{ display: 'inline-block', animation: 'spin 1s linear infinite' }}>
          ⏳
        </span>
      </div>
    );
  }

  if (captureState === SUBMITTED) {
    return (
      <p role="status" style={{ color: 'var(--color-success, #22C55E)' }}>
        Description saved.
      </p>
    );
  }

  // SUGGESTED, EDITING, DISCARDED, DEGRADED — all show the description form
  return (
    <form onSubmit={handleSubmit}>
      {errorMessage && (
        <p role="alert" style={{ color: 'var(--color-error, #EF4444)', margin: '0 0 8px' }}>
          {errorMessage}
        </p>
      )}

      <SuggestedDescriptionField
        state={captureState}
        suggestion={suggestion}
        value={description}
        onChange={handleDescriptionChange}
        onClear={handleClearSuggestion}
      />

      <button
        type="submit"
        style={{ ...captureButtonStyle, marginTop: '12px' }}
        aria-label="Save description"
      >
        Save description
      </button>
    </form>
  );
}

// ── Shared style ──────────────────────────────────────────────────────────────

const captureButtonStyle = {
  display:         'inline-flex',
  alignItems:      'center',
  justifyContent:  'center',
  minWidth:        '44px',
  minHeight:       '44px',
  padding:         '10px 20px',
  background:      'var(--color-primary, #4F46E5)',
  color:           '#FFFFFF',
  borderRadius:    '6px',
  border:          'none',
  cursor:          'pointer',
  fontSize:        '1rem',
  fontWeight:      600,
};

export default PhotoCapture;
