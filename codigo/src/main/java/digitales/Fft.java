package digitales;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Transformada rápida de Fourier (FFT) compleja para tamaños con factores 2, 3, 4 y 5
 * (los que usan FT8 y FT4: 32, 3200, 3840, 180000, 192000...). Método de Stockham, que recorre
 * la memoria en orden (rápido incluso con tamaños grandes).
 * Igual que FFTW sin normalizar: directa = exp(-i·2π·k·n/N), inversa = exp(+i·2π·k·n/N).
 */
public final class Fft {
    private final int n;
    private final int[] radices;
    private final double[] cosT, senT;   // exp(-i 2π k / n)
    private final ThreadLocal<double[][]> trabajo;

    private static final ConcurrentHashMap<Integer, Fft> PLANES = new ConcurrentHashMap<>();

    /** FFT preparada para tamaño n (se guarda para reutilizarla). */
    public static Fft de(int n) { return PLANES.computeIfAbsent(n, Fft::new); }

    private Fft(int n) {
        this.n = n;
        java.util.List<Integer> f = new java.util.ArrayList<>();
        int r = n;
        while (r % 4 == 0) { f.add(4); r /= 4; }
        while (r % 2 == 0) { f.add(2); r /= 2; }
        while (r % 3 == 0) { f.add(3); r /= 3; }
        while (r % 5 == 0) { f.add(5); r /= 5; }
        while (r % 7 == 0) { f.add(7); r /= 7; }
        if (r > 1) f.add(r);   // factor primo grande (DFT directa: lento, pero no se usa en FT8/FT4)
        radices = f.stream().mapToInt(Integer::intValue).toArray();
        cosT = new double[n];
        senT = new double[n];
        for (int k = 0; k < n; k++) {
            double a = -2 * Math.PI * k / n;
            cosT[k] = Math.cos(a);
            senT[k] = Math.sin(a);
        }
        trabajo = ThreadLocal.withInitial(() -> new double[][]{new double[n], new double[n]});
    }

    public int tam() { return n; }

    /** Transforma re/im en el sitio. inversa=false: exp(-i...); inversa=true: exp(+i...). Sin normalizar. */
    public void transformar(double[] re, double[] im, boolean inversa) {
        if (n == 1) return;
        double[][] w = trabajo.get();
        double[] xr = re, xi = im, yr = w[0], yi = w[1];
        double sg = inversa ? -1 : 1;
        int ns = 1;
        for (int R : radices) {
            int nr = n / R, bloques = nr / ns, paso = n / (ns * R);
            switch (R) {
                case 4 -> etapa4(xr, xi, yr, yi, ns, nr, bloques, paso, sg);
                case 2 -> etapa2(xr, xi, yr, yi, ns, nr, bloques, paso, sg);
                case 3 -> etapa3(xr, xi, yr, yi, ns, nr, bloques, paso, sg);
                case 5 -> etapa5(xr, xi, yr, yi, ns, nr, bloques, paso, sg);
                default -> etapaGeneral(xr, xi, yr, yi, R, ns, nr, paso, sg);
            }
            double[] t = xr; xr = yr; yr = t;
            t = xi; xi = yi; yi = t;
            ns *= R;
        }
        if (xr != re) {
            System.arraycopy(xr, 0, re, 0, n);
            System.arraycopy(xi, 0, im, 0, n);
        }
    }

    private void etapa2(double[] xr, double[] xi, double[] yr, double[] yi, int ns, int nr, int bloques, int paso, double sg) {
        for (int jm = 0; jm < ns; jm++) {
            int t = jm * paso;
            double c = cosT[t], s = sg * senT[t];
            for (int b = 0; b < bloques; b++) {
                int j = b * ns + jm, d = b * ns * 2 + jm;
                double ar = xr[j + nr], ai = xi[j + nr];
                double br = ar * c - ai * s, bi = ar * s + ai * c;
                double x0r = xr[j], x0i = xi[j];
                yr[d] = x0r + br; yi[d] = x0i + bi;
                yr[d + ns] = x0r - br; yi[d + ns] = x0i - bi;
            }
        }
    }

