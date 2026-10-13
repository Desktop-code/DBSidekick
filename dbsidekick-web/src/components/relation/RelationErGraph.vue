<template>
  <div class="er-wrap" v-loading="loading">
    <div class="er-toolbar">
      <div class="er-toolbar-left">
        <el-radio-group v-model="graphStatus" size="small" @change="reload">
          <el-radio-button value="CONFIRMED">只看已确认</el-radio-button>
          <el-radio-button value="PENDING">只看待审</el-radio-button>
          <el-radio-button value="ALL">全部</el-radio-button>
        </el-radio-group>
        <span v-if="props.database" class="er-db-badge">库：{{ props.database }}</span>
        <span v-else class="er-db-badge warn">未选库（将显示数据源下全部关系）</span>
      </div>
      <div class="er-actions">
        <el-button size="small" @click="resetLayout">重置布局</el-button>
        <el-button size="small" @click="exportPng">导出 PNG</el-button>
      </div>
    </div>
    <div v-if="hintText" class="er-hint">{{ hintText }}</div>
    <div v-if="!loading && loadError" class="er-empty er-error">{{ loadError }}</div>
    <div v-else-if="!loading && !edges.length" class="er-empty">
      {{ emptyHint }}
    </div>
    <div v-show="edges.length" ref="chartRef" class="er-chart" />

    <el-drawer v-model="drawerOpen" :title="drawerTitle" size="380px" destroy-on-close>
      <div v-if="drawerKind === 'node' && drawerNode" class="drawer-body">
        <div class="row"><span class="k">表名</span><span>{{ drawerNode.name }}</span></div>
        <div class="row"><span class="k">注释</span><span>{{ drawerNode.comment || '—' }}</span></div>
        <div class="row"><span class="k">业务域</span><span>{{ drawerNode.domain }}</span></div>
        <div class="row"><span class="k">字段数</span><span>{{ drawerNode.columnCount ?? 0 }}</span></div>
        <div class="row"><span class="k">关联数</span><span>{{ drawerNode.relationCount ?? 0 }}</span></div>
        <div class="sub-title">关联表</div>
        <ul class="rel-list">
          <li v-for="(r, i) in nodeRelations" :key="i">
            <span class="dir">{{ r.dir }}</span>
            {{ r.peer }}.{{ r.peerCol }}
            <el-tag size="small" :type="statusTagType(r.status)" class="st">{{ statusLabel(r.status) }}</el-tag>
          </li>
          <li v-if="!nodeRelations.length" class="muted">无关联</li>
        </ul>
      </div>
      <div v-else-if="drawerKind === 'edge' && drawerEdge" class="drawer-body">
        <div class="row">
          <span class="k">关系</span>
          <span>{{ drawerEdge.source }}.{{ drawerEdge.sourceColumn }} → {{ drawerEdge.target }}.{{ drawerEdge.targetColumn }}</span>
        </div>
        <div class="row"><span class="k">状态</span><span>{{ statusLabel(drawerEdge.status) }}</span></div>
        <div class="row"><span class="k">置信度</span><span>{{ drawerEdge.confidence ?? 0 }}</span></div>
        <div class="row"><span class="k">来源</span><span>{{ drawerEdge.sources || '—' }}</span></div>
        <div class="edge-actions">
          <template v-if="drawerEdge.status === 'PENDING'">
            <el-button type="primary" size="small" @click="onConfirmEdge">确认</el-button>
            <el-button type="danger" size="small" @click="onRejectEdge">拒绝</el-button>
          </template>
          <el-button v-else-if="drawerEdge.status === 'CONFIRMED'" type="warning" size="small" @click="onRejectEdge">
            禁用
          </el-button>
          <el-button v-else type="primary" size="small" @click="onResetEdge">恢复</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import {
  confirmRelation,
  getRelationGraph,
  rejectRelation,
  resetRelation
} from '../../api/relation'
import { useThemeStore } from '../../stores/theme'

