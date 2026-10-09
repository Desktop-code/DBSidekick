<template>
  <div class="ai-chat">
    <div class="chat-header">
      <div class="left">
        <span class="title clickable" title="点击重命名" @click="onRenameTitle">{{ sessionTitle }}</span>
        <div class="ds-wrap">
          <el-select
            v-model="localDsId"
            size="small"
            placeholder="选择数据源"
            style="width: 200px"
            :disabled="streaming"
            @change="onDsChange"
          >
            <el-option
              v-for="d in appStore.datasources"
              :key="d.id"
              :label="d.name"
              :value="d.id"
            />
          </el-select>
          <el-icon v-if="currentDs" class="ds-type-icon" :class="dsKind(currentDs)" :title="dialectOf(currentDs)">
            <Coin />
          </el-icon>
          <span v-if="appStore.currentDatabase" class="ds-db">{{ appStore.currentDatabase }}</span>
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
            :disabled="!localDsId"
            @click="askExample(q)"
          >
            {{ q }}
          </el-button>
        </div>
      </div>
      <div v-for="(msg, idx) in messages" :key="idx" class="msg" :class="msg.role">
        <div v-if="msg.role === 'user'" class="bubble user-bubble">{{ msg.content }}</div>
        <div v-else class="bubble ai-bubble">
          <!-- 推理过程 -->
          <section v-if="msg.phases?.length || showReasoningThinking(msg, idx)" class="section section-reason">
            <button type="button" class="reason-head" @click="toggleReason(msg, idx)">
              <span class="reason-mark" :class="reasonState(msg, idx)">
                <i v-if="reasonState(msg, idx) === 'running'" class="mini-spin" />
                <template v-else-if="reasonState(msg, idx) === 'fail'">✗</template>
                <template v-else>✓</template>
              </span>
              <span class="reason-title">推理过程</span>
              <span class="reason-sep">·</span>
              <span>{{ reasonStateText(msg, idx) }}</span>
              <span class="reason-sep">·</span>
              <span>耗时 {{ formatDuration(reasonTotalMs(msg)) }}</span>
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
                    <span class="phase-name">{{ p.phase || '阶段' }}</span>
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
            v-if="msg.pending && !msg.error && !msg.sql && !msg.phases?.length && !showReasoningThinking(msg, idx)"
            class="pending"
          >
            <span class="thinking-label">thinking</span>
            <span class="thinking-dots" aria-hidden="true">
              <i /><i /><i />
            </span>
          </div>

          <!-- 查询结果：等推理打完再出现 -->
          <section
            v-if="msg.summary || msg.error || msg.sql || msg.columns?.length"
            class="section section-result"
            :class="{ 'has-reason-above': msg.phases?.length }"
          >
            <div v-if="msg.summary" class="summary">{{ msg.summary }}</div>
            <div v-if="msg.error" class="err">{{ msg.error }}</div>
            <div v-if="msg.sql" class="sql-block">
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
                  <span class="page-size-label">每页</span>
                  <el-radio-group
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
              v-if="msg.scriptMode && msg.scriptSteps?.length"
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
            <div v-if="msg.columns?.length" class="table-block">
              <div class="table-meta">
                <span class="badge badge-rows">✓ {{ msg.rowCount ?? 0 }} 行</span>
                <span v-if="msg.truncated" class="badge badge-trunc">已截断</span>
                <span
                  v-if="msg.elapsedMs != null"
                  class="badge badge-time"
                  :class="elapsedBadgeClass(msg.elapsedMs)"
                >⚡ {{ formatDuration(msg.elapsedMs) }}</span>
              </div>
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
          </section>
        </div>
      </div>
    </div>

    <div class="composer">
      <div class="input-wrapper" :class="{ focused: composerFocus, disabled: streaming || !localDsId }">
        <textarea
          ref="composerRef"
          v-model="input"
          class="input-textarea"
          :disabled="streaming || !localDsId"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          @keydown="onKeydown"
          @focus="composerFocus = true"
          @blur="composerFocus = false"
        />
        <div class="input-footer">
          <span v-if="!localDsId" class="hint warn">请先选择数据源</span>
          <span v-else class="hint">Enter 发送 · Shift+Enter 换行</span>
          <el-button type="primary" :disabled="!canSend" :loading="streaming" @click="send">发送</el-button>
        </div>
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
import { Coin, CopyDocument, EditPen, FolderAdd } from '@element-plus/icons-vue'
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

