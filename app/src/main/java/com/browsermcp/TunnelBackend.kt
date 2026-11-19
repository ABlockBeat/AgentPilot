// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

/**
 * One tunnel backend, version 0.6.23.
 *
 * Our own connector is the default since 0.6.22: the phone and
 * the cloud both dial out to our own relay, no outside tunnel
 * service. Cloudflare Quick Tunnel (free, no account) and ngrok
 * (free account, one authtoken pasted once in Help) stay as
 * fallback. All backends share this shape, so the session owner
 * picks one and the rest of the app never learns which
 * connection is running.
 *
 * All callbacks run on the main thread, so the caller can touch the
 * UI in them directly.
 */
interface TunnelBackend {
    val installed: Boolean
    val running: Boolean
    val installing: Boolean
    val restarting: Boolean
    val publicUrl: String?
    fun install(onProgress: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit)
    fun start(onUrl: (String) -> Unit, onLog: (String) -> Unit, onStopped: () -> Unit, onError: (String) -> Unit)
    fun stop()
}
