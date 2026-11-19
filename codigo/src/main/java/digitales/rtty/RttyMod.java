package digitales.rtty;

import java.util.ArrayList;
import java.util.List;

/**
 * Generador de RTTY (AFSK): convierte texto en Baudot (ITA2) y en los dos tonos de MARK y SPACE, con fase continua
 * (sin saltos, como hace MMTTY). Bit de arranque en SPACE, 5 bits (el de menos peso primero) y 1,5 de parada en MARK.
 */
public final class RttyMod {
    private RttyMod() { }

    /** Códigos Baudot del texto, con los cambios de letras/cifras necesarios. Empieza con LTRS para sincronizar. */
    public static List<Integer> codigos(String texto) {
        List<Integer> r = new ArrayList<>();
        r.add(31);
        r.add(31);
        boolean cif = false;
        for (char ch : texto.toUpperCase().toCharArray()) {
            if (ch == '\n') { r.add(8); r.add(2); continue; }   // CR + LF
            int l = RttyDemod.LETRAS.indexOf(ch), c = RttyDemod.CIFRAS.indexOf(ch);
            if (ch == ' ') { r.add(4); cif = false; continue; }   // tras un espacio el que recibe vuelve a letras
            if (l > 0 && l != 27 && l != 31) {
                if (cif) { r.add(31); cif = false; }
                r.add(l);
            } else if (c > 0 && c != 27 && c != 31) {
                if (!cif) { r.add(27); cif = true; }
                r.add(c);
            }
        }
        r.add(31);
        return r;
    }

    /**
     * Audio (a fs muestras por segundo, entre -1 y 1) de una lista de códigos. «fase» guarda la fase entre llamadas
     * para que no haya saltos al ir mandando trozos.
     */
    public static float[] audio(List<Integer> codigos, double fs, double baudios, double markHz, double spaceHz, double[] fase) {
        double porBit = fs / baudios;
        int total = (int) Math.ceil(codigos.size() * 7.5 * porBit);
        float[] a = new float[total];
        double t = 0;
        int i = 0;
        for (int cod : codigos) {
            int[] bits = new int[8];
            bits[0] = 0;
            for (int k = 0; k < 5; k++) bits[k + 1] = (cod >> k) & 1;
            bits[6] = 1;
            bits[7] = 1;
            double[] dur = {1, 1, 1, 1, 1, 1, 1, 0.5};
            for (int k = 0; k < 8; k++) {
                t += dur[k] * porBit;
                double f = bits[k] == 1 ? markHz : spaceHz, df = 2 * Math.PI * f / fs;
                while (i < t && i < a.length) {
                    a[i++] = (float) Math.sin(fase[0]);
                    fase[0] += df;
                    if (fase[0] > 2 * Math.PI) fase[0] -= 2 * Math.PI;
                }
            }
        }
        return java.util.Arrays.copyOf(a, i);
    }

    /** Tono de MARK continuo (lo que se manda mientras no hay texto). */
    public static float[] reposo(int muestras, double fs, double markHz, double[] fase) {
        float[] a = new float[muestras];
        double df = 2 * Math.PI * markHz / fs;
        for (int i = 0; i < muestras; i++) {
            a[i] = (float) Math.sin(fase[0]);
            fase[0] += df;
            if (fase[0] > 2 * Math.PI) fase[0] -= 2 * Math.PI;
        }
        return a;
    }
}
