// Package tailnet embeds a Tailscale node (tsnet) inside AgentPilot
// and proxies inbound tailnet connections to the Java MCP server on
// loopback.
//
// The node is a separate device on the tailnet with its own 100.x
// address. The cloud dials that address; the bridge accepts the
// connection on the tsnet listener and forwards the bytes to
// 127.0.0.1:8765, where the existing Java McpServer listens. No file
// descriptor ever crosses the gomobile boundary, and the Java server
// stays localhost-only.
//
// Android and the netlink trap: Android SELinux denies apps the
// NETLINK_ROUTE socket, so Go's net.Interfaces() fails and tsnet
// dies at Start. Tailscale's own androidbin feature fixes this with
// a synthetic interface list, but its hook is compiled out of
// android+cgo builds, which is what gomobile produces. So this
// bridge registers the same synthetic-interface fallback itself
// through netmon.RegisterInterfaceGetter before starting on Android:
// one made-up interface carrying the outbound source addresses,
// discovered by asking the kernel to route an outbound UDP socket
// (which sends no packets and needs no permission).
//
// Environment: before any start call the bridge sets HOME, TMPDIR,
// XDG_CACHE_HOME and TS_LOGS_DIR under app storage, and
// TS_NO_LOGS_NO_SUPPORT, so no log path ever points at /tmp, which
// does not exist in the app sandbox and panics the log code.
//
// The auth key is passed to tsnet only. It is never logged, stored,
// or returned by any function here.
package tailnet

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// tailscaleVersion is the tailscale.com release this bridge was built
// against. Version reports the real pinned version from the build
// info when it can, and falls back to this.
const tailscaleVersion = "v1.104.4"

// mcpPort is the Java McpServer port on loopback. The tsnet listener
// takes the same port number on the tailnet address.
const mcpPort = 8765

var (
	mu           sync.Mutex
	server       *tsnet.Server
	listener     net.Listener
	acceptDone   chan struct{}
	fallbackUsed bool
	lastError    string
	started      bool

	registerGetterOnce sync.Once

	authMu  sync.Mutex
	authURL string
)

// nodeStatus is the JSON Status returns. The auth key never appears
// here, in any log, or in any other output.
type nodeStatus struct {
	Up           bool   `json:"up"`
	IP           string `json:"ip"`
	IP6          string `json:"ip6"`
	Hostname     string `json:"hostname"`
	NeedsLogin   bool   `json:"needs_login"`
	AuthURL      string `json:"auth_url"`
	FallbackUsed bool   `json:"fallback_used"`
	Error        string `json:"error"`
	Version      string `json:"version"`
}

// setupEnv points every path tsnet and its logs may touch under app
// storage, and creates the directories. stateDir is the node state
// directory (filesDir/tsnet); the other dirs sit next to it.
func setupEnv(stateDir string) error {
	base := filepath.Dir(stateDir)
	dirs := map[string]string{
		"HOME":           base,
		"TMPDIR":         filepath.Join(base, "tmp"),
		"XDG_CACHE_HOME": filepath.Join(base, "cache"),
		"TS_LOGS_DIR":    filepath.Join(base, "tsnet-logs"),
	}
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return fmt.Errorf("create state dir: %w", err)
	}
	for _, d := range dirs {
		if err := os.MkdirAll(d, 0700); err != nil {
			return fmt.Errorf("create dir %s: %w", d, err)
		}
	}
	for k, v := range dirs {
		os.Setenv(k, v)
	}
	// No diagnostic log upload: the node is the owner's own device,
	// and its logs are nobody else's business.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "1")
	return nil
}

// onAndroid reports whether the process runs on Android, even if it
// is a GOOS=linux binary under an Android kernel. /dev/__properties__
// is the bionic property service backing store, present on every
// Android since 5.0 and never on regular Linux.
func onAndroid() bool {
	if runtime.GOOS == "android" {
		return true
	}
	_, err := os.Stat("/dev/__properties__")
	return err == nil
}

// syntheticInterfaces returns a single made-up interface carrying
// the process's outbound source addresses. It runs only where the OS
// denies interface enumeration, so it must not call net.Interfaces
// or Interface.Addrs itself: both would hit the same denial. The
// addresses come from outbound UDP dials, which only make the kernel
// pick a route and a source address and send no packets.
func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, errors.New("tailnet: no outbound routes found")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{
			MTU:   1500,
			Name:  "android",
			Flags: net.FlagUp | net.FlagRunning,
		},
		AltAddrs: addrs,
	}}, nil
}

