package digitales.cw;

import javax.sound.sampled.*;

/**
 * El pitido de práctica de «Aprender CW»: suena por los altavoces del PC (la salida por defecto de Windows o la que se elija),
 * nunca por la emisora. Hace dos cosas: el tono mientras se aprieta el manipulador (con flancos suaves, sin clics) y la
 * reproducción de CW ya generado (letras, palabras, el simulador de QSO), con ruido opcional.
 */
public final class SonidoPractica implements AutoCloseable {
    public static final int FS = 12000;
    private static final int TROZO = 96;   // 8 ms por vuelta: poco retraso entre la pala y el pitido

    private final SourceDataLine linea;
    private final Thread hilo;
    private volatile boolean vivo = true, tecla = false;
    private volatile double tonoHz = 650, volumen = 0.7, ruido = 0;
    private volatile float[] reproduciendo;
    private volatile int pos;
    private double fase, env;
    private final java.util.Random azar = new java.util.Random();

    public SonidoPractica(String nombreSalida) throws Exception {
        AudioFormat fmt = new AudioFormat(FS, 16, 1, true, false);
        SourceDataLine l = null;
        if (nombreSalida != null && !nombreSalida.isEmpty()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!mi.getName().equals(nombreSalida)) continue;
                try { l = (SourceDataLine) AudioSystem.getMixer(mi).getLine(new DataLine.Info(SourceDataLine.class, fmt)); } catch (Exception ignored) { }
            }
        }
        if (l == null) l = AudioSystem.getSourceDataLine(fmt);
        l.open(fmt, TROZO * 2 * 6);   // ~48 ms de colchón
        l.start();
        linea = l;
        hilo = new Thread(this::bucle, "SonidoPractica");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.MAX_PRIORITY);
        hilo.start();
    }

    public void ponerTono(double hz) { tonoHz = hz; }
    public void ponerVolumen(double v) { volumen = Math.max(0, Math.min(1, v)); }
    /** Ruido de fondo al reproducir (0 = nada, 1 = mucho), para practicar como en la banda. */
    public void ponerRuido(double r) { ruido = Math.max(0, r); }
    /** Manipulador apretado o suelto: el pitido sigue a la tecla. */
    public void tecla(boolean apretada) { tecla = apretada; }

    /** Reproduce un audio ya hecho (entre -1 y 1, a 12000/s). Corta lo que estuviera sonando. */
    public void reproducir(float[] audio) { pos = 0; reproduciendo = audio; }
    public void parar() { reproduciendo = null; }
    public boolean sonando() { return reproduciendo != null; }

    private void bucle() {
        byte[] b = new byte[TROZO * 2];
        double sube = 1.0 / (0.004 * FS);   // flanco de 4 ms
        while (vivo) {
            float[] rep = reproduciendo;
            double w = 2 * Math.PI * tonoHz / FS, vol = volumen, rd = ruido;
            for (int i = 0; i < TROZO; i++) {
                double v;
                if (rep != null) {
                    int p = pos++;
                    if (p >= rep.length) { reproduciendo = null; rep = null; v = 0; }
                    else v = rep[p];
                } else {
                    env += tecla ? sube : -sube;
                    env = Math.max(0, Math.min(1, env));
                    v = env * Math.sin(fase);
                    fase += w;
                    if (fase > 2 * Math.PI) fase -= 2 * Math.PI;
                }
                if (rep != null && rd > 0) v += rd * 0.25 * azar.nextGaussian();
                int s = (int) Math.max(-32767, Math.min(32767, Math.round(v * vol * 0.6 * 32767)));
                b[2 * i] = (byte) s;
                b[2 * i + 1] = (byte) (s >> 8);
            }
            linea.write(b, 0, b.length);
        }
    }

    @Override
    public void close() {
        vivo = false;
        try { hilo.join(300); } catch (InterruptedException ignored) { }
        linea.stop();
        linea.close();
    }
}
