package main

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"errors"
	"io"
	"log"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"
)

// The relay (PROTOCOL.md, "Your own server (relay, D-106)"). Both phones connect out to it over TLS and send a
// 54-byte preamble; the bridge then waits under its rendezvous id, and the iPhone is joined to it. From then on the
// relay copies bytes both ways and never looks at them: what passes is the phones' own session, encrypted end to end
// with keys only they hold.

const (
	preambleSize = 54
	roleBridge   = 'B'
	roleRemote   = 'R'

	statusKeepAlive  = 0x00
	statusJoined     = 0x01
	statusNoBridge   = 0x02
	statusWrongKey   = 0x03
	statusBadVersion = 0x04
	statusFull       = 0x05
)

var magic = [4]byte{'P', 'L', 'R', 'Y'}

// Connections still in the TLS handshake or the preamble, on top of maxConnections.
const handshakeRoom = 256

type rendezvousID [16]byte

// Timing and limits. Tests shorten the timings.
type limits struct {
	// The TLS handshake and the preamble must arrive within this.
	preamble time.Duration
	// A waiting bridge hears a keep-alive this often.
	keepAlive time.Duration
	// An iPhone that finds no bridge is held this long for one to arrive (it may be reconnecting).
	hold time.Duration
	// A joined connection is closed after this long without a byte in a direction.
	idle time.Duration
	// A write that takes longer than this means the peer is gone.
	write time.Duration
	// Connections past the preamble at once; beyond it the relay answers "full".
	maxConnections int
	// New connections per minute from one address (an IPv6 /64 counts as one).
	perMinute int
	// Wrong access keys from one address within failWindow before it's turned away for failWindow.
	maxFailures int
	failWindow  time.Duration
	// The two limits above apply; off behind a proxy, where every connection comes from the proxy's address.
	perAddress bool
}

var defaultLimits = limits{
	preamble:       10 * time.Second,
	keepAlive:      30 * time.Second,
	hold:           10 * time.Second,
	idle:           5 * time.Minute,
	write:          30 * time.Second,
	maxConnections: 1000,
	perMinute:      60,
	maxFailures:    10,
	failWindow:     10 * time.Minute,
	perAddress:     true,
}

type server struct {
	tlsConfig *tls.Config
	limits    limits
	logf      func(format string, args ...any)

	keyMu sync.RWMutex
	key   [accessKeySize]byte

	mu       sync.Mutex
	waiting  map[rendezvousID]*waiter
	arrivals chan struct{} // closed and replaced whenever a bridge starts waiting
	closed   bool
	conns    map[net.Conn]struct{}

	gate *admissionGate

	active   atomic.Int64 // connections past the preamble
	sessions atomic.Int64 // joined pairs
	stats    counters
}

type counters struct {
	joined, noBridge, wrongKey, badPreamble, full, refused atomic.Int64
}

func newServer(id *identity, key [accessKeySize]byte, l limits, logf func(string, ...any)) *server {
	if logf == nil {
		logf = log.Printf
	}
	return &server{
		tlsConfig: &tls.Config{
			Certificates: []tls.Certificate{id.cert},
			MinVersion:   tls.VersionTLS12,
		},
		limits:   l,
		logf:     logf,
		key:      key,
		waiting:  make(map[rendezvousID]*waiter),
		arrivals: make(chan struct{}),
		conns:    make(map[net.Conn]struct{}),
		gate:     newAdmissionGate(l.perMinute, l.maxFailures, l.failWindow),
	}
}

func (s *server) setKey(key [accessKeySize]byte) {
	s.keyMu.Lock()
	s.key = key
	s.keyMu.Unlock()
}

func (s *server) keyMatches(candidate []byte) bool {
	s.keyMu.RLock()
	defer s.keyMu.RUnlock()
	return subtle.ConstantTimeCompare(candidate, s.key[:]) == 1
}

// serve accepts connections until ctx ends or the listener fails.
func (s *server) serve(ctx context.Context, ln net.Listener) error {
	go func() {
		<-ctx.Done()
		ln.Close()
		s.shutdown()
	}()
	var delay time.Duration
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				// Out of file descriptors or similar: back off a little, as net/http does.
				delay = min(max(2*delay, 5*time.Millisecond), time.Second)
				time.Sleep(delay)
				continue
			}
			return err
		}
		delay = 0
		go s.handle(conn)
	}
}

// track counts every open connection; past maxConnections plus room for handshakes in flight, new ones are closed
// before any work is done for them.
func (s *server) track(conn net.Conn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed || len(s.conns) >= s.limits.maxConnections+handshakeRoom {
		return false
	}
	s.conns[conn] = struct{}{}
	return true
}

func (s *server) untrack(conn net.Conn) {
	s.mu.Lock()
	delete(s.conns, conn)
	s.mu.Unlock()
}

func (s *server) shutdown() {
	s.mu.Lock()
	s.closed = true
	conns := make([]net.Conn, 0, len(s.conns))
	for c := range s.conns {
		conns = append(conns, c)
	}
	s.mu.Unlock()
	for _, c := range conns {
		c.Close()
	}
}

