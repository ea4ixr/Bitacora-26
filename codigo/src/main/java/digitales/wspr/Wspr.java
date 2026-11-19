package digitales.wspr;

import java.util.ArrayList;
import java.util.List;

/**
 * WSPR (baliza): codificador y descodificador, pasados a Java desde wsprd / wsprsim de WSJT-X
 * (K1JT, K9AN, KA9Q; licencia GNU GPL v3). Todo lo de WSPR está en esta carpeta: si se borra, el resto
 * del programa sigue igual.
 *
 * Audio de entrada: 12000 muestras/s, empezando en el minuto par (al menos 114 s).
 * Mensajes tipo 1 ("EA4ABC IN80 30"), tipo 2 ("PJ4/K1ABC 37") y tipo 3 ("<K1ABC> FN42AB 30").
 */
public final class Wspr {

    private Wspr() { }

    public static final int FS = 12000;
    /** Muestras por símbolo a 12000/s (1,4648 baudios) y separación entre tonos (Hz). */
    public static final int NSPS = 8192;
    public static final double DF_TONO = 12000.0 / 8192.0;
    public static final int NSIMB = 162;

    /** Vector de sincronismo. */
    static final int[] PR3 = {1,1,0,0,0,0,0,0,1,0,0,0,1,1,1,0,0,0,1,0,
        0,1,0,1,1,1,1,0,0,0,0,0,0,0,1,0,0,1,0,1,
        0,0,0,0,0,0,1,0,1,1,0,0,1,1,0,1,0,0,0,1,
        1,0,1,0,0,0,0,1,1,0,1,0,1,0,1,0,1,0,0,1,
        0,0,1,0,1,1,0,0,0,1,1,0,1,0,1,0,0,0,1,0,
        0,0,0,0,1,0,0,1,0,0,1,1,1,0,1,1,0,0,1,1,
        0,1,0,0,0,1,1,1,0,0,0,0,0,1,0,1,0,0,1,1,
        0,0,0,0,0,0,0,1,1,0,1,0,1,1,0,0,0,1,1,0,
        0,0};

    private static final float[] METRIC = {-7.4608f,-7.5256f,-7.5879f,-7.7598f,-8.4120f,0.9999f,0.9998f,0.9998f,0.9998f,0.9998f,0.9998f,0.9997f,0.9997f,0.9997f,0.9997f,0.9997f,0.9996f,0.9996f,0.9996f,0.9995f,0.9995f,0.9994f,0.9994f,0.9994f,0.9993f,0.9993f,0.9992f,0.9991f,0.9991f,0.9990f,0.9989f,0.9988f,0.9988f,0.9988f,0.9986f,0.9985f,0.9984f,0.9983f,0.9982f,0.9980f,0.9979f,0.9977f,0.9976f,0.9974f,0.9971f,0.9969f,0.9968f,0.9965f,0.9962f,0.9960f,0.9957f,0.9953f,0.9950f,0.9947f,0.9941f,0.9937f,0.9933f,0.9928f,0.9922f,0.9917f,0.9911f,0.9904f,0.9897f,0.9890f,0.9882f,0.9874f,0.9863f,0.9855f,0.9843f,0.9832f,0.9819f,0.9806f,0.9792f,0.9777f,0.9760f,0.9743f,0.9724f,0.9704f,0.9683f,0.9659f,0.9634f,0.9609f,0.9581f,0.9550f,0.9516f,0.9481f,0.9446f,0.9406f,0.9363f,0.9317f,0.9270f,0.9218f,0.9160f,0.9103f,0.9038f,0.8972f,0.8898f,0.8822f,0.8739f,0.8647f,0.8554f,0.8457f,0.8357f,0.8231f,0.8115f,0.7984f,0.7854f,0.7704f,0.7556f,0.7391f,0.7210f,0.7038f,0.6840f,0.6633f,0.6408f,0.6174f,0.5939f,0.5678f,0.5410f,0.5137f,0.4836f,0.4524f,0.4193f,0.3850f,0.3482f,0.3132f,0.2733f,0.2315f,0.1891f,0.1435f,0.0980f,0.0493f,0.0000f,-0.0510f,-0.1052f,-0.1593f,-0.2177f,-0.2759f,-0.3374f,-0.4005f,-0.4599f,-0.5266f,-0.5935f,-0.6626f,-0.7328f,-0.8051f,-0.8757f,-0.9498f,-1.0271f,-1.1019f,-1.1816f,-1.2642f,-1.3459f,-1.4295f,-1.5077f,-1.5958f,-1.6818f,-1.7647f,-1.8548f,-1.9387f,-2.0295f,-2.1152f,-2.2154f,-2.3011f,-2.3904f,-2.4820f,-2.5786f,-2.6730f,-2.7652f,-2.8616f,-2.9546f,-3.0526f,-3.1445f,-3.2445f,-3.3416f,-3.4357f,-3.5325f,-3.6324f,-3.7313f,-3.8225f,-3.9209f,-4.0248f,-4.1278f,-4.2261f,-4.3193f,-4.4220f,-4.5262f,-4.6214f,-4.7242f,-4.8234f,-4.9245f,-5.0298f,-5.1250f,-5.2232f,-5.3267f,-5.4332f,-5.5342f,-5.6431f,-5.7270f,-5.8401f,-5.9350f,-6.0407f,-6.1418f,-6.2363f,-6.3384f,-6.4536f,-6.5429f,-6.6582f,-6.7433f,-6.8438f,-6.9478f,-7.0789f,-7.1894f,-7.2714f,-7.3815f,-7.4810f,-7.5575f,-7.6852f,-7.8071f,-7.8580f,-7.9724f,-8.1000f,-8.2207f,-8.2867f,-8.4017f,-8.5287f,-8.6347f,-8.7082f,-8.8319f,-8.9448f,-9.0355f,-9.1885f,-9.2095f,-9.2863f,-9.4186f,-9.5064f,-9.6386f,-9.7207f,-9.8286f,-9.9453f,-10.0701f,-10.1735f,-10.3001f,-10.2858f,-10.5427f,-10.5982f,-10.7361f,-10.7042f,-10.9212f,-11.0097f,-11.0469f,-11.1155f,-11.2812f,-11.3472f,-11.4988f,-11.5327f};

