<template>
  <div class="ai-chat">
    <div class="chat-header">
      <div class="left">
        <span class="title clickable" title="点击重命名" @click="onRenameTitle">{{ sessionTitle }}</span>
        <div class="ds-wrap">
          <el-icon v-if="currentDs" class="ds-type-icon" :class="dsKind(currentDs)" :title="dialectOf(currentDs)">
            <Coin />
          </el-icon>
          <el-select
            v-model="localDsId"
            size="small"
            placeholder="选择数据源"
            class="header-select"
            :disabled="headerLocked"
            @change="onPickDatasource"
          >
            <el-option
              v-for="d in appStore.datasources"
              :key="d.id"
              :label="d.name"
              :value="d.id"
            />
          </el-select>
          <span class="sep">·</span>
          <el-select
            v-model="localDb"
            size="small"
            placeholder="选择数据库"
            class="header-select"
            filterable
            :disabled="headerLocked || !localDsId"
            :loading="dbLoading"
            @change="onPickDatabase"
          >
            <el-option v-for="name in dbOptions" :key="name" :label="name" :value="name" />
          </el-select>
        </div>
      </div>
      <el-button class="clear-btn" text :disabled="streaming" @click="clearChat">清空对话</el-button>
    </div>

    <div ref="scrollRef" class="messages" v-loading="loadingSession">
      <div v-if="!messages.length && !streaming && !loadingSession" class="examples">
        <div class="examples-title">试试这样问</div>
        <div class="examples-list">
          <el-button
            v-for="q in exampleQuestions"
            :key="q"
            round
            :disabled="!localDsId || !localDb"
            @click="askExample(q)"
          >
            {{ q }}
          </el-button>
        </div>
      </div>
      <div v-for="(msg, idx) in messages" :key="idx" class="msg" :class="msg.role">
        <div v-if="msg.role === 'user'" class="bubble user-bubble">{{ msg.content }}</div>
        <div v-else class="ai-stack">
          <div
            v-if="msg.pending && !msg.error && !msg.sql && !msg.phases?.length && !showReasoningThinking(msg, idx)"
            class="pending ai-card"
          >
            <span class="thinking-label">thinking</span>
            <span class="thinking-dots" aria-hidden="true">
              <i /><i /><i />
            </span>
          </div>

          <div
            v-if="hasReasonSection(msg, idx) || hasResultSection(msg)"
            class="ai-card"
          >
            <!-- 区域 1：推理过程 -->
            <section
              v-if="hasReasonSection(msg, idx)"
              class="ai-card-block section-reason"
            >
              <button type="button" class="card-head reason-head" @click="toggleReason(msg, idx)">
                <span class="reason-mark" :class="reasonState(msg, idx)">
                  <i v-if="reasonState(msg, idx) === 'running'" class="mini-spin" />
                  <template v-else-if="reasonState(msg, idx) === 'fail'">✗</template>
                  <template v-else>✓</template>
                </span>
                <span class="card-title">推理过程</span>
                <span class="card-sep">·</span>
                <span class="card-meta">{{ reasonStateText(msg, idx) }}</span>
                <span class="card-sep">·</span>
                <span class="card-meta">{{ formatDuration(reasonTotalMs(msg)) }}</span>
                <span class="reason-toggle">{{ isReasonOpen(msg, idx) ? '收起 ⌃' : '展开 ⌄' }}</span>
              </button>
              <div class="reason-panel" :class="{ open: isReasonOpen(msg, idx) }">
                <div class="reason-panel-inner">
                  <div
                    v-for="(p, i) in msg.phases"
                    :key="i"
                    class="phase-line"
                    :class="phaseMark(p)"
                  >
                    <div class="phase-head">
                      <span class="icon">{{ phaseIcon(p) }}</span>
                      <span class="phase-title">
                        <span class="phase-name">{{ p.phase || '阶段' }}</span>
                        <el-tooltip
                          v-if="phaseSource(p.phase)"
                          :content="phaseSource(p.phase).tip"
                          placement="top"
                          :show-after="300"
                        >
                          <span class="phase-src" :class="phaseSource(p.phase).kind">{{ phaseSource(p.phase).label }}</span>
                        </el-tooltip>
                      </span>
                      <span class="phase-time">{{ p.elapsedMs ?? 0 }}ms</span>
                    </div>
                    <el-tooltip
                      v-if="p.content"
                      :content="p.content"
                      placement="top"
                      :disabled="String(p.content).length <= 80"
                      :show-after="300"
                      popper-class="phase-content-tip"
                    >
                      <div class="phase-content" :class="phaseContentClass(p)">{{ truncate(p.content, 80) }}</div>
                    </el-tooltip>
                  </div>
                  <div v-if="showReasoningThinking(msg, idx)" class="thinking-hint" aria-live="polite">
                    <span class="thinking-label">thinking</span>
                    <span class="thinking-dots" aria-hidden="true">
                      <i /><i /><i />
                    </span>
                  </div>
                </div>
              </div>
            </section>

            <div
              v-if="hasReasonSection(msg, idx) && hasResultSection(msg)"
              class="ai-card-divider"
              aria-hidden="true"
            />

            <!-- 区域 2：查询结果 -->
            <section
              v-if="hasResultSection(msg)"
              class="ai-card-block section-result"
              :class="{ 'is-fail': !!msg.error }"
            >
              <div class="card-head result-head" :class="{ fail: !!msg.error }">
                <el-icon v-if="msg.error" class="result-icon fail"><CircleCloseFilled /></el-icon>
                <el-icon v-else class="result-icon"><DataLine /></el-icon>
                <span class="card-title">{{ msg.error ? '查询失败' : '查询结果' }}</span>
                <template v-if="msg.error">
                  <span class="card-sep">·</span>
                  <span class="card-meta fail-brief" :title="msg.error">{{ truncate(msg.error, 48) }}</span>
                </template>
                <template v-else>
                  <span class="card-sep">·</span>
                  <span class="card-meta">{{ msg.rowCount ?? 0 }} 行</span>
                  <template v-if="msg.elapsedMs != null">
                    <span class="card-sep">·</span>
                    <span class="card-meta">{{ formatDuration(msg.elapsedMs) }}</span>
                  </template>
                  <template v-if="msg.truncated">
                    <span class="card-sep">·</span>
                    <span class="card-meta trunc">已截断</span>
                  </template>
                </template>
              </div>

              <div v-if="msg.error" class="err">{{ msg.error }}</div>

              <div v-if="msg.sql" class="sql-block" :class="{ 'sql-fail': !!msg.error }">
                <div class="sql-head">
                  <span class="sql-title">{{ msg.scriptMode ? 'SQL 脚本' : 'SQL' }}</span>
                  <div class="btns">
                    <el-tooltip content="复制" placement="top">
                      <el-button text :icon="CopyDocument" @click="copySql(msg.sql)" />
                    </el-tooltip>
                    <el-tooltip content="在编辑器中打开" placement="top">
                      <el-button text :icon="EditPen" @click="openInEditor(msg.sql)" />
                    </el-tooltip>
                    <el-tooltip content="保存到脚本库" placement="top">
                      <el-button text :icon="FolderAdd" @click="openSaveScript(msg.sql)" />
                    </el-tooltip>
                    <span v-if="!msg.error" class="page-size-label">每页</span>
                    <el-radio-group
                      v-if="!msg.error"
                      v-model="resultPageSize"
                      size="small"
                      class="page-size-switch"
                    >
                      <el-radio-button :value="10">10</el-radio-button>
                      <el-radio-button :value="20">20</el-radio-button>
                      <el-radio-button :value="50">50</el-radio-button>
                    </el-radio-group>
                  </div>
                </div>
                <div class="sql-code">
                  <span class="sql-corner">SQL</span>
                  <div
                    v-for="(line, li) in highlightedSqlLines(msg.sql)"
                    :key="li"
                    class="sql-line"
                  >
                    <span class="ln">{{ li + 1 }}</span>
                    <code v-html="line"></code>
                  </div>
                </div>
              </div>
              <el-collapse
                v-if="!msg.error && msg.scriptMode && msg.scriptSteps?.length"
                class="script-steps"
              >
                <el-collapse-item title="中间步骤" name="steps">
                  <div
                    v-for="(s, si) in msg.scriptSteps"
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
                    <pre class="step-sql">{{ s.sql }}</pre>
                  </div>
                </el-collapse-item>
              </el-collapse>
              <div v-if="!msg.error && msg.columns?.length" class="table-block">
                <el-table
                  :data="pagedRows(msg)"
                  size="small"
                  class="result-table"
                  max-height="280"
                  style="width: 100%"
                >
                  <el-table-column
                    v-for="col in msg.columns"
                    :key="col"
                    :prop="col"
                    :label="col"
                    min-width="100"
                    show-overflow-tooltip
                    :align="isNumericColumn(col, msg.rows) ? 'right' : 'left'"
                  />
                </el-table>
                <el-pagination
                  v-if="(msg.rows?.length || 0) > resultPageSize"
                  class="result-pager"
                  small
                  background
                  layout="total, prev, pager, next"
                  :page-size="resultPageSize"
                  :current-page="msg.page || 1"
                  :total="msg.rows?.length || 0"
                  @current-change="(p) => onResultPageChange(msg, p)"
                />
              </div>
              <div
                v-if="canShowFeedback(msg)"
                class="result-feedback"
              >
                <span class="feedback-label">这个结果对你有帮助吗？</span>
                <button
                  type="button"
                  class="feedback-btn"
                  :class="{ active: msg.feedback === 'USEFUL' }"
                  :disabled="!!msg.feedback || feedbackBusy === idx"
                  @click="onResultFeedback(msg, 'USEFUL')"
                >👍 有用</button>
                <button
                  type="button"
                  class="feedback-btn"
                  :class="{ active: msg.feedback === 'USELESS' }"
                  :disabled="!!msg.feedback || feedbackBusy === idx"
                  @click="onResultFeedback(msg, 'USELESS')"
                >👎 没用</button>
              </div>
            </section>
          </div>
        </div>
      </div>
    </div>

    <div class="composer">
      <div v-if="contextLabel" class="composer-context">{{ contextLabel }}</div>
      <div class="input-wrapper" :class="{ focused: composerFocus, disabled: streaming }">
        <textarea
          ref="composerRef"
          v-model="input"
          class="input-textarea"
          rows="1"
          :disabled="streaming"
          placeholder="输入问题..."
          @input="resizeComposer"
          @keydown="onKeydown"
          @focus="composerFocus = true"
          @blur="composerFocus = false"
        />
        <div class="input-footer">
          <span class="hint">Enter 发送 · Shift+Enter 换行</span>
          <el-button type="primary" size="small" :disabled="!canSend" :loading="streaming" @click="send">发送</el-button>
        </div>
      </div>
      <div class="composer-tools">
        <div class="tool-left">
          <el-button text size="small" :disabled="streaming" @click="clearChat">清空对话</el-button>
          <el-dropdown trigger="click" :disabled="streaming" @command="insertSql">
            <el-button text size="small" :disabled="streaming">插入常用SQL</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-for="item in SQL_SNIPPETS" :key="item.label" :command="item.sql">
                  {{ item.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <span class="char-count" :class="{ over: input.length > INPUT_MAX }">{{ input.length }} / {{ INPUT_MAX }}</span>
      </div>
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
        <el-button type="primary" :loading="saving" @click="confirmSaveScript">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCloseFilled, Coin, CopyDocument, DataLine, EditPen, FolderAdd } from '@element-plus/icons-vue'
import { listInstanceDatabases } from '../../api/datasource'
import { submitRelationFeedback } from '../../api/relation'
import { getSettings } from '../../api/settings'
import { streamQuery } from '../../api/text2sql'
import { saveScript } from '../../api/script'
import { getSession, renameSession, frequentQuestions } from '../../api/session'
import { useAppStore } from '../../stores/app'
import { useTabsStore } from '../../stores/tabs'

const props = defineProps({
  tab: { type: Object, required: true }
})

const appStore = useAppStore()
const tabsStore = useTabsStore()

/** AI 对话结果表分页：超过此数量才显示分页，每页条数 */
const resultPageSize = ref(10)

const INPUT_MAX = 2000
const SQL_SNIPPETS = [
  { label: '查询前 100 行', sql: 'SELECT * FROM  LIMIT 100' },
  { label: '按字段计数', sql: 'SELECT  , COUNT(*) AS cnt\nFROM  \nGROUP BY  \nORDER BY cnt DESC' },
  { label: '按条件筛选', sql: 'SELECT * FROM  WHERE  ' }
]

const sessionId = ref(props.tab?.props?.sessionId || '')
const sessionTitle = ref(props.tab?.title?.replace(/^AI会话:\s*/, '') || '新会话')
const localDsId = ref(
  props.tab?.props?.datasourceId || props.tab?.datasourceId || appStore.currentDatasourceId || ''
)
const localDb = ref(props.tab?.props?.dbName || appStore.currentDatabase || '')
const boundDsId = ref(localDsId.value)
const boundDb = ref(localDb.value)
const dbOptions = ref([])
const dbLoading = ref(false)
const applyingBinding = ref(false)
const input = ref(props.tab?.props?.draft || '')
const composerRef = ref(null)
const composerFocus = ref(false)
const reasonOpenMap = ref({})
const streaming = ref(false)
const loadingSession = ref(false)
const feedbackBusy = ref(-1)
const messages = ref([])
const scrollRef = ref(null)

const saveVisible = ref(false)
const saveName = ref('')
const saveSql = ref('')
const saving = ref(false)

const currentDs = computed(() => appStore.datasources.find((d) => d.id === localDsId.value) || null)
const canSend = computed(
  () =>
    !!localDsId.value
    && !!localDb.value
    && !!input.value.trim()
    && input.value.length <= INPUT_MAX
    && !streaming.value
)
const headerLocked = computed(() => streaming.value || loadingSession.value)
const contextLabel = computed(() => {
  if (!currentDs.value) return ''
  return localDb.value
    ? `当前数据源：${currentDs.value.name} · ${localDb.value}`
    : `当前数据源：${currentDs.value.name}`
})
const saveDsLabel = computed(() => {
  const d = currentDs.value
  if (!d) return '未选择'
  return `${d.name} (${dialectOf(d)})`
})

const FALLBACK_EXAMPLES = [
  '查最近的订单',
  '每个部门有多少人',
  '上个月销售额 Top10',
  '统计每个用户的订单数'
]
const exampleQuestions = ref([...FALLBACK_EXAMPLES])

async function loadExampleQuestions() {
  const id = appStore.currentDatasourceId || localDsId.value
  if (!id) {
    exampleQuestions.value = [...FALLBACK_EXAMPLES]
    return
  }
  try {
    const res = await frequentQuestions(id, 5)
    const list = Array.isArray(res?.questions) ? res.questions.map((q) => String(q || '').trim()).filter(Boolean) : []
    exampleQuestions.value = list.length ? list : [...FALLBACK_EXAMPLES]
  } catch {
    exampleQuestions.value = [...FALLBACK_EXAMPLES]
  }
}

async function askExample(q) {
  if (!localDsId.value || streaming.value) return
  input.value = q
  await send()
}

function dialectOf(d) {
  const t = (d?.type || '').toString().toUpperCase()
  return t.includes('POSTGRES') ? 'PostgreSQL' : 'MySQL'
}

function dsKind(d) {
  return dialectOf(d) === 'PostgreSQL' ? 'postgres' : 'mysql'
}

function reasonState(msg, idx) {
  if (msg?.error) return 'fail'
  if (showReasoningThinking(msg, idx) || msg?.phases?.some((p) => p.typing)) return 'running'
  if (streaming.value && idx === messages.value.length - 1 && msg?.pending && !msg.sql) return 'running'
  return 'done'
}

function reasonStateText(msg, idx) {
  const state = reasonState(msg, idx)
  if (state === 'running') return '执行中'
  if (state === 'fail') return '失败'
  return '已完成'
}

function isReasonOpen(msg, idx) {
  const saved = reasonOpenMap.value[idx]
  if (typeof saved === 'boolean') return saved
  return reasonState(msg, idx) === 'running'
}

function toggleReason(msg, idx) {
  reasonOpenMap.value = {
    ...reasonOpenMap.value,
    [idx]: !isReasonOpen(msg, idx)
  }
}

function reasonTotalMs(msg) {
  return (msg?.phases || []).reduce((sum, p) => sum + (Number(p?.elapsedMs) || 0), 0)
}

function formatDuration(ms) {
  const n = Number(ms)
  if (!Number.isFinite(n)) return '-'
  if (n < 1000) return `${Math.round(n)}ms`
  return `${(n / 1000).toFixed(1)}s`
}

function hasReasonSection(msg, idx) {
  return !!(msg?.phases?.length || showReasoningThinking(msg, idx))
}

function hasResultSection(msg) {
  return !!(msg?.error || msg?.sql || msg?.columns?.length)
}

/** 有 SQL 且执行成功（有结果列、无错误）才显示反馈 */
function canShowFeedback(msg) {
  if (!msg || msg.role !== 'assistant' || msg.pending) return false
  if (!msg.sql || msg.error) return false
  return Array.isArray(msg.columns) && msg.columns.length > 0
}

async function onResultFeedback(msg, feedback) {
  if (!msg || msg.feedback || !canShowFeedback(msg)) return
  const sid = sessionId.value
  if (!sid || !msg.id) {
    ElMessage.warning('会话尚未保存完成，请稍后再反馈')
    return
  }
  const idx = messages.value.indexOf(msg)
  feedbackBusy.value = idx
  try {
    await submitRelationFeedback({
      sessionId: sid,
      messageId: msg.id,
      sql: msg.sql,
      feedback
    })
    msg.feedback = feedback
    ElMessage.success(feedback === 'USEFUL' ? '已标记为有用' : '已标记为没用')
  } catch {
    /* 拦截器已提示 */
  } finally {
    feedbackBusy.value = -1
  }
}

function phaseMark(p) {
  if (p?.typing) return 'run'
  const text = `${p?.phase || ''} ${p?.content || ''}`
  if (/失败|错误/.test(text)) return 'fail'
  if (/跳过/.test(text)) return 'skip'
  return 'ok'
}

function phaseIcon(p) {
  const mark = phaseMark(p)
  if (mark === 'fail') return '✗'
  if (mark === 'skip') return '○'
  if (mark === 'run') return '…'
  return '✓'
}

/** 标签只按阶段名映射，不改推理结果。执行落到业务库，标成数据库。 */
const PHASE_SOURCE = {
  理解问题: { kind: 'local', label: '本地', tip: 'QueryComplexityAnalyzer' },
  检索示例: { kind: 'local', label: '本地', tip: 'FewShotService' },
  安全校验: { kind: 'local', label: '本地', tip: 'SqlGuard' },
  脚本模式: { kind: 'local', label: '本地', tip: 'Text2SqlService' },
  改写问题: { kind: 'ai', label: 'AI', tip: 'ai' },
  生成SQL: { kind: 'ai', label: 'AI', tip: 'ai' },
  '生成SQL(重试)': { kind: 'ai', label: 'AI', tip: 'ai' },
  '生成SQL（重试）': { kind: 'ai', label: 'AI', tip: 'ai' },
  检索Schema: { kind: 'vector', label: '向量', tip: 'Milvus + ONNX BGE' },
  执行: { kind: 'db', label: '数据库', tip: 'db' }
}

const llmSourceTip = ref('DeepSeek')

const boundDialect = computed(() => {
  const id = localDsId.value || appStore.currentDatasourceId
  const ds = (appStore.datasources || []).find((d) => d.id === id)
  return dialectOf(ds)
})

function phaseSource(phase) {
  const key = String(phase || '').replace(/\s+/g, '')
  const hit = PHASE_SOURCE[key]
  if (!hit) return null
  let tip = hit.tip
  if (tip === 'ai') tip = llmSourceTip.value
  if (tip === 'db') tip = boundDialect.value
  return { kind: hit.kind, label: hit.label, tip }
}

function truncate(text, max = 80) {
  const s = text == null ? '' : String(text).replace(/\s+/g, ' ').trim()
  if (s.length <= max) return s
  return `${s.slice(0, max)}...`
}

function phaseContentClass(p) {
  const phase = p?.phase || ''
  if (phase === '生成SQL' || phase === '生成SQL(重试)') return 'sql-preview'
  if (phase === '执行') return 'exec-result'
  if (phase === '安全校验' && /不通过|失败|危险|已拦截/.test(String(p?.content || ''))) return 'check-fail'
  return ''
}

const SQL_KEYWORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'FULL', 'CROSS',
  'ON', 'AND', 'OR', 'AS', 'LIMIT', 'OFFSET', 'HAVING', 'UNION', 'INSERT', 'UPDATE',
  'DELETE', 'CREATE', 'WITH', 'DISTINCT', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
  'IN', 'IS', 'NOT', 'NULL', 'BY', 'ASC', 'DESC', 'GROUP', 'ORDER', 'INTO', 'VALUES',
  'SET', 'EXISTS', 'BETWEEN', 'LIKE', 'ALL'
])

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function highlightSqlLine(line) {
  let html = ''
  let i = 0
  while (i < line.length) {
    const ch = line[i]
    if (ch === "'" || ch === '"') {
      const quote = ch
      let j = i + 1
      while (j < line.length) {
        if (line[j] === quote) {
          j += 1
          break
        }
        j += 1
      }
      html += `<span class="tok-str">${escapeHtml(line.slice(i, j))}</span>`
      i = j
      continue
    }
    if (ch === '-' && line[i + 1] === '-') {
      html += `<span class="tok-cmt">${escapeHtml(line.slice(i))}</span>`
      break
    }
    if (ch === '/' && line[i + 1] === '*') {
      const end = line.indexOf('*/', i + 2)
      if (end < 0) {
        html += `<span class="tok-cmt">${escapeHtml(line.slice(i))}</span>`
        break
      }
      html += `<span class="tok-cmt">${escapeHtml(line.slice(i, end + 2))}</span>`
      i = end + 2
      continue
    }
    if (/[0-9]/.test(ch) && (i === 0 || !/[A-Za-z_]/.test(line[i - 1]))) {
      let j = i + 1
      while (j < line.length && /[0-9.]/.test(line[j])) j += 1
      html += `<span class="tok-num">${escapeHtml(line.slice(i, j))}</span>`
      i = j
      continue
    }
    if (/[A-Za-z_]/.test(ch)) {
      let j = i + 1
      while (j < line.length && /[A-Za-z0-9_]/.test(line[j])) j += 1
      const word = line.slice(i, j)
      const upper = word.toUpperCase()
      let n = j
      while (n < line.length && /\s/.test(line[n])) n += 1
      let m = n
      while (m < line.length && /[A-Za-z]/.test(line[m])) m += 1
      const next = line.slice(n, m).toUpperCase()
      if ((upper === 'GROUP' || upper === 'ORDER') && next === 'BY') {
        html += `<span class="tok-kw">${escapeHtml(line.slice(i, m))}</span>`
        i = m
        continue
      }
      let p = j
      while (p < line.length && /\s/.test(line[p])) p += 1
      if (line[p] === '(' && !SQL_KEYWORDS.has(upper)) {
        html += `<span class="tok-fn">${escapeHtml(word)}</span>`
      } else if (SQL_KEYWORDS.has(upper)) {
        html += `<span class="tok-kw">${escapeHtml(word)}</span>`
      } else {
        html += escapeHtml(word)
      }
      i = j
      continue
    }
    html += escapeHtml(ch)
    i += 1
  }
  return html || '&nbsp;'
}

