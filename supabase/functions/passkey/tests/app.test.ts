/**
 * Entry-point regressions: server diagnostics must never reach a response body.
 *
 * The #567 review found the two 500 paths in the entrypoint file echoing raw
 * error messages (an unauthenticated maintenance endpoint and the global error
 * handler), while the logs were the redacted half of the same values. These
 * tests import the real entrypoint app (../app.ts) - the one index.ts serves -
 * and drive it through app.request(), so the handlers under test are the
 * production handlers rather than a test-built twin (tests/routes.test.ts
 * builds its own app and cannot see index-level handlers).
 */

import { assert, assertEquals } from "jsr:@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { app } from "../app.ts"
import { setPasskeyClientForTests } from "../utils/client.ts"
import { generateAuthChallenge } from "../services/auth.ts"
import { generateRegistrationChallenge } from "../services/registration.ts"
import { createMockSupabaseClient, mockPasskey } from "./helpers/mocks.ts"

const CLEANUP_CANARY = 'pg-canary: relation "passkey_challenges" does not exist'
const CHALLENGE_CANARY = "pg-canary: duplicate key value violates unique constraint"

function captureLogs(action: () => Promise<void>): Promise<string> {
  const output: unknown[][] = []
  const originals = { log: console.log, error: console.error, warn: console.warn }
  const record = (...args: unknown[]) => output.push(args)
  console.log = record
  console.error = record
  console.warn = record
  return Promise.resolve(action()).finally(() => Object.assign(console, originals)).then(() => JSON.stringify(output))
}

Deno.test("maintenance cleanup failure returns a generic 500 to unauthenticated callers", async () => {
  const client = createMockSupabaseClient()
  client.mockResponse('passkey_challenges', {
    data: null,
    error: { code: '42P01', message: CLEANUP_CANARY }
  }, 'delete')
  setPasskeyClientForTests(client as unknown as SupabaseClient)
  try {
    const response = await app.request('/passkey/maintenance/cleanup', { method: 'POST' })
    assertEquals(response.status, 500)
    const body = await response.text()
    assert(!body.includes(CLEANUP_CANARY), `cleanup 500 leaked the database message: ${body}`)
    assert(body.includes('Internal server error'))
  } finally {
    setPasskeyClientForTests(null)
  }
})

Deno.test("global error handler returns a generic 500 and keeps the diagnostic in logs only", async () => {
  const diagnostic = "gotrue-canary: identity provider refused the session mint"
  const originalEnvGet = Deno.env.get
  Deno.env.get = (key: string) => {
    if (key === 'SUPABASE_URL') throw new Error(diagnostic)
    return originalEnvGet(key)
  }
  setPasskeyClientForTests(null)
  const output = await captureLogs(async () => {
    try {
      const response = await app.request('/passkey/health', { method: 'GET' })
      assertEquals(response.status, 500)
      const body = await response.text()
      assert(!body.includes(diagnostic), `global 500 leaked the thrown diagnostic: ${body}`)
      assert(body.includes('Internal server error'))
    } finally {
      Deno.env.get = originalEnvGet
      setPasskeyClientForTests(null)
    }
  })
  assert(output.includes('Global error:'), "the global handler logged the failure")
  assert(!output.includes(diagnostic), "the log is redacted exactly like the response body")
})

Deno.test("authentication challenge store failure logs a code, never the database message", async () => {
  const client = createMockSupabaseClient()
  client.mockResponse('rpc.find_user_by_email', {
    data: [{ id: 'user-456', email: 'canary-user@example.com' }],
    error: null
  }, 'call')
  client.mockResponse('user_passkeys', { data: [mockPasskey], error: null }, 'select')
  client.mockResponse('passkey_challenges', {
    data: null,
    error: { code: '23505', message: CHALLENGE_CANARY }
  }, 'insert')

  const output = await captureLogs(async () => {
    const result = await generateAuthChallenge(client as unknown as SupabaseClient, 'canary-user@example.com')
    // The failure stays inert and indistinguishable from the other pre-auth
    // failure states, with nothing extra in the response.
    assertEquals(result.success, true)
    assert('allowCredentials' in result)
    assertEquals(result.allowCredentials.length, 0)
  })
  assert(!output.includes(CHALLENGE_CANARY), "store failure leaked the raw Postgres message")
  assert(output.includes('23505'), "the allowlisted code is retained for diagnosis")
})

Deno.test("registration challenge store failure reports a code, never the database message", async () => {
  const client = createMockSupabaseClient()
  client.mockResponse('passkey_challenges', {
    data: null,
    error: { code: '23505', message: CHALLENGE_CANARY }
  }, 'insert')

  const result = await generateRegistrationChallenge(client as unknown as SupabaseClient, 'user-456')
  assertEquals(result.success, false)
  if (!result.success) {
    assert(!result.error.includes(CHALLENGE_CANARY), `registration 400 leaked the database message: ${result.error}`)
    assertEquals(result.error, 'Failed to store challenge (23505)')
  }
})
