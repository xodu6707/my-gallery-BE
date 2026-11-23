import { createServer } from 'node:http'
import { timingSafeEqual } from 'node:crypto'
import sharp from 'sharp'
import { processPhoto, ImageProblem } from './process.mjs'

const secret = process.env.INGEST_TOKEN
if (!secret || secret.length < 32) throw new Error('INGEST_TOKEN of at least 32 characters is required')
const maxBytes = Number(process.env.PHOTO_MAX_BYTES ?? 52428800)
const maxPixels = Number(process.env.PHOTO_MAX_PIXELS ?? 100000000)
sharp.cache({ memory: 64 }); sharp.concurrency(2)
let busy = false
function reply(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(body))
}
const server = createServer(async (request, response) => {
  if (request.url === '/health' && request.method === 'GET') return reply(response, 200, { status: 'UP', mode: 'ingestion', sharp: sharp.versions.sharp })
  const provided = Buffer.from(request.headers.authorization ?? '')
  const expected = Buffer.from(`Bearer ${secret}`)
  if (provided.length !== expected.length || !timingSafeEqual(provided, expected)) return reply(response, 401, { code: 'UNAUTHORIZED' })
  if (request.url !== '/process' || request.method !== 'POST') return reply(response, 404, { code: 'NOT_FOUND' })
  if (busy) return reply(response, 503, { code: 'BUSY' })
  if (Number(request.headers['content-length']) > maxBytes) return reply(response, 413, { code: 'FILE_TOO_LARGE' })
  busy = true
  try {
    const chunks = []; let size = 0
    for await (const chunk of request) {
      size += chunk.length
      if (size > maxBytes) { reply(response, 413, { code: 'FILE_TOO_LARGE' }); request.destroy(); return }
      chunks.push(chunk)
    }
    reply(response, 200, await processPhoto(Buffer.concat(chunks), maxPixels))
  } catch (error) {
    reply(response, error instanceof ImageProblem ? 422 : 503, { code: error instanceof ImageProblem ? error.code : 'INGEST_UNAVAILABLE' })
  } finally { busy = false }
})
server.requestTimeout = 120000
server.listen(8090, '0.0.0.0')
