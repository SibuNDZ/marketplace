import React, { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, CartResponse, ApiError, ShippingAddress, fieldErrorsFrom } from '../lib/api'
import { Topbar } from '../components/layout/Topbar'
import { ErrorSurface } from '../components/ui/ErrorSurface'

const EMPTY_SHIPPING: ShippingAddress = {
  recipientName: '', phone: '', addressLine1: '', addressLine2: '',
  city: '', province: '', postalCode: '',
}

function Field({ label, error, children }: { label: string; error?: string[]; children: React.ReactNode }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4, fontSize: 13, fontWeight: 500 }}>
      {label}
      {children}
      {error?.map((msg, i) => (
        <span key={i} style={{ fontSize: 12, color: 'var(--clay)', fontWeight: 400 }}>{msg}</span>
      ))}
    </label>
  )
}

const inputStyle = (hasError?: boolean): React.CSSProperties => ({
  padding: '9px 12px', border: `1.5px solid ${hasError ? 'var(--clay)' : 'var(--line)'}`,
  borderRadius: 'var(--r-sm)', fontFamily: 'var(--body)', fontSize: 14,
})

export function CartPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [checkoutError, setCheckoutError] = useState<ApiError>()

  // Order placement and shipping collection are two separate steps now:
  // POST /orders creates the PENDING order; the address is only submitted
  // at pay-time (POST /orders/{id}/pay), so there's a brief in-between
  // screen rather than the old immediate cart->Stripe redirect.
  const [pendingOrderId, setPendingOrderId] = useState<number>()
  const [shipping, setShipping] = useState<ShippingAddress>(EMPTY_SHIPPING)
  const [payError, setPayError] = useState<ApiError>()
  const [fieldErrors, setFieldErrors] = useState<Record<string, string[]>>({})

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
    onSuccess: (order) => setPendingOrderId(order.id),
    onError: (e) => { if (e instanceof ApiError) setCheckoutError(e) },
  })

  const pay = useMutation({
    mutationFn: () =>
      api<{ checkoutUrl: string }>(`/api/v1/orders/${pendingOrderId}/pay`, { method: 'POST', body: shipping }),
    onSuccess: (session) => { window.location.href = session.checkoutUrl },
    onError: (e) => {
      if (e instanceof ApiError) {
        const fe = fieldErrorsFrom(e)
        setFieldErrors(fe)
        setPayError(Object.keys(fe).length === 0 ? e : undefined)
      }
    },
  })

  const setField = <K extends keyof ShippingAddress>(key: K, value: ShippingAddress[K]) =>
    setShipping(s => ({ ...s, [key]: value }))

  const submitShipping = (e: FormEvent) => {
    e.preventDefault()
    setFieldErrors({})
    setPayError(undefined)
    pay.mutate()
  }

  const lines = cart?.items ?? []
  const isEmpty = lines.length === 0

  if (pendingOrderId) {
    return (
      <>
        <Topbar />
        <main className="page-shell no-catrail" style={{ maxWidth: 480 }}>
          <h1 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 26, marginBottom: 8 }}>
            Shipping details
          </h1>
          <p style={{ color: 'var(--ink-soft)', fontSize: 13, marginBottom: 24 }}>
            Order <span className="num">#{pendingOrderId}</span> — where should it be delivered?
          </p>

          <form onSubmit={submitShipping} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {payError && <ErrorSurface error={payError} onDismiss={() => setPayError(undefined)} />}

            <Field label="Recipient name" error={fieldErrors.recipientName}>
              <input required value={shipping.recipientName} onChange={e => setField('recipientName', e.target.value)}
                style={inputStyle(!!fieldErrors.recipientName)} />
            </Field>

            <Field label="Phone" error={fieldErrors.phone}>
              <input required type="tel" value={shipping.phone} onChange={e => setField('phone', e.target.value)}
                style={inputStyle(!!fieldErrors.phone)} />
            </Field>

            <Field label="Address line 1" error={fieldErrors.addressLine1}>
              <input required value={shipping.addressLine1} onChange={e => setField('addressLine1', e.target.value)}
                style={inputStyle(!!fieldErrors.addressLine1)} />
            </Field>

            <Field label="Address line 2 (optional)" error={fieldErrors.addressLine2}>
              <input value={shipping.addressLine2 ?? ''} onChange={e => setField('addressLine2', e.target.value)}
                style={inputStyle(!!fieldErrors.addressLine2)} />
            </Field>

            <div style={{ display: 'flex', gap: 12 }}>
              <div style={{ flex: 1 }}>
                <Field label="City" error={fieldErrors.city}>
                  <input required value={shipping.city} onChange={e => setField('city', e.target.value)}
                    style={inputStyle(!!fieldErrors.city)} />
                </Field>
              </div>
              <div style={{ flex: 1 }}>
                <Field label="Province" error={fieldErrors.province}>
                  <input required value={shipping.province} onChange={e => setField('province', e.target.value)}
                    style={inputStyle(!!fieldErrors.province)} />
                </Field>
              </div>
            </div>

            <Field label="Postal code" error={fieldErrors.postalCode}>
              <input required value={shipping.postalCode} onChange={e => setField('postalCode', e.target.value)}
                style={inputStyle(!!fieldErrors.postalCode)} />
            </Field>

            <button type="submit" disabled={pay.isPending} style={{
              width: '100%', padding: '13px', background: 'var(--ink)', color: '#fff',
              border: 'none', borderRadius: 'var(--r-sm)', fontWeight: 700, fontSize: 16, marginTop: 6,
            }}>
              {pay.isPending ? 'Continuing…' : 'Continue to payment'}
            </button>
            <p style={{ fontSize: 12, color: 'var(--ink-soft)', textAlign: 'center' }}>
              You'll complete payment securely on Stripe
            </p>
          </form>
        </main>
      </>
    )
  }

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
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
