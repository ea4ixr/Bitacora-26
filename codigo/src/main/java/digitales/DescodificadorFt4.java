package digitales;

import java.util.ArrayList;
import java.util.List;

/**
 * Descodificador de FT4. Pasado a Java desde WSJT-X (ft4_decode, getcandidates4, ft4_downsample,
 * sync4d, get_ft4_bitmetrics, subtractft4...), de K1JT, K9AN y colaboradores (GPL).
 * Recibe el audio de un periodo de 7,5 s (12000 muestras/s) y devuelve los mensajes.
 */
public final class DescodificadorFt4 {
    static final int KK = 91, ND = 87, NS = 16, NN = NS + ND, NN2 = NN + 2, NSPS = 576;
    static final int NZ = NSPS * NN, NMAX = 21 * 3456, NFFT1 = 2304, NH1 = NFFT1 / 2, NSTEP = NSPS;
    static final int NHSYM = (NMAX - NFFT1) / NSTEP, NDOWN = 18, NSS = NSPS / NDOWN, NDMAX = NMAX / NDOWN;
    static final int[][] COSTAS = {{0, 1, 3, 2}, {1, 0, 2, 3}, {2, 3, 1, 0}, {3, 2, 0, 1}};
    static final int[] GRAY = {0, 1, 3, 2};
    static final int[] RVEC = {0,1,0,0,1,0,1,0,0,1,0,1,1,1,1,0,1,0,0,0,1,0,0,1,1,0,1,1,0,
            1,0,0,1,0,1,1,0,0,0,0,1,0,0,0,1,0,1,0,0,1,1,1,1,0,0,1,0,1,
            0,1,0,1,0,1,1,0,1,1,1,1,1,0,0,0,1,0,1};
    private static final int MAXCAND = 200;

    private final Ft8Mensaje mensajes;

    public DescodificadorFt4(Ft8Mensaje mensajes) { this.mensajes = mensajes; }

    private static int nint(double x) { return (int) Math.round(x); }

    // ------------------------------------------------------------------ ventanas y tablas
    private static double[] nuttal;
    private static double[] ventanaBajada;
    private static double[][] ctwkR, ctwkI;      // [idf+16][1..64]
    private static double[][] csR, csI;          // 4 Costas x 1..64

    private static synchronized void preparar() {
        if (nuttal != null) return;
        double[] w = new double[NFFT1];
        double a0 = 0.3635819, a1 = -0.4891775, a2 = 0.1365995, a3 = -0.0106411;
        for (int i = 0; i < NFFT1; i++)
            w[i] = a0 + a1 * Math.cos(2 * Math.PI * i / NFFT1) + a2 * Math.cos(4 * Math.PI * i / NFFT1) + a3 * Math.cos(6 * Math.PI * i / NFFT1);
        // ventana de ft4_downsample (con aritmética de precisión simple, como en WSJT-X)
        int nfft2 = NMAX / NDOWN;
        float df = 12000.0f / NMAX, baud = 12000.0f / NSPS;
        float bwT = 0.5f * baud, bwF = 4 * baud;
        int iwt = (int) (bwT / df), iwf = (int) (bwF / df), iws = (int) (baud / df);
        double[] win = new double[nfft2];
        for (int j = 0; j < iwt; j++) win[j] = 0.5 * (1 + Math.cos(Math.PI * (iwt - 1 - j) / iwt));
        for (int j = iwt; j < iwt + iwf; j++) win[j] = 1.0;
        for (int j = 0; j < iwt; j++) win[iwt + iwf + j] = 0.5 * (1 + Math.cos(Math.PI * j / iwt));
        double[] r = new double[nfft2];
        for (int i = 0; i < nfft2; i++) r[i] = win[(i + iws) % nfft2];
        // giros de frecuencia (twkfreq1 con a(1)=idf a fs/2)
        double fs = 12000.0 / NDOWN;
        double[][] tr = new double[33][2 * NSS + 1], ti = new double[33][2 * NSS + 1];
        for (int idf = -16; idf <= 16; idf++) {
            double dphi = idf * (2 * Math.PI / (fs / 2.0));
            double wr = 1, wi = 0, sr = Math.cos(dphi), si = Math.sin(dphi);
            for (int i = 1; i <= 2 * NSS; i++) {
                double nr = wr * sr - wi * si, ni = wr * si + wi * sr;
                wr = nr; wi = ni;
                tr[idf + 16][i] = wr; ti[idf + 16][i] = wi;
            }
        }
        double[][] cr = new double[4][2 * NSS + 1], ci = new double[4][2 * NSS + 1];
        for (int a = 0; a < 4; a++) {
            int k = 1;
            double phi = 0;
            for (int i = 0; i <= 3; i++) {
                double dphi = 2 * (2 * Math.PI) * COSTAS[a][i] / NSS;
                for (int j = 1; j <= NSS / 2; j++) {
                    cr[a][k] = Math.cos(phi); ci[a][k] = Math.sin(phi);
                    phi = (phi + dphi) % (2 * Math.PI);
                    k++;
                }
            }
        }
        ventanaBajada = r;
        ctwkR = tr; ctwkI = ti;
        csR = cr; csI = ci;
        nuttal = w;
    }

