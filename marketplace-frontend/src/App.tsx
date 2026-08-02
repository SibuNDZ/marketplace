import React from 'react'
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { CartDrawerProvider } from './context/CartDrawerContext'
import { Footer } from './components/layout/Footer'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { CheckEmailPage } from './pages/CheckEmailPage'
import { VerifyEmailPage } from './pages/VerifyEmailPage'
import { ForgotPasswordPage } from './pages/ForgotPasswordPage'
import { ResetPasswordPage } from './pages/ResetPasswordPage'
import { CatalogPage } from './pages/CatalogPage'
import { ProductDetailPage } from './pages/ProductDetailPage'
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

export default function App() {
  return (
    <BrowserRouter>
      <CartDrawerProvider>
        <Routes>
          <Route path="/" element={<CatalogPage />} />
          <Route path="/products/:id" element={<ProductDetailPage />} />
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
      </CartDrawerProvider>
    </BrowserRouter>
  )
}
