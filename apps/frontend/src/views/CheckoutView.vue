<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import LoadState from '@/components/LoadState.vue'
import SandboxBanner from '@/components/SandboxBanner.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useLoad } from '@/composables/useLoad'
import { ApiError, newIdempotencyKey, request } from '@/lib/api'
import { demoOperatorKey, isDemo } from '@/lib/demo'
import { dateTime, rupiah } from '@/lib/format'
import type { Checkout, PaymentDetail, PaymentStatus } from '@/lib/types'

const route = useRoute()
const checkoutId = String(route.params.id)
// The token is in the URL fragment, which browsers do not send to servers.
const token = new URLSearchParams(route.hash.replace(/^#/, '')).get('token') ?? ''
const tokenHeaders = { 'X-Checkout-Token': token }

const METHODS = [
  { id: 'QRIS', name: 'QRIS simulasi', note: 'Menampilkan QR contoh' },
  { id: 'VIRTUAL_ACCOUNT', name: 'Virtual Account simulasi', note: 'Bank simulasi, tanpa transfer nyata' },
]
const BANKS = [
  { code: 'SIM_BANK_A', name: 'Bank Simulasi A' },
  { code: 'SIM_BANK_B', name: 'Bank Simulasi B' },
  { code: 'SIM_BANK_C', name: 'Bank Simulasi C' },
]
const MESSAGES: Record<PaymentStatus, string> = {
  CREATED: 'Menyiapkan pembayaran simulasi.',
  PENDING: 'Menunggu pembayaran simulasi. Pending belum berarti sukses.',
  UNKNOWN: 'Hasil belum pasti. Jangan membayar ulang. Sistem sedang mengecek status.',
  SUCCEEDED: 'Pembayaran simulasi berhasil. Tidak ada uang nyata yang berpindah.',
  FAILED: 'Pembayaran simulasi gagal. Silakan pilih metode lain.',
  EXPIRED: 'Batas waktu habis tanpa pembayaran.',
}

const method = ref('QRIS')
const bank = ref('SIM_BANK_A')
const startError = ref<string | null>(null)
const busy = ref(false)
const paymentId = ref<string | null>(null)
const choosing = ref(false)

const checkout = useLoad(
  () =>
    request<Checkout>(`/api/v1/checkout-sessions/${checkoutId}`, { headers: tokenHeaders }),
  3000,
)
const payment = useLoad(async () => {
  const id = paymentId.value ?? checkout.data.value?.latestPayment?.paymentId
  return id ? request<PaymentDetail>(`/api/v1/payments/${id}`, { headers: tokenHeaders }) : null
}, 3000)

watch(
  () => checkout.data.value?.latestPayment?.paymentId,
  () => void payment.reload(),
)

const showForm = computed(
  () =>
    checkout.data.value?.status === 'OPEN' &&
    (choosing.value || !payment.data.value || payment.data.value.status === 'FAILED'),
)

async function pay(): Promise<void> {
  busy.value = true
  startError.value = null
  try {
    const created = await request<PaymentDetail>('/api/v1/payments', {
      method: 'POST',
      headers: { ...tokenHeaders, 'Idempotency-Key': newIdempotencyKey() },
      body: {
        checkoutId,
        paymentMethod: method.value,
        channelCode: method.value === 'VIRTUAL_ACCOUNT' ? bank.value : null,
      },
    })
    paymentId.value = created.paymentId
    payment.data.value = created
    choosing.value = false
  } catch (e) {
    startError.value = e instanceof ApiError ? e.message : 'Pembayaran tidak dapat dimulai.'
  } finally {
    busy.value = false
  }
}

const demoMessage = ref<string | null>(null)
const lastEventId = ref<string | null>(null)

async function answer(outcome: 'SUCCESS' | 'FAILED', eventId: string): Promise<void> {
  demoMessage.value = null
  try {
    await request(`/api/v1/sandbox/payments/${payment.data.value?.paymentId}/events`, {
      method: 'POST',
      apiKey: demoOperatorKey,
      body: { outcome, eventId },
    })
    lastEventId.value = eventId
    demoMessage.value = `Event ${eventId.slice(0, 8)} terkirim.`
    await Promise.all([payment.reload(), checkout.reload()])
  } catch (e) {
    demoMessage.value = e instanceof ApiError ? e.message : 'Event gagal dikirim.'
  }
}

async function replay(): Promise<void> {
  if (lastEventId.value) {
    await answer('SUCCESS', lastEventId.value)
    demoMessage.value = 'Event yang sama dikirim ulang. Replay diabaikan, status tidak berubah.'
  }
}
</script>

<template>
  <div class="min-h-screen bg-muted/40">
    <SandboxBanner text="SANDBOX: ini simulasi. Jangan memasukkan kartu, PIN, OTP, atau login bank asli." />
    <div class="mx-auto flex max-w-5xl flex-wrap items-start gap-4 p-4 pt-8">
      <main class="min-w-0 flex-[3_1_26rem]">
        <LoadState
          :loading="checkout.loading.value"
          :error="
            checkout.data.value
              ? checkout.error.value
              : 'Checkout tidak ditemukan. Periksa tautan yang Anda terima.'
          "
          :has-data="checkout.data.value !== null"
        >
          <Card v-if="checkout.data.value">
            <CardHeader>
              <CardTitle>
                <div class="flex flex-wrap justify-between gap-3">
                  <div>
                    <h1 class="text-base font-semibold">{{ checkout.data.value.merchantName }}</h1>
                    <div class="font-mono text-xs font-medium text-muted-foreground">
                      {{ checkout.data.value.merchantOrderId }}
                    </div>
                  </div>
                  <div class="text-right">
                    <div class="text-2xl font-semibold tabular-nums">{{ rupiah(checkout.data.value.amount) }}</div>
                    <div class="text-sm font-normal text-muted-foreground">
                      Berlaku sampai {{ dateTime(checkout.data.value.expiresAt) }}
                    </div>
                  </div>
                </div>
              </CardTitle>
            </CardHeader>
            <CardContent class="flex flex-col gap-5">
              <p v-if="checkout.data.value.description">{{ checkout.data.value.description }}</p>

              <form v-if="showForm" class="flex flex-col gap-3" @submit.prevent="pay">
                <fieldset class="flex flex-col gap-2">
                  <legend class="pb-2 text-sm font-semibold">Pilih metode simulasi</legend>
                  <RadioGroup v-model="method" class="gap-2">
                    <Label
                      v-for="item in METHODS"
                      :key="item.id"
                      :for="`method-${item.id}`"
                      class="flex min-h-15 cursor-pointer items-center gap-3 rounded-lg border p-3 font-normal has-data-[state=checked]:border-primary has-data-[state=checked]:bg-indigo-50"
                    >
                      <RadioGroupItem :id="`method-${item.id}`" :value="item.id" />
                      <span>
                        <span class="font-medium">{{ item.name }}</span><br />
                        <span class="text-sm text-muted-foreground">{{ item.note }}</span>
                      </span>
                    </Label>
                  </RadioGroup>
                </fieldset>
                <div v-if="method === 'VIRTUAL_ACCOUNT'" class="flex flex-col gap-1.5">
                  <Label for="bank">Bank simulasi</Label>
                  <Select v-model="bank">
                    <SelectTrigger id="bank" class="w-full data-[size=default]:h-11"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem v-for="item in BANKS" :key="item.code" :value="item.code">{{ item.name }}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <Alert v-if="startError" variant="destructive" role="alert">
                  <AlertDescription>{{ startError }}</AlertDescription>
                </Alert>
                <Button type="submit" class="h-11" :disabled="busy">Lanjutkan pembayaran simulasi</Button>
              </form>

              <section
                v-if="payment.data.value"
                role="status"
                class="flex flex-col gap-3 rounded-lg border p-4"
              >
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <h2 class="text-sm font-semibold">Status pembayaran</h2>
                  <StatusBadge :status="payment.data.value.status" />
                </div>
                <p>{{ MESSAGES[payment.data.value.status] }}</p>
                <template v-if="payment.data.value.status === 'PENDING' && payment.data.value.instruction">
                  <div
                    v-if="payment.data.value.method === 'QRIS'"
                    class="flex size-44 items-center justify-center rounded-lg border border-dashed border-neutral-500 bg-muted p-4 text-center font-medium text-neutral-700"
                  >
                    QR CONTOH<br />Tidak bisa dipindai
                  </div>
                  <div class="rounded-lg bg-muted p-3">
                    <div class="text-sm text-neutral-600">{{ payment.data.value.instruction.label }}</div>
                    <div class="font-mono text-sm break-all">{{ payment.data.value.instruction.value }}</div>
                  </div>
                </template>
                <Button
                  v-if="checkout.data.value.status === 'OPEN' && payment.data.value.status === 'PENDING' && !choosing"
                  variant="outline"
                  class="h-11 self-start"
                  @click="choosing = true"
                >
                  Pilih metode lain
                </Button>
              </section>

              <p v-if="checkout.data.value.status === 'EXPIRED' && !payment.data.value" class="text-sm">
                Checkout ini sudah kedaluwarsa.
              </p>
            </CardContent>
          </Card>
        </LoadState>
      </main>

      <aside
        v-if="isDemo && payment.data.value"
        aria-label="Kontrol demo"
        class="flex min-w-0 flex-[2_1_16rem] flex-col gap-2 rounded-xl border border-dashed border-neutral-500 bg-muted p-5 text-sm"
      >
        <h2 class="font-semibold">Kontrol demo</h2>
        <p class="mb-2 text-neutral-600">Bukan bagian checkout. Panel ini mengirim jawaban provider simulasi.</p>
        <Button variant="outline" class="h-11" @click="answer('SUCCESS', newIdempotencyKey())">Simulasikan sukses</Button>
        <Button variant="outline" class="h-11" @click="answer('FAILED', newIdempotencyKey())">Simulasikan gagal</Button>
        <Button variant="outline" class="h-11" :disabled="!lastEventId" @click="replay">Kirim ulang event terakhir</Button>
        <p v-if="demoMessage" role="status" class="pt-1 text-neutral-700">{{ demoMessage }}</p>
      </aside>
    </div>
  </div>
</template>
