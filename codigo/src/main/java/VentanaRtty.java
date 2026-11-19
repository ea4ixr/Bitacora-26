import digitales.EmisorDigitales;
import digitales.Fft;
import digitales.MotorDigitales;
import digitales.rtty.RttyDemod;
import digitales.rtty.RttyMod;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ventana RTTY: recibe y transmite RTTY (Baudot 45,45/75 baudios, 170 Hz) por la tarjeta de sonido, con la emisora en DATA.
 * El descodificador es una traducción del de MMTTY (JE3HHT, LGPL). Se trabaja «semiautomático»: un clic por paso
 * (F2 Responder → F3 599 → F4 TU 73, que guarda el contacto en el LOG), con el paso siguiente marcado en ámbar.
 */
final class VentanaRtty extends JPanel {

    // Mismos colores, letras y botones que la ventana DIGITALES
    private static final Color FONDO = VentanaDigitales.FONDO, PANEL = VentanaDigitales.FONDO2, CAMPO = VentanaDigitales.CAMPO,
            LINEA = VentanaDigitales.LINEA, TEXTO = VentanaDigitales.TEXTO, GRIS = VentanaDigitales.GRIS, AMBAR = VentanaDigitales.AMBAR,
            VERDE = VentanaDigitales.VERDE, TX = new Color(255, 122, 122);
    private static final Font MONO = new Font("Monospaced", Font.PLAIN, 14);
    private static final int FS = 12000;
    private static final double SHIFT = 170;

    private final LibroGuardiaUI app;
    private final VentanaDigitales dig;

    // Recepción
    private volatile MotorDigitales.Fuente fuente;
    private volatile boolean escuchando;
    private volatile RttyDemod demod;
    private double centro = 1585;   // Hz entre MARK y SPACE
    private boolean rev = false, b75 = false;
    private final Cascada cascada = new Cascada();

    // Transmisión
    private final AtomicBoolean transmitiendo = new AtomicBoolean(false);
    private volatile boolean cortarTx;

    // Pantalla
    private final JLabel lblNivel = new JLabel("Señal —"), lblEstado = new JLabel(" ");
    private final JTextPane rx = new JTextPane();
    private final JTextArea txt = new JTextArea(3, 40);
    private final JTextField cInd = new JTextField(10), cRstS = new JTextField("599", 5), cRstR = new JTextField("599", 5),
            cInter = new JTextField(8), cCom = new JTextField(14);
    private final JLabel cNombre = new JLabel(" ");
    private final JButton[] macros = new JButton[8];
    private final JButton bTx = botonRojo("TRANSMITIR"), bEscuchar = VentanaDigitales.boton("ESCUCHAR");
    private final JToggleButton t45 = VentanaDigitales.conmutador("RTTY 45", true), t75 = VentanaDigitales.conmutador("RTTY 75", false);
    private final JCheckBox chkRev = new JCheckBox("REV (invertido)"), chkSql = new JCheckBox("Silenciador", true);
    // Nivel del silenciador (como MMTTY): a la derecha corta más ruido, a la izquierda deja pasar señales más flojas
    private final JSlider sSql = new JSlider(0, 100, LibroGuardiaUI.ConfigApp.getInt("RTTY_SQL", 20));
    private String nombre = "", qth = "";
    private int paso = 0;   // 0 nada · 2 responder · 3 informe · 4 despedida
    private long inicioQso = 0;

    private static final String[][] MACROS_DEF = {
            {"CQ", "CQ CQ CQ DE <YO> <YO> <YO> PSE K"},
            {"Responder", "<SU> DE <YO> <YO> K"},
            {"599 + datos", "<SU> DE <YO> TNX UR <RST> <RST> NAME <MINOMBRE> QTH <MIQTH> <SU> DE <YO> K"},
            {"TU 73", "<SU> DE <YO> TNX FER QSO 73 SK"},
            {"Mi información", "RIG <EQUIPO> ANT <ANTENA> LOC <MILOC>"},
            {"QRZ?", "QRZ? DE <YO> K"},
            {"AGN?", "AGN? AGN? <SU> DE <YO> K"},
            {"Libre", ""}};

