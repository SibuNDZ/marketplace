import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, CartResponse, ApiError } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { ErrorSurface } from '../components/ui/ErrorSurface'

export function CartPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [checkoutError, setCheckoutError] = useState<ApiError>()

  const { data: cart, isLoading } = useQuery<CartResponse>({
    queryKey: ['cart'],
    queryFn: () => api('/api/v1/cart'),
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

  const placeOrder = useMutation({
    mutationFn: () => api<{ id: number }>('/api/v1/orders', { method: 'POST' }),
    onSuccess: async (order) => {
      // After placing, initiate payment
      const session = await api<{ checkoutUrl: string }>(`/api/v1/orders/${order.id}/pay`, { method: 'POST' })
      window.location.href = session.checkoutUrl
    },
    onError: (e) => { if (e instanceof ApiError) setCheckoutError(e) },
  })

  const lines = cart?.items ?? []
  const isEmpty = lines.length === 0

  return (
    <>
      <Topbar />
      <main className="page-shell">
        <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 28, marginBottom: 28 }}>Your cart</h1>

        {isLoading && <p style={{ color: 'var(--ink-soft)' }}>Loading…</p>}

        {!isLoading && isEmpty && (
          <div style={{ textAlign: 'center', padding: '60px 0' }}>
            <p style={{ color: 'var(--ink-soft)', marginBottom: 16 }}>Your cart is empty</p>
            <Link to="/" style={{ padding: '10px 24px', background: 'var(--ink)', color: '#fff', borderRadius: 'var(--r-pill)', fontWeight: 600 }}>
              Browse products
            </Link>
          </div>
        )}

        {!isEmpty && (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 40, alignItems: 'start' }}>
            {/* Lines */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
              {lines.map(line => (
                <div key={line.productId} style={{
                  display: 'flex', alignItems: 'center', gap: 16,
                  padding: '16px 0', borderBottom: '1px solid var(--line)',
                }}>
                  <div style={{ flex: 1 }}>
                    <p style={{ fontWeight: 600, marginBottom: 2 }}>{line.productName}</p>
                    <p className="num" style={{ fontSize: 13, color: 'var(--ink-soft)' }}>R{Number(line.unitPrice).toFixed(2)} each</p>
                    {line.availableStock < line.quantity && (
                      <p style={{ fontSize: 12, color: 'var(--clay)', marginTop: 2 }}>
                        Only <span className="num">{line.availableStock}</span> available
                      </p>
                    )}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <button onClick={() => updateQty.mutate({ productId: line.productId, quantity: line.quantity - 1 })}
                      style={{ width: 28, height: 28, border: '1px solid var(--line)', borderRadius: 'var(--r-sm)', background: 'none', fontSize: 16 }}>−</button>
                    <span className="num" style={{ minWidth: 28, textAlign: 'center', fontWeight: 600 }}>{line.quantity}</span>
                    <button onClick={() => updateQty.mutate({ productId: line.productId, quantity: line.quantity + 1 })}
                      style={{ width: 28, height: 28, border: '1px solid var(--line)', borderRadius: 'var(--r-sm)', background: 'none', fontSize: 16 }}>+</button>
                  </div>
                  <p className="num" style={{ fontWeight: 700, minWidth: 80, textAlign: 'right' }}>
                    R{Number(line.lineTotal).toFixed(2)}
                  </p>
                  <button onClick={() => removeItem.mutate(line.productId)}
                    style={{ color: 'var(--ink-soft)', background: 'none', border: 'none', fontSize: 18, lineHeight: 1 }}>×</button>
                </div>
              ))}
            </div>

            {/* Summary */}
            <div style={{ background: 'var(--card)', borderRadius: 'var(--r)', padding: 24, boxShadow: 'var(--shadow)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 20 }}>
                <span style={{ fontWeight: 600 }}>Total</span>
                <span className="num" style={{ fontWeight: 700, fontSize: 20 }}>R{Number(cart?.subtotal ?? 0).toFixed(2)}</span>
              </div>
              {checkoutError && <ErrorSurface error={checkoutError} onDismiss={() => setCheckoutError(undefined)} />}
              <button disabled={placeOrder.isPending} onClick={() => placeOrder.mutate()} style={{
                width: '100%', padding: '13px', background: 'var(--ink)', color: '#fff',
                border: 'none', borderRadius: 'var(--r-sm)', fontWeight: 700, fontSize: 16, marginTop: 16,
              }}>
                {placeOrder.isPending ? 'Placing order…' : 'Continue to payment'}
              </button>
              <p style={{ fontSize: 12, color: 'var(--ink-soft)', textAlign: 'center', marginTop: 10 }}>
                You'll complete payment securely on Stripe
              </p>
            </div>
          </div>
        )}
      </main>
    </>
  )
}
