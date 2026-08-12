/**
 * CsvImportWizard — bulk CSV import for technician skills and certifications.
 *
 * Flow:
 *   1. File select — validates size and extension client-side.
 *   2. Parse — papaparse-style manual parse (no third-party dep), handles BOM,
 *      CRLF, quoted fields, and wrong column order.
 *   3. Preview — shows per-row validation status before committing.
 *   4. Commit — POSTs in chunks of at most 200 rows with a stable Idempotency-Key
 *      per chunk so a network retry cannot double-insert.
 *   5. Results — aggregated per-row outcome with downloadable error report.
 *
 * Certification currency: NEVER computed client-side. Only server-returned
 * values are rendered (this wizard submits, it does not display currency status).
 *
 * @module features/admin/CsvImportWizard
 */

import React, { useState, useCallback, useId } from 'react';
import { Modal, Button, FormField } from '../../components/index.js';
import { upsertTechnicianSkills, upsertTechnicianCertifications } from '../../api/workforce.js';
import { newAttemptKey } from '../../lib/idempotency.js';
import styles from './CsvImportWizard.module.css';
import adminStyles from './admin.module.css';

const CHUNK_SIZE = 200;
const MAX_ROWS   = 5000;

// --- CSV Schema -----------------------------------------------------------

/** @type {Record<string, { required: string[], optional: string[] }>} */
const SCHEMAS = {
  skills: {
    required: ['technicianId', 'skillCode'],
    optional: ['proficiencyLevel'],
  },
  certifications: {
    required: ['technicianId', 'typeCode'],
    optional: ['certificateReference', 'issuedOn', 'expiresOn', 'issuingBody'],
  },
};

// --- CSV parsing ----------------------------------------------------------

/**
 * Minimal CSV parser: handles BOM, CRLF, quoted commas, trailing newlines.
 * Returns { headers: string[], rows: string[][], error: string|null }
 *
 * @param {string} raw
 * @returns {{ headers: string[], rows: string[][], error: string | null }}
 */
function parseCsv(raw) {
  // Strip BOM
  const text = raw.replace(/^﻿/, '');
  // Normalise line endings
  const lines = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
  // Drop trailing empty lines
  const nonEmpty = lines.filter((l, i) => i === 0 || l.trim() !== '');
  if (nonEmpty.length < 2) {
    return { headers: [], rows: [], error: 'CSV file must contain a header row and at least one data row.' };
  }

  const headers = splitCsvRow(nonEmpty[0]);
  const rows    = nonEmpty.slice(1).map(splitCsvRow);
  return { headers, rows, error: null };
}

/**
 * Splits a single CSV row respecting double-quoted fields.
 * @param {string} line
 * @returns {string[]}
 */
function splitCsvRow(line) {
  const cells = [];
  let cur  = '';
  let inQ  = false;

  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      if (inQ && line[i + 1] === '"') {
        cur += '"'; i++;
      } else {
        inQ = !inQ;
      }
    } else if (ch === ',' && !inQ) {
      cells.push(cur);
      cur = '';
    } else {
      cur += ch;
    }
  }
  cells.push(cur);
  return cells.map((c) => c.trim());
}

// --- Row validation -------------------------------------------------------

/**
 * @param {string[]} headers
 * @param {string[]} values
 * @param {string} mode
 * @returns {{ row: object, error: string | null }}
 */
function validateRow(headers, values, mode) {
  const schema = SCHEMAS[mode];
  if (!schema) return { row: {}, error: `Unknown mode: ${mode}` };

  // Map by header name (case-insensitive, handles wrong column order)
  const map = {};
  for (let i = 0; i < headers.length; i++) {
    map[headers[i].toLowerCase()] = values[i] ?? '';
  }

  // Check required fields
  for (const field of schema.required) {
    if (!map[field.toLowerCase()]) {
      return { row: map, error: `Missing required field: ${field}` };
    }
  }

  // Build typed row
  const row = {};
  for (const field of [...schema.required, ...schema.optional]) {
    const val = map[field.toLowerCase()];
    if (val !== undefined && val !== '') row[field] = val;
  }

  return { row, error: null };
}

// --- Chunked submit -------------------------------------------------------

/**
 * Splits validated rows into chunks and submits each with a stable idempotency key.
 * Returns per-row results: { typeCode|skillCode, outcome, error }
 *
 * @param {Array<{ row: object, error: string | null }>} validatedRows
 * @param {string} mode
 * @param {string | undefined} defaultTechnicianId
 * @param {(done: number, total: number) => void} onProgress
 * @returns {Promise<Array<{ key: string, outcome: string, error: string | null }>>}
 */
