/* ============================================
   Octopus Mobile Debug Console - Application
   ============================================ */

(function () {
  'use strict';

  // --- State ---
  let tools = [];
  let selectedTool = null;
  let history = [];
  const MAX_HISTORY = 10;

  // --- Token (from URL fragment or query) ---
  const h = new URLSearchParams(location.hash.replace(/^#/, ''));
  const q = new URLSearchParams(location.search);
  const TOKEN = h.get('token') || q.get('token') || '';
  if (q.get('token')) {
    history.replaceState(null, '', location.pathname + (TOKEN ? '#token=' + encodeURIComponent(TOKEN) : ''));
  } else if (h.get('token')) {
    history.replaceState(null, '', location.pathname);
  }

  function authHeaders(extra) {
    return Object.assign({ 'Authorization': 'Bearer ' + TOKEN }, extra || {});
  }

  function streamUrl(p) {
    return p + (p.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(TOKEN);
  }

  // --- DOM References ---
  const $ = (id) => document.getElementById(id);

  // --- Tool Loading ---
  async function loadTools() {
    if (!TOKEN) {
      $('toolList').textContent = '缺少访问令牌，请使用 /debug.html?token=<token> 打开';
      return;
    }
    try {
      const res = await fetch('/api/debug/tools', { headers: authHeaders() });
      const json = await res.json();
      if (json.code === 0) {
        tools = json.data;
        renderToolList();
      }
    } catch (e) {
      $('toolList').textContent = 'Failed to load tools: ' + e.message;
    }
  }

  // --- Tool List Rendering ---
  const CATEGORY_LABELS = {
    screen: '屏幕',
    input: '输入',
    app: '应用',
    file: '文件',
    network: '网络',
    system: '系统',
    workflow: '工作流',
    other: '其他'
  };
  const CATEGORY_ORDER = ['screen', 'input', 'app', 'file', 'network', 'system', 'workflow', 'other'];

  function renderToolList() {
    const container = $('toolList');
    container.innerHTML = '';

    // Group tools by category (fallback to 'other')
    const groups = {};
    tools.forEach(function (t) {
      const cat = t.category || 'other';
      if (!groups[cat]) groups[cat] = [];
      groups[cat].push(t);
    });

    CATEGORY_ORDER.forEach(function (cat) {
      if (!groups[cat] || groups[cat].length === 0) return;

      const details = document.createElement('details');
      details.className = 'tool-group';
      details.open = true;
      details.dataset.category = cat;

      const summary = document.createElement('summary');
      summary.className = 'tool-group-title';
      summary.textContent = CATEGORY_LABELS[cat] || cat;
      details.appendChild(summary);

      const body = document.createElement('div');
      body.className = 'tool-group-body';

      groups[cat].forEach(function (t) {
        const btn = document.createElement('button');
        btn.className = 'tool-btn';
        btn.textContent = t.name;
        btn.title = t.description;
        btn.dataset.toolName = t.name;
        btn.onclick = function () { selectTool(t.name); };
        body.appendChild(btn);
      });

      details.appendChild(body);
      container.appendChild(details);
    });

    // Re-apply current filter
    filterTools($('searchInput').value);
  }

  // --- Tool Search / Filter ---
  function filterTools(query) {
    const q = (query || '').trim().toLowerCase();
    const container = $('toolList');
    const buttons = container.querySelectorAll('.tool-btn');
    buttons.forEach(function (btn) {
      const name = (btn.dataset.toolName || '').toLowerCase();
      if (!q || name.indexOf(q) !== -1) {
        btn.classList.remove('hidden');
      } else {
        btn.classList.add('hidden');
      }
    });
    // Auto-hide groups with no visible tools
    container.querySelectorAll('.tool-group').forEach(function (group) {
      const visible = group.querySelectorAll('.tool-btn:not(.hidden)').length;
      group.classList.toggle('hidden', visible === 0);
    });
  }

  // --- Tool Selection ---
  function selectTool(name) {
    selectedTool = tools.find(function (t) { return t.name === name; });
    if (!selectedTool) return;

    // Update active state
    document.querySelectorAll('.tool-btn').forEach(function (b) {
      b.classList.toggle('active', b.dataset.toolName === name);
    });

    // Show panel
    var panel = $('toolPanel');
    panel.style.display = '';
    $('toolName').textContent = selectedTool.displayName || selectedTool.name;
    $('toolDesc').textContent = selectedTool.description;

    // Render params
    var fields = $('paramFields');
    fields.innerHTML = '';
    if (!selectedTool.parameters || selectedTool.parameters.length === 0) {
      fields.innerHTML = '<div class="no-params">No parameters required</div>';
    } else {
      selectedTool.parameters.forEach(function (p) {
        var row = document.createElement('div');
        row.className = 'param-row';

        var paramLabel = document.createElement('div');
        paramLabel.className = 'param-label';

        var nameSpan = document.createElement('span');
        nameSpan.textContent = p.name;
        paramLabel.appendChild(nameSpan);

        var typeSpan = document.createElement('span');
        typeSpan.className = 'type';
        typeSpan.textContent = p.type;
        paramLabel.appendChild(typeSpan);

        if (p.required) {
          var requiredSpan = document.createElement('span');
          requiredSpan.className = 'required';
          requiredSpan.textContent = 'required';
          paramLabel.appendChild(requiredSpan);
        }

        var input = document.createElement('input');
        input.className = 'param-input';
        input.id = 'param_' + p.name;
        input.placeholder = p.description || '';

        row.appendChild(paramLabel);
        row.appendChild(input);
        fields.appendChild(row);
      });
    }
  }

  // --- Quick Execute ---
  async function quickExec(name) {
    if (tools.length === 0) {
      await loadTools();
    }
    selectTool(name);
    await executeTool();
  }

  // --- Execute Tool ---
  async function executeTool() {
    if (!selectedTool) return;

    var btn = $('execBtn');
    if (btn) {
      btn.disabled = true;
      btn.textContent = 'Executing...';
    }

    var params = {};
    if (selectedTool.parameters) {
      selectedTool.parameters.forEach(function (p) {
        var input = $('param_' + p.name);
        if (input && input.value.trim() !== '') {
          var val = input.value.trim();
          if (p.type === 'int' || p.type === 'long') val = Number(val);
          else if (p.type === 'double' || p.type === 'float') val = parseFloat(val);
          params[p.name] = val;
        }
      });
    }

    var startTime = Date.now();
    try {
      var res = await fetch('/api/debug/execute', {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ tool: selectedTool.name, params: params })
      });
      var json = await res.json();
      var elapsed = Date.now() - startTime;
      showResult(json.data, elapsed);
    } catch (e) {
      var elapsed = Date.now() - startTime;
      showResult({ success: false, error: e.message }, elapsed);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.textContent = 'Execute';
      }
    }
  }

  // --- Show Result ---
  function showResult(data, elapsed) {
    var panel = $('resultPanel');
    var status = $('resultStatus');
    var time = $('resultTime');
    var body = $('resultBody');
    var img = $('resultImage');
    var timeBar = $('responseTimeBar');

    panel.style.display = 'block';
    var isSuccess = !!data.success;
    status.textContent = isSuccess ? 'SUCCESS' : 'ERROR';
    status.className = 'result-status ' + (isSuccess ? 'success' : 'error');
    time.textContent = elapsed + 'ms';
    img.style.display = 'none';

    // Build content
    var content = '';
    if (data.data != null) content += data.data;
    if (data.error != null) {
      if (content) content += '\n\n--- ERROR ---\n';
      content += data.error;
    }
    if (!content) content = '(empty)';

    try {
      body.textContent = JSON.stringify(JSON.parse(content), null, 2);
    } catch (e) {
      body.textContent = content;
    }

    // Show image if file path
    if (data.success && data.data && /\.(png|jpg|jpeg|webp)$/i.test(data.data.trim())) {
      var filePath = data.data.trim();
      img.src = streamUrl('/api/debug/file?path=' + encodeURIComponent(filePath));
      img.style.display = 'block';
    }

    // Response time bar
    renderResponseTimeBar(timeBar, elapsed);

    // Add to history
    addHistory({
      toolName: selectedTool ? selectedTool.name : 'unknown',
      params: collectCurrentParams(),
      success: isSuccess,
      elapsed: elapsed,
      timestamp: Date.now()
    });

    panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  // --- Response Time Bar ---
  function renderResponseTimeBar(container, elapsed) {
    if (!container) return;
    container.style.display = 'flex';

    var fill = container.querySelector('.bar-fill');
    var label = container.querySelector('.bar-label');

    // Determine color class and bar width
    var cls, width;
    if (elapsed < 500) {
      cls = 'fast';
      width = Math.min(100, (elapsed / 500) * 100);
    } else if (elapsed < 2000) {
      cls = 'medium';
      width = Math.min(100, 50 + ((elapsed - 500) / 1500) * 50);
    } else {
      cls = 'slow';
      width = 100;
    }

    fill.className = 'bar-fill ' + cls;
    fill.style.width = width + '%';
    label.textContent = elapsed + 'ms';
  }

  // --- Execution History ---
  function addHistory(entry) {
    history.unshift(entry);
    if (history.length > MAX_HISTORY) {
      history.pop();
    }
    renderHistory();
  }

  function renderHistory() {
    var list = $('historyList');
    var empty = $('historyEmpty');

    if (history.length === 0) {
      list.innerHTML = '';
      empty.style.display = 'block';
      return;
    }

    empty.style.display = 'none';
    list.innerHTML = '';

    history.forEach(function (entry, idx) {
      var li = document.createElement('li');
      li.className = 'history-item';
      li.dataset.index = idx;

      var timeStr = formatTime(entry.timestamp);

      li.innerHTML =
        '<div class="history-item-name">' + escapeHtml(entry.toolName) + '</div>' +
        '<div class="history-item-meta">' +
          '<span>' + timeStr + '</span>' +
          '<span class="history-item-status ' + (entry.success ? 'success' : 'error') + '">' +
            (entry.success ? 'OK' : 'ERR') +
          '</span>' +
        '</div>';

      li.onclick = function () { restoreHistoryEntry(entry); };
      list.appendChild(li);
    });
  }

  function restoreHistoryEntry(entry) {
    // Select the tool and fill in the saved params
    if (tools.length === 0) return;
    selectTool(entry.toolName);

    // Fill parameter values
    if (entry.params && selectedTool && selectedTool.parameters) {
      selectedTool.parameters.forEach(function (p) {
        var input = $('param_' + p.name);
        if (input && entry.params[p.name] !== undefined) {
          input.value = entry.params[p.name];
        }
      });
    }
  }

  function clearHistory() {
    history = [];
    renderHistory();
  }

  function collectCurrentParams() {
    var params = {};
    if (selectedTool && selectedTool.parameters) {
      selectedTool.parameters.forEach(function (p) {
        var input = $('param_' + p.name);
        if (input && input.value.trim() !== '') {
          params[p.name] = input.value.trim();
        }
      });
    }
    return params;
  }

  // --- Utilities ---
  function escapeHtml(str) {
    return String(str || '').replace(/[&<>"']/g, function (m) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m];
    });
  }

  function formatTime(ts) {
    var d = new Date(ts);
    var h = String(d.getHours()).padStart(2, '0');
    var m = String(d.getMinutes()).padStart(2, '0');
    var s = String(d.getSeconds()).padStart(2, '0');
    return h + ':' + m + ':' + s;
  }

  // --- Init ---
  function init() {
    // Search input listener
    var searchInput = $('searchInput');
    if (searchInput) {
      searchInput.addEventListener('input', function () {
        filterTools(this.value);
      });
    }

    // Clear history button
    var clearBtn = $('historyClearBtn');
    if (clearBtn) {
      clearBtn.addEventListener('click', clearHistory);
    }

    // Attach quick-action buttons (declarative data-tool, no inline onclick)
    document.querySelectorAll('.quick-btn[data-tool]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        quickExec(btn.dataset.tool);
      });
    });

    // Execute button
    var execBtn = $('execBtn');
    if (execBtn) {
      execBtn.addEventListener('click', executeTool);
    }

    // Load tools
    loadTools();
  }

  // Run on DOM ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

})();
