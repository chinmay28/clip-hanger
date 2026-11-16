// Package discover lets the Clip Hanger phone app find a server on the same
// network without being told its address.
//
// The app shouts: it broadcasts a fixed query to a UDP port, and every Clip
// Hanger server listening answers with what the app needs to build an address
// — the port its web server is on, a name to show in a list, and its version.
// The address itself is wherever the answer came from.
//
// The answer gives away nothing the web port does not already say to anyone
// who can reach it: that Clip Hanger is here, and where. It authorizes nothing.
//
// Two limits keep it from being a nuisance. It answers only the exact query,
// so stray packets are ignored rather than echoed; and it answers only
// addresses on a private, loopback or link-local network (or Tailscale's
// carrier-grade NAT range), so a server on a public address cannot be used to
// reflect traffic at a stranger.
//
// It is optional and inert by default for a server nobody else can reach:
// `clip serve` starts it only when the web server is bound to something other
// than loopback, and `--discovery=false` (or CLIP_DISCOVERY=off) turns it off.
package discover

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"
)

// DefaultPort is the UDP port the responder listens on — the one after the
// web server's default (8124), so the pair are easy to open in a firewall
// together.
const DefaultPort = 8125

// Query is what the app broadcasts. Versioned, so a later query that wants a
// different answer can be told apart from this one.
const Query = "CLIP-HANGER-DISCOVER/1"

// Service is what every answer says it is, so a client can tell this answer
// from anything else that happens to be on the port.
const Service = "clip-hanger"

// Announcement is the answer.
type Announcement struct {
	Service string `json:"service"`

	// Name is what the app lists this server as — the machine's hostname.
	Name string `json:"name"`

	// Port is the web server's port. The scheme is http unless Secure.
	Port    int    `json:"port"`
	Secure  bool   `json:"secure,omitempty"`
	Version string `json:"version,omitempty"`
}

// Found is one answer to a probe, with the address it came from.
type Found struct {
	Announcement
	Host string `json:"host"`
}

// Serve answers queries arriving on conn until ctx is done, then closes conn
// and returns nil. announce is asked for the answer each time, so nothing in
// it can go stale.
func Serve(ctx context.Context, conn net.PacketConn, announce func() Announcement) error {
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()

	buf := make([]byte, 512)
	for {
		n, from, err := conn.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			if errors.Is(err, net.ErrClosed) {
				return err
			}
			// A transient read error — an ICMP unreachable surfacing on a
			// later read, say — is not a reason to stop answering.
			continue
		}
		if strings.TrimSpace(string(buf[:n])) != Query || !answerable(from) {
			continue
		}
		reply := announce()
		reply.Service = Service
		body, err := json.Marshal(reply)
		if err != nil {
			continue
		}
		conn.WriteTo(body, from)
	}
}

// Listen opens the UDP port and serves it until ctx is done. addr is a
// host:port; an empty host listens on every interface, which is what
// receiving a broadcast needs.
func Listen(ctx context.Context, addr string, announce func() Announcement) error {
	conn, err := net.ListenPacket("udp4", addr)
	if err != nil {
		return err
	}
	return Serve(ctx, conn, announce)
}

// Enabled reports whether `serve` should run the responder: not when asked
// not to (flag false, or env "off"/"0"/"false"/"no"), and not when the web
// server is bound to loopback — then nobody who could hear the answer could
// reach the address it names.
func Enabled(flag bool, env string, bind string) bool {
	switch strings.ToLower(strings.TrimSpace(env)) {
	case "off", "0", "false", "no":
		return false
	}
	if !flag {
		return false
	}
	host := strings.Trim(strings.TrimSpace(bind), "[]")
	if host == "localhost" {
		return false
	}
	if ip, err := netip.ParseAddr(host); err == nil && ip.Unmap().IsLoopback() {
		return false
	}
	return true
}

// answerable reports whether a query came from somewhere worth answering:
// the local network, never the internet.
func answerable(from net.Addr) bool {
	udp, ok := from.(*net.UDPAddr)
	if !ok {
		return false
	}
	ip, ok := netip.AddrFromSlice(udp.IP)
	if !ok {
		return false
	}
	ip = ip.Unmap()
	return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() ||
		// Carrier-grade NAT space, which is what Tailscale hands out.
		netip.MustParsePrefix("100.64.0.0/10").Contains(ip)
}

// Probe sends the query to target and collects answers until ctx is done (or
// two seconds pass, without a deadline). It is what the phone app does,
// written here so the round trip is tested from both ends.
func Probe(ctx context.Context, conn net.PacketConn, target net.Addr) ([]Found, error) {
	if _, err := conn.WriteTo([]byte(Query), target); err != nil {
		return nil, err
	}

	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(2 * time.Second)
	}
	conn.SetReadDeadline(deadline)

	var found []Found
	seen := map[string]bool{}
	buf := make([]byte, 2048)
	for {
		n, from, err := conn.ReadFrom(buf)
		if err != nil {
			var timeout net.Error
			if errors.As(err, &timeout) && timeout.Timeout() {
				return found, nil
			}
			return found, err
		}
		var a Announcement
		if json.Unmarshal(buf[:n], &a) != nil || a.Service != Service || a.Port <= 0 || a.Port > 65535 {
			continue
		}
		host := from.String()
		if udp, ok := from.(*net.UDPAddr); ok {
			host = udp.IP.String()
		}
		key := net.JoinHostPort(host, strconv.Itoa(a.Port))
		if seen[key] {
			continue
		}
		seen[key] = true
		found = append(found, Found{Announcement: a, Host: host})
	}
}
