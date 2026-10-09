<template>
  <div class="query-editor">
    <div class="toolbar">
      <el-button size="small" @click="openSave">保存</el-button>
      <el-button size="small" @click="onFormat">美化SQL</el-button>
      <el-button size="small" :loading="optimizing" @click="onAiOptimize">AI优化</el-button>
      <el-button size="small" :loading="explaining" @click="onExplainSql">解释SQL</el-button>
      <el-button size="small" :disabled="!hasResult" @click="exportCsv">导出CSV</el-button>
      <el-radio-group v-model="resultPageSize" size="small" class="page-size-switch">
        <el-radio-button :value="10">10</el-radio-button>
        <el-radio-button :value="20">20</el-radio-button>
        <el-radio-button :value="50">50</el-radio-button>
      </el-radio-group>
    </div>

    <div class="runbar">
      <el-select v-model="datasourceId" size="small" placeholder="选择数据源" style="width: 220px" filterable>
        <el-option
          v-for="d in appStore.datasources"
          :key="d.id"
          :label="`${d.name} (${dialectOf(d)})`"
          :value="d.id"
        />
      </el-select>
      <el-button type="primary" size="small" :loading="running" :disabled="!canRun" @click="runSql">
        ▶ 运行
      </el-button>
      <el-button size="small" :disabled="!running" @click="onStop">⏹ 停止</el-button>
      <el-button size="small" :loading="planning" @click="onExplainPlan">解释执行计划</el-button>
    </div>

    <div ref="editorHost" class="monaco-host" />

    <div v-show="resultShown" class="result-area">
      <el-tabs v-model="resultTab" class="result-tabs">
        <el-tab-pane label="结果" name="result">
          <div class="pane-scroll">
            <div class="result-meta" v-if="hasResult || lastOk">
              <span v-if="scriptMode">脚本 · {{ scriptSteps.length }} 步 · </span>
              行数 {{ rowCount }}
              <span v-if="truncated">（已截断）</span>
              <span v-if="elapsedMs != null"> · 耗时 {{ elapsedMs }}ms</span>
            </div>
            <el-table
              v-if="columns.length"
              :data="pagedRows"
              size="small"
              border
              :max-height="tableMaxHeight"
              style="width: 100%"
            >
              <el-table-column
                v-for="col in columns"
                :key="col"
                :prop="col"
                :label="col"
                min-width="100"
                show-overflow-tooltip
              />
            </el-table>
            <el-pagination
              v-if="rows.length > resultPageSize"
              class="result-pager"
              small
              background
              layout="total, prev, pager, next"
              :page-size="resultPageSize"
              :current-page="resultPage"
              :total="rows.length"
              @current-change="onResultPageChange"
            />
            <div v-else-if="!columns.length" class="empty-hint">
              {{ scriptMode && lastOk ? '脚本已执行（无最终结果集，可查看「步骤」页签）' : '暂无数据' }}
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="步骤" name="steps">
          <div class="pane-scroll">
            <div v-if="scriptSteps.length" class="script-steps-list">
              <div
                v-for="(s, si) in scriptSteps"
                :key="si"
                class="script-step-row"
              >
                <span class="idx">#{{ (s.index ?? si) + 1 }}</span>
                <span class="meta">
                  {{ s.isFinalSelect ? '最终 SELECT' : '步骤' }}
                  · {{ s.rowCount ?? 0 }} 行
                  · {{ s.elapsedMs ?? 0 }}ms
                  <span v-if="s.error" class="step-err"> · {{ s.error }}</span>
                </span>
                <pre class="step-sql" v-html="highlightSql(s.sql)"></pre>
              </div>
            </div>
            <div v-else class="empty-hint">暂无步骤（单条 SQL 或尚未执行脚本）</div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="消息" name="message">
          <div class="pane-scroll">
            <div v-if="message" class="msg" :class="{ err: messageIsError }">{{ message }}</div>
            <div v-else class="empty-hint">暂无消息</div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="执行计划" name="plan">
          <div class="pane-scroll">
            <div v-if="planNote" class="result-meta">{{ planNote }}</div>
            <el-collapse v-if="planText" class="plan-collapse">
              <el-collapse-item title="原始 EXPLAIN 输出" name="raw">
                <pre class="plan-text">{{ planText }}</pre>
              </el-collapse-item>
            </el-collapse>
            <el-table
              v-if="planRows.length"
              :data="planRows"
              size="small"
              border
              :max-height="tableMaxHeight"
              style="width: 100%"
            >
              <el-table-column
                v-for="col in planColumns"
                :key="col"
                :prop="col"
                :label="col"
                min-width="90"
                show-overflow-tooltip
              />
            </el-table>
            <div v-else class="empty-hint">暂无执行计划</div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="saveVisible" title="保存到脚本库" width="440px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="脚本名称" required>
          <el-input v-model="saveName" placeholder="请输入名称" />
        </el-form-item>
        <el-form-item label="目标数据源">
          <el-input :model-value="saveDsLabel" readonly />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmSave">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="optimizeVisible" title="AI 优化建议" width="720px" destroy-on-close>
      <div class="opt-block">
        <div class="opt-label">原始 SQL</div>
        <pre class="opt-sql muted">{{ optimizeOriginal }}</pre>
      </div>
      <div class="opt-block">
        <div class="opt-label">优化后 SQL</div>
        <el-input v-model="optimizeResult" type="textarea" :rows="8" />
      </div>
      <ul v-if="optimizeChanges.length" class="opt-changes">
        <li v-for="(c, i) in optimizeChanges" :key="i">{{ c }}</li>
      </ul>
      <template #footer>
        <el-button @click="optimizeVisible = false">取消</el-button>
        <el-button type="primary" @click="applyOptimize">应用优化</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="explainVisible" title="SQL 解释" width="560px" destroy-on-close>
      <div class="explain-body">{{ explainText }}</div>
      <template #footer>
        <el-button @click="copyExplain">复制解释</el-button>
        <el-button type="primary" @click="explainVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as monaco from 'monaco-editor'
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import { format } from 'sql-formatter'
import { executeSql, explainPlan } from '../../api/sql'
import { explainSql, optimizeSql } from '../../api/text2sql'
import { saveScript } from '../../api/script'
import { useAppStore } from '../../stores/app'