    // ------------------------------------------------------------------ candidatos (getcandidates4)
    private record Cand(double f, double s) { }

    private List<Cand> candidatos(float[] dd, double fa, double fb, double syncmin, int nfqso) {
        preparar();
        double df = 12000.0 / NFFT1, fac = 1.0 / 300.0;
        double[] savg = new double[NH1 + 1];
        Fft f = Fft.de(NFFT1);
        double[] re = new double[NFFT1], im = new double[NFFT1];
        for (int j = 1; j <= NHSYM; j++) {
            int ia = (j - 1) * NSTEP;
            if (ia + NFFT1 > NMAX) break;
            for (int i = 0; i < NFFT1; i++) { re[i] = fac * dd[ia + i] * nuttal[i]; im[i] = 0; }
            f.transformar(re, im, false);
            for (int i = 1; i <= NH1; i++) savg[i] += re[i] * re[i] + im[i] * im[i];
        }
        for (int i = 1; i <= NH1; i++) savg[i] /= NHSYM;
        double[] savsm = new double[NH1 + 1];
        for (int i = 8; i <= NH1 - 7; i++) {
            double s = 0;
            for (int q = i - 7; q <= i + 7; q++) s += savg[q];
            savsm[i] = s / 15.0;
        }
        int nfa = (int) (fa / df);
        if (nfa < nint(200.0 / df)) nfa = nint(200.0 / df);
        int nfb = (int) (fb / df);
        if (nfb > nint(4910.0 / df)) nfb = nint(4910.0 / df);
        double[] sbase = baseline(savg.clone(), nfa, nfb);
        for (int i = nfa; i <= nfb; i++) if (sbase[i] <= 0) return new ArrayList<>();
        for (int i = nfa; i <= nfb; i++) savsm[i] /= sbase[i];
        double fOffset = -1.5 * 12000.0 / NSPS;
        List<Cand> lista = new ArrayList<>();
        for (int i = nfa + 1; i <= nfb - 1; i++) {
            if (savsm[i] >= savsm[i - 1] && savsm[i] >= savsm[i + 1] && savsm[i] >= syncmin) {
                double den = savsm[i - 1] - 2 * savsm[i] + savsm[i + 1], del = 0;
                if (den != 0) del = 0.5 * (savsm[i - 1] - savsm[i + 1]) / den;
                double fpeak = (i + del) * df + fOffset;
                if (fpeak < 200.0 || fpeak > 4910.0) continue;
                double speak = savsm[i] - 0.25 * (savsm[i - 1] - savsm[i + 1]) * del;
                lista.add(new Cand(fpeak, speak));
                if (lista.size() == MAXCAND) break;
            }
        }
        List<Cand> r = new ArrayList<>();
        for (Cand c : lista) if (Math.abs(c.f - nfqso) <= 20.0) r.add(c);
        for (Cand c : lista) if (Math.abs(c.f - nfqso) > 20.0) r.add(c);
        return r;
    }

    private static double[] baseline(double[] s, int nfa, int nfb) {
        double df = 12000.0 / NFFT1;
        int ia = Math.max(nint(200.0 / df), nfa), ib = Math.min(NH1, nfb);
        for (int i = ia; i <= ib; i++) s[i] = 10.0 * Math.log10(s[i]);
        int nseg = 10, npct = 10, nlen = (ib - ia + 1) / nseg, i0 = (ib - ia + 1) / 2;
        double[] x = new double[1001], y = new double[1001];
        int k = 0;
        for (int n = 1; n <= nseg; n++) {
            int ja = ia + (n - 1) * nlen, jb = ja + nlen - 1;
            double[] tmp = new double[nlen];
            for (int i = 0; i < nlen; i++) tmp[i] = s[ja + i];
            java.util.Arrays.sort(tmp);
            int j = Math.min(Math.max(nint(nlen * 0.01 * npct), 1), nlen);
            double base = tmp[j - 1];
            for (int i = ja; i <= jb; i++) if (s[i] <= base) { if (k < 1000) k++; x[k] = i - i0; y[k] = s[i]; }
        }
        double[] a = minimosCuadrados(x, y, k, 5);
        double[] sbase = new double[NH1 + 1];
        for (int i = ia; i <= ib; i++) {
            double t = i - i0;
            double v = a[0] + t * (a[1] + t * (a[2] + t * (a[3] + t * a[4]))) + 0.65;
            sbase[i] = Math.pow(10, v / 10.0);
        }
        return sbase;
    }