const props = defineProps({
  datasourceId: { type: String, default: '' },
  database: { type: String, default: '' }
})

const DOMAIN_COLORS = {
  sys: '#3B82F6',
  oms: '#10B981',
  pms: '#F59E0B',
  ums: '#8B5CF6',
  wms: '#06B6D4',
  other: '#94A3B8'
}
const DOMAIN_ORDER = ['sys', 'oms', 'pms', 'ums', 'wms', 'other']
const EDGE_COLOR = '#94A3B8'
const EDGE_HOVER = '#3B82F6'
const EDGE_WIDTH = 1.5

const themeStore = useThemeStore()
const chartRef = ref(null)
const loading = ref(false)
const nodes = ref([])
const edges = ref([])
const truncated = ref(false)
const hiddenCount = ref(0)
const sparseHint = ref(false)
const autoPendingHint = ref(false)
const loadError = ref('')
/** 默认只看已确认；若不足 10 条会自动补待审虚线 */
const graphStatus = ref('CONFIRMED')
const emptyHint = ref('暂无关系数据，多问几次 AI 或点击「推断表关系」生成种子')

const drawerOpen = ref(false)
const drawerKind = ref('')
const drawerNode = ref(null)
const drawerEdge = ref(null)

let chart = null
let resizeObs = null

const hintText = computed(() => {
  const parts = []
  if (sparseHint.value) {
    parts.push('关系较少，多问几次 AI 可自动积累')
  }
  if (autoPendingHint.value) {
    parts.push('已确认关系较少，已同时显示待审（虚线）')
  }
  if (truncated.value && hiddenCount.value) {
    parts.push(`还有 ${hiddenCount.value} 个节点未显示`)
  }
  return parts.join(' · ')
})

const drawerTitle = computed(() => {
  if (drawerKind.value === 'node') return '表详情'
  if (drawerKind.value === 'edge') return '关系详情'
  return ''
})

const nodeRelations = computed(() => {
  const n = drawerNode.value
  if (!n) return []
  const name = n.id || n.name
  const out = []
  for (const e of edges.value) {
    if (e.source === name) {
      out.push({ dir: '→', peer: e.target, peerCol: e.targetColumn, status: e.status })
    } else if (e.target === name) {
      out.push({ dir: '←', peer: e.source, peerCol: e.sourceColumn, status: e.status })
    }
  }
  return out
})

function statusLabel(s) {
  if (s === 'CONFIRMED') return '已确认'
  if (s === 'PENDING') return '待审'
  if (s === 'REJECTED') return '已拒绝'
  return s || '-'
}

function statusTagType(s) {
  if (s === 'CONFIRMED') return 'success'
  if (s === 'PENDING') return 'warning'
  return 'info'
}

function isDark() {
  return themeStore.resolvedTheme === 'dark'
}

function normalizeDomain(raw) {
  const d = String(raw || 'other').toLowerCase()
  if (DOMAIN_COLORS[d] && d !== 'other') return d
  return 'other'
}

function truncateLabel(name) {
  const s = String(name || '')
  if (s.length <= 10) return s
  return `${s.slice(0, 10)}…`
}

function edgeLineStyle(status) {
  return {
    color: EDGE_COLOR,
    width: EDGE_WIDTH,
    type: status === 'PENDING' ? 'dashed' : 'solid',
    curveness: 0.08,
    opacity: 0.95
  }
}

