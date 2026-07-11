import React, { createContext, useContext, useState } from 'react'

interface Ctx { isOpen: boolean; open: () => void; close: () => void }
const CartDrawerCtx = createContext<Ctx>({ isOpen: false, open: () => {}, close: () => {} })

export function CartDrawerProvider({ children }: { children: React.ReactNode }) {
  const [isOpen, setIsOpen] = useState(false)
  return (
    <CartDrawerCtx.Provider value={{ isOpen, open: () => setIsOpen(true), close: () => setIsOpen(false) }}>
      {children}
    </CartDrawerCtx.Provider>
  )
}

export function useCartDrawer() { return useContext(CartDrawerCtx) }
