/**
 * @fileoverview Unit tests for csvParser.js
 */
import { describe, it, expect } from 'vitest'
import {
  parseCsvText,
  validateSkillRow,
  validateCertificationRow,
  chunkRows,
  buildErrorReportCsv,
  MAX_CSV_ROWS,
  MAX_BATCH_SIZE,
} from './csvParser.js'

describe('parseCsvText', () => {
  it('parses a simple CSV with headers and one data row', () => {
    const text = 'name,value\nhello,world\n'
    const { rows, parseError } = parseCsvText(text)
    expect(parseError).toBeFalsy()
    expect(rows).toHaveLength(1)
    expect(rows[0]).toEqual({ name: 'hello', value: 'world' })
  })

  it('strips BOM prefix', () => {
    const text = '﻿code,name\nPLUMBING,Plumbing\n'
    const { rows } = parseCsvText(text)
    expect(rows[0]).toEqual({ code: 'PLUMBING', name: 'Plumbing' })
  })

  it('handles CRLF line endings', () => {
    const text = 'a,b\r\n1,2\r\n3,4\r\n'
    const { rows } = parseCsvText(text)
    expect(rows).toHaveLength(2)
  })

  it('handles quoted fields containing commas', () => {
    const text = 'name,address\nAlice,"1 Main St, City"\n'
    const { rows } = parseCsvText(text)
    expect(rows[0].address).toBe('1 Main St, City')
  })

  it('normalises header keys to lowercase', () => {
    const text = 'SkillCode,PROFICIENCY\nELEC,EXPERT\n'
    const { rows } = parseCsvText(text)
    expect(rows[0]).toHaveProperty('skillcode')
    expect(rows[0]).toHaveProperty('proficiency')
  })

  it('returns parseError when header row is missing', () => {
    const { parseError } = parseCsvText('')
    expect(parseError).toBeTruthy()
  })

  it('trims whitespace from field values', () => {
    const text = 'code,name\n PLUMBING , Plumbing \n'
    const { rows } = parseCsvText(text)
    expect(rows[0].code).toBe('PLUMBING')
    expect(rows[0].name).toBe('Plumbing')
  })
})

describe('validateSkillRow', () => {
  it('marks a valid skill row as valid', () => {
    const raw = { skill_code: 'PLUMBING', proficiency: 'EXPERT', years_experience: '5' }
    const result = validateSkillRow(raw, 0)
    expect(result.valid).toBe(true)
    expect(result.errors).toHaveLength(0)
  })

  it('marks a row with missing skill_code as invalid', () => {
    const raw = { skill_code: '', proficiency: 'EXPERT' }
    const result = validateSkillRow(raw, 0)
    expect(result.valid).toBe(false)
    expect(result.errors.some(e => /code/i.test(e))).toBe(true)
  })

  it('marks a row with invalid proficiency as invalid', () => {
    const raw = { skill_code: 'PLUMBING', proficiency: 'GODLIKE' }
    const result = validateSkillRow(raw, 0)
    expect(result.valid).toBe(false)
  })

  it('accepts rows without years_experience', () => {
    const raw = { skill_code: 'PLUMBING', proficiency: 'EXPERT' }
    const result = validateSkillRow(raw, 0)
    expect(result.valid).toBe(true)
  })

  it('accepts all valid proficiency levels', () => {
    for (const prof of ['BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'EXPERT']) {
      const raw = { skill_code: 'CODE', proficiency: prof }
      expect(validateSkillRow(raw, 0).valid).toBe(true)
    }
  })

  it('preserves raw record in result', () => {
    const raw = { skill_code: 'X', proficiency: 'EXPERT' }
    const result = validateSkillRow(raw, 2)
    expect(result.raw).toBe(raw)
    expect(result.index).toBe(2)
  })
})

describe('validateCertificationRow', () => {
  it('marks a valid certification row as valid', () => {
    const raw = {
      type_code: 'GAS_SAFE',
      issued_on: '2025-01-01',
      expires_on: '2027-01-01',
    }
    const result = validateCertificationRow(raw, 0)
    expect(result.valid).toBe(true)
  })

  it('marks a row with missing type_code as invalid', () => {
    const raw = { type_code: '', issued_on: '2025-01-01' }
    const result = validateCertificationRow(raw, 0)
    expect(result.valid).toBe(false)
    expect(result.errors.some(e => /typeCode/i.test(e))).toBe(true)
  })

  it('marks a row with invalid issued_on date format as invalid', () => {
    const raw = { type_code: 'GAS_SAFE', issued_on: '01/01/2025' }
    const result = validateCertificationRow(raw, 0)
    expect(result.valid).toBe(false)
  })

  it('accepts a row without expires_on (perpetual)', () => {
    const raw = { type_code: 'GAS_SAFE', issued_on: '2025-01-01', expires_on: '' }
    const result = validateCertificationRow(raw, 0)
    expect(result.valid).toBe(true)
  })

  it('marks a row where expires_on is before issued_on as invalid', () => {
    const raw = { type_code: 'GAS_SAFE', issued_on: '2025-06-01', expires_on: '2025-01-01' }
    const result = validateCertificationRow(raw, 0)
    expect(result.valid).toBe(false)
  })
})

describe('chunkRows', () => {
  it('returns a single chunk when rows <= chunkSize', () => {
    const rows = Array.from({ length: 3 }, (_, i) => ({ index: i }))
    expect(chunkRows(rows, 200)).toHaveLength(1)
  })

  it('returns multiple chunks when rows > chunkSize', () => {
    const rows = Array.from({ length: 250 }, (_, i) => ({ index: i }))
    const chunks = chunkRows(rows, 200)
    expect(chunks).toHaveLength(2)
    expect(chunks[0]).toHaveLength(200)
    expect(chunks[1]).toHaveLength(50)
  })

  it('MAX_BATCH_SIZE is 200', () => {
    expect(MAX_BATCH_SIZE).toBe(200)
  })
})

describe('buildErrorReportCsv', () => {
  it('returns a CSV string with a header row and one row per error', () => {
    const rows = [
      { index: 1, raw: { code: 'BAD' }, errors: ['code is required'], valid: false },
    ]
    const csv = buildErrorReportCsv(rows)
    expect(csv).toContain('row_number')
    expect(csv).toContain('code is required')
  })
})

describe('MAX_CSV_ROWS', () => {
  it('is 1000', () => {
    expect(MAX_CSV_ROWS).toBe(1000)
  })
})
