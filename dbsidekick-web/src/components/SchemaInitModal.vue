<template>
  <el-dialog
    :model-value="visible"
    width="600px"
    :show-close="allowClose"
    :close-on-click-modal="false"
    :close-on-press-escape="allowClose"
    :before-close="onBeforeClose"
    destroy-on-close
    class="schema-init-dialog"
    @update:model-value="onVisibleChange"
  >
    <template #header>
      <div class="init-header">
        <h2 class="init-title">一键初始化</h2>
        <p v-if="subtitle" class="init-subtitle">{{ subtitle }}</p>
      </div>
    </template>

    <div class="init-body">
      <ul class="steps">
        <li v-for="s in steps" :key="s.key" class="step" :class="statusClass(s.status)">
          <div class="step-row">
            <span class="icon" aria-hidden="true">
              <el-icon v-if="s.status === 'RUNNING'" class="spin" :size="18"><Loading /></el-icon>
              <el-icon v-else-if="s.status === 'SUCCEEDED'" class="ok" :size="18"><CircleCheck /></el-icon>
              <el-icon v-else-if="s.status === 'FAILED'" class="fail" :size="18"><CircleClose /></el-icon>
              <span v-else class="ring" />
            </span>
            <span class="step-name">{{ s.label }}</span>
            <span class="step-right">{{ rightText(s) }}</span>
          </div>
          <el-progress
            v-if="s.status === 'RUNNING' && s.total > 0"
            class="step-progress"
            :percentage="progressPct(s)"
            :stroke-width="3"
            :show-text="false"
            color="#409EFF"
          />
        </li>
      </ul>

      <p v-if="overallMessage" class="overall" :class="{ fail: overall === 'FAILED', ok: overall === 'SUCCEEDED' }">
        {{ overallMessage }}
      </p>
    </div>

    <template #footer>
      <div class="init-footer" :class="{ running: !allowClose }">
        <template v-if="allowClose">
          <el-button type="primary" @click="close">完成</el-button>
        </template>
        <template v-else>
          <span class="wait-text">🚀 正在执行初始化任务，请稍候...</span>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { CircleCheck, CircleClose, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { initSchemaStream } from '../api/schema'
import { useAppStore } from '../stores/app'

const STEP_DEFS = [
  { key: 'SYNC', label: '1. 同步表结构' },
  { key: 'INFER', label: '2. 推断表关系' },
  { key: 'ALIAS', label: '3. 生成表别名' },
  { key: 'INDEX', label: '4. 建立向量索引' }
]

const appStore = useAppStore()

const visible = computed(() => !!appStore.schemaInit?.open)
const datasourceId = computed(() => appStore.schemaInit?.datasourceId || '')
const database = computed(() => appStore.schemaInit?.database || '')
const token = computed(() => appStore.schemaInit?.token || 0)

const steps = reactive(STEP_DEFS.map((d) => ({
  key: d.key,
  label: d.label,
  status: 'PENDING',
  message: '等待中',
  current: 0,
  total: 0,
  error: ''
})))

const allowClose = ref(false)
const overall = ref('')
const overallMessage = ref('')
const currentDb = ref('')
const dbIndex = ref(0)
const dbTotal = ref(0)
const running = ref(false)
let abortController = null

const datasourceName = computed(() => {
  if (!datasourceId.value) return ''
  const ds = appStore.datasources.find((d) => d.id === datasourceId.value)
  return ds?.name || datasourceId.value
})

/** 副标题：数据源：XXX  |  正在初始化库：yyy (i/n) */
const subtitle = computed(() => {
  const name = datasourceName.value
  if (!name) return ''
  const parts = [`数据源：${name}`]
  if (currentDb.value) {
    const idx = dbIndex.value > 0 && dbTotal.value > 0
      ? ` (${dbIndex.value}/${dbTotal.value})`
      : ''
    parts.push(`正在初始化库：${currentDb.value}${idx}`)
  } else if (database.value) {
    parts.push(`正在初始化库：${database.value}`)
  } else if (dbTotal.value > 1) {
    parts.push(`按库依次初始化（共 ${dbTotal.value} 个）`)
  }
  return parts.join('  |  ')
})

function resetSteps() {
  for (const s of steps) {
    s.status = 'PENDING'
    s.message = '等待中'
    s.current = 0
    s.total = 0
    s.error = ''
  }
  allowClose.value = false
  overall.value = ''
  overallMessage.value = ''
  currentDb.value = database.value || ''
  dbIndex.value = 0
  dbTotal.value = 0
}

function findStep(key) {
  return steps.find((s) => s.key === key)
}

function statusClass(status) {
  return {
    pending: status === 'PENDING',
    running: status === 'RUNNING',
    ok: status === 'SUCCEEDED',
    fail: status === 'FAILED'
  }
}

function rightText(s) {
  if (s.status === 'RUNNING') {
    if (s.total > 0) return `${s.current}/${s.total}`
    return '执行中'
  }
  if (s.status === 'SUCCEEDED') return '已完成'
  if (s.status === 'FAILED') return s.error || s.message || '失败'
  return '等待中'
}

function progressPct(s) {
  if (!s.total || s.total <= 0) return 0
  return Math.min(100, Math.round((s.current / s.total) * 100))
}

function onBeforeClose(done) {
  if (!allowClose.value) {
    return
  }
  done()
}

function onVisibleChange(v) {
  if (!v && allowClose.value) {
    close()
  }
}

function close() {
  abortController?.abort()
  abortController = null
  running.value = false
  appStore.closeSchemaInit()
}

async function start() {
  if (!datasourceId.value || running.value) return
  resetSteps()
  running.value = true
  abortController = new AbortController()

  await initSchemaStream(
    {
      datasourceId: datasourceId.value,
      database: database.value || undefined,
      overwriteAliases: false
    },
    {
      onInitStart(data) {
        const dbs = data?.databases
        if (Array.isArray(dbs) && dbs.length > 0) {
          dbTotal.value = dbs.length
          if (dbs.length === 1) {
            currentDb.value = dbs[0]
            dbIndex.value = 1
          }
        }
      },
      onDbStart(data) {
        if (data?.database) {
          currentDb.value = data.database
          if (typeof data.index === 'number') dbIndex.value = data.index
          if (typeof data.total === 'number') dbTotal.value = data.total
          for (const s of steps) {
            s.status = 'PENDING'
            s.message = '等待中'
            s.current = 0
            s.total = 0
            s.error = ''
          }
        }
      },
      onStep(data) {
        const s = findStep(data?.step)
        if (!s) return
        s.status = data.status || s.status
        if (data.message) s.message = data.message
        if (data.error) s.error = data.error
        if (typeof data.current === 'number') s.current = data.current
        if (typeof data.total === 'number') s.total = data.total
      },
      onProgress(data) {
        const s = findStep(data?.step)
        if (!s) return
        if (s.status === 'PENDING') s.status = 'RUNNING'
        if (typeof data.current === 'number') s.current = data.current
        if (typeof data.total === 'number') s.total = data.total
        if (data.message) s.message = data.message
      },
      onInitDone(data) {
        overall.value = data?.overall || ''
        if (overall.value === 'SUCCEEDED') {
          overallMessage.value = '全部完成，可以开始用自然语言查库了'
          appStore.setDatasourceIndexed(datasourceId.value, true)
        } else {
          overallMessage.value = '初始化未完全成功，可关闭后查看错误或使用「高级操作」重试'
        }
        allowClose.value = true
        running.value = false
      },
      onError(data) {
        overall.value = 'FAILED'
        overallMessage.value = data?.message || '初始化失败'
        allowClose.value = true
        running.value = false
      },
      onFail(err) {
        overall.value = 'FAILED'
        overallMessage.value = err?.message || '连接中断'
        ElMessage.error(overallMessage.value)
        allowClose.value = true
        running.value = false
      },
      onDone() {
        if (!allowClose.value) {
          allowClose.value = true
          running.value = false
        }
      }
    },
    { signal: abortController.signal }
  )
}

watch(
  () => [visible.value, token.value],
  ([open]) => {
    if (open) {
      start()
    } else {
      abortController?.abort()
      abortController = null
      running.value = false
    }
  }
)
</script>

<style scoped>
.init-header {
  padding-right: 8px;
}
.init-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.3;
  color: var(--el-text-color-primary);
}
.init-subtitle {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: #64748b;
  word-break: break-all;
}