async function submitChunks(validatedRows, mode, defaultTechnicianId, onProgress) {
  const results = [];

  // Group by technicianId
  /** @type {Map<string, Array<{ row: object, index: number }>>} */
  const byTech = new Map();
  for (let i = 0; i < validatedRows.length; i++) {
    const { row, error } = validatedRows[i];
    if (error) {
      results.push({ key: row.technicianId ?? String(i), index: i, outcome: 'SKIPPED', error });
      continue;
    }
    const techId = row.technicianId ?? defaultTechnicianId;
    if (!techId) {
      results.push({ key: String(i), index: i, outcome: 'SKIPPED', error: 'technicianId is required' });
      continue;
    }
    const list = byTech.get(techId) ?? [];
    list.push({ row, index: i });
    byTech.set(techId, list);
  }

  let done = results.length;
  const total = validatedRows.length;

  for (const [techId, entries] of byTech.entries()) {
    // Chunk into batches of CHUNK_SIZE
    for (let c = 0; c < entries.length; c += CHUNK_SIZE) {
      const chunk   = entries.slice(c, c + CHUNK_SIZE);
      const items   = chunk.map(({ row }) => {
        const { technicianId: _t, ...rest } = row;
        return rest;
      });
      const chunkKey = newAttemptKey();

      try {
        let apiResults;
        if (mode === 'certifications') {
          apiResults = await upsertTechnicianCertifications(techId, items, { idempotencyKey: chunkKey });
        } else {
          apiResults = await upsertTechnicianSkills(techId, items, { idempotencyKey: chunkKey });
        }
        // Server returns per-row outcome array
        const rowResults = Array.isArray(apiResults) ? apiResults : [];
        for (let i = 0; i < chunk.length; i++) {
          const rr = rowResults[i];
          results.push({
            key: items[i].typeCode ?? items[i].skillCode ?? String(chunk[i].index),
            index: chunk[i].index,
            outcome: rr?.outcome ?? 'COMMITTED',
            error: rr?.error ?? null,
          });
        }
      } catch (err) {
        // Entire chunk failed — mark all rows in chunk as ERROR
        for (const entry of chunk) {
          results.push({
            key: items[0]?.typeCode ?? String(entry.index),
            index: entry.index,
            outcome: 'ERROR',
            error: err?.message ?? 'Network error',
          });
        }
      }

      done += chunk.length;
      onProgress(done, total);
    }
  }

  return results;
}

// --- Error report download ------------------------------------------------

/**
 * Generates and downloads a CSV error report for rejected rows.
 * @param {Array<{ key: string, index: number, outcome: string, error: string | null }>} results
 */
function downloadErrorReport(results) {
  const failed = results.filter((r) => r.error || r.outcome === 'ERROR' || r.outcome === 'SKIPPED');
  if (failed.length === 0) return;

  const rows = [
    ['row', 'key', 'outcome', 'error'],
    ...failed.map((r) => [String(r.index + 2), r.key, r.outcome, r.error ?? '']),
  ];
  const csv = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href     = url;
  a.download = 'import-errors.csv';
  a.click();
  URL.revokeObjectURL(url);
}

// --- Component -----------------------------------------------------------

/**
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   mode: 'skills' | 'certifications',
 *   technicianId?: string,
 * }} props
 */