function highlightedSqlLines(sql) {
  const text = sql == null ? '' : String(sql)
  const lines = text.split('\n')
  return lines.length ? lines.map(highlightSqlLine) : ['&nbsp;']
}

function isNumericColumn(col, rows) {
  const name = String(col || '').toLowerCase()
  if (/amount|count|total|price|qty|num|数量|金额|单价|合计|耗时/.test(name)) return true
  const sample = (rows || [])
    .slice(0, 8)
    .map((row) => row?.[col])
    .filter((value) => value != null && value !== '')
  if (!sample.length) return false
  return sample.every((value) => typeof value === 'number' || /^-?\d+(\.\d+)?$/.test(String(value).trim()))
}

/** 流式进行中、尚无最终结果，且当前没有打字机在跑 → 显示 thinking 等待动画 */
function showReasoningThinking(msg, idx) {
  if (!streaming.value || !msg || msg.role !== 'assistant') return false
  if (idx !== messages.value.length - 1) return false
  if (msg.summary || msg.error || msg.sql || (msg.columns && msg.columns.length)) return false
  if (msg.phases?.some((p) => p.typing)) return false
  return true
}

function syncTabMeta() {
  if (!props.tab?.id) return
  tabsStore.updateTab(props.tab.id, {
    title: `AI会话: ${sessionTitle.value || '新会话'}`,
    props: {
      ...(props.tab.props || {}),
      sessionId: sessionId.value || null,
      datasourceId: localDsId.value || null,
      dbName: localDb.value || ''
    }
  })
}

