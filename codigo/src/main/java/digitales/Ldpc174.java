package digitales;

/**
 * Descodificador del código LDPC(174,91) de FT8/FT4: propagación de creencias y, si no basta,
 * búsqueda "OSD" (ordered statistics). Pasado a Java desde decode174_91.f90 y osd174_91.f90
 * de WSJT-X (K1JT, K9AN y colaboradores, GPL).
 */
final class Ldpc174 {
    private Ldpc174() { }

    static final int N = 174, K = 91, M = 83;

    /** Resultado: bits (174), errores duros (-1 si falla) y distancia. */
    static final class Resultado {
        int[] cw = new int[N + 1];   // 1..174
        int nharderror = -1;
        double dmin;
    }

    private static double platanh(double x) {
        int signo = 1;
        double z = x;
        if (x < 0) { signo = -1; z = Math.abs(x); }
        if (z <= 0.664) return x / 0.83;
        if (z <= 0.9217) return signo * (z - 0.4064) / 0.322;
        if (z <= 0.9951) return signo * (z - 0.8378) / 0.0524;
        if (z <= 0.9998) return signo * (z - 0.9914) / 0.0012;
        return signo * 7.0;
    }

    /** ¿Los 91 primeros bits (1..91) tienen bien el CRC? */
    static boolean crcBien(int[] cw) {
        int[] a = new int[12];
        for (int i = 0; i < 77; i++) if (cw[i + 1] != 0) a[i / 8] |= 0x80 >> (i % 8);
        int c = Ft8Codigo.crc(a, 82);
        int guardado = 0;
        for (int i = 78; i <= 91; i++) guardado = (guardado << 1) | (cw[i] & 1);
        return c == guardado;
    }

    /**
     * llr (1..174), apmask (1..174). maxosd: -1 solo BP; 0..3 número de intentos OSD. norder: profundidad OSD.
     */
    static Resultado descodificar(double[] llr, int maxosd, int norder, int[] apmask) {
        Resultado r = new Resultado();
        int maxIter = 30;
        int nosd;
        double[][] zsave = new double[4][];
        if (maxosd > 3) maxosd = 3;
        if (maxosd == 0) { nosd = 1; zsave[1] = llr.clone(); }
        else nosd = Math.max(maxosd, 0);
        double[][] toc = new double[M + 1][8], tov = new double[N + 1][4], tanhtoc = new double[M + 1][8];
        int[][] nm = Ft8Tablas.NM, mn = Ft8Tablas.MN;
        int[] nrw = Ft8Tablas.FILAS_NM;
        for (int j = 1; j <= M; j++) for (int i = 1; i <= nrw[j - 1]; i++) toc[j][i] = llr[nm[j - 1][i - 1]];
        int ncnt = 0, nclast = 0;
        double[] zn = new double[N + 1], zsum = new double[N + 1];
        int[] cw = r.cw;
        for (int iter = 0; iter <= maxIter; iter++) {
            for (int i = 1; i <= N; i++) {
                zn[i] = llr[i];
                if (apmask[i] != 1) zn[i] += tov[i][1] + tov[i][2] + tov[i][3];
            }
            for (int i = 1; i <= N; i++) zsum[i] += zn[i];
            if (iter > 0 && iter <= maxosd) zsave[iter] = zsum.clone();
            for (int i = 1; i <= N; i++) cw[i] = zn[i] > 0 ? 1 : 0;
            int ncheck = 0;
            for (int i = 1; i <= M; i++) {
                int s = 0;
                for (int q = 0; q < nrw[i - 1]; q++) s += cw[nm[i - 1][q]];
                if ((s & 1) != 0) ncheck++;
            }
            if (ncheck == 0 && crcBien(cw)) {
                int nh = 0;
                double dmin = 0;
                for (int i = 1; i <= N; i++) {
                    if ((2 * cw[i] - 1) * llr[i] < 0) nh++;
                    int hd = llr[i] >= 0 ? 1 : 0;
                    if (hd != cw[i]) dmin += Math.abs(llr[i]);
                }
                r.nharderror = nh;
                r.dmin = dmin;
                return r;
            }
            if (iter > 0) {
                int nd = ncheck - nclast;
                if (nd < 0) ncnt = 0; else ncnt++;
                if (ncnt >= 5 && iter >= 10 && ncheck > 15) { r.nharderror = -1; break; }
            }
            nclast = ncheck;
            for (int j = 1; j <= M; j++) {
                for (int i = 1; i <= nrw[j - 1]; i++) {
                    int ibj = nm[j - 1][i - 1];
                    double t = zn[ibj];
                    for (int kk = 1; kk <= 3; kk++) if (mn[ibj - 1][kk - 1] == j) t -= tov[ibj][kk];
                    toc[j][i] = t;
                }
            }
            for (int i = 1; i <= M; i++) for (int q = 1; q <= 7; q++) tanhtoc[i][q] = Math.tanh(-toc[i][q] / 2);
            for (int j = 1; j <= N; j++) {
                for (int i = 1; i <= 3; i++) {
                    int ichk = mn[j - 1][i - 1];
                    double tmn = 1;
                    for (int q = 1; q <= nrw[ichk - 1]; q++) if (nm[ichk - 1][q - 1] != j) tmn *= tanhtoc[ichk][q];
                    tov[j][i] = 2 * platanh(-tmn);
                }
            }
        }
        for (int i = 1; i <= nosd; i++) {
            if (zsave[i] == null) continue;
            Resultado o = osd(zsave[i], apmask, norder);
            if (o.nharderror > 0) {
                double dmin = 0;
                for (int q = 1; q <= N; q++) {
                    int hd = llr[q] >= 0 ? 1 : 0;
                    if (hd != o.cw[q]) dmin += Math.abs(llr[q]);
                }
                o.dmin = dmin;
                return o;
            }
        }
        r.nharderror = -1;
        return r;
    }

