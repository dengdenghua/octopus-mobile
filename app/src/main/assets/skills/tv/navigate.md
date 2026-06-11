---
name: navigate
description: Smart UI navigation — learn routes from user behavior and auto-navigate using A* pathfinding on the UI knowledge graph.
risk: low
timeout_ms: 30000
parameters:
  - name: action
    type: string
    required: true
    description: "Action type: 'record_start', 'record_stop', 'navigate', 'list_nodes', 'graph_stats', 'passive_on', 'passive_off', 'current_state'"
  - name: name
    type: string
    required: false
    description: "Route name for recording, or goal label for navigation (e.g. 'Netflix search')"
  - name: app_package
    type: string
    required: false
    description: "Target app package for navigation (e.g. 'com.netflix.ninja')"
---

# Navigate — UI Navigation Knowledge Graph

## Actions

### Record a Route
```
navigate(action="record_start", name="Open Netflix search")
```
Then use the remote control normally. When done:
```
navigate(action="record_stop")
```

### Auto-Navigate
```
navigate(action="navigate", app_package="com.netflix.ninja", name="search")
```

### View Graph
```
navigate(action="list_nodes")
navigate(action="graph_stats")
```

### Passive Learning
```
navigate(action="passive_on")
```
The agent will automatically learn from daily remote control usage.

### Check Current State
```
navigate(action="current_state")
```
