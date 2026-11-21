package digitales.cw;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;

/**
 * Manipulador de CW por la línea DTR o RTS de un puerto COM (la emisora en modo CW, como si fuera la llave).
 *
 * Igual que el PTT por puerto COM de DIGITALES, sin librerías: usa el PowerShell de Windows. A PowerShell se le manda
 * cada carácter entero (sus tiempos de tono y silencio en milisegundos) y él los cumple con un cronómetro de precisión,
 * así los puntos y rayas salen exactos aunque Java esté ocupado. Si Bitácora 26 se cierra, la línea vuelve a reposo.
 */
public final class CwSerie implements AutoCloseable {

    private final String puerto;
    private final boolean rts;
    private final Process proceso;
    private final BufferedWriter entrada;
    private final BlockingQueue<String> respuestas = new LinkedBlockingQueue<>();
    private volatile boolean cerrado = false;

    public CwSerie(String puerto, boolean rts) throws Exception {
        if (puerto == null || !puerto.matches("COM[0-9]{1,3}")) throw new Exception("elige el puerto COM del manipulador");
        this.puerto = puerto;
        this.rts = rts;
        String linea = rts ? "RtsEnable" : "DtrEnable";
        String script = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "try {",
                "  $p = New-Object System.IO.Ports.SerialPort '" + puerto + "'",
                "  $p.DtrEnable = $false; $p.RtsEnable = $false; $p.Handshake = 'None'",
                "  $p.Open()",
                "} catch { [Console]::Out.WriteLine('ERR ' + $_.Exception.Message); [Console]::Out.Flush(); exit 1 }",
                "[Console]::Out.WriteLine('OK'); [Console]::Out.Flush()",
                "$sw = [System.Diagnostics.Stopwatch]::new()",
                "$seguir = $true",
                "while ($seguir) {",
                "  $l = [Console]::In.ReadLine()",
                "  if ($l -eq $null -or $l -eq 'Q') { $seguir = $false; continue }",
                "  try {",
                "    if ($l.StartsWith('K ')) {",
                "      $sw.Restart(); $t = 0.0",
                "      foreach ($x in $l.Substring(2).Split(',')) {",
                "        $v = [double]$x",
                "        $p." + linea + " = ($v -gt 0)",
                "        $t += [Math]::Abs($v)",
                "        while ($sw.Elapsed.TotalMilliseconds -lt $t - 2) { [System.Threading.Thread]::Sleep(1) }",
                "        while ($sw.Elapsed.TotalMilliseconds -lt $t) { }",
                "      }",
                "      $p." + linea + " = $false",
                "    } elseif ($l -eq 'U') { $p." + linea + " = $false }",
                "    [Console]::Out.WriteLine('OK')",
                "  } catch { [Console]::Out.WriteLine('ERR ' + $_.Exception.Message) }",
                "  [Console]::Out.Flush()",
                "}",
                "try { $p.RtsEnable = $false; $p.DtrEnable = $false; $p.Close() } catch { }");
        String codificado = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", codificado);
        pb.redirectErrorStream(true);
        try {
            proceso = pb.start();
        } catch (IOException e) {
            throw new Exception("no se puede abrir " + puerto + " (no se encuentra PowerShell)");
        }
        entrada = new BufferedWriter(new OutputStreamWriter(proceso.getOutputStream(), StandardCharsets.US_ASCII));
        Thread lector = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proceso.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    l = l.trim();
                    if (l.equals("OK") || l.startsWith("ERR")) respuestas.add(l);
                }
            } catch (IOException ignored) { }
            respuestas.add("ERR el puerto " + puerto + " se ha cerrado");
        }, "CwSerie-" + puerto);
        lector.setDaemon(true);
        lector.start();
        String r = respuestas.poll(10, TimeUnit.SECONDS);
        if (!"OK".equals(r)) {
            proceso.destroyForcibly();
            throw new Exception("no se puede abrir " + puerto + (r == null ? " (no contesta)" : ": " + r.replaceFirst("^ERR ?", "")));
        }
    }

    public boolean es(String puerto, boolean rts) { return !cerrado && this.puerto.equals(puerto) && this.rts == rts && proceso.isAlive(); }

    /** Manipula una tanda de elementos (en puntos; positivos = tono) a «ppm» palabras por minuto. Vuelve al terminar. */
    public synchronized void manipular(List<Double> elementos, double ppm) throws Exception {
        if (cerrado || !proceso.isAlive()) throw new Exception("el puerto " + puerto + " del manipulador está cerrado");
        if (elementos.isEmpty()) return;
        double punto = 1200.0 / ppm;
        StringBuilder sb = new StringBuilder("K ");
        for (int i = 0; i < elementos.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "%.1f", elementos.get(i) * punto));
        }
        respuestas.clear();
        entrada.write(sb.toString());
        entrada.newLine();
        entrada.flush();
        double total = 0;
        for (double e : elementos) total += Math.abs(e) * punto;
        String r = respuestas.poll((long) total + 3000, TimeUnit.MILLISECONDS);
        if (!"OK".equals(r)) throw new Exception("manipulador por " + puerto + ": " + (r == null ? "no contesta" : r.replaceFirst("^ERR ?", "")));
    }

    @Override
    public synchronized void close() {
        if (cerrado) return;
        cerrado = true;
        try {
            entrada.write("U"); entrada.newLine();
            entrada.write("Q"); entrada.newLine();
            entrada.flush();
            entrada.close();
        } catch (IOException ignored) { }
        try { if (!proceso.waitFor(3, TimeUnit.SECONDS)) proceso.destroyForcibly(); } catch (InterruptedException e) { proceso.destroyForcibly(); }
    }
}
