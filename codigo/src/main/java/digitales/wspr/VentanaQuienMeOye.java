package digitales.wspr;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «¿Quién me oye?» de FT8 / FT4: qué estaciones han oído mi indicativo, según PSK Reporter (pskreporter.info).
 * Normas de PSK Reporter: como mucho una consulta cada 5 minutos y con un correo de contacto (appcontact).
 * Se pide siempre lo de las últimas 6 horas y el periodo y la banda se filtran aquí, sin volver a preguntar.
 */
public final class VentanaQuienMeOye extends JFrame {

    private static final long CADA_MS = 5 * 60_000L;   // PSK Reporter: no más de una consulta cada 5 minutos
    private static final String CONTACTO = "info@bitacora26.es";
    private static final Color FONDO = new Color(20, 28, 40), CAMPO = new Color(14, 20, 30), TEXTO = new Color(225, 232, 240),
            AMBAR = new Color(255, 183, 51), LINEA = new Color(60, 78, 104), GRIS = new Color(150, 165, 185);

    private static VentanaQuienMeOye abierta;
    // Lo último que ha contestado PSK Reporter (compartido, para no preguntar de más aunque se cierre y abra la ventana)
    private static long ultimaConsulta = 0;
    private static String ultimoIndicativo = "";
    private static List<Escucha> ultimas = new ArrayList<>();

    private record Escucha(long t, String call, String locator, long hz, int snr, String modo) { }

    /** Abre (o trae al frente) la ventana. */
    public static void abrir(Window padre, String miIndicativo, String miGrid,
                             java.util.function.BiPredicate<String, Boolean> elegir,
                             java.util.function.Supplier<java.util.Set<String>> oidos) {
        accionElegir = elegir;
        oidosEnBanda = oidos;
        if (abierta != null && abierta.isDisplayable()) { abierta.toFront(); return; }
        abierta = new VentanaQuienMeOye(miIndicativo == null ? "" : miIndicativo.trim().toUpperCase(), miGrid == null ? "" : miGrid.trim());
        abierta.setLocationRelativeTo(padre);
        abierta.setVisible(true);
    }

    /** Elige la estación en la ventana de digitales (indicativo, responder). Devuelve false si no se la ha oído en la banda. */
    private static java.util.function.BiPredicate<String, Boolean> accionElegir;
    /** Indicativos que se han oído transmitir en la banda (se les puede responder). */
    private static java.util.function.Supplier<java.util.Set<String>> oidosEnBanda;

