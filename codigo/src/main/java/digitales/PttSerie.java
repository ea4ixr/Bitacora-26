package digitales;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * PTT por las líneas RTS o DTR de un puerto COM (como WSJT-X), para interfaces de sonido externas
 * o el segundo puerto COM de emisoras como la FT-891.
 *
 * Sin librerías: usa el PowerShell que trae Windows (System.IO.Ports.SerialPort). Se abre una sola vez,
 * se le mandan órdenes cortas por su entrada y contesta "OK". Si Bitácora 26 se cierra o se cuelga,
 * PowerShell deja de recibir órdenes, cierra el puerto y la línea vuelve a reposo (la emisora a recepción).
 */
public final class PttSerie implements AutoCloseable {

    /** Programa que se lanza (en las pruebas se puede cambiar por otro que haga lo mismo). */
    static String programa = "powershell.exe";

    private final String puerto;
    private final boolean rts;
    private final Process proceso;
    private final BufferedWriter entrada;
    private final BlockingQueue<String> respuestas = new LinkedBlockingQueue<>();
    private volatile boolean cerrado = false;

    /** Abre el puerto (por ejemplo "COM4") con RTS y DTR en reposo. Tarda 1 o 2 segundos (arranca PowerShell). */
    public PttSerie(String puerto, boolean rts) throws Exception {
        if (puerto == null || !puerto.matches("COM[0-9]{1,3}")) throw new Exception("elige el puerto COM del PTT en AJUSTES");
        this.puerto = puerto;
        this.rts = rts;
        String script = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "try {",
                "  $p = New-Object System.IO.Ports.SerialPort '" + puerto + "'",
                "  $p.DtrEnable = $false; $p.RtsEnable = $false; $p.Handshake = 'None'",
                "  $p.Open()",
                "} catch { [Console]::Out.WriteLine('ERR ' + $_.Exception.Message); [Console]::Out.Flush(); exit 1 }",
                "[Console]::Out.WriteLine('OK'); [Console]::Out.Flush()",
                "$seguir = $true",
                "while ($seguir) {",
                "  $l = [Console]::In.ReadLine()",
                "  if ($l -eq $null -or $l -eq 'Q') { $seguir = $false; continue }",
                "  try {",
                "    if ($l -eq 'R1') { $p.RtsEnable = $true } elseif ($l -eq 'R0') { $p.RtsEnable = $false }",
                "    elseif ($l -eq 'D1') { $p.DtrEnable = $true } elseif ($l -eq 'D0') { $p.DtrEnable = $false }",
                "    [Console]::Out.WriteLine('OK')",
                "  } catch { [Console]::Out.WriteLine('ERR ' + $_.Exception.Message) }",
                "  [Console]::Out.Flush()",
                "}",
                "try { $p.RtsEnable = $false; $p.DtrEnable = $false; $p.Close() } catch { }");
        String codificado = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder(programa, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", codificado);
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
        }, "PttSerie-" + puerto);
        lector.setDaemon(true);
        lector.start();
        String r = respuestas.poll(10, TimeUnit.SECONDS);
        if (!"OK".equals(r)) {
            proceso.destroyForcibly();
            throw new Exception("no se puede abrir " + puerto + (r == null ? " (no contesta)" : ": " + r.replaceFirst("^ERR ?", "")));
        }
    }

    public boolean es(String puerto, boolean rts) { return !cerrado && this.puerto.equals(puerto) && this.rts == rts && proceso.isAlive(); }

    /** Transmitir (true) o recibir (false). */
    public synchronized void poner(boolean transmitir) throws Exception {
        if (cerrado || !proceso.isAlive()) throw new Exception("el puerto " + puerto + " del PTT está cerrado");
        respuestas.clear();
        entrada.write((rts ? "R" : "D") + (transmitir ? "1" : "0"));
        entrada.newLine();
        entrada.flush();
        String r = respuestas.poll(3, TimeUnit.SECONDS);
        if (!"OK".equals(r)) throw new Exception("PTT por " + puerto + ": " + (r == null ? "no contesta" : r.replaceFirst("^ERR ?", "")));
    }

    @Override
    public synchronized void close() {
        if (cerrado) return;
        cerrado = true;
        try {
            entrada.write(rts ? "R0" : "D0"); entrada.newLine();
            entrada.write("Q"); entrada.newLine();
            entrada.flush();
            entrada.close();
        } catch (IOException ignored) { }
        try { if (!proceso.waitFor(3, TimeUnit.SECONDS)) proceso.destroyForcibly(); } catch (InterruptedException e) { proceso.destroyForcibly(); }
    }

    /**
     * Puertos COM del ordenador, con su nombre si Windows lo sabe: {"COM4", "COM4 · Silicon Labs ... Standard COM Port"}.
     * Tarda uno o dos segundos.
     */
    public static List<String[]> puertos() {
        List<String[]> r = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && "powershell.exe".equals(programa)) return r;
        String script = "Get-CimInstance Win32_PnPEntity | Where-Object { $_.Name -match '\\(COM[0-9]+\\)' } | ForEach-Object { $_.Name }; "
                + "[System.IO.Ports.SerialPort]::GetPortNames() | ForEach-Object { 'PUERTO ' + $_ }";
        String codificado = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        try {
            Process p = new ProcessBuilder(programa, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", codificado)
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            Map<String, String> nombres = new TreeMap<>(Comparator.comparingInt(PttSerie::numero));
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) {
                    l = l.trim();
                    if (l.startsWith("PUERTO ")) {
                        String c = l.substring(7).trim();
                        if (c.matches("COM[0-9]{1,3}")) nombres.putIfAbsent(c, c);
                    } else {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((COM[0-9]{1,3})\\)").matcher(l);
                        if (m.find()) nombres.put(m.group(1), m.group(1) + " · " + l.replace("(" + m.group(1) + ")", "").trim());
                    }
                }
            }
            p.waitFor(8, TimeUnit.SECONDS);
            for (Map.Entry<String, String> e : nombres.entrySet()) r.add(new String[]{e.getKey(), e.getValue()});
        } catch (Exception ignored) { }
        return r;
    }

    private static int numero(String com) {
        try { return Integer.parseInt(com.substring(3)); } catch (Exception e) { return 9999; }
    }
}
