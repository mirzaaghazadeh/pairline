package main

import (
	"encoding/hex"
	"encoding/json"
	"net/netip"
	"os"
	"path/filepath"
	"testing"
)

// The shared vectors (protocol/test-vectors/v1-remote.json, "relay"): the relay's links and preambles must be exactly
// what the phones expect.
type relayVectors struct {
	Relay struct {
		Fingerprint    string `json:"fingerprint"`
		AccessKey      string `json:"access_key"`
		RendezvousID   string `json:"rendezvous_id"`
		PreambleBridge string `json:"preamble_bridge"`
		PreambleRemote string `json:"preamble_remote"`
		Links          []struct {
			Why  string `json:"why"`
			URI  string `json:"uri"`
			Host string `json:"host"`
			Port int    `json:"port"`
		} `json:"links"`
		Status map[string]int `json:"status"`
	} `json:"relay"`
}

func loadVectors(t *testing.T) relayVectors {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("..", "protocol", "test-vectors", "v1-remote.json"))
	if err != nil {
		t.Fatal(err)
	}
	var v relayVectors
	if err := json.Unmarshal(data, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestVectorsLinksAndPreambles(t *testing.T) {
	v := loadVectors(t).Relay
	var fp [32]byte
	var key [accessKeySize]byte
	var id rendezvousID
	copy(fp[:], unhex(t, v.Fingerprint))
	copy(key[:], unhex(t, v.AccessKey))
	copy(id[:], unhex(t, v.RendezvousID))

	// The first three vectors are links as a relay prints them.
	for _, l := range v.Links[:3] {
		if got := buildLink(l.Host, l.Port, fp, key); got != l.URI {
			t.Errorf("%s: built %s, want %s", l.Why, got, l.URI)
		}
	}
	if got := hex.EncodeToString(preamble(roleBridge, key, id)); got != v.PreambleBridge {
		t.Errorf("bridge preamble %s, want %s", got, v.PreambleBridge)
	}
	if got := hex.EncodeToString(preamble(roleRemote, key, id)); got != v.PreambleRemote {
		t.Errorf("iPhone preamble %s, want %s", got, v.PreambleRemote)
	}
	// And the server reads them back.
	s := &server{key: key}
	for _, pre := range []string{v.PreambleBridge, v.PreambleRemote} {
		var b [preambleSize]byte
		copy(b[:], unhex(t, pre))
		role, gotID, status := s.check(b)
		if status != 0 || gotID != id || (role != roleBridge && role != roleRemote) {
			t.Errorf("preamble %s: role %c, status %d", pre, role, status)
		}
	}
	want := map[string]int{"keepalive": statusKeepAlive, "joined": statusJoined, "no_bridge": statusNoBridge,
		"wrong_key": statusWrongKey, "bad_version": statusBadVersion, "full": statusFull}
	for name, b := range want {
		if v.Status[name] != b {
			t.Errorf("status %s: vectors say %d, relay sends %d", name, v.Status[name], b)
		}
	}
}

func TestPublicEndpoint(t *testing.T) {
	cases := []struct {
		in   string
		host string
		port int
		ok   bool
	}{
		{"203.0.113.5", "203.0.113.5", 443, true},
		{"203.0.113.5:8443", "203.0.113.5", 8443, true},
		{"Relay.Example.COM.", "relay.example.com", 443, true},
		{"relay.example.com:47840", "relay.example.com", 47840, true},
		{"2a01:4f8::1", "2a01:4f8::1", 443, true},
		{"[2a01:4f8::1]:8443", "2a01:4f8::1", 8443, true},
		{"[2a01:4f8::1]", "2a01:4f8::1", 443, true},
		{"relay", "", 0, false},
		{"relay.example.com:0", "", 0, false},
		{"relay.example.com:99999", "", 0, false},
		{"[2a01:4f8::1", "", 0, false},
		{"fe80::1%eth0", "", 0, false},
		{"bad_name.example.com", "", 0, false},
	}
	for _, c := range cases {
		host, port, err := publicEndpoint(c.in, 443)
		if (err == nil) != c.ok || host != c.host || port != c.port {
			t.Errorf("%q: got %q %d %v", c.in, host, port, err)
		}
	}
}

func TestPublicAddresses(t *testing.T) {
	public := []string{"5.6.7.8", "93.184.216.34", "2a01:4f8:c17:1234::1", "2600::1"}
	notPublic := []string{"10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.0.1", "127.0.0.1", "169.254.1.1",
		"203.0.113.5", "198.51.100.1", "192.0.2.1", "198.18.0.1", "224.0.0.1", "0.0.0.0", "::1", "fd00::1",
		"fe80::1", "2001:db8::1", "2002::1", "::ffff:10.0.0.1"}
	for _, s := range public {
		if !isPublic(netip.MustParseAddr(s)) {
			t.Errorf("%s should be public", s)
		}
	}
	for _, s := range notPublic {
		if isPublic(netip.MustParseAddr(s)) {
			t.Errorf("%s shouldn't be public", s)
		}
	}
}

func TestIdentityPersists(t *testing.T) {
	dir := t.TempDir()
	first, created, err := loadOrCreateIdentity(dir)
	if err != nil || !created {
		t.Fatal(created, err)
	}
	second, created, err := loadOrCreateIdentity(dir)
	if err != nil || created {
		t.Fatal(created, err)
	}
	if first.fingerprint != second.fingerprint {
		t.Fatal("the fingerprint changed between starts")
	}
	key1, made, err := loadOrCreateAccessKey(dir)
	if err != nil || !made {
		t.Fatal(made, err)
	}
	key2, made, err := loadOrCreateAccessKey(dir)
	if err != nil || made || key1 != key2 {
		t.Fatal("the access key changed between starts")
	}
	key3, err := newAccessKey(dir)
	if err != nil || key3 == key1 {
		t.Fatal("new-key kept the old key")
	}
	for _, name := range []string{keyFile, accessKeyFile} {
		info, err := os.Stat(filepath.Join(dir, name))
		if err != nil || info.Mode().Perm() != 0o600 {
			t.Errorf("%s: mode %v, %v", name, info.Mode().Perm(), err)
		}
	}
}

func TestHalfAnIdentityIsAnError(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := loadOrCreateIdentity(dir); err != nil {
		t.Fatal(err)
	}
	os.Remove(filepath.Join(dir, certFile))
	if _, _, err := loadOrCreateIdentity(dir); err == nil {
		t.Fatal("a key without its certificate was accepted (a new certificate would change the pin)")
	}
}
