<script setup lang="ts">
import { ref, onMounted, onUnmounted, shallowRef, nextTick, watch } from 'vue'
import { usePolling } from '@/composables/usePolling'
import { useThemeStore } from '@/stores/theme'
import type { MetricsResponse } from '@/types/api'
import { Chart, DoughnutController, BarController, LineController, ArcElement, BarElement, LineElement, PointElement, CategoryScale, LinearScale, Legend, Tooltip, Filler } from 'chart.js'
import AppHeader from '@/components/layout/AppHeader.vue'
import UCard from '@/components/ui/UCard.vue'
import UButton from '@/components/ui/UButton.vue'

Chart.register(DoughnutController, BarController, LineController, ArcElement, BarElement, LineElement, PointElement, CategoryScale, LinearScale, Legend, Tooltip, Filler)

const theme = useThemeStore()
const data = ref<MetricsResponse | null>(null)
const interval = ref(5)
const chartInstances = shallowRef<Record<string, Chart | null>>({})
const chartInit = ref(false)

function destroyCharts() { Object.values(chartInstances.value).forEach(c => c?.destroy()); chartInstances.value = {}; chartInit.value = false }
function getCtx(id: string) { const el = document.getElementById(id) as HTMLCanvasElement | null; return el ? el.getContext('2d') : null }
function getColor(name: string) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim() }

/** 首次创建所有图表 */
function createCharts(d: MetricsResponse) {
  const tc = getCtx('c-tool'); if (tc) chartInstances.value.tool = new Chart(tc, { type: 'doughnut', data: { labels: ['成功', '失败'], datasets: [{ data: [d.tool.success || 0, d.tool.failure || 0], backgroundColor: [getColor('--success'), getColor('--danger')], borderWidth: 0, borderRadius: 4 }] }, options: { cutout: '60%', plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, padding: 12, font: { size: 11 } } } } } })
  const sc = getCtx('c-skill'); if (sc) chartInstances.value.skill = new Chart(sc, { type: 'doughnut', data: { labels: ['成功', '失败'], datasets: [{ data: [d.skillGen.success || 0, d.skillGen.failure || 0], backgroundColor: [getColor('--brand'), getColor('--warning')], borderWidth: 0, borderRadius: 4 }] }, options: { cutout: '60%', plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, padding: 12, font: { size: 11 } } } } } })
  const pc = getCtx('c-phase'); if (pc) chartInstances.value.phase = new Chart(pc, { type: 'bar', data: { labels: ['拆解', '工具映射', '步骤生成', '组装'], datasets: [{ label: '平均耗时 ms', data: [parse(d.skillGen.phaseDecompose), parse(d.skillGen.phaseToolMap), parse(d.skillGen.phaseStepGen), parse(d.skillGen.phaseAssembly)], backgroundColor: ['#3B82F6', '#8B5CF6', '#06B6D4', '#10B981'], borderRadius: 6, borderWidth: 0 }] }, options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { font: { size: 10 } } }, x: { ticks: { font: { size: 10 } } } } } })
  createTrendChart()
  chartInit.value = true
}

/** 增量更新图表数据（不 destroy/recreate canvas） */
function updateCharts(d: MetricsResponse) {
  const ci = chartInstances.value
  // doughnut: tool
  const t = ci.tool; if (t) { t.data.datasets[0].data = [d.tool.success || 0, d.tool.failure || 0]; t.update('none') }
  // doughnut: skill
  const s = ci.skill; if (s) { s.data.datasets[0].data = [d.skillGen.success || 0, d.skillGen.failure || 0]; s.update('none') }
  // bar: phase
  const p = ci.phase; if (p) { p.data.datasets[0].data = [parse(d.skillGen.phaseDecompose), parse(d.skillGen.phaseToolMap), parse(d.skillGen.phaseStepGen), parse(d.skillGen.phaseAssembly)]; p.update('none') }
  // line: trend — 重建（历史数据增量的标签+数据可能变化）
  updateTrendChart()
}

/** 趋势图独立处理：数据点数量可能增加，需要替换整个 data */
function createTrendChart() {
  const h = storageGet()
  const rc = getCtx('c-trend');
  if (rc && h.length > 1) {
    chartInstances.value.trend = new Chart(rc, {
      type: 'line',
      data: {
        labels: h.map((x: any) => new Date(x.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })),
        datasets: [
          { label: '总请求', data: h.map((x: any) => x.chatTotal), borderColor: getColor('--brand'), borderWidth: 2, tension: 0.3, pointRadius: 0, fill: true, backgroundColor: getColor('--brand-soft') },
          { label: '成功', data: h.map((x: any) => x.chatSuccess), borderColor: getColor('--success'), borderWidth: 2, tension: 0.3, pointRadius: 0, fill: false },
        ],
      },
      options: { interaction: { intersect: false, mode: 'index' }, plugins: { legend: { position: 'bottom', labels: { boxWidth: 10, padding: 12, font: { size: 10 }, usePointStyle: true } } }, scales: { y: { beginAtZero: true, ticks: { font: { size: 10 } } }, x: { ticks: { font: { size: 9 }, maxTicksLimit: 8 } } } },
    })
  }
}