function buildOption() {
  const dark = isDark()
  const categories = DOMAIN_ORDER.map((name) => ({ name }))
  const domainToCat = Object.fromEntries(DOMAIN_ORDER.map((d, i) => [d, i]))

  const chartNodes = nodes.value.map((n) => {
    const domain = normalizeDomain(n.domain)
    const rc = Number(n.relationCount) || 0
    const size = 20 + Math.min(rc * 2, 20)
    const fullName = String(n.name || n.id)
    return {
      id: String(n.id),
      name: truncateLabel(fullName),
      value: rc,
      symbolSize: size,
      category: domainToCat[domain] ?? domainToCat.other,
      raw: { ...n, domain, name: fullName },
      itemStyle: {
        color: DOMAIN_COLORS[domain] || DOMAIN_COLORS.other,
        borderColor: dark ? '#1E293B' : '#FFFFFF',
        borderWidth: 1
      },
      label: {
        show: true,
        color: dark ? '#E2E8F0' : '#334155',
        fontSize: 11,
        formatter: truncateLabel(fullName)
      }
    }
  })

  const showEdgeLabel = edges.value.length <= 20
  const chartEdges = edges.value.map((e) => {
    const srcCol = e.sourceColumn || '?'
    const tgtCol = e.targetColumn || '?'
    return {
      source: String(e.source),
      target: String(e.target),
      raw: e,
      label: showEdgeLabel
        ? {
            show: true,
            formatter: `${srcCol}→${tgtCol}`,
            fontSize: 10,
            color: dark ? '#94A3B8' : '#64748B'
          }
        : { show: false },
      lineStyle: edgeLineStyle(e.status),
      emphasis: {
        label: {
          show: true,
          formatter: `${e.source}.${srcCol} → ${e.target}.${tgtCol}`,
          fontSize: 11,
          color: EDGE_HOVER
        },
        lineStyle: {
          color: EDGE_HOVER,
          width: 3,
          opacity: 1
        }
      }
    }
  })

  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter(params) {
        if (params.dataType === 'edge' || params.data?.raw?.sourceColumn != null) {
          const e = params.data.raw || params.data
          return `${e.source}.${e.sourceColumn} → ${e.target}.${e.targetColumn}<br/>状态 ${statusLabel(e.status)}<br/>置信度 ${e.confidence ?? 0}<br/>来源 ${e.sources || '—'}`
        }
        const n = params.data?.raw || params.data
        const name = n.name || n.id
        return `${name}<br/>${n.comment || '无注释'}<br/>关联数 ${n.relationCount ?? 0}`
      }
    },
    legend: {
      data: DOMAIN_ORDER,
      orient: 'vertical',
      left: 8,
      bottom: 8,
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 6,
      padding: [8, 10],
      backgroundColor: dark ? 'rgba(15, 23, 42, 0.72)' : 'rgba(255, 255, 255, 0.78)',
      borderRadius: 6,
      textStyle: { color: dark ? '#CBD5E1' : '#475569', fontSize: 11 },
      selectedMode: true
    },
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        categories,
        data: chartNodes,
        links: chartEdges,
        label: { position: 'right', fontSize: 11 },
        force: {
          repulsion: 1200,
          edgeLength: 100,
          gravity: 0.05
        },
        emphasis: {
          focus: 'adjacency',
          scale: true,
          lineStyle: { width: 3, color: EDGE_HOVER }
        },
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [0, 8],
        lineStyle: { opacity: 0.95, color: EDGE_COLOR, width: EDGE_WIDTH }
      }
    ]
  }
}

function renderChart() {
  if (!chartRef.value || !edges.value.length) return
  const el = chartRef.value
  if (el.clientWidth < 8 || el.clientHeight < 8) {
    setTimeout(() => renderChart(), 80)
    return
  }
  if (chart && chart.getDom() !== el) {
    chart.dispose()
    chart = null
  }
  if (!chart) {
    chart = echarts.init(el, null, { renderer: 'canvas' })
    chart.on('click', onChartClick)
  }
  chart.setOption(buildOption(), true)
  requestAnimationFrame(() => {
    chart?.resize()
    setTimeout(() => chart?.resize(), 80)
  })
}

function onChartClick(params) {
  if (params.dataType === 'node' || (params.data && params.data.raw && params.data.raw.relationCount != null)) {
    drawerKind.value = 'node'
    drawerNode.value = params.data.raw || params.data
    drawerEdge.value = null
    drawerOpen.value = true
    return
  }
  if (params.dataType === 'edge' || (params.data && params.data.raw && params.data.raw.sourceColumn != null)) {
    drawerKind.value = 'edge'
    drawerEdge.value = params.data.raw || params.data
    drawerNode.value = null
    drawerOpen.value = true
  }
}

