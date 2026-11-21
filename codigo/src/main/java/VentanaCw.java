import digitales.EmisorDigitales;
import digitales.Fft;
import digitales.MotorDigitales;
import digitales.PttSerie;
import digitales.cw.CwDemod;
import digitales.cw.CwMod;
import digitales.cw.CwSerie;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ventana CW: descodifica la CW que llega por la tarjeta de sonido y transmite lo que se escribe, de dos maneras:
 *  - con tono de audio (emisora en USB o DATA, PTT de DIGITALES), como FT8 y RTTY;
 *  - manipulando la emisora en modo CW por la línea DTR o RTS de un puerto COM (como la llave).
 * Con un manipulador propio enchufado a la emisora no hace falta nada: Bitácora 26 solo descodifica.
 * El descodificador junta ideas de fldigi (GPL) y ggmorse (MIT): ver digitales/cw/CwDemod.java.
 * Se trabaja igual que en RTTY: F2 Responder → F3 informe → F4 TU 73 (guarda en el LOG), con el paso siguiente en ámbar.
 */
final class VentanaCw extends JPanel {

    // Mismos colores, letras y botones que la ventana DIGITALES
    private static final Color FONDO = VentanaDigitales.FONDO, PANEL = VentanaDigitales.FONDO2, CAMPO = VentanaDigitales.CAMPO,
            LINEA = VentanaDigitales.LINEA, TEXTO = VentanaDigitales.TEXTO, GRIS = VentanaDigitales.GRIS, AMBAR = VentanaDigitales.AMBAR,
            VERDE = VentanaDigitales.VERDE, TX = new Color(255, 122, 122);
    private static final Font MONO = new Font("Monospaced", Font.PLAIN, 14);
    private static final int FS = 12000;

    private final LibroGuardiaUI app;
    private final VentanaDigitales dig;

    // Recepción
    private volatile MotorDigitales.Fuente fuente;
    private volatile boolean escuchando;
    private volatile CwDemod demod;
    private double tono = 700;   // Hz de la señal en el audio
    private final Cascada cascada = new Cascada();

    // Transmisión
    private final AtomicBoolean transmitiendo = new AtomicBoolean(false);
    private volatile boolean cortarTx;
    private CwSerie serie;

    // Grabación de prueba (30 s de lo que entra, a 12000 muestras por segundo)
    private final JButton bGrabar = VentanaDigitales.boton("GRABAR");
    private volatile short[] grabacion, grabTx;   // canal izquierdo: lo que entra; derecho: lo que transmitimos
    private volatile int grabadas;
    private int txPos;

    // Pantalla
    private final JLabel lblNivel = new JLabel("Señal —"), lblPpm = new JLabel("— ppm"), lblEstado = new JLabel(" ");
    private final JTextPane rx = new JTextPane();
    private final JTextArea txt = new JTextArea(3, 40);
    private final JTextField cInd = new JTextField(10), cRstS = new JTextField("599", 5), cRstR = new JTextField("599", 5),
            cInter = new JTextField(8), cCom = new JTextField(14);
    private final JLabel cNombre = new JLabel(" ");
    private final JButton[] macros = new JButton[8];
    private final JButton bTx = botonRojo("TRANSMITIR"), bEscuchar = VentanaDigitales.boton("ESCUCHAR");
    private final JCheckBox chkSeguir = new JCheckBox("Seguir su velocidad", true), chkSql = new JCheckBox("Silenciador", true),
            chkAfc = new JCheckBox("AFC", true);
    // Nivel del silenciador: a la derecha corta más ruido, a la izquierda deja pasar señales más flojas
    private final JSlider sSql = new JSlider(0, 100, LibroGuardiaUI.ConfigApp.getInt("CW_SQL", 40));
    private final JSpinner spPpmRx = new JSpinner(new SpinnerNumberModel(LibroGuardiaUI.ConfigApp.getInt("CW_PPM_RX", 20), 5, 60, 1));
    private final JSpinner spPpmTx = new JSpinner(new SpinnerNumberModel(LibroGuardiaUI.ConfigApp.getInt("CW_PPM_TX", 20), 5, 50, 1));
    private final JComboBox<String> cbComo = new JComboBox<>(new String[]{"Tono de audio (emisora en USB/DATA)", "Manipular la emisora en CW (puerto COM)"});
    private final JComboBox<String> cbPuerto = new JComboBox<>(), cbLinea = new JComboBox<>(new String[]{"DTR", "RTS"});
    private final List<String> puertos = new ArrayList<>();
    private String nombre = "", qth = "";
    private int paso = 0;   // 0 nada · 2 responder · 3 informe · 4 despedida
    private long inicioQso = 0;

    private static final String[][] MACROS_DEF = {
            {"CQ", "CQ CQ CQ DE <YO> <YO> <YO> K"},
            {"Responder", "<SU> DE <YO> <YO> K"},
            {"599 + datos", "<SU> DE <YO> GM TNX FER CALL UR RST <RST> <RST> NAME <MINOMBRE> <MINOMBRE> QTH <MIQTH> <MIQTH> HW? <SU> DE <YO> <KN>"},
            {"TU 73", "<SU> DE <YO> TNX FER QSO 73 <SK> E E"},
            {"Mi información", "LOC <MILOC> <MILOC>"},
            {"QRZ?", "QRZ? DE <YO> K"},
            {"AGN?", "AGN? AGN? <SU> DE <YO> <KN>"},
            {"Libre", ""}};

