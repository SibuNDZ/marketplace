import React from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, CartResponse } from '../../lib/api'
import { useCartDrawer } from '../../context/CartDrawerContext'

export function CartDrawer() {
  const { isOpen, close } = useCartDrawer()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const { data: cart } = useQuery<CartResponse>({
    queryKey: ['cart'],
    queryFn: () => api('/api/v1/cart'),
    enabled: isOpen,
  })

  const updateQty = useMutation({
    mutationFn: ({ productId, quantity }: { productId: number; quantity: number }) =>
      api(`/api/v1/cart/items/${productId}`, { method: 'PUT', body: { quantity } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cart'] }),
  })

  const removeItem = useMutation({
    mutationFn: (productId: number) => api(`/api/v1/cart/items/${productId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cart'] }),
  })

  if (!isOpen) return null

  const lines = cart?.items ?? []
  const subtotal = Number(cart?.subtotal ?? 0)

  return (
    <>
      <div onClick={close} style={{
        position: 'fixed', inset: 0, background: 'rgba(24,36,32,0.4)', zIndex: 199,
      }} />
      <aside style={{
        position: 'fixed', top: 0, right: 0, bottom: 0, width: 380, maxWidth: '92vw',
        background: 'var(--card)', zIndex: 200, boxShadow: 'var(--shadow-lift)',
        display: 'flex', flexDirection: 'column',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '18px 20px', borderBottom: '1px solid var(--line)' }}>
          <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 18 }}>Your cart</h2>
          <button onClick={close} aria-label="Close cart" style={{ background: 'none', border: 'none', fontSize: 20, color: 'var(--ink-soft)' }}>×</button>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 20px' }}>
          {lines.length === 0 && (
            <p style={{ textAlign: 'center', color: 'var(--ink-soft)', padding: '40px 0', fontSize: 13 }}>Your cart is empty</p>
          )}
          {lines.map(line => (
            <div key={line.productId} style={{ display: 'flex', gap: 12, padding: '14px 0', borderBottom: '1px solid var(--line)' }}>
              <img
                src={`https://picsum.photos/seed/mk-${line.productId}/80/80`}
                alt=""
                width={56} height={56}
                style={{ borderRadius: 'var(--r-sm)', objectFit: 'cover', flexShrink: 0 }}
              />
              <div style={{ flex: 1, minWidth: 0 }}>
                <p style={{ fontSize: 13, fontWeight: 600, marginBottom: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{line.productName}</p>
                <p className="num" style={{ fontSize: 12, color: 'var(--ink-soft)', marginBottom: 6 }}>R{Number(line.unitPrice).toFixed(2)}</p>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <button onClick={() => updateQty.mutate({ productId: line.productId, quantity: line.quantity - 1 })}
                    style={{ width: 22, height: 22, border: '1px solid var(--line)', borderRadius: 4, background: 'none', fontSize: 13 }}>−</button>
                  <span className="num" style={{ minWidth: 20, textAlign: 'center', fontSize: 12 }}>{line.quantity}</span>
                  <button onClick={() => updateQty.mutate({ productId: line.productId, quantity: line.quantity + 1 })}
                    style={{ width: 22, height: 22, border: '1px solid var(--line)', borderRadius: 4, background: 'none', fontSize: 13 }}>+</button>
                  <button onClick={() => removeItem.mutate(line.productId)}
                    style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--ink-soft)', background: 'none', border: 'none' }}>Remove</button>
                </div>
              </div>
            </div>
          ))}
        </div>

        <div style={{ padding: '16px 20px', borderTop: '1px solid var(--line)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
            <span style={{ fontWeight: 600, fontSize: 14 }}>Subtotal</span>
            <span className="num" style={{ fontWeight: 700, fontSize: 18 }}>R{subtotal.toFixed(2)}</span>
          </div>
          <button
            disabled={lines.length === 0}
            onClick={() => { close(); navigate('/cart') }}
            style={{
              width: '100%', padding: '13px', border: 'none', borderRadius: 'var(--r-pill)',
              background: lines.length === 0 ? 'var(--line)' : 'var(--flame-gradient)',
              color: lines.length === 0 ? 'var(--ink-soft)' : '#fff',
              fontWeight: 700, fontSize: 15,
            }}
          >
            Go to cart →
          </button>
        </div>
      </aside>
    </>
  )
}
