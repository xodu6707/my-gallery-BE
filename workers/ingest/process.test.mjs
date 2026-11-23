import test from 'node:test'
import assert from 'node:assert/strict'
import sharp from 'sharp'
import { processPhoto } from './process.mjs'

const create = () => sharp({ create: { width: 120, height: 80, channels: 3, background: '#90a0b0' } })
test('JPEG without EXIF is valid, keeps dimensions and does not invent metadata', async () => {
  const source = await create().jpeg().toBuffer()
  const result = await processPhoto(source)
  assert.equal(result.metadata.exifStatus, 'ABSENT')
  assert.equal(result.metadata.model, null)
  assert.equal(result.metadata.capturedAtRaw, null)
  assert.equal(result.metadata.width, 120)
  const output = await sharp(Buffer.from(result.view, 'base64')).metadata()
  assert.equal(output.space, 'srgb'); assert.ok(output.icc); assert.equal(output.exif, undefined)
})
test('orientation is applied once, original survives and EXIF dates remain timezone-free', async () => {
  const source = await create().withMetadata({ orientation: 6 }).withExifMerge({ IFD0: { Make: 'Sony', Model: 'ILCE-6400' }, IFD2: { DateTimeOriginal: '2026:10:13 12:34:56', LensModel: 'Test 35mm', FNumber: '28/10', ExposureTime: '1/125', ISOSpeedRatings: '400' } }).jpeg().toBuffer()
  const result = await processPhoto(source)
  assert.equal(result.metadata.width, 80); assert.equal(result.metadata.height, 120)
  assert.equal(result.metadata.model, 'ILCE-6400')
  assert.equal(result.metadata.capturedAtRaw, '2026:10:13 12:34:56'); assert.equal(result.metadata.originalOffset, null)
  const output = await sharp(Buffer.from(result.view, 'base64')).metadata()
  assert.equal(output.width, 80); assert.equal(output.height, 120); assert.equal(output.orientation, undefined)
  assert.equal((await sharp(source).metadata()).orientation, 6)
})
test('signature, truncated JPEG and pixel limits reject without trusting MIME', async () => {
  await assert.rejects(processPhoto(await create().png().toBuffer()), { code: 'INVALID_JPEG' })
  const source = await create().jpeg().toBuffer()
  await assert.rejects(processPhoto(source.subarray(0, 20)), { code: 'DECODE_FAILED' })
  await assert.rejects(processPhoto(source, 100), { code: 'PIXEL_LIMIT' })
})
