<template>
  <div class="rel-manage">
    <div class="head">
      <span class="title">表关系管理</span>
      <div class="filters">
        <el-select v-model="dsId" size="small" filterable placeholder="数据源" style="width: 200px" @change="onSearch">
          <el-option v-for="d in appStore.datasources" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-select v-model="status" size="small" style="width: 120px" @change="onSearch">
          <el-option label="全部" value="" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="待审" value="PENDING" />
          <el-option label="已拒绝" value="REJECTED" />
        </el-select>
        <el-input
          v-model="keyword"
          size="small"
          clearable
          placeholder="表名或字段名"
          style="width: 180px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-button size="small" @click="onSearch">搜索</el-button>
        <el-button size="small" @click="openSettings">设置</el-button>
      </div>
    </div>

    <div class="table-wrap">
      <el-table :data="items" size="small" border stripe height="100%" v-loading="loading">
        <el-table-column label="源表.字段" min-width="180">
          <template #default="{ row }">{{ row.sourceTable }}.{{ row.sourceColumn }}</template>
        </el-table-column>
        <el-table-column label="" width="40" align="center">
          <template #default>→</template>
        </el-table-column>
        <el-table-column label="目标表.字段" min-width="180">
          <template #default="{ row }">{{ row.targetTable }}.{{ row.targetColumn }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="barPercent(row.confidence)"
              :format="() => String(row.confidence ?? 0)"
              :stroke-width="10"
            />
          </template>
        </el-table-column>
        <el-table-column prop="sources" label="来源" min-width="140" show-overflow-tooltip />
        <el-table-column prop="lastUsedAt" label="最后使用" width="160" />
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button link type="primary" size="small" @click="onConfirm(row)">确认</el-button>
              <el-button link type="danger" size="small" @click="onReject(row)">拒绝</el-button>
            </template>
            <el-button v-else-if="row.status === 'CONFIRMED'" link type="warning" size="small" @click="onReject(row)">
              禁用
            </el-button>
            <el-button v-else link type="primary" size="small" @click="onReset(row)">恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="pager">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        @current-change="onPage"
      />
    </div>

    <el-drawer v-model="settingsOpen" title="关系学习设置" size="420px">
      <div class="cfg" v-loading="cfgLoading">
        <div class="cfg-row">
          <span>晋升阈值</span>
          <el-slider v-model="form.threshold" :min="1" :max="10" show-input />
        </div>
        <div class="cfg-row">
          <span>执行成功</span>
          <el-input-number v-model="form.sqlSuccess" :step="1" />
        </div>
        <div class="cfg-row">
          <span>结果有行</span>
          <el-input-number v-model="form.rowCount" :step="1" />
        </div>
        <div class="cfg-row">
          <span>保存到脚本</span>
          <el-input-number v-model="form.saved" :step="1" />
        </div>
        <div class="cfg-row">
          <span>手工确认</span>
          <el-input-number v-model="form.confirmed" :step="1" />
        </div>
        <div class="cfg-row">
          <span>手工拒绝</span>
          <el-input-number v-model="form.rejected" :step="1" />
        </div>
        <div class="cfg-block">
          <span>排除字段名</span>
          <div class="tags">
            <el-tag v-for="t in form.excluded" :key="t" closable size="small" @close="removeTag(form.excluded, t)">
              {{ t }}
            </el-tag>
            <el-input v-model="excludedInput" size="small" placeholder="回车添加" style="width: 120px" @keyup.enter="addExcluded" />
          </div>
        </div>
        <div class="cfg-block">
          <span>临时表前缀</span>
          <div class="tags">
            <el-tag v-for="t in form.prefixes" :key="t" closable size="small" @close="removeTag(form.prefixes, t)">
              {{ t }}
            </el-tag>
            <el-input v-model="prefixInput" size="small" placeholder="回车添加" style="width: 120px" @keyup.enter="addPrefix" />
          </div>
        </div>
        <el-button type="primary" :loading="savingCfg" @click="saveConfig">保存</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  confirmRelation,
  getRelationConfig,
  listRelations,
  rejectRelation,
  resetRelation,
  updateRelationConfig
} from '../../api/relation'
import { useAppStore } from '../../stores/app'

const props = defineProps({
  tab: { type: Object, default: null }
})

const appStore = useAppStore()
const dsId = ref(props.tab?.props?.datasourceId || appStore.currentDatasourceId || '')
const status = ref('')
const keyword = ref('')
const page = ref(1)
const pageSize = 20
const total = ref(0)
const items = ref([])
const loading = ref(false)

const settingsOpen = ref(false)
const cfgLoading = ref(false)
const savingCfg = ref(false)
const excludedInput = ref('')
const prefixInput = ref('')
const form = reactive({
  threshold: 3,
  sqlSuccess: 1,
  rowCount: 1,
  saved: 5,
  confirmed: 10,
  rejected: -100,
  excluded: [],
  prefixes: []
})

