import React from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, CartResponse } from '../../lib/api'
import { useCartDrawer } from '../../context/CartDrawerContext'
import { CartLineImage } from './CartLineImage'

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
    mutationFn: ({ productId, variantId, quantity }: { productId: number; variantId?: number | null; quantity: number }) =>
      // variantId identifies WHICH line when a product sits in the cart under
      // more than one option.
      api(`/api/v1/cart/items/${productId}${variantId != null ? `?variantId=${variantId}` : ''}`,
        { method: 'PUT', body: { quantity } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cart'] }),
  })

  const removeItem = useMutation({
    mutationFn: ({ productId, variantId }: { productId: number; variantId?: number | null }) =>
      api(`/api/v1/cart/items/${productId}${variantId != null ? `?variantId=${variantId}` : ''}`,
        { method: 'DELETE' }),
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
          <button onClick={close} className="qty-btn" aria-label="Close cart">×</button>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 20px' }}>
          {lines.length === 0 && (
            <p style={{ textAlign: 'center', color: 'var(--ink-soft)', padding: '40px 0', fontSize: 13 }}>Your cart is empty</p>
          )}
          {lines.map(line => (
            <div key={line.productId} style={{ display: 'flex', gap: 12, padding: '14px 0', borderBottom: '1px solid var(--line)' }}>
              {/* Was a picsum placeholder keyed on product id — see
                  CartLineImage for why a wrong photo is worse than none. */}
              <CartLineImage line={line} size={56} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <p style={{ fontSize: 13, fontWeight: 600, marginBottom: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {line.productName}
                  {line.variantLabel && <span style={{ color: 'var(--ink-soft)', fontWeight: 400 }}> · {line.variantLabel}</span>}
                </p>
                <p className="num" style={{ fontSize: 12, color: 'var(--ink-soft)', marginBottom: 6 }}>R{Number(line.unitPrice).toFixed(2)}</p>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                  <button
                    type="button"
                    className="qty-btn qty-btn--boxed"
                    aria-label={`Decrease quantity of ${line.productName}`}
                    onClick={() => updateQty.mutate({ productId: line.productId, variantId: line.variantId, quantity: line.quantity - 1 })}
                  >−</button>
                  <span className="num" style={{ minWidth: 20, textAlign: 'center', fontSize: 12 }}>{line.quantity}</span>
                  <button
                    type="button"
                    className="qty-btn qty-btn--boxed"
                    aria-label={`Increase quantity of ${line.productName}`}
                    onClick={() => updateQty.mutate({ productId: line.productId, variantId: line.variantId, quantity: line.quantity + 1 })}
                  >+</button>
                  <button
                    type="button"
                    onClick={() => removeItem.mutate({ productId: line.productId, variantId: line.variantId })}
                    aria-label={`Remove ${line.productName} from cart`}
                    style={{ marginLeft: 'auto', minHeight: 44, padding: '0 8px', fontSize: 13, color: 'var(--ink-soft)', background: 'none', border: 'none' }}
                  >Remove</button>
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
