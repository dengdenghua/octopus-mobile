import { useState } from 'react'

export default function DiscoverPage() {
  const [searchText, setSearchText] = useState('')

  const shortcuts = [
    { icon: '🌐', name: '浏览器', color: 'rgba(108, 92, 231, 0.15)' },
    { icon: '☁️', name: '网盘', color: 'rgba(0, 210, 160, 0.15)' },
    { icon: '🎬', name: '视频', color: 'rgba(255, 92, 114, 0.15)' },
    { icon: '🧩', name: '插件', color: 'rgba(255, 192, 72, 0.15)' },
    { icon: '🖥', name: '投屏', color: 'rgba(108, 92, 231, 0.15)' },
    { icon: '📱', name: '多窗口', color: 'rgba(0, 210, 160, 0.15)' },
    { icon: '💾', name: '记忆', color: 'rgba(255, 192, 72, 0.15)' },
    { icon: '🧬', name: '自进化', color: 'rgba(255, 92, 114, 0.15)' },
  ]

  return (
    <div style={{
      height: '100%',
      display: 'flex',
      flexDirection: 'column',
      alignItems: 'center',
      paddingTop: '60px',
      paddingX: '24px',
    }}>
      {/* Logo */}
      <div style={{
        fontSize: '36px',
        marginBottom: '6px',
        filter: 'drop-shadow(0 0 20px rgba(108, 92, 231, 0.3))',
      }}>
        🐙
      </div>
      <div style={{
        fontSize: '18px',
        fontWeight: 700,
        letterSpacing: '-0.5px',
        marginBottom: '24px',
        background: 'linear-gradient(135deg, var(--accent), #8b7cf7)',
        WebkitBackgroundClip: 'text',
        WebkitTextFillColor: 'transparent',
      }}>
        Octopus
      </div>

      {/* 搜索栏 - 居中大号 */}
      <div style={{
        width: '100%',
        maxWidth: '340px',
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: '20px',
        padding: '14px 18px',
        display: 'flex',
        alignItems: 'center',
        gap: '12px',
        transition: 'all 0.3s',
        boxShadow: '0 0 0 0 transparent',
      }}
        onFocusCapture={e => {
          const el = e.currentTarget
          el.style.borderColor = 'var(--accent)'
          el.style.boxShadow = '0 0 0 3px var(--accent-glow)'
        }}
        onBlurCapture={e => {
          const el = e.currentTarget
          el.style.borderColor = 'var(--border)'
          el.style.boxShadow = '0 0 0 0 transparent'
        }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="2">
          <circle cx="11" cy="11" r="8" />
          <path d="M21 21l-4.35-4.35" />
        </svg>
        <input
          value={searchText}
          onChange={e => setSearchText(e.target.value)}
          placeholder="搜索或输入指令..."
          style={{
            flex: 1, background: 'transparent', border: 'none', outline: 'none',
            color: 'var(--text-primary)', fontSize: '15px', fontFamily: 'inherit',
          }}
        />
        <div style={{
          width: '34px', height: '34px', borderRadius: '50%', background: 'var(--accent)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
          boxShadow: '0 0 16px var(--accent-glow)',
          transition: 'transform 0.2s',
        }}
          onMouseEnter={e => (e.currentTarget.style.transform = 'scale(1.1)')}
          onMouseLeave={e => (e.currentTarget.style.transform = 'scale(1)')}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.5">
            <path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z" />
          </svg>
        </div>
      </div>

      {/* 快捷入口 - 类似浏览器 speed dial */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(4, 1fr)',
        gap: '16px',
        marginTop: '32px',
        width: '100%',
        maxWidth: '340px',
      }}>
        {shortcuts.map(s => (
          <div key={s.name} style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: '6px',
            cursor: 'pointer',
          }}>
            <div style={{
              width: '48px',
              height: '48px',
              borderRadius: '14px',
              background: s.color,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: '22px',
              transition: 'transform 0.2s',
            }}
              onMouseEnter={e => (e.currentTarget.style.transform = 'scale(1.08)')}
              onMouseLeave={e => (e.currentTarget.style.transform = 'scale(1)')}
            >
              {s.icon}
            </div>
            <span style={{ fontSize: '11px', color: 'var(--text-secondary)', fontWeight: 500 }}>{s.name}</span>
          </div>
        ))}
      </div>

      {/* AI 建议 - 底部小字 */}
      <div style={{
        marginTop: '28px',
        display: 'flex',
        gap: '8px',
        width: '100%',
        maxWidth: '340px',
      }}>
        {[
          { text: '查天气', icon: '🌤' },
          { text: '整理网盘', icon: '📦' },
          { text: '继续看剧', icon: '▶️' },
        ].map((s, i) => (
          <div key={i} style={{
            flex: 1,
            padding: '8px 10px',
            borderRadius: '10px',
            background: 'rgba(108, 92, 231, 0.06)',
            border: '1px solid rgba(108, 92, 231, 0.1)',
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
            fontSize: '12px',
            color: 'var(--text-secondary)',
            cursor: 'pointer',
            transition: 'all 0.2s',
          }}
            onMouseEnter={e => {
              e.currentTarget.style.background = 'rgba(108, 92, 231, 0.12)'
              e.currentTarget.style.borderColor = 'rgba(108, 92, 231, 0.25)'
            }}
            onMouseLeave={e => {
              e.currentTarget.style.background = 'rgba(108, 92, 231, 0.06)'
              e.currentTarget.style.borderColor = 'rgba(108, 92, 231, 0.1)'
            }}
          >
            <span style={{ fontSize: '13px' }}>{s.icon}</span>
            <span>{s.text}</span>
          </div>
        ))}
      </div>

      {/* 底部状态条 - 极简 */}
      <div style={{
        position: 'absolute',
        bottom: '100px',
        left: '24px',
        right: '24px',
        display: 'flex',
        justifyContent: 'center',
        gap: '16px',
        fontSize: '10px',
        color: 'var(--text-muted)',
      }}>
        <span>🧬 82%</span>
        <span>💾 5 条记忆</span>
        <span>🔔 2 条规则</span>
      </div>
    </div>
  )
}
