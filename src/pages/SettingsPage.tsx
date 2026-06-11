export default function SettingsPage() {
  const permissions = [
    { name: '无障碍', ok: true },
    { name: '通知', ok: true },
    { name: '悬浮窗', ok: true },
    { name: '电池', ok: true },
    { name: '存储', ok: true },
    { name: 'Shizuku', ok: false },
  ]

  const channels = [
    { name: '钉钉', icon: '💬', connected: true },
    { name: '飞书', icon: '🐦', connected: false },
    { name: 'QQ', icon: '🐧', connected: false },
    { name: 'Discord', icon: '🎮', connected: true },
    { name: 'Telegram', icon: '✈️', connected: true },
    { name: '微信', icon: '💚', connected: false },
  ]

  return (
    <div>
      <div className="page-header">
        <div className="page-title">⚙ 设置</div>
      </div>

      <div className="settings-section">
        {/* 权限状态 */}
        <div className="settings-card">
          <div className="settings-card-title">权限状态</div>
          <div className="perm-grid">
            {permissions.map(p => (
              <div className="perm-item" key={p.name}>
                <div className={`perm-check ${p.ok ? 'ok' : 'no'}`}>
                  {p.ok ? '✓' : '✗'}
                </div>
                <span>{p.name}</span>
              </div>
            ))}
          </div>
        </div>

        {/* LLM 配置 */}
        <div className="settings-card">
          <div className="settings-card-title">模型配置</div>
          <div className="llm-info">
            <span className="llm-model">gpt-4o</span>
            <span className="llm-provider">· OpenAI</span>
          </div>
          <div style={{ marginTop: '8px', fontSize: '11px', color: 'var(--text-muted)' }}>
            Base URL: https://api.openai.com/v1
          </div>
          <div style={{ marginTop: '4px', fontSize: '11px', color: 'var(--text-muted)' }}>
            API Key: sk-••••••••••••3f7a
          </div>
          <div style={{ marginTop: '8px', display: 'flex', gap: '6px' }}>
            <span className="tag tag-purple">VLM ✓</span>
            <span className="tag tag-green">本地降级 ✓</span>
          </div>
        </div>

        {/* 渠道 */}
        <div className="settings-card">
          <div className="settings-card-title">消息渠道</div>
          <div className="channel-grid">
            {channels.map(ch => (
              <div className={`channel-item ${ch.connected ? 'connected' : ''}`} key={ch.name}>
                <div className="channel-icon">{ch.icon}</div>
                <span>{ch.name}</span>
                <span className="channel-status" style={{ color: ch.connected ? 'var(--success)' : 'var(--text-muted)' }}>
                  {ch.connected ? '已连接' : '未配置'}
                </span>
              </div>
            ))}
          </div>
        </div>

        {/* 其他设置 */}
        <div className="settings-card">
          <div className="settings-card-title">其他</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '13px' }}>
              <span>局域网配置</span>
              <span style={{ color: 'var(--text-muted)', fontSize: '11px' }}>192.168.1.105:9527</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '13px' }}>
              <span>设备管理</span>
              <span style={{ color: 'var(--text-muted)', fontSize: '11px' }}>2 台设备</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '13px' }}>
              <span>浏览器引擎</span>
              <span style={{ color: 'var(--text-muted)', fontSize: '11px' }}>GeckoView 151</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '13px' }}>
              <span>投屏控制</span>
              <span style={{ color: 'var(--text-muted)', fontSize: '11px' }}>未连接</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '13px' }}>
              <span>插件管理</span>
              <span style={{ color: 'var(--text-muted)', fontSize: '11px' }}>3 个已加载</span>
            </div>
          </div>
        </div>

        {/* 版本 */}
        <div style={{ textAlign: 'center', fontSize: '11px', color: 'var(--text-muted)', padding: '8px 0' }}>
          Octopus Mobile v0.0.2 · Apache 2.0
        </div>
      </div>
    </div>
  )
}
