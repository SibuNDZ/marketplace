import React, { FormEvent, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, Page, ProductResponse } from '../lib/api'
import { SiteHeader } from '../components/layout/SiteHeader'
import { useCategoryTree } from '../hooks/useCategoryTree'
import heroImg from '../assets/landing/hero-editorial.jpg'
import deptPantry from '../assets/landing/dept-pantry.jpg'
import deptFashion from '../assets/landing/dept-fashion.jpg'
import deptHomeLiving from '../assets/landing/dept-home-living.jpg'
import deptJewellery from '../assets/landing/dept-jewellery.jpg'
import deptBeauty from '../assets/landing/dept-beauty.jpg'
import '../styles/landing.css'

/**
 * The editorial landing at "/" — the Avant-Garde Boutique composition.
 *
 * Honesty contract, same as everywhere else: every number on this page is
 * fetched, never typed. The featured rail is the real catalogue, the
 * spotlight vendor's stats come from their live listings, the trust band
 * only claims what the platform actually does, and the newsletter form
 * stores a real subscription. Full catalogue browsing lives one click away:
 * any category/search/filter URL still renders the classic catalogue (see
 * RootSwitch in App.tsx).
 */

/**
 * Department slugs with photography; the rest get type tiles.
 *
 * Curation rule (owner directive, 2026-08-30): tile imagery must SUPPORT
 * the locally-curated positioning — South African subjects only (the
 * veldskoen, the custom-made SA oak table). International brand
 * photography is the same dishonesty as a fake badge; departments without
 * a local photo keep their typographic tile until one exists.
 */
const DEPT_IMAGES: Record<string, string> = {
  pantry: deptPantry,
  fashion: deptFashion,
  'home-and-living': deptHomeLiving,
  // The Jewellery DEPARTMENT's root slug is jewellery-collections;
  // plain "jewellery" is a subcategory under Fashion.
  'jewellery-collections': deptJewellery,
  'beauty-and-personal-care': deptBeauty,
}

function DepartmentsSection() {
  const { data: tree } = useCategoryTree(false)
  const roots = useMemo(
    () => (tree ?? []).slice().sort((a, b) => b.productCount - a.productCount).slice(0, 6),
    [tree],
  )
  if (roots.length === 0) return null
  return (
    <section className="landing-departments" aria-labelledby="departments-heading">
      <h2 className="landing-heading" id="departments-heading">Departments</h2>
      <hr className="landing-rule" />
      <div className="dept-grid">
        {roots.map((root, i) => {
          const img = DEPT_IMAGES[root.slug]
          return (
            <Link
              key={root.slug}
              to={`/?category=${root.slug}`}
              className={img
                ? 'dept-tile'
                : `dept-tile dept-tile--type ${i % 2 ? 'dept-tile--charcoal' : 'dept-tile--ember'}`}
            >
              {img
                ? <img src={img} alt="" loading="lazy" />
                : <span className="dept-tile__glyph" aria-hidden>{root.icon ?? '🛍️'}</span>}
              <span className="dept-tile__label">
                <h3>{root.name}</h3>
                <span className="num">
                  {root.productCount} {root.productCount === 1 ? 'piece' : 'pieces'}
                </span>
              </span>
            </Link>
          )
        })}
      </div>
    </section>
  )
}