self.MonacoEnvironment = {
  getWorker() {
    return new editorWorker()
  }
}

const props = defineProps({
  tabId: { type: String, required: true },
  initialSql: { type: String, default: '' },
  initialDatasourceId: { type: String, default: '' }
})

const appStore = useAppStore()

/** 结果表分页：超过此数量才显示分页，每页条数（与 AI 对话一致） */
const resultPageSize = ref(10)

const editorHost = ref(null)
let editor = null
let resizeObs = null
let abortController = null

const sqlText = ref(props.initialSql || '')
const datasourceId = ref(props.initialDatasourceId || appStore.currentDatasourceId || '')
const running = ref(false)
const planning = ref(false)
const optimizing = ref(false)
const explaining = ref(false)
const resultTab = ref('result')
const columns = ref([])
const rows = ref([])
const rowCount = ref(0)
const truncated = ref(false)
const elapsedMs = ref(null)
const message = ref('')
const messageIsError = ref(false)
const lastOk = ref(false)
const scriptMode = ref(false)
const scriptSteps = ref([])
const planRows = ref([])
const planColumns = ref([])
const planText = ref('')
const planNote = ref('')
const tableMaxHeight = 200
const resultPage = ref(1)
const resultShown = ref(false)

let themeReady = false

function ensureSqlTheme() {
  if (themeReady) return
  monaco.editor.defineTheme('navicat-sql', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'keyword.sql', foreground: '0000CC', fontStyle: 'bold' },
      { token: 'keyword.block.sql', foreground: '0000CC', fontStyle: 'bold' },
      { token: 'keyword.try.sql', foreground: '0000CC', fontStyle: 'bold' },
      { token: 'keyword.catch.sql', foreground: '0000CC', fontStyle: 'bold' },
      { token: 'keyword.choice.sql', foreground: '0000CC', fontStyle: 'bold' },
      { token: 'operator.sql', foreground: '0000CC', fontStyle: 'bold' },
      { token: 'predefined.sql', foreground: 'A3157A' },
      { token: 'string.sql', foreground: '067D17' },
      { token: 'number.sql', foreground: 'C05000' },
      { token: 'comment.sql', foreground: '808080', fontStyle: 'italic' },
      { token: 'comment.quote.sql', foreground: '808080', fontStyle: 'italic' },
      { token: 'identifier.sql', foreground: '1F1F1F' },
      { token: 'identifier.quote.sql', foreground: '1F1F1F' },
      { token: 'delimiter.sql', foreground: '303133' },
      { token: 'delimiter.parenthesis.sql', foreground: '303133' },
      { token: 'delimiter.square.sql', foreground: '303133' }
    ],
    colors: {
      'editor.background': '#FFFFFF',
      'editor.foreground': '#1F1F1F',
      'editorLineNumber.foreground': '#B0B4BA',
      'editorLineNumber.activeForeground': '#606266',
      'editor.selectionBackground': '#D6E8FF',
      'editor.lineHighlightBackground': '#F7F9FC',
      'editorCursor.foreground': '#303133'
    }
  })
  themeReady = true
}

