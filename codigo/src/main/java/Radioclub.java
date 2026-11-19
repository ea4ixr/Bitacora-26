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

/** Radioclub: compartir, papeles, socios, cargos, contraseñas y ordenadores */
class Radioclub {
    final LibroGuardiaUI app;   // la ventana principal

    Radioclub(LibroGuardiaUI app) {
        this.app = app;
    }

    // ===================== COMPARTIR CON EL RADIOCLUB (sincronización por una carpeta compartida) =====================
    // Cada ordenador escribe SOLO en su propio archivo (disp_xxxx.txt) lo que cambia, firmado con su llave; los demás lo leen.
    // Socios, cuotas, cargos y datos del radioclub llevan además la firma de un administrador: sin ella, se rechazan.
    private static final String CARPETA_SYNC = "Bitacora 26 - radioclub";
    private static final String[] CLAVES_RC_SYNC = {"RADIOCLUB_NOMBRE", "RADIOCLUB_IND_CB", "RADIOCLUB_IND_HF", "RADIOCLUB_IND_ESP", "RADIOCLUB_QTH", "RADIOCLUB_GRID", "RADIOCLUB_WEB"};
    JLabel lblSync;
    private volatile String ultimoProblemaSync;
    private volatile boolean socioNuevoPorSync;
    private java.security.KeyPair llaveDisp;
    private final java.util.concurrent.atomic.AtomicBoolean syncEnCola = new java.util.concurrent.atomic.AtomicBoolean(false);

    boolean syncActivo() {
        return "1".equals(LibroGuardiaUI.ConfigApp.get("RC_SYNC_ACTIVO", "0")) && !LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", "").isEmpty();
    }

    void actualizarEstadoSync(String texto, Color color) {
        SwingUtilities.invokeLater(() -> {
            if (lblSync == null) return;
            lblSync.setText(texto == null ? " " : texto);
            lblSync.setForeground(color == null ? app.TEMA_TEXTO : color);
        });
    }

    /** Lanza la sincronización en segundo plano (si ya hay una en marcha, no se repite). */
    /**
     * Si se ha elegido la propia subcarpeta "Bitacora 26 - radioclub" (la que crea el programa),
     * se usa la carpeta de encima, que es la compartida de verdad.
     */
    private static String carpetaBaseSync(String carpeta) {
        if (carpeta == null) return "";
        String c = carpeta.trim();
        while (c.endsWith("\\") || c.endsWith("/")) c = c.substring(0, c.length() - 1);
        File f = new File(c);
        if (f.getName().equalsIgnoreCase(CARPETA_SYNC) && f.getParentFile() != null) return f.getParent();
        return c;
    }

    /** Nombre del archivo de este ordenador dentro de la carpeta compartida. */
    private String miFicheroSync() throws Exception {
        String miPub = java.util.Base64.getEncoder().encodeToString(llaveDispositivo().getPublic().getEncoded());
        return "disp_" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(miPub.getBytes(StandardCharsets.UTF_8))).substring(0, 16) + ".txt";
    }

    /**
     * Prepara la subcarpeta "Bitacora 26 - radioclub" y el archivo de este ordenador.
     * Devuelve null si se puede escribir, o el motivo (en palabras sencillas) si no.
     */
    private String prepararCarpetaSync(String carpeta) {
        if (carpeta == null || carpeta.isBlank()) return "no se ha elegido ninguna carpeta";
        carpeta = carpetaBaseSync(carpeta);
        File base = new File(carpeta);
        if (esAccesoDirectoDeRed(base))
            return "esa carpeta es un acceso directo de Windows (por ejemplo a un FTP), no una carpeta de verdad: "
                    + "los archivos se quedarían en este ordenador y no llegarían al FTP";
        if (!base.isDirectory()) return "no encuentro la carpeta " + carpeta;
        File dir = new File(base, CARPETA_SYNC);
        try {
            java.nio.file.Files.createDirectories(dir.toPath());
            java.nio.file.Files.write(new File(dir, miFicheroSync()).toPath(), new byte[0],
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return null;
        } catch (java.nio.file.AccessDeniedException ex) {
            return "Windows no deja escribir en " + dir.getPath() + " (sin permiso de escritura; si es una carpeta compartida "
                    + "de Drive, el dueño tiene que darte permiso de \"Editor\")";
        } catch (Exception ex) {
            return "no se puede escribir en " + dir.getPath() + " (" + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage()) + ")";
        }
    }

    /** Las "ubicaciones de red" de Windows (FTP añadido en el Explorador) son carpetas falsas con un acceso directo dentro. */
    private static boolean esAccesoDirectoDeRed(File f) {
        String ruta = f.getAbsolutePath().toLowerCase();
        return ruta.contains("\\network shortcuts\\") || ruta.endsWith("\\network shortcuts")
                || ruta.startsWith("ftp:") || new File(f, "target.lnk").isFile();
    }

    void sincronizarPronto() {
        if (!syncActivo() || !syncEnCola.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try { sincronizarRadioclub(); } finally { syncEnCola.set(false); }
        }, "SyncRadioclub");
        t.setDaemon(true);
        t.start();
    }

    // ---- llaves ----
    private static byte[] derivar(char[] clave, String salB64) throws Exception {
        javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return f.generateSecret(new javax.crypto.spec.PBEKeySpec(clave, java.util.Base64.getDecoder().decode(salB64), 120_000, 256)).getEncoded();
    }

    /** Llaves nuevas de un cargo: {pública, privada cifrada con la contraseña, sal, PrivateKey} */
    private static Object[] crearLlavesCargo(char[] clave) {
        try {
            java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String sal2 = nuevaSal();
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(derivar(clave, sal2), "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(kp.getPrivate().getEncoded());
            byte[] todo = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, todo, 0, iv.length);
            System.arraycopy(ct, 0, todo, iv.length, ct.length);
            java.util.Base64.Encoder b = java.util.Base64.getEncoder();
            return new Object[]{b.encodeToString(kp.getPublic().getEncoded()), b.encodeToString(todo), sal2, kp.getPrivate()};
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static java.security.PrivateKey abrirLlaveCargo(char[] clave, String privCifrada, String sal2) {
        try {
            byte[] todo = java.util.Base64.getDecoder().decode(privCifrada);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(derivar(clave, sal2), "AES"), new javax.crypto.spec.GCMParameterSpec(128, todo, 0, 12));
            byte[] pk = c.doFinal(todo, 12, todo.length - 12);
            return java.security.KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pk));
        } catch (Exception e) {
            return null;
        }
    }

    /** Llave de este ordenador (se crea la primera vez y no sale de él). */
    synchronized java.security.KeyPair llaveDispositivo() throws Exception {
        if (llaveDisp != null) return llaveDisp;
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("Ed25519");
        String pr = LibroGuardiaUI.ConfigApp.get("SYNC_DEV_PRIV", ""), pu = LibroGuardiaUI.ConfigApp.get("SYNC_DEV_PUB", "");
        if (pr.isEmpty() || pu.isEmpty()) {
            java.security.KeyPair kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            LibroGuardiaUI.ConfigApp.put("SYNC_DEV_PRIV", java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
            LibroGuardiaUI.ConfigApp.put("SYNC_DEV_PUB", java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
            llaveDisp = kp;
        } else {
            llaveDisp = new java.security.KeyPair(
                    kf.generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pu))),
                    kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(pr))));
        }
        return llaveDisp;
    }

    static String firmar(java.security.PrivateKey k, String msg) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("Ed25519");
        s.initSign(k);
        s.update(msg.getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(s.sign());
    }

    private static boolean verificar(String pubB64, String msg, String firmaB64) {
        try {
            if (pubB64 == null || pubB64.isEmpty() || firmaB64 == null || firmaB64.isEmpty()) return false;
            java.security.PublicKey pk = java.security.KeyFactory.getInstance("Ed25519")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pubB64)));
            java.security.Signature s = java.security.Signature.getInstance("Ed25519");
            s.initVerify(pk);
            s.update(msg.getBytes(StandardCharsets.UTF_8));
            return s.verify(java.util.Base64.getDecoder().decode(firmaB64));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- datos en texto: "clave=valor&clave=valor" (ordenado, para que dos copias iguales den el mismo texto) ----
    static String codificar(java.util.SortedMap<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static java.util.SortedMap<String, String> decodificar(String s) {
        java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String par : s.split("&")) {
            int k = par.indexOf('=');
            if (k < 0) continue;
            m.put(java.net.URLDecoder.decode(par.substring(0, k), StandardCharsets.UTF_8), java.net.URLDecoder.decode(par.substring(k + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String quitarNumeral(String nombreInterno) {
        int i = nombreInterno == null ? -1 : nombreInterno.lastIndexOf(" #");
        return i > 0 ? nombreInterno.substring(0, i) : (nombreInterno == null ? "" : nombreInterno);
    }

    /** Lo que hay ahora en este ordenador de cada tipo que se comparte: tipo -> (clave -> datos en texto). */
    private Map<String, Map<String, String>> estadoCompartido(Connection conn) throws Exception {
        Map<String, Map<String, String>> r = new LinkedHashMap<>();
        for (String t : new String[]{"ADMIN", "RCCLAVE", "PERM", "ROL", "CARGO", "VINC", "CLAVETEMP", "EXPUL", "RC", "LOGO", "SOCIO", "CUOTA", "ACT", "QSO", "OPER", "HOLA", "CB"}) r.put(t, new LinkedHashMap<>());
        try (Statement st0 = conn.createStatement(); ResultSet rs0 = st0.executeQuery("SELECT op, sal, hash, f FROM rc_claves_temp")) {
            while (rs0.next()) {
                java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                m.put("sal", rs0.getString(2)); m.put("hash", rs0.getString(3)); m.put("f", String.valueOf(rs0.getLong(4)));
                r.get("CLAVETEMP").put(rs0.getString(1), codificar(m));
            }
        }
        if (!LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_VER", "").isEmpty()) {   // huella de la contraseña del radioclub (no la contraseña)
            java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
            m.put("sal", LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_SAL", ""));
            m.put("ver", LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_VER", ""));
            r.get("RCCLAVE").put("RCCLAVE", codificar(m));
        }
        {   // Permisos de cada papel (solo los envía el PC Radioclub)
            java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
            for (String rol : ROLES_CON_PERMISOS) m.put(rol, permisosDeRol(rol));
            r.get("PERM").put("PERM", codificar(m));
        }
        String miMarcaV = miFicheroSync().substring(5, 21);
        try (Statement st0 = conn.createStatement(); ResultSet rs0 = st0.executeQuery("SELECT uuid, COALESCE(indicativo,''), COALESCE(nombre,''), grado, pub, COALESCE(priv,''), COALESCE(sal2,'') "
                + "FROM rc_cargos WHERE COALESCE(uuid,'') <> '' AND COALESCE(pub,'') <> ''")) {
            while (rs0.next()) {   // Cargos (Super Admin / Admin) de los socios
                java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                m.put("ind", rs0.getString(2)); m.put("nom", rs0.getString(3)); m.put("grado", String.valueOf(rs0.getInt(4)));
                m.put("pub", rs0.getString(5)); m.put("priv", rs0.getString(6)); m.put("sal2", rs0.getString(7));
                r.get("CARGO").put(rs0.getString(1), codificar(m));
                String vinc = LibroGuardiaUI.ConfigApp.get("RC_VINC_" + rs0.getString(1), "");
                if (!vinc.isEmpty()) {   // este ordenador tiene activado ese cargo
                    java.util.SortedMap<String, String> v = new java.util.TreeMap<>();
                    v.put("cargo", rs0.getString(1)); v.put("s", vinc);
                    r.get("VINC").put(rs0.getString(1) + "/" + miMarcaV, codificar(v));
                }
            }
        }
        try (Statement st0 = conn.createStatement(); ResultSet rs0 = st0.executeQuery("SELECT dev, COALESCE(nota,''), COALESCE(rol,'ADMIN') FROM rc_admins WHERE hasta = 0")) {
            while (rs0.next()) {
                java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                m.put("rol", rs0.getString(3));
                m.put("nota", rs0.getString(2));
                r.get("ROL").put(rs0.getString(1), codificar(m));
            }
        }
        try (Statement st0 = conn.createStatement(); ResultSet rs0 = st0.executeQuery("SELECT dev, COALESCE(nota,'') FROM rc_expulsados")) {
            while (rs0.next()) {
                java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                m.put("nota", rs0.getString(2));
                r.get("EXPUL").put(rs0.getString(1), codificar(m));
            }
        }
        // Operadores de este ordenador que son socios del radioclub (para que el ordenador del radioclub los tenga)
        String miMarca = miFicheroSync().substring(5, 21);
        {   // Este ordenador se presenta (nombre del equipo y operador): así sale en "Ordenadores del radioclub"
            java.util.SortedMap<String, String> hola = new java.util.TreeMap<>();
            String eq = System.getenv("COMPUTERNAME");
            hola.put("eq", eq == null ? "" : eq);
            hola.put("op", app.operadorActual == null ? "" : app.operadorActual);
            String socInv = LibroGuardiaUI.ConfigApp.get("RC_SOCIO_INVITADO", "");
            if (!socInv.isEmpty()) {
                hola.put("soc", socInv);   // el socio para el que se hizo la clave de invitación
                // Sus datos (para que el PC Radioclub lo tenga en la lista de socios, tenga HF, CB o los dos)
                try {
                    String g = LibroGuardiaUI.ConfigApp.get("RC_INV_DATOS", "");
                    java.util.SortedMap<String, String> inv = g.isEmpty() ? new java.util.TreeMap<>() : decodificar(g);
                    String[] d = {inv.getOrDefault("shf", ""), inv.getOrDefault("scb", ""), inv.getOrDefault("snom", ""), inv.getOrDefault("sqth", ""), inv.getOrDefault("sgrid", "")};
                    String id = app.idOperador(d);
                    if (!id.isEmpty() && app.obtenerListaOperadores().contains(id)) {
                        String[] f = app.leerDatosOperador(id);
                        for (int k = 0; k < 5; k++) if (f[k] != null && !f[k].isBlank()) d[k] = f[k];
                    }
                    if (!app.idOperador(d).isEmpty()) {
                        hola.put("shf", d[0]); hola.put("scb", d[1]); hola.put("snom", d[2]); hola.put("sqth", d[3]); hola.put("sgrid", d[4]);
                    }
                } catch (Exception ignored) { }
            }
            r.get("HOLA").put(miMarca, codificar(hola));
        }
        for (String op : app.obtenerListaOperadores()) {
            if (!"1".equals(LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + op + "_SOCIO", "0"))) continue;
            String[] f = app.leerDatosOperador(op);
            java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
            m.put("id", op); m.put("hf", f[0]); m.put("cb", f[1]); m.put("nom", f[2]); m.put("qth", f[3]); m.put("grid", f[4]);
            r.get("OPER").put(miMarca + "/" + op, codificar(m));
        }
        try (Statement st = conn.createStatement()) {
            // El ordenador del radioclub se da a conocer a los demás con su llave
            String admin = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "");
            if (!admin.isEmpty()) {
                java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                m.put("pub", admin);
                r.get("ADMIN").put("ADMIN", codificar(m));
            }
            java.util.SortedMap<String, String> rc = new java.util.TreeMap<>();
            for (String k : CLAVES_RC_SYNC) rc.put(k, LibroGuardiaUI.ConfigApp.get(k, ""));
            r.get("RC").put("RC", codificar(rc));
            File logo = ficheroLogoRadioclub();   // el logo del radioclub también llega a todos
            if (logo.isFile() && logo.length() < 2_000_000) {
                java.util.SortedMap<String, String> lg = new java.util.TreeMap<>();
                lg.put("png", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.nio.file.Files.readAllBytes(logo.toPath())));
                r.get("LOGO").put("LOGO", codificar(lg));
            }
            try (ResultSet rs = st.executeQuery("SELECT uuid, num, ind_hf, ind_cb, nombre, qth FROM socios WHERE COALESCE(uuid,'') <> ''")) {
                while (rs.next()) {
                    java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                    m.put("num", String.valueOf(rs.getInt(2)));
                    m.put("hf", rs.getString(3) == null ? "" : rs.getString(3));
                    m.put("cb", rs.getString(4) == null ? "" : rs.getString(4));
                    m.put("nom", rs.getString(5) == null ? "" : rs.getString(5));
                    m.put("qth", rs.getString(6) == null ? "" : rs.getString(6));
                    r.get("SOCIO").put(rs.getString(1), codificar(m));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT s.uuid, c.anio, c.fecha_pago FROM socios_cuotas c JOIN socios s ON s.id = c.socio_id WHERE COALESCE(s.uuid,'') <> ''")) {
                while (rs.next()) {
                    java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                    m.put("fecha", rs.getString(3) == null ? "" : rs.getString(3));
                    r.get("CUOTA").put(rs.getString(1) + "/" + rs.getInt(2), codificar(m));
                }
            }
            Map<String, String> puntos = new HashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT actividad, tipo, valor, puntos FROM actividad_puntos ORDER BY actividad, tipo, valor")) {
                while (rs.next()) puntos.merge(rs.getString(1), rs.getString(2) + "\t" + rs.getString(3) + "\t" + rs.getInt(4) + "\n", String::concat);
            }
            try (ResultSet rs = st.executeQuery("SELECT uuid, nombre, tipo, operador_creador, umbral_bronce, umbral_plata, umbral_oro, cat6_nombre, cat6_umbral, "
                    + "fecha_creacion, banda_simple, modo_simple, indicativo_rc, numeral FROM actividades WHERE radioclub = 1 AND COALESCE(uuid,'') <> ''")) {
                while (rs.next()) {
                    java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                    m.put("nombre", quitarNumeral(rs.getString(2)));
                    String[] n = {"tipo", "creador", "bronce", "plata", "oro", "cat6", "cat6u", "fecha", "banda", "modo", "indrc"};
                    for (int i = 0; i < n.length; i++) m.put(n[i], rs.getString(i + 3) == null ? "" : rs.getString(i + 3));
                    m.put("puntos", puntos.getOrDefault(rs.getString(2), ""));
                    r.get("ACT").put(rs.getString(1), codificar(m));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT q.uuid, q.operador, q.fecha, q.hora, q.indicativo, q.banda, q.modo, q.freq_tx, q.freq_rx, q.rst_sent, q.rst_rcvd, "
                    + "q.nombre, q.qth, q.grid, q.puntos, q.num_contactos, q.estacion, "
                    + "CASE WHEN a.uuid IS NOT NULL AND a.uuid <> '' THEN a.uuid ELSE COALESCE(q.act_uuid, '') END, COALESCE(q.modificado_por, ''), COALESCE(q.comentario, '') "
                    + "FROM qsos q LEFT JOIN actividades a ON q.actividad_numeral > 0 AND a.numeral = q.actividad_numeral "
                    + "WHERE q.radioclub = 1 AND COALESCE(q.uuid,'') <> ''")) {
                String[] n = {"op", "fecha", "hora", "ind", "banda", "modo", "ftx", "frx", "rsts", "rstr", "nom", "qth", "grid", "puntos", "num", "est", "act"};
                while (rs.next()) {
                    java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                    for (int i = 0; i < n.length; i++) m.put(n[i], rs.getString(i + 2) == null ? "" : rs.getString(i + 2));
                    String mod = rs.getString(n.length + 2);
                    if (mod != null && !mod.isEmpty()) m.put("mod", mod);   // solo si se ha cambiado (así los demás no cambian)
                    String com = rs.getString(n.length + 3);
                    if (com != null && !com.isEmpty()) m.put("com", com);   // comentario, solo si lo hay
                    r.get("QSO").put(rs.getString(1), codificar(m));
                }
            }
        }
        // Lista BANDA CIUDADANA: se comparte con todos los socios (indicativo, nombre y QTH)
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT indicativo, nombre, qth FROM cb_directorio WHERE eliminado = 0")) {
            while (rs.next()) {
                String ind = app.txtCB(rs.getString(1));
                if (ind.isEmpty()) continue;
                java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                m.put("nom", app.txtCB(rs.getString(2)));
                m.put("qth", app.txtCB(rs.getString(3)));
                r.get("CB").put(ind, codificar(m));
            }
        }
        return r;
    }

    private static String[] versionDe(Connection conn, String tipo, String clave) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT f, dev FROM sync_ver WHERE tipo = ? AND clave = ?")) {
            ps.setString(1, tipo); ps.setString(2, clave);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new String[]{String.valueOf(rs.getLong(1)), rs.getString(2)} : null; }
        }
    }

    private static void guardarVersion(Connection conn, String tipo, String clave, long f, String dev) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sync_ver (tipo, clave, f, dev) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(tipo, clave) DO UPDATE SET f = excluded.f")) {
            ps.setString(1, tipo); ps.setString(2, clave); ps.setLong(3, f); ps.setString(4, dev);
            ps.executeUpdate();
        }
    }

    private static void guardarPublicado(Connection conn, String tipo, String clave, String datos) throws Exception {
        if (datos == null) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_pub WHERE tipo = ? AND clave = ?")) {
                ps.setString(1, tipo); ps.setString(2, clave); ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO sync_pub (tipo, clave, datos) VALUES (?, ?, ?)")) {
            ps.setString(1, tipo); ps.setString(2, clave); ps.setString(3, datos); ps.executeUpdate();
        }
    }

    /**
     * Sincroniza con la carpeta compartida: primero envía lo cambiado aquí y después recoge lo de los demás.
     * Devuelve sin hacer nada si no está activado.
     */
    synchronized void sincronizarRadioclub() {
        if (!syncActivo()) return;
        if (!app.cuentaB26.sincronizarPermitido()) {
            actualizarEstadoSync("Radioclub: parado. Hace falta una cuenta de Bitácora 26 (gratuita)", new Color(255, 110, 90));
            return;
        }
        // Actualización obligatoria: con una versión más antigua que la mínima no se sincroniza con el radioclub
        if (!SwingUtilities.isEventDispatchThread()) app.comprobarVersionMinima();
        if (app.versionMinimaExigida != null) {
            actualizarEstadoSync("Radioclub: sin sincronizar. Actualiza el programa (hace falta la versión " + app.versionMinimaExigida + ")", new Color(255, 110, 90));
            return;
        }
        if (!syncPorFtp()) { sincronizarRadioclubEnCarpeta(); return; }
        // Por FTP: se trae lo nuevo del servidor a una copia en este ordenador, se sincroniza con ella y se sube lo de aquí
        try (LibroGuardiaUI.FtpSimple ftp = abrirFtpRadioclub()) {
            traerDelFtp(ftp);
            syncTerminoBien = false;
            sincronizarRadioclubEnCarpeta();
            if (syncTerminoBien) llevarAlFtp(ftp);
        } catch (Exception ex) {
            actualizarEstadoSync("Radioclub (FTP): " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), new Color(255, 110, 90));
        }
    }

    private volatile boolean syncTerminoBien;

    private static boolean syncPorFtp() { return "FTP".equals(LibroGuardiaUI.ConfigApp.get("RC_SYNC_TIPO", "CARPETA")); }

    /** Copia en este ordenador de lo que hay en el FTP (Documentos\\Libro de Guardia\\radioclub_ftp). */
    private static File carpetaEspejoFtp() {
        return new File(new File(LibroGuardiaUI.DatabaseManager.obtenerRutaActiva()).getParentFile(), "radioclub_ftp");
    }

    private static String origenFtp(String host, String puerto, String usuario, String carpeta) {
        return "ftp://" + usuario.trim() + "@" + host.trim().toLowerCase() + ":" + (puerto.isBlank() ? "21" : puerto.trim()) + "/" + carpeta.trim();
    }

    private LibroGuardiaUI.FtpSimple abrirFtpRadioclub() throws Exception {
        int puerto = 21;
        try { puerto = Integer.parseInt(LibroGuardiaUI.ConfigApp.get("RC_FTP_PUERTO", "21").trim()); } catch (Exception ignored) { }
        LibroGuardiaUI.FtpSimple f = new LibroGuardiaUI.FtpSimple(LibroGuardiaUI.ConfigApp.get("RC_FTP_SERVIDOR", ""), puerto, "1".equals(LibroGuardiaUI.ConfigApp.get("RC_FTP_SEGURO", "0")));
        try {
            f.entrar(LibroGuardiaUI.ConfigApp.get("RC_FTP_USUARIO", ""), LibroGuardiaUI.ConfigApp.get("RC_FTP_CLAVE", ""));
            f.irACarpeta(LibroGuardiaUI.ConfigApp.get("RC_FTP_CARPETA", ""));
            f.irACarpeta(CARPETA_SYNC);
            return f;
        } catch (Exception ex) {
            f.close();
            throw ex;
        }
    }

    /** Baja del FTP la parte nueva de cada archivo (los archivos solo crecen). */
    private void traerDelFtp(LibroGuardiaUI.FtpSimple f) throws Exception {
        File dir = new File(carpetaEspejoFtp(), CARPETA_SYNC);
        java.nio.file.Files.createDirectories(dir.toPath());
        for (Map.Entry<String, Long> e : f.archivos().entrySet()) {
            String n = e.getKey();
            if (!n.startsWith("disp_") || !n.endsWith(".txt") || n.contains("/") || n.contains("\\") || n.contains("..")) continue;
            File local = new File(dir, n);
            long l = local.isFile() ? local.length() : 0;
            long r = e.getValue() == null || e.getValue() < 0 ? f.tamano(n) : e.getValue();
            if (r <= l) continue;
            byte[] b = f.bajar(n, l);
            java.nio.file.Files.write(local.toPath(), b, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /** Sube al FTP lo que este ordenador ha añadido a su archivo. */
    private void llevarAlFtp(LibroGuardiaUI.FtpSimple f) throws Exception {
        String mio = miFicheroSync();
        File local = new File(new File(carpetaEspejoFtp(), CARPETA_SYNC), mio);
        if (!local.isFile()) return;
        byte[] todo = java.nio.file.Files.readAllBytes(local.toPath());
        long r = f.tamano(mio);
        if (r == todo.length) return;
        if (r > 0 && r < todo.length) f.anadir(mio, java.util.Arrays.copyOfRange(todo, (int) r, todo.length));
        else if (r < 0 || r == 0) f.subir(mio, todo);
    }

    /** Sincroniza con la carpeta (la compartida, o la copia del FTP). */
    private synchronized void sincronizarRadioclubEnCarpeta() {
        File dir = new File(carpetaBaseSync(LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", "")), CARPETA_SYNC);
        String problema = prepararCarpetaSync(LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", ""));
        if (problema != null) {
            actualizarEstadoSync("Radioclub: " + problema, new Color(255, 110, 90));
            ultimoProblemaSync = problema;
            return;
        }
        ultimoProblemaSync = null;
        socioNuevoPorSync = false;
        int enviados = 0, pendientes = 0, recibidos = 0, rechazados = 0, deshechos = 0;
        boolean hayCambiosRecibidos = false;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            java.security.KeyPair kp = llaveDispositivo();
            String miPub = java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
            String miFichero = "disp_" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(miPub.getBytes(StandardCharsets.UTF_8))).substring(0, 16) + ".txt";
            // Lo que no tenga identificador único, se le pone
            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE socios SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid = ''");
                st.execute("UPDATE actividades SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid = ''");
                st.execute("UPDATE rc_cargos SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid = ''");
                st.execute("UPDATE qsos SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid = ''");
            }
            // La lista BANDA CIUDADANA se completa con los contactos de CB de este ordenador (de cualquier LOG o actividad)
            try { app.sincronizarDirectorioCB(conn); } catch (Exception ex) { System.err.println("Lista CB: " + ex.getMessage()); }
            boolean soyRadioclub = miPub.equals(LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", ""));
            String miRolAhora = rolDeOrdenador(conn, miPub, System.currentTimeMillis());
            boolean hayAdmin = !LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "").isEmpty();

            // ---------- 1. ENVIAR lo que ha cambiado aquí ----------
            progresoSync = 10;
            // Una sola vez: el ordenador del radioclub (o un Super Admin, ya con lo último recibido) vuelve a publicar
            // los datos del radioclub (nombre, indicativos...) para corregir copias antiguas en los socios
            String repub = LibroGuardiaUI.ConfigApp.get("RC_REPUBLICAR_V2", "");
            if ((soyRadioclub && !"1".equals(repub)) || ("SUPER".equals(miRolAhora) && "P".equals(repub))) {
                guardarPublicado(conn, "RC", "RC", null);
                LibroGuardiaUI.ConfigApp.put("RC_REPUBLICAR_V2", "1");
            }
            Map<String, Map<String, String>> ahora = estadoCompartido(conn);
            Map<String, Map<String, String>> antes = new HashMap<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT tipo, clave, datos FROM sync_pub")) {
                while (rs.next()) antes.computeIfAbsent(rs.getString(1), x -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
            }
            // Protección: si desde aquí se van a borrar muchos contactos del radioclub de golpe (por ejemplo, al volver a una
            // copia antigua de la base de datos), se pregunta antes. Si se dice que no, se recuperan aquí y no se borran en nadie.
            {
                Map<String, String> qAhora = ahora.getOrDefault("QSO", new HashMap<>()), qAntes = antes.getOrDefault("QSO", new HashMap<>());
                List<String> aBorrar = new ArrayList<>();
                for (String k : qAntes.keySet()) if (!qAhora.containsKey(k)) aBorrar.add(k);
                if (aBorrar.size() > LIMITE_BORRADO_MASIVO && !confirmarBorradoMasivo(aBorrar.size())) {
                    for (String k : aBorrar) {
                        try { aplicarEntidad(conn, "QSO", k, false, decodificar(qAntes.get(k)), miPub); } catch (Exception ignored) { }
                    }
                    hayCambiosRecibidos = true;
                    ahora = estadoCompartido(conn);
                }
            }
            StringBuilder salida = new StringBuilder();
            List<String[]> publicados = new ArrayList<>();   // {tipo, clave, datos o null}
            for (Map.Entry<String, Map<String, String>> t : ahora.entrySet()) {
                String tipo = t.getKey();
                Map<String, String> ant = antes.getOrDefault(tipo, new HashMap<>());
                Set<String> claves = new java.util.LinkedHashSet<>(t.getValue().keySet());
                claves.addAll(ant.keySet());
                for (String clave : claves) {
                    String datos = t.getValue().get(clave);
                    if (java.util.Objects.equals(datos, ant.get(clave))) continue;
                    if (tipo.equals("ADMIN") && !soyRadioclub) continue;   // solo el ordenador del radioclub se da a conocer
                    boolean borrar = datos == null;
                    String[] ver = versionDe(conn, tipo, clave);
                    String permiso = permisoNecesario(conn, tipo, borrar, ver, miPub, borrar ? new java.util.TreeMap<>() : decodificar(datos), clave);
                    if (tipo.equals("CB") && !borrar) permiso = ant.get(clave) != null ? "LISTACB" : null;   // aquí: ¿ya estaba en la lista compartida?
                    if ("?".equals(permiso)) continue;
                    // Lista BANDA CIUDADANA: lo que se quita de la lista en un ordenador sin permiso no se quita en los demás
                    if (tipo.equals("CB") && borrar && !rolTiene(miRolAhora, "LISTACB")) { guardarPublicado(conn, tipo, clave, null); continue; }
                    // Permisos y papeles de los ordenadores: solo los anuncia el PC Radioclub (los demás los reciben o los calculan)
                    if (tipo.equals("ROL") && !soyRadioclub) { guardarPublicado(conn, tipo, clave, datos); continue; }
                    if (tipo.equals("PERM") && !rolTiene(miRolAhora, "PERMISOS")) { guardarPublicado(conn, tipo, clave, datos); continue; }
                    boolean firmaAdmin = permiso != null;
                    if (tipo.equals("LOGO") && !rolTiene(miRolAhora, permiso)) { guardarPublicado(conn, tipo, clave, datos); continue; }
                    if (firmaAdmin && !rolTiene(miRolAhora, permiso)) {
                        String oficial = ant.get(clave);
                        if (oficial != null && !tipo.equals("RC") && hayAdmin) {
                            // Cambiado aquí sin permiso: se vuelve a dejar como está en el radioclub
                            if (aplicarEntidad(conn, tipo, clave, false, decodificar(oficial), miPub) == 1) { deshechos++; hayCambiosRecibidos = true; continue; }
                        } else if (oficial != null && tipo.equals("RC") && hayAdmin) {
                            aplicarEntidad(conn, tipo, clave, false, decodificar(oficial), miPub);
                            deshechos++; hayCambiosRecibidos = true; continue;
                        }
                        pendientes++;
                        continue;
                    }
                    long f = System.currentTimeMillis();
                    String d = borrar ? "" : datos;
                    String adm = firmaAdmin ? "RADIOCLUB" : "";
                    String msg = tipo + "\n" + clave + "\n" + f + "\n" + (borrar ? 1 : 0) + "\n" + d + "\n" + adm;
                    java.util.SortedMap<String, String> linea = new java.util.TreeMap<>();
                    linea.put("v", "1"); linea.put("t", tipo); linea.put("k", clave); linea.put("f", String.valueOf(f));
                    linea.put("del", borrar ? "1" : "0"); linea.put("d", d); linea.put("dev", miPub); linea.put("adm", adm);
                    linea.put("s", firmar(kp.getPrivate(), msg));
                    salida.append(codificar(linea)).append('\n');
                    publicados.add(new String[]{tipo, clave, datos});
                    guardarVersion(conn, tipo, clave, f, ver == null ? miPub : ver[1]);
                    enviados++;
                }
            }
            if (salida.length() > 0) {
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(dir, miFichero), true)) {
                    fo.write(salida.toString().getBytes(StandardCharsets.UTF_8));
                }
                for (String[] pb : publicados) guardarPublicado(conn, pb[0], pb[1], pb[2]);
            }

            // ---------- 2. RECOGER lo de los demás ----------
            progresoSync = 35;
            List<String> lineas = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT linea FROM sync_pend ORDER BY id")) {
                while (rs.next()) lineas.add(rs.getString(1));
            }
            try (Statement st = conn.createStatement()) { st.execute("DELETE FROM sync_pend"); }
            File[] otros = dir.listFiles((x, n) -> n.startsWith("disp_") && n.endsWith(".txt") && !n.equals(miFichero));
            Map<String, Long> nuevasPos = new HashMap<>();
            if (otros != null) {
                java.util.Arrays.sort(otros);
                for (File fi : otros) {
                    long pos = 0;
                    try (PreparedStatement ps = conn.prepareStatement("SELECT pos FROM sync_leido WHERE fichero = ?")) {
                        ps.setString(1, fi.getName());
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next()) pos = rs.getLong(1); }
                    }
                    if (fi.length() <= pos) continue;
                    byte[] b;
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(fi, "r")) {
                        raf.seek(pos);
                        b = new byte[(int) (raf.length() - pos)];
                        raf.readFully(b);
                    }
                    int ultimo = -1;
                    for (int i = b.length - 1; i >= 0; i--) if (b[i] == '\n') { ultimo = i; break; }
                    if (ultimo < 0) continue;   // aún no ha llegado una línea completa
                    for (String l : new String(b, 0, ultimo, StandardCharsets.UTF_8).split("\n")) if (!l.isBlank()) lineas.add(l);
                    nuevasPos.put(fi.getName(), pos + ultimo + 1);
                }
            }
            // Todo en el orden en que se hizo (así, por ejemplo, se sabe quién era el ordenador del radioclub en cada momento)
            lineas = new ArrayList<>(lineas);
            lineas.sort(java.util.Comparator.comparingLong(l -> { try { return Long.parseLong(decodificar(l).getOrDefault("f", "0")); } catch (Exception e) { return 0L; } }));
            Set<String> tocados = new HashSet<>();
            boolean algunoAplicado = true;
            List<String> porAplicar = lineas;
            // Se repite mientras se vayan aplicando (por si una cuota llega antes que su socio)
            int totalLineas = Math.max(1, lineas.size()), vistas = 0;
            while (algunoAplicado && !porAplicar.isEmpty()) {
                algunoAplicado = false;
                List<String> esperando = new ArrayList<>();
                for (String l : porAplicar) {
                    vistas++;
                    progresoSync = 35 + Math.min(60, 60 * vistas / totalLineas);
                    int r = aplicarLinea(conn, l, miPub, tocados);
                    if (r == 1) { recibidos++; algunoAplicado = true; hayCambiosRecibidos = true; }
                    else if (r == 0) esperando.add(l);
                    else if (r == -1) rechazados++;
                }
                porAplicar = esperando;
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sync_pend (linea) VALUES (?)")) {
                for (String l : porAplicar) { ps.setString(1, l); ps.executeUpdate(); }
            }
            for (Map.Entry<String, Long> e : nuevasPos.entrySet()) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO sync_leido (fichero, pos) VALUES (?, ?)")) {
                    ps.setString(1, e.getKey()); ps.setLong(2, e.getValue()); ps.executeUpdate();
                }
            }
            // Contactos que llegaron antes que su actividad: se enganchan ahora
            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE qsos SET actividad_numeral = (SELECT a.numeral FROM actividades a WHERE a.uuid = qsos.act_uuid), "
                        + "actividad = (SELECT a.nombre FROM actividades a WHERE a.uuid = qsos.act_uuid) "
                        + "WHERE COALESCE(act_uuid,'') <> '' AND EXISTS (SELECT 1 FROM actividades a WHERE a.uuid = qsos.act_uuid "
                        + "AND (qsos.actividad_numeral <> a.numeral OR COALESCE(qsos.actividad,'') <> a.nombre))");
            }
            // Lo recibido queda como "ya enviado", para no devolverlo como si fuera un cambio de aquí
            if (!tocados.isEmpty()) {
                Map<String, Map<String, String>> tras = estadoCompartido(conn);
                for (String tc : tocados) {
                    int k = tc.indexOf('|');
                    String tipo = tc.substring(0, k), clave = tc.substring(k + 1);
                    guardarPublicado(conn, tipo, clave, tras.getOrDefault(tipo, Map.of()).get(clave));
                }
            }
            if (porAplicar.size() > 0) pendientes += porAplicar.size();
            // Super Admin: tras recibir lo último, en la próxima sincronización vuelve a publicar los datos del radioclub
            if ("SUPER".equals(miRolAhora) && LibroGuardiaUI.ConfigApp.get("RC_REPUBLICAR_V2", "").isEmpty())
                LibroGuardiaUI.ConfigApp.put("RC_REPUBLICAR_V2", "P");
        } catch (Exception ex) {
            actualizarEstadoSync("Radioclub: error al sincronizar (" + ex.getMessage() + ")", new Color(255, 110, 90));
            return;
        }
        String hora = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date());
        syncTerminoBien = true;
        String txt = "Radioclub: sincronizado " + hora
                + (recibidos > 0 ? " · recibidos " + recibidos : "") + (enviados > 0 ? " · enviados " + enviados : "")
                + (pendientes > 0 ? " · " + pendientes + " pendientes" : "") + (rechazados > 0 ? " · " + rechazados + " rechazados" : "")
                + (deshechos > 0 ? " · " + deshechos + " cambios sin permiso deshechos" : "");
        actualizarEstadoSync(txt, rechazados > 0 ? new Color(255, 110, 90) : pendientes > 0 ? app.TEMA_AMBAR : app.cat.VERDE_ENCENDIDO);
        if (hayCambiosRecibidos) SwingUtilities.invokeLater(this::refrescarTrasSincronizar);
        SwingUtilities.invokeLater(this::ofrecerActivarCargo);
        subirClaveRCALaCuenta();   // la cuenta lleva el radioclub (para los demás ordenadores de la persona)
        if (socioNuevoPorSync) {   // un operador se ha apuntado como socio desde su ordenador: se comparte ya el socio nuevo
            socioNuevoPorSync = false;
            Thread t = new Thread(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) { } sincronizarPronto(); }, "SyncSocioNuevo");
            t.setDaemon(true);
            t.start();
        }
    }

    // ===================== SINCRONIZAR MIS ORDENADORES (el log de un operador en varios ordenadores) =====================
    // Igual que con el radioclub: cada ordenador escribe SOLO en su archivo (disp_xxxx.txt) lo que cambia y lee el de los demás,
    // en una carpeta en la nube (Drive, OneDrive...) o en un servidor FTP. Se sincronizan los contactos y actividades del
    // operador que NO son del radioclub (los del radioclub ya van por "Compartir con el radioclub").
    private static final String PREFIJO_CARPETA_SYNC_OP = "Bitacora 26 - operador ";
    JLabel lblSyncOp;

    private static String claveSyncOp(String op, String k) { return "OPERADOR_FICHA_" + op + "_SYNC_" + k; }

    private static boolean syncOpPorFtp(String op) { return "FTP".equals(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "TIPO"), "CARPETA")); }

    static boolean syncOpActivo(String op) {
        if (op == null || op.isBlank() || !"1".equals(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "ACTIVO"), "0"))) return false;
        return syncOpPorFtp(op) ? !LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_SERVIDOR"), "").isBlank()
                : !LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "CARPETA"), "").isBlank();
    }

    boolean syncOpActivo() { return syncOpActivo(app.operadorActual); }

    /** Nombre de la subcarpeta del operador (sin caracteres que Windows no admite). */
    private static String nombreCarpetaSyncOp(String op) {
        return PREFIJO_CARPETA_SYNC_OP + op.replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    /** Si se ha elegido la propia subcarpeta del operador, se usa la de encima. */
    private static String carpetaBaseSyncOp(String carpeta, String op) {
        if (carpeta == null) return "";
        String c = carpeta.trim();
        while (c.endsWith("\\") || c.endsWith("/")) c = c.substring(0, c.length() - 1);
        File f = new File(c);
        if (f.getName().equalsIgnoreCase(nombreCarpetaSyncOp(op)) && f.getParentFile() != null) return f.getParent();
        return c;
    }

    /** Copia en este ordenador de lo que hay en el FTP del operador. */
    private static File carpetaEspejoFtpOp(String op) {
        return new File(new File(new File(LibroGuardiaUI.DatabaseManager.obtenerRutaActiva()).getParentFile(), "operador_ftp"),
                op.replaceAll("[\\\\/:*?\"<>|]", "-"));
    }

    private void actualizarEstadoSyncOp(String texto, Color color) {
        SwingUtilities.invokeLater(() -> {
            if (lblSyncOp == null) return;
            lblSyncOp.setText(texto == null ? " " : texto);
            lblSyncOp.setForeground(color == null ? app.TEMA_TEXTO : color);
        });
    }

    /** Prepara la carpeta del operador y el archivo de este ordenador. Devuelve null si se puede escribir, o el motivo. */
    private String prepararCarpetaSyncOp(File base, File dir) {
        if (esAccesoDirectoDeRed(base))
            return "esa carpeta es un acceso directo de Windows (por ejemplo a un FTP), no una carpeta de verdad";
        if (!base.isDirectory()) return "no encuentro la carpeta " + base.getPath();
        try {
            java.nio.file.Files.createDirectories(dir.toPath());
            java.nio.file.Files.write(new File(dir, miFicheroSync()).toPath(), new byte[0],
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return null;
        } catch (java.nio.file.AccessDeniedException ex) {
            return "Windows no deja escribir en " + dir.getPath();
        } catch (Exception ex) {
            return "no se puede escribir en " + dir.getPath() + " (" + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage()) + ")";
        }
    }

    private LibroGuardiaUI.FtpSimple abrirFtpOp(String op) throws Exception {
        int puerto = 21;
        try { puerto = Integer.parseInt(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_PUERTO"), "21").trim()); } catch (Exception ignored) { }
        LibroGuardiaUI.FtpSimple f = new LibroGuardiaUI.FtpSimple(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_SERVIDOR"), ""), puerto, "1".equals(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_SEGURO"), "0")));
        try {
            f.entrar(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_USUARIO"), ""), LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_CLAVE"), ""));
            f.irACarpeta(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_CARPETA"), ""));
            f.irACarpeta(nombreCarpetaSyncOp(op));
            return f;
        } catch (Exception ex) {
            f.close();
            throw ex;
        }
    }

    /** Baja del FTP la parte nueva de cada archivo a la carpeta local indicada (los archivos solo crecen). */
    private static void traerDelFtpA(LibroGuardiaUI.FtpSimple f, File dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.toPath());
        for (Map.Entry<String, Long> e : f.archivos().entrySet()) {
            String n = e.getKey();
            if (!n.startsWith("disp_") || !n.endsWith(".txt") || n.contains("/") || n.contains("\\") || n.contains("..")) continue;
            File local = new File(dir, n);
            long l = local.isFile() ? local.length() : 0;
            long r = e.getValue() == null || e.getValue() < 0 ? f.tamano(n) : e.getValue();
            if (r <= l) continue;
            byte[] b = f.bajar(n, l);
            java.nio.file.Files.write(local.toPath(), b, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    /** Sube al FTP lo que este ordenador ha añadido a su archivo (desde la carpeta local indicada). */
    private void llevarAlFtpDesde(LibroGuardiaUI.FtpSimple f, File dir) throws Exception {
        String mio = miFicheroSync();
        File local = new File(dir, mio);
        if (!local.isFile()) return;
        byte[] todo = java.nio.file.Files.readAllBytes(local.toPath());
        long r = f.tamano(mio);
        if (r == todo.length) return;
        if (r > 0 && r < todo.length) f.anadir(mio, java.util.Arrays.copyOfRange(todo, (int) r, todo.length));
        else if (r <= 0) f.subir(mio, todo);
    }

    // ===================== Sincronizar ordenadores / radioclub con ventana de progreso =====================

    /** Lugar donde se dejan los archivos (QSL y diplomas) para mis otros ordenadores: una carpeta o el FTP. */
    private interface Almacen {
        Map<String, Long> lista() throws Exception;
        byte[] leer(String n) throws Exception;
        void escribir(String n, byte[] b) throws Exception;
    }

    private static final long MAX_ARCHIVO_SYNC = 15L * 1024 * 1024;

    /**
     * Copia entre mis ordenadores las QSL recibidas y los diplomas recibidos (solo añade: nunca borra nada).
     * Devuelve {enviados, recibidos}.
     */
    private int[] sincronizarArchivosMisOrdenadores(String op, java.util.function.Consumer<String> aviso) throws Exception {
        LibroGuardiaUI.FtpSimple ftp = null;
        try {
            Almacen al;
            if (syncOpPorFtp(op)) {
                final LibroGuardiaUI.FtpSimple f = ftp = abrirFtpOp(op);
                al = new Almacen() {
                    public Map<String, Long> lista() throws Exception { return f.archivos(); }
                    public byte[] leer(String n) throws Exception { return f.bajar(n, 0); }
                    public void escribir(String n, byte[] b) throws Exception { f.subir(n, b); }
                };
            } else {
                File dir = new File(carpetaBaseSyncOp(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "CARPETA"), ""), op), nombreCarpetaSyncOp(op));
                java.nio.file.Files.createDirectories(dir.toPath());
                al = new Almacen() {
                    public Map<String, Long> lista() {
                        Map<String, Long> m = new HashMap<>();
                        File[] fs = dir.listFiles();
                        if (fs != null) for (File x : fs) if (x.isFile()) m.put(x.getName(), x.length());
                        return m;
                    }
                    public byte[] leer(String n) throws Exception { return java.nio.file.Files.readAllBytes(new File(dir, n).toPath()); }
                    public void escribir(String n, byte[] b) throws Exception { java.nio.file.Files.write(new File(dir, n).toPath(), b); }
                };
            }
            Map<String, Long> remoto = al.lista();
            int env = 0, rec = 0;
            Object[][] carpetas = {{"dip", CuentaBitacora.carpetaDiplomasRecibidos(), "diplomas"}, {"qsl", CuentaBitacora.carpetaQslRecibidas(), "QSL"}};
            for (Object[] c : carpetas) {
                String pre = "arch_" + c[0] + "__";
                File loc = (File) c[1];
                File[] fs = loc.listFiles(File::isFile);
                Set<String> locales = new HashSet<>();
                if (fs != null) for (File x : fs) locales.add(x.getName());
                // Enviar lo que falta allí
                if (fs != null) for (File x : fs) {
                    String n = pre + x.getName();
                    if (remoto.containsKey(n) || x.length() > MAX_ARCHIVO_SYNC || x.getName().startsWith(".") || x.getName().equalsIgnoreCase("desktop.ini")) continue;
                    aviso.accept("Enviando " + c[2] + ": " + x.getName());
                    try {
                        al.escribir(n, java.nio.file.Files.readAllBytes(x.toPath()));
                        if ("qsl".equals(c[0])) { String m = datosQslRecibida(x.getName()); if (m != null) al.escribir("arch_qslm__" + x.getName() + ".txt", m.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                        env++;
                    } catch (Exception ignored) { }
                }
                // Recibir lo que falta aquí
                for (String n : remoto.keySet()) {
                    if (!n.startsWith(pre)) continue;
                    String nombre = n.substring(pre.length());
                    if (nombre.isEmpty() || nombre.contains("/") || nombre.contains("\\") || nombre.contains("..") || locales.contains(nombre)) continue;
                    aviso.accept("Recibiendo " + c[2] + ": " + nombre);
                    try {
                        java.nio.file.Files.write(new File(loc, nombre).toPath(), al.leer(n));
                        if ("qsl".equals(c[0]) && remoto.containsKey("arch_qslm__" + nombre + ".txt")) {
                            ponerQslRecibida(nombre, new String(al.leer("arch_qslm__" + nombre + ".txt"), java.nio.charset.StandardCharsets.UTF_8));
                        }
                        rec++;
                    } catch (Exception ignored) { }
                }
            }
            return new int[]{env, rec};
        } finally {
            if (ftp != null) try { ftp.close(); } catch (Exception ignored) { }
        }
    }

    /** Los datos de una QSL recibida (su fila en la base de datos), en texto, para llevarla a otro ordenador. */
    private static String datosQslRecibida(String fichero) {
        try (Connection c = LibroGuardiaUI.DatabaseManager.conectar(); PreparedStatement ps = c.prepareStatement(
                "SELECT de, cuenta, minuto, banda, modo, COALESCE(uuid_contacto,''), llegada FROM qsl_recibidas WHERE fichero = ? OR fichero LIKE ? LIMIT 1")) {
            ps.setString(1, fichero); ps.setString(2, "%" + fichero);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                m.put("de", rs.getString(1)); m.put("cuenta", rs.getString(2)); m.put("minuto", String.valueOf(rs.getLong(3)));
                m.put("banda", rs.getString(4)); m.put("modo", rs.getString(5)); m.put("uuid", rs.getString(6)); m.put("llegada", String.valueOf(rs.getLong(7)));
                return codificar(m);
            }
        } catch (Exception e) { return null; }
    }

    /** Apunta en la base de datos una QSL que ha llegado de otro ordenador (si no estaba ya). */
    private static void ponerQslRecibida(String fichero, String datos) {
        try (Connection c = LibroGuardiaUI.DatabaseManager.conectar()) {
            String ruta = new File(CuentaBitacora.carpetaQslRecibidas(), fichero).getAbsolutePath();
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM qsl_recibidas WHERE fichero = ? OR fichero LIKE ?")) {
                ps.setString(1, ruta); ps.setString(2, "%" + fichero);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return; }
            }
            Map<String, String> m = decodificar(datos.trim());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO qsl_recibidas (fichero, de, cuenta, minuto, banda, modo, uuid_contacto, llegada, vista) VALUES (?,?,?,?,?,?,?,?,1)")) {
                ps.setString(1, ruta); ps.setString(2, m.getOrDefault("de", "")); ps.setString(3, m.getOrDefault("cuenta", ""));
                ps.setLong(4, Long.parseLong(m.getOrDefault("minuto", "0"))); ps.setString(5, m.getOrDefault("banda", "")); ps.setString(6, m.getOrDefault("modo", ""));
                ps.setString(7, m.getOrDefault("uuid", "")); ps.setLong(8, Long.parseLong(m.getOrDefault("llegada", String.valueOf(System.currentTimeMillis()))));
                ps.executeUpdate();
            }
        } catch (Exception ignored) { }
    }

    /** Ventana con barra de progreso. Los pasos se ejecutan en segundo plano, uno detrás de otro. */
    private void ventanaProgreso(String titulo, String[] nombresPasos, java.util.List<java.util.function.Function<java.util.function.Consumer<String>, String>> pasos, Runnable alTerminar) {
        JDialog d = new JDialog(app, titulo, Dialog.ModalityType.MODELESS);
        JProgressBar barra = new JProgressBar(0, pasos.size() * 100);
        barra.setStringPainted(true);
        barra.setPreferredSize(new Dimension(460, 26));
        JLabel paso = new JLabel("Preparando…"), detalle = new JLabel(" ");
        detalle.setForeground(Color.GRAY);
        JTextArea resumen = new JTextArea(5, 40);
        resumen.setEditable(false);
        resumen.setLineWrap(true);
        resumen.setWrapStyleWord(true);
        JButton cerrar = new JButton("Cerrar");
        cerrar.setEnabled(false);
        cerrar.addActionListener(e -> d.dispose());
        JPanel p = new JPanel(new BorderLayout(6, 8));
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        JPanel arriba = new JPanel(new GridLayout(3, 1, 0, 6));
        arriba.add(paso); arriba.add(barra); arriba.add(detalle);
        p.add(arriba, BorderLayout.NORTH);
        p.add(new JScrollPane(resumen), BorderLayout.CENTER);
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bot.add(cerrar);
        p.add(bot, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(app);
        d.setVisible(true);
        Thread t = new Thread(() -> {
            for (int i = 0; i < pasos.size(); i++) {
                final int n = i;
                SwingUtilities.invokeLater(() -> { paso.setText("Paso " + (n + 1) + " de " + pasos.size() + ": " + nombresPasos[n]); barra.setIndeterminate(true); });
                String r;
                try {
                    r = pasos.get(i).apply(txt -> SwingUtilities.invokeLater(() -> detalle.setText(txt)));
                } catch (Exception ex) {
                    r = "Error: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                }
                final String res = r;
                SwingUtilities.invokeLater(() -> {
                    barra.setIndeterminate(false);
                    barra.setValue((n + 1) * 100);
                    resumen.append("• " + nombresPasos[n] + ": " + res + "\n");
                });
            }
            SwingUtilities.invokeLater(() -> {
                paso.setText("Sincronización terminada.");
                detalle.setText(" ");
                cerrar.setEnabled(true);
                if (alTerminar != null) alTerminar.run();
            });
        }, "SyncConVentana");
        t.setDaemon(true);
        t.start();
    }

    /** Archivo → Sincronizar ordenadores: contactos, actividades, QSL recibidas y diplomas recibidos, con ventana de progreso. */
    void sincronizarOrdenadoresConVentana() {
        String op = app.operadorActual == null ? "" : app.operadorActual.trim();
        java.util.List<java.util.function.Function<java.util.function.Consumer<String>, String>> pasos = new ArrayList<>();
        pasos.add(av -> {
            av.accept("Enviando y recibiendo contactos y actividades…");
            sincronizarMisOrdenadores();
            return "hecho";
        });
        pasos.add(av -> {
            try {
                int[] r = sincronizarArchivosMisOrdenadores(op, av);
                return r[0] + " enviados, " + r[1] + " recibidos";
            } catch (Exception ex) { throw new RuntimeException(ex.getMessage() == null ? ex.toString() : ex.getMessage(), ex); }
        });
        ventanaProgreso("Sincronizar ordenadores · " + op, new String[]{"Contactos y actividades", "QSL y diplomas recibidos"}, pasos, () -> {
            refrescarTrasSincronizar();
        });
    }

    /** Archivo → Sincronizar Datos del Radioclub: todo lo del radioclub (datos, socios, actividades, contactos…), con ventana de progreso. */
    void sincronizarRadioclubConVentana() {
        java.util.List<java.util.function.Function<java.util.function.Consumer<String>, String>> pasos = new ArrayList<>();
        pasos.add(av -> {
            av.accept("Datos del radioclub, socios, actividades, contactos y directorio CB…");
            sincronizarRadioclub();
            return "hecho";
        });
        ventanaProgreso("Sincronizar Datos del Radioclub", new String[]{"Todos los datos del radioclub"}, pasos, () -> {
            refrescarTrasSincronizar();
            SwingUtilities.invokeLater(this::elegirActividadesRadioclub);
        });
    }

    // ================================================================ actividades del radioclub que se ven en este PC
    // Todas las actividades del radioclub siguen llegando y guardándose (así la sincronización no cambia y no se pierde nada);
    // en este PC solo se MUESTRAN las que el operador marca. En el ordenador del radioclub se ven siempre todas.

    private static final String CFG_ACT_VISIBLES = "RC_ACT_VISIBLES", CFG_ACT_INI = "RC_ACT_VISIBLES_INI";

    private java.util.Set<String> actividadesRcVisibles() {
        java.util.Set<String> r = new java.util.HashSet<>();
        for (String u : LibroGuardiaUI.ConfigApp.get(CFG_ACT_VISIBLES, "").split(",")) if (!u.isBlank()) r.add(u.trim());
        return r;
    }

    /** La primera vez (al actualizar a esta versión) se siguen viendo todas las que ya había. */
    private void inicializarActividadesRcVisibles() {
        if ("1".equals(LibroGuardiaUI.ConfigApp.get(CFG_ACT_INI, ""))) return;
        java.util.Set<String> r = new java.util.LinkedHashSet<>();
        try (Connection c = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid FROM actividades WHERE COALESCE(radioclub,0) = 1 AND COALESCE(uuid,'') <> ''")) {
            while (rs.next()) r.add(rs.getString(1));
        } catch (Exception e) {
            return;
        }
        LibroGuardiaUI.ConfigApp.put(CFG_ACT_VISIBLES, String.join(",", r));
        LibroGuardiaUI.ConfigApp.put(CFG_ACT_INI, "1");
    }

    /** ¿Se muestra en este PC esta actividad? (las propias, siempre). */
    boolean actividadVisible(int radioclub, String uuid) {
        if (radioclub != 1 || uuid == null || uuid.isBlank() || esOrdenadorRadioclub()) return true;
        inicializarActividadesRcVisibles();
        return actividadesRcVisibles().contains(uuid);
    }

    /** Quita de la vista de este PC una actividad del radioclub (no se borra de ningún sitio). */
    void ocultarActividadRc(String uuid) {
        inicializarActividadesRcVisibles();
        java.util.Set<String> vis = actividadesRcVisibles();
        vis.remove(uuid);
        LibroGuardiaUI.ConfigApp.put(CFG_ACT_VISIBLES, String.join(",", vis));
    }

    boolean soyOrdenadorRadioclub() { return esOrdenadorRadioclub(); }

    /** Al estrenar esta versión: si hay radioclub, se explica y se abre una vez la ventana para elegir actividades. */
    void preguntarActividadesTrasActualizar() {
        if ("1".equals(LibroGuardiaUI.ConfigApp.get("RC_ACT_PREGUNTADO", ""))) return;
        if (!radioclubConfigurado() || esOrdenadorRadioclub()) { LibroGuardiaUI.ConfigApp.put("RC_ACT_PREGUNTADO", "1"); return; }
        int n = 0;
        try (Connection c = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM actividades WHERE COALESCE(radioclub,0) = 1 AND COALESCE(uuid,'') <> ''")) {
            if (rs.next()) n = rs.getInt(1);
        } catch (Exception e) {
            return;
        }
        LibroGuardiaUI.ConfigApp.put("RC_ACT_PREGUNTADO", "1");
        if (n == 0) return;
        JOptionPane.showMessageDialog(app, "Novedad: ahora eliges qué actividades del radioclub quieres ver en este ordenador.\n\n"
                        + "Las que desmarques desaparecen solo de aquí: en el ordenador del radioclub siguen\n"
                        + "intactas y puedes volver a marcarlas cuando quieras (Archivo → Sincronizar Datos del Radioclub).\n\n"
                        + "No hace falta borrar ninguna actividad del radioclub: basta con desmarcarla.",
                "Actividades del radioclub", JOptionPane.INFORMATION_MESSAGE);
        elegirActividadesRadioclub();
    }

    /** Ventana para marcar qué actividades del radioclub se ven en este PC. */
    void elegirActividadesRadioclub() {
        if (esOrdenadorRadioclub()) return;
        inicializarActividadesRcVisibles();
        java.util.List<Object[]> filas = new ArrayList<>();
        try (Connection c = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT a.uuid, a.numeral, a.nombre, (SELECT COUNT(*) FROM qsos q WHERE q.actividad_numeral = a.numeral) "
                     + "FROM actividades a WHERE COALESCE(a.radioclub,0) = 1 AND COALESCE(a.uuid,'') <> '' ORDER BY a.numeral")) {
            java.util.Set<String> vis = actividadesRcVisibles();
            while (rs.next())
                filas.add(new Object[]{vis.contains(rs.getString(1)), rs.getInt(2) + " - " + app.nombreVisibleDeActividad(rs.getString(3)), rs.getInt(4), rs.getString(1)});
        } catch (Exception e) {
            return;
        }
        if (filas.isEmpty()) return;
        javax.swing.table.DefaultTableModel m = new javax.swing.table.DefaultTableModel(new Object[]{"Ver en este PC", "Actividad del radioclub", "Contactos"}, 0) {
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : c == 2 ? Integer.class : String.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        };
        for (Object[] f : filas) m.addRow(new Object[]{f[0], f[1], f[2]});
        JTable t = new JTable(m);
        t.setRowHeight(24);
        t.getColumnModel().getColumn(0).setMaxWidth(130); t.getColumnModel().getColumn(0).setPreferredWidth(130);
        t.getColumnModel().getColumn(2).setMaxWidth(90);
        JDialog d = new JDialog(app, "Actividades del radioclub en este ordenador", Dialog.ModalityType.APPLICATION_MODAL);
        JLabel ayuda = new JLabel("<html>Marca las actividades del radioclub que quieres ver en este ordenador.<br>"
                + "Las desmarcadas desaparecen de aquí, pero siguen en el ordenador del radioclub y puedes volver a marcarlas cuando quieras.<br>"
                + "Las actividades nuevas del radioclub llegan desmarcadas.</html>");
        ayuda.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        JButton bTodas = new JButton("Marcar todas"), bNinguna = new JButton("Desmarcar todas"), bOk = new JButton("Aceptar"), bCancel = new JButton("Cancelar");
        bTodas.addActionListener(e -> { for (int i = 0; i < m.getRowCount(); i++) m.setValueAt(true, i, 0); });
        bNinguna.addActionListener(e -> { for (int i = 0; i < m.getRowCount(); i++) m.setValueAt(false, i, 0); });
        bCancel.addActionListener(e -> d.dispose());
        bOk.addActionListener(e -> {
            if (t.isEditing()) t.getCellEditor().stopCellEditing();
            java.util.Set<String> vis = new java.util.LinkedHashSet<>();
            for (int i = 0; i < m.getRowCount(); i++) if (Boolean.TRUE.equals(m.getValueAt(i, 0))) vis.add((String) filas.get(i)[3]);
            LibroGuardiaUI.ConfigApp.put(CFG_ACT_VISIBLES, String.join(",", vis));
            d.dispose();
            refrescarTrasSincronizar();
        });
        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        botones.add(bTodas); botones.add(bNinguna); botones.add(Box.createHorizontalStrut(20)); botones.add(bCancel); botones.add(bOk);
        d.add(ayuda, BorderLayout.NORTH);
        d.add(new JScrollPane(t), BorderLayout.CENTER);
        d.add(botones, BorderLayout.SOUTH);
        d.setSize(620, Math.min(520, 200 + filas.size() * 24));
        d.setLocationRelativeTo(app);
        d.setVisible(true);
    }

    private final java.util.concurrent.atomic.AtomicBoolean syncOpEnCola = new java.util.concurrent.atomic.AtomicBoolean(false);
    JButton btnSincronizarOp;

    /** Cuánto lleva la sincronización que está en marcha (0 a 100), para la ventana del arranque. */
    volatile int progresoSync = 0;

    /** Al arrancar: primero el radioclub y luego mis ordenadores (una detrás de otra), con una ventanita y una barra ámbar. */
    void sincronizarAlArrancar() {
        boolean rc = syncActivo(), op = syncOpActivo();
        if (!rc && !op) return;
        if (!app.cuentaB26.sincronizarPermitido()) {   // sin cuenta: como antes, solo se avisa en la barra de abajo
            if (rc) sincronizarPronto();
            return;
        }
        JDialog d = new JDialog(app, "Bitácora 26", Dialog.ModalityType.MODELESS);
        d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JLabel texto = new JLabel("Preparando la sincronización…");
        texto.setFont(texto.getFont().deriveFont(Font.BOLD, 14f));
        texto.setForeground(LibroGuardiaUI.TEMA_AMBAR);
        JLabel detalle = new JLabel("Puedes seguir usando el programa mientras tanto.");
        detalle.setForeground(LibroGuardiaUI.TEMA_TEXTO);
        final int[] pct = {0};
        JComponent barra = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g.setColor(LibroGuardiaUI.TEMA_CAMPO);
                g.fillRoundRect(0, 0, w, h, 10, 10);
                g.setColor(LibroGuardiaUI.TEMA_AMBAR);
                g.fillRoundRect(0, 0, (int) (w * pct[0] / 100.0), h, 10, 10);
                g.setColor(LibroGuardiaUI.TEMA_LINEA);
                g.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);
                String t = pct[0] + " %";
                g.setFont(getFont().deriveFont(Font.BOLD, 13f));
                FontMetrics fm = g.getFontMetrics();
                g.setColor(pct[0] >= 50 ? new Color(30, 30, 30) : LibroGuardiaUI.TEMA_TEXTO);
                g.drawString(t, (w - fm.stringWidth(t)) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
                g.dispose();
            }
        };
        barra.setPreferredSize(new Dimension(420, 26));
        JPanel p = new JPanel(new GridLayout(3, 1, 0, 8));
        p.setBackground(LibroGuardiaUI.TEMA_FONDO);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LibroGuardiaUI.TEMA_LINEA),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)));
        p.add(texto); p.add(barra); p.add(detalle);
        d.setContentPane(p);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(app);
        javax.swing.Timer reloj = new javax.swing.Timer(150, e -> { pct[0] = Math.max(0, Math.min(100, progresoSync)); barra.repaint(); });
        d.setVisible(true);
        reloj.start();
        Thread t = new Thread(() -> {
            try {
                if (rc && syncEnCola.compareAndSet(false, true)) {
                    progresoSync = 0;
                    SwingUtilities.invokeLater(() -> texto.setText("Sincronizando datos con el radioclub…"));
                    try { sincronizarRadioclub(); } finally { syncEnCola.set(false); }
                    progresoSync = 100;
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                }
                if (op && syncOpEnCola.compareAndSet(false, true)) {
                    progresoSync = 0;
                    SwingUtilities.invokeLater(() -> {
                        texto.setText("Sincronizando mis ordenadores…");
                        if (btnSincronizarOp != null) { btnSincronizarOp.setEnabled(false); btnSincronizarOp.setText("Sincronizando…"); }
                    });
                    actualizarEstadoSyncOp("Mis ordenadores: sincronizando…", app.TEMA_TEXTO);
                    try { sincronizarMisOrdenadores(); } finally {
                        syncOpEnCola.set(false);
                        SwingUtilities.invokeLater(() -> {
                            if (btnSincronizarOp != null) { btnSincronizarOp.setEnabled(true); btnSincronizarOp.setText("Sincronizar"); }
                        });
                    }
                    progresoSync = 100;
                    try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                }
            } finally {
                SwingUtilities.invokeLater(() -> { reloj.stop(); d.dispose(); });
            }
        }, "SyncAlArrancar");
        t.setDaemon(true);
        t.start();
    }

    /** Sincroniza mis ordenadores en segundo plano (al arrancar, con el botón o al activarlo). */
    void sincronizarMisOrdenadoresEnSegundoPlano() {
        if (!syncOpActivo() || !syncOpEnCola.compareAndSet(false, true)) return;
        if (btnSincronizarOp != null) { btnSincronizarOp.setEnabled(false); btnSincronizarOp.setText("Sincronizando…"); }
        actualizarEstadoSyncOp("Mis ordenadores: sincronizando…", app.TEMA_TEXTO);
        Thread t = new Thread(() -> {
            try { sincronizarMisOrdenadores(); } finally {
                syncOpEnCola.set(false);
                SwingUtilities.invokeLater(() -> {
                    if (btnSincronizarOp != null) { btnSincronizarOp.setEnabled(true); btnSincronizarOp.setText("Sincronizar"); }
                });
            }
        }, "SyncMisOrdenadores");
        t.setDaemon(true);
        t.start();
    }

    /** Sincroniza el log del operador actual con sus otros ordenadores (si lo tiene activado). */
    synchronized void sincronizarMisOrdenadores() {
        String op = app.operadorActual;
        if (!syncOpActivo(op)) return;
        if (!app.cuentaB26.sincronizarPermitido()) return;   // sin cuenta (pasados los días de margen) no se sincroniza
        if (!syncOpPorFtp(op)) {
            sincronizarMisOrdenadoresEnCarpeta(op, new File(carpetaBaseSyncOp(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "CARPETA"), ""), op)));
            return;
        }
        try (LibroGuardiaUI.FtpSimple ftp = abrirFtpOp(op)) {
            File espejo = carpetaEspejoFtpOp(op);
            File dir = new File(espejo, nombreCarpetaSyncOp(op));
            traerDelFtpA(ftp, dir);
            if (sincronizarMisOrdenadoresEnCarpeta(op, espejo)) llevarAlFtpDesde(ftp, dir);
        } catch (Exception ex) {
            actualizarEstadoSyncOp("Mis ordenadores (FTP): " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), new Color(255, 110, 90));
        }
    }

    /** Lo que hay ahora en este ordenador del operador: tipo -> (uuid -> datos en texto). Primero las actividades. */
    static Map<String, Map<String, String>> estadoMisOrdenadores(Connection conn, String op) throws Exception {
        Map<String, Map<String, String>> r = new LinkedHashMap<>();
        r.put("ACT", new LinkedHashMap<>());
        r.put("QSO", new LinkedHashMap<>());
        Map<String, String> puntos = new HashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT actividad, tipo, valor, puntos FROM actividad_puntos ORDER BY actividad, tipo, valor")) {
            while (rs.next()) puntos.merge(rs.getString(1), rs.getString(2) + "\t" + rs.getString(3) + "\t" + rs.getInt(4) + "\n", String::concat);
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid, nombre, tipo, operador_creador, umbral_bronce, umbral_plata, umbral_oro, cat6_nombre, cat6_umbral, "
                + "fecha_creacion, banda_simple, modo_simple, indicativo_rc FROM actividades WHERE COALESCE(radioclub,0) = 0 AND COALESCE(uuid,'') <> '' "
                + "AND (operador_creador = ? OR COALESCE(operador_creador,'') = '')")) {
            ps.setString(1, op);
            try (ResultSet rs = ps.executeQuery()) {
                String[] n = {"tipo", "creador", "bronce", "plata", "oro", "cat6", "cat6u", "fecha", "banda", "modo", "indrc"};
                while (rs.next()) {
                    java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                    m.put("nombre", quitarNumeral(rs.getString(2)));
                    for (int i = 0; i < n.length; i++) m.put(n[i], rs.getString(i + 3) == null ? "" : rs.getString(i + 3));
                    m.put("puntos", puntos.getOrDefault(rs.getString(2), ""));
                    r.get("ACT").put(rs.getString(1), codificar(m));
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT q.uuid, q.fecha, q.hora, q.indicativo, q.banda, q.modo, q.freq_tx, q.freq_rx, q.rst_sent, q.rst_rcvd, "
                + "q.nombre, q.qth, q.grid, q.puntos, q.num_contactos, q.estacion, COALESCE(a.uuid, ''), COALESCE(q.subido, 0), COALESCE(q.comentario, '') "
                + "FROM qsos q LEFT JOIN actividades a ON q.actividad_numeral > 0 AND a.numeral = q.actividad_numeral "
                + "WHERE q.operador = ? AND COALESCE(q.radioclub,0) = 0 AND COALESCE(q.uuid,'') <> ''")) {
            ps.setString(1, op);
            try (ResultSet rs = ps.executeQuery()) {
                String[] n = {"fecha", "hora", "ind", "banda", "modo", "ftx", "frx", "rsts", "rstr", "nom", "qth", "grid", "puntos", "num", "est", "act", "sub"};
                while (rs.next()) {
                    java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
                    for (int i = 0; i < n.length; i++) m.put(n[i], rs.getString(i + 2) == null ? "" : rs.getString(i + 2));
                    String com = rs.getString(n.length + 2);
                    if (com != null && !com.isEmpty()) m.put("com", com);   // comentario, solo si lo hay
                    r.get("QSO").put(rs.getString(1), codificar(m));
                }
            }
        }
        return r;
    }

    static Long versionOp(Connection conn, String op, String tipo, String clave) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT f FROM sync_op_ver WHERE op = ? AND tipo = ? AND clave = ?")) {
            ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, clave);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        }
    }

    private static void guardarVersionOp(Connection conn, String op, String tipo, String clave, long f) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO sync_op_ver (op, tipo, clave, f) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, clave); ps.setLong(4, f); ps.executeUpdate();
        }
    }

    private static void guardarPublicadoOp(Connection conn, String op, String tipo, String clave, String datos) throws Exception {
        if (datos == null) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_op_pub WHERE op = ? AND tipo = ? AND clave = ?")) {
                ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, clave); ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO sync_op_pub (op, tipo, clave, datos) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, clave); ps.setString(4, datos); ps.executeUpdate();
        }
    }

    /** Contacto o actividad que ya estaba en los dos ordenadores (por ejemplo, pasado antes con un ADI): uuid de aquí. */
    private static String aliasOp(Connection conn, String op, String tipo, String remoto) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT local FROM sync_op_alias WHERE op = ? AND tipo = ? AND remoto = ?")) {
            ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, remoto);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static void guardarAliasOp(Connection conn, String op, String tipo, String remoto, String local) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO sync_op_alias (op, tipo, remoto, local) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, remoto); ps.setString(4, local); ps.executeUpdate();
        }
    }

    /** Uuid de una actividad de otro ordenador en este (puede ser la misma o una que ya estaba aquí). */
    private static String actividadLocalOp(Connection conn, String op, String remoto) throws Exception {
        if (remoto == null || remoto.isEmpty()) return "";
        String a = aliasOp(conn, op, "ACT", remoto);
        return a != null ? a : remoto;
    }

    /**
     * Decide a qué fila de aquí corresponde lo recibido. Devuelve su uuid, o null si no se debe tocar
     * (por ejemplo, porque aquí es un contacto del radioclub o de otro operador).
     */
    private static String resolverLocalOp(Connection conn, String op, String tipo, String remoto, boolean borrar,
                                          java.util.SortedMap<String, String> x) throws Exception {
        String alias = aliasOp(conn, op, tipo, remoto);
        if (alias != null) return alias;
        if (tipo.equals("QSO")) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(operador,''), COALESCE(radioclub,0) FROM qsos WHERE uuid = ?")) {
                ps.setString(1, remoto);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1).equals(op) && rs.getInt(2) == 0 ? remoto : null;
                }
            }
            if (borrar) return remoto;
            // ¿Ya estaba aquí el mismo contacto (misma fecha, hora, indicativo, banda y modo)?
            try (PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM qsos WHERE operador = ? AND COALESCE(radioclub,0) = 0 "
                    + "AND COALESCE(uuid,'') <> '' AND fecha = ? AND substr(COALESCE(hora,''), 1, 5) = substr(?, 1, 5) "
                    + "AND upper(COALESCE(indicativo,'')) = upper(?) AND upper(COALESCE(banda,'')) = upper(?) AND upper(COALESCE(modo,'')) = upper(?) "
                    + "AND uuid NOT IN (SELECT local FROM sync_op_alias WHERE op = ? AND tipo = 'QSO') LIMIT 1")) {
                ps.setString(1, op); ps.setString(2, x.getOrDefault("fecha", "")); ps.setString(3, x.getOrDefault("hora", ""));
                ps.setString(4, x.getOrDefault("ind", "")); ps.setString(5, x.getOrDefault("banda", "")); ps.setString(6, x.getOrDefault("modo", ""));
                ps.setString(7, op);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        guardarAliasOp(conn, op, tipo, remoto, rs.getString(1));
                        return rs.getString(1);
                    }
                }
            }
            return remoto;
        }
        // ACT
        try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(radioclub,0) FROM actividades WHERE uuid = ?")) {
            ps.setString(1, remoto);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) == 0 ? remoto : null;
            }
        }
        if (borrar) return remoto;
        // ¿Ya estaba aquí una actividad con el mismo nombre y tipo?
        String nombre = x.getOrDefault("nombre", "");
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid, nombre FROM actividades WHERE COALESCE(radioclub,0) = 0 AND COALESCE(uuid,'') <> '' "
                + "AND COALESCE(tipo,'SIMPLE') = ? AND uuid NOT IN (SELECT local FROM sync_op_alias WHERE op = ? AND tipo = 'ACT')")) {
            ps.setString(1, x.getOrDefault("tipo", "SIMPLE").isEmpty() ? "SIMPLE" : x.getOrDefault("tipo", "SIMPLE"));
            ps.setString(2, op);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (quitarNumeral(rs.getString(2)).equalsIgnoreCase(nombre)) {
                        guardarAliasOp(conn, op, tipo, remoto, rs.getString(1));
                        return rs.getString(1);
                    }
                }
            }
        }
        return remoto;
    }

    /** Deja aquí un contacto recibido de otro ordenador del operador. */
    private static void aplicarQsoOp(Connection conn, String op, String uuid, boolean borrar, java.util.SortedMap<String, String> x) throws Exception {
        if (borrar) {
            guardarEnPapelera(conn, uuid, "Otro ordenador mío");
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qsos WHERE uuid = ? AND operador = ? AND COALESCE(radioclub,0) = 0")) {
                ps.setString(1, uuid); ps.setString(2, op); ps.executeUpdate();
            }
            return;
        }
        String actUuid = actividadLocalOp(conn, op, x.getOrDefault("act", ""));
        int numeral = 0;
        String actNombre = "";
        if (!actUuid.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT numeral, nombre FROM actividades WHERE uuid = ?")) {
                ps.setString(1, actUuid);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { numeral = rs.getInt(1); actNombre = rs.getString(2); } }
            }
        }
        boolean existe;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM qsos WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) { existe = rs.next(); }
        }
        String[] n = {"fecha", "hora", "ind", "banda", "modo", "ftx", "frx", "rsts", "rstr", "nom", "qth", "grid", "puntos", "num", "est"};
        String sql = existe
                ? "UPDATE qsos SET fecha = ?, hora = ?, indicativo = ?, banda = ?, modo = ?, freq_tx = ?, freq_rx = ?, rst_sent = ?, rst_rcvd = ?, "
                + "nombre = ?, qth = ?, grid = ?, puntos = ?, num_contactos = ?, estacion = ?, act_uuid = ?, actividad_numeral = ?, actividad = ?, subido = ?, "
                + "operador = ? WHERE uuid = ?"
                : "INSERT INTO qsos (fecha, hora, indicativo, banda, modo, freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, puntos, num_contactos, "
                + "estacion, act_uuid, actividad_numeral, actividad, subido, operador, radioclub, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < n.length; i++) {
                String v = x.getOrDefault(n[i], "");
                if (n[i].equals("puntos") || n[i].equals("num")) {
                    int num;
                    try { num = v.isEmpty() ? (n[i].equals("num") ? 1 : 0) : Integer.parseInt(v.trim()); } catch (NumberFormatException e) { num = 0; }
                    ps.setInt(i + 1, num);
                } else ps.setString(i + 1, v);
            }
            ps.setString(16, numeral > 0 ? "" : actUuid);   // solo si su actividad aún no ha llegado
            ps.setInt(17, numeral);
            ps.setString(18, numeral > 0 ? actNombre : "");
            ps.setInt(19, "1".equals(x.getOrDefault("sub", "0")) ? 1 : 0);
            ps.setString(20, op);
            ps.setString(21, uuid);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET comentario = ? WHERE uuid = ?")) {
            ps.setString(1, x.getOrDefault("com", "")); ps.setString(2, uuid); ps.executeUpdate();
        }
    }

    /** Deja aquí una actividad recibida de otro ordenador del operador (como actividad propia, no del radioclub). */
    private static void aplicarActividadOp(Connection conn, String uuid, boolean borrar, java.util.SortedMap<String, String> x) throws Exception {
        int numeral = -1;
        String nombreAnt = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT numeral, nombre FROM actividades WHERE uuid = ? AND COALESCE(radioclub,0) = 0")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { numeral = rs.getInt(1); nombreAnt = rs.getString(2); } }
        }
        if (borrar) {
            if (nombreAnt == null) return;
            try (PreparedStatement p1 = conn.prepareStatement("DELETE FROM actividad_puntos WHERE actividad = ?");
                 PreparedStatement p2 = conn.prepareStatement("DELETE FROM actividades WHERE uuid = ?")) {
                p1.setString(1, nombreAnt); p1.executeUpdate(); p2.setString(1, uuid); p2.executeUpdate();
            }
            LibroGuardiaUI.soltarContactosHuerfanos(conn, numeral);
            return;
        }
        if (nombreAnt == null) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(numeral), 0) + 1 FROM actividades")) {
                numeral = rs.next() ? rs.getInt(1) : 1;
            }
        }
        String nombre = x.getOrDefault("nombre", "ACTIVIDAD") + " #" + numeral;
        String[] campos = {"tipo", "creador", "bronce", "plata", "oro", "cat6", "cat6u", "fecha", "banda", "modo", "indrc"};
        if (nombreAnt == null) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO actividades (nombre, tipo, operador_creador, umbral_bronce, umbral_plata, umbral_oro, "
                    + "cat6_nombre, cat6_umbral, fecha_creacion, banda_simple, modo_simple, indicativo_rc, numeral, radioclub, uuid) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)")) {
                ps.setString(1, nombre);
                for (int i = 0; i < campos.length; i++) ps.setString(i + 2, x.getOrDefault(campos[i], ""));
                ps.setInt(13, numeral);
                ps.setString(14, uuid);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE actividades SET nombre = ?, tipo = ?, operador_creador = ?, umbral_bronce = ?, umbral_plata = ?, "
                    + "umbral_oro = ?, cat6_nombre = ?, cat6_umbral = ?, fecha_creacion = ?, banda_simple = ?, modo_simple = ?, indicativo_rc = ? WHERE uuid = ?")) {
                ps.setString(1, nombre);
                for (int i = 0; i < campos.length; i++) ps.setString(i + 2, x.getOrDefault(campos[i], ""));
                ps.setString(13, uuid);
                ps.executeUpdate();
            }
            if (!nombre.equals(nombreAnt)) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET actividad = ? WHERE actividad_numeral = ?")) {
                    ps.setString(1, nombre); ps.setInt(2, numeral); ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM actividad_puntos WHERE actividad = ?")) { ps.setString(1, nombreAnt); ps.executeUpdate(); }
        }
        String puntos = x.getOrDefault("puntos", "");
        if (!puntos.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO actividad_puntos (actividad, tipo, valor, puntos) VALUES (?, ?, ?, ?)")) {
                for (String fila : puntos.split("\n")) {
                    String[] c = fila.split("\t");
                    if (c.length < 3) continue;
                    ps.setString(1, nombre); ps.setString(2, c[0]); ps.setString(3, c[1]); ps.setInt(4, Integer.parseInt(c[2].trim()));
                    ps.executeUpdate();
                }
            }
        }
    }

    /** Aplica una línea recibida. Devuelve el uuid tocado aquí ("tipo|uuid"), o null si no se ha aplicado. */
    static String aplicarLineaOp(Connection conn, String op, String linea, String miMarca) {
        try {
            java.util.SortedMap<String, String> m = decodificar(linea);
            if (!op.equals(m.getOrDefault("op", ""))) return null;          // de otro operador
            if (miMarca.equals(m.getOrDefault("dev", ""))) return null;     // es mía
            String tipo = m.getOrDefault("t", ""), remoto = m.getOrDefault("k", ""), d = m.getOrDefault("d", "");
            if (!tipo.equals("QSO") && !tipo.equals("ACT") || remoto.isEmpty()) return null;
            boolean borrar = "1".equals(m.get("del"));
            long f = Long.parseLong(m.getOrDefault("f", "0"));
            java.util.SortedMap<String, String> x = decodificar(d);
            String local = resolverLocalOp(conn, op, tipo, remoto, borrar, x);
            if (local == null) return null;
            Long ver = versionOp(conn, op, tipo, local);
            if (ver != null && ver > f) return null;   // aquí hay algo más nuevo
            if (tipo.equals("QSO")) aplicarQsoOp(conn, op, local, borrar, x);
            else aplicarActividadOp(conn, local, borrar, x);
            guardarVersionOp(conn, op, tipo, local, f);
            return tipo + "|" + local;
        } catch (Exception ex) {
            System.err.println("Mis ordenadores: línea no aplicada: " + ex.getMessage());
            return null;
        }
    }

    // ================================================================ buzón con el móvil (bitacora26.es)
    // El móvil no puede usar la carpeta de Google Drive ni el FTP: lo que se cruza con él pasa por un buzón del servidor,
    // con las mismas líneas que se escriben en la carpeta. El servidor lo borra en cuanto se recoge (o a los 30 días).

    /** Cuenta de Bitácora 26 de este operador en este ordenador ("" si no tiene). */
    private static String cuentaBuzon(String op) {
        return CuentaBitacora.cuentaDeIndicativo(CuentaBitacora.baseIndicativo(op), null);
    }

    static String comprimirB64(String s) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); }
        return java.util.Base64.getEncoder().encodeToString(bo.toByteArray());
    }

    static String descomprimirB64(String b64) throws Exception {
        try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(b64)))) {
            return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Recoge lo que han dejado los móviles. info[0] = móviles de la cuenta, info[1] = móviles que piden el LOG entero. */
    private List<String> buzonRecoger(String cuenta, List<Long> ids, int[] info) {
        List<String> lineas = new ArrayList<>();
        for (int vuelta = 0; vuelta < 10; vuelta++) {
            String[] r = app.cuentaB26.firmadoB26(cuenta, "buzon_recoger", "{}");
            if (!"200".equals(r[0])) return lineas;
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\"moviles\"\\s*:\\s*(\\d+)").matcher(r[1]);
            if (mm.find()) info[0] = Integer.parseInt(mm.group(1));
            mm = java.util.regex.Pattern.compile("\"piden_completo\"\\s*:\\s*(\\d+)").matcher(r[1]);
            if (mm.find()) info[1] = Integer.parseInt(mm.group(1));
            java.util.regex.Matcher ml = java.util.regex.Pattern.compile("\"lotes\"\\s*:\\s*\\[(.*?)\\]", java.util.regex.Pattern.DOTALL).matcher(r[1]);
            java.util.regex.Matcher mi = java.util.regex.Pattern.compile("\"ids\"\\s*:\\s*\\[(.*?)\\]").matcher(r[1]);
            if (!ml.find() || !mi.find()) return lineas;
            List<Long> estos = new ArrayList<>();
            java.util.regex.Matcher n = java.util.regex.Pattern.compile("\\d+").matcher(mi.group(1));
            while (n.find()) estos.add(Long.parseLong(n.group()));
            java.util.regex.Matcher s = java.util.regex.Pattern.compile("\"([A-Za-z0-9+/=]*)\"").matcher(ml.group(1));
            while (s.find()) {
                try {
                    for (String l : descomprimirB64(s.group(1)).split("\n")) if (!l.isBlank()) lineas.add(l);
                } catch (Exception ignored) { }
            }
            ids.addAll(estos);
            java.util.regex.Matcher q = java.util.regex.Pattern.compile("\"quedan\"\\s*:\\s*(\\d+)").matcher(r[1]);
            if (estos.isEmpty() || !q.find() || Integer.parseInt(q.group(1)) <= 0) break;
            buzonBorrar(cuenta, estos);   // para que la siguiente vuelta traiga los siguientes
            ids.removeAll(estos);
        }
        return lineas;
    }

    private void buzonBorrar(String cuenta, List<Long> ids) {
        for (int i = 0; i < ids.size(); i += 50) {
            StringBuilder sb = new StringBuilder("{\"ids\":[");
            for (int k = i; k < Math.min(ids.size(), i + 50); k++) sb.append(k > i ? "," : "").append(ids.get(k));
            app.cuentaB26.firmadoB26(cuenta, "buzon_borrar", sb.append("]}").toString());
        }
    }

    private boolean buzonEnviar(String cuenta, String texto, String a, boolean ultimo) {
        try {
            String datos = "{\"lote\":\"" + comprimirB64(texto) + "\"" + (a.isEmpty() ? "" : ",\"a\":\"" + a + "\",\"ultimo\":" + ultimo) + "}";
            return "200".equals(app.cuentaB26.firmadoB26(cuenta, "buzon_enviar", datos)[0]);
        } catch (Exception e) {
            return false;
        }
    }

    /** Manda al buzón lo que esté pendiente (en orden). Lo que no se puede mandar se queda para la próxima vez. */
    private void buzonMandarPendientes(Connection conn, String op, String cuenta) throws Exception {
        List<Object[]> pend = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, texto FROM sync_buzon_pend WHERE op = ? ORDER BY id")) {
            ps.setString(1, op);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) pend.add(new Object[]{rs.getLong(1), rs.getString(2)}); }
        }
        for (Object[] p : pend) {
            if (!buzonEnviar(cuenta, (String) p[1], "", false)) return;
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_buzon_pend WHERE id = ?")) { ps.setLong(1, (Long) p[0]); ps.executeUpdate(); }
        }
    }

    /** Un móvil nuevo ha pedido el LOG entero: se le manda todo lo de este operador, en trozos. */
    private void buzonMandarCompleto(Connection conn, String op, String cuenta, String miMarca) throws Exception {
        Map<String, Map<String, String>> todo = estadoMisOrdenadores(conn, op);
        List<String> lineas = new ArrayList<>();
        long f = System.currentTimeMillis();
        for (Map.Entry<String, Map<String, String>> t : todo.entrySet())   // primero las actividades, luego los contactos
            for (Map.Entry<String, String> e : t.getValue().entrySet()) {
                Long ver = versionOp(conn, op, t.getKey(), e.getKey());
                java.util.SortedMap<String, String> linea = new java.util.TreeMap<>();
                linea.put("v", "1"); linea.put("t", t.getKey()); linea.put("k", e.getKey()); linea.put("f", String.valueOf(ver == null ? f : ver));
                linea.put("del", "0"); linea.put("d", e.getValue()); linea.put("dev", miMarca); linea.put("op", op);
                lineas.add(codificar(linea));
            }
        int trozo = 3000;
        for (int i = 0; i < lineas.size() || i == 0; i += trozo) {
            boolean ultimo = i + trozo >= lineas.size();
            String texto = String.join("\n", lineas.subList(Math.min(i, lineas.size()), Math.min(lineas.size(), i + trozo))) + "\n";
            if (!buzonEnviar(cuenta, texto, "PIDEN", ultimo)) return;
            if (ultimo) break;
        }
    }

    /** Sincroniza con la carpeta del operador (la de la nube, o la copia del FTP). Devuelve true si ha ido bien. */
    private boolean sincronizarMisOrdenadoresEnCarpeta(String op, File base) {
        File dir = new File(base, nombreCarpetaSyncOp(op));
        String problema = prepararCarpetaSyncOp(base, dir);
        if (problema != null) {
            actualizarEstadoSyncOp("Mis ordenadores: " + problema, new Color(255, 110, 90));
            return false;
        }
        int enviados = 0, recibidos = 0, recibidosBz = 0;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            String miFichero = miFicheroSync();
            String miMarca = miFichero.substring(5, 21);
            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE actividades SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid = ''");
                st.execute("UPDATE qsos SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid = ''");
            }
            // ---------- 0. BUZÓN del móvil: ver qué ha dejado (si este operador tiene cuenta de Bitácora 26) ----------
            String cuentaBz = "";
            int[] infoBz = {0, 0};
            List<Long> idsBz = new ArrayList<>();
            List<String> lineasBz = new ArrayList<>();
            try {
                cuentaBz = cuentaBuzon(op);
                if (!cuentaBz.isEmpty()) lineasBz = buzonRecoger(cuentaBz, idsBz, infoBz);
            } catch (Exception ignored) { }

            // ---------- 1. ENVIAR lo que ha cambiado aquí ----------
            progresoSync = 10;
            // Si hay un ordenador mío que aún no ha recibido todo lo de este (uno nuevo, o que empezó a sincronizar
            // después), se le manda TODO otra vez (con su fecha de siempre, así no pisa nada más nuevo)
            List<String> otrosAhora = new ArrayList<>();
            File[] otrosFich = dir.listFiles((x, n) -> n.startsWith("disp_") && n.endsWith(".txt") && !n.equals(miFichero));
            if (otrosFich != null) for (File fi : otrosFich) otrosAhora.add(fi.getName());
            boolean reenviarTodo = false;
            for (String n : otrosAhora) {
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sync_op_leido WHERE op = ? AND fichero = ?")) {
                    ps.setString(1, op); ps.setString(2, "#todo:" + n);
                    try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) reenviarTodo = true; }
                }
            }
            Map<String, Map<String, String>> ahora = estadoMisOrdenadores(conn, op);
            Map<String, Map<String, String>> antes = new HashMap<>();
            if (!reenviarTodo) try (PreparedStatement ps = conn.prepareStatement("SELECT tipo, clave, datos FROM sync_op_pub WHERE op = ?")) {
                ps.setString(1, op);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) antes.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
                }
            }
            StringBuilder salida = new StringBuilder();
            List<String[]> publicados = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> t : ahora.entrySet()) {
                String tipo = t.getKey();
                Map<String, String> ant = antes.getOrDefault(tipo, new HashMap<>());
                Set<String> claves = new java.util.LinkedHashSet<>(t.getValue().keySet());
                claves.addAll(ant.keySet());
                for (String clave : claves) {
                    String datos = t.getValue().get(clave);
                    if (java.util.Objects.equals(datos, ant.get(clave))) continue;
                    boolean borrar = datos == null;
                    long f = System.currentTimeMillis();
                    if (reenviarTodo) {   // si no ha cambiado desde la última vez, se reenvía con la fecha que ya tenía
                        String yaPub = null;
                        try (PreparedStatement ps = conn.prepareStatement("SELECT datos FROM sync_op_pub WHERE op = ? AND tipo = ? AND clave = ?")) {
                            ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, clave);
                            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) yaPub = rs.getString(1); }
                        }
                        Long fv = versionOp(conn, op, tipo, clave);
                        if (fv != null && java.util.Objects.equals(yaPub, datos)) f = fv;
                    }
                    java.util.SortedMap<String, String> linea = new java.util.TreeMap<>();
                    linea.put("v", "1"); linea.put("t", tipo); linea.put("k", clave); linea.put("f", String.valueOf(f));
                    linea.put("del", borrar ? "1" : "0"); linea.put("d", borrar ? "" : datos); linea.put("dev", miMarca); linea.put("op", op);
                    salida.append(codificar(linea)).append('\n');
                    publicados.add(new String[]{tipo, clave, datos, String.valueOf(f)});
                    enviados++;
                }
            }
            if (salida.length() > 0) {
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(dir, miFichero), true)) {
                    fo.write(salida.toString().getBytes(StandardCharsets.UTF_8));
                }
                for (String[] pb : publicados) {
                    guardarPublicadoOp(conn, op, pb[0], pb[1], pb[2]);
                    guardarVersionOp(conn, op, pb[0], pb[1], Long.parseLong(pb[3]));
                }
                // Lo mismo, al buzón de los móviles (si hay alguno; el reenvío completo es solo para los ordenadores)
                if (!reenviarTodo && !cuentaBz.isEmpty() && infoBz[0] > 0) {
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO sync_buzon_pend (op, texto) VALUES (?, ?)")) {
                        ps.setString(1, op); ps.setString(2, salida.toString()); ps.executeUpdate();
                    }
                }
            }
            // Apuntado: estos ordenadores ya tienen todo lo de este
            for (String n : otrosAhora) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO sync_op_leido (op, fichero, pos) VALUES (?, ?, 0)")) {
                    ps.setString(1, op); ps.setString(2, "#todo:" + n); ps.executeUpdate();
                }
            }
            if (!cuentaBz.isEmpty()) {
                try { buzonMandarPendientes(conn, op, cuentaBz); } catch (Exception ignored) { }
            }

            // ---------- 2. RECOGER lo de mis otros ordenadores ----------
            progresoSync = 35;
            List<String> lineas = new ArrayList<>();
            Map<String, Long> nuevasPos = new HashMap<>();
            File[] otros = dir.listFiles((x, n) -> n.startsWith("disp_") && n.endsWith(".txt") && !n.equals(miFichero));
            if (otros != null) {
                java.util.Arrays.sort(otros);
                for (File fi : otros) {
                    long pos = 0;
                    try (PreparedStatement ps = conn.prepareStatement("SELECT pos FROM sync_op_leido WHERE op = ? AND fichero = ?")) {
                        ps.setString(1, op); ps.setString(2, fi.getName());
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next()) pos = rs.getLong(1); }
                    }
                    if (fi.length() <= pos) continue;
                    byte[] b;
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(fi, "r")) {
                        raf.seek(pos);
                        b = new byte[(int) (raf.length() - pos)];
                        raf.readFully(b);
                    }
                    int ultimo = -1;
                    for (int i = b.length - 1; i >= 0; i--) if (b[i] == '\n') { ultimo = i; break; }
                    if (ultimo < 0) continue;   // aún no ha llegado una línea completa
                    for (String l : new String(b, 0, ultimo, StandardCharsets.UTF_8).split("\n")) if (!l.isBlank()) lineas.add(l);
                    nuevasPos.put(fi.getName(), pos + ultimo + 1);
                }
            }
            lineas.addAll(lineasBz);   // y lo que ha llegado del móvil
            // En el orden en que se hicieron (las actividades antes que sus contactos, si son del mismo momento)
            lineas.sort(java.util.Comparator.comparingLong(l -> { try { return Long.parseLong(decodificar(l).getOrDefault("f", "0")); } catch (Exception e) { return 0L; } }));
            Set<String> tocados = new java.util.LinkedHashSet<>();
            int totalOp = Math.max(1, lineas.size()), vistasOp = 0;
            for (String l : lineas) {
                vistasOp++;
                progresoSync = 35 + Math.min(60, 60 * vistasOp / totalOp);
                String t = aplicarLineaOp(conn, op, l, miMarca);
                if (t != null) { tocados.add(t); recibidos++; }
            }
            recibidosBz = lineasBz.size();
            if (!idsBz.isEmpty()) buzonBorrar(cuentaBz, idsBz);   // ya está guardado aquí: fuera del servidor
            for (Map.Entry<String, Long> e : nuevasPos.entrySet()) {
                try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO sync_op_leido (op, fichero, pos) VALUES (?, ?, ?)")) {
                    ps.setString(1, op); ps.setString(2, e.getKey()); ps.setLong(3, e.getValue()); ps.executeUpdate();
                }
            }
            if (!tocados.isEmpty()) {
                // Contactos que llegaron antes que su actividad: se enganchan ahora
                try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET act_uuid = (SELECT s.local FROM sync_op_alias s WHERE s.op = ? AND s.tipo = 'ACT' AND s.remoto = qsos.act_uuid) "
                        + "WHERE operador = ? AND COALESCE(radioclub,0) = 0 AND act_uuid IN (SELECT remoto FROM sync_op_alias WHERE op = ? AND tipo = 'ACT')")) {
                    ps.setString(1, op); ps.setString(2, op); ps.setString(3, op); ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET actividad_numeral = (SELECT a.numeral FROM actividades a WHERE a.uuid = qsos.act_uuid), "
                        + "actividad = (SELECT a.nombre FROM actividades a WHERE a.uuid = qsos.act_uuid), act_uuid = '' "
                        + "WHERE operador = ? AND COALESCE(radioclub,0) = 0 AND COALESCE(act_uuid,'') <> '' AND actividad_numeral = 0 "
                        + "AND EXISTS (SELECT 1 FROM actividades a WHERE a.uuid = qsos.act_uuid)")) {
                    ps.setString(1, op); ps.executeUpdate();
                }
                // Lo recibido queda como "ya enviado", para no devolverlo como si fuera un cambio de aquí
                Map<String, Map<String, String>> tras = estadoMisOrdenadores(conn, op);
                for (String tc : tocados) {
                    int k = tc.indexOf('|');
                    String tipo = tc.substring(0, k), clave = tc.substring(k + 1);
                    guardarPublicadoOp(conn, op, tipo, clave, tras.getOrDefault(tipo, Map.of()).get(clave));
                }
            }
            // Un móvil nuevo ha pedido el LOG entero
            if (!cuentaBz.isEmpty() && infoBz[1] > 0) {
                try { buzonMandarCompleto(conn, op, cuentaBz, miMarca); } catch (Exception ignored) { }
            }
        } catch (Exception ex) {
            actualizarEstadoSyncOp("Mis ordenadores: error al sincronizar (" + ex.getMessage() + ")", new Color(255, 110, 90));
            return false;
        }
        String hora = new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date());
        actualizarEstadoSyncOp("Mis ordenadores: sincronizado " + hora
                + (recibidos > 0 ? " · recibidos " + recibidos : "") + (enviados > 0 ? " · enviados " + enviados : "")
                + (recibidosBz > 0 ? " · del móvil " + recibidosBz : ""), app.cat.VERDE_ENCENDIDO);
        if (recibidos > 0) SwingUtilities.invokeLater(this::refrescarTrasSincronizar);
        return true;
    }

    /** Ventana "Sincronizar mis ordenadores" del operador (desde Editar operador). */
    void configurarSyncMisOrdenadores(Component padre, String op) {
        if (!app.cuentaB26.exigirCuenta(padre, "sincronizar tus ordenadores")) return;
        JCheckBox chk = new JCheckBox("Sincronizar los contactos y actividades de " + op + " entre mis ordenadores",
                "1".equals(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "ACTIVO"), "0")));
        JTextField txtCarpeta = new JTextField(LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "CARPETA"), ""), 26);
        JButton btnCarpeta = new JButton("Elegir carpeta…");
        btnCarpeta.addActionListener(ev -> {
            JFileChooser fc = new JFileChooser(txtCarpeta.getText().isEmpty() ? null : new File(txtCarpeta.getText()));
            fc.setDialogTitle("Carpeta de Google Drive para mis ordenadores");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(btnCarpeta) == JFileChooser.APPROVE_OPTION) txtCarpeta.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel filaDisco = new JPanel(new BorderLayout(6, 0));
        filaDisco.add(txtCarpeta, BorderLayout.CENTER);
        filaDisco.add(btnCarpeta, BorderLayout.EAST);
        String[] ftpDatos = {LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_SERVIDOR"), ""), LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_PUERTO"), "21"),
                LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_USUARIO"), ""), LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_CLAVE"), ""),
                LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_CARPETA"), ""), LibroGuardiaUI.ConfigApp.get(claveSyncOp(op, "FTP_SEGURO"), "0")};
        JLabel lblFtp = new JLabel();
        Runnable pintarFtp = () -> lblFtp.setText(ftpDatos[0].isBlank() ? "(sin configurar)"
                : (("1".equals(ftpDatos[5]) ? "ftps://" : "ftp://") + ftpDatos[2] + "@" + ftpDatos[0] + (ftpDatos[4].isBlank() ? "" : "/" + ftpDatos[4].replaceFirst("^/+", ""))));
        pintarFtp.run();
        JButton btnFtp = new JButton("Datos del FTP…");
        btnFtp.addActionListener(ev -> {
            String[] r = pedirDatosFtp(btnFtp, ftpDatos, nombreCarpetaSyncOp(op), "Datos del FTP para mis ordenadores",
                    "<html>En todos tus ordenadores pones los mismos datos. En la carpeta del servidor se crea<br>"
                            + "\"" + nombreCarpetaSyncOp(op) + "\". Ejemplo de servidor: ftp.miservidor.es (sin \"ftp://\").</html>");
            if (r != null) { System.arraycopy(r, 0, ftpDatos, 0, r.length); pintarFtp.run(); }
        });
        JPanel filaFtp = new JPanel(new BorderLayout(6, 0));
        filaFtp.add(lblFtp, BorderLayout.CENTER);
        filaFtp.add(btnFtp, BorderLayout.EAST);
        CardLayout tarjetas = new CardLayout();
        JPanel donde = new JPanel(tarjetas);
        donde.add(filaDisco, "CARPETA");
        donde.add(filaFtp, "FTP");
        JComboBox<String> cbDonde = new JComboBox<>(new String[]{"Carpeta de Google Drive", "Servidor FTP"});
        cbDonde.setSelectedIndex(syncOpPorFtp(op) ? 1 : 0);
        cbDonde.addActionListener(ev -> tarjetas.show(donde, cbDonde.getSelectedIndex() == 1 ? "FTP" : "CARPETA"));
        tarjetas.show(donde, cbDonde.getSelectedIndex() == 1 ? "FTP" : "CARPETA");

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 4, 5, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        p.add(chk, g);
        g.gridy = 1; g.gridwidth = 1; g.weightx = 0;
        p.add(cbDonde, g);
        g.gridx = 1; g.weightx = 1;
        p.add(donde, g);
        g.gridx = 0; g.gridy = 2; g.gridwidth = 2;
        JLabel nota = new JLabel("<html><div style='width:470px'>"
                + "En cada uno de tus ordenadores: entra con el operador <b>" + op + "</b>, abre esta ventana y elige la misma carpeta "
                + "de la nube (o los mismos datos del FTP). Usa una carpeta tuya, que no compartas con nadie.<br><br>"
                + "Se sincronizan tus contactos y tus actividades (los del radioclub ya van por \"Compartir con el radioclub\"). "
                + "Se hace sola al abrir el programa y al cerrarlo, y cuando quieras con el botón \"Sincronizar\" del LOG Principal. "
                + "Funciona sin internet y se envía al volver a tenerlo. "
                + "Si un contacto ya estaba en los dos ordenadores (por ejemplo, porque lo pasaste con un ADI), no se duplica."
                + "</div></html>");
        nota.setFont(nota.getFont().deriveFont(11f));
        p.add(nota, g);

        while (true) {
            int r = JOptionPane.showConfirmDialog(padre, p, "Sincronizar mis ordenadores", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            boolean porFtp = cbDonde.getSelectedIndex() == 1;
            String carpeta = carpetaBaseSyncOp(txtCarpeta.getText(), op);
            if (chk.isSelected()) {
                if (porFtp && (ftpDatos[0].isBlank() || ftpDatos[2].isBlank())) {
                    JOptionPane.showMessageDialog(padre, "Pulsa \"Datos del FTP…\" y escribe el servidor y el usuario.", "Sincronizar mis ordenadores", JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                if (!porFtp) {
                    if (carpeta.isEmpty()) {
                        JOptionPane.showMessageDialog(padre, "Elige la carpeta de Google Drive.", "Sincronizar mis ordenadores", JOptionPane.WARNING_MESSAGE);
                        continue;
                    }
                    String problema = prepararCarpetaSyncOp(new File(carpeta), new File(carpeta, nombreCarpetaSyncOp(op)));
                    if (problema != null) {
                        JOptionPane.showMessageDialog(padre, "No se puede usar esa carpeta: " + problema + ".", "Sincronizar mis ordenadores", JOptionPane.WARNING_MESSAGE);
                        continue;
                    }
                }
            }
            LibroGuardiaUI.ConfigApp.put(claveSyncOp(op, "ACTIVO"), chk.isSelected() ? "1" : "0");
            LibroGuardiaUI.ConfigApp.put(claveSyncOp(op, "TIPO"), porFtp ? "FTP" : "CARPETA");
            LibroGuardiaUI.ConfigApp.put(claveSyncOp(op, "CARPETA"), carpeta);
            String[] k = {"FTP_SERVIDOR", "FTP_PUERTO", "FTP_USUARIO", "FTP_CLAVE", "FTP_CARPETA", "FTP_SEGURO"};
            for (int i = 0; i < k.length; i++) LibroGuardiaUI.ConfigApp.put(claveSyncOp(op, k[i]), ftpDatos[i]);
            if (!chk.isSelected()) {
                if (op.equals(app.operadorActual)) actualizarEstadoSyncOp(" ", null);
            } else if (op.equals(app.operadorActual)) {
                sincronizarMisOrdenadoresEnSegundoPlano();
            }
            SwingUtilities.invokeLater(app::actualizarVisibilidadBotonDiploma);
            return;
        }
    }

    void refrescarTrasSincronizar() {
        cargarLogoRadioclub();
        if (app.cbSelectorActividad == null) return;
        recargarDesplegableIndicativoRC();
        actualizarControlesRadioclub();
        Object selPrevia = app.cbSelectorActividad.getSelectedItem();
        app.actualizarDesplegableActividades();
        app.rehaciendoDesplegableActividades = true;
        try {
            if (selPrevia != null) app.cbSelectorActividad.setSelectedItem(selPrevia);
        } finally {
            app.rehaciendoDesplegableActividades = false;
        }
        app.cargarQSOsDesdeBaseDatos();
    }

    /** Aplica una línea recibida. 1 = aplicada, 0 = falta algo (se reintenta), -1 = rechazada (firma no válida), 2 = ya estaba o es antigua. */
    // ===================== PAPELES Y PERMISOS DEL RADIOCLUB =====================
    // Papeles: PC Radioclub (el maestro, lo puede todo), Super Admin, Admin y Socio.
    // El PC Radioclub decide qué puede hacer cada papel (Gestionar permisos); se comparte firmado con todos.
    // Los papeles van con el socio (por su indicativo): en cada ordenador suyo se activan una vez con la contraseña del cargo.
    static final String[][] PERMISOS_RC = {
            {"SOCIOS", "Dar de alta, cambiar y dar de baja socios"},
            {"CUOTAS", "Apuntar las cuotas de los socios"},
            {"DATOS", "Cambiar los datos del radioclub (nombre, indicativos, QTH, web)"},
            {"ACTIVIDADES", "Cambiar y borrar actividades del radioclub creadas por otros"},
            {"CONTACTOS", "Cambiar y borrar contactos de otros socios en el LOG Radioclub"},
            {"LISTACB", "Quitar y modificar indicativos de la lista BANDA CIUDADANA para todos"},
            {"INVITAR", "Sacar la clave de invitación para nuevos socios"},
            {"ADMINS", "Nombrar y quitar Admins"},
            {"EXPULSAR", "Expulsar y readmitir socios"},
            {"CLAVETEMP", "Poner contraseñas temporales a los operadores"},
            {"REGISTRO", "Ver el registro de cambios y deshacerlos"}};
    static final String[] ROLES_CON_PERMISOS = {"SUPER", "ADMIN", "SOCIO"};
    static final String PERM_DEF_SUPER = "SOCIOS,CUOTAS,DATOS,ACTIVIDADES,CONTACTOS,LISTACB,INVITAR,ADMINS,EXPULSAR,CLAVETEMP,REGISTRO";
    static final String PERM_DEF_ADMIN = "SOCIOS,CUOTAS,DATOS,ACTIVIDADES,INVITAR,EXPULSAR,REGISTRO";
    static final String PERM_DEF_SOCIO = "";

    static String permisosDeRol(String rol) {
        String def = switch (rol) { case "SUPER" -> PERM_DEF_SUPER; case "ADMIN" -> PERM_DEF_ADMIN; default -> PERM_DEF_SOCIO; };
        return LibroGuardiaUI.ConfigApp.get("RC_PERM_" + rol, def);
    }

    static boolean rolTiene(String rol, String permiso) {
        if ("RC".equals(rol)) return true;
        if (permiso == null) return true;
        if (permiso.equals("RC")) return false;   // cosas que solo hace el PC Radioclub
        if (permiso.equals("PERMISOS")) return "SUPER".equals(rol);   // gestionar permisos: el PC Radioclub y los Super Admin
        for (String p : permisosDeRol(rol).split(",")) if (p.trim().equals(permiso)) return true;
        return false;
    }

    /** Papel de un ordenador en un momento dado: RC, SUPER, ADMIN o SOCIO. */
    private static String rolDeOrdenador(Connection conn, String dev, long f) {
        String admin = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "");
        if ((!admin.isEmpty() && admin.equals(dev)) || fueAdminEn(dev, f)) return "RC";
        if (esSuperRol(conn, dev, f)) return "SUPER";
        if (esAdminRol(conn, dev, f)) return "ADMIN";
        return "SOCIO";
    }

    private String miPubB64() throws Exception {
        return java.util.Base64.getEncoder().encodeToString(llaveDispositivo().getPublic().getEncoded());
    }

    /** Papel de este ordenador ahora mismo. */
    private String miRol() {
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            return rolDeOrdenador(conn, miPubB64(), System.currentTimeMillis());
        } catch (Exception e) {
            return "SOCIO";
        }
    }

    /** ¿Puede este ordenador hacer eso en el radioclub? Sin compartir con el radioclub, todo vale. */
    boolean tengoPermiso(String permiso) {
        if (!syncActivo()) return true;
        return rolTiene(miRol(), permiso);
    }

    boolean pedirPermisoRC(Component padre, String permiso, String paraQue) {
        if (tengoPermiso(permiso)) return true;
        JOptionPane.showMessageDialog(padre, "Para " + paraQue + " hace falta un permiso que tu papel en el radioclub no tiene.\n\n"
                        + "Lo que puede hacer cada papel lo decide el PC Radioclub (Socios del radioclub → Gestionar permisos).",
                "Permisos del radioclub", JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    /** Permiso que hace falta para un cambio compartido (null = cualquiera; "RC" = solo el PC Radioclub; "?" = tipo desconocido). */
    private static String permisoNecesario(Connection conn, String tipo, boolean borrar, String[] ver, String dev, java.util.SortedMap<String, String> x, String clave) throws Exception {
        boolean deOtro = ver != null && ver[1] != null && !ver[1].equals(dev);
        switch (tipo) {
            case "PERM": return "PERMISOS";
            case "RCCLAVE": return "PERMISOS";   // la contraseña del radioclub: el PC Radioclub y los Super Admin
            case "ROL", "ADMIN": return "RC";
            case "CARGO": {
                boolean esSuper = "1".equals(x.getOrDefault("grado", ""));
                try (PreparedStatement ps = conn.prepareStatement("SELECT grado FROM rc_cargos WHERE uuid = ?")) {
                    ps.setString(1, clave);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next() && rs.getInt(1) == 1) esSuper = true; }
                }
                return esSuper ? "PERMISOS" : "ADMINS";   // Super Admin: el PC Radioclub y los Super Admin
            }
            case "CLAVETEMP": return "CLAVETEMP";
            case "EXPUL": return "EXPULSAR";
            case "RC", "LOGO": return "DATOS";
            case "SOCIO": return "SOCIOS";
            case "CUOTA": return "CUOTAS";
            // Borrar una actividad del radioclub (aunque la creara uno mismo) necesita el permiso ACTIVIDADES:
            // así el ordenador del radioclub nunca pierde una actividad porque un socio la quite de su PC
            case "ACT": return borrar ? "PERMISOS" : deOtro ? "ACTIVIDADES" : null;   // borrar: solo el PC Radioclub y los Super Admin
            case "QSO": return deOtro ? "CONTACTOS" : null;
            case "CB": {
                if (borrar) return "LISTACB";
                // Añadir un indicativo nuevo lo puede cualquiera; cambiar uno que ya está en la lista, solo con permiso
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM cb_directorio WHERE indicativo = ? AND eliminado = 0")) {
                    ps.setString(1, LibroGuardiaUI.txtCB(clave));
                    try (ResultSet rs = ps.executeQuery()) { return rs.next() ? "LISTACB" : null; }
                }
            }
            case "OPER", "HOLA", "VINC": return null;
            default: return "?";
        }
    }

    /** Cargos del radioclub por socio: indicativo -> {uuid, grado, pub, priv, sal2}. */
    private static Map<String, String[]> cargosPorSocio() {
        Map<String, String[]> r = new HashMap<>();
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT UPPER(TRIM(indicativo)), uuid, grado, COALESCE(pub,''), COALESCE(priv,''), COALESCE(sal2,'') FROM rc_cargos "
                     + "WHERE COALESCE(uuid,'') <> '' AND COALESCE(pub,'') <> ''")) {
            while (rs.next()) r.put(rs.getString(1), new String[]{rs.getString(2), String.valueOf(rs.getInt(3)), rs.getString(4), rs.getString(5), rs.getString(6)});
        } catch (Exception ignored) { }
        return r;
    }

    /** En cuántos ordenadores está activado un cargo. */
    private static int cargoActivadoEn(String uuid) {
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM rc_admins WHERE nota = ? AND hasta = 0")) {
            ps.setString(1, "CARGO:" + uuid);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Cargo del operador que está usando este ordenador (por su operador o sus indicativos HF/CB), o null. */
    private String[] miCargo() {
        if (app.operadorActual == null || app.operadorActual.isBlank()) return null;
        Map<String, String[]> cargos = cargosPorSocio();
        List<String> ids = new ArrayList<>();
        ids.add(app.operadorActual.trim().toUpperCase());
        String[] d = app.leerDatosOperador(app.operadorActual);
        if (d != null) for (int i = 0; i < 2 && i < d.length; i++) if (d[i] != null && !d[i].isBlank()) ids.add(d[i].trim().toUpperCase());
        for (String id : ids) if (cargos.containsKey(id)) return cargos.get(id);
        return null;
    }

    private final Set<String> cargosOfrecidos = new HashSet<>();

    /** Tras sincronizar: si el operador tiene un cargo sin activar en este ordenador, se le ofrece activarlo (una vez por sesión). */
    private void ofrecerActivarCargo() {
        if (esOrdenadorRadioclub()) return;
        String[] c = miCargo();
        if (c == null || !LibroGuardiaUI.ConfigApp.get("RC_VINC_" + c[0], "").isEmpty() || !cargosOfrecidos.add(c[0])) return;
        activarMiCargo(app, false);
    }

    /** Activa en este ordenador el cargo del operador (con la contraseña del cargo que le dio el radioclub). */
    private void activarMiCargo(Component padre, boolean avisarSiNoHay) {
        String[] c = miCargo();
        if (c == null) {
            if (avisarSiNoHay) JOptionPane.showMessageDialog(padre, "El operador " + app.operadorActual + " no tiene ningún cargo en el radioclub.", "Activar mi cargo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String papel = "1".equals(c[1]) ? "Super Admin" : "Admin";
        if (!LibroGuardiaUI.ConfigApp.get("RC_VINC_" + c[0], "").isEmpty()) {
            if (avisarSiNoHay) JOptionPane.showMessageDialog(padre, "Tu cargo de " + papel + " ya está activado en este ordenador.", "Activar mi cargo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        for (int intento = 0; intento < 3; intento++) {
            JPasswordField pf = new JPasswordField(16);
            int r = JOptionPane.showConfirmDialog(padre, new Object[]{"<html>" + app.operadorActual + ": en el radioclub eres <b>" + papel + "</b>.<br>"
                            + "Para activarlo en este ordenador, escribe la contraseña del cargo<br>(te la dio el PC Radioclub). Solo se pide una vez en cada ordenador.</html>", pf},
                    "Activar mi cargo", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            java.security.PrivateKey llave = abrirLlaveCargo(pf.getPassword(), c[3], c[4]);
            if (llave == null) {
                JOptionPane.showMessageDialog(padre, "Contraseña del cargo incorrecta.", "Activar mi cargo", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            try {
                String miPub = miPubB64();
                String firma = firmar(llave, "VINC\n" + miPub + "\n" + c[0]);
                LibroGuardiaUI.ConfigApp.put("RC_VINC_" + c[0], firma);
                try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
                     PreparedStatement ps = conn.prepareStatement("INSERT INTO rc_admins (dev, nota, desde, hasta, rol) VALUES (?, ?, ?, 0, ?) "
                             + "ON CONFLICT(dev) DO UPDATE SET nota = excluded.nota, rol = excluded.rol, desde = excluded.desde, hasta = 0")) {
                    ps.setString(1, miPub); ps.setString(2, "CARGO:" + c[0]); ps.setLong(3, System.currentTimeMillis());
                    ps.setString(4, "1".equals(c[1]) ? "SUPER" : "ADMIN");
                    ps.executeUpdate();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(padre, "No se ha podido activar: " + ex.getMessage(), "Activar mi cargo", JOptionPane.ERROR_MESSAGE);
                return;
            }
            JOptionPane.showMessageDialog(padre, "Hecho: tu cargo de " + papel + " está activado en este ordenador.\nLos demás lo sabrán al sincronizar.",
                    "Activar mi cargo", JOptionPane.INFORMATION_MESSAGE);
            sincronizarPronto();
            return;
        }
    }

    /** Un ordenador activa un cargo (firmado con la llave del cargo, que solo se abre con su contraseña). */
    private int aplicarVinculo(Connection conn, String clave, boolean borrar, java.util.SortedMap<String, String> x, String dev, long f, String[] ver, Set<String> tocados) throws Exception {
        String cu = borrar ? (clave.contains("/") ? clave.substring(0, clave.indexOf('/')) : "") : x.getOrDefault("cargo", "");
        if (cu.isEmpty()) return -1;
        if (borrar) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE rc_admins SET hasta = ? WHERE dev = ? AND nota = ? AND hasta = 0")) {
                ps.setLong(1, f); ps.setString(2, dev); ps.setString(3, "CARGO:" + cu); ps.executeUpdate();
            }
        } else {
            String pub = null;
            int grado = 2;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(pub,''), grado FROM rc_cargos WHERE uuid = ?")) {
                ps.setString(1, cu);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { pub = rs.getString(1); grado = rs.getInt(2); } }
            }
            if (pub == null || pub.isEmpty()) return 0;   // aún no ha llegado el cargo: se reintenta
            if (!verificar(pub, "VINC\n" + dev + "\n" + cu, x.get("s"))) return -1;   // no sabe la contraseña del cargo
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO rc_admins (dev, nota, desde, hasta, rol) VALUES (?, ?, ?, 0, ?) "
                    + "ON CONFLICT(dev) DO UPDATE SET nota = excluded.nota, rol = excluded.rol, "
                    + "desde = CASE WHEN rc_admins.hasta = 0 AND rc_admins.nota = excluded.nota THEN rc_admins.desde ELSE excluded.desde END, hasta = 0")) {
                ps.setString(1, dev); ps.setString(2, "CARGO:" + cu); ps.setLong(3, f); ps.setString(4, grado == 1 ? "SUPER" : "ADMIN");
                ps.executeUpdate();
            }
        }
        guardarVersion(conn, "VINC", clave, f, ver == null ? dev : ver[1]);
        tocados.add("VINC|" + clave);
        return 1;
    }

    /** Ventana "Gestionar permisos" (solo en el PC Radioclub): qué puede hacer cada papel. */
    private void abrirGestionPermisos(Component padre) {
        if (!esOrdenadorRadioclub() && !"SUPER".equals(miRol())) {
            JOptionPane.showMessageDialog(padre, "Los permisos solo los gestionan el PC Radioclub y los Super Admin.", "Gestionar permisos", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        DefaultTableModel m = new DefaultTableModel(new Object[]{"Permiso", "Super Admin", "Admin", "Socio"}, 0) {
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? String.class : Boolean.class; }
            @Override public boolean isCellEditable(int r, int c) { return c > 0; }
        };
        for (String[] p : PERMISOS_RC) m.addRow(new Object[]{p[1], rolTiene("SUPER", p[0]), rolTiene("ADMIN", p[0]), rolTiene("SOCIO", p[0])});
        JTable t = new JTable(m);
        t.setRowHeight(22);
        t.getColumnModel().getColumn(0).setPreferredWidth(420);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(700, 300));
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html>Marca lo que puede hacer cada papel. El <b>PC Radioclub</b> lo puede todo, y solo él puede: nombrar Super Admin,<br>"
                + "cambiar la contraseña del radioclub y ceder el PC Radioclub. Los permisos los gestionan el PC Radioclub y los Super Admin.<br>"
                + "Los permisos llegan a todos los ordenadores al sincronizar, y cada uno los comprueba.</html>"), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(padre, p, "Gestionar permisos", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (t.isEditing()) t.getCellEditor().stopCellEditing();
        for (int c = 0; c < ROLES_CON_PERMISOS.length; c++) {
            List<String> l = new ArrayList<>();
            for (int r = 0; r < PERMISOS_RC.length; r++) if (Boolean.TRUE.equals(m.getValueAt(r, c + 1))) l.add(PERMISOS_RC[r][0]);
            LibroGuardiaUI.ConfigApp.put("RC_PERM_" + ROLES_CON_PERMISOS[c], String.join(",", l));
        }
        registrarCambio("Cambió los permisos de los papeles del radioclub", "", "");
        sincronizarPronto();
    }

    private int aplicarLinea(Connection conn, String linea, String miPub, Set<String> tocados) {
        try {
            java.util.SortedMap<String, String> m = decodificar(linea);
            String tipo = m.getOrDefault("t", ""), clave = m.getOrDefault("k", ""), d = m.getOrDefault("d", ""), dev = m.getOrDefault("dev", ""), adm = m.getOrDefault("adm", "");
            boolean borrar = "1".equals(m.get("del"));
            long f = Long.parseLong(m.getOrDefault("f", "0"));
            String msg = tipo + "\n" + clave + "\n" + f + "\n" + (borrar ? 1 : 0) + "\n" + d + "\n" + adm;
            if (!verificar(dev, msg, m.get("s"))) return -1;   // no viene de quien dice
            if (dev.equals(miPub)) return 2;
            if (estaExpulsado(conn, dev)) return -1;   // ordenador expulsado del radioclub
            String[] ver = versionDe(conn, tipo, clave);
            // Datos del radioclub: lo que manda el PC Radioclub o un Super Admin se aplica aunque aquí haya una versión
            // "más nueva" de otro ordenador; entre ellos, gana lo más reciente
            boolean rcAutoridad = false;
            if (tipo.equals("RC") && !dev.isEmpty()) {
                String papel = rolDeOrdenador(conn, dev, f);
                if (papel.equals("RC") || papel.equals("SUPER")) {
                    long ultimaAut = 0;
                    try { ultimaAut = Long.parseLong(LibroGuardiaUI.ConfigApp.get("RC_F_AUTORIDAD", "0")); } catch (Exception ignored) { }
                    if (f < ultimaAut) return 2;
                    LibroGuardiaUI.ConfigApp.put("RC_F_AUTORIDAD", String.valueOf(f));
                    rcAutoridad = true;
                }
            }
            if (ver != null && Long.parseLong(ver[0]) > f && !rcAutoridad) return 2;   // ya tengo algo más nuevo (si es la misma versión se vuelve a dejar igual)
            // Firma de administrador, si hace falta
            String admin = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "");
            if (tipo.equals("ADMIN")) {
                // El primero que se da a conocer como ordenador del radioclub es el que vale (firmado con su propia llave)
                // Después, el ordenador del radioclub puede ceder ese papel a otro (firmado por él)
                String pub = decodificar(d).getOrDefault("pub", "");
                if (borrar || pub.isEmpty()) return -1;
                if (admin.isEmpty()) {
                    if (!pub.equals(dev)) return -1;
                    LibroGuardiaUI.ConfigApp.put("RC_ADMIN_PUB", pub);
                    return 1;
                }
                if (admin.equals(pub)) return 2;                 // ya es ese
                String prueba = decodificar(d).getOrDefault("prueba", "");
                if (!admin.equals(dev) && !prueba.isEmpty()) {
                    // Un administrador se queda con el ordenador del radioclub: tiene que saber la contraseña del radioclub
                    if (!pruebaClaveRadioclubValida(prueba)) return -1;
                    if (!esSuperRol(conn, dev, f)) return 0;     // aún no sé que es Super Admin: se reintenta
                }
                if (admin.equals(dev) || (!prueba.isEmpty() && esSuperRol(conn, dev, f))) {   // el ordenador del radioclub lo cede (o un administrador se queda con él)
                    anotarAdminAnterior(admin, f);
                    LibroGuardiaUI.ConfigApp.put("RC_ADMIN_PUB", pub);
                    guardarVersion(conn, tipo, clave, f, dev);
                    return 1;
                }
                return 2;                                         // un cambio antiguo: no vale, pero no es una falsificación
            }
            if (tipo.equals("VINC")) return aplicarVinculo(conn, clave, borrar, decodificar(d), dev, f, ver, tocados);
            String permiso = permisoNecesario(conn, tipo, borrar, ver, dev, decodificar(d), clave);
            if ("?".equals(permiso)) return -1;
            if (permiso != null) {
                if (admin.isEmpty()) return 0;              // aún no sé cuál es el PC Radioclub: se reintenta
                // Vale según el papel que tenía ese ordenador en ese momento y lo que el PC Radioclub permite a ese papel
                if (!rolTiene(rolDeOrdenador(conn, dev, f), permiso)) return tipo.equals("CB") ? 2 : -1;
            }
            versionActualRol = f;
            int r = aplicarEntidad(conn, tipo, clave, borrar, decodificar(d), miPub);
            if (r != 1) return r;
            guardarVersion(conn, tipo, clave, f, ver == null ? dev : ver[1]);
            tocados.add(tipo + "|" + clave);
            return 1;
        } catch (Exception ex) {
            System.err.println("Sincronización: línea no aplicada: " + ex.getMessage());
            return 0;
        }
    }


    /** Deja en este ordenador un elemento compartido con los datos dados (1 = hecho, 0 = falta algo, -1 = tipo desconocido). */
    private int aplicarEntidad(Connection conn, String tipo, String clave, boolean borrar, java.util.SortedMap<String, String> x, String miPub) throws Exception {
            switch (tipo) {
                case "LOGO" -> {
                    File logo = ficheroLogoRadioclub();
                    if (borrar) {
                        if (logo.isFile() && !logo.delete()) System.err.println("No se pudo borrar el logo");
                    } else {
                        byte[] b = java.util.Base64.getUrlDecoder().decode(x.getOrDefault("png", ""));
                        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(b));
                        if (img == null || img.getWidth() > LOGO_MAX_PIXELES || img.getHeight() > LOGO_MAX_PIXELES) return -1;   // no es un logo válido
                        java.nio.file.Files.write(logo.toPath(), b);
                    }
                }
                case "PERM" -> {
                    if (!borrar) for (String rol : ROLES_CON_PERMISOS) if (x.containsKey(rol)) LibroGuardiaUI.ConfigApp.put("RC_PERM_" + rol, x.get(rol));
                }
                case "CARGO" -> {
                    if (borrar) {
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rc_cargos WHERE uuid = ?")) { ps.setString(1, clave); ps.executeUpdate(); }
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE rc_admins SET hasta = ? WHERE nota = ? AND hasta = 0")) {
                            ps.setLong(1, versionActualRol > 0 ? versionActualRol : System.currentTimeMillis()); ps.setString(2, "CARGO:" + clave); ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE rc_admins SET rol = ? WHERE nota = ? AND hasta = 0")) {
                            ps.setString(1, "1".equals(x.getOrDefault("grado", "2")) ? "SUPER" : "ADMIN"); ps.setString(2, "CARGO:" + clave); ps.executeUpdate();
                        }
                        int id = -1;
                        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM rc_cargos WHERE uuid = ?")) {
                            ps.setString(1, clave);
                            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) id = rs.getInt(1); }
                        }
                        String sql = id < 0 ? "INSERT INTO rc_cargos (indicativo, nombre, grado, sal, clave, pub, priv, sal2, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                : "UPDATE rc_cargos SET indicativo = ?, nombre = ?, grado = ?, sal = ?, clave = ?, pub = ?, priv = ?, sal2 = ? WHERE uuid = ?";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setString(1, x.getOrDefault("ind", "")); ps.setString(2, x.getOrDefault("nom", ""));
                            ps.setInt(3, Integer.parseInt(x.getOrDefault("grado", "2"))); ps.setString(4, x.getOrDefault("sal", ""));
                            ps.setString(5, x.getOrDefault("clave", "")); ps.setString(6, x.getOrDefault("pub", ""));
                            ps.setString(7, x.getOrDefault("priv", "")); ps.setString(8, x.getOrDefault("sal2", "")); ps.setString(9, clave);
                            ps.executeUpdate();
                        }
                    }
                }
                case "RC" -> {
                    if (!borrar) for (String k : CLAVES_RC_SYNC) if (x.containsKey(k)) LibroGuardiaUI.ConfigApp.put(k, x.get(k));
                }
                case "SOCIO" -> {
                    if (borrar) {
                        try (PreparedStatement p1 = conn.prepareStatement("DELETE FROM socios_cuotas WHERE socio_id IN (SELECT id FROM socios WHERE uuid = ?)");
                             PreparedStatement p2 = conn.prepareStatement("DELETE FROM socios WHERE uuid = ?")) {
                            p1.setString(1, clave); p1.executeUpdate(); p2.setString(1, clave); p2.executeUpdate();
                        }
                    } else {
                        boolean existe;
                        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM socios WHERE uuid = ?")) {
                            ps.setString(1, clave);
                            try (ResultSet rs = ps.executeQuery()) { existe = rs.next(); }
                        }
                        String sql = existe ? "UPDATE socios SET num = ?, ind_hf = ?, ind_cb = ?, nombre = ?, qth = ? WHERE uuid = ?"
                                : "INSERT INTO socios (num, ind_hf, ind_cb, nombre, qth, uuid) VALUES (?, ?, ?, ?, ?, ?)";
                        try (PreparedStatement ps = conn.prepareStatement(sql)) {
                            ps.setInt(1, Integer.parseInt(x.getOrDefault("num", "0"))); ps.setString(2, x.getOrDefault("hf", ""));
                            ps.setString(3, x.getOrDefault("cb", "")); ps.setString(4, x.getOrDefault("nom", ""));
                            ps.setString(5, x.getOrDefault("qth", "")); ps.setString(6, clave);
                            ps.executeUpdate();
                        }
                    }
                }
                case "CUOTA" -> {
                    int k = clave.lastIndexOf('/');
                    String socioUuid = clave.substring(0, k);
                    int anio = Integer.parseInt(clave.substring(k + 1));
                    int socioId = -1;
                    try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM socios WHERE uuid = ?")) {
                        ps.setString(1, socioUuid);
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next()) socioId = rs.getInt(1); }
                    }
                    if (socioId < 0) return borrar ? 2 : 0;   // aún no ha llegado el socio
                    try (PreparedStatement ps = conn.prepareStatement(borrar ? "DELETE FROM socios_cuotas WHERE socio_id = ? AND anio = ?"
                            : "INSERT OR REPLACE INTO socios_cuotas (socio_id, anio, fecha_pago) VALUES (?, ?, ?)")) {
                        ps.setInt(1, socioId); ps.setInt(2, anio);
                        if (!borrar) ps.setString(3, x.getOrDefault("fecha", ""));
                        ps.executeUpdate();
                    }
                }
                case "EXPUL" -> {
                    if (borrar) {
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rc_expulsados WHERE dev = ?")) { ps.setString(1, clave); ps.executeUpdate(); }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO rc_expulsados (dev, nota) VALUES (?, ?)")) {
                            ps.setString(1, clave); ps.setString(2, x.getOrDefault("nota", "")); ps.executeUpdate();
                        }
                    }
                }
                case "HOLA" -> {   // sirve para saber qué ordenadores hay; el PC Radioclub apunta como socio al de la clave de invitación
                    if (!borrar && esOrdenadorRadioclub() && !x.getOrDefault("soc", "").isBlank()) {
                        String[] d = {x.getOrDefault("shf", ""), x.getOrDefault("scb", ""), x.getOrDefault("snom", ""), x.getOrDefault("sqth", ""), x.getOrDefault("sgrid", "")};
                        if (!app.idOperador(d).isEmpty()) apuntarOperadorDelRadioclub(conn, d);
                    }
                }
                case "CB" -> {
                    // Lista BANDA CIUDADANA de otro socio: se añade o se completa aquí (no se borra nada)
                    String ind = app.txtCB(clave);
                    if (ind.isEmpty()) return 2;
                    if (borrar) {   // lo ha quitado de la lista el ordenador del radioclub o un superadministrador
                        // Si el operador de este ordenador tiene contactos con ese indicativo en su log, aquí se queda
                        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM qsos WHERE UPPER(TRIM(indicativo)) = ? AND operador = ? LIMIT 1")) {
                            ps.setString(1, ind); ps.setString(2, app.operadorActual == null ? "" : app.operadorActual);
                            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return 2; }
                        }
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE cb_directorio SET eliminado = 1, corte = ? WHERE indicativo = ? AND eliminado = 0")) {
                            ps.setLong(1, app.maxIdQsos(conn)); ps.setString(2, ind);
                            if (ps.executeUpdate() == 0) return 2;
                        }
                        return 1;
                    }
                    String nom = app.txtCB(x.getOrDefault("nom", "")), qth = app.txtCB(x.getOrDefault("qth", ""));
                    Integer eliminado = null;
                    try (PreparedStatement ps = conn.prepareStatement("SELECT eliminado FROM cb_directorio WHERE indicativo = ?")) {
                        ps.setString(1, ind);
                        try (ResultSet rs = ps.executeQuery()) { if (rs.next()) eliminado = rs.getInt(1); }
                    }
                    if (eliminado != null && eliminado == 1) return 2;   // quitado a mano de la lista en este ordenador
                    if (eliminado == null) {
                        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO cb_directorio (indicativo, nombre, qth, eliminado, corte) VALUES (?, ?, ?, 0, 0)")) {
                            ps.setString(1, ind); ps.setString(2, nom); ps.setString(3, qth); ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE cb_directorio SET nombre = CASE WHEN ? <> '' THEN ? ELSE nombre END, "
                                + "qth = CASE WHEN ? <> '' THEN ? ELSE qth END WHERE indicativo = ?")) {
                            ps.setString(1, nom); ps.setString(2, nom); ps.setString(3, qth); ps.setString(4, qth); ps.setString(5, ind);
                            ps.executeUpdate();
                        }
                    }
                }
                case "RCCLAVE" -> {
                    if (!borrar) {
                        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_CLAVE_SAL", x.getOrDefault("sal", ""));
                        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_CLAVE_VER", x.getOrDefault("ver", ""));
                    }
                }
                case "CLAVETEMP" -> {
                    if (!borrar) {
                        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO rc_claves_temp (op, sal, hash, f) VALUES (?, ?, ?, ?)")) {
                            ps.setString(1, clave); ps.setString(2, x.getOrDefault("sal", "")); ps.setString(3, x.getOrDefault("hash", ""));
                            ps.setLong(4, Long.parseLong(x.getOrDefault("f", "0"))); ps.executeUpdate();
                        }
                        ponerClaveTemporalLocal(clave, x.getOrDefault("sal", ""), x.getOrDefault("hash", ""), Long.parseLong(x.getOrDefault("f", "0")));
                    }
                }
                case "ROL" -> {
                    long f = versionActualRol;
                    if (borrar) {
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE rc_admins SET hasta = ? WHERE dev = ? AND hasta = 0")) {
                            ps.setLong(1, f); ps.setString(2, clave); ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO rc_admins (dev, nota, desde, hasta, rol) VALUES (?, ?, ?, 0, ?) "
                                + "ON CONFLICT(dev) DO UPDATE SET nota = excluded.nota, rol = excluded.rol, hasta = 0, desde = CASE WHEN rc_admins.hasta = 0 THEN rc_admins.desde ELSE excluded.desde END")) {
                            ps.setString(1, clave); ps.setString(2, x.getOrDefault("nota", "")); ps.setLong(3, f); ps.setString(4, x.getOrDefault("rol", "ADMIN")); ps.executeUpdate();
                        }
                    }
                }
                case "ACT" -> aplicarActividad(conn, clave, borrar, x);
                case "QSO" -> aplicarQso(conn, clave, borrar, x, miPub);
                case "OPER" -> {
                    // Solo le interesa al ordenador del radioclub: lo apunta como operador y como socio (si no lo era)
                    if (borrar || !esOrdenadorRadioclub()) return 2;   // en los demás ordenadores no se añaden operadores de otros
                    String[] d = {x.getOrDefault("hf", ""), x.getOrDefault("cb", ""), x.getOrDefault("nom", ""), x.getOrDefault("qth", ""), x.getOrDefault("grid", "")};
                    apuntarOperadorDelRadioclub(conn, d);
                }
                default -> { return -1; }
            }
            return 1;
    }

    /** Si en la carpeta compartida ya hay un ordenador del radioclub (distinto de este), devuelve su llave. */
    private String adminEnCarpeta(File dir) {
        File[] fs = dir.listFiles((x, n) -> n.startsWith("disp_") && n.endsWith(".txt"));
        if (fs == null) return null;
        String miPub;
        try { miPub = java.util.Base64.getEncoder().encodeToString(llaveDispositivo().getPublic().getEncoded()); } catch (Exception e) { return null; }
        for (File f : fs) {
            try {
                for (String l : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (!l.contains("t=ADMIN")) continue;
                    java.util.SortedMap<String, String> m = decodificar(l);
                    if (!"ADMIN".equals(m.get("t"))) continue;
                    String dev = m.getOrDefault("dev", "");
                    if (!dev.isEmpty() && !dev.equals(miPub)) return dev;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void aplicarActividad(Connection conn, String uuid, boolean borrar, java.util.SortedMap<String, String> x) throws Exception {
        int numeral = -1;
        String nombreAnt = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT numeral, nombre FROM actividades WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { numeral = rs.getInt(1); nombreAnt = rs.getString(2); } }
        }
        if (borrar) {
            if (numeral < 0) return;
            try (PreparedStatement p1 = conn.prepareStatement("DELETE FROM actividad_puntos WHERE actividad = ?");
                 PreparedStatement p2 = conn.prepareStatement("DELETE FROM actividades WHERE uuid = ?")) {
                p1.setString(1, nombreAnt); p1.executeUpdate(); p2.setString(1, uuid); p2.executeUpdate();
            }
            app.soltarContactosHuerfanos(conn, numeral);
            return;
        }
        if (numeral < 0) {
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(numeral), 0) + 1 FROM actividades")) {
                numeral = rs.next() ? rs.getInt(1) : 1;
            }
        }
        String nombre = x.getOrDefault("nombre", "ACTIVIDAD") + " #" + numeral;
        String[] campos = {"tipo", "creador", "bronce", "plata", "oro", "cat6", "cat6u", "fecha", "banda", "modo", "indrc"};
        if (nombreAnt == null) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO actividades (nombre, tipo, operador_creador, umbral_bronce, umbral_plata, umbral_oro, "
                    + "cat6_nombre, cat6_umbral, fecha_creacion, banda_simple, modo_simple, indicativo_rc, numeral, radioclub, uuid) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)")) {
                ps.setString(1, nombre);
                for (int i = 0; i < campos.length; i++) ps.setString(i + 2, x.getOrDefault(campos[i], ""));
                ps.setInt(13, numeral);
                ps.setString(14, uuid);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE actividades SET nombre = ?, tipo = ?, operador_creador = ?, umbral_bronce = ?, umbral_plata = ?, "
                    + "umbral_oro = ?, cat6_nombre = ?, cat6_umbral = ?, fecha_creacion = ?, banda_simple = ?, modo_simple = ?, indicativo_rc = ?, radioclub = 1 WHERE uuid = ?")) {
                ps.setString(1, nombre);
                for (int i = 0; i < campos.length; i++) ps.setString(i + 2, x.getOrDefault(campos[i], ""));
                ps.setString(13, uuid);
                ps.executeUpdate();
            }
            if (!nombre.equals(nombreAnt)) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET actividad = ? WHERE actividad_numeral = ?")) {
                    ps.setString(1, nombre); ps.setInt(2, numeral); ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM actividad_puntos WHERE actividad = ?")) { ps.setString(1, nombreAnt); ps.executeUpdate(); }
        }
        String puntos = x.getOrDefault("puntos", "");
        if (!puntos.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO actividad_puntos (actividad, tipo, valor, puntos) VALUES (?, ?, ?, ?)")) {
                for (String fila : puntos.split("\n")) {
                    String[] c = fila.split("\t");
                    if (c.length < 3) continue;
                    ps.setString(1, nombre); ps.setString(2, c[0]); ps.setString(3, c[1]); ps.setInt(4, Integer.parseInt(c[2]));
                    ps.executeUpdate();
                }
            }
        }
    }

    /** Fecha de la línea que se está aplicando (para saber desde cuándo es administrador). */
    private long versionActualRol = 0;

    /** ¿Ese ordenador era administrador del radioclub en ese momento? */
    private static boolean esAdminRol(Connection conn, String dev, long f) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM rc_admins WHERE dev = ? AND desde <= ? AND (hasta = 0 OR hasta >= ?)")) {
            ps.setString(1, dev); ps.setLong(2, f); ps.setLong(3, f);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            return false;
        }
    }

    /** ¿Ese ordenador era superadministrador en ese momento? (tiene los mismos poderes que el ordenador del radioclub) */
    private static boolean esSuperRol(Connection conn, String dev, long f) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM rc_admins WHERE dev = ? AND rol = 'SUPER' AND desde <= ? AND (hasta = 0 OR hasta >= ?)")) {
            ps.setString(1, dev); ps.setLong(2, f); ps.setLong(3, f);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean soySuperRC() {
        if (!syncActivo()) return false;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            return esSuperRol(conn, java.util.Base64.getEncoder().encodeToString(llaveDispositivo().getPublic().getEncoded()), System.currentTimeMillis());
        } catch (Exception e) {
            return false;
        }
    }

    /** Poderes del ordenador del radioclub: él mismo o un superadministrador. */
    private boolean tienePoderRadioclub() { return esOrdenadorRadioclub() || soySuperRC(); }

    /** Una contraseña temporal para un operador de este ordenador (la ha puesto el radioclub o un superadministrador). */
    private static void ponerClaveTemporalLocal(String op, String sal, String hash, long f) {
        String id = op == null ? "" : op.trim().toUpperCase();
        if (id.isEmpty() || sal.isEmpty() || hash.isEmpty()) return;
        boolean esDeAqui = false;
        for (String o : LibroGuardiaUI.ConfigApp.get("OPERADORES_GUARDADOS", "").split(",")) if (o.trim().equalsIgnoreCase(id)) esDeAqui = true;
        if (!esDeAqui) return;
        long usada = 0;
        try { usada = Long.parseLong(LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_TEMP_USADA", "0")); } catch (Exception ignored) { }
        if (f <= usada) return;   // ya se usó
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_TEMP_SAL", sal);
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_TEMP_HASH", hash);
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_TEMP_F", String.valueOf(f));
    }

    /** ¿Es uno de los indicativos del propio radioclub (amateur, CB o especial)? */
    private static boolean esIndicativoDelRadioclub(String ind) {
        if (ind == null || ind.isBlank()) return false;
        String i = ind.trim().toUpperCase();
        for (String k : new String[]{"RADIOCLUB_IND_HF", "RADIOCLUB_IND_CB", "RADIOCLUB_IND_ESP"})
            if (i.equals(LibroGuardiaUI.ConfigApp.get(k, "").trim().toUpperCase())) return true;
        return false;
    }

    private static boolean estaExpulsado(Connection conn, String dev) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM rc_expulsados WHERE dev = ?")) {
            ps.setString(1, dev);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** A partir de cuántos contactos borrados de golpe se pide confirmación antes de borrarlos en el radioclub. */
    static final int LIMITE_BORRADO_MASIVO = 5;

    /** Pregunta si de verdad se quieren borrar muchos contactos del radioclub. Devuelve true si se confirma. */
    private boolean confirmarBorradoMasivo(int n) {
        final boolean[] si = {false};
        Runnable r = () -> {
            Object[] op = {"No, recuperarlos", "Sí, borrarlos en todos"};
            int e = JOptionPane.showOptionDialog(app,
                    "Se van a borrar " + n + " contactos del radioclub de golpe.\n"
                    + "Si se borran, desaparecerán también en los ordenadores de todos los socios.\n\n"
                    + "Si no lo has hecho tú a propósito (por ejemplo, has vuelto a una copia antigua),\n"
                    + "pulsa «No, recuperarlos».",
                    "¿Borrar " + n + " contactos del radioclub?", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, op, op[0]);
            si[0] = e == 1;
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r);
        } catch (Exception ex) { return false; }
        return si[0];
    }

    /** Ventana «Recuperar contactos borrados»: lo que otros ordenadores borraron del radioclub, para devolverlo. */
    void abrirPapelera() {
        DefaultTableModel m = new DefaultTableModel(new Object[]{"Borrado el (UTC)", "Borrado por", "Dónde estaba", "Fecha", "Hora", "Indicativo", "Banda", "Modo", "Operador"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        List<Integer> ids = new ArrayList<>();
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement()) {
            try { st.execute("SELECT 1 FROM qsos_papelera LIMIT 1"); } catch (Exception e) { guardarEnPapelera(conn, "", ""); }
            try (Statement st2 = conn.createStatement()) { st2.execute("ALTER TABLE qsos_papelera ADD COLUMN radioclub INTEGER"); } catch (Exception ignored) { }
            try (ResultSet rs = st.executeQuery("SELECT id, borrado_en, COALESCE(borrado_por,''), COALESCE(radioclub,1), COALESCE(actividad,''), COALESCE(actividad_numeral,0), "
                    + "fecha, hora, indicativo, banda, modo, operador FROM qsos_papelera ORDER BY id DESC")) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                    String quien = rs.getString(3).isEmpty() ? "Otro ordenador del radioclub" : rs.getString(3);
                    String donde = (rs.getInt(4) == 1 ? "LOG Radioclub" : "Mi LOG") + (rs.getInt(6) > 0 && !rs.getString(5).isEmpty() ? " · " + rs.getString(5) : "");
                    m.addRow(new Object[]{rs.getString(2), quien, donde, rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12)});
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(app, "No se ha podido abrir la papelera: " + e.getMessage(), "Recuperar contactos borrados", JOptionPane.ERROR_MESSAGE);
            return;
        }
        JTable t = new JTable(m);
        t.setAutoCreateRowSorter(true);
        JDialog d = new JDialog(app, "Recuperar contactos borrados", Dialog.ModalityType.APPLICATION_MODAL);
        JLabel nota = new JLabel("<html>Contactos borrados: los que has borrado tú (de tu LOG, de tus actividades o del radioclub) y los del radioclub "
                + "que se borraron desde otro ordenador. Selecciona los que quieras (con Ctrl o Mayúsculas se eligen varios) y pulsa «Recuperar»: "
                + "vuelven a donde estaban (los del radioclub, también a los demás socios).</html>");
        nota.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JButton recuperar = new JButton("Recuperar"), cerrar = new JButton("Cerrar");
        recuperar.addActionListener(e -> {
            int[] sel = t.getSelectedRows();
            if (sel.length == 0) { JOptionPane.showMessageDialog(d, "Selecciona primero los contactos que quieres recuperar."); return; }
            int hechos = 0;
            List<Integer> filas = new ArrayList<>();
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
                for (int v : sel) {
                    int fila = t.convertRowIndexToModel(v);
                    if (recuperarDePapelera(conn, ids.get(fila))) { hechos++; filas.add(fila); }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "No se ha podido recuperar: " + ex.getMessage());
            }
            filas.sort(java.util.Collections.reverseOrder());
            for (int f : filas) { m.removeRow(f); ids.remove(f); }
            refrescarTrasSincronizar();
            sincronizarPronto();
            JOptionPane.showMessageDialog(d, hechos == 1 ? "Recuperado 1 contacto." : "Recuperados " + hechos + " contactos.");
        });
        cerrar.addActionListener(e -> d.dispose());
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bot.add(recuperar);
        bot.add(cerrar);
        JPanel todo = new JPanel(new BorderLayout());
        todo.add(nota, BorderLayout.NORTH);
        todo.add(new JScrollPane(t), BorderLayout.CENTER);
        todo.add(bot, BorderLayout.SOUTH);
        if (m.getRowCount() == 0) todo.add(new JLabel("  La papelera está vacía."), BorderLayout.CENTER);
        d.setContentPane(todo);
        d.setSize(1000, 460);
        d.setLocationRelativeTo(app);
        d.setVisible(true);
    }

    /** Devuelve un contacto de la papelera a donde estaba (su LOG, su actividad, el radioclub). Si era del radioclub, se comparte en la próxima sincronización. */
    private static boolean recuperarDePapelera(Connection conn, int id) throws Exception {
        String uuid = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM qsos_papelera WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) uuid = rs.getString(1); }
        }
        if (uuid == null) uuid = "";
        boolean existe = false;
        if (!uuid.isEmpty()) try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM qsos WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) { existe = rs.next(); }
        }
        if (!existe) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO qsos (uuid, operador, fecha, hora, indicativo, banda, modo, freq_tx, freq_rx, "
                    + "rst_sent, rst_rcvd, nombre, qth, grid, act_uuid, puntos, num_contactos, estacion, comentario, actividad_numeral, actividad, radioclub, subido) "
                    + "SELECT p.uuid, p.operador, p.fecha, p.hora, p.indicativo, p.banda, p.modo, p.freq_tx, p.freq_rx, p.rst_sent, p.rst_rcvd, p.nombre, p.qth, "
                    + "p.grid, p.act_uuid, p.puntos, p.num_contactos, p.estacion, p.comentario, "
                    + "COALESCE((SELECT a.numeral FROM actividades a WHERE a.uuid = p.act_uuid AND COALESCE(p.act_uuid,'') <> ''), "
                    + "(SELECT a.numeral FROM actividades a WHERE a.numeral = p.actividad_numeral AND p.actividad_numeral > 0), 0), "
                    + "COALESCE((SELECT a.nombre FROM actividades a WHERE a.uuid = p.act_uuid AND COALESCE(p.act_uuid,'') <> ''), "
                    + "(SELECT p.actividad FROM actividades a WHERE a.numeral = p.actividad_numeral AND p.actividad_numeral > 0), ''), COALESCE(p.radioclub, 1), 0 "
                    + "FROM qsos_papelera p WHERE p.id = ?")) {
                ps.setInt(1, id); ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sync_pub WHERE tipo = 'QSO' AND clave = ?")) { ps.setString(1, uuid); ps.executeUpdate(); }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qsos_papelera WHERE id = ? OR (uuid = ? AND ? <> '')")) {
            ps.setInt(1, id); ps.setString(2, uuid); ps.setString(3, uuid); ps.executeUpdate();
        }
        return true;
    }

    /**
     * Papelera del radioclub: cuando otro ordenador borra un contacto, aquí se guarda una copia completa antes de
     * quitarlo (fecha del borrado y quién lo pidió). Así, si se borra algo por error, se puede recuperar.
     */
    static void guardarEnPapelera(Connection conn, String uuid, String quien) {
        guardarEnPapeleraDonde(conn, "uuid = ?", uuid, quien);
    }

    /** Igual, buscando el contacto por su número (id) en la base de datos: para lo que borra uno mismo. */
    static void guardarEnPapeleraPorId(Connection conn, int id, String quien) {
        guardarEnPapeleraDonde(conn, "id = ?", String.valueOf(id), quien);
    }

    private static void guardarEnPapeleraDonde(Connection conn, String donde, String valor, String quien) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS qsos_papelera (id INTEGER PRIMARY KEY AUTOINCREMENT, borrado_en TEXT, borrado_por TEXT, "
                    + "uuid TEXT, operador TEXT, fecha TEXT, hora TEXT, indicativo TEXT, banda TEXT, modo TEXT, freq_tx TEXT, freq_rx TEXT, "
                    + "rst_sent TEXT, rst_rcvd TEXT, nombre TEXT, qth TEXT, grid TEXT, actividad TEXT, actividad_numeral INTEGER, act_uuid TEXT, "
                    + "puntos INTEGER, num_contactos INTEGER, estacion TEXT, comentario TEXT)");
        } catch (Exception ignored) { }
        try (Statement st = conn.createStatement()) { st.execute("ALTER TABLE qsos_papelera ADD COLUMN radioclub INTEGER"); } catch (Exception ignored) { }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO qsos_papelera (borrado_en, borrado_por, uuid, operador, fecha, hora, indicativo, "
                + "banda, modo, freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, actividad, actividad_numeral, act_uuid, puntos, num_contactos, "
                + "estacion, comentario, radioclub) SELECT datetime('now'), ?, uuid, operador, fecha, hora, indicativo, banda, modo, freq_tx, freq_rx, rst_sent, "
                + "rst_rcvd, nombre, qth, grid, actividad, actividad_numeral, act_uuid, puntos, num_contactos, estacion, comentario, COALESCE(radioclub,0) FROM qsos WHERE " + donde)) {
            ps.setString(1, quien == null ? "" : quien);
            if (donde.startsWith("id")) ps.setInt(2, Integer.parseInt(valor)); else ps.setString(2, valor);
            ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    private void aplicarQso(Connection conn, String uuid, boolean borrar, java.util.SortedMap<String, String> x, String miPub) throws Exception {
        if (borrar) {
            String[] ver = versionDe(conn, "QSO", uuid);
            boolean esMio = ver != null && miPub.equals(ver[1]);
            String indQuitado = null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT indicativo FROM qsos WHERE uuid = ? AND UPPER(banda) = 'CB'")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) indQuitado = rs.getString(1); }
            }
            if (!esMio) guardarEnPapelera(conn, uuid, "Otro ordenador del radioclub");   // antes de borrarlo, se guarda por si hay que recuperarlo
            try (PreparedStatement ps = conn.prepareStatement(esMio ? "UPDATE qsos SET radioclub = 0 WHERE uuid = ?" : "DELETE FROM qsos WHERE uuid = ?")) {
                ps.setString(1, uuid); ps.executeUpdate();
            }
            if (indQuitado != null) app.quitarDeListaCBSiNoQuedaEnRadioclub(conn, indQuitado);
            return;
        }
        String actUuid = x.getOrDefault("act", "");
        int numeral = 0;
        String actNombre = "";
        if (!actUuid.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT numeral, nombre FROM actividades WHERE uuid = ?")) {
                ps.setString(1, actUuid);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { numeral = rs.getInt(1); actNombre = rs.getString(2); } }
            }
        }
        boolean existe;
        String indAntes = null, bandaAntes = null, indicativoCBCambiado = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT indicativo, banda FROM qsos WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) { existe = rs.next(); if (existe) { indAntes = rs.getString(1); bandaAntes = rs.getString(2); } }
        }
        if (existe && indAntes != null && "CB".equalsIgnoreCase(bandaAntes) && !app.txtCB(indAntes).equals(app.txtCB(x.getOrDefault("ind", "")))) {
            indicativoCBCambiado = indAntes;   // al terminar, el antiguo se quita de la lista si ya no queda en el radioclub
        }
        String[] n = {"op", "fecha", "hora", "ind", "banda", "modo", "ftx", "frx", "rsts", "rstr", "nom", "qth", "grid", "puntos", "num", "est"};
        String sql = existe
                ? "UPDATE qsos SET operador = ?, fecha = ?, hora = ?, indicativo = ?, banda = ?, modo = ?, freq_tx = ?, freq_rx = ?, rst_sent = ?, rst_rcvd = ?, "
                + "nombre = ?, qth = ?, grid = ?, puntos = ?, num_contactos = ?, estacion = ?, act_uuid = ?, actividad_numeral = ?, actividad = ?, modificado_por = ?, radioclub = 1 WHERE uuid = ?"
                : "INSERT INTO qsos (operador, fecha, hora, indicativo, banda, modo, freq_tx, freq_rx, rst_sent, rst_rcvd, nombre, qth, grid, puntos, num_contactos, "
                + "estacion, act_uuid, actividad_numeral, actividad, modificado_por, radioclub, subido, uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < n.length; i++) {
                String v = x.getOrDefault(n[i], "");
                if (n[i].equals("puntos") || n[i].equals("num")) ps.setInt(i + 1, v.isEmpty() ? 0 : Integer.parseInt(v));
                else ps.setString(i + 1, v);
            }
            ps.setString(17, actUuid);
            ps.setInt(18, numeral);
            ps.setString(19, actNombre);
            ps.setString(20, x.getOrDefault("mod", ""));
            ps.setString(21, uuid);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET comentario = ? WHERE uuid = ?")) {
            ps.setString(1, x.getOrDefault("com", "")); ps.setString(2, uuid); ps.executeUpdate();
        }
        if (indicativoCBCambiado != null) app.quitarDeListaCBSiNoQuedaEnRadioclub(conn, indicativoCBCambiado);
    }

    /**
     * LOG Radioclub: cualquier socio puede cambiar los datos de un contacto (queda apuntado quién);
     * quitar o borrar contactos de otro operador solo se hace en el ordenador del radioclub.
     */
    /** Pasa los contactos seleccionados (de cualquier log o actividad) a otra actividad, o los saca de la actividad. */
    void pasarSeleccionadosAActividad() {
        int[] filasSel = app.tablaQSOs.getSelectedRows();
        List<Integer> ids = new ArrayList<>();
        for (int f : filasSel) {
            Object idObj = app.modeloTabla.getValueAt(app.tablaQSOs.convertRowIndexToModel(f), app.COL_ESTANDAR_ID);
            if (idObj instanceof Integer) ids.add((Integer) idObj);
        }
        if (ids.isEmpty()) return;
        int numeralActual = app.obtenerNumeralDeActividadDesdeSeleccion();

        // Destinos: "ninguna" (solo si se está en una actividad) y todas las actividades menos la actual
        List<Integer> numerales = new ArrayList<>();
        List<String> internos = new ArrayList<>(), tipos = new ArrayList<>(), indsRc = new ArrayList<>();
        List<Boolean> esRc = new ArrayList<>();
        List<String> etiquetas = new ArrayList<>();
        if (numeralActual > 0) {
            numerales.add(0); internos.add(""); tipos.add(""); esRc.add(false); indsRc.add("");
            etiquetas.add("(Ninguna: sacarlos de esta actividad y dejarlos solo en el log)");
        }
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT numeral, nombre, COALESCE(tipo,'SIMPLE'), COALESCE(radioclub,0), COALESCE(indicativo_rc,'') "
                     + "FROM actividades WHERE numeral > 0 ORDER BY numeral DESC")) {
            while (rs.next()) {
                if (rs.getInt(1) == numeralActual) continue;
                boolean rc = rs.getInt(4) == 1;
                numerales.add(rs.getInt(1));
                internos.add(rs.getString(2));
                tipos.add(rs.getString(3));
                esRc.add(rc);
                indsRc.add(rs.getString(5));
                etiquetas.add(app.nombreVisibleDeActividad(rs.getString(2)) + (rc ? "  (radioclub)" : ""));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(app, "Error al leer las actividades: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (numerales.isEmpty()) {
            JOptionPane.showMessageDialog(app, "No hay ninguna otra actividad. Créala primero con Nueva Actividad.", "Pasar a otra actividad", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // ¿Alguno de los seleccionados está en el LOG Radioclub?
        boolean hayDelRc = false;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(radioclub,0) FROM qsos WHERE id = ?")) {
            for (int id : ids) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next() && rs.getInt(1) == 1) { hayDelRc = true; break; } }
            }
        } catch (Exception ignored) { }

        JComboBox<String> cbAct = new JComboBox<>(etiquetas.toArray(new String[0]));
        JCheckBox chkQuitarRc = new JCheckBox("Quitarlos también del LOG Radioclub (no eran del radioclub)");
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(new JLabel("<html>¿A qué actividad pasas " + (ids.size() == 1 ? "este contacto" : "estos " + ids.size() + " contactos") + "?<br>"
                + "No se duplican: cada contacto se queda en su log y cambia de actividad.</html>"), BorderLayout.NORTH);
        p.add(cbAct, BorderLayout.CENTER);
        p.add(chkQuitarRc, BorderLayout.SOUTH);
        final boolean hayDelRcF = hayDelRc;
        Runnable verCasilla = () -> {
            int k = cbAct.getSelectedIndex();
            boolean destinoRc = k >= 0 && esRc.get(k);
            chkQuitarRc.setVisible(hayDelRcF && !destinoRc && numerales.get(Math.max(k, 0)) > 0);
            if (!chkQuitarRc.isVisible()) chkQuitarRc.setSelected(false);
            Window w = SwingUtilities.getWindowAncestor(p);
            if (w != null) w.pack();
        };
        cbAct.addActionListener(e -> verCasilla.run());
        verCasilla.run();
        int r = JOptionPane.showConfirmDialog(app, p, "Pasar a otra actividad", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION || cbAct.getSelectedIndex() < 0) return;
        int i = cbAct.getSelectedIndex();
        int numeral = numerales.get(i);
        String interno = internos.get(i);
        boolean actRc = esRc.get(i);
        String indRc = indsRc.get(i);
        boolean quitarRc = chkQuitarRc.isVisible() && chkQuitarRc.isSelected();
        if ("PUNTOS".equalsIgnoreCase(tipos.get(i))) {
            int rp = JOptionPane.showConfirmDialog(app, "Es una actividad por puntos: los contactos pasan con 0 puntos\n"
                    + "(luego puedes ajustarlos en la tabla). ¿Continuar?", "Pasar a otra actividad", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (rp != JOptionPane.YES_OPTION) return;
        }

        boolean permisoOtros = !syncActivo() || tengoPermiso("CONTACTOS");
        String yo = app.operadorActual == null ? "" : app.operadorActual;
        int pasados = 0, sinPermiso = 0;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            conn.setAutoCommit(false);
            try (PreparedStatement psOp = conn.prepareStatement("SELECT COALESCE(operador,'') FROM qsos WHERE id = ?");
                 PreparedStatement psUpd = conn.prepareStatement("UPDATE qsos SET actividad_numeral = ?, actividad = ?, modificado_por = ?, "
                         + "act_uuid = COALESCE((SELECT uuid FROM actividades WHERE numeral = ? AND numeral > 0), '') WHERE id = ?");
                 PreparedStatement psRc = conn.prepareStatement("UPDATE qsos SET radioclub = 1, "
                         + "estacion = CASE WHEN COALESCE(estacion,'') = '' THEN ? ELSE estacion END WHERE id = ?");
                 PreparedStatement psSinRc = conn.prepareStatement("UPDATE qsos SET radioclub = 0 WHERE id = ?")) {
                for (int id : ids) {
                    String op = "";
                    psOp.setInt(1, id);
                    try (ResultSet rs = psOp.executeQuery()) { if (rs.next()) op = rs.getString(1); }
                    if (!(op.isEmpty() || op.equalsIgnoreCase(yo) || permisoOtros)) { sinPermiso++; continue; }
                    psUpd.setInt(1, numeral);
                    psUpd.setString(2, interno);
                    psUpd.setString(3, yo);
                    psUpd.setInt(4, numeral);
                    psUpd.setInt(5, id);
                    psUpd.executeUpdate();
                    if (actRc) {
                        // En una actividad del radioclub, el contacto también va al LOG Radioclub
                        psRc.setString(1, indRc);
                        psRc.setInt(2, id);
                        psRc.executeUpdate();
                    } else if (quitarRc) {
                        psSinRc.setInt(1, id);
                        psSinRc.executeUpdate();
                    }
                    pasados++;
                }
                conn.commit();
            } catch (Exception exTx) {
                conn.rollback();
                throw exTx;
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(app, "No se han podido pasar los contactos: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String msg = numeral > 0
                ? pasados + (pasados == 1 ? " contacto pasado" : " contactos pasados") + " a la actividad " + app.nombreVisibleDeActividad(interno) + "."
                : pasados + (pasados == 1 ? " contacto sacado" : " contactos sacados") + " de la actividad (siguen en el log).";
        if (sinPermiso > 0) msg += "\n\n" + sinPermiso + " son de otros operadores y tu papel no tiene permiso para cambiarlos.\n"
                + "Hazlo desde el PC Radioclub.";
        if (syncActivo() && pasados > 0 && (actRc || hayDelRc)) msg += "\n\nPulsa Sincronizar para que el cambio llegue a los demás.";
        JOptionPane.showMessageDialog(app, msg, "Pasar a otra actividad", JOptionPane.INFORMATION_MESSAGE);
        app.cargarQSOsDesdeBaseDatos();
    }

    boolean puedeTocarQso(int idQso, boolean borrar) {
        // Los contactos de un operador solo los cambia ese operador (que ha entrado con su contraseña) o el ordenador del radioclub
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(operador,'') FROM qsos WHERE id = ?")) {
            ps.setInt(1, idQso);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return true;
                String op = rs.getString(1);
                if (op.isEmpty() || op.equalsIgnoreCase(app.operadorActual == null ? "" : app.operadorActual)) return true;
                if (syncActivo() && tengoPermiso("CONTACTOS")) return true;
                JOptionPane.showMessageDialog(app, "Este contacto es de " + op + ".\n\n"
                                + "Solo lo puede " + (borrar ? "quitar o borrar" : "cambiar") + " " + op + " (entrando con su contraseña), el PC Radioclub o quien tenga ese permiso en el radioclub.",
                        "Contacto de otro operador", JOptionPane.INFORMATION_MESSAGE);
                return false;
            }
        } catch (Exception e) {
            return true;
        }
    }

    // ===================== CARGOS DEL RADIOCLUB, CONTRASEÑAS Y REGISTRO DE CAMBIOS =====================
    private static final String SEP_REG = "\u001F";   // separa los datos guardados para deshacer

    /**
     * Contraseña maestra: es la contraseña del radioclub y solo vale EN EL PC RADIOCLUB, para entrar en cualquier usuario
     * de ese ordenador (por ejemplo, si un socio olvida la suya allí). En los ordenadores de los socios no abre nada.
     */
    private static boolean esClaveMaestra(char[] c) {
        if (c == null || c.length == 0) return false;
        String admin = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", ""), yo = LibroGuardiaUI.ConfigApp.get("SYNC_DEV_PUB", "");
        if (admin.isEmpty() || !admin.equals(yo)) return false;
        String sal = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_SAL", ""), ver = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_VER", "");
        if (sal.isEmpty() || ver.isEmpty()) return false;
        return sha256B64(cifrarClave(c, sal)).equals(ver);
    }

    /** Sal fija para comprobar la contraseña maestra cuando el radioclub todavía no ha puesto la suya. */
    private static final String SAL_SIN_CLAVE_RC = java.util.Base64.getEncoder().encodeToString("Bitacora26-radioclub".getBytes(StandardCharsets.UTF_8));

    private static String sha256B64(String b64) {
        try {
            return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(b64)));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Prueba de que se sabe la contraseña del radioclub (sirve para que un administrador se quede con el ordenador del radioclub).
     * Los demás ordenadores solo guardan una "huella" de esa prueba, así que viéndola no se puede sacar.
     */
    private static boolean pruebaClaveRadioclubValida(String prueba) {
        if (prueba == null || prueba.isEmpty()) return false;
        String sal = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_SAL", ""), ver = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_VER", "");
        return !ver.isEmpty() && sha256B64(prueba).equals(ver);
    }

    /** Pide la contraseña del radioclub. Devuelve la prueba (para mandarla) o null si se cancela o no es correcta. */
    private String pedirClaveRadioclub(Component padre, String para) {
        String sal = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_SAL", "");
        if (sal.isEmpty() && LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_VER", "").isEmpty()) sal = "";
        for (int intento = 0; intento < 3; intento++) {
            JPasswordField pf = new JPasswordField(16);
            int r = JOptionPane.showConfirmDialog(padre, new Object[]{"Contraseña del radioclub para " + para + ":", pf},
                    "Contraseña del radioclub", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            char[] c = pf.getPassword();
            String prueba = cifrarClave(c, sal.isEmpty() ? SAL_SIN_CLAVE_RC : sal);
            if (pruebaClaveRadioclubValida(prueba)) return prueba;
            JOptionPane.showMessageDialog(padre, sal.isEmpty() ? "El ordenador del radioclub todavía no ha puesto contraseña." : "Contraseña incorrecta.",
                    "Contraseña del radioclub", JOptionPane.WARNING_MESSAGE);
        }
        return null;
    }

    // ---- Contraseña de cada operador (se guarda cifrada en este ordenador) ----
    static boolean operadorTieneClave(String id) {
        return !LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_CLAVE", "").isEmpty() || !LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_TEMP_HASH", "").isEmpty();
    }

    /** Tras entrar con la contraseña temporal: hay que poner una nueva (y la temporal deja de valer). */
    static boolean cambiarTemporalSiHaceFalta(Component padre, String id) {
        if (!entroConTemporal) return true;
        entroConTemporal = false;
        char[] nueva = pedirClaveNueva(padre, "Contraseña nueva de " + id, "<html>Has entrado con una contraseña temporal.<br>Pon ahora tu contraseña nueva:</html>");
        if (nueva == null) return false;
        guardarClaveOperador(id, nueva);
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_TEMP_USADA", LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_TEMP_F", "0"));
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_TEMP_SAL", "");
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_TEMP_HASH", "");
        return true;
    }

    static void guardarClaveOperador(String id, char[] clave) {
        String sal = nuevaSal();
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_SAL", sal);
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_CLAVE", cifrarClave(clave, sal));
    }

    static boolean claveOperadorCorrecta(String id, char[] clave) {
        entroConTemporal = false;
        if (esClaveMaestra(clave)) return true;   // contraseña del radioclub, solo en el PC Radioclub
        // Contraseña temporal (la ha puesto el radioclub o un superadministrador): vale una vez
        String ts = LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_TEMP_SAL", ""), th = LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_TEMP_HASH", "");
        String limpia = new String(clave).trim().toUpperCase();
        if (!ts.isEmpty() && !th.isEmpty() && cifrarClave(limpia.toCharArray(), ts).equals(th)) {
            entroConTemporal = true;
            return true;
        }
        String sal = LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_SAL", ""), h = LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_CLAVE", "");
        boolean aqui = !sal.isEmpty() && !h.isEmpty() && cifrarClave(clave, sal).equals(h);
        // Con cuenta Bitácora 26 la contraseña es la de la cuenta: si no coincide con la de aquí, o se ha cambiado en la web,
        // se pregunta a la cuenta (si hay internet) y se apunta la buena
        String cuenta = CuentaBitacora.cuentaB26DeOperador(id);
        boolean obsoleta = "1".equals(LibroGuardiaUI.ConfigApp.get("OPERADOR_FICHA_" + id + "_CLAVE_OBSOLETA", "0"));
        if (!cuenta.isEmpty() && CuentaBitacora.instanciaB26 != null && (!aqui || obsoleta)) {
            Boolean srv = CuentaBitacora.instanciaB26.cuentaB26.comprobarClaveEnServidor(cuenta, new String(clave));
            if (Boolean.TRUE.equals(srv)) {
                guardarClaveOperador(id, clave);
                LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_CLAVE_OBSOLETA", "0");
                return true;
            }
            if (Boolean.FALSE.equals(srv)) return false;
        }
        return aqui;
    }

    /** Contraseña temporal fácil de dictar (sin letras que se confunden). */
    private static String claveTemporalAleatoria() {
        String letras = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(letras.charAt(r.nextInt(letras.length())));
        return sb.toString();
    }

    /** La última contraseña aceptada era la temporal: hay que poner una nueva. */
    private static volatile boolean entroConTemporal = false;

    /** Pide dos veces una contraseña nueva. Devuelve null si se cancela. */
    static char[] pedirClaveNueva(Component padre, String titulo, String texto) {
        while (true) {
            JPasswordField p1 = new JPasswordField(16), p2 = new JPasswordField(16);
            int r = JOptionPane.showConfirmDialog(padre, new Object[]{texto, "Contraseña:", p1, "Repítela:", p2}, titulo,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            if (p1.getPassword().length < 4) { JOptionPane.showMessageDialog(padre, "Pon como mínimo 4 caracteres.", titulo, JOptionPane.WARNING_MESSAGE); continue; }
            if (!java.util.Arrays.equals(p1.getPassword(), p2.getPassword())) { JOptionPane.showMessageDialog(padre, "Las dos contraseñas no coinciden.", titulo, JOptionPane.WARNING_MESSAGE); continue; }
            return p1.getPassword();
        }
    }

    /**
     * Comprueba la contraseña del operador. Si todavía no tiene, se le pide que ponga una.
     * Devuelve true si se puede seguir.
     */
    boolean comprobarClaveOperador(Component padre, String id, String para) {
        if (id == null || id.isBlank()) return false;
        if (!operadorTieneClave(id)) {
            char[] nueva = pedirClaveNueva(padre, "Contraseña de " + id, "<html>El operador <b>" + id + "</b> todavía no tiene contraseña.<br>"
                    + "Ponle una: se pedirá al elegirlo, para que nadie use un operador que no es el suyo.</html>");
            if (nueva == null) return false;
            guardarClaveOperador(id, nueva);
            return true;
        }
        for (int intento = 0; intento < 3; intento++) {
            JPasswordField pf = new JPasswordField(16);
            Object[] opciones = {"Aceptar", "He olvidado mi contraseña", "Cancelar"};
            JOptionPane op = new JOptionPane(new Object[]{"Contraseña de " + id + (para == null ? "" : " para " + para) + ":", pf},
                    JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, opciones, opciones[0]);
            JDialog dlg = op.createDialog(padre, "Contraseña del operador");
            dlg.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                @Override public void windowGainedFocus(java.awt.event.WindowEvent e) { pf.requestFocusInWindow(); }
            });
            pf.addActionListener(e -> op.setValue(opciones[0]));   // Intro = Aceptar
            dlg.setVisible(true);
            dlg.dispose();
            Object r = op.getValue();
            if (opciones[1].equals(r)) {
                if (recuperarClaveOperador(padre, id)) return true;
                continue;
            }
            if (!opciones[0].equals(r)) return false;
            if (claveOperadorCorrecta(id, pf.getPassword())) return cambiarTemporalSiHaceFalta(padre, id);
            JOptionPane.showMessageDialog(padre, "Contraseña incorrecta.", "Contraseña del operador", JOptionPane.WARNING_MESSAGE);
        }
        return false;
    }

    /**
     * «He olvidado mi contraseña»: con cuenta Bitácora 26, un código al correo de la cuenta y se pone una nueva
     * (aquí y en la cuenta, que es la misma). Sin cuenta: la contraseña temporal del radioclub.
     */
    private boolean recuperarClaveOperador(Component padre, String id) {
        String cuenta = app.cuentaB26.cuentaB26DeOperador(id);
        if (cuenta.isEmpty()) {
            boolean hayRc = radioclubConfigurado() || !LibroGuardiaUI.ConfigApp.get("RADIOCLUB_NOMBRE", "").trim().isEmpty();
            JOptionPane.showMessageDialog(padre, hayRc
                    ? "El operador " + id + " no tiene cuenta Bitácora 26, así que no se le puede mandar un código al correo.\n\n"
                      + "Pide a " + nombreRadioclub() + " (o a un superadministrador) una contraseña temporal:\n"
                      + "Mi Estación → Radioclub → Socios del radioclub… → Contraseña temporal.\n"
                      + "Con ella entras una vez y pones una contraseña nueva."
                    : "El operador " + id + " no tiene cuenta Bitácora 26, así que no se le puede mandar un código al correo.\n\n"
                      + "Por seguridad, sin la contraseña no se pueden cambiar sus datos.\n"
                      + "Si creas una cuenta Bitácora 26 (Mi Estación → Operador), la próxima vez podrás recuperarla con tu correo.",
                    "He olvidado mi contraseña", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        String ind = app.cuentaB26.indDeCuenta(cuenta);
        if (JOptionPane.showConfirmDialog(padre, "Te mandamos un código al correo de tu cuenta Bitácora 26 (" + ind + ")\n"
                + "para poner una contraseña nueva. ¿Seguimos?", "He olvidado mi contraseña", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return false;
        String[] r = app.cuentaB26.postCuentas("/clave_olvidada", "{\"ind\":\"" + app.jsonTexto(ind) + "\"}");
        if (!"200".equals(r[0])) {
            JOptionPane.showMessageDialog(padre, ("0".equals(r[0]) ? "Sin conexión con bitacora26.es" : app.cuentaB26.errorJson(r)) + ".",
                    "He olvidado mi contraseña", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        String pruebas = app.campoJson(r[1], "codigo_pruebas");   // solo en el servidor de pruebas
        JTextField txtCodigo = new JTextField(pruebas, 10);
        JPasswordField pf1 = new JPasswordField(16), pf2 = new JPasswordField(16);
        while (true) {
            Object[] form = {"Escribe el código que te ha llegado al correo:", txtCodigo, " ", "Contraseña nueva (al menos 8 caracteres):", pf1, "Repítela:", pf2,
                    "<html><font size='2' color='#8796ad'>Será también la de tu cuenta Bitácora 26 y la de la web.</font></html>"};
            if (JOptionPane.showConfirmDialog(padre, form, "Contraseña nueva", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return false;
            char[] c1 = pf1.getPassword(), c2 = pf2.getPassword();
            String falta = txtCodigo.getText().trim().isEmpty() ? "Escribe el código del correo."
                    : c1.length < 8 ? "La contraseña tiene que tener al menos 8 caracteres."
                    : !java.util.Arrays.equals(c1, c2) ? "Las dos contraseñas no coinciden." : null;
            if (falta != null) { JOptionPane.showMessageDialog(padre, falta, "Contraseña nueva", JOptionPane.WARNING_MESSAGE); continue; }
            String[] rn = app.cuentaB26.postCuentas("/clave_nueva", "{\"ind\":\"" + app.jsonTexto(ind) + "\",\"codigo\":\"" + app.jsonTexto(txtCodigo.getText().trim())
                    + "\",\"clave\":\"" + app.jsonTexto(new String(c1)) + "\"}");
            if (!"200".equals(rn[0])) {
                JOptionPane.showMessageDialog(padre, ("0".equals(rn[0]) ? "Sin conexión con bitacora26.es" : app.cuentaB26.errorJson(rn)) + ".",
                        "Contraseña nueva", JOptionPane.WARNING_MESSAGE);
                if ("0".equals(rn[0])) return false;
                continue;
            }
            guardarClaveOperador(id, c1);
            LibroGuardiaUI.ConfigApp.put("B26_CLAVE_CAMBIO_" + cuenta, String.valueOf(System.currentTimeMillis() / 1000 + 5));
            LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_CLAVE_OBSOLETA", "0");
            JOptionPane.showMessageDialog(padre, "Hecho: ya tienes tu contraseña nueva (aquí y en tu cuenta Bitácora 26).",
                    "Contraseña nueva", JOptionPane.INFORMATION_MESSAGE);
            return true;
        }
    }

    /** Contraseña cifrada (PBKDF2, no se puede leer). */
    private static String cifrarClave(char[] clave, String sal) {
        try {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] h = f.generateSecret(new javax.crypto.spec.PBEKeySpec(clave, java.util.Base64.getDecoder().decode(sal), 120_000, 256)).getEncoded();
            return java.util.Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String nuevaSal() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    /**
     * Un operador que es socio del radioclub: en el ordenador del radioclub se añade a la lista de operadores
     * (sin cambiar su ficha si ya estaba) y a la lista de socios (si no estaba ya, por su indicativo HF o CB).
     */
    private void apuntarOperadorDelRadioclub(Connection conn, String[] d) throws Exception {
        String id = app.idOperador(d);
        if (id.isEmpty()) return;
        if (esIndicativoDelRadioclub(d[0]) || esIndicativoDelRadioclub(d[1])) return;   // el radioclub no es un socio
        List<String> lista = app.obtenerListaOperadores();
        if (!lista.contains(id)) {
            lista.add(id);
            app.guardarListaOperadores(lista);
            String k = "OPERADOR_FICHA_" + id + "_";
            LibroGuardiaUI.ConfigApp.put(k + "HF", d[0]); LibroGuardiaUI.ConfigApp.put(k + "CB", d[1]); LibroGuardiaUI.ConfigApp.put(k + "NOMBRE", d[2]);
            LibroGuardiaUI.ConfigApp.put(k + "QTH", d[3]); LibroGuardiaUI.ConfigApp.put(k + "GRID", d[4]);
            app.anadirOperadorCBAlDirectorio(d);
        }
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_SOCIO", "1");
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM socios WHERE (? <> '' AND UPPER(ind_hf) = ?) OR (? <> '' AND UPPER(ind_cb) = ?)")) {
            ps.setString(1, d[0]); ps.setString(2, d[0]); ps.setString(3, d[1]); ps.setString(4, d[1]);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return; }   // ya es socio
        }
        int num = 1;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(num), 0) + 1 FROM socios")) { if (rs.next()) num = rs.getInt(1); }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO socios (num, ind_hf, ind_cb, nombre, qth, uuid) VALUES (?, ?, ?, ?, ?, lower(hex(randomblob(16))))")) {
            ps.setInt(1, num); ps.setString(2, d[0]); ps.setString(3, d[1]); ps.setString(4, d[2]); ps.setString(5, d[3]);
            ps.executeUpdate();
        }
        socioNuevoPorSync = true;
    }

    /** Guarda si el operador es socio del radioclub y, si lo es, hace que llegue al radioclub. */
    void marcarOperadorSocio(String id, String[] datos, boolean socio) {
        if (id == null || id.isEmpty()) return;
        LibroGuardiaUI.ConfigApp.put("OPERADOR_FICHA_" + id + "_SOCIO", socio ? "1" : "0");
        if (socio && syncActivo() && tengoPermiso("SOCIOS")) {
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
                apuntarOperadorDelRadioclub(conn, datos);
            } catch (Exception ex) {
                System.err.println("No se pudo apuntar el socio: " + ex.getMessage());
            }
            socioNuevoPorSync = false;
        }
        sincronizarPronto();   // en los demás ordenadores, se envía al del radioclub
    }

    private boolean esOrdenadorRadioclub() {
        try {
            String admin = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "");
            return !admin.isEmpty() && admin.equals(java.util.Base64.getEncoder().encodeToString(llaveDispositivo().getPublic().getEncoded()));
        } catch (Exception e) {
            return false;
        }
    }

    /** Apunta un cambio del radioclub: quién, qué, cuándo, y lo necesario para deshacerlo. */
    private void registrarCambio(String accion, String tipo, String datos) {
        String quien = app.operadorActual == null ? "" : app.operadorActual;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO rc_registro (fecha, quien, accion, tipo, datos) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + " UTC");
            ps.setString(2, quien);
            ps.setString(3, accion);
            ps.setString(4, tipo);
            ps.setString(5, datos == null ? "" : datos);
            ps.executeUpdate();
        } catch (Exception ex) {
            System.err.println("No se pudo apuntar el cambio: " + ex.getMessage());
        }
    }

    /** Copia de un socio y sus cuotas (para deshacer): id, num, hf, cb, nombre, qth, "año:fecha;año:fecha" */
    private static String copiaSocio(Connection conn, int id) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement("SELECT num, ind_hf, ind_cb, nombre, qth FROM socios WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "";
                sb.append(id).append(SEP_REG).append(rs.getInt(1));
                for (int i = 2; i <= 5; i++) sb.append(SEP_REG).append(rs.getString(i) == null ? "" : rs.getString(i));
            }
        }
        StringBuilder cuotas = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement("SELECT anio, fecha_pago FROM socios_cuotas WHERE socio_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cuotas.append(rs.getInt(1)).append(':').append(rs.getString(2)).append(';');
            }
        }
        return sb.append(SEP_REG).append(cuotas).toString();
    }

    private static String descripcionSocio(String copia) {
        if (copia == null || copia.isEmpty()) return "";
        String[] c = copia.split(SEP_REG, -1);
        String ind = !c[2].isEmpty() ? c[2] : c[3];
        return "nº " + c[1] + (ind.isEmpty() ? "" : " " + ind) + (c[4].isEmpty() ? "" : " (" + c[4] + ")");
    }

    private static String fechaCuota(Connection conn, int id, int anio) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT fecha_pago FROM socios_cuotas WHERE socio_id = ? AND anio = ?")) {
            ps.setInt(1, id); ps.setInt(2, anio);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    /** Vuelve a dejar un socio como estaba en la copia (con sus cuotas). */
    private static void restaurarSocio(Connection conn, String copia) throws Exception {
        if (copia == null || copia.isEmpty()) return;
        String[] c = copia.split(SEP_REG, -1);
        int id = Integer.parseInt(c[0]);
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO socios (id, num, ind_hf, ind_cb, nombre, qth) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, id); ps.setInt(2, Integer.parseInt(c[1]));
            for (int i = 2; i <= 5; i++) ps.setString(i + 1, c[i]);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM socios_cuotas WHERE socio_id = ?")) { ps.setInt(1, id); ps.executeUpdate(); }
        if (c.length > 6 && !c[6].isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO socios_cuotas (socio_id, anio, fecha_pago) VALUES (?, ?, ?)")) {
                for (String q : c[6].split(";")) {
                    if (q.isEmpty()) continue;
                    int k = q.indexOf(':');
                    ps.setInt(1, id); ps.setInt(2, Integer.parseInt(q.substring(0, k))); ps.setString(3, q.substring(k + 1));
                    ps.executeUpdate();
                }
            }
        }
    }

    /** Ventana con el registro de cambios; un administrador puede deshacer el que elija. */
    private void abrirRegistroCambios(Component padre) {
        Window w = SwingUtilities.getWindowAncestor(padre instanceof Component ? padre : app);
        JDialog d = new JDialog(w, "Registro de cambios del radioclub", Dialog.ModalityType.APPLICATION_MODAL);
        DefaultTableModel m = new DefaultTableModel(new Object[]{"Fecha", "Quién", "Cambio", "id", "tipo", "datos", "deshecho"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable t = new JTable(m);
        t.setRowHeight(22);
        for (int k = 0; k < 4; k++) t.removeColumn(t.getColumnModel().getColumn(3));
        t.getColumnModel().getColumn(0).setPreferredWidth(140);
        t.getColumnModel().getColumn(1).setPreferredWidth(90);
        t.getColumnModel().getColumn(2).setPreferredWidth(560);
        Runnable cargar = () -> {
            m.setRowCount(0);
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT fecha, quien, accion, id, tipo, datos, deshecho FROM rc_registro ORDER BY id DESC")) {
                while (rs.next()) m.addRow(new Object[]{rs.getString(1), rs.getString(2),
                        (rs.getInt(7) == 1 ? "[DESHECHO] " : "") + rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getInt(7)});
            } catch (Exception ignored) { }
        };
        cargar.run();
        JButton bDeshacer = app.cat.botonCtrl("Deshacer el cambio elegido"), bCerrar = app.cat.botonCtrl("Cerrar");
        bDeshacer.setMargin(new Insets(5, 10, 5, 10));
        bCerrar.setMargin(new Insets(5, 10, 5, 10));
        bCerrar.addActionListener(e -> d.dispose());
        bDeshacer.addActionListener(e -> {
            int f = t.getSelectedRow();
            if (f < 0) { JOptionPane.showMessageDialog(d, "Elige en la lista el cambio que quieres deshacer.", "Deshacer", JOptionPane.INFORMATION_MESSAGE); return; }
            int fm = t.convertRowIndexToModel(f);
            if ((Integer) m.getValueAt(fm, 6) == 1) { JOptionPane.showMessageDialog(d, "Ese cambio ya está deshecho.", "Deshacer", JOptionPane.INFORMATION_MESSAGE); return; }
            String tipo = String.valueOf(m.getValueAt(fm, 4));
            if (tipo.isEmpty() || tipo.equals("null")) { JOptionPane.showMessageDialog(d, "Ese cambio no se puede deshacer.", "Deshacer", JOptionPane.INFORMATION_MESSAGE); return; }
            if (!pedirPermisoRC(d, "REGISTRO", "deshacer cambios")) return;
            String accion = String.valueOf(m.getValueAt(fm, 2));
            if (JOptionPane.showConfirmDialog(d, "¿Deshacer este cambio?\n\n" + accion, "Deshacer", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            String datos = String.valueOf(m.getValueAt(fm, 5));
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
                switch (tipo) {
                    case "SOCIO_ALTA" -> {
                        int id = Integer.parseInt(datos);
                        try (PreparedStatement p1 = conn.prepareStatement("DELETE FROM socios WHERE id = ?");
                             PreparedStatement p2 = conn.prepareStatement("DELETE FROM socios_cuotas WHERE socio_id = ?")) {
                            p1.setInt(1, id); p1.executeUpdate(); p2.setInt(1, id); p2.executeUpdate();
                        }
                    }
                    case "SOCIO_EDIT", "SOCIO_BAJA" -> restaurarSocio(conn, datos);
                    case "CUOTA_PAGO" -> {
                        String[] c = datos.split(SEP_REG, -1);
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM socios_cuotas WHERE socio_id = ? AND anio = ?")) {
                            ps.setInt(1, Integer.parseInt(c[0])); ps.setInt(2, Integer.parseInt(c[1])); ps.executeUpdate();
                        }
                    }
                    case "CUOTA_QUITA" -> {
                        String[] c = datos.split(SEP_REG, -1);
                        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO socios_cuotas (socio_id, anio, fecha_pago) VALUES (?, ?, ?)")) {
                            ps.setInt(1, Integer.parseInt(c[0])); ps.setInt(2, Integer.parseInt(c[1])); ps.setString(3, c.length > 2 ? c[2] : "");
                            ps.executeUpdate();
                        }
                    }
                    case "RC_DATOS" -> {
                        for (String kv : datos.split(SEP_REG)) {
                            int k = kv.indexOf('=');
                            if (k > 0) LibroGuardiaUI.ConfigApp.put(kv.substring(0, k), kv.substring(k + 1));
                        }
                        SwingUtilities.invokeLater(() -> {
                            cargarLogoRadioclub();
                            if (app.cbSelectorActividad != null) { recargarDesplegableIndicativoRC(); actualizarControlesRadioclub(); }
                        });
                    }
                    default -> { }
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE rc_registro SET deshecho = 1 WHERE id = ?")) {
                    ps.setInt(1, (Integer) m.getValueAt(fm, 3)); ps.executeUpdate();
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "No se pudo deshacer: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            registrarCambio("Deshizo: " + accion, "", "");
            cargar.run();
        });
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        abajo.add(bDeshacer);
        abajo.add(bCerrar);
        d.setLayout(new BorderLayout());
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        d.add(sp, BorderLayout.CENTER);
        d.add(abajo, BorderLayout.SOUTH);
        d.setSize(900, 480);
        d.setLocationRelativeTo(w);
        d.setVisible(true);
    }

    // ===================== GESTIÓN DE SOCIOS DEL RADIOCLUB =====================

    /** Solo cifras (para el Nº de socio). */
    private static void soloCifras(JTextField campo) {
        ((AbstractDocument) campo.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int o, String t, AttributeSet a) throws BadLocationException {
                if (t != null) super.insertString(fb, o, t.replaceAll("[^0-9]", ""), a);
            }
            @Override public void replace(FilterBypass fb, int o, int l, String t, AttributeSet a) throws BadLocationException {
                super.replace(fb, o, l, t == null ? "" : t.replaceAll("[^0-9]", ""), a);
            }
        });
    }

    private void abrirGestionSocios() {
        String nombreRC = nombreRadioclub();
        boolean privado = !(syncActivo() && (tengoPermiso("SOCIOS") || tengoPermiso("CUOTAS")));   // socio: ve a todos los socios, pero solo su propia cuota
        java.util.Set<String> misIndicativos = new java.util.HashSet<>();
        for (String op : app.obtenerListaOperadores()) {
            String[] f = app.leerDatosOperador(op);
            if (!f[0].isBlank()) misIndicativos.add(f[0].trim().toUpperCase());
            if (!f[1].isBlank()) misIndicativos.add(f[1].trim().toUpperCase());
        }
        JDialog d = new JDialog(app, (privado ? "Socios de " : "Gestión de ") + nombreRC, true);
        d.setLayout(new BorderLayout(0, 6));
        int anioActual = java.time.LocalDate.now().getYear();

        // Cabecera: logo, nombre e indicativos del radioclub
        JPanel cab = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        cab.setBackground(app.TEMA_FONDO);
        JLabel logo = new JLabel();
        File fLogo = ficheroLogoRadioclub();
        if (fLogo.isFile()) {
            try {
                BufferedImage img = ImageIO.read(fLogo);
                if (img != null) {
                    double e = Math.min(56.0 / img.getWidth(), 56.0 / img.getHeight());
                    logo.setIcon(new ImageIcon(img.getScaledInstance(Math.max(1, (int) (img.getWidth() * e)),
                            Math.max(1, (int) (img.getHeight() * e)), Image.SCALE_SMOOTH)));
                }
            } catch (Exception ignored) { }
        }
        cab.add(logo);
        JPanel textos = new JPanel(new GridLayout(2, 1));
        textos.setOpaque(false);
        JLabel lblNom = new JLabel(nombreRC);
        lblNom.setFont(new Font("Arial", Font.BOLD, 20));
        lblNom.setForeground(Color.WHITE);
        List<String> partes = new ArrayList<>();
        String hfRC = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_HF", "").trim(), cbRC = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_CB", "").trim();
        String qthRC = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_QTH", "").trim(), gridRC = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_GRID", "").trim();
        if (!hfRC.isEmpty()) partes.add("Amateur: " + hfRC);
        if (!cbRC.isEmpty()) partes.add("CB: " + cbRC);
        if (!qthRC.isEmpty()) partes.add("QTH: " + qthRC + (gridRC.isEmpty() ? "" : " (" + gridRC + ")"));
        JLabel lblSub = new JLabel(String.join("  ·  ", partes));
        lblSub.setFont(new Font("Arial", Font.BOLD, 12));
        lblSub.setForeground(app.TEMA_AMBAR);
        textos.add(lblNom);
        textos.add(lblSub);
        cab.add(textos);

        // Barra: buscador, año de la cuota, filtro y botones
        JTextField txtBuscar = new JTextField(16);
        app.aplicarFiltroMayusculas(txtBuscar);
        java.util.TreeSet<Integer> anios = new java.util.TreeSet<>();
        for (int a = anioActual - 2; a <= anioActual + 1; a++) anios.add(a);
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT anio FROM socios_cuotas")) {
            while (rs.next()) anios.add(rs.getInt(1));
        } catch (Exception ignored) { }
        JComboBox<Integer> cbAnio = new JComboBox<>(anios.descendingSet().toArray(new Integer[0]));
        cbAnio.setSelectedItem(anioActual);
        JComboBox<String> cbVer = new JComboBox<>(new String[]{"Todos los socios", "Cuota pagada", "Cuota pendiente"});
        JButton bAnadir = app.cat.botonCtrl("Añadir socio"), bEditar = app.cat.botonCtrl("Editar"), bQuitar = app.cat.botonCtrl("Quitar");
        JButton bPagar = app.cat.botonCtrl("Marcar cuota pagada"), bExportar = app.cat.botonCtrl("Exportar lista");
        bPagar.setForeground(app.cat.VERDE_ENCENDIDO);
        for (JButton b : new JButton[]{bAnadir, bEditar, bQuitar, bPagar, bExportar}) b.setMargin(new Insets(5, 10, 5, 10));
        JPanel barra = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        barra.setOpaque(false);
        barra.add(new JLabel("Buscar:"));
        barra.add(txtBuscar);
        barra.add(new JLabel("  Cuota del año:"));
        barra.add(cbAnio);
        barra.add(new JLabel("  Ver:"));
        barra.add(cbVer);
        barra.add(Box.createHorizontalStrut(16));
        barra.add(bAnadir);
        barra.add(bEditar);
        barra.add(bQuitar);
        barra.add(bPagar);
        barra.add(bExportar);
        if (privado) {
            for (JButton b : new JButton[]{bAnadir, bEditar, bQuitar, bPagar, bExportar}) b.setVisible(false);
            cbVer.setVisible(false);
            JLabel soloVer = new JLabel("Solo lectura. Solo ves tu cuota; los socios y las cuotas se cambian en el ordenador del radioclub");
            soloVer.setForeground(app.TEMA_AMBAR);
            barra.add(soloVer);
        }
        JButton bRegistro = app.cat.botonCtrl("Registro de cambios");
        bRegistro.setMargin(new Insets(5, 10, 5, 10));
        if (!privado) barra.add(bRegistro);
        // Registro del radioclub: todo lo hecho por todos (quién, qué y cuándo), con las firmas comprobadas
        JButton bRegistroRC = app.cat.botonCtrl("Registro");
        bRegistroRC.setMargin(new Insets(5, 10, 5, 10));
        bRegistroRC.addActionListener(e -> abrirRegistroRadioclub(d));
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setBackground(app.TEMA_FONDO);
        arriba.add(cab, BorderLayout.NORTH);
        arriba.add(barra, BorderLayout.CENTER);

        // Tabla (columna 7 oculta: id del socio)
        DefaultTableModel modelo = new DefaultTableModel(new Object[]{"Nº socio", "Indicativo Amateur", "Indicativo CB",
                "Nombre y apellidos", "QTH", "Cuota " + anioActual, "Fecha de pago", "id"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 || c == 7 ? Integer.class : String.class; }
        };
        JTable tabla = new JTable(modelo);
        tabla.setRowHeight(24);
        tabla.setAutoCreateRowSorter(true);
        tabla.removeColumn(tabla.getColumnModel().getColumn(7));
        javax.swing.table.DefaultTableCellRenderer centro = new javax.swing.table.DefaultTableCellRenderer();
        centro.setHorizontalAlignment(SwingConstants.CENTER);
        tabla.getColumnModel().getColumn(0).setCellRenderer(centro);
        tabla.getColumnModel().getColumn(6).setCellRenderer(centro);
        tabla.getColumnModel().getColumn(5).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                setHorizontalAlignment(SwingConstants.CENTER);
                setFont(getFont().deriveFont(Font.BOLD));
                if (!sel) setForeground(String.valueOf(v).startsWith("✓") ? app.cat.VERDE_ENCENDIDO : new Color(255, 107, 90));
                return this;
            }
        });
        int[] anchos = {70, 140, 120, 280, 160, 110, 110};
        for (int i = 0; i < anchos.length; i++) tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        JLabel pie = new JLabel(" ");
        pie.setFont(new Font("Arial", Font.BOLD, 13));
        pie.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));

        Runnable cargar = () -> {
            int anio = (Integer) cbAnio.getSelectedItem();
            tabla.getColumnModel().getColumn(5).setHeaderValue("Cuota " + anio);
            tabla.getTableHeader().repaint();
            String buscado = app.sinTildes(txtBuscar.getText().trim());
            int ver = cbVer.getSelectedIndex();
            modelo.setRowCount(0);
            int total = 0, pagados = 0;
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
                 PreparedStatement ps = conn.prepareStatement("SELECT s.id, s.num, s.ind_hf, s.ind_cb, s.nombre, s.qth, c.fecha_pago "
                         + "FROM socios s LEFT JOIN socios_cuotas c ON c.socio_id = s.id AND c.anio = ? ORDER BY s.num, s.nombre")) {
                ps.setInt(1, anio);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String fecha = rs.getString(7);
                        boolean pagado = fecha != null;
                        String[] t = {rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)};
                        for (int i = 0; i < t.length; i++) if (t[i] == null) t[i] = "";
                        if (esIndicativoDelRadioclub(t[0]) || esIndicativoDelRadioclub(t[1])) continue;   // el radioclub va arriba, aparte (no es un socio)
                        total++;
                        if (pagado) pagados++;
                        if (ver == 1 && !pagado || ver == 2 && pagado) continue;
                        if (!buscado.isEmpty() && !app.sinTildes(String.join(" ", t) + " " + rs.getInt(2)).contains(buscado)) continue;
                        boolean mia = misIndicativos.contains(t[0].trim().toUpperCase()) || misIndicativos.contains(t[1].trim().toUpperCase());
                        if (privado && !mia) {   // la cuota de los demás no se enseña
                            modelo.addRow(new Object[]{rs.getInt(2), t[0], t[1], t[2], t[3], "", "", rs.getInt(1)});
                            continue;
                        }
                        modelo.addRow(new Object[]{rs.getInt(2), t[0], t[1], t[2], t[3],
                                pagado ? "✓ PAGADA" : "✗ PENDIENTE", pagado ? fecha : "", rs.getInt(1)});
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "Error al leer los socios: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
            if (privado) pie.setText("<html>Socios: <font color='#ffbe3c'>" + total + "</font></html>");
            else pie.setText("<html>Socios: <font color='#ffbe3c'>" + total + "</font>  ·  Cuota " + anio + " pagada: <font color='#3ddc5a'>"
                    + pagados + "</font>  ·  Pendiente: <font color='#ff6b5a'>" + (total - pagados) + "</font></html>");
        };

        java.util.function.Supplier<List<Integer>> elegidos = () -> {
            List<Integer> ids = new ArrayList<>();
            for (int f : tabla.getSelectedRows()) ids.add((Integer) modelo.getValueAt(tabla.convertRowIndexToModel(f), 7));
            return ids;
        };

        bAnadir.addActionListener(e -> {
            if (!pedirPermisoRC(d, "SOCIOS", "añadir socios")) return;
            if (editarSocio(d, -1, (Integer) cbAnio.getSelectedItem())) cargar.run();
        });
        Runnable editar = () -> {
            List<Integer> ids = elegidos.get();
            if (ids.size() != 1) {
                JOptionPane.showMessageDialog(d, "Elige un socio de la tabla.", "Editar socio", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!pedirPermisoRC(d, "SOCIOS", "editar socios")) return;
            if (editarSocio(d, ids.get(0), (Integer) cbAnio.getSelectedItem())) cargar.run();
        };
        bEditar.addActionListener(e -> editar.run());
        tabla.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && tabla.getSelectedRow() >= 0) editar.run(); }
        });
        bQuitar.addActionListener(e -> {
            List<Integer> ids = elegidos.get();
            if (ids.isEmpty()) return;
            if (!pedirPermisoRC(d, "SOCIOS", "quitar socios")) return;
            if (JOptionPane.showConfirmDialog(d, "¿Quitar " + (ids.size() == 1 ? "este socio" : ids.size() + " socios")
                            + " de la lista?\n(Sus cuotas también se borran. Los contactos del log no se tocan.)",
                    "Quitar socio", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
                 PreparedStatement p1 = conn.prepareStatement("DELETE FROM socios WHERE id = ?");
                 PreparedStatement p2 = conn.prepareStatement("DELETE FROM socios_cuotas WHERE socio_id = ?")) {
                for (int id : ids) {
                    String copia = copiaSocio(conn, id);   // para poder deshacerlo
                    p1.setInt(1, id); p1.executeUpdate(); p2.setInt(1, id); p2.executeUpdate();
                    registrarCambio("Quitó al socio " + descripcionSocio(copia), "SOCIO_BAJA", copia);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "Error al quitar: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
            cargar.run();
        });
        bPagar.addActionListener(e -> {
            List<Integer> ids = elegidos.get();
            if (ids.isEmpty()) {
                JOptionPane.showMessageDialog(d, "Elige en la tabla el socio o los socios que han pagado.", "Cuota", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!pedirPermisoRC(d, "CUOTAS", "cambiar las cuotas")) return;
            int anio = (Integer) cbAnio.getSelectedItem();
            // Si todos los elegidos ya habían pagado, se pregunta si se quita el pago (por si fue un error)
            boolean todosPagados = true;
            for (int f : tabla.getSelectedRows()) if (!String.valueOf(modelo.getValueAt(tabla.convertRowIndexToModel(f), 5)).startsWith("✓")) todosPagados = false;
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
                if (todosPagados) {
                    if (JOptionPane.showConfirmDialog(d, "La cuota " + anio + " ya está pagada.\n¿Quieres marcarla como PENDIENTE?",
                            "Cuota", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM socios_cuotas WHERE socio_id = ? AND anio = ?")) {
                        for (int id : ids) {
                            String fechaAnt = fechaCuota(conn, id, anio);
                            ps.setInt(1, id); ps.setInt(2, anio); ps.executeUpdate();
                            registrarCambio("Puso PENDIENTE la cuota " + anio + " del socio " + descripcionSocio(copiaSocio(conn, id)),
                                    "CUOTA_QUITA", id + SEP_REG + anio + SEP_REG + (fechaAnt == null ? "" : fechaAnt));
                        }
                    }
                } else {
                    String hoy = new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date());
                    try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO socios_cuotas (socio_id, anio, fecha_pago) VALUES (?, ?, ?)")) {
                        for (int id : ids) {
                            ps.setInt(1, id); ps.setInt(2, anio); ps.setString(3, hoy);
                            if (ps.executeUpdate() > 0)
                                registrarCambio("Marcó PAGADA la cuota " + anio + " del socio " + descripcionSocio(copiaSocio(conn, id)),
                                        "CUOTA_PAGO", id + SEP_REG + anio);
                        }
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "Error al guardar la cuota: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
            cargar.run();
        });
        bExportar.addActionListener(e -> exportarSocios(d, modelo, (Integer) cbAnio.getSelectedItem()));
        bRegistro.addActionListener(e -> { abrirRegistroCambios(d); cargar.run(); });
        // Cuando se cierra la ventana de socios, lo cambiado se comparte con el radioclub
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { sincronizarPronto(); }
        });
        cbAnio.addActionListener(e -> cargar.run());
        cbVer.addActionListener(e -> cargar.run());
        txtBuscar.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { cargar.run(); }
            @Override public void removeUpdate(DocumentEvent e) { cargar.run(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });

        JPanel abajo = new JPanel(new BorderLayout());
        abajo.add(pie, BorderLayout.WEST);
        JLabel ayuda = new JLabel("Doble clic en un socio para editarlo");
        ayuda.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
        abajo.add(ayuda, BorderLayout.EAST);
        if (!privado && syncActivo()) {   // abajo, para que se vea también en pantallas pequeñas
            JPanel centroAbajo = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
            centroAbajo.setOpaque(false);
            centroAbajo.add(bRegistroRC);
            abajo.add(centroAbajo, BorderLayout.CENTER);
        }
        d.add(arriba, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(tabla);
        sp.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        d.add(sp, BorderLayout.CENTER);
        d.add(abajo, BorderLayout.SOUTH);
        cargar.run();
        d.setSize(1200, 620);
        d.setLocationRelativeTo(app);
        d.setVisible(true);
    }

    /** Ventana para añadir (id = -1) o editar un socio. Devuelve true si se ha guardado. */
    private boolean editarSocio(Component padre, int id, int anio) {
        JTextField tNum = new JTextField(6), tHf = new JTextField(14), tCb = new JTextField(14), tNom = new JTextField(26), tQth = new JTextField(20);
        soloCifras(tNum);
        for (JTextField t : new JTextField[]{tHf, tCb, tNom, tQth}) app.aplicarFiltroMayusculas(t);
        JCheckBox chkPagada = new JCheckBox("Cuota " + anio + " pagada");
        JTextField tFecha = new JTextField(10);
        String fechaAntes = null;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            if (id < 0) {
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(num), 0) + 1 FROM socios")) {
                    tNum.setText(rs.next() ? String.valueOf(rs.getInt(1)) : "1");
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement("SELECT num, ind_hf, ind_cb, nombre, qth FROM socios WHERE id = ?")) {
                    ps.setInt(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            tNum.setText(String.valueOf(rs.getInt(1)));
                            tHf.setText(rs.getString(2)); tCb.setText(rs.getString(3));
                            tNom.setText(rs.getString(4)); tQth.setText(rs.getString(5));
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("SELECT fecha_pago FROM socios_cuotas WHERE socio_id = ? AND anio = ?")) {
                    ps.setInt(1, id); ps.setInt(2, anio);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) fechaAntes = rs.getString(1); }
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        String hoy = new java.text.SimpleDateFormat("dd/MM/yyyy").format(new java.util.Date());
        chkPagada.setSelected(fechaAntes != null);
        tFecha.setText(fechaAntes != null ? fechaAntes : hoy);
        tFecha.setEnabled(chkPagada.isSelected());
        chkPagada.addActionListener(e -> tFecha.setEnabled(chkPagada.isSelected()));
        JPanel filaCuota = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaCuota.add(chkPagada);
        filaCuota.add(new JLabel("  Fecha de pago:"));
        filaCuota.add(tFecha);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        String[] et = {"Nº socio:", "Indicativo Amateur:", "Indicativo CB:", "Nombre y apellidos:", "QTH:", "Cuota:"};
        JComponent[] cs = {tNum, tHf, tCb, tNom, tQth, filaCuota};
        for (int i = 0; i < et.length; i++) {
            g.gridy = i; g.gridx = 0; panel.add(new JLabel(et[i]), g);
            g.gridx = 1; panel.add(cs[i], g);
        }
        while (true) {
            int r = JOptionPane.showConfirmDialog(padre, panel, id < 0 ? "Añadir socio" : "Editar socio",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return false;
            String hf = tHf.getText().trim().toUpperCase(), cb = tCb.getText().trim().toUpperCase();
            String nom = tNom.getText().trim().toUpperCase(), qth = tQth.getText().trim().toUpperCase();
            if (hf.isEmpty() && cb.isEmpty() && nom.isEmpty()) {
                JOptionPane.showMessageDialog(padre, "Escribe como mínimo un indicativo o el nombre.", "Falta información", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            String fecha = tFecha.getText().trim();
            if (chkPagada.isSelected() && !fecha.matches("\\d{2}/\\d{2}/\\d{4}")) {
                JOptionPane.showMessageDialog(padre, "La fecha de pago tiene que ser así: 15/01/" + anio, "Fecha", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            int num = tNum.getText().trim().isEmpty() ? 0 : Integer.parseInt(tNum.getText().trim());
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
                int idSocio = id;
                String copiaAntes = id < 0 ? null : copiaSocio(conn, id);   // para poder deshacerlo
                if (id < 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO socios (num, ind_hf, ind_cb, nombre, qth) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, num); ps.setString(2, hf); ps.setString(3, cb); ps.setString(4, nom); ps.setString(5, qth);
                        ps.executeUpdate();
                        try (ResultSet k = ps.getGeneratedKeys()) { if (k.next()) idSocio = k.getInt(1); }
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE socios SET num = ?, ind_hf = ?, ind_cb = ?, nombre = ?, qth = ? WHERE id = ?")) {
                        ps.setInt(1, num); ps.setString(2, hf); ps.setString(3, cb); ps.setString(4, nom); ps.setString(5, qth); ps.setInt(6, id);
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM socios_cuotas WHERE socio_id = ? AND anio = ?")) {
                    ps.setInt(1, idSocio); ps.setInt(2, anio); ps.executeUpdate();
                }
                if (chkPagada.isSelected()) {
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO socios_cuotas (socio_id, anio, fecha_pago) VALUES (?, ?, ?)")) {
                        ps.setInt(1, idSocio); ps.setInt(2, anio); ps.setString(3, fecha); ps.executeUpdate();
                    }
                }
                if (id < 0) registrarCambio("Añadió al socio " + descripcionSocio(copiaSocio(conn, idSocio)), "SOCIO_ALTA", String.valueOf(idSocio));
                else registrarCambio("Editó al socio " + descripcionSocio(copiaAntes), "SOCIO_EDIT", copiaAntes);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(padre, "Error al guardar el socio: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return false;
            }
            // El indicativo CB del socio también va a la lista BANDA CIUDADANA
            if (!cb.isEmpty()) app.anadirOperadorCBAlDirectorio(new String[]{hf, cb, nom, qth});
            return true;
        }
    }

    /** Guarda la lista que se ve en la tabla en un archivo CSV (se abre con Excel). */
    private void exportarSocios(Component padre, DefaultTableModel modelo, int anio) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Exportar lista de socios");
        fc.setSelectedFile(new File("Socios " + nombreRadioclub() + " " + anio + ".csv"));
        if (fc.showSaveDialog(padre) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv")) f = new File(f.getParentFile(), f.getName() + ".csv");
        try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write('\uFEFF');   // para que Excel lea bien las tildes
            w.write("Nº socio;Indicativo Amateur;Indicativo CB;Nombre y apellidos;QTH;Cuota " + anio + ";Fecha de pago\r\n");
            for (int r = 0; r < modelo.getRowCount(); r++) {
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < 7; c++) {
                    String v = String.valueOf(modelo.getValueAt(r, c)).replace("✓ ", "").replace("✗ ", "").replace(";", ",");
                    if (c > 0) sb.append(';');
                    sb.append(v);
                }
                w.write(sb + "\r\n");
            }
            JOptionPane.showMessageDialog(padre, "Lista guardada en:\n" + f.getAbsolutePath(), "Exportar", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(padre, "No se pudo guardar: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ===================== RADIOCLUB =====================

    String nombreRadioclub() {
        String n = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_NOMBRE", "").trim().toUpperCase();
        return n.isEmpty() ? "RADIOCLUB" : n;
    }

    /** Indicativos del radioclub que están rellenos, en el orden CB, HF, Especial ("CB: XXX"). */
    List<String> opcionesIndicativoRadioclub() {
        List<String> lista = new ArrayList<>();
        String cb = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_CB", "").trim().toUpperCase();
        String hf = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_HF", "").trim().toUpperCase();
        String esp = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_ESP", "").trim().toUpperCase();
        if (!cb.isEmpty()) lista.add("CB: " + cb);
        if (!hf.isEmpty()) lista.add("HF: " + hf);
        if (!esp.isEmpty()) lista.add("ESPECIAL: " + esp);
        return lista;
    }

    boolean radioclubConfigurado() {
        return !opcionesIndicativoRadioclub().isEmpty();
    }

    /** Indicativo del radioclub elegido en el desplegable (solo el indicativo, sin "CB: "). */
    String indicativoRadioclubElegido() {
        if (!app.modoRadioclub || app.cbIndicativoRC == null) return "";
        Object sel = app.cbIndicativoRC.getSelectedItem();
        if (sel == null) return "";
        String s = sel.toString();
        int pos = s.indexOf(": ");
        return (pos >= 0 ? s.substring(pos + 2) : s).trim().toUpperCase();
    }

    void recargarDesplegableIndicativoRC() {
        if (app.cbIndicativoRC == null) return;
        String previo = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_ELEGIDO", "");
        app.cbIndicativoRC.removeAllItems();
        for (String op : opcionesIndicativoRadioclub()) app.cbIndicativoRC.addItem(op);
        for (int i = 0; i < app.cbIndicativoRC.getItemCount(); i++) {
            if (app.cbIndicativoRC.getItemAt(i).startsWith(previo + ":")) {
                app.cbIndicativoRC.setSelectedIndex(i);
                break;
            }
        }
    }

    /** Muestra u oculta los controles del modo radioclub según el estado actual. */
    void actualizarControlesRadioclub() {
        if (app.chkModoRadioclub == null) return;
        boolean configurado = radioclubConfigurado();
        if (!configurado && app.modoRadioclub) {
            app.modoRadioclub = false;
            LibroGuardiaUI.ConfigApp.put("MODO_RADIOCLUB", "0");
        }
        app.chkModoRadioclub.setText(nombreRadioclub());
        app.chkModoRadioclub.setVisible(configurado);
        app.chkModoRadioclub.setSelected(app.modoRadioclub);
        app.lblIndicativoRC.setVisible(app.modoRadioclub);
        app.cbIndicativoRC.setVisible(app.modoRadioclub);
        if (app.lblNombreRC != null) {
            app.lblNombreRC.setText(nombreRadioclub());
            app.lblNombreRC.setVisible(configurado);
            String ind = indicativoRadioclubElegido();
            Object vistaSel = app.cbSelectorActividad == null ? null : app.cbSelectorActividad.getSelectedItem();
            // En el LOG Principal y en el LOG Radioclub se ven los indicativos del radioclub
            boolean enLogRadioclub = vistaSel == null || app.NOMBRE_VISTA_RC.equals(vistaSel.toString())
                    || "LOG Principal".equals(vistaSel.toString());
            String hf = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_HF", "").trim().toUpperCase();
            String cb = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_CB", "").trim().toUpperCase();
            String linea1, linea2 = " ";
            if (enLogRadioclub && configurado) {
                // LOG Principal y LOG Radioclub: primero el indicativo de radioaficionado (si lo tiene) y debajo el de CB
                if (!hf.isEmpty()) {
                    linea1 = "Amateur: " + hf;
                    if (!cb.isEmpty()) linea2 = "CB: " + cb;
                } else {
                    linea1 = cb.isEmpty() ? "" : "CB: " + cb;
                }
            } else {
                linea1 = app.modoRadioclub && !ind.isEmpty() ? "Indicativo: " + ind : "";
            }
            app.lblIndicativoActivoRC.setText(linea1.isEmpty() ? " " : linea1);
            app.lblIndicativoActivoRC.setVisible(true);   // siempre ocupa su sitio: así no se mueve nada
            if (app.lblIndicativoActivoRC2 != null) app.lblIndicativoActivoRC2.setText(linea2);
            app.lblIndicativoActivoRC.setToolTipText(app.modoRadioclub
                    ? "Los contactos se apuntan con este indicativo y van también al " + app.NOMBRE_VISTA_RC : null);
        }
        app.revalidate();
        app.repaint();
    }

    void alternarModoRadioclub() {
        app.modoRadioclub = app.chkModoRadioclub.isSelected();
        LibroGuardiaUI.ConfigApp.put("MODO_RADIOCLUB", app.modoRadioclub ? "1" : "0");
        actualizarControlesRadioclub();
        app.lblEstado.setText(app.modoRadioclub
                ? "Modo " + nombreRadioclub() + " activado. Los contactos irán también al " + app.NOMBRE_VISTA_RC + "."
                : "Modo " + nombreRadioclub() + " desactivado.");
    }

    /**
     * El modo radioclub ya no se marca a mano: va con la actividad. En una actividad del radioclub
     * (o en el LOG Radioclub) los contactos se apuntan con el indicativo del radioclub elegido al crearla.
     */
    void actualizarModoRadioclubSegunSeleccion() {
        if (app.cbIndicativoRC == null) return;
        int num = app.obtenerNumeralDeActividadDesdeSeleccion();
        boolean vistaRC = esVistaRadioclubSeleccionada();
        boolean actRC = esActividadDelRadioclub(num);
        app.modoRadioclub = radioclubConfigurado() && (vistaRC || actRC);
        String buscado = actRC ? indicativoRcDeActividad(num) : "";
        if (buscado.isEmpty()) buscado = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_ELEGIDO", "");
        recargarDesplegableIndicativoRC();
        for (int i = 0; i < app.cbIndicativoRC.getItemCount(); i++) {
            String it = app.cbIndicativoRC.getItemAt(i);
            if (it.equals(buscado) || it.startsWith(buscado + ":")) { app.cbIndicativoRC.setSelectedIndex(i); break; }
        }
        actualizarControlesRadioclub();
    }

    /** Indicativo del radioclub con el que se creó la actividad (por ejemplo "HF: EA4RCS"), o "" si no tiene. */
    private String indicativoRcDeActividad(int numeral) {
        if (numeral <= 0) return "";
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(indicativo_rc, '') FROM actividades WHERE numeral = ? LIMIT 1")) {
            ps.setInt(1, numeral);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : ""; }
        } catch (Exception e) {
            return "";
        }
    }

    boolean esVistaRadioclubSeleccionada() {
        return app.cbSelectorActividad != null && app.NOMBRE_VISTA_RC.equals(app.cbSelectorActividad.getSelectedItem());
    }

    boolean esActividadDelRadioclub(int numeralActividad) {
        if (numeralActividad <= 0) return false;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(radioclub, 0) FROM actividades WHERE numeral = ? LIMIT 1")) {
            ps.setInt(1, numeralActividad);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * "Generar iframe para la web…": se vincula la carpeta de Google Drive de una actividad y se da el código
     * para pegar en cualquier web. Los diplomas se descargan del Drive del radioclub (no pasan por bitacora26.es).
     */
    /** false: el código del iframe está hecho, pero no se enseña (se da solo el enlace a la página). */
    private static final boolean MOSTRAR_CODIGO_IFRAME = false;

    void generarIframeDiplomas(Component padre) { generarIframeDiplomas(padre, true); }

    /** Mi Estación → "Diplomas en la web…": lo mismo para las actividades propias del operador (no del radioclub). */
    void diplomasWebOperador() {
        String op = LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual;
        JTextField txt = new JTextField(LibroGuardiaUI.ConfigApp.get("OP_CARPETA_DIPLOMAS_" + op, ""), 34);
        txt.setEditable(false);
        JButton elegir = new JButton("Elegir carpeta…"), iframe = new JButton("Generar Buscador de Diplomas…");
        elegir.addActionListener(ev -> {
            JFileChooser fc = new JFileChooser(txt.getText().isEmpty() ? null : new File(txt.getText()));
            fc.setDialogTitle("Carpeta de los diplomas de mis actividades (por ejemplo, en Google Drive)");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(elegir) == JFileChooser.APPROVE_OPTION) {
                txt.setText(fc.getSelectedFile().getAbsolutePath());
                LibroGuardiaUI.ConfigApp.put("OP_CARPETA_DIPLOMAS_" + op, txt.getText());
            }
        });
        iframe.addActionListener(ev -> generarIframeDiplomas(iframe, false));
        JPanel p = new JPanel(new BorderLayout(6, 8));
        JPanel fila = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fila.add(txt); fila.add(elegir); fila.add(iframe);
        p.add(new JLabel("<html><b>Carpeta de mis actividades (diplomas)</b></html>"), BorderLayout.NORTH);
        p.add(fila, BorderLayout.CENTER);
        p.add(new JLabel("<html><span style='color:gray'>Para tus actividades (las que no son del radioclub). Al crear un diploma, además de en su carpeta de siempre,<br>"
                + "se guarda una copia aquí, en la subcarpeta de la actividad.</span><br><br>"
                + "<b>Importante:</b> instala antes <b>Google Drive para ordenador</b> (google.com/drive/download).<br>"
                + "La carpeta tiene que estar <b>dentro de «Mi unidad»</b> de Google Drive. Mejor una carpeta con tu indicativo,<br>"
                + "por ejemplo <b>Mi unidad\\" + (op.isEmpty() ? "EA4XXX" : op) + "</b>.</html>"), BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(app, p, "Diplomas en la web", JOptionPane.PLAIN_MESSAGE);
    }

    void generarIframeDiplomas(Component padre, boolean delRadioclub) {
        String op = LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual;
        String claveRaizLocal = delRadioclub ? "RC_CARPETA_DIPLOMAS" : "OP_CARPETA_DIPLOMAS_" + op;
        String claveDrive = delRadioclub ? "RC_DRIVE_RAIZ" : "OP_DRIVE_RAIZ_" + op;
        String cuenta = CuentaBitacora.cuentaB26DeOperador(LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual);
        if (cuenta.isEmpty()) {
            JOptionPane.showMessageDialog(padre, "Para crear el buscador hace falta una cuenta de Bitácora 26\n(Mi Estación → Operador).",
                    "Generador de Buscador de Diplomas", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<String> cbAct = new JComboBox<>();
        Map<String, String> subDe = new HashMap<>();
        try (java.sql.Connection c = LibroGuardiaUI.DatabaseManager.conectar();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT numeral, nombre, COALESCE(uuid,'') FROM actividades WHERE COALESCE(radioclub,0) = ? AND numeral > 0 ORDER BY numeral")) {
            ps.setInt(1, delRadioclub ? 1 : 0);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!actividadVisible(delRadioclub ? 1 : 0, rs.getString(3))) continue;
                    String t = rs.getInt(1) + " - " + app.nombreVisibleDeActividad(rs.getString(2));
                    cbAct.addItem(t);
                    subDe.put(t, GeneradorDiplomaUI.subcarpetaWeb(rs.getString(2)));
                }
            }
        } catch (Exception ignored) { }
        if (LibroGuardiaUI.ConfigApp.get(claveRaizLocal, "").isEmpty()) {
            JOptionPane.showMessageDialog(padre, "Elige primero la carpeta de los diplomas (botón «Elegir carpeta…»).", "Generador de Buscador de Diplomas", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (cbAct.getItemCount() == 0) {
            JOptionPane.showMessageDialog(padre, "No hay ninguna actividad.", "Generador de Buscador de Diplomas", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JTextField txtEnlace = new JTextField(40), txtTitulo = new JTextField(40), txtAlto = new JTextField("420", 5);
        JComboBox<String> cbTema = new JComboBox<>(new String[]{"Automático", "Claro", "Oscuro"});
        Runnable ponerTitulo = () -> {
            String a = String.valueOf(cbAct.getSelectedItem()).replaceAll("^\\s*\\d+\\s*-\\s*", "");
            txtTitulo.setText("Descarga tu diploma · " + a);
        };
        txtEnlace.setText(LibroGuardiaUI.ConfigApp.get(claveDrive, ""));
        String raizLocal = LibroGuardiaUI.ConfigApp.get(claveRaizLocal, "");
        String nombreRaiz = raizLocal.isEmpty() ? "la carpeta de actividades" : "«" + new File(raizLocal).getName() + "»";
        cbAct.addActionListener(e -> ponerTitulo.run());
        ponerTitulo.run();
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        Object[][] filas = {{"Actividad:", cbAct}, {"Enlace de " + nombreRaiz + " en Drive:", txtEnlace}, {"Título:", txtTitulo}};   // alto y colores ya no: el buscador está en bitacora26.es
        for (int i = 0; i < filas.length; i++) {
            g.gridy = i; g.gridx = 0; g.weightx = 0; p.add(new JLabel((String) filas[i][0]), g);
            g.gridx = 1; g.weightx = 1; p.add((Component) filas[i][1], g);
        }
        g.gridy = filas.length; g.gridx = 0; g.gridwidth = 2;
        JLabel nota = new JLabel("<html><span style='color:gray'>Solo hace falta <b>una vez</b>: el enlace de la carpeta de actividades entera (" + nombreRaiz + "), no el de cada actividad.<br>"
                + "<b style='color:#ffb733'>El enlace hay que copiarlo desde la web de Google Drive (drive.google.com)</b>, no desde la carpeta del ordenador.<br>"
                + "En drive.google.com: botón derecho en " + nombreRaiz + " → Compartir → «Cualquiera con el enlace» → Copiar enlace.<br>"
                + "Al elegir la actividad se usa su subcarpeta. Los diplomas se descargan de vuestro Drive: no se guardan en bitacora26.es.</span><br><br>"
                + "<b>Aviso:</b> el buscador lleva el aviso «Hecho con Bitácora 26». Según la licencia y las condiciones de uso de Bitácora 26,<br>"
                + "es obligatorio mantenerlo visible: no se puede quitar, tapar ni ocultar.</html>");
        p.add(nota, g);
        JLabel guia = new JLabel("<html><u>¿Dudas? Abre la guía paso a paso con imágenes</u></html>");
        guia.setForeground(new Color(111, 168, 255));
        guia.setFont(guia.getFont().deriveFont(Font.BOLD));
        guia.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        guia.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                try { Desktop.getDesktop().browse(new java.net.URI("https://bitacora26.es/guia.html#buscador-diplomas")); } catch (Exception ignored) { }
            }
        });
        g.gridy = filas.length + 1;
        p.add(guia, g);
        if (JOptionPane.showConfirmDialog(padre, p, "Generador de Buscador de Diplomas", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        LibroGuardiaUI.ConfigApp.put(claveDrive, txtEnlace.getText().trim());
        // La subcarpeta de la actividad (donde se copian sus diplomas al crearlos)
        String sub = subDe.getOrDefault(String.valueOf(cbAct.getSelectedItem()), "");
        String[] r = app.cuentaB26.firmadoB26(cuenta, "diplomas_web", "{\"carpeta\":\"" + app.jsonTexto(txtEnlace.getText().trim()) + "\",\"titulo\":\""
                + app.jsonTexto(txtTitulo.getText().trim()) + "\",\"actividad\":\"" + app.jsonTexto(String.valueOf(cbAct.getSelectedItem())) + "\",\"subcarpeta\":\"" + app.jsonTexto(sub) + "\"}");
        if (!"200".equals(r[0])) {
            JOptionPane.showMessageDialog(padre, "No se ha podido crear el buscador: " + CuentaBitacora.errorJson(r), "Generador de Buscador de Diplomas", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String token = LibroGuardiaUI.campoJson(r[1], "token");
        int alto;
        try { alto = Math.max(250, Math.min(2000, Integer.parseInt(txtAlto.getText().trim()))); } catch (Exception e) { alto = 420; }
        String tema = cbTema.getSelectedIndex() == 1 ? "&tema=claro" : cbTema.getSelectedIndex() == 2 ? "&tema=oscuro" : "";
        String codigo = "<iframe src=\"https://bitacora26.es/diplomas.html?c=" + token + tema + "\"\n  width=\"100%\" height=\"" + alto
                + "\" style=\"border:0\" title=\"Buscador de diplomas · Bitácora 26\"></iframe>";
        String enlace = "https://bitacora26.es/?c=" + token;   // la portada de Bitácora 26, con «Buscar diploma» arriba
        java.util.regex.Matcher mp = java.util.regex.Pattern.compile("\"pdfs\"\\s*:\\s*(\\d+)").matcher(r[1]);
        String cuantos = mp.find() ? mp.group(1) : "?";
        JPanel q = new JPanel(new BorderLayout(4, 8));
        q.add(new JLabel("<html>Buscador creado (" + cuantos + " diplomas en la carpeta).<br><br>"
                + "Pon este enlace en un botón o texto de tu web, QRZ.com, redes…:</html>"), BorderLayout.NORTH);
        JTextField txtEnlaceWeb = new JTextField(enlace, 45);
        txtEnlaceWeb.setEditable(false);
        txtEnlaceWeb.addFocusListener(new FocusAdapter() { @Override public void focusGained(FocusEvent e) { txtEnlaceWeb.selectAll(); } });
        JButton copiar = new JButton("Copiar enlace");
        copiar.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(enlace), null);
            copiar.setText("✓ Copiado");
        });
        JPanel filaEnlace = new JPanel(new BorderLayout(6, 0));
        filaEnlace.add(txtEnlaceWeb, BorderLayout.CENTER);
        filaEnlace.add(copiar, BorderLayout.EAST);
        q.add(filaEnlace, BorderLayout.CENTER);
        q.add(new JLabel("<html><b>Recuerda:</b> el aviso «Hecho con Bitácora 26» tiene que quedar visible (licencia y condiciones de uso de Bitácora 26).</html>"), BorderLayout.SOUTH);
        // Opción del iframe (código para meter el buscador dentro de otra web): hecha, pero oculta de momento
        if (MOSTRAR_CODIGO_IFRAME) {
            JTextArea ta = new JTextArea(codigo, 3, 60);
            ta.setEditable(false);
            ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JButton copiarCodigo = new JButton("Copiar código (iframe)");
            copiarCodigo.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(codigo), null);
                copiarCodigo.setText("✓ Copiado");
            });
            JPanel pIframe = new JPanel(new BorderLayout(4, 4));
            pIframe.add(new JScrollPane(ta), BorderLayout.CENTER);
            pIframe.add(copiarCodigo, BorderLayout.EAST);
            JPanel todo = new JPanel(new BorderLayout(4, 10));
            todo.add(q, BorderLayout.NORTH);
            todo.add(pIframe, BorderLayout.CENTER);
            q = todo;
        }
        JOptionPane.showMessageDialog(padre, q, "Buscador de diplomas", JOptionPane.INFORMATION_MESSAGE);
    }

    private java.util.SortedMap<String, String> invitacionElegida;

    /** Aún no hay radioclub en este ordenador (ni creado aquí ni unido con clave). */
    boolean hayQueCrearRadioclub() {
        return LibroGuardiaUI.ConfigApp.get("RADIOCLUB_NOMBRE", "").trim().isEmpty()
                && LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "").isEmpty() && !syncActivo();
    }

    /** Ventana para crear el radioclub: sus datos, «Crear Radioclub» y, para los socios, «Tengo una clave de invitación». */
    private int dialogoCrearRadioclub(JPanel datos, JTextField txtNombre,
                                      java.util.function.BiConsumer<java.util.SortedMap<String, String>, Boolean> usarInvitacion) {
        JDialog d = new JDialog(app, "Radioclub", Dialog.ModalityType.APPLICATION_MODAL);
        final int[] r = {JOptionPane.CANCEL_OPTION};
        JLabel intro = new JLabel("<html><b>Todavía no perteneces a ningún radioclub.</b><br>"
                + "Si eres <b>socio</b> de un radioclub, pide al radioclub su <b>clave de invitación</b> y pulsa «Tengo una clave de invitación».<br>"
                + "Si quieres <b>crear</b> el radioclub en este ordenador, rellena sus datos y pulsa «Crear Radioclub».</html>");
        intro.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        JButton bCrear = new JButton("Crear Radioclub"), bClave = new JButton("Tengo una clave de invitación…"), bCancelar = new JButton("Cancelar");
        bCrear.setFont(bCrear.getFont().deriveFont(Font.BOLD));
        bCrear.setBackground(app.TEMA_AMBAR);
        bCrear.setForeground(new Color(27, 20, 4));
        bCrear.addActionListener(e -> {
            if (txtNombre.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(d, "Escribe primero el nombre del radioclub.", "Crear Radioclub", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (JOptionPane.showConfirmDialog(d, "Al crear el radioclub, ESTE ordenador pasará a ser el ordenador del radioclub:\n"
                            + "el que guarda los datos, añade o quita socios y da la clave de invitación.\n\n"
                            + "Hazlo en el ordenador que vaya a llevar el radioclub.\n\n¿Crear el radioclub en este ordenador?",
                    "Crear Radioclub", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            r[0] = JOptionPane.OK_OPTION;
            d.dispose();
        });
        bClave.setEnabled(usarInvitacion != null);
        bClave.addActionListener(e -> {
            if (!app.cuentaB26.exigirCuenta(d, "unirte a tu radioclub")) return;
            java.util.SortedMap<String, String> inv = claveRCDesdeMiCuenta(d);   // primero, la guardada en tu cuenta
            if (inv == null) inv = pedirClaveInvitacion(d);
            if (inv == null) return;
            invitacionElegida = inv;
            r[0] = 2;
            d.dispose();
        });
        bCancelar.addActionListener(e -> d.dispose());
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bot.add(bClave);
        bot.add(bCrear);
        bot.add(bCancelar);
        JPanel todo = new JPanel(new BorderLayout());
        todo.add(intro, BorderLayout.NORTH);
        todo.add(datos, BorderLayout.CENTER);
        todo.add(bot, BorderLayout.SOUTH);
        d.setContentPane(todo);
        d.pack();
        d.setLocationRelativeTo(app);
        d.setVisible(true);
        return r[0];
    }

    void abrirDialogoConfigRadioclub() {
        boolean crear = hayQueCrearRadioclub();
        configurarRadioclubInterno();
        // Recién creado: se vuelve a abrir, ya con la pestaña «Gestión de …» para configurar el resto
        if (crear && !LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "").isEmpty()) SwingUtilities.invokeLater(this::abrirDialogoConfigRadioclub);
    }

    private void configurarRadioclubInterno() {
        // Compartiendo, los datos del radioclub solo se cambian en el ordenador del radioclub (en los demás se ven, sin poder cambiarlos)
        boolean puedeCambiarRC = app.cbSelectorActividad == null || tengoPermiso("DATOS");
        String[] clavesRC = {"RADIOCLUB_NOMBRE", "RADIOCLUB_IND_CB", "RADIOCLUB_IND_HF", "RADIOCLUB_IND_ESP", "RADIOCLUB_QTH", "RADIOCLUB_GRID", "RADIOCLUB_WEB"};
        StringBuilder antesRC = new StringBuilder();
        for (String k : clavesRC) antesRC.append(k).append('=').append(LibroGuardiaUI.ConfigApp.get(k, "")).append(SEP_REG);
        JTextField txtNombreRC = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_NOMBRE", ""), 20);
        JTextField txtCB = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_CB", ""), 12);
        JTextField txtHF = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_HF", ""), 12);
        JTextField txtEsp = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_ESP", ""), 12);
        JTextField txtQthRC = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_QTH", ""), 20);
        JTextField txtGridRC = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_GRID", ""), 8);
        JTextField txtWebRC = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_WEB", ""), 20);   // la web va tal cual (sin mayúsculas)
        txtWebRC.setToolTipText("Página web del radioclub. Pinchando en el logo, el nombre o los indicativos del radioclub se abre");
        app.aplicarFiltroMayusculas(txtNombreRC);
        app.aplicarFiltroMayusculas(txtCB);
        app.aplicarFiltroMayusculas(txtHF);
        app.aplicarFiltroMayusculas(txtEsp);
        app.aplicarFiltroMayusculas(txtQthRC);
        app.aplicarFiltroMayusculas(txtGridRC);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        String[] etiquetas = {"Nombre del radioclub:", "Indicativo CB:", "Indicativo HF:", "Indicativo especial:", "QTH:", "Grid:", "Página web:"};
        JTextField[] campos = {txtNombreRC, txtCB, txtHF, txtEsp, txtQthRC, txtGridRC, txtWebRC};
        for (int i = 0; i < etiquetas.length; i++) {
            gbc.gridy = i;
            gbc.gridx = 0;
            panel.add(new JLabel(etiquetas[i]), gbc);
            gbc.gridx = 1;
            panel.add(campos[i], gbc);
        }
        // Grid del radioclub: también se puede elegir pinchando en el mapa
        JButton btnMapaGridRC = new JButton("Elegir en el mapa…");
        btnMapaGridRC.setToolTipText("Abre un mapa con la cuadrícula de locators: pincha en el QTH del radioclub");
        btnMapaGridRC.addActionListener(e -> {
            String elegido = app.elegirGridEnMapa(panel, txtGridRC.getText().trim(), txtQthRC.getText().trim());
            if (elegido != null) txtGridRC.setText(elegido);
        });
        gbc.gridy = 5;
        gbc.gridx = 2;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(btnMapaGridRC, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        // Logo del radioclub: se elige una imagen y se guarda una copia en Documentos\\Libro de Guardia
        final File[] logoElegido = {null};
        final boolean[] quitarLogo = {false};
        JLabel vistaLogo = new JLabel();
        vistaLogo.setPreferredSize(new Dimension(60, 60));
        vistaLogo.setHorizontalAlignment(SwingConstants.CENTER);
        vistaLogo.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        java.util.function.Consumer<File> verLogo = f -> {
            vistaLogo.setIcon(null);
            vistaLogo.setText(f == null ? "sin logo" : "");
            if (f == null) return;
            try {
                BufferedImage img = ImageIO.read(f);
                if (img == null) { vistaLogo.setText("?"); return; }
                double e = Math.min(56.0 / img.getWidth(), 56.0 / img.getHeight());
                vistaLogo.setIcon(new ImageIcon(img.getScaledInstance(
                        Math.max(1, (int) (img.getWidth() * e)), Math.max(1, (int) (img.getHeight() * e)), Image.SCALE_SMOOTH)));
            } catch (Exception ex) {
                vistaLogo.setText("?");
            }
        };
        verLogo.accept(ficheroLogoRadioclub().isFile() ? ficheroLogoRadioclub() : null);
        vistaLogo.setFont(vistaLogo.getFont().deriveFont(10f));
        JButton btnElegirLogo = new JButton("Elegir imagen...");
        JButton btnQuitarLogo = new JButton("Quitar");
        btnElegirLogo.addActionListener(ev -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Elige el logo del radioclub");
            fc.setFileFilter(new FileNameExtensionFilter("Imágenes PNG o JPG (*.png, *.jpg)", "png", "jpg", "jpeg"));
            fc.setAcceptAllFileFilterUsed(false);
            if (fc.showOpenDialog(btnElegirLogo) == JFileChooser.APPROVE_OPTION) {
                File elegido = fc.getSelectedFile();
                String motivo = comprobarLogoRadioclub(elegido);
                if (motivo != null) {
                    JOptionPane.showMessageDialog(btnElegirLogo,
                            "No se puede usar esta imagen como logo:\n\n" + motivo + "\n\n" + TEXTO_REQUISITOS_LOGO,
                            "Logo del radioclub", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                logoElegido[0] = elegido;
                quitarLogo[0] = false;
                verLogo.accept(logoElegido[0]);
            }
        });
        btnQuitarLogo.addActionListener(ev -> {
            logoElegido[0] = null;
            quitarLogo[0] = true;
            verLogo.accept(null);
        });
        JPanel filaLogo = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaLogo.add(vistaLogo);
        filaLogo.add(btnElegirLogo);
        filaLogo.add(btnQuitarLogo);
        gbc.gridy = etiquetas.length;
        gbc.gridx = 0;
        panel.add(new JLabel("Logo:"), gbc);
        gbc.gridx = 1;
        panel.add(filaLogo, gbc);

        gbc.gridy = etiquetas.length + 1;
        gbc.gridx = 1;
        JLabel notaLogo = new JLabel("<html>El logo debe ser <b>PNG o JPG</b>, como máximo <b>300 × 300 píxeles</b>.<br>"
                + "Con 72 ppp es suficiente. Mejor cuadrado (en PNG puede llevar fondo transparente).</html>");
        notaLogo.setFont(notaLogo.getFont().deriveFont(11f));
        notaLogo.setForeground(new Color(255, 120, 120));
        panel.add(notaLogo, gbc);

        gbc.gridy = etiquetas.length + 2;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        JLabel nota = new JLabel("<html>Rellena solo los indicativos que tenga el radioclub.<br>"
                + "Si los dejas todos vacíos, no podrás crear actividades del radioclub<br>"
                + "(en Nueva Actividad no saldrá la casilla del radioclub).</html>");
        nota.setForeground(new Color(150, 170, 200));
        panel.add(nota, gbc);

        // COMPARTIR con el radioclub: carpeta compartida (Google Drive, OneDrive, Dropbox...)
        if (!puedeCambiarRC) {
            for (JTextField t : campos) t.setEditable(false);
            btnMapaGridRC.setEnabled(false);
        }
        JCheckBox chkCompartir = new JCheckBox("Compartir con el radioclub", "1".equals(LibroGuardiaUI.ConfigApp.get("RC_SYNC_ACTIVO", "0")));
        chkCompartir.addActionListener(ev -> {   // compartir con el radioclub solo con cuenta
            if (chkCompartir.isSelected() && !app.cuentaB26.exigirCuenta(chkCompartir, "compartir con el radioclub")) chkCompartir.setSelected(false);
        });
        JCheckBox chkEsRC = new JCheckBox("Este es el ordenador del radioclub (el que administra socios, cuotas y datos)", esOrdenadorRadioclub());
        chkEsRC.setEnabled(!esOrdenadorRadioclub() && LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "").isEmpty());   // el PC Radioclub es fijo
        JTextField txtCarpeta = new JTextField(syncPorFtp() ? "" : LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", ""), 24);
        JButton btnCarpeta = new JButton("Elegir carpeta…");
        btnCarpeta.addActionListener(ev -> {
            JFileChooser fc = new JFileChooser(txtCarpeta.getText().isEmpty() ? null : new File(txtCarpeta.getText()));
            fc.setDialogTitle("Carpeta compartida del radioclub (Google Drive)");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(btnCarpeta) == JFileChooser.APPROVE_OPTION) txtCarpeta.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel filaDisco = new JPanel(new BorderLayout(6, 0));
        filaDisco.add(txtCarpeta, BorderLayout.CENTER);
        filaDisco.add(btnCarpeta, BorderLayout.EAST);
        // ...o un servidor FTP
        String[] ftpDatos = {LibroGuardiaUI.ConfigApp.get("RC_FTP_SERVIDOR", ""), LibroGuardiaUI.ConfigApp.get("RC_FTP_PUERTO", "21"), LibroGuardiaUI.ConfigApp.get("RC_FTP_USUARIO", ""),
                LibroGuardiaUI.ConfigApp.get("RC_FTP_CLAVE", ""), LibroGuardiaUI.ConfigApp.get("RC_FTP_CARPETA", ""), LibroGuardiaUI.ConfigApp.get("RC_FTP_SEGURO", "0")};
        JLabel lblFtp = new JLabel();
        Runnable pintarFtp = () -> lblFtp.setText(ftpDatos[0].isBlank() ? "(sin configurar)"
                : (("1".equals(ftpDatos[5]) ? "ftps://" : "ftp://") + ftpDatos[2] + "@" + ftpDatos[0] + (ftpDatos[4].isBlank() ? "" : "/" + ftpDatos[4].replaceFirst("^/+", ""))));
        pintarFtp.run();
        JButton btnFtp = new JButton("Datos del FTP…");
        btnFtp.addActionListener(ev -> {
            String[] r = pedirDatosFtp(btnFtp, ftpDatos);
            if (r != null) { System.arraycopy(r, 0, ftpDatos, 0, r.length); pintarFtp.run(); }
        });
        JPanel filaFtp = new JPanel(new BorderLayout(6, 0));
        filaFtp.add(lblFtp, BorderLayout.CENTER);
        filaFtp.add(btnFtp, BorderLayout.EAST);
        CardLayout tarjetas = new CardLayout();
        JPanel donde = new JPanel(tarjetas);
        donde.add(filaDisco, "CARPETA");
        donde.add(filaFtp, "FTP");
        JComboBox<String> cbDonde = new JComboBox<>(new String[]{"Carpeta de Google Drive", "Servidor FTP"});
        cbDonde.setSelectedIndex(syncPorFtp() ? 1 : 0);
        cbDonde.addActionListener(ev -> tarjetas.show(donde, cbDonde.getSelectedIndex() == 1 ? "FTP" : "CARPETA"));
        tarjetas.show(donde, cbDonde.getSelectedIndex() == 1 ? "FTP" : "CARPETA");
        JPanel filaCarpeta = new JPanel(new BorderLayout(6, 0));
        filaCarpeta.add(cbDonde, BorderLayout.WEST);
        filaCarpeta.add(donde, BorderLayout.CENTER);
        // Dos pestañas: «Datos» (panel) y «Gestión de <radioclub>» (compartir, socios, contraseña…)
        // (la pestaña de gestión se monta más abajo, por apartados: Compartir · Socios · Ordenador del radioclub · Contraseña)
        JPanel pGestion = new JPanel();
        pGestion.setLayout(new BoxLayout(pGestion, BoxLayout.Y_AXIS));
        pGestion.setBorder(BorderFactory.createEmptyBorder(4, 16, 10, 16));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel notaCompartir = new JLabel("<html>Se comparten el LOG Radioclub, sus actividades, los socios, estos datos y el logo. Cada socio cambia sus propios contactos.<br>"
                + "Lo demás (socios, cuotas, datos, contactos de otros…) lo cambia el PC Radioclub o quien tenga permiso (Socios del radioclub → Gestionar permisos).<br>"
                + "Quien no tiene permiso para las cuotas ve solo la suya. Funciona sin internet y se envía al volver a tenerlo.</html>");
        notaCompartir.setFont(notaCompartir.getFont().deriveFont(11f));
        notaCompartir.setForeground(new Color(150, 170, 200));
        // Clave de invitación (radioclub) / unirse con una clave (socios) y lista de ordenadores (radioclub)
        final String[] pubInvitacion = {null};
        final java.util.SortedMap<String, String>[] invitacion = new java.util.SortedMap[]{null};
        final java.util.function.BiConsumer<java.util.SortedMap<String, String>, Boolean>[] usarInvitacion = new java.util.function.BiConsumer[]{null};
        JPanel filaClave = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaClave.setOpaque(false);
        JPanel filaSociosRC = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaSociosRC.setOpaque(false);
        JPanel filaOrdenadorRC = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaOrdenadorRC.setOpaque(false);
        {   // Socios y cuotas (antes en el menú Mi Estación): quien tiene permiso los gestiona; los demás solo los ven (y su propia cuota)
            boolean gestionaSocios = syncActivo() && (tengoPermiso("SOCIOS") || tengoPermiso("CUOTAS"));
            if (gestionaSocios || (syncActivo() && radioclubConfigurado())) {
                JButton bSociosCuotas = new JButton((gestionaSocios ? "Gestión de " : "Socios de ") + nombreRadioclub() + "…");
                bSociosCuotas.setToolTipText("Lista de socios y cuotas");
                bSociosCuotas.addActionListener(ev -> abrirGestionSocios());
                filaSociosRC.add(bSociosCuotas);
            }
        }
        if (!esOrdenadorRadioclub() && soySuperRC()) {
            // Super Admin: si el ordenador del radioclub se estropea, este puede pasar a serlo
            JButton bQuedar = new JButton("Hacer este el ordenador del radioclub…");
            bQuedar.addActionListener(ev -> {
                if (JOptionPane.showConfirmDialog(panel, "¿Convertir ESTE ordenador en el del radioclub?\n\n"
                                + "Hazlo solo si el ordenador del radioclub se ha estropeado o ya no se va a usar.\n"
                                + "El anterior pasará a ser uno más.", "Ordenador del radioclub", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                        != JOptionPane.YES_OPTION) return;
                String prueba = pedirClaveRadioclub(panel, "hacer este el ordenador del radioclub");
                if (prueba == null) return;
                try {
                    String error = cederRadioclub(java.util.Base64.getEncoder().encodeToString(llaveDispositivo().getPublic().getEncoded()), prueba);
                    JOptionPane.showMessageDialog(panel, error == null ? "Hecho: este es ahora el ordenador del radioclub.\nCierra Configurar Radioclub con \"Cancelar\" y vuelve a abrirlo."
                            : "No se ha podido: " + error, "Ordenador del radioclub", error == null ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
                    if (error == null) registrarCambio("Pasó a ser el ordenador del radioclub", "", "");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(panel, "No se ha podido: " + ex.getMessage(), "Ordenador del radioclub", JOptionPane.ERROR_MESSAGE);
                }
            });
            filaOrdenadorRC.add(bQuedar);
        }
        if (syncActivo()) {
            JButton bGenerar = new JButton("Clave de invitación para los socios…");
            bGenerar.addActionListener(ev -> mostrarClaveInvitacion(panel));
            JButton bOrdenadores = new JButton("Socios del radioclub…");
            bOrdenadores.addActionListener(ev -> abrirOrdenadoresRadioclub(panel));
            filaSociosRC.add(bOrdenadores);
            if (tengoPermiso("INVITAR")) filaSociosRC.add(bGenerar);
        }
        // En los ordenadores de los socios: con la clave no hace falta ver carpetas ni FTP
        final boolean[] porClave = {"1".equals(LibroGuardiaUI.ConfigApp.get("RC_SYNC_POR_CLAVE", "0")) && syncActivo() && !esOrdenadorRadioclub()};
        final boolean[] verAMano = {false};      // los detalles (carpeta, FTP...) solo si se pide "Configurar a mano…"
        final boolean[] dejarPulsado = {false};
        JLabel lblClaveInfo = new JLabel();
        lblClaveInfo.setFont(lblClaveInfo.getFont().deriveFont(Font.BOLD, 12f));
        JButton bAMano = new JButton("Configurar a mano…");
        bAMano.setToolTipText("Solo para el ordenador del radioclub o si no tienes clave de invitación");
        JButton bDejar = new JButton("Dejar de compartir");
        Runnable aplicarModo = () -> {
            boolean esRC = esOrdenadorRadioclub();
            boolean completo = esRC || verAMano[0];
            // «Compartir con el radioclub» (y dónde: carpeta o FTP) se ve siempre, aunque ya esté configurado:
            // si el FTP se cae o el radioclub cambia de carpeta, se cambia aquí
            chkCompartir.setVisible(true);
            chkEsRC.setVisible(completo);
            notaCompartir.setVisible(completo);
            cbDonde.setVisible(true);
            filaCarpeta.setVisible(true);
            lblClaveInfo.setVisible(!esRC && !completo);
            if (chkCompartir.isSelected()) {
                lblClaveInfo.setForeground(app.cat.VERDE_ENCENDIDO);
                lblClaveInfo.setText("✓ Compartiendo con " + nombreRadioclub() + (porClave[0] ? " (clave de invitación)" : "")
                        + (porClave[0] && cbDonde.getSelectedIndex() == 0 ? ": elige arriba la carpeta que te ha compartido el radioclub" : ""));
            } else if (dejarPulsado[0]) {
                lblClaveInfo.setForeground(app.TEMA_AMBAR);
                lblClaveInfo.setText("Al pulsar Aceptar se dejará de compartir con el radioclub");
            } else {
                lblClaveInfo.setForeground(app.TEMA_AMBAR);
                lblClaveInfo.setText("Para compartir con tu radioclub, pídele la clave de invitación y pulsa \"Unirme con una clave…\"");
            }
            bAMano.setVisible(!esRC && !completo);
            bDejar.setVisible(!esRC && !completo && chkCompartir.isSelected());
            Window w = SwingUtilities.getWindowAncestor(panel);
            if (w != null) w.pack();
        };
        bAMano.addActionListener(ev -> { verAMano[0] = true; aplicarModo.run(); });
        bDejar.addActionListener(ev -> { chkCompartir.setSelected(false); pubInvitacion[0] = null; dejarPulsado[0] = true; aplicarModo.run(); });
        if (!esOrdenadorRadioclub()) {
            JButton bUnirme = new JButton(syncActivo() ? "Unirme con otra clave…" : "Unirme con una clave…");
            bUnirme.setToolTipText("Pega la clave de invitación que te ha pasado el radioclub");
            usarInvitacion[0] = (inv, avisar) -> {
                pubInvitacion[0] = inv.get("pub");
                invitacion[0] = inv;
                // Los datos del radioclub vienen en la clave: se rellenan los que estén vacíos
                JTextField[] camposRC = {txtNombreRC, txtCB, txtHF, txtEsp, txtQthRC, txtGridRC, txtWebRC};
                String[] clavesCampos = {"RADIOCLUB_NOMBRE", "RADIOCLUB_IND_CB", "RADIOCLUB_IND_HF", "RADIOCLUB_IND_ESP", "RADIOCLUB_QTH", "RADIOCLUB_GRID", "RADIOCLUB_WEB"};
                for (int ci = 0; ci < camposRC.length; ci++) {
                    int k = java.util.Arrays.asList(CLAVES_RC_SYNC).indexOf(clavesCampos[ci]);
                    String v = k < 0 ? "" : inv.getOrDefault("r" + k, "");
                    if (camposRC[ci].getText().isBlank() && !v.isBlank()) camposRC[ci].setText(v);
                }
                chkCompartir.setSelected(true);
                chkEsRC.setSelected(false);
                porClave[0] = true;
                verAMano[0] = false;
                dejarPulsado[0] = false;
                if ("FTP".equals(inv.get("tipo"))) {
                    ftpDatos[0] = inv.getOrDefault("h", ""); ftpDatos[1] = inv.getOrDefault("p", "21"); ftpDatos[2] = inv.getOrDefault("u", "");
                    ftpDatos[3] = inv.getOrDefault("c", ""); ftpDatos[4] = inv.getOrDefault("d", ""); ftpDatos[5] = inv.getOrDefault("s", "0");
                    pintarFtp.run();
                    cbDonde.setSelectedIndex(1);
                    aplicarModo.run();
                    if (avisar) JOptionPane.showMessageDialog(panel, "Clave correcta (" + inv.getOrDefault("n", "radioclub") + ").\n\nPulsa Aceptar para empezar a compartir.",
                            "Unirme al radioclub", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    cbDonde.setSelectedIndex(0);
                    aplicarModo.run();
                    if (avisar) JOptionPane.showMessageDialog(panel, "Clave correcta (" + inv.getOrDefault("n", "radioclub") + ").\n\n"
                                    + "El radioclub comparte con una carpeta de Google Drive: pulsa \"Elegir carpeta…\",\n"
                                    + "elige la carpeta que el radioclub te ha compartido y después pulsa Aceptar.",
                            "Unirme al radioclub", JOptionPane.INFORMATION_MESSAGE);
                }
            };
            bUnirme.addActionListener(ev -> {
                if (!app.cuentaB26.exigirCuenta(panel, "unirte a tu radioclub")) return;
                java.util.SortedMap<String, String> guardada = claveRCDesdeMiCuenta(panel);
                if (guardada != null) { usarInvitacion[0].accept(guardada, true); return; }
                java.util.SortedMap<String, String> inv = pedirClaveInvitacion(panel);
                if (inv != null) usarInvitacion[0].accept(inv, true);
            });
            filaClave.add(bUnirme);
            filaClave.add(bDejar);
            filaClave.add(bAMano);
        }

        // Contraseña del radioclub (solo en el ordenador del radioclub): la piden los administradores para quedarse con él
        JPasswordField pfRc = new JPasswordField(12), pfRc2 = new JPasswordField(12);
        JPanel filaClaveRC = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaClaveRC.setOpaque(false);
        boolean hayClaveRC = !LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_VER", "").isEmpty();
        filaClaveRC.add(new JLabel(hayClaveRC ? "Nueva contraseña:" : "Contraseña:"));
        filaClaveRC.add(pfRc);
        filaClaveRC.add(new JLabel("Repítela:"));
        filaClaveRC.add(pfRc2);
        JLabel notaClaveRC = new JLabel(hayClaveRC ? "(ya tiene; déjala vacía para no cambiarla)" : "(sin poner: los administradores no podrán quedarse con el ordenador del radioclub)");
        notaClaveRC.setFont(notaClaveRC.getFont().deriveFont(11f));
        notaClaveRC.setForeground(hayClaveRC ? new Color(150, 170, 200) : app.TEMA_AMBAR);
        JPanel zonaClaveRC = new JPanel();
        zonaClaveRC.setOpaque(false);
        zonaClaveRC.setLayout(new BoxLayout(zonaClaveRC, BoxLayout.Y_AXIS));
        // Como en la ficha del operador: si ya tiene contraseña, los campos solo salen al pulsar «Cambiar contraseña»
        JButton bCambiarClaveRC = new JButton("Cambiar contraseña");
        JPanel filaCambiarRC = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaCambiarRC.setOpaque(false);
        filaCambiarRC.add(bCambiarClaveRC);
        notaClaveRC.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 0));
        for (JComponent x : new JComponent[]{filaCambiarRC, filaClaveRC, notaClaveRC}) { x.setAlignmentX(Component.LEFT_ALIGNMENT); zonaClaveRC.add(x); }
        filaCambiarRC.setVisible(hayClaveRC);
        filaClaveRC.setVisible(!hayClaveRC);
        notaClaveRC.setVisible(!hayClaveRC);
        if (hayClaveRC) notaClaveRC.setText("(déjala vacía para no cambiarla)");
        bCambiarClaveRC.addActionListener(ev -> {
            filaCambiarRC.setVisible(false);
            filaClaveRC.setVisible(true);
            notaClaveRC.setVisible(true);
            zonaClaveRC.setMaximumSize(new Dimension(Integer.MAX_VALUE, zonaClaveRC.getPreferredSize().height));
            zonaClaveRC.revalidate();
            Window w = SwingUtilities.getWindowAncestor(zonaClaveRC);
            if (w != null) w.pack();
            pfRc.requestFocusInWindow();
        });
        // ---- Pestaña de gestión, por apartados (cada uno con su título, uno debajo de otro)
        {
            java.util.function.Function<String, JLabel> tituloRC = t -> {
                JLabel l = new JLabel(t);
                l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
                l.setForeground(app.TEMA_AMBAR);
                l.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
                return l;
            };
            java.util.function.Consumer<JComponent> poner = comp -> {
                comp.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (comp instanceof JPanel f && f.getLayout() instanceof FlowLayout) f.setMaximumSize(new Dimension(Integer.MAX_VALUE, f.getPreferredSize().height));
                pGestion.add(comp);
                pGestion.add(Box.createVerticalStrut(4));
            };
            // Un título que solo se ve si se ve lo que tiene debajo
            java.util.function.BiConsumer<JLabel, JComponent> seguir = (titulo, comp) -> {
                titulo.setVisible(comp.isVisible());
                comp.addComponentListener(new ComponentAdapter() {
                    @Override public void componentShown(ComponentEvent e) { titulo.setVisible(true); }
                    @Override public void componentHidden(ComponentEvent e) { titulo.setVisible(false); }
                });
            };
            // Compartir
            poner.accept(tituloRC.apply("Compartir con el radioclub"));
            poner.accept(chkCompartir);
            JPanel filaDonde = new JPanel(new BorderLayout(8, 0));
            filaDonde.setOpaque(false);
            filaDonde.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
            filaDonde.add(filaCarpeta, BorderLayout.CENTER);
            filaDonde.setMaximumSize(new Dimension(760, filaCarpeta.getPreferredSize().height + 4));
            poner.accept(filaDonde);
            JPanel filaInfo = new JPanel(new FlowLayout(FlowLayout.LEFT, 22, 2));
            filaInfo.setOpaque(false);
            filaInfo.add(lblClaveInfo);
            poner.accept(filaInfo);
            filaClave.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
            poner.accept(filaClave);
            notaCompartir.setBorder(BorderFactory.createEmptyBorder(4, 22, 0, 0));
            poner.accept(notaCompartir);
            // Carpeta de actividades (diplomas) y buscador para la web
            poner.accept(tituloRC.apply("Carpeta de actividades (diplomas)"));
            JPanel filaDip = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            filaDip.setOpaque(false);
            filaDip.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
            JTextField txtDip = new JTextField(LibroGuardiaUI.ConfigApp.get("RC_CARPETA_DIPLOMAS", ""), 34);
            txtDip.setEditable(false);
            JButton bDip = new JButton("Elegir carpeta…");
            bDip.addActionListener(ev -> {
                JFileChooser fc = new JFileChooser(txtDip.getText().isEmpty() ? null : new File(txtDip.getText()));
                fc.setDialogTitle("Carpeta de los diplomas de las actividades (por ejemplo, en Google Drive)");
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (fc.showOpenDialog(bDip) == JFileChooser.APPROVE_OPTION) {
                    txtDip.setText(fc.getSelectedFile().getAbsolutePath());
                    LibroGuardiaUI.ConfigApp.put("RC_CARPETA_DIPLOMAS", txtDip.getText());
                }
            });
            JButton bIframe = new JButton("Generar Buscador de Diplomas…");
            bIframe.addActionListener(ev -> generarIframeDiplomas(bIframe));
            filaDip.add(txtDip);
            filaDip.add(bDip);
            filaDip.add(bIframe);
            poner.accept(filaDip);
            JLabel notaDip = new JLabel("<html>Al crear un diploma de una actividad del radioclub, además de en su carpeta de siempre, se guarda una copia aquí (subcarpeta de la actividad).<br>"
                    + "<b>Importante:</b> instala antes <b>Google Drive para ordenador</b> y elige una carpeta <b>dentro de «Mi unidad»</b> (mejor con el indicativo del radioclub).<br>"
                    + "Si otros socios también generan diplomas, compárteles esa carpeta como Editor. Para el buscador de la web, compártela con «cualquiera con el enlace».<br>"
                    + "El enlace para el buscador se copia desde la web de Google Drive (drive.google.com), no desde la carpeta del ordenador.</html>");
            notaDip.setFont(notaDip.getFont().deriveFont(11f));
            notaDip.setForeground(new Color(150, 170, 200));
            notaDip.setBorder(BorderFactory.createEmptyBorder(4, 22, 0, 0));
            poner.accept(notaDip);
            JLabel guiaDip = new JLabel("<html><u>¿Cómo se configura? Abre la guía paso a paso con imágenes</u></html>");
            guiaDip.setForeground(new Color(111, 168, 255));
            guiaDip.setFont(guiaDip.getFont().deriveFont(Font.BOLD, 11f));
            guiaDip.setBorder(BorderFactory.createEmptyBorder(4, 22, 0, 0));
            guiaDip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            guiaDip.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    try { Desktop.getDesktop().browse(new java.net.URI("https://bitacora26.es/guia.html#buscador-diplomas")); } catch (Exception ignored) { }
                }
            });
            poner.accept(guiaDip);
            // Socios
            if (filaSociosRC.getComponentCount() > 0) {
                poner.accept(tituloRC.apply("Socios"));
                poner.accept(filaSociosRC);
            }
            // Ordenador del radioclub
            JLabel tOrd = tituloRC.apply("Ordenador del radioclub");
            poner.accept(tOrd);
            JPanel filaEsRC = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            filaEsRC.setOpaque(false);
            filaEsRC.add(chkEsRC);
            poner.accept(filaEsRC);
            if (filaOrdenadorRC.getComponentCount() > 0) poner.accept(filaOrdenadorRC);
            if (filaOrdenadorRC.getComponentCount() == 0) seguir.accept(tOrd, chkEsRC);
            // Contraseña del radioclub
            JLabel tClave = tituloRC.apply("Contraseña del radioclub");
            poner.accept(tClave);
            zonaClaveRC.setMaximumSize(new Dimension(Integer.MAX_VALUE, zonaClaveRC.getPreferredSize().height));
            poner.accept(zonaClaveRC);
            seguir.accept(tClave, zonaClaveRC);
            pGestion.add(Box.createVerticalGlue());
        }
        final boolean poderRC = tienePoderRadioclub();   // el PC Radioclub o un Super Admin
        zonaClaveRC.setVisible(poderRC || chkEsRC.isSelected());
        chkEsRC.addActionListener(ev -> { zonaClaveRC.setVisible(poderRC || chkEsRC.isSelected()); Window w = SwingUtilities.getWindowAncestor(panel); if (w != null) w.pack(); });
        gbc.gridwidth = 1;
        aplicarModo.run();
        app.guardarOpcionCompartir = () -> {
            boolean porFtp = cbDonde.getSelectedIndex() == 1;
            // Con clave de invitación: se sabe desde el principio cuál es el ordenador del radioclub
            if (pubInvitacion[0] != null && !pubInvitacion[0].isEmpty()) {
                if (!pubInvitacion[0].equals(LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", ""))) {
                    try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement()) {
                        st.execute("DELETE FROM sync_pub");
                        st.execute("DELETE FROM sync_leido");
                        st.execute("DELETE FROM sync_pend");
                    } catch (Exception ignored) { }
                    LibroGuardiaUI.ConfigApp.put("RC_ADMIN_PUB", pubInvitacion[0]);
                }
            }
            // Clave personal: este ordenador es de ese socio; se crea (o completa) su operador con sus datos
            if (invitacion[0] != null && chkCompartir.isSelected()) {
                java.util.SortedMap<String, String> inv = invitacion[0];
                invitacion[0] = null;
                LibroGuardiaUI.ConfigApp.put("RC_SOCIO_INVITADO", inv.getOrDefault("ind", "").trim().toUpperCase());
                java.util.SortedMap<String, String> guardar = new java.util.TreeMap<>();
                for (String kk : new String[]{"shf", "scb", "snom", "sqth", "sgrid"}) guardar.put(kk, inv.getOrDefault(kk, ""));
                LibroGuardiaUI.ConfigApp.put("RC_INV_DATOS", codificar(guardar));
                String opNuevo = crearOperadorDesdeInvitacion(inv);
                if (opNuevo != null && !opNuevo.equals(app.operadorActual)) JOptionPane.showMessageDialog(app, "Tu operador " + opNuevo + " ya está en este ordenador con tus datos del radioclub.\n"
                        + "Si aún no tiene contraseña, se te pedirá al elegirlo.", "Unirme al radioclub", JOptionPane.INFORMATION_MESSAGE);
            }
            String carpeta = porFtp ? carpetaEspejoFtp().getPath() : carpetaBaseSync(txtCarpeta.getText());
            boolean activar = chkCompartir.isSelected() && (porFtp ? !ftpDatos[0].isBlank() : !carpeta.isEmpty());
            String origenAntes = LibroGuardiaUI.ConfigApp.get("RC_SYNC_ORIGEN", carpetaBaseSync(LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", "")));
            String origenNuevo = porFtp ? origenFtp(ftpDatos[0], ftpDatos[1], ftpDatos[2], ftpDatos[4]) : carpeta;
            boolean cambiaOrigen = !origenNuevo.equalsIgnoreCase(origenAntes);
            if (porFtp) {
                LibroGuardiaUI.ConfigApp.put("RC_FTP_SERVIDOR", ftpDatos[0].trim());
                LibroGuardiaUI.ConfigApp.put("RC_FTP_PUERTO", ftpDatos[1].trim().isEmpty() ? "21" : ftpDatos[1].trim());
                LibroGuardiaUI.ConfigApp.put("RC_FTP_USUARIO", ftpDatos[2].trim());
                LibroGuardiaUI.ConfigApp.put("RC_FTP_CLAVE", ftpDatos[3]);
                LibroGuardiaUI.ConfigApp.put("RC_FTP_CARPETA", ftpDatos[4].trim());
                LibroGuardiaUI.ConfigApp.put("RC_FTP_SEGURO", ftpDatos[5]);
            }
            if (activar && porFtp) {
                // Otro servidor u otra carpeta: la copia de aquí se empieza de nuevo
                if (cambiaOrigen) {
                    File[] viejos = new File(carpetaEspejoFtp(), CARPETA_SYNC).listFiles();
                    if (viejos != null) for (File v : viejos) v.delete();
                }
                app.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                try (LibroGuardiaUI.FtpSimple f = abrirFtpRadioclub()) {
                    traerDelFtp(f);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(app, "No se puede usar el FTP:\n\n" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()),
                            "Compartir con el radioclub", JOptionPane.WARNING_MESSAGE);
                    activar = false;
                } finally {
                    app.setCursor(Cursor.getDefaultCursor());
                }
            }
            String dondeTxt = porFtp ? "En el FTP" : "En esa carpeta";
            if (activar) {
                File dir = new File(carpeta, CARPETA_SYNC);
                File[] ficheros = dir.listFiles((x, n) -> n.startsWith("disp_") && n.endsWith(".txt") && n.length() > 0);
                boolean hayDatos = false;
                if (ficheros != null) for (File fi : ficheros) if (fi.length() > 0) hayDatos = true;
                if (esAccesoDirectoDeRed(new File(carpeta))) {
                    JOptionPane.showMessageDialog(app, "Esa carpeta es un acceso directo de Windows (por ejemplo a un FTP), no una carpeta de verdad:\n"
                            + carpeta + "\n\nLo que se guarde ahí se queda en este ordenador y no llega a los demás.\n\n"
                            + "Usad una carpeta de Google Drive (Google Drive para ordenador instalado),\n"
                            + "o una carpeta de red que tenga letra de unidad (por ejemplo Z:).", "Compartir con el radioclub", JOptionPane.WARNING_MESSAGE);
                    activar = false;
                } else if (!new File(carpeta).isDirectory()) {
                    JOptionPane.showMessageDialog(app, "No encuentro la carpeta:\n" + carpeta, "Compartir con el radioclub", JOptionPane.WARNING_MESSAGE);
                    activar = false;
                } else if (chkEsRC.isSelected() && !esOrdenadorRadioclub() && !radioclubCedido && (adminEnCarpeta(dir) != null || !LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "").isEmpty())) {
                    JOptionPane.showMessageDialog(app, dondeTxt + " ya hay otro ordenador que es el del radioclub.\n"
                            + "Solo puede haber uno.", "Compartir con el radioclub", JOptionPane.WARNING_MESSAGE);
                    activar = false;
                } else if (chkEsRC.isSelected() && !esOrdenadorRadioclub() && !radioclubCedido) {
                    {
                        try {
                            LibroGuardiaUI.ConfigApp.put("RC_ADMIN_PUB", java.util.Base64.getEncoder().encodeToString(llaveDispositivo().getPublic().getEncoded()));
                        } catch (Exception ex) {
                            activar = false;
                        }
                    }
                } else if (!chkEsRC.isSelected() && !esOrdenadorRadioclub() && !hayDatos) {
                    JOptionPane.showMessageDialog(app, dondeTxt + " todavía no hay datos del radioclub:\n" + (porFtp ? origenNuevo : carpeta) + "\n\n"
                            + "Primero tiene que activar \"Compartir\" el ordenador del radioclub (marcando la casilla\n"
                            + "\"Este es el ordenador del radioclub\"). Después, los demás eligen la misma carpeta.\n\n"
                            + (porFtp ? "Comprobad que todos tenéis puestos el mismo servidor y la misma carpeta del FTP."
                            : "Si el radioclub ya lo ha hecho, espera un par de minutos a que Google Drive\n"
                            + "traiga sus archivos a este ordenador y vuelve a intentarlo."),
                            "Compartir con el radioclub", JOptionPane.INFORMATION_MESSAGE);
                    activar = false;
                }
            }
            if (activar) {
                // Se comprueba ya que se puede escribir en ella (por FTP, en la copia de aquí: al servidor se sube al sincronizar)
                String problema = prepararCarpetaSync(carpeta);
                if (problema != null) {
                    JOptionPane.showMessageDialog(app, "No se puede compartir con esa carpeta:\n\n" + problema,
                            "Compartir con el radioclub", JOptionPane.WARNING_MESSAGE);
                    activar = false;
                }
            }
            // Sin compartir también se puede marcar como ordenador del radioclub
            if (!radioclubCedido && !activar && !chkCompartir.isSelected() && chkEsRC.isSelected() && !esOrdenadorRadioclub()) {
                try {
                    LibroGuardiaUI.ConfigApp.put("RC_ADMIN_PUB", java.util.Base64.getEncoder().encodeToString(llaveDispositivo().getPublic().getEncoded()));
                } catch (Exception ignored) { }
            }
            // Si cambia la carpeta, en la nueva se vuelve a dejar todo y se lee todo desde el principio
            if (activar && cambiaOrigen) {
                try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement()) {
                    st.execute("DELETE FROM sync_pub");
                    st.execute("DELETE FROM sync_leido");
                    st.execute("DELETE FROM sync_pend");
                } catch (Exception ignored) { }
            }
            LibroGuardiaUI.ConfigApp.put("RC_SYNC_CARPETA", carpeta);
            LibroGuardiaUI.ConfigApp.put("RC_SYNC_TIPO", porFtp ? "FTP" : "CARPETA");
            LibroGuardiaUI.ConfigApp.put("RC_SYNC_POR_CLAVE", activar && porClave[0] && !esOrdenadorRadioclub() ? "1" : "0");
            if (activar) LibroGuardiaUI.ConfigApp.put("RC_SYNC_ORIGEN", origenNuevo);
            LibroGuardiaUI.ConfigApp.put("RC_SYNC_ACTIVO", activar ? "1" : "0");
            if (!activar && dejarPulsado[0]) borrarClaveRCDeCuenta();   // deja el radioclub: la cuenta ya no lo lleva
            actualizarEstadoSync(activar ? "Compartiendo con el radioclub…" : null, null);
            if (activar) sincronizarPronto();
        };
        boolean sinVentana = false;
        if (app.invitacionInicial != null && usarInvitacion[0] != null) {
            java.util.SortedMap<String, String> inv = app.invitacionInicial;
            app.invitacionInicial = null;
            sinVentana = "FTP".equals(inv.get("tipo"));   // por FTP ya está todo: no hace falta la ventana
            usarInvitacion[0].accept(inv, !sinVentana);
        }
        JTabbedPane pestanasRC = new JTabbedPane();
        // Quien no puede cambiar los datos del radioclub solo ve la pestaña de gestión
        pestanasRC.addTab("Datos", panel);   // los socios también los ven (sin poder cambiarlos)
        String nomRc = LibroGuardiaUI.ConfigApp.get("RADIOCLUB_NOMBRE", "").trim();
        // La pestaña de gestión solo para quien puede cambiar algo del radioclub (un socio sin permisos solo ve los datos)
        boolean puedeGestionar = !syncActivo() || esOrdenadorRadioclub() || soySuperRC();
        for (String perm : new String[]{"DATOS", "ADMINS", "CLAVETEMP", "CUOTAS", "EXPULSAR", "INVITAR", "SOCIOS"}) if (tengoPermiso(perm)) puedeGestionar = true;
        if (puedeGestionar) pestanasRC.addTab("Gestión de " + (nomRc.isEmpty() ? "el radioclub" : nomRc), pGestion);
        int resp;
        if (!sinVentana && hayQueCrearRadioclub()) {
            // Todavía no hay radioclub: solo los datos y el botón «Crear Radioclub» (o unirse con la clave de invitación)
            resp = dialogoCrearRadioclub(panel, txtNombreRC, usarInvitacion[0]);
            if (resp == JOptionPane.OK_OPTION) chkEsRC.setSelected(true);
            if (resp == 2 && usarInvitacion[0] != null && invitacionElegida != null) {
                // Socio con clave: se rellena todo y se enseña la pestaña de gestión para terminar (Aceptar)
                usarInvitacion[0].accept(invitacionElegida, true);
                invitacionElegida = null;
                pestanasRC.setComponentAt(0, panel);   // el panel de datos estuvo en la otra ventana: vuelve a su pestaña
                if (pestanasRC.getTabCount() < 2) pestanasRC.addTab("Gestión del radioclub", pGestion);   // para terminar de unirse
                pestanasRC.setSelectedIndex(1);
                resp = JOptionPane.showConfirmDialog(app, pestanasRC, "Configurar Radioclub", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            }
        } else {
            resp = sinVentana ? JOptionPane.OK_OPTION : JOptionPane.showConfirmDialog(app, pestanasRC, "Configurar Radioclub",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        }
        if (resp != JOptionPane.OK_OPTION) return;

        if (!puedeCambiarRC) {   // en un ordenador que no es el del radioclub solo se guarda lo de compartir y el logo
            app.guardarOpcionCompartir.run();
            app.guardarLogoRadioclub(quitarLogo[0], logoElegido[0]);
            return;
        }
        String[] indsAntes = {LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_CB", "").trim().toUpperCase(),
                LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_HF", "").trim().toUpperCase(), LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_ESP", "").trim().toUpperCase()};
        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_NOMBRE", txtNombreRC.getText().trim().toUpperCase());
        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_IND_CB", txtCB.getText().trim().toUpperCase());
        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_IND_HF", txtHF.getText().trim().toUpperCase());
        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_IND_ESP", txtEsp.getText().trim().toUpperCase());
        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_QTH", txtQthRC.getText().trim().toUpperCase());
        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_GRID", txtGridRC.getText().trim().toUpperCase());
        LibroGuardiaUI.ConfigApp.put("RADIOCLUB_WEB", txtWebRC.getText().trim());
        StringBuilder despuesRC = new StringBuilder();
        for (String k : clavesRC) despuesRC.append(k).append('=').append(LibroGuardiaUI.ConfigApp.get(k, "")).append(SEP_REG);
        if (!despuesRC.toString().equals(antesRC.toString())) registrarCambio("Cambió los datos del radioclub", "RC_DATOS", antesRC.toString());
        // Un indicativo del radioclub corregido: se corrige también en sus contactos y actividades
        String[] indsDespues = {txtCB.getText().trim().toUpperCase(), txtHF.getText().trim().toUpperCase(), txtEsp.getText().trim().toUpperCase()};
        String[] queInd = {"CB", "HF", "especial"};
        for (int i = 0; i < 3; i++) {
            if (indsAntes[i].isEmpty() || indsDespues[i].isEmpty() || indsAntes[i].equals(indsDespues[i])) continue;
            int n = contarConIndicativo(indsAntes[i]);
            if (n == 0) continue;
            if (JOptionPane.showConfirmDialog(app, "Has cambiado el indicativo " + queInd[i] + " del radioclub de " + indsAntes[i] + " a " + indsDespues[i] + ".\n\n"
                            + "Hay " + n + " contactos y actividades con " + indsAntes[i] + ". ¿Cambiarlos también a " + indsDespues[i] + "?\n"
                            + "(Se corregirán en todos los ordenadores del radioclub al sincronizar.)",
                    "Indicativo del radioclub", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION) {
                int c = renombrarIndicativoRC(indsAntes[i], indsDespues[i]);
                JOptionPane.showMessageDialog(app, "Hecho: " + c + " cambios de " + indsAntes[i] + " a " + indsDespues[i] + ".", "Indicativo del radioclub", JOptionPane.INFORMATION_MESSAGE);
            }
        }
        app.guardarOpcionCompartir.run();
        // Contraseña del radioclub: se guarda solo su huella (los demás ordenadores no pueden sacarla)
        if (tienePoderRadioclub() && pfRc.getPassword().length > 0) {
            // Un Super Admin solo puede cambiarla si sabe la que hay ahora
            boolean sabeLaActual = esOrdenadorRadioclub() || LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_VER", "").isEmpty()
                    || pedirClaveRadioclub(app, "cambiarla (escribe la que hay AHORA)") != null;
            if (!sabeLaActual) {
                JOptionPane.showMessageDialog(app, "Sin la contraseña actual del radioclub no se puede cambiar: no se ha cambiado.",
                        "Contraseña del radioclub", JOptionPane.WARNING_MESSAGE);
            } else if (pfRc.getPassword().length < 4 || !java.util.Arrays.equals(pfRc.getPassword(), pfRc2.getPassword())) {
                JOptionPane.showMessageDialog(app, pfRc.getPassword().length < 4 ? "La contraseña del radioclub tiene que tener como mínimo 4 caracteres: no se ha cambiado."
                        : "Las dos contraseñas del radioclub no coinciden: no se ha cambiado.", "Contraseña del radioclub", JOptionPane.WARNING_MESSAGE);
            } else {
                String sal = nuevaSal();
                LibroGuardiaUI.ConfigApp.put("RADIOCLUB_CLAVE_SAL", sal);
                LibroGuardiaUI.ConfigApp.put("RADIOCLUB_CLAVE_VER", sha256B64(cifrarClave(pfRc.getPassword(), sal)));
                registrarCambio("Cambió la contraseña del radioclub", "", "");
                sincronizarPronto();
            }
        }
        // El indicativo CB del radioclub también va a la lista BANDA CIUDADANA (con su nombre y QTH)
        app.anadirOperadorCBAlDirectorio(new String[]{"", txtCB.getText(), txtNombreRC.getText(), txtQthRC.getText()});

        app.guardarLogoRadioclub(quitarLogo[0], logoElegido[0]);
        cargarLogoRadioclub();

        // Al primer arranque la ventana aún no tiene sus controles: se crearán ya con estos datos
        if (app.cbSelectorActividad == null) return;
        recargarDesplegableIndicativoRC();
        actualizarControlesRadioclub();
        Object selPrevia = app.cbSelectorActividad.getSelectedItem();
        app.actualizarDesplegableActividades();
        app.rehaciendoDesplegableActividades = true;   // se vuelve a la misma actividad: no se toca la banda
        try {
            if (selPrevia != null) app.cbSelectorActividad.setSelectedItem(selPrevia);
        } finally {
            app.rehaciendoDesplegableActividades = false;
        }
    }

    // ===================== CLAVE DE INVITACIÓN Y ORDENADORES DEL RADIOCLUB =====================
    private static final String PREFIJO_CLAVE = "B26-";

    // ================================================================ indicativo del radioclub corregido

    /** Contactos y actividades que usan ese indicativo como estación, operador o indicativo de la actividad. */
    private static int contarConIndicativo(String ind) {
        try (Connection c = LibroGuardiaUI.DatabaseManager.conectar();
             PreparedStatement ps = c.prepareStatement("SELECT (SELECT COUNT(*) FROM qsos WHERE UPPER(TRIM(COALESCE(estacion,''))) = ? OR UPPER(TRIM(COALESCE(operador,''))) = ?)"
                     + " + (SELECT COUNT(*) FROM actividades WHERE UPPER(TRIM(COALESCE(indicativo_rc,''))) = ?)")) {
            ps.setString(1, ind); ps.setString(2, ind); ps.setString(3, ind);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (Exception e) {
            return 0;
        }
    }

    /** Cambia un indicativo del radioclub (mal escrito) por el bueno. Los cambios se mandan a los demás al sincronizar. */
    int renombrarIndicativoRC(String viejo, String nuevo) {
        int n = 0;
        try (Connection c = LibroGuardiaUI.DatabaseManager.conectar()) {
            String[][] sqls = {{"UPDATE qsos SET estacion = ? WHERE UPPER(TRIM(COALESCE(estacion,''))) = ?"},
                    {"UPDATE qsos SET operador = ? WHERE UPPER(TRIM(COALESCE(operador,''))) = ?"},
                    {"UPDATE actividades SET indicativo_rc = ? WHERE UPPER(TRIM(COALESCE(indicativo_rc,''))) = ?"}};
            for (String[] q : sqls) {
                try (PreparedStatement ps = c.prepareStatement(q[0])) { ps.setString(1, nuevo); ps.setString(2, viejo); n += ps.executeUpdate(); }
            }
        } catch (Exception ignored) { }
        refrescarTrasSincronizar();
        sincronizarPronto();
        return n;
    }

    /**
     * Al abrir (en el ordenador del radioclub o con permiso para cambiar sus datos): actividades del radioclub con un indicativo
     * que ya no es del radioclub (estaba mal escrito y se corrigió antes de que esto existiera). Se pregunta una vez por cada uno.
     */
    void revisarIndicativosAntiguosRC() {
        try {
            if (!radioclubConfigurado() || !(esOrdenadorRadioclub() || (syncActivo() && tengoPermiso("DATOS")))) return;
            List<String> actuales = new ArrayList<>();
            for (String k : new String[]{"RADIOCLUB_IND_CB", "RADIOCLUB_IND_HF", "RADIOCLUB_IND_ESP"}) {
                String v = LibroGuardiaUI.ConfigApp.get(k, "").trim().toUpperCase();
                if (!v.isEmpty()) actuales.add(v);
            }
            if (actuales.isEmpty()) return;
            List<String> viejos = new ArrayList<>();
            try (Connection c = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT DISTINCT UPPER(TRIM(indicativo_rc)) FROM actividades WHERE COALESCE(radioclub,0) = 1 AND TRIM(COALESCE(indicativo_rc,'')) <> ''"
                         // y también los contactos del radioclub hechos con un indicativo del radioclub que ya no existe
                         + " UNION SELECT DISTINCT UPPER(TRIM(estacion)) FROM qsos WHERE COALESCE(radioclub,0) = 1 AND TRIM(COALESCE(estacion,'')) <> ''")) {
                while (rs.next()) { String v = rs.getString(1); if (!actuales.contains(v)) viejos.add(v); }
            }
            for (String v : viejos) {
                if ("1".equals(LibroGuardiaUI.ConfigApp.get("RC_IND_ANTIGUO_NO_" + v, ""))) continue;
                int n = contarConIndicativo(v);
                if (n == 0) continue;
                JComboBox<String> cb = new JComboBox<>(actuales.toArray(new String[0]));
                // Se propone el más parecido (mismo largo y casi las mismas letras)
                int mejor = 0, menos = Integer.MAX_VALUE;
                for (int i = 0; i < actuales.size(); i++) { int d = distancia(v, actuales.get(i)); if (d < menos) { menos = d; mejor = i; } }
                cb.setSelectedIndex(mejor);
                Object[] op = {"Cambiarlo", "No, está bien así"};
                int r = JOptionPane.showOptionDialog(app, new Object[]{"<html>Hay <b>" + n + "</b> contactos y actividades del radioclub con el indicativo <b>" + v + "</b>,<br>"
                                + "que ya no es un indicativo del radioclub (¿estaba mal escrito?).<br><br>Cambiarlo por:</html>", cb},
                        "Indicativo del radioclub", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, op, op[0]);
                if (r == 0) {
                    String nuevo = (String) cb.getSelectedItem();
                    int c = renombrarIndicativoRC(v, nuevo);
                    JOptionPane.showMessageDialog(app, "Hecho: " + c + " cambios de " + v + " a " + nuevo + ".\nSe corregirán en los demás ordenadores al sincronizar.",
                            "Indicativo del radioclub", JOptionPane.INFORMATION_MESSAGE);
                } else if (r == 1) {
                    LibroGuardiaUI.ConfigApp.put("RC_IND_ANTIGUO_NO_" + v, "1");
                }
            }
        } catch (Exception ignored) { }
    }

    private static int distancia(String a, String b) {
        int[] p = new int[b.length() + 1], q = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) p[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            q[0] = i;
            for (int j = 1; j <= b.length(); j++) q[j] = Math.min(Math.min(q[j - 1] + 1, p[j] + 1), p[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = p; p = q; q = t;
        }
        return p[b.length()];
    }

    // ================================================================ clave del radioclub en la cuenta Bitácora 26
    // La pertenencia al radioclub va con la cuenta: cualquier ordenador de la persona, al entrar con su cuenta, se une solo.
    // El servidor la guarda cifrada y solo la entrega a ordenadores conectados (con firma) a esa misma cuenta.

    /** Comprueba una clave de invitación (texto «B26-…»). Devuelve sus datos o null. */
    private static java.util.SortedMap<String, String> invitacionValida(String txt) {
        try {
            txt = txt.replaceAll("\\s+", "");
            if (!txt.startsWith(PREFIJO_CLAVE)) return null;
            java.util.SortedMap<String, String> m = decodificar(new String(java.util.Base64.getUrlDecoder().decode(txt.substring(PREFIJO_CLAVE.length())), StandardCharsets.UTF_8));
            java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(m.getOrDefault("pub", ""))));
            if (!"FTP".equals(m.get("tipo")) && !"CARPETA".equals(m.get("tipo"))) return null;
            if (m.getOrDefault("ind", "").isEmpty()) return null;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static String huella(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Cuenta de Bitácora 26 que va con el radioclub en este ordenador (la del operador; si no, la única que haya). */
    private static String cuentaParaRC() {
        String op = LibroGuardiaUI.ConfigApp.get("OPERADOR_ACTUAL", "").trim().toUpperCase();
        String c = op.isEmpty() ? "" : CuentaBitacora.cuentaB26DeOperador(op);
        if (c.isEmpty()) { List<String> cs = CuentaBitacora.cuentasB26(); if (cs.size() == 1) c = cs.get(0); }
        return c;
    }

    /** Guarda en la cuenta la clave del radioclub de este socio (solo si ha cambiado). Sin preguntar nada. */
    boolean guardarClaveRCEnCuenta(String cuenta, String socio) {
        if (cuenta == null || cuenta.isEmpty() || !syncActivo()) return false;
        if (socio == null || socio.isBlank() || esIndicativoDelRadioclub(socio)) return false;
        try {
            String inv = generarClaveInvitacion(socio.trim().toUpperCase());
            if (huella(inv).equals(LibroGuardiaUI.ConfigApp.get("RC_EN_CUENTA_" + cuenta, ""))) return true;   // ya está
            String[] r = app.cuentaB26.firmadoB26(cuenta, "rc_guardar", "{\"inv\":\"" + inv + "\"}");
            if (!"200".equals(r[0])) return false;
            LibroGuardiaUI.ConfigApp.put("RC_EN_CUENTA_" + cuenta, huella(inv));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** En segundo plano, tras sincronizar con el radioclub: que la cuenta tenga la clave (por si es un ordenador nuevo de la persona). */
    void subirClaveRCALaCuenta() {
        try {
            if (!syncActivo()) return;
            String op = app.operadorActual == null ? "" : app.operadorActual.trim().toUpperCase();
            String cuenta = cuentaParaRC();
            if (op.isEmpty() || cuenta.isEmpty()) return;
            guardarClaveRCEnCuenta(cuenta, op);
        } catch (Exception ignored) { }
    }

    /** La clave del radioclub que va con la cuenta (o null si no tiene). */
    java.util.SortedMap<String, String> leerClaveRCDeCuenta(String cuenta) {
        try {
            if (cuenta == null || cuenta.isEmpty()) return null;
            String[] r = app.cuentaB26.firmadoB26(cuenta, "rc_leer", "{}");
            if (!"200".equals(r[0])) return null;
            String inv = app.campoJson(r[1], "inv");
            return inv.isEmpty() ? null : invitacionValida(inv);
        } catch (Exception e) {
            return null;
        }
    }

    /** «Unirme con una clave»: primero la que va con la cuenta. */
    java.util.SortedMap<String, String> claveRCDesdeMiCuenta(Component padre) {
        return leerClaveRCDeCuenta(cuentaParaRC());
    }

    /** Al dejar el radioclub: la cuenta deja de llevarlo (si no, los demás ordenadores se volverían a unir). */
    void borrarClaveRCDeCuenta() {
        String cuenta = cuentaParaRC();
        if (cuenta.isEmpty()) return;
        new Thread(() -> {
            try {
                app.cuentaB26.firmadoB26(cuenta, "rc_borrar", "{}");
                LibroGuardiaUI.ConfigApp.put("RC_EN_CUENTA_" + cuenta, "");
            } catch (Exception ignored) { }
        }, "ClaveRC").start();
    }

    /**
     * Al abrir: si este ordenador tiene cuenta pero no está en el radioclub, y la cuenta lleva un radioclub, se une solo
     * (por FTP sin ventanas; por Google Drive se abre la ventana para elegir la carpeta compartida).
     */
    void unirseSiLaCuentaLoLleva() {
        if (syncActivo() || esOrdenadorRadioclub() || !app.cuentaB26.hayCuenta()) return;
        new Thread(() -> {
            java.util.SortedMap<String, String> inv = leerClaveRCDeCuenta(cuentaParaRC());
            if (inv == null) return;
            SwingUtilities.invokeLater(() -> {
                if (syncActivo() || app.invitacionInicial != null) return;
                app.invitacionInicial = inv;
                abrirDialogoConfigRadioclub();
            });
        }, "ClaveRC").start();
    }

    /** Datos conocidos de un socio por su indicativo (HF o CB): {HF, CB, Nombre, QTH, Grid}, o null. */
    String[] datosSocioConocido(String ind) {
        String i = ind == null ? "" : ind.trim().toUpperCase();
        if (i.isEmpty()) return null;
        String[] r = null;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
             PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(ind_hf,''), COALESCE(ind_cb,''), COALESCE(nombre,''), COALESCE(qth,'') FROM socios "
                     + "WHERE UPPER(TRIM(ind_hf)) = ? OR UPPER(TRIM(ind_cb)) = ? LIMIT 1")) {
            ps.setString(1, i); ps.setString(2, i);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) r = new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), ""}; }
        } catch (Exception ignored) { }
        // Lo que venía en la clave de invitación de este ordenador
        java.util.SortedMap<String, String> inv = null;
        try { String g = LibroGuardiaUI.ConfigApp.get("RC_INV_DATOS", ""); if (!g.isEmpty()) inv = decodificar(g); } catch (Exception ignored) { }
        if (inv != null && (i.equalsIgnoreCase(inv.getOrDefault("shf", "")) || i.equalsIgnoreCase(inv.getOrDefault("scb", "")))) {
            String[] d = {inv.getOrDefault("shf", ""), inv.getOrDefault("scb", ""), inv.getOrDefault("snom", ""), inv.getOrDefault("sqth", ""), inv.getOrDefault("sgrid", "")};
            if (r == null) r = d; else for (int k = 0; k < 5; k++) if (r[k].isBlank()) r[k] = d[k];
        }
        if (r == null) return null;
        // El grid (y lo que falte) de la ficha de operador, si este ordenador la tiene
        String id = r[0].isBlank() ? r[1].trim().toUpperCase() : r[0].trim().toUpperCase();
        if (app.obtenerListaOperadores().contains(id)) {
            String[] f = app.leerDatosOperador(id);
            for (int k = 0; k < 5; k++) if (r[k].isBlank() && f[k] != null) r[k] = f[k];
        }
        return r;
    }

    /** Texto que el radioclub pasa a UN socio: dónde se comparte, cuál es el PC Radioclub, los datos del radioclub y los del socio. */
    private String generarClaveInvitacion(String socio) { return generarClaveInvitacion(socio, false); }

    private String generarClaveInvitacion(String socio, boolean esCB) {
        java.util.SortedMap<String, String> m = new java.util.TreeMap<>();
        m.put("v", "2");
        m.put("n", nombreRadioclub());
        m.put("pub", LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", ""));
        if (syncPorFtp()) {
            m.put("tipo", "FTP");
            m.put("h", LibroGuardiaUI.ConfigApp.get("RC_FTP_SERVIDOR", ""));
            m.put("p", LibroGuardiaUI.ConfigApp.get("RC_FTP_PUERTO", "21"));
            m.put("u", LibroGuardiaUI.ConfigApp.get("RC_FTP_USUARIO", ""));
            m.put("c", LibroGuardiaUI.ConfigApp.get("RC_FTP_CLAVE", ""));
            m.put("d", LibroGuardiaUI.ConfigApp.get("RC_FTP_CARPETA", ""));
            m.put("s", LibroGuardiaUI.ConfigApp.get("RC_FTP_SEGURO", "0"));
        } else {
            m.put("tipo", "CARPETA");
        }
        for (int k = 0; k < CLAVES_RC_SYNC.length; k++) m.put("r" + k, LibroGuardiaUI.ConfigApp.get(CLAVES_RC_SYNC[k], ""));
        String ind = socio.trim().toUpperCase();
        m.put("ind", ind);
        String[] d = datosSocioConocido(ind);
        if (d == null) d = esCB ? new String[]{"", ind, "", "", ""} : new String[]{ind, "", "", "", ""};
        m.put("shf", d[0]); m.put("scb", d[1]); m.put("snom", d[2]); m.put("sqth", d[3]); m.put("sgrid", d[4]);
        return PREFIJO_CLAVE + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(codificar(m).getBytes(StandardCharsets.UTF_8));
    }

    private void mostrarClaveInvitacion(Component padre) { mostrarClaveInvitacion(padre, null); }

    /** Clave de invitación de un socio: cada socio tiene la suya, que solo vale con su indicativo. Siempre sale la misma para el mismo socio. */
    private void mostrarClaveInvitacion(Component padre, String socioElegido) {
        if (!syncActivo()) {
            JOptionPane.showMessageDialog(padre, "Primero activa \"Compartir con el radioclub\" (marca la casilla, elige dónde y pulsa Aceptar).\n"
                    + "Después vuelve aquí para sacar la clave.", "Clave de invitación", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String socio = socioElegido;
        if (socio == null || socio.isBlank()) {
            java.util.TreeSet<String> lista = new java.util.TreeSet<>();
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COALESCE(ind_hf,''), COALESCE(ind_cb,'') FROM socios")) {
                while (rs.next()) { String i = rs.getString(1).isBlank() ? rs.getString(2) : rs.getString(1); if (!i.isBlank() && !esIndicativoDelRadioclub(i)) lista.add(i.trim().toUpperCase()); }
            } catch (Exception ignored) { }
            JComboBox<String> cb = new JComboBox<>(lista.toArray(new String[0]));
            cb.setEditable(true);
            cb.setSelectedItem("");
            if (JOptionPane.showConfirmDialog(padre, new Object[]{"<html>Cada socio tiene su propia clave, que solo vale con su indicativo.<br>"
                            + "Elige el socio o escribe su indicativo (HF o, si no tiene, CB):</html>", cb},
                    "Clave de invitación", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
            Object o = cb.getEditor().getItem();
            socio = o == null ? "" : o.toString().trim().toUpperCase();
            if (socio.isEmpty()) return;
        }
        if (esIndicativoDelRadioclub(socio)) {
            JOptionPane.showMessageDialog(padre, "Ese es un indicativo del radioclub, no de un socio.", "Clave de invitación", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final String socioF = socio.trim().toUpperCase();
        boolean esCB = false;
        if (datosSocioConocido(socioF) == null) {   // no está en la lista de socios: ¿qué indicativo es?
            Object[] op = {"Amateur (HF)", "Banda Ciudadana (CB)"};
            int r = JOptionPane.showOptionDialog(padre, socioF + " no está todavía en la lista de socios.\n¿Es un indicativo amateur (HF) o de Banda Ciudadana (CB)?",
                    "Clave de invitación", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, op, op[0]);
            if (r < 0) return;
            esCB = r == 1;
        }
        String clave = generarClaveInvitacion(socioF, esCB);
        JTextArea t = new JTextArea(clave, 6, 50);
        t.setLineWrap(true);
        t.setEditable(false);
        JButton copiar = new JButton("Copiar");
        copiar.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(clave), null);
            copiar.setText("Copiada ✓");
        });
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(new JLabel("<html>Clave de invitación de <b>" + socioF + "</b> (solo vale con ese indicativo; para otro socio, saca otra).<br>"
                + "Pásasela (WhatsApp, correo…). La pega en Configurar Radioclub → <b>Unirme con una clave…</b><br>"
                + "Sirve para todos sus ordenadores, y se le rellenan solos sus datos y los del radioclub.<br><br>"
                + (syncPorFtp() ? "<font color='#ff9a8a'>Lleva dentro la contraseña del FTP: no la publiques.</font>"
                : "Además, comparte con este socio la carpeta de Google Drive (permiso de Editor).") + "</html>"), BorderLayout.NORTH);
        p.add(new JScrollPane(t), BorderLayout.CENTER);
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.LEFT));
        abajo.add(copiar);
        p.add(abajo, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(padre, p, "Clave de invitación de " + socioF, JOptionPane.PLAIN_MESSAGE);
    }

    /** Al unirse con una clave: se crea (o se completa) el operador del socio y se apunta como socio. */
    private String crearOperadorDesdeInvitacion(java.util.SortedMap<String, String> inv) {
        String[] d = {inv.getOrDefault("shf", ""), inv.getOrDefault("scb", ""), inv.getOrDefault("snom", ""), inv.getOrDefault("sqth", ""), inv.getOrDefault("sgrid", "")};
        String id = app.idOperador(d);
        if (id.isEmpty()) id = inv.getOrDefault("ind", "").trim().toUpperCase();
        if (id.isEmpty()) return null;
        List<String> lista = app.obtenerListaOperadores();
        String k = "OPERADOR_FICHA_" + id + "_";
        String[] campos = {"HF", "CB", "NOMBRE", "QTH", "GRID"};
        if (!lista.contains(id)) { lista.add(id); app.guardarListaOperadores(lista); }
        for (int i = 0; i < 5; i++) if (LibroGuardiaUI.ConfigApp.get(k + campos[i], "").isBlank() && !d[i].isBlank()) LibroGuardiaUI.ConfigApp.put(k + campos[i], d[i].trim().toUpperCase());
        LibroGuardiaUI.ConfigApp.put(k + "SOCIO", "1");
        app.anadirOperadorCBAlDirectorio(app.leerDatosOperador(id));
        return id;
    }

    /** Pide la clave al socio y la comprueba. Devuelve sus datos o null. */
    java.util.SortedMap<String, String> pedirClaveInvitacion(Component padre) { return pedirClaveInvitacion(padre, null); }

    java.util.SortedMap<String, String> pedirClaveInvitacion(Component padre, String indicativoYaEscrito) {
        JTextArea t = new JTextArea(4, 50);
        t.setLineWrap(true);
        while (true) {
            int r = JOptionPane.showConfirmDialog(padre, new Object[]{"Pega aquí la clave de invitación del radioclub:", new JScrollPane(t)},
                    "Unirme con una clave", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            String txt = t.getText().replaceAll("\\s+", "");
            try {
                if (!txt.startsWith(PREFIJO_CLAVE)) throw new Exception();
                java.util.SortedMap<String, String> m = decodificar(new String(java.util.Base64.getUrlDecoder().decode(txt.substring(PREFIJO_CLAVE.length())), StandardCharsets.UTF_8));
                String pub = m.getOrDefault("pub", "");
                java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(java.util.Base64.getDecoder().decode(pub)));
                if (!"FTP".equals(m.get("tipo")) && !"CARPETA".equals(m.get("tipo"))) throw new Exception();
                String ind = m.getOrDefault("ind", "");
                if (ind.isEmpty()) {
                    JOptionPane.showMessageDialog(padre, "Esa clave es de las antiguas (igual para todos).\n\n"
                            + "Ahora cada socio tiene su propia clave: pídele al radioclub la tuya.", "Unirme con una clave", JOptionPane.WARNING_MESSAGE);
                    return null;
                }
                Object tuInd = indicativoYaEscrito != null && !indicativoYaEscrito.isBlank() ? indicativoYaEscrito
                        : JOptionPane.showInputDialog(padre, "Esta clave es personal. Escribe tu indicativo (HF o CB):", "Unirme con una clave", JOptionPane.QUESTION_MESSAGE);
                if (tuInd == null) return null;
                String ti = tuInd.toString().trim().toUpperCase();
                if (ti.isEmpty() || !(ti.equals(ind) || ti.equalsIgnoreCase(m.getOrDefault("shf", "")) || ti.equalsIgnoreCase(m.getOrDefault("scb", "")))) {
                    JOptionPane.showMessageDialog(padre, "Esta clave no es para ese indicativo.\n\nCada socio tiene su propia clave: pídele al radioclub la tuya.",
                            "Unirme con una clave", JOptionPane.WARNING_MESSAGE);
                    return null;
                }
                return m;
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(padre, "Esa clave no es válida. Cópiala entera, desde \"" + PREFIJO_CLAVE + "\" hasta el final.",
                        "Unirme con una clave", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    /** Ordenadores que comparten con el radioclub (vistos en la carpeta), con la opción de expulsar o readmitir. */
    /**
     * Registro del radioclub: todo lo que se ha hecho en el radioclub, quién lo hizo, desde qué ordenador y cuándo.
     * Se saca de los ficheros firmados de la carpeta del radioclub, así que es el mismo en todos los ordenadores
     * y nadie puede borrarlo ni cambiarlo sin que se note: cada línea lleva la firma de su ordenador y aquí se comprueba.
     */
    private void abrirRegistroRadioclub(Component padre) {
        if (!syncActivo()) {
            JOptionPane.showMessageDialog(padre, "Primero activa \"Compartir con el radioclub\".", "Registro del radioclub", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String miPub;
        try { miPub = miPubB64(); } catch (Exception e) { return; }
        String rcPub = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "");
        // 1) Leer todas las líneas
        List<java.util.SortedMap<String, String>> lineas = new ArrayList<>();
        File[] fs = new File(carpetaBaseSync(LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", "")), CARPETA_SYNC).listFiles((x, n) -> n.startsWith("disp_") && n.endsWith(".txt"));
        if (fs != null) for (File f : fs) {
            try {
                for (String l : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (l.isBlank()) continue;
                    java.util.SortedMap<String, String> m = decodificar(l);
                    if (m.getOrDefault("t", "").isEmpty()) continue;
                    lineas.add(m);
                }
            } catch (Exception ignored) { }
        }
        lineas.sort((a, b) -> Long.compare(numeroLargo(a.get("f")), numeroLargo(b.get("f"))));
        // 2) Quién es cada ordenador (por su aviso de conexión) y nombres de los socios
        Map<String, String> personaDe = new HashMap<>(), equipoDe = new HashMap<>(), socioPorUuid = new HashMap<>();
        for (java.util.SortedMap<String, String> m : lineas) {
            java.util.SortedMap<String, String> d = decodificar(m.getOrDefault("d", ""));
            String dev = m.getOrDefault("dev", "");
            if ("HOLA".equals(m.get("t"))) {
                if (!d.getOrDefault("eq", "").isBlank()) equipoDe.put(dev, d.get("eq").trim().toUpperCase());
                String p = !d.getOrDefault("soc", "").isBlank() ? d.get("soc") : d.getOrDefault("op", "");
                if (!p.isBlank()) personaDe.put(dev, p.trim().toUpperCase());
            }
            if ("SOCIO".equals(m.get("t")) && !"1".equals(m.get("del"))) {
                String n = !d.getOrDefault("hf", "").isBlank() ? d.get("hf") : !d.getOrDefault("cb", "").isBlank() ? d.get("cb") : d.getOrDefault("nom", "");
                if (!n.isBlank()) socioPorUuid.put(m.getOrDefault("k", ""), n.trim().toUpperCase());
            }
        }
        if (System.getenv("COMPUTERNAME") != null && !System.getenv("COMPUTERNAME").isBlank()) equipoDe.putIfAbsent(miPub, System.getenv("COMPUTERNAME").trim().toUpperCase());
        if (app.operadorActual != null && !app.operadorActual.isBlank()) personaDe.putIfAbsent(miPub, app.operadorActual.trim().toUpperCase());
        Set<String> expulsados = new HashSet<>();
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT dev FROM rc_expulsados")) {
            while (rs.next()) expulsados.add(rs.getString(1));
        } catch (Exception ignored) { }
        // 3) Cada línea en palabras (de la más antigua a la más nueva, para saber si algo es nuevo o un cambio)
        Set<String> vistos = new HashSet<>();
        Set<String> rutinarios = Set.of("QSO", "CB", "HOLA", "OPER", "ACT");
        List<Object[]> filas = new ArrayList<>();   // fecha, quién, ordenador, qué, firma, tipo, f
        for (java.util.SortedMap<String, String> m : lineas) {
            String tipo = m.getOrDefault("t", ""), clave = m.getOrDefault("k", ""), dTxt = m.getOrDefault("d", ""), dev = m.getOrDefault("dev", "");
            boolean borrar = "1".equals(m.get("del"));
            long f = numeroLargo(m.get("f"));
            String msg = tipo + "\n" + clave + "\n" + f + "\n" + (borrar ? 1 : 0) + "\n" + dTxt + "\n" + m.getOrDefault("adm", "");
            boolean firmaBien = verificar(dev, msg, m.get("s"));
            boolean nuevo = vistos.add(tipo + "|" + clave);
            java.util.SortedMap<String, String> d = decodificar(dTxt);
            String que;
            switch (tipo) {
                case "SOCIO" -> que = borrar ? "Quitó al socio " + socioPorUuid.getOrDefault(clave, "")
                        : (nuevo ? "Añadió al socio " : "Cambió los datos del socio ") + String.join(" · ", textosNoVacios(
                        d.getOrDefault("num", "").equals("0") ? "" : "Nº " + d.getOrDefault("num", ""), d.getOrDefault("hf", ""), d.getOrDefault("cb", ""), d.getOrDefault("nom", "")));
                case "CUOTA" -> {
                    int b = clave.lastIndexOf('/');
                    String socio = b > 0 ? socioPorUuid.getOrDefault(clave.substring(0, b), "?") : "?", anio = b > 0 ? clave.substring(b + 1) : "";
                    que = borrar ? "Quitó la cuota " + anio + " de " + socio : "Marcó pagada la cuota " + anio + " de " + socio
                            + (d.getOrDefault("fecha", "").isBlank() ? "" : " (" + d.get("fecha") + ")");
                }
                case "CARGO" -> que = borrar ? "Quitó el cargo de " + d.getOrDefault("ind", "")
                        : "Puso a " + String.join(" ", textosNoVacios(d.getOrDefault("ind", ""), d.getOrDefault("nom", "").isBlank() ? "" : "(" + d.get("nom") + ")"))
                        + " como " + ("1".equals(d.getOrDefault("grado", "")) ? "Super Admin" : "0".equals(d.getOrDefault("grado", "")) ? "socio" : "Admin");
                case "VINC" -> que = borrar ? "Desactivó su cargo en un ordenador" : "Activó su cargo en este ordenador";
                case "EXPUL" -> que = (borrar ? "Volvió a admitir un ordenador de " : "Expulsó un ordenador de ") + personaDe.getOrDefault(clave, equipoDe.getOrDefault(clave, "?"))
                        + (d.getOrDefault("nota", "").isBlank() ? "" : " (" + d.get("nota") + ")");
                case "ROL" -> que = borrar ? "Quitó un papel" : "Cambió un papel" + (d.getOrDefault("rol", "").isBlank() ? "" : ": " + d.get("rol"));
                case "PERM" -> que = "Cambió lo que puede hacer cada papel (permisos)";
                case "CLAVETEMP" -> que = "Puso una contraseña temporal a " + clave;
                case "RCCLAVE" -> que = nuevo ? "Puso la contraseña del radioclub" : "Cambió la contraseña del radioclub";
                case "ADMIN" -> {
                    String pub = d.getOrDefault("pub", "");
                    String aQuien = personaDe.getOrDefault(pub, "") + (equipoDe.containsKey(pub) ? " (" + equipoDe.get(pub) + ")" : "");
                    que = nuevo ? "Se dio a conocer como PC Radioclub"
                            : !d.getOrDefault("prueba", "").isEmpty() ? "Se quedó con el PC Radioclub (con la contraseña del radioclub)"
                            : "Cedió el PC Radioclub a " + (aQuien.isBlank() ? "otro ordenador" : aQuien.trim());
                }
                case "RC" -> que = nuevo ? "Puso los datos del radioclub" : "Cambió los datos del radioclub";
                case "LOGO" -> que = borrar ? "Quitó el logo del radioclub" : nuevo ? "Puso el logo del radioclub" : "Cambió el logo del radioclub";
                case "ACT" -> que = (borrar ? "Borró la actividad " : nuevo ? "Creó la actividad " : "Cambió la actividad ") + d.getOrDefault("nombre", "");
                case "QSO" -> que = (borrar ? "Borró el contacto con " : nuevo ? "Anotó un contacto con " : "Cambió el contacto con ") + d.getOrDefault("ind", "")
                        + (d.getOrDefault("fecha", "").isBlank() ? "" : " (" + d.get("fecha") + " " + d.getOrDefault("hora", "") + ")");
                case "OPER" -> que = "Datos del operador " + clave;
                case "CB" -> que = (borrar ? "Quitó de BANDA CIUDADANA a " : "BANDA CIUDADANA: ") + clave;
                case "HOLA" -> que = "Se conectó";
                default -> que = tipo;
            }
            String quien;
            if (!rcPub.isEmpty() && rcPub.equals(dev)) quien = "PC Radioclub";
            else if (fueAdminEn(dev, f)) quien = "PC Radioclub (el anterior)";
            else quien = personaDe.getOrDefault(dev, "Desconocido");
            if (dev.equals(miPub)) quien += "  (este ordenador)";
            if (expulsados.contains(dev)) quien += "  [EXPULSADO]";
            filas.add(new Object[]{f, quien, equipoDe.getOrDefault(dev, ""), que, firmaBien ? "Correcta" : "NO VÁLIDA", tipo});
        }
        java.util.Collections.reverse(filas);   // lo más nuevo arriba

        // 4) Ventana
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(padre), "Registro del radioclub", Dialog.ModalityType.APPLICATION_MODAL);
        d.setLayout(new BorderLayout(0, 6));
        DefaultTableModel modelo = new DefaultTableModel(new Object[]{"Fecha y hora", "Quién", "Ordenador", "Qué hizo", "Firma"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable tabla = new JTable(modelo);
        tabla.setRowHeight(22);
        tabla.setAutoCreateRowSorter(true);
        tabla.getColumnModel().getColumn(0).setPreferredWidth(130);
        tabla.getColumnModel().getColumn(1).setPreferredWidth(170);
        tabla.getColumnModel().getColumn(2).setPreferredWidth(120);
        tabla.getColumnModel().getColumn(3).setPreferredWidth(520);
        tabla.getColumnModel().getColumn(4).setPreferredWidth(80);
        tabla.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foco, r, c);
                boolean mala = "NO VÁLIDA".equals(t.getValueAt(r, 4));
                if (!sel) comp.setForeground(mala ? new Color(255, 110, 90) : t.getForeground());
                return comp;
            }
        });
        JTextField txtBuscar = new JTextField(18);
        JCheckBox chkTodo = new JCheckBox("Ver también contactos, actividades y conexiones");
        chkTodo.setOpaque(false);
        JLabel lblCuenta = new JLabel();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        Runnable rellenar = () -> {
            modelo.setRowCount(0);
            String b = txtBuscar.getText().trim().toUpperCase();
            int malas = 0;
            for (Object[] x : filas) {
                if (!chkTodo.isSelected() && rutinarios.contains((String) x[5])) continue;
                String fecha = fmt.format(new java.util.Date((Long) x[0]));
                if (!b.isEmpty() && !(fecha + " " + x[1] + " " + x[2] + " " + x[3]).toUpperCase().contains(b)) continue;
                if ("NO VÁLIDA".equals(x[4])) malas++;
                modelo.addRow(new Object[]{fecha, x[1], x[2], x[3], x[4]});
            }
            lblCuenta.setText(modelo.getRowCount() + " anotaciones" + (malas > 0 ? "  ·  " + malas + " con la firma NO VÁLIDA (no se han aplicado)" : ""));
            lblCuenta.setForeground(malas > 0 ? new Color(255, 110, 90) : app.TEMA_AMBAR);
        };
        txtBuscar.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { rellenar.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { rellenar.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rellenar.run(); }
        });
        chkTodo.addActionListener(e -> rellenar.run());
        rellenar.run();
        JPanel barra = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        barra.setBackground(app.TEMA_FONDO);
        barra.add(new JLabel("Buscar:"));
        barra.add(txtBuscar);
        barra.add(chkTodo);
        barra.add(lblCuenta);
        JLabel nota = new JLabel("<html>Todo lo que se ha hecho en el radioclub, sacado de los ficheros firmados de cada ordenador. "
                + "Es igual en todos los ordenadores y no se puede borrar ni cambiar: si alguien tocase un fichero, su firma saldría como NO VÁLIDA.</html>");
        nota.setFont(nota.getFont().deriveFont(11f));
        nota.setForeground(new Color(150, 170, 200));
        nota.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setBackground(app.TEMA_FONDO);
        arriba.add(barra, BorderLayout.NORTH);
        arriba.add(nota, BorderLayout.SOUTH);
        JButton bCerrar = app.cat.botonCtrl("Cerrar");
        bCerrar.addActionListener(e -> d.dispose());
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        abajo.setBackground(app.TEMA_FONDO);
        abajo.add(bCerrar);
        d.add(arriba, BorderLayout.NORTH);
        d.add(new JScrollPane(tabla), BorderLayout.CENTER);
        d.add(abajo, BorderLayout.SOUTH);
        d.getContentPane().setBackground(app.TEMA_FONDO);
        Rectangle pantalla = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        d.setSize(Math.min(1100, pantalla.width - 20), Math.min(650, pantalla.height - 20));
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
    }

    private static long numeroLargo(String s) {
        try { return Long.parseLong(s == null ? "0" : s.trim()); } catch (Exception e) { return 0; }
    }

    static List<String> textosNoVacios(String... t) {
        List<String> r = new ArrayList<>();
        for (String x : t) if (x != null && !x.isBlank()) r.add(x.trim());
        return r;
    }

    private void abrirOrdenadoresRadioclub(Component padre) {
        if (!syncActivo()) {
            JOptionPane.showMessageDialog(padre, "Primero activa \"Compartir con el radioclub\".", "Socios del radioclub", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String miPub;
        try { miPub = miPubB64(); } catch (Exception e) { return; }
        String rcPub = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "");
        // Ordenadores que hay en la carpeta del radioclub: dev -> operadores, último cambio y el operador que lo usa
        Map<String, java.util.TreeSet<String>> ops = new LinkedHashMap<>();
        Map<String, Long> ultimo = new HashMap<>();
        Map<String, String> operadorDe = new HashMap<>();
        Map<String, String> equipoDe = new HashMap<>();   // nombre del ordenador (Windows): así un ordenador reinstalado no cuenta como otro
        File[] fs = new File(carpetaBaseSync(LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", "")), CARPETA_SYNC).listFiles((x, n) -> n.startsWith("disp_") && n.endsWith(".txt"));
        if (fs != null) for (File f : fs) {
            try {
                for (String l : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (l.isBlank()) continue;
                    java.util.SortedMap<String, String> m = decodificar(l);
                    String dev = m.getOrDefault("dev", "");
                    if (dev.isEmpty()) continue;
                    ops.computeIfAbsent(dev, k -> new java.util.TreeSet<>());
                    try { ultimo.merge(dev, Long.parseLong(m.getOrDefault("f", "0")), Math::max); } catch (Exception ignored) { }
                    java.util.SortedMap<String, String> d = decodificar(m.getOrDefault("d", ""));
                    for (String k : new String[]{"op", "id"}) { String v = d.getOrDefault(k, ""); if (!v.isBlank()) ops.get(dev).add(v.trim().toUpperCase()); }
                    if ("HOLA".equals(m.get("t")) && !d.getOrDefault("eq", "").isBlank()) equipoDe.put(dev, d.get("eq").trim().toUpperCase());
                    if ("HOLA".equals(m.get("t")) && !d.getOrDefault("soc", "").isBlank()) operadorDe.put(dev, d.get("soc").trim().toUpperCase());
                    else if ("HOLA".equals(m.get("t")) && !d.getOrDefault("op", "").isBlank()) operadorDe.put(dev, d.get("op").trim().toUpperCase());
                }
            } catch (Exception ignored) { }
        }
        // Este ordenador también cuenta (con su operador actual)
        ops.computeIfAbsent(miPub, k -> new java.util.TreeSet<>());
        if (System.getenv("COMPUTERNAME") != null && !System.getenv("COMPUTERNAME").isBlank()) equipoDe.put(miPub, System.getenv("COMPUTERNAME").trim().toUpperCase());
        if (!LibroGuardiaUI.ConfigApp.get("RC_SOCIO_INVITADO", "").isEmpty()) operadorDe.put(miPub, LibroGuardiaUI.ConfigApp.get("RC_SOCIO_INVITADO", ""));
        else if (app.operadorActual != null && !app.operadorActual.isBlank()) operadorDe.put(miPub, app.operadorActual.trim().toUpperCase());
        Set<String> expulsados = new HashSet<>();
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT dev FROM rc_expulsados")) {
            while (rs.next()) expulsados.add(rs.getString(1));
        } catch (Exception ignored) { }
        // Socio -> sus ordenadores (un socio es una sola fila aunque tenga el programa en varios ordenadores)
        Map<String, List<String>> ordenadoresDe = new java.util.TreeMap<>();
        Runnable agrupar = () -> {
            ordenadoresDe.clear();
            for (Map.Entry<String, java.util.TreeSet<String>> e : ops.entrySet()) {
                String dev = e.getKey();
                if (dev.equals(rcPub)) continue;   // el PC Radioclub va arriba, aparte
                String socio = operadorDe.getOrDefault(dev, e.getValue().isEmpty() ? "" : e.getValue().first());
                if (socio.isEmpty() || esIndicativoDelRadioclub(socio)) continue;
                ordenadoresDe.computeIfAbsent(socio, k -> new ArrayList<>()).add(dev);
            }
        };
        agrupar.run();
        DefaultTableModel modelo = new DefaultTableModel(new Object[]{"Socio", "Ordenadores", "Último cambio", "Papel"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm");
        Runnable rellenar = () -> {
            modelo.setRowCount(0);
            Map<String, String[]> cargos = cargosPorSocio();
            for (Map.Entry<String, List<String>> e : ordenadoresDe.entrySet()) {
                String socio = e.getKey();
                long u = 0;
                boolean todosExpulsados = true;
                for (String dev : e.getValue()) { u = Math.max(u, ultimo.getOrDefault(dev, 0L)); if (!expulsados.contains(dev)) todosExpulsados = false; }
                String[] c = cargos.get(socio);
                String papel = todosExpulsados ? "EXPULSADO" : c == null ? "Socio" : ("1".equals(c[1]) ? "Super Admin" : "Admin")
                        + (cargoActivadoEn(c[0]) == 0 ? "  (sin activar)" : "");
                Set<String> equipos = new HashSet<>();   // el mismo ordenador instalado otra vez cuenta una sola vez
                for (String dev : e.getValue()) equipos.add(equipoDe.getOrDefault(dev, dev));
                modelo.addRow(new Object[]{socio + (socio.equalsIgnoreCase(app.operadorActual) ? "  (tú)" : ""), equipos.size(), u == 0 ? "" : fmt.format(new java.util.Date(u)), papel});
            }
        };
        rellenar.run();
        JTable tabla = new JTable(modelo);
        tabla.setRowHeight(22);
        java.util.function.Supplier<String> socioElegido = () -> {
            int f = tabla.getSelectedRow();
            if (f < 0) {
                JOptionPane.showMessageDialog(tabla, "Elige primero un socio de la lista.", "Socios del radioclub", JOptionPane.INFORMATION_MESSAGE);
                return null;
            }
            return String.valueOf(modelo.getValueAt(tabla.convertRowIndexToModel(f), 0)).replace("  (tú)", "").trim();
        };

        // ---- Papeles: Super Admin (lo ponen el PC Radioclub y los Super Admin), Admin y Socio ----
        java.util.function.BiConsumer<String, Integer> ponerPapel = (socio, grado) -> {   // grado 1 = Super Admin, 2 = Admin, 0 = socio
            Map<String, String[]> cargos = cargosPorSocio();
            String[] actual = cargos.get(socio);
            boolean tocaSuper = grado == 1 || (actual != null && "1".equals(actual[1]));
            if (tocaSuper ? !tienePoderRadioclub() : !tengoPermiso("ADMINS")) {
                JOptionPane.showMessageDialog(tabla, tocaSuper ? "Los Super Admin solo los nombran y los quitan el PC Radioclub y otro Super Admin."
                        : "Tu papel en el radioclub no tiene permiso para nombrar o quitar Admins.", "Papeles", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String nombrePapel = grado == 1 ? "Super Admin" : grado == 2 ? "Admin" : "socio";
            if (JOptionPane.showConfirmDialog(tabla, "¿Poner a " + socio + " como " + nombrePapel + "?\n\n"
                            + (grado == 0 ? "Volverá a ser un socio normal (lo que hizo con su cargo sigue valiendo)."
                            : "Lo que puede hacer un " + nombrePapel + " lo decide el PC Radioclub en \"Gestionar permisos\".\n"
                            + "El papel vale en todos sus ordenadores: en cada uno lo activará una vez con la contraseña del cargo."),
                    "Papeles", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return;
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
                if (grado == 0) {
                    if (actual == null) return;
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rc_cargos WHERE uuid = ?")) { ps.setString(1, actual[0]); ps.executeUpdate(); }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE rc_admins SET hasta = ? WHERE nota = ? AND hasta = 0")) {
                        ps.setLong(1, System.currentTimeMillis()); ps.setString(2, "CARGO:" + actual[0]); ps.executeUpdate();
                    }
                } else if (actual != null) {   // ya tenía cargo: solo cambia el papel (la contraseña del cargo sigue igual)
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE rc_cargos SET grado = ? WHERE uuid = ?")) { ps.setInt(1, grado); ps.setString(2, actual[0]); ps.executeUpdate(); }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE rc_admins SET rol = ? WHERE nota = ? AND hasta = 0")) {
                        ps.setString(1, grado == 1 ? "SUPER" : "ADMIN"); ps.setString(2, "CARGO:" + actual[0]); ps.executeUpdate();
                    }
                } else {
                    char[] clave = pedirClaveNueva(tabla, "Contraseña del cargo de " + socio,
                            "<html>Pon una contraseña para el cargo de <b>" + socio + "</b>.<br>"
                                    + "Dásela a " + socio + ": la escribirá una sola vez en cada ordenador suyo para activar el cargo.</html>");
                    if (clave == null) return;
                    Object[] llaves = crearLlavesCargo(clave);
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO rc_cargos (indicativo, nombre, grado, sal, clave, uuid, pub, priv, sal2) VALUES (?, ?, ?, '', '', ?, ?, ?, ?)")) {
                        ps.setString(1, socio); ps.setString(2, ""); ps.setInt(3, grado);
                        ps.setString(4, java.util.UUID.randomUUID().toString().replace("-", ""));
                        ps.setString(5, (String) llaves[0]); ps.setString(6, (String) llaves[1]); ps.setString(7, (String) llaves[2]);
                        ps.executeUpdate();
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(tabla, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            registrarCambio("Puso a " + socio + " como " + nombrePapel, "", "");
            rellenar.run();
            sincronizarPronto();
            if (grado != 0 && actual == null)
                JOptionPane.showMessageDialog(tabla, socio + " ya es " + nombrePapel + ".\n\nDale la contraseña del cargo. En cada uno de sus ordenadores, al entrar con su operador,\n"
                        + "el programa se la pedirá una vez (o desde \"Socios del radioclub…\" → \"Activar mi cargo…\").", "Papeles", JOptionPane.INFORMATION_MESSAGE);
        };
        JButton bSuper = new JButton("Hacer Super Admin"), bAdmin = new JButton("Hacer Admin"), bSocio = new JButton("Dejar en socio");
        bSuper.addActionListener(e -> { String s = socioElegido.get(); if (s != null) ponerPapel.accept(s, 1); });
        bAdmin.addActionListener(e -> { String s = socioElegido.get(); if (s != null) ponerPapel.accept(s, 2); });
        bSocio.addActionListener(e -> { String s = socioElegido.get(); if (s != null) ponerPapel.accept(s, 0); });

        // ---- Expulsar / readmitir: todos los ordenadores del socio ----
        java.util.function.Consumer<Boolean> cambiar = expulsar -> {
            String socio = socioElegido.get();
            if (socio == null) return;
            if (!tengoPermiso("EXPULSAR")) {
                JOptionPane.showMessageDialog(tabla, "Tu papel en el radioclub no tiene permiso para expulsar o readmitir socios.", "Expulsar", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            List<String> devs = ordenadoresDe.getOrDefault(socio, List.of());
            if (expulsar && devs.contains(miPub)) {
                JOptionPane.showMessageDialog(tabla, "No te puedes expulsar a ti mismo.", "Expulsar", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (expulsar && JOptionPane.showConfirmDialog(tabla, "¿Expulsar a " + socio + " (" + devs.size() + (devs.size() == 1 ? " ordenador" : " ordenadores") + ")?\n\n"
                            + "Desde ahora nadie aceptará sus cambios. Lo que ya hizo se queda.\n"
                            + "Si lo hacéis por seguridad, cambiad también la contraseña del FTP y pasad una clave nueva.",
                    "Expulsar", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
                 PreparedStatement ps = conn.prepareStatement(expulsar ? "INSERT OR REPLACE INTO rc_expulsados (dev, nota) VALUES (?, ?)" : "DELETE FROM rc_expulsados WHERE dev = ?")) {
                for (String dev : devs) {
                    ps.setString(1, dev);
                    if (expulsar) ps.setString(2, socio);
                    ps.executeUpdate();
                    if (expulsar) expulsados.add(dev); else expulsados.remove(dev);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(tabla, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            registrarCambio((expulsar ? "Expulsó a " : "Readmitió a ") + socio, "", "");
            rellenar.run();
            sincronizarPronto();
        };
        JButton bExpulsar = new JButton("Expulsar"), bReadmitir = new JButton("Readmitir");
        bExpulsar.addActionListener(e -> cambiar.accept(true));
        bReadmitir.addActionListener(e -> cambiar.accept(false));

        // ---- Contraseña temporal para un operador que ha olvidado la suya ----
        JButton bTemp = new JButton("Contraseña temporal…");
        bTemp.addActionListener(e -> {
            if (!tengoPermiso("CLAVETEMP")) {
                JOptionPane.showMessageDialog(tabla, "Tu papel en el radioclub no tiene permiso para poner contraseñas temporales.", "Contraseña temporal", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int f = tabla.getSelectedRow();
            String sugerido = f < 0 ? "" : String.valueOf(modelo.getValueAt(tabla.convertRowIndexToModel(f), 0)).replace("  (tú)", "").trim();
            Object op = JOptionPane.showInputDialog(tabla, "Indicativo del operador que ha olvidado su contraseña:", "Contraseña temporal",
                    JOptionPane.QUESTION_MESSAGE, null, null, sugerido);
            if (op == null || op.toString().isBlank()) return;
            String id = op.toString().trim().toUpperCase();
            String temporal = claveTemporalAleatoria();
            String sal = nuevaSal();
            long ahora = System.currentTimeMillis();
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar();
                 PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO rc_claves_temp (op, sal, hash, f) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, id); ps.setString(2, sal); ps.setString(3, cifrarClave(temporal.toCharArray(), sal)); ps.setLong(4, ahora);
                ps.executeUpdate();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(tabla, "Error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            ponerClaveTemporalLocal(id, sal, cifrarClave(temporal.toCharArray(), sal), ahora);   // por si el operador también está en este ordenador
            registrarCambio("Puso una contraseña temporal a " + id, "", "");
            sincronizarPronto();
            JTextField t = new JTextField(temporal, 12);
            t.setEditable(false);
            t.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
            JOptionPane.showMessageDialog(tabla, new Object[]{"Contraseña temporal de " + id + ":", t,
                            "<html>Dásela a " + id + ". Le llegará a su ordenador al sincronizar.<br>"
                                    + "Al entrar con ella tendrá que poner una contraseña nueva. Solo vale una vez.</html>"},
                    "Contraseña temporal", JOptionPane.INFORMATION_MESSAGE);
        });

        // ---- Ceder el PC Radioclub a otro ordenador (solo si el actual se va a dejar de usar) ----
        JButton bCeder = new JButton("Ceder el PC Radioclub…");
        bCeder.addActionListener(e -> {
            String socio = socioElegido.get();
            if (socio == null) return;
            if (LibroGuardiaUI.ConfigApp.get("RADIOCLUB_CLAVE_VER", "").isEmpty()) {
                JOptionPane.showMessageDialog(tabla, "Antes de ceder el PC Radioclub hay que poner la contraseña del radioclub\n(en Configurar Radioclub).",
                        "Ceder el PC Radioclub", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String dev = null;
            long mejor = -1;
            for (String dv : ordenadoresDe.getOrDefault(socio, List.of())) {
                if (expulsados.contains(dv) || dv.equals(miPub)) continue;
                long u = ultimo.getOrDefault(dv, 0L);
                if (u > mejor) { mejor = u; dev = dv; }
            }
            if (dev == null) {
                JOptionPane.showMessageDialog(tabla, socio + " no tiene ningún otro ordenador que pueda ser el PC Radioclub.", "Ceder el PC Radioclub", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (JOptionPane.showConfirmDialog(tabla, "¿Ceder el PC Radioclub al ordenador de " + socio + " (el último que ha usado)?\n\n"
                            + "• Ese ordenador pasará a ser el PC Radioclub (el maestro): lo podrá todo.\n"
                            + "• ESTE ordenador pasará a ser uno más.\n"
                            + "• Hazlo SOLO si este ordenador se va a dejar de usar. Cambiar el PC Radioclub a menudo\n"
                            + "  hace que los ordenadores de los socios no se pongan de acuerdo.\n\n"
                            + "Esto no se puede deshacer desde aquí.",
                    "Ceder el PC Radioclub", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
            if (pedirClaveRadioclub(tabla, "ceder el PC Radioclub") == null) return;
            String error = cederRadioclub(dev);
            if (error != null) {
                JOptionPane.showMessageDialog(tabla, "No se ha podido ceder: " + error, "Ceder el PC Radioclub", JOptionPane.ERROR_MESSAGE);
                return;
            }
            registrarCambio("Cedió el PC Radioclub al ordenador de " + socio, "", "");
            JOptionPane.showMessageDialog(tabla, "Hecho. Al sincronizar, ese ordenador será el PC Radioclub.\n\n"
                    + "Cierra ahora la ventana Configurar Radioclub con \"Cancelar\".", "Ceder el PC Radioclub", JOptionPane.INFORMATION_MESSAGE);
            Window w = SwingUtilities.getWindowAncestor(tabla);
            if (w != null) w.dispose();
        });
        // ---- Clave de invitación (es la misma para todos los socios): por si un socio la pierde y quiere otro ordenador ----
        JButton bClave = new JButton("Clave de invitación…");
        bClave.addActionListener(e -> {
            int f = tabla.getSelectedRow();
            mostrarClaveInvitacion(tabla, f < 0 ? null : String.valueOf(modelo.getValueAt(tabla.convertRowIndexToModel(f), 0)).replace("  (tú)", "").trim());
        });
        if (tengoPermiso("INVITAR")) {
            JPopupMenu menuSocio = new JPopupMenu();
            JMenuItem itClave = new JMenuItem("Ver la clave de invitación para este socio…");
            itClave.addActionListener(e -> { String s = socioElegido.get(); if (s != null) mostrarClaveInvitacion(tabla, s); });
            menuSocio.add(itClave);
            tabla.addMouseListener(new java.awt.event.MouseAdapter() {
                private void menu(java.awt.event.MouseEvent ev) {
                    if (!ev.isPopupTrigger()) return;
                    int f = tabla.rowAtPoint(ev.getPoint());
                    if (f < 0) return;
                    tabla.setRowSelectionInterval(f, f);
                    menuSocio.show(tabla, ev.getX(), ev.getY());
                }
                @Override public void mousePressed(java.awt.event.MouseEvent ev) { menu(ev); }
                @Override public void mouseReleased(java.awt.event.MouseEvent ev) { menu(ev); }
            });
        }
        JButton bPermisos = new JButton("Gestionar permisos…");
        bPermisos.addActionListener(e -> abrirGestionPermisos(tabla));
        JButton bActivar = new JButton("Activar mi cargo…");
        bActivar.addActionListener(e -> { activarMiCargo(tabla, true); rellenar.run(); });

        JPanel p = new JPanel(new BorderLayout(6, 6));
        boolean soyRC = esOrdenadorRadioclub();
        JPanel cajaRC = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        cajaRC.setBorder(BorderFactory.createTitledBorder("PC Radioclub (el maestro)" + (soyRC ? ": este ordenador" : "")));
        JTextField tHf = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_HF", ""), 10), tCb = new JTextField(LibroGuardiaUI.ConfigApp.get("RADIOCLUB_IND_CB", ""), 10);
        tHf.setEditable(false);
        tCb.setEditable(false);
        cajaRC.add(new JLabel("Indicativo amateur:"));
        cajaRC.add(tHf);
        cajaRC.add(Box.createHorizontalStrut(12));
        cajaRC.add(new JLabel("Indicativo CB:"));
        cajaRC.add(tCb);
        String miPapel = soyRC ? "PC Radioclub" : switch (miRol()) { case "SUPER" -> "Super Admin"; case "ADMIN" -> "Admin"; default -> "Socio"; };
        JLabel explica = new JLabel("<html>Cada socio sale una sola vez, aunque tenga el programa en varios ordenadores. Tu papel aquí: <b>" + miPapel + "</b>.<br>"
                + "Lo que puede hacer cada papel (Super Admin, Admin y Socio) lo decide el PC Radioclub en <b>Gestionar permisos</b>.<br>"
                + "Si alguien deja el radioclub o hace cambios que no debe, expúlsalo.</html>");
        JPanel arribaOrd = new JPanel(new BorderLayout(0, 8));
        arribaOrd.add(cajaRC, BorderLayout.NORTH);
        arribaOrd.add(explica, BorderLayout.CENTER);
        p.add(arribaOrd, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(tabla);
        sp.setPreferredSize(new Dimension(640, 240));
        p.add(sp, BorderLayout.CENTER);
        JPanel bot = new JPanel(new GridLayout(0, 1, 0, 2));
        JPanel bot1 = new JPanel(new FlowLayout(FlowLayout.LEFT)), bot2 = new JPanel(new FlowLayout(FlowLayout.LEFT)), bot3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        if (soyRC || "SUPER".equals(miRol())) bot1.add(bSuper);   // Super Admin: los nombran el PC Radioclub y los Super Admin
        if (tengoPermiso("ADMINS")) { bot1.add(bAdmin); bot1.add(bSocio); }
        if (tengoPermiso("INVITAR")) bot2.add(bClave);
        if (tengoPermiso("CLAVETEMP")) bot2.add(bTemp);
        if (tengoPermiso("EXPULSAR")) { bot2.add(bExpulsar); bot2.add(bReadmitir); }
        if (soyRC || "SUPER".equals(miRol())) bot3.add(bPermisos);
        if (soyRC) bot3.add(bCeder);
        if (!soyRC && miCargo() != null) bot3.add(bActivar);
        for (JPanel b : new JPanel[]{bot1, bot2, bot3}) if (b.getComponentCount() > 0) bot.add(b);
        p.add(bot, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(padre, p, "Socios del radioclub", JOptionPane.PLAIN_MESSAGE);
    }

    /** Ordenadores que fueron el del radioclub y hasta cuándo ("llave@fecha;..."): sus cambios de entonces siguen valiendo. */
    private static void anotarAdminAnterior(String pub, long hasta) {
        String lista = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_ANTERIORES", "");
        for (String e : lista.split(";")) if (e.startsWith(pub + "@")) return;
        LibroGuardiaUI.ConfigApp.put("RC_ADMIN_ANTERIORES", (lista.isEmpty() ? "" : lista + ";") + pub + "@" + hasta);
    }

    private static boolean fueAdminEn(String pub, long f) {
        for (String e : LibroGuardiaUI.ConfigApp.get("RC_ADMIN_ANTERIORES", "").split(";")) {
            int k = e.lastIndexOf('@');
            if (k > 0 && e.substring(0, k).equals(pub)) {
                try { return f <= Long.parseLong(e.substring(k + 1)); } catch (Exception ignored) { }
            }
        }
        return false;
    }

    /** Se ha cedido el radioclub en esta sesión: la casilla "Este es el ordenador del radioclub" ya no vale. */
    private volatile boolean radioclubCedido = false;

    /**
     * Cede el papel de ordenador del radioclub a otro ordenador: se deja en la carpeta compartida una línea firmada
     * por este (el actual) diciendo cuál es el nuevo. Devuelve null si va bien, o el motivo.
     */
    private synchronized String cederRadioclub(String nuevoPub) { return cederRadioclub(nuevoPub, null); }

    private synchronized String cederRadioclub(String nuevoPub, String prueba) {
        try {
            java.security.KeyPair kp = llaveDispositivo();
            String miPub = java.util.Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
            boolean tomarlo = miPub.equals(nuevoPub);   // un administrador se queda con el papel (el del radioclub se ha estropeado)
            if (tomarlo ? !soySuperRC() : !esOrdenadorRadioclub()) return "este ordenador no puede hacerlo";
            if (tomarlo && esOrdenadorRadioclub()) return "este ya es el ordenador del radioclub";
            String adminAnterior = LibroGuardiaUI.ConfigApp.get("RC_ADMIN_PUB", "");
            String problema = prepararCarpetaSync(LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", ""));
            if (problema != null) return problema;
            java.util.SortedMap<String, String> dm = new java.util.TreeMap<>();
            dm.put("pub", nuevoPub);
            if (tomarlo) {
                if (prueba == null) return "falta la contraseña del radioclub";
                dm.put("prueba", prueba);
            }
            String d = codificar(dm);
            long f = System.currentTimeMillis();
            String msg = "ADMIN\nADMIN\n" + f + "\n0\n" + d + "\nRADIOCLUB";
            java.util.SortedMap<String, String> linea = new java.util.TreeMap<>();
            linea.put("v", "1"); linea.put("t", "ADMIN"); linea.put("k", "ADMIN"); linea.put("f", String.valueOf(f));
            linea.put("del", "0"); linea.put("d", d); linea.put("dev", miPub); linea.put("adm", "RADIOCLUB");
            linea.put("s", firmar(kp.getPrivate(), msg));
            File fichero = new File(new File(carpetaBaseSync(LibroGuardiaUI.ConfigApp.get("RC_SYNC_CARPETA", "")), CARPETA_SYNC), miFicheroSync());
            java.nio.file.Files.write(fichero.toPath(), (codificar(linea) + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
                guardarVersion(conn, "ADMIN", "ADMIN", f, miPub);
                guardarPublicado(conn, "ADMIN", "ADMIN", d);
            }
            anotarAdminAnterior(adminAnterior, f);
            LibroGuardiaUI.ConfigApp.put("RC_ADMIN_PUB", nuevoPub);
            radioclubCedido = !tomarlo;
            sincronizarPronto();   // por FTP, así se sube ya
            return null;
        } catch (Exception ex) {
            return ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
    }

    /** Ventana con los datos del FTP del radioclub y un botón para probar la conexión. Devuelve null si se cancela. */
    private String[] pedirDatosFtp(Component padre, String[] actual) {
        return pedirDatosFtp(padre, actual, CARPETA_SYNC, "Datos del FTP del radioclub",
                "<html>Todos los del radioclub ponen los mismos datos. En la carpeta del servidor se crea<br>"
                        + "\"" + CARPETA_SYNC + "\". Ejemplo de servidor: ftp.miradioclub.es (sin \"ftp://\").</html>");
    }

    private String[] pedirDatosFtp(Component padre, String[] actual, String subcarpeta, String titulo, String textoNota) {
        JTextField txtServidor = new JTextField(actual[0], 22);
        JTextField txtPuerto = new JTextField(actual[1].isBlank() ? "21" : actual[1], 5);
        ((AbstractDocument) txtPuerto.getDocument()).setDocumentFilter(new DocumentFilter() {   // solo números
            @Override public void insertString(FilterBypass fb, int o, String t, AttributeSet a) throws BadLocationException { if (t != null && t.matches("\\d*")) super.insertString(fb, o, t, a); }
            @Override public void replace(FilterBypass fb, int o, int l, String t, AttributeSet a) throws BadLocationException { if (t == null || t.matches("\\d*")) super.replace(fb, o, l, t, a); }
        });
        JTextField txtUsuario = new JTextField(actual[2], 16);
        JPasswordField txtClave = new JPasswordField(actual[3], 16);
        JTextField txtCarpetaFtp = new JTextField(actual[4], 22);
        JCheckBox chkSeguro = new JCheckBox("Conexión segura (FTPS) — recomendable si el servidor la admite", "1".equals(actual[5]));
        JLabel lblPrueba = new JLabel(" ");
        JButton btnProbar = new JButton("Probar conexión");

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        Object[][] filas = {{"Servidor:", txtServidor}, {"Puerto:", txtPuerto}, {"Usuario:", txtUsuario}, {"Contraseña:", txtClave}, {"Carpeta en el servidor:", txtCarpetaFtp}};
        int y = 0;
        for (Object[] f : filas) {
            g.gridy = y++; g.gridx = 0; g.weightx = 0;
            p.add(new JLabel((String) f[0]), g);
            g.gridx = 1; g.weightx = 1;
            p.add((Component) f[1], g);
        }
        g.gridy = y++; g.gridx = 0; g.gridwidth = 2;
        p.add(chkSeguro, g);
        JLabel nota = new JLabel(textoNota);
        nota.setFont(nota.getFont().deriveFont(11f));
        g.gridy = y++;
        p.add(nota, g);
        JPanel filaProbar = new JPanel(new BorderLayout(8, 0));
        filaProbar.add(btnProbar, BorderLayout.WEST);
        filaProbar.add(lblPrueba, BorderLayout.CENTER);
        g.gridy = y;
        p.add(filaProbar, g);

        java.util.function.Supplier<String[]> leer = () -> new String[]{
                txtServidor.getText().trim().replaceFirst("(?i)^ftps?://", "").replaceAll("/+$", ""),
                txtPuerto.getText().trim().isEmpty() ? "21" : txtPuerto.getText().trim(),
                txtUsuario.getText().trim(), new String(txtClave.getPassword()), txtCarpetaFtp.getText().trim(), chkSeguro.isSelected() ? "1" : "0"};
        btnProbar.addActionListener(ev -> {
            String[] d = leer.get();
            if (d[0].isEmpty()) { lblPrueba.setForeground(new Color(255, 110, 90)); lblPrueba.setText("Falta el servidor."); return; }
            btnProbar.setEnabled(false);
            lblPrueba.setForeground(app.TEMA_TEXTO);
            lblPrueba.setText("Conectando…");
            new Thread(() -> {
                String res;
                try (LibroGuardiaUI.FtpSimple f = new LibroGuardiaUI.FtpSimple(d[0], Integer.parseInt(d[1]), "1".equals(d[5]))) {
                    f.entrar(d[2], d[3]);
                    f.irACarpeta(d[4]);
                    f.irACarpeta(subcarpeta);
                    f.archivos();
                    res = null;
                } catch (Exception ex) {
                    res = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
                String fres = res;
                SwingUtilities.invokeLater(() -> {
                    btnProbar.setEnabled(true);
                    lblPrueba.setForeground(fres == null ? app.cat.VERDE_ENCENDIDO : new Color(255, 110, 90));
                    lblPrueba.setText(fres == null ? "Conexión correcta." : "<html><div style='width:280px'>" + fres + "</div></html>");
                });
            }, "ProbarFTP").start();
        });
        while (true) {
            int r = JOptionPane.showConfirmDialog(padre, p, titulo, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            String[] d = leer.get();
            if (d[0].isEmpty() || d[2].isEmpty()) {
                JOptionPane.showMessageDialog(padre, "Escribe como mínimo el servidor y el usuario.", "Datos del FTP", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            return d;
        }
    }

    /** Archivo donde se guarda la copia del logo del radioclub (Documentos\\Libro de Guardia). */
    static File ficheroLogoRadioclub() {
        return new File(new File(LibroGuardiaUI.DatabaseManager.obtenerRutaActiva()).getParentFile(), "logo_radioclub.png");
    }

    private static final int LOGO_MAX_PIXELES = 300;
    private static final String TEXTO_REQUISITOS_LOGO =
            "Requisitos del logo:\n" +
            "• Formato PNG o JPG.\n" +
            "• Como máximo 300 × 300 píxeles.\n" +
            "• Con 72 ppp es suficiente (los ppp solo importan al imprimir).";

    /** Comprueba que el logo es PNG o JPG y no pasa de 300 x 300 píxeles. Devuelve el motivo del rechazo o null si vale. */
    private static String comprobarLogoRadioclub(File f) {
        if (f == null || !f.isFile()) return "• No se encuentra el archivo.";
        String nombreMin = f.getName().toLowerCase();
        if (!nombreMin.endsWith(".png") && !nombreMin.endsWith(".jpg") && !nombreMin.endsWith(".jpeg")) {
            return "• El archivo no es PNG ni JPG.";
        }
        try (javax.imageio.stream.ImageInputStream iis = ImageIO.createImageInputStream(f)) {
            java.util.Iterator<javax.imageio.ImageReader> lectores = ImageIO.getImageReaders(iis);
            if (iis == null || !lectores.hasNext()) return "• El archivo no es una imagen válida.";
            javax.imageio.ImageReader lector = lectores.next();
            try {
                String formato = lector.getFormatName();
                if (!"png".equalsIgnoreCase(formato) && !"jpeg".equalsIgnoreCase(formato) && !"jpg".equalsIgnoreCase(formato)) {
                    return "• El archivo no es PNG ni JPG de verdad (solo tiene el nombre).";
                }
                lector.setInput(iis);
                int an = lector.getWidth(0), al = lector.getHeight(0);
                if (an > LOGO_MAX_PIXELES || al > LOGO_MAX_PIXELES) {
                    return "• La imagen mide " + an + " × " + al + " píxeles y el máximo es "
                            + LOGO_MAX_PIXELES + " × " + LOGO_MAX_PIXELES + ".";
                }
            } finally {
                lector.dispose();
            }
        } catch (Exception e) {
            return "• No se pudo leer la imagen (" + e.getMessage() + ").";
        }
        return null;
    }

    /** Muestra el logo del radioclub en la cabecera (si hay radioclub y logo). El hueco se mantiene siempre. */
    void cargarLogoRadioclub() {
        if (app.lblLogoRC == null) return;
        app.lblLogoRC.setIcon(null);
        File f = ficheroLogoRadioclub();
        if (!radioclubConfigurado() || !f.isFile()) return;
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return;
            double escala = Math.min((double) app.TAM_LOGO / img.getWidth(), (double) app.TAM_LOGO / img.getHeight());
            int an = Math.max(1, (int) Math.round(img.getWidth() * escala));
            int al = Math.max(1, (int) Math.round(img.getHeight() * escala));
            app.lblLogoRC.setIcon(new ImageIcon(img.getScaledInstance(an, al, Image.SCALE_SMOOTH)));
        } catch (Exception e) {
            System.err.println("No se pudo cargar el logo del radioclub: " + e.getMessage());
        }
    }

    // =================== FIN RADIOCLUB ===================
}