function FeaturedSection() {
  const { data } = useQuery<Page<ProductResponse>>({
    queryKey: ['landing', 'featured'],
    queryFn: () => api('/api/v1/products?page=0&size=8&sort=createdAt,desc'),
    staleTime: 5 * 60 * 1000,
  })
  const products = data?.content ?? []
  if (products.length === 0) return null
  return (
    <section className="landing-featured" aria-labelledby="featured-heading">
      <div className="landing-featured__head">
        {/* "Featured", not "Featured locally": the rail shows whatever the
            live catalogue holds, and today that includes international
            brands. A locality claim the products do not back is the same
            dishonesty as a fake badge — reinstate the word only if the
            rail is ever filtered to local makers. */}
        <h2 className="landing-heading landing-heading--left" id="featured-heading">
          Featured
        </h2>
        <Link to="/?shop=all" className="landing-link">Shop all <span aria-hidden>→</span></Link>
      </div>
      <div className="featured-rail">
        {products.map(p => (
          <Link key={p.id} to={`/products/${p.id}`} className="featured-card">
            <span className="featured-card__well">
              {p.imageUrl && <img src={p.imageUrl} alt={p.name} loading="lazy" />}
            </span>
            <span className="featured-card__vendor">{p.vendorName}</span>
            <span className="featured-card__name">{p.name}</span>
            <span className="featured-card__price num">
              {/* en-ZA: space-grouped thousands, comma decimals — R 30 165,71 */}
              R {Number(p.price).toLocaleString('en-ZA',
                { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
            </span>
          </Link>
        ))}
      </div>
    </section>
  )
}

function SpotlightSection() {
  const { data } = useQuery<Page<ProductResponse>>({
    queryKey: ['landing', 'spotlight-pool'],
    queryFn: () => api('/api/v1/products?page=0&size=50'),
    staleTime: 5 * 60 * 1000,
  })
  const spotlight = useMemo(() => {
    const byVendor = new Map<number, ProductResponse[]>()
    for (const p of data?.content ?? []) {
      // Editorial pick: most listings wins. Seed/demo accounts are not
      // real stores and never get the spotlight.
      if (p.vendorName === 'Fixture Vendor') continue
      if (p.vendorId == null) continue
      const list = byVendor.get(p.vendorId) ?? []
      list.push(p)
      byVendor.set(p.vendorId, list)
    }
    let best: ProductResponse[] | undefined
    for (const list of byVendor.values()) {
      if (!best || list.length > best.length) best = list
    }
    if (!best) return undefined
    const sold = best.reduce((n, p) => n + (p.soldCount ?? 0), 0)
    const reviews = best.reduce((n, p) => n + p.reviewCount, 0)
    // avgRating is a string (BigDecimal serialization, see ProductResponse)
    const rating = reviews > 0
      ? best.reduce((n, p) => n + Number(p.avgRating) * p.reviewCount, 0) / reviews
      : undefined
    const withImage = best.find(p => p.imageUrl)
    return {
      vendorId: best[0].vendorId,
      name: best[0].vendorName,
      pieces: best.length,
      sold,
      rating,
      image: withImage?.imageUrl,
      imageAlt: withImage ? `${withImage.name}, listed by ${best[0].vendorName}` : '',
    }
  }, [data])

  if (!spotlight) return null
  return (
    <section className="landing-spotlight" aria-labelledby="spotlight-heading">
      <div className="landing-spotlight__inner">
        <div className="landing-spotlight__art">
          {spotlight.image && <img src={spotlight.image} alt={spotlight.imageAlt} loading="lazy" />}
          {/* No possessive: vendor names ending in s ("Cavioure Designers")
              would render as the awkward "Designers's". */}
          <p className="landing-spotlight__caption">
            From the live listings of {spotlight.name}
          </p>
        </div>
        <div className="landing-spotlight__body">
          <span className="landing-kicker">Vendor spotlight</span>
          <h2 id="spotlight-heading">{spotlight.name}</h2>
          <p className="landing-spotlight__blurb">
            A store currently selling on eRestyu. Every piece is listed, priced
            and dispatched by the vendor themselves. The figures below come
            straight from their live listings.
          </p>
          <div className="landing-spotlight__stats">
            <div><strong className="num">{spotlight.pieces}</strong><span>Pieces</span></div>
            <div><strong className="num">{spotlight.sold}</strong><span>Sold</span></div>
            <div>
              <strong className={spotlight.rating ? 'num' : undefined}>
                {spotlight.rating ? spotlight.rating.toFixed(1) : 'New'}
              </strong>
              <span>Rating</span>
            </div>
          </div>
          <Link to={`/shop/${spotlight.vendorId}`} className="landing-link">
            Visit their store <span aria-hidden>→</span>
          </Link>
        </div>
      </div>
    </section>
  )
}

/* Every claim below is traced to a real behavior: Yoco-hosted encrypted
   checkout; per-vendor delivery with the fee itemised before payment
   (OrderService delivery lines); free cancellation on unpaid orders
   (terms page, OrderService.cancelOrder); hello@erestyu.com is live. */
const TRUST_ITEMS = [
  {
    title: 'Secure checkout',
    body: 'Payments run on an encrypted, hosted checkout. Your card details never touch our servers.',
  },
  {
    title: 'Delivery across South Africa',
    body: 'Vendors dispatch nationwide. Any delivery fee is itemised before you pay, never after.',
  },
  {
    title: 'Cancel unpaid orders free',
    body: 'Changed your mind before paying? Cancel instantly and the stock is released. Our team handles anything after.',
  },
  {
    title: 'Local support',
    body: 'A South African team answers at hello@erestyu.com.',
  },
]

function TrustSection() {
  return (
    <section className="landing-trust" aria-label="Why shop with eRestyu">
      <div className="landing-trust__inner">
        {TRUST_ITEMS.map(item => (
          <div key={item.title} className="landing-trust__item">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M20 6L9 17l-5-5" />
            </svg>
            <h3>{item.title}</h3>
            <p>{item.body}</p>
          </div>
        ))}
      </div>
    </section>
  )
}

function NewsletterSection() {
  const [email, setEmail] = useState('')
  const [state, setState] = useState<'idle' | 'sending' | 'done' | 'error'>('idle')

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setState('sending')
    try {
      await api('/api/v1/newsletter/subscribe', {
        method: 'POST', body: { email }, auth: false,
      })
      setState('done')
    } catch {
      setState('error')
    }
  }

  return (
    <section className="landing-newsletter" aria-labelledby="newsletter-heading">
      <h2 className="landing-heading" id="newsletter-heading">Join the inner circle</h2>
      <hr className="landing-rule" />
      <p>
        Occasional news from South Africa&rsquo;s makers: new arrivals, vendor
        stories, and early access when something special lands.
      </p>
      {state === 'done' ? (
        <p className="landing-newsletter__done">
          You&rsquo;re on the list. Welcome to the inner circle.
        </p>
      ) : (
        <>
          <form onSubmit={submit}>
            <input
              type="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="Enter your email"
              aria-label="Email address"
            />
            <button type="submit" disabled={state === 'sending'}>
              {state === 'sending' ? 'Joining…' : 'Subscribe'} <span aria-hidden>→</span>
            </button>
          </form>
          {state === 'error' && (
            <p className="landing-newsletter__error">
              That did not go through. Please try again in a moment.
            </p>
          )}
        </>
      )}
      <p className="landing-newsletter__fine">No spam. Unsubscribe any time.</p>
    </section>
  )
}

export function HomePage() {
  return (
    <>
      <SiteHeader />
      <main className="landing">
        <section className="landing-hero" aria-label="eRestyu, South Africa's marketplace">
          <div className="landing-hero__copy">
            <span className="landing-kicker">Curated excellence</span>
            <h1>The Local <em>Loom.</em></h1>
            <p className="landing-hero__sub">
              A marketplace celebrating South Africa&rsquo;s makers and
              independent brands, from the Karoo to the Coast.
            </p>
            <Link to="/?shop=all" className="landing-cta">
              Shop collections <span aria-hidden>→</span>
            </Link>
          </div>
          <div className="landing-hero__art">
            <img src={heroImg} alt="South African artisan fashion editorial" />
          </div>
        </section>

        <DepartmentsSection />
        <FeaturedSection />
        <SpotlightSection />
        <TrustSection />
        <NewsletterSection />
      </main>
    </>
  )
}
