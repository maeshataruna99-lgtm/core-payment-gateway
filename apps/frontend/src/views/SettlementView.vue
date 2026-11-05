<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import LoadState from '@/components/LoadState.vue'
import MerchantSelect from '@/components/MerchantSelect.vue'
import { useLoad } from '@/composables/useLoad'
import { ApiError, newIdempotencyKey } from '@/lib/api'
import { authed, isStaff } from '@/lib/auth'
import { isDemo } from '@/lib/demo'
import { dateTime, rupiah } from '@/lib/format'
import { discrepanciesToCsv } from '@/lib/reconciliationCsv'
import type { ReconciliationRun, Settlement } from '@/lib/types'

const merchantId = ref<string | null>(null)
const query = () => (merchantId.value ? `?merchantId=${merchantId.value}` : '')

const batches = useLoad(() => authed<Settlement[]>(`/api/v1/settlements${query()}`))
const preview = useLoad(async () =>
  isStaff.value && merchantId.value
    ? authed<Settlement>('/api/v1/settlements/preview', {
        method: 'POST',
        body: { merchantId: merchantId.value },
      })
    : null,
)
const runs = useLoad(async () =>
  isStaff.value && merchantId.value
    ? authed<ReconciliationRun[]>(`/api/v1/reconciliation/runs?merchantId=${merchantId.value}`)
    : [],
)

watch(merchantId, () => {
  void batches.reload()
  void preview.reload()
  void runs.reload()
  run.value = null
})

const canFinalize = computed(() => (preview.data.value?.net ?? 0) > 0)
const confirmOpen = ref(false)
const finalizeError = ref<string | null>(null)
const finalizeBusy = ref(false)
let finalizeKey = ''

function openConfirm(): void {
  finalizeError.value = null
  finalizeKey = newIdempotencyKey()
  confirmOpen.value = true
}

async function finalizeBatch(): Promise<void> {
  finalizeBusy.value = true
  finalizeError.value = null
  try {
    await authed('/api/v1/settlements', {
      method: 'POST',
      headers: { 'Idempotency-Key': finalizeKey },
      body: { merchantId: merchantId.value, expectedNet: preview.data.value?.net, confirm: true },
    })
    confirmOpen.value = false
    await Promise.all([batches.reload(), preview.reload()])
  } catch (e) {
    finalizeError.value = e instanceof ApiError ? e.message : 'Finalisasi gagal.'
    await preview.reload()
  } finally {
    finalizeBusy.value = false
  }
}

const DAY = 86_400_000
const from = ref(new Date(Date.now() - 30 * DAY).toISOString().slice(0, 10))
const to = ref(new Date(Date.now() + DAY).toISOString().slice(0, 10))
const FAULTS = ['AMOUNT', 'STATUS', 'MISSING', 'DUPLICATE', 'UNKNOWN_REFERENCE']
const faults = ref<string[]>(['AMOUNT', 'UNKNOWN_REFERENCE'])
const fileText = ref('')
const run = ref<ReconciliationRun | null>(null)
const reconError = ref<string | null>(null)
const reconBusy = ref(false)

const period = () =>
  `merchantId=${merchantId.value}&from=${from.value}T00:00:00Z&to=${to.value}T00:00:00Z`

async function generateFile(): Promise<void> {
  reconError.value = null
  try {
    fileText.value = await authed<string>(
      `/api/v1/sandbox/settlement-files?${period()}&faults=${faults.value.join(',')}`,
    )
  } catch (e) {
    reconError.value = e instanceof ApiError ? e.message : 'File tidak dapat dibuat.'
  }
}

async function runReconciliation(): Promise<void> {
  reconBusy.value = true
  reconError.value = null
  try {
    run.value = await authed<ReconciliationRun>(`/api/v1/reconciliation/runs?${period()}`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/csv' },
      rawBody: fileText.value,
    })
    await runs.reload()
  } catch (e) {
    reconError.value = e instanceof ApiError ? (e.fieldErrors[0]?.message ?? e.message) : 'Rekonsiliasi gagal.'
  } finally {
    reconBusy.value = false
  }
}

async function openRun(runId: string): Promise<void> {
  run.value = await authed<ReconciliationRun>(`/api/v1/reconciliation/runs/${runId}`)
}