    private static final long POLY1 = 0xf2d05351L, POLY2 = 0xe4613c47L;

    // ===================================================================== resultado

    /** Una baliza oída. */
    public static final class Resultado {
        public final String mensaje, indicativo, locator;
        public final int dbm;
        public final double snr, dt, audioHz, deriva;
        Resultado(String mensaje, String indicativo, String locator, int dbm, double snr, double dt, double audioHz, double deriva) {
            this.mensaje = mensaje; this.indicativo = indicativo; this.locator = locator; this.dbm = dbm;
            this.snr = snr; this.dt = dt; this.audioHz = audioHz; this.deriva = deriva;
        }
        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "%5.0f %5.1f %8.1f %3.0f  %s", snr, dt, audioHz, deriva, mensaje);
        }
    }

    // ===================================================================== codificar (TX)

    /** Los 162 tonos (0..3) de un mensaje, o null si no se puede codificar. */
    public static int[] tonos(String mensaje) {
        String m = mensaje.trim().toUpperCase().replaceAll("\\s+", " ");
        String[] w = m.split(" ");
        long n, mm;
        int[] nu = {0, -1, 1, 0, -1, 2, 1, 0, -1, 1};
        try {
            if (w.length == 3 && !w[0].contains("/") && !w[0].startsWith("<") && w[0].length() >= 3 && w[0].length() <= 6 && w[1].length() == 4) {
                n = packCall(w[0]);
                int p = Integer.parseInt(w[2]);
                String g = w[1];
                int[] g4 = new int[4];
                for (int i = 0; i < 4; i++) g4[i] = codLocator(g.charAt(i));
                for (int x : g4) if (x < 0) return null;
                mm = (179 - 10L * g4[0] - g4[2]) * 180 + 10L * g4[1] + g4[3];
                mm = mm * 128 + p + 64;
            } else if (w.length == 3 && w[0].startsWith("<") && w[0].endsWith(">")) {
                String call = w[0].substring(1, w[0].length() - 1);
                String grid = w[1];
                int power = Math.max(0, Math.min(60, Integer.parseInt(w[2])));
                power = power + nu[power % 10];
                int ntype = -(power + 1);
                int ihash = nhash(call, 146);
                mm = 128L * ihash + ntype + 64;
                if (grid.length() == 4) grid = grid + "  ";
                StringBuilder g6 = new StringBuilder();
                for (int i = 1; i < grid.length(); i++) g6.append(grid.charAt(i));
                g6.append(grid.charAt(0));
                n = packCall(g6.toString());
            } else if (w.length == 2 && w[0].contains("/")) {
                int power = Math.max(0, Math.min(60, Integer.parseInt(w[1])));
                power = power + nu[power % 10];
                long[] r = packPrefix(w[0]);
                if (r == null) return null;
                n = r[0];
                int ntype = power + 1 + (int) r[2];
                mm = 128 * r[1] + ntype + 64;
            } else return null;
        } catch (Exception e) {
            return null;
        }
        if (n < 0) return null;
        int[] data = new int[11];
        data[0] = (int) (0xFF & (n >> 20)); data[1] = (int) (0xFF & (n >> 12)); data[2] = (int) (0xFF & (n >> 4));
        data[3] = (int) (((n & 0x0F) << 4) + ((mm >> 18) & 0x0F));
        data[4] = (int) (0xFF & (mm >> 10)); data[5] = (int) (0xFF & (mm >> 2)); data[6] = (int) ((mm & 0x03) << 6);
        int[] bits = new int[176];
        long enc = 0;
        int k = 0;
        for (int b = 0; b < 11; b++) for (int i = 7; i >= 0; i--) {
            enc = ((enc << 1) | ((data[b] >> i) & 1)) & 0xFFFFFFFFL;
            bits[k++] = paridad(enc & POLY1);
            bits[k++] = paridad(enc & POLY2);
        }
        int[] inter = new int[NSIMB];
        int p = 0;
        for (int i = 0; p < NSIMB; i++) {
            int j = bitrev8(i);
            if (j < NSIMB) { inter[j] = bits[p]; p++; }
        }
        int[] t = new int[NSIMB];
        for (int i = 0; i < NSIMB; i++) t[i] = 2 * inter[i] + PR3[i];
        return t;
    }

    /** Audio (12000/s) de la baliza: 162 símbolos a partir de f0 Hz (el tono 0). Fase continua. */
    public static float[] audio(int[] tonos, double f0, double amplitud) {
        float[] a = new float[tonos.length * NSPS];
        double fase = 0;
        for (int i = 0; i < tonos.length; i++) {
            double dphi = 2 * Math.PI * (f0 + tonos[i] * DF_TONO) / FS;
            for (int j = 0; j < NSPS; j++) { a[i * NSPS + j] = (float) (amplitud * Math.sin(fase)); fase += dphi; }
            fase %= 2 * Math.PI;
        }
        return a;
    }

    static int paridad(long x) { return Long.bitCount(x) & 1; }
    static int bitrev8(int i) { return (Integer.reverse(i & 0xFF) >>> 24) & 0xFF; }

    static int codLocator(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch == ' ') return 36;
        if (ch >= 'A' && ch <= 'R') return ch - 'A';
        return -1;
    }

    static int codCall(char ch) {
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch == ' ') return 36;
        if (ch >= 'A' && ch <= 'Z') return ch - 55;
        return -1;
    }

    static long packCall(String call) {
        if (call.length() > 6) return -1;
        char[] c6 = {' ', ' ', ' ', ' ', ' ', ' '};
        if (call.length() > 2 && Character.isDigit(call.charAt(2))) {
            for (int i = 0; i < call.length(); i++) c6[i] = call.charAt(i);
        } else if (call.length() > 1 && Character.isDigit(call.charAt(1))) {
            for (int i = 1; i < call.length() + 1 && i < 6; i++) c6[i] = call.charAt(i - 1);
        } else return -1;
        int[] c = new int[6];
        for (int i = 0; i < 6; i++) { c[i] = codCall(c6[i]); if (c[i] < 0) return -1; }
        long n = c[0];
        n = n * 36 + c[1];
        n = n * 10 + c[2];
        n = n * 27 + c[3] - 10;
        n = n * 27 + c[4] - 10;
        n = n * 27 + c[5] - 10;
        return n;
    }

    /** Tipo 2 (prefijo o sufijo), como pack_prefix de WSJT-X: {n (indicativo), m, nadd}. */
    static long[] packPrefix(String callsign) {
        int i1 = callsign.indexOf('/');
        if (i1 <= 0) return null;
        String antes = callsign.substring(0, i1), despues = callsign.substring(i1 + 1);
        long n, m, nadd;
        if (despues.length() == 1) {
            n = packCall(antes);
            nadd = 1;
            char nc = despues.charAt(0);
            m = nc >= '0' && nc <= '9' ? nc - 48 : nc >= 'A' && nc <= 'Z' ? nc - 65 + 10 : 38;
            m = 60000 - 32768 + m;
        } else if (despues.length() == 2) {
            n = packCall(antes);
            nadd = 1;
            m = 10L * (despues.charAt(0) - 48) + (despues.charAt(1) - 48);
            m = 60000 + 26 + m;
        } else {
            n = packCall(despues);
            int plen = antes.length();
            if (plen > 3) return null;
            m = plen == 1 ? 37L * 36 + 36 : plen == 2 ? 36 : 0;
            for (int i = 0; i < plen; i++) {
                char ch = antes.charAt(i);
                int nc = ch >= '0' && ch <= '9' ? ch - 48 : ch >= 'A' && ch <= 'Z' ? ch - 65 + 10 : 36;
                m = 37 * m + nc;
            }
            nadd = 0;
            if (m > 32768) { m -= 32768; nadd = 1; }
        }
        if (n < 0) return null;
        return new long[]{n, m, nadd};
    }

    /** Hash de indicativo (nhash de Bob Jenkins, como en WSJT-X), 15 bits. */
    static int nhash(String s, int initval) {
        byte[] k = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int length = k.length;
        int a, b, c;
        a = b = c = 0xdeadbeef + length + initval;
        int off = 0;
        while (length > 12) {
            a += (k[off] & 0xff) + ((k[off + 1] & 0xff) << 8) + ((k[off + 2] & 0xff) << 16) + ((k[off + 3] & 0xff) << 24);
            b += (k[off + 4] & 0xff) + ((k[off + 5] & 0xff) << 8) + ((k[off + 6] & 0xff) << 16) + ((k[off + 7] & 0xff) << 24);
            c += (k[off + 8] & 0xff) + ((k[off + 9] & 0xff) << 8) + ((k[off + 10] & 0xff) << 16) + ((k[off + 11] & 0xff) << 24);
            a -= c; a ^= Integer.rotateLeft(c, 4); c += b;
            b -= a; b ^= Integer.rotateLeft(a, 6); a += c;
            c -= b; c ^= Integer.rotateLeft(b, 8); b += a;
            a -= c; a ^= Integer.rotateLeft(c, 16); c += b;
            b -= a; b ^= Integer.rotateLeft(a, 19); a += c;
            c -= b; c ^= Integer.rotateLeft(b, 4); b += a;
            length -= 12; off += 12;
        }
        switch (length) {
            case 12: c += (k[off + 11] & 0xff) << 24;
            case 11: c += (k[off + 10] & 0xff) << 16;
            case 10: c += (k[off + 9] & 0xff) << 8;
            case 9: c += k[off + 8] & 0xff;
            case 8: b += (k[off + 7] & 0xff) << 24;
            case 7: b += (k[off + 6] & 0xff) << 16;
            case 6: b += (k[off + 5] & 0xff) << 8;
            case 5: b += k[off + 4] & 0xff;
            case 4: a += (k[off + 3] & 0xff) << 24;
            case 3: a += (k[off + 2] & 0xff) << 16;
            case 2: a += (k[off + 1] & 0xff) << 8;
            case 1: a += k[off] & 0xff; break;
            case 0: return (c & 32767);
        }
        c ^= b; c -= Integer.rotateLeft(b, 14);
        a ^= c; a -= Integer.rotateLeft(c, 11);
        b ^= a; b -= Integer.rotateLeft(a, 25);
        c ^= b; c -= Integer.rotateLeft(b, 16);
        a ^= c; a -= Integer.rotateLeft(c, 4);
        b ^= a; b -= Integer.rotateLeft(a, 14);
        c ^= b; c -= Integer.rotateLeft(b, 24);
        return c & 32767;
    }

    // ===================================================================== descodificar (RX)

    private static final double DT = 1.0 / 375.0, DF = 375.0 / 256.0, DFM = 375.0 / 512.0;   // DFM: resolución de los espectros de 512

    /** Indicativos oídos (para los mensajes tipo 3, que solo traen el hash). Se mantiene entre turnos. */
    public static final java.util.Map<Integer, String> HASH = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Descodifica un turno de WSPR-2. audio: 12000/s desde el inicio del minuto par (114 s o más).
     * Busca entre 1350 y 1650 Hz de audio (la banda de WSPR con el dial en la frecuencia estándar).
     */
    public static List<Resultado> descodificar(float[] audio) {
        int np = 46080;
        float[] id = new float[np + 1024], qd = new float[np + 1024];
        bajarA375(audio, id, qd);

        int[][] mettab = new int[2][256];
        double bias = 0.45;
        for (int i = 0; i < 256; i++) {
            mettab[0][i] = (int) Math.round(10 * (METRIC[i] - bias));
            mettab[1][i] = (int) Math.round(10 * (METRIC[255 - i] - bias));
        }

        // Espectros de 512 puntos cada 128 (como wsprd)
        int nffts = 4 * (np / 512) - 1;
        float[] w = new float[512];
        for (int i = 0; i < 512; i++) w[i] = (float) Math.sin(0.006147931 * i);
        float[][] ps = new float[512][nffts];
        double[] re = new double[512], im = new double[512];
        for (int i = 0; i < nffts; i++) {
            for (int j = 0; j < 512; j++) { int k = i * 128 + j; re[j] = id[k] * w[j]; im[j] = qd[k] * w[j]; }
            fft(re, im);
            for (int j = 0; j < 512; j++) {
                int k = j + 256; if (k > 511) k -= 512;
                ps[j][i] = (float) (re[k] * re[k] + im[k] * im[k]);
            }
        }
        float[] psavg = new float[512];
        for (int i = 0; i < nffts; i++) for (int j = 0; j < 512; j++) psavg[j] += ps[j][i];
        float[] smspec = new float[411];
        for (int i = 0; i < 411; i++) for (int j = -3; j <= 3; j++) smspec[i] += psavg[256 - 205 + i + j];
        float[] tmp = smspec.clone();
        java.util.Arrays.sort(tmp);
        float ruido = tmp[122];
        double minSnr = Math.pow(10.0, -8.0 / 10.0);
        for (int j = 0; j < 411; j++) {
            smspec[j] = smspec[j] / ruido - 1f;
            if (smspec[j] < minSnr) smspec[j] = (float) (0.1 * minSnr);
        }
        List<double[]> cand = new ArrayList<>();   // {freq, snr, drift, shift, sync}
        double fmin = -110, fmax = 110;
        for (int j = 1; j < 410 && cand.size() < 200; j++) {
            if (smspec[j] > smspec[j - 1] && smspec[j] > smspec[j + 1]) {
                double f = (j - 205) * DFM;
                if (f >= fmin && f <= fmax) cand.add(new double[]{f, 10 * Math.log10(smspec[j]) - 26.3, 0, 0, 0});
            }
        }
        cand.sort((x, y) -> Double.compare(y[1], x[1]));

        // Estimación gruesa de frecuencia, desplazamiento (DT) y deriva
        int maxdrift = 4;
        for (double[] c : cand) {
            double smax = -1e30;
            int if0 = (int) (c[0] / DFM + 256);
            for (int ifr = if0 - 2; ifr <= if0 + 2; ifr++)
                for (int k0 = -10; k0 < 22; k0++)
                    for (int idrift = -maxdrift; idrift <= maxdrift; idrift++) {
                        double ss = 0, pow = 0;
                        for (int k = 0; k < 162; k++) {
                            int ifd = (int) (ifr + ((float) k - 81f) / 81f * ((float) idrift) / (2f * (float) DFM));
                            int kindex = k0 + 2 * k;
                            if (kindex >= 0 && kindex < nffts && ifd - 3 >= 0 && ifd + 3 < 512) {
                                double p0 = Math.sqrt(ps[ifd - 3][kindex]), p1 = Math.sqrt(ps[ifd - 1][kindex]);
                                double p2 = Math.sqrt(ps[ifd + 1][kindex]), p3 = Math.sqrt(ps[ifd + 3][kindex]);
                                ss += (2 * PR3[k] - 1) * ((p1 + p3) - (p0 + p2));
                                pow += p0 + p1 + p2 + p3;
                            }
                        }
                        double s1 = pow > 0 ? ss / pow : 0;
                        if (s1 > smax) { smax = s1; c[3] = 128 * (k0 + 1); c[2] = idrift; c[0] = (ifr - 256) * DFM; c[4] = s1; }
                    }
        }

        if (System.getProperty("wspr.debug") != null) for (double[] c : cand) System.err.printf("grueso f=%.2f snr=%.1f drift=%.1f shift=%d sync=%.3f%n", c[0], c[1], c[2], (int) c[3], c[4]);
        // Ajuste fino
        double minsync1 = 0.10, minsync2 = 0.12;
        int[] symbols = new int[162];
        for (double[] c : cand) {
            Sync s = new Sync(c[0], (int) c[3], c[2]);
            sincroDemodular(id, qd, np, null, s, 0, 0, 0, s.shift - 128, s.shift + 128, 64, 50, 0);
            sincroDemodular(id, qd, np, null, s, -2, 2, 0.25, 0, 0, 0, 50, 1);
            double sync1 = s.sync;
            Sync sp = new Sync(s.f, s.shift, s.drift + 0.5), sm = new Sync(s.f, s.shift, s.drift - 0.5);
            sincroDemodular(id, qd, np, null, sp, 0, 0, 0, 0, 0, 0, 50, 1);
            sincroDemodular(id, qd, np, null, sm, 0, 0, 0, 0, 0, 0, 50, 1);
            if (sp.sync > sync1) { s.drift = sp.drift; sync1 = sp.sync; } else if (sm.sync > sync1) { s.drift = sm.drift; sync1 = sm.sync; }
            s.f = sp.sync > sync1 || sm.sync > sync1 ? s.f : s.f;
            s.sync = sync1;
            if (s.sync > minsync1) {
                sincroDemodular(id, qd, np, null, s, 0, 0, 0, s.shift - 32, s.shift + 32, 16, 50, 0);
                sincroDemodular(id, qd, np, null, s, -2, 2, 0.05, 0, 0, 0, 50, 1);
            }
            c[0] = s.f; c[3] = s.shift; c[2] = s.drift; c[4] = s.sync;
        }

        // Quitar repetidos
        List<double[]> buenos = new ArrayList<>();
        for (double[] c : cand) {
            double[] igual = null;
            for (double[] b : buenos) if (Math.abs(c[0] - b[0]) < 0.05 && Math.abs(c[3] - b[3]) < 16) { igual = b; break; }
            if (igual != null) { if (c[4] > igual[4]) System.arraycopy(c, 0, igual, 0, 5); }
            else if (c[4] > minsync2) buenos.add(c);
        }

        if (System.getProperty("wspr.debug") != null) for (double[] c : buenos) System.err.printf("cand f=%.2f snr=%.1f drift=%.1f shift=%d sync=%.3f%n", c[0], c[1], c[2], (int) c[3], c[4]);
        if (System.getProperty("wspr.debug") != null) System.err.println("cands " + cand.size() + " buenos " + buenos.size());
        List<Resultado> salida = new ArrayList<>();
        double minrms = 52.0 * (50 / 64.0);
        int iifac = 8;
        for (double[] c : buenos) {
            boolean hecho = false;
            for (int idt = 0; !hecho && idt <= 128 / iifac; idt++) {
                int ii = (idt + 1) / 2;
                if (idt % 2 == 1) ii = -ii;
                ii = iifac * ii;
                Sync s = new Sync(c[0], (int) c[3] + ii, c[2]);
                sincroDemodular(id, qd, np, symbols, s, 0, 0, 0, 0, 0, 0, 50, 2);
                double sq = 0;
                for (int i = 0; i < 162; i++) { double y = symbols[i] - 128.0; sq += y * y; }
                if (Math.sqrt(sq / 162.0) <= minrms) continue;
                int[] sym = desentrelazar(symbols);
                int[] dec = fano(sym, 81, mettab, 60, 10000);
                if (dec == null) continue;
                Resultado r = desempaquetar(dec, c[1], (s.shift) * DT - 1.0, 1500 + s.f, s.drift);
                if (r == null) continue;
                hecho = true;
                boolean rep = false;
                for (Resultado x : salida) if (x.indicativo.equals(r.indicativo) && Math.abs(x.audioHz - r.audioHz) < 4) rep = true;
                if (!rep) salida.add(r);
            }
        }
        return salida;
    }

    /** Pasa el audio a banda base compleja de 375 muestras/s centrada en 1500 Hz (filtro paso bajo + diezmado ×32). */
    static void bajarA375(float[] audio, float[] id, float[] qd) {
        int dec = 32, ntaps = 32 * 16 + 1, h = ntaps / 2;
        double fc = 175.0 / FS;
        double[] t = new double[ntaps];
        double suma = 0;
        for (int i = 0; i < ntaps; i++) {
            int n = i - h;
            double sinc = n == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * n) / (Math.PI * n);
            double win = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (ntaps - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (ntaps - 1));
            t[i] = sinc * win; suma += t[i];
        }
        for (int i = 0; i < ntaps; i++) t[i] /= suma;
        int n = audio.length;
        double w0 = 2 * Math.PI * 1500.0 / FS;
        double[] cr = new double[n], ci = new double[n];
        for (int i = 0; i < n; i++) { cr[i] = audio[i] * Math.cos(w0 * i); ci[i] = -audio[i] * Math.sin(w0 * i); }
        for (int k = 0; k < 46080; k++) {
            int c = k * dec;
            if (c >= n) break;
            double sr = 0, si = 0;
            for (int j = 0; j < ntaps; j++) {
                int x = c + j - h;
                if (x < 0 || x >= n) continue;
                sr += t[j] * cr[x]; si += t[j] * ci[x];
            }
            id[k] = (float) (sr * 20); qd[k] = (float) (si * 20);
        }
    }

    static final class Sync {
        double f, drift, sync;
        int shift;
        Sync(double f, int shift, double drift) { this.f = f; this.shift = shift; this.drift = drift; }
    }

    /** sync_and_demodulate de wsprd: modo 0 = mejor desplazamiento; 1 = mejor frecuencia; 2 = símbolos blandos. */
    static void sincroDemodular(float[] id, float[] qd, int np, int[] symbols, Sync s, int ifmin, int ifmax, double fstep,
                                int lagmin, int lagmax, int lagstep, int symfac, int mode) {
        double dt = DT, df = DF, df15 = df * 1.5, df05 = df * 0.5;
        double syncmax = -1e30, fbest = s.f;
        int bestShift = s.shift;
        if (mode == 0) { ifmin = 0; ifmax = 0; fstep = 0; }
        if (mode == 1 || mode == 2) { lagmin = s.shift; lagmax = s.shift; lagstep = 1; }
        if (mode == 2) { ifmin = 0; ifmax = 0; }
        double twopidt = 2 * Math.PI * dt;
        double[] fsymb = new double[162];
        double[] c0 = new double[256], s0 = new double[256], c1 = new double[256], s1 = new double[256];
        double[] c2 = new double[256], s2 = new double[256], c3 = new double[256], s3 = new double[256];
        for (int ifreq = ifmin; ifreq <= ifmax; ifreq++) {
            double f0 = s.f + ifreq * fstep;
            for (int lag = lagmin; lag <= lagmax; lag += Math.max(1, lagstep)) {
                double ss = 0, totp = 0;
                double fplast = -10000;
                for (int i = 0; i < 162; i++) {
                    double fp = f0 + (s.drift / 2.0) * ((double) i - 81.0) / 81.0;
                    if (i == 0 || fp != fplast) {
                        tabla(twopidt * (fp - df15), c0, s0); tabla(twopidt * (fp - df05), c1, s1);
                        tabla(twopidt * (fp + df05), c2, s2); tabla(twopidt * (fp + df15), c3, s3);
                        fplast = fp;
                    }
                    double i0 = 0, q0 = 0, i1 = 0, q1 = 0, i2 = 0, q2 = 0, i3 = 0, q3 = 0;
                    for (int j = 0; j < 256; j++) {
                        int k = lag + i * 256 + j;
                        if (k > 0 && k < np) {
                            i0 += id[k] * c0[j] + qd[k] * s0[j]; q0 += -id[k] * s0[j] + qd[k] * c0[j];
                            i1 += id[k] * c1[j] + qd[k] * s1[j]; q1 += -id[k] * s1[j] + qd[k] * c1[j];
                            i2 += id[k] * c2[j] + qd[k] * s2[j]; q2 += -id[k] * s2[j] + qd[k] * c2[j];
                            i3 += id[k] * c3[j] + qd[k] * s3[j]; q3 += -id[k] * s3[j] + qd[k] * c3[j];
                        }
                    }
                    double p0 = Math.sqrt(i0 * i0 + q0 * q0), p1 = Math.sqrt(i1 * i1 + q1 * q1);
                    double p2 = Math.sqrt(i2 * i2 + q2 * q2), p3 = Math.sqrt(i3 * i3 + q3 * q3);
                    totp += p0 + p1 + p2 + p3;
                    double cmet = (p1 + p3) - (p0 + p2);
                    ss = PR3[i] == 1 ? ss + cmet : ss - cmet;
                    if (mode == 2) fsymb[i] = PR3[i] == 1 ? p3 - p1 : p2 - p0;
                }
                ss = totp > 0 ? ss / totp : 0;
                if (ss > syncmax) { syncmax = ss; bestShift = lag; fbest = f0; }
            }
        }
        if (mode <= 1) { s.sync = syncmax; s.shift = bestShift; s.f = fbest; return; }
        s.sync = syncmax;
        double fsum = 0, f2sum = 0;
        for (int i = 0; i < 162; i++) { fsum += fsymb[i] / 162.0; f2sum += fsymb[i] * fsymb[i] / 162.0; }
        double fac = Math.sqrt(Math.max(1e-12, f2sum - fsum * fsum));
        for (int i = 0; i < 162; i++) {
            double v = symfac * fsymb[i] / fac;
            if (v > 127) v = 127; if (v < -128) v = -128;
            symbols[i] = (int) (v + 128);
        }
    }

    private static void tabla(double dphi, double[] c, double[] s) {
        double cd = Math.cos(dphi), sd = Math.sin(dphi);
        c[0] = 1; s[0] = 0;
        for (int j = 1; j < 256; j++) { c[j] = c[j - 1] * cd - s[j - 1] * sd; s[j] = c[j - 1] * sd + s[j - 1] * cd; }
    }

    static int[] desentrelazar(int[] sym) {
        int[] t = new int[162];
        int p = 0;
        for (int i = 0; p < 162; i++) {
            int j = bitrev8(i);
            if (j < 162) { t[p] = sym[j]; p++; }
        }
        return t;
    }

    /** Descodificador secuencial de Fano (KA9Q / K1JT) para K=32, r=1/2. Devuelve los 11 bytes o null. */
    static int[] fano(int[] symbols, int nbits, int[][] mettab, int delta, int maxcycles) {
        long[] encstate = new long[nbits + 1];
        long[] gamma = new long[nbits + 1];
        int[][] metrics = new int[nbits + 1][4];
        int[][] tm = new int[nbits + 1][2];
        int[] ii = new int[nbits + 1];
        for (int n = 0; n < nbits; n++) {
            int a = symbols[2 * n], b = symbols[2 * n + 1];
            metrics[n][0] = mettab[0][a] + mettab[0][b];
            metrics[n][1] = mettab[0][a] + mettab[1][b];
            metrics[n][2] = mettab[1][a] + mettab[0][b];
            metrics[n][3] = mettab[1][a] + mettab[1][b];
        }
        int lastnode = nbits - 1, tail = nbits - 31;
        int np = 0;
        encstate[0] = 0;
        int lsym = enc(encstate[0]);
        int m0 = metrics[0][lsym], m1 = metrics[0][3 ^ lsym];
        if (m0 > m1) { tm[0][0] = m0; tm[0][1] = m1; } else { tm[0][0] = m1; tm[0][1] = m0; encstate[0]++; }
        ii[0] = 0;
        long maxc = (long) maxcycles * nbits;
        long t = 0;
        gamma[0] = 0;
        long i;
        boolean fin = false;
        for (i = 1; i <= maxc; i++) {
            long ngamma = gamma[np] + tm[np][ii[np]];
            if (ngamma >= t) {
                if (gamma[np] < t + delta) while (ngamma >= t + delta) t += delta;
                gamma[np + 1] = ngamma;
                encstate[np + 1] = (encstate[np] << 1) & 0xFFFFFFFFL;
                np++;
                if (np == lastnode + 1) { fin = true; break; }
                lsym = enc(encstate[np]);
                if (np >= tail) {
                    tm[np][0] = metrics[np][lsym];
                } else {
                    m0 = metrics[np][lsym]; m1 = metrics[np][3 ^ lsym];
                    if (m0 > m1) { tm[np][0] = m0; tm[np][1] = m1; } else { tm[np][0] = m1; tm[np][1] = m0; encstate[np]++; }
                }
                ii[np] = 0;
                continue;
            }
            for (;;) {
                if (np == 0 || gamma[np - 1] < t) {
                    t -= delta;
                    if (ii[np] != 0) { ii[np] = 0; encstate[np] ^= 1; }
                    break;
                }
                --np;
                if (np < tail && ii[np] != 1) { ii[np]++; encstate[np] ^= 1; break; }
            }
        }
        if (!fin) return null;
        int nbytes = nbits >> 3;
        int[] data = new int[11];
        for (int k = 0; k < nbytes; k++) data[k] = (int) (encstate[7 + 8 * k] & 0xFF);
        return data;
    }

    private static int enc(long encstate) {
        return (paridad(encstate & POLY1) << 1) | paridad(encstate & POLY2);
    }

    /** Del mensaje de 50 bits a texto (unpk_ de wsprd), con las comprobaciones de WSJT-X. */
    static Resultado desempaquetar(int[] d, double snr, double dt, double audioHz, double deriva) {
        long n1 = ((long) (d[0] & 255) << 20) + ((d[1] & 255) << 12) + ((d[2] & 255) << 4) + (((d[3] & 255) >> 4) & 15);
        long n2 = ((long) ((d[3] & 255) & 15) << 18) + ((d[4] & 255) << 10) + ((d[5] & 255) << 2) + (((d[6] & 255) >> 6) & 3);
        String call = unpackCall(n1);
        if (call == null) return null;
        String grid = unpackGrid(n2);
        if (grid == null) return null;
        int ntype = (int) (n2 & 127) - 64;
        if (ntype >= 0 && ntype <= 62) {
            int nu = ntype % 10;
            if (nu == 0 || nu == 3 || nu == 7) {
                if (call.isEmpty()) return null;
                HASH.put(nhash(call, 146), call);
                return new Resultado(call + " " + grid + " " + ntype, call, grid, ntype, snr, dt, audioHz, deriva);
            }
            int nadd = nu;
            if (nu > 3) nadd = nu - 3;
            if (nu > 7) nadd = nu - 7;
            long n3 = n2 / 128 + 32768L * (nadd - 1);
            String c2 = unpackPfx(n3, call);
            if (c2 == null) return null;
            int ndbm = ntype - nadd;
            int nu2 = ndbm % 10;
            if (!(nu2 == 0 || nu2 == 3 || nu2 == 7 || nu2 == 10)) return null;
            HASH.put(nhash(c2, 146), c2);
            return new Resultado(c2 + " " + ndbm, c2, "", ndbm, snr, dt, audioHz, deriva);
        } else if (ntype < 0) {
            int ndbm = -(ntype + 1);
            String c6 = String.format("%-6s", call);
            String grid6 = c6.charAt(5) + c6.substring(0, 5);
            grid6 = grid6.trim();
            int nu = ndbm % 10;
            if ((nu != 0 && nu != 3 && nu != 7 && nu != 10) || grid6.length() < 4 || !Character.isLetter(grid6.charAt(0))
                    || !Character.isLetter(grid6.charAt(1)) || !Character.isDigit(grid6.charAt(2)) || !Character.isDigit(grid6.charAt(3)))
                return null;
            if (ntype == -64) return null;
            int ihash = (int) ((n2 - ntype - 64) / 128);
            String quien = HASH.get(ihash);
            String c = quien == null ? "<...>" : "<" + quien + ">";
            return new Resultado(c + " " + grid6 + " " + ndbm, quien == null ? "<...>" : quien, grid6, ndbm, snr, dt, audioHz, deriva);
        }
        return null;
    }

    private static final String C = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ ";

    static String unpackCall(long ncall) {
        if (ncall >= 262177560L) return null;
        long n = ncall;
        char[] t = new char[6];
        int i = (int) (n % 27 + 10); t[5] = C.charAt(i); n /= 27;
        i = (int) (n % 27 + 10); t[4] = C.charAt(i); n /= 27;
        i = (int) (n % 27 + 10); t[3] = C.charAt(i); n /= 27;
        i = (int) (n % 10); t[2] = C.charAt(i); n /= 10;
        i = (int) (n % 36); t[1] = C.charAt(i); n /= 36;
        i = (int) n; if (i > 36) return null; t[0] = C.charAt(i);
        return new String(t).trim();
    }

    static String unpackGrid(long ngrid) {
        ngrid = ngrid >> 7;
        if (ngrid >= 32400) return null;
        int dlat = (int) (ngrid % 180) - 90;
        int dlong = (int) (ngrid / 180) * 2 - 180 + 2;
        if (dlong < -180) dlong += 360;
        if (dlong > 180) dlong += 360;
        int nlong = (int) (60.0 * (180.0 - dlong) / 5.0);
        int n1 = nlong / 240, n2 = (nlong - 240 * n1) / 24;
        char g0 = C.charAt(10 + n1), g2 = C.charAt(n2);
        int nlat = (int) (60.0 * (dlat + 90) / 2.5);
        n1 = nlat / 240; n2 = (nlat - 240 * n1) / 24;
        char g1 = C.charAt(10 + n1), g3 = C.charAt(n2);
        return "" + g0 + g1 + g2 + g3;
    }

    static String unpackPfx(long nprefix, String call) {
        if (nprefix < 60000) {
            long n = nprefix;
            char[] pfx = new char[3];
            for (int i = 2; i >= 0; i--) {
                int nc = (int) (n % 37);
                pfx[i] = nc <= 9 ? (char) (nc + 48) : nc <= 35 ? (char) (nc + 55) : ' ';
                n /= 37;
            }
            return new String(pfx).trim() + "/" + call;
        }
        int nc = (int) (nprefix - 60000);
        if (nc >= 0 && nc <= 9) return call + "/" + (char) (nc + 48);
        if (nc >= 10 && nc <= 35) return call + "/" + (char) (nc + 55);
        if (nc >= 36 && nc <= 125) return call + "/" + (char) ((nc - 26) / 10 + 48) + (char) ((nc - 26) % 10 + 48);
        return null;
    }

    /** FFT compleja de tamaño potencia de 2 (in situ). */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    int a = i + j, b = i + j + len / 2;
                    double ur = re[a], ui = im[a];
                    double vr = re[b] * cr - im[b] * ci, vi = re[b] * ci + im[b] * cr;
                    re[a] = ur + vr; im[a] = ui + vi; re[b] = ur - vr; im[b] = ui - vi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
}
