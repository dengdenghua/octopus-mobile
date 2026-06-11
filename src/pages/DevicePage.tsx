import { useState } from 'react'

export default function DevicePage() {
  const [selectedDevice, setSelectedDevice] = useState<string | null>('local')
  const [browserTab, setBrowserTab] = useState<'tabs' | 'extensions'>('tabs')

  const devices = [
    { id: 'local', name: '我的手机', type: 'phone', model: 'Xiaomi 14', status: 'online', battery: 72, currentApp: '微信', ip: '192.168.1.105', isLocal: true },
    { id: 'tv1', name: '客厅电视', type: 'tv', model: 'Mi TV Stick 4K', status: 'online', battery: -1, currentApp: 'YouTube', ip: '192.168.1.203', isLocal: false },
    { id: 'phone2', name: '备用机', type: 'phone', model: 'Pixel 7', status: 'offline', battery: 15, currentApp: '-', ip: '192.168.1.178', isLocal: false },
  ]

  const browserTabs = [
    { id: 1, title: '京东 - iPhone 16 Pro', url: 'jd.com/item/1000896', active: true },
    { id: 2, title: 'GitHub - octopus-mobile', url: 'github.com/octopus-mobile', active: false },
  ]

  const extensions = [
    { id: 1, name: 'AdBlock Plus', icon: '🛡️', enabled: true },
    { id: 2, name: 'Octopus Agent', icon: '🐙', enabled: true },
  ]

  const selected = devices.find(d => d.id === selectedDevice)

  return (
    <div>
      <div className="page-header">
        <div className="page-title">🖥 设备</div>
        <div className="status-indicator">
          <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
            {devices.filter(d => d.status === 'online').length}/{devices.length} 在线
          </span>
        </div>
      </div>

      <div style={{ padding: '0 16px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
        {/* 设备列表 - 紧凑横排 */}
        <div style={{ display: 'flex', gap: '8px' }}>
          {devices.map(device => (
            <div
              key={device.id}
              onClick={() => setSelectedDevice(device.id)}
              style={{
                flex: 1, padding: '10px', borderRadius: '12px', cursor: 'pointer',
                background: selectedDevice === device.id ? 'rgba(108, 92, 231, 0.1)' : 'var(--bg-card)',
                border: selectedDevice === device.id ? '1px solid rgba(108, 92, 231, 0.3)' : '1px solid var(--border)',
                transition: 'all 0.2s',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px' }}>
                <span style={{ fontSize: '16px' }}>{device.type === 'tv' ? '📺' : '📱'}</span>
                <span style={{ fontSize: '12px', fontWeight: 600 }}>{device.name}</span>
                {device.isLocal && <span className="tag tag-green" style={{ fontSize: '8px' }}>本机</span>}
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', color: 'var(--text-muted)' }}>
                <span>{device.model}</span>
                <span style={{ color: device.status === 'online' ? 'var(--success)' : 'var(--text-muted)' }}>
                  {device.status === 'online' ? '在线' : '离线'}
                </span>
              </div>
            </div>
          ))}
        </div>

        {/* 选中设备 - 远程控制 + 多窗口 合并 */}
        {selected && (
          <div style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: '14px', padding: '12px',
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
              <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)' }}>
                {selected.name} · {selected.currentApp}
              </span>
              {selected.battery >= 0 && <span style={{ fontSize: '10px', color: 'var(--text-muted)' }}>🔋 {selected.battery}%</span>}
            </div>
            {/* 远程控制 - 紧凑横排 */}
            <div style={{ display: 'flex', gap: '6px' }}>
              {[
                { icon: '📸', label: '截图' },
                { icon: '👆', label: '点击' },
                { icon: '📱', label: '打开' },
                { icon: '🏠', label: 'Home' },
                { icon: '⬅️', label: '返回' },
              ].map(action => (
                <div key={action.label} style={{
                  flex: 1, padding: '8px 4px', borderRadius: '8px',
                  background: 'var(--bg-primary)', textAlign: 'center',
                  cursor: 'pointer', transition: 'all 0.2s',
                  fontSize: '10px', color: 'var(--text-secondary)',
                }}
                  onMouseEnter={e => (e.currentTarget.style.background = 'var(--bg-card-hover)')}
                  onMouseLeave={e => (e.currentTarget.style.background = 'var(--bg-primary)')}
                >
                  <div style={{ fontSize: '16px', marginBottom: '2px' }}>{action.icon}</div>
                  {action.label}
                </div>
              ))}
            </div>
            {/* 多窗口 - 紧凑 */}
            <div style={{ marginTop: '10px', display: 'flex', gap: '6px' }}>
              {[
                { name: '微信', tag: 'freeform', size: '540×720' },
                { name: 'YouTube', tag: 'display:1', size: '1920×1080' },
              ].map((w, i) => (
                <div key={i} style={{
                  flex: 1, display: 'flex', alignItems: 'center', gap: '6px',
                  background: 'var(--bg-primary)', borderRadius: '8px', padding: '6px 8px', fontSize: '11px',
                }}>
                  <span>{i === 0 ? '📱' : '📺'}</span>
                  <span style={{ flex: 1, color: 'var(--text-primary)' }}>{w.name}</span>
                  <span className={`tag ${i === 0 ? 'tag-purple' : 'tag-yellow'}`} style={{ fontSize: '8px' }}>{w.tag}</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 浏览器引擎 - 紧凑 */}
        <div style={{
          background: 'var(--bg-card)', border: '1px solid var(--border)',
          borderRadius: '14px', padding: '12px',
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
            <span style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)' }}>🌐 浏览器</span>
            <span className="tag tag-green" style={{ fontSize: '8px' }}>GeckoView 151</span>
          </div>
          <div className="mode-toggle" style={{ marginBottom: '10px' }}>
            <button className={`mode-btn ${browserTab === 'tabs' ? 'active' : ''}`} onClick={() => setBrowserTab('tabs')} style={{ fontSize: '11px', padding: '6px' }}>
              标签页
            </button>
            <button className={`mode-btn ${browserTab === 'extensions' ? 'active' : ''}`} onClick={() => setBrowserTab('extensions')} style={{ fontSize: '11px', padding: '6px' }}>
              扩展
            </button>
          </div>
          {browserTab === 'tabs' ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              {browserTabs.map(tab => (
                <div key={tab.id} style={{
                  display: 'flex', alignItems: 'center', gap: '6px',
                  background: tab.active ? 'rgba(108, 92, 231, 0.08)' : 'var(--bg-primary)',
                  border: tab.active ? '1px solid rgba(108, 92, 231, 0.2)' : '1px solid transparent',
                  borderRadius: '8px', padding: '6px 8px', fontSize: '11px',
                }}>
                  <span>📄</span>
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--text-primary)' }}>{tab.title}</span>
                  {tab.active && <span className="tag tag-purple" style={{ fontSize: '8px' }}>活跃</span>}
                </div>
              ))}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              {extensions.map(ext => (
                <div key={ext.id} style={{
                  display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px',
                  padding: '4px 0',
                }}>
                  <span>{ext.icon}</span>
                  <span style={{ flex: 1 }}>{ext.name}</span>
                  <div className={`toggle-switch ${ext.enabled ? 'on' : ''}`} style={{ width: '30px', height: '16px' }} />
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 投屏 + 视觉理解 - 合并一行 */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
          <div style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: '14px', padding: '12px',
          }}>
            <div style={{ fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '8px' }}>
              🖥 投屏
            </div>
            <div style={{
              background: 'var(--bg-primary)', borderRadius: '8px', padding: '12px',
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '4px',
            }}>
              <span style={{ fontSize: '20px' }}>📺</span>
              <span style={{ fontSize: '10px', color: 'var(--text-muted)' }}>未连接</span>
            </div>
          </div>
          <div style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: '14px', padding: '12px',
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '8px' }}>
              <span>👁️ 视觉</span>
              <div className="toggle-switch on" style={{ width: '30px', height: '16px' }} />
            </div>
            <div style={{ fontSize: '10px', color: 'var(--text-muted)', lineHeight: '1.5' }}>
              VLM 多模态分析<br />
              系统弹窗自动降级
            </div>
          </div>
        </div>

        <div style={{ height: '10px' }} />
      </div>
    </div>
  )
}
