package main

import (
	"errors"
	"strings"
)

// A small QR Code encoder (ISO/IEC 18004), enough for the relay's link: byte mode, error correction level M,
// versions 1–20 (up to 666 bytes), with the best of the eight masks. It exists so the relay can print its link as a
// code the iPhone's camera reads, without a third-party module (D-106). The structure follows Project Nayuki's public
// description of the algorithm.

type qrCode struct {
	size       int
	modules    [][]bool // [y][x], true = dark
	isFunction [][]bool
}

const qrMaxVersion = 20

// Level M: error-correction codewords per block, and the number of blocks, for versions 1–20.
var (
	qrECCPerBlock = [qrMaxVersion + 1]int{0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26}
	qrBlocks      = [qrMaxVersion + 1]int{0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16}
)

var errQRTooLong = errors.New("too long for a QR code")

func encodeQR(data []byte) (*qrCode, error) {
	version := 0
	for v := 1; v <= qrMaxVersion; v++ {
		if 4+qrCountBits(v)+8*len(data) <= 8*qrDataCodewords(v) {
			version = v
			break
		}
	}
	if version == 0 {
		return nil, errQRTooLong
	}
	capacity := qrDataCodewords(version) * 8

	var bits bitBuffer
	bits.append(0b0100, 4) // byte mode
	bits.append(len(data), qrCountBits(version))
	for _, b := range data {
		bits.append(int(b), 8)
	}
	bits.append(0, min(4, capacity-len(bits)))
	bits.append(0, (8-len(bits)%8)%8)
	for pad := 0xEC; len(bits) < capacity; pad ^= 0xEC ^ 0x11 {
		bits.append(pad, 8)
	}
	codewords := make([]byte, len(bits)/8)
	for i, bit := range bits {
		if bit {
			codewords[i>>3] |= 1 << (7 - i&7)
		}
	}

	q := newQR(version)
	q.drawFunctionPatterns(version)
	q.drawCodewords(q.addECCAndInterleave(version, codewords))

	best, bestPenalty := 0, -1
	for mask := 0; mask < 8; mask++ {
		q.applyMask(mask)
		q.drawFormatBits(mask)
		if p := q.penalty(); bestPenalty < 0 || p < bestPenalty {
			best, bestPenalty = mask, p
		}
		q.applyMask(mask) // XOR again undoes it
	}
	q.applyMask(best)
	q.drawFormatBits(best)
	return q, nil
}

func qrCountBits(version int) int {
	if version < 10 {
		return 8
	}
	return 16
}

func qrRawDataModules(version int) int {
	result := (16*version+128)*version + 64
	if version >= 2 {
		align := version/7 + 2
		result -= (25*align-10)*align - 55
		if version >= 7 {
			result -= 36
		}
	}
	return result
}

func qrDataCodewords(version int) int {
	return qrRawDataModules(version)/8 - qrECCPerBlock[version]*qrBlocks[version]
}

func newQR(version int) *qrCode {
	size := version*4 + 17
	q := &qrCode{size: size, modules: make([][]bool, size), isFunction: make([][]bool, size)}
	for i := range q.modules {
		q.modules[i] = make([]bool, size)
		q.isFunction[i] = make([]bool, size)
	}
	return q
}

func (q *qrCode) setFunction(x, y int, dark bool) {
	q.modules[y][x] = dark
	q.isFunction[y][x] = true
}

func (q *qrCode) drawFunctionPatterns(version int) {
	for i := 0; i < q.size; i++ {
		q.setFunction(6, i, i%2 == 0)
		q.setFunction(i, 6, i%2 == 0)
	}
	q.drawFinder(3, 3)
	q.drawFinder(q.size-4, 3)
	q.drawFinder(3, q.size-4)
	pos := qrAlignmentPositions(version, q.size)
	n := len(pos)
	for i := 0; i < n; i++ {
		for j := 0; j < n; j++ {
			if (i == 0 && j == 0) || (i == 0 && j == n-1) || (i == n-1 && j == 0) {
				continue // the finder patterns' corners
			}
			for dy := -2; dy <= 2; dy++ {
				for dx := -2; dx <= 2; dx++ {
					q.setFunction(pos[i]+dx, pos[j]+dy, max(abs(dx), abs(dy)) != 1)
				}
			}
		}
	}
	q.drawFormatBits(0) // reserves the area; drawn for real once the mask is chosen
	if version >= 7 {
		rem := version
		for i := 0; i < 12; i++ {
			rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
		}
		bits := version<<12 | rem
		for i := 0; i < 18; i++ {
			bit := (bits>>i)&1 != 0
			a, b := q.size-11+i%3, i/3
			q.setFunction(a, b, bit)
			q.setFunction(b, a, bit)
		}
	}
}

