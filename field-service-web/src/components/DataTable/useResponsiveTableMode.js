/**
 * @fileoverview useResponsiveTableMode — container-width observer that switches to stacked
 * card rendering below 768 px. Uses ResizeObserver with a window.matchMedia fallback.
 */
import { useState, useEffect, useRef } from 'react'

const BREAKPOINT = 768

/**
 * @param {React.RefObject<HTMLElement>} containerRef
 * @returns {{ isStacked: boolean }}
 */
export function useResponsiveTableMode(containerRef) {
  const [isStacked, setIsStacked] = useState(false)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    function measure(width) {
      setIsStacked(width < BREAKPOINT)
    }

    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver((entries) => {
        for (const entry of entries) {
          measure(entry.contentRect.width)
        }
      })
      ro.observe(el)
      // Initial measurement
      measure(el.getBoundingClientRect().width)
      return () => ro.disconnect()
    }

    // Fallback: window resize (less precise, but safe)
    function onResize() {
      measure(el.getBoundingClientRect().width)
    }
    window.addEventListener('resize', onResize, { passive: true })
    measure(el.getBoundingClientRect().width)
    return () => window.removeEventListener('resize', onResize)
  }, [containerRef])

  return { isStacked }
}
