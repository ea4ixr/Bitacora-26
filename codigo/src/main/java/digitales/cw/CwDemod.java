package digitales.cw;

/**
 * Descodificador de CW.
 *
 * Junta dos ideas de dos programas libres:
 *  - fldigi (W1HKJ y colaboradores, GPL v3; parte de CW de Dave Freese y Lawrence Glaister VE7IT): bajar la señal a
 *    banda base con un filtro estrecho que se ajusta a la velocidad, control automático de nivel con suelo de ruido,
 *    detector con histéresis y seguimiento de la velocidad con las parejas punto-raya.
 *  - ggmorse (Georgi Gerganov, licencia MIT): cada poco se repasan los últimos segundos probando muchas velocidades
 *    y umbrales, y se elige la combinación en la que los puntos, rayas y espacios encajan mejor con 1-3-7.
 * Lo segundo se usa para afinar lo primero: el texto sale al momento (como fldigi) pero con el umbral y la velocidad
 * que mejor encajan (como ggmorse). Así aguanta mejor el ruido, el QSB y los cambios de velocidad.
 *
 * Recibe muestras a 12000 por segundo (cualquier escala) y entrega los caracteres por «Salida».
 */
public final class CwDemod {

    public interface Salida { void texto(String s); }

    private static final double FS = 12000;
    private static final int DEC = 24;                 // 12000 / 24 = 500 valores por segundo (2 ms)
    private static final double FD = FS / DEC;

    private final Salida salida;
    private double tonoHz = 700;
    private double fase = 0;

    // diezmado
    private double accI, accQ;
    private int nAcc;

    // filtro paso bajo (complejo) a 500 Hz
    private double[] fir = new double[1];
    private final double[] hI = new double[256], hQ = new double[256];
    private int hp = 0;
    private double anchoHz = -1;

    // alisado de la envolvente
    private double[] alis = new double[1];
    private int ap = 0, an = 0;
    private double asum = 0;

    // control de nivel (fldigi)
    private double sigAvg = 0, ruido = 0, pico = 0;

    // velocidad
    private double ppm = 20;          // velocidad recibida
    private boolean ppmFija = false;
    private double dosPuntos;         // en muestras de 2 ms
    private final double[] seg = new double[16];
    private int segN = 0, segP = 0;

    // detector
    private boolean enTono = false, despues = false, espacioPuesto = true;
    private long t = 0, tIni = 0, tFin = 0;
    private final StringBuilder rep = new StringBuilder();
    private double ultimo = 0;
    private double umbAlto = 0.6, umbBajo = 0.45;   // sobre la envolvente normalizada

    // repaso de los últimos segundos (ggmorse)
    private static final int VENT = (int) (4 * FD);
    private final float[] hist = new float[VENT];
    private int hpos = 0;
    private long hcuenta = 0;
    private double nivelBueno = -1;   // umbral elegido (normalizado); -1 = aún no
    private double coste = 99;
    private boolean usarRepaso = true;

    private static final int MODO = 2;   // 2 = umbral por niveles alto/bajo (lo mejor en las pruebas); 1 = el de fldigi tal cual
    // Umbral por niveles (lo que mejor ha salido en las pruebas con ruido, QSB, CW a mano y cambios de velocidad):
    // nivel alto = el 90 % de los últimos 10 puntos; nivel bajo (ruido) = el 30 % de los últimos 40 puntos
    // (el ruido no tiene QSB, la señal sí); umbral a mitad con un 8 % de histéresis.
    private static final double PH = 0.9, PL = 0.3, FR = 0.5, HY = 0.08, VD = 10, VL = 40, SM = 0.3;
    private static final double HUECO = 0.5;            // huecos de menos de medio punto dentro de un tono: se unen
    private static final double CMAX = 0.3, RAT = 1.3;  // el repaso solo cambia la velocidad si encaja muy bien y es muy distinta
    private final double[] ven = new double[(int) (8 * FD)];
    private int vp = 0, vn2 = 0;
    private double pico2 = 0;
    private double hi = 1, lo = 0;

    // calidad
    private double metrica = 0;

    public CwDemod(Salida salida) {
        this.salida = salida;
        ponerPpm(20);
    }

    /** Tono de la señal en el audio (Hz). */
    public void ponerTono(double hz) { tonoHz = hz; }
    public double tono() { return tonoHz; }