function pagedRows(msg) {
  const rows = msg?.rows || []
  const page = Math.max(1, msg?.page || 1)
  const size = resultPageSize.value || 10
  const start = (page - 1) * size
  return rows.slice(start, start + size)
}

function onResultPageChange(msg, page) {
  if (!msg) return
  msg.page = page
}

watch(resultPageSize, () => {
  for (const msg of messages.value) {
    if (msg?.rows?.length) {
      msg.page = 1
    }
  }
})

function needsNewSession() {
  return messages.value.length > 0 || !!sessionId.value
}

async function loadDbOptions(dsId, pickFirstIfMissing = false) {
  if (!dsId) {
    dbOptions.value = []
    return
  }
  dbLoading.value = true
  applyingBinding.value = true
  try {
    const names = await listInstanceDatabases(dsId)
    dbOptions.value = Array.isArray(names) ? names : []
    if (localDb.value && dbOptions.value.includes(localDb.value)) return
    if (pickFirstIfMissing && dbOptions.value.length) {
      const first = dbOptions.value[0]
      boundDb.value = first
      localDb.value = first
      appStore.setCurrentDatabase(first)
    }
  } catch {
    dbOptions.value = localDb.value ? [localDb.value] : []
  } finally {
    dbLoading.value = false
    await nextTick()
    applyingBinding.value = false
  }
}