    /** Parte de abajo de DIGITALES cuando se elige CW (la de arriba, con frecuencia, bandas y reloj, es la misma). */
    VentanaCw(LibroGuardiaUI app, VentanaDigitales dig) {
        super(new BorderLayout());
        this.app = app;
        this.dig = dig;
        try { tono = Double.parseDouble(LibroGuardiaUI.ConfigApp.get("CW_TONO", "700")); } catch (Exception ignored) { }
        construir();
        new Timer(1000, e -> { if (isShowing()) refrescarCabecera(); }).start();
        pintarPasos();
    }

    /** Al elegir CW arriba: empieza a escuchar. */
    void activar() { if (!escuchando) arrancar(); }

    /** Al cambiar a otro modo o cerrar DIGITALES: corta la transmisión, deja de escuchar y suelta el puerto COM. */
    void desactivar() {
        cortarTx = true;
        parar();
        CwSerie s = serie;
        serie = null;
        if (s != null) new Thread(s::close, "CW-cerrar-COM").start();
    }

    /** ¿Se transmite manipulando la emisora (modo CW) por puerto COM? */
    boolean manipulaPorCom() { return cbComo.getSelectedIndex() == 1; }

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

        lblNivel.setFont(VentanaDigitales.f(Font.BOLD, 11));
        lblNivel.setForeground(GRIS);
        lblPpm.setFont(VentanaDigitales.f(Font.BOLD, 14));
        lblPpm.setForeground(AMBAR);

