import React from 'react'

/**
 * The centred card the auth screens share.
 *
 * Extracted when the fourth screen needed it. LoginPage and RegisterPage
 * still inline their own markup because they carry extra chrome (the
 * buyer/seller picker, the footer link row) that would turn this into a
 * props grab-bag; this covers the four plain single-purpose screens.
 */
export function AuthShell({ title, children }: {
  title: string
  children: React.ReactNode
}) {
  return (
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center',
      justifyContent: 'center', background: 'var(--paper)', padding: 24,
    }}>
      <div style={{
        background: 'var(--card)', borderRadius: 'var(--r)', padding: '40px 36px',
        width: '100%', maxWidth: 440, boxShadow: 'var(--shadow)',
      }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{
            fontFamily: 'var(--display)', fontWeight: 800, fontSize: 28,
            letterSpacing: '-0.03em', marginBottom: 6,
            background: 'var(--flame-gradient)', WebkitBackgroundClip: 'text',
            WebkitTextFillColor: 'transparent', backgroundClip: 'text',
            display: 'inline-block',
          }}>
            eRestyu
          </div>
          <p style={{
            fontFamily: 'var(--display)', fontWeight: 700, fontSize: 18,
            color: 'var(--ink)',
          }}>
            {title}
          </p>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {children}
        </div>
      </div>
    </div>
  )
}