.init-body {
  min-height: 220px;
  padding: 4px 4px 8px;
}

.steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.step-row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 22px;
}

.icon {
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.icon .ok {
  color: var(--el-color-success);
}
.icon .fail {
  color: var(--el-color-danger);
}
.icon .spin {
  animation: spin 1s linear infinite;
  color: #409eff;
}
.ring {
  box-sizing: border-box;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 1.5px solid #cbd5e1;
  background: transparent;
}

.step-name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.step.ok .step-name {
  color: #64748b;
  font-weight: 400;
}
.step.pending .step-name {
  color: #94a3b8;
  font-weight: 400;
}
.step.fail .step-name {
  color: var(--el-color-danger);
}

.step-right {
  flex-shrink: 0;
  max-width: 46%;
  font-size: 13px;
  color: #64748b;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.step.running .step-right {
  color: #409eff;
  font-variant-numeric: tabular-nums;
}
.step.ok .step-right {
  color: #94a3b8;
}
.step.fail .step-right {
  color: var(--el-color-danger);
  white-space: normal;
}

.step-progress {
  margin: 8px 0 0 32px;
}
.step-progress :deep(.el-progress-bar__outer) {
  background-color: #e2e8f0;
}

.overall {
  margin: 20px 0 0;
  font-size: 13px;
  line-height: 1.5;
}
.overall.ok {
  color: var(--el-color-success);
}
.overall.fail {
  color: var(--el-color-danger);
}

.init-footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  width: 100%;
  margin-top: 4px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.init-footer.running {
  justify-content: center;
}
.wait-text {
  font-size: 13px;
  color: #64748b;
  line-height: 32px;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>

<style>
/* el-dialog 内边距加大（teleport 到 body，需非 scoped） */
.schema-init-dialog.el-dialog {
  border-radius: 12px;
}
.schema-init-dialog .el-dialog__header {
  padding: 24px 28px 12px;
  margin-right: 0;
}
.schema-init-dialog .el-dialog__body {
  padding: 8px 28px 12px;
}
.schema-init-dialog .el-dialog__footer {
  padding: 0 28px 22px;
}
</style>