    // ------------------------------------------------------------------ OSD
    private static int[][] gen;   // gen[i][j] i=1..91, j=1..174

    private static synchronized int[][] generador() {
        if (gen != null) return gen;
        int[][] g = new int[K + 1][N + 1];
        for (int i = 1; i <= K; i++) {
            int[] a = new int[12];
            a[(i - 1) / 8] |= 0x80 >> ((i - 1) % 8);
            int[] c = Ft8Codigo.codificar174(a);
            for (int j = 1; j <= N; j++) g[i][j] = (c[(j - 1) / 8] >> (7 - (j - 1) % 8)) & 1;
        }
        gen = g;
        return g;
    }

    /** Siguiente patrón de bits (nextpat91). Devuelve iflag. */
    private static int siguiente(int[] mi, int k, int iorder) {
        int ind = -1;
        for (int i = 1; i <= k - 1; i++) if (mi[i] == 0 && mi[i + 1] == 1) ind = i;
        if (ind < 0) return -1;
        int[] ms = new int[k + 1];
        for (int i = 1; i <= ind - 1; i++) ms[i] = mi[i];
        ms[ind] = 1;
        ms[ind + 1] = 0;
        if (ind + 1 < k) {
            int suma = 0;
            for (int i = 1; i <= k; i++) suma += ms[i];
            int nz = iorder - suma;
            for (int i = k - nz + 1; i <= k; i++) ms[i] = 1;
        }
        System.arraycopy(ms, 0, mi, 0, k + 1);
        for (int i = 1; i <= k; i++) if (mi[i] == 1) return i;
        return -1;
    }

    // Filas de bits: 174 columnas en 3 números de 64 bits (bit c-1 = columna c)
    private static boolean bit(long[] f, int c) { return ((f[(c - 1) >> 6] >>> ((c - 1) & 63)) & 1L) != 0; }
    private static void cambiar(long[] f, int c) { f[(c - 1) >> 6] ^= 1L << ((c - 1) & 63); }

