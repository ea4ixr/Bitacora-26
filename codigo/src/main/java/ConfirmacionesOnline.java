import javax.swing.*;
import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baja las confirmaciones de los servicios online y marca los contactos del LOG con la ★:
 *  - eQSL: las QSL que me han llegado (con fecha, hora, banda y modo) y la imagen de cada tarjeta, que se guarda en la carpeta QSL;
 *  - LoTW: los contactos confirmados (LoTW no tiene tarjetas);
 *  - QRZ.com: los contactos que el logbook de QRZ da por confirmados.
 * Cada servicio se usa con los datos del operador que los tenga configurados (y la casilla «Bajar…» marcada).
 * Se hace al arrancar (al minuto), cada 3 horas y desde el menú QSL. Solo se pide lo nuevo desde la última vez.
 * Bits en qsos.b26_qsl: 4 = eQSL, 8 = LoTW, 16 = QRZ.com (1 y 2 son las QSL de Bitácora 26).
 */
final class ConfirmacionesOnline {
    private ConfirmacionesOnline() { }

    private static final String AGENTE = "Bitacora26/" + LibroGuardiaUI.VERSION_APP + " (info@bitacora26.es)";
    private static final AtomicBoolean enMarcha = new AtomicBoolean(false);
    /** eQSL pide no bajar imágenes deprisa: una cada 8 segundos y como mucho 40 por vez (el resto, la próxima). */
    private static final int ESPERA_IMAGEN_MS = 8000, MAX_IMAGENES = 40;
    private static java.util.concurrent.ScheduledExecutorService reloj;

