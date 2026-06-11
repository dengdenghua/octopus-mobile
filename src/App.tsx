import { useState } from 'react'
import ChatPage from '@/pages/ChatPage'
import DiscoverPage from '@/pages/DiscoverPage'
import DevicePage from '@/pages/DevicePage'
import SettingsPage from '@/pages/SettingsPage'

type Tab = 'chat' | 'discover' | 'device' | 'settings'

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('discover')
  const [floatExpanded, setFloatExpanded] = useState(false)

  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh' }}>
      <div className="phone-frame">
        {/* 刘海 */}
        <div className="phone-notch" />

        {/* 状态栏 */}
        <div className="status-bar">
          <span>9:41</span>
          <span style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
            <span style={{ fontSize: '10px' }}>5G</span>
            <span style={{ fontSize: '10px' }}>🔋</span>
          </span>
        </div>

        {/* 页面内容 */}
        <div className="page-content page-enter" key={activeTab}>
          {activeTab === 'chat' && <ChatPage />}
          {activeTab === 'discover' && <DiscoverPage />}
          {activeTab === 'device' && <DevicePage />}
          {activeTab === 'settings' && <SettingsPage />}
        </div>

        {/* 对话页输入框 */}
        {activeTab === 'chat' && (
          <div className="chat-input-area">
            <input className="chat-input" placeholder="输入指令..." />
            <button className="send-btn">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z" />
              </svg>
            </button>
          </div>
        )}

        {/* 悬浮球 */}
        {activeTab !== 'chat' && (
          floatExpanded ? (
            <div className="floating-ball-expanded" onClick={() => setFloatExpanded(false)}>
              <div className="float-header">
                <span style={{ fontSize: '16px' }}>🤖</span>
                <span className="float-task-name">打开微信发消息</span>
                <span className="tag tag-purple">轮次 3</span>
              </div>
              <div className="float-step">
                <span>👆</span>
                <span className="step-tool">tap</span>
                <span style={{ color: 'var(--text-muted)' }}>(540, 960)</span>
                <span className="step-result" style={{ color: 'var(--success)' }}>✓</span>
              </div>
              <div className="float-step">
                <span>⌨️</span>
                <span className="step-tool">input_text</span>
                <span style={{ color: 'var(--text-muted)' }}>("你好")</span>
                <span className="step-result" style={{ color: 'var(--success)' }}>✓</span>
              </div>
              <div className="float-step">
                <span>🔄</span>
                <span className="step-tool">get_screen_info</span>
                <span style={{ color: 'var(--text-muted)' }}>...</span>
                <span className="thinking-dots"><span /><span /><span /></span>
              </div>
              <div className="float-actions">
                <button className="float-btn pause">⏸ 暂停</button>
                <button className="float-btn cancel">⏹ 取消</button>
                <button className="float-btn chat">💬 对话</button>
              </div>
            </div>
          ) : (
            <div className="floating-ball" onClick={() => setFloatExpanded(true)}>
              <div style={{
                width: '48px', height: '48px', borderRadius: '50%',
                background: 'linear-gradient(135deg, var(--accent), #8b7cf7)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: '20px', boxShadow: '0 0 20px var(--accent-glow)',
                animation: 'pulse 2s infinite'
              }}>
                🤖
              </div>
            </div>
          )
        )}

        {/* 底部导航 - 4 Tab */}
        <div className="bottom-nav">
          <div className={`nav-item ${activeTab === 'chat' ? 'active' : ''}`} onClick={() => setActiveTab('chat')}>
            <div className="nav-icon">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
              </svg>
            </div>
            <span>对话</span>
          </div>
          <div className={`nav-item ${activeTab === 'discover' ? 'active' : ''}`} onClick={() => setActiveTab('discover')}>
            <div className="nav-icon">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <circle cx="11" cy="11" r="8" />
                <path d="M21 21l-4.35-4.35" />
              </svg>
            </div>
            <span>发现</span>
          </div>
          <div className={`nav-item ${activeTab === 'device' ? 'active' : ''}`} onClick={() => setActiveTab('device')}>
            <div className="nav-icon">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
                <path d="M8 21h8M12 17v4" />
              </svg>
            </div>
            <span>设备</span>
          </div>
          <div className={`nav-item ${activeTab === 'settings' ? 'active' : ''}`} onClick={() => setActiveTab('settings')}>
            <div className="nav-icon">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                <circle cx="12" cy="12" r="3" />
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
              </svg>
            </div>
            <span>设置</span>
          </div>
        </div>
      </div>
    </div>
  )
}