    /** Velocidad (palabras por minuto). Si «fija», no se sigue la velocidad de la señal. */
    public void ponerPpm(double p) {
        ppm = Math.max(5, Math.min(60, p));
        dosPuntos = 2 * puntoMuestras(ppm);
        segN = 0;
        ajustarFiltro();
    }
    public void ponerPpmFija(boolean si) { ppmFija = si; }
    public void ponerRepaso(boolean si) { usarRepaso = si; }
    private boolean silenciador = true;
    private double nivelSql = 2;   // 2: con solo ruido salen unas 5 letras por minuto
    public void ponerSilenciador(boolean si) { silenciador = si; }
    private volatile boolean hayTono = true;
    /** Lo dice la ventana mirando el espectro: si en la frecuencia sintonizada no hay ninguna señal, no se escribe nada. */
    public void ponerHayTono(boolean si) { hayTono = si; }
    /** Cuánto tiene que destacar la señal sobre el ruido (relación entre el nivel alto y el bajo). */
    public void ponerNivelSilenciador(double r) { nivelSql = r; }

    public double ppm() { return ppm; }
    /** Señal sobre ruido aproximada (0..100) para el medidor. */
    public double calidad() { return metrica; }

    private static double puntoMuestras(double ppm) { return 1.2 / ppm * FD; }

