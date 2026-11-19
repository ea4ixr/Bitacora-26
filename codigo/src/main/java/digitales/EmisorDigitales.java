package digitales;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Transmisor de los modos digitales: en el momento justo de cada periodo pone la emisora en transmisión (PTT),
 * saca por la tarjeta de sonido el audio del mensaje y la vuelve a recepción.
 * Solo transmite mientras la ventana le dé un "plan" (qué mensaje, a qué frecuencia y en qué periodo);
 * parar() corta al momento.
 */
public final class EmisorDigitales {

    /** Pone la emisora en transmisión (true) o en recepción (false). Debe lanzar una excepción si no puede. */
    public interface Ptt { void poner(boolean transmitir) throws Exception; }

    /** Lo que el emisor avisa a la ventana (desde su propio hilo, no desde Swing). */
    public interface Avisos {
        /** Ha empezado a transmitir el mensaje en el periodo que empieza en inicioPeriodo (ms UTC). */
        default void empieza(long inicioPeriodo, Plan plan) { }
        /** Ha terminado de transmitir (completo = false si se cortó antes de acabar). */
        default void termina(long inicioPeriodo, Plan plan, boolean completo) { }
        /** Algo ha ido mal y se ha dejado de transmitir. */
        default void error(String texto) { }
    }

    /**
     * Qué transmitir: el texto (para mostrarlo), sus tonos, la frecuencia de audio, el modo y el turno
     * (primerTurno = periodos pares: en FT8 los segundos 00 y 30).
     */
    public record Plan(String texto, int[] tonos, double hz, GeneradorFtx.Modo modo, boolean primerTurno,
                       java.util.List<int[]> otrosTonos, double[] otrosHz) {
        public Plan(String texto, int[] tonos, double hz, GeneradorFtx.Modo modo, boolean primerTurno) {
            this(texto, tonos, hz, modo, primerTurno, null, null);
        }
    }

    /** Audio del plan: una señal, o varias a la vez (modo Fox de las expediciones), repartiendo el volumen. */
    static float[] audioDe(Plan p) {
        float[] a = GeneradorFtx.audio(p.tonos(), p.hz(), p.modo());
        if (p.otrosTonos() == null || p.otrosTonos().isEmpty()) return a;
        int n = 1 + p.otrosTonos().size();
        for (int k = 0; k < p.otrosTonos().size(); k++) {
            float[] b = GeneradorFtx.audio(p.otrosTonos().get(k), p.otrosHz()[k], p.modo());
            for (int i = 0; i < a.length && i < b.length; i++) a[i] += b[i];
        }
        for (int i = 0; i < a.length; i++) a[i] /= n;
        return a;
    }

    /** Por dónde sale el audio: la tarjeta de sonido (en las pruebas, otra cosa). */
    public interface Altavoz {
        int tasa();
        int canales();
        void escribir(byte[] b, int n) throws Exception;
        /** Termina: esperando a que suene todo (true) o tirando lo que quede (false). */
        void terminar(boolean esperar);
    }

    /** Abre la salida de sonido por su nombre (vacío = la de por defecto). */
    public interface AbridorAltavoz { Altavoz abrir(String nombre) throws Exception; }

    /** Cuánto se puede empezar tarde dentro del periodo (como WSJT-X, que también sale tarde si hace falta). */
    private static final long TARDE_FT8_MS = 2500, TARDE_FT4_MS = 1000;
    /** Tiempo para que la emisora pase a transmisión antes de que empiece el audio. */
    private static final long ANTES_PTT_MS = 250;
    /** Afinar: como mucho este tiempo seguido. */
    public static final long AFINAR_MAX_MS = 30_000;

    private final Ptt ptt;
    private final Avisos avisos;
    private final AbridorAltavoz abridor;
    private volatile Plan plan;
    private volatile String salida = "";
    private volatile double nivelDb = -16;
    /** Último periodo ya descodificado (para no salir antes de saber qué contestar). */
    private volatile long descodificadoHasta = Long.MIN_VALUE;
    private volatile boolean esperarDescodificacion = true;
    private volatile boolean enMarcha = true;
    private volatile boolean cortar = false;
    private volatile boolean transmitiendo = false;
    private volatile boolean afinando = false;
    private volatile long ultimoPeriodoHecho = Long.MIN_VALUE;
    private final Thread hilo;

    public EmisorDigitales(Ptt ptt, Avisos avisos) { this(ptt, avisos, EmisorDigitales::tarjeta); }

