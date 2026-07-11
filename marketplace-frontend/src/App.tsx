import React from 'react'
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { useAuth } from './context/AuthContext'
import { CartDrawerProvider } from './context/CartDrawerContext'
import { Footer } from './components/layout/Footer'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { CatalogPage } from './pages/CatalogPage'
import { ProductDetailPage } from './pages/ProductDetailPage'
import { CartPage } from './pages/CartPage'
import { CheckoutSuccessPage } from './pages/CheckoutSuccessPage'
import { CheckoutCancelledPage } from './pages/CheckoutCancelledPage'
import { OrdersPage } from './pages/OrdersPage'
import { OrderDetailPage } from './pages/OrderDetailPage'
import { VendorDashboardPage } from './pages/VendorDashboardPage'
import { AdminPage } from './pages/AdminPage'
import { PrivacyPolicyPage } from './pages/legal/PrivacyPolicyPage'
import { TermsPage } from './pages/legal/TermsPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return null
  if (!user) return <Navigate to="/login" replace />
  return <>{children}</>
}

// Rendered once after Routes so it lands below whatever page just rendered.
// Hidden on auth pages — a marketing footer is clutter on a login/register form.
function ChromeFooter() {
  const { pathname } = useLocation()
  if (pathname === '/login' || pathname === '/register') return null
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
          <Route path="/checkout/success" element={<CheckoutSuccessPage />} />
          <Route path="/checkout/cancelled" element={<CheckoutCancelledPage />} />
          <Route path="/cart" element={<RequireAuth><CartPage /></RequireAuth>} />
          <Route path="/orders" element={<RequireAuth><OrdersPage /></RequireAuth>} />
          <Route path="/orders/:id" element={<RequireAuth><OrderDetailPage /></RequireAuth>} />
          <Route path="/vendor" element={<RequireAuth><VendorDashboardPage /></RequireAuth>} />
          <Route path="/admin" element={<RequireAuth><AdminPage /></RequireAuth>} />
          <Route path="/privacy" element={<PrivacyPolicyPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
        <ChromeFooter />
      </CartDrawerProvider>
    </BrowserRouter>
  )
}
