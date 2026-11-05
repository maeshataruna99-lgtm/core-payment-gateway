<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import SandboxBanner from '@/components/SandboxBanner.vue'
import { ApiError } from '@/lib/api'
import { signIn } from '@/lib/auth'
import { demoMerchantKey, demoOperatorKey, isDemo } from '@/lib/demo'

const router = useRouter()
const key = ref('')
const error = ref<string | null>(null)
const busy = ref(false)

async function enter(value: string): Promise<void> {
  busy.value = true
  error.value = null
  try {
    await signIn(value.trim())
    await router.push('/')
  } catch (e) {
    error.value =
      e instanceof ApiError && e.status === 401
        ? 'Kunci API tidak dikenal.'
        : 'Server tidak dapat dihubungi. Coba lagi.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="min-h-screen bg-muted/40">
    <SandboxBanner />
    <main class="mx-auto flex max-w-md flex-col gap-4 p-6 pt-16">
      <Card>
        <CardHeader>
          <CardTitle><h1 class="text-xl font-semibold">Masuk ke PayGate Sandbox</h1></CardTitle>
          <CardDescription>Gunakan kunci API merchant atau operator.</CardDescription>
        </CardHeader>
        <CardContent>
          <form class="flex flex-col gap-4" @submit.prevent="enter(key)">
            <div class="flex flex-col gap-1.5">
              <Label for="api-key">Kunci API</Label>
              <Input id="api-key" v-model="key" type="password" autocomplete="off" class="h-11" required />
            </div>
            <Alert v-if="error" variant="destructive" role="alert">
              <AlertDescription>{{ error }}</AlertDescription>
            </Alert>
            <Button type="submit" class="h-11" :disabled="busy || !key.trim()">Masuk</Button>
          </form>
        </CardContent>
      </Card>
      <Card v-if="isDemo" class="border-dashed bg-muted">
        <CardHeader>
          <CardTitle><h2 class="text-sm font-semibold">Masuk demo</h2></CardTitle>
          <CardDescription>Memakai kunci dummy demo lokal.</CardDescription>
        </CardHeader>
        <CardContent class="flex flex-col gap-2">
          <Button variant="outline" class="h-11" :disabled="busy" @click="enter(demoOperatorKey!)">
            Masuk sebagai operator demo
          </Button>
          <Button variant="outline" class="h-11" :disabled="busy" @click="enter(demoMerchantKey!)">
            Masuk sebagai merchant demo
          </Button>
        </CardContent>
      </Card>
    </main>
  </div>
</template>
