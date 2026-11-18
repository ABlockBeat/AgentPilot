# Embedded Tailscale spike (Phase 0)

The AgentPilot app embeds a Tailscale node (tsnet) so the owner
never installs the separate Tailscale app. This directory is the
spike: prove it on the owner's phone, or stop and stay on the
separate-app Tailscale mode (v0.6.16).

## Layout

- `tailnet/bridge.go` — the Go bridge (package `tailnet`). Wraps
  `tsnet.Server`, proxies inbound tailnet connections to the Java
  MCP server on 127.0.0.1:8765, and registers the synthetic
  interface fallback for Android's netlink denial. ASCII only.
- `go.mod`, `go.sum` — module `agentpilot-tailnet`. Pinned: Go
  1.27.3, tailscale.com v1.104.4, x/mobile 20260709.
- `build-aar.sh` — builds the AAR with gomobile bind (arm64-v8a
  only). Run from the repo root: `bash tailnet/build-aar.sh`.
  Output: `app/libs/agentpilot-tailnet.aar`, never committed.

## The gomobile bind command

Run from `tailnet/` (the script does this):

```
gomobile bind \
  -target=android/arm64 \
  -androidapi 26 \
  -javapkg=com.browsermcp \
  -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384,-Wl,-z,common-page-size=16384" \
  -o app/libs/agentpilot-tailnet.aar \
  ./tailnet
```

`-javapkg=com.browsermcp` plus Go package `tailnet` yields the
Java class `com.browsermcp.tailnet.Tailnet`. The build script
verifies that class exists in the AAR's classes.jar, and that the
only native lib is `jni/arm64-v8a/libgojni.so`.

## Kotlin harness

`app/src/main/java/com/browsermcp/TailnetTestActivity.kt` — a hidden
debug screen marked TEST, reached from Settings. Paste a one-time
auth key, Start, read the Status JSON and the 100.x IP, Stop.
Clear state wipes the node so the next Start enrolls fresh. The key
is cleared from the field at once and never saved, shown, or logged.

## Go / no-go (on the owner's phone, Android 12, arm64)

1. Start succeeds (netlink trap resolved or fallback working).
2. `TailscaleIPs()` returns a 100.x address.
3. The cloud can open `http://<100.x>:8765/mcp` through the Go
   proxy to the Java McpServer.

If any of the three fails, stop here and stay on v0.6.16
separate-app mode.

## Notes

- The bridge sets HOME, TMPDIR, XDG_CACHE_HOME, TS_LOGS_DIR under
  app storage and TS_NO_LOGS_NO_SUPPORT before any start call.
  Node state lives in `noBackupFilesDir/tsnet`.
- The 16 KiB page-size linker flags keep the .so loadable on
  Android 15+ 16 KB-page devices.
- The UI test workflow builds the same arm64-only AAR: its
  emulator is x86_64, so the native lib never loads there, and the
  Maestro flow never opens the TEST screen. The AAR step is still
  required there, or Gradle fails on the missing file dependency.
- Before any release ships this: add the Tailscale BSD 3-Clause
  notice to the repo and surface it in Settings (licence
  requirement, Phase 1).
