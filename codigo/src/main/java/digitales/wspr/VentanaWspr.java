package digitales.wspr;

import digitales.MotorDigitales;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Ventana de WSPR (balizas): escucha la tarjeta de sonido y, cada 2 minutos (minutos pares), descodifica las balizas.
 * Va aparte de DIGITALES: si se borra la carpeta wspr, el botón WSPR de DIGITALES vuelve a gris y nada más cambia.
 */
public final class VentanaWspr extends JFrame {

    private static VentanaWspr abierta;

    /** Abre (o trae al frente) la ventana. miGrid: el locator de la estación, para la distancia. */
    public static void abrir(Window padre, String miGrid, String entrada,
                             java.util.function.BiFunction<String, String, String> leerCfg, java.util.function.BiConsumer<String, String> guardarCfg) {
        abrir(padre, miGrid, entrada, leerCfg, guardarCfg, null);
    }

    /** Igual, con la orden para poner la emisora (por CAT) en una frecuencia de dial en Hz. */
    public static void abrir(Window padre, String miGrid, String entrada,
                             java.util.function.BiFunction<String, String, String> leerCfg, java.util.function.BiConsumer<String, String> guardarCfg,
                             java.util.function.LongConsumer sintonizar) {
        abrir(padre, miGrid, entrada, leerCfg, guardarCfg, sintonizar, "", null, "");
    }

    /** Igual, con mi indicativo, el PTT y la salida de sonido de DIGITALES (para transmitir). */
    public static void abrir(Window padre, String miGrid, String entrada,
                             java.util.function.BiFunction<String, String, String> leerCfg, java.util.function.BiConsumer<String, String> guardarCfg,
                             java.util.function.LongConsumer sintonizar, String miIndicativo, digitales.EmisorDigitales.Ptt ptt, String salida) {
        SINTONIZAR = sintonizar;
        MI_INDICATIVO = miIndicativo == null ? "" : miIndicativo.trim().toUpperCase();
        PTT = ptt;
        SALIDA = salida == null ? "" : salida;
        if (abierta != null && abierta.isDisplayable()) { abierta.toFront(); return; }
        LEER = leerCfg; GUARDAR = guardarCfg; ENTRADA = entrada == null ? "" : entrada;
        abierta = new VentanaWspr(miGrid);
        abierta.setLocationRelativeTo(padre);
        abierta.setVisible(true);
    }

    // Frecuencias de dial estándar de WSPR (USB): la baliza está entre 1400 y 1600 Hz de audio
    private static final String[][] BANDAS = {
            {"160m", "1.836600"}, {"80m", "3.568600"}, {"60m", "5.287200"}, {"40m", "7.038600"}, {"30m", "10.138700"},
            {"20m", "14.095600"}, {"17m", "18.104600"}, {"15m", "21.094600"}, {"12m", "24.924600"}, {"10m", "28.124600"},
            {"6m", "50.293000"}, {"2m", "144.489000"}};

    private static final Color FONDO = new Color(20, 28, 40), TEXTO = new Color(225, 232, 240), AMBAR = new Color(255, 183, 51);