func (q *qrCode) drawFinder(x, y int) {
	for dy := -4; dy <= 4; dy++ {
		for dx := -4; dx <= 4; dx++ {
			xx, yy := x+dx, y+dy
			if xx >= 0 && xx < q.size && yy >= 0 && yy < q.size {
				dist := max(abs(dx), abs(dy))
				q.setFunction(xx, yy, dist != 2 && dist != 4)
			}
		}
	}
}

func qrAlignmentPositions(version, size int) []int {
	if version == 1 {
		return nil
	}
	n := version/7 + 2
	step := (version*8 + n*3 + 5) / (n*4 - 4) * 2
	result := make([]int, n)
	result[0] = 6
	for i, pos := n-1, size-7; i >= 1; i, pos = i-1, pos-step {
		result[i] = pos
	}
	return result
}

// drawFormatBits writes level M (format bits 00) and the mask, BCH-protected, in both copies, plus the dark module.
func (q *qrCode) drawFormatBits(mask int) {
	data := 0<<3 | mask
	rem := data
	for i := 0; i < 10; i++ {
		rem = (rem << 1) ^ ((rem >> 9) * 0x537)
	}
	bits := (data<<10 | rem) ^ 0x5412
	bit := func(i int) bool { return (bits>>i)&1 != 0 }
	for i := 0; i <= 5; i++ {
		q.setFunction(8, i, bit(i))
	}
	q.setFunction(8, 7, bit(6))
	q.setFunction(8, 8, bit(7))
	q.setFunction(7, 8, bit(8))
	for i := 9; i < 15; i++ {
		q.setFunction(14-i, 8, bit(i))
	}
	for i := 0; i < 8; i++ {
		q.setFunction(q.size-1-i, 8, bit(i))
	}
	for i := 8; i < 15; i++ {
		q.setFunction(8, q.size-15+i, bit(i))
	}
	q.setFunction(8, q.size-8, true)
}

func (q *qrCode) addECCAndInterleave(version int, data []byte) []byte {
	numBlocks, eccLen := qrBlocks[version], qrECCPerBlock[version]
	raw := qrRawDataModules(version) / 8
	numShort := numBlocks - raw%numBlocks
	shortLen := raw / numBlocks
	divisor := rsDivisor(eccLen)
	blocks := make([][]byte, 0, numBlocks)
	k := 0
	for i := 0; i < numBlocks; i++ {
		dataLen := shortLen - eccLen
		if i >= numShort {
			dataLen++
		}
		block := append([]byte(nil), data[k:k+dataLen]...)
		k += dataLen
		ecc := rsRemainder(block, divisor)
		if i < numShort {
			block = append(block, 0) // a placeholder so every block has the same length; skipped below
		}
		blocks = append(blocks, append(block, ecc...))
	}
	result := make([]byte, 0, raw)
	for i := range blocks[0] {
		for j, block := range blocks {
			if i != shortLen-eccLen || j >= numShort {
				result = append(result, block[i])
			}
		}
	}
	return result
}

// drawCodewords places the bits in the zigzag order, two columns at a time from the bottom right, skipping the
// vertical timing pattern and every function module.
func (q *qrCode) drawCodewords(data []byte) {
	i := 0
	for right := q.size - 1; right >= 1; right -= 2 {
		if right == 6 {
			right = 5
		}
		for vert := 0; vert < q.size; vert++ {
			for j := 0; j < 2; j++ {
				x := right - j
				y := vert
				if (right+1)&2 == 0 {
					y = q.size - 1 - vert
				}
				if !q.isFunction[y][x] && i < len(data)*8 {
					q.modules[y][x] = (data[i>>3]>>(7-i&7))&1 != 0
					i++
				}
			}
		}
	}
}

func (q *qrCode) applyMask(mask int) {
	for y := 0; y < q.size; y++ {
		for x := 0; x < q.size; x++ {
			if q.isFunction[y][x] {
				continue
			}
			var invert bool
			switch mask {
			case 0:
				invert = (x+y)%2 == 0
			case 1:
				invert = y%2 == 0
			case 2:
				invert = x%3 == 0
			case 3:
				invert = (x+y)%3 == 0
			case 4:
				invert = (x/3+y/2)%2 == 0
			case 5:
				invert = x*y%2+x*y%3 == 0
			case 6:
				invert = (x*y%2+x*y%3)%2 == 0
			case 7:
				invert = ((x+y)%2+x*y%3)%2 == 0
			}
			if invert {
				q.modules[y][x] = !q.modules[y][x]
			}
		}
	}
}

