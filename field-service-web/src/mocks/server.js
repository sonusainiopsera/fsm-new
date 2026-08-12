import { setupServer } from 'msw/node'
import { notificationPreferencesHandlers } from './handlers/notificationPreferences.js'

export const server = setupServer(...notificationPreferencesHandlers)
