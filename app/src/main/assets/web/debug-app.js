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

  // --- DOM References ---
  const $ = (id) => document.getElementById(id);

  // --- Tool Loading ---
  async function loadTools() {
    try {
      const res = await fetch('/api/debug/tools');
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
  function renderToolList() {
    const container = $('toolList');
    container.innerHTML = '';
    tools.forEach(function (t) {
      const btn = document.createElement('button');
      btn.className = 'tool-btn';
      btn.textContent = t.name;
      btn.title = t.description;
      btn.dataset.toolName = t.name;
      btn.onclick = function () { selectTool(t.name); };
      container.appendChild(btn);
    });
    // Re-apply current filter
    filterTools($('searchInput').value);
  }

  // --- Tool Search / Filter ---
  function filterTools(query) {
    const q = (query || '').trim().toLowerCase();
    const buttons = $('toolList').querySelectorAll('.tool-btn');
    buttons.forEach(function (btn) {
      const name = (btn.dataset.toolName || '').toLowerCase();
      if (!q || name.indexOf(q) !== -1) {
        btn.classList.remove('hidden');
      } else {
        btn.classList.add('hidden');
      }
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
        row.innerHTML =
          '<div class="param-label">' +
            '<span>' + escapeHtml(p.name) + '</span>' +
            '<span class="type">' + escapeHtml(p.type) + '</span>' +
            (p.required ? '<span class="required">required</span>' : '') +
          '</div>' +
          '<input class="param-input" id="param_' + p.name + '" placeholder="' + escapeHtml(p.description || '') + '">';
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
        headers: { 'Content-Type': 'application/json' },
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
      img.src = '/api/debug/file?path=' + encodeURIComponent(filePath);
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
    var div = document.createElement('div');
    div.textContent = str || '';
    return div.innerHTML;
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

    // Expose quickExec globally for onclick handlers in HTML
    window.quickExec = quickExec;

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