func (s *server) handle(raw net.Conn) {
	addr := remoteAddr(raw)
	if !s.limits.perAddress {
		addr = netip.Addr{} // the proxy's address says nothing about who's behind it; the gate lets it through
	}
	if !s.gate.admit(addr, time.Now()) || !s.track(raw) {
		s.stats.refused.Add(1)
		raw.Close()
		return
	}
	defer s.untrack(raw)
	if tcp, ok := raw.(*net.TCPConn); ok {
		tcp.SetNoDelay(true)
		tcp.SetKeepAliveConfig(net.KeepAliveConfig{Enable: true, Idle: 30 * time.Second, Interval: 10 * time.Second, Count: 3})
	}
	conn := tls.Server(raw, s.tlsConfig)
	p := &peer{conn: conn, write: s.limits.write}
	defer p.close()

	conn.SetDeadline(time.Now().Add(s.limits.preamble))
	if err := conn.Handshake(); err != nil {
		return
	}
	var pre [preambleSize]byte
	if _, err := io.ReadFull(conn, pre[:]); err != nil {
		return
	}
	conn.SetDeadline(time.Time{})

	role, id, status := s.check(pre)
	if status != 0 {
		if status == statusWrongKey {
			s.stats.wrongKey.Add(1)
			s.gate.failed(addr, time.Now())
		} else {
			s.stats.badPreamble.Add(1)
		}
		p.send(status)
		return
	}
	if s.active.Add(1) > int64(s.limits.maxConnections) {
		s.active.Add(-1)
		s.stats.full.Add(1)
		p.send(statusFull)
		return
	}
	defer s.active.Add(-1)

	if role == roleBridge {
		s.wait(id, p)
	} else {
		s.join(id, p)
	}
}

// check reads the preamble: the role and rendezvous id, or the status byte to answer and close with.
func (s *server) check(pre [preambleSize]byte) (byte, rendezvousID, byte) {
	var id rendezvousID
	if [4]byte(pre[0:4]) != magic || pre[4] != 0x01 || (pre[5] != roleBridge && pre[5] != roleRemote) {
		return 0, id, statusBadVersion
	}
	if !s.keyMatches(pre[6:38]) {
		return 0, id, statusWrongKey
	}
	copy(id[:], pre[38:54])
	return pre[5], id, 0
}

// A connection after its preamble. Writes are serialized; close is idempotent and closes the TLS connection.
type peer struct {
	conn  *tls.Conn
	write time.Duration

	wmu    sync.Mutex
	once   sync.Once
	closed atomic.Bool
}

func (p *peer) send(b ...byte) error {
	return p.writeAll(b)
}

func (p *peer) writeAll(b []byte) error {
	p.wmu.Lock()
	defer p.wmu.Unlock()
	p.conn.SetWriteDeadline(time.Now().Add(p.write))
	_, err := p.conn.Write(b)
	return err
}

func (p *peer) close() {
	p.once.Do(func() {
		p.closed.Store(true)
		p.conn.Close()
	})
}

// A bridge waiting under its rendezvous id. partner is set once an iPhone is joined to it.
type waiter struct {
	p       *peer
	mu      sync.Mutex
	partner *peer
	gone    bool
	joined  chan struct{}
}

// wait keeps a bridge waiting: keep-alives until an iPhone is joined, then the bridge→iPhone half of the copy.
// A newer bridge with the same id replaces this one.
func (s *server) wait(id rendezvousID, p *peer) {
	w := &waiter{p: p, joined: make(chan struct{})}
	s.mu.Lock()
	old := s.waiting[id]
	s.waiting[id] = w
	close(s.arrivals)
	s.arrivals = make(chan struct{})
	s.mu.Unlock()
	if old != nil {
		old.leave()
	}

	// One keep-alive at once: the bridge learns its key was taken and it's waiting, without the first 30 s of doubt.
	w.mu.Lock()
	first := p.send(statusKeepAlive)
	w.mu.Unlock()
	if first != nil {
		p.close()
	}

	stop := make(chan struct{})
	defer close(stop)
	go func() {
		t := time.NewTicker(s.limits.keepAlive)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-w.joined:
				return
			case <-t.C:
				// Checked and sent under w.mu, which join holds to set the partner: a keep-alive is either written
				// before the join, and so before 0x01, or not at all. One after 0x01 would be the session's first byte.
				w.mu.Lock()
				joined := w.partner != nil
				var err error
				if !joined {
					err = p.send(statusKeepAlive)
				}
				w.mu.Unlock()
				if joined {
					return
				}
				if err != nil {
					p.close()
					return
				}
			}
		}
	}()

	// The bridge sends nothing until it's joined, so this read returns only when it closes, misbehaves, or speaks
	// after an iPhone was joined; from then on it copies the bridge's bytes to the iPhone.
	buf := make([]byte, 32*1024)
	for {
		n, err := p.conn.Read(buf)
		if n > 0 {
			w.mu.Lock()
			partner := w.partner
			w.mu.Unlock()
			if partner == nil {
				break // bytes before the join: not a Pairline bridge
			}
			if partner.writeAll(buf[:n]) != nil {
				break
			}
			p.conn.SetReadDeadline(time.Now().Add(s.limits.idle))
		}
		if err != nil {
			break
		}
	}
	w.leave()
	s.mu.Lock()
	if s.waiting[id] == w {
		delete(s.waiting, id)
	}
	s.mu.Unlock()
}