function exportCsv(): void {
  if (!run.value) return
  const blob = new Blob([discrepanciesToCsv(run.value.discrepancies ?? [])], { type: 'text/csv' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `rekonsiliasi-${run.value.runId}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
}
</script>

<template>
  <div class="flex flex-wrap items-end justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">{{ isStaff ? 'Settlement dan rekonsiliasi' : 'Settlement' }}</h1>
      <p class="text-sm text-muted-foreground">Simulasi. Tidak ada transfer bank.</p>
    </div>
    <MerchantSelect v-if="isStaff" v-model="merchantId" />
  </div>

  <Tabs default-value="batch">
    <TabsList v-if="isStaff" class="h-12">
      <TabsTrigger value="batch" class="h-10">Batch</TabsTrigger>
      <TabsTrigger value="reconciliation" class="h-10">Rekonsiliasi</TabsTrigger>
    </TabsList>

    <TabsContent value="batch" class="flex flex-col gap-4">
      <Card v-if="isStaff" class="max-w-xl">
        <CardHeader>
          <CardTitle><h2 class="text-sm font-semibold">Batch berikutnya</h2></CardTitle>
          <CardDescription>Preview, belum tersimpan.</CardDescription>
        </CardHeader>
        <CardContent>
          <LoadState :loading="preview.loading.value" :error="preview.error.value" :has-data="preview.data.value !== null">
            <div v-if="preview.data.value" class="flex flex-col gap-3 text-sm tabular-nums">
              <div class="flex justify-between"><span>Gross</span><span>{{ rupiah(preview.data.value.gross) }}</span></div>
              <div class="flex justify-between"><span>Fee simulasi</span><span>{{ rupiah(-preview.data.value.fees) }}</span></div>
              <div class="flex justify-between"><span>Refund</span><span>{{ rupiah(-preview.data.value.refunds) }}</span></div>
              <div class="flex justify-between"><span>Adjustment</span><span>{{ rupiah(preview.data.value.adjustments) }}</span></div>
              <div class="flex justify-between border-t pt-3 text-base font-semibold"><span>Net</span><span>{{ rupiah(preview.data.value.net) }}</span></div>
              <p class="text-muted-foreground">
                Batch final tidak bisa diubah. Refund setelahnya masuk sebagai adjustment di batch berikutnya.
              </p>
              <Button class="h-11 self-start" :disabled="!canFinalize" @click="openConfirm">Finalisasi batch</Button>
              <p v-if="!canFinalize" class="text-muted-foreground">Belum ada jumlah positif untuk disettle.</p>
            </div>
          </LoadState>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle><h2 class="text-sm font-semibold">Batch final</h2></CardTitle>
        </CardHeader>
        <CardContent>
          <LoadState
            :loading="batches.loading.value"
            :error="batches.error.value"
            :empty="batches.data.value?.length === 0"
            empty-text="Belum ada batch final."
          >
            <div class="overflow-x-auto">
              <Table class="min-w-[40rem]">
                <TableHeader>
                  <TableRow>
                    <TableHead>Final</TableHead>
                    <TableHead class="text-right">Gross</TableHead>
                    <TableHead class="text-right">Fee</TableHead>
                    <TableHead class="text-right">Refund</TableHead>
                    <TableHead class="text-right">Adjustment</TableHead>
                    <TableHead class="text-right">Net</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow v-for="batch in batches.data.value" :key="batch.id" class="tabular-nums">
                    <TableCell class="text-muted-foreground">{{ batch.finalizedAt ? dateTime(batch.finalizedAt) : '' }}</TableCell>
                    <TableCell class="text-right">{{ rupiah(batch.gross) }}</TableCell>
                    <TableCell class="text-right">{{ rupiah(batch.fees) }}</TableCell>
                    <TableCell class="text-right">{{ rupiah(batch.refunds) }}</TableCell>
                    <TableCell class="text-right">{{ rupiah(batch.adjustments) }}</TableCell>
                    <TableCell class="text-right font-medium">{{ rupiah(batch.net) }}</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </div>
          </LoadState>
        </CardContent>
      </Card>
    </TabsContent>

    <TabsContent v-if="isStaff" value="reconciliation" class="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle><h2 class="text-sm font-semibold">Jalankan rekonsiliasi</h2></CardTitle>
          <CardDescription>Selisih hanya dicatat. Ledger dan status payment tidak ditimpa.</CardDescription>
        </CardHeader>
        <CardContent class="flex flex-col gap-4">
          <div class="flex flex-wrap gap-3">
            <div class="flex flex-col gap-1.5">
              <Label for="recon-from">Dari</Label>
              <Input id="recon-from" v-model="from" type="date" class="h-11" />
            </div>
            <div class="flex flex-col gap-1.5">
              <Label for="recon-to">Sampai (tidak termasuk)</Label>
              <Input id="recon-to" v-model="to" type="date" class="h-11" />
            </div>
          </div>

          <fieldset v-if="isDemo" class="rounded-lg border border-dashed border-neutral-500 bg-muted p-4">
            <legend class="px-1 text-sm font-medium">Kontrol demo: file settlement mock</legend>
            <div class="flex flex-wrap gap-x-4">
              <label v-for="fault in FAULTS" :key="fault" class="flex min-h-11 cursor-pointer items-center gap-2 text-sm">
                <input v-model="faults" type="checkbox" :value="fault" class="size-4 accent-primary" />
                {{ fault }}
              </label>
            </div>
            <Button variant="outline" class="h-11" @click="generateFile">Buat file mock</Button>
          </fieldset>

          <div class="flex flex-col gap-1.5">
            <Label for="recon-file">File settlement provider (CSV)</Label>
            <textarea
              id="recon-file"
              v-model="fileText"
              rows="6"
              class="rounded-lg border border-input bg-background p-3 font-mono text-xs focus-visible:outline-2 focus-visible:outline-ring"
              placeholder="provider_reference,amount,currency,status,occurred_at"
            />
          </div>
          <Alert v-if="reconError" variant="destructive" role="alert">
            <AlertDescription>{{ reconError }}</AlertDescription>
          </Alert>
          <Button class="h-11 self-start" :disabled="reconBusy || !fileText.trim()" @click="runReconciliation">
            Jalankan rekonsiliasi
          </Button>
        </CardContent>
      </Card>

      <Card v-if="run">
        <CardHeader>
          <CardTitle>
            <h2 class="text-sm font-semibold">{{ run.matchedCount }} cocok, {{ run.discrepancyCount }} discrepancy</h2>
          </CardTitle>
          <CardDescription>{{ run.rowCount }} baris file, {{ dateTime(run.createdAt) }}</CardDescription>
        </CardHeader>
        <CardContent class="flex flex-col gap-3">
          <p v-if="run.discrepancies?.length === 0" class="py-4 text-center text-sm text-muted-foreground">
            Tidak ada selisih.
          </p>
          <div v-else class="overflow-x-auto">
            <Table class="min-w-[40rem]">
              <TableHeader>
                <TableRow>
                  <TableHead>Tipe</TableHead>
                  <TableHead>Provider ref</TableHead>
                  <TableHead>Internal</TableHead>
                  <TableHead>File provider</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <TableRow v-for="(item, index) in run.discrepancies" :key="index">
                  <TableCell>
                    <span class="rounded-full border border-red-200 bg-red-50 px-2 py-0.5 text-xs font-medium text-red-800">{{ item.type }}</span>
                  </TableCell>
                  <TableCell class="font-mono text-xs">{{ item.providerReference ?? '-' }}</TableCell>
                  <TableCell>{{ item.internalValue ?? '-' }}</TableCell>
                  <TableCell>{{ item.providerValue ?? '-' }}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </div>
          <Button variant="outline" class="h-11 self-start" @click="exportCsv">Ekspor CSV</Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle><h2 class="text-sm font-semibold">Riwayat run</h2></CardTitle>
        </CardHeader>
        <CardContent>
          <LoadState
            :loading="runs.loading.value"
            :error="runs.error.value"
            :empty="runs.data.value?.length === 0"
            empty-text="Belum ada run."
          >
            <ul class="flex flex-col gap-2 text-sm">
              <li
                v-for="item in runs.data.value"
                :key="item.runId"
                class="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-2 pl-3"
              >
                <span>
                  {{ dateTime(item.createdAt) }}: {{ item.matchedCount }} cocok, {{ item.discrepancyCount }} discrepancy
                </span>
                <Button variant="outline" class="h-11" @click="openRun(item.runId)">Buka</Button>
              </li>
            </ul>
          </LoadState>
        </CardContent>
      </Card>
    </TabsContent>
  </Tabs>

  <Dialog v-model:open="confirmOpen">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>Finalisasi batch {{ rupiah(preview.data.value?.net ?? 0) }}?</DialogTitle>
        <DialogDescription>
          Aksi ini tidak bisa dibatalkan. Ini tetap simulasi dan tidak memindahkan uang nyata.
        </DialogDescription>
      </DialogHeader>
      <Alert v-if="finalizeError" variant="destructive" role="alert">
        <AlertDescription>{{ finalizeError }}</AlertDescription>
      </Alert>
      <DialogFooter>
        <Button variant="outline" class="h-11" @click="confirmOpen = false">Batal</Button>
        <Button class="h-11" :disabled="finalizeBusy" @click="finalizeBatch">Ya, finalisasi</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
