/**
 * @fileoverview Unit tests for useUrlPageState hook.
 */
import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import React from 'react'
import { useUrlPageState } from './useUrlPageState.js'

function wrapper({ children }) {
  return React.createElement(MemoryRouter, null, children)
}

describe('useUrlPageState', () => {
  it('returns default state when no URL params are set', () => {
    const { result } = renderHook(() => useUrlPageState({ defaultSize: 25, defaultSort: 'name:ASC' }), { wrapper })
    expect(result.current.state.page).toBe(0)
    expect(result.current.state.size).toBe(25)
    expect(result.current.state.sort).toBe('name:ASC')
  })

  it('setPage updates the page in state', () => {
    const { result } = renderHook(() => useUrlPageState(), { wrapper })
    act(() => { result.current.setPage(2) })
    expect(result.current.state.page).toBe(2)
  })

  it('setSize updates the size and resets page to 0', () => {
    const { result } = renderHook(() => useUrlPageState(), { wrapper })
    act(() => { result.current.setPage(3) })
    act(() => { result.current.setSize(50) })
    expect(result.current.state.size).toBe(50)
    expect(result.current.state.page).toBe(0)
  })

  it('setSort updates the sort field', () => {
    const { result } = renderHook(() => useUrlPageState(), { wrapper })
    act(() => { result.current.setSort('createdAt:DESC') })
    expect(result.current.state.sort).toBe('createdAt:DESC')
  })

  it('setFilter updates filter and resets page to 0', () => {
    const { result } = renderHook(() => useUrlPageState(), { wrapper })
    act(() => { result.current.setPage(2) })
    act(() => { result.current.setFilter('hello') })
    expect(result.current.state.filter).toBe('hello')
    expect(result.current.state.page).toBe(0)
  })

  it('resetToFirstPage resets page to 0', () => {
    const { result } = renderHook(() => useUrlPageState(), { wrapper })
    act(() => { result.current.setPage(5) })
    act(() => { result.current.resetToFirstPage() })
    expect(result.current.state.page).toBe(0)
  })

  it('exposes setPage, setSize, setSort, setFilter, resetToFirstPage functions', () => {
    const { result } = renderHook(() => useUrlPageState(), { wrapper })
    expect(typeof result.current.setPage).toBe('function')
    expect(typeof result.current.setSize).toBe('function')
    expect(typeof result.current.setSort).toBe('function')
    expect(typeof result.current.setFilter).toBe('function')
    expect(typeof result.current.resetToFirstPage).toBe('function')
  })
})
