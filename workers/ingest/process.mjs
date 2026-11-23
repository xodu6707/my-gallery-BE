import sharp from 'sharp'
import exifr from 'exifr'

export class ImageProblem extends Error { constructor(code) { super(code); this.code = code } }
export async function processPhoto(input, maxPixels = 100_000_000) {
  if (input.length < 4 || input[0] !== 255 || input[1] !== 216 || input[2] !== 255) throw new ImageProblem('INVALID_JPEG')
  let info
  try { info = await sharp(input, { limitInputPixels: false }).metadata() }
  catch { throw new ImageProblem('DECODE_FAILED') }
  if (info.format !== 'jpeg') throw new ImageProblem('INVALID_JPEG')
  if (!info.width || !info.height || info.width * info.height > maxPixels) throw new ImageProblem('PIXEL_LIMIT')
  const options = { limitInputPixels: maxPixels, failOn: 'error' }
  let thumbnail, view
  try {
    thumbnail = await sharp(input, options).autoOrient().resize({ width: 480, height: 480, fit: 'inside', withoutEnlargement: true }).withIccProfile('srgb').jpeg({ quality: 80 }).toBuffer()
    view = await sharp(input, options).autoOrient().resize({ width: 2560, height: 2560, fit: 'inside', withoutEnlargement: true }).withIccProfile('srgb').jpeg({ quality: 90 }).toBuffer()
  } catch { throw new ImageProblem('DECODE_FAILED') }
  let tags = {}, exifStatus = info.exif ? 'PRESENT' : 'ABSENT'
  try {
    tags = await exifr.parse(input, { pick: ['Make', 'Model', 'LensModel', 'FocalLength', 'FNumber', 'ExposureTime', 'ISO', 'DateTimeOriginal', 'OffsetTimeOriginal'], reviveValues: false, translateValues: false, silentErrors: false, gps: false }) ?? {}
  } catch { exifStatus = 'PARSE_FAILED' }
  const text = name => typeof tags[name] === 'string' ? tags[name].replace(/[\u0000-\u001f]/g, '').slice(0, 160) : null
  const number = name => typeof tags[name] === 'number' && Number.isFinite(tags[name]) && tags[name] > 0 ? tags[name] : null
  const swapped = [5, 6, 7, 8].includes(info.orientation)
  return {
    pipelineVersion: 'jpeg-srgb-v1',
    metadata: {
      width: swapped ? info.height : info.width, height: swapped ? info.width : info.height,
      originalWidth: info.width, originalHeight: info.height, orientation: info.orientation ?? null,
      make: text('Make'), model: text('Model'), lens: text('LensModel'), focalLength: number('FocalLength'),
      aperture: number('FNumber'), exposureSeconds: number('ExposureTime'), iso: number('ISO'),
      capturedAtRaw: text('DateTimeOriginal'), originalOffset: text('OffsetTimeOriginal'),
      source: 'EXIF', exifStatus, originalHasIcc: !!info.icc, displayColourSpace: 'sRGB',
    },
    thumbnail: thumbnail.toString('base64'), view: view.toString('base64'),
  }
}