// outboundIP reports the local source address the kernel picks for an
// outbound socket to addr. Dialing UDP performs no I/O, so this works
// under Android's app sandbox where interface enumeration does not.
func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok || ua.IP == nil {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}

// registerSyntheticGetter installs the synthetic interface list as
// the process-wide interface source. It is the same fallback
// Tailscale's own Android app registers from Java.
func registerSyntheticGetter() {
	registerGetterOnce.Do(func() {
		netmon.RegisterInterfaceGetter(syntheticInterfaces)
		fallbackUsed = true
	})
}

// isNetlinkError reports whether err looks like the Android
// interface-enumeration denial: the netlink socket is refused to
// app UIDs, so net.Interfaces fails at Start.
func isNetlinkError(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "netlink") ||
		strings.Contains(msg, "permission denied") ||
		strings.Contains(msg, "operation not permitted") ||
		strings.Contains(msg, "not permitted")
}

// newServer builds a node that enrolls with authKey (when given) and
// reports its interactive login URL through UserLogf.
func newServer(stateDir, authKey, hostname string, ephemeral bool) *tsnet.Server {
	return &tsnet.Server{
		Dir:       stateDir,
		Hostname:  hostname,
		AuthKey:   authKey,
		Ephemeral: ephemeral,
		UserLogf: func(format string, args ...any) {
			msg := fmt.Sprintf(format, args...)
			captureAuthURL(msg)
		},
		// Logf stays unset: backend logs are discarded.
	}
}

// captureAuthURL remembers the interactive login URL when tsnet
// prints one, so Status can show it. Only a login.tailscale.com
// device-auth URL is ever kept. It has its own lock: tsnet calls
// UserLogf from its own goroutines, never under the main lock.
func captureAuthURL(msg string) {
	const marker = "https://login.tailscale.com/a/"
	i := strings.Index(msg, marker)
	if i < 0 {
		return
	}
	rest := msg[i:]
	if j := strings.IndexAny(rest, " \t\r\n\"'"); j >= 0 {
		rest = rest[:j]
	}
	setAuthURL(rest)
}

func currentAuthURL() string {
	authMu.Lock()
	defer authMu.Unlock()
	return authURL
}

func setAuthURL(u string) {
	authMu.Lock()
	defer authMu.Unlock()
	authURL = u
}

