import React from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Heart } from 'lucide-react'
import { ApiError } from '../../lib/api'
import { useAuth } from '../../context/AuthContext'
import { useFavoriteIds, useToggleFavorite } from '../../hooks/useFavorites'

/**
 * The wishlist heart. Same signed-out contract as Add to cart on the card:
 * a guest's tap is not an error, it is a sign-in with a way back to the
 * exact view they were browsing.
 */
export function FavoriteHeart({ productId, className = 'fav-heart' }: {
  productId: number
  className?: string
}) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const favoriteIds = useFavoriteIds()
  const toggle = useToggleFavorite()

  const isFavorite = favoriteIds.has(productId)

  const onClick = (event: React.MouseEvent) => {
    // The heart often sits inside the card's image Link.
    event.preventDefault()
    event.stopPropagation()
    if (!user) {
      navigate('/login', { state: { from: location.pathname + location.search } })
      return
    }
    toggle.mutate(
      { productId, next: !isFavorite },
      {
        onError: (e) => {
          if (e instanceof ApiError && e.status === 401) {
            navigate('/login', { state: { from: location.pathname + location.search } })
          }
        },
      },
    )
  }

  return (
    <button
      type="button"
      className={`${className}${isFavorite ? ' is-favorite' : ''}`}
      onClick={onClick}
      aria-pressed={isFavorite}
      aria-label={isFavorite ? 'Remove from wishlist' : 'Save to wishlist'}
      title={isFavorite ? 'Remove from wishlist' : 'Save to wishlist'}
    >
      <Heart size={18} strokeWidth={2}
        fill={isFavorite ? 'currentColor' : 'none'} aria-hidden />
    </button>
  )
}
