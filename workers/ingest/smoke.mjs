import sharp from 'sharp'
import exifr from 'exifr'
import { createServer } from 'node:http'
const source = await sharp({ create: { width: 120, height: 80, channels: 3, background: '#aabbcc' } }).jpeg().toBuffer()
const thumbnail = await sharp(source).rotate().resize({ width: 60 }).toColourspace('srgb').jpeg().toBuffer()
const metadata = await sharp(thumbnail).metadata()
const exif = await exifr.parse(source)
if (metadata.width !== 60 || metadata.height !== 40) throw new Error('Thumbnail dimensions mismatch')
const result = { status: 'UP', mode: 'smoke-only', sharp: sharp.versions.sharp, width: metadata.width, height: metadata.height, missingExifHandled: !exif }
console.log(JSON.stringify(result))
createServer((request, response) => {
  response.writeHead(request.url === '/health' ? 200 : 404, { 'Content-Type': 'application/json' })
  response.end(JSON.stringify(request.url === '/health' ? result : { error: 'not_found' }))
}).listen(8090, '0.0.0.0')
