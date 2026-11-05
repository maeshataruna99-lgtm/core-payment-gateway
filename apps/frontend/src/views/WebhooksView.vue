<script setup lang="ts">
import { ref } from 'vue'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
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
import LoadState from '@/components/LoadState.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useLoad } from '@/composables/useLoad'
import { ApiError } from '@/lib/api'
import { authed, isStaff } from '@/lib/auth'
import { isDemo } from '@/lib/demo'
import { dateTime } from '@/lib/format'
import type { WebhookDelivery, WebhookEndpoint } from '@/lib/types'

const EVENT_TYPES = [
  'payment.succeeded',
  'payment.failed',
  'payment.expired',
  'refund.succeeded',
  'refund.failed',
  'settlement.finalized',
]

const deliveries = useLoad(
  async () => (await authed<{ items: WebhookDelivery[] }>('/api/v1/webhook-deliveries')).items,
  3000,
)
const endpoints = useLoad(async () =>
  isStaff.value ? [] : authed<WebhookEndpoint[]>('/api/v1/webhook-endpoints'),
)

const url = ref(isDemo ? 'http://localhost:8080/demo/webhook-receiver?failTimes=1' : '')
const events = ref<string[]>(['payment.succeeded', 'payment.failed'])
const formError = ref<string | null>(null)
const busy = ref(false)
const createdSecret = ref<string | null>(null)

async function register(): Promise<void> {
  busy.value = true
  formError.value = null
  try {
    const created = await authed<WebhookEndpoint>('/api/v1/webhook-endpoints', {
      method: 'POST',
      body: { url: url.value, events: events.value },
    })
    createdSecret.value = created.secret ?? null
    await endpoints.reload()
  } catch (e) {
    formError.value = e instanceof ApiError ? (e.fieldErrors[0]?.message ?? e.message) : 'Pendaftaran gagal.'
  } finally {
    busy.value = false
  }
}

const disabling = ref<WebhookEndpoint | null>(null)

async function confirmDisable(): Promise<void> {
  if (disabling.value) {
    await authed(`/api/v1/webhook-endpoints/${disabling.value.id}/disable`, { method: 'POST' })
    disabling.value = null
    await endpoints.reload()
  }
}

const actionError = ref<string | null>(null)

async function retry(delivery: WebhookDelivery): Promise<void> {
  actionError.value = null
  try {
    await authed(`/api/v1/webhook-deliveries/${delivery.id}/retry`, { method: 'POST' })
    await deliveries.reload()
  } catch (e) {
    actionError.value = e instanceof ApiError ? e.message : 'Retry gagal.'
  }
}

const history = ref<WebhookDelivery | null>(null)
const historyOpen = ref(false)