function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function tokenClass(type) {
  const name = String(type || '')
  if (name.startsWith('keyword') || name.startsWith('operator')) return 'tok-kw'
  if (name.startsWith('predefined')) return 'tok-fn'
  if (name.startsWith('string')) return 'tok-str'
  if (name.startsWith('number')) return 'tok-num'
  if (name.startsWith('comment')) return 'tok-cmt'
  return ''
}

function highlightSql(sql) {
  const text = sql == null ? '' : String(sql)
  if (!text) return ''
  let tokenized = []
  try {
    tokenized = monaco.editor.tokenize(text, 'sql')
  } catch {
    return escapeHtml(text)
  }
  return text.split('\n').map((line, index) => {
    const tokens = tokenized[index] || []
    if (!tokens.length) return escapeHtml(line)
    let html = ''
    for (let i = 0; i < tokens.length; i++) {
      const start = tokens[i].offset
      const end = i + 1 < tokens.length ? tokens[i + 1].offset : line.length
      const piece = escapeHtml(line.slice(start, end))
      const cls = tokenClass(tokens[i].type)
      html += cls ? `<span class="${cls}">${piece}</span>` : piece
    }
    return html
  }).join('\n')
}

function showResults() {
  resultShown.value = true
  nextTick(() => editor?.layout())
}

const saveVisible = ref(false)
const saveName = ref('')
const saving = ref(false)

const optimizeVisible = ref(false)
const optimizeOriginal = ref('')
const optimizeResult = ref('')
const optimizeChanges = ref([])

const explainVisible = ref(false)
const explainText = ref('')

const hasResult = computed(() => columns.value.length > 0)
const canRun = computed(() => !!datasourceId.value && !!sqlText.value.trim() && !running.value)
const pagedRows = computed(() => {
  const list = rows.value || []
  const page = Math.max(1, resultPage.value || 1)
  const size = resultPageSize.value || 10
  const start = (page - 1) * size
  return list.slice(start, start + size)
})
const currentDs = computed(() => appStore.datasources.find((d) => d.id === datasourceId.value) || null)
const saveDsLabel = computed(() => {
  const d = currentDs.value
  if (!d) return '未选择'
  return `${d.name} (${dialectOf(d)})`
})

function dialectOf(d) {
  const t = (d?.type || '').toString().toUpperCase()
  return t.includes('POSTGRES') ? 'PostgreSQL' : 'MySQL'
}

function onResultPageChange(page) {
  resultPage.value = page
}

watch(resultPageSize, () => {
  resultPage.value = 1
})

function formatterLang() {
  return dialectOf(currentDs.value) === 'PostgreSQL' ? 'postgresql' : 'mysql'
}