        // Cascada + ajustes
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        cascada.setPreferredSize(new Dimension(800, 170));
        JPanel opc = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        opc.setOpaque(false);
        chkSeguir.setSelected(!"0".equals(LibroGuardiaUI.ConfigApp.get("CW_SEGUIR", "1")));
        spPpmRx.setEnabled(!chkSeguir.isSelected());
        spPpmRx.setToolTipText("Velocidad fija para descodificar (si no se sigue la del otro)");
        opc.add(lblPpm);
        for (JCheckBox c : new JCheckBox[]{chkSeguir}) { c.setOpaque(false); c.setForeground(TEXTO); c.setFont(VentanaDigitales.f(Font.BOLD, 12)); c.setFocusable(false); opc.add(c); }
        opc.add(spPpmRx);
        chkSeguir.addActionListener(e -> {
            spPpmRx.setEnabled(!chkSeguir.isSelected());
            LibroGuardiaUI.ConfigApp.put("CW_SEGUIR", chkSeguir.isSelected() ? "1" : "0");
            ajustarDemod();
        });
        spPpmRx.addChangeListener(e -> { LibroGuardiaUI.ConfigApp.putInt("CW_PPM_RX", (Integer) spPpmRx.getValue()); ajustarDemod(); });
        chkSql.setOpaque(false); chkSql.setForeground(TEXTO); chkSql.setFont(VentanaDigitales.f(Font.BOLD, 12)); chkSql.setFocusable(false);
        chkAfc.setSelected(!"0".equals(LibroGuardiaUI.ConfigApp.get("CW_AFC", "1")));
        chkAfc.setToolTipText("<html>AFC: la raya roja sigue sola a la señal si se mueve un poco (hasta 50 Hz),<br>"
                + "y si donde está no hay nada durante unos segundos, se va a la señal más clara</html>");
        chkAfc.setOpaque(false); chkAfc.setForeground(TEXTO); chkAfc.setFont(VentanaDigitales.f(Font.BOLD, 12)); chkAfc.setFocusable(false);
        chkAfc.addActionListener(e -> LibroGuardiaUI.ConfigApp.put("CW_AFC", chkAfc.isSelected() ? "1" : "0"));
        opc.add(chkAfc);
        opc.add(chkSql);
        chkSql.addActionListener(e -> { sSql.setEnabled(chkSql.isSelected()); ajustarDemod(); });
        sSql.setOpaque(false);
        sSql.setPreferredSize(new Dimension(120, 22));
        sSql.setFocusable(false);
        sSql.setToolTipText("Nivel del silenciador: a la derecha salen menos letras sueltas por el ruido; a la izquierda se leen señales más débiles");
        sSql.addChangeListener(e -> {
            ajustarDemod();
            if (!sSql.getValueIsAdjusting()) LibroGuardiaUI.ConfigApp.putInt("CW_SQL", sSql.getValue());
        });
        opc.add(sSql);
        opc.add(lblNivel);
        opc.add(lbl("Pincha en la señal para sintonizarla (la raya roja)", 12, TEXTO, false));
        JLabel lblPruebas = lbl("EN PRUEBAS", 11, AMBAR, true);
        lblPruebas.setToolTipText("<html>El descodificador de CW ya lee bien las señales fuertes y limpias.<br>"
                + "Con señales débiles, con QSB o con CW hecho a mano todavía falla: estamos mejorándolo en cada versión.</html>");
        opc.add(lblPruebas);
        // GRABAR sirve para mejorar el descodificador: solo lo ve quien lo está entrenando (EA4IXR, o CW_GRABAR=1 en la configuración)
        if ("EA4IXR".equalsIgnoreCase(CuentaBitacora.baseIndicativo(LibroGuardiaUI.operadorActual))
                || "1".equals(LibroGuardiaUI.ConfigApp.get("CW_GRABAR", "0"))) opc.add(bGrabar);
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
        bGrabar.setToolTipText("<html>Graba lo que entra y lo que transmites (para revisar el descodificador):<br>"
                + "canal izquierdo = lo que llega de la emisora · canal derecho = lo que transmite Bitácora 26<br>"
                + "Un clic empieza y otro para (máximo 20 minutos). Se guarda en Documentos\\Libro de Guardia\\grabacion_cw_FECHA_HORA.wav</html>");
        bGrabar.addActionListener(e -> grabar());
        bEscuchar.setForeground(VERDE);
        bEscuchar.addActionListener(e -> { if (escuchando) parar(); else arrancar(); });
        bAfinar.addActionListener(e -> afinar());
        bEditar.addActionListener(e -> editarMacros());
        fm.add(bEscuchar); fm.add(bAfinar); fm.add(bEditar);
        // Cómo se transmite
        JPanel como = new JPanel(new GridBagLayout());
        como.setOpaque(false);
        GridBagConstraints cc = new GridBagConstraints();
        cc.insets = new Insets(2, 2, 2, 2);
        cc.anchor = GridBagConstraints.WEST;
        cc.fill = GridBagConstraints.HORIZONTAL;
        cbComo.setSelectedIndex("COM".equals(LibroGuardiaUI.ConfigApp.get("CW_TX_MODO", "AUDIO")) ? 1 : 0);
        cbComo.setToolTipText("<html>Tono de audio: escribes y Bitácora 26 manda un pitido que suena como CW (emisora en USB o DATA).<br>"
                + "Manipular la emisora: la emisora en modo CW y Bitácora 26 hace de llave por la línea DTR o RTS de un puerto COM.<br>"
                + "Con tu propio manipulador enchufado a la emisora no hace falta nada: Bitácora 26 descodifica lo que oyes.</html>");
        String lin = LibroGuardiaUI.ConfigApp.get("CW_LINEA", "DTR");
        cbLinea.setSelectedItem(lin);
        cbPuerto.setToolTipText("Puerto COM por el que se manipula (en la FT-891, el segundo puerto: «Standard COM Port»)");
        cbLinea.setToolTipText("Línea del puerto que hace de llave (tiene que coincidir con el menú de la emisora)");
        spPpmTx.setToolTipText("Velocidad a la que transmites (palabras por minuto)");
        cc.gridx = 0; cc.gridy = 0; como.add(lbl("Transmitir:", 12, TEXTO, true), cc);
        cc.gridx = 1; cc.gridwidth = 3; como.add(cbComo, cc);
        cc.gridwidth = 1;
        cc.gridx = 0; cc.gridy = 1; como.add(lbl("Puerto:", 12, TEXTO, false), cc);
        cc.gridx = 1; como.add(cbPuerto, cc);
        cc.gridx = 2; como.add(cbLinea, cc);
        cc.gridx = 0; cc.gridy = 2; como.add(lbl("Velocidad:", 12, TEXTO, false), cc);
        cc.gridx = 1; como.add(spPpmTx, cc);
        cc.gridx = 2; como.add(lbl("ppm", 12, TEXTO, false), cc);
        cbComo.addActionListener(e -> {
            LibroGuardiaUI.ConfigApp.put("CW_TX_MODO", manipulaPorCom() ? "COM" : "AUDIO");
            mostrarPuerto();
            if (manipulaPorCom() && puertos.isEmpty()) buscarPuertos();
        });
        cbPuerto.addActionListener(e -> {
            int i = cbPuerto.getSelectedIndex();
            if (i >= 0 && i < puertos.size()) LibroGuardiaUI.ConfigApp.put("CW_PUERTO", puertos.get(i));
        });
        cbLinea.addActionListener(e -> LibroGuardiaUI.ConfigApp.put("CW_LINEA", String.valueOf(cbLinea.getSelectedItem())));
        spPpmTx.addChangeListener(e -> LibroGuardiaUI.ConfigApp.putInt("CW_PPM_TX", (Integer) spPpmTx.getValue()));
        mostrarPuerto();
        if (manipulaPorCom()) buscarPuertos();
        JPanel arribaMandos = new JPanel(new BorderLayout(0, 6));
        arribaMandos.setOpaque(false);
        arribaMandos.add(fm, BorderLayout.NORTH);
        arribaMandos.add(como, BorderLayout.CENTER);
        mandos.add(arribaMandos, BorderLayout.NORTH);
        bTx.addActionListener(e -> {
            if (transmitiendo.get()) cortarTx = true;
            else transmitirTexto(txt.getText(), 0);
        });
        mandos.add(bTx, BorderLayout.CENTER);
        JLabel nota = lbl("<html>Con tu propio manipulador enchufado a la emisora no hace falta nada más: aquí ves lo que se oye.<br>"
                + "Descodificador <b>en pruebas</b>: lee bien las señales fuertes; con señales débiles o con QSB aún falla y lo estamos mejorando.<br>"
                + "Basado en fldigi (GPL) y ggmorse (MIT).</html>", 11, GRIS, false);
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
        CwDemod d = demod;
        if (d != null && escuchando) {
            lblNivel.setText(String.format("Señal %.0f", d.calidad()));
            lblPpm.setText(String.format("%.0f ppm", d.ppm()));
        } else { lblNivel.setText("Señal —"); lblPpm.setText("— ppm"); }
    }

    private void mostrarPuerto() {
        boolean com = manipulaPorCom();
        cbPuerto.setEnabled(com);
        cbLinea.setEnabled(com);
    }

    /** Lista de puertos COM (tarda un par de segundos: se hace aparte). */
    private void buscarPuertos() {
        cbPuerto.removeAllItems();
        cbPuerto.addItem("buscando…");
        new Thread(() -> {
            List<String[]> ps = PttSerie.puertos();
            SwingUtilities.invokeLater(() -> {
                puertos.clear();
                cbPuerto.removeAllItems();
                String guardado = LibroGuardiaUI.ConfigApp.get("CW_PUERTO", "");
                int sel = -1;
                for (String[] p : ps) {
                    puertos.add(p[0]);
                    cbPuerto.addItem(p[1]);
                    if (p[0].equals(guardado)) sel = puertos.size() - 1;
                }
                if (ps.isEmpty()) cbPuerto.addItem("(no hay puertos COM)");
                if (sel >= 0) cbPuerto.setSelectedIndex(sel);
            });
        }, "CW-puertos").start();
    }

    // ------------------------------------------------------------------ recepción
    private void ajustarDemod() {
        CwDemod d = demod;
        if (d == null) return;
        d.ponerTono(tono);
        d.ponerPpmFija(!chkSeguir.isSelected());
        if (!chkSeguir.isSelected()) d.ponerPpm((Integer) spPpmRx.getValue());
        d.ponerSilenciador(chkSql.isSelected());
        d.ponerNivelSilenciador(1.2 + sSql.getValue() / 50.0);   // 40 = 2,0 (con solo ruido salen unas 5 letras por minuto)
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
        demod = new CwDemod(t -> SwingUtilities.invokeLater(() -> anadir(t, false)));
        demod.ponerPpm(chkSeguir.isSelected() ? LibroGuardiaUI.ConfigApp.getInt("CW_PPM_TX", 20) : (Integer) spPpmRx.getValue());
        ajustarDemod();
        escuchando = true;
        bEscuchar.setText("PARAR");
        lblEstado.setText(compartido ? "Escuchando con el audio de DIGITALES (deja DIGITALES en ESCUCHAR)." : "Escuchando.");
        Thread h = new Thread(this::bucle, "CW-RX");
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
            CwDemod d = demod;
            if (d != null && !transmitiendo.get()) d.procesar(buf, n);
            short[] gr = grabacion;
            if (gr != null) {
                int g0 = grabadas, m = Math.min(n, gr.length - g0);
                for (int i = 0; i < m; i++) gr[g0 + i] = (short) Math.max(-32767, Math.min(32767, Math.round(buf[i])));
                grabadas = g0 + m;
                if (grabadas >= gr.length) { short[] tx = grabTx; grabacion = null; grabTx = null; guardarGrabacion(gr, tx); }
                else if (grabadas / (FS * 10) != g0 / (FS * 10)) SwingUtilities.invokeLater(() -> {
                    if (grabacion != null) { bGrabar.setText(String.format("■ PARAR GRABACIÓN (%d:%02d)", grabadas / FS / 60, grabadas / FS % 60)); apuntar(""); }
                });
            }
            // Cascada: una línea cada 2048 muestras (~0,17 s)
            for (int i = 0; i < n; i++) {
                ventana[lleno++] = buf[i];
                if (lleno == n2) {
                    double[] re = new double[n2], im = new double[n2];
                    for (int k = 0; k < n2; k++) re[k] = ventana[k] * (0.5 - 0.5 * Math.cos(2 * Math.PI * k / n2));
                    Fft.de(n2).transformar(re, im, false);
                    float[] db = new float[n2 / 2];
                    for (int k = 0; k < n2 / 2; k++) db[k] = (float) (10 * Math.log10(re[k] * re[k] + im[k] * im[k] + 1e-9));
                    SwingUtilities.invokeLater(() -> { cascada.linea(db); espectro(db); });
                    lleno = 0;
                }
            }
        }
    }

    // Espectro medio de los últimos ~2 segundos (para saber si hay señal y dónde)
    private float[] medio;
    private int sinSenal = 0;
    private long ultimoClic = 0;

    /** Con cada línea de la cascada: silenciador por espectro, AFC y, si no hay nada sintonizado, ir a la señal más clara. */
    private void espectro(float[] db) {
        if (medio == null || medio.length != db.length) medio = db.clone();
        else for (int k = 0; k < db.length; k++) medio[k] += 0.15f * (db[k] - medio[k]);
        double hzBin = (double) FS / (db.length * 2);
        // ruido: la mediana entre 300 y 2800 Hz (en CW la señal suele estar hacia 600-700 Hz, pero en USB/DATA puede estar en cualquier parte)
        int r0 = (int) (300 / hzBin), r1 = Math.min(medio.length - 1, (int) (2800 / hzBin));
        float[] v = java.util.Arrays.copyOfRange(medio, r0, r1);
        java.util.Arrays.sort(v);
        float ruido = v[v.length / 2];
        int kt = (int) Math.round(tono / hzBin);
        float aqui = -1e9f;
        for (int k = Math.max(1, kt - 2); k <= kt + 2 && k < medio.length; k++) aqui = Math.max(aqui, medio[k]);
        boolean hay = aqui > ruido + 4;
        CwDemod dm = demod;
        if (dm != null) dm.ponerHayTono(hay);
        if (!chkAfc.isSelected() || transmitiendo.get()) return;
        afc(db);
        // Si lo sintonizado no tiene señal durante ~3 s y hay una señal clara en la banda de paso, se va a ella
        sinSenal = hay ? 0 : sinSenal + 1;
        // Si la has elegido tú (pinchando) en los últimos 2 minutos, no se va a otra: la estación puede estar escuchando
        boolean elegidaHacePoco = System.currentTimeMillis() - ultimoClic < 120_000;
        if (sinSenal >= 18 && !elegidaHacePoco) {
            int mejor = -1;
            for (int k = r0; k <= r1; k++) if (mejor < 0 || medio[k] > medio[mejor]) mejor = k;
            if (mejor > 0 && medio[mejor] > ruido + 8) {
                tono = mejor * hzBin;
                if (dm != null) dm.ponerTono(tono);
                lblEstado.setText(String.format("AFC: señal encontrada en %.0f Hz.", tono));
                if (grabacion != null) apuntar("AFC");
                cascada.repaint();
            }
            sinSenal = 0;
        }
    }

    /** AFC: si hay una señal clara a menos de 50 Hz de la raya roja, la raya se va acercando a ella poco a poco. */
    private void afc(float[] db) {
        if (transmitiendo.get()) return;
        double hzBin = (double) FS / (db.length * 2);
        int k0 = (int) ((tono - 50) / hzBin), k1 = (int) ((tono + 50) / hzBin), mejor = -1;
        for (int k = Math.max(1, k0); k <= k1 && k < db.length - 1; k++) if (mejor < 0 || db[k] > db[mejor]) mejor = k;
        if (mejor < 1) return;
        // ruido: la mediana de 150 Hz alrededor
        int r0 = Math.max(1, (int) ((tono - 150) / hzBin)), r1 = Math.min(db.length - 1, (int) ((tono + 150) / hzBin));
        float[] v = java.util.Arrays.copyOfRange(db, r0, r1);
        java.util.Arrays.sort(v);
        if (v.length == 0 || db[mejor] < v[v.length / 2] + 10) return;   // nada claro: no se mueve
        double a = db[mejor - 1], b = db[mejor], c = db[mejor + 1], d = (a - c) / (2 * (a - 2 * b + c));
        double pico = (mejor + (Double.isFinite(d) ? Math.max(-0.5, Math.min(0.5, d)) : 0)) * hzBin;
        double nuevo = tono + 0.3 * (pico - tono);
        if (Math.abs(nuevo - tono) < 0.5) return;
        tono = nuevo;
        CwDemod dm = demod;
        if (dm != null) dm.ponerTono(tono);
        cascada.repaint();
    }

    private static final int MAX_GRABACION_S = 20 * 60;   // como mucho 20 minutos (unos 60 MB de memoria mientras graba)
    private String nombreGrabacion = "grabacion_cw";
    private final StringBuilder notasGrabacion = new StringBuilder();

    /** Un clic empieza a grabar y otro la para (o se para sola a los 20 minutos). Cada grabación con su fecha y hora. */
    private void grabar() {
        short[] gr = grabacion;
        if (gr != null) {   // parar y guardar lo grabado
            short[] tx = grabTx;
            int n = grabadas;
            grabacion = null;
            grabTx = null;
            new Thread(() -> guardarGrabacion(java.util.Arrays.copyOf(gr, n), tx == null ? null : java.util.Arrays.copyOf(tx, n)), "CW-guardar").start();
            return;
        }
        if (!escuchando) { lblEstado.setText("Pulsa ESCUCHAR antes de grabar."); return; }
        nombreGrabacion = "grabacion_cw_" + java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + "UTC";
        notasGrabacion.setLength(0);
        grabadas = 0;
        apuntar("empieza");
        txPos = 0;
        grabTx = new short[FS * MAX_GRABACION_S];
        grabacion = new short[FS * MAX_GRABACION_S];
        bGrabar.setText("■ PARAR GRABACIÓN");
        lblEstado.setText("Grabando… pulsa PARAR GRABACIÓN cuando quieras (como mucho 20 minutos).");
    }

    /** Apunta en las notas de la grabación el segundo, el tono y la frecuencia (para saber qué señal había en cada momento). */
    private void apuntar(String que) {
        CwDemod d = demod;
        notasGrabacion.append(String.format(Locale.US, "%7.1f s  %-8s tono=%.0f Hz  frecuencia=%d Hz  ppm=%.0f%n",
                grabadas / (double) FS, que, tono, dig.frecuenciaEmisora(), d == null ? 0 : d.ppm()));
    }

    /**
     * Si se está grabando, añade al canal derecho lo que se transmite (a[desde..desde+n), a «fsOrig» muestras por segundo),
     * pasado a 12000 por segundo. Va justo detrás de lo ya transmitido, o donde va la grabación si hubo una pausa.
     */
    private synchronized void grabarTx(float[] a, int desde, int n, double fsOrig, double amp) {
        short[] tx = grabTx;
        if (tx == null) return;
        if (txPos < grabadas - FS / 3 || txPos > grabadas + FS) txPos = grabadas;
        int m = (int) (n * FS / fsOrig);
        for (int k = 0; k < m && txPos < tx.length; k++, txPos++) {
            int src = desde + (int) (k * fsOrig / FS);
            if (src < a.length) tx[txPos] = (short) Math.max(-32767, Math.min(32767, Math.round(a[src] * amp)));
        }
    }

    /** Guarda la grabación como WAV (16 bits, estéreo, 12000 Hz) y apunta en un texto el tono y la velocidad de ese momento. */
    private void guardarGrabacion(short[] gr, short[] tx) {
        File dir = new File(System.getProperty("user.home"), "Documents" + File.separator + LibroGuardiaUI.NOMBRE_APP);
        dir.mkdirs();
        File f = new File(dir, nombreGrabacion + ".wav");
        String msg;
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            int datos = gr.length * 4;
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + datos)); o.writeBytes("WAVE");
            o.writeBytes("fmt "); o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1));
            o.writeShort(Short.reverseBytes((short) 2)); o.writeInt(Integer.reverseBytes(FS)); o.writeInt(Integer.reverseBytes(FS * 4));
            o.writeShort(Short.reverseBytes((short) 4)); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(datos));
            for (int i = 0; i < gr.length; i++) {
                o.writeShort(Short.reverseBytes(gr[i]));
                o.writeShort(Short.reverseBytes(tx == null ? 0 : tx[i]));
            }
            CwDemod d = demod;
            apuntar("termina");
            java.nio.file.Files.writeString(new File(dir, nombreGrabacion + ".txt").toPath(), notasGrabacion + 
                    String.format(Locale.US, "%n") +
                    String.format(Locale.US, "tono=%.0f Hz%nppm=%.0f%nfrecuencia=%d Hz%nppm_tx=%d%ntransmitir=%s%n"
                                    + "canal izquierdo = recibido, canal derecho = transmitido%n",
                            tono, d == null ? 0 : d.ppm(), dig.frecuenciaEmisora(), (Integer) spPpmTx.getValue(), manipulaPorCom() ? "COM" : "AUDIO"));
            msg = "Grabación guardada en " + f.getPath();
        } catch (Exception ex) {
            msg = "No se ha podido guardar la grabación: " + ex.getMessage();
        }
        final String m = msg;
        SwingUtilities.invokeLater(() -> { bGrabar.setText("GRABAR"); lblEstado.setText(m); });
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
        }, "CW-datos").start();
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
        adi(a, "MODE", "CW");
        adi(a, "RST_SENT", cRstS.getText().trim());
        adi(a, "RST_RCVD", cRstR.getText().trim());
        adi(a, "QSO_DATE", DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(ins));
        adi(a, "TIME_ON", DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC).format(ins));
        if (dial > 0) {
            // Manipulando en modo CW la emisora ya dice la frecuencia buena; con tono de audio (USB) se le suma el tono
            adi(a, "FREQ", String.format(Locale.US, "%.6f", (manipulaPorCom() ? dial : dial + tono) / 1e6));
            String b = app.bandaPorFrecuencia(dial);
            if (b != null) adi(a, "BAND", b);
        }
        adi(a, "NAME", nombre);
        adi(a, "QTH", qth);
        String com = (cInter.getText().trim().isEmpty() ? "" : "Intercambio " + cInter.getText().trim() + " ") + cCom.getText().trim();
        adi(a, "COMMENT", com.trim());
        a.append("<EOR>");
        if (app.registrarQsoDigital(a.toString(), "CW")) {
            lblEstado.setText(call + " guardado en el LOG.");
            borrarContacto();
        } else lblEstado.setText("No se ha podido guardar " + call + " (mira la barra de abajo de la ventana principal).");
    }

    private static void adi(StringBuilder a, String et, String v) {
        if (v == null || v.isBlank()) return;
        a.append('<').append(et).append(':').append(v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append('>').append(v).append(' ');
    }

    // ------------------------------------------------------------------ mensajes rápidos
    private static String macroNombre(int i) { return LibroGuardiaUI.ConfigApp.get("CW_MACRO_NOMBRE_" + i, MACROS_DEF[i][0]); }
    private static String macroTexto(int i) { return LibroGuardiaUI.ConfigApp.get("CW_MACRO_" + i, MACROS_DEF[i][1]); }

    private String rellenar(String m) {
        String[] yo = app.leerDatosOperador(LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual);
        String loc = yo[4] == null ? "" : yo[4];
        return m.replace("<YO>", miIndicativo()).replace("<SU>", cInd.getText().trim().toUpperCase())
                .replace("<RST>", cRstS.getText().trim()).replace("<MINOMBRE>", yo[2] == null ? "" : yo[2].split(" ")[0])
                .replace("<MIQTH>", yo[3] == null ? "" : yo[3]).replace("<MILOC>", loc)
                .replace("<EQUIPO>", "").replace("<ANTENA>", "").replaceAll("\\s+", " ").trim();
    }

    private void lanzarMacro(int i) {
        if (!isShowing()) return;   // las teclas F1…F8 son de la parte (RTTY o CW) que se está viendo
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
        p.add(new JLabel("<html>Se rellenan solos: &lt;YO&gt; tu indicativo · &lt;SU&gt; el suyo · &lt;RST&gt; · &lt;MINOMBRE&gt; · &lt;MIQTH&gt; · &lt;MILOC&gt;<br>"
                + "Señales de procedimiento (van seguidas, sin espacio entre letras): &lt;SK&gt; &lt;KN&gt; &lt;AR&gt; &lt;BT&gt;</html>"));
        if (JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(this), p, "Mensajes rápidos de CW", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        for (int i = 0; i < 8; i++) {
            LibroGuardiaUI.ConfigApp.put("CW_MACRO_NOMBRE_" + i, nom[i].getText().trim());
            LibroGuardiaUI.ConfigApp.put("CW_MACRO_" + i, tex[i].getText().trim());
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
        transmitir(texto.trim(), macro, 0);
    }

    /** Transmite el texto (o, si es null, tono o llave apretada durante «afinarMs»). */
    private void transmitir(String texto, int macro, long afinarMs) {
        transmitiendo.set(true);
        cortarTx = false;
        bTx.setText("■ PARAR TX");
        if (texto != null) anadir("\n" + texto + "\n", true);
        final double ppm = (Integer) spPpmTx.getValue(), hz = tono;
        final boolean porCom = manipulaPorCom();
        final int ip = cbPuerto.getSelectedIndex();
        final String puerto = ip >= 0 && ip < puertos.size() ? puertos.get(ip) : LibroGuardiaUI.ConfigApp.get("CW_PUERTO", "");
        final boolean rts = "RTS".equals(cbLinea.getSelectedItem());
        Thread th = new Thread(() -> {
            boolean completo = false;
            try {
                if (porCom) completo = transmitirPorCom(texto, afinarMs, ppm, puerto, rts);
                else completo = transmitirPorAudio(texto, afinarMs, ppm, hz);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> lblEstado.setText("No se ha podido transmitir: " + ex.getMessage()));
            } finally {
                transmitiendo.set(false);
                final boolean ok = completo;
                SwingUtilities.invokeLater(() -> {
                    bTx.setText("TRANSMITIR");
                    if (!ok) { if (!lblEstado.getText().startsWith("No se ha podido")) lblEstado.setText(texto == null ? "Afinado terminado." : "Transmisión cortada."); return; }
                    lblEstado.setText("Transmitido. Escuchando.");
                    if (macro == 0 && texto != null) txt.setText("");
                    if (macro == 2) paso = 3;
                    else if (macro == 3) paso = 4;
                    else if (macro == 4) { guardar(); paso = 0; }
                    pintarPasos();
                });
            }
        }, "CW-TX");
        th.setDaemon(true);
        th.start();
    }

    /** Tono de audio por la salida de DIGITALES, con su PTT (emisora en USB o DATA). */
    private boolean transmitirPorAudio(String texto, long afinarMs, double ppm, double hz) throws Exception {
        EmisorDigitales.Altavoz linea = null;
        boolean ptt = false;
        try {
            linea = EmisorDigitales.abrirAltavoz(LibroGuardiaUI.ConfigApp.get("DIGITALES_SALIDA", ""));
            int fs = linea.tasa(), canales = linea.canales();
            List<Double> el = new ArrayList<>();
            if (texto == null) el.add(afinarMs / (1200.0 / ppm));
            else el.addAll(CwMod.elementos(texto, 1));
            float[] a = CwMod.audio(el, fs, ppm, hz, 5);
            int nivel = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_NIVEL_TX", 60);
            double amp = Math.pow(10, ((nivel - 100) * 0.4) / 20.0) * 32000;
            dig.ponerPtt(true);
            ptt = true;
            Thread.sleep(150);
            SwingUtilities.invokeLater(() -> lblEstado.setText(texto == null ? "Afinando (tono continuo)…" : "Transmitiendo…"));
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
                grabarTx(a, i, n, fs, amp);
                linea.escribir(b, n * canales * 2);
            }
            linea.terminar(!cortarTx);
            linea = null;
            return !cortarTx;
        } finally {
            if (linea != null) try { linea.terminar(false); } catch (Exception ignored) { }
            if (ptt) try { dig.ponerPtt(false); } catch (Exception ignored) { }
        }
    }

    /**
     * Manipulando la emisora (en modo CW) por DTR o RTS. Se manda letra a letra, así «PARAR TX» corta enseguida.
     * La emisora pasa sola a transmisión con la llave (BK-IN / semi break-in encendido).
     */
    private boolean transmitirPorCom(String texto, long afinarMs, double ppm, String puerto, boolean rts) throws Exception {
        CwSerie s = serie;
        if (s == null || !s.es(puerto, rts)) {
            if (s != null) s.close();
            SwingUtilities.invokeLater(() -> lblEstado.setText("Abriendo " + puerto + "…"));
            s = new CwSerie(puerto, rts);
            serie = s;
        }
        SwingUtilities.invokeLater(() -> lblEstado.setText(texto == null ? "Afinando (llave apretada)…" : "Transmitiendo por " + puerto + "…"));
        if (texto == null) {
            List<Double> el = new ArrayList<>();
            el.add(afinarMs / (1200.0 / ppm));
            if (grabTx != null) { float[] g = CwMod.audio(el, FS, ppm, 700, 5); grabarTx(g, 0, g.length, FS, 20000); }   // en la grabación, como un tono de 700 Hz
            s.manipular(el, ppm);
            return true;
        }
        // trozo a trozo: cada letra (o señal de procedimiento) con su espacio detrás
        String t = texto.toUpperCase();
        for (int i = 0; i < t.length() && !cortarTx; i++) {
            int j = i + 1;
            if (t.charAt(i) == '<') { int f = t.indexOf('>', i); if (f > i) j = f + 1; }
            String trozo = t.substring(i, j);
            while (j < t.length() && t.charAt(j) == ' ') { trozo += " "; j++; }
            List<Double> el = CwMod.elementos(trozo, 1);
            if (grabTx != null) { float[] g = CwMod.audio(el, FS, ppm, 700, 5); grabarTx(g, 0, g.length, FS, 20000); }   // en la grabación, como un tono de 700 Hz
            s.manipular(el, ppm);
            i = j - 1;
        }
        return !cortarTx;
    }

    // ------------------------------------------------------------------ cascada
    private final class Cascada extends JPanel {
        private static final double F0 = 200, F1 = 3000;
        private BufferedImage img;
        private float[] ultima;

        Cascada() {
            setBackground(Color.BLACK);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    double f = F0 + (F1 - F0) * e.getX() / Math.max(1, getWidth());
                    tono = Math.max(250, Math.min(2900, afinarAlPico(f)));
                    LibroGuardiaUI.ConfigApp.put("CW_TONO", String.valueOf(Math.round(tono)));
                    ultimoClic = System.currentTimeMillis();
                    ajustarDemod();
                    lblEstado.setText(String.format("Sintonizado en %.0f Hz.", tono));
                    if (grabacion != null) apuntar("pinchas");
                }
            });
        }

        /** Busca la señal más fuerte a ±40 Hz de donde se ha pinchado (así no hace falta afinar el clic). */
        private double afinarAlPico(double f) {
            float[] db = ultima;
            if (db == null) return f;
            double hzBin = (double) FS / (db.length * 2);
            int k0 = (int) ((f - 40) / hzBin), k1 = (int) ((f + 40) / hzBin), mejor = -1;
            for (int k = Math.max(1, k0); k <= k1 && k < db.length - 1; k++) if (mejor < 0 || db[k] > db[mejor]) mejor = k;
            if (mejor < 1) return f;
            // ajuste fino con la parábola de los tres puntos
            double a = db[mejor - 1], b = db[mejor], c = db[mejor + 1], d = (a - c) / (2 * (a - 2 * b + c));
            return (mejor + (Double.isFinite(d) ? Math.max(-0.5, Math.min(0.5, d)) : 0)) * hzBin;
        }

        void linea(float[] db) {
            ultima = db;
            int w = Math.max(1, getWidth()), h = Math.max(1, getHeight() - 16);
            if (img == null || img.getWidth() != w || img.getHeight() != h) img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics g = img.getGraphics();
            g.copyArea(0, 0, w, h - 1, 0, 1);
            g.dispose();
            double hzBin = (double) FS / (db.length * 2);
            // contraste: el suelo es la mediana (el ruido) y el techo 25 dB por encima, así las señales flojas se ven
            float[] orden = java.util.Arrays.copyOfRange(db, (int) (F0 / hzBin), Math.min(db.length, (int) (F1 / hzBin)));
            java.util.Arrays.sort(orden);
            float suelo = orden.length > 0 ? orden[orden.length / 2] : 0, techo = suelo + 25;
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
            for (int f = 300; f < F1; f += 100) {   // marcas cada 100 Hz y número cada 500
                int x = (int) ((f - F0) / (F1 - F0) * getWidth());
                if (f % 500 == 0) g.drawString(f + " Hz", x + 2, 11);
                else g.drawLine(x, 12, x, 15);
            }
            g.setFont(VentanaDigitales.f(Font.BOLD, 10));
            int x = (int) ((tono - F0) / (F1 - F0) * getWidth());
            g.setColor(new Color(255, 59, 59));
            g.fillRect(x - 1, 16, 2, getHeight() - 16);
            String t = "CW " + Math.round(tono) + " Hz";
            g.fillRect(x + 3, 18, g.getFontMetrics().stringWidth(t) + 6, 13);
            g.setColor(Color.WHITE);
            g.drawString(t, x + 6, 28);
        }
    }
}
