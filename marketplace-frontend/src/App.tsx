import React, { useLayoutEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate, useLocation, useNavigationType } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { CartDrawerProvider } from './context/CartDrawerContext'
import { RightPanelProvider } from './context/RightPanelContext'
import { Footer } from './components/layout/Footer'
import { FaqWidget } from './components/support/FaqWidget'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { CheckEmailPage } from './pages/CheckEmailPage'
import { VerifyEmailPage } from './pages/VerifyEmailPage'
import { ForgotPasswordPage } from './pages/ForgotPasswordPage'
import { ResetPasswordPage } from './pages/ResetPasswordPage'
import { CatalogPage } from './pages/CatalogPage'
import { ProductDetailPage } from './pages/ProductDetailPage'
import { VendorShopPage } from './pages/VendorShopPage'
import { SimilarItemsPage } from './pages/SimilarItemsPage'
import { CartPage } from './pages/CartPage'
import { CheckoutSuccessPage } from './pages/CheckoutSuccessPage'
import { CheckoutCancelledPage } from './pages/CheckoutCancelledPage'
import { OrdersPage } from './pages/OrdersPage'
import { OrderDetailPage } from './pages/OrderDetailPage'
import { VendorDashboardPage } from './pages/VendorDashboardPage'
import { VendorOrdersPage } from './pages/VendorOrdersPage'
import { AccountSettingsPage } from './pages/AccountSettingsPage'
import { FeedbackPage } from './pages/FeedbackPage'
import { ProductFormPage } from './pages/ProductFormPage'
import { AdminPage } from './pages/AdminPage'
import { AdminFeedbackPage } from './pages/AdminFeedbackPage'
import { AdminOrderDetailPage } from './pages/AdminOrderDetailPage'
import { PrivacyPolicyPage } from './pages/legal/PrivacyPolicyPage'
import { TermsPage } from './pages/legal/TermsPage'
import { AboutPage, CareersPage, ContactPage, ReturnsPage, ShippingInfoPage, HelpPage, HowItWorksPage } from './pages/InfoPages'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return null
  if (!user) return <Navigate to="/login" replace />
  return <>{children}</>
}

// Rendered once after Routes so it lands below whatever page just rendered.
// Hidden on auth pages — a marketing footer is clutter on a login/register form.
const AUTH_ROUTES = new Set([
  '/login', '/register', '/check-email', '/verify-email',
  '/forgot-password', '/reset-password',
])

function ChromeFooter() {
  const { pathname } = useLocation()
  if (AUTH_ROUTES.has(pathname)) return null
  return <Footer />
}

/** Same rule as the footer: a help bubble is clutter on a login form. */
function ChromeFaq() {
  const { pathname } = useLocation()
  if (AUTH_ROUTES.has(pathname)) return null
  return <FaqWidget />
}

/**
 * Resets scroll position when the route changes.
 *
 * React Router does not touch scroll on a client-side navigation, so a link
 * clicked while scrolled down mounts the next page at the OLD offset. Click
 * a related product from the shelf at the bottom of a product page and you
 * land on the new page already scrolled to its footer, looking at nothing.
 *
 * This is a plain component rather than react-router's own
 * <ScrollRestoration>, which only works under a data router
 * (createBrowserRouter). This app uses BrowserRouter + a flat <Routes>, so
 * that component is unavailable without restructuring the whole router.
 *
 * useLayoutEffect, not useEffect: it runs before the browser paints, so the
 * page never flashes at the wrong offset on the way to the top.
 *
 * Two deliberate exceptions, both cases where jumping to the top is wrong:
 *
 *   - A hash link is a request to go somewhere specific. The product page's
 *     "(4 reviews)" link is an href="#reviews"; scrolling to top would make
 *     it do nothing at all.
 *   - POP is the back/forward button. The browser restores the previous
 *     offset itself, and overriding that means going Back from a product
 *     dumps you at the top of the catalogue having lost your place in the
 *     grid — the exact complaint this component exists to fix, inverted.
 *
 * Keyed on pathname only, NOT search. Filter and pagination changes on the
 * catalogue rewrite the query string, and whether those should jump to the
 * top is a separate product decision from "links are broken".
 *
 * history.scrollRestoration is deliberately left at 'auto'. Setting it to
 * 'manual' would stop the browser restoring the catalogue offset on Back,
 * and this component skips POP precisely because the browser handles it.
 * The refresh-on-a-stub-document case is covered by the product page's own
 * loading→loaded scroll reset and its layout-matching skeleton instead.
 */
