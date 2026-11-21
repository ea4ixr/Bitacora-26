package digitales.cw;

import digitales.MotorDigitales;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * De dónde viene el manipulador en «Aprender CW». Cada fuente avisa del estado de los contactos
 * (bit 0 = llave recta o pala de punto, bit 1 = pala de raya):
 *  - mando USB (como la tarjeta Zero Delay): botón 1 y botón 2 del mando, leídos con winmm desde PowerShell;
 *  - puerto COM: líneas CTS (bit 0) y DSR (bit 1);
 *  - jack en el micrófono del PC: el manipulador cierra el circuito y se nota un «golpe» (hacia un lado al apretar y
 *    hacia el otro al soltar); se calibra con unas pulsaciones;
 *  - pitido de la emisora: la emisora en práctica y su tono por la tarjeta de sonido de DIGITALES.
 * (El teclado del PC lo lee directamente la ventana.)
 */
public abstract class Llave implements AutoCloseable {

    protected final IntConsumer aviso;
    protected volatile boolean vivo = true;

    protected Llave(IntConsumer aviso) { this.aviso = aviso; }

    @Override public void close() { vivo = false; }

    // ------------------------------------------------------------------ mando USB y puerto COM (PowerShell)
    private static final class PorPowerShell extends Llave {
        private final Process p;
        PorPowerShell(String script, String nombre, IntConsumer aviso) throws Exception {
            super(aviso);
            String cod = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", cod);
            pb.redirectErrorStream(true);
            p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            // PowerShell puede soltar antes líneas de progreso («#< CLIXML», XML…): se saltan hasta ver OK o ERR
            String primera = null, ultimoTexto = null;
            for (int i = 0; i < 400; i++) {
                String l = r.readLine();
                if (l == null) break;
                l = l.trim();
                if (l.startsWith("OK")) { primera = l; break; }
                if (l.startsWith("ERR")) { ultimoTexto = l.replaceFirst("^ERR ?", ""); break; }
                if (!l.isEmpty() && !l.startsWith("#<") && !l.startsWith("<")) ultimoTexto = l;
            }
            if (primera == null) {
                p.destroyForcibly();
                throw new Exception(ultimoTexto == null || ultimoTexto.isEmpty() ? "no contesta" : ultimoTexto);
            }
            Thread h = new Thread(() -> {
                try {
                    String l;
                    while (vivo && (l = r.readLine()) != null) {
                        l = l.trim();
                        if (l.startsWith("B ")) try { aviso.accept(Integer.parseInt(l.substring(2).trim())); } catch (NumberFormatException ignored) { }
                    }
                } catch (IOException ignored) { }
            }, nombre);
            h.setDaemon(true);
            h.setPriority(Thread.MAX_PRIORITY);
            h.start();
        }
        @Override public void close() {
            super.close();
            try { p.getOutputStream().write("Q\n".getBytes()); p.getOutputStream().flush(); } catch (IOException ignored) { }
            p.destroy();
        }
    }

