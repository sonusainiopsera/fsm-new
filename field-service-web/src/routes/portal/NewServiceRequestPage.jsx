/**
 * NewServiceRequestPage — guided form for submitting a portal service request.
 *
 * AC coverage (WO-174):
 * - AC-1  Site select scoped to customer's own sites; asset select filtered by site.
 * - AC-1  Client-side validation mirrors server allow-list rules.
 * - AC-1  Server field errors rendered inline against correct field.
 * - AC-3  Idempotency-Key generated once per form instance, reused on retry.
 * - AC-3  Submit disabled during flight — double-tap cannot create two requests.
 * - AC-2  Success shows reference + commitments + navigates to status view.
 * - AC-7  Appearance switch inherited from AppearanceProvider.
 * - AC-8  Customer density tokens; plain-language labels.
 * - No internal state enums, dispatch scores, codes, or PII in DOM.
 */

import React, { useCallback, useId, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';

import {
  PageHeader,
  FormField,
  Button,
  EmptyState,
  StateSurface,
} from '../../components/index.js';

import { fetchPortalSites, fetchSiteAssets, submitServiceRequest } from '../../api/portalClient.js';

import styles from './NewServiceRequestPage.module.css';

const MAX_FAULT_DESCRIPTION = 2000;
const MIN_FAULT_DESCRIPTION = 4;

/**
 * Generates a stable per-mount UUID for Idempotency-Key.
 * Stable across retries — regenerated only when the user explicitly resets.
 */
function useIdempotencyKey() {
  const ref = useRef(crypto.randomUUID());
  const rotate = useCallback(() => { ref.current = crypto.randomUUID(); }, []);
  return [ref, rotate];
}

export default function NewServiceRequestPage() {
  const navigate = useNavigate();
  const formHeadingId = useId();
  const [idempotencyKeyRef, rotateIdempotencyKey] = useIdempotencyKey();

  // ── Form state ──────────────────────────────────────────────────────────────
  const [siteId, setSiteId] = useState('');
  const [assetId, setAssetId] = useState('');
  const [faultDescription, setFaultDescription] = useState('');
  const [contactPreference, setContactPreference] = useState('EMAIL');
  const [clientErrors, setClientErrors] = useState({});

  // ── Sites query ─────────────────────────────────────────────────────────────
  const {
    data: sitesData,
    isLoading: sitesLoading,
    isError: sitesError,
  } = useQuery({
    queryKey: ['portal', 'sites'],
    queryFn: ({ signal }) => fetchPortalSites({ signal }),
    staleTime: 5 * 60_000,
    retry: false,
  });

  const sites = sitesData?.data ?? [];

  // ── Assets query (fires when a site is selected) ─────────────────────────────
  const {
    data: assetsData,
    isLoading: assetsLoading,
  } = useQuery({
    queryKey: ['portal', 'sites', siteId, 'assets'],
    queryFn: ({ signal }) => fetchSiteAssets(siteId, { signal }),
    enabled: Boolean(siteId),
    staleTime: 5 * 60_000,
    retry: false,
  });

  const assets = assetsData?.data ?? [];

  // ── Submission mutation ───────────────────────────────────────────────────────
  const {
    mutate: submit,
    isPending,
    error: serverError,
  } = useMutation({
    mutationFn: (body) => submitServiceRequest(body, idempotencyKeyRef.current),
    onSuccess: (response) => {
      navigate(`/portal/status/${encodeURIComponent(response.workOrderId)}`, {
        state: { reference: response.reference, respondByAt: response.respondByAt, resolveByAt: response.resolveByAt },
        replace: false,
      });
    },
    onError: () => {
      // Rotate the key only on non-idempotency errors so retries of the same
      // intent reuse the key (AC-3). The server deduplicates on the original key.
    },
  });

  // ── Client-side validation ─────────────────────────────────────────────────
  const validate = useCallback(() => {
    const errs = {};
    if (!siteId) errs.siteId = 'Please select a site.';
    if (!faultDescription.trim()) {
      errs.faultDescription = 'Please describe the fault.';
    } else if (faultDescription.trim().length < MIN_FAULT_DESCRIPTION) {
      errs.faultDescription = `Description must be at least ${MIN_FAULT_DESCRIPTION} characters.`;
    } else if (faultDescription.length > MAX_FAULT_DESCRIPTION) {
      errs.faultDescription = `Description must not exceed ${MAX_FAULT_DESCRIPTION} characters.`;
    }
    if (!contactPreference) errs.contactPreference = 'Please select a contact preference.';
    return errs;
  }, [siteId, faultDescription, contactPreference]);

  // ── Server field error mapping ─────────────────────────────────────────────
  const serverFieldErrors = useMemo(() => {
    if (!serverError?.fieldErrors) return {};
    const map = {};
    for (const fe of serverError.fieldErrors) {
      map[fe.field] = (map[fe.field] ?? []).concat(fe.message);
    }
    return map;
  }, [serverError]);

  // ── Submit handler ─────────────────────────────────────────────────────────
  const handleSubmit = useCallback((e) => {
    e.preventDefault();
    const errs = validate();
    setClientErrors(errs);
    if (Object.keys(errs).length > 0) return;

    submit({
      siteId,
      assetId: assetId || null,
      faultDescription: faultDescription.trim(),
      contactPreference,
    });
  }, [validate, submit, siteId, assetId, faultDescription, contactPreference]);

  const handleSiteChange = useCallback((e) => {
    setSiteId(e.target.value);
    setAssetId('');
  }, []);

  // ── Empty state: no sites ──────────────────────────────────────────────────
  if (!sitesLoading && sites.length === 0 && !sitesError) {
    return (
      <div className={styles.page}>
        <PageHeader title="New service request" />
        <EmptyState
          title="No sites registered"
          description="You don't have any sites linked to your account yet. Please contact us to have a site added."
        />
      </div>
    );
  }

  const charsLeft = MAX_FAULT_DESCRIPTION - faultDescription.length;
  const submitDisabled = isPending || sitesLoading;

  // Merge client + server field errors (client wins)
  function fieldErrors(name) {
    const client = clientErrors[name];
    const server = serverFieldErrors[name];
    if (client) return [client];
    if (server) return server;
    return [];
  }

  const generalServerError =
    serverError && !serverError.fieldErrors?.length
      ? (serverError.message ?? 'Something went wrong. Please try again.')
      : null;

  return (
    <div className={styles.page}>
      <PageHeader title="New service request" />

      {sitesError && (
        <div role="alert" className={styles.errorAlert}>
          We couldn't load your sites. Please refresh to try again.
        </div>
      )}

      {generalServerError && (
        <div role="alert" className={styles.errorAlert} aria-live="assertive">
          {generalServerError}
        </div>
      )}

      <form
        className={styles.form}
        onSubmit={handleSubmit}
        aria-labelledby={formHeadingId}
        noValidate
      >
        <h2 id={formHeadingId} className={styles.sectionHeading}>
          Tell us about the issue
        </h2>

        {/* Site select */}
        <FormField
          label="Site"
          required
          errors={fieldErrors('siteId')}
          help="Select the site where the issue is occurring."
        >
          <select
            value={siteId}
            onChange={handleSiteChange}
            disabled={sitesLoading}
            className={styles.select}
          >
            <option value="">
              {sitesLoading ? 'Loading sites…' : '— choose a site —'}
            </option>
            {sites.map((s) => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
        </FormField>

        {/* Asset select (optional) */}
        <FormField
          label="Asset (optional)"
          errors={fieldErrors('assetId')}
          help="If the issue is with a specific piece of equipment, select it here."
        >
          <select
            value={assetId}
            onChange={(e) => setAssetId(e.target.value)}
            disabled={!siteId || assetsLoading}
            className={styles.select}
          >
            <option value="">
              {!siteId ? 'Select a site first' : assetsLoading ? 'Loading assets…' : '— no specific asset —'}
            </option>
            {assets.map((a) => (
              <option key={a.id} value={a.id}>
                {[a.assetTag, a.model].filter(Boolean).join(' — ') || a.assetType}
              </option>
            ))}
          </select>
        </FormField>

        {/* Fault description with live counter */}
        <FormField
          label="Describe the fault"
          required
          errors={fieldErrors('faultDescription')}
          help={`Explain what is wrong, in as much detail as you can (${MIN_FAULT_DESCRIPTION}–${MAX_FAULT_DESCRIPTION} characters).`}
        >
          <textarea
            value={faultDescription}
            onChange={(e) => setFaultDescription(e.target.value)}
            rows={5}
            maxLength={MAX_FAULT_DESCRIPTION}
            className={styles.textarea}
            placeholder="E.g. The HVAC unit on floor 2 is making a loud grinding noise and not cooling the space…"
          />
        </FormField>
        <p
          className={[styles.charCount, charsLeft < 100 ? styles.charCountWarn : ''].filter(Boolean).join(' ')}
          aria-live="polite"
          aria-atomic="true"
        >
          {charsLeft} characters remaining
        </p>

        {/* Contact preference */}
        <FormField
          label="How should we contact you?"
          required
          errors={fieldErrors('contactPreference')}
        >
          <div className={styles.radioGroup} role="group" aria-label="Contact preference">
            {[
              { value: 'EMAIL', label: 'Email' },
              { value: 'PHONE', label: 'Phone' },
            ].map(({ value, label }) => (
              <label key={value} className={styles.radioLabel}>
                <input
                  type="radio"
                  name="contactPreference"
                  value={value}
                  checked={contactPreference === value}
                  onChange={() => setContactPreference(value)}
                  className={styles.radio}
                />
                {label}
              </label>
            ))}
          </div>
        </FormField>

        <div className={styles.actions}>
          <Button
            type="submit"
            variant="primary"
            loading={isPending}
            disabled={submitDisabled}
            aria-label={isPending ? 'Submitting your request…' : 'Submit service request'}
          >
            {isPending ? 'Submitting…' : 'Submit request'}
          </Button>
        </div>
      </form>
    </div>
  );
}
