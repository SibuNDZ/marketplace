import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError, Page, ReviewResponse, ReviewSummary } from '../../lib/api'
import { useAuth } from '../../context/AuthContext'

/** Read-only star row. Half stars are not shown: the data is whole 1-5 ratings. */
function Stars({ rating, size = 14 }: { rating: number; size?: number }) {
  const full = Math.round(rating)
  return (
    <span style={{ color: 'var(--marigold)', fontSize: size, letterSpacing: 1 }}
      aria-label={`${rating} out of 5`}>
      {'★'.repeat(full)}{'☆'.repeat(5 - full)}
    </span>
  )
}

/** Clickable stars for the write/edit form. */
function StarPicker({ value, onChange }: { value: number; onChange: (n: number) => void }) {
  const [hover, setHover] = useState(0)
  const shown = hover || value
  return (
    <div style={{ display: 'flex', gap: 2 }} onMouseLeave={() => setHover(0)}>
      {[1, 2, 3, 4, 5].map(n => (
        <button key={n} type="button" onClick={() => onChange(n)} onMouseEnter={() => setHover(n)}
          aria-label={`${n} star${n > 1 ? 's' : ''}`}
          style={{
            background: 'none', border: 'none', cursor: 'pointer', padding: '2px 1px',
            fontSize: 26, lineHeight: 1, minHeight: 44, minWidth: 34,
            color: n <= shown ? 'var(--marigold)' : 'var(--line)',
          }}>
          {n <= shown ? '★' : '☆'}
        </button>
      ))}
    </div>
  )
}

function ratingBreakdown(reviews: ReviewResponse[]) {
  const counts = [0, 0, 0, 0, 0] // index 0 = 1 star
  reviews.forEach(r => { if (r.rating >= 1 && r.rating <= 5) counts[r.rating - 1]++ })
  return counts
}

/**
 * The review section: aggregate, distribution, the caller's own write/edit
 * form, and the list.
 *
 * Eligibility comes from the summary endpoint (canReview / myReviewId), not
 * from guessing client-side: reviewing requires a DELIVERED purchase and is
 * one-per-product, so rendering a form for everyone would invite people to
 * write something and then reject it. Each state gets an honest explanation
 * instead.
 */