    /** Parte de abajo de DIGITALES cuando se elige RTTY (la de arriba, con frecuencia, bandas y reloj, es la misma). */
    VentanaRtty(LibroGuardiaUI app, VentanaDigitales dig) {
        super(new BorderLayout());
        this.app = app;
        this.dig = dig;
        b75 = "75".equals(LibroGuardiaUI.ConfigApp.get("RTTY_BAUDIOS", "45"));
        rev = "1".equals(LibroGuardiaUI.ConfigApp.get("RTTY_REV", "0"));
        try { centro = Double.parseDouble(LibroGuardiaUI.ConfigApp.get("RTTY_CENTRO", "1585")); } catch (Exception ignored) { }
        construir();
        new Timer(1000, e -> { if (isShowing()) refrescarCabecera(); }).start();
        pintarPasos();
    }

    /** Al elegir RTTY arriba: empieza a escuchar. */
    void activar() { if (!escuchando) arrancar(); }

    /** Al cambiar a FT8/FT4 o cerrar DIGITALES: corta la transmisión y deja de escuchar. */
    void desactivar() { cortarTx = true; parar(); }

    // ------------------------------------------------------------------ pantalla
    private JLabel lbl(String t, int tam, Color c, boolean negrita) {
        return VentanaDigitales.lbl(t, VentanaDigitales.f(negrita ? Font.BOLD : Font.PLAIN, tam), c);
    }

    private JPanel caja(String titulo, JComponent dentro) { return VentanaDigitales.caja(titulo, dentro); }

    private JButton boton(String t) { return VentanaDigitales.boton(t); }

