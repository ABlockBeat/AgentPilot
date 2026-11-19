// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

/**
 * One tunnel backend, version 0.6.21.
 *
 * Cloudflare Quick Tunnel is the default: free, no account. ngrok
 * is the alternative when Cloudflare does not start on a phone:
 * free account, one authtoken pasted once in Help. Both backends
 * share this shape, so the session owner picks one and the rest of
 * the app never learns which tunnel is running.
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
