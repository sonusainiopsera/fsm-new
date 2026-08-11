/**
 * @fileoverview CSV parser for batch import of skills and certifications.
 *
 * Handles: BOM, CRLF/LF, quoted commas, quoted newlines, Windows-style quoting.
 * Rejects files exceeding MAX_CSV_ROWS before parsing.
 * Returns per-row validation status for the preview table.
 *
 * @module features/admin/import/csvParser
 */

export const MAX_CSV_ROWS = 1000
export const MAX_BATCH_SIZE = 200

/** @typedef {{ index: number, raw: Record<string, string>, errors: string[], valid: boolean }} ParsedRow */

/**
 * Strips UTF-8 BOM if present.
 * @param {string} text
 * @returns {string}
 */
function stripBom(text) {
  return text.startsWith('﻿') ? text.slice(1) : text
}

/**
 * Normalises line endings to LF.
 * @param {string} text
 * @returns {string}
 */
function normaliseLf(text) {
  return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
}

/**
 * Parses a single CSV line, handling quoted fields.
 * @param {string} line
 * @returns {string[]}
 */
export function parseCsvLine(line) {
  const fields = []
  let current = ''
  let inQuote = false
  let i = 0

  while (i < line.length) {
    const ch = line[i]
    if (ch === '"') {
      if (inQuote && line[i + 1] === '"') {
        // Escaped double-quote
        current += '"'
        i += 2
        continue
      }
      inQuote = !inQuote
    } else if (ch === ',' && !inQuote) {
      fields.push(current.trim())
      current = ''
    } else {
      current += ch
    }
    i++
  }
  fields.push(current.trim())
  return fields
}

/**
 * Parses raw CSV text into rows mapped by header columns.
 *
 * @param {string} rawText
 * @returns {{ headers: string[], rows: Array<Record<string, string>>, parseError?: string }}
 */
export function parseCsvText(rawText) {
  const clean = normaliseLf(stripBom(rawText))
  const lines = clean.split('\n').filter(l => l.trim() !== '')

  if (lines.length === 0) {
    return { headers: [], rows: [], parseError: 'File is empty.' }
  }

  if (lines.length > MAX_CSV_ROWS + 1) {
    return { headers: [], rows: [], parseError: `File exceeds the maximum of ${MAX_CSV_ROWS} data rows.` }
  }

  const headers = parseCsvLine(lines[0]).map(h => h.toLowerCase().trim())
  if (headers.length === 0 || headers.every(h => h === '')) {
    return { headers: [], rows: [], parseError: 'Header row is empty or unreadable.' }
  }

  const rows = lines.slice(1).map(line => {
    const values = parseCsvLine(line)
    /** @type {Record<string, string>} */
    const row = {}
    headers.forEach((h, i) => {
      row[h] = values[i] ?? ''
    })
    return row
  })

  return { headers, rows }
}

/**
 * Validates a parsed skill import row.
 * Expected columns: typeCode (or skill_code, skillcode), proficiency
 *
 * @param {Record<string, string>} row
 * @param {number} index
 * @returns {ParsedRow}
 */
export function validateSkillRow(row, index) {
  const errors = []
  const skillCode = (row['skillcode'] ?? row['skill_code'] ?? row['typecode'] ?? row['code'] ?? '').trim()
  const proficiency = (row['proficiency'] ?? '').trim().toUpperCase()

  if (!skillCode) errors.push('skill_code is required')
  if (!proficiency) errors.push('proficiency is required')
  const validProficiencies = new Set(['BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'EXPERT'])
  if (proficiency && !validProficiencies.has(proficiency)) {
    errors.push(`proficiency must be one of: ${[...validProficiencies].join(', ')}`)
  }

  return { index, raw: row, errors, valid: errors.length === 0 }
}

/**
 * Validates a parsed certification import row.
 * Expected columns: typeCode, issuedOn, expiresOn (optional), certificateReference, issuingBody
 *
 * @param {Record<string, string>} row
 * @param {number} index
 * @returns {ParsedRow}
 */
export function validateCertificationRow(row, index) {
  const errors = []
  const typeCode = (row['typecode'] ?? row['type_code'] ?? row['code'] ?? '').trim()
  const issuedOn = (row['issuedon'] ?? row['issued_on'] ?? row['issued'] ?? '').trim()
  const expiresOn = (row['expireson'] ?? row['expires_on'] ?? row['expires'] ?? '').trim()

  if (!typeCode) errors.push('typeCode is required')
  if (!issuedOn) errors.push('issuedOn is required')

  const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/
  if (issuedOn && !ISO_DATE.test(issuedOn)) errors.push('issuedOn must be YYYY-MM-DD')
  if (expiresOn && !ISO_DATE.test(expiresOn)) errors.push('expiresOn must be YYYY-MM-DD if provided')
  if (issuedOn && expiresOn && ISO_DATE.test(issuedOn) && ISO_DATE.test(expiresOn) && issuedOn > expiresOn) {
    errors.push('issuedOn must not be after expiresOn')
  }

  return { index, raw: row, errors, valid: errors.length === 0 }
}

/**
 * Splits valid rows into chunks of at most MAX_BATCH_SIZE.
 *
 * @template T
 * @param {T[]} rows
 * @param {number} [chunkSize]
 * @returns {T[][]}
 */
export function chunkRows(rows, chunkSize = MAX_BATCH_SIZE) {
  const chunks = []
  for (let i = 0; i < rows.length; i += chunkSize) {
    chunks.push(rows.slice(i, i + chunkSize))
  }
  return chunks
}

/**
 * Generates a CSV string for the error report download.
 *
 * @param {ParsedRow[]} failedRows
 * @returns {string}
 */
export function buildErrorReportCsv(failedRows) {
  const headers = ['row_number', 'errors', ...Object.keys(failedRows[0]?.raw ?? {})]
  const lines = [headers.join(',')]
  failedRows.forEach(r => {
    const values = [
      r.index + 2, // +2 for 1-based + header row
      `"${r.errors.join('; ')}"`,
      ...Object.values(r.raw).map(v => `"${String(v).replace(/"/g, '""')}"`)
    ]
    lines.push(values.join(','))
  })
  return lines.join('\n')
}