function initEditor() {
  if (!editorHost.value || editor) return
  ensureSqlTheme()
  editor = monaco.editor.create(editorHost.value, {
    value: sqlText.value || '',
    language: 'sql',
    theme: 'navicat-sql',
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    scrollBeyondLastLine: false,
    wordWrap: 'on',
    tabSize: 2
  })
  editor.onDidChangeModelContent(() => {
    sqlText.value = editor.getValue()
  })
  resizeObs = new ResizeObserver(() => {
    editor?.layout()
  })
  resizeObs.observe(editorHost.value)
}

function getTargetSql() {
  if (!editor) return (sqlText.value || '').trim()
  const sel = editor.getModel()?.getValueInRange(editor.getSelection())
  if (sel && sel.trim()) return sel.trim()
  return (editor.getValue() || '').trim()
}

function onFormat() {
  const sql = (editor?.getValue() || sqlText.value || '').trim()
  if (!sql) {
    ElMessage.warning('没有可美化的 SQL')
    return
  }
  try {
    const formatted = format(sql, { language: formatterLang() })
    editor?.setValue(formatted)
    sqlText.value = formatted
    ElMessage.success('已美化')
  } catch (e) {
    ElMessage.error(e?.message || '美化失败')
  }
}

async function onAiOptimize() {
  if (!datasourceId.value) {
    ElMessage.warning('请选择数据源')
    return
  }
  const sql = getTargetSql()
  if (!sql) {
    ElMessage.warning('没有可优化的 SQL')
    return
  }
  optimizing.value = true
  try {
    const res = await optimizeSql({ datasourceId: datasourceId.value, sql })
    if (!res?.success) {
      ElMessage.error(res?.error || '优化失败')
      return
    }
    optimizeOriginal.value = sql
    optimizeResult.value = res.optimizedSql || sql
    optimizeChanges.value = Array.isArray(res.changes) ? res.changes : []
    optimizeVisible.value = true
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '优化失败')
  } finally {
    optimizing.value = false
  }
}

function applyOptimize() {
  const sql = optimizeResult.value || ''
  if (!sql.trim()) {
    ElMessage.warning('优化结果为空')
    return
  }
  editor?.setValue(sql)
  sqlText.value = sql
  optimizeVisible.value = false
  ElMessage.success('已应用优化')
}

async function onExplainSql() {
  if (!datasourceId.value) {
    ElMessage.warning('请选择数据源')
    return
  }
  const sql = getTargetSql()
  if (!sql) {
    ElMessage.warning('没有可解释的 SQL')
    return
  }
  explaining.value = true
  try {
    const res = await explainSql({ datasourceId: datasourceId.value, sql })
    if (!res?.success) {
      ElMessage.error(res?.error || '解释失败')
      return
    }
    explainText.value = res.explanation || ''
    explainVisible.value = true
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '解释失败')
  } finally {
    explaining.value = false
  }
}

function copyExplain() {
  navigator.clipboard?.writeText(explainText.value || '').then(
    () => ElMessage.success('已复制'),
    () => ElMessage.warning('复制失败')
  )
}

function onStop() {
  if (abortController) {
    abortController.abort()
  }
}

function workingDatabase() {
  return appStore.currentDatabase || currentDs.value?.database || ''
}