// penalty scores a masked code by the standard's four rules; the lowest wins. Any mask gives a valid code, so this
// only picks the easiest one to scan.
func (q *qrCode) penalty() int {
	n := q.size
	at := func(x, y int, column bool) bool {
		if column {
			return q.modules[x][y]
		}
		return q.modules[y][x]
	}
	result := 0
	for _, column := range []bool{false, true} {
		for y := 0; y < n; y++ {
			run := 1
			for x := 1; x <= n; x++ {
				if x < n && at(x, y, column) == at(x-1, y, column) {
					run++
					continue
				}
				if run >= 5 {
					result += 3 + run - 5
				}
				run = 1
			}
			// 1:1:3:1:1 finder-like runs with four light modules on either side.
			for x := 0; x+11 <= n; x++ {
				a := [11]bool{}
				for i := range a {
					a[i] = at(x+i, y, column)
				}
				core := a[0] && !a[1] && a[2] && a[3] && a[4] && !a[5] && a[6]
				if core && !a[7] && !a[8] && !a[9] && !a[10] {
					result += 40
				}
				core = a[4] && !a[5] && a[6] && a[7] && a[8] && !a[9] && a[10]
				if core && !a[0] && !a[1] && !a[2] && !a[3] {
					result += 40
				}
			}
		}
	}
	for y := 0; y+1 < n; y++ {
		for x := 0; x+1 < n; x++ {
			c := q.modules[y][x]
			if c == q.modules[y][x+1] && c == q.modules[y+1][x] && c == q.modules[y+1][x+1] {
				result += 3
			}
		}
	}
	dark := 0
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			if q.modules[y][x] {
				dark++
			}
		}
	}
	total := n * n
	k := (abs(dark*20-total*10)+total-1)/total - 1
	return result + k*10
}

func rsDivisor(degree int) []byte {
	result := make([]byte, degree)
	result[degree-1] = 1
	root := byte(1)
	for i := 0; i < degree; i++ {
		for j := range result {
			result[j] = rsMultiply(result[j], root)
			if j+1 < len(result) {
				result[j] ^= result[j+1]
			}
		}
		root = rsMultiply(root, 0x02)
	}
	return result
}

func rsRemainder(data, divisor []byte) []byte {
	result := make([]byte, len(divisor))
	for _, b := range data {
		factor := b ^ result[0]
		copy(result, result[1:])
		result[len(result)-1] = 0
		for i := range result {
			result[i] ^= rsMultiply(divisor[i], factor)
		}
	}
	return result
}

func rsMultiply(x, y byte) byte {
	z := 0
	for i := 7; i >= 0; i-- {
		z = (z << 1) ^ ((z >> 7) * 0x11D)
		z ^= int((y>>i)&1) * int(x)
	}
	return byte(z)
}

type bitBuffer []bool

func (b *bitBuffer) append(value, count int) {
	for i := count - 1; i >= 0; i-- {
		*b = append(*b, (value>>i)&1 != 0)
	}
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// How the code is drawn as text (PAIRLINE_RELAY_QR).
type qrStyle string

const (
	// Black on white whatever the terminal's colours, with ANSI colour codes: a terminal, `docker logs`.
	qrANSI qrStyle = "ansi"
	// Plain text for a log viewer that shows ANSI codes as text: blocks mark the light modules, for a dark background.
	qrDark qrStyle = "dark"
	// The same for a light background: blocks mark the dark modules.
	qrLight qrStyle = "light"
	qrOff   qrStyle = "off"
)

// terminal draws the code with half blocks, two rows of modules per line, with a four-module quiet zone. Phones read
// it from a laptop screen showing an SSH session, `docker logs` or a web log viewer.
func (q *qrCode) terminal(style qrStyle) string {
	const quiet = 4
	dark := func(x, y int) bool {
		return x >= 0 && y >= 0 && x < q.size && y < q.size && q.modules[y][x]
	}
	// The character cells drawn as blocks: dark modules, except on a dark background, where the light ones are.
	filled := dark
	if style == qrDark {
		filled = func(x, y int) bool { return !dark(x, y) }
	}
	var sb strings.Builder
	for y := -quiet; y < q.size+quiet; y += 2 {
		if style == qrANSI {
			sb.WriteString("\x1b[30;47m")
		}
		for x := -quiet; x < q.size+quiet; x++ {
			top, bottom := filled(x, y), filled(x, y+1)
			if y+1 >= q.size+quiet {
				bottom = false // past the quiet zone: nothing drawn, whatever the style
			}
			switch {
			case top && bottom:
				sb.WriteString("█")
			case top:
				sb.WriteString("▀")
			case bottom:
				sb.WriteString("▄")
			default:
				sb.WriteString(" ")
			}
		}
		if style == qrANSI {
			sb.WriteString("\x1b[0m")
		}
		sb.WriteString("\n")
	}
	return sb.String()
}
