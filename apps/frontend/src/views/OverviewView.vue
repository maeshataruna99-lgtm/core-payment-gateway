<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import LoadState from '@/components/LoadState.vue'
import MerchantSelect from '@/components/MerchantSelect.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useLoad } from '@/composables/useLoad'
import { authed, isStaff } from '@/lib/auth'
import { dateTime, methodName, rupiah } from '@/lib/format'
import type { PaymentRow, PaymentStats, PaymentStatus, Settlement } from '@/lib/types'

const merchantId = ref<string | null>(null)

const query = () => (merchantId.value ? `?merchantId=${merchantId.value}` : '')

const { data, loading, error, forbidden, reload, lastUpdated } = useLoad(async () => {
  const [stats, refunds, recent, preview] = await Promise.all([
    authed<PaymentStats>(`/api/v1/payment-stats${query()}`),
    authed<{ count: number; amount: number }>(`/api/v1/refund-stats${query()}`),
    authed<{ items: PaymentRow[] }>(
      `/api/v1/payments${query()}${query() ? '&' : '?'}size=5`,
    ),
    isStaff.value && merchantId.value
      ? authed<Settlement>('/api/v1/settlements/preview', {
          method: 'POST',
          body: { merchantId: merchantId.value },
        })
      : Promise.resolve(null),
  ])
  return { stats, refunds, recent: recent.items, preview }
}, 5000)

watch(merchantId, () => void reload())

const STATES: { name: PaymentStatus; color: string }[] = [
  { name: 'SUCCEEDED', color: 'bg-green-800' },
  { name: 'PENDING', color: 'bg-amber-700' },
  { name: 'CREATED', color: 'bg-amber-400' },
  { name: 'FAILED', color: 'bg-red-700' },
  { name: 'EXPIRED', color: 'bg-neutral-500' },
  { name: 'UNKNOWN', color: 'bg-neutral-950' },
]

const successRate = computed(() => {
  const stats = data.value?.stats
  if (!stats || stats.total === 0) {
    return '-'
  }
  return `${((stats.counts.SUCCEEDED / stats.total) * 100).toLocaleString('id-ID', {
    maximumFractionDigits: 1,
  })}%`
})

const kpis = computed(() => {
  const value = data.value
  if (!value) {
    return []
  }
  return [
    {
      label: 'Volume simulasi',
      value: rupiah(value.stats.succeededAmount),
      note: 'Hanya attempt SUCCEEDED',
    },
    {
      label: 'Tingkat sukses',
      value: successRate.value,
      note: `${value.stats.counts.SUCCEEDED} dari ${value.stats.total} attempt`,
    },
    { label: 'Refund simulasi', value: rupiah(value.refunds.amount), note: `${value.refunds.count} refund` },
    {
      label: 'Perlu perhatian',
      value: String(value.stats.counts.UNKNOWN),
      note: 'UNKNOWN, hasil belum pasti',
    },
  ]
})

function percent(count: number): string {
  const total = data.value?.stats.total ?? 0
  return total === 0 ? '0%' : `${(count / total) * 100}%`
}
</script>

<template>
  <div class="flex flex-wrap items-end justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">Ringkasan</h1>
      <p class="text-sm text-muted-foreground">
        Data dari simulator. Dimuat ulang tiap 5 detik<template v-if="lastUpdated">, terakhir
          {{ lastUpdated.toLocaleTimeString('id-ID', { timeZone: 'Asia/Jakarta' }) }} WIB</template>.
      </p>
    </div>
    <MerchantSelect v-if="isStaff" v-model="merchantId" />
  </div>

  <LoadState :loading="loading" :error="error" :forbidden="forbidden" :has-data="data !== null">
    <div v-if="data" class="flex flex-col gap-4">
      <div class="grid grid-cols-[repeat(auto-fit,minmax(11rem,1fr))] gap-4">
        <Card v-for="kpi in kpis" :key="kpi.label" class="gap-1 py-4">
          <CardContent class="flex flex-col gap-1 px-4">
            <div class="text-sm text-muted-foreground">{{ kpi.label }}</div>
            <div class="text-2xl font-semibold tabular-nums">{{ kpi.value }}</div>
            <div class="text-xs text-muted-foreground">{{ kpi.note }}</div>
          </CardContent>
        </Card>
      </div>

      <div class="grid grid-cols-[repeat(auto-fit,minmax(18rem,1fr))] gap-4">
        <Card>
          <CardHeader>
            <CardTitle><h2 class="text-sm font-semibold">Status attempt</h2></CardTitle>
            <CardDescription>{{ data.stats.total }} attempt</CardDescription>
          </CardHeader>
          <CardContent class="flex flex-col gap-3">
            <div
              v-for="state in STATES"
              :key="state.name"
              class="grid grid-cols-[6.5rem_1fr_2.5rem] items-center gap-3"
            >
              <span class="text-xs font-medium">{{ state.name }}</span>
              <div class="h-2 rounded-full bg-muted" aria-hidden="true">
                <div
                  class="h-2 rounded-full"
                  :class="state.color"
                  :style="{ width: percent(data.stats.counts[state.name]) }"
                />
              </div>
              <span class="text-right text-sm font-medium tabular-nums">{{ data.stats.counts[state.name] }}</span>
            </div>
          </CardContent>
        </Card>

        <Card v-if="data.preview">
          <CardHeader>
            <CardTitle><h2 class="text-sm font-semibold">Preview settlement</h2></CardTitle>
            <CardDescription>Belum final, belum tersimpan.</CardDescription>
          </CardHeader>
          <CardContent class="flex flex-col gap-3 text-sm tabular-nums">
            <div class="flex justify-between"><span>Gross</span><span>{{ rupiah(data.preview.gross) }}</span></div>
            <div class="flex justify-between">
              <span>Fee simulasi</span><span>{{ rupiah(-data.preview.fees) }}</span>
            </div>
            <div class="flex justify-between">
              <span>Refund</span><span>{{ rupiah(-data.preview.refunds) }}</span>
            </div>
            <div class="flex justify-between">
              <span>Adjustment</span><span>{{ rupiah(data.preview.adjustments) }}</span>
            </div>
            <div class="flex justify-between border-t pt-3 font-semibold">
              <span>Net</span><span>{{ rupiah(data.preview.net) }}</span>
            </div>
            <RouterLink to="/settlement" class="font-medium text-primary underline underline-offset-4">
              Buka settlement
            </RouterLink>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle><h2 class="text-sm font-semibold">Aktivitas terbaru</h2></CardTitle>
        </CardHeader>
        <CardContent>
          <p v-if="data.recent.length === 0" class="py-6 text-center text-sm text-muted-foreground">
            Belum ada transaksi simulasi.
          </p>
          <div v-else class="overflow-x-auto">
            <Table class="min-w-[36rem]">
              <TableHeader>
                <TableRow>
                  <TableHead>Waktu</TableHead>
                  <TableHead>Channel</TableHead>
                  <TableHead class="text-right">Jumlah</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow v-for="row in data.recent" :key="row.paymentId">
                  <TableCell class="text-muted-foreground">{{ dateTime(row.createdAt) }}</TableCell>
                  <TableCell>{{ methodName(row.method) }}</TableCell>
                  <TableCell class="text-right tabular-nums">{{ rupiah(row.amount) }}</TableCell>
                  <TableCell><StatusBadge :status="row.status" /></TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </div>
          <RouterLink to="/monitor" class="mt-3 inline-block text-sm font-medium text-primary underline underline-offset-4">
            Lihat semua transaksi
          </RouterLink>
        </CardContent>
      </Card>
    </div>
  </LoadState>
</template>
