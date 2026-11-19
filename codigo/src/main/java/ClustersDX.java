import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

/**
 * Clústeres de DX de internet (URE, DXFun, RBN): conexión telnet. Se leen los avisos y, si el usuario lo pide, se anuncia uno.
 * Cada ordenador se conecta directamente con el indicativo de su operador; no se manda nada salvo el indicativo
 * al entrar. Los avisos (spots) llevan indicativo, frecuencia, comentario y quién avisa: ningún dato personal.
 */
final class ClustersDX {

    /** Un clúster: id para la configuración, nombre de la pestaña, dirección y si es de Banda Ciudadana. */
    record Fuente(String id, String nombre, String host, int puerto, boolean cb, boolean porDefecto) { }

    static final List<Fuente> FUENTES = List.of(
            new Fuente("URE", "URE", "ea4ure.com", 7300, false, true),
            new Fuente("DXFUN", "DXFun", "dxfun.com", 8000, false, true),
            new Fuente("RBN", "RBN", "telnet.reversebeacon.net", 7000, false, false));

    /** Un aviso. ms = hora UTC del aviso; hz = frecuencia. */
    record Aviso(long ms, long hz, String dx, String modo, String comentario, String avisa, String fuente,
                 String iso, String pais, String continente, String banda) { }

    interface Oyente {
        void aviso(Aviso a);
        void estado(String fuenteId, boolean conectado, String texto);
    }

    static boolean activa(Fuente f) {
        return "1".equals(LibroGuardiaUI.ConfigApp.get("CLUSTERDX_" + f.id(), f.porDefecto() ? "1" : "0"));
    }

    static void ponerActiva(Fuente f, boolean si) { LibroGuardiaUI.ConfigApp.put("CLUSTERDX_" + f.id(), si ? "1" : "0"); }

    private final Oyente oyente;
    private final Map<String, Conexion> conexiones = new ConcurrentHashMap<>();

    ClustersDX(Oyente oyente) { this.oyente = oyente; }

    void conectar(Fuente f, String indicativo) {
        parar(f.id());
        if (indicativo == null || indicativo.isBlank()) {
            oyente.estado(f.id(), false, "falta tu indicativo (Mi Estación → Operador)");
            return;
        }
        // Si en Servicios online hay un indicativo para este clúster, se entra con él
        String propio = LibroGuardiaUI.datoServicioActual("CLUSTER_" + f.id() + "_CALL").trim().toUpperCase();
        Conexion c = new Conexion(f, propio.isEmpty() ? indicativo.trim().toUpperCase() : propio);
        conexiones.put(f.id(), c);
        c.start();
    }

    void parar(String id) {
        Conexion c = conexiones.remove(id);
        if (c != null) c.cerrar();
    }

    void pararTodo() { for (String id : new ArrayList<>(conexiones.keySet())) parar(id); }