function ScrollToTop() {
  const { pathname, hash } = useLocation()
  const navigationType = useNavigationType()

  useLayoutEffect(() => {
    if (hash) return
    if (navigationType === 'POP') return
    window.scrollTo(0, 0)
  }, [pathname, hash, navigationType])

  return null
}

function SkipToContent() {
  return (
    <a
      href="#main-content"
      className="skip-link"
      onClick={(event) => {
        const main = document.querySelector('main')
        if (!(main instanceof HTMLElement)) return
        event.preventDefault()
        main.id = 'main-content'
        main.tabIndex = -1
        main.focus({ preventScroll: true })
        main.scrollIntoView({ block: 'start' })
      }}
    >
      Skip to content
    </a>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <SkipToContent />
      <CartDrawerProvider>
        <RightPanelProvider>
        <ScrollToTop />
        <Routes>
          <Route path="/" element={<CatalogPage />} />
          <Route path="/products/:id" element={<ProductDetailPage />} />
          <Route path="/products/:id/similar" element={<SimilarItemsPage />} />
          {/* Public storefront. Declared before the auth-gated /vendor
              routes it sits beside; "shop" keeps it unambiguous. */}
          <Route path="/shop/:vendorId" element={<VendorShopPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/check-email" element={<CheckEmailPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/checkout/success" element={<CheckoutSuccessPage />} />
          <Route path="/checkout/cancelled" element={<CheckoutCancelledPage />} />
          <Route path="/cart" element={<RequireAuth><CartPage /></RequireAuth>} />
          <Route path="/orders" element={<RequireAuth><OrdersPage /></RequireAuth>} />
          <Route path="/orders/:id" element={<RequireAuth><OrderDetailPage /></RequireAuth>} />
          <Route path="/vendor" element={<RequireAuth><VendorDashboardPage /></RequireAuth>} />
          <Route path="/vendor/orders" element={<RequireAuth><VendorOrdersPage /></RequireAuth>} />
          <Route path="/account" element={<RequireAuth><AccountSettingsPage /></RequireAuth>} />
          <Route path="/feedback" element={<RequireAuth><FeedbackPage /></RequireAuth>} />
          <Route path="/vendor/products/new" element={<RequireAuth><ProductFormPage /></RequireAuth>} />
          <Route path="/vendor/products/:id/edit" element={<RequireAuth><ProductFormPage /></RequireAuth>} />
          <Route path="/admin" element={<RequireAuth><AdminPage /></RequireAuth>} />
          <Route path="/admin/feedback" element={<RequireAuth><AdminFeedbackPage /></RequireAuth>} />
          <Route path="/admin/orders/:id" element={<RequireAuth><AdminOrderDetailPage /></RequireAuth>} />
          <Route path="/privacy" element={<PrivacyPolicyPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route path="/careers" element={<CareersPage />} />
          <Route path="/contact" element={<ContactPage />} />
          <Route path="/returns" element={<ReturnsPage />} />
          <Route path="/shipping" element={<ShippingInfoPage />} />
          <Route path="/help" element={<HelpPage />} />
          <Route path="/how-it-works" element={<HowItWorksPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        <ChromeFooter />
        <ChromeFaq />
        </RightPanelProvider>
      </CartDrawerProvider>
    </BrowserRouter>
  )
}