    /** Botón de mensaje rápido: como los de DIGITALES y, si es el paso que toca, en ámbar (como «Responder»). */
    private static JButton botonPaso(String texto) {
        JButton b = new JButton(texto) {
            @Override protected void paintComponent(Graphics g0) {
                if (!Boolean.TRUE.equals(getClientProperty("toca"))) { super.paintComponent(g0); return; }
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, new Color(255, 196, 70), 0, getHeight(), new Color(230, 150, 10)));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g.setColor(new Color(120, 80, 0));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g.setColor(new Color(27, 20, 4));
                g.setFont(getFont());
                FontMetrics fm = g.getFontMetrics();
                String t = getText();
                g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                g.dispose();
            }
        };
        b.setUI(new ControlEmisora.BotonEmisoraUI());
        b.setFont(VentanaDigitales.f(Font.BOLD, 13));
        b.setFocusable(false);
        return b;
    }

    /** Botón rojo grande, igual que «PARAR TX» de DIGITALES. */
    private static JButton botonRojo(String texto) {
        JButton b = new JButton(texto) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, new Color(205, 40, 40), 0, getHeight(), new Color(140, 20, 20)));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.setColor(new Color(60, 5, 5));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.setColor(Color.WHITE);
                g.setFont(VentanaDigitales.f(Font.BOLD, 20));
                FontMetrics fm = g.getFontMetrics();
                String t = getText();
                g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            }
        };
        b.setPreferredSize(new Dimension(100, 58));
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setFocusable(false);
        return b;
    }

    /** Campo de texto como los de DIGITALES. */
    private static void campo(JTextComponent t) {
        t.setBackground(CAMPO);
        t.setForeground(TEXTO);
        t.setCaretColor(TEXTO);
        t.setFont(VentanaDigitales.f(Font.BOLD, 13));
        t.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINEA), BorderFactory.createEmptyBorder(2, 4, 2, 4)));
    }

    private void construir() {
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.setBackground(FONDO);

        // 45 / 75 baudios (la cabecera, con frecuencia, bandas y reloj, es la de DIGITALES)
        ButtonGroup g = new ButtonGroup();
        g.add(t45); g.add(t75);
        t75.setSelected(b75); t45.setSelected(!b75);
        t45.addActionListener(e -> ponerBaudios(false));
        t75.addActionListener(e -> ponerBaudios(true));
        lblNivel.setFont(VentanaDigitales.f(Font.BOLD, 11));
        lblNivel.setForeground(GRIS);

        // Cascada + ajustes
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        cascada.setPreferredSize(new Dimension(800, 170));
        JPanel opc = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        opc.setOpaque(false);
        chkRev.setSelected(rev);
        for (JCheckBox c : new JCheckBox[]{chkRev, chkSql}) { c.setOpaque(false); c.setForeground(TEXTO); c.setFont(VentanaDigitales.f(Font.BOLD, 12)); c.setFocusable(false); opc.add(c); }
        chkRev.addActionListener(e -> { rev = chkRev.isSelected(); LibroGuardiaUI.ConfigApp.put("RTTY_REV", rev ? "1" : "0"); ajustarDemod(); });
        chkSql.addActionListener(e -> { sSql.setEnabled(chkSql.isSelected()); ajustarDemod(); });
        sSql.setOpaque(false);
        sSql.setPreferredSize(new Dimension(120, 22));
        sSql.setFocusable(false);
        sSql.setToolTipText("Nivel del silenciador: a la derecha salen menos letras sueltas por el ruido; a la izquierda se leen señales más débiles");
        sSql.addChangeListener(e -> {
            ajustarDemod();
            if (!sSql.getValueIsAdjusting()) LibroGuardiaUI.ConfigApp.putInt("RTTY_SQL", sSql.getValue());
        });
        opc.add(sSql);
        opc.add(t45); opc.add(t75); opc.add(lblNivel);
        opc.add(lbl("Desplazamiento 170 Hz · pincha en la señal para sintonizarla (las rayas rojas son MARK y SPACE)", 12, TEXTO, false));
        JPanel cascCaja = new JPanel(new BorderLayout());
        cascCaja.setOpaque(false);
        cascCaja.add(cascada);
        cascCaja.add(opc, BorderLayout.SOUTH);
        arriba.add(caja("CASCADA", cascCaja));

        // Texto recibido
        rx.setEditable(false);
        rx.setBackground(CAMPO);
        rx.setForeground(TEXTO);
        rx.setFont(MONO);
        rx.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        rx.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { pincharPalabra(e.getPoint()); }
        });
        JScrollPane spRx = new JScrollPane(rx);
        spRx.setBorder(BorderFactory.createLineBorder(LINEA));
        spRx.getViewport().setBackground(CAMPO);
        JPanel izq = new JPanel(new BorderLayout());
        izq.setOpaque(false);
        izq.add(caja("TEXTO RECIBIDO · pincha en un indicativo para pasarlo al contacto", spRx), BorderLayout.CENTER);

        // Transmitir
        txt.setLineWrap(true);
        txt.setBackground(CAMPO);
        txt.setForeground(TX);
        txt.setCaretColor(TEXTO);
        txt.setFont(MONO);
        JPanel pTx = new JPanel(new BorderLayout(0, 6));
        pTx.setOpaque(false);
        pTx.add(new JScrollPane(txt), BorderLayout.CENTER);
        JPanel pm = new JPanel(new GridLayout(2, 4, 6, 6));
        pm.setOpaque(false);
        for (int i = 0; i < 8; i++) {
            final int k = i;
            macros[i] = botonPaso("F" + (i + 1) + "  " + macroNombre(i));
            macros[i].addActionListener(e -> lanzarMacro(k));
            pm.add(macros[i]);
            registerKeyboardAction(e -> lanzarMacro(k), KeyStroke.getKeyStroke(KeyEvent.VK_F1 + i, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
        pTx.add(pm, BorderLayout.SOUTH);
        izq.add(caja("LO QUE VAS A TRANSMITIR · escribe y pulsa TRANSMITIR, o usa un mensaje rápido (F1…F8)", pTx), BorderLayout.SOUTH);

        // Contacto + mandos
        JPanel ctc = new JPanel(new GridBagLayout());
        ctc.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        for (JTextField t : new JTextField[]{cInd, cRstS, cRstR, cInter, cCom}) campo(t);
        cInd.setFont(VentanaDigitales.f(Font.BOLD, 30));
        cInd.setForeground(AMBAR);
        cInd.setBackground(PANEL);
        cInd.setBorder(BorderFactory.createEmptyBorder());
        cInd.addActionListener(e -> ponerContacto(cInd.getText()));
        cNombre.setForeground(TEXTO);
        cNombre.setFont(VentanaDigitales.f(Font.BOLD, 14));
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; ctc.add(cInd, c);
        c.gridy = 1; ctc.add(cNombre, c);
        c.gridwidth = 1;
        Object[][] campos = {{"RST enviado", cRstS}, {"RST recibido", cRstR}, {"Intercambio", cInter}, {"Comentario", cCom}};
        int y = 2;
        for (Object[] f : campos) {
            c.gridx = 0; c.gridy = y; c.weightx = 0; ctc.add(lbl((String) f[0], 13, TEXTO, false), c);
            c.gridx = 1; c.weightx = 1; ctc.add((Component) f[1], c);
            y++;
        }
        JPanel bc = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bc.setOpaque(false);
        JButton bGuardar = new JButton("Guardar en el LOG"), bBorrar = boton("Borrar"), bQrz = boton("Ver en QRZ.com");
        CuentaBitacora.pintarCrearCuenta(bGuardar);   // ámbar, como «Responder» en DIGITALES
        bGuardar.addActionListener(e -> guardar());
        bBorrar.addActionListener(e -> borrarContacto());
        bQrz.addActionListener(e -> {
            String s = cInd.getText().trim();
            if (!s.isEmpty()) try { Desktop.getDesktop().browse(new java.net.URI("https://www.qrz.com/db/" + s)); } catch (Exception ignored) { }
        });
        bc.add(bGuardar); bc.add(bBorrar); bc.add(bQrz);
        c.gridx = 0; c.gridy = y; c.gridwidth = 2; ctc.add(bc, c);

        JPanel mandos = new JPanel(new BorderLayout(0, 8));
        mandos.setOpaque(false);
        JPanel fm = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fm.setOpaque(false);
        JButton bAfinar = boton("AFINAR"), bEditar = boton("EDITAR MENSAJES…");
        bEscuchar.setForeground(VERDE);
        bEscuchar.addActionListener(e -> { if (escuchando) parar(); else arrancar(); });
        bAfinar.addActionListener(e -> afinar());
        bEditar.addActionListener(e -> editarMacros());
        fm.add(bEscuchar); fm.add(bAfinar); fm.add(bEditar);
        mandos.add(fm, BorderLayout.NORTH);
        bTx.addActionListener(e -> {
            if (transmitiendo.get()) cortarTx = true;
            else transmitirTexto(txt.getText(), 0);
        });
        mandos.add(bTx, BorderLayout.CENTER);
        JLabel nota = lbl("<html>Transmite por la tarjeta de sonido (emisora en DATA) con el PTT de DIGITALES.<br>"
                + "Descodificador basado en MMTTY (JE3HHT, licencia LGPL).</html>", 11, GRIS, false);
        mandos.add(nota, BorderLayout.SOUTH);

        JPanel derecha = new JPanel(new BorderLayout());
        derecha.setOpaque(false);
        derecha.setPreferredSize(new Dimension(430, 100));
        derecha.add(caja("CONTACTO EN CURSO", ctc), BorderLayout.NORTH);
        derecha.add(caja("MANDOS", mandos), BorderLayout.SOUTH);

        JPanel centro = new JPanel(new BorderLayout());
        centro.setOpaque(false);
        centro.add(izq, BorderLayout.CENTER);
        centro.add(derecha, BorderLayout.EAST);

        JPanel cuerpo = new JPanel(new BorderLayout());
        cuerpo.setOpaque(false);
        cuerpo.add(arriba, BorderLayout.NORTH);
        cuerpo.add(centro, BorderLayout.CENTER);
        raiz.add(cuerpo, BorderLayout.CENTER);
        lblEstado.setFont(VentanaDigitales.f(Font.BOLD, 12));
        lblEstado.setForeground(TEXTO);
        lblEstado.setOpaque(true);
        lblEstado.setBackground(new Color(16, 23, 35));
        lblEstado.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        raiz.add(lblEstado, BorderLayout.SOUTH);
        add(raiz);
    }

    private void refrescarCabecera() {
        RttyDemod d = demod;
        if (d != null && escuchando) lblNivel.setText(String.format("Señal %.0f", d.nivel() / 100));
        else lblNivel.setText("Señal —");
    }

    // ------------------------------------------------------------------ recepción
    private double markHz() { return rev ? centro - SHIFT / 2 : centro + SHIFT / 2; }
    private double spaceHz() { return rev ? centro + SHIFT / 2 : centro - SHIFT / 2; }

    private void ponerBaudios(boolean es75) {
        b75 = es75;
        LibroGuardiaUI.ConfigApp.put("RTTY_BAUDIOS", es75 ? "75" : "45");
        ajustarDemod();
    }

    private void ajustarDemod() {
        RttyDemod d = demod;
        if (d == null) return;
        d.ponerBaudios(b75 ? 75 : 45.45);
        d.ponerTonos(markHz(), spaceHz());
        d.ponerInvertido(false);
        d.ponerSilenciador(chkSql.isSelected());
        d.ponerNivelSilenciador(100 * Math.pow(10, sSql.getValue() / 50.0));   // 40 ≈ 600, el valor de MMTTY
        cascada.repaint();
    }

    private void arrancar() {
        boolean compartido = false;
        try {
            fuente = MotorDigitales.tarjeta(LibroGuardiaUI.ConfigApp.get("DIGITALES_ENTRADA", ""), VentanaDigitales.canalEntrada());
        } catch (Exception ex) {
            fuente = MotorDigitales.audioDeDigitales();   // la tarjeta la está usando DIGITALES: se usa su mismo audio
            compartido = true;
        }
        demod = new RttyDemod(FS, ch -> SwingUtilities.invokeLater(() -> anadir(String.valueOf(ch), false)));
        ajustarDemod();
        escuchando = true;
        bEscuchar.setText("PARAR");
        lblEstado.setText(compartido ? "Escuchando con el audio de DIGITALES (deja DIGITALES en ESCUCHAR)." : "Escuchando.");
        Thread h = new Thread(this::bucle, "RTTY-RX");
        h.setDaemon(true);
        h.start();
    }

    private void parar() {
        escuchando = false;
        bEscuchar.setText("ESCUCHAR");
        MotorDigitales.Fuente f = fuente;
        fuente = null;
        if (f != null) try { f.close(); } catch (Exception ignored) { }
    }

    private void bucle() {
        float[] buf = new float[600];
        int n2 = 2048;
        double[] ventana = new double[n2];
        int lleno = 0;
        while (escuchando) {
            MotorDigitales.Fuente f = fuente;
            if (f == null) break;
            int n;
            try { n = f.leer(buf); } catch (Exception e) { break; }
            if (n <= 0) continue;
            RttyDemod d = demod;
            if (d != null && !transmitiendo.get()) d.procesar(buf, n);
            // Cascada: una línea cada 2048 muestras (~0,17 s)
            for (int i = 0; i < n; i++) {
                ventana[lleno++] = buf[i];
                if (lleno == n2) {
                    double[] re = new double[n2], im = new double[n2];
                    for (int k = 0; k < n2; k++) re[k] = ventana[k] * (0.5 - 0.5 * Math.cos(2 * Math.PI * k / n2));
                    Fft.de(n2).transformar(re, im, false);
                    float[] db = new float[n2 / 2];
                    for (int k = 0; k < n2 / 2; k++) db[k] = (float) (10 * Math.log10(re[k] * re[k] + im[k] * im[k] + 1e-9));
                    SwingUtilities.invokeLater(() -> cascada.linea(db));
                    lleno = 0;
                }
            }
        }
    }

    private void anadir(String s, boolean propio) {
        StyledDocument doc = rx.getStyledDocument();
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, propio ? TX : TEXTO);
        try {
            if (doc.getLength() > 60000) doc.remove(0, 20000);
            doc.insertString(doc.getLength(), s, a);
        } catch (BadLocationException ignored) { }
        rx.setCaretPosition(doc.getLength());
    }

    /** Clic en el texto: si la palabra parece un indicativo, pasa al contacto. */
    private void pincharPalabra(Point p) {
        int pos = rx.viewToModel2D(p);
        String t = rx.getText().replace("\r", "");
        if (pos < 0 || pos >= t.length()) return;
        int a = pos, b = pos;
        while (a > 0 && !Character.isWhitespace(t.charAt(a - 1))) a--;
        while (b < t.length() && !Character.isWhitespace(t.charAt(b))) b++;
        String w = t.substring(a, b).replaceAll("[^A-Z0-9/]", "");
        if (w.matches("([A-Z0-9]{1,4}/)?[A-Z0-9]{1,3}[0-9][A-Z0-9]*[A-Z](/[A-Z0-9]{1,4})?") && !w.equals(miIndicativo())) ponerContacto(w);
    }

    // ------------------------------------------------------------------ contacto
    private String miIndicativo() { return dig.miIndicativo(); }

    private void ponerContacto(String call) {
        call = call == null ? "" : call.trim().toUpperCase();
        if (call.isEmpty()) return;
        cInd.setText(call);
        nombre = ""; qth = "";
        cNombre.setText("buscando…");
        inicioQso = System.currentTimeMillis();
        paso = 2;
        pintarPasos();
        final String c = call;
        new Thread(() -> {
            String[] d = app.datosCompletosIndicativo(c, false);
            String[] p = LibroGuardiaUI.paisDeIndicativo(c, "20m");
            SwingUtilities.invokeLater(() -> {
                if (!c.equals(cInd.getText().trim().toUpperCase())) return;
                nombre = d == null ? "" : d[0];
                qth = d == null ? "" : d[1];
                String pais = p == null ? "" : p[1];
                cNombre.setText((nombre + (qth.isEmpty() ? "" : " · " + qth) + (pais.isEmpty() ? "" : " · " + pais)).trim());
            });
        }, "RTTY-datos").start();
    }

    private void borrarContacto() {
        cInd.setText(""); cNombre.setText(" "); cInter.setText(""); cCom.setText("");
        cRstS.setText("599"); cRstR.setText("599");
        nombre = qth = "";
        paso = 0;
        pintarPasos();
    }

    private void guardar() {
        String call = cInd.getText().trim().toUpperCase();
        if (call.isEmpty()) { lblEstado.setText("Escribe o elige un indicativo antes de guardar."); return; }
        long dial = dig.frecuenciaEmisora();
        Instant ins = Instant.ofEpochMilli(inicioQso > 0 ? inicioQso : System.currentTimeMillis());
        StringBuilder a = new StringBuilder();
        adi(a, "CALL", call);
        adi(a, "MODE", "RTTY");
        adi(a, "RST_SENT", cRstS.getText().trim());
        adi(a, "RST_RCVD", cRstR.getText().trim());
        adi(a, "QSO_DATE", DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(ins));
        adi(a, "TIME_ON", DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC).format(ins));
        if (dial > 0) {
            adi(a, "FREQ", String.format(Locale.US, "%.6f", (dial + markHz()) / 1e6));
            String b = app.bandaPorFrecuencia(dial);
            if (b != null) adi(a, "BAND", b);
        }
        adi(a, "NAME", nombre);
        adi(a, "QTH", qth);
        String com = (cInter.getText().trim().isEmpty() ? "" : "Intercambio " + cInter.getText().trim() + " ") + cCom.getText().trim();
        adi(a, "COMMENT", com.trim());
        a.append("<EOR>");
        if (app.registrarQsoDigital(a.toString(), "RTTY")) {
            lblEstado.setText(call + " guardado en el LOG.");
            borrarContacto();
        } else lblEstado.setText("No se ha podido guardar " + call + " (mira la barra de abajo de la ventana principal).");
    }

    private static void adi(StringBuilder a, String et, String v) {
        if (v == null || v.isBlank()) return;
        a.append('<').append(et).append(':').append(v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append('>').append(v).append(' ');
    }

    // ------------------------------------------------------------------ mensajes rápidos
    private static String macroNombre(int i) { return LibroGuardiaUI.ConfigApp.get("RTTY_MACRO_NOMBRE_" + i, MACROS_DEF[i][0]); }
    private static String macroTexto(int i) { return LibroGuardiaUI.ConfigApp.get("RTTY_MACRO_" + i, MACROS_DEF[i][1]); }

    private String rellenar(String m) {
        String[] yo = app.leerDatosOperador(LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual);
        String loc = yo[4] == null ? "" : yo[4];
        return m.replace("<YO>", miIndicativo()).replace("<SU>", cInd.getText().trim().toUpperCase())
                .replace("<RST>", cRstS.getText().trim()).replace("<MINOMBRE>", yo[2] == null ? "" : yo[2].split(" ")[0])
                .replace("<MIQTH>", yo[3] == null ? "" : yo[3]).replace("<MILOC>", loc)
                .replace("<EQUIPO>", "").replace("<ANTENA>", "").replaceAll("\\s+", " ").trim();
    }

    private void lanzarMacro(int i) {
        String m = macroTexto(i);
        if (m.isBlank()) { txt.requestFocus(); return; }
        if (m.contains("<SU>") && cInd.getText().isBlank()) { lblEstado.setText("Primero elige el indicativo (pincha en él en el texto recibido)."); return; }
        transmitirTexto(rellenar(m), i + 1);
    }

    /** Paso siguiente en ámbar: 2 Responder (o F3 si ya contestó), 3 informe, 4 despedida. */
    private void pintarPasos() {
        for (int i = 0; i < 8; i++) {
            boolean toca = (paso == 2 && i == 1) || (paso == 3 && i == 2) || (paso == 4 && i == 3);
            macros[i].putClientProperty("toca", toca);
            macros[i].repaint();
        }
    }

    private void editarMacros() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 4));
        JTextField[] nom = new JTextField[8], tex = new JTextField[8];
        for (int i = 0; i < 8; i++) {
            JPanel f = new JPanel(new BorderLayout(6, 0));
            nom[i] = new JTextField(macroNombre(i), 12);
            tex[i] = new JTextField(macroTexto(i), 50);
            f.add(new JLabel("F" + (i + 1)), BorderLayout.WEST);
            JPanel d = new JPanel(new BorderLayout(4, 0));
            d.add(nom[i], BorderLayout.WEST);
            d.add(tex[i]);
            f.add(d);
            p.add(f);
        }
        p.add(new JLabel("<html>Se rellenan solos: &lt;YO&gt; tu indicativo · &lt;SU&gt; el suyo · &lt;RST&gt; · &lt;MINOMBRE&gt; · &lt;MIQTH&gt; · &lt;MILOC&gt;</html>"));
        if (JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(this), p, "Mensajes rápidos de RTTY", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        for (int i = 0; i < 8; i++) {
            LibroGuardiaUI.ConfigApp.put("RTTY_MACRO_NOMBRE_" + i, nom[i].getText().trim());
            LibroGuardiaUI.ConfigApp.put("RTTY_MACRO_" + i, tex[i].getText().trim());
            macros[i].setText("F" + (i + 1) + "  " + macroNombre(i));
        }
    }

    // ------------------------------------------------------------------ transmisión
    private void afinar() {
        if (transmitiendo.get()) { cortarTx = true; return; }
        transmitir(null, 0, 10_000);
    }

    private void transmitirTexto(String texto, int macro) {
        if (texto == null || texto.isBlank()) { lblEstado.setText("No hay nada que transmitir."); return; }
        if (transmitiendo.get()) { lblEstado.setText("Ya se está transmitiendo."); return; }
        transmitir(" " + texto.trim() + " ", macro, 0);
    }

    /** Transmite el texto (o, si es null, un tono de MARK durante «afinarMs»). */
    private void transmitir(String texto, int macro, long afinarMs) {
        transmitiendo.set(true);
        cortarTx = false;
        bTx.setText("■ PARAR TX");
        if (texto != null) anadir("\n" + texto.trim() + "\n", true);
        final double mk = markHz(), sp = spaceHz(), baud = b75 ? 75 : 45.45;
        Thread th = new Thread(() -> {
            EmisorDigitales.Altavoz linea = null;
            boolean ptt = false;
            boolean completo = false;
            try {
                linea = EmisorDigitales.abrirAltavoz(LibroGuardiaUI.ConfigApp.get("DIGITALES_SALIDA", ""));
                int fs = linea.tasa(), canales = linea.canales();
                double[] fase = {0};
                float[] a;
                if (texto == null) a = RttyMod.reposo((int) (fs * afinarMs / 1000), fs, mk, fase);
                else {
                    float[] lead = RttyMod.reposo(fs / 3, fs, mk, fase);   // un poco de MARK para que el otro se enganche
                    float[] cuerpo = RttyMod.audio(RttyMod.codigos(texto), fs, baud, mk, sp, fase);
                    a = new float[lead.length + cuerpo.length];
                    System.arraycopy(lead, 0, a, 0, lead.length);
                    System.arraycopy(cuerpo, 0, a, lead.length, cuerpo.length);
                }
                int nivel = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_NIVEL_TX", 60);
                double amp = Math.pow(10, ((nivel - 100) * 0.4) / 20.0) * 32000;
                dig.ponerPtt(true);
                ptt = true;
                Thread.sleep(150);
                SwingUtilities.invokeLater(() -> lblEstado.setText(texto == null ? "Afinando (tono de MARK)…" : "Transmitiendo…"));
                String canal = VentanaDigitales.canalSalida();
                int trozo = fs / 20;
                byte[] b = new byte[trozo * canales * 2];
                for (int i = 0; i < a.length && !cortarTx; i += trozo) {
                    int n = Math.min(trozo, a.length - i);
                    for (int k = 0; k < n; k++) {
                        int v = (int) Math.max(-32767, Math.min(32767, Math.round(a[i + k] * amp)));
                        for (int c = 0; c < canales; c++) {
                            boolean sale = canales < 2 || !("IZQ".equals(canal) && c == 1 || "DCHA".equals(canal) && c == 0);
                            int vc = sale ? v : 0, o = (k * canales + c) * 2;
                            b[o] = (byte) vc;
                            b[o + 1] = (byte) (vc >> 8);
                        }
                    }
                    linea.escribir(b, n * canales * 2);
                }
                linea.terminar(!cortarTx);
                linea = null;
                completo = !cortarTx;
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> lblEstado.setText("No se ha podido transmitir: " + ex.getMessage()));
            } finally {
                if (linea != null) try { linea.terminar(false); } catch (Exception ignored) { }
                if (ptt) try { dig.ponerPtt(false); } catch (Exception ignored) { }
                transmitiendo.set(false);
                final boolean ok = completo;
                SwingUtilities.invokeLater(() -> {
                    bTx.setText("TRANSMITIR");
                    if (!ok) { lblEstado.setText(texto == null ? "Afinado terminado." : "Transmisión cortada."); return; }
                    lblEstado.setText("Transmitido. Escuchando.");
                    if (macro == 0 && texto != null) txt.setText("");
                    // Siguiente paso
                    if (macro == 2) paso = 3;
                    else if (macro == 3) paso = 4;
                    else if (macro == 4) { guardar(); paso = 0; }
                    pintarPasos();
                });
            }
        }, "RTTY-TX");
        th.setDaemon(true);
        th.start();
    }

    // ------------------------------------------------------------------ cascada
    private final class Cascada extends JPanel {
        private static final double F0 = 200, F1 = 3000;
        private BufferedImage img;

        Cascada() {
            setBackground(Color.BLACK);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    double f = F0 + (F1 - F0) * e.getX() / Math.max(1, getWidth());
                    centro = Math.max(300, Math.min(2800, f));
                    LibroGuardiaUI.ConfigApp.put("RTTY_CENTRO", String.valueOf(Math.round(centro)));
                    ajustarDemod();
                    lblEstado.setText(String.format("Sintonizado: MARK %.0f Hz · SPACE %.0f Hz", markHz(), spaceHz()));
                }
            });
        }

        void linea(float[] db) {
            int w = Math.max(1, getWidth()), h = Math.max(1, getHeight() - 16);
            if (img == null || img.getWidth() != w || img.getHeight() != h) img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics g = img.getGraphics();
            g.copyArea(0, 0, w, h - 1, 0, 1);
            g.dispose();
            double hzBin = (double) FS / (db.length * 2);
            float max = -1e9f, min = 1e9f;
            for (int k = (int) (F0 / hzBin); k < F1 / hzBin && k < db.length; k++) { max = Math.max(max, db[k]); min = Math.min(min, db[k]); }
            float suelo = min + 6, techo = Math.max(suelo + 25, max);
            for (int x = 0; x < w; x++) {
                int k = (int) ((F0 + (F1 - F0) * x / w) / hzBin);
                float v = k < db.length ? (db[k] - suelo) / (techo - suelo) : 0;
                v = Math.max(0, Math.min(1, v));
                img.setRGB(x, 0, color(v));
            }
            repaint();
        }

        private int color(float v) {
            int r = (int) (255 * Math.max(0, Math.min(1, v * 2 - 1))), gg = (int) (255 * Math.max(0, Math.min(1, v * 1.6 - 0.4))),
                    b = (int) (90 + 165 * Math.min(1, v * 2));
            return (r << 16) | (gg << 8) | Math.min(255, b);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            if (img != null) g.drawImage(img, 0, 16, null);
            g.setColor(GRIS);
            g.setFont(VentanaDigitales.f(Font.PLAIN, 10));
            for (int f = 500; f < F1; f += 500) {
                int x = (int) ((f - F0) / (F1 - F0) * getWidth());
                g.drawString(f + " Hz", x + 2, 11);
            }
            g.setFont(VentanaDigitales.f(Font.BOLD, 10));
            for (int k = 0; k < 2; k++) {
                double f = k == 0 ? markHz() : spaceHz();
                int x = (int) ((f - F0) / (F1 - F0) * getWidth());
                g.setColor(new Color(255, 59, 59));
                g.fillRect(x - 1, 16, 2, getHeight() - 16);
                String t = (k == 0 ? "MARK " : "SPACE ") + Math.round(f);
                g.fillRect(x + 3, 18, g.getFontMetrics().stringWidth(t) + 6, 13);
                g.setColor(Color.WHITE);
                g.drawString(t, x + 6, 28);
            }
        }
    }
}