    /**
     * Anuncia un contacto en un clúster ("DX frecuencia indicativo comentario"), por la conexión que ya está abierta.
     * Espera unos segundos a que el clúster lo devuelva. Devuelve null si lo ha aceptado, o el motivo si no.
     * No llamar desde la pantalla (espera).
     */
    String anunciar(String fuenteId, double khz, String indicativoDx, String comentario) {
        Conexion c = conexiones.get(fuenteId);
        if (c == null || !c.dentro || c.salida == null) return "no estás conectado a este clúster";
        String orden = String.format(Locale.ROOT, "DX %.1f %s %s", khz, indicativoDx.trim().toUpperCase(Locale.ROOT),
                comentario == null ? "" : comentario.trim()).trim();
        // Solo caracteres normales (los clústeres no admiten acentos)
        orden = java.text.Normalizer.normalize(orden, java.text.Normalizer.Form.NFD).replaceAll("[^\\x20-\\x7E]", "");
        c.esperaDx = indicativoDx.trim().toUpperCase(Locale.ROOT);
        c.respuesta = null;
        try {
            synchronized (c) {
                c.salida.write((orden + "\r\n").getBytes(StandardCharsets.US_ASCII));
                c.salida.flush();
            }
        } catch (IOException e) {
            c.esperaDx = null;
            return "no se ha podido enviar (" + e.getMessage() + ")";
        }
        long hasta = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < hasta && c.respuesta == null) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        String r = c.respuesta;
        c.esperaDx = null;
        if (r == null) return null;   // sin queja del clúster en 8 s: se da por enviado
        return r.equals("OK") ? null : r;
    }

    // ---------------------------------------------------------------------------------------------------------
    /** Una conexión telnet: entra con el indicativo, lee los avisos y, si se corta, vuelve a probar al rato. */
    private final class Conexion extends Thread {
        private final Fuente f;
        private final String indicativo;
        private volatile boolean seguir = true;
        private volatile Socket so;
        volatile OutputStream salida;          // para anunciar
        volatile boolean dentro;               // ya ha entrado con el indicativo
        volatile String esperaDx, respuesta;   // anuncio enviado y lo que ha contestado el clúster

        Conexion(Fuente f, String indicativo) {
            super("ClusterDX-" + f.id());
            setDaemon(true);
            this.f = f;
            this.indicativo = indicativo;
        }

        void cerrar() {
            seguir = false;
            try { if (so != null) so.close(); } catch (Exception ignored) { }
            interrupt();
        }

        @Override public void run() {
            int espera = 15;
            String motivo = "";
            while (seguir) {
                try {
                    oyente.estado(f.id(), false, "conectando…");
                    so = new Socket();
                    so.connect(new InetSocketAddress(f.host(), f.puerto()), 10000);
                    so.setSoTimeout(1500);
                    leer(so.getInputStream(), so.getOutputStream());
                    espera = 15;
                    motivo = "el clúster ha cortado";
                } catch (Exception e) {
                    if (!seguir) break;
                    motivo = "sin conexión (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
                    oyente.estado(f.id(), false, motivo);
                } finally {
                    try { if (so != null) so.close(); } catch (Exception ignored) { }
                }
                if (!seguir) break;
                oyente.estado(f.id(), false, (motivo.isEmpty() ? "" : motivo + " · ") + "se reintenta en " + espera + " s");
                try { Thread.sleep(espera * 1000L); } catch (InterruptedException e) { break; }
                espera = Math.min(espera * 2, 300);
            }
            oyente.estado(f.id(), false, "desconectado");
        }

        private void leer(InputStream in, OutputStream out) throws IOException {
            StringBuilder linea = new StringBuilder();
            boolean entrado = false;
            salida = out;
            dentro = false;
            long inicio = System.currentTimeMillis(), ultimoDato = inicio;
            while (seguir) {
                int b;
                try { b = in.read(); ultimoDato = System.currentTimeMillis(); }
                catch (SocketTimeoutException t) {
                    // Conexión «muerta»: sigue abierta pero no llega nada. Se vuelve a conectar (antes se quedaba así horas).
                    if (System.currentTimeMillis() - ultimoDato > 10 * 60_000L) throw new IOException("10 minutos sin recibir nada");
                    // Sin salto de línea: puede ser la pregunta del indicativo ("login:", "Please enter your call:")
                    if (!entrado && pidePorIndicativo(linea.toString())) { mandar(out); entrado = true; linea.setLength(0); }
                    else if (entrado && pideContrasena(linea.toString())) { mandarContrasena(out); linea.setLength(0); }
                    else if (entrado && !historial && esPrompt(linea.toString())) pedirHistorial(out);
                    // Hay clústeres que no preguntan nada: a los 5 s se manda el indicativo igualmente
                    else if (!entrado && System.currentTimeMillis() - inicio > 5000) { mandar(out); entrado = true; }
                    continue;
                }
                if (b < 0) throw new EOFException("el clúster ha cerrado la conexión");
                if (b == 255) {   // órdenes de telnet (IAC): se ignoran
                    int cmd = in.read();
                    if (cmd >= 251 && cmd <= 254) in.read();
                    continue;
                }
                if (b == '\n' || b == '\r') {
                    String l = linea.toString();
                    linea.setLength(0);
                    if (l.isBlank()) continue;
                    if (!entrado && pidePorIndicativo(l)) { mandar(out); entrado = true; continue; }
                    if (entrado && pideContrasena(l)) { mandarContrasena(out); continue; }
                    if (entrado && !historial && esPrompt(l)) { pedirHistorial(out); continue; }
                    Aviso a = interpretar(l, f);
                    if (a == null) a = interpretarHistorial(l, f);
                    String espera = esperaDx;
                    if (espera != null) {   // contestación a un anuncio nuestro
                        String ll = l.toLowerCase(Locale.ROOT);
                        if (a != null && a.dx().equals(espera) && a.avisa().startsWith(indicativo)) respuesta = "OK";
                        else if (a == null && (ll.contains("error") || ll.contains("sorry") || ll.contains("not ") || ll.contains("invalid")
                                || ll.contains("denied") || ll.contains("must") || ll.contains("password") || ll.contains("register")))
                            respuesta = "el clúster contesta: " + l.trim();
                    }
                    if (a != null) {
                        if (!entrado) entrado = true;
                        oyente.aviso(a);
                    } else if (entrado) {
                        oyente.estado(f.id(), true, "conectado como " + indicativo);
                    }
                    continue;
                }
                if (linea.length() < 400) linea.append((char) b);
            }
        }

        private void mandarContrasena(OutputStream out) throws IOException {
            String pwd = LibroGuardiaUI.datoServicioActual("CLUSTER_" + f.id() + "_PWD");
            if (pwd == null || pwd.isEmpty()) { oyente.estado(f.id(), true, "el cluster pide contraseña (ponla en Servicios online)"); return; }
            out.write((pwd + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private boolean historial;

        /** Al entrar, se piden los últimos avisos (sh/dx) para no empezar con la lista vacía. RBN no lo admite. */
        private void pedirHistorial(OutputStream out) throws IOException {
            historial = true;
            if ("RBN".equals(f.id())) return;
            out.write("sh/dx 100\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private void mandar(OutputStream out) throws IOException {
            historial = false;
            out.write((indicativo + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            dentro = true;
            oyente.estado(f.id(), true, "conectado como " + indicativo);
        }
    }

    /** Línea de espera de órdenes del clúster, p. ej. «EA4IXR de EA4URE-2 18-Nov-2026 1904Z dxspider >». */
    static boolean esPrompt(String l) {
        String s = l.trim();
        return s.endsWith(">") && s.toLowerCase(Locale.ROOT).contains(" de ") && !s.startsWith("DX de");
    }

    // Avisos antiguos que contesta «sh/dx»:  "  7155.0  3X4U        18-Nov-2026 2144Z  up 201 loud     <KN9P>"
    private static final Pattern HIST = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+)?)\\s+([A-Za-z0-9/]+)\\s+(\\d{1,2})-([A-Za-z]{3})-(\\d{4})\\s+(\\d{4})Z\\s*(.*?)\\s*<([A-Za-z0-9/#\\-]+)>\\s*$");

    static Aviso interpretarHistorial(String linea, Fuente f) {
        Matcher m = HIST.matcher(linea);
        if (!m.matches()) return null;
        try {
            long hz = Math.round(Double.parseDouble(m.group(1)) * 1000);
            if (hz < 100_000) return null;
            java.time.LocalDate dia = java.time.LocalDate.parse(m.group(3) + "-" + m.group(4) + "-" + m.group(5),
                    java.time.format.DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH));
            int hh = Integer.parseInt(m.group(6).substring(0, 2)), mm = Integer.parseInt(m.group(6).substring(2));
            long cuando = dia.atTime(Math.min(hh, 23), Math.min(mm, 59)).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
            String dx = m.group(2).toUpperCase(Locale.ROOT), comentario = m.group(7).trim();
            String banda = banda(hz);
            String[] pais = LibroGuardiaUI.paisDeIndicativo(dx, banda);
            String entidad = pais == null ? "" : pais[1];
            return new Aviso(cuando, hz, dx, modo(hz, comentario), comentario, m.group(8).toUpperCase(Locale.ROOT).replace("-#", ""), f.nombre(),
                    pais == null ? "" : pais[0], entidad, continente(entidad), banda);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean pideContrasena(String l) {
        String s = l.trim().toLowerCase(Locale.ROOT);
        return s.endsWith("password:") || s.endsWith("password :") || s.endsWith("contraseña:") || s.matches(".*password\\s*[:>]\\s*$");
    }

    /**
     * Para «Probar» en Servicios online: entra en el clúster con el indicativo (y la contraseña si la pide) y mira si lo acepta.
     * Devuelve {"OK"|"MAL"|"AVISO", texto}.
     */
    static String[] probarEntrada(Fuente f, String call, String pwd) {
        if (call == null || call.isBlank()) return new String[]{"MAL", "falta el indicativo."};
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(f.host(), f.puerto()), 10000);
            s.setSoTimeout(500);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            StringBuilder todo = new StringBuilder(), linea = new StringBuilder();
            boolean llamado = false, conClave = false;
            long inicio = System.currentTimeMillis(), fin = inicio + 12000;
            while (System.currentTimeMillis() < fin) {
                int b;
                try { b = in.read(); }
                catch (SocketTimeoutException t) {
                    String l = linea.toString();
                    if (!llamado && (pidePorIndicativo(l) || System.currentTimeMillis() - inicio > 5000)) {
                        out.write((call.trim().toUpperCase() + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                        llamado = true; linea.setLength(0); fin = System.currentTimeMillis() + 6000;
                    } else if (llamado && !conClave && pideContrasena(l)) {
                        if (pwd == null || pwd.isEmpty()) return new String[]{"MAL", "este cluster pide contraseña: escríbela aquí."};
                        out.write((pwd + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                        conClave = true; linea.setLength(0); fin = System.currentTimeMillis() + 6000;
                    }
                    continue;
                }
                if (b < 0) break;
                if (b == 255) { int cmd = in.read(); if (cmd >= 251 && cmd <= 254) in.read(); continue; }
                if (b == '\n' || b == '\r') {
                    String l = linea.toString(); linea.setLength(0);
                    if (l.isBlank()) continue;
                    if (llamado) todo.append(l).append('\n');
                    if (!llamado && pidePorIndicativo(l)) {
                        out.write((call.trim().toUpperCase() + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                        llamado = true; fin = System.currentTimeMillis() + 6000;
                    } else if (llamado && !conClave && pideContrasena(l)) {
                        if (pwd == null || pwd.isEmpty()) return new String[]{"MAL", "este cluster pide contraseña: escríbela aquí."};
                        out.write((pwd + "\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                        conClave = true; fin = System.currentTimeMillis() + 6000;
                    }
                    continue;
                }
                if (linea.length() < 400) linea.append((char) b);
            }
            String t = todo.toString().toLowerCase(Locale.ROOT);
            if (t.contains("incorrect") || t.contains("invalid") || t.contains("wrong") || t.contains("denied") || t.contains("not registered") || t.contains("refused"))
                return new String[]{"MAL", "el cluster no acepta la entrada (revisa el indicativo y la contraseña)."};
            if (!llamado) return new String[]{"AVISO", "el cluster no ha contestado; prueba más tarde."};
            return new String[]{"OK", "entra como " + call.trim().toUpperCase() + (conClave ? " (con contraseña)" : "") + "."};
        } catch (Exception e) {
            return new String[]{"MAL", "sin conexión con el cluster (" + e.getMessage() + ")."};
        }
    }

    static boolean pidePorIndicativo(String l) {
        String s = l.trim().toLowerCase(Locale.ROOT);
        return s.endsWith("login:") || s.endsWith("call:") || s.endsWith("callsign:") || s.contains("enter your call")
                || s.endsWith("indicativo:") || s.endsWith("login: ") || s.matches(".*(login|call|callsign)\\s*[:>]\\s*$");
    }

    // ---------------------------------------------------------------------------------------------------------
    // "DX de EA4ZZB:     14025.0  3Y9ZZA       UP 2 fuerte en EA          1842Z"
    private static final Pattern SPOT = Pattern.compile(
            "^DX de\\s+([A-Za-z0-9/#\\-]+):?\\s+(\\d+(?:\\.\\d+)?)\\s+([A-Za-z0-9/]+)\\s+(.*?)\\s*(\\d{4})Z.*$", Pattern.CASE_INSENSITIVE);

    static Aviso interpretar(String linea, Fuente f) {
        Matcher m = SPOT.matcher(linea.trim());
        if (!m.matches()) return null;
        double khz;
        try { khz = Double.parseDouble(m.group(2)); } catch (NumberFormatException e) { return null; }
        long hz = Math.round(khz * 1000);
        if (hz < 100_000) return null;
        String dx = m.group(3).toUpperCase(Locale.ROOT);
        String comentario = m.group(4).trim();
        String avisa = m.group(1).toUpperCase(Locale.ROOT).replace("-#", "");
        // Hora del aviso (HHMM UTC) en el día de hoy; si queda en el futuro, era de ayer
        java.time.ZonedDateTime ahora = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        int hh = Integer.parseInt(m.group(5).substring(0, 2)), mm = Integer.parseInt(m.group(5).substring(2));
        java.time.ZonedDateTime cuando = ahora.withHour(Math.min(hh, 23)).withMinute(Math.min(mm, 59)).withSecond(0).withNano(0);
        if (cuando.isAfter(ahora.plusMinutes(5))) cuando = cuando.minusDays(1);
        String banda = banda(hz);
        String[] pais = LibroGuardiaUI.paisDeIndicativo(dx, banda);
        String iso = pais == null ? "" : pais[0], entidad = pais == null ? "" : pais[1];
        return new Aviso(cuando.toInstant().toEpochMilli(), hz, dx, modo(hz, comentario), comentario, avisa, f.nombre(),
                iso, entidad, continente(entidad), banda);
    }

    // ---------------------------------------------------------------------------------------------------------
    private static final long[][] BANDAS = {
            {1_800_000, 2_000_000}, {3_500_000, 4_000_000}, {5_250_000, 5_450_000}, {7_000_000, 7_300_000},
            {10_100_000, 10_150_000}, {14_000_000, 14_350_000}, {18_068_000, 18_168_000}, {21_000_000, 21_450_000},
            {24_890_000, 24_990_000}, {26_000_000, 27_999_999}, {28_000_000, 29_700_000}, {50_000_000, 54_000_000},
            {144_000_000, 148_000_000}, {430_000_000, 440_000_000}};
    private static final String[] NOMBRES = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "CB", "10m", "6m", "2m", "70cm"};

    static String banda(long hz) {
        for (int i = 0; i < BANDAS.length; i++) if (hz >= BANDAS[i][0] && hz <= BANDAS[i][1]) return NOMBRES[i];
        return "";
    }

    private static final long[] FT8 = {1_840_000, 3_573_000, 5_357_000, 7_074_000, 10_136_000, 14_074_000, 18_100_000, 21_074_000, 24_915_000, 28_074_000, 50_313_000, 144_174_000};
    private static final long[] FT4 = {3_575_000, 7_047_500, 10_140_000, 14_080_000, 18_104_000, 21_140_000, 24_919_000, 28_180_000, 50_318_000};
    private static final long[][] CW = {{1_800_000, 1_838_000}, {3_500_000, 3_570_000}, {7_000_000, 7_040_000}, {10_100_000, 10_130_000},
            {14_000_000, 14_070_000}, {18_068_000, 18_095_000}, {21_000_000, 21_070_000}, {24_890_000, 24_915_000},
            {28_000_000, 28_070_000}, {50_000_000, 50_100_000}, {144_000_000, 144_150_000}};

    /** Modo: el que diga el comentario y, si no dice nada, el que toca por la frecuencia. */
    static String modo(long hz, String comentario) {
        String c = " " + comentario.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", " ") + " ";
        String[][] palabras = {{" FT8 ", "FT8"}, {" FT4 ", "FT4"}, {" CW ", "CW"}, {" WPM ", "CW"}, {" RTTY ", "RTTY"},
                {" PSK", "Otros digitales"}, {" JT65", "Otros digitales"}, {" JT9 ", "Otros digitales"}, {" MSK144 ", "Otros digitales"},
                {" SSTV ", "Otros digitales"}, {" Q65 ", "Otros digitales"}, {" OLIVIA ", "Otros digitales"}, {" FM ", "FM"},
                {" AM ", "AM"}, {" SSB ", "SSB"}, {" USB ", "SSB"}, {" LSB ", "SSB"}};
        for (String[] p : palabras) if (c.contains(p[0])) return p[1];
        for (long f : FT8) if (Math.abs(hz - f) <= 3000) return "FT8";
        for (long f : FT4) if (Math.abs(hz - f) <= 3000) return "FT4";
        for (long[] r : CW) if (hz >= r[0] && hz < r[1]) return "CW";
        if (hz >= 26_965_000 && hz <= 27_405_000) return "SSB";
        if (hz >= 145_000_000 && hz <= 146_000_000 || hz >= 430_000_000) return "FM";
        return "SSB";
    }

    static boolean esFonia(String modo) { return "SSB".equals(modo) || "USB".equals(modo) || "LSB".equals(modo) || "AM".equals(modo) || "FM".equals(modo); }

    // ---------------------------------------------------------------------------------------------------------
    private static Map<String, String> continentes;

    static synchronized String continente(String entidad) {
        if (continentes == null) {
            continentes = new HashMap<>();
            try (InputStream is = ClustersDX.class.getResourceAsStream("/continentes_dxcc.txt")) {
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    String l;
                    while ((l = br.readLine()) != null) {
                        if (l.startsWith("#")) continue;
                        int i = l.lastIndexOf('|');
                        if (i > 0) continentes.put(l.substring(0, i), l.substring(i + 1).trim());
                    }
                }
            } catch (Exception ignored) { }
        }
        return entidad == null ? "" : continentes.getOrDefault(entidad, "");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Listas públicas de quién usa eQSL (AG) y LoTW. Se descargan como mucho una vez a la semana y se guardan en
    // Documentos\Libro de Guardia\listas. Solo sirven para enseñar la etiqueta eQSL / LoTW en el clúster.
    private static volatile Set<String> eqsl = Set.of(), lotw = Set.of();
    private static volatile boolean listasPedidas = false;

    static boolean usaEqsl(String call) { return eqsl.contains(base(call)); }
    static boolean usaLotw(String call) { return lotw.contains(base(call)); }

    private static String base(String call) {
        String c = call.toUpperCase(Locale.ROOT);
        String mejor = c;
        for (String p : c.split("/")) if (p.length() > 2 && p.matches(".*\\d.*") && (mejor.contains("/") || p.length() > mejor.length())) mejor = p;
        return mejor;
    }

    static void cargarListas(Path carpeta, Runnable alTerminar) {
        if (listasPedidas) return;
        listasPedidas = true;
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(carpeta);
                Path fl = carpeta.resolve("lotw-user-activity.csv"), fe = carpeta.resolve("eqsl-ag.txt");
                bajarSiViejo("https://lotw.arrl.org/lotw-user-activity.csv", fl);
                bajarSiViejo("https://www.eqsl.cc/qslcard/DownloadedFiles/AGMemberList.txt", fe);
                Set<String> l = new HashSet<>(), e = new HashSet<>();
                java.time.LocalDate hace = java.time.LocalDate.now().minusYears(1);
                if (Files.exists(fl)) for (String s : Files.readAllLines(fl, StandardCharsets.ISO_8859_1)) {
                    String[] p = s.split(",");
                    if (p.length >= 2 && p[0].matches("[A-Z0-9/]+")) {
                        try { if (java.time.LocalDate.parse(p[1].trim()).isAfter(hace)) l.add(p[0].trim()); } catch (Exception x) { l.add(p[0].trim()); }
                    }
                }
                if (Files.exists(fe)) for (String s : Files.readAllLines(fe, StandardCharsets.ISO_8859_1)) {
                    String c = s.trim().toUpperCase(Locale.ROOT);
                    if (c.matches("[A-Z0-9/]{3,}")) e.add(c);
                }
                lotw = l; eqsl = e;
            } catch (Exception ignored) { }
            if (alTerminar != null) alTerminar.run();
        }, "ListasQSL");
        t.setDaemon(true);
        t.start();
    }

    private static void bajarSiViejo(String url, Path destino) {
        try {
            if (Files.exists(destino) && System.currentTimeMillis() - Files.getLastModifiedTime(destino).toMillis() < 7L * 86_400_000) return;
            java.net.http.HttpClient h = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(15)).build();
            Path tmp = destino.resolveSibling(destino.getFileName() + ".tmp");
            java.net.http.HttpResponse<Path> r = h.send(java.net.http.HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(120))
                    .header("User-Agent", "Bitacora26/" + LibroGuardiaUI.VERSION_APP).build(), java.net.http.HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() == 200 && Files.size(tmp) > 1000) Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
            else Files.deleteIfExists(tmp);
        } catch (Exception ignored) { }
    }
}
