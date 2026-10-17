import React, { createContext, useContext, useState } from 'react'

// UI state for the right cart panel that must survive route changes:
// whether the desktop column is collapsed and whether the small-screen
// drawer is open. The cart contents themselves are server state
// (react-query ['cart']) and persist on their own.
//
// There is deliberately no per-line selection here. The panel once had
// tick boxes that changed its subtotal, but an order is always placed from
// the whole cart, so a ticked subset was a number checkout never honoured.
// Partial-cart checkout would need the backend to take the selection.
interface RightPanelCtx {
  collapsed: boolean
  setCollapsed: (v: boolean) => void
  drawerOpen: boolean
  openDrawer: () => void
  closeDrawer: () => void
}

const Ctx = createContext<RightPanelCtx>({
  collapsed: false, setCollapsed: () => {},
  drawerOpen: false, openDrawer: () => {}, closeDrawer: () => {},
})

export function RightPanelProvider({ children }: { children: React.ReactNode }) {
  const [collapsed, setCollapsed] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)

  return (
    <Ctx.Provider value={{
      collapsed, setCollapsed,
      drawerOpen, openDrawer: () => setDrawerOpen(true), closeDrawer: () => setDrawerOpen(false),
    }}>
      {children}
    </Ctx.Provider>
  )
}

export function useRightPanel() { return useContext(Ctx) }
