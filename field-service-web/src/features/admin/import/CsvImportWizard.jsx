/**
 * @fileoverview CSV import wizard for technician skills and certifications.
 *
 * Flow: upload → parse → preview (per-row validation) → commit (chunked batches) → results
 *
 * AC-6: Batches of at most 200 rows with an Idempotency-Key per chunk so a
 * retry cannot double-insert. Offers a downloadable error report for rejected rows.
 *
 * @module features/admin/import/CsvImportWizard
 */
import { useState, useRef, useCallback } from 'react'
import { Modal } from '../../../components/index.js'
import { Button } from '../../../components/index.js'
import {
  parseCsvText,
  validateSkillRow,
  validateCertificationRow,
  chunkRows,
  buildErrorReportCsv,
  MAX_CSV_ROWS,
  MAX_BATCH_SIZE,
} from './csvParser.js'
import { upsertTechnicianSkills, upsertTechnicianCertifications } from '../../../api/refdata.js'
import { generateAttemptKey } from '../../../lib/idempotency.js'

/** @typedef {'idle' | 'preview' | 'committing' | 'done'} WizardStep */

/**
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   technicianId: string,
 *   importType: 'skills' | 'certifications'
 * }} props
 */
export function CsvImportWizard({ open, onClose, technicianId, importType }) {
  const [step, setStep] = useState(/** @type {WizardStep} */ ('idle'))
  const [parseError, setParseError] = useState(/** @type {string | null} */ (null))
  const [rows, setRows] = useState(/** @type {import('./csvParser.js').ParsedRow[]} */ ([]))
  const [commitResults, setCommitResults] = useState(/** @type {{ committed: number, failed: number, errors: import('./csvParser.js').ParsedRow[] }} | null */ (null))
  const [isCommitting, setIsCommitting] = useState(false)
  const fileRef = useRef(null)

  const validRows = rows.filter(r => r.valid)
  const invalidRows = rows.filter(r => !r.valid)

  const handleFileChange = useCallback(async (e) => {
    const file = e.target.files?.[0]
    if (!file) return

    setParseError(null)
    setRows([])

    const text = await file.text()
    const { rows: rawRows, parseError: err } = parseCsvText(text)
    if (err) { setParseError(err); return }
    if (rawRows.length === 0) { setParseError('No data rows found.'); return }
    if (rawRows.length > MAX_CSV_ROWS) {
      setParseError(`File exceeds the maximum of ${MAX_CSV_ROWS} rows.`)
      return
    }

    const validator = importType === 'skills' ? validateSkillRow : validateCertificationRow
    setRows(rawRows.map((row, i) => validator(row, i)))
    setStep('preview')
  }, [importType])

  const handleCommit = useCallback(async () => {
    if (validRows.length === 0) return
    setIsCommitting(true)
    setStep('committing')

    let committed = 0
    const failedRows = [...invalidRows]

    const chunks = chunkRows(validRows, MAX_BATCH_SIZE)
    for (const chunk of chunks) {
      const idempotencyKey = generateAttemptKey()
      try {
        const items = chunk.map(r => importType === 'skills'
          ? {
              skillCode: r.raw['skillcode'] ?? r.raw['skill_code'] ?? r.raw['code'],
              proficiency: (r.raw['proficiency'] ?? '').toUpperCase(),
              yearsExperience: r.raw['years_experience'] ? Number(r.raw['years_experience']) : undefined,
            }
          : {
              typeCode: r.raw['typecode'] ?? r.raw['type_code'] ?? r.raw['code'],
              certificateReference: r.raw['certificatereference'] ?? r.raw['certificate_reference'] ?? undefined,
              issuedOn: r.raw['issuedon'] ?? r.raw['issued_on'],
              expiresOn: r.raw['expireson'] ?? r.raw['expires_on'] ?? undefined,
              issuingBody: r.raw['issuingbody'] ?? r.raw['issuing_body'] ?? undefined,
            })

        if (importType === 'skills') {
          await upsertTechnicianSkills(technicianId, { items }, idempotencyKey)
        } else {
          await upsertTechnicianCertifications(technicianId, { items }, idempotencyKey)
        }
        committed += chunk.length
      } catch (err) {
        // Mark batch rows as failed
        chunk.forEach(r => failedRows.push({ ...r, errors: [err?.message ?? 'Batch failed'] }))
      }
    }

    setCommitResults({ committed, failed: failedRows.length - invalidRows.length, errors: failedRows })
    setIsCommitting(false)
    setStep('done')
  }, [validRows, invalidRows, technicianId, importType])

  function downloadErrorReport() {
    if (!commitResults?.errors.length) return
    const csv = buildErrorReportCsv(commitResults.errors)
    const blob = new Blob([csv], { type: 'text/csv' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'import-errors.csv'
    a.click()
    URL.revokeObjectURL(url)
  }

  function handleClose() {
    setStep('idle')
    setParseError(null)
    setRows([])
    setCommitResults(null)
    if (fileRef.current) fileRef.current.value = ''
    onClose()
  }

  return (
    <Modal
      open={open}
      onClose={handleClose}
      title={`Import ${importType === 'skills' ? 'skills' : 'certifications'} from CSV`}
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)', fontFamily: 'var(--token-family-base)', minWidth: '480px' }}>

        {/* Step: idle / upload */}
        {(step === 'idle' || step === 'preview') && (
          <div>
            <label htmlFor="csv-file-input" style={{ display: 'block', marginBottom: 'var(--token-space-2)', fontWeight: 500, fontSize: 'var(--token-fs-14)' }}>
              Upload CSV file (max {MAX_CSV_ROWS} rows)
            </label>
            <input
              id="csv-file-input"
              ref={fileRef}
              type="file"
              accept=".csv,text/csv"
              onChange={handleFileChange}
              style={{ minHeight: '44px', display: 'block', width: '100%' }}
            />
            {parseError && (
              <p role="alert" style={{ color: 'var(--token-danger-emphasis)', marginTop: 'var(--token-space-2)', fontSize: 'var(--token-fs-14)' }}>
                ⚠ {parseError}
              </p>
            )}
          </div>
        )}

        {/* Step: preview */}
        {step === 'preview' && rows.length > 0 && (
          <div>
            <p style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)', marginBottom: 'var(--token-space-3)' }}>
              {validRows.length} valid row{validRows.length !== 1 ? 's' : ''} ready to import
              {invalidRows.length > 0 && `, ${invalidRows.length} invalid row${invalidRows.length !== 1 ? 's' : ''} excluded`}.
            </p>
            <div style={{ overflowX: 'auto', maxHeight: '260px', overflowY: 'auto', border: '1px solid var(--token-border-default)', borderRadius: 'var(--token-radius-control)' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--token-fs-13)' }}>
                <caption style={{ position: 'absolute', left: '-9999px' }}>CSV preview</caption>
                <thead>
                  <tr>
                    <th style={{ padding: '6px 12px', borderBottom: '1px solid var(--token-border-default)', textAlign: 'left' }}>Row</th>
                    <th style={{ padding: '6px 12px', borderBottom: '1px solid var(--token-border-default)', textAlign: 'left' }}>Status</th>
                    {Object.keys(rows[0]?.raw ?? {}).map(k => (
                      <th key={k} style={{ padding: '6px 12px', borderBottom: '1px solid var(--token-border-default)', textAlign: 'left' }}>{k}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {rows.map(row => (
                    <tr key={row.index} style={{ background: row.valid ? undefined : 'var(--token-danger-subtle)' }}>
                      <td style={{ padding: '4px 12px' }}>{row.index + 2}</td>
                      <td style={{ padding: '4px 12px' }}>
                        {row.valid
                          ? <span aria-label="Valid">✓</span>
                          : <span aria-label={`Invalid: ${row.errors.join('; ')}`} title={row.errors.join('; ')} style={{ color: 'var(--token-danger-emphasis)' }}>✕ {row.errors[0]}</span>
                        }
                      </td>
                      {Object.values(row.raw).map((v, i) => (
                        <td key={i} style={{ padding: '4px 12px', maxWidth: '150px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{v}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* Step: committing */}
        {step === 'committing' && (
          <p role="status" aria-live="polite" style={{ fontSize: 'var(--token-fs-14)' }}>
            Importing {validRows.length} rows in batches of {MAX_BATCH_SIZE}…
          </p>
        )}

        {/* Step: done */}
        {step === 'done' && commitResults && (
          <div role="status" aria-live="polite">
            <p style={{ fontWeight: 600, fontSize: 'var(--token-fs-16)', marginBottom: 'var(--token-space-2)' }}>
              Import complete
            </p>
            <p style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)' }}>
              {commitResults.committed} row{commitResults.committed !== 1 ? 's' : ''} committed.
              {commitResults.failed > 0 && ` ${commitResults.failed} row${commitResults.failed !== 1 ? 's' : ''} failed.`}
            </p>
            {commitResults.errors.length > 0 && (
              <Button
                type="button"
                variant="secondary"
                onClick={downloadErrorReport}
                style={{ marginTop: 'var(--token-space-3)' }}
              >
                Download error report
              </Button>
            )}
          </div>
        )}

        {/* Footer actions */}
        <div style={{ display: 'flex', gap: 'var(--token-space-3)', justifyContent: 'flex-end', marginTop: 'var(--token-space-2)' }}>
          <Button type="button" variant="secondary" onClick={handleClose}>
            {step === 'done' ? 'Close' : 'Cancel'}
          </Button>
          {step === 'preview' && validRows.length > 0 && (
            <Button type="button" variant="primary" onClick={handleCommit} disabled={isCommitting}>
              Import {validRows.length} row{validRows.length !== 1 ? 's' : ''}
            </Button>
          )}
        </div>
      </div>
    </Modal>
  )
}
