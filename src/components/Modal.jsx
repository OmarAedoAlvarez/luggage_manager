import React, { useEffect } from 'react'

const s = {
  overlay: {
    position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
    background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    zIndex: 2000,
  },
  panel: {
    width: 360,
    background: 'var(--panel)',
    border: '1px solid var(--border)',
    borderRadius: 12,
    boxShadow: '0 20px 40px rgba(0,0,0,0.4)',
    padding: '24px',
    display: 'flex', flexDirection: 'column', gap: 16,
    animation: 'modalIn 0.2s ease-out',
  },
  title: {
    fontFamily: 'var(--sans)', fontSize: 18, fontWeight: 700,
    color: 'var(--text-bright)', margin: 0,
  },
  body: {
    fontFamily: 'var(--sans)', fontSize: 14, color: 'var(--muted)',
    lineHeight: 1.5,
  },
  footer: {
    display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 8,
  },
  btn: (primary) => ({
    padding: '8px 16px', borderRadius: 6, cursor: 'pointer',
    fontFamily: 'var(--mono)', fontSize: 11, fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: 1,
    background: primary ? 'var(--red)' : 'transparent',
    color: primary ? 'white' : 'var(--muted)',
    border: primary ? 'none' : '1px solid var(--border)',
    transition: 'all 0.2s',
  }),
}

export default function Modal({ title, children, confirmLabel, onConfirm, onClose, isOpen }) {
  useEffect(() => {
    if (!isOpen) return
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [isOpen, onClose])

  if (!isOpen) return null

  return (
    <div style={s.overlay} onClick={onClose}>
      <style>{`
        @keyframes modalIn {
          from { opacity: 0; transform: scale(0.95); }
          to { opacity: 1; transform: scale(1); }
        }
      `}</style>
      <div style={s.panel} onClick={(e) => e.stopPropagation()}>
        <h3 style={s.title}>{title}</h3>
        <div style={s.body}>{children}</div>
        <div style={s.footer}>
          <button style={s.btn(false)} onClick={onClose}>Cancelar</button>
          <button 
            style={s.btn(true)} 
            onClick={() => {
              onConfirm()
              onClose()
            }}
          >
            {confirmLabel || 'Confirmar'}
          </button>
        </div>
      </div>
    </div>
  )
}