    static double[] minimosCuadrados(double[] x, double[] y, int npts, int nterms) {
        int nmax = 2 * nterms - 1;
        double[] sumx = new double[nmax + 1], sumy = new double[nterms + 1];
        for (int i = 1; i <= npts; i++) {
            double xt = 1;
            for (int n = 1; n <= nmax; n++) { sumx[n] += xt; xt *= x[i]; }
            double yt = y[i];
            for (int n = 1; n <= nterms; n++) { sumy[n] += yt; yt *= x[i]; }
        }
        double[][] m = new double[nterms][nterms + 1];
        for (int j = 0; j < nterms; j++) { for (int c = 0; c < nterms; c++) m[j][c] = sumx[j + c + 1]; m[j][nterms] = sumy[j + 1]; }
        for (int c = 0; c < nterms; c++) {
            int p = c;
            for (int r = c + 1; r < nterms; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            double[] t = m[c]; m[c] = m[p]; m[p] = t;
            if (Math.abs(m[c][c]) < 1e-300) return new double[nterms];
            for (int r = 0; r < nterms; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                for (int q = c; q <= nterms; q++) m[r][q] -= f * m[c][q];
            }
        }
        double[] a = new double[nterms];
        for (int c = 0; c < nterms; c++) a[c] = m[c][nterms] / m[c][c];
        return a;
    }

    // ------------------------------------------------------------------ bajar a 666,67 muestras/s (ft4_downsample)
    private double[] cxRe, cxIm;

    private void prepararEspectro(float[] dd) {
        double[] re = new double[NMAX], im = new double[NMAX];
        for (int i = 0; i < NMAX; i++) re[i] = dd[i];
        Fft.de(NMAX).transformar(re, im, false);
        cxRe = re; cxIm = im;
    }

    private void bajar(double f0, double[] cr, double[] ci) {
        int nfft2 = NMAX / NDOWN;
        double df = 12000.0 / NMAX;
        int i0 = nint(f0 / df);
        java.util.Arrays.fill(cr, 0);
        java.util.Arrays.fill(ci, 0);
        if (i0 >= 0 && i0 <= NMAX / 2) { cr[0] = cxRe[i0]; ci[0] = cxIm[i0]; }
        for (int i = 1; i <= nfft2 / 2; i++) {
            if (i0 + i >= 0 && i0 + i <= NMAX / 2) { cr[i] = cxRe[i0 + i]; ci[i] = cxIm[i0 + i]; }
            if (i0 - i >= 0 && i0 - i <= NMAX / 2) { cr[nfft2 - i] = cxRe[i0 - i]; ci[nfft2 - i] = cxIm[i0 - i]; }
        }
        for (int i = 0; i < nfft2; i++) { cr[i] *= ventanaBajada[i] / nfft2; ci[i] *= ventanaBajada[i] / nfft2; }
        Fft.de(nfft2).transformar(cr, ci, true);
    }

    // ------------------------------------------------------------------ sync4d
    private static double potencia(double zr, double zi) {
        double fac = 1.0 / (2.0 * NSS);
        return Math.sqrt((zr * fac) * (zr * fac) + (zi * fac) * (zi * fac));
    }

    private static double sync4d(double[] cr, double[] ci, int i0, int idf) {
        int np = NDMAX;
        double[] twR = ctwkR[idf + 16], twI = ctwkI[idf + 16];
        int[] ini = {i0, i0 + 33 * NSS, i0 + 66 * NSS, i0 + 99 * NSS};
        double sync = 0;
        for (int a = 0; a < 4; a++) {
            // csync2 = ctwk * csync(a)
            int ii = ini[a];
            double zr = 0, zi = 0;
            int desde = 1, hasta = 2 * NSS, inicio = ii;
            if (ii >= 0 && ii + 4 * NSS - 1 <= np - 1) {
                // completo
            } else if (a == 0 && ii < 0) {
                int npts = (ii + 4 * NSS - 1) / 2;
                if (npts <= 16) continue;
                desde = 2 * NSS - npts; inicio = 0;
            } else if (a == 3 && ii + 4 * NSS - 1 > np - 1) {
                int npts = (np - 1 - ii + 1) / 2;
                if (npts <= 16) continue;
                hasta = npts;
            } else continue;
            int idx = inicio;
            for (int j = desde; j <= hasta; j++) {
                double sr = twR[j] * csR[a][j] - twI[j] * csI[a][j];
                double si = twR[j] * csI[a][j] + twI[j] * csR[a][j];
                double xr = cr[idx], xi = ci[idx];
                zr += xr * sr + xi * si;
                zi += xi * sr - xr * si;
                idx += 2;
            }
            sync += potencia(zr, zi);
        }
        return sync;
    }

    // ------------------------------------------------------------------ métricas de bits
    private static double[][] metricas(double[] cdr, double[] cdi) {
        double[][] csr = new double[4][NN + 1], csi = new double[4][NN + 1], s4 = new double[4][NN + 1];
        Fft f = Fft.de(NSS);
        double[] br = new double[NSS], bi = new double[NSS];
        for (int k = 1; k <= NN; k++) {
            int i1 = (k - 1) * NSS;
            for (int q = 0; q < NSS; q++) { br[q] = cdr[i1 + q]; bi[q] = cdi[i1 + q]; }
            f.transformar(br, bi, false);
            for (int q = 0; q <= 3; q++) { csr[q][k] = br[q]; csi[q][k] = bi[q]; s4[q][k] = Math.hypot(br[q], bi[q]); }
        }
        int nsync = 0;
        int[] off = {0, 33, 66, 99};
        for (int a = 0; a < 4; a++) {
            for (int k = 1; k <= 4; k++) {
                int ip = 0; double mx = -1;
                for (int q = 0; q <= 3; q++) if (s4[q][k + off[a]] > mx) { mx = s4[q][k + off[a]]; ip = q; }
                if (COSTAS[a][k - 1] == ip) nsync++;
            }
        }
        if (nsync < 8) return null;
        double[][] bm = new double[4][2 * NN + 1];
        double[] s2 = new double[256];
        for (int nseq = 1; nseq <= 3; nseq++) {
            int nsym = nseq == 1 ? 1 : nseq == 2 ? 2 : 4;
            int nt = 1 << (2 * nsym);
            for (int ks = 1; ks <= NN - nsym + 1; ks += nsym) {
                for (int i = 0; i < nt; i++) {
                    int i1 = i / 64, i2 = (i & 63) / 16, i3 = (i & 15) / 4, i4 = i & 3;
                    double zr, zi;
                    if (nsym == 1) { zr = csr[GRAY[i4]][ks]; zi = csi[GRAY[i4]][ks]; }
                    else if (nsym == 2) { zr = csr[GRAY[i3]][ks] + csr[GRAY[i4]][ks + 1]; zi = csi[GRAY[i3]][ks] + csi[GRAY[i4]][ks + 1]; }
                    else {
                        zr = csr[GRAY[i1]][ks] + csr[GRAY[i2]][ks + 1] + csr[GRAY[i3]][ks + 2] + csr[GRAY[i4]][ks + 3];
                        zi = csi[GRAY[i1]][ks] + csi[GRAY[i2]][ks + 1] + csi[GRAY[i3]][ks + 2] + csi[GRAY[i4]][ks + 3];
                    }
                    s2[i] = Math.hypot(zr, zi);
                }
                int ipt = 1 + (ks - 1) * 2;
                int ibmax = nsym == 1 ? 1 : nsym == 2 ? 3 : 7;
                for (int ib = 0; ib <= ibmax; ib++) {
                    int bit = ibmax - ib;
                    double m1 = -1e30, m0 = -1e30;
                    for (int i = 0; i < nt; i++) {
                        if (((i >> bit) & 1) != 0) { if (s2[i] > m1) m1 = s2[i]; } else { if (s2[i] > m0) m0 = s2[i]; }
                    }
                    if (ipt + ib > 2 * NN) continue;
                    bm[nseq][ipt + ib] = m1 - m0;
                }
            }
        }
        bm[2][205] = bm[1][205]; bm[2][206] = bm[1][206];
        for (int i = 201; i <= 204; i++) bm[3][i] = bm[2][i];
        bm[3][205] = bm[1][205]; bm[3][206] = bm[1][206];
        for (int q = 1; q <= 3; q++) normalizar(bm[q], 2 * NN);
        return bm;
    }

    private static void normalizar(double[] b, int n) {
        double s = 0, s2 = 0;
        for (int i = 1; i <= n; i++) { s += b[i]; s2 += b[i] * b[i]; }
        double av = s / n, av2 = s2 / n, var = av2 - av * av;
        double sig = var > 0 ? Math.sqrt(var) : Math.sqrt(av2);
        if (sig == 0) return;
        for (int i = 1; i <= n; i++) b[i] /= sig;
    }

    // ------------------------------------------------------------------ bits -> tonos y onda
    /** 77 bits del mensaje (sin mezclar) -> 103 tonos de FT4. */
    public static int[] tonos(int[] bits77) {
        int[] m = new int[77];
        for (int i = 0; i < 77; i++) m[i] = (bits77[i] + RVEC[i]) & 1;
        int[] a = new int[12];
        for (int i = 0; i < 77; i++) if (m[i] != 0) a[i / 8] |= 0x80 >> (i % 8);
        int[] cw = Ft8Codigo.codificar174(Ft8Codigo.conCrc(a));
        int[] itmp = new int[ND + 1];
        for (int i = 1; i <= ND; i++) {
            int b1 = (cw[(2 * i - 2) / 8] >> (7 - (2 * i - 2) % 8)) & 1;   // codeword(2i-1)
            int b2 = (cw[(2 * i - 1) / 8] >> (7 - (2 * i - 1) % 8)) & 1;   // codeword(2i)
            int is = b2 + 2 * b1;
            itmp[i] = is <= 1 ? is : (is == 2 ? 3 : 2);
        }
        int[] t = new int[NN];
        for (int i = 0; i < 4; i++) { t[i] = COSTAS[0][i]; t[33 + i] = COSTAS[1][i]; t[66 + i] = COSTAS[2][i]; t[99 + i] = COSTAS[3][i]; }
        for (int i = 0; i < 29; i++) { t[4 + i] = itmp[1 + i]; t[37 + i] = itmp[30 + i]; t[70 + i] = itmp[59 + i]; }
        return t;
    }

    /** Onda de FT4 (gen_ft4wave): compleja si wi != null, si no real (seno). nwave = 105·576. */
    static void onda(int[] itone, double f0, double[] wr, double[] wi) {
        int nsym = NN, nsps = NSPS;
        double fs = 12000.0, dt = 1.0 / fs;
        double[] pulse = DescodificadorFt8.pulso(1.0, nsps);
        double[] dphi = new double[(nsym + 2) * nsps];
        double dphiPeak = 2 * Math.PI / nsps;
        for (int j = 1; j <= nsym; j++) {
            int ib = (j - 1) * nsps;
            for (int i = 0; i < 3 * nsps; i++) dphi[ib + i] += dphiPeak * pulse[i] * itone[j - 1];
        }
        for (int i = 0; i < dphi.length; i++) dphi[i] += 2 * Math.PI * f0 * dt;
        double phi = 0;
        int n = (nsym + 2) * nsps;
        for (int k = 0; k < n; k++) {
            if (wi != null) { wr[k] = Math.cos(phi); wi[k] = Math.sin(phi); } else wr[k] = Math.sin(phi);
            phi = (phi + dphi[k]) % (2 * Math.PI);
        }
        for (int i = 0; i < nsps; i++) {
            double g1 = (1.0 - Math.cos(2 * Math.PI * i / (2.0 * nsps))) / 2.0;
            double g2 = (1.0 + Math.cos(2 * Math.PI * i / (2.0 * nsps))) / 2.0;
            int k1 = (nsym + 1) * nsps + i;
            wr[i] *= g1; wr[k1] *= g2;
            if (wi != null) { wi[i] *= g1; wi[k1] *= g2; }
        }
    }

    // ------------------------------------------------------------------ restar (subtractft4)
    private static final int NFILT = 1400;
    private static double[] filR, filI;

    private static synchronized void prepararFiltro() {
        if (filR != null) return;
        int nfft = NMAX;
        double[] win = new double[NFILT + 1];
        double sum = 0;
        for (int j = -NFILT / 2; j <= NFILT / 2; j++) { win[j + NFILT / 2] = Math.pow(Math.cos(Math.PI * j / NFILT), 2); sum += win[j + NFILT / 2]; }
        double[] r = new double[nfft], im = new double[nfft], rr = new double[nfft];
        for (int i = 0; i <= NFILT; i++) r[i] = win[i] / sum;
        int sh = NFILT / 2 + 1;
        for (int i = 0; i < nfft; i++) rr[i] = r[(i + sh) % nfft];
        Fft.de(nfft).transformar(rr, im, false);
        for (int i = 0; i < nfft; i++) { rr[i] /= nfft; im[i] /= nfft; }
        filR = rr; filI = im;
    }

    private static void restar(float[] dd, int[] itone, double f0, double dt) {
        prepararFiltro();
        int nframe = NN2 * NSPS, nfft = NMAX;
        int nstart = (int) (dt * 12000 + 1 - NSPS);
        double[] rr = new double[nframe], ri = new double[nframe];
        onda(itone, f0, rr, ri);
        double[] fr = new double[nfft], fi = new double[nfft];
        for (int i = 1; i <= nframe; i++) {
            int id = nstart - 1 + i;
            if (id >= 1 && id <= NMAX && i <= nfft) { fr[i - 1] = dd[id - 1] * rr[i - 1]; fi[i - 1] = -dd[id - 1] * ri[i - 1]; }
        }
        Fft f = Fft.de(nfft);
        f.transformar(fr, fi, false);
        for (int i = 0; i < nfft; i++) {
            double a = fr[i] * filR[i] - fi[i] * filI[i], b = fr[i] * filI[i] + fi[i] * filR[i];
            fr[i] = a; fi[i] = b;
        }
        f.transformar(fr, fi, true);
        for (int i = 1; i <= nframe; i++) {
            int j = nstart + i - 1;
            if (j >= 1 && j <= NMAX) dd[j - 1] -= (float) (2 * (fr[i - 1] * rr[i - 1] - fi[i - 1] * ri[i - 1]));
        }
    }

    // ------------------------------------------------------------------ AP
    private int[] apbits = new int[175];
    private int[] mcq, mrrr, m73, mrr73;

    private void prepararAp(DescodificadorFt8.Opciones o) {
        if (mcq == null) {
            mcq = mezclar(new int[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0}, 0);
            mrrr = mezclar(new int[]{0,1,1,1,1,1,1,0,1,0,0,1,0,0,1,0,0,0,1}, 58);
            m73 = mezclar(new int[]{0,1,1,1,1,1,1,0,1,0,0,1,0,1,0,0,0,0,1}, 58);
            mrr73 = mezclar(new int[]{0,1,1,1,1,1,1,0,0,1,1,1,0,1,0,1,0,0,1}, 58);
        }
        apbits = new int[175];
        apbits[1] = 99; apbits[30] = 99;
        String mio = o.miIndicativo == null ? "" : o.miIndicativo.trim().toUpperCase();
        if (mio.length() < 3) return;
        String suyo = o.suIndicativo == null ? "" : o.suIndicativo.trim().toUpperCase();
        boolean sinSuyo = suyo.length() < 3;
        if (sinSuyo) suyo = mio;
        String txt = mio + " " + suyo + " RR73";
        int[] b = mensajes.aBits(txt);
        if (b == null || ((b[74] << 2) | (b[75] << 1) | b[76]) != 1) return;
        Ft8Mensaje.Texto t = new Ft8Mensaje().aTexto(b);
        if (!t.ok() || !t.mensaje().equals(txt)) return;
        int[] m = new int[77];
        for (int i = 0; i < 77; i++) m[i] = (b[i] + RVEC[i]) & 1;
        int[] a = new int[12];
        for (int i = 0; i < 77; i++) if (m[i] != 0) a[i / 8] |= 0x80 >> (i % 8);
        int[] cw = Ft8Codigo.codificar174(Ft8Codigo.conCrc(a));
        for (int i = 1; i <= 174; i++) apbits[i] = 2 * ((cw[(i - 1) / 8] >> (7 - (i - 1) % 8)) & 1) - 1;
        if (sinSuyo) apbits[30] = 99;
    }

    private static int[] mezclar(int[] v, int desde) {
        int[] r = new int[v.length + 1];
        for (int i = 0; i < v.length; i++) r[i + 1] = 2 * ((v[i] + RVEC[desde + i]) % 2) - 1;
        return r;
    }

    private static final int[] NAPPASSES = {2, 2, 2, 2, 2, 3};
    private static final int[][] NAPTYPES = {{0, 1, 2, 0, 0}, {0, 2, 3, 0, 0}, {0, 2, 3, 0, 0}, {0, 3, 6, 0, 0}, {0, 3, 6, 0, 0}, {0, 3, 1, 2, 0}};

    // ------------------------------------------------------------------ bucle principal
    private static final int[][] SYNC_BITS = {{0, 0, 0, 1, 1, 0, 1, 1}, {0, 1, 0, 0, 1, 1, 1, 0}, {1, 1, 1, 0, 0, 1, 0, 0}, {1, 0, 1, 1, 0, 0, 0, 1}};

    /** Descodifica un periodo de FT4 (audio desde el comienzo del periodo, con la amplitud de 16 bits). */
    public List<DescodificadorFt8.Resultado> descodificar(float[] audio, DescodificadorFt8.Opciones o) {
        preparar();
        float[] dd = new float[NMAX];
        System.arraycopy(audio, 0, dd, 0, Math.min(audio.length, NMAX));
        mensajes.ponerIndicativos(o.miIndicativo, o.suIndicativo);
        prepararAp(o);
        List<DescodificadorFt8.Resultado> res = new ArrayList<>();
        java.util.Set<String> vistos = new java.util.HashSet<>();
        double syncmin = 1.18;
        boolean restarSi = o.profundidad >= 2, osdSi = o.profundidad >= 3;
        int nsp = o.profundidad == 1 ? 1 : 3;
        int nd1 = 0;
        for (int isp = 1; isp <= nsp; isp++) {
            if (isp == 2) { if (res.isEmpty()) break; nd1 = res.size(); }
            else if (isp == 3) { if (res.size() - nd1 == 0) break; }
            List<Cand> cands = candidatos(dd, o.fMin, o.fMax, syncmin, o.frecRx);
            prepararEspectro(dd);
            final float[] ddP = dd;
            Object[] sal = new Object[cands.size()];
            java.util.stream.IntStream.range(0, cands.size()).parallel().forEach(ic -> sal[ic] = candidato(ddP, cands.get(ic), o, osdSi));
            for (Object x : sal) {
                if (x == null) continue;
                Object[] r = (Object[]) x;
                String msg = (String) r[0];
                int[] itone = (int[]) r[1];
                double f1 = (Double) r[2];
                int ibest = (Integer) r[3];
                if (restarSi) restar(dd, itone, f1, ibest / 666.67);
                if (!vistos.add(msg)) continue;
                double snr = (Double) r[4];
                double xsnr = snr > 0 ? 10 * Math.log10(snr) - 14.8 : -21.0;
                int nsnr = nint(Math.max(-21.0, xsnr));
                res.add(new DescodificadorFt8.Resultado(nsnr, ibest / 666.67 - 0.5, f1, msg, itone, (Integer) r[5], (Double) r[6]));
            }
        }
        return res;
    }

    /** Intenta descodificar un candidato. Devuelve {mensaje, tonos, f1, ibest, snr, iaptype, calidad} o null. */
    private Object[] candidato(float[] dd, Cand c, DescodificadorFt8.Opciones o, boolean osdSi) {
        double f0 = c.f, snr = c.s - 1.0;
        int nfft2 = NMAX / NDOWN;
        double[] c2r = new double[nfft2], c2i = new double[nfft2];
        bajar(f0, c2r, c2i);
        double sum2 = 0;
        for (int i = 0; i < nfft2; i++) sum2 += c2r[i] * c2r[i] + c2i[i] * c2i[i];
        sum2 /= (double) NMAX / NDOWN;
        if (sum2 > 0) { double q = Math.sqrt(sum2); for (int i = 0; i < nfft2; i++) { c2r[i] /= q; c2i[i] /= q; } }
        double smax1 = 0;
        int nqp = Math.max(0, Math.min(5, o.progreso));
        for (int iseg = 1; iseg <= 3; iseg++) {
            int ibest = -1, idfbest = 0;
            double smax = -99;
            for (int isync = 1; isync <= 2; isync++) {
                int idfmin, idfmax, idfstp, ibmin, ibmax, ibstp;
                if (isync == 1) {
                    idfmin = -12; idfmax = 12; idfstp = 3; ibstp = 4;
                    if (iseg == 1) { ibmin = 108; ibmax = 560; }
                    else if (iseg == 2) { ibmin = 560; ibmax = 1012; }
                    else { ibmin = -344; ibmax = 108; }
                } else {
                    idfmin = idfbest - 4; idfmax = idfbest + 4; idfstp = 1;
                    ibmin = ibest - 5; ibmax = ibest + 5; ibstp = 1;
                }
                ibest = -1; idfbest = 0; smax = -99;
                for (int idf = idfmin; idf <= idfmax; idf += idfstp) {
                    if (idf < -16 || idf > 16) continue;
                    for (int is = ibmin; is <= ibmax; is += ibstp) {
                        double s = sync4d(c2r, c2i, is, idf);
                        if (s > smax) { smax = s; ibest = is; idfbest = idf; }
                    }
                }
            }
            if (iseg == 1) smax1 = smax;
            if (smax < 1.2) continue;
            if (iseg > 1 && smax < smax1) continue;
            double f1 = f0 + idfbest;
            if (f1 <= 10.0 || f1 >= 4990.0) continue;
            double[] cbr = new double[nfft2], cbi = new double[nfft2];
            bajar(f1, cbr, cbi);
            double s2 = 0;
            for (int i = 0; i < nfft2; i++) s2 += cbr[i] * cbr[i] + cbi[i] * cbi[i];
            s2 /= (double) NSS * NN;
            if (s2 > 0) { double q = Math.sqrt(s2); for (int i = 0; i < nfft2; i++) { cbr[i] /= q; cbi[i] /= q; } }
            double[] cdr = new double[NN * NSS], cdi = new double[NN * NSS];
            if (ibest >= 0) {
                int it = Math.min(NDMAX - 1, ibest + NN * NSS - 1);
                for (int i = ibest; i <= it; i++) { cdr[i - ibest] = cbr[i]; cdi[i - ibest] = cbi[i]; }
            } else {
                for (int t = 0; t <= NN * NSS + 2 * ibest - 1; t++) { cdr[-ibest + t] = cbr[t]; cdi[-ibest + t] = cbi[t]; }
            }
            double[][] bm = metricas(cdr, cdi);
            if (bm == null) continue;
            int[] hbits = new int[2 * NN + 1];
            for (int i = 1; i <= 2 * NN; i++) hbits[i] = bm[1][i] >= 0 ? 1 : 0;
            int nsq = 0;
            int[] ini = {1, 67, 133, 199};
            for (int a = 0; a < 4; a++) for (int q = 0; q < 8; q++) if (hbits[ini[a] + q] == SYNC_BITS[a][q]) nsq++;
            if (nsq < 20) continue;
            double sc = 2.83;
            double[][] llrs = new double[4][175];
            for (int q = 1; q <= 3; q++) {
                for (int i = 1; i <= 58; i++) {
                    llrs[q][i] = sc * bm[q][8 + i];
                    llrs[q][58 + i] = sc * bm[q][74 + i];
                    llrs[q][116 + i] = sc * bm[q][140 + i];
                }
            }
            double apmag = 0;
            for (int i = 1; i <= 174; i++) apmag = Math.max(apmag, Math.abs(llrs[1][i]));
            apmag *= 1.1;
            int npasses = 3 + (o.ayudaAp ? NAPPASSES[nqp] : 0);
            if (o.profundidad == 1) npasses = 3;
            int nharderror = -1;
            for (int ipass = 1; ipass <= npasses; ipass++) {
                double[] llr;
                int[] apmask = new int[175];
                int iaptype = 0;
                int napwid = 50;
                if (ipass <= 3) llr = llrs[ipass].clone();
                else {
                    llr = llrs[3].clone();
                    iaptype = NAPTYPES[nqp][ipass - 3];
                    if (iaptype == 0) continue;
                    if (iaptype >= 3 && Math.abs(f1 - o.frecRx) > napwid) continue;
                    if (iaptype >= 2 && apbits[1] > 1) continue;
                    if (iaptype >= 3 && apbits[30] > 1) continue;
                    if (iaptype == 1) { for (int i = 1; i <= 29; i++) { apmask[i] = 1; llr[i] = apmag * mcq[i]; } }
                    else if (iaptype == 2) { for (int i = 1; i <= 29; i++) { apmask[i] = 1; llr[i] = apmag * apbits[i]; } }
                    else if (iaptype == 3) { for (int i = 1; i <= 58; i++) { apmask[i] = 1; llr[i] = apmag * apbits[i]; } }
                    else { for (int i = 1; i <= 77; i++) apmask[i] = 1; if (iaptype == 6) for (int i = 1; i <= 77; i++) llr[i] = apmag * apbits[i]; }
                }
                int maxosd = 2, ndeep = 2;
                if (Math.abs(o.frecRx - f1) <= napwid) maxosd = 3;
                if (!osdSi) maxosd = -1;
                Ldpc174.Resultado r = Ldpc174.descodificar(llr, maxosd, ndeep, apmask);
                nharderror = r.nharderror;
                int suma = 0;
                for (int i = 1; i <= 77; i++) suma += r.cw[i];
                if (suma == 0) continue;
                if (r.nharderror >= 0) {
                    int[] m77 = new int[77];
                    for (int i = 0; i < 77; i++) m77[i] = (r.cw[i + 1] + RVEC[i]) & 1;
                    Ft8Mensaje.Texto t = mensajes.aTexto(m77);
                    if (!t.ok()) return null;
                    int[] itone = tonos(m77);
                    double qual = 1.0 - (r.nharderror + r.dmin) / 60.0;
                    return new Object[]{t.mensaje(), itone, f1, ibest, snr, iaptype, qual};
                }
            }
            if (nharderror >= 0) break;
        }
        return null;
    }
}
