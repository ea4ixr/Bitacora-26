import com.jacob.activeX.ActiveXComponent;
import com.jacob.com.Dispatch;
import com.jacob.com.ComThread;
import com.jacob.com.Variant;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cuenta Bitácora 26: confirmaciones entre usuarios y QSL */
class CuentaBitacora {
    final LibroGuardiaUI app;   // la ventana principal

    CuentaBitacora(LibroGuardiaUI app) {
        this.app = app;
    }

    // ==================================================================================================================
    // CUENTA BITÁCORA 26: CONFIRMACIONES ENTRE USUARIOS Y QSL
    // - Alta de cada indicativo (radioaficionado o CB) con correo verificado; después todo va firmado con la llave
    //   de este ordenador, así nadie puede usar ese indicativo desde otro ordenador sin el código del correo.
    // - Al servidor solo va lo justo para cruzar cada contacto: indicativo, fecha y hora UTC, banda y modo.
    //   Nunca el nombre, QTH, comentarios, RST ni nada más.
    // - Dos contactos se confirman si los indicativos se cruzan, coinciden banda y modo, y la hora difiere 30 minutos como mucho.
    // - La QSL (14x9 cm a 72 ppp, JPEG lo más pequeño posible) llega a la cuenta del otro operador, no a su correo.
    // ==================================================================================================================
    private static final String CUENTAS_URL = System.getProperty("bitacora26.cuentas", "https://bitacora26.es/api/cuentas");
    private static final HttpClient httpCuentas = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
    static final int QSL_ANCHO = 397, QSL_ALTO = 255;   // 14 x 9 cm medidos a 72 ppp: son las medidas del diseño
    static final int QSL_PPP = 150;                     // la QSL se guarda a 150 ppp (827 x 531 píxeles): letras nítidas y poco peso
    static final double QSL_ESCALA = QSL_PPP / 72.0;
    private static final int QSL_OBJETIVO_BYTES = 90 * 1024;
    private long ultimoTCuentas = 0;
    private volatile int qslSinLeer = 0;
    private JMenu menuQsl;
    private java.util.concurrent.ScheduledExecutorService hiloCuentas;
    private final java.util.concurrent.atomic.AtomicBoolean syncCuentasEnMarcha = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String ultimoProblemaCuentas = null;

