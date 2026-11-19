package digitales;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Motor de los modos digitales: escucha la tarjeta de sonido de la emisora, lleva el reloj de los periodos
 * (15 s en FT8, 7,5 s en FT4), descodifica al final de cada periodo y da la cascada.
 * No transmite nada.
 */
public final class MotorDigitales {
    public static final int FS = 12000;
    private static final int TAM_ANILLO = FS * 64;   // 64 s de audio

    /** Lo que el motor avisa a la ventana (se llama desde hilos del motor, no desde Swing). */
    public interface Oyente {
        /** Una línea de la cascada: potencia en dB de 0 a 3200 Hz (paso en Hz por casilla). */
        default void lineaCascada(float[] db, double hzPorCasilla) { }
        /** Nivel de audio de entrada en dB (0..90 aprox.). */
        default void nivel(double db) { }
        /** Resultados de un periodo. inicioPeriodo = milisegundos UTC del comienzo del periodo. */
        default void resultados(long inicioPeriodo, GeneradorFtx.Modo modo, List<DescodificadorFt8.Resultado> r, long msTardado) { }
        /**
         * Primera pasada rápida del periodo (solo en FT8, antes de la completa): sirve para contestar a tiempo
         * en el turno siguiente, como WSJT-X. Después llegan igualmente los resultados completos.
         */
        default void rapidos(long inicioPeriodo, GeneradorFtx.Modo modo, List<DescodificadorFt8.Resultado> r) { }
        /** Mensaje para la barra de estado. */
        default void estado(String texto) { }
    }

    /** Fuente de audio (la tarjeta de sonido, o un fichero en las pruebas). Devuelve muestras a 12000/s. */
    public interface Fuente extends AutoCloseable {
        /** Lee audio (bloquea hasta tener algo). Devuelve el número de muestras puestas en buf (12000/s, escala 16 bits). */
        int leer(float[] buf) throws Exception;
        @Override void close();
    }

    private final Ft8Mensaje mensajes = new Ft8Mensaje();
    private final DescodificadorFt8 ft8 = new DescodificadorFt8(mensajes);
    private final DescodificadorFt4 ft4 = new DescodificadorFt4(mensajes);
    private final Oyente oyente;
    private final float[] anillo = new float[TAM_ANILLO];
    private volatile long total = 0;           // muestras recibidas desde el principio
    private final Object cerrojo = new Object();
    // Reloj: hora (ms UTC) de la muestra 0 = mínimo de (hora de lectura - total/FS) en los últimos segundos
    private final long[] desfases = new long[200];
    private int nDesfases = 0, posDesfase = 0;
    private volatile Fuente fuente;
    private volatile boolean enMarcha = false;
    private volatile GeneradorFtx.Modo modo = GeneradorFtx.Modo.FT8;
    private volatile DescodificadorFt8.Opciones opciones = new DescodificadorFt8.Opciones();
    private final ExecutorService hiloDescodificar = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DescodificarDigitales");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private volatile long ultimoPeriodoHecho = -1;
    /** Reloj para las pruebas (en la realidad, el del ordenador). */
    private final java.util.function.LongSupplier reloj;

    public MotorDigitales(Oyente oyente) { this(oyente, System::currentTimeMillis); }

    public MotorDigitales(Oyente oyente, java.util.function.LongSupplier reloj) {
        this.oyente = oyente;
        this.reloj = reloj;
    }

    /**
     * Copia del audio que está escuchando DIGITALES, para otras ventanas (WSPR) cuando la tarjeta ya está ocupada.
     * Si no hay nadie conectado (null), no hace nada.
     */
    public static volatile java.util.function.BiConsumer<float[], Integer> DERIVACION;

    /** Fuente que recibe el audio que escucha DIGITALES (cuando la tarjeta ya la está usando DIGITALES). */
    public static Fuente audioDeDigitales() {
        java.util.concurrent.LinkedBlockingQueue<float[]> cola = new java.util.concurrent.LinkedBlockingQueue<>(2000);
        java.util.function.BiConsumer<float[], Integer> c = (b, n) -> cola.offer(java.util.Arrays.copyOf(b, n));
        DERIVACION = c;
        return new Fuente() {
            float[] resto = new float[0];
            int pos = 0;
            @Override public int leer(float[] buf) {
                try {
                    if (pos >= resto.length) {
                        float[] x = cola.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                        if (x == null) return 0;
                        resto = x; pos = 0;
                    }
                    int n = Math.min(buf.length, resto.length - pos);
                    System.arraycopy(resto, pos, buf, 0, n);
                    pos += n;
                    return n;
                } catch (InterruptedException e) { return 0; }
            }
            @Override public void close() { if (DERIVACION == c) DERIVACION = null; }
        };
    }

