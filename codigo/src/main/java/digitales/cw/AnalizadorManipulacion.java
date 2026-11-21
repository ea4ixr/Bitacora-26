package digitales.cw;

import java.util.ArrayList;
import java.util.List;

/**
 * Lee lo que manda quien está aprendiendo: recibe los tiempos de tecla apretada y suelta (en milisegundos) y devuelve el texto,
 * la velocidad y cómo de bien medidos están los puntos, las rayas y los espacios (para darle consejos).
 * La velocidad se saca de sus propios puntos y rayas (cada uno manipula a su ritmo).
 */
public final class AnalizadorManipulacion {

    public interface Salida { void texto(String s); }

    private final Salida salida;
    private double punto = 60;                         // ms, se va ajustando
    private final List<Double> tonos = new ArrayList<>(), huecosLetra = new ArrayList<>(), huecosDentro = new ArrayList<>();
    private final List<double[]> ultimos = new ArrayList<>();   // {ms, 1=tono 0=silencio}
    private final StringBuilder letra = new StringBuilder();
    private long ultimoCambio = 0;
    private boolean apretada = false, espacioPuesto = true;

    public AnalizadorManipulacion(Salida salida, double ppmInicial) {
        this.salida = salida;
        punto = 1200.0 / Math.max(5, ppmInicial);
    }

    /** La tecla cambia (t en milisegundos de cualquier reloj que avance). */
    public synchronized void cambio(boolean apretar, long t) {
        if (apretar == apretada) return;
        double d = ultimoCambio == 0 ? 0 : t - ultimoCambio;
        ultimoCambio = t;
        apretada = apretar;
        if (apretar) {   // acaba un silencio
            if (d > 0 && d < 2 * punto && letra.length() > 0) { huecosDentro.add(d); if (huecosDentro.size() > 60) huecosDentro.remove(0); }
            else if (d >= 2 * punto && d < 6 * punto) { huecosLetra.add(d); if (huecosLetra.size() > 40) huecosLetra.remove(0); }
            if (d > 0 && d < 10 * punto) guardar(d, 0);
        } else {         // acaba un tono
            if (d < 8) return;   // rebote del contacto
            tonos.add(d);
            if (tonos.size() > 60) tonos.remove(0);
            recalcular();
            letra.append(d < 2 * punto ? '.' : '-');
            guardar(d, 1);
        }
    }

    /** Llamar a menudo (cada 20-50 ms): cierra la letra o pone el espacio cuando el silencio ya es largo. */
    public synchronized void reloj(long t) {
        if (apretada || ultimoCambio == 0) return;
        double sil = t - ultimoCambio;
        if (letra.length() > 0 && sil >= 2.2 * punto) {
            String c = Morse.texto(letra.toString());
            letra.setLength(0);
            espacioPuesto = false;
            salida.texto(c == null ? "_" : c);
        } else if (letra.length() == 0 && !espacioPuesto && sil >= 6 * punto) {
            espacioPuesto = true;
            salida.texto(" ");
        }
    }

    private void guardar(double ms, int tono) {
        ultimos.add(new double[]{ms, tono});
        if (ultimos.size() > 40) ultimos.remove(0);
    }

    /** Separa los tonos en cortos (puntos) y largos (rayas) y saca el punto. */
    private void recalcular() {
        if (tonos.size() < 4) return;
        double mn = 1e9, mx = 0;
        for (double v : tonos) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
        if (mx < 1.8 * mn) return;
        double umb = Math.sqrt(mn * mx), p = 0, r = 0;
        for (int it = 0; it < 5; it++) {
            double sp = 0, sr = 0; int np = 0, nr = 0;
            for (double v : tonos) { if (v <= umb) { sp += v; np++; } else { sr += v; nr++; } }
            if (np == 0 || nr == 0) return;
            p = sp / np; r = sr / nr;
            umb = (p + r) / 2;
        }
        punto = (p + r / 3) / 2;
    }

    public synchronized double ppm() { return 1200.0 / punto; }

    /** {punto medio, raya media en puntos, hueco dentro de la letra en puntos, hueco entre letras en puntos}; NaN si no hay datos. */
    public synchronized double[] medidas() {
        double sp = 0, sr = 0, sd = 0, sl = 0; int np = 0, nr = 0, nd = 0, nl = 0;
        for (double v : tonos) { if (v < 2 * punto) { sp += v; np++; } else { sr += v; nr++; } }
        for (double v : huecosDentro) { sd += v; nd++; }
        for (double v : huecosLetra) { if (v < 6 * punto) { sl += v; nl++; } }
        double p = np > 0 ? sp / np : Double.NaN;
        return new double[]{p, nr > 0 && np > 0 ? (sr / nr) / p : Double.NaN, nd > 0 && np > 0 ? (sd / nd) / p : Double.NaN, nl > 0 && np > 0 ? (sl / nl) / p : Double.NaN};
    }

    /** Los últimos tonos y silencios: {ms, 1=tono 0=silencio}, en unidades de punto para dibujarlos. */
    public synchronized List<double[]> ultimos() {
        List<double[]> r = new ArrayList<>();
        for (double[] u : ultimos) r.add(new double[]{u[0] / punto, u[1]});
        return r;
    }

    public synchronized void borrar() {
        tonos.clear(); huecosDentro.clear(); huecosLetra.clear(); ultimos.clear(); letra.setLength(0);
        ultimoCambio = 0; apretada = false; espacioPuesto = true;
    }
}