    /** Indicativos con cuenta de Bitácora 26 dados de alta en este ordenador. */
    static List<String> cuentasB26() {
        List<String> l = new ArrayList<>();
        for (String s : LibroGuardiaUI.ConfigApp.get("B26_CUENTAS", "").split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty() && !l.contains(t)) l.add(t);
        }
        return l;
    }

    private static void guardarCuentasB26(List<String> l) {
        LibroGuardiaUI.ConfigApp.put("B26_CUENTAS", String.join(",", l));
    }

    /**
     * Forma para comparar indicativos (igual que en el servidor): EA4IXR/P -> EA4IXR, "Apósitos" -> APOSITOS,
     * "Lobo Solitario" -> LOBOSOLITARIO. En CB cada operador pone lo que quiere: se quitan tildes, espacios y signos.
     */
    static String baseIndicativo(String ind) {
        String r = "";
        if (ind == null) return r;
        String t = java.text.Normalizer.normalize(ind, java.text.Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "").toUpperCase()
                .replaceAll("[^A-Z0-9/]", "");
        for (String p : t.split("/")) if (p.length() > r.length()) r = p;
        return r;
    }

    /** Indicativo (forma para comparar) de una cuenta. En CB el id lleva un número: APOSITOS-12 -> APOSITOS. */
    static String indDeCuenta(String id) {
        String i = LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + id + "_IND", "");
        return i.isEmpty() ? id.replaceAll("-[0-9]+$", "") : i;
    }

    /** La cuenta (id) de este ordenador que tiene ese indicativo (y de ese tipo, si se dice). "" si no hay. */
    static String cuentaDeIndicativo(String base, String tipo) {
        if (base == null || base.isEmpty()) return "";
        for (String id : cuentasB26()) {
            if (!indDeCuenta(id).equals(base)) continue;
            if (tipo == null || tipo.equals(LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + id + "_TIPO", "HAM"))) return id;
        }
        return "";
    }

    /** El indicativo tal como lo escribió el operador (con tildes, espacios...), para enseñarlo. */
    private String indicativoParaMostrar(String cuenta) {
        String base = indDeCuenta(cuenta);
        for (String op : app.obtenerListaOperadores()) {
            String[] d = app.leerDatosOperador(op);
            for (String i : new String[]{d[0], d[1]}) if (!i.isBlank() && base.equals(baseIndicativo(i))) return i.trim().toUpperCase();
        }
        return base;
    }

    private synchronized long tCuentas() {
        long t = Math.max(System.currentTimeMillis(), ultimoTCuentas + 1);
        ultimoTCuentas = t;
        return t;
    }

    /** POST al servidor de cuentas. Devuelve {código, cuerpo}; código 0 = sin conexión. */
    static String[] postCuentas(String ruta, String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(CUENTAS_URL + ruta)).timeout(java.time.Duration.ofSeconds(25))
                    .header("Content-Type", "application/json; charset=utf-8").header("User-Agent", "Bitacora26/" + LibroGuardiaUI.VERSION_APP)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            HttpResponse<String> r = httpCuentas.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new String[]{String.valueOf(r.statusCode()), r.body()};
        } catch (Exception e) {
            return new String[]{"0", "{\"error\":\"No hay conexión con bitacora26.es\"}"};
        }
    }

    private String miPubCuentas() throws Exception {
        return java.util.Base64.getEncoder().encodeToString(app.radioclub.llaveDispositivo().getPublic().getEncoded());
    }

    /** Petición firmada con la llave de este ordenador. */
    String[] firmadoB26(String ind, String accion, String datos) {
        try {
            java.security.KeyPair kp = app.radioclub.llaveDispositivo();
            long t = tCuentas();
            String s = app.radioclub.firmar(kp.getPrivate(), "B26\n" + accion + "\n" + ind + "\n" + t + "\n" + datos);
            String cuerpo = "{\"ind\":\"" + app.jsonTexto(ind) + "\",\"pub\":\"" + app.jsonTexto(miPubCuentas()) + "\",\"t\":" + t
                    + ",\"accion\":\"" + app.jsonTexto(accion) + "\",\"datos\":\"" + app.jsonTexto(datos) + "\",\"s\":\"" + s + "\"}";
            String[] r = postCuentas("/firmado", cuerpo);
            if ("403".equals(r[0]) && r[1].contains("no está vinculado")) cuentaYaNoVinculada(ind);
            return r;
        } catch (Exception e) {
            return new String[]{"0", "{\"error\":\"" + app.jsonTexto(String.valueOf(e.getMessage())) + "\"}"};
        }
    }

    private static boolean verdadJson(String o, String k) {
        return Pattern.compile("\"" + k + "\"\\s*:\\s*true").matcher(o).find();
    }

    static String errorJson(String[] r) {
        String e = LibroGuardiaUI.campoJson(r[1], "error");
        return e.isEmpty() ? "respuesta " + r[0] : e;
    }

    /** Minutos desde 1970 (UTC) de una fecha dd/MM/yyyy y hora HH:mm guardadas en UTC. */
    private static long minutoUtc(String fecha, String hora) {
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.of(LocalDate.parse(fecha.trim(), LibroGuardiaUI.FMT_FECHA_UI),
                    LocalTime.parse(hora.trim().length() > 5 ? hora.trim().substring(0, 5) : hora.trim(), LibroGuardiaUI.FMT_HORA_UI));
            return t.toEpochSecond(ZoneOffset.UTC) / 60;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Indicativo propio con el que se hizo un contacto: el de la estación (radioclub) o el del operador (HF o CB según la banda). */
    /** La cuenta (id) con la que se hizo un contacto: la de la estación (radioclub) o la del operador (HF o CB según la banda). */
    private String miIndicativoDelQso(Map<String, String[]> cacheOps, String operador, String estacion, String banda) {
        boolean cb = "CB".equalsIgnoreCase(banda == null ? "" : banda.trim());
        if (estacion != null && !estacion.isBlank()) return cuentaDeIndicativo(baseIndicativo(estacion), null);
        String op = operador == null ? "" : operador.trim().toUpperCase();
        String[] d = cacheOps.computeIfAbsent(op, app::leerDatosOperador);
        return cuentaDeIndicativo(baseIndicativo(cb ? d[1] : d[0]), cb ? "CB" : "HAM");
    }

    /** Arranca la comprobación en segundo plano (cada 5 minutos, si hay alguna cuenta en este ordenador). */
    private void arrancarCuentasB26() {
        if (hiloCuentas != null) return;
        hiloCuentas = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CuentasB26");
            t.setDaemon(true);
            return t;
        });
        hiloCuentas.scheduleWithFixedDelay(() -> sincronizarCuentasB26(false), 30, 300, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Pide una comprobación ya (sin esperar a los 5 minutos). */
    private void comprobarCuentasPronto() {
        if (hiloCuentas == null) arrancarCuentasB26();
        hiloCuentas.schedule(() -> sincronizarCuentasB26(false), 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Sube los contactos nuevos o cambiados, recoge las confirmaciones, manda las QSL automáticas y mira el buzón. */
    private void sincronizarCuentasB26(boolean avisar) {
        List<String> cuentas = cuentasB26();
        if (cuentas.isEmpty() || !syncCuentasEnMarcha.compareAndSet(false, true)) return;
        String problema = null;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            Map<String, String[]> cacheOps = new HashMap<>();
            // 1) Contactos nuevos o cambiados (y los que se han borrado)
            Map<String, List<String>> subir = new LinkedHashMap<>(), borrar = new LinkedHashMap<>();
            Map<String, List<Integer>> idsSubidos = new LinkedHashMap<>();
            Map<Integer, String> cuentaDeId = new HashMap<>();
            List<Integer> noMios = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, COALESCE(uuid,''), COALESCE(operador,''), COALESCE(estacion,''), COALESCE(indicativo,''), "
                         + "COALESCE(fecha,''), COALESCE(hora,''), COALESCE(banda,''), COALESCE(modo,''), COALESCE(b26_cuenta,'') "
                         + "FROM qsos WHERE COALESCE(b26_sub,0) = 0 LIMIT 5000")) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    String uuid = rs.getString(2);
                    String cuenta = miIndicativoDelQso(cacheOps, rs.getString(3), rs.getString(4), rs.getString(8));
                    String antes = rs.getString(10);
                    if (!antes.isEmpty() && !antes.equals(cuenta) && cuentas.contains(antes) && !uuid.isEmpty()) {
                        borrar.computeIfAbsent(antes, k -> new ArrayList<>()).add(uuid);   // antes era de otra cuenta
                    }
                    long minuto = minutoUtc(rs.getString(6), rs.getString(7));
                    if (!cuentas.contains(cuenta) || minuto <= 0 || rs.getString(5).isBlank()) { noMios.add(id); continue; }
                    if (uuid.isEmpty()) {
                        uuid = java.util.UUID.randomUUID().toString();
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET uuid = ? WHERE id = ?")) {
                            ps.setString(1, uuid); ps.setInt(2, id); ps.executeUpdate();
                        }
                    }
                    subir.computeIfAbsent(cuenta, k -> new ArrayList<>()).add("{\"id\":\"" + app.jsonTexto(uuid) + "\",\"call\":\"" + app.jsonTexto(rs.getString(5).trim())
                            + "\",\"minuto\":" + minuto + ",\"banda\":\"" + app.jsonTexto(rs.getString(8).trim()) + "\",\"modo\":\"" + app.jsonTexto(rs.getString(9).trim()) + "\"}");
                    idsSubidos.computeIfAbsent(cuenta, k -> new ArrayList<>()).add(id);
                    cuentaDeId.put(id, cuenta);
                }
            }
            List<String> borradosVistos = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, COALESCE(cuenta,'') FROM b26_borrados")) {
                while (rs.next()) {
                    borradosVistos.add(rs.getString(1));
                    if (cuentas.contains(rs.getString(2))) borrar.computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(1));
                }
            }
            if (!noMios.isEmpty()) {
                // Todo de una vez (una sola escritura en el disco): si no, con miles de contactos la base de datos
                // quedaba ocupada varios segundos y guardar un contacto a la vez podía fallar
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET b26_sub = 2 WHERE id = ?")) {
                    for (int id : noMios) { ps.setInt(1, id); ps.addBatch(); }
                    ps.executeBatch();
                    conn.commit();
                } catch (Exception ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            Set<String> todas = new java.util.LinkedHashSet<>(subir.keySet());
            todas.addAll(borrar.keySet());
            boolean borradosBien = true;
            for (String cuenta : todas) {
                List<String> s = subir.getOrDefault(cuenta, List.of()), b = borrar.getOrDefault(cuenta, List.of());
                List<Integer> ids = idsSubidos.getOrDefault(cuenta, List.of());
                // Los borrados van primero y en trozos de 1000 (el servidor admite como mucho 2000 por envío:
                // juntos con 1000 contactos nuevos, muchos borrados hacían que el envío fallara siempre)
                boolean okCuenta = true;
                for (int j = 0; j < b.size() && okCuenta; j += 1000) {
                    StringBuilder bb = new StringBuilder();
                    for (String u : b.subList(j, Math.min(b.size(), j + 1000))) bb.append(bb.length() == 0 ? "" : ",").append('"').append(LibroGuardiaUI.jsonTexto(u)).append('"');
                    String[] r = firmadoB26(cuenta, "subir", "{\"qsos\":[],\"borrar\":[" + bb + "]}");
                    if (!"200".equals(r[0])) { problema = cuenta + ": " + errorJson(r); borradosBien = false; okCuenta = false; }
                }
                if (!okCuenta) continue;   // como antes: si falla una cuenta, se sigue con las demás
                for (int i = 0; i < s.size(); i += 1000) {
                    List<String> trozo = s.subList(i, Math.min(s.size(), i + 1000));
                    String[] r = firmadoB26(cuenta, "subir", "{\"qsos\":[" + String.join(",", trozo) + "],\"borrar\":[]}");
                    if (!"200".equals(r[0])) { problema = cuenta + ": " + errorJson(r); borradosBien = false; break; }
                    conn.setAutoCommit(false);   // los 1000 de una vez
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET b26_sub = 1, b26_cuenta = ? WHERE id = ?")) {
                        for (int id : ids.subList(Math.min(i, ids.size()), Math.min(ids.size(), i + 1000))) { ps.setString(1, cuentaDeId.get(id)); ps.setInt(2, id); ps.addBatch(); }
                        ps.executeBatch();
                        conn.commit();
                    } catch (Exception ex) {
                        conn.rollback();
                        throw ex;
                    } finally {
                        conn.setAutoCommit(true);
                    }
                }
            }
            if (borradosBien && !borradosVistos.isEmpty()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM b26_borrados WHERE uuid = ?")) {
                    for (String u : borradosVistos) { ps.setString(1, u); ps.addBatch(); }
                    ps.executeBatch();
                    conn.commit();
                } catch (Exception ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            // 2) Confirmaciones (y QSL de cada contacto)
            if (problema == null) {
                Map<String, String[]> conf = new HashMap<>();   // uuid -> {cuenta, con, envio, recibo}
                boolean todoBien = true;
                for (String cuenta : cuentas) {
                    String[] r = firmadoB26(cuenta, "confirmados", "{}");
                    if (!"200".equals(r[0])) { problema = cuenta + ": " + errorJson(r); todoBien = false; break; }
                    Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(r[1].substring(Math.max(0, r[1].indexOf('['))));
                    while (m.find()) {
                        String o = m.group();
                        conf.put(app.campoJson(o, "id"), new String[]{cuenta, app.campoJson(o, "con"), verdadJson(o, "qsl_enviada") ? "1" : "0", verdadJson(o, "qsl_recibida") ? "1" : "0"});
                    }
                }
                if (todoBien) {
                    conn.setAutoCommit(false);
                    try (Statement st = conn.createStatement();
                         PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET b26_conf = ?, b26_qsl = ? WHERE uuid = ?")) {
                        st.executeUpdate("UPDATE qsos SET b26_conf = '', b26_qsl = 0 WHERE COALESCE(b26_conf,'') <> ''");
                        for (Map.Entry<String, String[]> e : conf.entrySet()) {
                            ps.setString(1, e.getValue()[1]);
                            ps.setInt(2, ("1".equals(e.getValue()[2]) ? 1 : 0) | ("1".equals(e.getValue()[3]) ? 2 : 0));
                            ps.setString(3, e.getKey());
                            ps.executeUpdate();
                        }
                        conn.commit();
                    } catch (Exception ex) {
                        conn.rollback();
                        throw ex;
                    } finally {
                        conn.setAutoCommit(true);
                    }
                    // 3) QSL automáticas: a los contactos confirmados a los que aún no se ha mandado
                    for (Map.Entry<String, String[]> e : conf.entrySet()) {
                        String cuenta = e.getValue()[0];
                        if (!"0".equals(e.getValue()[2]) || !"1".equals(LibroGuardiaUI.ConfigApp.get("QSL_" + cuenta + "_AUTO", "0"))) continue;
                        String err = enviarQslDeContacto(conn, e.getKey());
                        if (err != null) { problema = "QSL a " + e.getValue()[1] + ": " + err; break; }
                    }
                }
            }
            // 4) QSL que han llegado: se guardan en Documentos\Libro de Guardia\QSL y se borran del servidor
            int qslNuevas = descargarQslNuevas(conn, cuentas);
            int diplomasNuevos = descargarDiplomasNuevos(cuentas);
            if (qslNuevas + diplomasNuevos > 0) avisarLlegadas(qslNuevas, diplomasNuevos);
            java.util.List<String> sinCondiciones = new java.util.ArrayList<>();
            for (String cuenta : cuentas) {
                String[] r = firmadoB26(cuenta, "estado", "{}");
                if ("200".equals(r[0]) && r[1].contains("\"condiciones_al_dia\"") && !verdadJson(r[1], "condiciones_al_dia")) sinCondiciones.add(cuenta);
                if ("200".equals(r[0])) {
                    LibroGuardiaUI.ConfigApp.put("B26_CUENTA_" + cuenta + "_VERIF", verdadJson(r[1], "verificado") ? "1" : "0");
                    long cambio = app.numeroJson(r[1], "clave_cambio");
                    long sabido = Long.parseLong(LibroGuardiaUI.ConfigApp.get("B26_CLAVE_CAMBIO_" + cuenta, "0").replaceAll("[^0-9]", "0"));
                    if (cambio > sabido) {   // la contraseña se ha cambiado en la web (o en otro ordenador)
                        for (String op : app.obtenerListaOperadores()) if (cuenta.equals(cuentaB26DeOperador(op))) LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + op + "_CLAVE_OBSOLETA", "1");
                        LibroGuardiaUI.ConfigApp.put("B26_CLAVE_CAMBIO_" + cuenta, String.valueOf(cambio));
                    }
                }
            }
            qslSinLeer = contarQslSinVer();
            if (!sinCondiciones.isEmpty()) SwingUtilities.invokeLater(() -> pedirCondiciones(sinCondiciones));
        } catch (Exception ex) {
            problema = String.valueOf(ex.getMessage());
        } finally {
            syncCuentasEnMarcha.set(false);
        }
        ultimoProblemaCuentas = problema;
        final String p = problema;
        SwingUtilities.invokeLater(() -> {
            actualizarMenuQsl();
            if (app.tablaQSOs != null && !app.mostrandoVistaPuntos && !app.mostrandoVistaCB) app.cargarQSOsDesdeBaseDatos();
            if (avisar) JOptionPane.showMessageDialog(app, p == null ? "Hecho: contactos subidos, confirmaciones al día y QSL descargadas."
                    + (qslSinLeer > 0 ? "\nTienes " + qslSinLeer + " QSL nuevas en \"QSL recibidas\"." : "")
                    : "No se ha podido completar: " + p, "Confirmaciones", p == null ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        });
    }

    /** Cuentas a las que ya se les ha preguntado en esta sesión por las condiciones nuevas. */
    private final java.util.Set<String> condicionesPreguntadas = new java.util.HashSet<>();

    /**
     * Las condiciones de uso han cambiado (o la cuenta es de antes de que existieran): hasta aceptarlas,
     * la ficha de esa cuenta no la ven los demás. Se pregunta una vez por sesión.
     */
    private void pedirCondiciones(java.util.List<String> cuentas) {
        for (String cuenta : cuentas) {
            if (!condicionesPreguntadas.add(cuenta)) continue;
            JCheckBox chk = new JCheckBox("<html>He leído y acepto las <b>condiciones de uso</b> y la <b>política de privacidad</b>.</html>");
            JButton bCond = new JButton("Leer las condiciones de uso");
            bCond.addActionListener(e -> { try { Desktop.getDesktop().browse(new URI("https://bitacora26.es/condiciones.html")); } catch (Exception ignored) { } });
            JButton bPriv = new JButton("Leer la política de privacidad");
            bPriv.addActionListener(e -> { try { Desktop.getDesktop().browse(new URI("https://bitacora26.es/privacidad.html")); } catch (Exception ignored) { } });
            JPanel leer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            leer.add(bCond);
            leer.add(bPriv);
            Object[] form = {"<html>Las condiciones de uso de la cuenta Bitácora 26 de <b>" + indicativoParaMostrar(cuenta) + "</b> son nuevas.<br><br>"
                    + "<b>Novedad:</b> tu LOG completo se guarda en bitacora26.es como copia de seguridad y lo puedes ver,<br>"
                    + "cambiar, importar y exportar desde la web (lo que cambies allí llega a la aplicación y al revés).<br>"
                    + "Los demás usuarios de Bitácora 26 verán tu nombre, QTH y Grid cuando anoten un contacto contigo.<br>"
                    + "Tu correo no se enseña nunca. <b>Hasta que las aceptes no tendrás LOG en la web, Cluster ni el resto de servicios.</b></html>", leer, chk};
            Object[] botones = {"Aceptar", "Ahora no"};
            while (true) {
                int r = JOptionPane.showOptionDialog(app, form, "Condiciones de uso", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, botones, botones[0]);
                if (r != 0) break;
                if (!chk.isSelected()) {
                    JOptionPane.showMessageDialog(app, "Marca la casilla después de leerlas.", "Condiciones de uso", JOptionPane.INFORMATION_MESSAGE);
                    continue;
                }
                String[] res = firmadoB26(cuenta, "condiciones_aceptar", "{\"acepto\":true}");
                if (!"200".equals(res[0]))
                    JOptionPane.showMessageDialog(app, "0".equals(res[0]) ? "No hay conexión con bitacora26.es; se volverá a preguntar." : errorJson(res),
                            "Condiciones de uso", JOptionPane.WARNING_MESSAGE);
                break;
            }
        }
    }

    /** Desde la ventana de actualizar: las condiciones aceptadas allí se apuntan en todas mis cuentas. */
    void aceptarCondicionesEnTodas() {
        Thread t = new Thread(() -> {
            for (String cuenta : cuentasB26()) firmadoB26(cuenta, "condiciones_aceptar", "{\"acepto\":true}");
        }, "AceptarCondiciones");
        t.setDaemon(true);
        t.start();
    }

    private void actualizarMenuQsl() {
        if (menuQsl == null) return;
        int n = qslSinLeer + diplomasSinVer();
        menuQsl.setText(n > 0 ? "QSL (" + n + ")" : "QSL");
        menuQsl.setForeground(n > 0 ? app.TEMA_AMBAR : null);
    }

    /** Texto de la columna "Confirmación". */
    static String textoConfirmacion(String conf, int qsl) {
        if (conf == null || conf.isEmpty()) return (qsl & 1) != 0 ? "QSL ENVIADA" : "";   // QSL mandada sin estar confirmado
        return switch (qsl) {
            case 1 -> "★ QSL ENVIADA";
            case 2 -> "★ QSL RECIBIDA";
            case 3 -> "★ QSL ENV. Y REC.";
            default -> "★ CONFIRMADO";
        };
    }

    // ---------------------------------------------------------------- QSL: diseño y creación de la imagen
    /** Datos propios para la QSL: {nombre, QTH, locator} del operador (o del radioclub) que tiene ese indicativo. */
    private String[] datosPropiosQsl(String cuentaId) {
        String cuenta = indDeCuenta(cuentaId);
        for (String op : app.obtenerListaOperadores()) {
            String[] d = app.leerDatosOperador(op);
            if (cuenta.equals(baseIndicativo(d[0])) || cuenta.equals(baseIndicativo(d[1]))) return new String[]{d[2], d[3], d[4]};
        }
        for (String k : new String[]{"RADIOCLUB_IND_HF", "RADIOCLUB_IND_CB", "RADIOCLUB_IND_ESP"}) {
            for (String i : LibroGuardiaUI.ConfigApp.get(k, "").split("[,;]")) {
                if (cuenta.equals(baseIndicativo(i))) return new String[]{LibroGuardiaUI.ConfigApp.get("RADIOCLUB_NOMBRE", ""), LibroGuardiaUI.ConfigApp.get("RADIOCLUB_QTH", ""), LibroGuardiaUI.ConfigApp.get("RADIOCLUB_GRID", "")};
            }
        }
        return new String[]{"", "", ""};
    }

    /** JPEG lo más pequeño posible (calidad bajando hasta quedar por debajo de 40 KB), marcado a 72 ppp. */
    static byte[] qslAJpeg(BufferedImage img) throws Exception {
        javax.imageio.ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        byte[] mejor = null;
        for (float q = 0.80f; q >= 0.35f; q -= 0.05f) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                w.setOutput(ios);
                javax.imageio.ImageWriteParam p = w.getDefaultWriteParam();
                p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(q);
                javax.imageio.metadata.IIOMetadata md = w.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(img), p);
                try {   // 72 ppp
                    org.w3c.dom.Element arbol = (org.w3c.dom.Element) md.getAsTree("javax_imageio_jpeg_image_1.0");
                    org.w3c.dom.Element jfif = (org.w3c.dom.Element) arbol.getElementsByTagName("app0JFIF").item(0);
                    jfif.setAttribute("resUnits", "1");
                    jfif.setAttribute("Xdensity", String.valueOf(QSL_PPP));
                    jfif.setAttribute("Ydensity", String.valueOf(QSL_PPP));
                    md.setFromTree("javax_imageio_jpeg_image_1.0", arbol);
                } catch (Exception ignored) { }
                w.write(null, new javax.imageio.IIOImage(img, null, md), p);
            }
            mejor = out.toByteArray();
            if (mejor.length <= QSL_OBJETIVO_BYTES) break;
        }
        w.dispose();
        return mejor;
    }

    /** Manda la QSL de un contacto (por su uuid), esté confirmado o no. Devuelve null si ha ido bien, o el motivo. */
    private String enviarQslDeContacto(Connection conn, String uuid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(indicativo,''), COALESCE(fecha,''), COALESCE(hora,''), COALESCE(banda,''), "
                + "COALESCE(modo,''), COALESCE(rst_sent,''), COALESCE(b26_cuenta,''), COALESCE(b26_conf,''), COALESCE(freq_tx,'') FROM qsos WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "no está el contacto";
                String cuenta = rs.getString(7);
                if (!cuentasB26().contains(cuenta)) return "no tienes la cuenta de ese indicativo en este ordenador";
                String hora = rs.getString(3);
                byte[] jpg = qslAJpeg(dibujarQsl(cuenta, new String[]{rs.getString(1), rs.getString(2), hora.length() > 5 ? hora.substring(0, 5) : hora,
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(9), rs.getString(8).isEmpty() ? "0" : "1"}));
                String[] r = firmadoB26(cuenta, "qsl_enviar", "{\"id\":\"" + app.jsonTexto(uuid) + "\",\"img\":\"" + java.util.Base64.getEncoder().encodeToString(jpg) + "\"}");
                if (!"200".equals(r[0])) return errorJson(r);
                try (PreparedStatement up = conn.prepareStatement("UPDATE qsos SET b26_qsl = b26_qsl | 1 WHERE uuid = ?")) {
                    up.setString(1, uuid);
                    up.executeUpdate();
                }
                return null;
            }
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Ventanas
    /** Menú "QSL" de la barra de arriba. */
    JMenu crearMenuQsl() {
        instanciaB26 = app;
        menuQsl = new JMenu("QSL");
        JMenuItem iDiseno = new JMenuItem("Diseñar mi QSL…");
        iDiseno.addActionListener(e -> abrirDisenoQsl());
        JMenuItem iBuzon = new JMenuItem("QSL recibidas…");
        qslSinLeer = contarQslSinVer();
        SwingUtilities.invokeLater(this::actualizarMenuQsl);
        iBuzon.addActionListener(e -> abrirBuzonQsl(null));
        JMenuItem iYa = new JMenuItem("Comprobar confirmaciones ahora");
        iYa.addActionListener(e -> {
            if (cuentasB26().isEmpty()) { avisoSinCuentaB26(); return; }
            new Thread(() -> sincronizarCuentasB26(true), "CuentasB26-ya").start();
        });
        JMenuItem iDiplomas = new JMenuItem("Diplomas recibidos…");
        iDiplomas.addActionListener(e -> abrirDiplomasRecibidos());
        menuQsl.add(iDiseno);
        menuQsl.add(iBuzon);
        menuQsl.addSeparator();
        menuQsl.add(iYa);
        arrancarCuentasB26();
        return menuQsl;
    }


    private void avisoSinCuentaB26() {
        JOptionPane.showMessageDialog(app, "Para confirmar contactos y mandar QSL, conecta tu indicativo a tu cuenta Bitácora 26 en\n"
                + "\"Mi Estación → Operador\". Si aún no tienes cuenta, allí mismo hay un botón para crearla (es gratis).",
                "Cuenta Bitácora 26", JOptionPane.INFORMATION_MESSAGE);
    }

    /** La ventana principal (para las comprobaciones de contraseña, que son estáticas). */
    static volatile LibroGuardiaUI instanciaB26;

    /** Indicativo con cuenta Bitácora 26 vinculada en este ordenador de un operador ("" si no tiene). */
    static String cuentaB26DeOperador(String id) {
        String k = "OPERADOR_FICHA_" + id + "_";
        String hf = LibroGuardiaUI.ConfigApp.get(k + "HF", ""), cb = LibroGuardiaUI.ConfigApp.get(k + "CB", "");
        if (hf.isEmpty() && cb.isEmpty()) hf = id;
        String r = cuentaDeIndicativo(baseIndicativo(hf), "HAM");
        return r.isEmpty() ? cuentaDeIndicativo(baseIndicativo(cb), "CB") : r;
    }

    /** Fichas ya consultadas en esta sesión (para no gastar consultas: el servidor tiene un límite al día). */
    private final Map<String, List<String[]>> fichasB26 = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Ficha de un indicativo en la base de datos de Bitácora 26: lista de {nombre, qth, grid} (en CB puede haber
     * varios con el mismo indicativo). Lista vacía si no hay; null si no se puede (sin cuenta o sin conexión).
     * Se pide con la cuenta del operador (o la primera de este ordenador). Llamar fuera del hilo de Swing.
     */
    List<String[]> buscarFichasB26(String call, boolean cb) {
        String ind = baseIndicativo(call == null ? "" : call);
        if (ind.length() < 3) return null;
        if (!cb && !ind.matches(".*[0-9][A-Z]+")) return null;   // radioaficionado: solo indicativos completos (prefijo, número y letras)
        String clave = ind + "|" + (cb ? "CB" : "HAM");
        List<String[]> ya = fichasB26.get(clave);
        if (ya != null) return ya;
        String cuenta = LibroGuardiaUI.operadorActual == null ? "" : cuentaB26DeOperador(LibroGuardiaUI.operadorActual);
        if (cuenta.isEmpty()) {
            List<String> l = cuentasB26();
            if (l.isEmpty()) return null;
            cuenta = l.getFirst();
        }
        String[] r = firmadoB26(cuenta, "buscar", "{\"ind\":\"" + app.jsonTexto(ind) + "\"}");
        if (!"200".equals(r[0])) return null;
        List<String[]> lista = new ArrayList<>();
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(r[1].substring(Math.max(0, r[1].indexOf("\"fichas\""))));
        while (m.find()) {
            String f = m.group();
            if (cb != "CB".equals(app.campoJson(f, "tipo"))) continue;   // en CB, las fichas de CB; si no, las de radioaficionado
            String[] d = {app.campoJson(f, "nombre").trim().toUpperCase(), app.campoJson(f, "qth").trim().toUpperCase(), app.campoJson(f, "grid").trim().toUpperCase()};
            if (!(d[0] + d[1] + d[2]).isEmpty()) lista.add(d);
        }
        fichasB26.put(clave, lista);
        return lista;
    }

    /** ¿Es esta la contraseña de la cuenta? true/false, o null si no hay conexión. */
    Boolean comprobarClaveEnServidor(String cuenta, String clave) {
        String[] r = firmadoB26(cuenta, "clave_comprobar", "{\"clave\":\"" + app.jsonTexto(clave) + "\"}");
        if ("200".equals(r[0])) return true;
        if ("403".equals(r[0]) && r[1].contains("contraseña")) return false;
        return null;
    }

    /** Después de vincular o crear la cuenta: sus indicativos quedan en este ordenador. */
    private void guardarPerfilVinculado(String perfil) {
        List<String> l = cuentasB26();
        for (String[] par : new String[][]{{app.campoJson(perfil, "hf"), app.campoJson(perfil, "hf_id"), "HAM"}, {app.campoJson(perfil, "cb"), app.campoJson(perfil, "cb_id"), "CB"}}) {
            String ind = par[0], i = par[1].isEmpty() ? par[0] : par[1];   // i = id de la cuenta (en CB lleva un número)
            if (i.isEmpty()) continue;
            if (!l.contains(i)) l.add(i);
            LibroGuardiaUI.ConfigApp.put("B26_CUENTA_" + i + "_IND", baseIndicativo(ind));   // (el perfil lo trae tal como se escribió)
            LibroGuardiaUI.ConfigApp.put("B26_CUENTA_" + i + "_TIPO", par[2]);
            LibroGuardiaUI.ConfigApp.put("B26_CLAVE_CAMBIO_" + i, String.valueOf(app.numeroJson(perfil, "clave_cambio")));
            LibroGuardiaUI.ConfigApp.put("B26_CUENTA_" + i + "_PID", String.valueOf(app.campoJson(perfil, "correo").hashCode()));   // los indicativos de la misma cuenta
        }
        guardarCuentasB26(l);
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE qsos SET b26_sub = 0 WHERE COALESCE(b26_sub,0) = 2");   // ahora sí pueden ser de una cuenta
        } catch (Exception ignored) { }
        comprobarCuentasPronto();
    }

    private static String nombreDeEsteOrdenador() {
        String n = System.getenv("COMPUTERNAME");
        if (n == null || n.isBlank()) n = System.getenv("HOSTNAME");
        return n == null ? "" : n.trim();
    }

    /** Pide el código que ha llegado al correo. null si se cancela. */
    private static String pedirCodigoCorreo(Component padre, String aQuien, String pruebas) {
        String cod = JOptionPane.showInputDialog(padre, "Te hemos mandado un código de 6 cifras a " + aQuien + ".\n"
                + "(Si no llega en unos minutos, mira en correo no deseado.)\n\nEscribe el código:", pruebas == null || pruebas.isEmpty() ? "" : pruebas);
        return cod == null ? null : cod.trim();
    }

    /**
     * "Ya tengo cuenta": indicativo + contraseña + código del correo. Este ordenador queda vinculado.
     * Devuelve {perfil JSON, contraseña} o null.
     */
    private String[] vincularCuentaB26(Component padre, String indInicial) {
        JTextField txtInd = new JTextField(baseIndicativo(indInicial == null ? "" : indInicial), 14);
        app.aplicarFiltroMayusculas(txtInd);
        JPasswordField pf = new JPasswordField(16);
        if (JOptionPane.showConfirmDialog(padre, new Object[]{"Indicativo de tu cuenta (de radioaficionado o de CB):", txtInd,
                        "Contraseña de tu cuenta:", pf}, "Ya tengo cuenta Bitácora 26", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) return null;
        String ind = baseIndicativo(txtInd.getText());
        String clave = new String(pf.getPassword());
        if (!ind.matches("[A-Z0-9]{2,30}") || clave.isEmpty()) {
            JOptionPane.showMessageDialog(padre, "Escribe el indicativo de tu cuenta y su contraseña.", "Cuenta Bitácora 26", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        try {
            java.security.KeyPair kp = app.radioclub.llaveDispositivo();
            long t = tCuentas();
            String[] r = postCuentas("/vincular", "{\"ind\":\"" + app.jsonTexto(ind) + "\",\"clave\":\"" + app.jsonTexto(clave) + "\",\"pub\":\"" + app.jsonTexto(miPubCuentas())
                    + "\",\"equipo\":\"" + app.jsonTexto(nombreDeEsteOrdenador()) + "\",\"t\":" + t + ",\"s\":\"" + app.radioclub.firmar(kp.getPrivate(), "VINCULAR\n" + ind + "\n" + t) + "\"}");
            if (!"200".equals(r[0])) { JOptionPane.showMessageDialog(padre, errorJson(r), "Cuenta Bitácora 26", JOptionPane.WARNING_MESSAGE); return null; }
            for (int intento = 0; intento < 5; intento++) {
                String cod = pedirCodigoCorreo(padre, app.campoJson(r[1], "correo"), app.campoJson(r[1], "codigo_pruebas"));
                if (cod == null) return null;
                long t2 = tCuentas();
                String[] v = postCuentas("/vincular_codigo", "{\"ind\":\"" + app.jsonTexto(ind) + "\",\"pub\":\"" + app.jsonTexto(miPubCuentas()) + "\",\"codigo\":\""
                        + app.jsonTexto(cod) + "\",\"t\":" + t2 + ",\"s\":\"" + app.radioclub.firmar(kp.getPrivate(), "VINCULAR_CODIGO\n" + ind + "\n" + cod + "\n" + t2) + "\"}");
                if ("200".equals(v[0])) {
                    String perfil = v[1].substring(Math.max(0, v[1].indexOf("\"perfil\"")));
                    guardarPerfilVinculado(perfil);
                    return new String[]{perfil, clave};
                }
                JOptionPane.showMessageDialog(padre, errorJson(v), "Código", JOptionPane.WARNING_MESSAGE);
                if (v[1].contains("caducado") || v[1].contains("Demasiados")) return null;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "No se ha podido: " + ex.getMessage(), "Cuenta Bitácora 26", JOptionPane.WARNING_MESSAGE);
        }
        return null;
    }

    /** Botón «Crear cuenta» siempre en ámbar, como en la ficha del operador. */
    static void pintarCrearCuenta(JButton b) {
        b.setFont(b.getFont().deriveFont(Font.BOLD));
        b.setBackground(LibroGuardiaUI.TEMA_AMBAR);
        b.setForeground(new Color(27, 20, 4));
    }

    /** Ventana de opciones en la que el botón «Crear cuenta» sale en ámbar. Devuelve el número del botón pulsado (o -1). */
    static int opcionesCuenta(Component padre, Object mensaje, String titulo, int tipo, String... textos) {
        JButton[] bs = new JButton[textos.length];
        JOptionPane pane = new JOptionPane(mensaje, tipo, JOptionPane.DEFAULT_OPTION, null, bs);
        for (int i = 0; i < textos.length; i++) {
            bs[i] = new JButton(textos[i]);
            if ("Crear cuenta".equals(textos[i])) pintarCrearCuenta(bs[i]);
            JButton b = bs[i];
            b.addActionListener(e -> pane.setValue(b));
        }
        pane.setOptions(bs);
        pane.setInitialValue(bs[0]);
        JDialog d = pane.createDialog(padre, titulo);
        d.setVisible(true);
        d.dispose();
        for (int i = 0; i < bs.length; i++) if (bs[i] == pane.getValue()) return i;
        return -1;
    }

    /** ¿Hay alguna cuenta Bitácora 26 conectada en este ordenador? */
    boolean hayCuenta() { return !cuentasB26().isEmpty(); }

    /** «Ya tengo cuenta» desde la bienvenida (sin ficha de operador todavía). Devuelve {perfil JSON, contraseña} o null. */
    String[] vincularDesdeBienvenida(Component padre) { return vincularCuentaB26(padre, ""); }

    static final int DIAS_SIN_CUENTA = 5;

    /**
     * Sincronizar (ordenadores o radioclub) necesita cuenta. Quien ya sincronizaba sin cuenta tiene 5 días desde el primer aviso.
     * Devuelve true si se puede sincronizar ahora.
     */
    boolean sincronizarPermitido() {
        if (hayCuenta()) return true;
        long desde = 0;
        try { desde = Long.parseLong(LibroGuardiaUI.ConfigApp.get("SIN_CUENTA_AVISO_DESDE", "0")); } catch (Exception ignored) { }
        return desde > 0 && System.currentTimeMillis() - desde < DIAS_SIN_CUENTA * 86_400_000L;
    }

    /** Días que le quedan a quien sincronizaba sin cuenta (empieza a contar en el primer aviso). */
    int diasSinCuentaRestantes() {
        long desde = 0;
        try { desde = Long.parseLong(LibroGuardiaUI.ConfigApp.get("SIN_CUENTA_AVISO_DESDE", "0")); } catch (Exception ignored) { }
        if (desde <= 0) { desde = System.currentTimeMillis(); LibroGuardiaUI.ConfigApp.put("SIN_CUENTA_AVISO_DESDE", String.valueOf(desde)); }
        long quedan = DIAS_SIN_CUENTA * 86_400_000L - (System.currentTimeMillis() - desde);
        return quedan <= 0 ? 0 : (int) Math.ceil(quedan / 86_400_000.0);
    }

    /**
     * Para lo que solo se puede hacer con cuenta. Si no hay, lo explica y ofrece crearla o entrar.
     * Devuelve true si (ya) hay cuenta.
     */
    boolean exigirCuenta(Component padre, String que) {
        if (hayCuenta()) return true;
        int r = opcionesCuenta(padre, "<html>Para <b>" + que + "</b> hace falta una cuenta de Bitácora 26.<br><br>"
                        + "La cuenta es <b>gratuita</b> y se crea en un momento con tus datos.</html>",
                "Hace falta una cuenta", JOptionPane.INFORMATION_MESSAGE, "Crear cuenta", "Ya tengo cuenta", "Cancelar");
        if (r == 1) {
            vincularCuentaB26(padre, app.operadorActual);
        } else if (r == 0) {
            crearCuentaConMiFicha(padre);
        }
        return hayCuenta();
    }

    /** Crea la cuenta con la ficha del operador actual (pide solo la contraseña). */
    private void crearCuentaConMiFicha(Component padre) {
        String[] d = app.leerDatosOperador(app.operadorActual == null ? "" : app.operadorActual);
        if (d[2].isBlank() || d[3].isBlank() || !d[4].trim().toUpperCase().matches("[A-R]{2}[0-9]{2}([A-X]{2})?")) {
            JOptionPane.showMessageDialog(padre, "Para crear la cuenta hacen falta tu nombre, tu QTH y tu Grid.\n\n"
                    + "Rellénalos en Mi Estación → Operador (mis datos y mi cuenta) y pulsa allí «Crear cuenta».", "Crear cuenta", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JPasswordField p1 = new JPasswordField(16), p2 = new JPasswordField(16);
        while (true) {
            if (JOptionPane.showConfirmDialog(padre, new Object[]{"<html>Se creará la cuenta de <b>" + (d[0].isBlank() ? d[1] : d[0]) + "</b> con tus datos.<br><br>"
                            + "Contraseña de la cuenta (al menos 8 caracteres):</html>", p1, "Repítela:", p2},
                    "Crear cuenta", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            char[] c1 = p1.getPassword(), c2 = p2.getPassword();
            if (c1.length < 8) { JOptionPane.showMessageDialog(padre, "La contraseña tiene que tener al menos 8 caracteres.", "Crear cuenta", JOptionPane.WARNING_MESSAGE); continue; }
            if (!java.util.Arrays.equals(c1, c2)) { JOptionPane.showMessageDialog(padre, "Las dos contraseñas no coinciden.", "Crear cuenta", JOptionPane.WARNING_MESSAGE); continue; }
            if (crearCuentaB26(padre, d[0], d[1], d[2], d[3], d[4], c1) != null)
                JOptionPane.showMessageDialog(padre, "¡Cuenta creada! Este ordenador ya está conectado.", "Crear cuenta", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
    }

    /** Al abrir: a quien ya sincronizaba sin cuenta se le avisa de los días que le quedan (y luego de que está parado). */
    void avisarSincronizarSinCuenta() {
        if (hayCuenta() || !(app.radioclub.syncActivo() || app.radioclub.syncOpActivo())) return;
        int dias = diasSinCuentaRestantes();
        String txt = dias > 0
                ? "<html>Ahora, para <b>sincronizar tus ordenadores y con el radioclub</b> hace falta una cuenta de Bitácora 26 (es gratuita).<br><br>"
                + "De momento te sigue funcionando: tienes <b>" + dias + (dias == 1 ? " día" : " días") + "</b> para crearla.<br>"
                + "Pasado ese tiempo, la sincronización se parará hasta que la crees.</html>"
                : "<html><b>La sincronización está parada</b> porque no tienes cuenta de Bitácora 26.<br><br>"
                + "Crea tu cuenta (es gratuita) o entra con la que tengas y volverá a funcionar.</html>";
        int r = opcionesCuenta(app, txt, "Cuenta de Bitácora 26", dias > 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE,
                "Crear cuenta", "Ya tengo cuenta", dias > 0 ? "Más tarde" : "Cancelar");
        if (r == 1) vincularCuentaB26(app, app.operadorActual);
        else if (r == 0) crearCuentaConMiFicha(app);
    }

    /** Añade a mi cuenta el otro indicativo (el de CB o el de radioaficionado). */
    private boolean anadirIndicativoB26(Component padre, String con, String ind, String tipo) {
        // "con" es un indicativo que ya está en la cuenta (en este ordenador puede haber cuentas de varios operadores)
        if (con == null || !cuentasB26().contains(con)) return false;
        String[] r = firmadoB26(con, "indicativo_anadir", "{\"ind\":\"" + app.jsonTexto(baseIndicativo(ind)) + "\",\"tipo\":\"" + tipo + "\"}");
        if (!"200".equals(r[0])) {
            JOptionPane.showMessageDialog(padre, "0".equals(r[0]) ? "No hay conexión con bitacora26.es." : errorJson(r), "Cuenta Bitácora 26", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        guardarPerfilVinculado(r[1].substring(Math.max(0, r[1].indexOf("\"perfil\""))));
        JOptionPane.showMessageDialog(padre, "Hecho: " + ind.trim().toUpperCase() + " está ya en tu cuenta (y en la web).", "Cuenta Bitácora 26", JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    /** "Crear mi cuenta" con los datos de la ficha del operador. Devuelve el perfil JSON o null. */
    private String crearCuentaB26(Component padre, String hf, String cb, String nombre, String qth, String grid, char[] clave) {
        JTextField txtCorreo = new JTextField(24);
        JCheckBox chkAcepto = new JCheckBox("<html>He leído y acepto las <b>condiciones de uso</b> y la <b>política de privacidad</b><br>"
                + "(qué datos se guardan, quién los ve y para qué se usan).</html>");
        JButton bLeerCond = new JButton("Leer las condiciones de uso");
        bLeerCond.addActionListener(e -> {
            try { Desktop.getDesktop().browse(new URI("https://bitacora26.es/condiciones.html")); } catch (Exception ignored) { }
        });
        JButton bLeer = new JButton("Leer la política de privacidad");
        bLeer.addActionListener(e -> {
            try { Desktop.getDesktop().browse(new URI("https://bitacora26.es/privacidad.html")); } catch (Exception ignored) { }
        });
        JPanel leer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leer.add(bLeerCond);
        leer.add(bLeer);
        // Nombre y apellidos, QTH y locator son obligatorios para todos (como en la web)
        java.util.List<String> faltan = new java.util.ArrayList<>();
        if (nombre == null || nombre.isBlank()) faltan.add("nombre y apellidos");
        if (qth == null || qth.isBlank()) faltan.add("QTH");
        if (grid == null || grid.isBlank()) faltan.add("locator (Grid)");
        if (!faltan.isEmpty()) {
            JOptionPane.showMessageDialog(padre, "Para crear la cuenta hace falta rellenar antes en la ficha del operador: " + String.join(", ", faltan) + ".\n"
                    + "Si no sabes tu locator, búscalo en el mapa con el botón que hay junto al Grid.", "Crear cuenta Bitácora 26", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        JPanel filaCorreo = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaCorreo.add(new JLabel("Tu correo:"));
        filaCorreo.add(txtCorreo);
        java.util.List<Object> partes = new java.util.ArrayList<>(java.util.List.of(
                "<html>Se creará con <b>" + String.join(" · ", app.radioclub.textosNoVacios(hf.toUpperCase(), cb.toUpperCase())) + "</b> y la contraseña de la ficha.</html>",
                filaCorreo));
        partes.add("<html><font size='2'>Los demás usuarios de Bitácora 26 verán tu indicativo, nombre y apellidos, QTH y locator cuando anoten un contacto contigo<br>"
                + "y, si pulsas ON AIR, en el Cluster.<br>"
                + "Tu correo no se enseña nunca.</font></html>");
        partes.add(chkAcepto);
        partes.add(leer);
        Object[] form = partes.toArray();
        Object[] botones = {"Mandarme el código", "Cancelar"};
        String correo;
        while (true) {
            if (JOptionPane.showOptionDialog(padre, form, "Crear cuenta Bitácora 26", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, botones, botones[0]) != 0) return null;
            correo = txtCorreo.getText().trim();
            String falta = !correo.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? "Escribe tu correo: te llegará un código."
                    : !chkAcepto.isSelected() ? "Para crear la cuenta tienes que aceptar las condiciones de uso y la política de privacidad." : null;
            if (falta == null) break;
            JOptionPane.showMessageDialog(padre, falta, "Crear cuenta", JOptionPane.INFORMATION_MESSAGE);
        }
        try {
            java.security.KeyPair kp = app.radioclub.llaveDispositivo();
            long t = tCuentas();
            String c = correo.trim().toLowerCase();
            String[] r = postCuentas("/registro", "{\"hf\":\"" + app.jsonTexto(hf) + "\",\"cb\":\"" + app.jsonTexto(cb) + "\",\"nombre\":\"" + app.jsonTexto(nombre)
                    + "\",\"qth\":\"" + app.jsonTexto(qth) + "\",\"grid\":\"" + app.jsonTexto(grid) + "\",\"correo\":\"" + app.jsonTexto(c) + "\",\"clave\":\""
                    + app.jsonTexto(new String(clave)) + "\",\"acepto\":true,\"condiciones\":true,\"pub\":\"" + app.jsonTexto(miPubCuentas()) + "\",\"equipo\":\"" + app.jsonTexto(nombreDeEsteOrdenador())
                    + "\",\"t\":" + t + ",\"s\":\"" + app.radioclub.firmar(kp.getPrivate(), "REGISTRO\n" + c + "\n" + t) + "\"}");
            if (!"200".equals(r[0])) { JOptionPane.showMessageDialog(padre, errorJson(r), "Crear mi cuenta", JOptionPane.WARNING_MESSAGE); return null; }
            for (int intento = 0; intento < 5; intento++) {
                String cod = pedirCodigoCorreo(padre, c, app.campoJson(r[1], "codigo_pruebas"));
                if (cod == null) return null;
                String[] v = postCuentas("/registro_verificar", "{\"correo\":\"" + app.jsonTexto(c) + "\",\"codigo\":\"" + app.jsonTexto(cod) + "\"}");
                if ("200".equals(v[0])) {
                    String perfil = v[1].substring(Math.max(0, v[1].indexOf("\"perfil\"")));
                    guardarPerfilVinculado(perfil);
                    return perfil;
                }
                JOptionPane.showMessageDialog(padre, errorJson(v), "Código", JOptionPane.WARNING_MESSAGE);
                if (v[1].contains("caducado") || v[1].contains("Demasiados")) return null;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "No se ha podido: " + ex.getMessage(), "Crear mi cuenta", JOptionPane.WARNING_MESSAGE);
        }
        return null;
    }


    /** El servidor ya no reconoce este ordenador en esa cuenta (se quitó desde la web, o la cuenta ya no existe). */
    private void cuentaYaNoVinculada(String ind) {
        List<String> l = cuentasB26();
        if (!l.remove(ind)) return;
        guardarCuentasB26(l);
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE qsos SET b26_sub = 0, b26_conf = '', b26_qsl = 0, b26_cuenta = '' WHERE b26_cuenta = ?")) {
            ps.setString(1, ind); ps.executeUpdate();
        } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(app, "Este ordenador ya no está vinculado a la cuenta de " + ind + ".\n"
                + "(Se quitó desde la web o la cuenta ya no existe.)\n\n"
                + "Para volver a usarla: Mi Estación → Operador → «Ya tengo cuenta» (o «Crear mi cuenta»).",
                "Cuenta Bitácora 26", JOptionPane.INFORMATION_MESSAGE));
    }

    /** Recuadro "Cuenta Bitácora 26" de la ficha del operador (opcional). campos = HF, CB, nombre, QTH, grid. */
    JPanel panelCuentaB26Operador(JTextField[] campos, JPasswordField pf1, JPasswordField pf2, JLabel notaClave, String notaBase) {
        JPanel caja = new JPanel(new BorderLayout(6, 6));
        caja.setOpaque(false);
        javax.swing.border.TitledBorder borde = BorderFactory.createTitledBorder("Cuenta Bitácora 26 (opcional)");
        caja.setBorder(borde);
        JLabel lbl = new JLabel();
        JButton bCrear = new JButton("Crear cuenta"), bTengo = new JButton("Ya tengo cuenta"), bGestionar = new JButton("Gestionar mi cuenta…");
        pintarCrearCuenta(bCrear);
        // Aviso solo cuando hace falta: un indicativo de la ficha que no está en la cuenta
        JPanel aviso = new JPanel(new BorderLayout(8, 0));
        aviso.setBackground(new Color(70, 58, 30));
        aviso.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(200, 150, 50)), BorderFactory.createEmptyBorder(4, 8, 4, 4)));
        JLabel lblAviso = new JLabel();
        lblAviso.setForeground(Color.WHITE);
        JButton bAnadir = new JButton("Añadir");
        aviso.add(lblAviso, BorderLayout.CENTER);
        aviso.add(bAnadir, BorderLayout.EAST);
        final String[] queAnadir = {"", "", ""};   // indicativo como se escribió, tipo, cuenta con la que se pide
        Runnable pintar = () -> {
            String hfTxt = campos[0].getText().trim().toUpperCase(), cbTxt = campos[1].getText().trim().toUpperCase();
            String hf = baseIndicativo(hfTxt), cb = baseIndicativo(cbTxt);
            String cuentaHf = cuentaDeIndicativo(hf, "HAM"), cuentaCb = cuentaDeIndicativo(cb, "CB");
            boolean conectada = !cuentaHf.isEmpty() || !cuentaCb.isEmpty();
            borde.setTitle(conectada ? "Cuenta Bitácora 26" : "Cuenta Bitácora 26 (opcional)");
            if (conectada) {
                lbl.setText("<html><font color='#4cd07d'><b>✓ Conectada:</b></font> "
                        + String.join(" · ", app.radioclub.textosNoVacios(cuentaHf.isEmpty() ? "" : hfTxt, cuentaCb.isEmpty() ? "" : cbTxt)) + "</html>");
            } else {
                lbl.setText("<html><font color='#8796ad'>Sin cuenta: confirmaciones y QSL desactivadas</font></html>");
            }
            bCrear.setVisible(!conectada);
            bTengo.setVisible(!conectada);
            bGestionar.setVisible(conectada);
            // ¿Falta en la cuenta alguno de los indicativos de la ficha?
            queAnadir[0] = !conectada ? "" : !hf.isEmpty() && cuentaHf.isEmpty() ? hfTxt : !cb.isEmpty() && cuentaCb.isEmpty() ? cbTxt : "";
            queAnadir[1] = !queAnadir[0].isEmpty() && queAnadir[0].equals(cbTxt) && cuentaCb.isEmpty() ? "CB" : "HAM";
            queAnadir[2] = cuentaHf.isEmpty() ? cuentaCb : cuentaHf;
            lblAviso.setText("«" + queAnadir[0] + "» no está en tu cuenta");
            boolean antes = aviso.isVisible();
            aviso.setVisible(!queAnadir[0].isEmpty());
            if (notaClave != null) notaClave.setText("<html><font size='2'>" + notaBase + "<br>"
                    + (conectada ? "Es también la de tu cuenta Bitácora 26 y la de la web." : "Si creas cuenta, será también la de la web.") + "</font></html>");
            caja.revalidate();
            caja.repaint();
            if (antes != aviso.isVisible()) {
                Window w = SwingUtilities.getWindowAncestor(caja);
                if (w != null) w.pack();
            }
        };
        bCrear.addActionListener(e -> {
            String hfTxt = campos[0].getText().trim(), cbTxt = campos[1].getText().trim();
            if (!pf1.isVisible() && pf1.getClientProperty("mostrar") instanceof Runnable mostrar) mostrar.run();
            char[] c1 = pf1.getPassword(), c2 = pf2.getPassword();
            String nomTxt = campos[2].getText().trim(), qthTxt = campos[3].getText().trim(), gridTxt = campos[4].getText().trim().toUpperCase();
            String falta = hfTxt.isEmpty() && cbTxt.isEmpty() ? "Escribe primero tu indicativo."
                    : !gridTxt.matches("[A-R]{2}[0-9]{2}([A-X]{2})?") ? "Pon tu Grid (locator). Si no lo sabes, pulsa «Elegir en el mapa…» y pincha donde vives."
                    : nomTxt.isEmpty() || qthTxt.isEmpty() ? "Para crear la cuenta pon tu nombre y apellidos y tu QTH."
                    : c1.length < 8 ? "Escribe arriba la contraseña dos veces (al menos 8 caracteres): será la de tu cuenta."
                    : !java.util.Arrays.equals(c1, c2) ? "Las dos contraseñas no coinciden." : null;
            if (falta != null) { JOptionPane.showMessageDialog(caja, falta, "Crear cuenta", JOptionPane.INFORMATION_MESSAGE); return; }
            String perfil = crearCuentaB26(caja, hfTxt, cbTxt, campos[2].getText().trim(), campos[3].getText().trim(), campos[4].getText().trim(), c1);
            pintar.run();
            if (perfil != null) JOptionPane.showMessageDialog(caja, "¡Cuenta creada! Este ordenador ya está conectado.\nPulsa Aceptar para guardar el operador.",
                    "Crear cuenta", JOptionPane.INFORMATION_MESSAGE);
        });
        bTengo.addActionListener(e -> {
            String[] r = vincularCuentaB26(caja, campos[0].getText().isBlank() ? campos[1].getText() : campos[0].getText());
            if (r == null) { pintar.run(); return; }
            // La ficha se rellena con los datos de la cuenta y la contraseña del operador pasa a ser la de la cuenta
            String[] k = {"hf", "cb", "nombre", "qth", "grid"};
            for (int i = 0; i < 5; i++) {
                String v = app.campoJson(r[0], k[i]);
                if (!v.isBlank()) campos[i].setText(v.toUpperCase());
            }
            pf1.setText(r[1]);
            pf2.setText(r[1]);
            pintar.run();
            // La clave del radioclub: si está guardada en la cuenta, no hace falta pedirla en este ordenador
            String cuentaId = app.campoJson(r[0], "hf_id");
            if (cuentaId.isBlank()) cuentaId = app.campoJson(r[0], "cb_id");
            String unirse = "";
            if (!cuentaId.isBlank()) {
                String soc = campos[0].getText().isBlank() ? campos[1].getText().trim().toUpperCase() : campos[0].getText().trim().toUpperCase();
                if (app.radioclub.syncActivo()) {
                    app.radioclub.guardarClaveRCEnCuenta(cuentaId, soc);
                } else if (app.invitacionInicial == null) {
                    java.util.SortedMap<String, String> inv = app.radioclub.leerClaveRCDeCuenta(cuentaId);
                    if (inv != null) {
                        String hf = campos[0].getText().trim(), cb = campos[1].getText().trim(), ind = inv.getOrDefault("ind", "");
                        if (ind.equalsIgnoreCase(hf) || ind.equalsIgnoreCase(cb) || inv.getOrDefault("shf", "").equalsIgnoreCase(hf)
                                || inv.getOrDefault("scb", "").equalsIgnoreCase(cb)) {
                            app.invitacionInicial = inv;
                            unirse = "\n\nAdemás, al guardar te unirás al radioclub " + inv.getOrDefault("n", "") + " sin tener que pegar su clave.";
                        }
                    }
                }
            }
            JOptionPane.showMessageDialog(caja, "Hecho: este ordenador está conectado a tu cuenta y la ficha se ha rellenado.\nPulsa Aceptar para guardar el operador." + unirse,
                    "Cuenta Bitácora 26", JOptionPane.INFORMATION_MESSAGE);
        });
        bGestionar.addActionListener(e -> {
            String hf = baseIndicativo(campos[0].getText()), cb = baseIndicativo(campos[1].getText());
            String id = cuentaDeIndicativo(hf, "HAM");
            gestionarCuentaB26(caja, id.isEmpty() ? cuentaDeIndicativo(cb, "CB") : id);
            pintar.run();
        });
        bAnadir.addActionListener(e -> {
            if (queAnadir[0].isEmpty() || queAnadir[2].isEmpty()) return;
            anadirIndicativoB26(caja, queAnadir[2], queAnadir[0], queAnadir[1]);
            pintar.run();
        });
        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { SwingUtilities.invokeLater(pintar); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { SwingUtilities.invokeLater(pintar); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        };
        campos[0].getDocument().addDocumentListener(dl);
        campos[1].getDocument().addDocumentListener(dl);
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        botones.setOpaque(false);
        botones.add(bCrear); botones.add(bTengo); botones.add(bGestionar);
        JPanel arriba = new JPanel(new BorderLayout(0, 6));
        arriba.setOpaque(false);
        arriba.add(lbl, BorderLayout.NORTH);
        arriba.add(botones, BorderLayout.CENTER);
        caja.add(arriba, BorderLayout.CENTER);
        caja.add(aviso, BorderLayout.SOUTH);
        aviso.setVisible(false);
        pintar.run();
        return caja;
    }

    /** Ventana "Mi cuenta Bitácora 26": indicativos, ordenadores, la web, desconectar este ordenador y borrar la cuenta. */
    private void gestionarCuentaB26(Component padre, String cuenta) {
        if (cuenta == null || cuenta.isEmpty()) return;
        // Lo que dice el servidor (si hay conexión)
        String[] rp = firmadoB26(cuenta, "perfil", "{}");
        String[] ro = firmadoB26(cuenta, "ordenadores", "{}");
        if (!cuentasB26().contains(cuenta)) return;   // el servidor dijo que ya no está conectado (ya se ha avisado)
        boolean conexion = "200".equals(rp[0]);
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(padre), "Mi cuenta Bitácora 26", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel c = new JPanel();
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        c.setBorder(BorderFactory.createEmptyBorder(10, 14, 6, 14));
        java.util.function.Function<String, JLabel> titulo = t -> {
            JLabel l = new JLabel(t);
            l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
            l.setForeground(app.TEMA_AMBAR);
            l.setBorder(BorderFactory.createEmptyBorder(10, 0, 4, 0));
            return l;
        };
        java.util.function.Consumer<JComponent> poner = comp -> { comp.setAlignmentX(Component.LEFT_ALIGNMENT); c.add(comp); };
        if (!conexion) {
            JLabel sin = new JLabel("<html><font color='#ff7a6b'>Sin conexión con bitacora26.es: solo se ven los datos de este ordenador.</font></html>");
            poner.accept(sin);
        }
        // Mis indicativos
        poner.accept(titulo.apply("Mis indicativos"));
        JPanel inds = new JPanel(new GridLayout(0, 2, 10, 4));
        if (conexion) {
            Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(rp[1].substring(Math.max(0, rp[1].indexOf("\"indicativos\""))));
            while (m.find()) {
                JLabel a = new JLabel(app.campoJson(m.group(), "ind"));
                a.setFont(a.getFont().deriveFont(Font.BOLD));
                inds.add(a);
                inds.add(new JLabel("CB".equals(app.campoJson(m.group(), "tipo")) ? "Banda Ciudadana" : "Radioaficionado"));
            }
        } else {
            for (String id : cuentasB26()) {
                if (!LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + id + "_PID", "-").equals(LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + cuenta + "_PID", "+")) && !id.equals(cuenta)) continue;
                JLabel a = new JLabel(indicativoParaMostrar(id));
                a.setFont(a.getFont().deriveFont(Font.BOLD));
                inds.add(a);
                inds.add(new JLabel("CB".equals(LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + id + "_TIPO", "HAM")) ? "Banda Ciudadana" : "Radioaficionado"));
            }
        }
        poner.accept(inds);
        // Mis ordenadores
        if ("200".equals(ro[0])) {
            poner.accept(titulo.apply("Mis ordenadores"));
            JPanel ords = new JPanel(new GridBagLayout());
            GridBagConstraints g = new GridBagConstraints();
            g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(2, 0, 2, 12);
            int fila = 0;
            Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(ro[1].substring(Math.max(0, ro[1].indexOf('['))));
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM/yyyy");
            while (m.find()) {
                String o = m.group(), idOrd = app.campoJson(o, "id");
                boolean este = verdadJson(o, "este");
                String nombre = app.campoJson(o, "equipo").isBlank() ? "Ordenador sin nombre" : app.campoJson(o, "equipo");
                g.gridy = fila++;
                g.gridx = 0; g.weightx = 1;
                ords.add(new JLabel("<html>" + nombre + (este ? " <font color='#4cd07d'>(este)</font>" : "") + "</html>"), g);
                g.gridx = 1; g.weightx = 0;
                ords.add(new JLabel(fmt.format(new java.util.Date(app.numeroJson(o, "alta") * 1000))), g);
                g.gridx = 2;
                JButton bQ = new JButton("Quitar");
                bQ.addActionListener(e -> {
                    if (JOptionPane.showConfirmDialog(d, "¿Quitar «" + nombre + "» de tu cuenta?" + (este ? "\n(Es este ordenador: se desconectará.)" : ""),
                            "Mis ordenadores", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                    String[] r = firmadoB26(cuenta, "quitar_ordenador", "{\"id\":\"" + app.jsonTexto(idOrd) + "\"}");
                    if (!"200".equals(r[0])) { JOptionPane.showMessageDialog(d, "No se ha podido: " + errorJson(r), "Mis ordenadores", JOptionPane.WARNING_MESSAGE); return; }
                    if (este) { olvidarPersonaLocal(cuenta); d.dispose(); return; }
                    bQ.setEnabled(false);
                    bQ.setText("Quitado");
                });
                ords.add(bQ, g);
            }
            poner.accept(ords);
        }
        // Más
        poner.accept(titulo.apply("Más"));
        JButton bWeb = new JButton("Abrir mi cuenta en la web"), bDesv = new JButton("Desconectar este ordenador"), bBorrar = new JButton("Borrar mi cuenta…");
        bBorrar.setBackground(new Color(90, 31, 26));
        bBorrar.setForeground(new Color(255, 217, 212));
        bWeb.addActionListener(e -> {
            try { Desktop.getDesktop().browse(new URI("https://bitacora26.es/cuenta.html")); } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "Abre en tu navegador: https://bitacora26.es/cuenta.html", "Mi cuenta", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        bDesv.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(d, "¿Desconectar este ordenador de tu cuenta?\n\nTu cuenta, tus contactos y tus QSL siguen igual.\n"
                    + "Para volver a conectarlo: «Ya tengo cuenta».", "Desconectar este ordenador", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            String[] r = firmadoB26(cuenta, "quitar_ordenador", "{}");
            if (!"200".equals(r[0]) && !"403".equals(r[0])) { JOptionPane.showMessageDialog(d, "No se ha podido: " + errorJson(r), "Desconectar", JOptionPane.WARNING_MESSAGE); return; }
            olvidarPersonaLocal(cuenta);
            d.dispose();
        });
        bBorrar.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(d, "¿BORRAR tu cuenta Bitácora 26?\n\nSe borran para siempre: la cuenta, tus indicativos, tus ordenadores,\n"
                    + "los contactos subidos para confirmar y tus QSL pendientes.\nTu log NO se toca.", "Borrar mi cuenta", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            JPasswordField pfB = new JPasswordField(16);
            if (JOptionPane.showConfirmDialog(d, new Object[]{"Para confirmarlo, escribe tu contraseña:", pfB}, "Borrar mi cuenta",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            String[] r = firmadoB26(cuenta, "borrar_cuenta", "{\"clave\":\"" + app.jsonTexto(new String(pfB.getPassword())) + "\"}");
            if (!"200".equals(r[0])) { JOptionPane.showMessageDialog(d, "No se ha podido: " + errorJson(r), "Borrar mi cuenta", JOptionPane.WARNING_MESSAGE); return; }
            olvidarPersonaLocal(cuenta);
            JOptionPane.showMessageDialog(d, "Tu cuenta se ha borrado.", "Borrar mi cuenta", JOptionPane.INFORMATION_MESSAGE);
            d.dispose();
        });
        JPanel fWeb = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 3)); fWeb.add(bWeb); poner.accept(fWeb);
        JPanel fDesv = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 3)); fDesv.add(bDesv); poner.accept(fDesv);
        JPanel fBorrar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 3));
        fBorrar.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        fBorrar.add(bBorrar);
        poner.accept(fBorrar);
        JLabel nb = new JLabel("<html><font size='2' color='#8796ad'>Borra la cuenta y lo subido al servidor. Tu log no se toca.</font></html>");
        poner.accept(nb);
        JButton bCerrar = new JButton("Cerrar");
        bCerrar.addActionListener(e -> d.dispose());
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.CENTER));
        abajo.add(bCerrar);
        d.setLayout(new BorderLayout());
        d.add(c, BorderLayout.CENTER);
        d.add(abajo, BorderLayout.SOUTH);
        d.pack();
        d.setSize(Math.max(d.getWidth(), 430), d.getHeight());
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
    }

    /** Quita de este ordenador todos los indicativos de la misma cuenta (persona). */
    private void olvidarPersonaLocal(String cuenta) {
        String grupo = LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + cuenta + "_PID", "+");
        for (String otra : cuentasB26()) if (!otra.equals(cuenta) && LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + otra + "_PID", "-").equals(grupo)) olvidarCuentaLocal(otra);
        olvidarCuentaLocal(cuenta);
    }

    /** Quita la cuenta de este ordenador (no toca el log). */
    private void olvidarCuentaLocal(String c) {
        List<String> l = cuentasB26();
        l.remove(c);
        guardarCuentasB26(l);
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE qsos SET b26_sub = 0, b26_conf = '', b26_qsl = 0, b26_cuenta = '' WHERE b26_cuenta = ?");
             PreparedStatement ps2 = conn.prepareStatement("DELETE FROM b26_borrados WHERE cuenta = ?")) {
            ps.setString(1, c); ps.executeUpdate();
            ps2.setString(1, c); ps2.executeUpdate();
        } catch (Exception ignored) { }
        app.cargarQSOsDesdeBaseDatos();
    }


    // ---------------------------------------------------------------- QSL: diseño libre (como el generador de diplomas)
    /** Un elemento de la QSL: un texto (con datos del contacto entre llaves) o una imagen. Medidas en píxeles de la QSL. */
    static class ElementoQsl {
        String tipo = "TEXTO";          // TEXTO o IMAGEN
        String texto = "";              // p. ej. "QSO con {INDICATIVO}" (puede tener varias líneas)
        String imagen = "";             // fichero en la carpeta del diseño
        int x = 20, y = 20, w = 160, h = 30;
        String fuente = "Arial";
        int estilo = Font.BOLD, tam = 14, color = 0xFFFFFF;
        int alinear = 0;                // 0 izquierda, 1 centro, 2 derecha
        boolean sombra = true;
        int fondo = 0x000000, fondoOpacidad = 0, radio = 0;   // opacidad 0 = sin fondo, 100 = fondo opaco
        int giro = 0;                   // grados
        int opacidad = 100;             // de todo el elemento
        boolean proporcion = true;      // imágenes: al cambiar el tamaño se mantiene la proporción

        ElementoQsl copia() {
            ElementoQsl e = new ElementoQsl();
            e.tipo = tipo; e.texto = texto; e.imagen = imagen; e.x = x + 8; e.y = y + 8; e.w = w; e.h = h; e.fuente = fuente; e.estilo = estilo;
            e.tam = tam; e.color = color; e.alinear = alinear; e.sombra = sombra; e.fondo = fondo; e.fondoOpacidad = fondoOpacidad; e.radio = radio;
            e.giro = giro; e.opacidad = opacidad; e.proporcion = proporcion;
            return e;
        }
    }

    /** El diseño completo de la QSL de un indicativo. */
    static class DisenoQsl {
        boolean vertical = false;
        int fondoColor = new Color(20, 60, 110).getRGB() & 0xFFFFFF;
        String fondoImagen = "";
        List<ElementoQsl> elementos = new ArrayList<>();

        int ancho() { return vertical ? QSL_ALTO : QSL_ANCHO; }
        int alto() { return vertical ? QSL_ANCHO : QSL_ALTO; }

        String guardar() {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("vertical", String.valueOf(vertical));
            p.setProperty("fondoColor", String.valueOf(fondoColor));
            p.setProperty("fondoImagen", fondoImagen);
            p.setProperty("n", String.valueOf(elementos.size()));
            for (int i = 0; i < elementos.size(); i++) {
                ElementoQsl e = elementos.get(i);
                String k = "e" + i + ".";
                p.setProperty(k + "tipo", e.tipo); p.setProperty(k + "texto", e.texto); p.setProperty(k + "imagen", e.imagen);
                p.setProperty(k + "x", "" + e.x); p.setProperty(k + "y", "" + e.y); p.setProperty(k + "w", "" + e.w); p.setProperty(k + "h", "" + e.h);
                p.setProperty(k + "fuente", e.fuente); p.setProperty(k + "estilo", "" + e.estilo); p.setProperty(k + "tam", "" + e.tam);
                p.setProperty(k + "color", "" + e.color); p.setProperty(k + "alinear", "" + e.alinear); p.setProperty(k + "sombra", "" + e.sombra);
                p.setProperty(k + "fondo", "" + e.fondo); p.setProperty(k + "fondoOpacidad", "" + e.fondoOpacidad); p.setProperty(k + "radio", "" + e.radio);
                p.setProperty(k + "giro", "" + e.giro); p.setProperty(k + "opacidad", "" + e.opacidad); p.setProperty(k + "proporcion", "" + e.proporcion);
            }
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                p.store(sw, null);
                return java.util.Base64.getEncoder().encodeToString(sw.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return "";
            }
        }

        static DisenoQsl cargar(String b64) {
            DisenoQsl d = new DisenoQsl();
            try {
                java.util.Properties p = new java.util.Properties();
                p.load(new java.io.StringReader(new String(java.util.Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)));
                d.vertical = Boolean.parseBoolean(p.getProperty("vertical", "false"));
                d.fondoColor = Integer.parseInt(p.getProperty("fondoColor", "" + d.fondoColor));
                d.fondoImagen = p.getProperty("fondoImagen", "");
                int n = Integer.parseInt(p.getProperty("n", "0"));
                for (int i = 0; i < n; i++) {
                    String k = "e" + i + ".";
                    ElementoQsl e = new ElementoQsl();
                    e.tipo = p.getProperty(k + "tipo", "TEXTO"); e.texto = p.getProperty(k + "texto", ""); e.imagen = p.getProperty(k + "imagen", "");
                    e.x = Integer.parseInt(p.getProperty(k + "x", "20")); e.y = Integer.parseInt(p.getProperty(k + "y", "20"));
                    e.w = Integer.parseInt(p.getProperty(k + "w", "160")); e.h = Integer.parseInt(p.getProperty(k + "h", "30"));
                    e.fuente = p.getProperty(k + "fuente", "Arial"); e.estilo = Integer.parseInt(p.getProperty(k + "estilo", "1"));
                    e.tam = Integer.parseInt(p.getProperty(k + "tam", "14")); e.color = Integer.parseInt(p.getProperty(k + "color", "16777215"));
                    e.alinear = Integer.parseInt(p.getProperty(k + "alinear", "0")); e.sombra = Boolean.parseBoolean(p.getProperty(k + "sombra", "true"));
                    e.fondo = Integer.parseInt(p.getProperty(k + "fondo", "0")); e.fondoOpacidad = Integer.parseInt(p.getProperty(k + "fondoOpacidad", "0"));
                    e.radio = Integer.parseInt(p.getProperty(k + "radio", "0")); e.giro = Integer.parseInt(p.getProperty(k + "giro", "0"));
                    e.opacidad = Integer.parseInt(p.getProperty(k + "opacidad", "100")); e.proporcion = Boolean.parseBoolean(p.getProperty(k + "proporcion", "true"));
                    d.elementos.add(e);
                }
            } catch (Exception ignored) { }
            return d;
        }

        /** Diseño de partida (el operador lo cambia todo a su gusto). */
        static DisenoQsl porDefecto(boolean cb) {
            DisenoQsl d = new DisenoQsl();
            ElementoQsl e = new ElementoQsl();
            e.texto = "{MI_INDICATIVO}"; e.x = 12; e.y = 8; e.w = 300; e.h = 50; e.tam = 40;
            d.elementos.add(e);
            e = new ElementoQsl();
            e.texto = "{MI_NOMBRE}\n{MI_QTH} · Locator {MI_LOCATOR}"; e.x = 13; e.y = 56; e.w = 300; e.h = 34; e.tam = 12;
            d.elementos.add(e);
            if (cb) {
                e = new ElementoQsl();
                e.texto = "{CB}"; e.x = 268; e.y = 10; e.w = 118; e.h = 18; e.tam = 10; e.color = 0x1E1E1E; e.sombra = false; e.alinear = 1;
                e.fondo = 0xFFBE3C; e.fondoOpacidad = 100; e.radio = 9;
                d.elementos.add(e);
            }
            e = new ElementoQsl();   // recuadro (un texto vacío con fondo), a todo lo ancho
            e.texto = ""; e.x = 10; e.y = 140; e.w = 377; e.h = 105; e.fondo = 0; e.fondoOpacidad = 65; e.radio = 12; e.sombra = false;
            d.elementos.add(e);
            e = new ElementoQsl();
            e.texto = "QSO con {INDICATIVO}"; e.x = 22; e.y = 146; e.w = 353; e.h = 22; e.tam = 15; e.sombra = false;
            d.elementos.add(e);
            // Datos del contacto en columnas: el título pequeño arriba y el dato debajo (cada uno se puede mover por separado)
            String[][] col = {{"FECHA", "{FECHA}"}, {"HORA UTC", "{HORA}"}, {"BANDA", "{BANDA}"}, {"MODO", "{MODO}"},
                    {"FRECUENCIA", "{FRECUENCIA}"}, {"RST", "{RST}"}};
            int[][] sitio = {{22, 70}, {92, 54}, {146, 50}, {196, 46}, {242, 90}, {332, 43}};   // x y ancho de cada columna
            for (int k = 0; k < col.length; k++) {
                e = new ElementoQsl();
                e.texto = col[k][0]; e.x = sitio[k][0]; e.y = 172; e.w = sitio[k][1]; e.h = 11; e.tam = 7; e.estilo = Font.PLAIN;
                e.color = 0xBFD4EE; e.alinear = 1; e.sombra = false;
                d.elementos.add(e);
                e = new ElementoQsl();
                e.texto = col[k][1]; e.x = sitio[k][0]; e.y = 183; e.w = sitio[k][1]; e.h = 16; e.tam = 11; e.alinear = 1; e.sombra = false;
                d.elementos.add(e);
            }
            e = new ElementoQsl();
            e.texto = "73 y gracias por el contacto"; e.x = 22; e.y = 206; e.w = 353; e.h = 16; e.tam = 11; e.estilo = Font.ITALIC; e.sombra = false;
            d.elementos.add(e);
            e = new ElementoQsl();
            e.texto = "{CONFIRMADO}"; e.x = 22; e.y = 226; e.w = 353; e.h = 14; e.tam = 9; e.estilo = Font.PLAIN; e.color = 0xFFD278; e.sombra = false;
            d.elementos.add(e);
            return d;
        }
    }

    /** Datos que se pueden poner en la QSL (entre llaves). */
    static final String[][] DATOS_QSL = {
            {"{MI_INDICATIVO}", "Mi indicativo"}, {"{MI_NOMBRE}", "Mi nombre"}, {"{MI_QTH}", "Mi QTH"}, {"{MI_LOCATOR}", "Mi locator"},
            {"{INDICATIVO}", "Indicativo del otro"}, {"{FECHA}", "Fecha del contacto"}, {"{HORA}", "Hora (UTC)"}, {"{BANDA}", "Banda"},
            {"{MODO}", "Modo"}, {"{RST}", "RST enviado"}, {"{FRECUENCIA}", "Frecuencia (MHz)"},
            {"{CB}", "\"BANDA CIUDADANA\" (solo en CB)"}, {"{CONFIRMADO}", "\"Confirmado en Bitácora 26\" (solo si lo está)"}};

    static File carpetaDisenoQsl(String cuenta) {
        File f = new File(System.getProperty("user.home"), "Documents" + File.separator + LibroGuardiaUI.NOMBRE_APP + File.separator + "QSL_diseno"
                + File.separator + cuenta.replaceAll("[^A-Z0-9]", "_"));
        f.mkdirs();
        return f;
    }

    private DisenoQsl disenoQsl(String cuenta) {
        String b = LibroGuardiaUI.ConfigApp.get("QSL_" + cuenta + "_DISENO", "");
        return b.isEmpty() ? DisenoQsl.porDefecto("CB".equals(LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + cuenta + "_TIPO", "HAM"))) : DisenoQsl.cargar(b);
    }

    /** c = {indicativo del otro, fecha, hora (UTC), banda, modo, RST enviado, frecuencia, confirmado (1/0)}. */
    private Map<String, String> valoresQsl(String cuenta, String[] c) {
        String[] yo = datosPropiosQsl(cuenta);
        Map<String, String> v = new HashMap<>();
        boolean cb = "CB".equalsIgnoreCase(c[3] == null ? "" : c[3].trim());
        v.put("{MI_INDICATIVO}", indicativoParaMostrar(cuenta)); v.put("{MI_NOMBRE}", yo[0]); v.put("{MI_QTH}", yo[1]); v.put("{MI_LOCATOR}", yo[2]);
        v.put("{INDICATIVO}", c[0] == null ? "" : c[0].toUpperCase()); v.put("{FECHA}", c[1]); v.put("{HORA}", c[2]);
        v.put("{BANDA}", cb ? "CB" : c[3]); v.put("{MODO}", c[4]); v.put("{RST}", c[5]);
        v.put("{FRECUENCIA}", c.length > 6 ? app.frecuenciaParaMostrar(c[6]) : "");
        v.put("{CB}", cb ? "BANDA CIUDADANA" : "");
        v.put("{CONFIRMADO}", c.length > 7 && "1".equals(c[7]) ? "Confirmado en Bitácora 26 · bitacora26.es" : "");
        return v;
    }

    static String resolverTextoQsl(String plantilla, Map<String, String> v) {
        String t = plantilla == null ? "" : plantilla;
        for (Map.Entry<String, String> e : v.entrySet()) t = t.replace(e.getKey(), e.getValue() == null ? "" : e.getValue());
        // Si una línea solo tenía datos vacíos, sobran los separadores que quedan sueltos
        StringBuilder r = new StringBuilder();
        for (String l : t.split("\n", -1)) {
            String limpia = l.replaceAll("^[\\s·,\\-|]+|[\\s·,\\-|]+$", "");
            if (r.length() > 0) r.append('\n');
            r.append(limpia.isEmpty() && !l.isBlank() ? "" : l.replaceAll("\\s*·\\s*$", "").replaceAll("^\\s*·\\s*", ""));
        }
        return r.toString();
    }

    /** Pinta la QSL. escala = píxeles de pantalla por píxel de QSL. Las imágenes se buscan en carpeta (con una caché opcional). */
    static void pintarQsl(Graphics2D g, DisenoQsl d, Map<String, String> v, File carpeta, double escala, Map<String, BufferedImage> cache) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        java.awt.geom.AffineTransform base = g.getTransform();
        g.scale(escala, escala);
        g.setColor(new Color(d.fondoColor));
        g.fillRect(0, 0, d.ancho(), d.alto());
        BufferedImage fondo = imagenQsl(carpeta, d.fondoImagen, cache);
        if (fondo != null) g.drawImage(fondo, 0, 0, d.ancho(), d.alto(), null);
        for (ElementoQsl e : d.elementos) {
            java.awt.geom.AffineTransform antes = g.getTransform();
            Composite compAntes = g.getComposite();
            if (e.opacidad < 100) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, e.opacidad) / 100f));
            if (e.giro != 0) g.rotate(Math.toRadians(e.giro), e.x + e.w / 2.0, e.y + e.h / 2.0);
            if ("IMAGEN".equals(e.tipo)) {
                BufferedImage im = imagenQsl(carpeta, e.imagen, cache);
                if (im != null) g.drawImage(im, e.x, e.y, e.w, e.h, null);
            } else {
                String texto = resolverTextoQsl(e.texto, v);
                boolean soloDatosVacios = !e.texto.isBlank() && texto.isBlank();   // p. ej. {CB} fuera de CB: no se pinta nada
                if (!soloDatosVacios) {
                    if (e.fondoOpacidad > 0) {
                        Color f = new Color(e.fondo);
                        g.setColor(new Color(f.getRed(), f.getGreen(), f.getBlue(), (int) Math.round(255 * Math.min(100, e.fondoOpacidad) / 100.0)));
                        g.fill(new java.awt.geom.RoundRectangle2D.Float(e.x, e.y, e.w, e.h, e.radio * 2f, e.radio * 2f));
                    }
                    if (!texto.isEmpty()) {
                        g.setFont(new Font(e.fuente, e.estilo, Math.max(1, e.tam)));
                        FontMetrics fm = g.getFontMetrics();
                        int yy = e.y + fm.getAscent() + 1;
                        for (String l : texto.split("\n", -1)) {
                            int ancho = fm.stringWidth(l);
                            int xx = e.alinear == 1 ? e.x + (e.w - ancho) / 2 : e.alinear == 2 ? e.x + e.w - ancho - 2 : e.x + 2;
                            if (e.sombra) {
                                g.setColor(new Color(0, 0, 0, 150));
                                g.drawString(l, xx + Math.max(1, e.tam / 20f), yy + Math.max(1, e.tam / 20f));
                            }
                            g.setColor(new Color(e.color));
                            g.drawString(l, xx, yy);
                            yy += fm.getHeight();
                        }
                    }
                }
            }
            g.setComposite(compAntes);
            g.setTransform(antes);
        }
        g.setTransform(base);
    }

    static BufferedImage imagenQsl(File carpeta, String nombre, Map<String, BufferedImage> cache) {
        if (nombre == null || nombre.isEmpty()) return null;
        if (cache != null && cache.containsKey(nombre)) return cache.get(nombre);
        BufferedImage im = null;
        try { im = ImageIO.read(new File(carpeta, nombre)); } catch (Exception ignored) { }
        if (cache != null) cache.put(nombre, im);
        return im;
    }

    /** La QSL de un contacto, ya dibujada a su tamaño real (14x9 cm a 150 ppp). */
    BufferedImage dibujarQsl(String cuenta, String[] c) {
        DisenoQsl d = disenoQsl(cuenta);
        BufferedImage img = new BufferedImage((int) Math.round(d.ancho() * QSL_ESCALA), (int) Math.round(d.alto() * QSL_ESCALA), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        pintarQsl(g, d, valoresQsl(cuenta, c), carpetaDisenoQsl(cuenta), QSL_ESCALA, null);
        g.dispose();
        return img;
    }

    /** Guarda una imagen elegida por el operador en la carpeta del diseño, reducida (nunca más del doble de la QSL). */
    private static String guardarImagenDisenoQsl(String cuenta, BufferedImage o, String prefijo) throws Exception {
        int max = QSL_ANCHO * 2;
        double e = Math.min(1.0, (double) max / Math.max(o.getWidth(), o.getHeight()));
        int w = Math.max(1, (int) Math.round(o.getWidth() * e)), h = Math.max(1, (int) Math.round(o.getHeight() * e));
        BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = r.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(o, 0, 0, w, h, null);
        g.dispose();
        String nombre = prefijo + "_" + Long.toString(System.currentTimeMillis(), 36) + ".png";
        ImageIO.write(r, "png", new File(carpetaDisenoQsl(cuenta), nombre));
        return nombre;
    }

    /** Ventana "Diseñar mi QSL": editor libre, como el generador de diplomas. */
    private void abrirDisenoQsl() {
        List<String> cuentas = cuentasB26();
        if (cuentas.isEmpty()) {
            avisoSinCuentaB26();
            return;
        }
        new EditorQsl(cuentas).setVisible(true);
    }

    private class EditorQsl extends JDialog {
        private final JComboBox<String> cbCuenta;
        private String cuenta;
        private DisenoQsl d;
        private ElementoQsl sel;
        private final Map<String, BufferedImage> cache = new HashMap<>();
        private double escala = 2.0;
        private boolean cargando = false;
        private boolean cambiado = false;
        private final JPanel lienzo;
        private final JLabel lblTam = new JLabel(" ");
        // Propiedades
        private final JTextArea txtTexto = new JTextArea(3, 22);
        private final JComboBox<String> cbDato = new JComboBox<>();
        private final JComboBox<String> cbFuente = new JComboBox<>(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        private final JComboBox<String> cbEstilo = new JComboBox<>(new String[]{"Normal", "Negrita", "Cursiva", "Negrita y cursiva"});
        private final JSpinner spTam = new JSpinner(new SpinnerNumberModel(14, 4, 160, 1));
        private final JComboBox<String> cbAlinear = new JComboBox<>(new String[]{"Izquierda", "Centro", "Derecha"});
        private final JCheckBox chkSombra = new JCheckBox("Sombra");
        private final JButton bColor = new JButton("Color del texto…"), bColorFondoEl = new JButton("Color del fondo…");
        private final JSpinner spFondoOp = new JSpinner(new SpinnerNumberModel(0, 0, 100, 5)), spRadio = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
        private final JSpinner spGiro = new JSpinner(new SpinnerNumberModel(0, -180, 180, 1)), spOpacidad = new JSpinner(new SpinnerNumberModel(100, 5, 100, 5));
        private final JSpinner spX = new JSpinner(new SpinnerNumberModel(0, -500, 1000, 1)), spY = new JSpinner(new SpinnerNumberModel(0, -500, 1000, 1));
        private final JSpinner spW = new JSpinner(new SpinnerNumberModel(10, 4, 1000, 1)), spH = new JSpinner(new SpinnerNumberModel(10, 4, 1000, 1));
        private final JCheckBox chkProporcion = new JCheckBox("Mantener la proporción");
        private final JPanel panelTexto = new JPanel(new GridBagLayout()), panelComun = new JPanel(new GridBagLayout());
        private final JLabel lblSel = new JLabel("Pincha en un elemento de la QSL para cambiarlo");
        private final JCheckBox chkAuto = new JCheckBox("Enviar mi QSL automáticamente cuando se confirme un contacto");
        private final JComboBox<String> cbOrientacion = new JComboBox<>(new String[]{"Horizontal (14 × 9 cm)", "Vertical (9 × 14 cm)"});
        // Arrastrar
        private int modoArrastre = 0;   // 0 nada, 1 mover, 2 cambiar tamaño
        private Point inicioRaton;
        private Rectangle inicioCaja;

        EditorQsl(List<String> cuentas) {
            super(app, "Diseñar mi QSL", true);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) { cerrar(); }
            });
            cbCuenta = new JComboBox<>(cuentas.toArray(new String[0]));
            cbCuenta.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    return super.getListCellRendererComponent(l, v == null ? "" : indicativoParaMostrar(v.toString()), i, s, f);
                }
            });
            for (String[] dato : DATOS_QSL) cbDato.addItem(dato[1] + "  " + dato[0]);
            lienzo = new JPanel() {
                @Override protected void paintComponent(Graphics g0) {
                    super.paintComponent(g0);
                    Graphics2D g = (Graphics2D) g0.create();
                    pintarQsl(g, d, valoresEjemplo(), carpetaDisenoQsl(cuenta), escala, cache);
                    // Marco del elemento elegido y su tirador para cambiar el tamaño (no salen en la QSL)
                    for (ElementoQsl e : d.elementos) {
                        boolean elegido = e == sel;
                        if (!elegido && !"TEXTO".equals(e.tipo)) continue;
                        Rectangle r = new Rectangle((int) Math.round(e.x * escala), (int) Math.round(e.y * escala), (int) Math.round(e.w * escala), (int) Math.round(e.h * escala));
                        Graphics2D g2 = (Graphics2D) g.create();
                        if (e.giro != 0) g2.rotate(Math.toRadians(e.giro), r.getCenterX(), r.getCenterY());
                        if (elegido) {
                            g2.setColor(new Color(255, 190, 60));
                            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{5f, 4f}, 0f));
                            g2.drawRect(r.x, r.y, r.width, r.height);
                            g2.setStroke(new BasicStroke(1f));
                            g2.fillRect(r.x + r.width - 5, r.y + r.height - 5, 10, 10);
                        } else {
                            g2.setColor(new Color(255, 255, 255, 50));
                            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{2f, 3f}, 0f));
                            g2.drawRect(r.x, r.y, r.width, r.height);
                        }
                        g2.dispose();
                    }
                    g.dispose();
                }
                @Override public Dimension getPreferredSize() {
                    return new Dimension((int) Math.round(d.ancho() * escala), (int) Math.round(d.alto() * escala));
                }
            };
            lienzo.setFocusable(true);
            configurarRaton();
            configurarTeclado();

            // ---- Barra de arriba
            JButton bFondo = new JButton("Imagen de fondo…"), bQuitarFondo = new JButton("Quitar fondo"), bColorFondo = new JButton("Color de fondo…");
            JButton bPorDefecto = new JButton("Empezar de nuevo");
            JComboBox<String> cbZoom = new JComboBox<>(new String[]{"Zoom 100%", "Zoom 150%", "Zoom 200%", "Zoom 300%"});
            cbZoom.setSelectedIndex(2);
            JPanel arriba1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
            arriba1.add(new JLabel("Indicativo:")); arriba1.add(cbCuenta);
            arriba1.add(cbOrientacion);
            arriba1.add(bFondo); arriba1.add(bQuitarFondo); arriba1.add(bColorFondo);
            arriba1.add(bPorDefecto); arriba1.add(cbZoom);
            JButton bAddTexto = new JButton("+ Texto"), bAddDato = new JButton("+ Dato del contacto"), bAddImagen = new JButton("+ Imagen o logo…");
            JButton bDuplicar = new JButton("Duplicar"), bBorrar = new JButton("Borrar"), bFrente = new JButton("Traer al frente"), bFondoCapa = new JButton("Enviar al fondo");
            JComboBox<String> cbAddDato = new JComboBox<>();
            for (String[] dato : DATOS_QSL) cbAddDato.addItem(dato[1]);
            JPanel arriba2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
            arriba2.add(bAddTexto); arriba2.add(cbAddDato); arriba2.add(bAddDato); arriba2.add(bAddImagen);
            arriba2.add(new JLabel("   ")); arriba2.add(bDuplicar); arriba2.add(bBorrar); arriba2.add(bFrente); arriba2.add(bFondoCapa);
            JPanel arriba = new JPanel(new GridLayout(2, 1));
            arriba.add(arriba1); arriba.add(arriba2);

            // ---- Propiedades (derecha)
            txtTexto.setLineWrap(false);
            JButton bInsertar = new JButton("Insertar");
            cbFuente.setPrototypeDisplayValue("Arial Rounded MT Bold XXX");
            cbDato.setPrototypeDisplayValue("\"Confirmado en Bitácora 26\" XX");
            JScrollPane spTexto = new JScrollPane(txtTexto);
            spTexto.setPreferredSize(new Dimension(310, 70));
            spTexto.setMaximumSize(new Dimension(310, 70));
            panelTexto.setLayout(new BoxLayout(panelTexto, BoxLayout.Y_AXIS));
            panelComun.setLayout(new BoxLayout(panelComun, BoxLayout.Y_AXIS));
            for (JComponent c : new JComponent[]{
                    fila(new JLabel("Texto (Intro = otra línea; los datos van entre llaves):")), spTexto,
                    fila(cbDato, bInsertar),
                    fila(new JLabel("Letra:"), cbFuente),
                    fila(cbEstilo, new JLabel("Tamaño:"), spTam),
                    fila(cbAlinear, chkSombra),
                    fila(bColor),
                    fila(bColorFondoEl, new JLabel("Opacidad del fondo %:"), spFondoOp),
                    fila(new JLabel("Esquinas redondeadas:"), spRadio)}) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                panelTexto.add(c);
            }
            for (JComponent c : new JComponent[]{
                    fila(new JLabel("X:"), spX, new JLabel("  Y:"), spY),
                    fila(new JLabel("Ancho:"), spW, new JLabel("  Alto:"), spH),
                    fila(new JLabel("Giro (grados):"), spGiro, new JLabel("  Opacidad %:"), spOpacidad),
                    fila(chkProporcion)}) {
                c.setAlignmentX(Component.LEFT_ALIGNMENT);
                panelComun.add(c);
            }
            JPanel derecha = new JPanel();
            derecha.setLayout(new BoxLayout(derecha, BoxLayout.Y_AXIS));
            lblSel.setFont(lblSel.getFont().deriveFont(Font.BOLD));
            for (JComponent c : new JComponent[]{lblSel, panelTexto, panelComun}) { c.setAlignmentX(Component.LEFT_ALIGNMENT); derecha.add(c); }
            JLabel ayuda = new JLabel("<html><div style='width:270px'><br>Arrastra para mover; el cuadrito naranja de la esquina cambia el tamaño. "
                    + "Flechas: mover 1 píxel (con Mayúsculas, 10). Supr: borrar el elemento elegido.<br><br>"
                    + "Datos que puedes poner en cualquier texto: {MI_INDICATIVO} {MI_NOMBRE} {MI_QTH} {MI_LOCATOR} {INDICATIVO} {FECHA} {HORA} "
                    + "{BANDA} {MODO} {RST} {FRECUENCIA} {CB} {CONFIRMADO}</div></html>");
            ayuda.setFont(ayuda.getFont().deriveFont(11f));
            ayuda.setAlignmentX(Component.LEFT_ALIGNMENT);
            derecha.add(ayuda);
            derecha.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

            // ---- Abajo
            JButton bGuardar = new JButton("Guardar"), bCancelar = new JButton("Cerrar sin guardar");
            JPanel abajo = new JPanel(new BorderLayout());
            JPanel izq = new JPanel(new FlowLayout(FlowLayout.LEFT));
            izq.add(chkAuto);
            izq.add(lblTam);
            JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            der.add(bGuardar); der.add(bCancelar);
            abajo.add(izq, BorderLayout.CENTER); abajo.add(der, BorderLayout.EAST);

            JPanel centroLienzo = new JPanel(new GridBagLayout());
            centroLienzo.setBackground(new Color(60, 60, 60));
            centroLienzo.add(lienzo);
            JScrollPane spDerecha = new JScrollPane(derecha);
            spDerecha.setPreferredSize(new Dimension(380, 500));
            spDerecha.setMinimumSize(new Dimension(380, 200));
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(centroLienzo), spDerecha);
            split.setResizeWeight(1.0);
            setLayout(new BorderLayout(4, 4));
            add(arriba, BorderLayout.NORTH);
            add(split, BorderLayout.CENTER);
            add(abajo, BorderLayout.SOUTH);

            // ---- Acciones
            cbCuenta.addActionListener(e -> { if (!cargando && preguntarSiGuardar()) cargarCuenta(); });
            cbOrientacion.addActionListener(e -> {
                if (cargando) return;
                boolean v = cbOrientacion.getSelectedIndex() == 1;
                if (v != d.vertical) { d.vertical = v; cambio(); lienzo.revalidate(); }
            });
            cbZoom.addActionListener(e -> {
                escala = new double[]{1.0, 1.5, 2.0, 3.0}[cbZoom.getSelectedIndex()];
                lienzo.revalidate(); lienzo.repaint();
            });
            bFondo.addActionListener(e -> {
                File f = elegirImagen("Imagen de fondo de la QSL");
                if (f == null) return;
                try {
                    BufferedImage o = ImageIO.read(f);
                    if (o == null) throw new Exception("no es una imagen");
                    // Se recorta a la forma de la QSL
                    double objetivo = (double) d.ancho() / d.alto();
                    int w = o.getWidth(), h = o.getHeight(), cw = w, ch = h;
                    if ((double) w / h > objetivo) cw = (int) Math.round(h * objetivo); else ch = (int) Math.round(w / objetivo);
                    BufferedImage r = o.getSubimage((w - cw) / 2, (h - ch) / 2, cw, ch);
                    d.fondoImagen = guardarImagenDisenoQsl(cuenta, r, "fondo");
                    cambio();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "No se ha podido usar esa imagen: " + ex.getMessage(), "Imagen de fondo", JOptionPane.WARNING_MESSAGE);
                }
            });
            bQuitarFondo.addActionListener(e -> { d.fondoImagen = ""; cambio(); });
            bColorFondo.addActionListener(e -> {
                Color c = app.elegirColor(this, "Color de fondo", new Color(d.fondoColor));
                if (c != null) { d.fondoColor = c.getRGB() & 0xFFFFFF; cambio(); }
            });
            bPorDefecto.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(this, "¿Empezar de nuevo con el diseño de partida? Se pierde lo que has hecho en esta QSL.",
                        "Diseñar mi QSL", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                boolean v = d.vertical;
                d = DisenoQsl.porDefecto("CB".equals(LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + cuenta + "_TIPO", "HAM")));
                d.vertical = v;
                elegir(null);
                cambio();
            });
            bAddTexto.addActionListener(e -> {
                ElementoQsl el = new ElementoQsl();
                el.texto = "Texto nuevo"; el.x = 30; el.y = 30;
                d.elementos.add(el); elegir(el); cambio();
                txtTexto.requestFocusInWindow(); txtTexto.selectAll();
            });
            bAddDato.addActionListener(e -> {
                ElementoQsl el = new ElementoQsl();
                el.texto = DATOS_QSL[cbAddDato.getSelectedIndex()][0]; el.x = 30; el.y = 30;
                d.elementos.add(el); elegir(el); cambio();
            });
            bAddImagen.addActionListener(e -> {
                File f = elegirImagen("Imagen o logo para la QSL");
                if (f == null) return;
                try {
                    BufferedImage o = ImageIO.read(f);
                    if (o == null) throw new Exception("no es una imagen");
                    ElementoQsl el = new ElementoQsl();
                    el.tipo = "IMAGEN";
                    el.imagen = guardarImagenDisenoQsl(cuenta, o, "img");
                    double esc = Math.min(1.0, Math.min(150.0 / o.getWidth(), 120.0 / o.getHeight()));
                    el.w = Math.max(8, (int) Math.round(o.getWidth() * esc)); el.h = Math.max(8, (int) Math.round(o.getHeight() * esc));
                    el.x = 20; el.y = 20; el.sombra = false;
                    d.elementos.add(el); elegir(el); cambio();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "No se ha podido usar esa imagen: " + ex.getMessage(), "Imagen", JOptionPane.WARNING_MESSAGE);
                }
            });
            bDuplicar.addActionListener(e -> { if (sel != null) { ElementoQsl c = sel.copia(); d.elementos.add(c); elegir(c); cambio(); } });
            bBorrar.addActionListener(e -> borrarElegido());
            bFrente.addActionListener(e -> { if (sel != null) { d.elementos.remove(sel); d.elementos.add(sel); cambio(); } });
            bFondoCapa.addActionListener(e -> { if (sel != null) { d.elementos.remove(sel); d.elementos.add(0, sel); cambio(); } });
            bInsertar.addActionListener(e -> {
                txtTexto.insert(DATOS_QSL[cbDato.getSelectedIndex()][0], txtTexto.getCaretPosition());
                txtTexto.requestFocusInWindow();
            });
            txtTexto.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { leerPropiedades(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { leerPropiedades(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { }
            });
            for (JComboBox<?> c : new JComboBox<?>[]{cbFuente, cbEstilo, cbAlinear}) c.addActionListener(e -> leerPropiedades());
            for (JSpinner s : new JSpinner[]{spTam, spFondoOp, spRadio, spGiro, spOpacidad, spX, spY, spW, spH}) s.addChangeListener(e -> leerPropiedades());
            chkSombra.addActionListener(e -> leerPropiedades());
            chkProporcion.addActionListener(e -> leerPropiedades());
            bColor.addActionListener(e -> {
                if (sel == null) return;
                Color c = app.elegirColor(this, "Color del texto", new Color(sel.color));
                if (c != null) { sel.color = c.getRGB() & 0xFFFFFF; cambio(); }
            });
            bColorFondoEl.addActionListener(e -> {
                if (sel == null) return;
                Color c = app.elegirColor(this, "Color del fondo", new Color(sel.fondo));
                if (c != null) { sel.fondo = c.getRGB() & 0xFFFFFF; if (sel.fondoOpacidad == 0) sel.fondoOpacidad = 100; elegir(sel); cambio(); }
            });
            chkAuto.addActionListener(e -> cambiado = true);
            bGuardar.addActionListener(e -> { guardar(); dispose(); });
            bCancelar.addActionListener(e -> cerrar());

            cargarCuenta();
            pack();
            Rectangle pantalla = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            setSize(Math.min(Math.max(getWidth(), 1250), pantalla.width - 20), Math.min(Math.max(getHeight(), 760), pantalla.height - 20));
            setLocationRelativeTo(app);
        }

        private JPanel fila(JComponent... cs) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            for (JComponent c : cs) p.add(c);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
            return p;
        }

        private Map<String, String> valoresEjemplo() {
            boolean cb = "CB".equals(LibroGuardiaUI.ConfigApp.get("B26_CUENTA_" + cuenta + "_TIPO", "HAM"));
            return valoresQsl(cuenta, new String[]{cb ? "30DEMO1" : "EA9ABC", LocalDate.now(ZoneOffset.UTC).format(app.FMT_FECHA_UI), "18:30",
                    cb ? "CB" : "20m", cb ? "AM" : "USB", "59", cb ? "27.185" : "14.205", "1"});
        }

        private File elegirImagen(String titulo) {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle(titulo);
            fc.setFileFilter(new FileNameExtensionFilter("Imágenes (JPG, PNG, GIF, BMP)", "jpg", "jpeg", "png", "gif", "bmp"));
            return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION ? fc.getSelectedFile() : null;
        }

        private void cargarCuenta() {
            cargando = true;
            cuenta = String.valueOf(cbCuenta.getSelectedItem());
            d = disenoQsl(cuenta);
            cache.clear();
            cbOrientacion.setSelectedIndex(d.vertical ? 1 : 0);
            chkAuto.setSelected("1".equals(LibroGuardiaUI.ConfigApp.get("QSL_" + cuenta + "_AUTO", "0")));
            cargando = false;
            cambiado = false;
            elegir(null);
            lienzo.revalidate();
            actualizarVista();
        }

        private void guardar() {
            LibroGuardiaUI.ConfigApp.put("QSL_" + cuenta + "_DISENO", d.guardar());
            LibroGuardiaUI.ConfigApp.put("QSL_" + cuenta + "_AUTO", chkAuto.isSelected() ? "1" : "0");
            cambiado = false;
            if (chkAuto.isSelected()) comprobarCuentasPronto();
        }

        private boolean preguntarSiGuardar() {
            if (!cambiado) return true;
            String antes = cuenta;
            int r = JOptionPane.showConfirmDialog(this, "¿Guardar los cambios de la QSL de " + antes + "?", "Diseñar mi QSL", JOptionPane.YES_NO_CANCEL_OPTION);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) {
                cargando = true; cbCuenta.setSelectedItem(antes); cargando = false;
                return false;
            }
            if (r == JOptionPane.YES_OPTION) guardar();
            return true;
        }

        private void cerrar() {
            if (cambiado) {
                int r = JOptionPane.showConfirmDialog(this, "¿Guardar los cambios de tu QSL?", "Diseñar mi QSL", JOptionPane.YES_NO_CANCEL_OPTION);
                if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
                if (r == JOptionPane.YES_OPTION) guardar();
            }
            dispose();
        }

        private void cambio() {
            cambiado = true;
            lienzo.repaint();
            actualizarVista();
        }

        /** Tamaño real del fichero que se mandará. */
        private void actualizarVista() {
            try {
                BufferedImage img = new BufferedImage((int) Math.round(d.ancho() * QSL_ESCALA), (int) Math.round(d.alto() * QSL_ESCALA), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                pintarQsl(g, d, valoresEjemplo(), carpetaDisenoQsl(cuenta), QSL_ESCALA, cache);
                g.dispose();
                byte[] jpg = qslAJpeg(img);
                lblTam.setText("    Tamaño real: " + (d.vertical ? "9 × 14" : "14 × 9") + " cm a " + QSL_PPP + " ppp (" + img.getWidth() + " × " + img.getHeight() + " píxeles) · "
                        + Math.max(1, jpg.length / 1024) + " KB al enviarla");
            } catch (Exception ex) {
                lblTam.setText("    " + ex.getMessage());
            }
        }

        private void elegir(ElementoQsl e) {
            sel = e;
            cargando = true;
            boolean hay = e != null, texto = hay && "TEXTO".equals(e.tipo);
            lblSel.setText(!hay ? "Pincha en un elemento de la QSL para cambiarlo" : texto ? "Texto elegido" : "Imagen elegida");
            panelTexto.setVisible(texto);
            panelComun.setVisible(hay);
            chkProporcion.setVisible(hay && !texto);
            if (hay) {
                if (texto) {
                    txtTexto.setText(e.texto);
                    cbFuente.setSelectedItem(e.fuente);
                    cbEstilo.setSelectedIndex(e.estilo == Font.BOLD ? 1 : e.estilo == Font.ITALIC ? 2 : e.estilo == (Font.BOLD | Font.ITALIC) ? 3 : 0);
                    spTam.setValue(e.tam);
                    cbAlinear.setSelectedIndex(e.alinear);
                    chkSombra.setSelected(e.sombra);
                    spFondoOp.setValue(e.fondoOpacidad);
                    spRadio.setValue(e.radio);
                }
                spX.setValue(e.x); spY.setValue(e.y); spW.setValue(e.w); spH.setValue(e.h);
                spGiro.setValue(e.giro); spOpacidad.setValue(e.opacidad);
                chkProporcion.setSelected(e.proporcion);
            }
            cargando = false;
            lienzo.repaint();
        }

        private void leerPropiedades() {
            if (cargando || sel == null) return;
            if ("TEXTO".equals(sel.tipo)) {
                sel.texto = txtTexto.getText();
                if (cbFuente.getSelectedItem() != null) sel.fuente = String.valueOf(cbFuente.getSelectedItem());
                sel.estilo = new int[]{Font.PLAIN, Font.BOLD, Font.ITALIC, Font.BOLD | Font.ITALIC}[cbEstilo.getSelectedIndex()];
                sel.tam = (Integer) spTam.getValue();
                sel.alinear = cbAlinear.getSelectedIndex();
                sel.sombra = chkSombra.isSelected();
                sel.fondoOpacidad = (Integer) spFondoOp.getValue();
                sel.radio = (Integer) spRadio.getValue();
            }
            int nw = (Integer) spW.getValue(), nh = (Integer) spH.getValue();
            sel.proporcion = chkProporcion.isSelected();
            if ("IMAGEN".equals(sel.tipo) && sel.proporcion && sel.w > 0 && sel.h > 0) {
                if (nw != sel.w) nh = Math.max(4, (int) Math.round(nw * (double) sel.h / sel.w));
                else if (nh != sel.h) nw = Math.max(4, (int) Math.round(nh * (double) sel.w / sel.h));
            }
            sel.x = (Integer) spX.getValue(); sel.y = (Integer) spY.getValue(); sel.w = nw; sel.h = nh;
            sel.giro = (Integer) spGiro.getValue(); sel.opacidad = (Integer) spOpacidad.getValue();
            if (nw != (Integer) spW.getValue() || nh != (Integer) spH.getValue()) {
                cargando = true; spW.setValue(nw); spH.setValue(nh); cargando = false;
            }
            cambio();
        }

        private void borrarElegido() {
            if (sel == null) return;
            d.elementos.remove(sel);
            elegir(null);
            cambio();
        }

        private ElementoQsl elementoEn(Point p) {
            double qx = p.x / escala, qy = p.y / escala;
            for (int i = d.elementos.size() - 1; i >= 0; i--) {
                ElementoQsl e = d.elementos.get(i);
                double[] q = {qx, qy};
                if (e.giro != 0) {   // se deshace el giro para saber si se ha pinchado dentro
                    java.awt.geom.AffineTransform t = java.awt.geom.AffineTransform.getRotateInstance(-Math.toRadians(e.giro), e.x + e.w / 2.0, e.y + e.h / 2.0);
                    t.transform(q, 0, q, 0, 1);
                }
                if (q[0] >= e.x - 2 && q[0] <= e.x + e.w + 2 && q[1] >= e.y - 2 && q[1] <= e.y + e.h + 2) return e;
            }
            return null;
        }

        private boolean enTirador(Point p) {
            if (sel == null) return false;
            double[] q = {p.x / escala, p.y / escala};
            if (sel.giro != 0) {
                java.awt.geom.AffineTransform t = java.awt.geom.AffineTransform.getRotateInstance(-Math.toRadians(sel.giro), sel.x + sel.w / 2.0, sel.y + sel.h / 2.0);
                t.transform(q, 0, q, 0, 1);
            }
            double margen = 7 / escala;
            return Math.abs(q[0] - (sel.x + sel.w)) <= margen && Math.abs(q[1] - (sel.y + sel.h)) <= margen;
        }

        private void configurarRaton() {
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    lienzo.requestFocusInWindow();
                    if (enTirador(e.getPoint())) {
                        modoArrastre = 2;
                    } else {
                        ElementoQsl el = elementoEn(e.getPoint());
                        if (el != sel) elegir(el);
                        modoArrastre = el == null ? 0 : 1;
                    }
                    if (sel != null) { inicioRaton = e.getPoint(); inicioCaja = new Rectangle(sel.x, sel.y, sel.w, sel.h); }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (sel == null || modoArrastre == 0) return;
                    int dx = (int) Math.round((e.getX() - inicioRaton.x) / escala), dy = (int) Math.round((e.getY() - inicioRaton.y) / escala);
                    if (modoArrastre == 1) {
                        sel.x = inicioCaja.x + dx; sel.y = inicioCaja.y + dy;
                    } else {
                        int nw = Math.max(4, inicioCaja.width + dx), nh = Math.max(4, inicioCaja.height + dy);
                        if ("IMAGEN".equals(sel.tipo) && sel.proporcion) nh = Math.max(4, (int) Math.round(nw * (double) inicioCaja.height / inicioCaja.width));
                        sel.w = nw; sel.h = nh;
                    }
                    cargando = true;
                    spX.setValue(sel.x); spY.setValue(sel.y); spW.setValue(sel.w); spH.setValue(sel.h);
                    cargando = false;
                    cambiado = true;
                    lienzo.repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (modoArrastre != 0) actualizarVista();
                    modoArrastre = 0;
                }
                @Override public void mouseMoved(MouseEvent e) {
                    lienzo.setCursor(Cursor.getPredefinedCursor(enTirador(e.getPoint()) ? Cursor.SE_RESIZE_CURSOR
                            : elementoEn(e.getPoint()) != null ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            };
            lienzo.addMouseListener(m);
            lienzo.addMouseMotionListener(m);
        }

        private void configurarTeclado() {
            lienzo.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (sel == null) return;
                    int paso = e.isShiftDown() ? 10 : 1;
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_LEFT -> sel.x -= paso;
                        case KeyEvent.VK_RIGHT -> sel.x += paso;
                        case KeyEvent.VK_UP -> sel.y -= paso;
                        case KeyEvent.VK_DOWN -> sel.y += paso;
                        case KeyEvent.VK_DELETE -> { borrarElegido(); return; }
                        default -> { return; }
                    }
                    e.consume();
                    elegir(sel);
                    cambio();
                }
            });
        }
    }

    /** Carpeta donde se guardan las QSL que llegan: Documentos\Libro de Guardia\QSL */
    static File carpetaQslRecibidas() {
        File f = new File(System.getProperty("user.home"), "Documents" + File.separator + LibroGuardiaUI.NOMBRE_APP + File.separator + "QSL");
        f.mkdirs();
        return f;
    }

    /** Descarga las QSL que han llegado a mis cuentas, las guarda en la carpeta QSL y las borra del servidor. Devuelve las nuevas. */
    private int descargarQslNuevas(Connection conn, List<String> cuentas) throws Exception {
        int nuevas = 0;
        for (String cuenta : cuentas) {
            String[] r = firmadoB26(cuenta, "buzon", "{}");
            if (!"200".equals(r[0])) continue;
            Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(listaJson(r[1], "qsl"));
            while (m.find()) {
                String o = m.group(), qid = app.campoJson(o, "qid"), de = app.campoJson(o, "de"), idMio = app.campoJson(o, "id");
                long minuto = app.numeroJson(o, "minuto");
                String banda = app.campoJson(o, "banda"), modo = app.campoJson(o, "modo");
                banda = banda.equalsIgnoreCase("CB") ? "CB" : banda.toLowerCase();
                String[] b = firmadoB26(cuenta, "qsl_bajar", "{\"qid\":\"" + app.jsonTexto(qid) + "\"}");
                if (!"200".equals(b[0])) continue;
                byte[] img;
                try {   // la imagen va entera en "img" (sin expresiones regulares: es un texto largo)
                    int i = b[1].indexOf("\"img\""), a = b[1].indexOf('"', b[1].indexOf(':', i) + 1), z = b[1].indexOf('"', a + 1);
                    img = java.util.Base64.getDecoder().decode(b[1].substring(a + 1, z));
                } catch (Exception e) {
                    continue;
                }
                java.time.LocalDateTime f = java.time.LocalDateTime.ofEpochSecond(minuto * 60, 0, ZoneOffset.UTC);
                String nombre = "QSL_" + de.replaceAll("[^A-Z0-9]", "_") + "_" + f.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + "_"
                        + banda.replaceAll("[^A-Za-z0-9]", "") + "_" + modo.replaceAll("[^A-Za-z0-9]", "");
                File destino = new File(carpetaQslRecibidas(), nombre + ".jpg");
                for (int k = 2; destino.exists(); k++) destino = new File(carpetaQslRecibidas(), nombre + "_" + k + ".jpg");
                java.nio.file.Files.write(destino.toPath(), img);
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO qsl_recibidas (fichero, de, cuenta, minuto, banda, modo, uuid_contacto, llegada, vista) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)")) {
                    ps.setString(1, destino.getName()); ps.setString(2, de); ps.setString(3, cuenta); ps.setLong(4, minuto);
                    ps.setString(5, banda); ps.setString(6, modo); ps.setString(7, idMio); ps.setLong(8, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                if (!idMio.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET b26_qsl = COALESCE(b26_qsl,0) | 2 WHERE uuid = ?")) {
                        ps.setString(1, idMio); ps.executeUpdate();
                    }
                }
                // Ya está en este ordenador: se borra del servidor
                firmadoB26(cuenta, "qsl_recibida", "{\"qid\":\"" + app.jsonTexto(qid) + "\"}");
                nuevas++;
            }
        }
        return nuevas;
    }

    // ---------------------------------------------------------------- Diplomas entre usuarios de Bitácora 26
    /** Tamaño máximo de un diploma para mandarlo por Bitácora 26 (el servidor tiene el mismo límite). */
    static final long DIPLOMA_MAX_BYTES = 2L * 1024 * 1024;
    /** Lo que no se descarga en este tiempo se borra del servidor. */
    static final int DIAS_EN_SERVIDOR = 2;
    private static final java.util.concurrent.ExecutorService colaDiplomas = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EnvioDiplomas"); t.setDaemon(true); return t;
    });
    private volatile boolean avisoDiplomaGrande = false;

    /** La parte [ ... ] de una lista del JSON (o "" si no está). */
    private static String listaJson(String json, String clave) {
        int i = json.indexOf("\"" + clave + "\"");
        if (i < 0) return "";
        int a = json.indexOf('[', i);
        if (a < 0) return "";
        int nivel = 0;
        for (int k = a; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '[') nivel++;
            else if (c == ']' && --nivel == 0) return json.substring(a, k + 1);
        }
        return json.substring(a);
    }

    /** Carpeta donde se guardan los diplomas que llegan: Documentos\Libro de Guardia\Diplomas recibidos */
    static File carpetaDiplomasRecibidos() {
        File f = new File(System.getProperty("user.home"), "Documents" + File.separator + LibroGuardiaUI.NOMBRE_APP + File.separator + "Diplomas recibidos");
        f.mkdirs();
        return f;
    }

    private static int diplomasSinVer() {
        try { return Integer.parseInt(LibroGuardiaUI.ConfigApp.get("DIPLOMAS_SIN_VER", "0")); } catch (Exception e) { return 0; }
    }

    /**
     * Acaba de crearse un diploma en la carpeta del usuario: si el contacto usa Bitácora 26, se le manda también por aquí
     * (en segundo plano, de uno en uno). Si ocupa más de 2 MB no se manda y se avisa. log recibe cada resultado.
     */
    void enviarDiploma(String call, String banda, String nombreActividad, File pdf, java.util.function.Consumer<String> log) {
        if (call == null || call.isBlank() || pdf == null || !pdf.isFile() || cuentasB26().isEmpty()) return;
        boolean cb = "CB".equalsIgnoreCase(banda == null ? "" : banda.trim());
        String op = LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual;
        String k = "OPERADOR_FICHA_" + op + "_";
        String cuenta = cb ? cuentaDeIndicativo(baseIndicativo(LibroGuardiaUI.ConfigApp.get(k + "CB", "")), "CB")
                : cuentaDeIndicativo(baseIndicativo(LibroGuardiaUI.ConfigApp.get(k + "HF", op)), "HAM");
        if (cuenta.isEmpty()) cuenta = cuentaB26DeOperador(op);
        if (cuenta.isEmpty()) return;
        final String miCuenta = cuenta;
        if (pdf.length() > DIPLOMA_MAX_BYTES) {
            String txt = String.format(java.util.Locale.ROOT, "%.1f", pdf.length() / 1048576.0).replace('.', ',');
            log.accept("Diploma de " + call + " NO enviado por Bitácora 26: ocupa demasiado (" + txt + " MB; máximo 2 MB). Envíalo por otros medios.");
            if (!avisoDiplomaGrande) {
                avisoDiplomaGrande = true;
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(app,
                        "El diploma de " + call + " ocupa demasiado (" + txt + " MB) para mandarlo por Bitácora 26 (máximo 2 MB).\n\n"
                                + "Se ha guardado igualmente en tu carpeta, pero tendrás que enviarlo por otros medios.\n"
                                + "Consejo: usa una imagen de fondo más ligera en «Configurar Diploma».",
                        "Diploma demasiado grande", JOptionPane.WARNING_MESSAGE));
            }
            return;
        }
        colaDiplomas.submit(() -> {
            try {
                String b64 = java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(pdf.toPath()));
                String[] r = firmadoB26(miCuenta, "diploma_enviar", "{\"ind\":\"" + app.jsonTexto(baseIndicativo(call)) + "\",\"nombre\":\""
                        + app.jsonTexto(nombreActividad == null ? "" : nombreActividad) + "\",\"pdf\":\"" + b64 + "\"}");
                if ("200".equals(r[0])) log.accept("Diploma de " + call + " enviado por Bitácora 26 (le llegará al abrir el programa; si no lo abre en "
                        + DIAS_EN_SERVIDOR + " días, se borrará).");
                else if ("404".equals(r[0])) { /* no usa Bitácora 26: solo se queda en la carpeta */ }
                else log.accept("Diploma de " + call + " no enviado por Bitácora 26: " + errorJson(r));
            } catch (Exception e) {
                log.accept("Diploma de " + call + " no enviado por Bitácora 26: " + e.getMessage());
            }
        });
    }

    /** Descarga los diplomas que me han mandado, los guarda en "Diplomas recibidos" y los borra del servidor. Devuelve cuántos. */
    private int descargarDiplomasNuevos(List<String> cuentas) {
        int nuevos = 0;
        for (String cuenta : cuentas) {
            String[] r = firmadoB26(cuenta, "buzon", "{}");
            if (!"200".equals(r[0])) continue;
            Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(listaJson(r[1], "diplomas"));
            while (m.find()) {
                String o = m.group(), did = app.campoJson(o, "did"), de = app.campoJson(o, "de"), nombre = app.campoJson(o, "nombre");
                if (did.isEmpty()) continue;
                String[] b = firmadoB26(cuenta, "diploma_bajar", "{\"did\":\"" + app.jsonTexto(did) + "\"}");
                if (!"200".equals(b[0])) continue;
                byte[] pdf;
                try {
                    int i = b[1].indexOf("\"pdf\""), a = b[1].indexOf('"', b[1].indexOf(':', i) + 1), z = b[1].indexOf('"', a + 1);
                    pdf = java.util.Base64.getDecoder().decode(b[1].substring(a + 1, z));
                } catch (Exception e) {
                    continue;
                }
                if (pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P') continue;
                String base = "Diploma_" + de.replaceAll("[^A-Za-z0-9]", "_") + (nombre.isBlank() ? "" : "_" + java.text.Normalizer.normalize(nombre, java.text.Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9 ._-]", "_").trim())
                        + "_" + java.time.LocalDate.now(ZoneOffset.UTC);
                File destino = new File(carpetaDiplomasRecibidos(), base + ".pdf");
                for (int k = 2; destino.exists(); k++) destino = new File(carpetaDiplomasRecibidos(), base + "_" + k + ".pdf");
                try {
                    java.nio.file.Files.write(destino.toPath(), pdf);
                } catch (Exception e) {
                    continue;
                }
                firmadoB26(cuenta, "diploma_recibido", "{\"did\":\"" + app.jsonTexto(did) + "\"}");
                nuevos++;
            }
        }
        if (nuevos > 0) LibroGuardiaUI.ConfigApp.put("DIPLOMAS_SIN_VER", String.valueOf(diplomasSinVer() + nuevos));
        return nuevos;
    }

    /** Aviso de lo que acaba de llegar (sin parar el programa). */
    private void avisarLlegadas(int qsl, int diplomas) {
        StringBuilder t = new StringBuilder("Te ha llegado ");
        if (qsl > 0) t.append(qsl == 1 ? "una QSL nueva" : qsl + " QSL nuevas");
        if (qsl > 0 && diplomas > 0) t.append(" y ");
        if (diplomas > 0) t.append(diplomas == 1 ? "un diploma nuevo" : diplomas + " diplomas nuevos");
        t.append(".\n\n");
        if (qsl > 0) t.append("QSL: menú QSL → QSL recibidas.\n");
        if (diplomas > 0) t.append("Diplomas: menú QSL → Diplomas recibidos (carpeta ").append(carpetaDiplomasRecibidos().getAbsolutePath()).append(").\n");
        t.append("\nLo que te mandan solo se guarda ").append(DIAS_EN_SERVIDOR).append(" días en el servidor: abre Bitácora 26 a menudo para no perder nada.");
        SwingUtilities.invokeLater(() -> {
            JOptionPane p = new JOptionPane(t.toString(), JOptionPane.INFORMATION_MESSAGE);
            JDialog d = p.createDialog(app, "QSL y diplomas");
            d.setModal(false);
            d.setVisible(true);
        });
    }

    /** Ventana "Diplomas recibidos": lista de la carpeta, con abrir y abrir la carpeta. */
    void abrirDiplomasRecibidos() {
        LibroGuardiaUI.ConfigApp.put("DIPLOMAS_SIN_VER", "0");
        actualizarMenuQsl();
        File[] fs = carpetaDiplomasRecibidos().listFiles((dir, n) -> { String x = n.toLowerCase(java.util.Locale.ROOT); return x.endsWith(".pdf") || x.endsWith(".jpg") || x.endsWith(".jpeg") || x.endsWith(".png"); });
        java.util.List<File> lista = new ArrayList<>(fs == null ? java.util.List.of() : java.util.Arrays.asList(fs));
        lista.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        JDialog d = new JDialog(app, "Diplomas recibidos", true);
        DefaultListModel<String> m = new DefaultListModel<>();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm");
        for (File f : lista) m.addElement(f.getName() + "   ·   " + fmt.format(new java.util.Date(f.lastModified())));
        JList<String> l = new JList<>(m);
        JButton bAbrir = new JButton("Abrir"), bCarpeta = new JButton("Abrir la carpeta"), bCerrar = new JButton("Cerrar");
        Runnable abrir = () -> {
            int i = l.getSelectedIndex();
            if (i < 0) { JOptionPane.showMessageDialog(d, "Elige primero un diploma de la lista.", "Diplomas recibidos", JOptionPane.INFORMATION_MESSAGE); return; }
            try { Desktop.getDesktop().open(lista.get(i)); } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "No se ha podido abrir:\n" + lista.get(i).getAbsolutePath(), "Diplomas recibidos", JOptionPane.WARNING_MESSAGE);
            }
        };
        bAbrir.addActionListener(e -> abrir.run());
        l.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) abrir.run(); }
        });
        bCarpeta.addActionListener(e -> {
            try { Desktop.getDesktop().open(carpetaDiplomasRecibidos()); } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "La carpeta es:\n" + carpetaDiplomasRecibidos().getAbsolutePath(), "Diplomas recibidos", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        bCerrar.addActionListener(e -> d.dispose());
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        abajo.add(bAbrir); abajo.add(bCarpeta); abajo.add(bCerrar);
        JLabel nota = new JLabel("<html>&nbsp;" + (lista.isEmpty() ? "Todavía no te ha llegado ningún diploma." : "Doble clic para abrir un diploma.")
                + " Los diplomas que te mandan otros usuarios de Bitácora 26 llegan solos y se guardan en <b>"
                + carpetaDiplomasRecibidos().getAbsolutePath().replace("&", "&amp;") + "</b> (en el servidor solo están " + DIAS_EN_SERVIDOR + " días).</html>");
        d.setLayout(new BorderLayout(4, 4));
        d.add(nota, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(l);
        sp.setPreferredSize(new Dimension(560, 420));
        // Vista previa del diploma elegido (a la derecha)
        JLabel vista = new JLabel("Elige un diploma para verlo aquí", SwingConstants.CENTER);
        vista.setForeground(Color.GRAY);
        JScrollPane spVista = new JScrollPane(vista);
        spVista.setPreferredSize(new Dimension(560, 420));
        final int[] pedido = {0};
        l.addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) return;
            int i = l.getSelectedIndex();
            if (i < 0) return;
            File f = lista.get(i);
            int yo = ++pedido[0];
            vista.setIcon(null);
            vista.setText("Cargando…");
            new Thread(() -> {
                Image img = vistaPreviaDiploma(f, 540, 400);
                SwingUtilities.invokeLater(() -> {
                    if (yo != pedido[0]) return;
                    if (img == null) { vista.setIcon(null); vista.setText("No se puede mostrar la vista previa"); }
                    else { vista.setText(null); vista.setIcon(new ImageIcon(img)); }
                });
            }, "VistaDiploma").start();
        });
        JSplitPane partes = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sp, spVista);
        partes.setResizeWeight(0.45);
        d.add(partes, BorderLayout.CENTER);
        d.add(abajo, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(app);
        d.setVisible(true);
    }

    /** Imagen reducida de un diploma (la primera página si es PDF), para la vista previa. */
    static Image vistaPreviaDiploma(File f, int maxAncho, int maxAlto) {
        try {
            java.awt.image.BufferedImage img;
            if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(f)) {
                    img = new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 60);
                }
            } else {
                img = javax.imageio.ImageIO.read(f);
            }
            if (img == null) return null;
            double k = Math.min((double) maxAncho / img.getWidth(), (double) maxAlto / img.getHeight());
            if (k > 1) k = 1;
            return img.getScaledInstance(Math.max(1, (int) (img.getWidth() * k)), Math.max(1, (int) (img.getHeight() * k)), Image.SCALE_SMOOTH);
        } catch (Throwable e) {
            return null;
        }
    }

    /** QSL recibidas que aún no se han mirado en la ventana. */
    private static int contarQslSinVer() {
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM qsl_recibidas WHERE vista = 0")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ventana "QSL recibidas" (las que están en la carpeta QSL). Si soloDe (uuid de un contacto) no es null, abre directamente esa QSL. */
    void abrirBuzonQsl(String soloDe) {
        List<String[]> filas = new ArrayList<>();   // {id, fichero, de, contacto, banda, modo, mi indicativo, llegada, nueva, uuid}
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm");
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, fichero, de, minuto, banda, modo, cuenta, llegada, vista, COALESCE(uuid_contacto,'') FROM qsl_recibidas ORDER BY llegada DESC")) {
            while (rs.next()) {
                java.time.LocalDateTime f = java.time.LocalDateTime.ofEpochSecond(rs.getLong(4) * 60, 0, ZoneOffset.UTC);
                filas.add(new String[]{String.valueOf(rs.getInt(1)), rs.getString(2), rs.getString(3), f.format(app.FMT_FECHA_UI) + " " + f.format(app.FMT_HORA_UI) + " UTC",
                        rs.getString(5), rs.getString(6), indicativoParaMostrar(rs.getString(7)), fmt.format(new java.util.Date(rs.getLong(8))), rs.getInt(9) == 0 ? "NUEVA" : "",
                        rs.getString(10), String.valueOf(rs.getLong(4))});
            }
        } catch (Exception ignored) { }
        if (soloDe != null) {
            String[] encontrada = null;
            for (String[] f : filas) if (f[9].equals(soloDe)) { encontrada = f; break; }
            if (encontrada == null) {   // llegó antes de confirmarse: se busca por indicativo, banda y hora (±30 min)
                try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement(
                        "SELECT COALESCE(b26_conf,''), COALESCE(fecha,''), COALESCE(hora,''), COALESCE(banda,''), COALESCE(indicativo,'') FROM qsos WHERE uuid = ?")) {
                    ps.setString(1, soloDe);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String otro = rs.getString(1).isEmpty() ? baseIndicativo(rs.getString(5)) : rs.getString(1).replaceAll("-[0-9]+$", "");
                            long minuto = minutoUtc(rs.getString(2), rs.getString(3));
                            String banda = rs.getString(4).equalsIgnoreCase("CB") ? "CB" : rs.getString(4).toLowerCase();
                            for (String[] f : filas) {
                                if (f[2].equals(otro) && f[4].equalsIgnoreCase(banda) && Math.abs(Long.parseLong(f[10]) - minuto) <= 30) { encontrada = f; break; }
                            }
                        }
                    }
                } catch (Exception ignored) { }
            }
            if (encontrada != null) verQslRecibida(app, encontrada);
            else JOptionPane.showMessageDialog(app, "Esa QSL todavía no ha llegado a este ordenador (se descargan solas cada pocos minutos).",
                    "QSL recibida", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JDialog d = new JDialog(app, "QSL recibidas", true);
        DefaultTableModel m = new DefaultTableModel(new Object[]{"De", "Contacto", "Banda", "Modo", "Mi indicativo", "Llegó", ""}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (String[] f : filas) m.addRow(new Object[]{f[2], f[3], f[4], f[5], f[6], f[7], f[8]});
        JTable t = new JTable(m);
        t.setRowHeight(22);
        t.setAutoCreateRowSorter(true);
        t.getColumnModel().getColumn(1).setPreferredWidth(170);
        t.getColumnModel().getColumn(5).setPreferredWidth(130);
        JButton bVer = new JButton("Ver"), bCarpeta = new JButton("Abrir la carpeta QSL"), bBorrar = new JButton("Borrar"), bCerrar = new JButton("Cerrar");
        java.util.function.Supplier<String[]> elegida = () -> {
            int f = t.getSelectedRow();
            if (f < 0) { JOptionPane.showMessageDialog(d, "Elige primero una QSL de la lista.", "QSL recibidas", JOptionPane.INFORMATION_MESSAGE); return null; }
            return filas.get(t.convertRowIndexToModel(f));
        };
        bVer.addActionListener(e -> { String[] f = elegida.get(); if (f != null) verQslRecibida(d, f); });
        t.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) bVer.doClick(); }
        });
        bCarpeta.addActionListener(e -> {
            try { Desktop.getDesktop().open(carpetaQslRecibidas()); } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "La carpeta es:\n" + carpetaQslRecibidas().getAbsolutePath(), "QSL recibidas", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        bBorrar.addActionListener(e -> {
            String[] f = elegida.get();
            if (f == null || JOptionPane.showConfirmDialog(d, "¿Borrar de tu ordenador la QSL de " + f[2] + "?", "QSL recibidas", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            new File(carpetaQslRecibidas(), f[1]).delete();
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement("DELETE FROM qsl_recibidas WHERE id = ?")) {
                ps.setInt(1, Integer.parseInt(f[0])); ps.executeUpdate();
            } catch (Exception ignored) { }
            int fila = t.convertRowIndexToModel(t.getSelectedRow());
            filas.remove(fila);
            m.removeRow(fila);
        });
        bCerrar.addActionListener(e -> d.dispose());
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        abajo.add(bVer); abajo.add(bCarpeta); abajo.add(bBorrar); abajo.add(bCerrar);
        JLabel nota = new JLabel("<html>&nbsp;" + (filas.isEmpty() ? "Todavía no te ha llegado ninguna QSL." : "Doble clic para ver una QSL.")
                + " Las QSL llegan solas y se guardan en tu carpeta <b>" + carpetaQslRecibidas().getAbsolutePath().replace("&", "&amp;") + "</b>"
                + " (en el servidor se borran en cuanto llegan).</html>");
        d.setLayout(new BorderLayout(4, 4));
        d.add(nota, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(760, 300));
        d.add(sp, BorderLayout.CENTER);
        d.add(abajo, BorderLayout.SOUTH);
        // Al abrir la ventana, las nuevas pasan a vistas
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE qsl_recibidas SET vista = 1 WHERE vista = 0");
        } catch (Exception ignored) { }
        qslSinLeer = 0;
        actualizarMenuQsl();
        d.pack();
        d.setLocationRelativeTo(app);
        d.setVisible(true);
    }

    private void verQslRecibida(Component padre, String[] f) {
        try {
            BufferedImage b = ImageIO.read(new File(carpetaQslRecibidas(), f[1]));
            if (b == null) throw new Exception();
            int factor = b.getWidth() < 500 ? 2 : 1;   // las de 72 ppp (pequeñas) se ven al doble
            JLabel l = new JLabel(new ImageIcon(b.getScaledInstance(b.getWidth() * factor, b.getHeight() * factor, Image.SCALE_SMOOTH)));
            JOptionPane.showMessageDialog(padre, new Object[]{"QSL de " + f[2] + " · " + f[3] + " · " + f[4] + " " + f[5], l}, "QSL de " + f[2], JOptionPane.PLAIN_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "No se encuentra el fichero de esa QSL en la carpeta:\n" + carpetaQslRecibidas().getAbsolutePath(), "QSL", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Botón derecho → "Enviar mi QSL" sobre un contacto (confirmado o no; la otra estación tiene que usar Bitácora 26). */
    /**
     * Botón derecho → "Descargar mi QSL de …": la QSL de ese contacto (con tu diseño) se guarda como imagen JPG,
     * por ejemplo para mandarla por correo a quien no usa Bitácora 26.
     */
    void descargarQslPorId(int id) {
        String[] c = null;
        String cuenta = "";
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(indicativo,''), COALESCE(fecha,''), "
                + "COALESCE(hora,''), COALESCE(banda,''), COALESCE(modo,''), COALESCE(rst_sent,''), COALESCE(b26_cuenta,''), COALESCE(b26_conf,''), COALESCE(freq_tx,'') FROM qsos WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String hora = rs.getString(3);
                    c = new String[]{rs.getString(1), rs.getString(2), hora.length() > 5 ? hora.substring(0, 5) : hora, rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(9), rs.getString(8).isEmpty() ? "0" : "1"};
                    cuenta = rs.getString(7);
                }
            }
        } catch (Exception ignored) { }
        if (c == null) return;
        if (cuenta.isEmpty()) {   // contacto que no se ha subido: el diseño del indicativo que corresponde (CB u otro)
            boolean cb = "CB".equalsIgnoreCase(c[3].trim());
            String op = LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual;
            String k = "OPERADOR_FICHA_" + op + "_";
            cuenta = cb ? cuentaDeIndicativo(baseIndicativo(LibroGuardiaUI.ConfigApp.get(k + "CB", "")), "CB")
                    : cuentaDeIndicativo(baseIndicativo(LibroGuardiaUI.ConfigApp.get(k + "HF", op)), "HAM");
            if (cuenta.isEmpty()) cuenta = cuentaB26DeOperador(op);
        }
        byte[] jpg;
        try {
            jpg = qslAJpeg(dibujarQsl(cuenta, c));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(app, "No se ha podido crear la QSL: " + e.getMessage(), "Descargar QSL", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String nombre = ("QSL " + c[0] + " " + c[1].replace("/", "-")).replaceAll("[\\\\/:*?\"<>|]", "_") + ".jpg";
        File carpeta = new File(System.getProperty("user.home"), "Documents" + File.separator + LibroGuardiaUI.NOMBRE_APP + File.separator + "QSL");
        carpeta.mkdirs();
        JFileChooser fc = new JFileChooser(carpeta);
        fc.setDialogTitle("Guardar la QSL de " + c[0]);
        fc.setSelectedFile(new File(carpeta, nombre));
        if (fc.showSaveDialog(app) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")) f = new File(f.getParentFile(), f.getName() + ".jpg");
        try {
            java.nio.file.Files.write(f.toPath(), jpg);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(app, "No se ha podido guardar: " + e.getMessage(), "Descargar QSL", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(app, "QSL guardada en:\n" + f.getAbsolutePath() + "\n\n¿Quieres abrir la carpeta?", "Descargar QSL",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE) == JOptionPane.YES_OPTION) {
            try { Desktop.getDesktop().open(f.getParentFile()); } catch (Exception ignored) { }
        }
    }

    void enviarQslPorId(int id) {
        new Thread(() -> {
            String uuid = null;
            int sub = 0;
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement("SELECT uuid, COALESCE(b26_sub,0) FROM qsos WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { uuid = rs.getString(1); sub = rs.getInt(2); } }
            } catch (Exception ignored) { }
            if (sub != 1) {   // aún no estaba subido: se sube ahora
                sincronizarCuentasB26(false);
                try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); PreparedStatement ps = conn.prepareStatement("SELECT uuid, COALESCE(b26_sub,0) FROM qsos WHERE id = ?")) {
                    ps.setInt(1, id);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { uuid = rs.getString(1); sub = rs.getInt(2); } }
                } catch (Exception ignored) { }
            }
            String err;
            if (sub == 2) err = "ese contacto no es de ningún indicativo vinculado a este ordenador (Mi Estación → Operador)";
            else if (sub != 1 || uuid == null) err = "no se ha podido subir el contacto" + (ultimoProblemaCuentas == null ? "" : ": " + ultimoProblemaCuentas);
            else {
                try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) { err = enviarQslDeContacto(conn, uuid); } catch (Exception e) { err = e.getMessage(); }
            }
            final String e2 = err;
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(app, e2 == null ? "QSL enviada." : "No se ha podido enviar la QSL: " + e2, "Enviar QSL",
                        e2 == null ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                app.cargarQSOsDesdeBaseDatos();
            });
        }, "EnviarQSL").start();
    }
}