async function runSql() {
  if (!datasourceId.value) {
    ElMessage.warning('请选择数据源')
    return
  }
  const database = workingDatabase()
  if (!database) {
    ElMessage.warning('请先在左侧选择数据库')
    return
  }
  const sql = (editor?.getValue() || sqlText.value || '').trim()
  if (!sql) {
    ElMessage.warning('SQL 不能为空')
    return
  }
  if (abortController) {
    abortController.abort()
  }
  abortController = new AbortController()
  running.value = true
  message.value = ''
  messageIsError.value = false
  let requested = false
  try {
    requested = true
    const res = await executeSql({
      datasourceId: datasourceId.value,
      sql,
      database,
      signal: abortController.signal
    })
    elapsedMs.value = res?.elapsedMs ?? null
    scriptMode.value = !!res?.scriptMode
    scriptSteps.value = Array.isArray(res?.steps) ? res.steps : []
    if (res?.success) {
      columns.value = res.columns || []
      rows.value = res.rows || []
      rowCount.value = res.rowCount ?? rows.value.length
      truncated.value = !!res.truncated
      resultPage.value = 1
      lastOk.value = true
      const stepHint = scriptMode.value ? `（脚本 ${scriptSteps.value.length} 步）` : ''
      message.value =
        `执行成功${stepHint}，返回 ${rowCount.value} 行` + (truncated.value ? '（已截断）' : '')
      messageIsError.value = false
      resultTab.value = 'result'
      appStore.setQueryStats({ rowCount: rowCount.value, elapsedMs: elapsedMs.value || 0 })
    } else {
      columns.value = []
      rows.value = []
      rowCount.value = 0
      truncated.value = false
      lastOk.value = false
      message.value = res?.error || '执行失败'
      messageIsError.value = true
      resultTab.value = scriptSteps.value.length ? 'steps' : 'message'
    }
  } catch (e) {
    const canceled =
      e?.code === 'ERR_CANCELED' ||
      e?.name === 'CanceledError' ||
      e?.name === 'AbortError' ||
      /abort|cancel/i.test(String(e?.message || ''))
    columns.value = []
    rows.value = []
    rowCount.value = 0
    scriptMode.value = false
    scriptSteps.value = []
    lastOk.value = false
    if (canceled) {
      message.value = '已取消'
      messageIsError.value = false
    } else {
      message.value = e?.response?.data?.message || e?.message || '执行失败'
      messageIsError.value = true
    }
    resultTab.value = 'message'
  } finally {
    running.value = false
    abortController = null
    if (requested) showResults()
  }
}

async function onExplainPlan() {
  if (!datasourceId.value) {
    ElMessage.warning('请选择数据源')
    return
  }
  const database = workingDatabase()
  if (!database) {
    ElMessage.warning('请先在左侧选择数据库')
    return
  }
  const sql = (editor?.getValue() || sqlText.value || '').trim()
  if (!sql) {
    ElMessage.warning('SQL 不能为空')
    return
  }
  planning.value = true
  let requested = false
  try {
    requested = true
    const res = await explainPlan({ datasourceId: datasourceId.value, sql, database })
    if (!res?.success) {
      message.value = res?.error || '获取执行计划失败'
      messageIsError.value = true
      resultTab.value = 'message'
      return
    }
    const plan = Array.isArray(res.plan) ? res.plan : []
    planRows.value = plan
    planText.value = res.planText || ''
    planNote.value = res.note || (res.scriptMode ? '多步脚本：已对最终 SELECT 解释执行计划' : '')
    const cols = new Set()
    for (const row of plan) {
      Object.keys(row || {}).forEach((k) => cols.add(k))
    }
    planColumns.value = [...cols]
    resultTab.value = 'plan'
  } catch (e) {
    message.value = e?.response?.data?.message || e?.message || '获取执行计划失败'
    messageIsError.value = true
    resultTab.value = 'message'
  } finally {
    planning.value = false
    if (requested) showResults()
  }
}

function escapeCsvCell(val) {
  if (val == null) return ''
  const s = String(val)
  if (/[",\r\n]/.test(s)) {
    return `"${s.replace(/"/g, '""')}"`
  }
  return s
}

function exportCsv() {
  if (!columns.value.length) {
    ElMessage.warning('暂无结果可导出')
    return
  }
  const lines = []
  lines.push(columns.value.map(escapeCsvCell).join(','))
  for (const row of rows.value) {
    lines.push(columns.value.map((c) => escapeCsvCell(row[c])).join(','))
  }
  const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' })
  const a = document.createElement('a')
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const name =
    `query-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}.csv`
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出 CSV')
}

function defaultScriptName() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `查询-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}`
}

function openSave() {
  const sql = (editor?.getValue() || sqlText.value || '').trim()
  if (!sql) {
    ElMessage.warning('没有可保存的 SQL')
    return
  }
  saveName.value = defaultScriptName()
  saveVisible.value = true
}

