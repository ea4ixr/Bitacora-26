import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.List;

/**
 * LOG en la web (bitacora26.es): copia de seguridad del LOG de cada operador con cuenta de Bitácora 26,
 * en los dos sentidos. Lo que cambia aquí se sube; lo que se cambia en la web (o en otro ordenador mío) se baja.
 * Usa las mismas "líneas" que la sincronización de Mis ordenadores (Radioclub.estadoMisOrdenadores / aplicarLineaOp),
 * así que no hace falta tener activado Mis ordenadores. Se hace al arrancar y luego cada 5 minutos.
 */
final class LogWeb {
    private final LibroGuardiaUI app;
    private final java.util.concurrent.atomic.AtomicBoolean enMarcha = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Set<String> avisadoCondiciones = new HashSet<>();
    private static final int TROZO = 2000;

    LogWeb(LibroGuardiaUI app) {
        this.app = app;
    }

    /** Arranca: primera vez a los 40 s (después de la sincronización del arranque) y luego cada 5 minutos. */
    void arrancar() {
        java.util.concurrent.ScheduledExecutorService h = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LogWeb");
            t.setDaemon(true);
            return t;
        });
        h.scheduleWithFixedDelay(this::sincronizar, 40, 300, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Sube y baja lo pendiente de todos los operadores de este ordenador que tienen cuenta. */
    void sincronizar() {
        if (!enMarcha.compareAndSet(false, true)) return;
        try {
            if (!app.cuentaB26.sincronizarPermitido()) return;
            for (String op : app.obtenerListaOperadores()) {
                String cuenta = CuentaBitacora.cuentaDeIndicativo(CuentaBitacora.baseIndicativo(op), null);
                if (cuenta.isEmpty()) continue;
                try {
                    sincronizarOperador(op, cuenta);
                } catch (Exception ex) {
                    System.err.println("LOG en la web (" + op + "): " + ex.getMessage());
                }
            }
        } finally {
            enMarcha.set(false);
        }
    }

    private static void crearTabla(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS logweb_pub (op TEXT, tipo TEXT, clave TEXT, datos TEXT, PRIMARY KEY (op, tipo, clave))");
        }
    }

    private void sincronizarOperador(String op, String cuenta) throws Exception {
        boolean cambios = false;
        try (Connection conn = LibroGuardiaUI.DatabaseManager.conectar()) {
            crearTabla(conn);
            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE actividades SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid = ''");
                st.execute("UPDATE qsos SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL OR uuid = ''");
            }
            // 1) BAJAR lo cambiado en la web (o en mis otros ordenadores)
            String claveDesde = "LOGWEB_DESDE_" + cuenta;
            long desde = Long.parseLong(LibroGuardiaUI.ConfigApp.get(claveDesde, "0").replaceAll("[^0-9]", "0"));
            Set<String> tocados = new LinkedHashSet<>();
            for (int vuelta = 0; vuelta < 50; vuelta++) {
                String[] r = app.cuentaB26.firmadoB26(cuenta, "logweb_bajar", "{\"desde\":" + desde + "}");
                if ("403".equals(r[0]) && r[1].contains("condiciones")) { avisarCondiciones(cuenta); return; }
                if (!"200".equals(r[0])) return;
                String lote = LibroGuardiaUI.campoJson(r[1], "lote");
                List<String> lineas = new ArrayList<>();
                if (!lote.isEmpty()) for (String l : Radioclub.descomprimirB64(lote).split("\n")) if (!l.isBlank()) lineas.add(l);
                lineas.sort(Comparator.comparingLong(l -> { try { return Long.parseLong(Radioclub.decodificar(l).getOrDefault("f", "0")); } catch (Exception e) { return 0L; } }));
                for (String l : lineas) {
                    // En la web el operador puede venir escrito de otra forma (APOSITOS en vez de Apósitos)
                    SortedMap<String, String> m = Radioclub.decodificar(l);
                    if (!CuentaBitacora.baseIndicativo(m.getOrDefault("op", "")).equals(CuentaBitacora.baseIndicativo(op))) continue;
                    m.put("op", op);
                    String t = Radioclub.aplicarLineaOp(conn, op, Radioclub.codificar(m), "LOGWEB-NUNCA");
                    if (t != null) tocados.add(t);
                }
                long hasta = app.numeroJson(r[1], "hasta");
                if (hasta > desde) {
                    desde = hasta;
                    LibroGuardiaUI.ConfigApp.put(claveDesde, String.valueOf(desde));
                }
                if (!r[1].contains("\"quedan\": true") && !r[1].contains("\"quedan\":true")) break;
            }
            if (!tocados.isEmpty()) {
                cambios = true;
                // Contactos que llegaron antes que su actividad: se enganchan ahora (igual que en Mis ordenadores)
                try (PreparedStatement ps = conn.prepareStatement("UPDATE qsos SET actividad_numeral = (SELECT a.numeral FROM actividades a WHERE a.uuid = qsos.act_uuid), "
                        + "actividad = (SELECT a.nombre FROM actividades a WHERE a.uuid = qsos.act_uuid), act_uuid = '' "
                        + "WHERE operador = ? AND COALESCE(radioclub,0) = 0 AND COALESCE(act_uuid,'') <> '' AND actividad_numeral = 0 "
                        + "AND EXISTS (SELECT 1 FROM actividades a WHERE a.uuid = qsos.act_uuid)")) {
                    ps.setString(1, op);
                    ps.executeUpdate();
                }
            }
            // 2) SUBIR lo que ha cambiado aquí (la primera vez, todo el LOG)
            Map<String, Map<String, String>> ahora = Radioclub.estadoMisOrdenadores(conn, op);
            // Lo que acaba de llegar de la web ya está allí: no se devuelve
            for (String tc : tocados) {
                int k = tc.indexOf('|');
                guardarPub(conn, op, tc.substring(0, k), tc.substring(k + 1), ahora.getOrDefault(tc.substring(0, k), Map.of()).get(tc.substring(k + 1)));
            }
            Map<String, Map<String, String>> antes = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT tipo, clave, datos FROM logweb_pub WHERE op = ?")) {
                ps.setString(1, op);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) antes.computeIfAbsent(rs.getString(1), x -> new HashMap<>()).put(rs.getString(2), rs.getString(3));
                }
            }
            List<String[]> pend = new ArrayList<>();   // tipo, clave, datos (null = borrado), línea
            long ahoraMs = System.currentTimeMillis();
            for (Map.Entry<String, Map<String, String>> t : ahora.entrySet()) {
                Map<String, String> ant = antes.getOrDefault(t.getKey(), Map.of());
                Set<String> claves = new LinkedHashSet<>(t.getValue().keySet());
                claves.addAll(ant.keySet());
                for (String clave : claves) {
                    String datos = t.getValue().get(clave);
                    if (Objects.equals(datos, ant.get(clave))) continue;
                    if (datos == null && !ant.containsKey(clave)) continue;
                    // La primera vez se sube con la fecha que ya tenía (si la tiene), para no pisar nada más nuevo
                    Long ver = ant.containsKey(clave) ? null : Radioclub.versionOp(conn, op, t.getKey(), clave);
                    SortedMap<String, String> linea = new TreeMap<>();
                    linea.put("v", "1"); linea.put("t", t.getKey()); linea.put("k", clave);
                    linea.put("f", String.valueOf(ver != null ? ver : ahoraMs));
                    linea.put("del", datos == null ? "1" : "0"); linea.put("d", datos == null ? "" : datos);
                    linea.put("dev", "PC"); linea.put("op", op);
                    pend.add(new String[]{t.getKey(), clave, datos, Radioclub.codificar(linea)});
                }
            }
            for (int i = 0; i < pend.size(); i += TROZO) {
                List<String[]> trozo = pend.subList(i, Math.min(pend.size(), i + TROZO));
                StringBuilder sb = new StringBuilder();
                for (String[] p : trozo) sb.append(p[3]).append('\n');
                String[] r = app.cuentaB26.firmadoB26(cuenta, "logweb_subir", "{\"lote\":\"" + Radioclub.comprimirB64(sb.toString()) + "\"}");
                if ("403".equals(r[0]) && r[1].contains("condiciones")) { avisarCondiciones(cuenta); return; }
                if (!"200".equals(r[0])) return;   // se volverá a intentar en la próxima vuelta
                for (String[] p : trozo) guardarPub(conn, op, p[0], p[1], p[2]);
            }
        }
        if (cambios) SwingUtilities.invokeLater(() -> {
            if (app.tablaQSOs != null && !app.mostrandoVistaPuntos && !app.mostrandoVistaCB) app.cargarQSOsDesdeBaseDatos();
            app.lblEstado.setText("LOG en la web: han llegado cambios hechos en bitacora26.es.");
        });
    }

    private static void guardarPub(Connection conn, String op, String tipo, String clave, String datos) throws SQLException {
        if (datos == null) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM logweb_pub WHERE op = ? AND tipo = ? AND clave = ?")) {
                ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, clave); ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO logweb_pub (op, tipo, clave, datos) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, op); ps.setString(2, tipo); ps.setString(3, clave); ps.setString(4, datos); ps.executeUpdate();
        }
    }

    /** Sin las condiciones nuevas aceptadas no hay LOG en la web: se dice una vez por sesión, en la barra de abajo. */
    private void avisarCondiciones(String cuenta) {
        if (!avisadoCondiciones.add(cuenta)) return;
        SwingUtilities.invokeLater(() -> app.lblEstado.setText("LOG en la web: acepta las condiciones de uso nuevas de " + CuentaBitacora.indDeCuenta(cuenta)
                + " para guardar tu LOG en bitacora26.es."));
    }
}
