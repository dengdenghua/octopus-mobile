import { useState } from 'react'

export default function BrainPage() {
  const [mode, setMode] = useState<'remote' | 'local'>('remote')
  const [proactiveRules, setProactiveRules] = useState([
    { id: 1, name: '验证码短信自动复制', on: true },
    { id: 2, name: '电量低提醒', on: true },
    { id: 3, name: 'App 弹窗自动关闭', on: false },
  ])

  return (
    <div>
      <div className="page-header">
        <div className="page-title">🧠 大脑</div>
        <div className="status-indicator">
          <div className="status-dot busy" />
          <span>执行中</span>
        </div>
      </div>

      <div className="brain-section">
        {/* 模式切换 */}
        <div className="brain-card">
          <div className="brain-card-title">
            <span className="icon">⚡</span> 决策模式
          </div>
          <div className="mode-toggle">
            <button className={`mode-btn ${mode === 'remote' ? 'active' : ''}`} onClick={() => setMode('remote')}>
              🌐 远程母体
            </button>
            <button className={`mode-btn ${mode === 'local' ? 'active' : ''}`} onClick={() => setMode('local')}>
              📱 本地 LLM
            </button>
          </div>
          <div style={{ marginTop: '10px', fontSize: '11px', color: 'var(--text-muted)' }}>
            {mode === 'remote'
              ? '当前通过远程母体 Runtime 决策，30s 健康检查'
              : '当前通过本地 LLM 决策，断网可用'}
          </div>
        </div>

        {/* 任务队列 */}
        <div className="brain-card">
          <div className="brain-card-title">
            <span className="icon">📋</span> 任务队列
          </div>
          <div className="task-item">
            <div className="task-status-icon task-running">▶</div>
            <span className="task-name">打开微信发消息</span>
            <span className="tag tag-purple">HIGH</span>
          </div>
          <div className="task-item">
            <div className="task-status-icon task-queued">⏳</div>
            <span className="task-name">查明天天气</span>
            <span className="task-priority">NOR</span>
          </div>
          <div className="task-item">
            <div className="task-status-icon task-paused">⏸</div>
            <span className="task-name">下载文件报告</span>
            <span className="task-priority">LOW</span>
          </div>
        </div>

        {/* 记忆 */}
        <div className="brain-card">
          <div className="brain-card-title">
            <span className="icon">💾</span> 跨会话记忆
            <span style={{ marginLeft: 'auto', fontSize: '11px', color: 'var(--text-muted)' }}>
              偏好 3 · 事实 2 · 上下文 1
            </span>
          </div>
          <div className="memory-item">
            <div className="memory-dot pref" />
            <span>用饿了么不用美团</span>
            <span className="tag tag-purple" style={{ marginLeft: 'auto' }}>偏好</span>
          </div>
          <div className="memory-item">
            <div className="memory-dot pref" />
            <span>坐地铁不打车</span>
            <span className="tag tag-purple" style={{ marginLeft: 'auto' }}>偏好</span>
          </div>
          <div className="memory-item">
            <div className="memory-dot pref" />
            <span>习惯用搜狗输入法</span>
            <span className="tag tag-purple" style={{ marginLeft: 'auto' }}>偏好</span>
          </div>
          <div className="memory-item">
            <div className="memory-dot fact" />
            <span>我叫小明</span>
            <span className="tag tag-green" style={{ marginLeft: 'auto' }}>事实</span>
          </div>
          <div className="memory-item">
            <div className="memory-dot fact" />
            <span>公司在中关村</span>
            <span className="tag tag-green" style={{ marginLeft: 'auto' }}>事实</span>
          </div>
          <div className="memory-item">
            <div className="memory-dot ctx" />
            <span>刚才订的店是海底捞</span>
            <span className="tag tag-yellow" style={{ marginLeft: 'auto' }}>上下文</span>
          </div>
        </div>

        {/* 自进化 */}
        <div className="brain-card">
          <div className="brain-card-title">
            <span className="icon">🧬</span> 自进化
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '12px' }}>
            <span style={{ color: 'var(--text-secondary)' }}>健康度</span>
            <span style={{ color: 'var(--success)', fontWeight: 600 }}>82%</span>
          </div>
          <div className="health-bar">
            <div className="health-fill good" style={{ width: '82%' }} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--text-muted)', marginBottom: '12px' }}>
            <span>趋势：↑ improving</span>
            <span>B1 实时 · B2 反思 · B3 进化</span>
          </div>

          <div style={{ fontSize: '12px', color: 'var(--text-secondary)', marginBottom: '6px', fontWeight: 600 }}>
            教训 (5)
          </div>
          <div className="lesson-item">
            <span className="lesson-num">1.</span>
            <span>不要在搜索页点广告链接</span>
          </div>
          <div className="lesson-item">
            <span className="lesson-num">2.</span>
            <span>滚动查找优先于手动滑动</span>
          </div>
          <div className="lesson-item">
            <span className="lesson-num">3.</span>
            <span>微信搜索需要先点搜索框再输入</span>
          </div>
          <div className="lesson-item">
            <span className="lesson-num">4.</span>
            <span>系统弹窗应先截图用 VLM 分析</span>
          </div>
          <div className="lesson-item">
            <span className="lesson-num">5.</span>
            <span>美团外卖默认地址需确认</span>
          </div>
        </div>

        {/* 主动规则 */}
        <div className="brain-card">
          <div className="brain-card-title">
            <span className="icon">🔔</span> 主动规则
          </div>
          {proactiveRules.map(rule => (
            <div className="proactive-rule" key={rule.id}>
              <span>{rule.name}</span>
              <div
                className={`toggle-switch ${rule.on ? 'on' : ''}`}
                onClick={() => {
                  setProactiveRules(rules =>
                    rules.map(r => r.id === rule.id ? { ...r, on: !r.on } : r)
                  )
                }}
              />
            </div>
          ))}
          <div className="add-rule-btn">+ 添加规则</div>
        </div>

        {/* 视觉 */}
        <div className="brain-card">
          <div className="brain-card-title">
            <span className="icon">👁️</span> 视觉理解
            <div style={{ marginLeft: 'auto' }}>
              <div className="toggle-switch on" />
            </div>
          </div>
          <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginBottom: '8px' }}>
            VLM 多模态视觉分析 · 系统弹窗自动降级
          </div>
          <div className="screenshot-grid">
            <div className="screenshot-thumb">📱</div>
            <div className="screenshot-thumb">📱</div>
            <div className="screenshot-thumb">📱</div>
          </div>
        </div>

        {/* 浏览器引擎 */}
        <div className="brain-card">
          <div className="brain-card-title">
            <span className="icon">🌐</span> 浏览器引擎
            <span className="tag tag-green" style={{ marginLeft: 'auto' }}>GeckoView 151</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '12px', marginBottom: '6px' }}>
            <span style={{ color: 'var(--text-secondary)' }}>活跃标签页</span>
            <span style={{ fontWeight: 600 }}>3</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '12px', marginBottom: '6px' }}>
            <span style={{ color: 'var(--text-secondary)' }}>扩展已加载</span>
            <span style={{ fontWeight: 600 }}>3</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '12px' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Agent 工具</span>
            <span style={{ fontWeight: 600 }}>8 个可用</span>
          </div>
          <div style={{ marginTop: '8px', fontSize: '10px', color: 'var(--text-muted)' }}>
            反爬免疫 · WebExtension 支持 · 详情在「设备」页
          </div>
        </div>

        {/* 底部留白 */}
        <div style={{ height: '20px' }} />
      </div>
    </div>
  )
}
