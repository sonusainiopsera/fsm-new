/**
 * Synthetic minimal JPEG fixture — a 1×1 pixel red JPEG.
 * No real site imagery. Used in photo upload unit tests.
 *
 * Data URL of a 1×1 red JPEG (base64-encoded).
 */
export const SMALL_JPEG_DATA_URL =
  'data:image/jpeg;base64,' +
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoH' +
  'BwYIDAoMCwsKCwsNCxAQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQME' +
  'BAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU' +
  'FBQUFBQUFBT/wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/E' +
  'ABQQAQAAAAAAAAAAAAAAAAAAAAj/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAA' +
  'AAAAAAAAAAAAAAD/2gAMAwEAAhEDEQA/AJVAA//Z';

/** Returns a minimal synthetic JPEG File object for testing. */
export function makeSmallJpegFile(name = 'test-photo.jpg') {
  const binary = atob(SMALL_JPEG_DATA_URL.split(',')[1]);
  const bytes  = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new File([bytes], name, { type: 'image/jpeg' });
}

/** Returns a synthetic oversized file stub (6 MB) for the size-rejection test. */
export function makeOversizedFile(name = 'oversized.jpg') {
  const oversizedData = new Uint8Array(6 * 1024 * 1024);
  return new File([oversizedData], name, { type: 'image/jpeg' });
}