function openBoundChat(dsId, db) {
  appStore.setCurrentDatasource(dsId)
  appStore.setCurrentDatabase(db)
  appStore.setCurrentSessionId(null)
  tabsStore.openAiChat({
    datasourceId: dsId,
    dbName: db,
    title: 'AI会话: 新会话'
  })
}

async function onPickDatasource(nextId) {
  if (applyingBinding.value) return
  const prev = boundDsId.value
  if (!nextId || nextId === prev) {
    localDsId.value = prev
    return
  }
  if (!needsNewSession()) {
    boundDsId.value = nextId
    localDb.value = ''
    boundDb.value = ''
    appStore.setCurrentDatasource(nextId)
    await loadDbOptions(nextId, true)
    syncTabMeta()
    return
  }
  try {
    await ElMessageBox.confirm('是否新建会话？当前会话会保留。', '切换数据源', {
      confirmButtonText: '是',
      cancelButtonText: '否',
      type: 'warning'
    })
    const names = await listInstanceDatabases(nextId)
    const list = Array.isArray(names) ? names : []
    const db = list.includes(boundDb.value) ? boundDb.value : list[0]
    if (!db) {
      ElMessage.warning('该数据源下没有可用的库')
    } else {
      openBoundChat(nextId, db)
    }
  } catch {
    // 取消则回到原来的数据源
  }
  localDsId.value = prev
}

