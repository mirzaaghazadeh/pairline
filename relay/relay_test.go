package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

// testRelay runs a relay on a loopback port with short timings.
type testRelay struct {
	t    *testing.T
	s    *server
	addr string
	dir  string
	id   *identity
	key  [accessKeySize]byte
}

func startRelay(t *testing.T, change func(*limits)) *testRelay {
	t.Helper()
	dir := t.TempDir()
	id, _, err := loadOrCreateIdentity(dir)
	if err != nil {
		t.Fatal(err)
	}
	key, _, err := loadOrCreateAccessKey(dir)
	if err != nil {
		t.Fatal(err)
	}
	l := defaultLimits
	l.keepAlive = 50 * time.Millisecond
	l.hold = 300 * time.Millisecond
	l.preamble = time.Second
	l.idle = 2 * time.Second
	if change != nil {
		change(&l)
	}
	s := newServer(id, key, l, t.Logf)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		s.serve(ctx, ln)
		close(done)
	}()
	t.Cleanup(func() {
		cancel()
		<-done
	})
	return &testRelay{t: t, s: s, addr: ln.Addr().String(), dir: dir, id: id, key: key}
}

// dial connects as a phone would: TLS, accepting the server only by the pinned fingerprint.
func (r *testRelay) dial(fp [32]byte) (*tls.Conn, error) {
	return tls.Dial("tcp", r.addr, &tls.Config{
		InsecureSkipVerify: true, // replaced by the pin check below, as on the phones
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			if len(raw) == 0 || sha256.Sum256(raw[0]) != fp {
				return errors.New("pin mismatch")
			}
			return nil
		},
	})
}

func preamble(role byte, key [accessKeySize]byte, id rendezvousID) []byte {
	b := append([]byte("PLRY"), 0x01, role)
	b = append(b, key[:]...)
	return append(b, id[:]...)
}

func (r *testRelay) connect(role byte, id rendezvousID) *tls.Conn {
	r.t.Helper()
	c, err := r.dial(r.id.fingerprint)
	if err != nil {
		r.t.Fatal(err)
	}
	if _, err := c.Write(preamble(role, r.key, id)); err != nil {
		r.t.Fatal(err)
	}
	r.t.Cleanup(func() { c.Close() })
	return c
}

func readByte(t *testing.T, c net.Conn, within time.Duration) (byte, error) {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(within))
	defer c.SetReadDeadline(time.Time{})
	var b [1]byte
	_, err := io.ReadFull(c, b[:])
	return b[0], err
}

// readStatus skips keep-alives and returns the first other byte.
func readStatus(t *testing.T, c net.Conn, within time.Duration) byte {
	t.Helper()
	deadline := time.Now().Add(within)
	for {
		b, err := readByte(t, c, time.Until(deadline))
		if err != nil {
			t.Fatalf("no status byte: %v", err)
		}
		if b != statusKeepAlive {
			return b
		}
	}
}

func mustClose(t *testing.T, c net.Conn) {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 64)
	for {
		_, err := c.Read(buf)
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				t.Fatal("the relay didn't close the connection")
			}
			return
		}
	}
}

func rid(b byte) rendezvousID {
	var id rendezvousID
	for i := range id {
		id[i] = b
	}
	return id
}

func TestBridgeHearsKeepAlives(t *testing.T) {
	r := startRelay(t, nil)
	bridge := r.connect(roleBridge, rid(1))
	for i := 0; i < 3; i++ {
		b, err := readByte(t, bridge, time.Second)
		if err != nil || b != statusKeepAlive {
			t.Fatalf("keep-alive %d: %#x, %v", i, b, err)
		}
	}
}

func TestABridgeHearsAKeepAliveAtOnce(t *testing.T) {
	r := startRelay(t, func(l *limits) { l.keepAlive = time.Hour })
	bridge := r.connect(roleBridge, rid(2))
	if b, err := readByte(t, bridge, time.Second); err != nil || b != statusKeepAlive {
		t.Fatalf("no keep-alive right after the preamble: %#x %v", b, err)
	}
}

func TestSessionThroughTheRelay(t *testing.T) {
	r := startRelay(t, nil)
	bridge := r.connect(roleBridge, rid(7))
	// Let the bridge register before the iPhone arrives.
	if b, err := readByte(t, bridge, time.Second); err != nil || b != statusKeepAlive {
		t.Fatalf("keep-alive: %#x %v", b, err)
	}
	phone := r.connect(roleRemote, rid(7))
	if b := readStatus(t, phone, time.Second); b != statusJoined {
		t.Fatalf("iPhone got %#x", b)
	}
	if b := readStatus(t, bridge, time.Second); b != statusJoined {
		t.Fatalf("bridge got %#x", b)
	}
	// The iPhone speaks first (its HELLO), then the bridge answers; then a large transfer each way.
	hello := bytes.Repeat([]byte{0x10}, 104)
	if _, err := phone.Write(hello); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(hello))
	bridge.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := io.ReadFull(bridge, got); err != nil || !bytes.Equal(got, hello) {
		t.Fatalf("bridge read %x, %v", got, err)
	}
	big := bytes.Repeat([]byte("pairline"), 64*1024)
	go bridge.Write(big)
	back := make([]byte, len(big))
	phone.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(phone, back); err != nil || !bytes.Equal(back, big) {
		t.Fatalf("iPhone read %d bytes, %v", len(back), err)
	}
	// One side closing closes the other.
	phone.Close()
	mustClose(t, bridge)
}

