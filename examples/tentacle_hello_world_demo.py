"""
Octopus Mobile · 5 分钟 demo 脚本 (Python 端).

这个脚本演示 octopus-agent 母体端的 hello_world 协议交互流程.
不需要 octopus-agent 源码依赖 —— 纯 stdlib.

跑法:
    python examples/tentacle_hello_world_demo.py

或者装好 octopus-agent 后:
    python examples/tentacle_hello_world_demo.py --real
"""

from __future__ import annotations

import asyncio
import json
import sys


# ── 模拟版（无依赖） ─────────────────────────────────────────

async def mock_demo():
    """模拟演示完整 5 分钟链路，不依赖 octopus-agent 源码."""

    print()
    print('┌─────────────────────────────────────────────┐')
    print('│  Octopus Mobile · 5 分钟 hello world demo    │')
    print('└─────────────────────────────────────────────┘')
    print()
    print('🚀 启动 5 分钟 demo...')
    print()

    # 步骤 1: 设备握手
    print('📱 模拟手机: device/hello')
    hello_msg = {
        'jsonrpc': '2.0',
        'id': 'hello-001',
        'method': 'device/hello',
        'params': {
            'tentacle_id': 'android-demo-phone',
            'client_type': 'android_tentacle',
            'client_version': '0.1.0',
            'device_meta': {
                'brand': 'Xiaomi',
                'model': 'Mi 14 Pro',
                'android_version': '14',
                'sdk': 34,
            },
            'capabilities': [
                'android.tap', 'android.swipe', 'android.input_text',
                'android.open_app', 'android.get_screen_info',
                'android.hello_world',  # 我们刚加的工具
                'android.current_time',  # 我们刚加的工具
                'android.device_info',   # 我们刚加的工具
            ],
        },
    }
    print(f'   → {json.dumps(hello_msg, ensure_ascii=False)[:120]}...')
    await asyncio.sleep(0.1)
    print('✅ 母体: hello received (capabilities = {})'.format(
        len(hello_msg['params']['capabilities']),
    ))
    print()

    # 步骤 2: 心跳
    print('💓 手机: device/heartbeat (30s 一次)')
    heartbeat_msg = {
        'jsonrpc': '2.0',
        'method': 'device/heartbeat',
        'params': {
            'current_app': 'com.android.settings',
            'battery': 78,
            'is_charging': False,
            'screen_tree_hash': 'a1b2c3d4e5f6',
        },
    }
    print(f'   → current_app=com.android.settings battery=78%')
    print('✅ 母体: heartbeat recorded')
    print()

    # 步骤 3: 母体下发工具调用
    print('🧠 母体: 下发 tool/execute')
    tool_call = {
        'jsonrpc': '2.0',
        'id': 'call-hello-001',
        'method': 'tool/execute',
        'params': {
            'id': 'call-hello-001',
            'tool': 'android.hello_world',
            'args': {'name': 'Octopus Agent'},
        },
    }
    print(f'   → tool={tool_call["params"]["tool"]} args={tool_call["params"]["args"]}')

    # 步骤 4: 手机执行 hello_world
    print()
    print('📱 手机: 执行 HelloWorldTool.execute()')
    name = tool_call['params']['args']['name']
    result_msg = {
        'jsonrpc': '2.0',
        'id': 'call-hello-001',
        'method': 'tool/result',
        'params': {
            'call_id': 'call-hello-001',
            'success': True,
            'data': f'Hello, {name}! 👋\n\n(You just called your first custom tool.)',
            'duration_ms': 12,
        },
    }
    print(f'   ← success=true data="{result_msg["params"]["data"][:60]}..."')
    print()

    # 步骤 5: 母体收到结果
    print('🧠 母体: 收到结果')
    print(f'   ✅ success={result_msg["params"]["success"]}')
    print(f'   📝 data: {result_msg["params"]["data"]}')
    print()

    # 步骤 6: 屏幕变化
    print('📺 手机: device/screen_changed (5s 节流)')
    screen_msg = {
        'jsonrpc': '2.0',
        'method': 'device/screen_changed',
        'params': {
            'current_app': 'com.android.settings',
            'screen_hash': 'b2c3d4e5f6a7',
            'tree_delta': {
                'added': [{'tree': '<node text="设置" bounds="[0,0][1080,200]"/>'}],
            },
        },
    }
    print(f'   → app=com.android.settings hash={screen_msg["params"]["screen_hash"]}')
    print('✅ 母体: screen state cached')
    print()

    print('=' * 60)
    print('🎉 Demo 跑通！')
    print('=' * 60)
    print()
    print('刚才发生了什么：')
    print('  1. 手机发 hello（30 capabilities）')
    print('  2. 手机发 heartbeat（78% 电量）')
    print('  3. 母体下发 hello_world 工具调用')
    print('  4. 手机执行 hello_world 工具')
    print('  5. 手机回传结果')
    print('  6. 母体更新屏幕状态')
    print()
    print('下一步：')
    print('  - 跑真实链路: python -m runtime tentacle serve --port 8765')
    print('  - 加新工具: 参考 octopus-mobile/EXTENDING.md')
    print('  - 改提示词: 编辑 settings/system_prompt.txt')


# ── 真实版（需 octopus-agent dev install） ─────────────────────

async def real_demo():
    """
    跑真实链路. 需要:
      pip install -e ../octopus-agent
      有至少一个 LLM key (OPENAI_API_KEY 或 ANTHROPIC_API_KEY)
    """
    try:
        from runtime.tentacle import TentaclePool, MobileDevice
        from runtime.tentacle.transport.ws_server import serve
        from runtime.protocol.envelope import JsonRpcRequest
        import time
    except ImportError as e:
        print(f'❌ octopus-agent 未安装: {e}')
        print()
        print('安装方法:')
        print('  cd ../octopus-agent && pip install -e ".[dev,serve,anthropic]"')
        print('  cp .env.example .env  # 填入 API key')
        return

    print('🚀 启动真实 tentacle demo...')
    pool = TentaclePool()

    # 1. 启动 WS server
    server = await serve(host='0.0.0.0', port=8765, pool=pool)
    print('📡 WS server listening on :8765')

    # 2. 等连接
    print('⏳ 等待 Octopus Mobile 连接（120s 超时）...')
    try:
        tentacle = await pool.wait_for_online(timeout=120)
    except TimeoutError:
        print('❌ 超时未连接')
        return
    print(f'✅ 触手已连接: {tentacle.tentacle_id}')
    print(f'   设备: {tentacle.meta["brand"]} {tentacle.meta["model"]}')

    # 3. 下发 hello_world
    request = JsonRpcRequest(
        id=f'demo-{int(time.time())}',
        method='tool/execute',
        params={
            'id': 'call-hello-1',
            'tool': 'android.hello_world',
            'args': {'name': 'Octopus Agent'},
        },
    )
    await tentacle.send(request)
    print(f'📤 下发: hello_world(name="Octopus Agent")')

    # 4. 等结果
    result = await tentacle.wait_for_result('call-hello-1', timeout=30)
    print(f'📥 结果: {result}')

    # 5. 拆掉
    await tentacle.disconnect()
    server.close()
    print('🎉 Done')


if __name__ == '__main__':
    if '--real' in sys.argv:
        asyncio.run(real_demo())
    else:
        asyncio.run(mock_demo())