    private final String miGrid;
    private final JComboBox<String> cbBanda = new JComboBox<>();
    private final JButton bEscuchar = new JButton("ESCUCHAR");
    private final JLabel lblEstado = new JLabel(" ");
    // Récords: la baliza más lejana que oigo y la estación más lejana que me oye
    private final JLabel lblRecord = new JLabel(" ");
    private long oigoKm = -1, meOyeKm = -1;
    private String oigoQuien = "", meOyeQuien = "";
    private final JProgressBar barra = new JProgressBar(0, 120);
    private final DefaultTableModel modelo = new DefaultTableModel(
            new Object[]{"UTC", "dB", "DT", "Frecuencia (MHz)", "Deriva", "Indicativo", "Locator", "País", "Potencia", "Distancia"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };

    private volatile boolean escuchando = false;
    private volatile MotorDigitales.Fuente fuente;
    private Thread hilo;
    private final javax.swing.Timer reloj;

    private VentanaWspr(String miGrid) {
        super("WSPR · balizas · Bitácora 26");
        this.miGrid = miGrid == null ? "" : miGrid.trim().toUpperCase();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel raiz = new JPanel(new BorderLayout(8, 8));
        raiz.setBackground(FONDO);
        raiz.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel arriba = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        arriba.setOpaque(false);
        JLabel lb = new JLabel("Banda:");
        lb.setForeground(TEXTO);
        arriba.add(lb);
        for (String[] b : BANDAS) cbBanda.addItem(b[0] + "   (dial " + b[1] + " USB)");
        String guardada = leer("WSPR_BANDA", "20m");
        for (int i = 0; i < BANDAS.length; i++) if (BANDAS[i][0].equals(guardada)) cbBanda.setSelectedIndex(i);
        cbBanda.addActionListener(e -> { guardar("WSPR_BANDA", BANDAS[cbBanda.getSelectedIndex()][0]); sintonizar(); });
        cbBanda.setToolTipText("Con el CAT conectado, la emisora se pone sola en esa frecuencia (USB). Las balizas suenan entre 1400 y 1600 Hz.");
        arriba.add(cbBanda);
        bEscuchar.setFont(bEscuchar.getFont().deriveFont(Font.BOLD, 13f));
        bEscuchar.addActionListener(e -> { if (escuchando) parar(); else arrancar(); });
        arriba.add(bEscuchar);
        barra.setStringPainted(true);
        barra.setPreferredSize(new Dimension(230, 22));
        arriba.add(barra);
        JButton bBorrar = new JButton("Borrar lista");
        bBorrar.addActionListener(e -> modelo.setRowCount(0));
        arriba.add(bBorrar);
        raiz.add(arriba, BorderLayout.NORTH);

        JTable t = new JTable(modelo);
        t.setAutoCreateRowSorter(true);
        t.setRowHeight(22);
        t.getColumnModel().getColumn(7).setPreferredWidth(170);
        t.getColumnModel().getColumn(7).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foco, int fila, int col) {
                Object[] pb = v instanceof Object[] o ? o : null;
                super.getTableCellRendererComponent(tb, pb == null ? v : pb[0], sel, foco, fila, col);
                setIcon(pb == null ? null : (Icon) pb[1]);
                return this;
            }
        });
        final boolean[] sincronizando = {false};
        t.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || sincronizando[0]) return;
            int v = t.getSelectedRow();
            mapa.seleccionar(v < 0 ? null : String.valueOf(modelo.getValueAt(t.convertRowIndexToModel(v), 5)));
        });
        mapa.alPulsar = call -> {
            sincronizando[0] = true;
            try {
                t.clearSelection();
                if (call != null)
                    for (int m = 0; m < modelo.getRowCount(); m++)
                        if (call.equals(String.valueOf(modelo.getValueAt(m, 5)))) {
                            int v = t.convertRowIndexToView(m);
                            if (v >= 0) { t.setRowSelectionInterval(v, v); t.scrollRectToVisible(t.getCellRect(v, 0, true)); }
                            break;
                        }
            } finally { sincronizando[0] = false; }
        };
        mapa.setCasa(latLon(miGrid), MI_INDICATIVO.isEmpty() ? "YO" : MI_INDICATIVO);
        JSplitPane division = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(t), mapa);
        division.setResizeWeight(0.55);
        division.setBorder(null);
        raiz.add(division, BorderLayout.CENTER);

        JPanel abajo = new JPanel(new BorderLayout());
        abajo.setOpaque(false);
        JLabel nota = new JLabel("<html>Cada 2 minutos (minutos pares) se descodifican las balizas oídas. Usa la tarjeta de sonido de DIGITALES; "
                + "si DIGITALES está escuchando, se usa su mismo audio.<br>WSPR basado en WSJT-X (K1JT, K9AN y colaboradores).</html>");
        nota.setForeground(new Color(150, 168, 190));
        nota.setFont(nota.getFont().deriveFont(11f));
        abajo.add(panelTx(), BorderLayout.NORTH);
        abajo.add(nota, BorderLayout.CENTER);
        lblEstado.setForeground(TEXTO);
        lblRecord.setForeground(new Color(255, 178, 30));
        lblRecord.setFont(lblRecord.getFont().deriveFont(Font.BOLD, 13f));
        JPanel pie = new JPanel(new GridLayout(2, 1));
        pie.setOpaque(false);
        pie.add(lblRecord);
        pie.add(lblEstado);
        abajo.add(pie, BorderLayout.SOUTH);
        pintarRecord();
        raiz.add(abajo, BorderLayout.SOUTH);
        setContentPane(raiz);
        setSize(1450, 650);

        reloj = new javax.swing.Timer(1000, e -> {
            comprobarTx();
            long seg = System.currentTimeMillis() / 1000;
            if (chkTx.isSelected() && seg - ultimaConsultaMapa >= 300) { ultimaConsultaMapa = seg; actualizarMapaOyentes(); }
            long s = (System.currentTimeMillis() / 1000) % 120;
            barra.setValue((int) s);
            barra.setString(escuchando ? (s < 114 ? "Recibiendo " + s + " de 114 s" : "Descodificando…") : "Parado");
        });
        reloj.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { cortarTx = true; chkTx.setSelected(false); parar(); reloj.stop(); }
        });
    }

    private static java.util.function.BiFunction<String, String, String> LEER;
    private static java.util.function.BiConsumer<String, String> GUARDAR;
    private static String ENTRADA = "";
    private static java.util.function.LongConsumer SINTONIZAR;
    private static String MI_INDICATIVO = "", SALIDA = "";
    private static digitales.EmisorDigitales.Ptt PTT;

    private static String leer(String clave, String def) {
        try { return LEER == null ? def : LEER.apply(clave, def); } catch (Throwable e) { return def; }
    }

    private static void guardar(String clave, String valor) {
        try { if (GUARDAR != null) GUARDAR.accept(clave, valor); } catch (Throwable ignored) { }
    }

    /** Pone la emisora (con CAT) en el dial de WSPR de la banda elegida, en USB, como hace DIGITALES. */
    private void sintonizar() {
        java.util.function.LongConsumer s = SINTONIZAR;
        if (s == null) return;
        long hz = Math.round(Double.parseDouble(BANDAS[cbBanda.getSelectedIndex()][1]) * 1e6);
        try { s.accept(hz); } catch (Throwable ignored) { }
    }

    // ======================================================================= transmitir baliza

    private static final int[] DBM = {0, 3, 7, 10, 13, 17, 20, 23, 27, 30, 33, 37, 40, 43, 47, 50, 53, 57, 60};
    private final JCheckBox chkTx = new JCheckBox("Transmitir mi baliza");
    private final JTextField txtCall = new JTextField(8), txtGrid = new JTextField(4), txtHz = new JTextField(5);
    private final JComboBox<String> cbDbm = new JComboBox<>(), cbPct = new JComboBox<>(new String[]{"20 %", "25 %", "33 %", "50 %"});
    private final JSlider sNivel = new JSlider(-60, 0, -16);
    private final JLabel lblTx = new JLabel(" ");
    private volatile boolean cortarTx = false, transmitiendo = false;
    private long ultimoTurnoTx = -1;
    private final java.util.Random azar = new java.util.Random();

    private JPanel panelTx() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(51, 68, 92)), "TRANSMITIR",
                0, 0, null, AMBAR));
        chkTx.setOpaque(false);
        chkTx.setForeground(TEXTO);
        txtCall.setText(leer("WSPR_INDICATIVO", MI_INDICATIVO));
        String g = miGrid.length() >= 4 ? miGrid.substring(0, 4) : miGrid;
        txtGrid.setText(leer("WSPR_LOCATOR", g));
        for (int d : DBM) cbDbm.addItem(d + " dBm (" + vatios(d) + ")");
        cbDbm.setSelectedIndex(java.util.Arrays.binarySearch(DBM, Integer.parseInt(leer("WSPR_DBM", "37"))) < 0 ? 11
                : java.util.Arrays.binarySearch(DBM, Integer.parseInt(leer("WSPR_DBM", "37"))));
        cbPct.setSelectedItem(leer("WSPR_PCT", "20") + " %");
        txtHz.setText(leer("WSPR_HZ", String.valueOf(1420 + azar.nextInt(160))));
        sNivel.setValue(Integer.parseInt(leer("WSPR_NIVEL", "-16")));
        sNivel.setPreferredSize(new Dimension(110, 22));
        sNivel.setOpaque(false);
        chkTx.addActionListener(e -> {
            if (chkTx.isSelected()) {
                String m = mensajeTx();
                if (m == null || Wspr.tonos(m) == null) {
                    JOptionPane.showMessageDialog(this, "Revisa el indicativo (hasta 6 caracteres) y el locator (4 caracteres, p. ej. IN80).",
                            "WSPR", JOptionPane.WARNING_MESSAGE);
                    chkTx.setSelected(false);
                    return;
                }
                if (PTT == null) {
                    JOptionPane.showMessageDialog(this, "Abre WSPR desde la ventana DIGITALES para poder transmitir.", "WSPR", JOptionPane.WARNING_MESSAGE);
                    chkTx.setSelected(false);
                    return;
                }
                if (JOptionPane.showConfirmDialog(this, "Se transmitirá «" + m + "» en el " + cbPct.getSelectedItem()
                                + " de los turnos de 2 minutos (cada vez casi 2 minutos seguidos).\n"
                                + "Comprueba la potencia de la emisora: WSPR es una baliza de poca potencia.\n\n¿Empezar a transmitir?",
                        "WSPR", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                    chkTx.setSelected(false);
                    return;
                }
                guardar("WSPR_INDICATIVO", txtCall.getText().trim().toUpperCase());
                guardar("WSPR_LOCATOR", txtGrid.getText().trim().toUpperCase());
                guardar("WSPR_DBM", String.valueOf(DBM[cbDbm.getSelectedIndex()]));
                guardar("WSPR_PCT", ((String) cbPct.getSelectedItem()).replace(" %", ""));
                guardar("WSPR_HZ", txtHz.getText().trim());
                lblTx.setText("  Baliza preparada: transmitirá en algún turno de los próximos.");
            } else {
                cortarTx = true;
                lblTx.setText("  Sin transmitir.");
            }
        });
        sNivel.addChangeListener(e -> guardar("WSPR_NIVEL", String.valueOf(sNivel.getValue())));
        JButton bParar = new JButton("PARAR TX");
        bParar.setForeground(new Color(180, 30, 30));
        bParar.addActionListener(e -> { cortarTx = true; chkTx.setSelected(false); lblTx.setText("  Transmisión parada."); });
        p.add(chkTx);
        p.add(etiqueta("Indicativo:")); p.add(txtCall);
        p.add(etiqueta("Locator:")); p.add(txtGrid);
        p.add(etiqueta("Potencia:")); p.add(cbDbm);
        p.add(etiqueta("Turnos:")); p.add(cbPct);
        p.add(etiqueta("Audio Hz:")); p.add(txtHz);
        p.add(etiqueta("Nivel:")); p.add(sNivel);
        p.add(bParar);
        JButton bOyen = new JButton("¿QUIÉN ME OYE?");
        bOyen.addActionListener(e -> quienMeOye());
        p.add(bOyen);
        lblTx.setForeground(AMBAR);
        p.add(lblTx);
        p.setPreferredSize(new Dimension(940, 92));
        return p;
    }

    private final MapaWspr mapa = new MapaWspr();
    private long ultimaConsultaMapa = 0;

    /** En segundo plano: quién me oye (últimas 24 h) y lo pinta en el mapa. */
    private void actualizarMapaOyentes() {
        String call = txtCall.getText().trim().toUpperCase();
        if (!call.matches("[A-Z0-9/]{3,12}")) return;
        Thread th = new Thread(() -> {
            try {
                List<String[]> f = consultarOyentes(call);
                List<String[]> pm = new java.util.ArrayList<>();
                for (String[] c : f) pm.add(new String[]{c[3], c[2]});
                SwingUtilities.invokeLater(() -> { mapa.ponerMeOyen(pm); recordMeOyen(f); });
            } catch (Exception ignored) { }
        }, "WSPR-mapa");
        th.setDaemon(true);
        th.start();
    }

    /** Filas de wspr.live: time, frequency, rx_sign, rx_loc, distance, snr, power. */
    private static List<String[]> consultarOyentes(String call) throws Exception {
        String sql = "SELECT time, frequency, rx_sign, rx_loc, distance, snr, power FROM wspr.rx WHERE tx_sign = '"
                + call + "' AND time > subtractHours(now(), 24) ORDER BY time DESC LIMIT 500 FORMAT TabSeparated";
        java.net.http.HttpClient cli = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
        java.net.http.HttpRequest rq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "https://db1.wspr.live/?query=" + java.net.URLEncoder.encode(sql, java.nio.charset.StandardCharsets.UTF_8)))
                .timeout(java.time.Duration.ofSeconds(30)).header("User-Agent", "Bitacora26").GET().build();
        java.net.http.HttpResponse<String> rs = cli.send(rq, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (rs.statusCode() != 200) throw new Exception("respuesta " + rs.statusCode());
        List<String[]> out = new java.util.ArrayList<>();
        for (String lin : rs.body().split("\n")) {
            String[] c = lin.split("\t");
            if (c.length >= 7) out.add(c);
        }
        return out;
    }

    /** Pide a wspr.live (base de datos pública de wsprnet) quién ha oído mi baliza en las últimas 24 horas. */
    private void quienMeOye() {
        String call = txtCall.getText().trim().toUpperCase();
        if (!call.matches("[A-Z0-9/]{3,12}")) {
            JOptionPane.showMessageDialog(this, "Escribe tu indicativo en la casilla Indicativo.", "WSPR", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JDialog d = new JDialog(this, "Quién ha oído a " + call + " (últimas 24 horas)", false);
        DefaultTableModel m = new DefaultTableModel(new Object[]{"UTC", "Frecuencia (MHz)", "Le oyó", "Locator", "País", "Distancia", "dB", "Potencia"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable t = new JTable(m);
        t.setRowHeight(22);
        t.setAutoCreateRowSorter(true);
        t.getColumnModel().getColumn(4).setPreferredWidth(170);
        t.getColumnModel().getColumn(4).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean foco, int fila, int col) {
                Object[] pb = v instanceof Object[] o ? o : null;
                super.getTableCellRendererComponent(tb, pb == null ? v : pb[0], sel, foco, fila, col);
                setIcon(pb == null ? null : (Icon) pb[1]);
                return this;
            }
        });
        JLabel est = new JLabel("  Consultando wsprnet…");
        JButton bAct = new JButton("ACTUALIZAR");
        JPanel sur = new JPanel(new BorderLayout());
        sur.add(est, BorderLayout.CENTER);
        sur.add(bAct, BorderLayout.EAST);
        JLabel fuenteDatos = new JLabel("  Datos de wsprnet.org a través de wspr.live (pueden tardar unos minutos en aparecer).");
        fuenteDatos.setFont(fuenteDatos.getFont().deriveFont(11f));
        sur.add(fuenteDatos, BorderLayout.SOUTH);
        d.add(new JScrollPane(t), BorderLayout.CENTER);
        d.add(sur, BorderLayout.SOUTH);
        Runnable cargar = () -> {
            est.setText("  Consultando wsprnet…");
            bAct.setEnabled(false);
            new Thread(() -> {
                java.util.List<Object[]> filas = new java.util.ArrayList<>();
                String error = null;
                try {
                    List<String[]> pm = new java.util.ArrayList<>();
                    List<String[]> oyentes = consultarOyentes(call);
                    for (String[] c : oyentes) {
                        pm.add(new String[]{c[3], c[2]});
                        String hora = c[0].length() >= 16 ? c[0].substring(5, 16).replace('-', '/') : c[0];
                        double mhz = Double.parseDouble(c[1]) / 1e6;
                        int dbm = Integer.parseInt(c[6]);
                        filas.add(new Object[]{hora, String.format(java.util.Locale.ROOT, "%.6f", mhz), c[2], c[3], pais(c[2]),
                                c[4] + " km", Integer.parseInt(c[5]), dbm + " dBm (" + vatios(dbm) + ")"});
                    }
                    SwingUtilities.invokeLater(() -> { mapa.ponerMeOyen(pm); recordMeOyen(oyentes); });
                } catch (Exception ex) {
                    error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                }
                final String err = error;
                SwingUtilities.invokeLater(() -> {
                    m.setRowCount(0);
                    for (Object[] f : filas) m.addRow(f);
                    int maxKm = 0;
                    for (Object[] f : filas) try { maxKm = Math.max(maxKm, Integer.parseInt(((String) f[5]).replace(" km", ""))); } catch (Exception ignored) { }
                    est.setText(err != null ? "  No se ha podido consultar wsprnet: " + err
                            : filas.isEmpty() ? "  Nadie ha informado todavía de que te oiga. Espera unos minutos y pulsa ACTUALIZAR."
                            : "  " + filas.size() + " reportes. Lo más lejos: " + maxKm + " km.");
                    bAct.setEnabled(true);
                });
            }, "WSPR-oyentes").start();
        };
        bAct.addActionListener(e -> cargar.run());
        d.setSize(900, 500);
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        cargar.run();
    }

    private static JLabel etiqueta(String t) { JLabel l = new JLabel(t); l.setForeground(TEXTO); return l; }

    private String mensajeTx() {
        String c = txtCall.getText().trim().toUpperCase(), g = txtGrid.getText().trim().toUpperCase();
        if (c.isEmpty() || g.length() != 4) return null;
        return c + " " + g + " " + DBM[cbDbm.getSelectedIndex()];
    }

    /** Cada segundo: al empezar un turno par, decide (al azar, con el % elegido) si transmite. */
    private void comprobarTx() {
        if (!chkTx.isSelected() || transmitiendo) return;
        long ahora = System.currentTimeMillis();
        long inicio = ahora - ahora % 120_000;
        if (ahora - inicio > 900 || inicio == ultimoTurnoTx) return;
        ultimoTurnoTx = inicio;
        int pct = Integer.parseInt(((String) cbPct.getSelectedItem()).replace(" %", "").trim());
        if (azar.nextInt(100) >= pct) return;
        String m = mensajeTx();
        int[] t = m == null ? null : Wspr.tonos(m);
        if (t == null) return;
        double hz;
        try { hz = Double.parseDouble(txtHz.getText().trim()); } catch (Exception e) { hz = 1500; }
        hz = Math.max(1410, Math.min(1590, hz));
        final double f0 = hz - 1.5 * Wspr.DF_TONO;
        final double db = sNivel.getValue();
        transmitiendo = true;
        cortarTx = false;
        Thread th = new Thread(() -> transmitir(inicio, t, f0, db, m), "WSPR-TX");
        th.setDaemon(true);
        th.start();
    }

    private void transmitir(long inicio, int[] tonos, double f0, double db, String m) {
        digitales.EmisorDigitales.Altavoz linea = null;
        boolean ptt = false;
        try {
            float[] a12 = Wspr.audio(tonos, f0, 1.0);
            linea = digitales.EmisorDigitales.abrirAltavoz(SALIDA);
            int fs = linea.tasa(), canales = linea.canales();
            float[] a = digitales.EmisorDigitales.remuestrear(a12, fs);
            double amp = Math.pow(10, db / 20.0) * 32000;
            PTT.poner(true);
            ptt = true;
            SwingUtilities.invokeLater(() -> lblTx.setText("  TRANSMITIENDO «" + m + "»…"));
            long espera = inicio + 1000 - System.currentTimeMillis();
            if (espera > 0) Thread.sleep(espera);
            int desde = (int) Math.max(0, Math.min(a.length, (System.currentTimeMillis() - inicio - 1000) * fs / 1000));
            int trozo = fs / 20;
            byte[] b = new byte[trozo * canales * 2];
            for (int i = desde; i < a.length && !cortarTx; i += trozo) {
                int n = Math.min(trozo, a.length - i);
                for (int k = 0; k < n; k++) {
                    int v = (int) Math.round(a[i + k] * amp);
                    v = Math.max(-32767, Math.min(32767, v));
                    for (int c = 0; c < canales; c++) {
                        // canal de salida de «Archivo → Configurar audio» (Izquierdo, Derecho o los dos)
                        String cs = digitales.EmisorDigitales.CANAL_SALIDA;
                        int vc = canales < 2 || !("IZQ".equals(cs) && c == 1 || "DCHA".equals(cs) && c == 0) ? v : 0;
                        int o = (k * canales + c) * 2; b[o] = (byte) vc; b[o + 1] = (byte) (vc >> 8);
                    }
                }
                linea.escribir(b, n * canales * 2);
            }
            linea.terminar(!cortarTx);
            linea = null;
            String hora = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(inicio));
            SwingUtilities.invokeLater(() -> lblTx.setText(cortarTx ? "  Transmisión cortada." : "  Última baliza transmitida: " + hora + " UTC."));
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> { chkTx.setSelected(false); lblTx.setText("  No se ha podido transmitir: " + ex.getMessage()); });
        } finally {
            if (linea != null) try { linea.terminar(false); } catch (Exception ignored) { }
            if (ptt) try { PTT.poner(false); } catch (Exception ignored) { }
            transmitiendo = false;
        }
    }

    private void arrancar() {
        sintonizar();
        boolean compartido = false;
        try {
            fuente = MotorDigitales.tarjeta(ENTRADA);
        } catch (Exception ex) {
            // La tarjeta la está usando DIGITALES: se usa el mismo audio que escucha DIGITALES
            fuente = MotorDigitales.audioDeDigitales();
            compartido = true;
        }
        escuchando = true;
        bEscuchar.setText("PARAR");
        lblEstado.setText(compartido ? "  Escuchando con el audio de DIGITALES (deja DIGITALES en ESCUCHAR)." : "  Escuchando. El primer resultado llega al terminar el próximo turno de 2 minutos.");
        hilo = new Thread(this::bucle, "WSPR");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void parar() {
        escuchando = false;
        bEscuchar.setText("ESCUCHAR");
        MotorDigitales.Fuente f = fuente;
        fuente = null;
        if (f != null) try { f.close(); } catch (Exception ignored) { }
    }

    /** Lee audio sin parar y, al terminar cada turno de 2 minutos, descodifica en otro hilo. */
    private void bucle() {
        int cap = 125 * Wspr.FS;
        float[] anillo = new float[cap];
        long total = 0;
        long hecho = -1;
        float[] buf = new float[2400];
        while (escuchando) {
            MotorDigitales.Fuente f = fuente;
            if (f == null) break;
            int n;
            try { n = f.leer(buf); } catch (Exception e) { break; }
            long ahora = System.currentTimeMillis();
            for (int i = 0; i < n; i++) anillo[(int) ((total + i) % cap)] = buf[i];
            total += n;
            long inicio = ahora - ahora % 120_000;   // minuto par en curso
            long seg = ahora - inicio;
            if (seg >= 115_000 && inicio != hecho) {
                hecho = inicio;
                // La última muestra es "ahora": el turno empezó hace 'seg' ms
                long muestrasDesdeInicio = seg * Wspr.FS / 1000;
                if (muestrasDesdeInicio > total) continue;   // no se ha oído el turno entero
                long primera = total - muestrasDesdeInicio;
                float[] audio = new float[114 * Wspr.FS];
                for (int i = 0; i < audio.length; i++) audio[i] = anillo[(int) ((primera + i) % cap)] / 32768f;
                final long ini = inicio;
                final String dial = BANDAS[cbBanda.getSelectedIndex()][1];
                Thread d = new Thread(() -> {
                    List<Wspr.Resultado> r = Wspr.descodificar(audio);
                    SwingUtilities.invokeLater(() -> mostrar(ini, dial, r));
                }, "WSPR-descodificar");
                d.setDaemon(true);
                d.start();
            }
        }
    }

    private void mostrar(long inicio, String dial, List<Wspr.Resultado> r) {
        String hora = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(inicio));
        double dialMhz = Double.parseDouble(dial);
        for (Wspr.Resultado x : r) {
            String dist = "";
            double[] a = latLon(miGrid), b = latLon(x.locator);
            if (a != null && b != null) {
                long k = Math.round(km(a, b));
                dist = k + " km";
                if (k > oigoKm) { oigoKm = k; oigoQuien = x.indicativo + " (" + x.locator + ")"; }
            }
            modelo.insertRow(0, new Object[]{hora, (int) Math.round(x.snr), String.format(java.util.Locale.ROOT, "%.1f", x.dt),
                    String.format(java.util.Locale.ROOT, "%.6f", dialMhz + x.audioHz / 1e6), (int) Math.round(x.deriva),
                    x.indicativo, x.locator, pais(x.indicativo), x.dbm + " dBm (" + vatios(x.dbm) + ")", dist});
            mapa.anadirOigo(x.locator, x.indicativo);
        }
        lblEstado.setText("  " + hora + " UTC: " + (r.isEmpty() ? "ninguna baliza oída." : r.size() == 1 ? "1 baliza." : r.size() + " balizas."));
        pintarRecord();
    }

    private void pintarRecord() {
        String a = oigoKm < 0 ? "todavía ninguna" : oigoQuien + " a " + String.format(java.util.Locale.ROOT, "%,d", oigoKm).replace(',', '.') + " km";
        String b = meOyeKm < 0 ? "todavía nadie (últimas 24 h)" : meOyeQuien + " a " + String.format(java.util.Locale.ROOT, "%,d", meOyeKm).replace(',', '.') + " km (últimas 24 h)";
        lblRecord.setText("  Baliza más lejana que oigo: " + a + "     ·     Estación más lejana que me oye: " + b);
    }

    /** La estación más lejana de las que me han oído (filas de wspr.live: ..., rx_sign, rx_loc, distance, ...). */
    private void recordMeOyen(List<String[]> filas) {
        for (String[] c : filas) {
            try {
                long k = Long.parseLong(c[4].trim());
                if (k > meOyeKm) { meOyeKm = k; meOyeQuien = c[2] + " (" + c[3] + ")"; }
            } catch (Exception ignored) { }
        }
        pintarRecord();
    }

    /** País y bandera del indicativo, usando lo mismo que el resto de Bitácora 26. */
    private static final java.util.Map<String, Icon> BANDERAS = new java.util.HashMap<>();
    static Object pais(String indicativo) {
        try {
            Class<?> c = Class.forName("LibroGuardiaUI");
            java.lang.reflect.Method m = c.getDeclaredMethod("paisDeIndicativo", String.class, String.class);
            m.setAccessible(true);
            String[] r = (String[]) m.invoke(null, indicativo, "");
            if (r == null) return "";
            Icon ic = BANDERAS.get(r[0]);
            if (ic == null && !BANDERAS.containsKey(r[0])) {
                java.lang.reflect.Method b = c.getDeclaredMethod("banderaIso", String.class);
                b.setAccessible(true);
                ImageIcon grande = (ImageIcon) b.invoke(null, r[0]);
                if (grande != null) {
                    int alto = 14, ancho = Math.max(1, grande.getIconWidth() * alto / Math.max(1, grande.getIconHeight()));
                    ic = new ImageIcon(grande.getImage().getScaledInstance(ancho, alto, Image.SCALE_SMOOTH));
                }
                BANDERAS.put(r[0], ic);
            }
            return new Object[]{r[1], ic};
        } catch (Throwable e) {
            return "";
        }
    }

    private static String vatios(int dbm) {
        double w = Math.pow(10, (dbm - 30) / 10.0);
        if (w >= 1) return (w >= 10 ? String.valueOf(Math.round(w)) : String.format(java.util.Locale.ROOT, "%.1f", w)) + " W";
        return Math.round(w * 1000) + " mW";
    }

    static double[] latLon(String g) {
        if (g == null || g.length() < 4) return null;
        g = g.toUpperCase();
        char a = g.charAt(0), b = g.charAt(1), c = g.charAt(2), d = g.charAt(3);
        if (a < 'A' || a > 'R' || b < 'A' || b > 'R' || !Character.isDigit(c) || !Character.isDigit(d)) return null;
        double lon = (a - 'A') * 20 - 180 + (c - '0') * 2 + 1;
        double lat = (b - 'A') * 10 - 90 + (d - '0') + 0.5;
        if (g.length() >= 6 && Character.isLetter(g.charAt(4)) && Character.isLetter(g.charAt(5))) {
            lon = (a - 'A') * 20 - 180 + (c - '0') * 2 + (g.charAt(4) - 'A') * (2.0 / 24) + 1.0 / 24;
            lat = (b - 'A') * 10 - 90 + (d - '0') + (g.charAt(5) - 'A') * (1.0 / 24) + 0.5 / 24;
        }
        return new double[]{lat, lon};
    }

    static double km(double[] a, double[] b) {
        double f1 = Math.toRadians(a[0]), f2 = Math.toRadians(b[0]), dl = Math.toRadians(b[1] - a[1]);
        double x = Math.sin((f2 - f1) / 2), y = Math.sin(dl / 2);
        return 2 * 6371 * Math.asin(Math.sqrt(x * x + Math.cos(f1) * Math.cos(f2) * y * y));
    }
}
