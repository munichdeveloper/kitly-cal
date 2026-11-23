import { createServer, type Server } from "node:http"
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest"
import { GET as slotsRoute } from "@/app/api/cal/slots/route"
import { POST as webhookRoute } from "@/app/api/cal/webhook/route"
import {
  BOOKING_SOURCE,
  CalBookingError,
  confirmBooking,
  createPendingBooking,
  expireUnpaidBookings,
  listPendingBookings,
  releaseBooking,
} from "@/lib/cal-booking"
import { processBookingCreatedForCustomerPortal } from "@/lib/customer-portal-webhook"

/**
 * Contract test between dsb-new and kitly-cal. It belongs into dsb-new (copy it to tests/) and runs
 * the app's existing Cal client UNCHANGED, over real HTTP, against a kitly-cal instance in the mock
 * profile. If one of these tests fails, kitly-cal is no longer a drop-in replacement for cal.eu.
 *
 *   # kitly-cal (mock profile, sends its webhooks to this test)
 *   KITLY_CAL_WEBHOOK_URL=http://localhost:4998/api/cal/webhook KITLY_CAL_WEBHOOK_SECRET=contract-secret \
 *   KITLY_CAL_MOCK_FORWARD_WEBHOOKS=true SPRING_PROFILES_ACTIVE=mock PORT=4040 \
 *     java -jar backend/target/kitly-cal-backend.jar --kitly-cal.outbox.mode=inline --kitly-cal.outbox.polling=false
 *
 *   # dsb-new
 *   KITLY_CAL_URL=http://localhost:4040 pnpm vitest run tests/kitly-cal-contract.test.ts
 *
 * Without KITLY_CAL_URL the whole file is skipped.
 */

// Stops the webhook route before Airtable and Kitly, exactly like tests/cal-webhook-route.test.ts.
vi.mock("@/lib/customer-portal-webhook", () => ({
  processBookingCreatedForCustomerPortal: vi.fn().mockResolvedValue({ recordId: "rec_1" }),
}))

const BASE = process.env.KITLY_CAL_URL
const LIVE_KEY = process.env.KITLY_CAL_LIVE_KEY ?? "kcal_live_mock_0123456789abcdef"
const SANDBOX_KEY = process.env.KITLY_CAL_SANDBOX_KEY ?? "kcal_test_mock_0123456789abcdef"
const WEBHOOK_SECRET = process.env.KITLY_CAL_WEBHOOK_SECRET ?? "contract-secret"
const RECEIVER_PORT = Number(process.env.KITLY_CAL_RECEIVER_PORT ?? 4998)

const MONDAY_9_BERLIN = "2026-10-05T07:00:00.000Z"
const TUESDAY_9 = "2026-10-06T07:00:00.000Z"
const customer = { name: "Erika Beispiel", email: "erika@example.com", tenantId: "tenant_1", intakeHash: "hash_1" }

