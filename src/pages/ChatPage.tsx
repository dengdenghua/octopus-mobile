import { useState } from 'react'

export default function ChatPage() {
  const [mode, setMode] = useState<'remote' | 'local'>('remote')

  return (
    <div>
      <div className="page-header">
        <div className="page-title">Octopus</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          {/* 决策模式胶囊 */}
          <div
            onClick={() => setMode(m => m === 'remote' ? 'local' : 'remote')}
            style={{
              display: 'flex', alignItems: 'center', gap: '4px',
              padding: '3px 8px', borderRadius: '10px',
              background: mode === 'remote' ? 'rgba(108, 92, 231, 0.12)' : 'rgba(0, 210, 160, 0.12)',
              fontSize: '10px', fontWeight: 600, cursor: 'pointer',
              color: mode === 'remote' ? 'var(--accent)' : 'var(--success)',
            }}
          >
            {mode === 'remote' ? '🌐 远程' : '📱 本地'}
          </div>
          <div className="status-indicator">
            <div className="status-dot online" />
            <span>在线</span>
          </div>
        </div>
      </div>

      {/* 任务队列条 - 执行中时显示 */}
      <div style={{
        margin: '0 16px 8px', padding: '8px 12px', borderRadius: '12px',
        background: 'rgba(108, 92, 231, 0.08)', border: '1px solid rgba(108, 92, 231, 0.15)',
        display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px',
      }}>
        <div className="task-status-icon task-running" style={{ width: '18px', height: '18px', fontSize: '8px' }}>▶</div>
        <span style={{ flex: 1, color: 'var(--text-primary)' }}>打开微信发消息</span>
        <span className="tag tag-purple">轮次 3</span>
        <span style={{ color: 'var(--text-muted)', fontSize: '10px', cursor: 'pointer' }}>⏸</span>
        <span style={{ color: 'var(--error)', fontSize: '10px', cursor: 'pointer' }}>⏹</span>
      </div>
      {/* 排队提示 */}
      <div style={{
        margin: '0 16px 8px', padding: '6px 12px', borderRadius: '10px',
        background: 'rgba(255, 192, 72, 0.06)', border: '1px solid rgba(255, 192, 72, 0.1)',
        display: 'flex', alignItems: 'center', gap: '8px', fontSize: '11px', color: 'var(--text-muted)',
      }}>
        <span>⏳</span>
        <span>查明天天气 · 排队中</span>
      </div>

      <div className="chat-area">
        {/* 用户消息 */}
        <div className="msg-user">打开微信发消息给小明</div>

        {/* Agent 思考 */}
        <div className="msg-agent">
          <div className="msg-thinking">
            <span>🤔</span>
            <span>正在分析屏幕...</span>
            <div className="thinking-dots"><span /><span /><span /></div>
          </div>
        </div>

        {/* Agent 工具调用 */}
        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>📱</span>
            <span className="tool-name">get_screen_info</span>
            <span className="msg-tool-result">✓ 主屏幕</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>📱</span>
            <span className="tool-name">open_app</span>
            <span style={{ color: 'var(--text-muted)' }}>com.tencent.mm</span>
            <span className="msg-tool-result">✓</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>📱</span>
            <span className="tool-name">get_screen_info</span>
            <span className="msg-tool-result">✓ 微信首页</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>👆</span>
            <span className="tool-name">tap</span>
            <span style={{ color: 'var(--text-muted)' }}>(540, 380)</span>
            <span className="msg-tool-result">✓ 搜索</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>⌨️</span>
            <span className="tool-name">input_text</span>
            <span style={{ color: 'var(--text-muted)' }}>("小明")</span>
            <span className="msg-tool-result">✓</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>👆</span>
            <span className="tool-name">tap</span>
            <span style={{ color: 'var(--text-muted)' }}>(270, 280)</span>
            <span className="msg-tool-result">✓ 小明</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>⌨️</span>
            <span className="tool-name">input_text</span>
            <span style={{ color: 'var(--text-muted)' }}>("你好，今晚一起吃饭吗？")</span>
            <span className="msg-tool-result">✓</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>👆</span>
            <span className="tool-name">tap</span>
            <span style={{ color: 'var(--text-muted)' }}>(980, 1820)</span>
            <span className="msg-tool-result">✓ 发送</span>
          </div>
        </div>

        {/* Agent 回复 */}
        <div className="msg-agent">
          <div className="msg-agent-bubble">
            已打开微信并找到小明的对话，消息"你好，今晚一起吃饭吗？"已发送成功。
          </div>
        </div>

        {/* 第二轮对话 */}
        <div className="msg-user">帮我看看明天的天气</div>

        <div className="msg-agent">
          <div className="msg-thinking">
            <span>🤔</span>
            <span>正在查询天气...</span>
            <div className="thinking-dots"><span /><span /><span /></div>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>📱</span>
            <span className="tool-name">open_app</span>
            <span style={{ color: 'var(--text-muted)' }}>com.miui.weather</span>
            <span className="msg-tool-result">✓</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>📱</span>
            <span className="tool-name">get_screen_info</span>
            <span className="msg-tool-result">✓ 天气详情</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-agent-bubble">
            明天北京天气：晴转多云，最高 28°C，最低 16°C，空气质量良好。适合户外活动。
          </div>
        </div>

        {/* VLM 视觉理解示例 */}
        <div className="msg-user">这个弹窗是什么意思？</div>

        <div className="msg-agent">
          <div className="msg-tool-call">
            <span>📸</span>
            <span className="tool-name">take_screenshot</span>
            <span className="msg-tool-result">✓ +base64</span>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-thinking">
            <span>👁️</span>
            <span>VLM 视觉分析中...</span>
            <div className="thinking-dots"><span /><span /><span /></div>
          </div>
        </div>

        <div className="msg-agent">
          <div className="msg-agent-bubble">
            这是一个系统权限请求弹窗，询问是否允许"微信"访问你的位置信息。建议选择"仅在使用中允许"。
          </div>
        </div>

        {/* 底部留白 */}
        <div style={{ height: '80px' }} />
      </div>
    </div>
  )
}
