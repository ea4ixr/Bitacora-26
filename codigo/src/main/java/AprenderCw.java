import digitales.PttSerie;
import digitales.MotorDigitales;
import digitales.cw.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

/**
 * Ventana «Aprender CW»: escuchar letras con el método Koch, copiar palabras e indicativos, practicar la manipulación
 * (Bitácora 26 lee lo que mandas y te dice cómo están tus puntos, rayas y espacios), un simulador de QSO y tu progreso.
 * El pitido suena siempre por los altavoces del PC: en esta ventana nunca se transmite.
 * El manipulador puede venir de un mando USB (Zero Delay…), del micrófono del PC (jack 3.5), del pitido de la emisora
 * en práctica, de un puerto COM o del teclado.
 */
final class AprenderCw extends JDialog {

    // Colores de la maqueta (azul noche, ámbar de aviso, verde de acierto)
    private static final Color FONDO = new Color(0x0b1220), PANEL = new Color(0x121c2e), CAMPO = new Color(0x0f1828),
            LINEA = new Color(0x243450), TEXTO = new Color(0xdfe7f3), GRIS = new Color(0x8796ad), AMBAR = new Color(0xf5b324),
            VERDE = new Color(0x4cd07d), ROJO = new Color(0xff6b5e), BOTON = new Color(0x1d2b44), TINTA_AMBAR = new Color(0x1a1200);
    private static final Font MONO_G = new Font("Monospaced", Font.BOLD, 96), MONO = new Font("Monospaced", Font.PLAIN, 18);
    /** Orden de las letras del método Koch (el de LCWO). */
    static final String KOCH = "KMURESNAPTLWI.JZ=FOY,VG5/Q92H38B?47C1D60X";
    private static AprenderCw abierta;

    private final LibroGuardiaUI app;
    private final JTabbedPane pestanas = new JTabbedPane();
    private SonidoPractica sonido;
    private Manipulador manip;
    private Llave llave;
    private final AnalizadorManipulacion anal;
    private final javax.swing.Timer reloj;
    private final Random azar = new Random();

    // Ajustes (derecha)
    private final JRadioButton rMando = radio("Mando USB", "Por ejemplo una tarjeta Zero Delay con un pulsador (botón 1; botón 2 para la otra pala)."),
            rMic = radio("Jack 3.5 en el micrófono del PC", "El manipulador cierra el circuito y Bitácora 26 nota cada golpe."),
            rEmisora = radio("Manipulador en la emisora", "La emisora en práctica (sin transmitir) y su pitido entra por la tarjeta de sonido de DIGITALES."),
            rCom = radio("Puerto COM", "Interfaz que cierra la línea CTS (o DSR para la otra pala)."),
            rTeclado = radio("Teclado del PC", "Barra espaciadora (llave recta) o Ctrl izquierdo y derecho (palas).");
    private final JComboBox<String> cbTipo = new JComboBox<>(new String[]{"Llave recta", "Palas (yámbico A)", "Palas (yámbico B)"});
    private final JComboBox<String> cbPuerto = new JComboBox<>(), cbMic = new JComboBox<>();
    private final List<String> puertos = new ArrayList<>();
    private final JCheckBox chkInvertir = new JCheckBox("<html><div style='width:230px'>Invertir (si suena al soltar y no al apretar)</div></html>");
    private final JButton bConectar = botonPrin("Conectar manipulador");
    private final JPanel padRaton = new JPanel(new BorderLayout());
    private final JRadioButton rRaton = radio("Ratón", "Pincha en el recuadro: izquierdo = llave o punto, derecho = raya.");
    private final JLabel led = new JLabel("●  suelto"), lblFuente = new JLabel(" ");
    private final JSlider sTono = new JSlider(400, 900, cfgI("TONO", 650)), sVol = new JSlider(0, 100, cfgI("VOL", 70));
    private final JSpinner spPpm = new JSpinner(new SpinnerNumberModel(cfgI("PPM", 20), 5, 40, 1)),
            spEfec = new JSpinner(new SpinnerNumberModel(cfgI("EFEC", 8), 3, 40, 1));

    // Escuchar letras
    private final JLabel lGrande = new JLabel("?", SwingConstants.CENTER), lMorse = new JLabel("pulsa Empezar", SwingConstants.CENTER),
            lUltima = new JLabel("—"), lTanda = new JLabel("—"), lLeccion = new JLabel();
    private final JButton bEmpezar = botonPrin("Empezar (2 minutos)");
    private final JPanel panelLetras = new JPanel(new GridLayout(0, 14, 5, 5));
    private JComponent vEscuchar, vManLetra, vCopiar, vManip, vQso, vProgreso;
    private final JLabel lEstEsc = new JLabel(), lEstMan = new JLabel(), lEstEsc2 = new JLabel(), lEstMan2 = new JLabel();
    // Manipular letras
    private final JLabel mGrande = new JLabel("?", SwingConstants.CENTER), mMorse = new JLabel("pulsa Empezar", SwingConstants.CENTER),
            mUltima = new JLabel("—"), mTanda = new JLabel("—"), mLeccion = new JLabel();
    private final JButton bEmpezarM = botonPrin("Empezar (2 minutos)");
    private final JCheckBox chkVerCodigo = new JCheckBox("Ver el código (puntos y rayas)", true);
    private boolean manipActiva = false, esperandoManip = false;
    private char letraManip;
    private int mOk, mTotal;
    private long finManip;
    private final List<Character> bolsa = new ArrayList<>(), bolsaM = new ArrayList<>();
    private boolean tandaActiva = false, esperandoRespuesta = false;
    private char letraActual;
    private int tandaOk, tandaTotal;
    private long finTanda;

    // Copiar
    private final JComboBox<String> cbCopiar = new JComboBox<>(new String[]{"Indicativos", "Abreviaturas: CQ DE TNX 73 RST…", "QSO típico", "Texto en español"});
    private final JTextField tfCopia = new JTextField();
    private final JLabel lCopiaRes = new JLabel(" "), lCopiaSono = new JLabel(" ");
    private String textoCopiar = "";

