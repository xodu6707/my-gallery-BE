import sharp from 'sharp'
import { mkdir, writeFile } from 'node:fs/promises'
const directory = process.argv[2] ?? '/tmp/photo-fixtures'
await mkdir(directory, { recursive: true })
const create = () => sharp({ create: { width: 120, height: 80, channels: 3, background: '#91a2a8' } })
await create().jpeg().toFile(`${directory}/plain.jpg`)
await create().withMetadata({ orientation: 6 }).withExifMerge({ IFD0: { Make: 'Sony', Model: 'ILCE-6400' }, IFD2: { DateTimeOriginal: '2026:10:13 12:34:56', LensModel: 'Test 35mm' } }).jpeg().toFile(`${directory}/rotated.jpg`)
await create().png().toFile(`${directory}/unsupported.png`)
await writeFile(`${directory}/broken.jpg`, Buffer.from([255,216,255,224,0,2,0,0,0]))
console.log('Created synthetic photo fixtures')
