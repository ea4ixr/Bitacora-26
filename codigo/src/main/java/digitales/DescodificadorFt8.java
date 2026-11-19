package digitales;

import java.util.ArrayList;
import java.util.List;

/**
 * Descodificador de FT8. Pasado a Java desde WSJT-X (ft8_decode, sync8, ft8b, ft8_downsample,
 * sync8d, subtractft8, get_spectrum_baseline...), de K1JT, K9AN, G4WJS y colaboradores (GPL).
 * Recibe 15 s de audio a 12000 muestras/s y devuelve los mensajes descodificados.
 */
public final class DescodificadorFt8 {
    // ft8_params
    static final int KK = 91, ND = 58, NS = 21, NN = 79, NSPS = 1920, NZ = NSPS * NN, NMAX = 15 * 12000;
    static final int NFFT1 = 2 * NSPS, NH1 = NFFT1 / 2, NSTEP = NSPS / 4, NHSYM = NMAX / NSTEP - 3, NDOWN = 60;
    static final int[] ICOS7 = {3, 1, 4, 0, 6, 5, 2};
    static final int[] GRAY = {0, 1, 3, 2, 5, 6, 4, 7};
    private static final int MAXCAND = 600;

    /** Un mensaje descodificado. */
    public record Resultado(int snr, double dt, double frecuencia, String mensaje, int[] tonos, int tipoAp, double calidad) { }

    /** Opciones: profundidad 1 (rápido), 2 (normal) o 3 (a fondo). */
    public static final class Opciones {
        public int fMin = 200, fMax = 3000, frecRx = 1500, frecTx = 1500, profundidad = 3;
        public String miIndicativo = "", suIndicativo = "";
        /** Situación del contacto como en WSJT-X: 0 llamando CQ, 1..5 mensajes Tx1..Tx5 (para la ayuda "AP"). */
        public int progreso = 0;
        public boolean ayudaAp = true;
        public int anchoAp = 25;
        /** Una copia para cambiar algo sin tocar las del usuario. */
        public Opciones copia() {
            Opciones c = new Opciones();
            c.fMin = fMin; c.fMax = fMax; c.frecRx = frecRx; c.frecTx = frecTx; c.profundidad = profundidad;
            c.miIndicativo = miIndicativo; c.suIndicativo = suIndicativo; c.progreso = progreso; c.ayudaAp = ayudaAp; c.anchoAp = anchoAp;
            return c;
        }
    }

    private final Ft8Mensaje mensajes;

    public DescodificadorFt8(Ft8Mensaje mensajes) { this.mensajes = mensajes; }

    // ------------------------------------------------------------------ utilidades
    private static int nint(double x) { return (int) Math.round(x); }

