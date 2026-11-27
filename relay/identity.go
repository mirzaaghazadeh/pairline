package main

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// The relay's identity (PROTOCOL.md, "Your own server (relay, D-106)"): a P-256 key with a self-signed certificate,
// whose SHA-256 the phones pin, and a 32-byte access key that keeps strangers from using the relay. All three live in
// the data directory and are made on the first start.
const (
	keyFile       = "server.key"
	certFile      = "server.crt"
	accessKeyFile = "access.key"
	accessKeySize = 32
)

var b64 = base64.RawURLEncoding

type identity struct {
	cert        tls.Certificate
	fingerprint [32]byte
}

// loadOrCreateIdentity reads the key and certificate from dir, making them (and dir) first if they don't exist.
// It returns whether they were just made.
func loadOrCreateIdentity(dir string) (*identity, bool, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, false, fmt.Errorf("can't create the data directory: %w", err)
	}
	keyPath, certPath := filepath.Join(dir, keyFile), filepath.Join(dir, certFile)
	_, keyErr := os.Stat(keyPath)
	_, certErr := os.Stat(certPath)
	created := false
	switch {
	case errors.Is(keyErr, os.ErrNotExist) && errors.Is(certErr, os.ErrNotExist):
		if err := createIdentity(keyPath, certPath); err != nil {
			return nil, false, err
		}
		created = true
	case keyErr != nil || certErr != nil:
		return nil, false, fmt.Errorf("%s and %s must both exist or both be missing in %s", keyFile, certFile, dir)
	}
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, false, fmt.Errorf("can't read the server's key and certificate: %w", err)
	}
	return &identity{cert: cert, fingerprint: sha256.Sum256(cert.Certificate[0])}, created, nil
}

func createIdentity(keyPath, certPath string) error {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	if err != nil {
		return err
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "Pairline relay"},
		// The phones check only the pinned fingerprint, never the dates, so it never needs renewing.
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.AddDate(100, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return err
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return err
	}
	if err := writeFileAtomic(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8}), 0o600); err != nil {
		return err
	}
	return writeFileAtomic(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644)
}

// loadOrCreateAccessKey reads the access key from dir, making a new random one if there's none.
func loadOrCreateAccessKey(dir string) ([accessKeySize]byte, bool, error) {
	key, err := readAccessKey(dir)
	if err == nil {
		return key, false, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return key, false, err
	}
	key, err = newAccessKey(dir)
	return key, err == nil, err
}

// newAccessKey replaces the access key with a new random one: every link given out before stops working.
func newAccessKey(dir string) ([accessKeySize]byte, error) {
	var key [accessKeySize]byte
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return key, err
	}
	if _, err := rand.Read(key[:]); err != nil {
		return key, err
	}
	return key, writeFileAtomic(filepath.Join(dir, accessKeyFile), []byte(b64.EncodeToString(key[:])+"\n"), 0o600)
}

func readAccessKey(dir string) ([accessKeySize]byte, error) {
	var key [accessKeySize]byte
	data, err := os.ReadFile(filepath.Join(dir, accessKeyFile))
	if err != nil {
		return key, err
	}
	raw, err := b64.DecodeString(strings.TrimSpace(string(data)))
	if err != nil || len(raw) != accessKeySize {
		return key, fmt.Errorf("%s isn't a 32-byte base64url key", accessKeyFile)
	}
	copy(key[:], raw)
	return key, nil
}

// writeFileAtomic writes through a temporary file and a rename, so a crash never leaves half a key behind.
func writeFileAtomic(path string, data []byte, mode os.FileMode) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// fileStamp tells whether a file changed: its size and modification time, or nil when it's missing.
func fileStamp(path string) []byte {
	info, err := os.Stat(path)
	if err != nil {
		return nil
	}
	return []byte(fmt.Sprintf("%d/%d", info.Size(), info.ModTime().UnixNano()))
}

func sameStamp(a, b []byte) bool { return bytes.Equal(a, b) }
