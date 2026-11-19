package digitales;

/**
 * Fabrica el audio de un mensaje FT8 o FT4 (para transmitir, y para las pruebas).
 * Igual que gen_ft8wave / gen_ft4wave de WSJT-X (GPL): tonos con pulso gaussiano (GFSK) y rampas suaves.
 */
public final class GeneradorFtx {
    private GeneradorFtx() { }

    public enum Modo { FT8, FT4 }

    /** Tonos del mensaje (79 en FT8, 103 en FT4) o null si el texto no se puede enviar. */
    public static int[] tonos(Ft8Mensaje m, String texto, Modo modo) {
        int[] b = m.aBits(texto);
        if (b == null) return null;
        return modo == Modo.FT8 ? DescodificadorFt8.tonos(b) : DescodificadorFt4.tonos(b);
    }

    /**
     * Audio (valores -1..1) a 12000 muestras/s de los tonos dados, empezando en la frecuencia f0 (Hz).
     * FT8: 79 símbolos de 1920 muestras (12,64 s). FT4: 105 símbolos de 576 muestras (6,05 s, con rampas).
     */
    public static float[] audio(int[] tonos, double f0, Modo modo) {
        if (modo == Modo.FT4) {
            int n = DescodificadorFt4.NN2 * DescodificadorFt4.NSPS;
            double[] w = new double[n];
            DescodificadorFt4.onda(tonos, f0, w, null);
            float[] r = new float[n];
            for (int i = 0; i < n; i++) r[i] = (float) w[i];
            return r;
        }
        int nsym = DescodificadorFt8.NN, nsps = DescodificadorFt8.NSPS;
        double fs = 12000.0, dt = 1.0 / fs, bt = 2.0;
        double[] pulse = DescodificadorFt8.pulso(bt, nsps);
        double[] dphi = new double[(nsym + 2) * nsps];
        double dphiPeak = 2 * Math.PI / nsps;
        for (int j = 1; j <= nsym; j++) {
            int ib = (j - 1) * nsps;
            for (int i = 0; i < 3 * nsps; i++) dphi[ib + i] += dphiPeak * pulse[i] * tonos[j - 1];
        }
        for (int i = 0; i < 2 * nsps; i++) dphi[i] += dphiPeak * tonos[0] * pulse[nsps + i];
        for (int i = 0; i < 2 * nsps; i++) dphi[nsym * nsps + i] += dphiPeak * tonos[nsym - 1] * pulse[i];
        for (int i = 0; i < dphi.length; i++) dphi[i] += 2 * Math.PI * f0 * dt;
        int n = nsym * nsps;
        float[] w = new float[n];
        double phi = 0;
        for (int k = 0; k < n; k++) {
            w[k] = (float) Math.sin(phi);
            phi = (phi + dphi[nsps + k]) % (2 * Math.PI);
        }
        int nramp = (int) Math.round(nsps / 8.0);
        for (int i = 0; i < nramp; i++) {
            w[i] *= (float) ((1.0 - Math.cos(2 * Math.PI * i / (2.0 * nramp))) / 2.0);
            int k1 = n - nramp + i;
            w[k1] *= (float) ((1.0 + Math.cos(2 * Math.PI * i / (2.0 * nramp))) / 2.0);
        }
        return w;
    }
}