async function confirmSave() {
  const name = saveName.value.trim()
  if (!name) {
    ElMessage.warning('请输入脚本名称')
    return
  }
  const sql = (editor?.getValue() || sqlText.value || '').trim()
  saving.value = true
  try {
    const ds = currentDs.value
    await saveScript({
      name,
      content: sql,
      datasourceId: datasourceId.value || null,
      dbName: workingDatabase() || ds?.database || null,
      source: 'MANUAL'
    })
    ElMessage.success('已保存到脚本库')
    saveVisible.value = false
    appStore.bumpScriptRefresh()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!appStore.datasources.length) {
    try {
      await appStore.loadDatasources()
    } catch {
      // ignore
    }
  }
  if (!datasourceId.value && props.initialDatasourceId) {
    datasourceId.value = props.initialDatasourceId
  }
  await nextTick()
  initEditor()
})

onUnmounted(() => {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  resizeObs?.disconnect()
  resizeObs = null
  if (editor) {
    editor.dispose()
    editor = null
  }
})

watch(
  () => props.initialSql,
  (v) => {
    if (v != null && editor && editor.getValue() !== v) {
      editor.setValue(v)
      sqlText.value = v
    }
  }
)
</script>

<style scoped>
.query-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #fff;
}
.toolbar,
.runbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid #ebeef5;
  flex-shrink: 0;
  flex-wrap: wrap;
}
.page-size-switch {
  margin-left: 4px;
}
.runbar {
  background: #fafafa;
}
.monaco-host {
  flex: 1;
  min-height: 160px;
  border-bottom: 1px solid #ebeef5;
}
.result-area {
  height: 280px;
  min-height: 180px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-top: 1px solid #ebeef5;
}
.result-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 0 8px 8px;
  min-height: 0;
}
.result-tabs :deep(.el-tabs__header) {
  flex-shrink: 0;
  margin-bottom: 6px;
}
.result-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.result-tabs :deep(.el-tab-pane) {
  height: 100%;
}
.pane-scroll {
  height: 100%;
  overflow: auto;
  padding-bottom: 4px;
}
.result-pager {
  margin-top: 10px;
  justify-content: flex-end;
}
.result-meta {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.empty-hint {
  font-size: 12px;
  color: #909399;
  padding: 16px 4px;
}
.msg {
  font-size: 13px;
  padding: 10px;
  white-space: pre-wrap;
  color: #303133;
}
.msg.err {
  color: #f56c6c;
  background: #fef0f0;
  border-radius: 6px;
}
.script-steps-list {
  padding: 0 2px 8px;
}
.script-step-row {
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
  font-size: 12px;
}
.script-step-row:last-child {
  border-bottom: none;
}
.script-step-row .idx {
  font-weight: 600;
  margin-right: 6px;
  color: #409eff;
}
.script-step-row .meta {
  color: #909399;
}
.script-step-row .step-err {
  color: #f56c6c;
}
.script-step-row .step-sql {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 120px;
  overflow: auto;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
  color: #1f1f1f;
}
.step-sql :deep(.tok-kw) {
  color: #0000cc;
  font-weight: 700;
}
.step-sql :deep(.tok-fn) {
  color: #a3157a;
}
.step-sql :deep(.tok-str) {
  color: #067d17;
}
.step-sql :deep(.tok-num) {
  color: #c05000;
}
.step-sql :deep(.tok-cmt) {
  color: #808080;
  font-style: italic;
}
.plan-collapse {
  margin-bottom: 6px;
}
.plan-text {
  margin: 0;
  max-height: 100px;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
}
.opt-block {
  margin-bottom: 12px;
}
.opt-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.opt-sql {
  margin: 0;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 6px;
  font-size: 12px;
  white-space: pre-wrap;
  max-height: 160px;
  overflow: auto;
}
.opt-sql.muted {
  color: #606266;
}
.opt-changes {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: #303133;
}
.explain-body {
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  color: #303133;
}
</style>