    private void etapa4(double[] xr, double[] xi, double[] yr, double[] yi, int ns, int nr, int bloques, int paso, double sg) {
        for (int jm = 0; jm < ns; jm++) {
            int t1 = jm * paso, t2 = 2 * t1, t3 = 3 * t1;
            double c1 = cosT[t1], s1 = sg * senT[t1], c2 = cosT[t2], s2 = sg * senT[t2], c3 = cosT[t3], s3 = sg * senT[t3];
            for (int b = 0; b < bloques; b++) {
                int j = b * ns + jm, d = b * ns * 4 + jm;
                double v0r = xr[j], v0i = xi[j];
                double ar = xr[j + nr], ai = xi[j + nr];
                double v1r = ar * c1 - ai * s1, v1i = ar * s1 + ai * c1;
                ar = xr[j + 2 * nr]; ai = xi[j + 2 * nr];
                double v2r = ar * c2 - ai * s2, v2i = ar * s2 + ai * c2;
                ar = xr[j + 3 * nr]; ai = xi[j + 3 * nr];
                double v3r = ar * c3 - ai * s3, v3i = ar * s3 + ai * c3;
                double s0r = v0r + v2r, s0i = v0i + v2i, d0r = v0r - v2r, d0i = v0i - v2i;
                double s2r = v1r + v3r, s2i = v1i + v3i, d2r = v1r - v3r, d2i = v1i - v3i;
                yr[d] = s0r + s2r; yi[d] = s0i + s2i;
                yr[d + 2 * ns] = s0r - s2r; yi[d + 2 * ns] = s0i - s2i;
                yr[d + ns] = d0r + sg * d2i; yi[d + ns] = d0i - sg * d2r;
                yr[d + 3 * ns] = d0r - sg * d2i; yi[d + 3 * ns] = d0i + sg * d2r;
            }
        }
    }

    private void etapa3(double[] xr, double[] xi, double[] yr, double[] yi, int ns, int nr, int bloques, int paso, double sg) {
        double k = sg * S3;
        for (int jm = 0; jm < ns; jm++) {
            int t1 = jm * paso, t2 = 2 * t1;
            double c1 = cosT[t1], s1 = sg * senT[t1], c2 = cosT[t2], s2 = sg * senT[t2];
            for (int b = 0; b < bloques; b++) {
                int j = b * ns + jm, d = b * ns * 3 + jm;
                double v0r = xr[j], v0i = xi[j];
                double ar = xr[j + nr], ai = xi[j + nr];
                double v1r = ar * c1 - ai * s1, v1i = ar * s1 + ai * c1;
                ar = xr[j + 2 * nr]; ai = xi[j + 2 * nr];
                double v2r = ar * c2 - ai * s2, v2i = ar * s2 + ai * c2;
                double sr = v1r + v2r, si = v1i + v2i, dr = v1r - v2r, di = v1i - v2i;
                double mr = v0r - 0.5 * sr, mi = v0i - 0.5 * si;
                yr[d] = v0r + sr; yi[d] = v0i + si;
                yr[d + ns] = mr + k * di; yi[d + ns] = mi - k * dr;
                yr[d + 2 * ns] = mr - k * di; yi[d + 2 * ns] = mi + k * dr;
            }
        }
    }

    private void etapa5(double[] xr, double[] xi, double[] yr, double[] yi, int ns, int nr, int bloques, int paso, double sg) {
        for (int jm = 0; jm < ns; jm++) {
            int t1 = jm * paso;
            double c1 = cosT[t1], s1 = sg * senT[t1], c2 = cosT[2 * t1], s2 = sg * senT[2 * t1];
            double c3 = cosT[3 * t1], s3 = sg * senT[3 * t1], c4 = cosT[4 * t1], s4 = sg * senT[4 * t1];
            for (int b = 0; b < bloques; b++) {
                int j = b * ns + jm, d = b * ns * 5 + jm;
                double x0r = xr[j], x0i = xi[j];
                double ar = xr[j + nr], ai = xi[j + nr];
                double v1r = ar * c1 - ai * s1, v1i = ar * s1 + ai * c1;
                ar = xr[j + 2 * nr]; ai = xi[j + 2 * nr];
                double v2r = ar * c2 - ai * s2, v2i = ar * s2 + ai * c2;
                ar = xr[j + 3 * nr]; ai = xi[j + 3 * nr];
                double v3r = ar * c3 - ai * s3, v3i = ar * s3 + ai * c3;
                ar = xr[j + 4 * nr]; ai = xi[j + 4 * nr];
                double v4r = ar * c4 - ai * s4, v4i = ar * s4 + ai * c4;
                double s14r = v1r + v4r, s14i = v1i + v4i, d14r = v1r - v4r, d14i = v1i - v4i;
                double s23r = v2r + v3r, s23i = v2i + v3i, d23r = v2r - v3r, d23i = v2i - v3i;
                double pr = x0r + C5A * s14r + C5B * s23r, pi = x0i + C5A * s14i + C5B * s23i;
                double qr = x0r + C5B * s14r + C5A * s23r, qi = x0i + C5B * s14i + C5A * s23i;
                double tr = sg * (S5A * d14i + S5B * d23i), ti = -sg * (S5A * d14r + S5B * d23r);
                double ur = sg * (S5B * d14i - S5A * d23i), ui = -sg * (S5B * d14r - S5A * d23r);
                yr[d] = x0r + s14r + s23r; yi[d] = x0i + s14i + s23i;
                yr[d + ns] = pr + tr; yi[d + ns] = pi + ti;
                yr[d + 4 * ns] = pr - tr; yi[d + 4 * ns] = pi - ti;
                yr[d + 2 * ns] = qr + ur; yi[d + 2 * ns] = qi + ui;
                yr[d + 3 * ns] = qr - ur; yi[d + 3 * ns] = qi - ui;
            }
        }
    }