func TestIPhoneIsHeldForABridgeThatArrives(t *testing.T) {
	r := startRelay(t, func(l *limits) { l.hold = 2 * time.Second })
	phone := r.connect(roleRemote, rid(3))
	time.Sleep(100 * time.Millisecond)
	bridge := r.connect(roleBridge, rid(3))
	if b := readStatus(t, phone, 2*time.Second); b != statusJoined {
		t.Fatalf("iPhone got %#x", b)
	}
	if b := readStatus(t, bridge, 2*time.Second); b != statusJoined {
		t.Fatalf("bridge got %#x", b)
	}
}

func TestNoBridge(t *testing.T) {
	r := startRelay(t, nil)
	phone := r.connect(roleRemote, rid(4))
	if b := readStatus(t, phone, 2*time.Second); b != statusNoBridge {
		t.Fatalf("got %#x", b)
	}
	mustClose(t, phone)
}

func TestOtherRendezvousIDsDontMeet(t *testing.T) {
	r := startRelay(t, nil)
	r.connect(roleBridge, rid(5))
	phone := r.connect(roleRemote, rid(6))
	if b := readStatus(t, phone, 2*time.Second); b != statusNoBridge {
		t.Fatalf("got %#x", b)
	}
}

func TestWrongKey(t *testing.T) {
	r := startRelay(t, nil)
	c, err := r.dial(r.id.fingerprint)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	wrong := r.key
	wrong[0] ^= 1
	c.Write(preamble(roleBridge, wrong, rid(1)))
	if b := readStatus(t, c, time.Second); b != statusWrongKey {
		t.Fatalf("got %#x", b)
	}
	mustClose(t, c)
}

func TestMalformedPreambles(t *testing.T) {
	r := startRelay(t, nil)
	cases := map[string][]byte{
		"magic":   append([]byte("XLRY"), preamble(roleBridge, r.key, rid(1))[4:]...),
		"version": func() []byte { b := preamble(roleBridge, r.key, rid(1)); b[4] = 2; return b }(),
		"role":    func() []byte { b := preamble(roleBridge, r.key, rid(1)); b[5] = 'X'; return b }(),
	}
	for name, pre := range cases {
		c, err := r.dial(r.id.fingerprint)
		if err != nil {
			t.Fatal(err)
		}
		c.Write(pre)
		if b := readStatus(t, c, time.Second); b != statusBadVersion {
			t.Errorf("%s: got %#x", name, b)
		}
		c.Close()
	}
}

func TestShortPreambleTimesOut(t *testing.T) {
	r := startRelay(t, func(l *limits) { l.preamble = 300 * time.Millisecond })
	c, err := r.dial(r.id.fingerprint)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.Write([]byte("PLRY"))
	mustClose(t, c)
}

func TestWrongPinIsRefusedByTheClient(t *testing.T) {
	r := startRelay(t, nil)
	other := r.id.fingerprint
	other[0] ^= 1
	if _, err := r.dial(other); err == nil {
		t.Fatal("a connection with the wrong pin went through")
	}
}

func TestANewerBridgeReplacesTheOlder(t *testing.T) {
	r := startRelay(t, nil)
	older := r.connect(roleBridge, rid(8))
	readByte(t, older, time.Second)
	newer := r.connect(roleBridge, rid(8))
	readByte(t, newer, time.Second)
	mustClose(t, older)
	phone := r.connect(roleRemote, rid(8))
	if b := readStatus(t, phone, time.Second); b != statusJoined {
		t.Fatalf("iPhone got %#x", b)
	}
	if b := readStatus(t, newer, time.Second); b != statusJoined {
		t.Fatalf("newer bridge got %#x", b)
	}
}

func TestABridgeThatSpeaksBeforeTheJoinIsDropped(t *testing.T) {
	r := startRelay(t, nil)
	bridge := r.connect(roleBridge, rid(9))
	readByte(t, bridge, time.Second)
	bridge.Write([]byte{0x00, 0x00, 0x00, 0x64})
	mustClose(t, bridge)
	phone := r.connect(roleRemote, rid(9))
	if b := readStatus(t, phone, 2*time.Second); b != statusNoBridge {
		t.Fatalf("got %#x", b)
	}
}

