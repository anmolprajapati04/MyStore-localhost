import React, { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import axios from 'axios'
import { useAuth } from '../context/AuthContext'
import { useCart } from '../context/CartContext'
import { useToast } from './Toast'
import { formatINR } from '../utils/currencyUtils'
import { FALLBACK_IMAGE_URI, handleImageError } from '../utils/imageUtils'

const QUICK_PROMPTS = [
  'Items for kids',
  'Compare Sony TVs',
  'Gifts under 2000',
  'How do returns work?',
]

const welcomeMessage = () => ({
  role: 'bot',
  reply: "Hi, I'm Mira. Ask me the way you would in a store — “items for kids”, “compare Sony TVs”, or “gifts under 2000”. I only recommend products from this catalog.",
  suggestions: QUICK_PROMPTS,
})

function ownerKey(user) {
  return user?.username ? `user:${user.username}` : 'guest'
}

function chatStorageKey(owner) {
  return `mystoreAssistantChat:${owner}`
}

function sessionStorageKey(owner) {
  return `mystoreAssistantSession:${owner}`
}

function loadMessages(owner) {
  try {
    const raw = localStorage.getItem(chatStorageKey(owner))
    if (!raw) return [welcomeMessage()]
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) && parsed.length ? parsed : [welcomeMessage()]
  } catch {
    return [welcomeMessage()]
  }
}

function saveMessages(owner, messages) {
  try {
    localStorage.setItem(chatStorageKey(owner), JSON.stringify(messages))
  } catch {
    // ignore quota / private mode
  }
}

function assistantSessionId(owner) {
  const key = sessionStorageKey(owner)
  const existing = sessionStorage.getItem(key)
  if (existing) return existing
  const created = `${owner}:${crypto.randomUUID()}`
  sessionStorage.setItem(key, created)
  return created
}

function BotMessage({ text }) {
  const parts = text.split(/(\*\*[^*]+\*\*)/g)
  return (
    <p className="mira-text">
      {parts.map((part, index) =>
        part.startsWith('**') && part.endsWith('**')
          ? <strong key={index}>{part.slice(2, -2)}</strong>
          : <span key={index}>{part}</span>
      )}
    </p>
  )
}

export default function ShoppingAssistant() {
  const navigate = useNavigate()
  const { user } = useAuth()
  const { addToCart } = useCart()
  const toast = useToast()
  const owner = useMemo(() => ownerKey(user), [user?.username])
  const [open, setOpen] = useState(false)
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [messages, setMessages] = useState(() => loadMessages(owner))
  const listRef = useRef(null)

  useEffect(() => {
    sessionStorage.removeItem('mystoreAssistantSession')
    setMessages(loadMessages(owner))
    setInput('')
    setBusy(false)
  }, [owner])

  useEffect(() => {
    saveMessages(owner, messages)
  }, [owner, messages])

  useEffect(() => {
    if (listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight
    }
  }, [messages, open, busy])

  const ask = async (text) => {
    const message = (text || '').trim()
    if (!message || busy) return

    setInput('')
    setMessages(prev => [...prev, { role: 'user', reply: message }])
    setBusy(true)
    try {
      const { data } = await axios.post('/api/assistant/chat', {
        sessionId: assistantSessionId(owner),
        userId: user?.username || null,
        message,
      })
      setMessages(prev => [...prev, { role: 'bot', ...data }])
    } catch {
      setMessages(prev => [...prev, {
        role: 'bot',
        reply: 'I could not reach the shopping assistant just now. Please try again in a moment.',
        suggestions: QUICK_PROMPTS,
      }])
    } finally {
      setBusy(false)
    }
  }

  const addProduct = (product) => {
    addToCart({
      id: product.id,
      name: product.name,
      price: Number(product.price),
      imagePath: product.imagePath,
      category: product.category,
    })
    toast.success(`${product.name} added to cart`)
  }

  return (
    <div className={`mira${open ? ' mira--open' : ''}`}>
      {open && (
        <section className="mira-panel" aria-label="Shopping assistant">
          <header className="mira-header">
            <div className="mira-avatar" aria-hidden="true">M</div>
            <div>
              <div className="mira-title">Mira</div>
              <div className="mira-subtitle">
                {user?.username ? `Private chat · ${user.username}` : 'Shopping assistant'}
              </div>
            </div>
            <button className="mira-close" onClick={() => setOpen(false)} aria-label="Close assistant">×</button>
          </header>

          <div className="mira-thread" ref={listRef}>
            {messages.map((msg, index) => (
              <div key={`${owner}-${index}`} className={`mira-bubble mira-bubble--${msg.role}`}>
                <BotMessage text={msg.reply} />

                {msg.role === 'bot' && msg.comparisonRows?.length > 0 && msg.products?.length > 1 && (
                  <div className="mira-table-wrap">
                    <table className="mira-table">
                      <thead>
                        <tr>
                          <th>Feature</th>
                          {msg.products.map(product => (
                            <th key={product.id}>{product.name}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {msg.comparisonRows.filter(row => row.Feature !== 'Product').map((row, rowIndex) => (
                          <tr key={rowIndex}>
                            <td>{row.Feature}</td>
                            {msg.products.map((_, col) => (
                              <td key={col}>{row[`item${col + 1}`]}</td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                {msg.role === 'bot' && msg.products?.length > 0 && (
                  <div className="mira-products">
                    {msg.products.map(product => (
                      <article key={product.id} className="mira-product">
                        <img
                          src={product.imagePath || FALLBACK_IMAGE_URI}
                          alt={product.name}
                          onError={handleImageError}
                        />
                        <div>
                          <h4>{product.name}</h4>
                          <p>{formatINR(product.price)}</p>
                          <div className="mira-product-actions">
                            <button type="button" onClick={() => addProduct(product)}>Add to cart</button>
                            <button type="button" className="mira-linkish" onClick={() => { setOpen(false); navigate('/products') }}>View catalog</button>
                          </div>
                        </div>
                      </article>
                    ))}
                  </div>
                )}

                {msg.role === 'bot' && msg.suggestions?.length > 0 && (
                  <div className="mira-chips">
                    {msg.suggestions.map(suggestion => (
                      <button key={suggestion} type="button" onClick={() => ask(suggestion)}>{suggestion}</button>
                    ))}
                  </div>
                )}
              </div>
            ))}
            {busy && <div className="mira-bubble mira-bubble--bot mira-typing">Mira is thinking…</div>}
          </div>

          <form className="mira-composer" onSubmit={(event) => { event.preventDefault(); ask(input) }}>
            <input
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="Compare products, ask for deals, or get help…"
              aria-label="Message Mira"
            />
            <button type="submit" disabled={busy || !input.trim()}>Send</button>
          </form>
        </section>
      )}

      <button
        className="mira-fab"
        onClick={() => setOpen(value => !value)}
        aria-label={open ? 'Close shopping assistant' : 'Open shopping assistant'}
      >
        {open ? '×' : '💬'}
        {!open && <span>Shop with Mira</span>}
      </button>
    </div>
  )
}
