<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { ApiError, newIdempotencyKey, request } from '@/lib/api'
import { demoMerchantKey, isDemo, SCENARIOS } from '@/lib/demo'
import { rupiah } from '@/lib/format'
import type { Checkout } from '@/lib/types'

const router = useRouter()
const amount = ref(250000)
const suffix = ref('0')
const error = ref<string | null>(null)
const busy = ref(false)

// The scenario replaces the last two digits, which is how the simulated provider chooses it.
const finalAmount = computed(() => Math.floor(amount.value / 100) * 100 + Number(suffix.value))

async function create(): Promise<void> {
  busy.value = true
  error.value = null
  try {
    const checkout = await request<Checkout>('/api/v1/checkout-sessions', {
      method: 'POST',
      apiKey: demoMerchantKey,
      headers: { 'Idempotency-Key': newIdempotencyKey() },
      body: {
        merchantOrderId: `DEMO-${Date.now()}`,
        amount: finalAmount.value,
        currency: 'IDR',
        description: 'Pesanan demo sintetis',
      },
    })
    const url = new URL(checkout.checkoutUrl ?? '')
    await router.push(url.pathname + url.hash)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'Checkout tidak dapat dibuat.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold tracking-tight">Checkout demo</h1>
    <p class="text-sm text-muted-foreground">Membuat checkout sintetis atas nama merchant demo.</p>
  </div>

  <Alert v-if="!isDemo">
    <AlertDescription>Kontrol ini hanya ada di image demo lokal.</AlertDescription>
  </Alert>

  <Card v-else class="max-w-xl">
    <CardContent>
      <form class="flex flex-col gap-4" @submit.prevent="create">
        <div class="flex flex-col gap-1.5">
          <Label for="demo-amount">Jumlah (Rp)</Label>
          <Input id="demo-amount" v-model.number="amount" type="number" min="100" step="100" class="h-11" required />
        </div>
        <fieldset>
          <legend class="pb-2 text-sm font-medium">Skenario provider simulasi</legend>
          <RadioGroup v-model="suffix" class="gap-2">
            <Label
              v-for="item in SCENARIOS"
              :key="item.suffix"
              :for="`scenario-${item.suffix}`"
              class="flex min-h-15 cursor-pointer items-center gap-3 rounded-lg border p-3 font-normal has-data-[state=checked]:border-primary has-data-[state=checked]:bg-indigo-50"
            >
              <RadioGroupItem :id="`scenario-${item.suffix}`" :value="String(item.suffix)" />
              <span>
                <span class="font-medium">{{ item.name }}</span><br />
                <span class="text-sm text-muted-foreground">{{ item.note }}</span>
              </span>
            </Label>
          </RadioGroup>
        </fieldset>
        <p class="text-sm">Jumlah akhir: <b class="tabular-nums">{{ rupiah(finalAmount) }}</b></p>
        <Alert v-if="error" variant="destructive" role="alert">
          <AlertDescription>{{ error }}</AlertDescription>
        </Alert>
        <Button type="submit" class="h-11" :disabled="busy">Buat dan buka checkout</Button>
      </form>
    </CardContent>
  </Card>
</template>
