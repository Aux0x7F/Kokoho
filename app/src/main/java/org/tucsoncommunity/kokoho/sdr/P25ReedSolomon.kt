package org.tucsoncommunity.kokoho.sdr

/**
 * Reed-Solomon codes over GF(64), as P25 Phase 1 uses them (TIA-102.BAAA): RS(24,12,13) on link control in LDU1
 * and RS(24,16,9) on the encryption sync in LDU2. 24 6-bit words per block, [parity] = 2t of them parity (12 or 8),
 * correcting up to t = 6 or 4 bad words; generator roots alpha^1..alpha^(2t) in GF(64) from x^6 + x + 1.
 * Berlekamp-Massey, Chien search and Forney's formula (OP25 / ezpwd). Pure: unit-tested.
 */
object P25ReedSolomon {

    /** Primitive polynomial x^6 + x + 1 (TIA-102.BAAA). */
    private const val POLY = 0x43

    /** alpha^i for i in 0..126, and log_alpha(x) for x in 1..63. */
    private val EXP = IntArray(128).also { e ->
        var x = 1
        for (i in 0 until 63) { e[i] = x; e[i + 63] = x; x = x shl 1; if (x and 0x40 != 0) x = x xor POLY }
    }
    private val LOG = IntArray(64).also { l -> for (i in 0 until 63) l[EXP[i]] = i }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
    private fun div(a: Int, b: Int) = if (a == 0) 0 else EXP[LOG[a] - LOG[b] + 63]
    private fun pow(i: Int) = EXP[(i % 63 + 63) % 63]

    /**
     * Corrects the 24 6-bit [words] (first 24-[parity] data, last [parity] parity; c_23..c_0) in place; the number of
     * words fixed, or null when more than [parity]/2 words are wrong.
     */
    fun decode(words: IntArray, parity: Int): Int? {
        // Syndromes S_j = R(alpha^(j+1)), j = 0..2t-1, with words[0] the x^23 coefficient.
        val syn = IntArray(parity) { j ->
            val a = EXP[j + 1]; var s = 0
            for (w in words) s = mul(s, a) xor w
            s
        }
        if (syn.all { it == 0 }) return 0

        // Berlekamp-Massey for the error locator sigma(x) = 1 + sigma_1 x + ... + sigma_L x^L.
        var sigma = IntArray(parity + 1).also { it[0] = 1 }
        var b = IntArray(parity + 1).also { it[0] = 1 }
        var l = 0; var m = 1; var bb = 1
        for (n in 0 until parity) {
            var d = syn[n]
            for (i in 1..l) d = d xor mul(sigma[i], syn[n - i])
            if (d == 0) { m++ } else {
                val t = sigma.clone()
                val scale = div(d, bb)
                for (i in 0..parity - m) if (b[i] != 0) sigma[i + m] = sigma[i + m] xor mul(scale, b[i])
                if (2 * l <= n) { l = n + 1 - l; b = t; bb = d; m = 1 } else m++
            }
        }
        if (l > parity / 2) return null

        // Chien search: alpha^(-pos) is a root of sigma when pos (0 = words[23], 23 = words[0]) has an error.
        val pos = IntArray(l); var found = 0
        for (p in 0 until 24) {
            val xInv = pow(-p); var sum = 1; var xPow = 1
            for (i in 1..l) { xPow = mul(xPow, xInv); sum = sum xor mul(sigma[i], xPow) }
            if (sum == 0) { if (found == l) return null; pos[found++] = p }
        }
        if (found != l) return null

        // Error evaluator omega(x) = S(x) sigma(x) mod x^(2t), and sigma'(x) (odd terms in characteristic 2).
        val omega = IntArray(parity) { i ->
            var s = 0; for (j in 0..minOf(i, l)) s = s xor mul(syn[i - j], sigma[j]); s
        }
        for (p in pos) {
            val xInv = pow(-p)
            var num = 0; var xPow = 1
            for (i in 0 until parity) { num = num xor mul(omega[i], xPow); xPow = mul(xPow, xInv) }
            var den = 0; var xInv2 = 1
            var i = 1
            while (i <= l) { den = den xor mul(sigma[i], xInv2); xInv2 = mul(xInv2, mul(xInv, xInv)); i += 2 }
            if (den == 0) return null
            words[23 - p] = words[23 - p] xor div(num, den)
        }
        return l
    }

    /** Appends [parity] RS parity words to the first 24-[parity] data words in [words] (for tests). */
    fun encode(words: IntArray, parity: Int) {
        // Generator g(x) = (x + alpha^1)(x + alpha^2)...(x + alpha^(2t)), monic of degree 2t; g[0] = constant term.
        val g = IntArray(parity + 1); g[0] = 1
        for (i in 1..parity) {
            val root = EXP[i]
            for (j in i downTo 1) g[j] = g[j - 1] xor mul(g[j], root)
            g[0] = mul(g[0], root)
        }
        val rem = IntArray(parity)
        for (i in 0 until 24 - parity) {
            val fb = words[i] xor rem[parity - 1]
            for (j in parity - 1 downTo 1) rem[j] = rem[j - 1] xor mul(fb, g[j])
            rem[0] = mul(fb, g[0])
        }
        for (i in 0 until parity) words[24 - parity + i] = rem[parity - 1 - i]
    }
}