    /** Mandos de juego que ve Windows (número y nombre), para elegir. */
    public static List<String> mandos() {
        List<String> r = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-PnpDevice -Class HIDClass,USB -PresentOnly -ErrorAction SilentlyContinue | Where-Object { $_.FriendlyName -match 'game|joystick|mando|controller|gamepad' } | ForEach-Object { $_.FriendlyName }")
                    .redirectErrorStream(true).start();
            try (BufferedReader b = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = b.readLine()) != null) if (!l.isBlank()) r.add(l.trim());
            }
        } catch (Exception ignored) { }
        return r;
    }

    /** Primer mando conectado (Zero Delay y parecidos): cualquier botón = punto/llave, botón 2 = raya. */
    public static Llave mandoUsb(IntConsumer aviso) throws Exception {
        String s = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$ProgressPreference = 'SilentlyContinue'",
                "Add-Type @'",
                "using System; using System.Runtime.InteropServices;",
                "public static class Mando {",
                "  [StructLayout(LayoutKind.Sequential)] public struct JOYINFOEX { public int dwSize, dwFlags, dwXpos, dwYpos, dwZpos, dwRpos, dwUpos, dwVpos, dwButtons, dwButtonNumber, dwPOV, dwReserved1, dwReserved2; }",
                "  [DllImport(\"winmm.dll\")] public static extern int joyGetPosEx(int id, ref JOYINFOEX ji);",
                "  [DllImport(\"winmm.dll\")] public static extern int timeBeginPeriod(int p);",
                "  public static int Botones(int id) { JOYINFOEX j = new JOYINFOEX(); j.dwSize = Marshal.SizeOf(j); j.dwFlags = 0x80; return joyGetPosEx(id, ref j) == 0 ? j.dwButtons : -1; }",
                "}",
                "'@",
                "$id = -1",
                "for ($i = 0; $i -lt 16; $i++) { if ([Mando]::Botones($i) -ge 0) { $id = $i; break } }",
                "if ($id -lt 0) { [Console]::Out.WriteLine('ERR no se encuentra ningún mando USB (¿está enchufado?)'); [Console]::Out.Flush(); exit 1 }",
                "[void][Mando]::timeBeginPeriod(1)",
                "[Console]::Out.WriteLine('OK'); [Console]::Out.Flush()",
                "$ant = -1",
                "while ($true) {",
                "  $b = [Mando]::Botones($id)",
                "  if ($b -lt 0) { [Console]::Out.WriteLine('ERR'); [Console]::Out.Flush(); break }",
                "  $v = 0; if (($b -band (-bnot 2)) -ne 0) { $v = 1 }; if (($b -band 2) -ne 0) { $v = $v -bor 2 }",
                "  if ($v -ne $ant) { [Console]::Out.WriteLine('B ' + $v); [Console]::Out.Flush(); $ant = $v }",
                "  [System.Threading.Thread]::Sleep(1)",
                "}");
        return new PorPowerShell(s, "LlaveMando", aviso);
    }

    /** Puerto COM: CTS = punto/llave, DSR = raya. */
    public static Llave puertoCom(String puerto, IntConsumer aviso) throws Exception {
        if (puerto == null || !puerto.matches("COM[0-9]{1,3}")) throw new Exception("elige el puerto COM");
        String s = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$ProgressPreference = 'SilentlyContinue'",
                "try { $p = New-Object System.IO.Ports.SerialPort '" + puerto + "'; $p.DtrEnable = $true; $p.RtsEnable = $true; $p.Open() }",
                "catch { [Console]::Out.WriteLine('ERR ' + $_.Exception.Message); [Console]::Out.Flush(); exit 1 }",
                "[Console]::Out.WriteLine('OK'); [Console]::Out.Flush()",
                "$ant = -1",
                "while ($true) {",
                "  $v = 0; if ($p.CtsHolding) { $v = $v -bor 1 }; if ($p.DsrHolding) { $v = $v -bor 2 }",
                "  if ($v -ne $ant) { [Console]::Out.WriteLine('B ' + $v); [Console]::Out.Flush(); $ant = $v }",
                "  [System.Threading.Thread]::Sleep(1)",
                "}");
        return new PorPowerShell(s, "LlaveCom", aviso);
    }

    // ------------------------------------------------------------------ por audio
    private abstract static class PorAudio extends Llave {
        protected final MotorDigitales.Fuente fuente;
        PorAudio(String tarjeta, IntConsumer aviso) throws Exception {
            super(aviso);
            fuente = MotorDigitales.tarjeta(tarjeta, "MONO");
            Thread h = new Thread(() -> {
                float[] buf = new float[120];   // 10 ms
                while (vivo) {
                    int n;
                    try { n = fuente.leer(buf); } catch (Exception e) { break; }
                    for (int i = 0; i < n; i++) muestra(buf[i]);
                }
            }, "LlaveAudio");
            h.setDaemon(true);
            h.setPriority(Thread.MAX_PRIORITY);
            h.start();
        }
        abstract void muestra(double x);
        @Override public void close() { super.close(); fuente.close(); }
    }

    /**
     * Jack en el micrófono: al cerrar y abrir el contacto se ve un golpe de un signo y luego del otro. Se calibra solo:
     * el primer golpe fuerte se toma como «apretar». Si sale al revés, «invertir» lo arregla.
     */
    public static final class Microfono extends PorAudio {
        private double ruido = 50, ultimo;
        private int esperaMuestras = 0, signoApretar = 0;
        private boolean apretada = false;
        private volatile boolean invertir;
        public Microfono(String tarjeta, boolean invertir, IntConsumer aviso) throws Exception { super(tarjeta, aviso); this.invertir = invertir; }
        public void ponerInvertido(boolean si) { invertir = si; }
        @Override void muestra(double x) {
            double d = x - ultimo;   // derivada: solo los cambios bruscos
            ultimo = x;
            ruido += (Math.abs(d) - ruido) * 0.0005;
            if (esperaMuestras > 0) { esperaMuestras--; return; }
            if (Math.abs(d) > Math.max(600, 12 * ruido)) {
                int s = d > 0 ? 1 : -1;
                if (signoApretar == 0) signoApretar = s;
                boolean ap = (s == signoApretar) != invertir;
                if (ap != apretada) { apretada = ap; aviso.accept(ap ? 1 : 0); }
                esperaMuestras = 12 * 6;   // 6 ms sin mirar (el golpe dura un poco)
            }
        }
    }

    /** Pitido de la emisora (en práctica, sin transmitir) por la tarjeta de sonido: tecla = hay tono. */
    public static final class PitidoEmisora extends PorAudio {
        private final double tonoHz;
        private double fase, i5, q5, pico = 1, ruido = 1;
        private int n5;
        private boolean apretada;
        public PitidoEmisora(String tarjeta, double tonoHz, IntConsumer aviso) throws Exception { super(tarjeta, aviso); this.tonoHz = tonoHz; }
        @Override void muestra(double x) {
            i5 += x * Math.cos(fase);
            q5 -= x * Math.sin(fase);
            fase += 2 * Math.PI * tonoHz / 12000;
            if (fase > 2 * Math.PI) fase -= 2 * Math.PI;
            if (++n5 < 24) return;   // cada 2 ms
            double e = Math.hypot(i5, q5) / n5;
            i5 = q5 = 0; n5 = 0;
            pico = Math.max(e, pico * 0.999);
            if (e < pico * 0.3) ruido += (e - ruido) * 0.01;
            double umbral = ruido + 0.4 * (pico - ruido), h = 0.1 * (pico - ruido);
            if (pico < 4 * ruido) { if (apretada) { apretada = false; aviso.accept(0); } return; }
            if (!apretada && e > umbral + h) { apretada = true; aviso.accept(1); }
            else if (apretada && e < umbral - h) { apretada = false; aviso.accept(0); }
        }
    }
}
