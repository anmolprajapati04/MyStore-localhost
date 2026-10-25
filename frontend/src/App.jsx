import React, { Suspense, lazy } from 'react'
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom'
import Header from './components/Header'
import ProtectedRoute from './components/ProtectedRoute'
import { AuthProvider } from './context/AuthContext'
import { CartProvider } from './context/CartContext'
import { ToastProvider } from './components/Toast'
import { ErrorBoundary } from './components/ErrorBoundary'
import ShoppingAssistant from './components/ShoppingAssistant'
import './index.css'

// Code splitting — each page loads on demand
const Home     = lazy(() => import('./pages/Home'))
const Login    = lazy(() => import('./pages/Login'))
const Register = lazy(() => import('./pages/Register'))
const Products = lazy(() => import('./pages/Products'))
const Cart     = lazy(() => import('./pages/Cart'))
const Checkout = lazy(() => import('./pages/Checkout'))
const Orders   = lazy(() => import('./pages/Orders'))
const Admin    = lazy(() => import('./pages/Admin'))
const PaymentSuccess = lazy(() => import('./pages/PaymentSuccess'))
const PaymentFailure = lazy(() => import('./pages/PaymentFailure'))
const Wishlist = lazy(() => import('./pages/Wishlist'))


function PageLoader() {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      minHeight: 'calc(100vh - 72px)', paddingTop: '72px'
    }}>
      <div style={{ textAlign: 'center' }}>
        <div style={{
          width: 40, height: 40, border: '3px solid var(--border)',
          borderTop: '3px solid var(--primary)', borderRadius: '50%',
          animation: 'spin 0.8s linear infinite', margin: '0 auto 1rem'
        }} />
        <p style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>Loading…</p>
      </div>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  )
}

function App() {
  return (
    <ErrorBoundary>
      <AuthProvider>
        <CartProvider>
          <ToastProvider>
            <Router>
              <div className="App">
                <Header />
                <main>
                  <Suspense fallback={<PageLoader />}>
                    <Routes>
                      <Route path="/"         element={<Home />} />
                      <Route path="/login"    element={<Login />} />
                      <Route path="/register" element={<Register />} />
                      <Route path="/products" element={<Products />} />
                      <Route path="/cart"     element={<Cart />} />
                      <Route path="/checkout" element={<ProtectedRoute><Checkout /></ProtectedRoute>} />
                      <Route path="/orders"   element={<ProtectedRoute><Orders /></ProtectedRoute>} />
                      <Route path="/payment-success" element={<ProtectedRoute><PaymentSuccess /></ProtectedRoute>} />
                      <Route path="/payment-failure" element={<ProtectedRoute><PaymentFailure /></ProtectedRoute>} />
                      <Route path="/wishlist" element={<ProtectedRoute><Wishlist /></ProtectedRoute>} />
                      <Route path="/admin"    element={
                        <ProtectedRoute roles={['ROLE_ADMIN', 'ROLE_SUPER_ADMIN']}>
                          <Admin />
                        </ProtectedRoute>
                      } />
                    </Routes>
                  </Suspense>
                </main>
                <ShoppingAssistant />
              </div>
            </Router>
          </ToastProvider>
        </CartProvider>
      </AuthProvider>
    </ErrorBoundary>
  )
}

export default App
