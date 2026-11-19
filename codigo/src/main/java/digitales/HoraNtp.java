package digitales;

import java.net.*;

/**
 * Consulta la hora exacta a un servidor de hora de internet (NTP), como hace Meinberg NTP,
 * y calcula cuánto va adelantado o atrasado el reloj del ordenador.
 * Solo se conecta cuando se le pide (al pinchar en el reloj de DIGITALES).
 */
public final class HoraNtp {

    /** Servidores, por orden: el Real Instituto y Observatorio de la Armada (hora oficial de España) y otros. */
    public static final String[] SERVIDORES = {"hora.roa.es", "es.pool.ntp.org", "time.windows.com"};

    /** Resultado: cuánto hay que sumar al reloj del ordenador (ms; positivo = el ordenador va atrasado). */
    public record Medida(String servidor, long desfaseMs, long retardoMs) { }

    private static final long ENTRE_1900_Y_1970 = 2_208_988_800L;
    static int puerto = 123;

    /** Pregunta a los servidores hasta que uno conteste; hace 4 preguntas y se queda con la de menos retardo. */
    public static Medida medir() throws Exception {
        Exception ultimo = null;
        for (String s : SERVIDORES) {
            try { return medir(s); } catch (Exception e) { ultimo = e; }
        }
        throw new Exception("no contesta ningún servidor de hora" + (ultimo != null ? " (" + ultimo.getMessage() + ")" : ""));
    }

    public static Medida medir(String servidor) throws Exception {
        InetAddress dir = InetAddress.getByName(servidor);
        Medida mejor = null;
        try (DatagramSocket so = new DatagramSocket()) {
            so.setSoTimeout(2000);
            for (int i = 0; i < 4; i++) {
                byte[] b = new byte[48];
                b[0] = 0x23;   // versión 4, modo cliente
                long t1 = System.currentTimeMillis();
                escribirMarca(b, 40, t1);
                so.send(new DatagramPacket(b, b.length, dir, puerto));
                DatagramPacket r = new DatagramPacket(new byte[48], 48);
                try { so.receive(r); } catch (SocketTimeoutException e) { continue; }
                long t4 = System.currentTimeMillis();
                byte[] d = r.getData();
                int modo = d[0] & 0x7, estrato = d[1] & 0xFF;
                if (r.getLength() < 48 || modo != 4 || estrato == 0 || estrato > 15) continue;
                long t2 = leerMarca(d, 32), t3 = leerMarca(d, 40);
                long desfase = ((t2 - t1) + (t3 - t4)) / 2;
                long retardo = (t4 - t1) - (t3 - t2);
                if (mejor == null || retardo < mejor.retardoMs()) mejor = new Medida(servidor, desfase, retardo);
                Thread.sleep(100);
            }
        }
        if (mejor == null) throw new Exception(servidor + " no contesta");
        return mejor;
    }

    static void escribirMarca(byte[] b, int o, long ms) {
        long seg = ms / 1000 + ENTRE_1900_Y_1970;
        long frac = (ms % 1000) * 0x100000000L / 1000;
        for (int i = 3; i >= 0; i--) { b[o + i] = (byte) seg; seg >>= 8; }
        for (int i = 7; i >= 4; i--) { b[o + i] = (byte) frac; frac >>= 8; }
    }

    static long leerMarca(byte[] b, int o) {
        long seg = 0, frac = 0;
        for (int i = 0; i < 4; i++) seg = (seg << 8) | (b[o + i] & 0xFF);
        for (int i = 4; i < 8; i++) frac = (frac << 8) | (b[o + i] & 0xFF);
        return (seg - ENTRE_1900_Y_1970) * 1000 + frac * 1000 / 0x100000000L;
    }

    /**
     * Corrige el reloj de Windows sumándole el desfase. Necesita permiso de administrador:
     * Windows muestra su ventana de permiso. Devuelve cuando ha terminado (o se ha cancelado).
     */
    public static void corregirWindows(long desfaseMs) throws Exception {
        if (Math.abs(desfaseMs) > 24L * 3600_000) throw new Exception("desfase demasiado grande: revisa la fecha del ordenador");
        String orden = "Set-Date -Adjust ([TimeSpan]::FromMilliseconds(" + desfaseMs + ")) | Out-Null";
        String exterior = "Start-Process -FilePath powershell.exe -Verb RunAs -WindowStyle Hidden -Wait "
                + "-ArgumentList '-NoProfile','-NonInteractive','-Command','" + orden + "'";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", exterior)
                .redirectErrorStream(true).start();
        p.getOutputStream().close();
        String salida = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        if (p.exitValue() != 0) throw new Exception(salida.toLowerCase().contains("cancel") ? "no se ha dado permiso" : "Windows no lo ha permitido" + (salida.isEmpty() ? "" : ": " + primeraLinea(salida)));
    }

    private static String primeraLinea(String s) { int i = s.indexOf('\n'); return i < 0 ? s : s.substring(0, i).trim(); }
}