function mergeGraphPayload(base, extra) {
  const nodeMap = new Map()
  for (const n of [...(base.nodes || []), ...(extra.nodes || [])]) {
    const id = String(n.id || n.name)
    const prev = nodeMap.get(id)
    if (!prev) {
      nodeMap.set(id, { ...n })
    } else {
      nodeMap.set(id, {
        ...prev,
        relationCount: Math.max(Number(prev.relationCount) || 0, Number(n.relationCount) || 0)
      })
    }
  }
  const edgeMap = new Map()
  for (const e of [...(base.edges || []), ...(extra.edges || [])]) {
    edgeMap.set(String(e.id || `${e.source}-${e.target}-${e.sourceColumn}-${e.targetColumn}`), e)
  }
  return {
    nodes: [...nodeMap.values()],
    edges: [...edgeMap.values()],
    truncated: !!(base.truncated || extra.truncated),
    hiddenCount: Math.max(Number(base.hiddenCount) || 0, Number(extra.hiddenCount) || 0),
    totalEdges: (Number(base.totalEdges) || 0) + (Number(extra.totalEdges) || 0)
  }
}

async function fetchGraph(status) {
  return getRelationGraph({
    datasourceId: props.datasourceId,
    database: props.database || undefined,
    status: status || undefined
  })
}

async function reload() {
  if (!props.datasourceId) {
    nodes.value = []
    edges.value = []
    truncated.value = false
    hiddenCount.value = 0
    sparseHint.value = false
    autoPendingHint.value = false
    loadError.value = '请先选择数据源'
    emptyHint.value = '请先选择数据源'
    return
  }
  if (!props.database) {
    nodes.value = []
    edges.value = []
    truncated.value = false
    hiddenCount.value = 0
    sparseHint.value = false
    autoPendingHint.value = false
    loadError.value = ''
    emptyHint.value = '请先在上方选择要查看的库，图仅显示该库内的表与关系'
    return
  }
  loading.value = true
  loadError.value = ''
  sparseHint.value = false
  autoPendingHint.value = false
  try {
    let res
    if (graphStatus.value === 'ALL') {
      res = await fetchGraph(undefined)
    } else if (graphStatus.value === 'PENDING') {
      res = await fetchGraph('PENDING')
    } else {
      res = await fetchGraph('CONFIRMED')
      const confirmedEdges = Array.isArray(res?.edges) ? res.edges : []
      if (confirmedEdges.length < 10) {
        const pendingRes = await fetchGraph('PENDING')
        res = mergeGraphPayload(res || {}, pendingRes || {})
        autoPendingHint.value = (pendingRes?.edges || []).length > 0
      }
    }

    nodes.value = Array.isArray(res?.nodes) ? res.nodes : []
    edges.value = Array.isArray(res?.edges) ? res.edges : []
    truncated.value = !!res?.truncated
    hiddenCount.value = Number(res?.hiddenCount) || 0
    const edgeTotal = Number(res?.totalEdges) || edges.value.length
    sparseHint.value = edgeTotal > 0 && edgeTotal < 5

    if (!edges.value.length) {
      if (graphStatus.value === 'CONFIRMED') {
        emptyHint.value = '当前库暂无已确认关系，可切换到「全部」或「待审」，或多问几次 AI 积累'
      } else if (graphStatus.value === 'PENDING') {
        emptyHint.value = '当前筛选下没有待审关系，可切换到「全部」查看'
      } else if (props.database) {
        emptyHint.value = `库「${props.database}」暂无关系数据，多问几次 AI 或对该库执行「推断表关系」`
      } else {
        emptyHint.value = '暂无关系数据，多问几次 AI 或点击「推断表关系」生成种子'
      }
    }
  } catch (e) {
    nodes.value = []
    edges.value = []
    const msg = e?.message || String(e)
    loadError.value = /404|Not Found/i.test(msg)
      ? '关系图接口不可用，请重启后端后再试（需包含 /api/relation/graph）'
      : `加载失败：${msg}`
  } finally {
    loading.value = false
    await nextTick()
    renderChart()
  }
}

