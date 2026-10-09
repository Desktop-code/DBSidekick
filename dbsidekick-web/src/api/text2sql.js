/**
 * Text2SQL SSE（POST + fetch 流式解析，不用 EventSource）
 */
import request from './request'

function parseAndDispatch(chunk, handlers) {
  if (!chunk || !chunk.trim()) return
  let eventName = 'message'
  const dataLines = []
  const lines = chunk.split(/\r?\n/)
  let hasPayload = false
  for (const line of lines) {
    if (line.startsWith(':')) {
      // SSE comment / keep-alive，忽略
      continue
    }
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
    case 'phase':
      handlers.onPhase?.(data)
      break
    case 'result':
      handlers.onResult?.(data)
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
 * @param {{ datasourceId: string, question: string, topK?: number, sessionId?: string }} payload
 * @param {{ onPhase?, onResult?, onError?, onDone?, onFail? }} handlers
 * @returns {Promise<void>}
 */
export async function streamQuery(payload, handlers = {}) {
  const { datasourceId, question, topK = 5, sessionId, dbName, title } = payload || {}
  let res
  try {
    res = await fetch('/api/text2sql/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        datasourceId,
        question,
        topK,
        sessionId: sessionId || undefined,
        dbName: dbName || undefined,
        title: title || undefined
      })
    })
  } catch (err) {
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
  let donePayload = null
  let firstChunkLogged = false
  const markDone = () => {
    if (!doneCalled) {
      doneCalled = true
      console.log('[SSE] done', Date.now())
      handlers.onDone?.(donePayload)
    }
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      if (!firstChunkLogged) {
        firstChunkLogged = true
        console.log('[SSE] first chunk received', Date.now(), 'bytes=', value?.byteLength ?? 0)
      }
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const chunk = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        // 兼容 \r\n\r\n
        parseAndDispatch(chunk.replace(/\r/g, ''), {
          ...handlers,
          onPhase: (data) => {
            console.log('[SSE] event: phase', Date.now(), data?.phase)
            handlers.onPhase?.(data)
          },
          onResult: (data) => {
            console.log('[SSE] event: result', Date.now())
            handlers.onResult?.(data)
          },
          onError: (data) => {
            console.log('[SSE] event: error', Date.now())
            handlers.onError?.(data)
          },
          onDone: (data) => {
            console.log('[SSE] event: done', Date.now())
            donePayload = data
            markDone()
          }
        })
      }
    }
    // 尾部残留
    if (buffer.trim()) {
      parseAndDispatch(buffer.replace(/\r/g, ''), {
        ...handlers,
        onDone: (data) => {
          donePayload = data
          markDone()
        }
      })
    }
    markDone()
  } catch (err) {
    handlers.onFail?.(err)
  }
}

export async function text2sqlHealth() {
  const res = await fetch('/api/text2sql/health')
  return res.json()
}

export async function milvusHealth() {
  const res = await fetch('/api/ai/milvus/health')
  return res.json()
}

export function probeMilvus({ host, port }) {
  return request.post('/ai/milvus/probe', { host, port }, { timeout: 15000 })
}

export async function embeddingHealth() {
  const res = await fetch('/api/ai/embedding/health')
  return res.json()
}

export function optimizeSql({ datasourceId, sql }) {
  return request.post('/text2sql/optimize', { datasourceId, sql }, { timeout: 120000 })
}

export function explainSql({ datasourceId, sql }) {
  return request.post('/text2sql/explain', { datasourceId, sql }, { timeout: 120000 })
}
