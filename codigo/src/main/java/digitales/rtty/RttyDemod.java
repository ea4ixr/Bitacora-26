package digitales.rtty;

/**
 * Descodificador de RTTY (Baudot/ITA2, 45,45 o 75 baudios, 170 Hz de desplazamiento).
 *
 * Es una traducción a Java de la parte de recepción de MMTTY (clase CFSKDEM, con el demodulador IIR que trae por defecto,
 * el limitador con control automático de nivel, el alisado, la «lógica de mayoría» para leer cada bit y el silenciador).
 * MMTTY: Copyright 2000-2013 Makoto Mori (JE3HHT), Nobuyuki Oba. Licencia GNU LGPL v3 (https://github.com/n5ac/mmtty).
 * Esta traducción se distribuye con la misma licencia LGPL v3.
 *
 * Recibe muestras a 12000 por segundo en escala de 16 bits (como las da MotorDigitales.Fuente) y devuelve caracteres.
 */
public final class RttyDemod {

    /** Recibe cada carácter descodificado. */
    public interface Salida { void caracter(char c); }

    private final double fs;
    private final Salida salida;

    // Resonadores IIR de MARK y SPACE (CIIRTANK de MMTTY)
    private final Tanque tm = new Tanque(), ts = new Tanque();
    private double markHz, spaceHz, anchoIir = 60.0;
    private boolean inv;

    // Limitador con AGC
    private double limMax = 1, limMin = -1, dAnt = 0, limAgc = 200;

    // Alisado (media móvil de ~70 Hz)
    private final Media avgM, avgS;

    // Bits
    private double baudios = 45.45;
    private int reCount, count;
    private int modo = 0, mark, space, datos, quedan;
    private static final int BITS = 5;

    // Silenciador
    private boolean silenciador = true;
    private double nivelSql = 600.0;
    private double deff, avgDeff;
    private int sqCount;
    private final Media sqMedia = new Media(8);

    // Baudot
    private boolean cifras = false;

    public RttyDemod(double fs, Salida salida) {
        this.fs = fs;
        this.salida = salida;
        int n = (int) Math.round(fs / 70.0);
        avgM = new Media(n);
        avgS = new Media(n);
        ponerBaudios(45.45);
        ponerTonos(1500, 1670);
    }

    /** Tonos de audio de MARK y SPACE en Hz. */
    public void ponerTonos(double mark, double space) {
        markHz = mark;
        spaceHz = space;
        tm.ajustar(markHz, fs, anchoIir);
        ts.ajustar(spaceHz, fs, anchoIir);
    }

    public void ponerBaudios(double b) {
        baudios = b;
        reCount = (int) (fs / b + 0.5);
        count = reCount;
        anchoIir = b >= 70 ? 90.0 : 60.0;
        if (markHz > 0) ponerTonos(markHz, spaceHz);
    }

    public void ponerInvertido(boolean si) { inv = si; }
    public void ponerSilenciador(boolean si) { silenciador = si; }
    /** Nivel del silenciador (MMTTY usa 600 por defecto). */
    public void ponerNivelSilenciador(double n) { nivelSql = n; }

    /** Nivel de la señal (diferencia entre MARK y SPACE), para el medidor. */
    public double nivel() { return avgDeff; }

    public void procesar(float[] buf, int n) {
        for (int i = 0; i < n; i++) muestra(buf[i]);
    }

    private void muestra(double d) {
        // Limitador con AGC: ajusta la ganancia en cada paso por cero
        if (limMax < d) limMax = d;
        if (limMin > d) limMin = d;
        if (d >= 0 && dAnt < 0) {
            double r = limMax - limMin;
            if (r != 0) {
                limAgc = (64.0 * 16384.0) / r;
                if (limAgc >= 4096.0) limAgc = 4096;
            } else limAgc = 200;
            limMax = 1;
            limMin = -1;
        }
        dAnt = d;
        d *= limAgc;
        if (d > 16384.0) d = 16384.0;
        if (d < -16384.0) d = -16384.0;

        // Demodulador IIR + detector + alisado
        double dm = Math.abs(tm.paso(d)), ds = Math.abs(ts.paso(d));
        dm = avgM.media(dm);
        ds = avgS.media(ds);
        bits(dm, ds);
    }

