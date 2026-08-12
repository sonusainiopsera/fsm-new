import { setupWorker } from 'msw/browser'
import { notificationPreferencesHandlers } from './handlers/notificationPreferences.js'

export const worker = setupWorker(...notificationPreferencesHandlers)
