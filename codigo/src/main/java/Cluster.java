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

/** Cluster de Bitácora 26 y ON AIR */
class Cluster {
    final LibroGuardiaUI app;   // la ventana principal

    Cluster(LibroGuardiaUI app) {
        this.app = app;
    }

    // ===================== CLUSTER Y ON AIR =====================
    // El servidor de bitacora26.es guarda quién está en el aire. Solo con cuenta de Bitácora 26: todo va firmado
    // con la llave de este ordenador, y el nombre, el QTH y el locator los pone el servidor desde la ficha de la cuenta.
    static final int ALTO_CLUSTER_MAPA = 230;
    CartelOnAir cartelOnAir;
    volatile boolean onAir = false;
    private javax.swing.Timer latidoOnAir, vigiaOnAir, sondeoCluster;
    private String ultimoEnvioOnAir = "";
    private long ultimoEnvioOnAirMs = 0;
    PanelCluster panelClusterMapa;
    JComponent envolturaDerechaRef;
    JPanel filaSobreTabla;
    JToggleButton btnClusterMapa;
    boolean clusterDesplegado = false;
    private JFrame ventanaCluster;
    private final java.util.List<OpCluster> operadoresCluster = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.List<PanelCluster> panelesCluster = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Un operador en el aire, tal como lo devuelve el cluster. */
    record OpCluster(String op, String nombre, String qth, String grid, long tx, long rx, String banda, String modo, long desde, String pais, String info) {}

    /** País para la bandera: el que manda el operador, o (versiones anteriores) el de su indicativo. */
    private static String paisCluster(String pais, String op, String banda) {
        if (pais != null && pais.matches("[A-Za-z]{2}")) return pais.toUpperCase();
        String[] p = LibroGuardiaUI.paisDeIndicativo(op, banda);
        return p == null ? "" : p[0];
    }

    boolean cascadaAbierta() { return app.cat.panelCascada != null && app.cat.panelCascada.isVisible(); }

    /** Cartel rojo «ON AIR», como el de los estudios de radio: apagado casi no se ve; encendido, rojo brillante. */
    static class CartelOnAir extends JComponent {
        boolean encendido = false;
        CartelOnAir() {
            setPreferredSize(new Dimension(118, 38));
            setMinimumSize(new Dimension(118, 38));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        void setEncendido(boolean on) { encendido = on; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), m = 3;   // margen para el brillo, siempre dentro del componente
            int bw = w - 2 * m, bh = h - 2 * m;
            if (encendido) {
                for (int i = 3; i >= 1; i--) {   // brillo rojo alrededor (recogido)
                    g.setColor(new Color(255, 40, 40, 34 * (4 - i)));
                    g.fillRoundRect(m - i, m - i, bw + 2 * i, bh + 2 * i, 10 + i, 10 + i);
                }
                g.setPaint(new GradientPaint(0, m, new Color(228, 30, 36), 0, m + bh, new Color(170, 10, 18)));
                g.fillRoundRect(m, m, bw, bh, 9, 9);
                g.setColor(new Color(255, 120, 120));
            } else {
                g.setColor(new Color(28, 17, 17));
                g.fillRoundRect(m, m, bw, bh, 9, 9);
                g.setColor(new Color(58, 32, 32));
            }
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(m, m, bw - 1, bh - 1, 9, 9);
            Font f = new Font("Arial", Font.BOLD, 19);
            java.util.Map<java.awt.font.TextAttribute, Object> at = new java.util.HashMap<>();
            at.put(java.awt.font.TextAttribute.TRACKING, 0.14);
            f = f.deriveFont(at);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            String t = "ON AIR";
            int x = (w - fm.stringWidth(t)) / 2, y = (h - fm.getHeight()) / 2 + fm.getAscent();
            if (encendido) {
                g.setColor(new Color(255, 255, 255, 90));
                g.drawString(t, x, y + 1);
                g.setColor(Color.WHITE);
            } else {
                g.setColor(new Color(78, 40, 40));
            }
            g.drawString(t, x, y);
            g.dispose();
        }
    }

    /** Indicativo con el que se sale al aire: en CB el de CB; en las demás bandas el de HF. */
    private String indicativoOnAir() {
        String[] d = app.leerDatosOperador(app.operadorActual == null ? "" : app.operadorActual);
        boolean cb = app.cbBanda != null && "CB".equalsIgnoreCase(String.valueOf(app.cbBanda.getSelectedItem()));
        String ind = cb ? d[1] : d[0];
        if (ind == null || ind.isBlank()) ind = cb ? d[0] : d[1];
        if (ind == null || ind.isBlank()) ind = app.operadorActual == null ? "" : app.operadorActual;
        return ind.trim().toUpperCase();
    }