    /** Índices (1..n) que ordenan x[1..n] de menor a mayor, como indexx. */
    private static int[] indexx(double[] x, int desde, int n) {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i + 1;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(x[desde + a - 1], x[desde + b - 1]));
        int[] r = new int[n + 1];
        for (int i = 0; i < n; i++) r[i + 1] = idx[i];
        return r;
    }

    // ------------------------------------------------------------------ línea base del espectro
    private static double[] ventanaNuttal;

    private static synchronized double[] nuttal() {
        if (ventanaNuttal != null) return ventanaNuttal;
        double[] w = new double[NFFT1];
        double a0 = 0.3635819, a1 = -0.4891775, a2 = 0.1365995, a3 = -0.0106411, suma = 0;
        for (int i = 0; i < NFFT1; i++) {
            w[i] = a0 + a1 * Math.cos(2 * Math.PI * i / NFFT1) + a2 * Math.cos(4 * Math.PI * i / NFFT1) + a3 * Math.cos(6 * Math.PI * i / NFFT1);
            suma += w[i];
        }
        for (int i = 0; i < NFFT1; i++) w[i] = w[i] / suma * NSPS * 2 / 300.0;
        ventanaNuttal = w;
        return w;
    }

    /** sbase(1..NH1) en dB. */
    static double[] lineaBase(float[] dd, int nfa, int nfb) {
        double[] win = nuttal();
        int nst = NFFT1 / 2, nf = 93;
        double[] savg = new double[NH1 + 1];
        Fft f = Fft.de(NFFT1);
        double[] re = new double[NFFT1], im = new double[NFFT1];
        for (int j = 1; j <= nf; j++) {
            int ia = (j - 1) * nst;
            int ib = ia + NFFT1 - 1;
            if (ib > NMAX - 1) break;
            for (int i = 0; i < NFFT1; i++) { re[i] = dd[ia + i] * win[i]; im[i] = 0; }
            f.transformar(re, im, false);
            for (int i = 1; i <= NH1; i++) savg[i] += re[i] * re[i] + im[i] * im[i];
        }
        int nwin = nfb - nfa;
        if (nfa < 100) { nfa = 100; if (nwin < 100) nfb = nfa + nwin; }
        if (nfb > 4910) { nfb = 4910; if (nwin < 100) nfa = nfb - nwin; }
        return baseline(savg, nfa, nfb);
    }

    private static double[] baseline(double[] s, int nfa, int nfb) {
        double df = 12000.0 / 3840.0;
        int ia = Math.max(1, nint(nfa / df)), ib = nint(nfb / df);
        double[] sdb = s.clone();
        for (int i = ia; i <= ib; i++) sdb[i] = 10.0 * Math.log10(sdb[i]);
        int nseg = 10, npct = 10, nterms = 5;
        int nlen = (ib - ia + 1) / nseg, i0 = (ib - ia + 1) / 2;
        double[] x = new double[1001], y = new double[1001];
        int k = 0;
        for (int n = 1; n <= nseg; n++) {
            int ja = ia + (n - 1) * nlen, jb = ja + nlen - 1;
            double[] tmp = new double[nlen];
            for (int i = 0; i < nlen; i++) tmp[i] = sdb[ja + i];
            java.util.Arrays.sort(tmp);
            int j = nint(nlen * 0.01 * npct);
            if (j < 1) j = 1;
            if (j > nlen) j = nlen;
            double base = tmp[j - 1];
            for (int i = ja; i <= jb; i++) {
                if (sdb[i] <= base) {
                    if (k < 1000) k++;
                    x[k] = i - i0;
                    y[k] = sdb[i];
                }
            }
        }
        double[] a = ajustePolinomio(x, y, k, nterms);
        double[] sbase = new double[NH1 + 1];
        for (int i = ia; i <= ib; i++) {
            double t = i - i0;
            sbase[i] = a[0] + t * (a[1] + t * (a[2] + t * (a[3] + t * a[4]))) + 0.65;
        }
        return sbase;
    }

    /** Mínimos cuadrados de grado nterms-1 (como polyfit con mode=0). */
    private static double[] ajustePolinomio(double[] x, double[] y, int npts, int nterms) {
        int nmax = 2 * nterms - 1;
        double[] sumx = new double[nmax + 1], sumy = new double[nterms + 1];
        for (int i = 1; i <= npts; i++) {
            double xt = 1;
            for (int n = 1; n <= nmax; n++) { sumx[n] += xt; xt *= x[i]; }
            double yt = y[i];
            for (int n = 1; n <= nterms; n++) { sumy[n] += yt; yt *= x[i]; }
        }
        double[][] m = new double[nterms][nterms + 1];
        for (int j = 0; j < nterms; j++) {
            for (int c = 0; c < nterms; c++) m[j][c] = sumx[j + c + 1];
            m[j][nterms] = sumy[j + 1];
        }
        // eliminación de Gauss con pivote
        for (int c = 0; c < nterms; c++) {
            int p = c;
            for (int r = c + 1; r < nterms; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            double[] t = m[c]; m[c] = m[p]; m[p] = t;
            if (Math.abs(m[c][c]) < 1e-300) return new double[nterms];
            for (int r = 0; r < nterms; r++) {
                if (r == c) continue;
                double fct = m[r][c] / m[c][c];
                for (int q = c; q <= nterms; q++) m[r][q] -= fct * m[c][q];
            }
        }
        double[] a = new double[nterms];
        for (int c = 0; c < nterms; c++) a[c] = m[c][nterms] / m[c][c];
        return a;
    }

    // ------------------------------------------------------------------ búsqueda de candidatos (sync8)
    private static final class Candidatos {
        double[][] c;   // [1..n][1..3]: frecuencia, dt, sync
        int n;
        double[] sbase;
    }

    private Candidatos sync8(float[] dd, int nfa, int nfb, double syncmin, int nfqso, int maxcand) {
        int jz = 62;
        double[][] s = new double[NH1 + 1][NHSYM + 1];
        double tstep = NSTEP / 12000.0, df = 12000.0 / NFFT1, fac = 1.0 / 300.0;
        Fft f = Fft.de(NFFT1);
        double[] re = new double[NFFT1], im = new double[NFFT1];
        double smax = 0;
        for (int j = 1; j <= NHSYM; j++) {
            int ia = (j - 1) * NSTEP;
            for (int i = 0; i < NFFT1; i++) { re[i] = i < NSPS ? fac * dd[ia + i] : 0; im[i] = 0; }
            f.transformar(re, im, false);
            for (int i = 1; i <= NH1; i++) { s[i][j] = re[i] * re[i] + im[i] * im[i]; }
        }
        Candidatos out = new Candidatos();
        out.sbase = lineaBase(dd, nfa, nfb);
        int ia = Math.max(1, nint(nfa / df)), ib = nint(nfb / df);
        int nssy = NSPS / NSTEP, nfos = NFFT1 / NSPS;
        int jstrt = (int) (0.5 / tstep);
        double[][] sync2d = new double[NH1 + 1][2 * jz + 1];
        for (int i = ia; i <= ib; i++) {
            for (int j = -jz; j <= jz; j++) {
                double ta = 0, tb = 0, tc = 0, t0a = 0, t0b = 0, t0c = 0;
                for (int n = 0; n <= 6; n++) {
                    int m = j + jstrt + nssy * n;
                    if (m >= 1 && m <= NHSYM) {
                        ta += s[i + nfos * ICOS7[n]][m];
                        for (int q = 0; q <= 6; q++) t0a += s[i + nfos * q][m];
                    }
                    int mb = m + nssy * 36;
                    if (mb >= 1 && mb <= NHSYM) {
                        tb += s[i + nfos * ICOS7[n]][mb];
                        for (int q = 0; q <= 6; q++) t0b += s[i + nfos * q][mb];
                    }
                    int mc = m + nssy * 72;
                    if (mc >= 1 && mc <= NHSYM) {
                        tc += s[i + nfos * ICOS7[n]][mc];
                        for (int q = 0; q <= 6; q++) t0c += s[i + nfos * q][mc];
                    }
                }
                double t = ta + tb + tc, t0 = (t0a + t0b + t0c - t) / 6.0;
                double syncAbc = t / t0;
                t = tb + tc;
                t0 = (t0b + t0c - t) / 6.0;
                double syncBc = t / t0;
                sync2d[i][j + jz] = Math.max(syncAbc, syncBc);
            }
        }
        double[] red = new double[NH1 + 1], red2 = new double[NH1 + 1];
        int[] jpeak = new int[NH1 + 1], jpeak2 = new int[NH1 + 1];
        int mlag = 13, mlag2 = jz;
        for (int i = ia; i <= ib; i++) {
            double mx = -1e30; int jm = 0;
            for (int j = -mlag; j <= mlag; j++) if (sync2d[i][j + jz] > mx) { mx = sync2d[i][j + jz]; jm = j; }
            jpeak[i] = jm; red[i] = mx;
            mx = -1e30; jm = 0;
            for (int j = -mlag2; j <= mlag2; j++) if (sync2d[i][j + jz] > mx) { mx = sync2d[i][j + jz]; jm = j; }
            jpeak2[i] = jm; red2[i] = mx;
        }
        int iz = ib - ia + 1;
        int[] indx = indexx(red, ia, iz);
        int npctile = nint(0.40 * iz);
        if (npctile < 1) { out.c = new double[1][4]; out.n = 0; return out; }
        int ibase = Math.min(Math.max(indx[npctile] - 1 + ia, 1), NH1);
        double base = red[ibase];
        for (int i = 0; i <= NH1; i++) red[i] /= base;
        int[] indx2 = indexx(red2, ia, iz);
        int ibase2 = Math.min(Math.max(indx2[npctile] - 1 + ia, 1), NH1);
        double base2 = red2[ibase2];
        for (int i = 0; i <= NH1; i++) red2[i] /= base2;
        int maxpre = 1000;
        double[][] c0 = new double[maxpre + 1][4];
        int k = 0;
        for (int i = 1; i <= Math.min(maxpre, iz); i++) {
            int n = ia + indx[iz + 1 - i] - 1;
            if (k >= maxpre) break;
            if (red[n] >= syncmin && !Double.isNaN(red[n])) {
                k++;
                c0[k][1] = n * df; c0[k][2] = (jpeak[n] - 0.5) * tstep; c0[k][3] = red[n];
            }
            if (jpeak2[n] == jpeak[n]) continue;
            if (k >= maxpre) break;
            if (red2[n] >= syncmin && !Double.isNaN(red2[n])) {
                k++;
                c0[k][1] = n * df; c0[k][2] = (jpeak2[n] - 0.5) * tstep; c0[k][3] = red2[n];
            }
        }
        int ncand = k;
        for (int i = 2; i <= ncand; i++) {
            for (int j = 1; j <= i - 1; j++) {
                double fdiff = Math.abs(c0[i][1]) - Math.abs(c0[j][1]);
                double tdiff = Math.abs(c0[i][2] - c0[j][2]);
                if (Math.abs(fdiff) < 4.0 && tdiff < 0.04) {
                    if (c0[i][3] >= c0[j][3]) c0[j][3] = 0;
                    if (c0[i][3] < c0[j][3]) c0[i][3] = 0;
                }
            }
        }
        double[] syncs = new double[ncand + 1];
        for (int i = 1; i <= ncand; i++) syncs[i] = c0[i][3];
        int[] ix = ncand > 0 ? indexx(syncs, 1, ncand) : new int[1];
        double[][] cand = new double[maxcand + 2][4];
        k = 1;
        for (int i = 1; i <= ncand; i++) {
            if (Math.abs(c0[i][1] - nfqso) <= 10.0 && c0[i][3] >= syncmin) {
                cand[k][1] = c0[i][1]; cand[k][2] = c0[i][2]; cand[k][3] = c0[i][3];
                c0[i][3] = 0;
                k++;
                if (k > maxcand) break;
            }
        }
        for (int i = ncand; i >= 1 && k <= maxcand; i--) {
            int j = ix[i];
            if (c0[j][3] >= syncmin) {
                cand[k][2] = c0[j][2]; cand[k][3] = c0[j][3]; cand[k][1] = Math.abs(c0[j][1]);
                k++;
            }
        }
        out.c = cand;
        out.n = k - 1;
        return out;
    }

    // ------------------------------------------------------------------ bajar a 200 muestras/s (ft8_downsample)
    private static final int NFFT1D = 192000, NFFT2D = 3200;
    private double[] cxRe, cxIm;   // espectro del audio entero (se calcula una vez por pasada)
    private static double[] taper;

    private static synchronized double[] taper() {
        if (taper == null) {
            taper = new double[101];
            for (int i = 0; i <= 100; i++) taper[i] = 0.5 * (1.0 + Math.cos(i * Math.PI / 100));
        }
        return taper;
    }

    /** Espectro del audio entero (una vez por pasada, como newdat en WSJT-X). */
    private void prepararEspectro(float[] dd) {
        double[] re = new double[NFFT1D], im = new double[NFFT1D];
        for (int i = 0; i < NMAX; i++) re[i] = dd[i];
        Fft.de(NFFT1D).transformar(re, im, false);
        cxRe = re; cxIm = im;
    }

    /** Devuelve c1 (re, im) de 3200 muestras complejas centradas en f0. */
    private void bajar(float[] dd, boolean nuevo, double f0, double[] c1r, double[] c1i) {
        if (nuevo || cxRe == null) {
            double[] re = new double[NFFT1D], im = new double[NFFT1D];
            for (int i = 0; i < NMAX; i++) re[i] = dd[i];
            Fft.de(NFFT1D).transformar(re, im, false);
            cxRe = re; cxIm = im;
        }
        double[] tp = taper();
        double df = 12000.0 / NFFT1D, baud = 12000.0 / NSPS;
        int i0 = nint(f0 / df);
        double ft = f0 + 8.5 * baud;
        int it = Math.min(nint(ft / df), NFFT1D / 2);
        double fb = f0 - 1.5 * baud;
        int ib = Math.max(1, nint(fb / df));
        java.util.Arrays.fill(c1r, 0);
        java.util.Arrays.fill(c1i, 0);
        int k = 0;
        for (int i = ib; i <= it; i++) { c1r[k] = cxRe[i]; c1i[k] = cxIm[i]; k++; }
        for (int i = 0; i <= 100; i++) { c1r[i] *= tp[100 - i]; c1i[i] *= tp[100 - i]; }
        for (int i = 0; i <= 100; i++) { int q = k - 1 - 100 + i; if (q >= 0) { c1r[q] *= tp[i]; c1i[q] *= tp[i]; } }
        // cshift(c1, i0-ib): rotar a la izquierda
        int sh = ((i0 - ib) % NFFT2D + NFFT2D) % NFFT2D;
        double[] tr = new double[NFFT2D], ti = new double[NFFT2D];
        for (int i = 0; i < NFFT2D; i++) { tr[i] = c1r[(i + sh) % NFFT2D]; ti[i] = c1i[(i + sh) % NFFT2D]; }
        Fft.de(NFFT2D).transformar(tr, ti, true);
        double fac = 1.0 / Math.sqrt((double) NFFT1D * NFFT2D);
        for (int i = 0; i < NFFT2D; i++) { c1r[i] = tr[i] * fac; c1i[i] = ti[i] * fac; }
    }

    // ------------------------------------------------------------------ sync8d
    private static double[][] csyncR, csyncI;

    private static synchronized void prepararCsync() {
        if (csyncR != null) return;
        csyncR = new double[7][33];
        csyncI = new double[7][33];
        for (int i = 0; i <= 6; i++) {
            double phi = 0, dphi = 2 * Math.PI * ICOS7[i] / 32.0;
            for (int j = 1; j <= 32; j++) {
                csyncR[i][j] = Math.cos(phi); csyncI[i][j] = Math.sin(phi);
                phi = (phi + dphi) % (2 * Math.PI);
            }
        }
    }

    private static double sync8d(double[] cr, double[] ci, int i0, double[] twR, double[] twI, boolean tweak) {
        prepararCsync();
        int np2 = 2812;
        double sync = 0;
        double[] sR = new double[33], sI = new double[33];
        for (int i = 0; i <= 6; i++) {
            int i1 = i0 + i * 32, i2 = i1 + 36 * 32, i3 = i1 + 72 * 32;
            for (int j = 1; j <= 32; j++) {
                double r = csyncR[i][j], im = csyncI[i][j];
                if (tweak) { double nr = twR[j] * r - twI[j] * im, ni = twR[j] * im + twI[j] * r; r = nr; im = ni; }
                sR[j] = r; sI[j] = im;
            }
            for (int ii : new int[]{i1, i2, i3}) {
                if (ii >= 0 && ii + 31 <= np2 - 1) {
                    double zr = 0, zi = 0;
                    for (int j = 1; j <= 32; j++) {
                        double ar = cr[ii + j - 1], ai = ci[ii + j - 1];
                        // a * conj(s)
                        zr += ar * sR[j] + ai * sI[j];
                        zi += ai * sR[j] - ar * sI[j];
                    }
                    sync += zr * zr + zi * zi;
                }
            }
        }
        return sync;
    }

    private static void twkfreq1(double[] cr, double[] ci, int npts, double fsample, double a1) {
        double wr = 1, wi = 0;
        double dphi = a1 * (2 * Math.PI / fsample);
        double sr = Math.cos(dphi), si = Math.sin(dphi);
        for (int i = 0; i < npts; i++) {
            double nr = wr * sr - wi * si, ni = wr * si + wi * sr;
            wr = nr; wi = ni;
            double xr = cr[i], xi = ci[i];
            cr[i] = wr * xr - wi * xi;
            ci[i] = wr * xi + wi * xr;
        }
    }

    // ------------------------------------------------------------------ bits del mensaje -> tonos
    public static int[] tonos(int[] bits77) {
        int[] a = new int[12];
        for (int i = 0; i < 77; i++) if (bits77[i] != 0) a[i / 8] |= 0x80 >> (i % 8);
        int[] a91 = Ft8Codigo.conCrc(a);
        int[] cw = Ft8Codigo.codificar174(a91);
        int[] t = new int[NN];
        for (int i = 0; i < 7; i++) { t[i] = ICOS7[i]; t[36 + i] = ICOS7[i]; t[72 + i] = ICOS7[i]; }
        int k = 7;
        for (int j = 1; j <= ND; j++) {
            int i = 3 * j - 2;
            if (j == 30) k += 7;
            int b0 = (cw[(i - 1) / 8] >> (7 - (i - 1) % 8)) & 1;
            int b1 = (cw[i / 8] >> (7 - i % 8)) & 1;
            int b2 = (cw[(i + 1) / 8] >> (7 - (i + 1) % 8)) & 1;
            t[k] = GRAY[b0 * 4 + b1 * 2 + b2];
            k++;
        }
        return t;
    }

    // ------------------------------------------------------------------ restar una señal (subtractft8)
    private static final int NFILT = 4000;
    private static double[] cwR, cwI, endcorr;

    private static final int NFFT_RESTA = NMAX;   // 180000, como WSJT-X

    private static synchronized void prepararFiltro() {
        if (cwR != null) return;
        int nfft = NFFT_RESTA;
        double[] win = new double[NFILT + 1];
        double sumw = 0;
        for (int j = -NFILT / 2; j <= NFILT / 2; j++) { win[j + NFILT / 2] = Math.pow(Math.cos(Math.PI * j / NFILT), 2); sumw += win[j + NFILT / 2]; }
        double[] r = new double[nfft], im = new double[nfft];
        for (int i = 0; i <= NFILT; i++) r[i] = win[i] / sumw;
        // cshift(cw, NFILT/2+1) (Fortran, 1-based) = rotar a la izquierda NFILT/2+1 posiciones... sobre cw(1:nfft)
        double[] rr = new double[nfft];
        int sh = NFILT / 2 + 1;
        // cw(1:NFILT+1)=window -> índice 0..NFILT; cshift a la izquierda por sh
        for (int i = 0; i < nfft; i++) rr[i] = r[(i + sh) % nfft];
        Fft.de(nfft).transformar(rr, im, false);
        for (int i = 0; i < nfft; i++) { rr[i] /= nfft; im[i] /= nfft; }
        cwR = rr; cwI = im;
        endcorr = new double[NFILT / 2 + 2];
        for (int j = 1; j <= NFILT / 2 + 1; j++) {
            double s = 0;
            for (int q = j - 1; q <= NFILT / 2; q++) s += win[q + NFILT / 2];
            endcorr[j] = 1.0 / (1.0 - s / sumw);
        }
    }

    /** Onda compleja de referencia (gen_ft8wave, bt=2) a frecuencia f0. */
    static void ondaCompleja(int[] itone, double f0, double[] wr, double[] wi) {
        int nsym = NN, nsps = NSPS;
        double fs = 12000.0, dt = 1.0 / fs, bt = 2.0;
        double[] pulse = pulso(bt, nsps);
        double[] dphi = new double[(nsym + 2) * nsps];
        double dphiPeak = 2 * Math.PI / nsps;
        for (int j = 1; j <= nsym; j++) {
            int ib = (j - 1) * nsps;
            for (int i = 0; i < 3 * nsps; i++) dphi[ib + i] += dphiPeak * pulse[i] * itone[j - 1];
        }
        for (int i = 0; i < 2 * nsps; i++) dphi[i] += dphiPeak * itone[0] * pulse[nsps + i];
        for (int i = 0; i < 2 * nsps; i++) dphi[nsym * nsps + i] += dphiPeak * itone[nsym - 1] * pulse[i];
        double phi = 0;
        for (int i = 0; i < dphi.length; i++) dphi[i] += 2 * Math.PI * f0 * dt;
        int nwave = nsym * nsps;
        for (int k = 0; k < nwave; k++) {
            wr[k] = Math.cos(phi); wi[k] = Math.sin(phi);
            phi = (phi + dphi[nsps + k]) % (2 * Math.PI);
        }
        int nramp = nint(nsps / 8.0);
        for (int i = 0; i < nramp; i++) {
            double g = (1.0 - Math.cos(2 * Math.PI * i / (2.0 * nramp))) / 2.0;
            wr[i] *= g; wi[i] *= g;
            int k1 = nsym * nsps - nramp + i;
            double g2 = (1.0 + Math.cos(2 * Math.PI * i / (2.0 * nramp))) / 2.0;
            wr[k1] *= g2; wi[k1] *= g2;
        }
    }

    private static final java.util.Map<String, double[]> PULSOS = new java.util.concurrent.ConcurrentHashMap<>();

    static double[] pulso(double bt, int nsps) {
        return PULSOS.computeIfAbsent(bt + ":" + nsps, k -> {
            double[] p = new double[3 * nsps];
            for (int i = 1; i <= 3 * nsps; i++) {
                double tt = (i - 1.5 * nsps) / nsps;
                p[i - 1] = gfsk(bt, tt);
            }
            return p;
        });
    }

    static double gfsk(double b, double t) {
        double c = Math.PI * Math.sqrt(2.0 / Math.log(2.0));
        return 0.5 * (erf(c * b * (t + 0.5)) - erf(c * b * (t - 0.5)));
    }

    /** Función de error (precisión de 1e-7, suficiente para la forma del pulso). */
    static double erf(double x) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double y = 1 - t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? y : -y;
    }

    private static void restar(float[] dd, int[] itone, double f0, double dt) {
        prepararFiltro();
        int nfft = NFFT_RESTA, nframe = NZ;
        double[] rr = new double[nframe], ri = new double[nframe];
        ondaCompleja(itone, f0, rr, ri);
        int nstart = (int) (dt * 12000) + 1;   // Fortran 1-based
        double[] fr = new double[nfft], fi = new double[nfft];
        for (int i = 1; i <= nframe; i++) {
            int j = nstart - 1 + i;
            if (j >= 1 && j <= NMAX) {
                double d = dd[j - 1];
                fr[i - 1] = d * rr[i - 1];
                fi[i - 1] = -d * ri[i - 1];
            }
        }
        Fft f = Fft.de(nfft);
        f.transformar(fr, fi, false);
        for (int i = 0; i < nfft; i++) {
            double a = fr[i] * cwR[i] - fi[i] * cwI[i], b = fr[i] * cwI[i] + fi[i] * cwR[i];
            fr[i] = a; fi[i] = b;
        }
        f.transformar(fr, fi, true);
        for (int j = 1; j <= NFILT / 2 + 1; j++) { fr[j - 1] *= endcorr[j]; fi[j - 1] *= endcorr[j]; }
        for (int q = 0; q <= NFILT / 2; q++) {
            int idx = nframe - q;   // cfilt(nframe:nframe-NFILT/2:-1) * endcorrection(1..)
            fr[idx - 1] *= endcorr[q + 1]; fi[idx - 1] *= endcorr[q + 1];
        }
        for (int i = 1; i <= nframe; i++) {
            int j = nstart + i - 1;
            if (j >= 1 && j <= NMAX) {
                double zr = fr[i - 1] * rr[i - 1] - fi[i - 1] * ri[i - 1];
                dd[j - 1] -= (float) (2.0 * zr);
            }
        }
    }

    // ------------------------------------------------------------------ símbolos AP (ft8apset)
    private int[] apsym = new int[59];   // 1..58
    private int[] mcq, mrrr, m73, mrr73;

    private void prepararAp(Opciones o) {
        if (mcq == null) {
            mcq = conv(new int[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,0});
            mrrr = conv(new int[]{0,1,1,1,1,1,1,0,1,0,0,1,0,0,1,0,0,0,1});
            m73 = conv(new int[]{0,1,1,1,1,1,1,0,1,0,0,1,0,1,0,0,0,0,1});
            mrr73 = conv(new int[]{0,1,1,1,1,1,1,0,0,1,1,1,0,1,0,1,0,0,1});
        }
        apsym = new int[59];
        apsym[1] = 99; apsym[30] = 99;
        String mio = o.miIndicativo == null ? "" : o.miIndicativo.trim().toUpperCase();
        if (mio.length() < 3) return;
        String suyo = o.suIndicativo == null ? "" : o.suIndicativo.trim().toUpperCase();
        boolean sinSuyo = suyo.length() < 3;
        if (sinSuyo) suyo = "KA1ABC";
        int[] b = mensajes.aBits(mio + " " + suyo + " RRR");
        if (b == null || ((b[74] << 2) | (b[75] << 1) | b[76]) != 1) return;
        Ft8Mensaje.Texto t = new Ft8Mensaje().aTexto(b);
        if (!t.ok() || !t.mensaje().equals(mio + " " + suyo + " RRR")) return;
        for (int i = 1; i <= 58; i++) apsym[i] = 2 * b[i - 1] - 1;
        if (sinSuyo) apsym[30] = 99;
    }

    private static int[] conv(int[] v) {
        int[] r = new int[v.length + 1];
        for (int i = 0; i < v.length; i++) r[i + 1] = 2 * v[i] - 1;
        return r;
    }

    private static final int[] NAPPASSES = {2, 2, 2, 4, 4, 3};
    private static final int[][] NAPTYPES = {{0, 1, 2, 0, 0}, {0, 2, 3, 0, 0}, {0, 2, 3, 0, 0}, {0, 3, 4, 5, 6}, {0, 3, 4, 5, 6}, {0, 3, 1, 2, 0}};

    // ------------------------------------------------------------------ ft8b: descodificar un candidato
    private static final class Salida {
        boolean bien;
        String msg;
        int[] itone;
        double xsnr, xdt, f1;
        int iaptype, nharderrors;
        double dmin;
    }

    private Salida ft8b(float[] dd0, boolean[] nuevo, Opciones o, double f1, double xdt, double xbase, int imetric, boolean lsubtract) {
        Salida sal = new Salida();
        double fs2 = 12000.0 / NDOWN, dt2 = 1.0 / fs2;
        int np2 = 2812;
        double[] cr = new double[NFFT2D], ci = new double[NFFT2D];
        bajar(dd0, false, f1, cr, ci);
        int i0 = nint((xdt + 0.5) * fs2);
        double smax = 0;
        int ibest = 0;
        for (int idt = i0 - 10; idt <= i0 + 10; idt++) {
            double sy = sync8d(cr, ci, idt, null, null, false);
            if (sy > smax) { smax = sy; ibest = idt; }
        }
        smax = 0;
        double delfbest = 0;
        double[] twR = new double[33], twI = new double[33];
        for (int ifr = -5; ifr <= 5; ifr++) {
            double delf = ifr * 0.5, dphi = 2 * Math.PI * delf * dt2, phi = 0;
            for (int i = 1; i <= 32; i++) { twR[i] = Math.cos(phi); twI[i] = Math.sin(phi); phi = (phi + dphi) % (2 * Math.PI); }
            double sy = sync8d(cr, ci, ibest, twR, twI, true);
            if (sy > smax) { smax = sy; delfbest = delf; }
        }
        f1 = f1 + delfbest;
        bajar(dd0, false, f1, cr, ci);
        double[] ss = new double[10];
        for (int idt = -4; idt <= 4; idt++) ss[idt + 5] = sync8d(cr, ci, ibest + idt, null, null, false);
        smax = -1; int iloc = 1;
        for (int q = 1; q <= 9; q++) if (ss[q] > smax) { smax = ss[q]; iloc = q; }
        ibest = iloc - 5 + ibest;
        xdt = (ibest - 1) * dt2;
        // espectros de los 79 símbolos
        double[][] csR = new double[8][NN + 1], csI = new double[8][NN + 1], s8 = new double[8][NN + 1];
        Fft f32 = Fft.de(32);
        double[] br = new double[32], bi = new double[32];
        for (int k = 1; k <= NN; k++) {
            int i1 = ibest + (k - 1) * 32;
            java.util.Arrays.fill(br, 0); java.util.Arrays.fill(bi, 0);
            if (i1 >= 0 && i1 + 31 <= np2 - 1) for (int q = 0; q < 32; q++) { br[q] = cr[i1 + q]; bi[q] = ci[i1 + q]; }
            f32.transformar(br, bi, false);
            for (int q = 0; q <= 7; q++) {
                csR[q][k] = br[q] / 1e3; csI[q][k] = bi[q] / 1e3;
                s8[q][k] = Math.hypot(br[q], bi[q]);
            }
        }
        int nsync = 0;
        for (int k = 1; k <= 7; k++) {
            for (int off : new int[]{0, 36, 72}) {
                int ip = 0; double mx = -1;
                for (int q = 0; q <= 7; q++) if (s8[q][k + off] > mx) { mx = s8[q][k + off]; ip = q; }
                if (ICOS7[k - 1] == ip) nsync++;
            }
        }
        int syncmin = 6;
        if (imetric == 2) syncmin = 7;
        if (o.profundidad <= 2) syncmin = 8;
        if (nsync <= syncmin) return sal;
        double[] bmeta = new double[175], bmetb = new double[175], bmetc = new double[175], bmetd = new double[175], bmete = new double[175];
        double[] s2 = new double[512];
        for (int nsym = 1; nsym <= 3; nsym++) {
            int nt = 1 << (3 * nsym);
            for (int ihalf = 1; ihalf <= 2; ihalf++) {
                for (int k = 1; k <= 29; k += nsym) {
                    int ks = ihalf == 1 ? k + 7 : k + 43;
                    for (int i = 0; i < nt; i++) {
                        int i1 = i / 64, i2 = (i & 63) / 8, i3 = i & 7;
                        double zr, zi;
                        if (nsym == 1) { zr = csR[GRAY[i3]][ks]; zi = csI[GRAY[i3]][ks]; }
                        else if (nsym == 2) { zr = csR[GRAY[i2]][ks] + csR[GRAY[i3]][ks + 1]; zi = csI[GRAY[i2]][ks] + csI[GRAY[i3]][ks + 1]; }
                        else {
                            zr = csR[GRAY[i1]][ks] + csR[GRAY[i2]][ks + 1] + csR[GRAY[i3]][ks + 2];
                            zi = csI[GRAY[i1]][ks] + csI[GRAY[i2]][ks + 1] + csI[GRAY[i3]][ks + 2];
                        }
                        s2[i] = Math.hypot(zr, zi);
                        if (imetric == 2) s2[i] *= s2[i];
                    }
                    int i32 = 1 + (k - 1) * 3 + (ihalf - 1) * 87;
                    int ibmax = nsym == 1 ? 2 : nsym == 2 ? 5 : 8;
                    for (int ib = 0; ib <= ibmax; ib++) {
                        int bit = ibmax - ib;
                        double m1 = -1e30, m0 = -1e30;
                        for (int i = 0; i < nt; i++) {
                            if (((i >> bit) & 1) != 0) { if (s2[i] > m1) m1 = s2[i]; } else { if (s2[i] > m0) m0 = s2[i]; }
                        }
                        double bm = m1 - m0;
                        if (i32 + ib > 174) continue;
                        if (nsym == 1) {
                            bmeta[i32 + ib] = bm;
                            double den = Math.max(m1, m0);
                            bmetd[i32 + ib] = den > 0 ? bm / den : 0;
                        } else if (nsym == 2) bmetb[i32 + ib] = bm;
                        else bmetc[i32 + ib] = bm;
                    }
                }
            }
        }
        for (int i = 1; i <= 174; i++) {
            double a = bmeta[i], b = bmetb[i], c = bmetc[i];
            double m = a;
            if (Math.abs(b) > Math.abs(m)) m = b;
            if (Math.abs(c) > Math.abs(m)) m = c;
            bmete[i] = m;
        }
        normalizar(bmeta); normalizar(bmetb); normalizar(bmetc); normalizar(bmetd); normalizar(bmete);
        double sc = 2.83;
        double[][] llrs = new double[6][175];
        for (int i = 1; i <= 174; i++) {
            llrs[1][i] = sc * bmeta[i]; llrs[2][i] = sc * bmetb[i]; llrs[3][i] = sc * bmetc[i];
            llrs[4][i] = sc * bmetd[i]; llrs[5][i] = sc * bmete[i];
        }
        boolean lapon = o.ayudaAp;
        int nqp = Math.max(0, Math.min(5, o.progreso));
        int npasses = lapon ? 5 + 2 * NAPPASSES[nqp] : 5;
        int[] apmask = new int[175];
        for (int ipass = 1; ipass <= npasses; ipass++) {
            double[] llrz;
            int iaptype = 0;
            java.util.Arrays.fill(apmask, 0);
            if (ipass <= 5) llrz = llrs[ipass].clone();
            else {
                llrz = ((ipass - 5) % 2 == 1) ? llrs[1].clone() : llrs[3].clone();
                double apmag = 0;
                for (int i = 1; i <= 174; i++) apmag = Math.max(apmag, Math.abs(llrz[i]));
                apmag *= 1.1;
                iaptype = NAPTYPES[nqp][(ipass - 4) / 2];
                if (iaptype == 0) continue;
                if (iaptype >= 3 && Math.abs(f1 - o.frecRx) > o.anchoAp && Math.abs(f1 - o.frecTx) > o.anchoAp) continue;
                if (iaptype >= 2 && apsym[1] > 1) continue;
                if (iaptype >= 3 && apsym[30] > 1) continue;
                if (iaptype == 1) {
                    for (int i = 1; i <= 29; i++) { apmask[i] = 1; llrz[i] = apmag * mcq[i]; }
                    apmask[75] = apmask[76] = apmask[77] = 1;
                    llrz[75] = -apmag; llrz[76] = -apmag; llrz[77] = apmag;
                } else if (iaptype == 2) {
                    for (int i = 1; i <= 29; i++) { apmask[i] = 1; llrz[i] = apmag * apsym[i]; }
                    apmask[75] = apmask[76] = apmask[77] = 1;
                    llrz[75] = -apmag; llrz[76] = -apmag; llrz[77] = apmag;
                } else if (iaptype == 3) {
                    for (int i = 1; i <= 58; i++) { apmask[i] = 1; llrz[i] = apmag * apsym[i]; }
                    apmask[75] = apmask[76] = apmask[77] = 1;
                    llrz[75] = -apmag; llrz[76] = -apmag; llrz[77] = apmag;
                } else {
                    for (int i = 1; i <= 77; i++) apmask[i] = 1;
                    for (int i = 1; i <= 58; i++) llrz[i] = apmag * apsym[i];
                    int[] m = iaptype == 4 ? mrrr : iaptype == 5 ? m73 : mrr73;
                    for (int i = 59; i <= 77; i++) llrz[i] = apmag * m[i - 58];
                }
            }
            int maxosd = 2;
            if (o.profundidad == 1) maxosd = -1;
            Ldpc174.Resultado r = Ldpc174.descodificar(llrz, maxosd, 2, apmask);
            if (r.nharderror < 0 || r.nharderror > 36) continue;
            boolean todoCero = true;
            for (int i = 1; i <= 174; i++) if (r.cw[i] != 0) { todoCero = false; break; }
            if (todoCero) continue;
            int[] m77 = new int[77];
            for (int i = 0; i < 77; i++) m77[i] = r.cw[i + 1];
            int n3 = (m77[71] << 2) | (m77[72] << 1) | m77[73];
            int i3 = (m77[74] << 2) | (m77[75] << 1) | m77[76];
            if (i3 > 5 || (i3 == 0 && n3 > 6)) continue;
            if (i3 == 0 && n3 == 2) continue;
            Ft8Mensaje.Texto t = mensajes.aTexto(m77);
            if (!t.ok() || t.mensaje().contains("/R") || t.mensaje().startsWith("TU; ")) {
                if (i3 >= 1 && i3 <= 3) continue;   // sin modo concurso
            }
            if (!t.ok()) continue;
            int[] itone = tonos(m77);
            double xsig = 0, xnoi = 0;
            for (int i = 1; i <= 79; i++) {
                xsig += s8[itone[i - 1]][i] * s8[itone[i - 1]][i];
                int ios = (itone[i - 1] + 4) % 7;
                xnoi += s8[ios][i] * s8[ios][i];
            }
            double xsnr2 = 0.001;
            double arg = xsig / xbase / 3.0e6 - 1.0;
            if (arg > 0.1) xsnr2 = arg;
            double xsnr = 10.0 * Math.log10(xsnr2) - 27.0;
            if (nsync <= 10 && xsnr < -25.0) return sal;
            if (xsnr < -25.0) xsnr = -25.0;
            sal.bien = true;
            sal.msg = t.mensaje();
            sal.itone = itone;
            sal.xsnr = xsnr;
            sal.xdt = xdt;
            sal.f1 = f1;
            sal.iaptype = iaptype;
            sal.nharderrors = r.nharderror;
            sal.dmin = r.dmin;
            return sal;
        }
        return sal;
    }

    private static void normalizar(double[] b) {
        int n = 174;
        double s = 0, s2 = 0;
        for (int i = 1; i <= n; i++) { s += b[i]; s2 += b[i] * b[i]; }
        double av = s / n, av2 = s2 / n, var = av2 - av * av;
        double sig = var > 0 ? Math.sqrt(var) : Math.sqrt(av2);
        if (sig == 0) return;
        for (int i = 1; i <= n; i++) b[i] /= sig;
    }

    // ------------------------------------------------------------------ el bucle principal (ft8_decode)
    /**
     * Descodifica 15 s de audio (12000 muestras/s, con la amplitud de 16 bits: -32768..32767).
     * El audio debe empezar en el segundo 0 (o 15, 30, 45) del minuto.
     */
    public List<Resultado> descodificar(float[] audio, Opciones o) {
        float[] dd = new float[NMAX];
        System.arraycopy(audio, 0, dd, 0, Math.min(audio.length, NMAX));
        mensajes.ponerIndicativos(o.miIndicativo, o.suIndicativo);
        prepararAp(o);
        List<Resultado> res = new ArrayList<>();
        java.util.Set<String> vistos = new java.util.HashSet<>();
        int npass = o.profundidad == 1 ? 2 : 3;
        for (int ipass = 1; ipass <= npass; ipass++) {
            double syncmin = o.profundidad <= 2 ? 2.1 : 1.3;
            int imetric = ipass == 1 ? 1 : 2;
            if (ipass == 3 && res.isEmpty()) continue;
            Candidatos c = sync8(dd, o.fMin, o.fMax, syncmin, o.frecRx, MAXCAND);
            prepararEspectro(dd);
            final int im = imetric;
            final float[] ddPasada = dd;
            // Los candidatos de una pasada se pueden descodificar a la vez (todos usan el mismo espectro);
            // las señales se restan después, en el mismo orden que WSJT-X.
            Salida[] sal = new Salida[c.n + 1];
            java.util.stream.IntStream.rangeClosed(1, c.n).parallel().forEach(ic -> {
                double f1 = c.c[ic][1], xdt = c.c[ic][2];
                int ib = Math.max(1, Math.min(NH1, nint(f1 / 3.125)));
                double xbase = Math.pow(10.0, 0.1 * (c.sbase[ib] - 40.0));
                sal[ic] = ft8b(ddPasada, null, o, f1, xdt, xbase, im, false);
            });
            for (int ic = 1; ic <= c.n; ic++) {
                Salida s = sal[ic];
                if (s == null || !s.bien) continue;
                restar(dd, s.itone, s.f1, s.xdt);
                if (vistos.add(s.msg)) {
                    double qual = 1.0 - (s.nharderrors + s.dmin) / 60.0;
                    res.add(new Resultado((int) Math.round(s.xsnr), s.xdt - 0.5, s.f1, s.msg, s.itone, s.iaptype, qual));
                }
            }
        }
        return res;
    }
}
