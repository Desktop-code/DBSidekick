import { defineStore } from 'pinia'
import { listDatasources, testDatasource } from '../api/datasource'
import { text2sqlHealth, milvusHealth, embeddingHealth } from '../api/text2sql'

export const useAppStore = defineStore('app', {
  state: () => ({
    datasources: [],
    currentDatasourceId: null,
    /** @type {Record<string, 'up'|'down'|'unknown'>} */
    datasourceStatus: {},
    /** @type {Record<string, boolean>} */
    datasourceIndexed: {},
    /** @type {{ llm: 'up'|'down'|'unknown', embedding: 'up'|'down'|'unknown', milvus: 'up'|'down'|'unknown' }} */
    health: {
      llm: 'unknown',
      embedding: 'unknown',
      milvus: 'unknown'
    },
    healthDetail: {
      llm: 'LLM: 未检测',
      embedding: 'Embedding: 未检测',
      milvus: 'Milvus: 未检测'
    },
    lastRowCount: 0,
    lastElapsedMs: 0,
    statusText: '就绪',
    scriptRefreshTrigger: 0,
    scriptFocusToken: 0,
    currentSessionId: null,
    sessionRefreshTrigger: 0,
    /** 当前查询范围。旧连接在选中数据源时默认带回保存的库名。 */
    currentDatabase: null
  }),
  getters: {
    currentDatasource(state) {
      return state.datasources.find((d) => d.id === state.currentDatasourceId) || null
    },
    currentDatasourceName(state) {
      const ds = state.datasources.find((d) => d.id === state.currentDatasourceId)
      return ds?.name || '未选择'
    },
    currentDialect(state) {
      const ds = state.datasources.find((d) => d.id === state.currentDatasourceId)
      if (!ds) return '-'
      const t = (ds.type || '').toString().toUpperCase()
      if (t.includes('POSTGRES')) return 'PostgreSQL'
      return 'MySQL'
    }
  },
  actions: {
    async loadDatasources() {
      const list = await listDatasources()
      this.datasources = Array.isArray(list) ? list : []
    },
    setCurrentDatasource(id) {
      if (this.currentDatasourceId !== id) {
        this.currentSessionId = null
        this.currentDatabase = null
        const ds = (this.datasources || []).find((d) => d.id === id)
        if (ds?.database) {
          this.currentDatabase = ds.database
        }
      }
      this.currentDatasourceId = id
    },
    setCurrentDatabase(name) {
      const text = name == null ? '' : String(name).trim()
      this.currentDatabase = text || null
    },
    setCurrentSessionId(id) {
      this.currentSessionId = id || null
    },
    bumpSessionRefresh() {
      this.sessionRefreshTrigger += 1
    },
    setDatasourceStatus(id, status) {
      this.datasourceStatus = { ...this.datasourceStatus, [id]: status }
    },
    setDatasourceIndexed(id, indexed) {
      this.datasourceIndexed = { ...this.datasourceIndexed, [id]: !!indexed }
    },
    async refreshDatasourceStatuses() {
      const list = this.datasources || []
      for (const ds of list) {
        try {
          const res = await testDatasource({
            id: ds.id,
            name: ds.name,
            type: ds.type,
            host: ds.host,
            port: ds.port,
            database: ds.database,
            schema: ds.schema,
            username: ds.username,
            password: ''
          })
          this.setDatasourceStatus(ds.id, res?.success ? 'up' : 'down')
        } catch {
          this.setDatasourceStatus(ds.id, 'down')
        }
      }
    },
    setQueryStats({ rowCount = 0, elapsedMs = 0 } = {}) {
      this.lastRowCount = rowCount
      this.lastElapsedMs = elapsedMs
    },
    bumpScriptRefresh() {
      this.scriptRefreshTrigger += 1
    },
    focusFirstScript() {
      this.scriptFocusToken += 1
    },
    async refreshHealth() {
      try {
        const [llm, milvus, emb] = await Promise.all([
          text2sqlHealth().catch(() => ({})),
          milvusHealth().catch(() => ({})),
          embeddingHealth().catch(() => ({}))
        ])

        if (llm.llmConfigured) {
          this.health.llm = 'up'
          this.healthDetail.llm = `LLM: UP · model=${llm.model || '-'}`
        } else {
          this.health.llm = 'unknown'
          this.healthDetail.llm = 'LLM: 未配置 API Key'
        }

        const provider = (emb.provider || 'onnx').toLowerCase()
        if (emb.loaded) {
          this.health.embedding = 'up'
          this.healthDetail.embedding = `Embedding: UP · provider=${provider}`
        } else if (emb.error) {
          this.health.embedding = 'down'
          this.healthDetail.embedding = `Embedding: DOWN · ${emb.error}`
        } else {
          this.health.embedding = 'unknown'
          this.healthDetail.embedding = `Embedding: 未就绪 · provider=${provider}`
        }

        if (milvus.reachable) {
          this.health.milvus = 'up'
          this.healthDetail.milvus = `Milvus: UP · collection=${milvus.collection || '-'} · rowCount=${milvus.rowCount ?? 0}`
        } else {
          this.health.milvus = 'down'
          this.healthDetail.milvus = `Milvus: DOWN${milvus.error ? ' · ' + milvus.error : ''}`
        }
      } catch {
        // ignore
      }
    }
  }
})