async function onPickDatabase(next) {
  if (applyingBinding.value) return
  const prev = boundDb.value
  if (!next || next === prev) {
    localDb.value = prev
    return
  }
  if (!needsNewSession()) {
    boundDb.value = next
    appStore.setCurrentDatabase(next)
    syncTabMeta()
    return
  }
  try {
    await ElMessageBox.confirm('是否新建会话？当前会话会保留。', '切换数据库', {
      confirmButtonText: '是',
      cancelButtonText: '否',
      type: 'warning'
    })
    openBoundChat(boundDsId.value || localDsId.value, next)
  } catch {
    // 取消则回到原来的库
  }
  localDb.value = prev
}

function restoreBinding() {
  if (localDsId.value && appStore.currentDatasourceId !== localDsId.value) {
    appStore.setCurrentDatasource(localDsId.value)
  }
  if (localDb.value) appStore.setCurrentDatabase(localDb.value)
  appStore.setCurrentSessionId(sessionId.value || null)
}

function resizeComposer() {
  const el = composerRef.value
  if (!el) return
  const line = 22
  const max = line * 6
  el.style.height = 'auto'
  const next = Math.min(max, Math.max(line, el.scrollHeight))
  el.style.height = `${next}px`
  el.style.overflowY = el.scrollHeight > max ? 'auto' : 'hidden'
}

function insertSql(sql) {
  const el = composerRef.value
  const cur = input.value || ''
  const text = String(sql || '')
  if (!el) {
    input.value = cur + text
  } else {
    const start = el.selectionStart ?? cur.length
    const end = el.selectionEnd ?? start
    input.value = cur.slice(0, start) + text + cur.slice(end)
  }
  nextTick(() => {
    resizeComposer()
    composerRef.value?.focus?.()
  })
}

async function onRenameTitle() {
  try {
    const { value } = await ElMessageBox.prompt('请输入会话标题', '重命名会话', {
      inputValue: sessionTitle.value || '新会话',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValidator: (v) => !!(v && v.trim()) || '标题不能为空'
    })
    const title = value.trim()
    if (!sessionId.value) {
      sessionTitle.value = title
      syncTabMeta()
      ElMessage.success('已重命名')
      return
    }
    await renameSession(sessionId.value, title)
    sessionTitle.value = title
    syncTabMeta()
    appStore.bumpSessionRefresh()
    ElMessage.success('已重命名')
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e?.response?.data?.message || e?.message || '重命名失败')
  }
}

function clearChat() {
  if (streaming.value) return
  messages.value = []
  sessionId.value = ''
  sessionTitle.value = '新会话'
  appStore.setCurrentSessionId(null)
  syncTabMeta()
}

function mapHistoryMessages(list) {
  const out = []
  for (const m of list || []) {
    if (!m) continue
    if (m.role === 'user') {
      out.push({ role: 'user', content: m.content || '' })
      continue
    }
    const ai = {
      role: 'assistant',
      summary: m.content || '',
      phases: [],
      id: m.id || '',
      sql: m.sqlText || '',
      columns: [],
      rows: [],
      rowCount: 0,
      truncated: false,
      elapsedMs: null,
      error: '',
      pending: false,
      page: 1,
      feedback: m.userFeedback || ''
    }
    if (m.reasoningJson) {
      try {
        const steps = JSON.parse(m.reasoningJson)
        if (Array.isArray(steps)) {
          ai.phases = steps.map((s) => {
            const elapsed = s.elapsedMs ?? 0
            const full = `【${s.phase}】${s.content || ''} (${elapsed}ms)`
            return {
              phase: s.phase,
              content: s.content,
              elapsedMs: elapsed,
              displayText: full,
              typing: false
            }
          })
        }
      } catch {
        // ignore
      }
    }
    if (m.resultJson) {
      try {
        const snap = JSON.parse(m.resultJson)
        ai.sql = snap.sql || ai.sql
        ai.columns = snap.columns || []
        ai.rows = snap.rows || []
        ai.rowCount = snap.rowCount ?? ai.rows.length
        ai.truncated = !!snap.truncated
        ai.elapsedMs = snap.elapsedMs ?? null
        if (snap.success === false && snap.error) {
          ai.error = snap.error
        }
      } catch {
        // ignore
      }
    }
    if (m.sqlStatus === 'failed' || m.sqlStatus === 'blocked') {
      if (!ai.error && m.content) {
        ai.error = m.content
      }
    }
    out.push(ai)
  }
  return out
}

async function loadExistingSession(id) {
  loadingSession.value = true
  try {
    const detail = await getSession(id)
    sessionId.value = detail.id
    sessionTitle.value = detail.title || '新会话'
    if (detail.datasourceId) {
      localDsId.value = detail.datasourceId
      appStore.setCurrentDatasource(detail.datasourceId)
    }
    // 会话绑定的是当时那个库。只恢复数据源时，连接上若没写默认库，再提问会被要求重选。
    if (detail.dbName) {
      localDb.value = detail.dbName
      boundDb.value = detail.dbName
      appStore.setCurrentDatabase(detail.dbName)
    }
    boundDsId.value = localDsId.value
    await loadDbOptions(localDsId.value, !detail.dbName)
    if (detail.dbName) {
      localDb.value = detail.dbName
      boundDb.value = detail.dbName
    } else {
      boundDb.value = localDb.value
    }
    messages.value = mapHistoryMessages(detail.messages)
    appStore.setCurrentSessionId(sessionId.value)
    syncTabMeta()
    await scrollBottom()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '加载会话失败')
  } finally {
    loadingSession.value = false
  }
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

async function scrollBottom() {
  await nextTick()
  const el = scrollRef.value
  if (el) el.scrollTop = el.scrollHeight
}

function copySql(sql) {
  navigator.clipboard?.writeText(sql).then(
    () => ElMessage.success('已复制'),
    () => ElMessage.warning('复制失败')
  )
}

function openInEditor(sql) {
  if (!sql) {
    ElMessage.warning('没有可打开的 SQL')
    return
  }
  tabsStore.openQueryEditor({
    initialSql: sql,
    initialDatasourceId: localDsId.value || null,
    title: '查询编辑器'
  })
}