    private void etapaGeneral(double[] xr, double[] xi, double[] yr, double[] yi, int R, int ns, int nr, int paso, double sg) {
        double[] vr = new double[R], vi = new double[R];
        for (int j = 0; j < nr; j++) {
            int jm = j % ns, base = jm * paso;
            for (int q = 0; q < R; q++) {
                double ar = xr[j + q * nr], ai = xi[j + q * nr];
                int t = (int) (((long) q * base) % n);
                double c = cosT[t], s = sg * senT[t];
                vr[q] = ar * c - ai * s; vi[q] = ar * s + ai * c;
            }
            dft(vr, vi, R, sg);
            int d = (j / ns) * ns * R + jm;
            for (int q = 0; q < R; q++) { yr[d + q * ns] = vr[q]; yi[d + q * ns] = vi[q]; }
        }
    }

    private static final double C5A = Math.cos(2 * Math.PI / 5), C5B = Math.cos(4 * Math.PI / 5);
    private static final double S5A = Math.sin(2 * Math.PI / 5), S5B = Math.sin(4 * Math.PI / 5);
    private static final double S3 = Math.sin(2 * Math.PI / 3);

    /** DFT pequeña en el sitio: X[k] = Σ v[q]·exp(-sg·i·2π·q·k/R). */
    private void dft(double[] vr, double[] vi, int R, double sg) {
        switch (R) {
            case 2 -> {
                double ar = vr[0], ai = vi[0];
                vr[0] = ar + vr[1]; vi[0] = ai + vi[1];
                vr[1] = ar - vr[1]; vi[1] = ai - vi[1];
            }
            case 4 -> {
                double s0r = vr[0] + vr[2], s0i = vi[0] + vi[2], s1r = vr[0] - vr[2], s1i = vi[0] - vi[2];
                double s2r = vr[1] + vr[3], s2i = vi[1] + vi[3], s3r = vr[1] - vr[3], s3i = vi[1] - vi[3];
                vr[0] = s0r + s2r; vi[0] = s0i + s2i;
                vr[2] = s0r - s2r; vi[2] = s0i - s2i;
                // X1 = s1 - sg·i·s3 ; X3 = s1 + sg·i·s3
                vr[1] = s1r + sg * s3i; vi[1] = s1i - sg * s3r;
                vr[3] = s1r - sg * s3i; vi[3] = s1i + sg * s3r;
            }
            case 3 -> {
                double sr = vr[1] + vr[2], si = vi[1] + vi[2], dr = vr[1] - vr[2], di = vi[1] - vi[2];
                double mr = vr[0] - 0.5 * sr, mi = vi[0] - 0.5 * si;
                double k = sg * S3;   // X1 = m - i·sg·sin(120º)·d ; X2 = m + i·sg·sin(120º)·d
                vr[0] += sr; vi[0] += si;
                vr[1] = mr + k * di; vi[1] = mi - k * dr;
                vr[2] = mr - k * di; vi[2] = mi + k * dr;
            }
            case 5 -> {
                double s14r = vr[1] + vr[4], s14i = vi[1] + vi[4], d14r = vr[1] - vr[4], d14i = vi[1] - vi[4];
                double s23r = vr[2] + vr[3], s23i = vi[2] + vi[3], d23r = vr[2] - vr[3], d23i = vi[2] - vi[3];
                double x0r = vr[0], x0i = vi[0];
                double ar = x0r + C5A * s14r + C5B * s23r, ai = x0i + C5A * s14i + C5B * s23i;
                double br = x0r + C5B * s14r + C5A * s23r, bi = x0i + C5B * s14i + C5A * s23i;
                // -i·sg·(S5A·d14 + S5B·d23) y -i·sg·(S5B·d14 - S5A·d23)
                double tr = sg * (S5A * d14i + S5B * d23i), ti = -sg * (S5A * d14r + S5B * d23r);
                double ur = sg * (S5B * d14i - S5A * d23i), ui = -sg * (S5B * d14r - S5A * d23r);
                vr[0] = x0r + s14r + s23r; vi[0] = x0i + s14i + s23i;
                vr[1] = ar + tr; vi[1] = ai + ti;
                vr[4] = ar - tr; vi[4] = ai - ti;
                vr[2] = br + ur; vi[2] = bi + ui;
                vr[3] = br - ur; vi[3] = bi - ui;
            }
            default -> {
                // DFT directa con la tabla de giros (n es múltiplo de R)
                double[] or = new double[R], oi = new double[R];
                int salto = n / R;
                for (int k = 0; k < R; k++) {
                    double sr = 0, si = 0;
                    for (int q = 0; q < R; q++) {
                        int t = ((q * k) % R) * salto;
                        double c = cosT[t], s = sg * senT[t];
                        sr += vr[q] * c - vi[q] * s;
                        si += vr[q] * s + vi[q] * c;
                    }
                    or[k] = sr; oi[k] = si;
                }
                System.arraycopy(or, 0, vr, 0, R);
                System.arraycopy(oi, 0, vi, 0, R);
            }
        }
    }
}
