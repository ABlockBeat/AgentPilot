// Copyright (c) 2026 ABlockBeat. All rights reserved.

package com.browsermcp

/**
 * The one shared allowlist, version 0.6.25: the only apps
 * AgentPilot may drive. Browser + crypto exchanges + X, nothing
 * else. The picker and the MCP boundary both use this list, so
 * the scope rule lives in one place and cannot drift between
 * the UI and the server.
 */
object AllowedApps {

    val PACKAGES: List<String> = listOf(
        "com.binance.dev",
        "com.okinc.okx",
        "com.okinc.okex.gp",
        "com.bitget.exchange",
        "com.kubi.kucoin",
        "com.gateio.gateio",
        "com.wake.weexprd",
        "com.twitter.android",
        "com.android.chrome"
    )

    fun isAllowed(packageName: String): Boolean = PACKAGES.contains(packageName)

    /**
     * Refuse a package outside the allowlist with a plain error.
     * One rule, one message, used at both boundaries: the picker
     * never offers it, and drive_open_app never opens it.
     */
    fun requireAllowed(packageName: String) {
        if (!isAllowed(packageName)) {
            throw RuntimeException("That app is not allowed. AgentPilot drives only the browser, exchanges and X.")
        }
    }
}