function updateTrendChart() {
  const tc = chartInstances.value.trend
  if (!tc) { createTrendChart(); return }
  const h = storageGet()
  if (h.length <= 1) { tc.destroy(); chartInstances.value.trend = null; return }
  tc.data.labels = h.map((x: any) => new Date(x.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }))
  tc.data.datasets[0].data = h.map((x: any) => x.chatTotal)
  tc.data.datasets[1].data = h.map((x: any) => x.chatSuccess)
  tc.update('none')
}

/** 处理数据：KPI/详情文本自动响应式更新；图表按需增量/首次创建 */
function handleData(d: MetricsResponse) {
  data.value = d
  recordHistory(d)
  nextTick(() => {
    if (!chartInit.value) {
      createCharts(d)
    } else {
      updateCharts(d)
    }
  })
}

// 主题变化时完全重建图表（颜色需重新读取 CSS 变量）
watch(() => theme.isDark, () => {
  nextTick(() => {
    destroyCharts()
    if (data.value) createCharts(data.value)
  })
})

function storageGet() {
  try { return JSON.parse(localStorage.getItem('agent_monitor_history') || '[]') } catch { return [] }
}
function recordHistory(d: MetricsResponse) {
  const h = storageGet(); h.push({ ts: Date.now(), chatTotal: d.chat.total, chatSuccess: d.chat.success, chatFailure: d.chat.failure, sseActive: d.sse.active, skillGenTotal: d.skillGen.total, skillGenSuccess: d.skillGen.success })
  while (h.length > 30) h.shift()
  try { localStorage.setItem('agent_monitor_history', JSON.stringify(h)) } catch { /* */ }
}

const { start, stop } = usePolling<MetricsResponse>('/api/v1/metrics', interval.value * 1000, handleData)

function onChangeIntv(val: number) { interval.value = val; stop(); start() }
function exportJSON() { const b = new Blob([JSON.stringify(data.value, null, 2)], { type: 'application/json' }); const u = URL.createObjectURL(b); const a = document.createElement('a'); a.href = u; a.download = 'metrics.json'; a.click(); URL.revokeObjectURL(u) }

function parse(v?: string): number { if (!v) return 0; const n = v.replace('ms', ''); return parseFloat(n) || 0 }

onMounted(() => start())
onUnmounted(() => { stop(); destroyCharts() })
</script>