export function ProductReviews({ productId, summary }: { productId: string; summary?: ReviewSummary }) {
  const qc = useQueryClient()
  const { user } = useAuth()
  const [editing, setEditing] = useState(false)
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')
  const [error, setError] = useState<string>()

  const { data: reviews, isLoading } = useQuery<Page<ReviewResponse>>({
    queryKey: ['reviews', productId],
    queryFn: () => api(`/api/v1/products/${productId}/reviews?size=50`),
    enabled: !!productId,
  })

  const items = reviews?.content ?? []
  const myReviewId = summary?.myReviewId ?? null
  const myReview = items.find(r => r.id === myReviewId)

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['reviews', productId] })
    qc.invalidateQueries({ queryKey: ['review-summary', productId] })
    qc.invalidateQueries({ queryKey: ['product', productId] })
  }

  const save = useMutation({
    mutationFn: () => myReviewId
      ? api(`/api/v1/reviews/${myReviewId}`, { method: 'PUT', body: { rating, comment } })
      : api(`/api/v1/products/${productId}/reviews`, { method: 'POST', body: { rating, comment } }),
    onSuccess: () => { refresh(); setEditing(false); setError(undefined) },
    onError: (e) => setError(e instanceof ApiError ? e.detail : 'Could not save your review.'),
  })

  const remove = useMutation({
    mutationFn: () => api(`/api/v1/reviews/${myReviewId}`, { method: 'DELETE' }),
    onSuccess: () => { refresh(); setEditing(false); setRating(0); setComment('') },
    onError: (e) => setError(e instanceof ApiError ? e.detail : 'Could not delete your review.'),
  })

  const startEdit = () => {
    setRating(myReview?.rating ?? 0)
    setComment(myReview?.comment ?? '')
    setEditing(true)
    setError(undefined)
  }

  const breakdown = ratingBreakdown(items)
  const total = items.length || 1

  return (
    <section style={{ marginTop: 48, borderTop: '1px solid var(--line)', paddingTop: 32 }}>
      <h2 style={{ fontFamily: 'var(--display)', fontWeight: 700, fontSize: 22, marginBottom: 20 }}>
        Reviews
      </h2>

      {summary && summary.reviewCount > 0 ? (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 40, alignItems: 'flex-start', marginBottom: 28 }}>
          <div>
            <div style={{ fontSize: 40, fontWeight: 800, lineHeight: 1 }} className="num">
              {summary.averageRating.toFixed(1)}
            </div>
            <Stars rating={summary.averageRating} size={16} />
            <div style={{ fontSize: 13, color: 'var(--ink-soft)', marginTop: 4 }}>
              <span className="num">{summary.reviewCount}</span> review{summary.reviewCount !== 1 ? 's' : ''}
            </div>
          </div>
          {/* Distribution from the loaded page of reviews. */}
          <div style={{ flex: 1, minWidth: 220, maxWidth: 380, display: 'grid', gap: 4 }}>
            {[5, 4, 3, 2, 1].map(star => {
              const count = breakdown[star - 1]
              return (
                <div key={star} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
                  <span className="num" style={{ width: 12, color: 'var(--ink-soft)' }}>{star}</span>
                  <span style={{ color: 'var(--marigold)' }}>★</span>
                  <div style={{ flex: 1, height: 7, background: 'var(--line)', borderRadius: 4, overflow: 'hidden' }}>
                    <div style={{ width: `${(count / total) * 100}%`, height: '100%', background: 'var(--marigold)' }} />
                  </div>
                  <span className="num" style={{ width: 22, textAlign: 'right', color: 'var(--ink-soft)' }}>{count}</span>
                </div>
              )
            })}
          </div>
        </div>
      ) : (
        <p style={{ color: 'var(--ink-soft)', fontSize: 14, marginBottom: 24 }}>
          No reviews yet. Only buyers whose order has been delivered can review, so
          every rating here comes from someone who actually received the item.
        </p>
      )}

      {/* --- the caller's own review --- */}
      {!user ? (
        <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 24 }}>
          <Link to="/login" state={{ from: `/products/${productId}` }}
            style={{ color: 'var(--aloe)', fontWeight: 600 }}>Sign in</Link>{' '}
          to review a product you have bought.
        </p>
      ) : editing ? (
        <form onSubmit={e => { e.preventDefault(); save.mutate() }} style={{
          border: '1px solid var(--line)', borderRadius: 'var(--r)', padding: 18,
          marginBottom: 28, display: 'flex', flexDirection: 'column', gap: 12,
        }}>
          <strong style={{ fontSize: 14 }}>{myReviewId ? 'Edit your review' : 'Write a review'}</strong>
          <StarPicker value={rating} onChange={setRating} />
          <textarea value={comment} onChange={e => setComment(e.target.value)} rows={4} maxLength={1000}
            placeholder="What was it like? Be specific: quality, fit, delivery."
            style={{
              padding: '10px 12px', border: '1px solid var(--line)', borderRadius: 'var(--r-sm)',
              fontFamily: 'var(--body)', fontSize: 14, resize: 'vertical',
            }} />
          {error && <p style={{ fontSize: 13, color: 'var(--clay)' }}>{error}</p>}
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
            <button type="submit" disabled={rating < 1 || save.isPending} style={{
              padding: '10px 20px', background: 'var(--flame-gradient)', color: '#fff', border: 'none',
              borderRadius: 'var(--r-sm)', fontWeight: 700, cursor: 'pointer', minHeight: 44,
              opacity: rating < 1 || save.isPending ? 0.6 : 1,
            }}>
              {save.isPending ? 'Saving…' : myReviewId ? 'Update review' : 'Post review'}
            </button>
            <button type="button" onClick={() => { setEditing(false); setError(undefined) }} style={{
              background: 'none', border: 'none', color: 'var(--ink-soft)', fontSize: 13,
              fontWeight: 600, cursor: 'pointer', minHeight: 44,
            }}>Cancel</button>
            {myReviewId && (
              <button type="button"
                onClick={() => { if (confirm('Delete your review?')) remove.mutate() }}
                style={{
                  marginLeft: 'auto', background: 'none', border: 'none', color: 'var(--clay)',
                  fontSize: 13, fontWeight: 600, cursor: 'pointer', minHeight: 44,
                }}>Delete</button>
            )}
          </div>
        </form>
      ) : myReviewId ? (
        <div style={{ marginBottom: 24, display: 'flex', gap: 10, alignItems: 'center' }}>
          <span style={{ fontSize: 13, color: 'var(--ink-soft)' }}>You reviewed this product.</span>
          <button onClick={startEdit} style={{
            background: 'none', border: 'none', color: 'var(--trust-blue)',
            fontSize: 13, fontWeight: 600, cursor: 'pointer', minHeight: 44,
          }}>Edit your review</button>
        </div>
      ) : summary?.canReview ? (
        <button onClick={startEdit} style={{
          marginBottom: 24, padding: '10px 20px', border: '1px solid var(--aloe)',
          background: 'var(--aloe-tint)', color: 'var(--aloe-deep)', borderRadius: 'var(--r-sm)',
          fontWeight: 700, cursor: 'pointer', minHeight: 44,
        }}>
          Write a review
        </button>
      ) : (
        <p style={{ fontSize: 13, color: 'var(--ink-soft)', marginBottom: 24 }}>
          You can review this once an order containing it has been delivered.
        </p>
      )}

      {/* --- the list --- */}
      {isLoading ? <p style={{ color: 'var(--ink-soft)' }}>Loading reviews…</p> : items.length > 0 && (
        <div style={{ display: 'grid', gap: 18 }}>
          {items.map(r => (
            <article key={r.id} style={{ borderTop: '1px solid var(--line)', paddingTop: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6, flexWrap: 'wrap' }}>
                <strong style={{ fontSize: 13 }}>{r.reviewerName || 'Buyer'}</strong>
                <Stars rating={r.rating} />
                <span className="num" style={{ fontSize: 12, color: 'var(--ink-soft)' }}>
                  {new Date(r.createdAt).toLocaleDateString()}
                </span>
                {r.id === myReviewId && (
                  <span style={{
                    fontSize: 11, fontWeight: 700, color: 'var(--aloe-deep)',
                    background: 'var(--aloe-tint)', padding: '2px 8px', borderRadius: 'var(--r-pill)',
                  }}>Yours</span>
                )}
              </div>
              {r.comment && (
                <p style={{ fontSize: 14, lineHeight: 1.6, color: 'var(--ink-soft)', whiteSpace: 'pre-wrap' }}>
                  {r.comment}
                </p>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