    /** Al arrancar el programa: la primera vez al minuto y luego cada 3 horas. */
    static synchronized void arrancar(LibroGuardiaUI app) {
        if (reloj != null) return;
        reloj = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConfirmacionesOnline");
            t.setDaemon(true);
            return t;
        });
        reloj.scheduleWithFixedDelay(() -> ejecutar(app, false), 60, 3 * 3600, java.util.concurrent.TimeUnit.SECONDS);
    }

    static void ejecutarEnSegundoPlano(LibroGuardiaUI app, boolean avisar) {
        Thread t = new Thread(() -> ejecutar(app, avisar), "ConfirmacionesOnline-ya");
        t.setDaemon(true);
        t.start();
    }

    private static void ejecutar(LibroGuardiaUI app, boolean avisar) {
        if (!enMarcha.compareAndSet(false, true)) {
            if (avisar) SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(app, "Ya se están bajando las confirmaciones; espera un poco.",
                    "Confirmaciones online", JOptionPane.INFORMATION_MESSAGE));
            return;
        }
        StringBuilder resumen = new StringBuilder();
        int marcados = 0, imagenes = 0;
        boolean algo = false;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            for (String op0 : app.obtenerListaOperadores()) {
                String op = op0.trim().toUpperCase();
                // eQSL
                String eu = LibroGuardiaUI.datoServicio(op, "EQSL_USER").trim(), ep = LibroGuardiaUI.datoServicio(op, "EQSL_PWD");
                if ("1".equals(LibroGuardiaUI.datoServicio(op, "EQSL_BAJAR")) && !eu.isEmpty() && !ep.isEmpty()) {
                    algo = true;
                    try {
                        int[] r = bajarEqsl(app, conn, op, eu, ep, LibroGuardiaUI.datoServicio(op, "EQSL_QTH_NICK").trim());
                        marcados += r[0];
                        imagenes += r[1];
                        resumen.append("eQSL (").append(eu).append("): ").append(r[2]).append(" QSL, ").append(r[1]).append(" imágenes nuevas.\n");
                    } catch (Exception e) {
                        resumen.append("eQSL (").append(eu).append("): ").append(e.getMessage()).append("\n");
                    }
                }
                // LoTW
                String lu = LibroGuardiaUI.datoServicio(op, "LOTW_USER").trim(), lp = LibroGuardiaUI.datoServicio(op, "LOTW_PWD");
                if ("1".equals(LibroGuardiaUI.datoServicio(op, "LOTW_BAJAR")) && !lu.isEmpty() && !lp.isEmpty()) {
                    algo = true;
                    try {
                        int[] r = bajarLotw(conn, op, lu, lp);
                        marcados += r[0];
                        resumen.append("LoTW (").append(lu).append("): ").append(r[1]).append(" confirmaciones.\n");
                    } catch (Exception e) {
                        resumen.append("LoTW (").append(lu).append("): ").append(e.getMessage()).append("\n");
                    }
                }
                // QRZ.com
                String key = LibroGuardiaUI.datoServicio(op, "QRZ_API_KEY").trim();
                if ("1".equals(LibroGuardiaUI.datoServicio(op, "QRZ_BAJAR")) && !key.isEmpty()) {
                    algo = true;
                    try {
                        int[] r = bajarQrz(conn, key);
                        marcados += r[0];
                        resumen.append("QRZ.com: ").append(r[1]).append(" confirmados.\n");
                    } catch (Exception e) {
                        resumen.append("QRZ.com: ").append(e.getMessage()).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            resumen.append(e.getMessage());
        } finally {
            enMarcha.set(false);
        }
        final int m = marcados, im = imagenes;
        final boolean hubo = algo;
        final String txt = resumen.toString().trim();
        if (im > 0) app.cuentaB26.refrescarQslSinLeer();
        SwingUtilities.invokeLater(() -> {
            if (m > 0 && app.tablaQSOs != null && !app.mostrandoVistaPuntos && !app.mostrandoVistaCB) app.cargarQSOsDesdeBaseDatos();
            if (!avisar) return;
            if (!hubo) JOptionPane.showMessageDialog(app, "No hay ningún servicio con «Bajar…» marcado.\n"
                    + "Ve a Archivo → Servicios online (eQSL, LoTW o QRZ.com), marca la casilla «Bajar…» y guarda.", "Confirmaciones online", JOptionPane.INFORMATION_MESSAGE);
            else JOptionPane.showMessageDialog(app, txt + "\n\nContactos marcados con la ★: " + m, "Confirmaciones online", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    // ------------------------------------------------------------------ eQSL
    /** Devuelve {contactos marcados, imágenes bajadas, QSL en la lista}. */
    private static int[] bajarEqsl(LibroGuardiaUI app, Connection conn, String op, String usr, String pwd, String nick) throws Exception {
        String claveUlt = "EQSL_ULTIMA_" + op + "_" + usr.toUpperCase();
        String desde = LibroGuardiaUI.ConfigApp.get(claveUlt, "19900101");
        String hoy = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = "https://www.eqsl.cc/qslcard/DownloadInBox.cfm?UserName=" + enc(usr) + "&Password=" + enc(pwd) + "&RcvdSince=" + desde
                + (nick.isEmpty() ? "" : "&QTHNickname=" + enc(nick));
        String html = get(url);
        String l = html.toLowerCase();
        if (l.contains("no such username") || l.contains("password incorrect")) throw new Exception("el usuario o la contraseña no coinciden");
        List<Map<String, String>> regs = new ArrayList<>();
        Matcher a = Pattern.compile("(?i)href=\"?([^\" >]+\\.adi)").matcher(html);
        if (a.find()) {
            String enlace = a.group(1);
            String adi = get(enlace.startsWith("http") ? enlace : "https://www.eqsl.cc/qslcard/" + enlace.replaceFirst("^\\.?/", ""));
            regs = leerAdif(adi);
        } else if (!l.contains("no log entries") && !l.contains("you have no")) {
            throw new Exception("eQSL no ha dado la lista (" + texto(html) + ")");
        }
        int marcados = 0, imagenes = 0;
        for (Map<String, String> r : regs) {
            Qso q = qsoDeAdif(r);
            if (q == null) continue;
            String uuid = CuentaBitacora.buscarContactoDeQsl(conn, q.call, q.banda, q.minuto);
            if (!uuid.isEmpty() && marcar(conn, uuid, 4)) marcados++;
            // Imagen de la tarjeta (si no se tiene ya)
            if (imagenes >= MAX_IMAGENES || yaTengoImagen(conn, q)) continue;
            try {
                File f = bajarImagenEqsl(usr, pwd, nick, q, r.getOrDefault("mode", ""));
                if (f == null) continue;
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO qsl_recibidas (fichero, de, cuenta, minuto, banda, modo, uuid_contacto, llegada, vista, origen) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'eQSL')")) {
                    ps.setString(1, f.getName()); ps.setString(2, q.call); ps.setString(3, usr.toUpperCase()); ps.setLong(4, q.minuto);
                    ps.setString(5, q.banda); ps.setString(6, q.modo); ps.setString(7, uuid); ps.setLong(8, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                imagenes++;
                Thread.sleep(ESPERA_IMAGEN_MS);
            } catch (Exception ignored) { }
        }
        // Si quedaron imágenes por bajar, la próxima vez se vuelve a pedir desde la misma fecha
        if (imagenes < MAX_IMAGENES) LibroGuardiaUI.ConfigApp.put(claveUlt, hoy);
        return new int[]{marcados, imagenes, regs.size()};
    }

    private static boolean yaTengoImagen(Connection conn, Qso q) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qsl_recibidas WHERE origen = 'eQSL' AND de = ? AND minuto = ? AND LOWER(banda) = ?")) {
            ps.setString(1, q.call); ps.setLong(2, q.minuto); ps.setString(3, q.banda.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getInt(1) > 0; }
        } catch (Exception e) {
            return false;
        }
    }

    private static File bajarImagenEqsl(String usr, String pwd, String nick, Qso q, String modoAdif) throws Exception {
        LocalDateTime t = LocalDateTime.ofEpochSecond(q.minuto * 60, 0, ZoneOffset.UTC);
        String url = "https://www.eqsl.cc/qslcard/GeteQSL.cfm?Username=" + enc(usr) + "&Password=" + enc(pwd) + "&CallsignFrom=" + enc(q.call)
                + "&QSOBand=" + enc(q.banda.toUpperCase()) + "&QSOMode=" + enc(modoAdif) + "&QSOYear=" + t.getYear() + "&QSOMonth=" + t.getMonthValue()
                + "&QSODay=" + t.getDayOfMonth() + "&QSOHour=" + t.getHour() + "&QSOMinute=" + t.getMinute() + (nick.isEmpty() ? "" : "&QTHNickname=" + enc(nick));
        String html = get(url);
        Matcher m = Pattern.compile("(?i)<img[^>]+src=\"([^\"]+)\"").matcher(html);
        String src = null;
        while (m.find()) {
            String s = m.group(1);
            if (s.toLowerCase().contains("cfimg") || s.toLowerCase().contains("cffileservlet") || s.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif)$")) { src = s; break; }
        }
        if (src == null) return null;
        if (!src.startsWith("http")) src = "https://www.eqsl.cc" + (src.startsWith("/") ? "" : "/") + src;
        byte[] img = getBytes(src);
        if (img.length < 1000) return null;
        String ext = src.toLowerCase().contains(".png") ? ".png" : src.toLowerCase().contains(".gif") ? ".gif" : ".jpg";
        String nombre = "eQSL_" + q.call.replaceAll("[^A-Z0-9]", "_") + "_" + t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + "_"
                + q.banda.replaceAll("[^A-Za-z0-9]", "") + "_" + q.modo.replaceAll("[^A-Za-z0-9]", "");
        File destino = new File(CuentaBitacora.carpetaQslRecibidas(), nombre + ext);
        for (int k = 2; destino.exists(); k++) destino = new File(CuentaBitacora.carpetaQslRecibidas(), nombre + "_" + k + ext);
        java.nio.file.Files.write(destino.toPath(), img);
        return destino;
    }

    // ------------------------------------------------------------------ LoTW
    /** null = bien; si no, lo que ha fallado (para «Probar» en Servicios online). */
    static String probarLotw(String usr, String pwd) {
        if (usr.isBlank() || pwd.isBlank()) return "escribe el usuario y la contraseña de la web de LoTW.";
        try {
            String r = get("https://lotw.arrl.org/lotwuser/lotwreport.adi?login=" + enc(usr.trim()) + "&password=" + enc(pwd)
                    + "&qso_query=1&qso_qsl=yes&qso_qslsince=2999-01-01");
            if (r.contains("<eoh>") || r.toLowerCase().contains("arrl logbook of the world")) return null;
            return "el usuario o la contraseña no coinciden.";
        } catch (Exception e) {
            return "no se puede conectar con LoTW (" + e.getMessage() + ").";
        }
    }

    /** Devuelve {contactos marcados, confirmaciones en la lista}. */
    private static int[] bajarLotw(Connection conn, String op, String usr, String pwd) throws Exception {
        String claveUlt = "LOTW_ULTIMA_" + op + "_" + usr.toUpperCase();
        String desde = LibroGuardiaUI.ConfigApp.get(claveUlt, "1990-01-01");
        String hoy = LocalDate.now(ZoneOffset.UTC).toString();
        String adi = get("https://lotw.arrl.org/lotwuser/lotwreport.adi?login=" + enc(usr) + "&password=" + enc(pwd)
                + "&qso_query=1&qso_qsl=yes&qso_qsldetail=no&qso_qslsince=" + desde);
        if (!adi.toLowerCase().contains("<eoh>")) throw new Exception("LoTW no ha aceptado el usuario o la contraseña");
        List<Map<String, String>> regs = leerAdif(adi);
        int marcados = 0;
        for (Map<String, String> r : regs) {
            if (!"Y".equalsIgnoreCase(r.getOrDefault("qsl_rcvd", "Y"))) continue;
            Qso q = qsoDeAdif(r);
            if (q == null) continue;
            String uuid = CuentaBitacora.buscarContactoDeQsl(conn, q.call, q.banda, q.minuto);
            if (!uuid.isEmpty() && marcar(conn, uuid, 8)) marcados++;
        }
        LibroGuardiaUI.ConfigApp.put(claveUlt, hoy);
        return new int[]{marcados, regs.size()};
    }

    // ------------------------------------------------------------------ QRZ.com
    /** Devuelve {contactos marcados, confirmados en la lista}. */
    private static int[] bajarQrz(Connection conn, String key) throws Exception {
        int marcados = 0, total = 0;
        long despuesDe = 0;
        for (int vuelta = 0; vuelta < 40; vuelta++) {   // de 250 en 250
            String cuerpo = "KEY=" + enc(key) + "&ACTION=FETCH&OPTION=" + enc("STATUS:CONFIRMED,MAX:250,AFTERLOGID:" + despuesDe);
            HttpRequest rq = HttpRequest.newBuilder(URI.create("https://logbook.qrz.com/api")).timeout(java.time.Duration.ofSeconds(60))
                    .header("User-Agent", AGENTE).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build();
            String r;
            try (HttpClient c = cliente()) { r = c.send(rq, HttpResponse.BodyHandlers.ofString()).body(); }
            Map<String, String> res = new HashMap<>();
            for (String par : r.split("&(?=[A-Z_]+=)")) {
                int i = par.indexOf('=');
                if (i > 0) res.put(par.substring(0, i), URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8));
            }
            if (!"OK".equals(res.get("RESULT"))) {
                String razon = res.getOrDefault("REASON", "");
                if (razon.toLowerCase().contains("no log entries") || "0".equals(res.get("COUNT"))) break;
                throw new Exception(razon.isEmpty() ? "QRZ.com no ha contestado bien" : razon);
            }
            String adif = res.getOrDefault("ADIF", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
            List<Map<String, String>> regs = leerAdif(adif);
            if (regs.isEmpty()) break;
            for (Map<String, String> reg : regs) {
                total++;
                try { despuesDe = Math.max(despuesDe, Long.parseLong(reg.getOrDefault("app_qrzlog_logid", "0").trim())); } catch (Exception ignored) { }
                Qso q = qsoDeAdif(reg);
                if (q == null) continue;
                String uuid = CuentaBitacora.buscarContactoDeQsl(conn, q.call, q.banda, q.minuto);
                if (!uuid.isEmpty() && marcar(conn, uuid, 16)) marcados++;
            }
            if (regs.size() < 250 || despuesDe == 0) break;
        }
        return new int[]{marcados, total};
    }

    // ------------------------------------------------------------------ piezas
    private record Qso(String call, String banda, String modo, long minuto) { }

    private static Qso qsoDeAdif(Map<String, String> r) {
        String call = CuentaBitacora.baseIndicativo(r.getOrDefault("call", "").trim().toUpperCase());
        String banda = r.getOrDefault("band", "").trim().toLowerCase();
        String fecha = r.getOrDefault("qso_date", "").trim(), hora = r.getOrDefault("time_on", "").trim();
        if (call.isEmpty() || banda.isEmpty() || fecha.length() < 8 || hora.length() < 4) return null;
        try {
            LocalDateTime t = LocalDateTime.of(Integer.parseInt(fecha.substring(0, 4)), Integer.parseInt(fecha.substring(4, 6)), Integer.parseInt(fecha.substring(6, 8)),
                    Integer.parseInt(hora.substring(0, 2)), Integer.parseInt(hora.substring(2, 4)));
            String modo = r.getOrDefault("submode", r.getOrDefault("mode", "")).trim().toUpperCase();
            return new Qso(call, banda, modo, t.toEpochSecond(ZoneOffset.UTC) / 60);
        } catch (Exception e) {
            return null;
        }
    }

    /** Marca el contacto; devuelve true si no lo estaba ya. */
    private static boolean marcar(Connection conn, String uuid, int bit) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET b26_qsl = COALESCE(b26_qsl,0) | ? WHERE uuid = ? AND (COALESCE(b26_qsl,0) & ?) = 0")) {
            ps.setInt(1, bit); ps.setString(2, uuid); ps.setInt(3, bit);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Registros de un texto ADIF, con los nombres de campo en minúsculas. */
    static List<Map<String, String>> leerAdif(String adi) {
        List<Map<String, String>> regs = new ArrayList<>();
        int i = adi.toLowerCase().indexOf("<eoh>");
        i = i < 0 ? 0 : i + 5;
        Map<String, String> actual = new HashMap<>();
        Pattern p = Pattern.compile("<([A-Za-z0-9_]+)(?::(\\d+)(?::[A-Za-z])?)?>");
        Matcher m = p.matcher(adi);
        while (m.find(i)) {
            String campo = m.group(1).toLowerCase();
            i = m.end();
            if (campo.equals("eor")) { if (!actual.isEmpty()) regs.add(actual); actual = new HashMap<>(); continue; }
            if (m.group(2) == null) continue;
            int n = Integer.parseInt(m.group(2));
            int fin = Math.min(adi.length(), i + n);
            actual.put(campo, adi.substring(i, fin));
            i = fin;
        }
        return regs;
    }

    private static HttpClient cliente() {
        return HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    private static String get(String url) throws Exception {
        try (HttpClient c = cliente()) {
            HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(90))
                    .header("User-Agent", AGENTE).build(), HttpResponse.BodyHandlers.ofByteArray());
            return new String(r.body(), StandardCharsets.ISO_8859_1);
        }
    }

    private static byte[] getBytes(String url) throws Exception {
        try (HttpClient c = cliente()) {
            return c.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(60)).header("User-Agent", AGENTE).build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String texto(String html) {
        String t = html.replaceAll("(?s)<script.*?</script>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