    private final String yo, miGrid;
    private final DefaultTableModel modelo = new DefaultTableModel(
            new Object[]{"Hora UTC", "Indicativo", "País", "Locator", "Distancia", "dB", "Banda", "Modo", "¿Emite?"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable tabla = new JTable(modelo);
    private final MapaWspr mapa = new MapaWspr();
    private final JLabel lblResumen = new JLabel(" "), lblAviso = new JLabel(" "), lblPie = new JLabel(" ");
    private final JComboBox<String> cbPeriodo = new JComboBox<>(new String[]{"Últimos 30 minutos", "Última hora", "Últimas 6 horas"});
    private final JComboBox<String> cbBanda = new JComboBox<>(new String[]{"Todas las bandas"});
    private final JButton bActualizar = new JButton("Actualizar");
    private final javax.swing.Timer reloj;

    private VentanaQuienMeOye(String yo, String miGrid) {
        super("¿Quién me oye? · Bitácora 26");
        this.yo = yo;
        this.miGrid = miGrid;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel raiz = new JPanel(new BorderLayout(10, 10));
        raiz.setBackground(FONDO);
        raiz.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));

        JLabel titulo = new JLabel("¿QUIÉN ME OYE?  ·  " + (yo.isEmpty() ? "sin indicativo" : yo) + " en FT8 / FT4");
        titulo.setFont(new Font("Dialog", Font.BOLD, 18));
        titulo.setForeground(AMBAR);
        lblResumen.setForeground(TEXTO);
        JPanel tt = new JPanel(new GridLayout(2, 1, 0, 4));
        tt.setOpaque(false);
        tt.add(titulo);
        tt.add(lblResumen);

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        botones.setOpaque(false);
        botones.add(cbPeriodo);
        botones.add(cbBanda);
        botones.add(bActualizar);
        lblAviso.setForeground(GRIS);
        lblAviso.setFont(new Font("Dialog", Font.PLAIN, 11));
        lblAviso.setHorizontalAlignment(SwingConstants.RIGHT);
        JPanel der = new JPanel(new BorderLayout(0, 4));
        der.setOpaque(false);
        der.add(botones, BorderLayout.NORTH);
        der.add(lblAviso, BorderLayout.SOUTH);   // debajo de «Actualizar»: cada cuánto se actualiza la lista

        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        arriba.add(tt, BorderLayout.WEST);
        arriba.add(der, BorderLayout.EAST);
        raiz.add(arriba, BorderLayout.NORTH);

        tabla.setRowHeight(24);
        tabla.setAutoCreateRowSorter(true);
        tabla.setBackground(CAMPO);
        tabla.setForeground(TEXTO);
        tabla.setGridColor(LINEA);
        tabla.setFont(new Font("Dialog", Font.BOLD, 13));
        tabla.getTableHeader().setBackground(FONDO);
        tabla.getTableHeader().setForeground(AMBAR);
        javax.swing.table.DefaultTableCellRenderer ambar = new javax.swing.table.DefaultTableCellRenderer();
        ambar.setForeground(AMBAR);
        tabla.getColumnModel().getColumn(1).setCellRenderer(ambar);
        tabla.getColumnModel().getColumn(2).setPreferredWidth(150);
        javax.swing.table.DefaultTableCellRenderer emite = new javax.swing.table.DefaultTableCellRenderer();
        emite.setHorizontalAlignment(SwingConstants.CENTER);
        emite.setForeground(new Color(90, 220, 110));
        emite.setFont(emite.getFont().deriveFont(Font.BOLD));
        tabla.getColumnModel().getColumn(8).setCellRenderer(emite);
        tabla.getColumnModel().getColumn(8).setHeaderValue("<html><center>¿Emite?</center></html>");
        tabla.getTableHeader().setToolTipText("¿Emite?: SÍ = se le ha oído transmitir en la banda y puedes responderle");
        tabla.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foco, int fila, int col) {
                Object[] pb = v instanceof Object[] o ? o : null;
                super.getTableCellRendererComponent(tb, pb == null ? v : pb[0], sel, foco, fila, col);
                setIcon(pb == null ? null : (Icon) pb[1]);
                return this;
            }
        });
        // tabla ↔ mapa: al pinchar en uno se marca en el otro
        tabla.getSelectionModel().addListSelectionListener(e -> {
            int f = tabla.getSelectedRow();
            mapa.seleccionar(f < 0 ? null : (String) tabla.getValueAt(f, 1));
        });
        tabla.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) menu(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) menu(e); }
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && f >= 0) elegir((String) tabla.getValueAt(f, 1), false);
            }
            private void menu(java.awt.event.MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                if (f < 0) return;
                tabla.setRowSelectionInterval(f, f);
                menuEstacion((String) tabla.getValueAt(f, 1)).show(tabla, e.getX(), e.getY());
            }
        });
        mapa.alMenu = (call, pt) -> menuEstacion(call).show(mapa, pt.x, pt.y);
        mapa.alPulsar = call -> {
            tabla.clearSelection();
            if (call == null) return;
            for (int i = 0; i < tabla.getRowCount(); i++)
                if (call.equals(tabla.getValueAt(i, 1))) { tabla.setRowSelectionInterval(i, i); tabla.scrollRectToVisible(tabla.getCellRect(i, 0, true)); break; }
        };
        JScrollPane sp = new JScrollPane(tabla);
        sp.getViewport().setBackground(CAMPO);
        sp.setPreferredSize(new Dimension(640, 470));
        mapa.setCasa(VentanaWspr.latLon(miGrid), yo.isEmpty() ? "YO" : yo);
        mapa.setPreferredSize(new Dimension(640, 470));
        JPanel centro = new JPanel(new BorderLayout(10, 0));
        centro.setOpaque(false);
        centro.add(sp, BorderLayout.WEST);
        centro.add(mapa, BorderLayout.CENTER);
        raiz.add(centro, BorderLayout.CENTER);

        lblPie.setForeground(TEXTO);
        lblPie.setText("Datos de PSK Reporter (pskreporter.info)");
        raiz.add(lblPie, BorderLayout.SOUTH);
        setContentPane(raiz);
        pack();

        cbPeriodo.addActionListener(e -> pintar());
        cbBanda.addActionListener(e -> { if (!rellenandoBandas) pintar(); });
        bActualizar.addActionListener(e -> consultar(true));
        reloj = new javax.swing.Timer(1_000, e -> { if (!consultando) avisoProxima(); consultar(false); marcarEmisores(); });   // cuenta atrás cada segundo
        reloj.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { reloj.stop(); }
        });
        if (!ultimas.isEmpty() && ultimoIndicativo.equals(yo)) pintar();
        consultar(false);
    }

    private JPopupMenu menuEstacion(String call) {
        JPopupMenu m = new JPopupMenu();
        JMenuItem q = new JMenuItem("Ver " + call + " en QRZ.com");
        q.addActionListener(a -> {
            try { Desktop.getDesktop().browse(new java.net.URI("https://www.qrz.com/db/" + java.net.URLEncoder.encode(call, java.nio.charset.StandardCharsets.UTF_8))); }
            catch (Exception ex) { lblAviso.setText("No se ha podido abrir QRZ.com"); }
        });
        JMenuItem sel = new JMenuItem("Seleccionar contacto");
        sel.addActionListener(a -> elegir(call, false));
        JMenuItem resp = new JMenuItem("Responder");
        resp.addActionListener(a -> elegir(call, true));
        m.add(q); m.add(sel); m.add(resp);
        return m;
    }

    private void elegir(String call, boolean responder) {
        if (accionElegir != null && accionElegir.test(call, responder)) return;
        JOptionPane.showMessageDialog(this, "<html>" + call + " te oye, pero todavía no se le ha oído transmitir en la banda.<br>"
                + "Cuando aparezca en «Actividad en la banda» podrás seleccionarlo o responderle.</html>",
                "¿Quién me oye?", JOptionPane.INFORMATION_MESSAGE);
    }

    private int cuentaEmisores = 0;

    /** Columna «¿Emite?»: SÍ si la ventana de digitales le ha oído transmitir (se mira cada 5 s). */
    private void marcarEmisores() {
        if (++cuentaEmisores % 5 != 0 && cuentaEmisores != 1) return;
        java.util.Set<String> oidos = oidosEnBanda == null ? java.util.Set.of() : oidosEnBanda.get();
        for (int i = 0; i < modelo.getRowCount(); i++) {
            String v = oidos.contains(String.valueOf(modelo.getValueAt(i, 1)).toUpperCase()) ? "SÍ" : "";
            if (!v.equals(modelo.getValueAt(i, 8))) modelo.setValueAt(v, i, 8);
        }
    }

    private boolean consultando = false;

    /** Pregunta a PSK Reporter si han pasado 5 minutos desde la última vez (si no, se queda con lo que ya hay). */
    private void consultar(boolean pulsado) {
        long falta = ultimaConsulta + CADA_MS - System.currentTimeMillis();
        if (yo.length() < 3) { lblResumen.setText("Pon tu indicativo en Mi Estación para saber quién te oye."); return; }
        if (consultando) return;
        if (falta > 0 && ultimoIndicativo.equals(yo)) {
            avisoProxima();
            return;
        }
        consultando = true;
        bActualizar.setEnabled(false);
        lblAviso.setText("Actualizando…");
        ultimaConsulta = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            List<Escucha> r = null;
            String error = null;
            try { r = pedir(yo); } catch (Exception ex) { error = ex.getMessage(); }
            final List<Escucha> res = r;
            final String err = error;
            SwingUtilities.invokeLater(() -> {
                consultando = false;
                bActualizar.setEnabled(true);
                if (res != null) { ultimas = res; ultimoIndicativo = yo; pintar(); avisoProxima(); }
                else lblAviso.setText("Sin respuesta de PSK Reporter (" + (err == null ? "?" : err) + "). Se volverá a probar en 5 minutos.");
            });
        }, "PskReporter");
        t.setDaemon(true);
        t.start();
    }

    private void avisoProxima() {
        long falta = Math.max(0, ultimaConsulta + CADA_MS - System.currentTimeMillis());
        if (lblAviso.getText().startsWith("Sin respuesta")) return;   // se deja el aviso de error a la vista
        long seg = (falta + 999) / 1000;
        lblAviso.setText(String.format("Próxima actualización en %d:%02d", seg / 60, seg % 60));
    }

    /** Escuchas de mi indicativo en las últimas 6 horas (lo más que da PSK Reporter). */
    private static List<Escucha> pedir(String call) throws Exception {
        String url = "https://retrieve.pskreporter.info/query?senderCallsign=" + java.net.URLEncoder.encode(call, java.nio.charset.StandardCharsets.UTF_8)
                + "&flowStartSeconds=-21600&rronly=1&appcontact=" + java.net.URLEncoder.encode(CONTACTO, java.nio.charset.StandardCharsets.UTF_8);
        java.net.http.HttpClient cli = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
        java.net.http.HttpRequest rq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(40)).header("User-Agent", "Bitacora26").GET().build();
        java.net.http.HttpResponse<String> rs = cli.send(rq, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (rs.statusCode() != 200) throw new Exception("código " + rs.statusCode());
        return leerXml(rs.body());
    }

    /** Las líneas &lt;receptionReport … /&gt; de la respuesta. */
    static List<Escucha> leerXml(String xml) {
        List<Escucha> l = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<receptionReport\\s([^>]*)/?>").matcher(xml);
        while (m.find()) {
            String a = m.group(1);
            String call = atr(a, "receiverCallsign").toUpperCase(Locale.ROOT);
            if (call.isEmpty()) continue;
            long t = 0, hz = 0;
            int snr = 0;
            try { t = Long.parseLong(atr(a, "flowStartSeconds")) * 1000; } catch (Exception ignored) { }
            try { hz = Long.parseLong(atr(a, "frequency")); } catch (Exception ignored) { }
            try { snr = Integer.parseInt(atr(a, "sNR")); } catch (Exception ignored) { }
            l.add(new Escucha(t, call, atr(a, "receiverLocator").toUpperCase(Locale.ROOT), hz, snr, atr(a, "mode").toUpperCase(Locale.ROOT)));
        }
        l.sort((x, y) -> Long.compare(y.t, x.t));
        return l;
    }

    private static String atr(String a, String nombre) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b" + nombre + "=\"([^\"]*)\"").matcher(a);
        return m.find() ? m.group(1).trim() : "";
    }

    static String banda(long hz) {
        long[][] b = {{1_800_000, 2_000_000}, {3_500_000, 4_000_000}, {5_250_000, 5_450_000}, {7_000_000, 7_300_000}, {10_100_000, 10_150_000},
                {14_000_000, 14_350_000}, {18_068_000, 18_168_000}, {21_000_000, 21_450_000}, {24_890_000, 24_990_000}, {26_000_000, 27_999_999},
                {28_000_000, 29_700_000}, {50_000_000, 54_000_000}, {144_000_000, 148_000_000}, {430_000_000, 440_000_000}};
        String[] n = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "CB", "10m", "6m", "2m", "70cm"};
        for (int i = 0; i < b.length; i++) if (hz >= b[i][0] && hz <= b[i][1]) return n[i];
        return "";
    }

    private boolean rellenandoBandas = false;

    /** Pinta la lista y el mapa con el periodo y la banda elegidos (sin volver a preguntar a PSK Reporter). */
    private void pintar() {
        long desde = System.currentTimeMillis() - switch (cbPeriodo.getSelectedIndex()) { case 0 -> 30L; case 1 -> 60L; default -> 360L; } * 60_000L;
        // bandas que hay en lo recibido
        String bSel = (String) cbBanda.getSelectedItem();
        java.util.TreeSet<String> bandas = new java.util.TreeSet<>();
        for (Escucha e : ultimas) { String b = banda(e.hz); if (!b.isEmpty()) bandas.add(b); }
        rellenandoBandas = true;
        cbBanda.removeAllItems();
        cbBanda.addItem("Todas las bandas");
        for (String b : bandas) cbBanda.addItem(b);
        cbBanda.setSelectedItem(bSel != null && (bandas.contains(bSel)) ? bSel : "Todas las bandas");
        rellenandoBandas = false;
        String banda = cbBanda.getSelectedIndex() <= 0 ? "" : (String) cbBanda.getSelectedItem();

        modelo.setRowCount(0);
        double[] casa = VentanaWspr.latLon(miGrid);
        List<String[]> puntos = new ArrayList<>();
        java.util.Set<String> vistos = new java.util.HashSet<>();
        String lejano = "";
        double maxKm = -1;
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
        for (Escucha e : ultimas) {
            if (e.t < desde) continue;
            if (!banda.isEmpty() && !banda.equals(banda(e.hz))) continue;
            if (!vistos.add(e.call + "|" + banda(e.hz))) continue;   // cada estación una vez por banda (la última)
            double[] p = VentanaWspr.latLon(e.locator);
            double km = casa != null && p != null ? VentanaWspr.km(casa, p) : -1;
            if (km > maxKm) { maxKm = km; lejano = e.call; }
            modelo.addRow(new Object[]{hhmm.format(Instant.ofEpochMilli(e.t)), e.call, VentanaWspr.pais(e.call),
                    e.locator.length() >= 4 ? e.locator.substring(0, Math.min(6, e.locator.length())) : e.locator,
                    km < 0 ? "" : String.format(Locale.GERMANY, "%,d km", Math.round(km)),
                    String.format("%+d", e.snr), banda(e.hz), e.modo, ""});
            if (!e.locator.isEmpty()) { Object pa = VentanaWspr.pais(e.call); puntos.add(new String[]{e.locator, e.call, String.valueOf(pa instanceof Object[] o ? o[0] : pa)}); }
        }
        mapa.ponerMeOyenPorPais(puntos);
        int n = modelo.getRowCount();
        String per = ((String) cbPeriodo.getSelectedItem()).toLowerCase(Locale.ROOT);
        lblResumen.setText(n == 0 ? "Nadie te ha oído en " + per.replace("últimos", "los últimos").replace("última", "la última").replace("últimas", "las últimas") + "."
                : (n == 1 ? "1 estación te ha oído" : n + " estaciones te han oído") + " · "
                + per + (maxKm > 0 ? "  ·  la más lejana: " + lejano + " a " + String.format(Locale.GERMANY, "%,d km", Math.round(maxKm)) : ""));
        cuentaEmisores = 0;
        marcarEmisores();
    }
}