func TestIdleSessionsAreClosed(t *testing.T) {
	r := startRelay(t, func(l *limits) { l.idle = 300 * time.Millisecond })
	bridge := r.connect(roleBridge, rid(10))
	readByte(t, bridge, time.Second)
	phone := r.connect(roleRemote, rid(10))
	readStatus(t, phone, time.Second)
	readStatus(t, bridge, time.Second)
	mustClose(t, phone)
	mustClose(t, bridge)
}

func TestFull(t *testing.T) {
	r := startRelay(t, func(l *limits) { l.maxConnections = 2 })
	r.connect(roleBridge, rid(11))
	r.connect(roleBridge, rid(12))
	time.Sleep(100 * time.Millisecond)
	third := r.connect(roleBridge, rid(13))
	if b := readStatus(t, third, time.Second); b != statusFull {
		t.Fatalf("got %#x", b)
	}
}

func TestAnAddressWithTooManyWrongKeysIsTurnedAway(t *testing.T) {
	r := startRelay(t, func(l *limits) { l.maxFailures = 3 })
	wrong := r.key
	wrong[1] ^= 1
	for i := 0; i < 3; i++ {
		c, err := r.dial(r.id.fingerprint)
		if err != nil {
			t.Fatal(err)
		}
		c.Write(preamble(roleBridge, wrong, rid(1)))
		readStatus(t, c, time.Second)
		c.Close()
	}
	if c, err := r.dial(r.id.fingerprint); err == nil {
		c.Close()
		t.Fatal("still accepted after too many wrong keys")
	}
}

func TestBehindAProxyWrongKeysDontTurnEveryoneAway(t *testing.T) {
	// Behind a proxy every connection comes from its address: one address's mistakes must not lock out the rest.
	r := startRelay(t, func(l *limits) { l.maxFailures = 2; l.perMinute = 3; l.perAddress = false })
	wrong := r.key
	wrong[1] ^= 1
	for i := 0; i < 5; i++ {
		c, err := r.dial(r.id.fingerprint)
		if err != nil {
			t.Fatalf("connection %d turned away: %v", i, err)
		}
		c.Write(preamble(roleBridge, wrong, rid(1)))
		readStatus(t, c, time.Second)
		c.Close()
	}
	bridge := r.connect(roleBridge, rid(2))
	if b, err := readByte(t, bridge, time.Second); err != nil || b != statusKeepAlive {
		t.Fatalf("the right key was turned away: %#x %v", b, err)
	}
}

func TestRateLimitPerAddress(t *testing.T) {
	g := newAdmissionGate(3, 10, time.Minute)
	a := netip.MustParseAddr("2a01:4f8::1")
	b := netip.MustParseAddr("2a01:4f8::2") // the same /64
	c := netip.MustParseAddr("2a01:4f9::1")
	now := time.Unix(1_800_000_000, 0)
	if !g.admit(a, now) || !g.admit(b, now) || !g.admit(a, now) {
		t.Fatal("first three refused")
	}
	if g.admit(b, now) {
		t.Fatal("a fourth from the same /64 within a minute was admitted")
	}
	if !g.admit(c, now) {
		t.Fatal("another /64 was refused")
	}
	if !g.admit(a, now.Add(61*time.Second)) {
		t.Fatal("still refused a minute later")
	}
}

func TestHealthcheck(t *testing.T) {
	r := startRelay(t, nil)
	_, port, _ := net.SplitHostPort(r.addr)
	if err := healthcheck(config{listen: ":" + port, data: r.dir}, time.Second); err != nil {
		t.Fatalf("a running relay is unhealthy: %v", err)
	}
	// Another relay's certificate on this port (or none at all) is unhealthy.
	if err := healthcheck(config{listen: ":" + port, data: t.TempDir()}, time.Second); err == nil {
		t.Fatal("healthy with another certificate")
	}
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	_, free, _ := net.SplitHostPort(ln.Addr().String())
	ln.Close()
	if err := healthcheck(config{listen: ":" + free, data: r.dir}, time.Second); err == nil {
		t.Fatal("healthy with nothing listening")
	}
}

func TestNewAccessKeyTakesOver(t *testing.T) {
	r := startRelay(t, nil)
	old := r.key
	var next [accessKeySize]byte
	next[0] = 42
	r.s.setKey(next)
	c, err := r.dial(r.id.fingerprint)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.Write(preamble(roleBridge, old, rid(1)))
	if b := readStatus(t, c, time.Second); b != statusWrongKey {
		t.Fatalf("the old key still works: %#x", b)
	}
	r.key = next
	bridge := r.connect(roleBridge, rid(2))
	if b, err := readByte(t, bridge, time.Second); err != nil || b != statusKeepAlive {
		t.Fatalf("the new key doesn't work: %#x %v", b, err)
	}
}