function defaultScriptName() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const stamp =
    `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `-${pad(d.getHours())}${pad(d.getMinutes())}`
  return `查询-${stamp}`
}

function openSaveScript(sql) {
  if (!sql) {
    ElMessage.warning('没有可保存的 SQL')
    return
  }
  saveSql.value = sql
  saveName.value = defaultScriptName()
  saveVisible.value = true
}

async function confirmSaveScript() {
  const name = saveName.value.trim()
  if (!name) {
    ElMessage.warning('请输入脚本名称')
    return
  }
  saving.value = true
  try {
    const ds = currentDs.value
    await saveScript({
      name,
      content: saveSql.value,
      datasourceId: localDsId.value || null,
      dbName: appStore.currentDatabase || ds?.database || null,
      source: 'AI_GENERATED'
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

function phaseFullText(phase, content, elapsedMs) {
  return `【${phase || ''}】${content || ''} (${elapsedMs ?? 0}ms)`
}

function scriptLines(sql) {
  if (!sql) return []
  // 按分号分段，保留段内 -- 注释与换行
  const parts = []
  let cur = ''
  let inSingle = false
  let inDouble = false
  let inTick = false
  const s = String(sql)
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    const next = s[i + 1] || ''
    if (!inDouble && !inTick && c === "'" && !inSingle) {
      inSingle = true
      cur += c
      continue
    }
    if (inSingle) {
      cur += c
      if (c === "'" && next === "'") {
        cur += next
        i++
      } else if (c === "'") {
        inSingle = false
      }
      continue
    }
    if (!inSingle && !inTick && c === '"' && !inDouble) {
      inDouble = true
      cur += c
      continue
    }
    if (inDouble) {
      cur += c
      if (c === '"') inDouble = false
      continue
    }
    if (!inSingle && !inDouble && c === '`' && !inTick) {
      inTick = true
      cur += c
      continue
    }
    if (inTick) {
      cur += c
      if (c === '`') inTick = false
      continue
    }
    if (c === ';') {
      const part = cur.trim()
      if (part) parts.push(part.endsWith(';') ? part : part + ';')
      cur = ''
      continue
    }
    cur += c
  }
  const tail = cur.trim()
  if (tail) parts.push(tail.endsWith(';') ? tail : tail + ';')
  return parts
}

/** 打字机：按字吐出；偏快，避免结果已出推理还在拖 */
function typewriter(target, fullText, onTick) {
  return new Promise((resolve) => {
    const text = fullText || ''
    if (!text) {
      target.displayText = ''
      target.typing = false
      resolve()
      return
    }
    target.displayText = ''
    target.typing = true
    let i = 0
    const step = () => {
      const remain = text.length - i
      // 每次多吐几字，总时长压到大约 0.3~0.8s / 行
      const n =
        text.length > 100 ? 8 : text.length > 50 ? 5 : text.length > 20 ? 3 : 2
      i = Math.min(text.length, i + n)
      target.displayText = text.slice(0, i)
      onTick?.()
      if (i >= text.length) {
        target.typing = false
        resolve()
        return
      }
      const delay = remain < 12 ? 12 : 8
      setTimeout(step, delay)
    }
    step()
  })
}

async function send() {
  if (!canSend.value) return
  const database = localDb.value
  if (!database) {
    ElMessage.warning('请先选择数据库')
    return
  }
  const question = input.value.trim()
  const dsId = localDsId.value
  input.value = ''
  nextTick(() => resizeComposer())
  const sid = sessionId.value || ''

  messages.value.push({ role: 'user', content: question })
  messages.value.push({
    role: 'assistant',
    id: '',
    summary: '',
    phases: [],
    sql: '',
    columns: [],
    rows: [],
    rowCount: 0,
    truncated: false,
    elapsedMs: null,
    error: '',
    pending: true,
    scriptMode: false,
    scriptSteps: [],
    page: 1,
    feedback: ''
  })
  // 必须用数组里的响应式代理，不能用 push 前的普通对象，否则 phases 追加不触发视图更新
  const aiMsg = messages.value[messages.value.length - 1]
  streaming.value = true
  await scrollBottom()

  // 打字机串行队列：上一行打完再打下一条
  let typeChain = Promise.resolve()

  await streamQuery(
    {
      datasourceId: dsId,
      question,
      topK: 5,
      sessionId: sid || undefined,
      dbName: database,
      title: sessionTitle.value && sessionTitle.value !== '新会话' ? sessionTitle.value : undefined
    },
    {
      onPhase: (data) => {
        const elapsed = data.elapsedMs ?? 0
        const full = phaseFullText(data.phase, data.content, elapsed)
        // 轮到这一行再插入，避免先空行再填字
        typeChain = typeChain
          .then(async () => {
            aiMsg.phases.push({
              phase: data.phase,
              content: data.content,
              elapsedMs: elapsed,
              displayText: '',
              typing: true
            })
            const phase = aiMsg.phases[aiMsg.phases.length - 1]
            await typewriter(phase, full, () => scrollBottom())
          })
          .catch(() => {})
      },
      onResult: (data) => {
        // 先缓存结果，等推理打字机全部结束后再一次性展示
        const pendingResult = {
          sql: data.sql || '',
          columns: data.columns || [],
          rows: data.rows || [],
          rowCount: data.rowCount ?? 0,
          truncated: !!data.truncated,
          elapsedMs: data.elapsedMs ?? null,
          scriptMode: !!data.scriptMode,
          scriptSteps: Array.isArray(data.steps) ? data.steps : [],
          error: data.success === false && data.error ? data.error : '',
          summary:
            data.success === false && data.error
              ? '生成失败'
              : data.scriptMode
                ? `脚本模式，返回 ${data.rowCount ?? 0} 行`
                : `已生成 SQL，返回 ${data.rowCount ?? 0} 行`
        }
        typeChain = typeChain.then(() => {
          aiMsg.sql = pendingResult.sql
          aiMsg.columns = pendingResult.columns
          aiMsg.rows = pendingResult.rows
          aiMsg.rowCount = pendingResult.rowCount
          aiMsg.truncated = pendingResult.truncated
          aiMsg.elapsedMs = pendingResult.elapsedMs
          aiMsg.scriptMode = pendingResult.scriptMode
          aiMsg.scriptSteps = pendingResult.scriptSteps
          aiMsg.error = pendingResult.error
          aiMsg.summary = pendingResult.summary
          aiMsg.page = 1
          aiMsg.pending = false
          appStore.setQueryStats({
            rowCount: aiMsg.rowCount,
            elapsedMs: aiMsg.elapsedMs || 0
          })
          scrollBottom()
        })
      },
      onError: (data) => {
        const msg = data?.message || '查询失败'
        typeChain = typeChain.then(() => {
          aiMsg.pending = false
          aiMsg.error = msg
          aiMsg.summary = '生成失败'
          scrollBottom()
        })
      },
      onDone: async (data) => {
        await typeChain
        aiMsg.pending = false
        streaming.value = false
        const savedId = data?.sessionId || sid
        if (data?.messageId) {
          aiMsg.id = data.messageId
        }
        if (savedId && savedId !== sessionId.value) {
          sessionId.value = savedId
          appStore.setCurrentSessionId(savedId)
          syncTabMeta()
        }
        if (savedId) {
          try {
            const detail = await getSession(savedId)
            if (detail?.title) {
              sessionTitle.value = detail.title
              syncTabMeta()
            }
            if (!aiMsg.id && Array.isArray(detail?.messages)) {
              const lastAi = [...detail.messages].reverse().find((m) => m.role === 'assistant')
              if (lastAi?.id) aiMsg.id = lastAi.id
            }
          } catch {
            // ignore
          }
        }
        appStore.bumpSessionRefresh()
        scrollBottom()
      },
      onFail: (err) => {
        const msg = err?.message || String(err)
        typeChain = typeChain.then(() => {
          aiMsg.pending = false
          aiMsg.error = msg
          aiMsg.summary = '生成失败'
          streaming.value = false
          scrollBottom()
        })
      }
    }
  )
}

