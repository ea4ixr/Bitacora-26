package digitales;

/**
 * Protección contra errores de FT8/FT4: CRC de 14 bits y código LDPC(174,91).
 * Basado en WSJT-X (K1JT y colaboradores, GPL) y ft8_lib (Kārlis Goba, licencia MIT).
 */
final class Ft8Codigo {
    private Ft8Codigo() { }

    static final int N = 174, K = 91, M = 83;
    private static final int POLINOMIO_CRC = 0x2757, ANCHO_CRC = 14;

    /** CRC de 14 bits de los primeros numBits bits de msg (bytes, el bit más alto primero). */
    static int crc(int[] msg, int numBits) {
        int resto = 0, iByte = 0;
        int alto = 1 << (ANCHO_CRC - 1);
        for (int b = 0; b < numBits; b++) {
            if (b % 8 == 0) resto ^= (msg[iByte++] & 0xFF) << (ANCHO_CRC - 8);
            resto = (resto & alto) != 0 ? ((resto << 1) ^ POLINOMIO_CRC) : (resto << 1);
        }
        return resto & ((alto << 1) - 1);
    }

    /** CRC guardado en a91 (detrás de los 77 bits del mensaje). */
    static int crcGuardado(int[] a91) {
        return ((a91[9] & 0x07) << 11) | ((a91[10] & 0xFF) << 3) | ((a91[11] & 0xFF) >> 5);
    }

    /** 77 bits del mensaje (10 bytes) -> 91 bits con el CRC (12 bytes). */
    static int[] conCrc(int[] mensaje) {
        int[] a91 = new int[12];
        for (int i = 0; i < 10; i++) a91[i] = mensaje[i] & 0xFF;
        a91[9] &= 0xF8;
        a91[10] = 0;
        int c = crc(a91, 96 - 14);
        a91[9] |= (c >> 11) & 0xFF;
        a91[10] = (c >> 3) & 0xFF;
        a91[11] = (c << 5) & 0xFF;
        return a91;
    }

    private static int paridad8(int x) {
        x ^= x >> 4; x ^= x >> 2; x ^= x >> 1;
        return x & 1;
    }

    /** 91 bits (12 bytes) -> 174 bits (22 bytes) con las 83 comprobaciones LDPC. */
    static int[] codificar174(int[] a91) {
        int[] cw = new int[22];
        for (int j = 0; j < 22; j++) cw[j] = j < 12 ? a91[j] : 0;
        int mascara = 0x80 >> (K % 8), idx = 11;
        for (int i = 0; i < M; i++) {
            int suma = 0;
            for (int j = 0; j < 12; j++) suma ^= paridad8(a91[j] & Ft8Tablas.GENERADOR[i][j]);
            if ((suma & 1) != 0) cw[idx] |= mascara;
            mascara >>= 1;
            if (mascara == 0) { mascara = 0x80; idx++; }
        }
        return cw;
    }

    /** Número de comprobaciones que fallan (0 = palabra correcta). */
    static int errores(int[] bits) {
        int e = 0;
        for (int m = 0; m < M; m++) {
            int x = 0;
            for (int i = 0; i < Ft8Tablas.FILAS_NM[m]; i++) x ^= bits[Ft8Tablas.NM[m][i] - 1];
            if (x != 0) e++;
        }
        return e;
    }

    private static float tanhRapida(float x) {
        if (x < -4.97f) return -1f;
        if (x > 4.97f) return 1f;
        float x2 = x * x;
        return x * (945f + x2 * (105f + x2)) / (945f + x2 * (420f + x2 * 15f));
    }

    private static float atanhRapida(float x) {
        float x2 = x * x;
        return x * (945f + x2 * (-735f + x2 * 64f)) / (945f + x2 * (-1050f + x2 * 225f));
    }

    /**
     * Descodificador por propagación de creencias. llr = log(p(1)/p(0)) de cada bit.
     * Deja en bits el resultado (0/1) y devuelve el número de comprobaciones que fallan.
     */
    static int propagacion(float[] llr, int maxIter, int[] bits) {
        float[][] tov = new float[N][3];
        float[][] toc = new float[M][7];
        int minErr = M;
        int[] mejor = new int[N];
        for (int it = 0; it < maxIter; it++) {
            int suma = 0;
            for (int n = 0; n < N; n++) {
                bits[n] = (llr[n] + tov[n][0] + tov[n][1] + tov[n][2]) > 0 ? 1 : 0;
                suma += bits[n];
            }
            if (suma == 0) break;   // todo ceros: no vale
            int e = errores(bits);
            if (e < minErr) {
                minErr = e;
                System.arraycopy(bits, 0, mejor, 0, N);
                if (e == 0) break;
            }
            for (int m = 0; m < M; m++) {
                for (int ni = 0; ni < Ft8Tablas.FILAS_NM[m]; ni++) {
                    int n = Ft8Tablas.NM[m][ni] - 1;
                    float t = llr[n];
                    for (int mi = 0; mi < 3; mi++) if (Ft8Tablas.MN[n][mi] - 1 != m) t += tov[n][mi];
                    toc[m][ni] = tanhRapida(-t / 2);
                }
            }
            for (int n = 0; n < N; n++) {
                for (int mi = 0; mi < 3; mi++) {
                    int m = Ft8Tablas.MN[n][mi] - 1;
                    float t = 1f;
                    for (int ni = 0; ni < Ft8Tablas.FILAS_NM[m]; ni++) if (Ft8Tablas.NM[m][ni] - 1 != n) t *= toc[m][ni];
                    tov[n][mi] = -2 * atanhRapida(t);
                }
            }
        }
        if (minErr > 0) System.arraycopy(mejor, 0, bits, 0, N);
        return minErr;
    }

    /** Bits (0/1) -> bytes, el bit más alto primero. */
    static int[] empaquetar(int[] bits, int num) {
        int[] r = new int[(num + 7) / 8];
        for (int i = 0; i < num; i++) if (bits[i] != 0) r[i / 8] |= 0x80 >> (i % 8);
        return r;
    }
}