export function CsvImportWizard({ open, onClose, mode = 'certifications', technicianId }) {
  const fileInputId = useId();

  const [step, setStep]           = useState('select');   // select | preview | results
  const [file, setFile]           = useState(null);
  const [parseError, setParseError] = useState(null);
  const [headers, setHeaders]     = useState([]);
  const [validated, setValidated] = useState([]);         // { row, error }
  const [progress, setProgress]   = useState({ done: 0, total: 0 });
  const [importing, setImporting] = useState(false);
  const [results, setResults]     = useState([]);         // after submit

  const schema = SCHEMAS[mode] ?? SCHEMAS.certifications;
  const allCols = [...schema.required, ...schema.optional];

  // Reset on close
  const handleClose = useCallback(() => {
    setStep('select'); setFile(null); setParseError(null);
    setHeaders([]); setValidated([]); setResults([]); setImporting(false);
    onClose();
  }, [onClose]);

  // File selection + parse
  function handleFileChange(e) {
    const f = e.target.files?.[0];
    if (!f) return;

    if (!f.name.toLowerCase().endsWith('.csv')) {
      setParseError('Only .csv files are accepted.'); return;
    }
    if (f.size > 5 * 1024 * 1024) {
      setParseError('File is too large. Maximum 5 MB.'); return;
    }

    setFile(f);
    setParseError(null);

    const reader = new FileReader();
    reader.onload = (ev) => {
      const { headers: h, rows, error } = parseCsv(ev.target.result);
      if (error) { setParseError(error); return; }
      if (rows.length > MAX_ROWS) {
        setParseError(`File contains ${rows.length} data rows. Maximum is ${MAX_ROWS}.`);
        return;
      }
      setHeaders(h);
      setValidated(rows.map((r) => validateRow(h, r, mode)));
      setStep('preview');
    };
    reader.readAsText(f, 'utf-8');
  }

  // Commit
  async function handleCommit() {
    setImporting(true);
    setProgress({ done: 0, total: validated.length });
    try {
      const res = await submitChunks(
        validated, mode, technicianId,
        (done, total) => setProgress({ done, total }),
      );
      setResults(res);
      setStep('results');
    } finally {
      setImporting(false);
    }
  }

  const failCount    = validated.filter((r) => r.error).length;
  const validCount   = validated.length - failCount;
  const errorResults = results.filter((r) => r.error || r.outcome === 'ERROR' || r.outcome === 'SKIPPED');

  return (
    <Modal
      open={open}
      title={`Import ${mode === 'skills' ? 'Skills' : 'Certifications'} from CSV`}
      onClose={handleClose}
    >
      {/* Step 1 — select */}
      {step === 'select' && (
        <div className={styles.step}>
          <p className={styles.hint}>
            Upload a CSV file. Required columns: <strong>{schema.required.join(', ')}</strong>.
            Optional: <em>{schema.optional.join(', ')}</em>.
            Maximum {MAX_ROWS} rows per file. Column order does not matter.
          </p>

          {parseError && (
            <div className={adminStyles.errorSummary} role="alert">
              {parseError}
            </div>
          )}

          <FormField label="CSV file">
            <input
              id={fileInputId}
              type="file"
              accept=".csv"
              onChange={handleFileChange}
            />
          </FormField>

          <a
            href={`data:text/plain,${encodeURIComponent(allCols.join(','))}\n`}
            download={`${mode}-template.csv`}
            className={styles.templateLink}
          >
            Download column template
          </a>
        </div>
      )}

      {/* Step 2 — preview */}
      {step === 'preview' && (
        <div className={styles.step}>
          <p className={styles.hint}>
            Parsed <strong>{validated.length}</strong> rows —{' '}
            <span className={validCount > 0 ? styles.valid : ''}>
              {validCount} valid
            </span>
            {failCount > 0 && (
              <>, <span className={styles.invalid}>{failCount} invalid (will be skipped)</span></>
            )}.
            Review before committing.
          </p>

          <div className={styles.previewScroll} role="region" aria-label="Preview table" tabIndex={0}>
            <table className={styles.previewTable}>
              <thead>
                <tr>
                  <th>Row</th>
                  {headers.map((h) => <th key={h}>{h}</th>)}
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {validated.slice(0, 100).map((vr, i) => (
                  <tr key={i} className={vr.error ? styles.rowError : styles.rowOk}>
                    <td>{i + 2}</td>
                    {headers.map((h) => (
                      <td key={h}>{vr.row[h.toLowerCase()] ?? ''}</td>
                    ))}
                    <td>
                      {vr.error
                        ? <span className={styles.errorTag}>✕ {vr.error}</span>
                        : <span className={styles.okTag}>✓ Valid</span>}
                    </td>
                  </tr>
                ))}
                {validated.length > 100 && (
                  <tr>
                    <td colSpan={headers.length + 2} className={styles.truncated}>
                      … and {validated.length - 100} more rows (not shown in preview)
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {importing && (
            <p className={styles.progress} aria-live="polite">
              Importing… {progress.done} / {progress.total} rows
            </p>
          )}

          <div className={adminStyles.formActions}>
            <Button type="button" variant="ghost" onClick={() => setStep('select')}>Back</Button>
            <Button
              type="button"
              onClick={handleCommit}
              loading={importing}
              disabled={validCount === 0}
            >
              Commit {validCount} row{validCount !== 1 ? 's' : ''}
            </Button>
          </div>
        </div>
      )}

      {/* Step 3 — results */}
      {step === 'results' && (
        <div className={styles.step}>
          <p className={styles.hint} aria-live="polite">
            Import complete. <strong>{results.filter((r) => r.outcome === 'COMMITTED').length}</strong> committed,{' '}
            <strong>{errorResults.length}</strong> failed/skipped.
          </p>

          {errorResults.length > 0 && (
            <>
              <div className={styles.previewScroll} role="region" aria-label="Error rows" tabIndex={0}>
                <table className={styles.previewTable}>
                  <thead>
                    <tr><th>Row</th><th>Key</th><th>Outcome</th><th>Error</th></tr>
                  </thead>
                  <tbody>
                    {errorResults.map((r, i) => (
                      <tr key={i} className={styles.rowError}>
                        <td>{r.index + 2}</td>
                        <td>{r.key}</td>
                        <td>{r.outcome}</td>
                        <td>{r.error}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Button variant="secondary" onClick={() => downloadErrorReport(results)}>
                Download error report
              </Button>
            </>
          )}

          <div className={adminStyles.formActions}>
            <Button type="button" variant="ghost" onClick={handleClose}>Close</Button>
          </div>
        </div>
      )}
    </Modal>
  );
}
