import React, { createContext, useContext, useState } from 'react'

// UI state for the right cart panel that must survive route changes:
// which cart lines are UNticked, whether the desktop column is collapsed,
// and whether the small-screen drawer is open. The cart contents themselves
// are server state (react-query ['cart']) and persist on their own.
//
// Deselection is tracked (rather than selection) so a line added to the
// cart later arrives selected by default without any bookkeeping.
interface RightPanelCtx {
  collapsed: boolean
  setCollapsed: (v: boolean) => void
  drawerOpen: boolean
  openDrawer: () => void
  closeDrawer: () => void
  deselected: Set<string>
  toggleItem: (lineKey: string) => void
  selectAll: () => void
  deselectAll: (lineKeys: string[]) => void
}

const Ctx = createContext<RightPanelCtx>({
  collapsed: false, setCollapsed: () => {},
  drawerOpen: false, openDrawer: () => {}, closeDrawer: () => {},
  deselected: new Set(), toggleItem: () => {}, selectAll: () => {}, deselectAll: () => {},
})

export function RightPanelProvider({ children }: { children: React.ReactNode }) {
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [deselected, setDeselected] = useState<Set<string>>(new Set())

  const toggleItem = (lineKey: string) => setDeselected(prev => {
    const next = new Set(prev)
    next.has(lineKey) ? next.delete(lineKey) : next.add(lineKey)
    return next
  })

  return (
    <Ctx.Provider value={{
      collapsed, setCollapsed,
      drawerOpen, openDrawer: () => setDrawerOpen(true), closeDrawer: () => setDrawerOpen(false),
      deselected, toggleItem,
      selectAll: () => setDeselected(new Set()),
      deselectAll: (keys: string[]) => setDeselected(new Set(keys)),
    }}>
      {children}
    </Ctx.Provider>
  )
}

export function useRightPanel() { return useContext(Ctx) }