function resetLayout() {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

function exportPng() {
  if (!chart || !edges.value.length) {
    ElMessage.warning('暂无可导出的图')
    return
  }
  const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: isDark() ? '#0F172A' : '#FFFFFF' })
  const a = document.createElement('a')
  a.href = url
  a.download = `relation-er-${props.database || 'all'}.png`
  a.click()
}

async function onConfirmEdge() {
  if (!drawerEdge.value?.id) return
  try {
    await confirmRelation(drawerEdge.value.id)
    ElMessage.success('已确认')
    drawerOpen.value = false
    await reload()
  } catch {
    /* 拦截器已提示 */
  }
}

async function onRejectEdge() {
  if (!drawerEdge.value?.id) return
  try {
    await rejectRelation(drawerEdge.value.id)
    ElMessage.success(drawerEdge.value.status === 'CONFIRMED' ? '已禁用' : '已拒绝')
    drawerOpen.value = false
    await reload()
  } catch {
    /* 拦截器已提示 */
  }
}

async function onResetEdge() {
  if (!drawerEdge.value?.id) return
  try {
    await resetRelation(drawerEdge.value.id)
    ElMessage.success('已恢复为待审')
    drawerOpen.value = false
    await reload()
  } catch {
    /* 拦截器已提示 */
  }
}

watch(
  () => [props.datasourceId, props.database],
  () => {
    reload()
  }
)

watch(
  () => themeStore.resolvedTheme,
  () => {
    renderChart()
  }
)

onMounted(async () => {
  await reload()
  if (chartRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObs = new ResizeObserver(() => chart?.resize())
    resizeObs.observe(chartRef.value)
  }
  window.addEventListener('resize', onWinResize)
})

function onWinResize() {
  chart?.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', onWinResize)
  resizeObs?.disconnect()
  if (chart) {
    chart.dispose()
    chart = null
  }
})

defineExpose({ reload })
</script>

<style scoped>
.er-wrap {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 8px;
}
.er-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
.er-toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}
.er-db-badge {
  font-size: 12px;
  color: var(--sk-text-secondary, #64748b);
  background: var(--sk-bg-muted, #f1f5f9);
  padding: 2px 8px;
  border-radius: 4px;
  white-space: nowrap;
}
.er-db-badge.warn {
  color: var(--sk-warning-text, #b45309);
  background: rgba(245, 158, 11, 0.12);
}
.er-actions {
  display: flex;
  gap: 8px;
}
.er-hint {
  font-size: 12px;
  color: var(--sk-warning-text, #b45309);
  flex-shrink: 0;
}
.er-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--sk-text-tertiary, #94a3b8);
  font-size: 13px;
  padding: 24px;
  text-align: center;
}
.er-error {
  color: var(--sk-danger, #ef4444);
}
.er-chart {
  flex: 1;
  min-height: 320px;
  border: 1px solid var(--sk-border, #e2e8f0);
  border-radius: 8px;
  background: var(--sk-bg-elevated, #fff);
}
.drawer-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13px;
}
.row {
  display: flex;
  gap: 10px;
  line-height: 1.5;
}
.row .k {
  width: 56px;
  flex-shrink: 0;
  color: var(--sk-text-secondary, #64748b);
}
.sub-title {
  margin-top: 8px;
  font-weight: 600;
}
.rel-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rel-list li {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.rel-list .dir {
  color: var(--sk-text-tertiary);
  font-family: Consolas, Monaco, monospace;
}
.rel-list .st {
  margin-left: 4px;
}
.rel-list .muted,
.muted {
  color: var(--sk-text-tertiary);
}
.edge-actions {
  margin-top: 16px;
  display: flex;
  gap: 8px;
}
</style>
