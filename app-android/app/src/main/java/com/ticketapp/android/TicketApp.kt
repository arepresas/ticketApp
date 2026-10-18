package com.ticketapp.android

import android.app.Application

/**
 * Application entry point.
 *
 * Currently a marker only: the session store and HTTP client are
 * created per-use rather than held as singletons, so there is no
 * process-wide state to warm up here. It exists so the manifest has a
 * stable application class to grow into (crash reporting, DI graph)
 * without a later manifest migration.
 */
class TicketApp : Application()