onMounted(async () => {
  getSettings()
    .then((s) => {
      llmSourceTip.value = s?.['llm.provider'] === 'OPENAI_COMPATIBLE' ? 'OpenAI 兼容' : 'DeepSeek'
    })
    .catch(() => {})
  if (!appStore.datasources.length) {
    try {
      await appStore.loadDatasources()
    } catch {
      // ignore
    }
  }
  const existingId = props.tab?.props?.sessionId
  if (existingId) {
    await loadExistingSession(existingId)
  } else if (localDsId.value) {
    boundDsId.value = localDsId.value
    await loadDbOptions(localDsId.value, !localDb.value)
    boundDb.value = localDb.value
  }
  await nextTick()
  resizeComposer()
  const draft = props.tab?.props?.draft
  if (draft) {
    input.value = draft
    await nextTick()
    if (props.tab?.props?.autoSend) {
      await send()
    } else {
      composerRef.value?.focus?.()
    }
  }
  loadExampleQuestions()
})

watch(() => appStore.currentDatasourceId, () => {
  loadExampleQuestions()
})

watch(
  () => props.tab?.props?.datasourceId || props.tab?.datasourceId,
  (id) => {
    if (id && id !== localDsId.value) localDsId.value = id
  }
)

watch(
  () => props.tab?.props?.sessionId,
  async (id) => {
    if (id && id !== sessionId.value) {
      await loadExistingSession(id)
    }
  }
)

watch(() => tabsStore.activeId, (id) => {
  if (id === props.tab?.id) restoreBinding()
})

watch(input, () => nextTick(() => resizeComposer()))
</script>