const sessionId = ref(props.tab?.props?.sessionId || '')
const sessionTitle = ref(props.tab?.title?.replace(/^AI会话:\s*/, '') || '新会话')
const localDsId = ref(
  props.tab?.props?.datasourceId || props.tab?.datasourceId || appStore.currentDatasourceId || ''
)
const input = ref(props.tab?.props?.draft || '')
const composerRef = ref(null)
const composerFocus = ref(false)
const reasonOpenMap = ref({})
const streaming = ref(false)
const loadingSession = ref(false)
const messages = ref([])
const scrollRef = ref(null)

const saveVisible = ref(false)
const saveName = ref('')
const saveSql = ref('')
const saving = ref(false)

const currentDs = computed(() => appStore.datasources.find((d) => d.id === localDsId.value) || null)
const canSend = computed(() => !!localDsId.value && !!input.value.trim() && !streaming.value)
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

function elapsedBadgeClass(ms) {
  const n = Number(ms) || 0
  if (n < 1000) return 'fast'
  if (n < 5000) return 'mid'
  return 'slow'
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
      datasourceId: localDsId.value || null
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

async function onDsChange(id) {
  appStore.setCurrentDatasource(id)
  // 换库必须新开会话，避免跨库污染。没提问前不落库。
  if (streaming.value) return
  messages.value = []
  sessionId.value = ''
  sessionTitle.value = '新会话'
  appStore.setCurrentSessionId(null)
  syncTabMeta()
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
      sql: m.sqlText || '',
      columns: [],
      rows: [],
      rowCount: 0,
      truncated: false,
      elapsedMs: null,
      error: '',
      pending: false,
      page: 1
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
  const database = appStore.currentDatabase || currentDs.value?.database
  if (!database) {
    ElMessage.warning('请先在左侧选择数据库')
    return
  }
  const question = input.value.trim()
  const dsId = localDsId.value
  input.value = ''
  const sid = sessionId.value || ''

  messages.value.push({ role: 'user', content: question })
  messages.value.push({
    role: 'assistant',
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
    page: 1
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
  }
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
</script>

<style scoped>
.ai-chat {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.chat-header {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid var(--sk-border, #e2e8f0);
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
  color: var(--sk-text-primary, #1e293b);
}
.title.clickable {
  cursor: pointer;
  border-bottom: 1px dashed transparent;
}
.title.clickable:hover {
  color: #3b82f6;
  border-bottom-color: #3b82f6;
}
.ds-wrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.ds-type-icon {
  font-size: 14px;
  color: #3b82f6;
}
.ds-type-icon.postgres {
  color: #1e3a8a;
}
.ds-db {
  font-size: 12px;
  color: var(--sk-text-tertiary, #94a3b8);
}
.clear-btn {
  color: var(--sk-text-secondary, #64748b);
}
.clear-btn:hover {
  color: #ef4444;
}
.messages {
  flex: 1;
  overflow: auto;
  padding: 20px 16px 24px;
  background: #f8fafc;
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
  background: #409eff;
  color: #fff;
  white-space: pre-wrap;
}
.ai-bubble {
  background: #fff;
  border: 1px solid #ebeef5;
  color: #303133;
  width: 100%;
  max-width: 920px;
  padding: 0;
  overflow: hidden;
}

/* ---- 分区：推理 / 结果 ---- */
.section {
  padding: 16px;
}
.section-reason {
  padding-bottom: 8px;
}
.section-result.has-reason-above {
  padding-top: 0;
}
.reason-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 2px;
  border: none;
  background: transparent;
  color: var(--sk-text-secondary, #64748b);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
}
.reason-mark {
  width: 16px;
  text-align: center;
  font-weight: 700;
}
.reason-mark.done {
  color: #10b981;
}
.reason-mark.fail {
  color: #ef4444;
}
.reason-mark.running {
  color: #3b82f6;
}
.reason-title {
  color: var(--sk-text-primary, #1e293b);
  font-weight: 600;
}
.reason-sep {
  color: #cbd5e1;
}
.reason-toggle {
  margin-left: auto;
  font-size: 12px;
  color: var(--sk-text-tertiary, #94a3b8);
}
.mini-spin {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid #bfdbfe;
  border-top-color: #3b82f6;
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
  background: #f1f5f9;
  transition: background 0.15s;
}
.phase-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}
.phase-line .icon {
  width: 14px;
  text-align: center;
  color: #52c41a;
  flex-shrink: 0;
}
.phase-line.fail .icon {
  color: #ef4444;
}
.phase-line.skip .icon {
  color: #94a3b8;
}
.phase-line.run .icon {
  color: #3b82f6;
}
.phase-line .phase-name {
  color: #1e293b;
  font-weight: 500;
  flex: 1;
  min-width: 0;
}
.phase-line .phase-content {
  display: block;
  margin-left: 26px;
  color: #64748b;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.phase-line .phase-content.sql-preview {
  font-family: 'SF Mono', Consolas, monospace;
  color: #e2e8f0;
  background: #1e293b;
  padding: 1px 6px;
  border-radius: 4px;
}
.phase-line .phase-content.exec-result {
  color: #16a34a;
}
.phase-line .phase-content.check-fail {
  color: #ef4444;
}
.phase-line .phase-time {
  color: #94a3b8;
  font-family: 'SF Mono', Consolas, monospace;
  font-size: 11px;
  flex-shrink: 0;
  min-width: 60px;
  text-align: right;
}

.summary {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
  margin: 16px 0 0;
  line-height: 1.6;
}
.thinking-hint {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-top: 10px;
  padding: 4px 2px 2px;
  font-size: 12px;
  color: #909399;
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
  background: #909399;
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
  color: #f56c6c;
  background: #fef0f0;
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
}
.sql-block {
  margin-top: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
}
.sql-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: #f8fafc;
  font-size: 12px;
  color: #64748b;
}
.sql-title {
  font-weight: 600;
  color: #1e293b;
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
  color: #94a3b8;
}
.page-size-switch {
  margin-left: 4px;
}
.sql-code {
  position: relative;
  padding: 28px 0 10px;
  background: #1e1e2e;
  color: #cdd6f4;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.55;
}
.sql-corner {
  position: absolute;
  top: 8px;
  left: 12px;
  font-size: 11px;
  color: #94a3b8;
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
  color: #6b7280;
  user-select: none;
}
.sql-line :deep(.tok-kw) {
  color: #7aa2f7;
}
.sql-line :deep(.tok-fn) {
  color: #bb9af7;
}
.sql-line :deep(.tok-str) {
  color: #9ece6a;
}
.sql-line :deep(.tok-num) {
  color: #ff9e64;
}
.sql-line :deep(.tok-cmt) {
  color: #6b7280;
}
.script-steps {
  margin-top: 12px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  overflow: hidden;
}
.script-step-row {
  padding: 8px 0;
  border-bottom: 1px solid #f0f2f5;
  font-size: 12px;
}
.script-step-row:last-child {
  border-bottom: none;
}
.script-step-row .idx {
  color: #909399;
  margin-right: 8px;
}
.script-step-row .meta {
  color: #606266;
}
.script-step-row .step-err {
  color: #f56c6c;
}
.script-step-row .step-sql {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  white-space: pre-wrap;
  font-family: Consolas, Monaco, monospace;
  font-size: 11px;
  color: #303133;
}
.table-block {
  margin-top: 16px;
}
.table-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  line-height: 22px;
}
.badge-rows {
  color: #047857;
  background: #ecfdf5;
}
.badge-trunc {
  color: #b45309;
  background: #fffbeb;
}
.badge-time.fast {
  color: #047857;
  background: #ecfdf5;
}
.badge-time.mid {
  color: #1d4ed8;
  background: #eff6ff;
}
.badge-time.slow {
  color: #c2410c;
  background: #fff7ed;
}
.result-table {
  --el-table-border-color: transparent;
  --el-table-header-bg-color: #f8fafc;
  --el-table-row-hover-bg-color: #eff6ff;
  --el-table-bg-color: #fff;
}
.result-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}
.result-table :deep(.el-table__cell) {
  border-right: none;
  border-bottom: 1px solid #f1f5f9;
  padding: 8px 12px;
}
.result-table :deep(th.el-table__cell) {
  background: #f8fafc;
  color: #64748b;
  font-weight: 600;
}
.result-table :deep(.el-table__body tr) {
  height: 36px;
}
.result-table :deep(.el-table__body tr:nth-child(even) td) {
  background: #fafbfc;
}
.result-table :deep(.el-table__body tr:hover > td) {
  background: #eff6ff !important;
}
.result-pager {
  margin-top: 10px;
  justify-content: flex-end;
}
.pending {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 16px 8px;
  font-size: 13px;
  color: #909399;
  user-select: none;
}
.examples {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 180px;
  gap: 12px;
  color: #606266;
}
.examples-title {
  font-size: 13px;
  color: #909399;
}
.examples-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 560px;
}
.composer {
  padding: 0 16px 16px;
  background: #f8fafc;
}
.input-wrapper {
  background: #fff;
  border: 1px solid var(--sk-border, #e2e8f0);
  border-radius: 8px;
  padding: 10px 12px 8px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.input-wrapper.focused {
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.28);
}
.input-wrapper.disabled {
  background: #f8fafc;
}
.input-textarea {
  display: block;
  width: 100%;
  min-height: 60px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--sk-text-primary, #1e293b);
  font-size: 14px;
  font-family: inherit;
  line-height: 1.5;
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
  color: #94a3b8;
}
.hint.warn {
  color: #d97706;
}
</style>

<style>
.phase-content-tip {
  max-width: 480px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
