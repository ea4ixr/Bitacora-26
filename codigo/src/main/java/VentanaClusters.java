import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Ventana Cluster: una pestaña por clúster (Todos, Bitácora 26, URE, DXFun, RBN) y filtros por banda,
 * modo (varios a la vez; Fonía = SSB + AM + FM), continente, indicativo o país, países que faltan, eQSL, LoTW y antigüedad.
 * Los clústeres de internet solo se leen mientras la ventana está abierta.
 */
final class VentanaClusters extends JFrame {

    private final LibroGuardiaUI app;
    private final Cluster cluster;
    private final ClustersDX dx;
    private final List<ClustersDX.Aviso> avisos = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> estados = new HashMap<>();   // fuente -> texto
    private final Map<String, Boolean> conectados = new HashMap<>();
    // Pestañas dibujadas como en la maqueta: punto de estado, nombre y contador en una pastilla
    private final JPanel barraPestanas = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JButton btnElegir = new JButton("Elegir clústeres…");
    private final JButton btnAnunciar = new JButton("Anunciar en el cluster");
    { CuentaBitacora.pintarCrearCuenta(btnAnunciar); }
    private final List<Pestana> pestanas = new ArrayList<>();
    private int pestanaSel = 0;
    private final List<String> idsPestana = new ArrayList<>();      // "" = Todos, "B26", o id de la fuente
    private final DefaultTableModel modelo;
    private final JTable tabla;
    private final List<ClustersDX.Aviso> visibles = new ArrayList<>();
    private final JLabel pie = new JLabel(" "), conectadoComo = new JLabel(" ");
    private final Map<String, JToggleButton> chipsBanda = new LinkedHashMap<>(), chipsModo = new LinkedHashMap<>(), chipsCont = new LinkedHashMap<>();
    private final JTextField buscar = new JTextField(12);
    private final JCheckBox soloNuevos = new JCheckBox("Solo los que me faltan (país nuevo)");
    private final JCheckBox conEqsl = new JCheckBox("Con eQSL"), conLotw = new JCheckBox("Con LoTW"), soloB26 = new JCheckBox("Solo usuarios Bitácora 26");
    private final JComboBox<String> ultimos = new JComboBox<>(new String[]{"15 min", "30 min", "60 min", "2 horas", "Todo"});
    private Set<String> paisesTrabajados = Set.of();
    private final javax.swing.Timer refresco;
    private volatile boolean cambios = true;
    private int segundos;
    private int ordenCol = 0;          // columna por la que se ordena (0 = UTC)
    private boolean ordenAsc = false;  // al abrir: lo más nuevo arriba

    // Colores de la maqueta
    private static final Color FONDO = new Color(17, 25, 38), FONDO_TABLA = new Color(13, 20, 31), BORDE = new Color(38, 51, 72),
            BORDE_CLARO = new Color(62, 80, 108), TEXTO = new Color(222, 229, 238), TEXTO_2 = new Color(150, 165, 188);

    private static final String[] BANDAS = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m", "70cm", "CB"};
    private static final String[] MODOS = {"Fonía", "CW", "FT8", "FT4", "RTTY", "Otros digitales"};
    private static final String[][] CONTS = {{"EU", "Europa"}, {"AF", "África"}, {"AS", "Asia"}, {"NA", "N. América"}, {"SA", "S. América"}, {"OC", "Oceanía"}, {"AN", "Antártida"}};
    private static final Map<String, Color> COLOR_MODO = Map.of("CW", new Color(192, 57, 43), "SSB", new Color(46, 134, 193), "FT8", new Color(39, 174, 96),
            "FT4", new Color(22, 160, 133), "RTTY", new Color(142, 68, 173), "FM", new Color(214, 137, 16), "AM", new Color(185, 119, 14),
            "Otros digitales", new Color(110, 110, 140), "USB", new Color(46, 134, 193), "LSB", new Color(46, 134, 193));

