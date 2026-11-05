<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import LoadState from '@/components/LoadState.vue'
import MerchantSelect from '@/components/MerchantSelect.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useLoad } from '@/composables/useLoad'
import { ApiError, newIdempotencyKey } from '@/lib/api'
import { authed, isStaff } from '@/lib/auth'
import { dateTime, methodName, rupiah } from '@/lib/format'
import type { PaymentDetail, PaymentRow, PaymentStatus, Refund } from '@/lib/types'

const ALL = 'ALL'
const STATUSES: PaymentStatus[] = ['CREATED', 'PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'EXPIRED']
const EXPLANATIONS: Record<PaymentStatus, string> = {
  CREATED: 'Attempt tersimpan. Provider simulasi belum menjawab.',
  PENDING: 'Menunggu jawaban provider simulasi. Pending belum berarti sukses.',
  UNKNOWN: 'Provider simulasi timeout. Hasilnya belum pasti, jadi tidak dianggap gagal.',
  SUCCEEDED: 'Provider simulasi mengonfirmasi pembayaran.',
  FAILED: 'Provider simulasi menolak pembayaran.',
  EXPIRED: 'Batas waktu habis tanpa pembayaran. Sukses yang telat tetap akan dicatat.',
}

const status = ref(ALL)
const method = ref(ALL)
const merchantId = ref<string | null>(null)
const selectedId = ref<string | null>(null)

const { data, loading, error, forbidden, reload } = useLoad(async () => {
  const params = new URLSearchParams({ size: '50' })
  if (status.value !== ALL) params.set('status', status.value)
  if (method.value !== ALL) params.set('method', method.value)
  if (merchantId.value) params.set('merchantId', merchantId.value)
  return (await authed<{ items: PaymentRow[] }>(`/api/v1/payments?${params}`)).items
}, 5000)

const detail = useLoad(async () => {
  if (!selectedId.value) {
    return null
  }
  const [payment, refunds] = await Promise.all([
    authed<PaymentDetail>(`/api/v1/payments/${selectedId.value}`),
    authed<{ refundableAmount: number; items: Refund[] }>(`/api/v1/payments/${selectedId.value}/refunds`),
  ])
  return { payment, refunds }
}, 5000)

watch([status, method, merchantId], () => void reload())
watch(selectedId, () => void detail.reload())

const canRefund = computed(
  () => detail.data.value?.payment.status === 'SUCCEEDED' && detail.data.value.refunds.refundableAmount > 0,
)

const refundOpen = ref(false)
const refundAmount = ref<number>()
const refundReason = ref('')
const refundError = ref<string | null>(null)
const refundBusy = ref(false)
let refundKey = ''

function openRefund(): void {
  refundAmount.value = detail.data.value?.refunds.refundableAmount
  refundReason.value = ''
  refundError.value = null
  // One key per dialog, so a double submit cannot refund twice.
  refundKey = newIdempotencyKey()
  refundOpen.value = true
}

async function submitRefund(): Promise<void> {
  refundBusy.value = true
  refundError.value = null
  try {
    await authed(`/api/v1/payments/${selectedId.value}/refunds`, {
      method: 'POST',
      headers: { 'Idempotency-Key': refundKey },
      body: { amount: refundAmount.value, reason: refundReason.value },
    })
    refundOpen.value = false
    await detail.reload()
  } catch (e) {
    refundError.value = e instanceof ApiError ? e.message : 'Refund gagal.'
  } finally {
    refundBusy.value = false
  }
}
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold tracking-tight">Monitor transaksi</h1>
    <p class="text-sm text-muted-foreground">Event berasal dari simulator. Dimuat ulang tiap 5 detik.</p>
  </div>

  <div class="flex flex-wrap items-end gap-3">
    <div class="flex flex-col gap-1.5">
      <Label for="filter-status">Status</Label>
      <Select v-model="status">
        <SelectTrigger id="filter-status" class="min-w-40 data-[size=default]:h-11"><SelectValue /></SelectTrigger>
        <SelectContent>
          <SelectItem :value="ALL">Semua</SelectItem>
          <SelectItem v-for="item in STATUSES" :key="item" :value="item">{{ item }}</SelectItem>
        </SelectContent>
      </Select>
    </div>
    <div class="flex flex-col gap-1.5">
      <Label for="filter-method">Channel</Label>
      <Select v-model="method">
        <SelectTrigger id="filter-method" class="min-w-40 data-[size=default]:h-11"><SelectValue /></SelectTrigger>
        <SelectContent>
          <SelectItem :value="ALL">Semua</SelectItem>
          <SelectItem value="QRIS">QRIS simulasi</SelectItem>
          <SelectItem value="VIRTUAL_ACCOUNT">VA simulasi</SelectItem>
        </SelectContent>
      </Select>
    </div>
    <MerchantSelect v-if="isStaff" v-model="merchantId" />
  </div>

  <div class="flex flex-wrap items-start gap-4">
    <Card class="min-w-0 flex-[3_1_28rem] py-2">
      <CardContent class="px-3">
        <LoadState
          :loading="loading"
          :error="error"
          :forbidden="forbidden"
          :empty="data?.length === 0"
          empty-text="Tidak ada transaksi untuk filter ini."
        >
          <div class="overflow-x-auto">
            <Table class="min-w-[32rem]">
              <TableHeader>
                <TableRow>
                  <TableHead>Waktu</TableHead>
                  <TableHead>Channel</TableHead>
                  <TableHead class="text-right">Jumlah</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead><span class="sr-only">Aksi</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow
                  v-for="row in data"
                  :key="row.paymentId"
                  :class="{ 'bg-muted': row.paymentId === selectedId }"
                >
                  <TableCell class="text-muted-foreground">{{ dateTime(row.createdAt) }}</TableCell>
                  <TableCell>{{ methodName(row.method) }}</TableCell>
                  <TableCell class="text-right tabular-nums">{{ rupiah(row.amount) }}</TableCell>
                  <TableCell><StatusBadge :status="row.status" /></TableCell>
                  <TableCell class="text-right">
                    <Button
                      variant="link"
                      class="h-11"
                      :aria-pressed="row.paymentId === selectedId"
                      @click="selectedId = row.paymentId"
                    >
                      Detail
                    </Button>
                  </TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </div>
        </LoadState>
      </CardContent>
    </Card>

    <Card class="min-w-0 flex-[2_1_22rem]">
      <CardHeader>
        <CardTitle><h2 class="text-sm font-semibold">Detail payment</h2></CardTitle>
      </CardHeader>
      <CardContent>
        <p v-if="!selectedId" class="py-6 text-center text-sm text-muted-foreground">
          Pilih transaksi untuk melihat timeline dan buktinya.
        </p>
        <LoadState
          v-else
          :loading="detail.loading.value"
          :error="detail.error.value"
          :has-data="detail.data.value !== null"
        >
          <div v-if="detail.data.value" class="flex flex-col gap-4 text-sm">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <code class="font-mono text-xs break-all">{{ detail.data.value.payment.paymentId }}</code>
              <StatusBadge :status="detail.data.value.payment.status" />
            </div>
            <p>{{ EXPLANATIONS[detail.data.value.payment.status] }}</p>

            <dl class="grid grid-cols-[7rem_1fr] gap-x-3 gap-y-2 border-t pt-4">
              <dt class="text-muted-foreground">Jumlah</dt>
              <dd class="tabular-nums">{{ rupiah(detail.data.value.payment.amount) }}</dd>
              <dt class="text-muted-foreground">Metode</dt>
              <dd>{{ methodName(detail.data.value.payment.method) }}</dd>
              <template v-if="detail.data.value.payment.paidMethod">
                <dt class="text-muted-foreground">Dibayar lewat</dt>
                <dd>{{ methodName(detail.data.value.payment.paidMethod) }}</dd>
              </template>
              <dt class="text-muted-foreground">Provider ref</dt>
              <dd class="font-mono text-xs break-all">{{ detail.data.value.payment.providerReference ?? 'Belum diterima' }}</dd>
              <dt class="text-muted-foreground">Dapat direfund</dt>
              <dd class="tabular-nums">{{ rupiah(detail.data.value.refunds.refundableAmount) }}</dd>
            </dl>

            <Alert
              v-for="item in detail.data.value.payment.discrepancies"
              :key="item.at + item.type"
              variant="destructive"
            >
              <AlertTitle>{{ item.type }}</AlertTitle>
              <AlertDescription>{{ item.detail }}. Perlu ditinjau.</AlertDescription>
            </Alert>

            <div class="border-t pt-4">
              <h3 class="mb-2 font-semibold">Timeline</h3>
              <ol class="flex flex-col gap-2">
                <li
                  v-for="(event, index) in detail.data.value.payment.timeline"
                  :key="index"
                  class="border-l-2 pl-3"
                >
                  <div class="text-xs text-muted-foreground">{{ dateTime(event.at) }}</div>
                  <div>{{ event.event }}: {{ event.from }} ke {{ event.to }}</div>
                </li>
              </ol>
            </div>

            <div class="border-t pt-4">
              <h3 class="mb-2 font-semibold">Refund</h3>
              <p v-if="detail.data.value.refunds.items.length === 0" class="text-muted-foreground">
                Belum ada refund.
              </p>
              <ul v-else class="flex flex-col gap-2">
                <li
                  v-for="item in detail.data.value.refunds.items"
                  :key="item.refundId"
                  class="flex flex-wrap items-center justify-between gap-2"
                >
                  <span class="tabular-nums">{{ rupiah(item.amount) }}, {{ item.reason }}</span>
                  <StatusBadge :status="item.status" />
                </li>
              </ul>
              <Button v-if="canRefund" variant="outline" class="mt-3 h-11" @click="openRefund">
                Buat refund
              </Button>
            </div>
          </div>
        </LoadState>
      </CardContent>
    </Card>
  </div>

  <Dialog v-model:open="refundOpen">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>Buat refund simulasi</DialogTitle>
        <DialogDescription>
          Refund tidak bisa dibatalkan setelah berhasil. Tidak ada uang nyata yang berpindah.
        </DialogDescription>
      </DialogHeader>
      <form id="refund-form" class="flex flex-col gap-3" @submit.prevent="submitRefund">
        <div class="flex flex-col gap-1.5">
          <Label for="refund-amount">Jumlah (Rp)</Label>
          <Input id="refund-amount" v-model.number="refundAmount" type="number" min="1" class="h-11" required />
        </div>
        <div class="flex flex-col gap-1.5">
          <Label for="refund-reason">Alasan</Label>
          <Input id="refund-reason" v-model="refundReason" maxlength="255" class="h-11" required />
        </div>
        <Alert v-if="refundError" variant="destructive" role="alert">
          <AlertDescription>{{ refundError }}</AlertDescription>
        </Alert>
      </form>
      <DialogFooter>
        <Button variant="outline" class="h-11" @click="refundOpen = false">Batal</Button>
        <Button type="submit" form="refund-form" class="h-11" :disabled="refundBusy">Refund</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
