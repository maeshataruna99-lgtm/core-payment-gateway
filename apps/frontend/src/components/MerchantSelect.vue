<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { authed } from '@/lib/auth'
import type { Merchant } from '@/lib/types'

const model = defineModel<string | null>({ required: true })
const merchants = ref<Merchant[]>([])

onMounted(async () => {
  merchants.value = await authed<Merchant[]>('/api/v1/merchants')
  if (!model.value && merchants.value.length > 0) {
    model.value = merchants.value[0].id
  }
})
</script>

<template>
  <div class="flex flex-col gap-1.5">
    <Label for="merchant-select">Merchant</Label>
    <Select v-model="model">
      <SelectTrigger id="merchant-select" class="min-w-48 data-[size=default]:h-11">
        <SelectValue placeholder="Pilih merchant" />
      </SelectTrigger>
      <SelectContent>
        <SelectItem v-for="merchant in merchants" :key="merchant.id" :value="merchant.id">
          {{ merchant.name }}
        </SelectItem>
      </SelectContent>
    </Select>
  </div>
</template>
