import request from './request'

export function syncSchema(datasourceId, database) {
  return request.post(`/schema/sync/${datasourceId}`, null, {
    timeout: 120000,
    params: database ? { database } : undefined
  })
}

export function listDatabases(datasourceId) {
  return request.get(`/schema/databases/${datasourceId}`)
}

export function listTables(datasourceId, database) {
  return request.get(`/schema/tables/${datasourceId}`, {
    params: database ? { database } : undefined
  })
}

export function indexSchema(datasourceId, database) {
  return request.post(`/schema/index/${datasourceId}`, null, {
    timeout: 180000,
    params: database ? { database } : undefined
  })
}

export function generateAliases(datasourceId, overwrite = false, database) {
  return request.post(`/schema/aliases/generate/${datasourceId}`, null, {
    params: database ? { overwrite, database } : { overwrite },
    timeout: 600000
  })
}

export function clearSchemaIndex(datasourceId, database) {
  return request.delete(`/schema/index/${datasourceId}`, {
    params: { database }
  })
}

export function inferRelations(datasourceId, database) {
  return request.post(`/schema/infer-relations/${datasourceId}`, null, {
    timeout: 60000,
    params: database ? { database } : undefined
  })
}

/**
 * 解析 SSE chunk 并分发一键初始化事件。
 */
function parseInitChunk(chunk, handlers) {
  if (!chunk || !chunk.trim()) return
  let eventName = 'message'
  const dataLines = []
  const lines = chunk.split(/\r?\n/)
  let hasPayload = false
  for (const line of lines) {
    if (line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
      hasPayload = true
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
      hasPayload = true
    }
  }
  if (!hasPayload) return
  const raw = dataLines.join('\n')
  let data = raw
  if (raw) {
    try {
      data = JSON.parse(raw)
    } catch {
      // 保持原文
    }
  }
  switch (eventName) {
    case 'init_start':
      handlers.onInitStart?.(data)
      break
    case 'db_start':
      handlers.onDbStart?.(data)
      break
    case 'db_done':
      handlers.onDbDone?.(data)
      break
    case 'step':
      handlers.onStep?.(data)
      break
    case 'progress':
      handlers.onProgress?.(data)
      break
    case 'init_done':
      handlers.onInitDone?.(data)
      break
    case 'error':
      handlers.onError?.(data)
      break
    case 'done':
      handlers.onDone?.(data)
      break
    default:
      break
  }
}

/**
 * 一键初始化 SSE。
 * @param {{ datasourceId: string, database?: string, overwriteAliases?: boolean }} payload
 * @param {{ onInitStart?, onDbStart?, onDbDone?, onStep?, onProgress?, onInitDone?, onError?, onDone?, onFail? }} handlers
 * @param {{ signal?: AbortSignal }} options
 */
export async function initSchemaStream(payload, handlers = {}, options = {}) {
  const { datasourceId, database, overwriteAliases = false } = payload || {}
  let res
  try {
    res = await fetch('/api/schema/init/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        datasourceId,
        database: database || undefined,
        overwriteAliases: !!overwriteAliases
      }),
      signal: options.signal
    })
  } catch (err) {
    if (err?.name === 'AbortError') return
    handlers.onFail?.(err)
    return
  }
  if (!res.ok) {
    let msg = `HTTP ${res.status}`
    try {
      const body = await res.json()
      msg = body.message || body.error || msg
    } catch {
      // ignore
    }
    handlers.onFail?.(new Error(msg))
    return
  }
  if (!res.body) {
    handlers.onFail?.(new Error('响应无 body，无法流式读取'))
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let doneCalled = false
  const markDone = (data) => {
    if (!doneCalled) {
      doneCalled = true
      handlers.onDone?.(data)
    }
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const chunk = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        parseInitChunk(chunk.replace(/\r/g, ''), {
          ...handlers,
          onDone: (data) => markDone(data)
        })
      }
    }
    if (buffer.trim()) {
      parseInitChunk(buffer.replace(/\r/g, ''), {
        ...handlers,
        onDone: (data) => markDone(data)
      })
    }
    markDone()
  } catch (err) {
    if (err?.name === 'AbortError') return
    handlers.onFail?.(err)
  }
}