<style scoped>
.ai-chat {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.chat-header {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: var(--sk-bg-elevated);
  border-bottom: 1px solid var(--sk-border, var(--sk-border));
  gap: 12px;
}
.chat-header .left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.title {
  font-weight: 600;
  font-size: 14px;
  color: var(--sk-text-primary, var(--sk-text-primary));
}
.title.clickable {
  cursor: pointer;
  border-bottom: 1px dashed transparent;
}
.title.clickable:hover {
  color: var(--sk-primary);
  border-bottom-color: var(--sk-primary);
}
.ds-wrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.header-select {
  width: 160px;
}
.sep {
  color: var(--sk-text-tertiary);
  font-size: 12px;
}
.ds-type-icon {
  font-size: 14px;
  color: var(--sk-primary);
}
.ds-type-icon.postgres {
  color: var(--sk-postgres);
}
.ds-db {
  font-size: 12px;
  color: var(--sk-text-tertiary, var(--sk-text-tertiary));
}
.clear-btn {
  color: var(--sk-text-secondary, var(--sk-text-secondary));
}
.clear-btn:hover {
  color: var(--sk-danger);
}
.messages {
  flex: 1;
  overflow: auto;
  padding: 20px 16px 24px;
  background: var(--sk-bg);
}
.msg {
  display: flex;
  margin-bottom: 14px;
}
.msg.user {
  justify-content: flex-end;
}
.msg.assistant {
  justify-content: flex-start;
}
.bubble {
  max-width: 86%;
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.5;
}
.user-bubble {
  background: var(--sk-primary);
  color: var(--sk-on-primary);
  white-space: pre-wrap;
}
.ai-stack {
  width: 100%;
  max-width: 920px;
  color: var(--sk-text-primary);
}

/* ---- 整体卡片：推理 + 结果 ---- */
.ai-card {
  background: var(--sk-bg-elevated);
  border: 1px solid var(--sk-border, #e2e8f0);
  border-radius: 8px;
  padding: 0;
  box-sizing: border-box;
  overflow: hidden;
}
.ai-card-block {
  padding: 16px;
  box-sizing: border-box;
}
.ai-card-divider {
  height: 0;
  border: none;
  border-top: 1px solid var(--sk-border, #e2e8f0);
  margin: 0;
}
.card-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 24px;
  color: var(--sk-text-secondary);
  font-size: 13px;
  line-height: 1.4;
}
.card-title {
  color: var(--sk-text-primary);
  font-weight: 600;
}
.card-sep {
  color: var(--sk-border-strong, var(--sk-border));
}
.card-meta {
  color: var(--sk-text-secondary);
}
.card-meta.trunc {
  color: var(--sk-warning-text, var(--sk-warning));
}
.card-meta.fail-brief {
  color: var(--sk-danger);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.reason-head {
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.reason-mark {
  width: 16px;
  text-align: center;
  font-weight: 700;
}
.reason-mark.done {
  color: var(--sk-success);
}
.reason-mark.fail {
  color: var(--sk-danger);
}
.reason-mark.running {
  color: var(--sk-primary);
}
.reason-toggle {
  margin-left: auto;
  font-size: 12px;
  color: var(--sk-text-tertiary, var(--sk-text-secondary));
}
.result-head {
  margin-bottom: 12px;
}
.result-head.fail .card-title {
  color: var(--sk-danger);
}
.result-icon {
  font-size: 16px;
  color: var(--sk-primary);
  flex-shrink: 0;
}
.result-icon.fail {
  color: var(--sk-danger);
}
.mini-spin {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid var(--sk-border-strong);
  border-top-color: var(--sk-primary);
  border-radius: 50%;
  animation: mini-spin 0.8s linear infinite;
  vertical-align: -1px;
}
@keyframes mini-spin {
  to {
    transform: rotate(360deg);
  }
}
.reason-panel {
  max-height: 0;
  overflow: hidden;
  transition: max-height 0.2s ease;
}
.reason-panel.open {
  max-height: 640px;
}
.reason-panel-inner {
  overflow: hidden;
}
.phase-line {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 2px;
  padding: 4px 8px;
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
  border-radius: 6px;
}
.phase-line:hover {
  background: var(--sk-hover-bg);
  transition: background 0.15s;
}
.phase-head {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.phase-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.phase-line .icon {
  width: 14px;
  text-align: center;
  color: var(--sk-success);
  flex-shrink: 0;
}
.phase-line.fail .icon {
  color: var(--sk-danger);
}
.phase-line.skip .icon {
  color: var(--sk-text-tertiary);
}
.phase-line.run .icon {
  color: var(--sk-primary);
}
.phase-line .phase-name {
  color: var(--sk-text-primary);
  font-weight: 500;
  white-space: nowrap;
}
.phase-src {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 16px;
  font-weight: 500;
  border-radius: 4px;
  padding: 1px 6px;
}
.phase-src.local {
  background: var(--sk-hover-bg);
  color: var(--sk-text-strong);
  box-shadow: inset 0 0 0 1px var(--sk-border);
}
.phase-src.ai {
  background: var(--sk-active-bg);
  color: var(--sk-primary);
}
.phase-src.vector {
  background: var(--sk-chip-vector-bg);
  color: var(--sk-chip-vector-fg);
}
.phase-src.db {
  background: var(--sk-chip-db-bg);
  color: var(--sk-chip-db-fg);
}
.phase-line .phase-content {
  display: block;
  margin-left: 26px;
  color: var(--sk-text-secondary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.phase-line .phase-content.sql-preview {
  font-family: Consolas, 'Courier New', monospace;
  color: var(--sk-code-fg);
  background: var(--sk-code-bg);
  padding: 1px 6px;
  border-radius: 4px;
}
.phase-line .phase-content.exec-result {
  color: var(--sk-success);
}
.phase-line .phase-content.check-fail {
  color: var(--sk-danger);
}
.phase-line .phase-time {
  color: var(--sk-text-tertiary);
  font-family: 'SF Mono', Consolas, monospace;
  font-size: 11px;
  flex-shrink: 0;
  min-width: 60px;
  text-align: right;
}

.thinking-hint {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: 10px;
  padding: 4px 2px 2px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
  user-select: none;
}
.thinking-label {
  letter-spacing: 0.02em;
}
.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: 4px;
  height: 12px;
}
.thinking-dots i {
  display: inline-block;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--sk-text-tertiary);
  opacity: 0.35;
  animation: thinking-bounce 1.2s ease-in-out infinite;
}
.thinking-dots i:nth-child(2) {
  animation-delay: 0.2s;
}
.thinking-dots i:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes thinking-bounce {
  0%,
  80%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}
.err {
  color: var(--sk-danger);
  background: var(--sk-danger-bg);
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
}
.sql-block {
  border: 1px solid var(--sk-border);
  border-radius: 6px;
  overflow: hidden;
}
.sql-block.sql-fail {
  border-color: var(--sk-danger);
}
.sql-block + .script-steps,
.sql-block + .table-block,
.sql-block + .result-feedback,
.script-steps + .table-block,
.table-block + .result-feedback {
  margin-top: 12px;
}
.sql-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: var(--sk-bg);
  font-size: 12px;
  color: var(--sk-text-secondary);
}
.sql-title {
  font-weight: 600;
  color: var(--sk-text-primary);
}
.sql-head .btns {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: wrap;
}
.sql-head .btns :deep(.el-button) {
  padding: 4px 6px;
}
.page-size-label {
  margin-left: 8px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.page-size-switch {
  margin-left: 4px;
}
.sql-code {
  position: relative;
  padding: 28px 0 10px;
  background: var(--sk-code-bg);
  color: var(--sk-code-fg);
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
}
.sql-corner {
  position: absolute;
  top: 8px;
  left: 12px;
  font-size: 11px;
  color: var(--sk-code-muted);
  letter-spacing: 0.04em;
}
.sql-line {
  display: flex;
  gap: 12px;
  padding: 0 16px;
  white-space: pre-wrap;
  word-break: break-word;
}
.sql-line .ln {
  width: 24px;
  flex-shrink: 0;
  text-align: right;
  color: var(--sk-code-muted);
  user-select: none;
}
.sql-line :deep(.tok-kw) {
  color: #569cd6;
}
.sql-line :deep(.tok-fn) {
  color: #dcdcaa;
}
.sql-line :deep(.tok-str) {
  color: #ce9178;
}
.sql-line :deep(.tok-num) {
  color: #b5cea8;
}
.sql-line :deep(.tok-cmt) {
  color: #6a9955;
}
.script-steps {
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  overflow: hidden;
}
.script-step-row {
  padding: 8px 0;
  border-bottom: 1px solid var(--sk-border);
  font-size: 12px;
}
.script-step-row:last-child {
  border-bottom: none;
}
.script-step-row .idx {
  color: var(--sk-text-tertiary);
  margin-right: 8px;
}
.script-step-row .meta {
  color: var(--sk-text-secondary);
}
.script-step-row .step-err {
  color: var(--sk-danger);
}
.script-step-row .step-sql {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: var(--sk-hover-bg);
  border-radius: 4px;
  white-space: pre-wrap;
  font-family: Consolas, Monaco, monospace;
  font-size: 11px;
  color: var(--sk-text-primary);
}
.result-feedback {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--sk-text-secondary);
}
.feedback-label {
  margin-right: 4px;
}
.feedback-btn {
  appearance: none;
  border: 1px solid var(--sk-border);
  background: var(--sk-bg-elevated);
  color: var(--sk-text-secondary);
  border-radius: 6px;
  padding: 2px 10px;
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}
.feedback-btn:hover:not(:disabled) {
  border-color: var(--sk-primary);
  color: var(--sk-primary-strong);
}
.feedback-btn.active {
  border-color: var(--sk-primary);
  color: var(--sk-primary-strong);
  background: var(--sk-active-bg);
  cursor: default;
}
.feedback-btn:disabled:not(.active) {
  opacity: 0.55;
  cursor: not-allowed;
}
.table-block {
  margin-top: 0;
}
.result-table {
  --el-table-border-color: transparent;
  --el-table-header-bg-color: var(--sk-bg);
  --el-table-row-hover-bg-color: var(--sk-active-bg);
  --el-table-bg-color: var(--sk-bg-elevated);
}
.result-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.result-table :deep(.el-table__cell) {
  border-right: none;
  border-bottom: 1px solid var(--sk-hover-bg);
  padding: 8px 12px;
}
.result-table :deep(th.el-table__cell) {
  background: var(--sk-bg);
  color: var(--sk-text-secondary);
  font-weight: 600;
}
.result-table :deep(.el-table__body tr) {
  height: 36px;
}
.result-table :deep(.el-table__body tr:nth-child(even) td) {
  background: var(--sk-bg-sidebar);
}
.result-table :deep(.el-table__body tr:hover > td) {
  background: var(--sk-active-bg) !important;
}
.result-pager {
  margin-top: 10px;
  justify-content: flex-end;
}
.pending {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 16px;
  font-size: 13px;
  color: var(--sk-text-tertiary);
  user-select: none;
}
.examples {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 180px;
  gap: 12px;
  color: var(--sk-text-secondary);
}
.examples-title {
  font-size: 13px;
  color: var(--sk-text-tertiary);
}
.examples-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 560px;
}
.composer {
  padding: 0 16px 12px;
  background: var(--sk-bg);
}
.composer-context {
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
  text-align: left;
}
.input-wrapper {
  background: var(--sk-bg-elevated);
  border: 1px solid var(--sk-border);
  border-radius: 8px;
  padding: 8px 12px 8px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.input-wrapper.focused {
  border-color: var(--sk-primary);
  box-shadow: 0 0 0 2px var(--sk-active-bg);
}
.input-wrapper.disabled {
  background: var(--sk-bg);
}
.input-textarea {
  display: block;
  width: 100%;
  height: 22px;
  min-height: 22px;
  max-height: 132px;
  border: none;
  outline: none;
  resize: none;
  overflow-y: hidden;
  background: transparent;
  color: var(--sk-text-primary, var(--sk-text-primary));
  font-size: 14px;
  font-family: inherit;
  line-height: 22px;
  padding: 0;
}
.composer-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
}
.tool-left {
  display: flex;
  align-items: center;
  gap: 4px;
}
.char-count {
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.char-count.over {
  color: var(--sk-danger);
}
.input-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 4px;
}
.hint {
  margin-right: auto;
  font-size: 12px;
  color: var(--sk-text-tertiary);
}
.hint.warn {
  color: var(--sk-warning);
}
</style>

<style>
.phase-content-tip {
  max-width: 480px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