watch(
  () => props.tab?.props?.datasourceId,
  (v) => {
    if (v && v !== dsId.value) {
      dsId.value = v
      onSearch()
    }
  }
)

watch(
  () => props.tab?.props?.reloadToken,
  () => {
    const v = props.tab?.props?.datasourceId
    if (v) dsId.value = v
    load()
  }
)

function statusType(s) {
  if (s === 'CONFIRMED') return 'success'
  if (s === 'PENDING') return 'warning'
  return 'info'
}

function statusLabel(s) {
  if (s === 'CONFIRMED') return '已确认'
  if (s === 'PENDING') return '待审'
  if (s === 'REJECTED') return '已拒绝'
  return s || '-'
}

function barPercent(n) {
  const v = Number(n) || 0
  return Math.max(0, Math.min(100, Math.round((v / 20) * 100)))
}

function onSearch() {
  page.value = 1
  load()
}

function onPage(p) {
  page.value = p
  load()
}

async function load() {
  if (!dsId.value) {
    items.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await listRelations({
      datasourceId: dsId.value,
      status: status.value || undefined,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: pageSize
    })
    items.value = Array.isArray(res?.items) ? res.items : []
    total.value = Number(res?.total) || 0
  } catch {
    items.value = []
  } finally {
    loading.value = false
  }
}

async function onConfirm(row) {
  try {
    await confirmRelation(row.id)
    ElMessage.success('已确认')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function onReject(row) {
  try {
    await rejectRelation(row.id)
    ElMessage.success(row.status === 'CONFIRMED' ? '已禁用' : '已拒绝')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function onReset(row) {
  try {
    await resetRelation(row.id)
    ElMessage.success('已恢复为待审')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

function splitCsv(raw) {
  if (!raw) return []
  return String(raw)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

async function openSettings() {
  settingsOpen.value = true
  cfgLoading.value = true
  try {
    const cfg = await getRelationConfig()
    form.threshold = Number(cfg?.['confidence-threshold'] ?? 3)
    form.sqlSuccess = Number(cfg?.['score.sql-success'] ?? 1)
    form.rowCount = Number(cfg?.['score.row-count-positive'] ?? 1)
    form.saved = Number(cfg?.['score.saved-to-script'] ?? 5)
    form.confirmed = Number(cfg?.['score.user-confirmed'] ?? 10)
    form.rejected = Number(cfg?.['score.user-rejected'] ?? -100)
    form.excluded = splitCsv(cfg?.['excluded-column-names'])
    form.prefixes = splitCsv(cfg?.['temp-table-prefixes'])
  } catch {
    /* 拦截器已提示 */
  } finally {
    cfgLoading.value = false
  }
}

function removeTag(list, tag) {
  const i = list.indexOf(tag)
  if (i >= 0) list.splice(i, 1)
}

function addExcluded() {
  const v = excludedInput.value.trim().toLowerCase()
  if (v && !form.excluded.includes(v)) form.excluded.push(v)
  excludedInput.value = ''
}

function addPrefix() {
  const v = prefixInput.value.trim().toLowerCase()
  if (v && !form.prefixes.includes(v)) form.prefixes.push(v)
  prefixInput.value = ''
}

async function saveConfig() {
  savingCfg.value = true
  try {
    await updateRelationConfig({
      'confidence-threshold': String(form.threshold),
      'score.sql-success': String(form.sqlSuccess),
      'score.row-count-positive': String(form.rowCount),
      'score.saved-to-script': String(form.saved),
      'score.user-confirmed': String(form.confirmed),
      'score.user-rejected': String(form.rejected),
      'excluded-column-names': form.excluded.join(','),
      'temp-table-prefixes': form.prefixes.join(',')
    })
    ElMessage.success('已保存')
    settingsOpen.value = false
  } catch {
    /* 拦截器已提示 */
  } finally {
    savingCfg.value = false
  }
}

onMounted(async () => {
  if (!appStore.datasources.length) {
    try {
      await appStore.loadDatasources()
    } catch {
      /* ignore */
    }
  }
  if (!dsId.value && appStore.currentDatasourceId) {
    dsId.value = appStore.currentDatasourceId
  }
  await load()
})
</script>

<style scoped>
.rel-manage {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 12px;
  gap: 10px;
  min-height: 0;
  box-sizing: border-box;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.title {
  font-weight: 600;
  font-size: 14px;
  white-space: nowrap;
}
.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.table-wrap {
  flex: 1;
  min-height: 0;
}
.pager {
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
}
.cfg {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-right: 8px;
}
.cfg-row,
.cfg-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
</style>