    public EmisorDigitales(Ptt ptt, Avisos avisos, AbridorAltavoz abridor) {
        this.ptt = ptt;
        this.avisos = avisos;
        this.abridor = abridor;
        hilo = new Thread(this::bucle, "EmisorDigitales");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.MAX_PRIORITY);
        hilo.start();
    }

    // ------------------------------------------------------------------ lo que manda la ventana
    /** Plan para los próximos periodos (null = no transmitir). */
    public void ponerPlan(Plan p) { plan = p; }
    public Plan plan() { return plan; }
    /** Nombre de la salida de sonido (vacío = la de por defecto). */
    public void ponerSalida(String nombre) { salida = nombre == null ? "" : nombre; }
    /** Nivel del audio en dB (0 = el máximo, -40 = muy bajo). */
    public void ponerNivelDb(double db) { nivelDb = Math.max(-60, Math.min(0, db)); }
    /** Avisa de que el periodo que empezó en inicio (ms UTC) ya está descodificado. */
    public void descodificado(long inicio) { if (inicio > descodificadoHasta) descodificadoHasta = inicio; }
    /** Si no se está escuchando, no hay que esperar a ninguna descodificación. */
    public void ponerEsperarDescodificacion(boolean si) { esperarDescodificacion = si; }
    public boolean transmitiendo() { return transmitiendo; }
    public boolean afinando() { return afinando; }

    /** Corta ya cualquier transmisión (también afinar) y quita el plan. */
    public void parar() {
        plan = null;
        afinando = false;
        cortar = true;
    }

    /** Para del todo (al cerrar la ventana). */
    public void cerrar() {
        parar();
        enMarcha = false;
        hilo.interrupt();
    }

    /** Afinar: un tono fijo a la frecuencia dada, ya mismo, durante como mucho 30 segundos. */
    public void afinar(double hz) {
        if (transmitiendo) return;
        afinando = true;
        cortar = false;
        Thread t = new Thread(() -> transmitirTono(hz), "AfinarDigitales");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ salidas de sonido
    /** Nombres de las salidas de sonido. */
    /** Por qué canal sale el audio hacia la emisora: MONO, IZQ, DCHA o AUTO (los dos). Lo pone «Archivo → Configurar audio». */
    public static volatile String CANAL_SALIDA = "AUTO";

    /** Canal de salida de esta ventana (null = el de «Configurar audio»). DIGITALES tiene el suyo. */
    private volatile String canalSalida = null;
    public void ponerCanalSalida(String c) { canalSalida = c; }

    private boolean canalSale(int c, int canales) {
        if (canales < 2) return true;
        switch (canalSalida != null ? canalSalida : CANAL_SALIDA) {
            case "IZQ": return c == 0;
            case "DCHA": return c == 1;
            default: return true;
        }
    }

    public static List<String> salidas() {
        List<String> r = new ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(mi);
                if (m.isLineSupported(new Line.Info(SourceDataLine.class))) r.add(mi.getName());
            } catch (Exception ignored) { }
        }
        return r;
    }

    /** Para otras ventanas (WSPR): abre la salida de sonido por su nombre (vacío = la de por defecto). */
    public static Altavoz abrirAltavoz(String nombre) throws Exception { return tarjeta(nombre); }

    /** La salida de la tarjeta de sonido, como Altavoz. */
    static Altavoz tarjeta(String nombre) throws Exception {
        SourceDataLine l = abrirSalida(nombre);
        return new Altavoz() {
            @Override public int tasa() { return (int) l.getFormat().getSampleRate(); }
            @Override public int canales() { return l.getFormat().getChannels(); }
            @Override public void escribir(byte[] b, int n) { l.write(b, 0, n); }
            @Override public void terminar(boolean esperar) {
                try { if (esperar) l.drain(); else l.flush(); } catch (Exception ignored) { }
                try { l.stop(); } catch (Exception ignored) { }
                try { l.close(); } catch (Exception ignored) { }
            }
        };
    }

    /** Abre la salida (por nombre; vacío = la de por defecto). Devuelve la línea ya abierta y arrancada. */
    static SourceDataLine abrirSalida(String nombre) throws Exception {
        Mixer mixer = null;
        if (nombre != null && !nombre.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) if (mi.getName().equals(nombre)) mixer = AudioSystem.getMixer(mi);
            if (mixer == null) throw new Exception("no se encuentra la salida de sonido «" + nombre + "»");
        }
        int[] tasas = {48000, 44100, 12000, 24000, 96000};
        Exception ultimo = null;
        for (int canales : new int[]{1, 2}) {
            for (int tasa : tasas) {
                AudioFormat f = new AudioFormat(tasa, 16, canales, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, f);
                try {
                    SourceDataLine l = (SourceDataLine) (mixer != null ? mixer.getLine(info) : AudioSystem.getLine(info));
                    l.open(f, tasa * canales * 2 / 5);   // 0,2 s de búfer
                    l.start();
                    return l;
                } catch (Exception e) { ultimo = e; }
            }
        }
        throw new Exception("no se puede abrir la salida de sonido" + (ultimo != null ? ": " + ultimo.getMessage() : ""));
    }

    /**
     * Pasa audio de 12000 muestras/s a otra frecuencia de muestreo (interpolación con sinc y ventana de Blackman).
     * Los pesos se calculan una sola vez por cada "fase" (las posiciones se repiten), así es rápido.
     */
    public static float[] remuestrear(float[] x, int fsSalida) {
        if (fsSalida == MotorDigitales.FS) return x.clone();
        final int M = 16;   // muestras a cada lado
        int g = mcd(MotorDigitales.FS, fsSalida);
        int L = fsSalida / g, D = MotorDigitales.FS / g;   // salida j -> entrada j*D/L
        double paso = D / (double) L;
        double fc = Math.min(1.0, 1.0 / paso) * 0.9;   // corte un poco por debajo de la mitad de 12000 (o de la salida)
        double[][] pesos = new double[L][2 * M];
        for (int fase = 0; fase < L; fase++) {
            double frac = fase / (double) L;   // parte fraccionaria de la posición
            for (int q = 0; q < 2 * M; q++) {
                double d = frac - (q - M + 1);   // distancia a la muestra k0 - M + 1 + q
                double sinc = d == 0 ? fc : Math.sin(Math.PI * fc * d) / (Math.PI * d);
                double w = Math.abs(d) >= M ? 0 : 0.42 + 0.5 * Math.cos(Math.PI * d / M) + 0.08 * Math.cos(2 * Math.PI * d / M);
                pesos[fase][q] = sinc * w;
            }
        }
        int n = (int) ((long) x.length * L / D);
        float[] y = new float[n];
        for (int j = 0; j < n; j++) {
            long pos = (long) j * D;
            int k0 = (int) (pos / L);
            double[] p = pesos[(int) (pos % L)];
            double s = 0;
            int base = k0 - M + 1;
            for (int q = 0; q < 2 * M; q++) {
                int k = base + q;
                if (k >= 0 && k < x.length) s += x[k] * p[q];
            }
            y[j] = (float) s;
        }
        return y;
    }

    private static int mcd(int a, int b) { return b == 0 ? a : mcd(b, a % b); }

    /** Cuándo empieza la señal dentro del periodo: FT8 a los 0,5 s; FT4 un símbolo (48 ms) antes, por la rampa. */
    static long inicioSenalMs(GeneradorFtx.Modo m) { return m == GeneradorFtx.Modo.FT8 ? 500 : 452; }

    // ------------------------------------------------------------------ el bucle
    private void bucle() {
        while (enMarcha) {
            try { Thread.sleep(20); } catch (InterruptedException e) { if (!enMarcha) return; }
            Plan p = plan;
            if (p == null || afinando || transmitiendo) continue;
            long per = p.modo() == GeneradorFtx.Modo.FT8 ? 15000 : 7500;
            long ahora = System.currentTimeMillis();
            long inicio = Math.floorDiv(ahora, per) * per;
            if (inicio == ultimoPeriodoHecho) continue;
            boolean turnoPar = Math.floorMod(inicio / per, 2) == 0;
            if (turnoPar != p.primerTurno()) continue;
            long tarde = p.modo() == GeneradorFtx.Modo.FT8 ? TARDE_FT8_MS : TARDE_FT4_MS;
            long empiezaSenal = inicio + inicioSenalMs(p.modo());
            if (ahora > empiezaSenal + tarde) { ultimoPeriodoHecho = inicio; continue; }   // demasiado tarde: al siguiente
            // Esperar a saber qué ha dicho la otra estación en el periodo anterior (si se está escuchando)
            if (esperarDescodificacion && descodificadoHasta < inicio - per && ahora < empiezaSenal + tarde - 300) continue;
            ultimoPeriodoHecho = inicio;
            p = plan;   // el plan puede haber cambiado con la descodificación
            if (p == null) continue;
            transmitirPeriodo(inicio, p);
        }
    }

    private void transmitirPeriodo(long inicio, Plan p) {
        transmitiendo = true;
        cortar = false;
        Altavoz linea = null;
        boolean completo = false;
        boolean pttPuesto = false;
        try {
            float[] a12 = audioDe(p);
            linea = abridor.abrir(salida);
            int fs = linea.tasa(), canales = linea.canales();
            float[] a = remuestrear(a12, fs);
            double amp = Math.pow(10, nivelDb / 20.0) * 32000;
            if (cortar) return;
            ptt.poner(true);
            pttPuesto = true;
            avisos.empieza(inicio, p);
            long empiezaSenal = inicio + inicioSenalMs(p.modo());
            long espera = Math.max(empiezaSenal, System.currentTimeMillis() + ANTES_PTT_MS) - System.currentTimeMillis();
            if (espera > 0) Thread.sleep(espera);
            // si se sale tarde, se salta el trozo que ya ha pasado (así cada símbolo va en su sitio)
            long retraso = System.currentTimeMillis() - empiezaSenal;
            int desde = (int) Math.max(0, Math.min(a.length, retraso * fs / 1000));
            int trozo = fs / 20;   // 50 ms
            byte[] b = new byte[trozo * canales * 2];
            long tarde = p.modo() == GeneradorFtx.Modo.FT8 ? TARDE_FT8_MS : TARDE_FT4_MS;
            for (int i = desde; i < a.length && !cortar; i += trozo) {
                // La contestación del otro ha llegado ya empezado mi turno: se cambia al mensaje nuevo
                // (como salir tarde con él), mientras aún dé tiempo. Así no se pierde un turno entero.
                Plan q = plan;
                if (q != null && q != p && !q.texto().equals(p.texto()) && q.modo() == p.modo() && q.primerTurno() == p.primerTurno()
                        && System.currentTimeMillis() - empiezaSenal <= tarde) {
                    p = q;
                    a = remuestrear(audioDe(p), fs);
                    i = (int) Math.max(0, Math.min(a.length, (System.currentTimeMillis() - empiezaSenal) * fs / 1000));
                    avisos.empieza(inicio, p);
                    if (i >= a.length) break;
                }
                int n = Math.min(trozo, a.length - i);
                for (int k = 0; k < n; k++) {
                    int v = (int) Math.round(a[i + k] * amp);
                    v = Math.max(-32767, Math.min(32767, v));
                    for (int c = 0; c < canales; c++) {
                        int o = (k * canales + c) * 2;
                        int vc = canalSale(c, canales) ? v : 0;   // izquierdo, derecho o los dos («Configurar audio»)
                        b[o] = (byte) vc;
                        b[o + 1] = (byte) (vc >> 8);
                    }
                }
                linea.escribir(b, n * canales * 2);
            }
            completo = !cortar;
            linea.terminar(completo);
            linea = null;
        } catch (InterruptedException e) {
            // se está cerrando
        } catch (Exception e) {
            plan = null;
            avisos.error("No se ha podido transmitir: " + e.getMessage());
        } finally {
            if (linea != null) linea.terminar(false);
            if (pttPuesto || cortar) quitarPtt();
            transmitiendo = false;
            if (pttPuesto) avisos.termina(inicio, p, completo);
        }
    }

    private void transmitirTono(double hz) {
        transmitiendo = true;
        Altavoz linea = null;
        boolean pttPuesto = false;
        try {
            linea = abridor.abrir(salida);
            int fs = linea.tasa(), canales = linea.canales();
            double amp = Math.pow(10, nivelDb / 20.0) * 32000;
            ptt.poner(true);
            pttPuesto = true;
            Thread.sleep(ANTES_PTT_MS);
            int trozo = fs / 20;
            byte[] b = new byte[trozo * canales * 2];
            double fase = 0, dfase = 2 * Math.PI * hz / fs;
            long fin = System.currentTimeMillis() + AFINAR_MAX_MS;
            int rampa = fs / 100, hechas = 0;
            while (afinando && !cortar && System.currentTimeMillis() < fin) {
                for (int k = 0; k < trozo; k++) {
                    double env = Math.min(1.0, (hechas + k) / (double) rampa);
                    int v = (int) Math.round(Math.sin(fase) * amp * env);
                    fase += dfase;
                    if (fase > 2 * Math.PI) fase -= 2 * Math.PI;
                    for (int c = 0; c < canales; c++) {
                        int o = (k * canales + c) * 2;
                        int vc = canalSale(c, canales) ? v : 0;   // izquierdo, derecho o los dos («Configurar audio»)
                        b[o] = (byte) vc;
                        b[o + 1] = (byte) (vc >> 8);
                    }
                }
                hechas += trozo;
                linea.escribir(b, b.length);
            }
            linea.terminar(false);
            linea = null;
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            avisos.error("No se ha podido afinar: " + e.getMessage());
        } finally {
            if (linea != null) linea.terminar(false);
            if (pttPuesto) quitarPtt();
            afinando = false;
            transmitiendo = false;
            avisos.termina(-1, null, true);
        }
    }

    private void quitarPtt() {
        // se intenta dos veces: que la emisora no se quede nunca transmitiendo
        for (int i = 0; i < 2; i++) {
            try { ptt.poner(false); return; } catch (Exception e) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            }
        }
        avisos.error("¡Atención! No se ha podido devolver la emisora a recepción: pulsa su tecla de recepción.");
    }
}
