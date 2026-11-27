package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// qrSamples cover versions 1 to 20: short text, the relay's usual links, and long ones.
func qrSamples() []string {
	link := "pairline-relay://relay.example.net:443?v=1&fp=o5WSS0wsnGrbK548cKgxvxmDIGzdA2zMMsLHPsKvEfs&k=40hogcOdV9J0lYRjVnIzlEF0AuhfD4W-2HmTlZ9Y1Kg"
	return []string{
		"pairline",
		"Hello, world! 123",
		link,
		"pairline-relay://[2a01:4f8:c17:1234::1]:443?v=1&fp=o5WSS0wsnGrbK548cKgxvxmDIGzdA2zMMsLHPsKvEfs&k=40hogcOdV9J0lYRjVnIzlEF0AuhfD4W-2HmTlZ9Y1Kg",
		strings.Repeat("a", 200),
		strings.Repeat("0123456789", 30),
		strings.Repeat("Pairline relay. ", 41), // 656 bytes: version 20
	}
}

func matrixHash(q *qrCode) string {
	h := sha256.New()
	for _, row := range q.modules {
		for _, dark := range row {
			if dark {
				h.Write([]byte{1})
			} else {
				h.Write([]byte{0})
			}
		}
	}
	return hex.EncodeToString(h.Sum(nil))[:16]
}

// TestQRDecodes writes PNGs for an outside decoder when QR_PNG_DIR is set (verified with Core Image's QR detector on
// a Mac: relay/tools/qr-check.swift), and checks the matrices against the hashes recorded after that check.
func TestQRDecodes(t *testing.T) {
	dir := os.Getenv("QR_PNG_DIR")
	for i, text := range qrSamples() {
		q, err := encodeQR([]byte(text))
		if err != nil {
			t.Fatalf("sample %d: %v", i, err)
		}
		version := (q.size - 17) / 4
		t.Logf("sample %d: %d bytes, version %d, matrix %s", i, len(text), version, matrixHash(q))
		if dir != "" {
			writePNG(t, filepath.Join(dir, fmt.Sprintf("qr-%d.png", i)), q)
			os.WriteFile(filepath.Join(dir, fmt.Sprintf("qr-%d.txt", i)), []byte(text), 0o644)
		}
		if want, ok := qrGolden[i]; ok && matrixHash(q) != want {
			t.Errorf("sample %d: matrix %s, want %s (decoded correctly when recorded)", i, matrixHash(q), want)
		}
	}
}

// Recorded after Core Image decoded every sample to its exact text (2026-11-27: versions 1, 2, 8, 8, 10, 13, 20).
var qrGolden = map[int]string{
	0: "04f29cd95a55dab9",
	1: "3ebbf96ef1072bcf",
	2: "40f7500602440c74",
	3: "6f8a933c5a46f396",
	4: "aa30623851b44a18",
	5: "6a00bb8d069a8972",
	6: "9e009dd6aaa4a3e1",
}

func TestQRTextStyles(t *testing.T) {
	q, _ := encodeQR([]byte("pairline"))
	ansi, dark, light := q.terminal(qrANSI), q.terminal(qrDark), q.terminal(qrLight)
	if !strings.Contains(ansi, "\x1b[30;47m") {
		t.Error("ansi style without colour codes")
	}
	if strings.Contains(dark, "\x1b") || strings.Contains(light, "\x1b") {
		t.Error("a plain style has escape codes")
	}
	// Same layout, opposite blocks: the top-left corner is quiet zone, a block only on a dark background.
	if !strings.HasPrefix(dark, "█") || strings.HasPrefix(light, "█") {
		t.Errorf("quiet zone: dark %q, light %q", dark[:3], light[:3])
	}
	if strings.Count(dark, "\n") != strings.Count(light, "\n") {
		t.Error("the styles differ in height")
	}
}

func TestQRTooLong(t *testing.T) {
	if _, err := encodeQR([]byte(strings.Repeat("x", 700))); err == nil {
		t.Fatal("700 bytes fit in version 20-M?")
	}
}

func TestQRVersionsAndStructure(t *testing.T) {
	for v := 1; v <= qrMaxVersion; v++ {
		// Every version's codewords add up (the block table matches the module count).
		raw := qrRawDataModules(v) / 8
		if d := qrDataCodewords(v); d <= 0 || d+qrECCPerBlock[v]*qrBlocks[v] != raw {
			t.Errorf("version %d: %d data codewords of %d", v, d, raw)
		}
	}
	q, _ := encodeQR([]byte("pairline"))
	// Version 1: 21 modules, finder corners dark, the dark module at (8, size-8).
	if q.size != 21 || !q.modules[0][0] || !q.modules[0][20] || !q.modules[20][0] || !q.modules[13][8] {
		t.Fatal("version 1 structure")
	}
}

func writePNG(t *testing.T, path string, q *qrCode) {
	const scale, quiet = 8, 4
	n := (q.size + 2*quiet) * scale
	img := image.NewGray(image.Rect(0, 0, n, n))
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			mx, my := x/scale-quiet, y/scale-quiet
			c := color.Gray{Y: 255}
			if mx >= 0 && my >= 0 && mx < q.size && my < q.size && q.modules[my][mx] {
				c = color.Gray{Y: 0}
			}
			img.SetGray(x, y, c)
		}
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		t.Fatal(err)
	}
}
