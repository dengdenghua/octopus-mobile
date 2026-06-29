"use strict";

var octopusPort = null;
var pendingEvals = {};
var evalId = 0;

function connect() {
  try {
    octopusPort = browser.runtime.connectNative("octopus");
    octopusPort.onMessage.addListener(function(msg) {
      if (msg && msg.type === "eval" && typeof msg.script === "string") {
        var id = msg.id;
        browser.tabs.query({ active: true, currentWindow: true }).then(function(tabs) {
          if (!tabs || tabs.length === 0) {
            octopusPort.postMessage({ type: "eval_result", id: id, error: "no active tab" });
            return;
          }
          browser.tabs.executeScript(tabs[0].id, { code: msg.script, runAt: "document_idle" }).then(function(results) {
            if (browser.runtime.lastError) {
              octopusPort.postMessage({ type: "eval_result", id: id, error: browser.runtime.lastError.message || "executeScript failed" });
              return;
            }
            var result = null;
            if (results && results.length > 0) {
              var raw = results[0];
              result = raw === undefined || raw === null ? null :
                       typeof raw === "object" ? JSON.stringify(raw) : String(raw);
            }
            octopusPort.postMessage({ type: "eval_result", id: id, result: result });
          }, function(err) {
            octopusPort.postMessage({ type: "eval_result", id: id, error: err && err.message ? err.message : String(err) });
          });
        }, function(err) {
          octopusPort.postMessage({ type: "eval_result", id: id, error: err && err.message ? err.message : String(err) });
        });
      }
    });
    octopusPort.onDisconnect.addListener(function() {
      octopusPort = null;
      setTimeout(connect, 1000);
    });
  } catch(e) {
    setTimeout(connect, 1000);
  }
}

connect();