    VentanaClusters(LibroGuardiaUI app, Cluster cluster) {
        super("Cluster · Bitácora 26");
        this.app = app;
        this.cluster = cluster;
        try { setIconImages(app.getIconImages()); } catch (Exception ignored) { }
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        getContentPane().setBackground(FONDO);
        setLayout(new BorderLayout());

        LibroGuardiaUI.ConfigApp.quitar("CLUSTERDX_SPIDER");   // DX Spider ya no está
        dx = new ClustersDX(new ClustersDX.Oyente() {
            @Override public void aviso(ClustersDX.Aviso a) {
                synchronized (avisos) {
                    // El mismo aviso repetido (p. ej. el historial al volver a conectar) no se apunta dos veces
                    for (ClustersDX.Aviso x : avisos)
                        if (x.ms() == a.ms() && x.hz() == a.hz() && x.dx().equals(a.dx()) && x.avisa().equals(a.avisa()) && x.fuente().equals(a.fuente())) return;
                    avisos.add(0, a);
                    // Límite POR clúster: antes era un límite para todos juntos y RBN (que manda muchísimos)
                    // echaba de la lista los avisos de URE y DXFun.
                    int deEste = 0;
                    for (ClustersDX.Aviso x : avisos) if (x.fuente().equals(a.fuente())) deEste++;
                    for (int i = avisos.size() - 1; i >= 0 && deEste > 1500; i--)
                        if (avisos.get(i).fuente().equals(a.fuente())) { avisos.remove(i); deEste--; }
                }
                cambios = true;
            }
            @Override public void estado(String id, boolean ok, String texto) {
                SwingUtilities.invokeLater(() -> { estados.put(id, texto); conectados.put(id, ok); pintarPestanas(); });
            }
        });

        // ---- Pestañas (encima de los filtros)
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        JPanel filaPest = new JPanel(new BorderLayout());
        filaPest.setOpaque(false);
        filaPest.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDE),
                BorderFactory.createEmptyBorder(8, 10, 0, 6)));
        barraPestanas.setOpaque(false);
        filaPest.add(barraPestanas, BorderLayout.CENTER);
        btnElegir.setFont(new Font("Dialog", Font.BOLD, 12));
        btnElegir.setFocusable(false);
        btnElegir.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnElegir.addActionListener(e -> elegirClusteres());
        JPanel pd = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        pd.setOpaque(false);
        btnAnunciar.setFocusable(false);
        btnAnunciar.addActionListener(e -> ventanaAnunciar());
        filaPest.add(pd, BorderLayout.EAST);
        arriba.add(filaPest, BorderLayout.NORTH);

        // ---- Filtros
        JPanel filtros = new JPanel(new GridBagLayout());
        filtros.setBackground(FONDO);
        filtros.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDE),
                BorderFactory.createEmptyBorder(8, 6, 8, 8)));
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 4, 2, 4);
        int y = 0;
        y = filaChips(filtros, g, y, "Banda", BANDAS, null, chipsBanda, "Todas");
        // Modo: se pueden elegir varios a la vez (antes era uno solo; lo guardado se pasa al nuevo)
        String fm = LibroGuardiaUI.ConfigApp.get("CLUSTERDX_F_MODO", "");
        if (fm.equals("Todos")) LibroGuardiaUI.ConfigApp.put("CLUSTERDX_F_MODO", "");
        else if (fm.equals("Digitales")) LibroGuardiaUI.ConfigApp.put("CLUSTERDX_F_MODO", "FT8,FT4,RTTY,Otros digitales");
        y = filaChips(filtros, g, y, "Modo", MODOS, null, chipsModo, "Todos");
        chipsModo.get("Otros digitales").setToolTipText("PSK, JT65, JT9, MSK144, SSTV, Q65, Olivia...");
        String[] ids = new String[CONTS.length], nombres = new String[CONTS.length];
        for (int i = 0; i < CONTS.length; i++) { ids[i] = CONTS[i][0]; nombres[i] = CONTS[i][1]; }
        y = filaChips(filtros, g, y, "Continente", ids, nombres, chipsCont, "Todos");
        g.gridy = y; g.gridx = 0; g.anchor = GridBagConstraints.WEST; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        filtros.add(etiqueta("Mostrar"), g);
        JPanel otros = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        otros.setOpaque(false);
        buscar.setBackground(new Color(14, 21, 33));
        buscar.setForeground(Color.WHITE);
        buscar.setCaretColor(Color.WHITE);
        buscar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDE), BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        buscar.putClientProperty("JTextField.placeholderText", "Indicativo o país…");
        buscar.setToolTipText("Escribe un indicativo, parte de él o un país");
        otros.add(buscar);
        for (JCheckBox c : new JCheckBox[]{soloNuevos, conEqsl, conLotw, soloB26}) { c.setOpaque(false); c.setForeground(TEXTO); c.setFocusable(false); c.addActionListener(e -> cambios = true); otros.add(c); }
        otros.add(etiqueta("Últimos"));
        ultimos.setSelectedItem(LibroGuardiaUI.ConfigApp.get("CLUSTERDX_ULTIMOS", "30 min"));
        ultimos.addActionListener(e -> { LibroGuardiaUI.ConfigApp.put("CLUSTERDX_ULTIMOS", String.valueOf(ultimos.getSelectedItem())); cambios = true; });
        otros.add(ultimos);
        JButton quitar = new JButton("Quitar filtros");
        quitar.addActionListener(e -> {
            for (Map<String, JToggleButton> m : List.of(chipsBanda, chipsModo, chipsCont)) for (Map.Entry<String, JToggleButton> b : m.entrySet()) b.getValue().setSelected(b.getKey().equals("*"));
            LibroGuardiaUI.ConfigApp.put("CLUSTERDX_F_BANDA", ""); LibroGuardiaUI.ConfigApp.put("CLUSTERDX_F_CONTINENTE", "");
            LibroGuardiaUI.ConfigApp.put("CLUSTERDX_F_MODO", "");
            buscar.setText(""); soloNuevos.setSelected(false); conEqsl.setSelected(false); conLotw.setSelected(false); soloB26.setSelected(false);
            cambios = true;
        });
        quitar.setFont(new Font("Dialog", Font.BOLD, 11));
        quitar.setBackground(new Color(232, 236, 242));
        quitar.setForeground(new Color(20, 28, 40));
        quitar.setFocusable(false);
        otros.add(Box.createHorizontalStrut(30));
        otros.add(quitar);
        buscar.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { cambios = true; }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { cambios = true; }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { cambios = true; }
        });
        g.gridx = 1; g.anchor = GridBagConstraints.WEST; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        filtros.add(otros, g);
        arriba.add(filtros, BorderLayout.CENTER);
        add(arriba, BorderLayout.NORTH);

        // ---- Tabla
        modelo = new DefaultTableModel(new Object[]{"UTC", "Frecuencia", "Indicativo", "Modo", "País", "QSL", "Comentario", "Avisa", "Clúster"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tabla = new JTable(modelo);
        tabla.setRowHeight(26);
        tabla.setBackground(FONDO_TABLA);
        tabla.setForeground(TEXTO);
        tabla.setGridColor(new Color(36, 48, 66));
        tabla.setShowVerticalLines(false);
        tabla.setIntercellSpacing(new Dimension(0, 1));
        tabla.setSelectionBackground(new Color(44, 62, 92));
        tabla.setSelectionForeground(Color.WHITE);
        tabla.setFont(new Font("Dialog", Font.PLAIN, 12));
        JTableHeader cab = tabla.getTableHeader();
        cab.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v + (t.convertColumnIndexToModel(c) == ordenCol ? (ordenAsc ? "  ▲" : "  ▼") : ""), false, false, r, c);
                setFont(new Font("Dialog", Font.BOLD, 12));
                setForeground(Color.WHITE);
                setBackground(FONDO);
                setHorizontalAlignment(LEFT);
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDE), BorderFactory.createEmptyBorder(6, 8, 6, 4)));
                return this;
            }
        });
        tabla.setAutoCreateRowSorter(false);
        tabla.getTableHeader().setReorderingAllowed(false);
        int[] anchos = {52, 86, 110, 62, 200, 92, 250, 90, 200};
        for (int i = 0; i < anchos.length; i++) tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        tabla.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, s, f, r, c);
                setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
                if (!s) { setForeground(app.TEMA_AMBAR); setBackground(FONDO_TABLA); }
                return this;
            }
        });
        DefaultTableCellRenderer normal = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, v, s, false, r, c);
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
                if (!s) { setBackground(FONDO_TABLA); setForeground(c == 7 ? TEXTO_2 : TEXTO); }
                setToolTipText(c == 6 && v != null && !v.toString().isBlank() ? v.toString() : null);
                return this;
            }
        };
        for (int c : new int[]{0, 1, 6, 7}) tabla.getColumnModel().getColumn(c).setCellRenderer(normal);
        tabla.getColumnModel().getColumn(8).setCellRenderer(new Etiquetas(Etiquetas.CLUSTER));
        tabla.getColumnModel().getColumn(3).setCellRenderer(new Etiquetas(true));
        tabla.getColumnModel().getColumn(5).setCellRenderer(new Etiquetas(false));
        tabla.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
                ClustersDX.Aviso a = r < visibles.size() ? visibles.get(r) : null;
                boolean nuevo = a != null && esNuevo(a);
                super.getTableCellRendererComponent(t, "<html>" + (a == null ? "" : esc(nombrePais(a)))
                        + (nuevo ? (s ? " <b>PAÍS NUEVO</b>" : " <b><font size='-2' color='#ff5fd2'>PAÍS NUEVO</font></b>") : "") + "</html>", s, f, r, c);
                ImageIcon ic = a == null || a.iso().isEmpty() ? null : app.banderaIso(a.iso());
                setIcon(ic == null ? null : new ImageIcon(ic.getImage().getScaledInstance(-1, 13, Image.SCALE_SMOOTH)));
                setIconTextGap(6);
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
                if (!s) { setBackground(FONDO_TABLA); setForeground(TEXTO); }
                return this;
            }
        });
        // Pinchar en el nombre de una columna ordena por ella; otra vez, al revés
        cab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cab.setToolTipText("Pincha para ordenar; otra vez para el orden contrario");
        cab.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int c = tabla.convertColumnIndexToModel(cab.columnAtPoint(e.getPoint()));
                if (c < 0) return;
                if (c == ordenCol) ordenAsc = !ordenAsc;
                else { ordenCol = c; ordenAsc = c != 0; }
                cab.repaint();
                rellenar();
            }
        });
        tabla.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tabla.setToolTipText("Clic: prepara el contacto (y con CAT, la emisora) · doble clic: lo abre en QRZ.com");
        tabla.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int f = tabla.rowAtPoint(e.getPoint());
                if (f < 0 || f >= visibles.size()) return;
                ClustersDX.Aviso a = visibles.get(f);
                if (e.getClickCount() == 2 && !"CB".equals(a.banda())) {
                    try { Desktop.getDesktop().browse(new java.net.URI("https://www.qrz.com/db/" + java.net.URLEncoder.encode(a.dx(), java.nio.charset.StandardCharsets.UTF_8))); }
                    catch (Exception ignored) { }
                    return;
                }
                if (e.getClickCount() == 1) cluster.irA(a);
            }
        });
        JScrollPane sc = new JScrollPane(tabla);
        sc.setBorder(BorderFactory.createEmptyBorder());
        sc.getViewport().setBackground(FONDO_TABLA);
        add(sc, BorderLayout.CENTER);
        JPanel abajo = new JPanel(new BorderLayout());
        abajo.setBackground(FONDO);
        abajo.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDE), BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        pie.setForeground(TEXTO_2);
        pie.setFont(new Font("Dialog", Font.PLAIN, 11));
        conectadoComo.setForeground(TEXTO_2);
        conectadoComo.setFont(new Font("Dialog", Font.PLAIN, 11));
        abajo.add(pie, BorderLayout.CENTER);
        abajo.add(conectadoComo, BorderLayout.EAST);
        add(abajo, BorderLayout.SOUTH);

        construirPestanas();
        setSize(1260, 640);
        setLocationRelativeTo(app);

        refresco = new javax.swing.Timer(1000, e -> { if (++segundos % 15 == 0) cluster.pedirLectura(); if (cambios && isShowing()) { cambios = false; rellenar(); } });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentShown(ComponentEvent e) { arrancar(); }
            @Override public void componentHidden(ComponentEvent e) { parar(); }
        });
    }

    // ------------------------------------------------------------------------------------------------------
    private JLabel etiqueta(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(TEXTO_2);
        l.setFont(new Font("Dialog", Font.PLAIN, 12));
        return l;
    }

    private int filaChips(JPanel p, GridBagConstraints g, int y, String titulo, String[] ids, String[] textos, Map<String, JToggleButton> destino, String textoTodas) {
        g.gridy = y; g.gridx = 0; g.anchor = GridBagConstraints.WEST; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        p.add(etiqueta(titulo), g);
        JPanel fila = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fila.setOpaque(false);
        String clave = "CLUSTERDX_F_" + titulo.toUpperCase(Locale.ROOT);
        Set<String> marcados = new HashSet<>(Arrays.asList(LibroGuardiaUI.ConfigApp.get(clave, "").split(",")));
        List<String> todosIds = new ArrayList<>();
        List<String> todosTextos = new ArrayList<>();
        if (textoTodas != null) { todosIds.add("*"); todosTextos.add(textoTodas); }
        for (int i = 0; i < ids.length; i++) { todosIds.add(ids[i]); todosTextos.add(textos == null ? (ids[i].equals("CB") ? "CB (11m)" : ids[i]) : textos[i]); }
        for (int i = 0; i < todosIds.size(); i++) {
            String id = todosIds.get(i);
            JToggleButton b = new Chip(todosTextos.get(i));
            b.setSelected(marcados.contains(id));
            if (id.equals("Fonía")) b.setToolTipText("Fonía (phone): SSB, AM y FM");
            b.addActionListener(e -> {
                // «Todas»/«Todos» quita lo demás; elegir otra cosa quita «Todas». Sin nada elegido vuelve «Todas».
                if (textoTodas != null) {
                    if (id.equals("*")) for (Map.Entry<String, JToggleButton> o : destino.entrySet()) o.getValue().setSelected(o.getKey().equals("*"));
                    else destino.get("*").setSelected(seleccion(destino).isEmpty());
                }
                LibroGuardiaUI.ConfigApp.put(clave, String.join(",", seleccion(destino)));
                cambios = true;
                fila.repaint();
            });
            destino.put(id, b);
            fila.add(b);
        }
        if (textoTodas != null && seleccion(destino).isEmpty()) destino.get("*").setSelected(true);
        g.gridx = 1; g.anchor = GridBagConstraints.WEST; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        p.add(fila, g);
        return y + 1;
    }

    /** Lo elegido en una fila de filtros («Todas» no cuenta: es no filtrar). */
    private static Set<String> seleccion(Map<String, JToggleButton> m) {
        Set<String> s = new LinkedHashSet<>();
        for (Map.Entry<String, JToggleButton> e : m.entrySet()) if (e.getValue().isSelected() && !e.getKey().equals("*")) s.add(e.getKey());
        return s;
    }

    private void construirPestanas() {
        barraPestanas.removeAll();
        pestanas.clear();
        idsPestana.clear();
        idsPestana.add("");
        idsPestana.add("B26");
        idsPestana.add("CBB");   // Cluster CB (anuncios de banda ciudadana de los usuarios de Bitácora 26)
        for (ClustersDX.Fuente f : ClustersDX.FUENTES) if (ClustersDX.activa(f)) idsPestana.add(f.id());
        if (pestanaSel >= idsPestana.size()) pestanaSel = 0;
        for (int i = 0; i < idsPestana.size(); i++) {
            final int n = i;
            Pestana pe = new Pestana(idsPestana.get(i));
            pe.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { pestanaSel = n; cambios = true; barraPestanas.repaint(); }
            });
            pestanas.add(pe);
            barraPestanas.add(pe);
        }
        barraPestanas.add(Box.createHorizontalStrut(10));
        barraPestanas.add(btnElegir);   // justo detrás de las pestañas, para que se vea
        barraPestanas.add(btnAnunciar);
        barraPestanas.revalidate();
        pintarPestanas();
    }

    private void pintarPestanas() {
        for (int i = 0; i < pestanas.size(); i++) {
            Pestana pe = pestanas.get(i);
            String id = pe.id;
            pe.cuenta = contar(id);
            pe.estado = id.isEmpty() ? 0 : id.equals("B26") || (id.equals("CBB") && cluster.cbConectado) || Boolean.TRUE.equals(conectados.get(id)) ? 1 : 2;
            pe.setToolTipText(id.isEmpty() || id.equals("B26") || id.equals("CBB") ? null : fuente(id).host() + ":" + fuente(id).puerto() + " · " + estados.getOrDefault(id, ""));
            pe.revalidate();
        }
        barraPestanas.repaint();
    }

    /** Lengüeta de pestaña: punto verde/rojo, nombre y una pastilla con el número de avisos. */
    private final class Pestana extends JComponent {
        final String id;
        int cuenta, estado;
        Pestana(String id) { this.id = id; setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); setFont(new Font("Dialog", Font.BOLD, 12)); }
        String nombre() { return id.isEmpty() ? "Todos" : id.equals("B26") ? "Bitácora 26" : id.equals("CBB") ? "Cluster CB" : fuente(id).nombre(); }
        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont()), fp = getFontMetrics(getFont().deriveFont(10f));
            return new Dimension(14 + (estado == 0 ? 0 : 14) + fm.stringWidth(nombre()) + 8 + fp.stringWidth(String.valueOf(cuenta)) + 12 + 14, 30);
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean sel = pestanas.indexOf(this) == pestanaSel;
            int w = getWidth(), h = getHeight();
            g.setColor(sel ? FONDO_TABLA : new Color(22, 31, 46));
            g.fillRoundRect(0, 0, w - 1, h + 8, 10, 10);
            g.setColor(sel ? BORDE_CLARO : BORDE);
            g.drawRoundRect(0, 0, w - 1, h + 8, 10, 10);
            FontMetrics fm = g.getFontMetrics();
            int x = 14, base = (h + fm.getAscent() - fm.getDescent()) / 2;
            if (estado != 0) {
                g.setColor(estado == 1 ? new Color(76, 208, 125) : new Color(224, 72, 72));
                g.fillOval(x, h / 2 - 4, 8, 8);
                x += 14;
            }
            g.setColor(sel ? app.TEMA_AMBAR : TEXTO);
            g.drawString(nombre(), x, base);
            x += fm.stringWidth(nombre()) + 8;
            g.setFont(getFont().deriveFont(10f));
            FontMetrics fp = g.getFontMetrics();
            String c = String.valueOf(cuenta);
            int pw = fp.stringWidth(c) + 12, ph = 16;
            g.setColor(new Color(44, 58, 82));
            g.fillRoundRect(x, (h - ph) / 2, pw, ph, ph, ph);
            g.setColor(TEXTO);
            g.drawString(c, x + 6, (h - ph) / 2 + (ph + fp.getAscent() - fp.getDescent()) / 2);
            g.dispose();
        }
    }

    /** Botón de filtro en forma de pastilla: ámbar cuando está elegido. */
    private final class Chip extends JToggleButton {
        Chip(String texto) {
            super(texto);
            setFont(new Font("Dialog", Font.PLAIN, 11));
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont().deriveFont(Font.BOLD));
            return new Dimension(fm.stringWidth(getText()) + 18, 22);
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (isSelected()) {
                g.setColor(app.TEMA_AMBAR);
                g.fillRoundRect(0, 0, w - 1, h - 1, h, h);
                g.setFont(getFont().deriveFont(Font.BOLD));
                g.setColor(new Color(20, 24, 32));
            } else {
                g.setColor(getModel().isRollover() ? new Color(38, 52, 74) : new Color(26, 37, 54));
                g.fillRoundRect(0, 0, w - 1, h - 1, h, h);
                g.setColor(BORDE_CLARO);
                g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
                g.setFont(getFont());
                g.setColor(TEXTO);
            }
            FontMetrics fm = g.getFontMetrics();
            g.drawString(getText(), (w - fm.stringWidth(getText())) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }

    private int contar(String id) {
        List<ClustersDX.Aviso> todos = todosLosAvisos();
        if (id.isEmpty()) {
            Set<String> unicos = new HashSet<>();
            for (ClustersDX.Aviso a : todos) unicos.add(a.dx() + "|" + a.banda());
            return unicos.size();
        }
        String nombre = id.equals("B26") ? "Bitácora 26" : id.equals("CBB") ? "Cluster CB" : fuente(id).nombre();
        int n = 0;
        for (ClustersDX.Aviso a : todos) if (a.fuente().equals(nombre)) n++;
        return n;
    }

    private static ClustersDX.Fuente fuente(String id) {
        for (ClustersDX.Fuente f : ClustersDX.FUENTES) if (f.id().equals(id)) return f;
        return ClustersDX.FUENTES.get(0);
    }

    /** Los avisos de internet más los operadores de Bitácora 26 que están en el aire. */
    private List<ClustersDX.Aviso> todosLosAvisos() {
        List<ClustersDX.Aviso> l = new ArrayList<>(cluster.avisosBitacora26());
        l.addAll(cluster.avisosClusterCb());
        l.addAll(cluster.avisosClusterDx());
        synchronized (avisos) { l.addAll(avisos); }
        return l;
    }

    private void rellenar() {
        String pest = pestanaSel < 0 || pestanaSel >= idsPestana.size() ? "" : idsPestana.get(pestanaSel);
        String fuentePest = pest.isEmpty() ? null : pest.equals("B26") ? "Bitácora 26" : pest.equals("CBB") ? "Cluster CB" : fuente(pest).nombre();
        Set<String> bandas = seleccion(chipsBanda), modos = seleccion(chipsModo), conts = seleccion(chipsCont);
        String q = buscar.getText().trim().toUpperCase(Locale.ROOT);
        long limite = switch (String.valueOf(ultimos.getSelectedItem())) {
            case "15 min" -> 15; case "60 min" -> 60; case "2 horas" -> 120; case "Todo" -> 100_000; default -> 30; };
        long desde = System.currentTimeMillis() - limite * 60_000;
        List<ClustersDX.Aviso> todos = todosLosAvisos();
        visibles.clear();
        Set<String> vistos = new HashSet<>();
        // Los clústeres están unidos entre sí y repiten los mismos avisos: en «Todos» cada estación sale una vez
        // y en la columna Clúster se ven todos los clústeres que la han avisado
        Map<String, Set<String>> quienAvisa = new HashMap<>();
        for (ClustersDX.Aviso a : todos) if (a.ms() >= desde) quienAvisa.computeIfAbsent(a.dx() + "|" + a.banda(), k -> new TreeSet<>()).add(a.fuente());
        int total = 0;
        Set<String> contados = new HashSet<>();
        for (ClustersDX.Aviso a : todos) {
            if (fuentePest != null && !a.fuente().equals(fuentePest)) continue;
            if (fuentePest != null || !contados.contains(a.dx() + "|" + a.banda())) total++;
            if (fuentePest == null) contados.add(a.dx() + "|" + a.banda());
            if (a.ms() < desde) continue;
            if (!bandas.isEmpty() && !bandas.contains(a.banda())) continue;
            if (!modos.isEmpty() && !(modos.contains(a.modo()) || modos.contains("Fonía") && ClustersDX.esFonia(a.modo()))) continue;
            if (!conts.isEmpty() && !conts.contains(a.continente())) continue;
            if (!q.isEmpty() && !a.dx().contains(q) && !nombrePais(a).toUpperCase(Locale.ROOT).contains(q) && !a.pais().toUpperCase(Locale.ROOT).contains(q)) continue;
            if (soloNuevos.isSelected() && !esNuevo(a)) continue;
            if (conEqsl.isSelected() && !ClustersDX.usaEqsl(a.dx())) continue;
            if (conLotw.isSelected() && !ClustersDX.usaLotw(a.dx())) continue;
            if (soloB26.isSelected() && !a.fuente().equals("Bitácora 26")) continue;
            // En "Todos" no se repite la misma estación en la misma banda: se queda el aviso más reciente
            if (fuentePest == null && !vistos.add(a.dx() + "|" + a.banda())) continue;
            visibles.add(a);
        }
        visibles.sort(comparador(quienAvisa, fuentePest == null));
        modelo.setRowCount(0);
        DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
        for (ClustersDX.Aviso a : visibles) {
            modelo.addRow(new Object[]{hhmm.format(Instant.ofEpochMilli(a.ms())), frecuencia(a.hz()),
                    a.dx(), a.modo(), "", qsl(a), a.comentario(), a.avisa(),
                    fuentePest == null ? String.join("|", quienAvisa.getOrDefault(a.dx() + "|" + a.banda(), Set.of(a.fuente()))) : a.fuente()});
        }
        pie.setText("Mostrando " + visibles.size() + " de " + total + " avisos · clic en un aviso: prepara el contacto (y con CAT, la emisora) · doble clic: lo abre en QRZ.com");
        conectadoComo.setText(indicativoHf().isEmpty() ? "" : "<html>Conectado como <b><font color='#ffbe3c'>" + esc(indicativoHf()) + "</font></b></html>");
        pintarPestanas();
    }

    /** País DXCC aún no trabajado (en CB no se cuentan países). */
    private boolean esNuevo(ClustersDX.Aviso a) {
        return !"CB".equals(a.banda()) && !a.pais().isEmpty() && !paisesTrabajados.contains(a.pais());
    }

    /** Orden de la tabla según la columna elegida; a igualdad, lo más nuevo arriba. */
    private Comparator<ClustersDX.Aviso> comparador(Map<String, Set<String>> quienAvisa, boolean todos) {
        java.text.Collator col = java.text.Collator.getInstance(new Locale("es", "ES"));
        col.setStrength(java.text.Collator.PRIMARY);
        Comparator<ClustersDX.Aviso> c = switch (ordenCol) {
            case 1 -> Comparator.comparingLong(ClustersDX.Aviso::hz);
            case 2 -> Comparator.comparing(ClustersDX.Aviso::dx, col);
            case 3 -> Comparator.comparing(ClustersDX.Aviso::modo, col);
            case 4 -> Comparator.comparing(this::nombrePais, col);
            case 5 -> Comparator.comparing(this::qsl, col);
            case 6 -> Comparator.comparing(ClustersDX.Aviso::comentario, col);
            case 7 -> Comparator.comparing(ClustersDX.Aviso::avisa, col);
            case 8 -> Comparator.comparing(a -> todos ? String.join(" ", quienAvisa.getOrDefault(a.dx() + "|" + a.banda(), Set.of(a.fuente()))) : a.fuente(), col);
            default -> Comparator.comparingLong(ClustersDX.Aviso::ms);
        };
        if (!ordenAsc) c = c.reversed();
        return c.thenComparing(Comparator.comparingLong(ClustersDX.Aviso::ms).reversed());
    }

    /** Frecuencia en kHz con puntos de miles (14.205,0), igual en cualquier Windows. */
    private static String frecuencia(long hz) {
        if (hz <= 0) return "—";
        java.text.DecimalFormatSymbols sim = new java.text.DecimalFormatSymbols(Locale.ROOT);
        sim.setGroupingSeparator('.');
        sim.setDecimalSeparator(',');
        return new java.text.DecimalFormat("#,##0.0", sim).format(hz / 1000.0);
    }

    private String qsl(ClustersDX.Aviso a) {
        StringBuilder s = new StringBuilder();
        if (a.fuente().equals("Bitácora 26")) s.append("B26 ");
        if (ClustersDX.usaEqsl(a.dx())) s.append("eQSL ");
        if (ClustersDX.usaLotw(a.dx())) s.append("LoTW ");
        return s.toString().trim();
    }

    private String nombrePais(ClustersDX.Aviso a) {
        if (a.iso().isEmpty()) return a.pais();
        String n = new Locale("", a.iso()).getDisplayCountry(new Locale("es", "ES"));
        return n == null || n.isEmpty() || n.equalsIgnoreCase(a.iso()) ? a.pais() : n;
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }

    /** Recuadros de colores: el modo (CW, SSB, FT8…) o las etiquetas de QSL (B26, eQSL, LoTW). */
    private final class Etiquetas extends JComponent implements TableCellRenderer {
        static final int CLUSTER = 2;
        private final boolean esModo, esCluster;
        private String[] partes = new String[0];
        private boolean sel;
        Etiquetas(boolean esModo) { this.esModo = esModo; this.esCluster = false; setOpaque(true); }
        Etiquetas(int tipo) { this.esModo = false; this.esCluster = true; setOpaque(true); }
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int c) {
            String txt = v == null ? "" : v.toString();
            partes = txt.isBlank() ? new String[0] : esCluster ? txt.split("\\|") : esModo ? new String[]{txt} : txt.split(" ");
            setToolTipText(esCluster && partes.length > 1 ? "Avisado en: " + String.join(", ", partes) : null);
            sel = s;
            setBackground(s ? t.getSelectionBackground() : FONDO_TABLA);
            return this;
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font("Dialog", Font.BOLD, 10));
            FontMetrics fm = g.getFontMetrics();
            int x = 8;
            if (esCluster) {
                g.setFont(new Font("Dialog", Font.PLAIN, 10));
                fm = g.getFontMetrics();
                for (String p : partes) {
                    int w = fm.stringWidth(p) + 10, h = 16, y = (getHeight() - h) / 2;
                    g.setColor(BORDE_CLARO);
                    g.drawRoundRect(x, y, w, h, 6, 6);
                    g.setColor(TEXTO_2);
                    g.drawString(p, x + 5, y + (h + fm.getAscent() - fm.getDescent()) / 2);
                    x += w + 4;
                }
                g.dispose();
                return;
            }
            for (String p : partes) {
                String t = p.equals("Otros digitales") ? "DIGI" : p;
                Color c = esModo ? COLOR_MODO.getOrDefault(p, new Color(110, 110, 140))
                        : p.equals("B26") ? app.TEMA_AMBAR : p.equals("eQSL") ? new Color(30, 93, 140) : new Color(125, 60, 152);
                int w = Math.max(esModo ? 44 : 0, fm.stringWidth(t) + 10), h = 16, y = (getHeight() - h) / 2;
                g.setColor(c);
                g.fillRoundRect(x, y, w, h, 6, 6);
                g.setColor(p.equals("B26") ? Color.BLACK : Color.WHITE);
                g.drawString(t, x + (w - fm.stringWidth(t)) / 2, y + (h + fm.getAscent() - fm.getDescent()) / 2);
                x += w + 4;
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------------------
    private String indicativoHf() {
        String[] d = app.leerDatosOperador(app.operadorActual == null ? "" : app.operadorActual);
        return d == null || d[0] == null ? "" : d[0].trim().toUpperCase(Locale.ROOT);
    }

    private String indicativoCb() {
        String[] d = app.leerDatosOperador(app.operadorActual == null ? "" : app.operadorActual);
        String cb = d == null || d.length < 2 || d[1] == null ? "" : d[1].trim().toUpperCase(Locale.ROOT);
        return cb.isEmpty() ? indicativoHf() : cb;
    }

    private void arrancar() {
        for (ClustersDX.Fuente f : ClustersDX.FUENTES) {
            if (ClustersDX.activa(f)) dx.conectar(f, f.cb() ? indicativoCb() : indicativoHf());
            else dx.parar(f.id());
        }
        ClustersDX.cargarListas(Path.of(System.getProperty("user.home"), "Documents", LibroGuardiaUI.NOMBRE_APP, "listas"), () -> cambios = true);
        new Thread(() -> { paisesTrabajados = calcularPaisesTrabajados(); cambios = true; }, "PaisesTrabajados").start();
        refresco.start();
        cambios = true;
    }

    private void parar() {
        refresco.stop();
        dx.pararTodo();
    }

    /** Entidades DXCC ya trabajadas (todas las bandas), para marcar los países nuevos. */
    private Set<String> calcularPaisesTrabajados() {
        Set<String> s = new HashSet<>();
        try (java.sql.Connection c = LibroGuardiaUI.DatabaseManager.conectar();
             java.sql.ResultSet r = c.createStatement().executeQuery("SELECT DISTINCT indicativo, banda FROM qsos")) {
            while (r.next()) {
                String[] p = LibroGuardiaUI.paisDeIndicativo(r.getString(1), r.getString(2));
                if (p != null && !"CB".equalsIgnoreCase(r.getString(2))) s.add(p[1]);
            }
        } catch (Exception ignored) { }
        return s;
    }

    private void elegirClusteres() {
        JPanel p = new JPanel(new GridLayout(0, 1, 2, 4));
        p.add(new JLabel("<html>Marca los clústeres que quieres ver. Se conectan solo mientras esta ventana está abierta,<br>"
                + "con tu indicativo, y solo para leer (no se manda ningún aviso).</html>"));
        Map<ClustersDX.Fuente, JCheckBox> m = new LinkedHashMap<>();
        for (ClustersDX.Fuente f : ClustersDX.FUENTES) {
            JCheckBox c = new JCheckBox("<html><b>" + f.nombre() + "</b> <font color='#a9bad3'>· " + f.host() + ":" + f.puerto()
                    + (f.cb() ? " · Banda Ciudadana (entra con tu indicativo de CB)" : "") + (f.id().equals("RBN") ? " · avisos automáticos de CW y FT8" : "") + "</font></html>",
                    ClustersDX.activa(f));
            m.put(f, c);
            p.add(c);
        }
        if (JOptionPane.showConfirmDialog(this, p, "Elegir clústeres", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        for (Map.Entry<ClustersDX.Fuente, JCheckBox> e : m.entrySet()) ClustersDX.ponerActiva(e.getKey(), e.getValue().isSelected());
        construirPestanas();
        if (isShowing()) arrancar();
    }

    // ---------------------------------------------------------------------------------------------------------
    /** Ventanita para anunciar un contacto en URE y DXFun (el anuncio es público y sale con nuestro indicativo). */
    private void ventanaAnunciar() {
        String banda = app.cbBanda == null || app.cbBanda.getSelectedItem() == null ? "" : app.cbBanda.getSelectedItem().toString();
        String yo = app.operadorActual == null ? "" : app.operadorActual.trim().toUpperCase();
        long hz = ControlEmisora.herciosDeTexto(app.txtFreqTx == null ? "" : app.txtFreqTx.getText());
        // En CB (por la banda o por la frecuencia) se marca el Cluster CB; URE y DXFun son de radioaficionado
        final boolean esCb = banda.equalsIgnoreCase("CB") || (hz >= 25_600_000 && hz <= 28_000_000);
        String modo = app.cbModo == null || app.cbModo.getSelectedItem() == null ? "" : app.cbModo.getSelectedItem().toString();
        String rst = app.txtRstSent == null ? "" : app.txtRstSent.getText().trim();

        JDialog d = new JDialog(this, "Anunciar en el cluster", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(FONDO);
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4); g.anchor = GridBagConstraints.WEST; g.fill = GridBagConstraints.HORIZONTAL;
        JLabel tit = new JLabel("Anunciar un contacto en los clusters");
        tit.setFont(new Font("Dialog", Font.BOLD, 15)); tit.setForeground(app.TEMA_AMBAR);
        g.gridx = 0; g.gridy = 0; g.gridwidth = 2; p.add(tit, g);
        g.gridwidth = 1;
        JTextField tInd = new JTextField(app.txtIndicativo == null ? "" : app.txtIndicativo.getText().trim().toUpperCase(), 12);
        JTextField tFrec = new JTextField(hz > 0 ? String.format(java.util.Locale.ROOT, "%.1f", hz / 1000.0).replace('.', ',') : "", 12);
        JTextField tCom = new JTextField((modo + (rst.isEmpty() ? "" : " " + rst)).trim(), 26);
        Object[][] campos = {{"Indicativo", tInd}, {"Frecuencia (kHz)", tFrec}, {"Comentario", tCom}};
        int y = 1;
        for (Object[] c : campos) {
            JLabel l = new JLabel((String) c[0]); l.setForeground(TEXTO_2);
            g.gridx = 0; g.gridy = y; g.weightx = 0; p.add(l, g);
            JTextField t = (JTextField) c[1];
            t.setFont(new Font("Dialog", Font.BOLD, 13));
            g.gridx = 1; g.weightx = 1; p.add(t, g);
            y++;
        }
        tInd.setDocument(new javax.swing.text.PlainDocument() {   // en mayúsculas, como todo el programa
            @Override public void insertString(int o, String s, javax.swing.text.AttributeSet a) throws javax.swing.text.BadLocationException {
                super.insertString(o, s == null ? null : s.toUpperCase(), a);
            }
        });
        tInd.setText(app.txtIndicativo == null ? "" : app.txtIndicativo.getText().trim().toUpperCase());
        JLabel en = new JLabel("Publicar en"); en.setForeground(TEXTO_2);
        g.gridx = 0; g.gridy = y; g.weightx = 0; p.add(en, g);
        JPanel chk = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); chk.setOpaque(false);
        java.util.Map<String, JCheckBox> casillas = new LinkedHashMap<>();
        casillas.put("B26", new JCheckBox("Bitácora 26", !esCb));   // el cluster propio (radioaficionado)
        for (ClustersDX.Fuente f : ClustersDX.FUENTES) {
            if (!f.id().equals("URE") && !f.id().equals("DXFUN")) continue;
            boolean ok = Boolean.TRUE.equals(conectados.get(f.id()));
            JCheckBox cb = new JCheckBox(f.nombre() + (ok ? "" : " (sin conexión)"), ok && !esCb);
            cb.setEnabled(ok);
            casillas.put(f.id(), cb);
        }
        // Cluster CB: marcado solo si se está en banda CB (URE y DXFun son de radioaficionado)
        JCheckBox cajaCb = new JCheckBox("Cluster CB", esCb);
        casillas.put("CBB", cajaCb);
        for (JCheckBox cb : casillas.values()) {
            cb.setOpaque(false); cb.setForeground(TEXTO); cb.setFont(new Font("Dialog", Font.BOLD, 13));
            chk.add(cb); chk.add(Box.createHorizontalStrut(14));
        }
        g.gridx = 1; p.add(chk, g); y++;
        JLabel nota = new JLabel("<html>El anuncio es público: lo verán todos los conectados a esos clusters (el Cluster CB, también en bitacora26.es).<br>"
                + "Sale con tu indicativo (" + (yo.isEmpty() ? "sin indicativo" : yo) + "). En Bitácora 26: nunca a ti mismo (usa ON AIR), máx. 10 por hora.<br>En el Cluster CB, si te anuncias a ti mismo, se vuelve a subir solo cada 10 minutos durante 1 hora.</html>");
        nota.setForeground(TEXTO_2); nota.setFont(new Font("Dialog", Font.PLAIN, 11));
        g.gridx = 0; g.gridy = y; g.gridwidth = 2; p.add(nota, g); y++;
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); bot.setOpaque(false);
        JButton cancelar = new JButton("Cancelar");
        JButton enviar = new JButton("Enviar anuncio"); CuentaBitacora.pintarCrearCuenta(enviar);
        JButton quitar = new JButton("Quitar mis anuncios CB");
        quitar.setToolTipText("Quita del Cluster CB los anuncios que has puesto tú (por ejemplo, si dejas de transmitir)");
        quitar.addActionListener(e -> new Thread(() -> {
            int n = cluster.quitarAnunciosCb();
            SwingUtilities.invokeLater(() -> {
                cambios = true;
                JOptionPane.showMessageDialog(d, n < 0 ? "No se ha podido conectar con Bitácora 26." : n == 0 ? "No tenías ningún anuncio en el Cluster CB."
                        : "Quitados tus anuncios del Cluster CB (" + n + ").", "Cluster CB", JOptionPane.INFORMATION_MESSAGE);
            });
        }, "QuitarCb").start());
        bot.add(quitar);
        bot.add(cancelar); bot.add(enviar);
        g.gridy = y; g.anchor = GridBagConstraints.EAST; g.fill = GridBagConstraints.NONE; p.add(bot, g);
        cancelar.addActionListener(e -> d.dispose());
        enviar.addActionListener(e -> {
            String ind = tInd.getText().trim().toUpperCase();
            String tf = tFrec.getText().trim().replace(" ", "");
            if (tf.contains(",")) tf = tf.replace(".", "").replace(',', '.');   // 14.205,0 → 14205.0
            double khz;
            try { khz = Double.parseDouble(tf); } catch (Exception ex) { khz = 0; }
            java.util.List<String> donde = new ArrayList<>();
            for (java.util.Map.Entry<String, JCheckBox> c : casillas.entrySet()) if (c.getValue().isSelected() && c.getValue().isEnabled()) donde.add(c.getKey());
            boolean alCb = donde.contains("CBB");
            boolean enCb = khz >= 25600 && khz <= 28000;
            if (ind.isEmpty()) { JOptionPane.showMessageDialog(d, "Escribe el indicativo que quieres anunciar.", "Anunciar en el cluster", JOptionPane.WARNING_MESSAGE); return; }
            if (!(khz >= 100)) { JOptionPane.showMessageDialog(d, "Escribe la frecuencia en kHz (por ejemplo " + (alCb ? "27555,0" : "14205,0") + ").", "Anunciar en el cluster", JOptionPane.WARNING_MESSAGE); return; }
            if (donde.isEmpty()) { JOptionPane.showMessageDialog(d, "Marca al menos un cluster.", "Anunciar en el cluster", JOptionPane.WARNING_MESSAGE); return; }
            if (alCb && !enCb) { JOptionPane.showMessageDialog(d, "Para el Cluster CB la frecuencia tiene que ser de la banda ciudadana (26 a 28 MHz).", "Anunciar en el cluster", JOptionPane.WARNING_MESSAGE); return; }
            if (enCb && donde.size() > (alCb ? 1 : 0)) { JOptionPane.showMessageDialog(d, "Bitácora 26, URE y DXFun son clusters de radioaficionado: los anuncios de CB van solo al Cluster CB.", "Anunciar en el cluster", JOptionPane.WARNING_MESSAGE); return; }
            enviar.setEnabled(false); cancelar.setEnabled(false); enviar.setText("Enviando…");
            final double k = khz;
            final String com = tCom.getText().trim(), mc = modo;
            new Thread(() -> {
                StringBuilder res = new StringBuilder();
                boolean todoBien = true;
                for (String id : donde) {
                    String nombre = id.equals("URE") ? "URE" : id.equals("DXFUN") ? "DXFun" : id.equals("B26") ? "Bitácora 26" : "Cluster CB";
                    String r = id.equals("CBB") ? cluster.anunciarCb(ind, Math.round(k * 1000), mc, com)
                            : id.equals("B26") ? cluster.anunciarDx(ind, Math.round(k * 1000), com) : dx.anunciar(id, k, ind, com);
                    if (r == null) res.append(nombre).append(": anunciado.\n");
                    else { todoBien = false; res.append(nombre).append(": no se ha podido (").append(r).append(").\n"); }
                }
                final boolean bien = todoBien;
                SwingUtilities.invokeLater(() -> {
                    cambios = true;
                    d.dispose();
                    app.lblEstado.setText("Cluster: " + res.toString().trim().replace("\n", " · "));
                    JOptionPane.showMessageDialog(this, ind + " en " + String.format(java.util.Locale.ROOT, "%.1f", k).replace('.', ',') + " kHz\n\n" + res,
                            "Anunciar en el cluster", bien ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                });
            }, "AnunciarCluster").start();
        });
        d.setContentPane(p); d.pack(); d.setResizable(false); d.setLocationRelativeTo(this); d.setVisible(true);
    }
}
