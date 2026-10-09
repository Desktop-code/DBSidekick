<template>
  <div class="conn-manage">
    <div class="head">
      <span class="title">连接管理</span>
      <el-button type="primary" size="small" @click="openCreate">+ 新建连接</el-button>
    </div>
    <div class="table-wrap">
      <el-table :data="rows" size="small" border stripe height="100%" v-loading="loading">
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column label="地址" min-width="200">
          <template #default="{ row }">
            {{ row.host }}:{{ row.port }}/{{ row.database }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <span class="lamp" :title="statusTitle(row.id)">
              <i class="dot" :class="statusClass(row.id)" />
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" :loading="testingId === row.id" @click="onTest(row)">
              测试
            </el-button>
            <el-button link type="danger" size="small" @click="onRemove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <DbConfigDialog
      v-model="dialogVisible"
      :mode="dialogMode"
      :initial="dialogInitial"
      @saved="onSaved"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DbConfigDialog from '../DbConfigDialog.vue'
import { removeDatasource, testDatasource } from '../../api/datasource'
import { useAppStore } from '../../stores/app'

const props = defineProps({
  tab: { type: Object, default: null }
})

const appStore = useAppStore()
const loading = ref(false)
const testingId = ref('')
const dialogVisible = ref(false)
const dialogMode = ref('create')
const dialogInitial = ref(null)

const rows = computed(() => appStore.datasources)

function statusClass(id) {
  const s = appStore.datasourceStatus[id]
  if (s === 'up') return 'up'
  if (s === 'down') return 'down'
  return 'unknown'
}

function statusTitle(id) {
  const s = appStore.datasourceStatus[id]
  if (s === 'up') return '连通'
  if (s === 'down') return '断开'
  return '未测试'
}

function openCreate() {
  dialogMode.value = 'create'
  dialogInitial.value = null
  dialogVisible.value = true
}

function openEdit(row) {
  dialogMode.value = 'edit'
  dialogInitial.value = { ...row }
  dialogVisible.value = true
}

async function onSaved() {
  await appStore.loadDatasources()
  await appStore.refreshDatasourceStatuses()
}

async function onTest(row) {
  testingId.value = row.id
  try {
    const res = await testDatasource({
      id: row.id,
      name: row.name,
      type: row.type,
      host: row.host,
      port: row.port,
      database: row.database,
      schema: row.schema,
      username: row.username,
      password: ''
    })
    appStore.setDatasourceStatus(row.id, res?.success ? 'up' : 'down')
    if (res?.success) {
      ElMessage.success(`连接成功（${res.latencyMs ?? 0} ms）`)
    } else {
      ElMessage.error(res?.message || '连接失败')
    }
  } catch (e) {
    appStore.setDatasourceStatus(row.id, 'down')
    ElMessage.error(e?.message || '连接失败')
  } finally {
    testingId.value = ''
  }
}

async function onRemove(row) {
  try {
    await ElMessageBox.confirm(`确定删除连接「${row.name}」？相关 Schema 缓存也会清除。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await removeDatasource(row.id)
    if (appStore.currentDatasourceId === row.id) {
      appStore.setCurrentDatasource(null)
    }
    await appStore.loadDatasources()
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

watch(
  () => props.tab?.props?.openCreateToken ?? props.tab?.openCreateToken,
  (token) => {
    if (token) openCreate()
  }
)

onMounted(async () => {
  loading.value = true
  try {
    await appStore.loadDatasources()
    await appStore.refreshDatasourceStatuses()
    if (props.tab?.props?.openCreateToken || props.tab?.openCreateToken) {
      openCreate()
    }
  } finally {
    loading.value = false
  }
})

defineExpose({ openCreate, openEdit })
</script>

<style scoped>
.conn-manage {
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
  flex-shrink: 0;
}
.title {
  font-weight: 600;
  font-size: 14px;
}
.table-wrap {
  flex: 1;
  min-height: 0;
}
.lamp {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #c0c4cc;
  display: inline-block;
}
.dot.up {
  background: #67c23a;
}
.dot.down {
  background: #f56c6c;
}
.dot.unknown {
  background: #c0c4cc;
}
</style>