    /** Cuenta de Bitácora 26 (de este ordenador) del indicativo con el que se sale al aire. "" si no hay. */
    private String cuentaOnAir(String ind) {
        boolean cb = app.cbBanda != null && "CB".equalsIgnoreCase(String.valueOf(app.cbBanda.getSelectedItem()));
        String base = CuentaBitacora.baseIndicativo(ind);
        String c = CuentaBitacora.cuentaDeIndicativo(base, cb ? "CB" : "HAM");
        return c.isEmpty() ? CuentaBitacora.cuentaDeIndicativo(base, null) : c;
    }

    /** Cuenta para ver el cluster: la de ON AIR, la del operador o, si no, cualquiera de este ordenador. "" si no hay ninguna. */
    String cuentaParaVer() {
        if (onAir && !ultimaCuentaOnAir.isEmpty()) return ultimaCuentaOnAir;
        String c = app.operadorActual == null || app.operadorActual.isBlank() ? "" : CuentaBitacora.cuentaB26DeOperador(app.operadorActual);
        if (c.isEmpty()) {
            java.util.List<String> l = CuentaBitacora.cuentasB26();
            c = l.isEmpty() ? "" : l.get(0);
        }
        return c;
    }

    void alternarOnAir() {
        if (!onAir && (app.operadorActual == null || app.operadorActual.isBlank())) {
            JOptionPane.showMessageDialog(app, "Elige primero un operador.", "ON AIR", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!onAir && cuentaOnAir(indicativoOnAir()).isEmpty()) {
            JOptionPane.showMessageDialog(app, "<html>Para salir en el Cluster hace falta una <b>cuenta de Bitácora 26</b> con el indicativo <b>"
                    + indicativoOnAir() + "</b> en este ordenador.<br><br>"
                    + "En <b>Mi Estación → Operador (mis datos y mi cuenta)</b>: «Crear cuenta» o, si ya la tienes, «Ya tengo cuenta».<br>"
                    + "<span style='color:gray'>Así solo ven el Cluster los usuarios de Bitácora 26 y nadie puede salir con el indicativo de otro.</span></html>",
                    "ON AIR", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!onAir && !pedirComentarioOnAir()) return;   // Cancelar: no se sale al aire
        onAir = !onAir;
        cartelOnAir.setEncendido(onAir);
        if (onAir) {
            ultimoEnvioOnAir = "";
            avisoOnAirPendiente = true;   // el mensaje de "estás en el Cluster" sale cuando el Cluster lo confirma
            app.lblEstado.setText("ON AIR: conectando con el Cluster…");
            enviarOnAir(true);
            // Se repite cada 60 s (si no, el cluster lo quita a los 3 min) y se avisa al momento si cambia la frecuencia o el modo
            if (latidoOnAir == null) {
                latidoOnAir = new javax.swing.Timer(60_000, e -> enviarOnAir(true));
                vigiaOnAir = new javax.swing.Timer(2_000, e -> enviarOnAir(false));
            }
            latidoOnAir.start();
            vigiaOnAir.start();
        } else {
            if (latidoOnAir != null) { latidoOnAir.stop(); vigiaOnAir.stop(); }
            String cuenta = ultimaCuentaOnAir;
            ultimaCuentaOnAir = "";
            if (!cuenta.isEmpty()) app.cat.hiloCluster.submit(() -> firmadoCluster(cuenta, "cluster_off", "{}"));
            app.lblEstado.setText("ON AIR apagado: ya no apareces en el Cluster.");
            SwingUtilities.invokeLater(() -> app.cat.hiloCluster.submit(this::leerCluster));
        }
    }

    String ultimoIndicativoOnAir = "";
    /** Cuenta con la que se está en el aire (para quitarse al apagar ON AIR o al cerrar el programa). */
    volatile String ultimaCuentaOnAir = "";

    /** Al cerrar el programa con ON AIR encendido: se avisa al cluster de que ya no se está en el aire. */
    void apagarAlCerrar() {
        String cuenta = ultimaCuentaOnAir;
        if (onAir && !cuenta.isEmpty()) firmadoCluster(cuenta, "cluster_off", "{}");
    }

    /** Comentario de la actividad que se ve en el Cluster (lo escribe el operador al pulsar ON AIR). */
    private String comentarioOnAir = "";

    /** Al encender ON AIR: cuadro para escribir qué se está haciendo. Devuelve false si se cancela. */
    private boolean pedirComentarioOnAir() {
        String claveAct = "ONAIR_COMENTARIO_" + String.valueOf(app.cbSelectorActividad == null ? "" : app.cbSelectorActividad.getSelectedItem()).replaceAll("[^A-Za-z0-9]", "_");
        String previo = LibroGuardiaUI.ConfigApp.get(claveAct, "");
        if (previo.isEmpty()) previo = comentarioOnAir;
        if (previo.isEmpty()) previo = LibroGuardiaUI.ConfigApp.get("ONAIR_COMENTARIO", "");
        JTextField txt = new JTextField(previo, 32);
        ((javax.swing.text.AbstractDocument) txt.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter() {
            private String limpiar(String t) { return t == null ? "" : t.replaceAll("[{}<>\\p{Cntrl}]", ""); }
            @Override public void insertString(FilterBypass fb, int off, String t, javax.swing.text.AttributeSet a) throws javax.swing.text.BadLocationException {
                replace(fb, off, 0, t, a);
            }
            @Override public void replace(FilterBypass fb, int off, int len, String t, javax.swing.text.AttributeSet a) throws javax.swing.text.BadLocationException {
                t = limpiar(t);
                int cabe = 80 - (fb.getDocument().getLength() - len);
                if (t.length() > cabe) t = t.substring(0, Math.max(0, cabe));
                super.replace(fb, off, len, t, a);
            }
        });
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel("<html><b>¿Qué estás haciendo?</b> Se verá en el Cluster junto a tu indicativo.<br>"
                + "<span style='color:gray'>Por ejemplo: «Activando castillo DCE M-012» o «Llamando CQ, 59 desde Madrid». Puedes dejarlo en blanco.</span></html>"), BorderLayout.NORTH);
        p.add(txt, BorderLayout.CENTER);
        p.add(new JLabel("<html><span style='color:gray'>Máximo 80 letras.</span></html>"), BorderLayout.SOUTH);
        SwingUtilities.invokeLater(txt::requestFocusInWindow);
        int r = JOptionPane.showConfirmDialog(app, p, "ON AIR", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return false;
        comentarioOnAir = txt.getText().trim();
        LibroGuardiaUI.ConfigApp.put("ONAIR_COMENTARIO", comentarioOnAir);
        LibroGuardiaUI.ConfigApp.put(claveAct, comentarioOnAir);   // cada actividad recuerda el suyo
        return true;
    }

    /** País (ISO de 2 letras) del operador para la bandera del cluster: por su indicativo de radioaficionado o la división de CB. */
    private static String paisParaCluster(String[] d) {
        String[] p = LibroGuardiaUI.paisDeIndicativo(d[0], "");
        if (p == null) p = LibroGuardiaUI.paisDeIndicativo(d[1], "CB");
        return p == null ? "" : p[0];
    }
    private boolean avisoOnAirPendiente = false;

    /** Manda al cluster quién soy y dónde estoy. Si "siempre" es false, solo si algo ha cambiado. */
    private void enviarOnAir(boolean siempre) {
        if (!onAir) return;
        String ind = indicativoOnAir();
        String cuenta = cuentaOnAir(ind);
        String[] d = app.leerDatosOperador(app.operadorActual == null ? "" : app.operadorActual);
        long tx = Math.max(0, app.cat.herciosDeTexto(app.txtFreqTx.getText()));
        long rx = Math.max(0, app.cat.herciosDeTexto(app.txtFreqRx.getText()));
        if (rx == 0) rx = tx;
        // Sin CAT también se sale en el cluster: si aún no hay frecuencia, se ve el indicativo, el nombre y la hora
        String pais = paisParaCluster(d);
        String banda = app.cbBanda.getSelectedItem() == null ? "" : app.cbBanda.getSelectedItem().toString();
        String modo = app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString();
        // El nombre, el QTH y el locator no se mandan: los pone el servidor desde la ficha de la cuenta
        String json = "{\"op\":\"" + app.jsonTexto(ind) + "\",\"tx\":" + tx + ",\"rx\":" + rx
                + ",\"banda\":\"" + app.jsonTexto(banda) + "\",\"modo\":\"" + app.jsonTexto(modo) + "\",\"pais\":\"" + app.jsonTexto(pais)
                + "\",\"info\":\"" + app.jsonTexto(comentarioOnAir)
                + "\",\"operador\":\"" + app.jsonTexto(LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual) + "\"}";
        if (!siempre && json.equals(ultimoEnvioOnAir) && cuenta.equals(ultimaCuentaOnAir)) return;
        // Al girar el dial no se manda cada paso: como mucho, cada 2 segundos
        if (!siempre && System.currentTimeMillis() - ultimoEnvioOnAirMs < 1_800) return;
        String antes = ultimaCuentaOnAir;
        ultimoEnvioOnAir = json;
        ultimoEnvioOnAirMs = System.currentTimeMillis();
        ultimoIndicativoOnAir = ind;
        ultimaCuentaOnAir = cuenta;
        app.cat.hiloCluster.submit(() -> {
            // Si ha cambiado la cuenta (p. ej. de HF a CB), la anterior deja de estar en el aire
            if (!antes.isEmpty() && !antes.equals(cuenta)) firmadoCluster(antes, "cluster_off", "{}");
            if (cuenta.isEmpty()) {
                SwingUtilities.invokeLater(() -> {
                    if (onAir) app.lblEstado.setText("ON AIR: no hay cuenta de Bitácora 26 de " + ind + " en este ordenador; no sales en el Cluster.");
                });
                return;
            }
            String[] r = firmadoCluster(cuenta, "cluster_on", json);
            boolean ok = "200".equals(r[0]);
            SwingUtilities.invokeLater(() -> {
                if (!ok && onAir) app.lblEstado.setText("0".equals(r[0])
                        ? "ON AIR: no se puede conectar con el Cluster (¿hay internet?). Se seguirá intentando."
                        : "ON AIR: " + CuentaBitacora.errorJson(r));
                if (ok && onAir && avisoOnAirPendiente) {
                    avisoOnAirPendiente = false;
                    app.lblEstado.setText("ON AIR: estás en el Cluster de Bitácora 26 como " + ind + ".");
                }
            });
            if (ok) ponerLista(r[1]);   // la respuesta ya trae quién está en el aire
        });
    }

    /** Petición firmada al cluster; si se corta la conexión, se prueba otra vez al momento. */
    private String[] firmadoCluster(String cuenta, String accion, String datos) {
        String[] r = app.cuentaB26.firmadoB26(cuenta, accion, datos);
        if ("0".equals(r[0])) r = app.cuentaB26.firmadoB26(cuenta, accion, datos);
        return r;
    }

    /** Por qué no se ve el cluster ("" si se ve bien). */
    private volatile String motivoCluster = "";

    /** Pide al cluster la lista de operadores en el aire y la pone en los paneles. */
    private void leerCluster() {
        String cuenta = cuentaParaVer();
        if (cuenta.isEmpty()) {
            motivoCluster = "necesitas una cuenta de Bitácora 26 para verlo";
            SwingUtilities.invokeLater(() -> { operadoresCluster.clear(); for (PanelCluster p : panelesCluster) p.actualizar(false); });
            return;
        }
        String[] r = firmadoCluster(cuenta, "cluster_ver", "{}");
        leerClusterCb(cuenta);
        if ("200".equals(r[0])) { ponerLista(r[1]); return; }
        motivoCluster = "0".equals(r[0]) ? "sin conexión" : CuentaBitacora.errorJson(r);
        SwingUtilities.invokeLater(() -> { for (PanelCluster p : panelesCluster) p.actualizar(false); });
    }

    /** Pone en los paneles la lista que devuelve el servidor. */
    private void ponerLista(String cuerpo) {
        java.util.List<OpCluster> lista = new ArrayList<>();
        // Cada operador va entre llaves dentro de "operadores": [ {...}, {...} ]
        int ini = cuerpo.indexOf("\"operadores\"");
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(ini < 0 ? "" : cuerpo.substring(ini));
        while (m.find()) {
            String o = m.group();
            lista.add(new OpCluster(app.campoJson(o, "op"), app.campoJson(o, "nombre"), app.campoJson(o, "qth"), app.campoJson(o, "grid"),
                    app.numeroJson(o, "tx"), app.numeroJson(o, "rx"), app.campoJson(o, "banda"), app.campoJson(o, "modo"), app.numeroJson(o, "desde"), paisCluster(app.campoJson(o, "pais"), app.campoJson(o, "op"), app.campoJson(o, "banda")), app.campoJson(o, "info")));
        }
        motivoCluster = "";
        SwingUtilities.invokeLater(() -> {
            operadoresCluster.clear(); operadoresCluster.addAll(lista);
            for (PanelCluster p : panelesCluster) p.actualizar(true);
        });
    }

    /** Se pregunta al cluster cada 15 s mientras se esté viendo en algún sitio. */
    private void asegurarSondeoCluster() {
        if (sondeoCluster != null) return;
        sondeoCluster = new javax.swing.Timer(15_000, e -> {
            boolean visto = false;
            for (PanelCluster p : panelesCluster) if (p.isShowing()) visto = true;
            if (visto) app.cat.hiloCluster.submit(this::leerCluster);
        });
        sondeoCluster.start();
    }

    /** Cluster debajo del mapa: con la cascada abierta siempre; sin ella, solo si se ha desplegado con el botón CLUSTER. */
    void actualizarClusterIncrustado() {
        if (btnClusterMapa != null) btnClusterMapa.setVisible(!cascadaAbierta());
        if (envolturaDerechaRef != null) { envolturaDerechaRef.revalidate(); envolturaDerechaRef.repaint(); }
        if (cascadaAbierta() || clusterDesplegado) app.cat.hiloCluster.submit(this::leerCluster);
        app.revalidate();
        app.repaint();
    }

    private VentanaClusters ventanaClusters;

    /** Ventana Cluster con una pestaña por clúster (Bitácora 26, URE, DXFun, RBN) y filtros. */
    void abrirVentanaCluster() {
        if (ventanaClusters == null) ventanaClusters = new VentanaClusters(app, this);
        ventanaClusters.setVisible(true);
        ventanaClusters.toFront();
        pedirLectura();
    }

    /** Pide al servidor la lista de Bitácora 26 en el aire (sin esperar). */
    void pedirLectura() { app.cat.hiloCluster.submit(this::leerCluster); }

    /** Los operadores de Bitácora 26 en el aire, como avisos de la ventana Cluster. */
    java.util.List<ClustersDX.Aviso> avisosBitacora26() {
        java.util.List<ClustersDX.Aviso> l = new ArrayList<>();
        for (OpCluster o : operadoresCluster) {
            String[] p = LibroGuardiaUI.paisDeIndicativo(o.op(), o.banda());
            String entidad = p == null ? "" : p[1];
            String banda = o.tx() > 0 ? ClustersDX.banda(o.tx()) : (o.banda() == null ? "" : o.banda());
            if (banda.isEmpty() && o.banda() != null) banda = o.banda();
            String modo = o.modo() == null || o.modo().isBlank() ? (o.tx() > 0 ? ClustersDX.modo(o.tx(), "") : "") : o.modo().toUpperCase(java.util.Locale.ROOT);
            l.add(new ClustersDX.Aviso(o.desde() > 0 ? o.desde() * 1000 : System.currentTimeMillis(), o.tx(), o.op(), modo,
                    o.info() == null ? "" : o.info(), "", "Bitácora 26", p == null ? "" : p[0], entidad, ClustersDX.continente(entidad), banda));
        }
        return l;
    }

    // ------------------------------------------------------------------ Cluster CB (anuncios de banda ciudadana)
    private volatile java.util.List<ClustersDX.Aviso> avisosCb = java.util.List.of();
    volatile boolean cbConectado;

    /** Los anuncios del Cluster CB (para la pestaña «Cluster CB» de la ventana Cluster). */
    java.util.List<ClustersDX.Aviso> avisosClusterCb() { return avisosCb; }

    private void leerClusterCb(String cuenta) {
        String[] r = firmadoCluster(cuenta, "cb_ver", "{}");
        cbConectado = "200".equals(r[0]);
        if (cbConectado) avisosCb = avisosDeCb(r[1]);
        String[] rd = firmadoCluster(cuenta, "dx_ver", "{}");   // anuncios DX de los usuarios (servidor nuevo; si no lo tiene, nada)
        if ("200".equals(rd[0])) avisosDx = avisosDeDx(rd[1]);
    }

    // ------------------------------------------------------------------ Anuncios DX de los usuarios en el cluster Bitácora 26
    private volatile java.util.List<ClustersDX.Aviso> avisosDx = java.util.List.of();

    /** Estaciones DX anunciadas por los usuarios (pestaña «Bitácora 26»). */
    java.util.List<ClustersDX.Aviso> avisosClusterDx() { return avisosDx; }

    private java.util.List<ClustersDX.Aviso> avisosDeDx(String cuerpo) {
        java.util.List<ClustersDX.Aviso> l = new ArrayList<>();
        int ini = cuerpo.indexOf("\"anuncios\"");
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(ini < 0 ? "" : cuerpo.substring(ini));
        while (m.find()) {
            String o = m.group();
            String dx = app.campoJson(o, "dx");
            long hz = app.numeroJson(o, "hz");
            String banda = ClustersDX.banda(hz);
            String[] p = LibroGuardiaUI.paisDeIndicativo(dx, banda);
            String entidad = p == null ? "" : p[1];
            l.add(new ClustersDX.Aviso(app.numeroJson(o, "t") * 1000, hz, dx, app.campoJson(o, "modo"), app.campoJson(o, "info"),
                    app.campoJson(o, "anuncia"), "Bitácora 26", p == null ? "" : p[0], entidad, ClustersDX.continente(entidad), banda));
        }
        return l;
    }

    /** Anuncia una estación DX en el cluster de Bitácora 26. Devuelve null si ha ido bien, o el motivo. No llamar desde la pantalla. */
    String anunciarDx(String dx, long hz, String info) {
        String cuenta = cuentaParaVer();
        if (cuenta.isEmpty()) return "necesitas una cuenta de Bitácora 26 para anunciar";
        String json = "{\"dx\":\"" + app.jsonTexto(dx) + "\",\"hz\":" + hz + ",\"info\":\"" + app.jsonTexto(info == null ? "" : info) + "\"}";
        String[] r = firmadoCluster(cuenta, "dx_anunciar", json);
        if ("200".equals(r[0])) { avisosDx = avisosDeDx(r[1]); return null; }
        return "0".equals(r[0]) ? "sin conexión con Bitácora 26" : CuentaBitacora.errorJson(r);
    }

    private java.util.List<ClustersDX.Aviso> avisosDeCb(String cuerpo) {
        java.util.List<ClustersDX.Aviso> l = new ArrayList<>();
        int ini = cuerpo.indexOf("\"anuncios\"");
        Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(ini < 0 ? "" : cuerpo.substring(ini));
        while (m.find()) {
            String o = m.group();
            String dx = app.campoJson(o, "dx");
            String[] p = LibroGuardiaUI.paisDeIndicativo(dx, "CB");
            String entidad = p == null ? "" : p[1];
            String iso = app.campoJson(o, "pais");
            l.add(new ClustersDX.Aviso(app.numeroJson(o, "t") * 1000, app.numeroJson(o, "hz"), dx, app.campoJson(o, "modo"),
                    app.campoJson(o, "info"), app.campoJson(o, "anuncia"), "Cluster CB",
                    iso.isEmpty() && p != null ? p[0] : iso.toLowerCase(java.util.Locale.ROOT), entidad, ClustersDX.continente(entidad), "CB"));
        }
        return l;
    }

    /** Pone un anuncio en el Cluster CB. Devuelve null si ha ido bien, o el motivo. No llamar desde la pantalla. */
    String anunciarCb(String dx, long hz, String modo, String info) {
        String cuenta = cuentaParaVer();
        if (cuenta.isEmpty()) return "necesitas una cuenta de Bitácora 26 para anunciar";
        String[] p = LibroGuardiaUI.paisDeIndicativo(dx, "CB");
        String json = "{\"dx\":\"" + app.jsonTexto(dx) + "\",\"hz\":" + hz + ",\"modo\":\"" + app.jsonTexto(modo == null ? "" : modo)
                + "\",\"pais\":\"" + app.jsonTexto(p == null ? "" : p[0]) + "\",\"info\":\"" + app.jsonTexto(info == null ? "" : info)
                + "\",\"op\":\"" + app.jsonTexto(app.operadorActual == null ? "" : app.operadorActual) + "\"}";
        String[] r = firmadoCluster(cuenta, "cb_anunciar", json);
        if ("200".equals(r[0])) { avisosCb = avisosDeCb(r[1]); return null; }
        return "0".equals(r[0]) ? "sin conexión con Bitácora 26" : CuentaBitacora.errorJson(r);
    }

    /** Quita mis anuncios del Cluster CB. Devuelve cuántos se han quitado (-1 si ha fallado). No llamar desde la pantalla. */
    int quitarAnunciosCb() {
        String cuenta = cuentaParaVer();
        if (cuenta.isEmpty()) return -1;
        String[] r = firmadoCluster(cuenta, "cb_quitar", "{}");
        if (!"200".equals(r[0])) return -1;
        leerClusterCb(cuenta);
        return (int) app.numeroJson(r[1], "quitados");
    }

    /** Clic en un aviso de la ventana Cluster: se prepara el contacto como con un operador de Bitácora 26. */
    void irA(ClustersDX.Aviso a) {
        if ("Bitácora 26".equals(a.fuente())) {
            for (OpCluster o : operadoresCluster) if (o.op().equalsIgnoreCase(a.dx())) { irAOperadorCluster(o); return; }
        }
        String modo = "Otros digitales".equals(a.modo()) ? "" : a.modo();
        irAOperadorCluster(new OpCluster(a.dx(), "", "", "", a.hz(), a.hz(), a.banda(), modo, 0, a.iso(), a.comentario()));
    }

    /**
     * "Ir" a un operador del cluster: mi TX en su RX y mi RX en su TX, con su banda y su modo; el contacto se rellena
     * con sus datos y, con CAT, la emisora va allí (en split si TX y RX son distintas). La cascada sigue a la emisora.
     */
    private void irAOperadorCluster(OpCluster o) {
        long suTx = o.tx(), suRx = o.rx() > 0 ? o.rx() : o.tx();
        long miTx = suRx, miRx = suTx;
        boolean hayFrecuencia = suTx > 0;
        String banda = app.normalizarOpcion(o.banda(), app.BANDAS), modo = app.normalizarOpcion(o.modo(), app.MODOS);
        if (app.cat.comboContiene(app.cbBanda, banda)) app.cbBanda.setSelectedItem(banda);
        if (app.cat.comboContiene(app.cbModo, modo)) app.cbModo.setSelectedItem(modo);
        if (hayFrecuencia) {
            app.cat.frecuenciaPorPrograma = true;
            try {
                app.txtFreqTx.setText(String.format(java.util.Locale.GERMANY, "%,d", miTx));
                app.txtFreqRx.setText(String.format(java.util.Locale.GERMANY, "%,d", miRx));
            } finally { app.cat.frecuenciaPorPrograma = false; }
            app.cat.ultimaCifraEscritaMs = System.currentTimeMillis();
        }
        app.txtIndicativo.setText(o.op());
        // Cuando termine la búsqueda normal del indicativo, se completa con lo que da el cluster (solo lo vacío)
        javax.swing.Timer completar = new javax.swing.Timer(900, e -> {
            if (!app.txtIndicativo.getText().trim().equalsIgnoreCase(o.op())) return;
            if (app.txtNombre.getText().isBlank() && !o.nombre().isBlank()) app.txtNombre.setText(o.nombre().toUpperCase());
            if (app.txtQth.getText().isBlank() && !o.qth().isBlank()) app.txtQth.setText(o.qth().toUpperCase());
            if (app.txtGrid.getText().isBlank() && !o.grid().isBlank()) app.txtGrid.setText(o.grid().toUpperCase());
        });
        completar.setRepeats(false);
        completar.start();
        // El cursor se queda en el indicativo: con pulsar Intro se guarda el contacto
        SwingUtilities.invokeLater(() -> { app.txtIndicativo.requestFocusInWindow(); app.txtIndicativo.setCaretPosition(app.txtIndicativo.getText().length()); });
        if (app.catConectado && hayFrecuencia) {
            final int tx = (int) miTx, rx = (int) miRx;
            final int codModo = app.cat.codigoModoOmniRig(modo, miRx);
            app.cat.enviarOrden("Cluster: " + o.op(), rig -> {
                if (tx == rx) app.cat.ponerFrecuenciaEmisora(rig, rx);
                else {
                    try { Dispatch.call(rig, "SetSplitMode", new Variant(rx), new Variant(tx)); }
                    catch (Exception ex) { app.cat.ponerFrecuenciaEmisora(rig, rx); }
                }
                if (codModo != 0) Dispatch.put(rig, "Mode", new Variant(codModo));
            });
        }
        app.cat.refrescarFrontal();
        app.lblEstado.setText("Cluster: contacto con " + o.op() + " preparado" + (hayFrecuencia ? " · TX " + app.txtFreqTx.getText() + " · RX " + app.txtFreqRx.getText() : "")
                + (banda.isBlank() ? "" : " · " + banda + " " + modo) + " · pulsa Intro para guardarlo");
    }

    /** Lista del cluster (se usa en la ventana y debajo del mapa). Pinchando en un operador se prepara el contacto. */
    class PanelCluster extends JPanel {
        /** true = el de debajo del mapa: solo bandera, Operador, Frecuencia y Comentario. */
        private final boolean corto;
        private final DefaultTableModel modelo;
        // Si cabe, la tabla ocupa todo el ancho; si no, aparece la barra para desplazarla de lado
        private final JTable tabla = new JTable() {
            @Override public boolean getScrollableTracksViewportWidth() {
                return getParent() != null && getParent().getWidth() > getPreferredSize().width;
            }
        };
        private final JLabel cabecera = new JLabel(" ");
        private final java.util.List<OpCluster> filas = new ArrayList<>();

        PanelCluster() { this(false); }

        PanelCluster(boolean corto) {
            super(new BorderLayout());
            this.corto = corto;
            modelo = new DefaultTableModel(corto ? new Object[]{"", "Operador", "Frecuencia", "Comentario"}
                    : new Object[]{"", "Operador", "Nombre", "TX", "RX", "Banda", "Modo", "Desde", "Comentario"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            tabla.setModel(modelo);
            setBackground(app.TEMA_FONDO);
            setBorder(BorderFactory.createLineBorder(new Color(43, 61, 92)));
            cabecera.setFont(new Font("Arial", Font.BOLD, 12));
            cabecera.setForeground(app.TEMA_AMBAR);
            cabecera.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            if (corto) {
                JPanel norte = new JPanel(new BorderLayout());
                norte.setOpaque(false);
                norte.add(cabecera, BorderLayout.CENTER);
                JButton todos = new JButton("Ver todos los clústeres…");
                todos.setFont(new Font("Dialog", Font.PLAIN, 11));
                todos.setMargin(new Insets(1, 8, 1, 8));
                todos.setFocusable(false);
                todos.addActionListener(e -> abrirVentanaCluster());
                norte.add(todos, BorderLayout.EAST);
                add(norte, BorderLayout.NORTH);
            } else add(cabecera, BorderLayout.NORTH);
            tabla.setRowHeight(22);
            tabla.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            tabla.getTableHeader().setReorderingAllowed(false);
            int[] anchos = corto ? new int[]{30, 82, 96, 260} : new int[]{34, 80, 120, 82, 82, 46, 46, 46, 220};
            for (int i = 0; i < anchos.length; i++) tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
            // Bandera del país (pequeña), con el nombre del país al dejar el ratón encima
            tabla.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int f, int c) {
                    super.getTableCellRendererComponent(t, "", sel, foco, f, c);
                    String iso = v == null ? "" : v.toString();
                    ImageIcon ic = iso.isEmpty() ? null : app.banderaIso(iso);
                    setIcon(ic == null ? null : new ImageIcon(ic.getImage().getScaledInstance(-1, 14, Image.SCALE_SMOOTH)));
                    setHorizontalAlignment(CENTER);
                    setToolTipText(iso.isEmpty() ? null : new java.util.Locale("", iso).getDisplayCountry(new java.util.Locale("es", "ES")));
                    return this;
                }
            });
            tabla.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int f, int c) {
                    super.getTableCellRendererComponent(t, v, sel, foco, f, c);
                    setFont(getFont().deriveFont(Font.BOLD));
                    if (!sel) setForeground(app.TEMA_AMBAR);
                    return this;
                }
            });
            if (corto) tabla.getColumnModel().getColumn(3).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int f, int c) {
                    super.getTableCellRendererComponent(t, v, sel, foco, f, c);
                    setToolTipText(v == null || v.toString().isBlank() ? null : v.toString());
                    return this;
                }
            });
            tabla.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            tabla.setToolTipText("Pincha en un operador: se prepara el contacto (indicativo, nombre, frecuencias, banda y modo). Luego pulsa Intro para guardarlo.");
            tabla.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int f = tabla.rowAtPoint(e.getPoint());
                    if (f < 0 || f >= filas.size()) return;
                    irAOperadorCluster(filas.get(f));
                }
            });
            JScrollPane sc = new JScrollPane(tabla, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            sc.getViewport().setBackground(app.TEMA_FONDO);
            add(sc, BorderLayout.CENTER);
            panelesCluster.add(this);
            asegurarSondeoCluster();
            actualizar(true);
        }

        void actualizar(boolean conexionBien) {
            filas.clear();
            filas.addAll(operadoresCluster);
            modelo.setRowCount(0);
            java.time.format.DateTimeFormatter hhmm = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
            for (OpCluster o : filas) {
                String desde = o.desde() > 0 ? java.time.Instant.ofEpochSecond(o.desde()).atZone(app.horaLocal() ? java.time.ZoneId.systemDefault() : ZoneOffset.UTC).format(hhmm) : "";
                String fTx = o.tx() > 0 ? String.format(java.util.Locale.GERMANY, "%,d", o.tx()) : "—";
                long rx = o.rx() > 0 ? o.rx() : o.tx();
                String fRx = rx > 0 ? String.format(java.util.Locale.GERMANY, "%,d", rx) : "—";
                String info = o.info() == null ? "" : o.info();
                if (corto) {
                    // Frecuencia: la de transmisión (y la de recepción si es distinta); sin frecuencia, la banda y el modo
                    String fr = o.tx() > 0 ? fTx + (rx != o.tx() ? " / " + fRx : "") : (o.banda() + " " + o.modo()).trim();
                    modelo.addRow(new Object[]{o.pais(), o.op(), fr.isEmpty() ? "—" : fr, info});
                } else {
                    modelo.addRow(new Object[]{o.pais(), o.op(), o.nombre(), fTx, fRx, o.banda(), o.modo(), desde, info});
                }
            }
            String txt = "CLUSTER BITÁCORA 26 · " + (filas.isEmpty() ? "nadie en el aire" : filas.size() + (filas.size() == 1 ? " operador en el aire" : " operadores en el aire"));
            if (!conexionBien) txt += " · " + (motivoCluster.isEmpty() ? "sin conexión" : motivoCluster);
            cabecera.setText(txt);
        }
    }
}