// leave marks the bridge gone and closes it, and its iPhone if one was joined.
func (w *waiter) leave() {
	w.mu.Lock()
	w.gone = true
	partner := w.partner
	w.mu.Unlock()
	w.p.close()
	if partner != nil {
		partner.close()
	}
}

// join finds the bridge waiting under id (holding the iPhone up to limits.hold for one), joins them, and copies the
// iPhone's bytes to the bridge.
func (s *server) join(id rendezvousID, r *peer) {
	deadline := time.Now().Add(s.limits.hold)
	var w *waiter
	for w == nil {
		s.mu.Lock()
		candidate := s.waiting[id]
		if candidate != nil {
			delete(s.waiting, id)
		}
		arrivals := s.arrivals
		s.mu.Unlock()
		if candidate != nil {
			candidate.mu.Lock()
			if !candidate.gone {
				candidate.partner = r
				w = candidate
			}
			candidate.mu.Unlock()
			if w != nil {
				break
			}
			continue
		}
		left := time.Until(deadline)
		if left <= 0 {
			s.stats.noBridge.Add(1)
			r.send(statusNoBridge)
			return
		}
		timer := time.NewTimer(left)
		select {
		case <-arrivals:
		case <-timer.C:
		}
		timer.Stop()
	}
	close(w.joined)

	// The iPhone hears first, then the bridge: the bridge speaks only after the iPhone's hello, which the iPhone sends
	// only after its own 0x01.
	if r.send(statusJoined) != nil || w.p.send(statusJoined) != nil {
		w.leave()
		return
	}
	s.stats.joined.Add(1)
	s.sessions.Add(1)
	defer s.sessions.Add(-1)
	w.p.conn.SetReadDeadline(time.Now().Add(s.limits.idle))

	buf := make([]byte, 32*1024)
	for {
		r.conn.SetReadDeadline(time.Now().Add(s.limits.idle))
		n, err := r.conn.Read(buf)
		if n > 0 && w.p.writeAll(buf[:n]) != nil {
			break
		}
		if err != nil {
			break
		}
	}
	w.leave()
}

// waitingCount and sessionCount are for the status line in the log.
func (s *server) waitingCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.waiting)
}

func remoteAddr(c net.Conn) netip.Addr {
	if ap, err := netip.ParseAddrPort(c.RemoteAddr().String()); err == nil {
		return ap.Addr().Unmap()
	}
	return netip.Addr{}
}

// admissionGate limits new connections per address per minute, and turns away an address after too many wrong
// access keys. It forgets addresses it hasn't seen for a while; it never logs them.
type admissionGate struct {
	mu          sync.Mutex
	perMinute   int
	maxFailures int
	window      time.Duration
	seen        map[netip.Prefix]*gateEntry
	lastSweep   time.Time
}

type gateEntry struct {
	times    []time.Time
	failures []time.Time
	blocked  time.Time
}

func newAdmissionGate(perMinute, maxFailures int, window time.Duration) *admissionGate {
	return &admissionGate{perMinute: perMinute, maxFailures: maxFailures, window: window,
		seen: make(map[netip.Prefix]*gateEntry)}
}

func gateKey(a netip.Addr) netip.Prefix {
	bits := 32
	if a.Is6() {
		bits = 64
	}
	p, _ := a.Prefix(bits)
	return p
}

func (g *admissionGate) admit(a netip.Addr, now time.Time) bool {
	if !a.IsValid() {
		return true
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	g.sweep(now)
	e := g.seen[gateKey(a)]
	if e == nil {
		e = &gateEntry{}
		g.seen[gateKey(a)] = e
	}
	if now.Before(e.blocked) {
		return false
	}
	e.times = trim(e.times, now.Add(-time.Minute))
	if len(e.times) >= g.perMinute {
		return false
	}
	e.times = append(e.times, now)
	return true
}

func (g *admissionGate) failed(a netip.Addr, now time.Time) {
	if !a.IsValid() {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	e := g.seen[gateKey(a)]
	if e == nil {
		e = &gateEntry{}
		g.seen[gateKey(a)] = e
	}
	e.failures = append(trim(e.failures, now.Add(-g.window)), now)
	if len(e.failures) >= g.maxFailures {
		e.blocked = now.Add(g.window)
		e.failures = nil
	}
}

func (g *admissionGate) sweep(now time.Time) {
	if now.Sub(g.lastSweep) < time.Minute {
		return
	}
	g.lastSweep = now
	for k, e := range g.seen {
		if now.After(e.blocked) && len(trim(e.times, now.Add(-time.Minute))) == 0 &&
			len(trim(e.failures, now.Add(-g.window))) == 0 {
			delete(g.seen, k)
		}
	}
}

func trim(times []time.Time, after time.Time) []time.Time {
	i := 0
	for i < len(times) && !times[i].After(after) {
		i++
	}
	return times[i:]
}