    // Manipular
    private final JTextArea taManip = new JTextArea(4, 30);
    private final JLabel lmPpm = new JLabel("—"), lmPunto = new JLabel("—"), lmRaya = new JLabel("—"), lmHueco = new JLabel("—"), lmLetra = new JLabel("—");
    private final JPanel dibujo = new JPanel() {
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = 6, base = getHeight() - 6;
            for (double[] u : anal.ultimos()) {
                if (u[1] == 0) { x += (int) Math.min(40, u[0] * 4); continue; }
                int alto = (int) Math.min(getHeight() - 12, u[0] * 18);
                g.setColor(u[0] > 2 ? AMBAR : VERDE);
                g.fillRoundRect(x, base - alto, 12, alto, 4, 4);
                x += 16;
                if (x > getWidth() - 20) break;
            }
            g.setColor(LINEA);
            g.drawLine(0, base, getWidth(), base);
        }
    };

    // Simulador de QSO
    private final JTextArea taQso = new JTextArea(9, 30);
    private final JTextField tfQso = new JTextField();
    private final JComboBox<String> cbRuido = new JComboBox<>(new String[]{"Sin ruido", "Ruido suave", "Ruido fuerte"});
    private final JCheckBox chkVerTexto = new JCheckBox("Ver lo que manda", false);
    private String[] estacion;   // {indicativo, nombre, qth, rst}
    private int pasoQso = 0;
    private String ultimoQso = "";
    private final List<String> lineasQso = new ArrayList<>();

    // Progreso
    private final JLabel pLetras = new JLabel(), pCopia = new JLabel(), pManip = new JLabel(), pRacha = new JLabel();
    private final JPanel pPeores = new JPanel(new GridLayout(0, 1, 2, 4));

    /** Abre la ventana (o la trae delante si ya está abierta). */
    static void abrir(LibroGuardiaUI app) {
        if (abierta != null && abierta.isDisplayable()) { abierta.toFront(); return; }
        abierta = new AprenderCw(app);
        abierta.setVisible(true);
    }

    private AprenderCw(LibroGuardiaUI app) {
        super(app, "Aprender CW · Bitácora 26", false);
        this.app = app;
        anal = new AnalizadorManipulacion(this::textoManipulado, cfgI("PPM", 20));
        try {
            sonido = new SonidoPractica("");
            sonido.ponerTono(sTono.getValue());
            sonido.ponerVolumen(sVol.getValue() / 100.0);
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "No se puede abrir el sonido de los altavoces: " + e.getMessage(),
                    "Aprender CW", JOptionPane.WARNING_MESSAGE));
        }
        manip = new Manipulador(tipoElegido(), (Integer) spPpm.getValue(), (ap, ms) -> {
            if (sonido != null) sonido.tecla(ap);
            anal.cambio(ap, ms);
            SwingUtilities.invokeLater(() -> pintarLed(ap));
        });
        construir();
        reloj = new javax.swing.Timer(30, e -> {
            anal.reloj(System.nanoTime() / 1_000_000);
            if (tandaActiva && System.currentTimeMillis() > finTanda && !esperandoRespuesta) acabarTanda();
            if (manipActiva && System.currentTimeMillis() > finManip && !esperandoManip) acabarManipLetra();
        });
        reloj.start();
        new javax.swing.Timer(500, e -> { if (isShowing() && pestanas.getSelectedComponent() == vManip) refrescarManip(); }).start();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(teclas);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { cerrarTodo(); }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1340, 900);
        setLocationRelativeTo(app);
        conectarFuente();
    }

    // ------------------------------------------------------------------ construir
    private void construir() {
        JPanel raiz = new JPanel(new BorderLayout(10, 0));
        raiz.setBackground(PANEL);
        raiz.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        pestanas.setFont(VentanaDigitales.f(Font.BOLD, 13));
        estiloPestanas(pestanas);
        pestanas.addTab("Escuchar letras", vEscuchar = vistaEscuchar());
        pestanas.addTab("Manipular letras", vManLetra = vistaManipLetra());
        pestanas.addTab("Copiar palabras", vCopiar = vistaCopiar());
        pestanas.addTab("Practicar manipulación", vManip = vistaManipular());
        pestanas.addTab("Simulador de QSO", vQso = vistaQso());
        pestanas.addTab("Mi progreso", vProgreso = vistaProgreso());
        pestanas.addTab("Cómo es un QSO", vistaGuia());
        pestanas.addChangeListener(e -> {
            Component v = pestanas.getSelectedComponent();
            if (v == vProgreso) refrescarProgreso();
            if (v != vEscuchar) pararTanda();
            if (v != vManLetra) pararManipLetra();
            if (v != vCopiar && sonido != null) sonido.parar();
            pintarEstadoLeccion();
        });
        raiz.add(pestanas, BorderLayout.CENTER);
        JScrollPane der = new JScrollPane(vistaAjustes(), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        der.setBorder(null);
        der.setPreferredSize(new Dimension(385, 10));
        der.getVerticalScrollBar().setUnitIncrement(16);
        raiz.add(der, BorderLayout.EAST);
        setContentPane(raiz);
        estilo(raiz);
    }

    // ------------------------------------------------------------------ estilo de la maqueta
    /** Caja redondeada con el título en ámbar dentro, como en la maqueta. */
    static JPanel caja(String titulo, JComponent dentro) {
        JPanel p = new JPanel(new BorderLayout(0, 8)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CAMPO);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.setColor(LINEA);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 14, 14));
        String t = titulo.isEmpty() ? titulo : titulo.charAt(0) + titulo.substring(1).toLowerCase(new Locale("es"))
                .replace("bitácora 26", "Bitácora 26").replace(" qso", " QSO").replace(" cw", " CW").replace("koch", "Koch");
        JLabel lt = new JLabel(t);
        lt.setFont(VentanaDigitales.f(Font.BOLD, 14));
        lt.setForeground(AMBAR);
        p.add(lt, BorderLayout.NORTH);
        p.add(dentro, BorderLayout.CENTER);
        return p;
    }

    static JButton boton(String t) { return botonMaqueta(t, false); }
    static JButton botonPrin(String t) { return botonMaqueta(t, true); }

    private static JButton botonMaqueta(String t, boolean prin) {
        JButton b = new JButton(t) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color c = prin ? AMBAR : BOTON;
                if (getModel().isPressed()) c = c.darker();
                else if (getModel().isRollover()) c = prin ? new Color(0xffc645) : new Color(0x26385a);
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                if (!prin) { g2.setColor(LINEA); g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12); }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setRolloverEnabled(true);
        b.setOpaque(false);
        b.setForeground(prin ? TINTA_AMBAR : TEXTO);
        b.setFont(VentanaDigitales.f(Font.BOLD, 14));
        b.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Círculo de opción: gris si no está elegido, ámbar relleno si lo está. */
    static final class IconoRadio implements Icon {
        private final boolean sel;
        IconoRadio(boolean sel) { this.sel = sel; }
        public int getIconWidth() { return 18; }
        public int getIconHeight() { return 18; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(sel ? AMBAR : GRIS);
            g2.setStroke(new BasicStroke(1.6f));
            g2.drawOval(x + 1, y + 1, 15, 15);
            if (sel) g2.fillOval(x + 5, y + 5, 8, 8);
            g2.dispose();
        }
    }

    /** Casilla de marcar: cuadrado gris, ámbar con ✓ al marcarla. */
    static final class IconoCasilla implements Icon {
        private final boolean sel;
        IconoCasilla(boolean sel) { this.sel = sel; }
        public int getIconWidth() { return 18; }
        public int getIconHeight() { return 18; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (sel) { g2.setColor(AMBAR); g2.fillRoundRect(x + 1, y + 1, 16, 16, 5, 5); g2.setColor(TINTA_AMBAR);
                g2.setStroke(new BasicStroke(2.2f)); g2.drawPolyline(new int[]{x + 5, x + 8, x + 13}, new int[]{y + 9, y + 12, y + 6}, 3); }
            else { g2.setColor(GRIS); g2.setStroke(new BasicStroke(1.5f)); g2.drawRoundRect(x + 1, y + 1, 15, 15, 5, 5); }
            g2.dispose();
        }
    }

    /** Pone a todos los desplegables, casillas, barras y campos el estilo oscuro de la maqueta. */
    static void estilo(Component c) {
        if (c instanceof JComboBox<?> cb) {
            cb.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
                @Override protected JButton createArrowButton() {
                    JButton b = new JButton() {
                        @Override protected void paintComponent(Graphics g) {
                            Graphics2D g2 = (Graphics2D) g.create();
                            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            g2.setColor(TEXTO);
                            int cx = getWidth() / 2, cy = getHeight() / 2;
                            g2.setStroke(new BasicStroke(2f));
                            g2.drawPolyline(new int[]{cx - 5, cx, cx + 5}, new int[]{cy - 2, cy + 3, cy - 2}, 3);
                            g2.dispose();
                        }
                    };
                    b.setBorder(BorderFactory.createEmptyBorder());
                    b.setContentAreaFilled(false);
                    b.setFocusable(false);
                    return b;
                }
                @Override public void paintCurrentValueBackground(Graphics g, Rectangle r, boolean f) { g.setColor(FONDO); g.fillRect(r.x, r.y, r.width, r.height); }
            });
            cb.setBackground(FONDO);
            cb.setForeground(TEXTO);
            cb.setFont(VentanaDigitales.f(Font.PLAIN, 14));
            cb.setBorder(BorderFactory.createLineBorder(LINEA));
            cb.setRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                    super.getListCellRendererComponent(l, v, i, s, false);
                    setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                    setBackground(s && i >= 0 ? AMBAR : FONDO);
                    setForeground(s && i >= 0 ? TINTA_AMBAR : TEXTO);
                    return this;
                }
            });
            Dimension d = cb.getPreferredSize();
            cb.setPreferredSize(new Dimension(Math.max(d.width, 150), 36));
            return;
        }
        if (c instanceof JSlider sl) {
            sl.setOpaque(false);
            sl.setUI(new javax.swing.plaf.basic.BasicSliderUI(sl) {
                @Override public void paintTrack(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int y = trackRect.y + trackRect.height / 2 - 3;
                    g2.setColor(FONDO);
                    g2.fillRoundRect(trackRect.x, y, trackRect.width, 6, 6, 6);
                    g2.setColor(LINEA);
                    g2.drawRoundRect(trackRect.x, y, trackRect.width, 6, 6, 6);
                    g2.setColor(AMBAR);
                    g2.fillRoundRect(trackRect.x, y, thumbRect.x + thumbRect.width / 2 - trackRect.x, 6, 6, 6);
                    g2.dispose();
                }
                @Override public void paintThumb(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(AMBAR);
                    int d = 16, x = thumbRect.x + (thumbRect.width - d) / 2, y = thumbRect.y + (thumbRect.height - d) / 2;
                    g2.fillOval(x, y, d, d);
                    g2.dispose();
                }
                @Override protected Dimension getThumbSize() { return new Dimension(16, 16); }
                @Override public void paintFocus(Graphics g) { }
            });
            return;
        }
        if (c instanceof JSpinner sp) {
            sp.setBorder(BorderFactory.createLineBorder(LINEA));
            if (sp.getEditor() instanceof JSpinner.DefaultEditor de) {
                JFormattedTextField tf = de.getTextField();
                tf.setBackground(FONDO);
                tf.setForeground(TEXTO);
                tf.setCaretColor(TEXTO);
                tf.setFont(VentanaDigitales.f(Font.BOLD, 14));
                tf.setColumns(2);
                tf.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
            }
            for (Component k : sp.getComponents()) if (k instanceof JButton b) { b.setBackground(BOTON); b.setBorder(BorderFactory.createLineBorder(LINEA)); }
            return;
        }
        if (c instanceof JCheckBox ch) {
            ch.setOpaque(false);
            ch.setForeground(TEXTO);
            ch.setIcon(new IconoCasilla(false));
            ch.setSelectedIcon(new IconoCasilla(true));
            ch.setIconTextGap(8);
            ch.setFocusPainted(false);
            ch.setFont(VentanaDigitales.f(Font.PLAIN, 13));
            return;
        }
        if (c instanceof JTextField tf && !(tf instanceof JFormattedTextField)) {
            tf.setBackground(FONDO);
            tf.setForeground(TEXTO);
            tf.setCaretColor(AMBAR);
            tf.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINEA), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            return;
        }
        if (c instanceof JTextArea ta) {
            ta.setBackground(FONDO);
            ta.setForeground(TEXTO);
            ta.setCaretColor(AMBAR);
        }
        if (c instanceof JScrollPane sp) {
            if (sp.getViewport().getView() instanceof JTextArea) sp.setBorder(BorderFactory.createLineBorder(LINEA));
            sp.getVerticalScrollBar().setBackground(CAMPO);
        }
        if (c instanceof Container k) for (Component h : k.getComponents()) estilo(h);
    }

    /** Pestañas oscuras como en la maqueta: la elegida en ámbar con letra oscura y una raya ámbar debajo. */
    static void estiloPestanas(JTabbedPane tp) {
        tp.setOpaque(false);
        tp.setForeground(TEXTO);
        tp.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
            @Override protected void installDefaults() {
                super.installDefaults();
                tabInsets = new Insets(8, 8, 8, 8);
                selectedTabPadInsets = new Insets(0, 0, 0, 0);
                tabAreaInsets = new Insets(4, 0, 0, 0);
                contentBorderInsets = new Insets(2, 0, 0, 0);
            }
            @Override protected int calculateTabHeight(int pl, int i, int fh) { return super.calculateTabHeight(pl, i, fh) + 4; }
            @Override protected void paintTabBackground(Graphics g, int pl, int i, int x, int y, int w, int h, boolean sel) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(sel ? AMBAR : CAMPO);
                g2.fillRoundRect(x + 1, y, w - 4, h + 8, 12, 12);
                g2.dispose();
            }
            @Override protected void paintTabBorder(Graphics g, int pl, int i, int x, int y, int w, int h, boolean sel) {
                if (sel) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(LINEA);
                g2.drawRoundRect(x + 1, y, w - 5, h + 8, 12, 12);
                g2.dispose();
            }
            @Override protected void paintText(Graphics g, int pl, Font f, FontMetrics fm, int i, String t, Rectangle r, boolean sel) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(f);
                g2.setColor(sel ? new Color(0x1a1200) : TEXTO);
                g2.drawString(t, r.x, r.y + fm.getAscent());
                g2.dispose();
            }
            @Override protected void paintFocusIndicator(Graphics g, int pl, Rectangle[] rs, int i, Rectangle ir, Rectangle tr, boolean sel) { }
            @Override protected void paintContentBorder(Graphics g, int pl, int sel) {
                int y = calculateTabAreaHeight(pl, runCount, maxTabHeight);
                g.setColor(AMBAR);
                g.fillRect(0, y, tabPane.getWidth(), 2);
            }
        });
    }

    private JLabel l(String t, int tam, Color c, boolean negrita) {
        JLabel x = new JLabel(t);
        x.setFont(VentanaDigitales.f(negrita ? Font.BOLD : Font.PLAIN, tam));
        x.setForeground(c);
        return x;
    }

    private JPanel dato(String nombre, JLabel valor) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINEA));
        p.add(l(nombre, 14, GRIS, false), BorderLayout.WEST);
        valor.setFont(VentanaDigitales.f(Font.BOLD, 14));
        valor.setForeground(TEXTO);
        p.add(valor, BorderLayout.EAST);
        return p;
    }

    private JPanel columna(JComponent... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (JComponent c : cs) { c.setAlignmentX(Component.LEFT_ALIGNMENT); p.add(c); p.add(Box.createVerticalStrut(6)); }
        return p;
    }

    private JPanel fila(JComponent... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        for (JComponent c : cs) p.add(c);
        return p;
    }

    private JComponent envolver(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(PANEL);
        p.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        p.add(c, BorderLayout.NORTH);
        return p;
    }

    // Escuchar letras ---------------------------------------------------
    private JComponent vistaEscuchar() {
        lGrande.setFont(MONO_G);
        lGrande.setForeground(TEXTO);
        lMorse.setFont(new Font("Monospaced", Font.PLAIN, 22));
        lMorse.setForeground(GRIS);
        lLeccion.setFont(VentanaDigitales.f(Font.BOLD, 13));
        lLeccion.setForeground(AMBAR);
        JButton bRep = boton("Repetir sonido"), bVer = boton("Ver la letra");
        bEmpezar.addActionListener(e -> { if (tandaActiva) pararTanda(); else empezarTanda(); });
        bRep.addActionListener(e -> { if (letraActual != 0) tocar(String.valueOf(letraActual), false); });
        bVer.addActionListener(e -> { if (letraActual != 0) { lGrande.setText(String.valueOf(letraActual)); lMorse.setText(codigo(letraActual)); } });
        JPanel botones = fila(bEmpezar, bRep, bVer);
        ((FlowLayout) botones.getLayout()).setAlignment(FlowLayout.CENTER);
        for (JComponent c : new JComponent[]{lGrande, lMorse, botones}) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 10));
        JPanel arriba = columna(lLeccion, lGrande, lMorse, botones, dato("Última respuesta", lUltima), dato("Aciertos en esta tanda", lTanda),
                dato("Para pasar de lección", new JLabel("90 % escuchando y 90 % manipulando")), dato("Escuchar esta lección", lEstEsc), dato("Manipular esta lección", lEstMan));
        lGrande.setAlignmentX(Component.CENTER_ALIGNMENT);
        panelLetras.setOpaque(false);
        pintarLetras();
        JPanel p = columna(caja("ESCUCHA LA LETRA Y PULSA SU TECLA (O PÍNCHALA ABAJO)", arriba),
                caja("LETRAS QUE YA SABES · LA ÁMBAR ES LA NUEVA · PINCHA PARA CONTESTAR", panelLetras));
        return envolver(p);
    }

    private int leccion() { return Math.max(2, Math.min(KOCH.length(), cfgI("LECCION", 2))); }

    private void pintarLetras() {
        panelLetras.removeAll();
        int n = leccion();
        for (int i = 0; i < KOCH.length(); i++) {
            JLabel c = new JLabel(String.valueOf(KOCH.charAt(i)), SwingConstants.CENTER);
            c.setFont(new Font("Monospaced", Font.BOLD, 16));
            c.setOpaque(true);
            c.setBorder(BorderFactory.createLineBorder(i < n - 1 ? VERDE : i == n - 1 ? AMBAR : LINEA));
            c.setBackground(i == n - 1 ? AMBAR : i < n - 1 ? new Color(30, 70, 50) : CAMPO);
            c.setForeground(i == n - 1 ? Color.BLACK : i < n ? TEXTO : GRIS);
            c.setPreferredSize(new Dimension(34, 32));
            final char ch = KOCH.charAt(i);
            final boolean deLeccion = i < n;
            c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            c.setToolTipText(deLeccion ? "En una tanda: pincha para contestar «" + ch + "». Fuera de tanda: oye cómo suena." : "Pincha para oír cómo suena «" + ch + "»");
            c.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (tandaActiva) { if (esperandoRespuesta && deLeccion) responder(ch); }
                    else curiosear(ch);
                }
            });
            panelLetras.add(c);
        }
        lLeccion.setText("Lección " + (n - 1) + " · " + n + " letras · " + spPpm.getValue() + " ppm con espaciado de " + spEfec.getValue() + " ppm");
        pintarEstadoLeccion();
        panelLetras.revalidate();
        panelLetras.repaint();
    }

    private void empezarTanda() {
        tandaActiva = true;
        tandaOk = tandaTotal = 0;
        finTanda = System.currentTimeMillis() + 120_000;
        bEmpezar.setText("Parar");
        apuntarDia();
        siguienteLetra();
    }

    private void pararTanda() {
        if (!tandaActiva) return;
        tandaActiva = false;
        esperandoRespuesta = false;
        bEmpezar.setText("Empezar (2 minutos)");
    }

    private void siguienteLetra() {
        if (!tandaActiva) return;
        if (System.currentTimeMillis() > finTanda) { acabarTanda(); return; }
        letraActual = deLaBolsa(bolsa);
        lGrande.setText("?");
        lGrande.setForeground(TEXTO);
        lMorse.setText("escucha…");
        esperandoRespuesta = true;
        tocar(String.valueOf(letraActual), false);
    }

    /** Fuera de tanda: enseña y hace sonar la letra que se pincha o se teclea (no cuenta para lecciones ni progreso). */
    private void curiosear(char c) {
        lGrande.setText(String.valueOf(c));
        lGrande.setForeground(AMBAR);
        lMorse.setText(codigo(c));
        tocar(String.valueOf(c), false);
    }

    private void responder(char c) {
        if (!esperandoRespuesta) return;
        esperandoRespuesta = false;
        boolean bien = Character.toUpperCase(c) == letraActual;
        tandaTotal++;
        if (bien) tandaOk++;
        apuntarLetra(letraActual, bien);
        lGrande.setText(String.valueOf(letraActual));
        lGrande.setForeground(bien ? VERDE : ROJO);
        lMorse.setText(codigo(letraActual));
        lUltima.setText(bien ? letraActual + " · correcto" : "era " + letraActual + ", pulsaste " + Character.toUpperCase(c));
        lUltima.setForeground(bien ? VERDE : ROJO);
        lTanda.setText(tandaOk + " de " + tandaTotal + " (" + (100 * tandaOk / tandaTotal) + " %)");
        javax.swing.Timer t = new javax.swing.Timer(bien ? 500 : 1300, e -> siguienteLetra());
        t.setRepeats(false);
        t.start();
    }

    private void acabarTanda() {
        pararTanda();
        int pct = tandaTotal == 0 ? 0 : 100 * tandaOk / tandaTotal;
        String msg = "Tanda terminada: " + tandaOk + " de " + tandaTotal + " (" + pct + " %).";
        if (tandaTotal >= 20 && pct >= 90 && leccion() < KOCH.length()) {
            cfgPut("OK_ESC", leccion());
            msg += "\n¡Muy bien! Escuchar esta lección: superado." + intentarSubir();
        } else if (tandaTotal < 20) msg += "\nHaz al menos 20 letras en la tanda para poder pasar de lección.";
        else msg += "\nNecesitas un 90 % para pasar de lección. ¡Otra tanda!";
        JOptionPane.showMessageDialog(this, msg, "Aprender CW", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Saca letras de forma equilibrada: todas las de la lección salen lo mismo (la nueva, algo más a partir de 4 letras). */
    private char deLaBolsa(List<Character> b) {
        if (b.isEmpty()) {
            String sab = KOCH.substring(0, leccion());
            for (int r = 0; r < 3; r++) for (char c : sab.toCharArray()) b.add(c);
            if (sab.length() >= 4) b.add(sab.charAt(sab.length() - 1));   // un poco más la nueva
            Collections.shuffle(b, azar);
        }
        return b.remove(b.size() - 1);
    }

    /** Pasa de lección solo cuando se han superado las dos: escuchar y manipular. */
    private String intentarSubir() {
        int n = leccion();
        boolean esc = cfgI("OK_ESC", 0) >= n, man = cfgI("OK_MAN", 0) >= n;
        String r;
        if (esc && man && n < KOCH.length()) {
            cfgPut("LECCION", n + 1);
            bolsa.clear();
            bolsaM.clear();
            pintarLetras();
            r = "\n¡Lección superada escuchando y manipulando! Letra nueva: «" + KOCH.charAt(n) + "».";
        } else if (!man) r = "\nAhora supérala también en «Manipular letras» para pasar a la siguiente.";
        else r = "\nAhora supérala también en «Escuchar letras» para pasar a la siguiente.";
        pintarEstadoLeccion();
        return r;
    }

    private void pintarEstadoLeccion() {
        int n = leccion();
        boolean esc = cfgI("OK_ESC", 0) >= n, man = cfgI("OK_MAN", 0) >= n;
        for (JLabel x : new JLabel[]{lEstEsc, lEstEsc2}) { x.setText(esc ? "superado" : "pendiente"); x.setForeground(esc ? VERDE : AMBAR); }
        for (JLabel x : new JLabel[]{lEstMan, lEstMan2}) { x.setText(man ? "superado" : "pendiente"); x.setForeground(man ? VERDE : AMBAR); }
        mLeccion.setText(lLeccion.getText());
    }

    // Manipular letras --------------------------------------------------
    private JComponent vistaManipLetra() {
        mGrande.setFont(MONO_G);
        mGrande.setForeground(TEXTO);
        mMorse.setFont(new Font("Monospaced", Font.PLAIN, 26));
        mMorse.setForeground(GRIS);
        mLeccion.setFont(VentanaDigitales.f(Font.BOLD, 13));
        mLeccion.setForeground(AMBAR);
        chkVerCodigo.addActionListener(e -> { if (esperandoManip) mMorse.setText(chkVerCodigo.isSelected() ? codigo(letraManip) : "manipúlala…"); });
        JButton bOir = boton("Oír cómo suena"), bSaltar = boton("Saltar letra");
        bEmpezarM.addActionListener(e -> { if (manipActiva) pararManipLetra(); else empezarManipLetra(); });
        bOir.addActionListener(e -> { if (letraManip != 0) tocar(String.valueOf(letraManip), false); });
        bSaltar.addActionListener(e -> { if (esperandoManip) siguienteManip(); });
        JPanel botones = fila(bEmpezarM, bOir, bSaltar);
        ((FlowLayout) botones.getLayout()).setAlignment(FlowLayout.CENTER);
        JPanel filaChk = fila(chkVerCodigo);
        ((FlowLayout) filaChk.getLayout()).setAlignment(FlowLayout.CENTER);
        for (JComponent c : new JComponent[]{mGrande, mMorse, botones, filaChk}) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 10));
        JPanel arriba = columna(mLeccion, mGrande, mMorse, filaChk, botones, dato("Última letra", mUltima), dato("Aciertos en esta tanda", mTanda),
                dato("Escuchar esta lección", lEstEsc2), dato("Manipular esta lección", lEstMan2));
        JLabel ayuda = l("<html><div style='width:560px'>Bitácora 26 te propone una letra y tú la manipulas con tu manipulador (elígelo a la derecha). "
                + "Al terminar la letra, deja un pequeño silencio para que se dé por acabada. Igual que al escuchar, necesitas 20 letras y un 90 % para superar la lección.</div></html>", 13, GRIS, false);
        return envolver(columna(caja("MANIPULA LA LETRA QUE SE MUESTRA", arriba), caja("CÓMO FUNCIONA", ayuda)));
    }

    private void empezarManipLetra() {
        manipActiva = true;
        mOk = mTotal = 0;
        finManip = System.currentTimeMillis() + 120_000;
        bEmpezarM.setText("Parar");
        anal.borrar();
        apuntarDia();
        siguienteManip();
    }

    private void pararManipLetra() {
        if (!manipActiva) return;
        manipActiva = false;
        esperandoManip = false;
        bEmpezarM.setText("Empezar (2 minutos)");
    }

    private void siguienteManip() {
        if (!manipActiva) return;
        if (System.currentTimeMillis() > finManip) { acabarManipLetra(); return; }
        letraManip = deLaBolsa(bolsaM);
        mGrande.setText(String.valueOf(letraManip));
        mGrande.setForeground(TEXTO);
        mMorse.setText(chkVerCodigo.isSelected() ? codigo(letraManip) : "manipúlala…");
        esperandoManip = true;
    }

    private void responderManip(char c) {
        if (!esperandoManip) return;
        esperandoManip = false;
        boolean bien = c == letraManip;
        mTotal++;
        if (bien) mOk++;
        apuntarLetraManip(letraManip, bien);
        mGrande.setForeground(bien ? VERDE : ROJO);
        mMorse.setText(codigo(letraManip));
        String mandada = c == '_' ? "algo que no es una letra" : "«" + c + "» (" + codigo(c) + ")";
        mUltima.setText(bien ? letraManip + " · correcto" : "mandaste " + mandada);
        mUltima.setForeground(bien ? VERDE : ROJO);
        mTanda.setText(mOk + " de " + mTotal + " (" + (100 * mOk / mTotal) + " %)");
        if (!bien) tocar(String.valueOf(letraManip), false);   // así suena bien
        javax.swing.Timer t = new javax.swing.Timer(bien ? 600 : 1800, e -> siguienteManip());
        t.setRepeats(false);
        t.start();
    }

    private void acabarManipLetra() {
        pararManipLetra();
        int pct = mTotal == 0 ? 0 : 100 * mOk / mTotal;
        String msg = "Tanda terminada: " + mOk + " de " + mTotal + " (" + pct + " %).";
        if (mTotal >= 20 && pct >= 90 && leccion() < KOCH.length()) {
            cfgPut("OK_MAN", leccion());
            msg += "\n¡Muy bien! Manipular esta lección: superado." + intentarSubir();
        } else if (mTotal < 20) msg += "\nManipula al menos 20 letras en la tanda para poder superar la lección.";
        else msg += "\nNecesitas un 90 % para superar la lección. ¡Otra tanda!";
        JOptionPane.showMessageDialog(this, msg, "Aprender CW", JOptionPane.INFORMATION_MESSAGE);
    }

    private void apuntarLetraManip(char c, boolean bien) {
        String[] v = cfg("SM_" + (int) c, "0/0").split("/");
        int ok = Integer.parseInt(v[0]) + (bien ? 1 : 0), tot = Integer.parseInt(v[1]) + 1;
        if (tot > 100) { ok = ok * 50 / tot; tot = 50; }
        LibroGuardiaUI.ConfigApp.put("CWAPR_SM_" + (int) c, ok + "/" + tot);
    }

    // Copiar palabras ---------------------------------------------------
    private JComponent vistaCopiar() {
        tfCopia.setFont(MONO);
        lCopiaRes.setFont(new Font("Monospaced", Font.BOLD, 18));
        JButton bOtra = botonPrin("Otra tanda"), bOir = boton("Volver a oír"), bParar = boton("Parar"), bComp = boton("Comprobar");
        bParar.addActionListener(e -> { if (sonido != null) sonido.parar(); });
        bOtra.addActionListener(e -> {
            textoCopiar = generarCopia(cbCopiar.getSelectedIndex());
            tfCopia.setText("");
            lCopiaRes.setText(" ");
            lCopiaSono.setText(" ");
            apuntarDia();
            tocar(textoCopiar, true);
            tfCopia.requestFocusInWindow();
        });
        bOir.addActionListener(e -> { if (!textoCopiar.isEmpty()) tocar(textoCopiar, true); });
        bComp.addActionListener(e -> comprobarCopia());
        tfCopia.addActionListener(e -> comprobarCopia());
        JPanel p = columna(fila(cbCopiar), l("Escucha y escribe lo que oyes (separa las palabras con espacios). Enter para comprobar:", 14, GRIS, false),
                tfCopia, lCopiaRes, dato("Lo que sonó", lCopiaSono), fila(bOtra, bOir, bParar, bComp));
        return envolver(caja("COPIAR · INDICATIVOS, ABREVIATURAS Y QSO", p));
    }

    private void comprobarCopia() {
        if (textoCopiar.isEmpty()) return;
        String ref = textoCopiar.replace("<", "").replace(">", ""), yo = tfCopia.getText().trim().toUpperCase().replaceAll("\\s+", " ");
        int[][] d = new int[ref.length() + 1][yo.length() + 1];
        for (int i = 0; i <= ref.length(); i++) d[i][0] = i;
        for (int j = 0; j <= yo.length(); j++) d[0][j] = j;
        for (int i = 1; i <= ref.length(); i++) for (int j = 1; j <= yo.length(); j++)
            d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + (ref.charAt(i - 1) == yo.charAt(j - 1) ? 0 : 1));
        // camino: qué letras de lo que sonó se copiaron bien
        boolean[] bien = new boolean[ref.length()];
        int i = ref.length(), j = yo.length();
        while (i > 0 && j > 0) {
            if (ref.charAt(i - 1) == yo.charAt(j - 1) && d[i][j] == d[i - 1][j - 1]) { bien[i - 1] = true; i--; j--; }
            else if (d[i][j] == d[i - 1][j - 1] + 1) { i--; j--; }
            else if (d[i][j] == d[i - 1][j] + 1) i--; else j--;
        }
        StringBuilder h = new StringBuilder("<html>");
        int ok = 0, total = 0;
        for (int k = 0; k < ref.length(); k++) {
            char c = ref.charAt(k);
            if (c != ' ') { total++; if (bien[k]) ok++; }
            h.append(bien[k] || c == ' ' ? "<font color='#4cd07d'>" : "<font color='#ff6b5e'><u>").append(c == ' ' ? "&nbsp;" : String.valueOf(c))
                    .append(bien[k] || c == ' ' ? "</font>" : "</u></font>");
        }
        lCopiaRes.setText(h.append("</html>").toString());
        lCopiaSono.setText(ref);
        int pct = total == 0 ? 0 : 100 * ok / total;
        lCopiaSono.setToolTipText(ok + " de " + total + " letras (" + pct + " %)");
        if (pct >= 90) cfgPut("PPM_COPIA", (Integer) spPpm.getValue());
        cfgPut("COPIA_ULTIMA", pct);
    }

    private static final String[] PREFIJOS = {"EA1", "EA2", "EA3", "EA4", "EA5", "EA6", "EA7", "EA8", "EB1", "EC4", "DL", "DK", "F", "G", "M", "I", "IK", "IZ",
            "K", "W", "N", "AA", "VE", "JA", "OK", "SP", "ON", "PA", "HB9", "OE", "CT", "LZ", "YO", "UA", "RX", "9A", "S5", "OM", "LA", "SM", "OH", "OZ", "4X", "LU", "PY", "VK", "ZL"};
    private static final String[] ABREV = {"CQ", "DE", "TNX", "TU", "73", "RST", "UR", "NAME", "QTH", "HW?", "BK", "K", "<KN>", "<SK>", "599", "5NN", "GM", "GA",
            "GE", "FB", "OM", "YL", "WX", "RIG", "ANT", "PWR", "AGN", "PSE", "QRZ?", "QSL", "QRM", "QRN", "QSB", "QRP", "ES", "HR", "R", "=", "CFM", "GL", "DX"};
    private static final String[] NOMBRES = {"JUAN", "PEPE", "ANA", "LUIS", "PIERRE", "HANS", "JOHN", "MARIO", "JAN", "PAUL", "TONI", "MIKE", "JOSE", "ELENA"};
    private static final String[] QTHS = {"MADRID", "SEVILLA", "LYON", "BERLIN", "LONDON", "ROMA", "PRAHA", "WIEN", "LISBOA", "OSLO", "BOSTON", "TOKYO", "VALENCIA"};
    private static final String[] FRASES = {"HOLA DESDE MADRID", "BUENAS NOCHES Y GRACIAS", "LA PROPAGACION ESTA BIEN", "MI ANTENA ES UN DIPOLO",
            "HOY HACE BUEN TIEMPO", "NOS VEMOS EN LA BANDA", "GRACIAS POR EL CONTACTO", "EL RADIOCLUB TE SALUDA"};

    private String indicativoAzar() {
        String p = PREFIJOS[azar.nextInt(PREFIJOS.length)];
        if (!Character.isDigit(p.charAt(p.length() - 1))) p += azar.nextInt(10);
        StringBuilder s = new StringBuilder(p);
        for (int i = 0, n = 1 + azar.nextInt(3); i < n; i++) s.append((char) ('A' + azar.nextInt(26)));
        return s.toString();
    }

    private String generarCopia(int tipo) {
        StringBuilder s = new StringBuilder();
        switch (tipo) {
            case 0 -> { for (int i = 0; i < 5; i++) s.append(i > 0 ? " " : "").append(indicativoAzar()); }
            case 1 -> { for (int i = 0; i < 7; i++) s.append(i > 0 ? " " : "").append(ABREV[azar.nextInt(ABREV.length)]); }
            case 2 -> s.append(indicativoAzar()).append(" DE ").append(indicativoAzar()).append(" GM UR RST 5NN NAME ").append(NOMBRES[azar.nextInt(NOMBRES.length)])
                    .append(" QTH ").append(QTHS[azar.nextInt(QTHS.length)]).append(" HW? <KN>");
            default -> s.append(FRASES[azar.nextInt(FRASES.length)]);
        }
        return s.toString();
    }

    // Practicar manipulación --------------------------------------------
    private JComponent vistaManipular() {
        taManip.setFont(MONO);
        taManip.setLineWrap(true);
        taManip.setWrapStyleWord(true);
        taManip.setEditable(false);
        taManip.setBackground(CAMPO);
        taManip.setForeground(TEXTO);
        dibujo.setBackground(CAMPO);
        dibujo.setPreferredSize(new Dimension(500, 90));
        JButton bBorrar = boton("Borrar");
        bBorrar.addActionListener(e -> { taManip.setText(""); anal.borrar(); dibujo.repaint(); });
        JScrollPane sp = new JScrollPane(taManip);
        sp.setPreferredSize(new Dimension(500, 120));
        JPanel p = columna(l("Manipula con tu manipulador (elige abajo a la derecha cómo está conectado) y Bitácora 26 escribe lo que mandas:", 14, GRIS, false),
                sp, dato("Tu velocidad", lmPpm), dato("Puntos", lmPunto), dato("Rayas", lmRaya), dato("Espacio dentro de la letra", lmHueco),
                dato("Espacio entre letras", lmLetra), l("Tus últimos elementos (verde = punto, ámbar = raya):", 13, GRIS, false), dibujo, fila(bBorrar));
        return envolver(caja("TÚ MANIPULAS, BITÁCORA 26 ESCUCHA", p));
    }

    private void textoManipulado(String s) {
        SwingUtilities.invokeLater(() -> {
            Component pest = pestanas.getSelectedComponent();
            if (pest == vManLetra) { for (char c : s.toCharArray()) if (c != ' ') responderManip(c); return; }
            if (pest == vQso) tfQso.setText(tfQso.getText() + s);
            else { taManip.append(s); taManip.setCaretPosition(taManip.getDocument().getLength()); }
            dibujo.repaint();
        });
    }

    private void refrescarManip() {
        double[] m = anal.medidas();
        if (Double.isNaN(m[0])) return;
        lmPpm.setText(String.format("%.0f ppm", anal.ppm()));
        cfgPut("PPM_MANIP", (int) Math.round(anal.ppm()));
        lmPunto.setText("bien medidos");
        lmPunto.setForeground(VERDE);
        poner(lmRaya, m[1], 3, "rayas");
        poner(lmHueco, m[2], 1, "espacios");
        poner(lmLetra, m[3], 3, "espacios");
        dibujo.repaint();
    }

    private void poner(JLabel l, double v, double ideal, String que) {
        if (Double.isNaN(v)) { l.setText("—"); l.setForeground(TEXTO); return; }
        double r = v / ideal;
        boolean bien = r > 0.8 && r < 1.25;
        l.setText(String.format("%s: %.1f puntos (deberían ser %.0f)", bien ? "bien" : r < 1 ? "algo cortos" : "algo largos", v, ideal)
                .replace("cortos", que.equals("rayas") ? "cortas" : "cortos").replace("largos", que.equals("rayas") ? "largas" : "largos"));
        l.setForeground(bien ? VERDE : ROJO);
    }

    // Simulador de QSO --------------------------------------------------
    private JComponent vistaQso() {
        taQso.setFont(new Font("Monospaced", Font.PLAIN, 16));
        taQso.setLineWrap(true);
        taQso.setWrapStyleWord(true);
        taQso.setEditable(false);
        taQso.setBackground(CAMPO);
        taQso.setForeground(TEXTO);
        tfQso.setFont(MONO);
        chkVerTexto.setOpaque(false);
        chkVerTexto.setForeground(TEXTO);
        chkVerTexto.addActionListener(e -> pintarQso());
        cbRuido.setSelectedIndex(Math.min(2, cfgI("RUIDO", 1)));
        cbRuido.addActionListener(e -> cfgPut("RUIDO", cbRuido.getSelectedIndex()));
        JButton bNuevo = botonPrin("Nuevo QSO"), bAgn = boton("Pedir repetición (AGN?)"), bEnviar = boton("Enviar");
        bNuevo.addActionListener(e -> nuevoQso());
        bAgn.addActionListener(e -> { if (!ultimoQso.isEmpty()) tocar(ultimoQso, true); });
        bEnviar.addActionListener(e -> enviarQso());
        tfQso.addActionListener(e -> enviarQso());
        JScrollPane sp = new JScrollPane(taQso);
        sp.setPreferredSize(new Dimension(500, 230));
        JPanel p = columna(sp, l("Tu respuesta (escríbela, o manipúlala y se escribe sola; Enter para enviar):", 14, GRIS, false), tfQso,
                fila(l("Ruido:", 13, GRIS, false), cbRuido, chkVerTexto), fila(bNuevo, bAgn, bEnviar, botonChuleta()));
        return envolver(caja("SIMULADOR DE QSO · SIN SALIR AL AIRE", p));
    }

    private String miIndicativo() {
        String m = CuentaBitacora.baseIndicativo(LibroGuardiaUI.operadorActual);
        return m.isEmpty() ? "EA4XXX" : m;
    }

    private void nuevoQso() {
        estacion = new String[]{indicativoAzar(), NOMBRES[azar.nextInt(NOMBRES.length)], QTHS[azar.nextInt(QTHS.length)], new String[]{"599", "579", "559", "589"}[azar.nextInt(4)]};
        lineasQso.clear();
        pasoQso = 1;
        apuntarDia();
        hablaEstacion("CQ CQ CQ DE " + estacion[0] + " " + estacion[0] + " K");
    }

    private void hablaEstacion(String t) {
        ultimoQso = t;
        lineasQso.add("E" + t);
        pintarQso();
        tocar(t, true);
    }

    private void pintarQso() {
        StringBuilder s = new StringBuilder();
        for (String x : lineasQso) {
            boolean el = x.charAt(0) == 'E';
            s.append(el ? (estacion == null ? "?" : estacion[0]) + ": " + (chkVerTexto.isSelected() ? x.substring(1) : "(escucha… · marca «Ver lo que manda» para leerlo)") : "Tú: " + x.substring(1)).append("\n");
        }
        taQso.setText(s.toString());
    }

    private void enviarQso() {
        String t = tfQso.getText().trim().toUpperCase();
        if (t.isEmpty() || estacion == null) return;
        tfQso.setText("");
        lineasQso.add("T" + t);
        pintarQso();
        String yo = miIndicativo(), el = estacion[0];
        switch (pasoQso) {
            case 1 -> {
                if (!t.contains(el) && !t.contains(yo)) { hablaEstacion("QRZ? DE " + el + " K"); return; }
                pasoQso = 2;
                hablaEstacion(yo + " DE " + el + " GM TNX FER CALL UR RST " + estacion[3] + " " + estacion[3] + " NAME " + estacion[1] + " " + estacion[1]
                        + " QTH " + estacion[2] + " " + estacion[2] + " HW? " + yo + " DE " + el + " <KN>");
            }
            case 2 -> {
                pasoQso = 3;
                hablaEstacion("R R TNX FER RPRT ES NAME 73 ES GL " + yo + " DE " + el + " <SK> E E");
            }
            default -> {
                lineasQso.add("E(QSO terminado. ¡Muy bien! Pulsa «Nuevo QSO» para otro.)");
                pasoQso = 0;
                boolean ver = chkVerTexto.isSelected();
                chkVerTexto.setSelected(true);
                pintarQso();
                chkVerTexto.setSelected(ver);
            }
        }
    }

    // Cómo es un QSO + chuleta de abreviaturas ------------------------------
    /** {abreviatura, de dónde viene, qué significa} */
    static final String[][] ABREVIATURAS = {
        {"CQ", "Seek you", "Llamada general a cualquier estación"},
        {"DE", "De (from)", "De: EA1ZZZ DE EA4XXX = a EA1ZZZ, de EA4XXX"},
        {"K", "Go ahead", "Cambio, puede contestar cualquiera"},
        {"KN", "Go ahead (named)", "Cambio, solo para la estación nombrada"},
        {"BK", "Break", "Cambio rápido, sin dar indicativos"},
        {"SK", "End of contact", "Fin del contacto (también VA)"},
        {"AR", "End of message", "Fin del mensaje (+)"},
        {"BT o =", "Break text", "Separa frases o ideas"},
        {"CL", "Closing", "Cierro la estación, apago"},
        {"QRL?", "", "¿Está ocupada la frecuencia?"},
        {"QRL / C / YES", "", "Sí, está ocupada"},
        {"QRZ?", "", "¿Quién me llama?"},
        {"QRS", "", "Transmita más despacio"},
        {"QRQ", "", "Transmita más deprisa"},
        {"QRM", "", "Interferencias de otras estaciones"},
        {"QRN", "", "Ruido atmosférico (parásitos)"},
        {"QSB", "", "Su señal sube y baja (desvanecimiento)"},
        {"QRP", "", "Baja potencia / estación de poca potencia"},
        {"QRO", "", "Alta potencia / suba potencia"},
        {"QRT", "", "Dejo de transmitir, cierro"},
        {"QRX", "", "Espere, vuelvo enseguida"},
        {"QSL", "", "Recibido, confirmado (y también la tarjeta QSL)"},
        {"QSO", "", "Comunicado, contacto"},
        {"QSY", "", "Cambiar de frecuencia"},
        {"QTH", "", "Mi ubicación es…"},
        {"QRP?", "", "¿Debo bajar potencia?"},
        {"R", "Roger", "Recibido"},
        {"RST", "Readability, Strength, Tone", "Informe: legibilidad (1-5), fuerza (1-9), tono (1-9)"},
        {"5NN", "599", "Señal perfecta (N = 9, T = 0, para ir más rápido)"},
        {"UR", "Your", "Su / tu"},
        {"MY", "My", "Mi"},
        {"NAME / OP", "Name / operator", "Mi nombre es… / operador"},
        {"HW?", "How copy?", "¿Cómo me copia?"},
        {"FB", "Fine business", "Muy bien, estupendo"},
        {"GM", "Good morning", "Buenos días"},
        {"GA", "Good afternoon", "Buenas tardes"},
        {"GE", "Good evening", "Buenas tardes / noches"},
        {"GN", "Good night", "Buenas noches (al despedirse)"},
        {"DR", "Dear", "Querido"},
        {"OM", "Old man", "Colega (radioaficionado)"},
        {"YL", "Young lady", "Colega mujer"},
        {"XYL", "Ex young lady", "Esposa"},
        {"TKS / TNX", "Thanks", "Gracias"},
        {"TU", "Thank you", "Gracias (muy usado al final)"},
        {"FER", "For", "Por / para"},
        {"PSE", "Please", "Por favor"},
        {"ES", "& (and)", "Y"},
        {"AGN", "Again", "Otra vez, repita"},
        {"RPT", "Repeat", "Repita"},
        {"RPRT", "Report", "Informe"},
        {"WX", "Weather", "El tiempo (meteorología)"},
        {"TEMP", "Temperature", "Temperatura"},
        {"RIG", "Rig", "Mi equipo / emisora"},
        {"ANT", "Antenna", "Antena"},
        {"PWR", "Power", "Potencia"},
        {"W", "Watts", "Vatios"},
        {"HR", "Here", "Aquí"},
        {"SRI", "Sorry", "Lo siento"},
        {"CFM", "Confirm", "Confirmo"},
        {"CUL", "See you later", "Hasta luego"},
        {"GL", "Good luck", "Buena suerte"},
        {"GB", "Good bye", "Adiós"},
        {"DX", "Distance", "Estación lejana / larga distancia"},
        {"73", "Best regards", "Saludos cordiales"},
        {"88", "Love and kisses", "Besos y abrazos (a una YL)"},
        {"E E", "", "Dos puntos sueltos: despedida amistosa"},
    };

    private JComponent vistaGuia() {
        String yo = "EA4XXX", el = "EA1ZZZ";
        String css = "font-family:sans-serif;font-size:13px;color:#dfe6ee;width:590px";
        StringBuilder h = new StringBuilder("<html><body style='" + css + "'>");
        h.append("<p>Un QSO en CW sigue casi siempre el mismo orden. En el ejemplo tú eres <b>" + yo + "</b> y te contesta <b>" + el + "</b>.</p>");
        String[][] pasos = {
            {"1. Comprobar la frecuencia", "Escucha unos segundos en el trozo de CW de la banda. Si parece libre, pregunta si está ocupada.",
                "QRL?", "Si te contestan <b>C</b>, <b>YES</b> o <b>QRL</b>, está ocupada: busca otra. Si nadie contesta, es tuya."},
            {"2. Llamada general (CQ)", "Llama a quien esté escuchando, a una velocidad a la que tú también puedas copiar.",
                "CQ CQ CQ DE " + yo + " " + yo + " PSE K", "Llamada general + <b>DE</b> (de) + tu indicativo dos veces + <b>PSE</b> (por favor) + <b>K</b> (cambio, puede contestar cualquiera)."},
            {"3. Te contestan", "Quien te oye da tu indicativo y el suyo.",
                yo + " DE " + el + " " + el + " KN", "<b>KN</b> es cambio cerrado: solo debe contestar la estación nombrada."},
            {"4. Primer intercambio", "Agradeces la llamada y das el informe (RST), tu nombre y tu ciudad (QTH).",
                el + " DE " + yo + " = GE DR OM TKS FER CALL = UR RST 599 599 = MY NAME IS PACO PACO = QTH MADRID MADRID = HW? " + el + " DE " + yo + " KN",
                "<b>=</b> (BT) separa frases · <b>GE</b> buenas tardes/noches (GM mañana, GA tarde) · <b>DR OM</b> querido colega · <b>TKS FER CALL</b> gracias por la llamada · <b>HW?</b> ¿cómo me copias?"},
            {"5. Él te da sus datos", "Confirma que te ha recibido (<b>R</b> o <b>QSL</b>) y te pasa los suyos.",
                yo + " DE " + el + " = R QSL DR PACO = UR RST 579 579 = NAME IS LUIS LUIS = QTH VIGO VIGO = " + yo + " DE " + el + " KN", ""},
            {"6. Despedida y cierre", "Agradeces el QSO, mandas saludos (73) y cierras.",
                el + " DE " + yo + " = R QSL DR LUIS TKS FER QSO = 73 ES DX = " + el + " DE " + yo + " SK",
                "<b>ES</b> = y · <b>SK</b> (o VA) = fin del contacto. Él se despide igual: <tt>" + yo + " DE " + el + " = TKS PACO 73 = " + yo + " DE " + el + " SK</tt>. Al final es muy habitual mandar <b>TU</b> y dos puntos sueltos <b>E E</b>."},
        };
        for (String[] p : pasos) {
            h.append("<p style='margin-top:12px'><b style='color:#f5b942;font-size:14px'>").append(p[0]).append("</b><br>").append(p[1]).append("</p>");
            h.append("<table width='100%' cellpadding='6'><tr><td bgcolor='#0b111c' style='font-family:monospace;font-size:14px;color:#7ee08a'>")
             .append(p[2]).append("</td></tr></table>");
            if (!p[3].isEmpty()) h.append("<p style='color:#a9b4c2'>").append(p[3]).append("</p>");
        }
        h.append("<p style='margin-top:12px'>Consejo: repite los datos importantes (RST, nombre, QTH) dos veces; si no copias algo, pide <b>AGN</b> o <b>QRS</b> sin miedo.</p>");
        h.append("</body></html>");
        JEditorPane ep = new JEditorPane("text/html", h.toString());
        ep.setEditable(false);
        ep.setOpaque(false);
        ep.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ep, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setOpaque(false);
        p.add(sp, BorderLayout.CENTER);
        JButton bSim = boton("Practicarlo en el simulador");
        bSim.addActionListener(e -> pestanas.setSelectedComponent(vQso));
        p.add(fila(botonChuleta(), bSim), BorderLayout.SOUTH);
        JPanel c = caja("CÓMO SE HACE UN QSO EN CW", p);
        JPanel w = new JPanel(new BorderLayout());
        w.setOpaque(false);
        w.add(c, BorderLayout.CENTER);
        return w;
    }

    private JButton botonChuleta() {
        JButton b = boton("Abreviaturas");
        b.setToolTipText("Abre una ventanita con todas las abreviaturas, para tenerla a la vista mientras haces un QSO o practicas");
        b.addActionListener(e -> abrirChuleta(this));
        return b;
    }

    private static JDialog chuleta;

    /** Ventanita flotante con las abreviaturas; se puede usar desde cualquier ventana. */
    static void abrirChuleta(Window duenio) {
        if (chuleta != null && chuleta.isDisplayable()) { chuleta.toFront(); return; }
        JDialog d = new JDialog(duenio, "Abreviaturas de CW", Dialog.ModalityType.MODELESS);
        d.setAlwaysOnTop(true);
        javax.swing.table.DefaultTableModel m = new javax.swing.table.DefaultTableModel(new String[]{"Abreviatura", "Viene de", "Significa"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (String[] a : ABREVIATURAS) m.addRow(a);
        JTable t = new JTable(m);
        t.setRowHeight(24);
        t.setBackground(CAMPO);
        t.setForeground(TEXTO);
        t.setGridColor(LINEA);
        t.setSelectionBackground(AMBAR);
        t.setSelectionForeground(new Color(0x1a1200));
        t.setShowVerticalLines(false);
        t.getTableHeader().setBackground(PANEL);
        t.getTableHeader().setForeground(AMBAR);
        t.getTableHeader().setFont(VentanaDigitales.f(Font.BOLD, 13));
        t.getTableHeader().setDefaultRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            { setHorizontalAlignment(LEFT); }
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(tb, v, s, f, r, c);
                setBackground(PANEL); setForeground(AMBAR); setFont(VentanaDigitales.f(Font.BOLD, 13));
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, AMBAR), BorderFactory.createEmptyBorder(4, 6, 4, 6)));
                return this;
            }
        });
        t.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                super.getTableCellRendererComponent(tb, v, s, false, r, c);
                setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                if (!s) setForeground(c == 0 ? AMBAR : c == 1 ? GRIS : TEXTO);
                setFont(c == 0 ? new Font("Monospaced", Font.BOLD, 14) : VentanaDigitales.f(Font.PLAIN, 13));
                return this;
            }
        });
        t.setFont(VentanaDigitales.f(Font.PLAIN, 13));
        t.getColumnModel().getColumn(0).setPreferredWidth(120);
        t.getColumnModel().getColumn(1).setPreferredWidth(140);
        t.getColumnModel().getColumn(2).setPreferredWidth(330);
        javax.swing.table.TableRowSorter<javax.swing.table.DefaultTableModel> orden = new javax.swing.table.TableRowSorter<>(m);
        t.setRowSorter(orden);
        JTextField buscar = new JTextField();
        buscar.setFont(VentanaDigitales.f(Font.PLAIN, 14));
        buscar.setBackground(CAMPO);
        buscar.setForeground(TEXTO);
        buscar.setCaretColor(TEXTO);
        buscar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINEA), BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        buscar.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void f() {
                String q = buscar.getText().trim();
                orden.setRowFilter(q.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q)));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { f(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { f(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { f(); }
        });
        JPanel arriba = new JPanel(new BorderLayout(6, 0));
        arriba.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        arriba.setBackground(FONDO);
        JLabel lb = new JLabel("Buscar:");
        lb.setForeground(AMBAR);
        lb.setFont(VentanaDigitales.f(Font.BOLD, 13));
        arriba.add(lb, BorderLayout.WEST);
        arriba.add(buscar, BorderLayout.CENTER);
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.add(arriba, BorderLayout.NORTH);
        raiz.setBackground(FONDO);
        JScrollPane spT = new JScrollPane(t);
        spT.getViewport().setBackground(CAMPO);
        spT.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINEA));
        raiz.add(spT, BorderLayout.CENTER);
        d.setContentPane(raiz);
        d.setSize(600, 520);
        if (duenio != null) d.setLocation(Math.max(0, duenio.getX() + duenio.getWidth() - 620), duenio.getY() + 60);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        chuleta = d;
        d.setVisible(true);
    }

    // Mi progreso -------------------------------------------------------
    private JComponent vistaProgreso() {
        pPeores.setOpaque(false);
        JPanel p = columna(dato("Letras aprendidas", pLetras), dato("Velocidad de copia (90 % o más)", pCopia), dato("Velocidad manipulando", pManip),
                dato("Racha", pRacha), l("Las que más fallas:", 14, AMBAR, true), pPeores);
        refrescarProgreso();
        return envolver(caja("MI PROGRESO", p));
    }

    private void refrescarProgreso() {
        pLetras.setText(leccion() + " de " + KOCH.length());
        int pc = cfgI("PPM_COPIA", 0), pm = cfgI("PPM_MANIP", 0);
        pCopia.setText(pc == 0 ? "aún sin medir" : pc + " ppm");
        pManip.setText(pm == 0 ? "aún sin medir" : pm + " ppm");
        int r = cfgI("RACHA", 0);
        pRacha.setText(r == 0 ? "empieza hoy" : r + (r == 1 ? " día" : " días seguidos"));
        pPeores.removeAll();
        List<Object[]> l = new ArrayList<>();
        for (char c : KOCH.substring(0, leccion()).toCharArray()) {
            String[] v = cfg("ST_" + (int) c, "0/0").split("/");
            int ok = Integer.parseInt(v[0]), tot = Integer.parseInt(v[1]);
            if (tot >= 3) l.add(new Object[]{c, 100 * ok / tot});
        }
        l.sort(Comparator.comparingInt(o -> (Integer) o[1]));
        for (int i = 0; i < Math.min(6, l.size()); i++) {
            JProgressBar b = new JProgressBar(0, 100);
            b.setValue((Integer) l.get(i)[1]);
            b.setStringPainted(true);
            b.setString(l.get(i)[0] + "   " + l.get(i)[1] + " %");
            b.setForeground(((Integer) l.get(i)[1]) >= 90 ? VERDE : AMBAR);
            pPeores.add(b);
        }
        if (l.isEmpty()) pPeores.add(l("Aún no hay datos: haz alguna tanda en «Escuchar letras».", 13, GRIS, false));
        pPeores.revalidate();
        pPeores.repaint();
    }

    private void apuntarLetra(char c, boolean bien) {
        String[] v = cfg("ST_" + (int) c, "0/0").split("/");
        int ok = Integer.parseInt(v[0]) + (bien ? 1 : 0), tot = Integer.parseInt(v[1]) + 1;
        if (tot > 100) { ok = ok * 50 / tot; tot = 50; }   // cuentan más las respuestas recientes
        LibroGuardiaUI.ConfigApp.put("CWAPR_ST_" + (int) c, ok + "/" + tot);
    }

    /** Racha de días seguidos practicando. */
    private void apuntarDia() {
        String hoy = LocalDate.now().toString(), ult = cfg("DIA", "");
        if (hoy.equals(ult)) return;
        int r = LocalDate.now().minusDays(1).toString().equals(ult) ? cfgI("RACHA", 0) + 1 : 1;
        cfgPut("RACHA", r);
        LibroGuardiaUI.ConfigApp.put("CWAPR_DIA", hoy);
    }

    // Ajustes del manipulador ------------------------------------------
    private final JLabel lTono = new JLabel(), lVol = new JLabel();

    private JPanel datoSon(String n, JLabel v) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINEA));
        p.add(l(n, 14, TEXTO, false), BorderLayout.WEST);
        v.setFont(VentanaDigitales.f(Font.BOLD, 14));
        v.setForeground(TEXTO);
        p.add(v, BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return p;
    }

    private JRadioButton radio(String t, String ayuda) {
        JRadioButton r = new JRadioButton("<html><div style='width:250px;font-family:Arial'><span style='font-size:12px'>" + t + "</span><br><span style='font-size:10px;color:#8796ad'>" + ayuda + "</span></div></html>");
        r.setOpaque(false);
        r.setIcon(new IconoRadio(false));
        r.setSelectedIcon(new IconoRadio(true));
        r.setIconTextGap(10);
        r.setVerticalTextPosition(SwingConstants.TOP);
        r.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        r.setFont(VentanaDigitales.f(Font.PLAIN, 13));
        r.setForeground(TEXTO);
        r.setFocusable(false);
        return r;
    }

    private JComponent vistaAjustes() {
        JLabel tPad = new JLabel("<html><center>Pincha aquí y mantén<br><span style='font-size:10px'>izquierdo = llave / punto · derecho = raya</span></center></html>", SwingConstants.CENTER);
        tPad.setFont(VentanaDigitales.f(Font.BOLD, 15));
        tPad.setForeground(TEXTO);
        padRaton.add(tPad);
        padRaton.setBackground(BOTON);
        padRaton.setBorder(BorderFactory.createLineBorder(AMBAR, 2));
        padRaton.setPreferredSize(new Dimension(300, 90));
        padRaton.setMaximumSize(new Dimension(330, 90));
        padRaton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        padRaton.addMouseListener(new MouseAdapter() {
            private int bits = 0;
            private int bit(MouseEvent e) { return SwingUtilities.isRightMouseButton(e) ? 2 : SwingUtilities.isLeftMouseButton(e) ? 1 : 0; }
            @Override public void mousePressed(MouseEvent e) { bits |= bit(e); padRaton.setBackground(new Color(0x3a3010)); manip.contactos(bits); }
            @Override public void mouseReleased(MouseEvent e) { bits &= ~bit(e); if (bits == 0) padRaton.setBackground(BOTON); manip.contactos(bits); }
        });
        ButtonGroup g = new ButtonGroup();
        JRadioButton[] rs = {rMando, rMic, rEmisora, rCom, rTeclado, rRaton};
        String guardada = cfg("FUENTE", "MANDO");
        for (JRadioButton r : rs) {
            g.add(r);
            r.addActionListener(e -> { cfgPutS("FUENTE", fuenteElegida()); mostrarOpciones(); conectarFuente(); });
        }
        switch (guardada) {
            case "MIC" -> rMic.setSelected(true);
            case "EMISORA" -> rEmisora.setSelected(true);
            case "COM" -> rCom.setSelected(true);
            case "TECLADO" -> rTeclado.setSelected(true);
            case "RATON" -> rRaton.setSelected(true);
            default -> rMando.setSelected(true);
        }
        cbTipo.setSelectedIndex(Math.min(2, cfgI("TIPO", 0)));
        cbTipo.addActionListener(e -> { cfgPut("TIPO", cbTipo.getSelectedIndex()); manip.ponerTipo(tipoElegido()); });
        for (String n : MotorDigitales.entradas()) cbMic.addItem(n);
        if (!cfg("MIC", "").isEmpty()) cbMic.setSelectedItem(cfg("MIC", ""));
        cbMic.setToolTipText("Tarjeta de sonido donde está enchufado el manipulador (micrófono del PC)");
        cbMic.addActionListener(e -> { cfgPutS("MIC", String.valueOf(cbMic.getSelectedItem())); if (rMic.isSelected()) conectarFuente(); });
        chkInvertir.setOpaque(false);
        chkInvertir.setForeground(TEXTO);
        chkInvertir.setSelected("1".equals(cfg("INVERTIR", "0")));
        chkInvertir.addActionListener(e -> { cfgPutS("INVERTIR", chkInvertir.isSelected() ? "1" : "0"); if (llave instanceof Llave.Microfono m) m.ponerInvertido(chkInvertir.isSelected()); });
        cbPuerto.addActionListener(e -> {
            int i = cbPuerto.getSelectedIndex();
            if (i >= 0 && i < puertos.size()) { cfgPutS("PUERTO", puertos.get(i)); if (rCom.isSelected()) conectarFuente(); }
        });
        bConectar.setToolTipText("Vuelve a buscar el manipulador elegido arriba (por ejemplo, si lo has enchufado con la ventana ya abierta)");
        JButton bProbar = bConectar;
        bProbar.addActionListener(e -> conectarFuente());
        led.setFont(VentanaDigitales.f(Font.BOLD, 14));
        pintarLed(false);
        lblFuente.setFont(VentanaDigitales.f(Font.PLAIN, 12));
        lblFuente.setForeground(GRIS);
        lTono.setText(sTono.getValue() + " Hz");
        lVol.setText(sVol.getValue() + " %");
        sTono.addChangeListener(e -> lTono.setText(sTono.getValue() + " Hz"));
        sVol.addChangeListener(e -> lVol.setText(sVol.getValue() + " %"));
        sTono.addChangeListener(e -> { if (sonido != null) sonido.ponerTono(sTono.getValue()); cfgPut("TONO", sTono.getValue()); });
        sVol.addChangeListener(e -> { if (sonido != null) sonido.ponerVolumen(sVol.getValue() / 100.0); cfgPut("VOL", sVol.getValue()); });
        sTono.setOpaque(false);
        sVol.setOpaque(false);
        spPpm.addChangeListener(e -> { cfgPut("PPM", (Integer) spPpm.getValue()); manip.ponerPpm((Integer) spPpm.getValue()); pintarLetras(); });
        spEfec.addChangeListener(e -> { cfgPut("EFEC", (Integer) spEfec.getValue()); pintarLetras(); });
        spPpm.setToolTipText("Velocidad de las letras (y del manipulador de palas)");
        spEfec.setToolTipText("Espaciado Farnsworth: las letras suenan a la velocidad de la izquierda, con más silencio entre ellas");
        JPanel col = columna(rMando, rMic, cbMic, chkInvertir, rEmisora, rCom, cbPuerto, rTeclado, rRaton, padRaton, fila(l("Tipo:", 13, GRIS, false), cbTipo),
                fila(bProbar, led), lblFuente);
        JPanel son = columna(fila(l("Velocidad", 13, GRIS, false), spPpm, l("ppm · espaciado", 13, GRIS, false), spEfec),
                datoSon("Tono", lTono), sTono, datoSon("Volumen", lVol), sVol,
                l("<html><div style='width:260px'>El pitido suena por los altavoces del PC: en esta ventana nunca se transmite.</div></html>", 12, GRIS, false));
        mostrarOpciones();
        JPanel p = columna(caja("MI MANIPULADOR", col), caja("SONIDO DE PRÁCTICA", son));
        JPanel fondo = new JPanel(new BorderLayout());
        fondo.setBackground(PANEL);
        fondo.add(p, BorderLayout.NORTH);
        return fondo;
    }

    private void mostrarOpciones() {
        cbMic.setVisible(rMic.isSelected() || rEmisora.isSelected());
        chkInvertir.setVisible(rMic.isSelected());
        cbPuerto.setVisible(rCom.isSelected());
        padRaton.setVisible(rRaton.isSelected());
        bConectar.setVisible(!rTeclado.isSelected() && !rRaton.isSelected());
        if (rCom.isSelected() && puertos.isEmpty()) buscarPuertos();
        revalidate();
    }

    private void buscarPuertos() {
        cbPuerto.addItem("buscando…");
        new Thread(() -> {
            List<String[]> ps = PttSerie.puertos();
            SwingUtilities.invokeLater(() -> {
                puertos.clear();
                cbPuerto.removeAllItems();
                for (String[] p : ps) { puertos.add(p[0]); cbPuerto.addItem(p[1]); }
                int i = puertos.indexOf(cfg("PUERTO", ""));
                if (i >= 0) cbPuerto.setSelectedIndex(i);
                if (ps.isEmpty()) cbPuerto.addItem("(no hay puertos COM)");
            });
        }, "AprenderCw-puertos").start();
    }

    private String fuenteElegida() {
        return rMic.isSelected() ? "MIC" : rEmisora.isSelected() ? "EMISORA" : rCom.isSelected() ? "COM" : rTeclado.isSelected() ? "TECLADO" : rRaton.isSelected() ? "RATON" : "MANDO";
    }

    private Manipulador.Tipo tipoElegido() {
        return switch (cbTipo.getSelectedIndex()) { case 1 -> Manipulador.Tipo.YAMBICO_A; case 2 -> Manipulador.Tipo.YAMBICO_B; default -> Manipulador.Tipo.RECTA; };
    }

    /** Cierra la fuente anterior y abre la elegida (en segundo plano: el mando y el COM tardan un par de segundos). */
    private void conectarFuente() {
        Llave vieja = llave;
        llave = null;
        if (vieja != null) vieja.close();
        String f = fuenteElegida();
        if (f.equals("RATON")) { lblFuente.setText("<html><div style='width:260px'>Pincha y mantén en el recuadro: botón izquierdo = llave recta o punto; botón derecho = raya.</div></html>"); return; }
        if (f.equals("TECLADO")) { lblFuente.setText("<html><div style='width:260px'>Barra espaciadora o Ctrl izquierdo/derecho (con el cursor fuera de los campos de texto).</div></html>"); return; }
        lblFuente.setText("Conectando…");
        String mic = String.valueOf(cbMic.getSelectedItem() == null ? "" : cbMic.getSelectedItem());
        String puerto = cfg("PUERTO", "");
        boolean inv = chkInvertir.isSelected();
        new Thread(() -> {
            String msg;
            try {
                Llave n = switch (f) {
                    case "MIC" -> new Llave.Microfono(mic, inv, manip::contactos);
                    case "EMISORA" -> new Llave.PitidoEmisora(LibroGuardiaUI.ConfigApp.get("DIGITALES_ENTRADA", ""), cfgI("TONO_EMISORA", 650), manip::contactos);
                    case "COM" -> Llave.puertoCom(puerto, manip::contactos);
                    default -> Llave.mandoUsb(manip::contactos);
                };
                if (!isDisplayable() || !f.equals(fuenteElegida())) { n.close(); return; }
                llave = n;
                msg = switch (f) {
                    case "MIC" -> "Listo. Aprieta el manipulador: el primer golpe fuerte se toma como «apretar».";
                    case "EMISORA" -> "Listo. Escuchando el pitido de la emisora (" + cfgI("TONO_EMISORA", 650) + " Hz) por la tarjeta de DIGITALES.";
                    case "COM" -> "Listo. Manipulador en " + puerto + ".";
                    default -> "Listo. Mando USB conectado: aprieta el manipulador.";
                };
            } catch (Exception ex) {
                msg = "No se ha podido conectar: " + ex.getMessage();
            }
            final String m = msg;
            SwingUtilities.invokeLater(() -> lblFuente.setText("<html><div style='width:260px'>" + m + "</div></html>"));
        }, "AprenderCw-conectar").start();
    }

    private void pintarLed(boolean ap) {
        led.setText(ap ? "●  apretado" : "●  suelto");
        led.setForeground(ap ? VERDE : GRIS);
    }

    // Teclado: respuestas en «Escuchar letras» y manipulador de teclado --------------
    private final KeyEventDispatcher teclas = new KeyEventDispatcher() {
        private int bits = 0;
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (!isActive()) return false;
            Component foco = e.getComponent();
            boolean enCampo = foco instanceof javax.swing.text.JTextComponent tc && tc.isEditable();
            if (pestanas.getSelectedComponent() == vEscuchar && e.getID() == KeyEvent.KEY_TYPED && esperandoRespuesta && !enCampo) {
                char c = Character.toUpperCase(e.getKeyChar());
                if (KOCH.indexOf(c) >= 0) { responder(c); return true; }
            }
            if (pestanas.getSelectedComponent() == vEscuchar && e.getID() == KeyEvent.KEY_TYPED && !tandaActiva && !enCampo) {
                char c = Character.toUpperCase(e.getKeyChar());
                if (KOCH.indexOf(c) >= 0) { curiosear(c); return true; }
            }
            if (!rTeclado.isSelected() || enCampo) return false;
            int bit = 0;
            if (e.getKeyCode() == KeyEvent.VK_SPACE) bit = 1;
            else if (e.getKeyCode() == KeyEvent.VK_CONTROL) bit = e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT ? 2 : 1;
            if (bit == 0) return false;
            if (e.getID() == KeyEvent.KEY_PRESSED) bits |= bit;
            else if (e.getID() == KeyEvent.KEY_RELEASED) bits &= ~bit;
            else return true;
            manip.contactos(bits);
            return true;
        }
    };

    // Sonido -----------------------------------------------------------
    private static String codigo(char c) {
        String m = Morse.codigo(String.valueOf(c));
        if (m == null) return "";
        // con un espacio entre cada elemento, para que dos rayas seguidas no se vean como una sola
        StringBuilder r = new StringBuilder();
        for (char e : m.toCharArray()) { if (r.length() > 0) r.append(' '); r.append(e == '.' ? '·' : e == '-' ? '—' : e); }
        return r.toString();
    }

    /** Hace sonar un texto. Con «espaciado», el silencio entre letras y palabras va a la velocidad efectiva (Farnsworth). */
    private void tocar(String texto, boolean conRuido) {
        if (sonido == null) return;
        double ppm = (Integer) spPpm.getValue(), efec = Math.min(ppm, (Integer) spEfec.getValue());
        // Farnsworth (ARRL): el tiempo extra se reparte entre los huecos de letra y de palabra
        double farns = efec >= ppm ? 1 : Math.max(1, (60 * ppm - 37.2 * efec) / (19 * 1.2 * efec));   // = 1 cuando son iguales
        List<Double> el = new ArrayList<>(CwMod.elementos(texto, farns));
        el.add(0, -2.0);
        float[] a = CwMod.audio(el, SonidoPractica.FS, ppm, sTono.getValue(), 5);
        sonido.ponerRuido(conRuido && pestanas.getSelectedComponent() == vQso ? new double[]{0, 0.25, 0.7}[cbRuido.getSelectedIndex()] : 0);
        sonido.reproducir(a);
    }

    private void cerrarTodo() {
        reloj.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(teclas);
        if (llave != null) llave.close();
        manip.close();
        if (sonido != null) sonido.close();
        abierta = null;
    }

    // Ajustes guardados -----------------------------------------------
    private static String cfg(String k, String def) { return LibroGuardiaUI.ConfigApp.get("CWAPR_" + k, def); }
    private static int cfgI(String k, int def) { return LibroGuardiaUI.ConfigApp.getInt("CWAPR_" + k, def); }
    private static void cfgPut(String k, int v) { LibroGuardiaUI.ConfigApp.putInt("CWAPR_" + k, v); }
    private static void cfgPutS(String k, String v) { LibroGuardiaUI.ConfigApp.put("CWAPR_" + k, v); }
}
