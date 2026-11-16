package discover

import (
	"context"
	"net"
	"testing"
	"time"
)

// serve starts a responder on a loopback port and returns its address.
func serve(t *testing.T, announce func() Announcement) net.Addr {
	t.Helper()
	conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- Serve(ctx, conn, announce) }()
	t.Cleanup(func() {
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Errorf("Serve returned %v after its context ended, want nil", err)
			}
		case <-time.After(2 * time.Second):
			t.Error("Serve did not stop when its context ended")
		}
	})
	return conn.LocalAddr()
}

func client(t *testing.T) net.PacketConn {
	t.Helper()
	conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

func TestProbeFindsTheServer(t *testing.T) {
	addr := serve(t, func() Announcement {
		return Announcement{Name: "nvr", Port: 8124, Version: "v2026.11.12", Service: "something else"}
	})

	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	found, err := Probe(ctx, client(t), addr)
	if err != nil {
		t.Fatalf("Probe: %v", err)
	}
	if len(found) != 1 {
		t.Fatalf("found %d servers, want 1: %+v", len(found), found)
	}
	got := found[0]
	if got.Host != "127.0.0.1" || got.Port != 8124 || got.Name != "nvr" || got.Version != "v2026.11.12" {
		t.Errorf("found %+v", got)
	}
	// The responder stamps the service itself, whatever announce said.
	if got.Service != Service {
		t.Errorf("service = %q, want %q", got.Service, Service)
	}
}

func TestOnlyTheExactQueryIsAnswered(t *testing.T) {
	addr := serve(t, func() Announcement { return Announcement{Name: "nvr", Port: 8124} })
	conn := client(t)

	for _, junk := range []string{"", "hello", "CLIP-HANGER-DISCOVER/2", Query + "x", "SAND-TV-DISCOVER/1"} {
		if _, err := conn.WriteTo([]byte(junk), addr); err != nil {
			t.Fatal(err)
		}
	}
	conn.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
	buf := make([]byte, 512)
	if n, _, err := conn.ReadFrom(buf); err == nil {
		t.Fatalf("answered a packet that was not the query: %q", buf[:n])
	}
}

func TestProbeIgnoresAnswersThatAreNotOurs(t *testing.T) {
	// Something else on the port answers with JSON of the wrong shape.
	impostor, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer impostor.Close()
	go func() {
		buf := make([]byte, 512)
		_, from, err := impostor.ReadFrom(buf)
		if err != nil {
			return
		}
		impostor.WriteTo([]byte(`{"service":"sand-vault","port":8123}`), from)
		impostor.WriteTo([]byte(`not json`), from)
		impostor.WriteTo([]byte(`{"service":"clip-hanger","port":0}`), from)
		impostor.WriteTo([]byte(`{"service":"clip-hanger","port":70000}`), from)
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	found, err := Probe(ctx, client(t), impostor.LocalAddr())
	if err != nil {
		t.Fatalf("Probe: %v", err)
	}
	if len(found) != 0 {
		t.Errorf("found %+v, want nothing", found)
	}
}

func TestAnnounceIsAskedEachTime(t *testing.T) {
	n := 0
	addr := serve(t, func() Announcement { n++; return Announcement{Name: "nvr", Port: 8124 + n} })
	for want := 8125; want <= 8126; want++ {
		ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
		found, err := Probe(ctx, client(t), addr)
		cancel()
		if err != nil || len(found) != 1 || found[0].Port != want {
			t.Fatalf("probe %d: %+v, %v", want, found, err)
		}
	}
}

func TestAnswerableIsTheLocalNetworkOnly(t *testing.T) {
	for ip, want := range map[string]bool{
		"127.0.0.1":      true,
		"192.168.1.20":   true,
		"10.0.0.5":       true,
		"172.16.4.4":     true,
		"169.254.10.1":   true,
		"100.101.102.10": true, // Tailscale
		"8.8.8.8":        false,
		"203.0.113.7":    false,
	} {
		if got := answerable(&net.UDPAddr{IP: net.ParseIP(ip), Port: 5000}); got != want {
			t.Errorf("answerable(%s) = %v, want %v", ip, got, want)
		}
	}
	if answerable(&net.TCPAddr{IP: net.ParseIP("127.0.0.1")}) {
		t.Error("answered something that is not a UDP address")
	}
}

func TestEnabled(t *testing.T) {
	for _, tc := range []struct {
		flag      bool
		env, bind string
		want      bool
	}{
		{true, "", "0.0.0.0", true},
		{true, "", "192.168.1.20", true},
		{true, "", "", true},
		{false, "", "0.0.0.0", false},
		{true, "off", "0.0.0.0", false},
		{true, "OFF", "0.0.0.0", false},
		{true, "0", "0.0.0.0", false},
		{true, "no", "0.0.0.0", false},
		{true, "on", "0.0.0.0", true},
		// Nobody who could hear the answer could reach a loopback server.
		{true, "", "127.0.0.1", false},
		{true, "", "127.0.0.2", false},
		{true, "", "localhost", false},
		{true, "", "::1", false},
		{true, "", "[::1]", false},
	} {
		if got := Enabled(tc.flag, tc.env, tc.bind); got != tc.want {
			t.Errorf("Enabled(%v, %q, %q) = %v, want %v", tc.flag, tc.env, tc.bind, got, tc.want)
		}
	}
}
