import React, { FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, CartResponse, ApiError, PayResponse, ShippingAddress, fieldErrorsFrom } from '../lib/api'
import { SiteHeader as Topbar } from '../components/layout/SiteHeader'
import { ErrorSurface } from '../components/ui/ErrorSurface'
import { CartLineImage } from '../components/cart/CartLineImage'

const EMPTY_SHIPPING: ShippingAddress = {
  recipientName: '', phone: '', addressLine1: '', addressLine2: '',
  city: '', province: '', postalCode: '',
}

function Field({ label, error, children }: { label: string; error?: string[]; children: React.ReactNode }) {
  return (
    <label className="field">
      {label}
      {children}
      {error?.map((msg, i) => (
        <span key={i} className="field__error">{msg}</span>
      ))}
    </label>
  )
}

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
    mutationFn: ({ productId, variantId, quantity }: { productId: number; variantId?: number | null; quantity: number }) =>
      // variantId identifies WHICH line: a product can now appear twice in
      // one cart under different options. Same contract as RightCartPanel.
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

  const placeOrder = useMutation({
    mutationFn: () => api<{ id: number }>('/api/v1/orders', { method: 'POST' }),
    onSuccess: (order) => setPendingOrderId(order.id),
    onError: (e) => { if (e instanceof ApiError) setCheckoutError(e) },
  })

  const pay = useMutation({
    mutationFn: () =>
      api<PayResponse>(`/api/v1/orders/${pendingOrderId}/pay`, { method: 'POST', body: shipping }),
    // The response SHAPE says which provider answered: {checkoutUrl} is a
    // Stripe redirect; {processUrl, fields} is PayFast, which wants a form
    // POST of the signed fields in the exact order the backend built them.
    onSuccess: (session) => {
      if ('checkoutUrl' in session && session.checkoutUrl) {
        window.location.href = session.checkoutUrl
        return
      }
      if ('processUrl' in session && session.processUrl && session.fields) {
        const form = document.createElement('form')
        form.method = 'POST'
        form.action = session.processUrl
        for (const [name, value] of Object.entries(session.fields)) {
          const input = document.createElement('input')
          input.type = 'hidden'
          input.name = name
          input.value = value
          form.appendChild(input)
        }
        document.body.appendChild(form)
        form.submit()
      }
    },
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
        <main className="page-shell no-catrail narrow-form">
          <h1 className="page-heading page-heading--sm">Shipping details</h1>
          <p className="lede">
            Order <span className="num">#{pendingOrderId}</span>. Where should it be delivered?
          </p>

          <form onSubmit={submitShipping} className="form-stack">
            {payError && <ErrorSurface error={payError} onDismiss={() => setPayError(undefined)} />}

            <Field label="Recipient name" error={fieldErrors.recipientName}>
              <input required value={shipping.recipientName} onChange={e => setField('recipientName', e.target.value)}
                className={`input${fieldErrors.recipientName ? ' is-invalid' : ''}`} />
            </Field>

            <Field label="Phone" error={fieldErrors.phone}>
              <input required type="tel" value={shipping.phone} onChange={e => setField('phone', e.target.value)}
                className={`input${fieldErrors.phone ? ' is-invalid' : ''}`} />
            </Field>

            <Field label="Address line 1" error={fieldErrors.addressLine1}>
              <input required value={shipping.addressLine1} onChange={e => setField('addressLine1', e.target.value)}
                className={`input${fieldErrors.addressLine1 ? ' is-invalid' : ''}`} />
            </Field>

            <Field label="Address line 2 (optional)" error={fieldErrors.addressLine2}>
              <input value={shipping.addressLine2 ?? ''} onChange={e => setField('addressLine2', e.target.value)}
                className={`input${fieldErrors.addressLine2 ? ' is-invalid' : ''}`} />
            </Field>

            <div className="form-row">
              <Field label="City" error={fieldErrors.city}>
                <input required value={shipping.city} onChange={e => setField('city', e.target.value)}
                  className={`input${fieldErrors.city ? ' is-invalid' : ''}`} />
              </Field>
              <Field label="Province" error={fieldErrors.province}>
                <input required value={shipping.province} onChange={e => setField('province', e.target.value)}
                  className={`input${fieldErrors.province ? ' is-invalid' : ''}`} />
              </Field>
            </div>

            <Field label="Postal code" error={fieldErrors.postalCode}>
              <input required value={shipping.postalCode} onChange={e => setField('postalCode', e.target.value)}
                className={`input${fieldErrors.postalCode ? ' is-invalid' : ''}`} />
            </Field>

            <button type="submit" disabled={pay.isPending} className="btn-primary">
              {pay.isPending ? 'Continuing…' : 'Continue to payment'}
            </button>
            <p className="hint">You'll complete payment on our secure payment provider</p>
          </form>
        </main>
      </>
    )
  }

  return (
    <>
      <Topbar />
      <main className="page-shell no-catrail">
        <h1 className="page-heading">Your cart</h1>

        {isLoading && <p className="muted">Loading…</p>}

        {!isLoading && isEmpty && (
          <div className="cart-empty">
            <p>Your cart is empty</p>
            <Link to="/" className="btn-pill">Browse products</Link>
          </div>
        )}

        {!isEmpty && (
          <div className="cart-layout">
            <div>
              {lines.map(line => (
                <div key={`${line.productId}:${line.variantId ?? 'base'}`} className="cart-line">
                  <CartLineImage line={line} size={56} />
                  <div className="cart-line__body">
                    <p className="cart-line__name">
                      {line.productName}
                      {line.variantLabel && (
                        <span className="cart-line__opt"> · {line.variantLabel}</span>
                      )}
                    </p>
                    <p className="num cart-line__unit">R{Number(line.unitPrice).toFixed(2)} each</p>
                    {line.availableStock < line.quantity && (
                      <p className="cart-line__warn">
                        Only <span className="num">{line.availableStock}</span> available
                      </p>
                    )}
                  </div>
                  <div className="qty-stepper">
                    <button
                      type="button"
                      className="qty-btn qty-btn--boxed"
                      aria-label={`Decrease quantity of ${line.productName}`}
                      onClick={() => updateQty.mutate({ productId: line.productId, variantId: line.variantId, quantity: line.quantity - 1 })}
                    >−</button>
                    <span className="num qty-value">{line.quantity}</span>
                    <button
                      type="button"
                      className="qty-btn qty-btn--boxed"
                      aria-label={`Increase quantity of ${line.productName}`}
                      onClick={() => updateQty.mutate({ productId: line.productId, variantId: line.variantId, quantity: line.quantity + 1 })}
                    >+</button>
                  </div>
                  <p className="num cart-line__sum">
                    R{Number(line.lineTotal).toFixed(2)}
                  </p>
                  <button
                    type="button"
                    className="qty-btn cart-line__remove"
                    aria-label={`Remove ${line.productName} from cart`}
                    onClick={() => removeItem.mutate({ productId: line.productId, variantId: line.variantId })}
                  >×</button>
                </div>
              ))}
            </div>

            <div className="cart-summary">
              <div className="cart-summary__total">
                <span style={{ fontWeight: 600 }}>Total</span>
                <span className="num" style={{ fontWeight: 700, fontSize: 20 }}>R{Number(cart?.subtotal ?? 0).toFixed(2)}</span>
              </div>
              {checkoutError && <ErrorSurface error={checkoutError} onDismiss={() => setCheckoutError(undefined)} />}
              <button disabled={placeOrder.isPending} onClick={() => placeOrder.mutate()} className="btn-primary">
                {placeOrder.isPending ? 'Placing order…' : 'Continue to payment'}
              </button>
              <p className="hint">You'll complete payment on our secure payment provider</p>
            </div>
          </div>
        )}
      </main>
    </>
  )
}
