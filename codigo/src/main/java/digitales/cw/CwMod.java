package digitales.cw;

import java.util.ArrayList;
import java.util.List;

/**
 * Genera CW: de texto a la lista de tiempos de manipulación (en puntos) y de ahí a audio (tono con flancos suaves,
 * para que no haga «clics»). Sirve para transmitir por audio y para las pruebas del descodificador.
 */
public final class CwMod {
    private CwMod() { }

    /**
     * Elementos de manipulación: números positivos = tono, negativos = silencio, en unidades de punto.
     * Las señales de procedimiento se escriben entre < > (<SK>, <KN>...). «farnsworth» alarga los espacios (1 = normal).
     */
    public static List<Double> elementos(String texto, double farnsworth) {
        List<Double> r = new ArrayList<>();
        String t = texto.toUpperCase();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == ' ' || ch == '\n') {
                if (!r.isEmpty() && r.get(r.size() - 1) < 0) r.set(r.size() - 1, -7.0 * farnsworth);
                continue;
            }
            String clave = String.valueOf(ch);
            if (ch == '<') {
                int f = t.indexOf('>', i);
                if (f > i) { clave = t.substring(i, f + 1); i = f; }
            }
            String c = Morse.codigo(clave);
            if (c == null) continue;
            for (int k = 0; k < c.length(); k++) {
                r.add(c.charAt(k) == '.' ? 1.0 : 3.0);
                r.add(k == c.length() - 1 ? -3.0 * farnsworth : -1.0);
            }
        }
        return r;
    }

    /** Audio entre -1 y 1. «flancoMs»: subida y bajada del tono (5 ms es lo normal). */
    public static float[] audio(List<Double> elementos, double fs, double ppm, double hz, double flancoMs) {
        double punto = 1.2 / ppm;   // segundos
        double total = 0;
        for (double e : elementos) total += Math.abs(e) * punto;
        float[] a = new float[(int) Math.ceil(total * fs) + 1];
        int fl = Math.max(1, (int) (flancoMs / 1000 * fs));
        double t = 0, w = 2 * Math.PI * hz / fs;
        int i = 0;
        for (double e : elementos) {
            int n = (int) Math.round(Math.abs(e) * punto * fs);
            for (int k = 0; k < n && i < a.length; k++, i++) {
                double env = 0;
                if (e > 0) {
                    env = 1;
                    if (k < fl) env = 0.5 - 0.5 * Math.cos(Math.PI * k / fl);
                    else if (k >= n - fl) env = 0.5 - 0.5 * Math.cos(Math.PI * (n - k) / fl);
                }
                a[i] = (float) (env * Math.sin(w * i));
            }
        }
        return java.util.Arrays.copyOf(a, i);
    }
}