async function showHistory(delivery: WebhookDelivery): Promise<void> {
  history.value = await authed<WebhookDelivery>(`/api/v1/webhook-deliveries/${delivery.id}`)
  historyOpen.value = true
}
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold tracking-tight">Webhook Center</h1>
    <p class="text-sm text-muted-foreground">
      Pengiriman at-least-once. Merchant perlu mendedupe berdasarkan event ID.
    </p>
  </div>

  <Card v-if="!isStaff">
    <CardHeader>
      <CardTitle><h2 class="text-sm font-semibold">Endpoint</h2></CardTitle>
      <CardDescription>
        Alamat private diblokir, kecuali allowlist di profil lokal. Secret hanya tampil sekali.
      </CardDescription>
    </CardHeader>
    <CardContent class="flex flex-col gap-4">
      <LoadState
        :loading="endpoints.loading.value"
        :error="endpoints.error.value"
        :empty="endpoints.data.value?.length === 0"
        empty-text="Belum ada endpoint."
      >
        <ul class="flex flex-col gap-3">
          <li
            v-for="endpoint in endpoints.data.value"
            :key="endpoint.id"
            class="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-3"
          >
            <div class="min-w-0">
              <div class="font-mono text-xs break-all">{{ endpoint.url }}</div>
              <div class="pt-1 text-xs text-muted-foreground">{{ endpoint.events.join(', ') }}</div>
            </div>
            <div class="flex items-center gap-2">
              <span class="rounded-full border px-2 py-0.5 text-xs font-medium">
                {{ endpoint.active ? 'Aktif' : 'Nonaktif' }}
              </span>
              <Button
                v-if="endpoint.active"
                variant="destructive"
                class="h-11"
                @click="disabling = endpoint"
              >
                Nonaktifkan
              </Button>
            </div>
          </li>
        </ul>
      </LoadState>

      <form class="flex flex-col gap-3 border-t pt-4" @submit.prevent="register">
        <div class="flex flex-col gap-1.5">
          <Label for="endpoint-url">URL endpoint baru</Label>
          <Input id="endpoint-url" v-model="url" class="h-11" required />
        </div>
        <fieldset>
          <legend class="pb-2 text-sm font-medium">Langganan event</legend>
          <div class="grid grid-cols-[repeat(auto-fit,minmax(12rem,1fr))] gap-2">
            <label v-for="type in EVENT_TYPES" :key="type" class="flex min-h-11 cursor-pointer items-center gap-2 text-sm">
              <input v-model="events" type="checkbox" :value="type" class="size-4 accent-primary" />
              {{ type }}
            </label>
          </div>
        </fieldset>
        <Alert v-if="formError" variant="destructive" role="alert">
          <AlertDescription>{{ formError }}</AlertDescription>
        </Alert>
        <Button type="submit" class="h-11 self-start" :disabled="busy || events.length === 0">Daftarkan</Button>
      </form>
    </CardContent>
  </Card>

  <Card>
    <CardHeader>
      <CardTitle><h2 class="text-sm font-semibold">Riwayat pengiriman</h2></CardTitle>
      <CardDescription>Dimuat ulang tiap 3 detik.</CardDescription>
    </CardHeader>
    <CardContent>
      <Alert v-if="actionError" variant="destructive" role="alert" class="mb-3">
        <AlertDescription>{{ actionError }}</AlertDescription>
      </Alert>
      <LoadState
        :loading="deliveries.loading.value"
        :error="deliveries.error.value"
        :forbidden="deliveries.forbidden.value"
        :empty="deliveries.data.value?.length === 0"
        empty-text="Belum ada pengiriman. Pengiriman muncul setelah event terjadi pada endpoint yang berlangganan."
      >
        <div class="overflow-x-auto">
          <Table class="min-w-[44rem]">
            <TableHeader>
              <TableRow>
                <TableHead>Waktu</TableHead>
                <TableHead>Event</TableHead>
                <TableHead>Percobaan</TableHead>
                <TableHead>Hasil</TableHead>
                <TableHead>Aksi</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              <TableRow v-for="delivery in deliveries.data.value" :key="delivery.id">
                <TableCell class="text-muted-foreground">{{ dateTime(delivery.createdAt) }}</TableCell>
                <TableCell>{{ delivery.eventType }}</TableCell>
                <TableCell class="tabular-nums">{{ delivery.attempts }}</TableCell>
                <TableCell><StatusBadge :status="delivery.state" /></TableCell>
                <TableCell class="flex gap-2">
                  <Button variant="outline" class="h-11" @click="showHistory(delivery)">Riwayat</Button>
                  <Button v-if="delivery.state === 'FAILED'" class="h-11" @click="retry(delivery)">Retry</Button>
                </TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </div>
      </LoadState>
    </CardContent>
  </Card>

  <Dialog :open="createdSecret !== null" @update:open="createdSecret = null">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>Simpan secret ini</DialogTitle>
        <DialogDescription>Secret tidak akan ditampilkan lagi. Gunakan untuk memverifikasi tanda tangan webhook.</DialogDescription>
      </DialogHeader>
      <code class="rounded-lg bg-muted p-3 font-mono text-xs break-all">{{ createdSecret }}</code>
      <DialogFooter>
        <Button class="h-11" @click="createdSecret = null">Sudah disimpan</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>

  <Dialog :open="disabling !== null" @update:open="disabling = null">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>Nonaktifkan endpoint?</DialogTitle>
        <DialogDescription>
          Event baru tidak akan dikirim, dan pengiriman yang menunggu dibatalkan. Riwayat tetap tersimpan.
        </DialogDescription>
      </DialogHeader>
      <DialogFooter>
        <Button variant="outline" class="h-11" @click="disabling = null">Batal</Button>
        <Button variant="destructive" class="h-11" @click="confirmDisable">Nonaktifkan</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>

  <Dialog v-model:open="historyOpen">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>Riwayat percobaan</DialogTitle>
        <DialogDescription>{{ history?.eventType }}</DialogDescription>
      </DialogHeader>
      <p v-if="history?.history?.length === 0" class="text-sm text-muted-foreground">Belum ada percobaan.</p>
      <ol class="flex flex-col gap-2 text-sm">
        <li v-for="item in history?.history" :key="item.attempt" class="border-l-2 pl-3">
          <div class="text-xs text-muted-foreground">{{ dateTime(item.at) }}</div>
          Percobaan {{ item.attempt }}: {{ item.statusCode ? `HTTP ${item.statusCode}` : item.error }}
        </li>
      </ol>
      <AlertTitle class="sr-only">Akhir riwayat</AlertTitle>
    </DialogContent>
  </Dialog>
</template>