    static Resultado osd(double[] llr, int[] apmask, int ndeep) {
        int[][] g = generador();
        int k = K;
        Resultado res = new Resultado();
        double[] absrx0 = new double[N + 1];
        int[] hdec0 = new int[N + 1];
        for (int i = 1; i <= N; i++) { hdec0[i] = llr[i] >= 0 ? 1 : 0; absrx0[i] = Math.abs(llr[i]); }
        // columnas ordenadas por fiabilidad (de más a menos), como indexx(absrx) al revés
        Integer[] orden = new Integer[N];
        for (int i = 0; i < N; i++) orden[i] = i + 1;
        java.util.Arrays.sort(orden, (a, b) -> Double.compare(absrx0[a], absrx0[b]));
        int[] indices = new int[N + 1];
        for (int i = 1; i <= N; i++) indices[i] = orden[N - i];
        long[][] fila = new long[k + 1][3];
        for (int r = 1; r <= k; r++) for (int i = 1; i <= N; i++) if (g[r][indices[i]] == 1) cambiar(fila[r], i);
        for (int id = 1; id <= k; id++) {
            for (int icol = id; icol <= k + 20; icol++) {
                if (bit(fila[id], icol)) {
                    if (icol != id) {
                        for (int r = 1; r <= k; r++) {
                            boolean b1 = bit(fila[r], id), b2 = bit(fila[r], icol);
                            if (b1 != b2) { cambiar(fila[r], id); cambiar(fila[r], icol); }
                        }
                        int t = indices[id]; indices[id] = indices[icol]; indices[icol] = t;
                    }
                    long[] p = fila[id];
                    for (int ii = 1; ii <= k; ii++) {
                        if (ii != id && bit(fila[ii], id)) { fila[ii][0] ^= p[0]; fila[ii][1] ^= p[1]; fila[ii][2] ^= p[2]; }
                    }
                    break;
                }
            }
        }
        int[] hdec = new int[N + 1], apmaskr = new int[N + 1];
        double[] absrx = new double[N + 1];
        long[] hbits = new long[3];
        for (int i = 1; i <= N; i++) {
            hdec[i] = hdec0[indices[i]]; absrx[i] = absrx0[indices[i]]; apmaskr[i] = apmask[indices[i]];
            if (hdec[i] == 1) cambiar(hbits, i);
        }
        // m0 = hdec(1:k); c0 = suma de las filas con m0=1
        long[] c0 = new long[3];
        int[] m0 = new int[k + 1];
        for (int i = 1; i <= k; i++) { m0[i] = hdec[i]; if (m0[i] == 1) { c0[0] ^= fila[i][0]; c0[1] ^= fila[i][1]; c0[2] ^= fila[i][2]; } }
        int nhardmin = 0;
        double dmin = 0;
        for (int i = 1; i <= N; i++) if (bit(c0, i) != (hdec[i] == 1)) { nhardmin++; dmin += absrx[i]; }
        long[] cw = c0.clone();
        // máscaras de las columnas k+1..N y k+1..k+nt
        int nt = 40;
        long[] mascResto = new long[3], mascNt = new long[3];
        for (int i = k + 1; i <= N; i++) cambiar(mascResto, i);
        for (int i = k + 1; i <= k + nt; i++) cambiar(mascNt, i);
        if (ndeep > 0) {
            int nord = 1, npre1 = ndeep == 1 ? 0 : 1, ntheta = ndeep == 1 ? 12 : 10;
            int[] misub = new int[k + 1];
            long[] ce = new long[3], e2sub = new long[3], e2 = new long[3];
            for (int iorder = 1; iorder <= nord; iorder++) {
                java.util.Arrays.fill(misub, 0);
                for (int i = k - iorder + 1; i <= k; i++) misub[i] = 1;
                int iflag = k - iorder + 1;
                while (iflag >= 0) {
                    int iend = (iorder == nord && npre1 == 0) ? iflag : 1;
                    double d1 = 0;
                    // ce de misub (patrón base): c0 ^ filas de misub
                    long b0 = c0[0], b1 = c0[1], b2 = c0[2];
                    for (int i = 1; i <= k; i++) if (misub[i] == 1) { b0 ^= fila[i][0]; b1 ^= fila[i][1]; b2 ^= fila[i][2]; }
                    for (int n1 = iflag; n1 >= iend; n1--) {
                        boolean choca = apmaskr[n1] == 1 && misub[n1] == 0;
                        if (!choca) for (int i = 1; i <= k; i++) if (misub[i] == 1 && apmaskr[i] == 1) { choca = true; break; }
                        if (choca) continue;
                        // me = m0 ^ misub ^ (n1 si no estaba ya en misub)
                        boolean anadir = misub[n1] == 0;
                        ce[0] = b0; ce[1] = b1; ce[2] = b2;
                        if (anadir) { ce[0] ^= fila[n1][0]; ce[1] ^= fila[n1][1]; ce[2] ^= fila[n1][2]; }
                        int nd1kpt;
                        if (n1 == iflag) {
                            for (int q = 0; q < 3; q++) e2sub[q] = (ce[q] ^ hbits[q]) & mascResto[q];
                            nd1kpt = 1 + Long.bitCount(e2sub[0] & mascNt[0]) + Long.bitCount(e2sub[1] & mascNt[1]) + Long.bitCount(e2sub[2] & mascNt[2]);
                            d1 = 0;
                            for (int i = 1; i <= k; i++) {
                                int me = m0[i] ^ misub[i] ^ (i == n1 && anadir ? 1 : 0);
                                if ((me ^ hdec[i]) == 1) d1 += absrx[i];
                            }
                            System.arraycopy(e2sub, 0, e2, 0, 3);
                        } else {
                            for (int q = 0; q < 3; q++) e2[q] = e2sub[q] ^ (fila[n1][q] & mascResto[q]);
                            nd1kpt = 2 + Long.bitCount(e2[0] & mascNt[0]) + Long.bitCount(e2[1] & mascNt[1]) + Long.bitCount(e2[2] & mascNt[2]);
                        }
                        if (nd1kpt <= ntheta) {
                            double dd;
                            if (n1 == iflag) dd = d1;
                            else dd = d1 + ((bit(ce, n1) ? 1 : 0) ^ hdec[n1]) * absrx[n1];
                            for (int q = 0; q < 3; q++) {
                                long x = q == 0 ? (n1 == iflag ? e2sub[0] : e2[0]) : q == 1 ? (n1 == iflag ? e2sub[1] : e2[1]) : (n1 == iflag ? e2sub[2] : e2[2]);
                                while (x != 0) { int t = Long.numberOfTrailingZeros(x); dd += absrx[q * 64 + t + 1]; x &= x - 1; }
                            }
                            if (dd < dmin) {
                                dmin = dd;
                                cw[0] = ce[0]; cw[1] = ce[1]; cw[2] = ce[2];
                                nhardmin = Long.bitCount(ce[0] ^ hbits[0]) + Long.bitCount(ce[1] ^ hbits[1]) + Long.bitCount(ce[2] ^ hbits[2]);
                            }
                        }
                    }
                    iflag = siguiente(misub, k, iorder);
                }
            }
        }
        int[] cwOrig = new int[N + 1];
        for (int i = 1; i <= N; i++) cwOrig[indices[i]] = bit(cw, i) ? 1 : 0;
        res.cw = cwOrig;
        res.nharderror = crcBien(cwOrig) ? nhardmin : -nhardmin;
        res.dmin = dmin;
        return res;
    }
}