<template>
  <div class="mp">
    <AppHeader title="📊 监控面板">
      <template #actions>
        <select v-model.number="interval" @change="onChangeIntv(interval)" class="msel">
          <option :value="5">5s</option><option :value="10">10s</option><option :value="30">30s</option><option :value="0">暂停</option>
        </select>
        <UButton variant="ghost" size="sm" @click="exportJSON">📥 导出</UButton>
      </template>
    </AppHeader>
    <div class="mbd">
      <!-- KPI -->
      <div class="mkpi">
        <UCard padded><div class="kc"><div class="kci" :class="data?.nacos.connected ? 'kg' : 'kr'">🔗</div><div><div class="kv">{{ data?.nacos.connected ? '已连接' : '断开' }}</div><div class="kl">Nacos · {{ data?.nacos.loadedSkills || 0 }} 技能</div></div></div></UCard>
        <UCard padded><div class="kc"><div class="kci kb">💬</div><div><div class="kv">{{ data?.chat.total || 0 }}</div><div class="kl">对话 · {{ data?.chat.failure || 0 }} 失败</div></div></div></UCard>
        <UCard padded><div class="kc"><div class="kci" :class="(data?.sse.active||0) > 0 ? 'kg' : 'ka'">📡</div><div><div class="kv">{{ data?.sse.active || 0 }}</div><div class="kl">活跃 SSE</div></div></div></UCard>
        <UCard padded><div class="kc"><div class="kci kb">⚡</div><div><div class="kv">{{ data?.skillGen.total || 0 }}</div><div class="kl">Skill 生成 · {{ data?.skillGen.success || 0 }} 成功</div></div></div></UCard>
      </div>

      <!-- Grid -->
      <div class="mgd">
        <UCard padded><div class="ch">💬 对话</div><div class="mv">{{ data?.chat.total || 0 }}<span class="ml">总请求</span></div><div class="mr"><span>成功</span><span class="ok">{{ data?.chat.success || 0 }}</span></div><div class="mr"><span>失败</span><span class="err">{{ data?.chat.failure || 0 }}</span></div><div class="mr"><span>重试</span><span class="warn">{{ data?.chat.retry || 0 }}</span></div><div class="mr"><span>平均</span><span>{{ data?.chat.durationAvg || '--' }}</span></div></UCard>
        <UCard padded><div class="ch">📡 SSE</div><div class="mv" :style="{color:(data?.sse.active||0)>0?'var(--success)':'var(--text-tertiary)'}">{{ data?.sse.active || 0 }}<span class="ml">活跃连接</span></div><div class="mr"><span>总连接</span><span>{{ data?.sse.connections || 0 }}</span></div><div class="mr"><span>完成</span><span class="ok">{{ data?.sse.completed || 0 }}</span></div><div class="mr"><span>超时</span><span class="warn">{{ data?.sse.timeout || 0 }}</span></div><div class="mr"><span>异常</span><span class="err">{{ data?.sse.error || 0 }}</span></div></UCard>
        <UCard padded><div class="ch">🔧 工具调用</div><div class="mv">{{ data?.tool.total || 0 }}<span class="ml">总调用</span></div><div class="mr"><span>成功</span><span class="ok">{{ data?.tool.success || 0 }}</span></div><div class="mr"><span>失败</span><span class="err">{{ data?.tool.failure || 0 }}</span></div><div class="cw"><canvas id="c-tool" /></div></UCard>
        <UCard padded><div class="ch">⚡ Skill 生成</div><div class="mv">{{ data?.skillGen.total || 0 }}<span class="ml">总生成</span></div><div class="mr"><span>成功</span><span class="ok">{{ data?.skillGen.success || 0 }}</span></div><div class="mr"><span>失败</span><span class="err">{{ data?.skillGen.failure || 0 }}</span></div><div class="mr"><span>平均</span><span>{{ data?.skillGen.durationAvg || '--' }}</span></div><div class="cw"><canvas id="c-skill" /></div></UCard>
        <UCard padded><div class="ch">⏱ 阶段耗时 (avg)</div><div class="mr"><span>拆解</span><span>{{ data?.skillGen.phaseDecompose || '--' }}</span></div><div class="mr"><span>工具映射</span><span>{{ data?.skillGen.phaseToolMap || '--' }}</span></div><div class="mr"><span>步骤生成</span><span>{{ data?.skillGen.phaseStepGen || '--' }}</span></div><div class="mr"><span>组装</span><span>{{ data?.skillGen.phaseAssembly || '--' }}</span></div><div class="cw"><canvas id="c-phase" /></div></UCard>
        <UCard padded><div class="ch">📈 趋势</div><div class="cw"><canvas id="c-trend" /></div></UCard>
        <UCard padded v-if="data?.threadPool?.gauge && Object.keys(data.threadPool.gauge).length"><div class="ch">🧵 线程池</div><div v-for="(v,k) in data.threadPool.gauge" :key="k" class="mr"><span>{{ k.replace('agent.threadpool.', '').replace('.',' · ') }}</span><span>{{ v }}</span></div></UCard>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mp{display:flex;flex-direction:column;height:100%}
.mbd{flex:1;overflow-y:auto;padding:20px 24px}
.mkpi{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:18px}
.kc{display:flex;align-items:center;gap:12px}
.kci{width:42px;height:42px;border-radius:var(--r-md);display:flex;align-items:center;justify-content:center;font-size:20px;flex-shrink:0}
.kg{background:var(--success-soft)}.kr{background:var(--danger-soft)}.kb{background:var(--brand-soft)}.ka{background:var(--warning-soft)}
.kv{font-size:var(--text-2xl);font-weight:700;line-height:1.2}
.kl{font-size:10px;color:var(--text-tertiary)}
.mgd{display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));gap:14px}
.ch{font-size:var(--text-sm);font-weight:600;color:var(--text-secondary);margin-bottom:10px;padding-bottom:8px;border-bottom:1px solid var(--border)}
.mv{font-size:28px;font-weight:700;margin-bottom:4px}.ml{display:block;font-size:11px;color:var(--text-tertiary);font-weight:400;margin-top:2px}
.mr{display:flex;justify-content:space-between;padding:5px 0;border-bottom:1px solid var(--border);font-size:var(--text-sm);color:var(--text-secondary)}.mr:last-child{border-bottom:none}
.ok{color:var(--success)}.err{color:var(--danger)}.warn{color:var(--warning);font-weight:600}.info{color:var(--info)}
.cw{position:relative;width:100%;height:180px;margin-top:8px}.cw canvas{width:100%!important;height:100%!important}
.msel{padding:4px 8px;border:1px solid var(--border-strong);border-radius:var(--r-sm);font-size:11px;background:var(--bg-surface);color:var(--text-primary)}
@media(max-width:768px){.mkpi{grid-template-columns:repeat(2,1fr)}.mgd{grid-template-columns:1fr}}
</style>
