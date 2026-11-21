package digitales.cw;

/**
 * Convierte lo que se aprieta (llave recta o palas) en tecla apretada/suelta, con su tiempo.
 * Llave recta: la tecla sigue al contacto. Palas: un manipulador electrónico yámbico (modo A o B) hace los puntos y
 * las rayas a la velocidad elegida; con las dos palas apretadas alterna punto-raya.
 */
public final class Manipulador implements AutoCloseable {

    public interface Salida { void tecla(boolean apretada, long ms); }
    public enum Tipo { RECTA, YAMBICO_A, YAMBICO_B }

    private final Salida salida;
    private volatile Tipo tipo;
    private volatile double ppm;
    private volatile boolean pPunto, pRaya;      // palas apretadas ahora
    private volatile boolean memPunto, memRaya;  // pala apretada mientras sonaba el otro elemento
    private final Thread hilo;
    private volatile boolean vivo = true;
    private final Object aviso = new Object();

    public Manipulador(Tipo tipo, double ppm, Salida salida) {
        this.tipo = tipo;
        this.ppm = ppm;
        this.salida = salida;
        hilo = new Thread(this::bucle, "ManipuladorYambico");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.MAX_PRIORITY);
        hilo.start();
    }

    public void ponerTipo(Tipo t) { tipo = t; }
    public void ponerPpm(double p) { ppm = Math.max(5, Math.min(50, p)); }

    private static long ahora() { return System.nanoTime() / 1_000_000; }

    /** Estado de los contactos: bit 0 = pala de punto (o llave recta), bit 1 = pala de raya. */
    public void contactos(int bits) {
        boolean d = (bits & 1) != 0, r = (bits & 2) != 0;
        if (tipo == Tipo.RECTA) {
            boolean k = d || r;
            if (k != pPunto) { pPunto = k; salida.tecla(k, ahora()); }
            return;
        }
        if (d && !pPunto) memPunto = true;
        if (r && !pRaya) memRaya = true;
        pPunto = d;
        pRaya = r;
        synchronized (aviso) { aviso.notifyAll(); }
    }

    private void bucle() {
        boolean ultimoFuePunto = false;
        while (vivo) {
            if (tipo == Tipo.RECTA) { esperar(50); continue; }
            boolean quiereP = pPunto || memPunto, quiereR = pRaya || memRaya;
            if (!quiereP && !quiereR) { synchronized (aviso) { try { aviso.wait(20); } catch (InterruptedException e) { return; } } continue; }
            boolean esPunto = quiereP && quiereR ? !ultimoFuePunto : quiereP;
            if (esPunto) memPunto = false; else memRaya = false;
            double u = 1200.0 / ppm;
            boolean ambasAlEmpezar = pPunto && pRaya;
            salida.tecla(true, ahora());
            dormir(esPunto ? u : 3 * u, esPunto);
            salida.tecla(false, ahora());
            dormir(u, esPunto);
            ultimoFuePunto = esPunto;
            // Yámbico B: si se sueltan las dos palas mientras alternaba, aún sale el elemento contrario
            if (tipo == Tipo.YAMBICO_B && ambasAlEmpezar && !pPunto && !pRaya) {
                if (esPunto) memRaya = true; else memPunto = true;
            }
        }
    }

    /** Espera con precisión y apunta la pala contraria si se aprieta durante el elemento. */
    private void dormir(double ms, boolean esPunto) {
        long fin = System.nanoTime() + (long) (ms * 1e6);
        while (vivo) {
            long q = fin - System.nanoTime();
            if (q <= 0) break;
            if (esPunto && pRaya) memRaya = true;
            if (!esPunto && pPunto) memPunto = true;
            if (q > 2_000_000) esperar(1); else Thread.onSpinWait();
        }
    }

    private static void esperar(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    @Override public void close() { vivo = false; synchronized (aviso) { aviso.notifyAll(); } }
}