async function mockControl(path: string, body?: unknown) {
  const response = await fetch(`${BASE}/_mock/${path}`, {
    method: body === undefined && path === "state" ? "GET" : "POST",
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  expect(response.status).toBe(200)
  return (await response.json()) as Record<string, unknown>
}

describe.skipIf(!BASE)("dsb-new against a running kitly-cal", () => {
  let receiver: Server
  const webhookAnswers: number[] = []

  beforeAll(async () => {
    // Stands in for the deployed app: hands every incoming webhook to the real route handler.
    receiver = createServer((req, res) => {
      const chunks: Buffer[] = []
      req.on("data", (chunk: Buffer) => chunks.push(chunk))
      req.on("end", async () => {
        const headers = new Headers()
        for (const [name, value] of Object.entries(req.headers)) if (typeof value === "string") headers.set(name, value)
        const response = await webhookRoute(
          new Request(`http://localhost:${RECEIVER_PORT}${req.url}`, { method: "POST", headers, body: Buffer.concat(chunks).toString("utf8") }),
        )
        webhookAnswers.push(response.status)
        res.writeHead(response.status, { "Content-Type": "application/json" })
        res.end(await response.text())
      })
    })
    await new Promise<void>((resolve) => receiver.listen(RECEIVER_PORT, resolve))
  })

  afterAll(async () => {
    await new Promise((resolve) => receiver.close(resolve))
  })

  beforeEach(async () => {
    await mockControl("reset")
    await mockControl("clock", { now: MONDAY_9_BERLIN, freeze: true })
    webhookAnswers.length = 0
    vi.stubEnv("CALCOM_API_BASE_URL", BASE!)
    vi.stubEnv("CALCOM_API_KEY", LIVE_KEY)
    vi.stubEnv("CALCOM_EVENT_TYPE_ID", "1")
    vi.stubEnv("CALCOM_WEBHOOK_SECRET", WEBHOOK_SECRET)
    vi.spyOn(console, "info").mockImplementation(() => undefined)
    vi.spyOn(console, "warn").mockImplementation(() => undefined)
    vi.spyOn(console, "error").mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
    vi.mocked(processBookingCreatedForCustomerPortal).mockClear()
  })

  async function loadSlots(month = "2026-10") {
    const response = await slotsRoute(new Request(`http://localhost:3000/api/cal/slots?month=${month}&timeZone=Europe/Berlin`))
    return {
      status: response.status,
      body: (await response.json()) as { slotsByDate?: Record<string, { start: string; end: string }[]>; error?: string },
    }
  }
  const tuesdayStarts = async () => ((await loadSlots()).body.slotsByDate!["2026-10-06"] ?? []).map((slot) => slot.start)

  it("serves the slot picker through /api/cal/slots", async () => {
    const { status, body } = await loadSlots()
    expect(status).toBe(200)
    expect(body.slotsByDate!["2026-10-06"]).toEqual([
      { start: "2026-10-06T07:00:00.000Z", end: "2026-10-06T09:00:00.000Z" },
      { start: "2026-10-06T09:00:00.000Z", end: "2026-10-06T11:00:00.000Z" },
      { start: "2026-10-06T11:00:00.000Z", end: "2026-10-06T13:00:00.000Z" },
      { start: "2026-10-06T13:00:00.000Z", end: "2026-10-06T15:00:00.000Z" },
    ])
    expect(body.slotsByDate!["2026-10-10"]).toBeUndefined() // Saturday
    // Winter time after 25 October: 09:00 Berlin is 08:00 UTC.
    expect(body.slotsByDate!["2026-10-26"][0].start).toBe("2026-10-26T08:00:00.000Z")
  })

  it("shows the app's own hint when the key is wrong", async () => {
    vi.stubEnv("CALCOM_API_KEY", "kcal_live_wrong_0123456789abcdef")
    const { status, body } = await loadSlots()
    expect(status).toBe(401)
    expect(body.error).toMatch(/Cal API Key ist ungültig/)
  })

  it("runs the whole funnel: reserve, pay, confirm, webhook, customer portal", async () => {
    const booking = await createPendingBooking({ start: TUESDAY_9, ...customer })
    expect(booking).toMatchObject({ status: "pending", start: TUESDAY_9, end: "2026-10-06T09:00:00.000Z" })
    expect(booking.uid).toBeTruthy()
    expect(booking.createdAt).toBe(MONDAY_9_BERLIN)
    expect(booking.metadata).toEqual({ source: BOOKING_SOURCE, intakeHash: "hash_1", tenantId: "tenant_1" })

    // Reserved: the slot is gone for the next visitor, and nothing was announced yet.
    expect(await tuesdayStarts()).not.toContain(TUESDAY_9)
    expect(webhookAnswers).toEqual([])

    // Payment verified: confirm. kitly-cal calls the app's real webhook route, which accepts the signature.
    await confirmBooking(booking.uid)
    expect(webhookAnswers).toEqual([200])
    expect(processBookingCreatedForCustomerPortal).toHaveBeenCalledTimes(1)
    expect(processBookingCreatedForCustomerPortal).toHaveBeenCalledWith({
      triggerEvent: "BOOKING_CREATED",
      email: "erika@example.com",
      name: "Erika Beispiel",
      companyName: undefined,
      tenantId: "tenant_1",
      intakeHash: "hash_1",
      bookingId: booking.uid,
      bookingStart: TUESDAY_9,
    })
  })

  it("survives a reload of the payment success page: a second confirm neither fails nor repeats the webhook", async () => {
    const booking = await createPendingBooking({ start: TUESDAY_9, ...customer })
    await confirmBooking(booking.uid)
    await expect(confirmBooking(booking.uid)).resolves.toBeUndefined()
    expect(processBookingCreatedForCustomerPortal).toHaveBeenCalledTimes(1)
  })

  it("refuses a second reservation of the same slot, which the app shows as 'slot-vergeben'", async () => {
    await createPendingBooking({ start: TUESDAY_9, ...customer })
    const second = createPendingBooking({ start: TUESDAY_9, ...customer, email: "zweiter@example.com" })
    await expect(second).rejects.toBeInstanceOf(CalBookingError)
    await expect(second).rejects.toMatchObject({ status: 400 })
  })

  it("releases a reservation when the customer picks another slot", async () => {
    const first = await createPendingBooking({ start: TUESDAY_9, ...customer })
    expect(await releaseBooking(first.uid, "Anderer Termin gewählt.")).toBe(true)
    expect(await tuesdayStarts()).toContain(TUESDAY_9)
    // Releasing twice or releasing something unknown is reported as "not released", never as an error.
    expect(await releaseBooking(first.uid, "nochmal")).toBe(false)
    expect(await releaseBooking("gibt-es-nicht", "x")).toBe(false)
  })

  it("never releases a paid booking", async () => {
    const booking = await createPendingBooking({ start: TUESDAY_9, ...customer })
    await confirmBooking(booking.uid)
    expect(await releaseBooking(booking.uid, "Zahlung nicht abgeschlossen")).toBe(false)
    expect(await tuesdayStarts()).not.toContain(TUESDAY_9)
  })

  it("lets the housekeeping job expire exactly the unpaid funnel reservations", async () => {
    const old = await createPendingBooking({ start: TUESDAY_9, ...customer })
    const paid = await createPendingBooking({ start: "2026-10-06T09:00:00.000Z", ...customer })
    await confirmBooking(paid.uid)
    // A pending booking that did not come from the funnel must be left alone.
    const foreign = await fetch(`${BASE}/v2/bookings`, {
      method: "POST",
      headers: { Authorization: `Bearer ${LIVE_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        start: "2026-10-06T11:00:00.000Z",
        eventTypeId: 1,
        attendee: { name: "Direkt", email: "direkt@example.com", timeZone: "Europe/Berlin" },
      }),
    })
    const foreignUid = ((await foreign.json()) as { data: { uid: string } }).data.uid
    const { now } = await mockControl("clock", { advanceMinutes: 200 }) // past BOOKING_HOLD_MINUTES (180)
    const fresh = await createPendingBooking({ start: "2026-10-06T13:00:00.000Z", ...customer })

    expect((await listPendingBookings()).map((booking) => booking.uid)).toEqual([old.uid, foreignUid, fresh.uid])

    const result = await expireUnpaidBookings(new Date(String(now)))
    expect(result).toEqual({ checked: 2, expired: [old.uid], skipped: [], failed: [] })
    expect((await listPendingBookings()).map((booking) => booking.uid)).toEqual([foreignUid, fresh.uid])
    expect(await tuesdayStarts()).toEqual([TUESDAY_9])
  })

  it("pages through more than 100 pending bookings like the app expects", async () => {
    const starts = [...Object.values((await loadSlots("2026-10")).body.slotsByDate!), ...Object.values((await loadSlots("2026-11")).body.slotsByDate!)]
      .flat()
      .map((slot) => slot.start)
      .slice(0, 105)
    expect(starts).toHaveLength(105)
    for (const start of starts) await createPendingBooking({ start, ...customer })

    const pending = await listPendingBookings()
    expect(pending).toHaveLength(105)
    expect(new Set(pending.map((booking) => booking.uid)).size).toBe(105)
  })

  it("fails the confirmation when the reservation expired before the payment arrived", async () => {
    const booking = await createPendingBooking({ start: TUESDAY_9, ...customer })
    await releaseBooking(booking.uid, "Zahlung nicht abgeschlossen – Reservierung verfallen.")
    // The success page turns this error into "slot-verloren": choose a new slot, no second payment.
    await expect(confirmBooking(booking.uid)).rejects.toBeInstanceOf(CalBookingError)
    expect(processBookingCreatedForCustomerPortal).not.toHaveBeenCalled()
  })

  it("keeps retrying when the app rejects the webhook, e.g. because the secrets differ", async () => {
    vi.stubEnv("CALCOM_WEBHOOK_SECRET", "a-different-secret")
    const booking = await createPendingBooking({ start: TUESDAY_9, ...customer })
    await confirmBooking(booking.uid) // the booking itself is confirmed
    expect(webhookAnswers).toEqual([401])
    expect(processBookingCreatedForCustomerPortal).not.toHaveBeenCalled()

    vi.stubEnv("CALCOM_WEBHOOK_SECRET", WEBHOOK_SECRET)
    await mockControl("clock", { advanceMinutes: 1 })
    const run = await fetch(`${BASE}/internal/outbox/run`, { method: "POST", headers: { Authorization: `Bearer ${LIVE_KEY}` } })
    expect(((await run.json()) as { data: unknown }).data).toEqual({ delivered: 1, retrying: 0, dead: 0 })
    expect(webhookAnswers).toEqual([401, 200])
    expect(processBookingCreatedForCustomerPortal).toHaveBeenCalledTimes(1)
  })

  it("lets the whole funnel run with a sandbox key without touching real slots or the customer portal", async () => {
    vi.stubEnv("CALCOM_API_KEY", SANDBOX_KEY)
    const booking = await createPendingBooking({ start: TUESDAY_9, ...customer })
    await confirmBooking(booking.uid)
    expect(webhookAnswers).toEqual([])
    expect(processBookingCreatedForCustomerPortal).not.toHaveBeenCalled()

    vi.stubEnv("CALCOM_API_KEY", LIVE_KEY)
    expect(await tuesdayStarts()).toContain(TUESDAY_9)
    expect(await listPendingBookings()).toEqual([])
  })
})