// waitForUp waits until the node has a tailnet IPv4 address, or
// until the interactive login URL appears (no key, first run), or
// until timeoutMs passes.
func waitForUp(srv *tsnet.Server, timeoutMs int) (needsLogin bool, err error) {
	if timeoutMs <= 0 {
		timeoutMs = 60000
	}
	deadline := time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	for {
		ip4, _ := srv.TailscaleIPs()
		if ip4.IsValid() && !ip4.IsUnspecified() {
			return false, nil
		}
		if currentAuthURL() != "" {
			return true, nil
		}
		if time.Now().After(deadline) {
			return false, errors.New("tailnet: timed out waiting for the node to come up")
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// Start enrolls the embedded node and proxies inbound tailnet
// connections to the Java MCP server on 127.0.0.1:8765. stateDir is
// the node state directory (filesDir/tsnet); authKey is a one-time
// preauthorized key, empty when the node is already enrolled;
// hostname is the stable node name (for example "agentpilot").
// timeoutMs bounds the wait for the node to come up. Start must be
// called off the UI thread: it blocks until the node is up, the
// login URL appears, or the timeout passes. When the node is already
// enrolled, the auth key is ignored unless the state was cleared.
func Start(stateDir, authKey, hostname string, ephemeral bool, timeoutMs int) error {
	mu.Lock()
	defer mu.Unlock()
	if started {
		return errors.New("tailnet: already started")
	}
	if hostname == "" {
		hostname = "agentpilot"
	}
	if err := setupEnv(stateDir); err != nil {
		lastError = err.Error()
		return err
	}
	// On Android the interface list is denied, so install the
	// synthetic fallback before the first start. This is the path
	// Tailscale's own Android app takes.
	if onAndroid() {
		registerSyntheticGetter()
	}
	srv := newServer(stateDir, authKey, hostname, ephemeral)
	if err := srv.Start(); err != nil {
		// The fallback may not have been in place, or the
		// detection missed: retry once with it installed.
		if isNetlinkError(err) {
			registerSyntheticGetter()
			srv.Close()
			srv = newServer(stateDir, authKey, hostname, ephemeral)
			if err2 := srv.Start(); err2 != nil {
				lastError = err2.Error()
				return fmt.Errorf("tailnet: start failed after interface fallback: %w", err2)
			}
		} else {
			lastError = err.Error()
			return fmt.Errorf("tailnet: start failed: %w", err)
		}
	}
	ln, err := srv.Listen("tcp", fmt.Sprintf(":%d", mcpPort))
	if err != nil {
		srv.Close()
		lastError = err.Error()
		return fmt.Errorf("tailnet: listen failed: %w", err)
	}
	done := make(chan struct{})
	go acceptLoop(ln, done)
	if _, err := waitForUp(srv, timeoutMs); err != nil {
		ln.Close()
		srv.Close()
		<-done
		lastError = err.Error()
		return err
	}
	server = srv
	listener = ln
	acceptDone = done
	started = true
	lastError = ""
	return nil
}

// Stop closes the tailnet listener and the node. It is safe to call
// when the node is not running. Call it off the UI thread: closing
// the node can block briefly.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if !started {
		return nil
	}
	if listener != nil {
		listener.Close()
	}
	if acceptDone != nil {
		<-acceptDone
	}
	if server != nil {
		server.Close()
	}
	server = nil
	listener = nil
	acceptDone = nil
	setAuthURL("")
	started = false
	lastError = ""
	return nil
}

// ClearState wipes the node state directory, so the next Start
// enrolls a fresh node. The node must be stopped first: wiping
// state under a running node has no defined outcome.
func ClearState(stateDir string) error {
	mu.Lock()
	defer mu.Unlock()
	if started {
		return errors.New("tailnet: stop the node before clearing its state")
	}
	if err := os.RemoveAll(stateDir); err != nil {
		return fmt.Errorf("tailnet: clear state failed: %w", err)
	}
	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return fmt.Errorf("tailnet: recreate state dir failed: %w", err)
	}
	setAuthURL("")
	lastError = ""
	return nil
}

// Status returns the node state as JSON: up, the 100.x addresses,
// hostname, whether an interactive login is needed and its URL, and
// the last error. It never contains the auth key.
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	st := nodeStatus{
		FallbackUsed: fallbackUsed,
		Error:        lastError,
		Version:      Version(),
	}
	if server != nil {
		ip4, ip6 := server.TailscaleIPs()
		if ip4.IsValid() {
			st.IP = ip4.String()
		}
		if ip6.IsValid() {
			st.IP6 = ip6.String()
		}
		st.Hostname = server.Hostname
		st.Up = started && ip4.IsValid() && !ip4.IsUnspecified()
	}
	st.NeedsLogin = currentAuthURL() != "" && !st.Up
	st.AuthURL = currentAuthURL()
	b, err := json.Marshal(st)
	if err != nil {
		return `{"up":false,"error":"tailnet: status encode failed"}`
	}
	return string(b)
}

// TailnetIP returns the node's IPv4 tailnet address (100.x), or an
// empty string when the node is not up.
func TailnetIP() string {
	mu.Lock()
	defer mu.Unlock()
	if server == nil {
		return ""
	}
	ip4, _ := server.TailscaleIPs()
	if !ip4.IsValid() || ip4.IsUnspecified() {
		return ""
	}
	return ip4.String()
}

// Version returns the tailscale.com release this bridge was built
// against, as the version pinned in go.mod.
func Version() string {
	if info, ok := debug.ReadBuildInfo(); ok {
		for _, dep := range info.Deps {
			if dep.Path == "tailscale.com" {
				return dep.Version
			}
		}
	}
	return tailscaleVersion
}

// acceptLoop forwards every inbound tailnet connection to the Java
// MCP server on loopback. It ends when the listener closes.
func acceptLoop(ln net.Listener, done chan struct{}) {
	defer close(done)
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go proxyConn(c)
	}
}

// proxyConn forwards one inbound connection to the Java server and
// copies bytes both ways until either side closes.
func proxyConn(in net.Conn) {
	out, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", mcpPort), 5*time.Second)
	if err != nil {
		in.Close()
		return
	}
	done := make(chan struct{}, 2)
	go func() {
		io.Copy(out, in)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(in, out)
		done <- struct{}{}
	}()
	<-done
	in.Close()
	out.Close()
}