    private void bits(double dMark, double dSpace) {
        int b = dMark >= dSpace ? (inv ? 0 : 1) : (inv ? 1 : 0);
        double df = Math.abs(dMark - dSpace);
        if (deff < df) deff = df;
        if (sqCount <= 0) {
            avgDeff = sqMedia.media(deff);
            deff = 0;
            sqCount = (int) (fs / 10);
        }
        sqCount--;
        if (silenciador && modo == 0 && nivelSql * 10.0 > avgDeff) b = 1;

        switch (modo) {
            case 0 -> {   // esperando el bit de arranque
                if (b == 0) { count = reCount / 2; mark = space = 0; modo = 256; }
                else count = reCount;
            }
            case 256 -> {   // mitad del bit de arranque (lógica de mayoría)
                if (b == 1) mark++; else space++;
                if (count == 0) {
                    if (mark >= space) modo = 0;
                    else { count = reCount / 2; modo = 257; }
                }
            }
            case 257 -> {
                if (count == 0) { count = reCount; mark = space = 0; datos = 0; quedan = BITS; modo = 258; }
            }
            case 258 -> {   // bits de datos (el primero que llega es el de menos peso)
                if (b == 1) mark++; else space++;
                if (count == 0) {
                    int v = mark >= space ? 1 : 0;
                    mark = space = 0;
                    count = reCount;
                    datos |= v << (BITS - quedan);
                    quedan--;
                    if (quedan == 0) { count = reCount * 7 / 8; modo = 260; }
                }
            }
            case 260 -> {   // bit de parada
                if (b == 1) mark++; else space++;
                if (count == 0) {
                    int v = mark >= space ? 1 : 0;
                    mark = space = 0;
                    if (v == 1) { baudot(datos); count = reCount * 2 / 5; modo = 261; }
                    else modo = 8;   // error de trama
                }
            }
            case 261 -> { if (count == 0) modo = 262; }
            case 262 -> {   // esperando el final de la parada
                if (b == 1) count = reCount;
                else { count = reCount / 2; mark = space = 0; modo = 256; }
            }
            case 8 -> {   // error de trama: se espera a MARK
                count = reCount;
                if (b == 1) modo = 0;
            }
            default -> modo = 0;
        }
        count--;
    }

    // ------------------------------------------------------------------ Baudot (ITA2)
    static final String LETRAS = "\0E\nA SIU\rDRJNFCKTZLWHYPQOBG\0MXV\0";
    static final String CIFRAS = "\0003\n- \u000787\r$4',!:(5\")2#6019?&\0./;\0";   // cifras de EE. UU. (las de radioaficionado)

    private void baudot(int c) {
        if (c == 31) { cifras = false; return; }   // LTRS
        if (c == 27) { cifras = true; return; }    // FIGS
        char ch = (cifras ? CIFRAS : LETRAS).charAt(c);
        if (ch == ' ') cifras = false;              // vuelve a letras tras un espacio (USOS, como MMTTY)
        if (ch == '\r' || ch == '\0' || ch == '\u0007') return;
        salida.caracter(ch);
    }

    // ------------------------------------------------------------------ piezas de MMTTY
    /** Resonador IIR de segundo orden (CIIRTANK). */
    private static final class Tanque {
        double z1, z2, a0, b1, b2;
        void ajustar(double f, double smp, double bw) {
            b1 = 2 * Math.exp(-Math.PI * bw / smp) * Math.cos(2 * Math.PI * f / smp);
            b2 = -Math.exp(-2 * Math.PI * bw / smp);
            a0 = bw != 0 ? Math.sin(2 * Math.PI * f / smp) / ((smp / 6.0) / bw) : Math.sin(2 * Math.PI * f / smp);
        }
        double paso(double d) {
            d *= a0;
            d += z1 * b1 + z2 * b2;
            z2 = z1;
            if (Math.abs(d) < 1e-37) d = 0.0;
            z1 = d;
            return d;
        }
    }

    /** Media móvil (CSmooz). */
    private static final class Media {
        final double[] b;
        int wp, cnt;
        double suma;
        Media(int n) { b = new double[Math.max(1, n)]; }
        double media(double d) {
            if (cnt == b.length) suma -= b[wp]; else cnt++;
            b[wp] = d;
            suma += d;
            wp = (wp + 1) % b.length;
            return suma / cnt;
        }
    }
}