    public Ft8Mensaje mensajes() { return mensajes; }
    public GeneradorFtx.Modo modo() { return modo; }
    public void ponerModo(GeneradorFtx.Modo m) { modo = m; ultimoPeriodoHecho = -1; }
    public void ponerOpciones(DescodificadorFt8.Opciones o) { opciones = o; }
    public boolean enMarcha() { return enMarcha; }

    /** Duración del periodo en ms. */
    public long periodoMs() { return modo == GeneradorFtx.Modo.FT8 ? 15000 : 7500; }

    // ------------------------------------------------------------------ dispositivos
    /** Nombres de las entradas de sonido que sirven (micrófono, línea, la tarjeta USB de la emisora...). */
    public static List<String> entradas() {
        List<String> r = new ArrayList<>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(mi);
                if (m.isLineSupported(new Line.Info(TargetDataLine.class))) r.add(mi.getName());
            } catch (Exception ignored) { }
        }
        return r;
    }

    /** Nombres de las salidas de sonido (para transmitir, más adelante). */
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

    /** Qué canal de la entrada se usa: AUTO (el que más suena), IZQ, DCHA o MONO (los dos mezclados). Lo pone «Archivo → Configurar audio». */
    public static volatile String CANAL = "AUTO";

    /** Elige la muestra según CANAL. «e» guarda la energía de cada canal (para AUTO). */
    public static double elegirCanal(double izq, double dcha, double[] e) { return elegirCanal(CANAL, izq, dcha, e); }

    /** Igual, con un canal concreto (DIGITALES tiene el suyo). */
    public static double elegirCanal(String canal, double izq, double dcha, double[] e) {
        switch (canal == null ? CANAL : canal) {
            case "IZQ": return izq;
            case "DCHA": return dcha;
            case "MONO": case "AMBOS": return 0.5 * (izq + dcha);
            default:
                e[0] = e[0] * 0.99995 + izq * izq * 0.00005;
                e[1] = e[1] * 0.99995 + dcha * dcha * 0.00005;
                if (e[1] > 4 * e[0]) return dcha;
                if (e[0] > 4 * e[1]) return izq;
                return 0.5 * (izq + dcha);
        }
    }

    /** Fuente que lee de una entrada de la tarjeta de sonido (por nombre; vacío = la de por defecto). */
    public static Fuente tarjeta(String nombre) throws Exception { return tarjeta(nombre, null); }

    /** Igual, con un canal concreto (null = el de «Configurar audio»). */
    public static Fuente tarjeta(String nombre, String canal) throws Exception {
        Mixer mixer = null;
        if (nombre != null && !nombre.isBlank())
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) if (mi.getName().equals(nombre)) mixer = AudioSystem.getMixer(mi);
        int[] tasas = {12000, 48000, 24000, 96000, 44100, 32000, 22050, 16000, 8000};
        Exception ultimo = null;
        for (int canales : new int[]{2, 1}) {   // estéreo primero: se usa el canal por el que llega el audio (izquierdo o derecho)
            for (int tasa : tasas) {
                AudioFormat f = new AudioFormat(tasa, 16, canales, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, f);
                try {
                    TargetDataLine linea = (TargetDataLine) (mixer != null ? mixer.getLine(info) : AudioSystem.getLine(info));
                    linea.open(f, tasa * canales * 2 / 5);   // 0,2 s de búfer
                    linea.start();
                    FuenteTarjeta ft = new FuenteTarjeta(linea, tasa, canales);
                    ft.canal = canal;
                    return ft;
                } catch (Exception e) { ultimo = e; }
            }
        }
        throw new Exception("No se puede abrir la entrada de sonido" + (ultimo != null ? ": " + ultimo.getMessage() : ""));
    }

    /** Entrada de la tarjeta, pasada a 12000 muestras/s con un filtro paso bajo. */
    private static final class FuenteTarjeta implements Fuente {
        private final TargetDataLine linea;
        private final int tasa, canales;
        private final byte[] bytes;
        private final double[] fir;
        private final double[] historia;
        private int posHist = 0;
        private final double[] energia = new double[2];
        String canal;   // null = el de «Configurar audio»
        private double fase = 0;   // posición fraccionaria de la siguiente muestra de salida
        private final double paso;

        FuenteTarjeta(TargetDataLine linea, int tasa, int canales) {
            this.linea = linea;
            this.tasa = tasa;
            this.canales = canales;
            this.bytes = new byte[(tasa / 20) * canales * 2];   // 50 ms
            this.paso = tasa / (double) FS;
            // filtro paso bajo (corte 5 kHz) con ventana de Blackman, solo si hay que bajar la frecuencia
            int nt = tasa > FS ? 4 * (int) Math.ceil(paso * 8) + 1 : 1;
            fir = new double[nt];
            if (nt == 1) fir[0] = 1;
            else {
                double fc = 5000.0 / tasa, s = 0;
                for (int i = 0; i < nt; i++) {
                    int m = i - nt / 2;
                    double h = m == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
                    double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (nt - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (nt - 1));
                    fir[i] = h * w;
                    s += fir[i];
                }
                for (int i = 0; i < nt; i++) fir[i] /= s;
            }
            historia = new double[nt];
        }

        @Override public int leer(float[] buf) {
            int n = linea.read(bytes, 0, bytes.length);
            int muestras = n / (2 * canales);
            int k = 0;
            double ultima = 0, actual = 0;
            for (int i = 0; i < muestras; i++) {
                int o = i * 2 * canales;
                double x = (short) ((bytes[o] & 0xFF) | (bytes[o + 1] << 8));
                if (canales == 2) x = elegirCanal(canal, x, (short) ((bytes[o + 2] & 0xFF) | (bytes[o + 3] << 8)), energia);   // izquierdo, derecho o el que más suena
                historia[posHist] = x;
                posHist = (posHist + 1) % historia.length;
                if (tasa == FS) { if (k < buf.length) buf[k++] = (float) x; continue; }
                // salida filtrada en esta muestra de entrada
                double y = 0;
                int idx = posHist;
                for (int t = 0; t < fir.length; t++) { y += fir[t] * historia[idx]; idx = (idx + 1) % historia.length; }
                ultima = actual;
                actual = y;
                // interpolación lineal entre la muestra anterior y esta para las salidas que caen aquí
                while (fase <= 1.0) {
                    if (k < buf.length) buf[k++] = (float) (ultima + (actual - ultima) * fase);
                    fase += paso;
                }
                fase -= 1.0;
            }
            return k;
        }

        @Override public void close() {
            try { linea.stop(); } catch (Exception ignored) { }
            try { linea.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ marcha
    /** Empieza a escuchar la fuente. */
    public synchronized void arrancar(Fuente f) {
        parar();
        fuente = f;
        enMarcha = true;
        synchronized (cerrojo) { total = 0; nDesfases = 0; posDesfase = 0; }
        ultimoPeriodoHecho = -1;
        Thread t = new Thread(this::bucleCaptura, "CapturaDigitales");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        Thread v = new Thread(this::bucleReloj, "RelojDigitales");
        v.setDaemon(true);
        v.start();
    }

    public synchronized void parar() {
        enMarcha = false;
        Fuente f = fuente;
        fuente = null;
        if (f != null) f.close();
    }

    private void bucleCaptura() {
        float[] buf = new float[FS];
        float[] ventana = new float[4096];
        double[] hann = new double[4096];
        for (int i = 0; i < 4096; i++) hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / 4096);
        double[] re = new double[4096], im = new double[4096];
        long ultimaLinea = 0;
        Fuente f = fuente;
        try {
            while (enMarcha && f == fuente) {
                int n = f.leer(buf);
                if (n <= 0) continue;
                java.util.function.BiConsumer<float[], Integer> der = DERIVACION;
                if (der != null) try { der.accept(buf, n); } catch (Throwable ignored) { }
                long ahora = reloj.getAsLong();
                double sumaCuadrados = 0;
                synchronized (cerrojo) {
                    for (int i = 0; i < n; i++) {
                        anillo[(int) ((total + i) % TAM_ANILLO)] = buf[i];
                        sumaCuadrados += buf[i] * buf[i];
                    }
                    total += n;
                    // la última muestra leída llegó, como muy tarde, ahora
                    long d = ahora - Math.round(total * 1000.0 / FS);
                    desfases[posDesfase] = d;
                    posDesfase = (posDesfase + 1) % desfases.length;
                    if (nDesfases < desfases.length) nDesfases++;
                }
                double rms = Math.sqrt(sumaCuadrados / n);
                oyente.nivel(rms > 0 ? 20 * Math.log10(rms) : 0);
                // cascada: una línea cada 0,1 s con las últimas 4096 muestras
                if (total - ultimaLinea >= FS / 10 && total >= 4096) {
                    ultimaLinea = total;
                    synchronized (cerrojo) {
                        for (int i = 0; i < 4096; i++) ventana[i] = anillo[(int) ((total - 4096 + i) % TAM_ANILLO)];
                    }
                    for (int i = 0; i < 4096; i++) { re[i] = ventana[i] * hann[i]; im[i] = 0; }
                    Fft.de(4096).transformar(re, im, false);
                    double hz = FS / 4096.0;
                    int nb = (int) (3200 / hz);
                    float[] db = new float[nb];
                    for (int i = 0; i < nb; i++) db[i] = (float) (10 * Math.log10(re[i] * re[i] + im[i] * im[i] + 1e-9));
                    oyente.lineaCascada(db, hz);
                }
            }
        } catch (Exception e) {
            if (enMarcha) oyente.estado("Se ha cortado la entrada de sonido: " + e.getMessage());
        }
    }

    /** Hora UTC (ms) de una muestra. */
    private long horaMuestra(long s) {
        synchronized (cerrojo) {
            long min = Long.MAX_VALUE;
            for (int i = 0; i < nDesfases; i++) min = Math.min(min, desfases[i]);
            return min + Math.round(s * 1000.0 / FS);
        }
    }

    /** Número de muestra que corresponde a una hora UTC (ms). */
    private long muestraDeHora(long ms) {
        synchronized (cerrojo) {
            long min = Long.MAX_VALUE;
            for (int i = 0; i < nDesfases; i++) min = Math.min(min, desfases[i]);
            return Math.round((ms - min) * FS / 1000.0);
        }
    }

    private void bucleReloj() {
        while (enMarcha) {
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            if (nDesfases == 0) continue;
            GeneradorFtx.Modo m = modo;
            long per = m == GeneradorFtx.Modo.FT8 ? 15000 : 7500;
            long nmax = m == GeneradorFtx.Modo.FT8 ? DescodificadorFt8.NMAX : DescodificadorFt4.NMAX;
            long disparo = m == GeneradorFtx.Modo.FT8 ? 14700 : 6150;   // cuándo se descodifica dentro del periodo
            long ahora = reloj.getAsLong();
            long inicio = Math.floorDiv(ahora, per) * per;
            if (ahora - inicio < disparo || inicio == ultimoPeriodoHecho) continue;
            ultimoPeriodoHecho = inicio;
            long s0 = muestraDeHora(inicio);
            long hasta = total;
            // hace falta audio de casi todo el periodo (si se ha empezado a escuchar a mitad, se espera al siguiente)
            if (s0 < hasta - TAM_ANILLO || hasta - Math.max(s0, 0) < (m == GeneradorFtx.Modo.FT8 ? FS * 12L : FS * 5L)) continue;
            float[] audio = new float[(int) nmax];
            synchronized (cerrojo) {
                for (int i = 0; i < nmax; i++) {
                    long s = s0 + i;
                    if (s >= 0 && s < hasta && s > hasta - TAM_ANILLO) audio[i] = anillo[(int) (s % TAM_ANILLO)];
                }
            }
            ultimoAudio = audio;
            ultimoInicio = inicio;
            ultimoModo = m;
            DescodificadorFt8.Opciones o = opciones;
            final GeneradorFtx.Modo mm = m;
            hiloDescodificar.submit(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    if (mm == GeneradorFtx.Modo.FT8 && o.profundidad > 1) {
                        // pasada rápida: lo que me contestan llega antes de que empiece mi turno
                        DescodificadorFt8.Opciones rap = o.copia();
                        rap.profundidad = 1;
                        oyente.rapidos(inicio, mm, ft8.descodificar(audio, rap));
                    }
                    List<DescodificadorFt8.Resultado> r = mm == GeneradorFtx.Modo.FT8 ? ft8.descodificar(audio, o) : ft4.descodificar(audio, o);
                    oyente.resultados(inicio, mm, r, System.currentTimeMillis() - t0);
                } catch (Throwable e) {
                    oyente.estado("Error al descodificar: " + e);
                }
            });
        }
    }

    private volatile float[] ultimoAudio;
    private volatile long ultimoInicio;
    private volatile GeneradorFtx.Modo ultimoModo;

    /** Vuelve a descodificar el último periodo (botón DESCODIFICAR, por ejemplo tras cambiar la profundidad). */
    public boolean repetirUltimo() {
        float[] a = ultimoAudio;
        if (a == null) return false;
        long ini = ultimoInicio;
        GeneradorFtx.Modo m = ultimoModo;
        DescodificadorFt8.Opciones o = opciones;
        hiloDescodificar.submit(() -> {
            long t0 = System.currentTimeMillis();
            try {
                List<DescodificadorFt8.Resultado> r = m == GeneradorFtx.Modo.FT8 ? ft8.descodificar(a, o) : ft4.descodificar(a, o);
                oyente.resultados(ini, m, r, System.currentTimeMillis() - t0);
            } catch (Throwable e) { oyente.estado("Error al descodificar: " + e); }
        });
        return true;
    }

    /** Descodifica ya un trozo de audio (para las pruebas con ficheros). */
    public List<DescodificadorFt8.Resultado> descodificarAhora(float[] audio, GeneradorFtx.Modo m) {
        return m == GeneradorFtx.Modo.FT8 ? ft8.descodificar(audio, opciones) : ft4.descodificar(audio, opciones);
    }
}