    /** El filtro se estrecha o ensancha con la velocidad (como el «filtro adaptado» de fldigi). */
    private void ajustarFiltro() {
        double ancho = Math.max(8, Math.min(200, 2 * ppm));
        if (Math.abs(ancho - anchoHz) < 4) return;
        anchoHz = ancho;
        int n = 63;
        double fc = ancho / FD;
        double[] h = new double[n];
        double s = 0;
        for (int i = 0; i < n; i++) {
            double m = i - (n - 1) / 2.0;
            double sinc = m == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (n - 1));
            h[i] = sinc * w;
            s += h[i];
        }
        for (int i = 0; i < n; i++) h[i] /= s;
        fir = h;
        int la = Math.max(1, (int) Math.round(puntoMuestras(ppm) * 0.5));
        if (la != alis.length) { alis = new double[la]; ap = an = 0; asum = 0; }
    }

    public void procesar(float[] buf, int n) {
        double w = 2 * Math.PI * tonoHz / FS;
        for (int i = 0; i < n; i++) {
            double x = buf[i];
            accI += x * Math.cos(fase);
            accQ -= x * Math.sin(fase);
            fase += w;
            if (fase > 2 * Math.PI) fase -= 2 * Math.PI;
            if (++nAcc == DEC) {
                paso(accI / DEC, accQ / DEC);
                accI = accQ = 0;
                nAcc = 0;
            }
        }
    }

    private void paso(double i, double q) {
        hI[hp] = i;
        hQ[hp] = q;
        double yi = 0, yq = 0;
        int k = hp;
        for (double c : fir) {
            yi += c * hI[k];
            yq += c * hQ[k];
            if (--k < 0) k = hI.length - 1;
        }
        hp = (hp + 1) % hI.length;
        double env = Math.hypot(yi, yq);
        // alisado (la mitad de un punto)
        if (an == alis.length) asum -= alis[ap]; else an++;
        alis[ap] = env;
        asum += env;
        ap = (ap + 1) % alis.length;
        decidir(asum / an);
    }

    private static double media(double m, double v, double peso) { return m + (v - m) / peso; }

    private void decidir(double v) {
        t++;
        // Nivel (fldigi): media, suelo de ruido y pico, con subida rápida y bajada lenta
        sigAvg = media(sigAvg, v, 1000);
        if (v < sigAvg) ruido = media(ruido, v, v < ruido ? 200 : 1000);
        if (v > sigAvg) pico = media(pico, v, v > pico ? 200 : 1000);
        double vn = pico > 0 ? v / pico : 0;
        double nRuido = pico > 0 ? ruido / pico : 0, nSig = pico > 0 ? sigAvg / pico : 0;
        metrica = 0.8 * metrica;
        if (ruido > 1e-12 && ruido < sigAvg) metrica += 0.2 * Math.max(0, Math.min(100, 2.5 * 20 * Math.log10(sigAvg / ruido)));

        hist[hpos] = (float) vn;
        hpos = (hpos + 1) % VENT;
        hcuenta++;
        if (usarRepaso && hcuenta % 125 == 0 && hcuenta >= VENT / 2) repasar();

        if (MODO == 2) {
            ven[vp] = v; vp = (vp + 1) % ven.length; if (vn2 < ven.length) vn2++;
            int nv = Math.min(vn2, (int) (VD * puntoMuestras(ppm)));
            int nl = Math.min(vn2, (int) (VL * puntoMuestras(ppm)));
            if (t % 10 == 0 && nv > 20) {
                double[] c = new double[nv];
                for (int k = 0; k < nv; k++) c[k] = ven[(vp - 1 - k + ven.length) % ven.length];
                java.util.Arrays.sort(c);
                double h2 = c[(int) (nv * PH)], l2 = c[(int) (nv * PL)];
                if (VL > 0) {
                    double[] cl = new double[nl];
                    for (int k = 0; k < nl; k++) cl[k] = ven[(vp - 1 - k + ven.length) % ven.length];
                    java.util.Arrays.sort(cl);
                    l2 = cl[(int) (nl * PL)];
                }
                if (hi == 1 && lo == 0) { hi = h2; lo = l2; } else { hi += SM * (h2 - hi); lo += SM * (l2 - lo); }
            }
            // QSB: además del nivel alto «lento», un pico que sube al momento y baja a la mitad en 4 puntos;
            // así, cuando la señal se hunde, el umbral baja con ella. Ajustado con grabaciones reales de EA4IXR
            // (K3RA con QSB rápido: 55 → 60 palabras bien; IK2JTU con deterioro: 68,5 → 77,7 %; simulado: 72,3 → 75,7 %)
            pico2 = Math.max(v, pico2 * Math.exp(-Math.log(2) / (4 * puntoMuestras(ppm))));
            double hiU = 0.7 * pico2 + 0.3 * hi;
            double mid = lo + FR * (hiU - lo), h = HY * (hiU - lo);
            boolean abierto = !silenciador || (hi > nivelSql * lo && hayTono);
            if (!abierto && !enTono) { consulta(); return; }
            if (v > mid + h && !enTono) bajaTecla();
            if (v < mid - h && enTono) subeTecla();
            consulta();
            return;
        }
        if (usarRepaso && nivelBueno > 0 && coste < 1.0) {
            umbAlto = nivelBueno * 1.1;
            umbBajo = nivelBueno * 0.9;
        } else {
            double dif = nSig - nRuido;
            umbAlto = nSig - 0.2 * dif;
            umbBajo = nRuido + 0.7 * dif;
        }

        if (vn > umbAlto && !enTono) bajaTecla();
        if (vn < umbBajo && enTono) subeTecla();
        consulta();
    }

    private long tIniAnt = 0;
    private double ultimoAnt = 0;

    private void bajaTecla() {
        // Hueco demasiado corto dentro de un tono (ruido o QSB): se une con el tono anterior
        if (despues && rep.length() > 0 && t - tFin < HUECO * dosPuntos / 2) {
            rep.setLength(rep.length() - 1);
            tIni = tIniAnt;
            ultimo = ultimoAnt;
            enTono = true;
            return;
        }
        if (!despues) rep.setLength(0);
        tIni = t;
        enTono = true;
    }

    private void subeTecla() {
        enTono = false;
        tFin = t;
        double d = tFin - tIni;
        double punto = dosPuntos / 2;
        if (d < punto / 2) { if (rep.length() == 0) despues = false; return; }   // chasquido de ruido
        if (MODO == 2 && !ppmFija) { agrupar(d); }
        else if (!ppmFija && ultimo > 0) {
            if (d > 2 * ultimo && d < 4 * ultimo) seguir(ultimo, d);
            if (ultimo > 2 * d && ultimo < 4 * d) seguir(d, ultimo);
        }
        ultimoAnt = ultimo;
        tIniAnt = tIni;
        ultimo = d;
        rep.append(d <= dosPuntos ? '.' : '-');
        if (rep.length() > 8) { rep.setLength(0); despues = false; return; }
        despues = true;
    }

    private final double[] marcas = new double[24];
    private int mN = 0, mP = 0;

    /** Velocidad por los últimos tonos: se separan en cortos (puntos) y largos (rayas) y se miden los dos grupos. */
    private void agrupar(double d) {
        marcas[mP] = d; mP = (mP + 1) % marcas.length; if (mN < marcas.length) mN++;
        if (mN < 6) return;
        double mn = 1e9, mx = 0;
        for (int i = 0; i < mN; i++) { mn = Math.min(mn, marcas[i]); mx = Math.max(mx, marcas[i]); }
        if (mx < 2 * mn) return;   // todos iguales: no se sabe si son puntos o rayas
        double umb = Math.sqrt(mn * mx);
        double p = 0, r = 0;
        for (int it = 0; it < 6; it++) {
            double sp = 0, sr = 0; int np = 0, nr = 0;
            for (int i = 0; i < mN; i++) { if (marcas[i] <= umb) { sp += marcas[i]; np++; } else { sr += marcas[i]; nr++; } }
            if (np == 0 || nr == 0) return;
            p = sp / np; r = sr / nr;
            umb = (p + r) / 2;
        }
        if (r / p < 2 || r / p > 4.5) return;
        double dp = (p + r) / 2;
        if (dp < puntoMuestras(60) * 2 || dp > puntoMuestras(5) * 2) return;
        dosPuntos = dp;
        double nueva = 1.2 * FD / (dosPuntos / 2);
        if (Math.abs(nueva - ppm) > 1) { ppm = nueva; ajustarFiltro(); }
    }

    /** Seguimiento de velocidad con parejas punto-raya (fldigi). */
    private void seguir(double p, double r) {
        double dp = (p + r) / 2;
        if (dp < puntoMuestras(60) * 2 || dp > puntoMuestras(5) * 2) return;
        seg[segP] = dp;
        segP = (segP + 1) % seg.length;
        if (segN < seg.length) segN++;
        double s = 0;
        for (int i = 0; i < segN; i++) s += seg[i];
        dosPuntos = s / segN;
        double nueva = 1.2 * FD / (dosPuntos / 2);
        if (Math.abs(nueva - ppm) > 1) { ppm = nueva; ajustarFiltro(); }
    }

    private void consulta() {
        if (enTono) return;
        double punto = dosPuntos / 2;
        double sil = t - tFin;
        if (despues && sil >= 2 * punto) {
            String s = Morse.texto(rep.toString());
            rep.setLength(0);
            despues = false;
            espacioPuesto = false;
            salida.texto(s == null ? "_" : s);
        } else if (!despues && !espacioPuesto && sil > 5 * punto) {
            espacioPuesto = true;
            salida.texto(" ");
        }
    }

    /**
     * Repaso de los últimos 4 segundos (idea de ggmorse): se prueban velocidades y umbrales y se queda la pareja
     * en la que los tonos miden 1 o 3 puntos y los silencios 1, 3 o 7.
     */
    private void repasar() {
        int n = (int) Math.min(VENT, hcuenta);
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = hist[(int) ((hpos - n + i + VENT) % VENT)];
        double mejor = 1e9, mejorNivel = -1, mejorPpm = ppm;
        for (int pass = 0; pass < 2; pass++) {
            double s0, s1, ds, l0, l1, dl;
            if (pass == 0) { s0 = 5; s1 = 60; ds = 2.5; l0 = 0.15; l1 = 0.85; dl = 0.07; }
            else { s0 = Math.max(5, mejorPpm - 2.5); s1 = Math.min(60, mejorPpm + 2.5); ds = 0.5;
                   l0 = Math.max(0.1, mejorNivel - 0.07); l1 = Math.min(0.9, mejorNivel + 0.07); dl = 0.02;
                   if (mejorNivel < 0) break; }
            for (double l = l0; l <= l1 + 1e-9; l += dl) {
                // tramos por encima/debajo del nivel
                int[] ini = new int[n + 1], fin = new int[n + 1];
                boolean[] on = new boolean[n + 1];
                int m = 0;
                boolean est = x[0] > l;
                int st = 0;
                for (int i = 1; i < n; i++) {
                    boolean e = x[i] > l;
                    if (e != est) { on[m] = est; ini[m] = st; fin[m] = i; m++; est = e; st = i; }
                }
                if (m < 6) continue;
                for (double s = s0; s <= s1 + 1e-9; s += ds) {
                    double pm = puntoMuestras(s);
                    double cP = 0, cR = 0, cE = 0, sumP = 0, sumR = 0;
                    int nP = 0, nR = 0, nE = 0;
                    for (int k = 1; k < m; k++) {   // el primer tramo está cortado
                        double len = (fin[k] - ini[k]) / pm;
                        if (on[k]) {
                            if (len <= 2) { nP++; sumP += len; cP += (len - 1) * (len - 1); }
                            else { nR++; sumR += len; cR += (len - 3) * (len - 3); }
                        } else if (len < 9) {
                            double c1 = (len - 1) * (len - 1), c3 = (len - 3) * (len - 3), c7 = (len - 7) * (len - 7);
                            cE += Math.min(c1, Math.min(c3, c7));
                            nE++;
                        }
                    }
                    if (nP == 0 || nR == 0 || nE == 0) continue;
                    double c = cP / nP + cR / nR + cE / nE;
                    double rel = (sumR / nR) / (sumP / nP);
                    if (rel < 2.3 || rel > 3.8) c += 100;
                    if (c < mejor) { mejor = c; mejorNivel = l; mejorPpm = s; }
                }
            }
        }
        coste = mejor;
        if (mejor < CMAX && mejorNivel > 0) {
            nivelBueno = mejorNivel;
            if (!ppmFija && (mejorPpm > ppm * RAT || mejorPpm < ppm / RAT)) {
                ppm = mejorPpm;
                dosPuntos = 2 * puntoMuestras(ppm);
                segN = 0;
                ajustarFiltro();
            }
        }
    }
}
