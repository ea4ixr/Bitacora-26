import digitales.DescodificadorFt8;
import digitales.EmisorDigitales;
import digitales.Ft8Mensaje;
import digitales.GeneradorFtx;
import digitales.HoraNtp;
import digitales.MotorDigitales;
import digitales.PttSerie;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Ventana DIGITALES: FT8 y FT4 dentro de Bitácora 26 (sin WSJT-X).
 * Escucha y descodifica; y transmite solo cuando se pulsa ACTIVAR TX (con el PTT por el CAT y varias protecciones:
 * PARAR / Esc cortan al momento, 6 minutos sin tocar nada, 5 CQ sin respuesta, solo en bandas de radioaficionado,
 * reloj desfasado, CAT o sonido perdidos).
 * Todo lo de los modos digitales está en esta ventana y en la carpeta "digitales": para quitarlo basta con
 * borrar ambos y volver a poner en el botón DIGITALES la llamada a abrirProgramaDigitales.
 */
class VentanaDigitales extends JFrame {
    private static VentanaDigitales instancia;

    /** Abre (o trae al frente) la ventana DIGITALES. */
    static void abrir(LibroGuardiaUI app) {
        if (instancia == null || !instancia.isDisplayable()) instancia = new VentanaDigitales(app);
        instancia.setVisible(true);
        instancia.setExtendedState(instancia.getExtendedState() & ~Frame.ICONIFIED);
        instancia.toFront();
    }

    // ------------------------------------------------------------------ colores (los de Bitácora 26)
    static final Color FONDO = LibroGuardiaUI.TEMA_FONDO, FONDO2 = LibroGuardiaUI.TEMA_FONDO_2, CAMPO = LibroGuardiaUI.TEMA_CAMPO,
            AMBAR = LibroGuardiaUI.TEMA_AMBAR, TEXTO = LibroGuardiaUI.TEMA_TEXTO, LINEA = LibroGuardiaUI.TEMA_LINEA,
            VERDE = new Color(80, 230, 80), ROJO = new Color(235, 60, 60), GRIS = new Color(120, 135, 160),
            ROSA = new Color(255, 110, 230), CELESTE = new Color(90, 190, 255);
    private static final Font MONO = new Font("Monospaced", Font.PLAIN, 13), MONO_N = new Font("Monospaced", Font.BOLD, 13);

    // Frecuencias de llamada (dial, USB) de FT8 y FT4 en cada banda
    private static final String[][] FREC_FT8 = {{"160m", "1840000"}, {"80m", "3573000"}, {"60m", "5357000"}, {"40m", "7074000"},
            {"30m", "10136000"}, {"20m", "14074000"}, {"17m", "18100000"}, {"15m", "21074000"}, {"12m", "24915000"},
            {"10m", "28074000"}, {"6m", "50313000"}, {"2m", "144174000"}, {"70cm", "432174000"}, {"CB", "27265000"}};
    private static final String[][] FREC_FT4 = {{"80m", "3575000"}, {"40m", "7047500"}, {"30m", "10140000"}, {"20m", "14080000"},
            {"17m", "18104000"}, {"15m", "21140000"}, {"12m", "24919000"}, {"10m", "28180000"}, {"6m", "50318000"}, {"2m", "144170000"}};

    private final LibroGuardiaUI app;
    private final MotorDigitales motor;
    private final Cascada cascada = new Cascada();
    // Parte de abajo según el modo: FT8/FT4 o RTTY (la cabecera de arriba es la misma)
    private final JPanel tarjetas = new JPanel(new CardLayout());
    private VentanaRtty rtty;
    private boolean enRtty = false;
    private static final String[][] FREC_RTTY = {{"80m", "3580000"}, {"40m", "7040000"}, {"30m", "10140000"}, {"20m", "14080000"},
            {"17m", "18100000"}, {"15m", "21080000"}, {"12m", "24910000"}, {"10m", "28080000"}};
    private final DefaultTableModel modeloActividad = new DefaultTableModel(new Object[]{"UTC", "dB", "DT", "Hz", "Mensaje", "País", ""}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final List<Fila> filas = new ArrayList<>();
    private JTable tablaActividad;
    private final JPanel panelMiFrec = new JPanel();
    // ME LLAMAN: las estaciones que me llaman (rojo = sin contestar, ámbar = contestada sin terminar, sin color = hecho)
    private final JPanel panelMeLlaman = new JPanel();
    private static final class Llamada { Fila fl; int estado; Llamada(Fila f) { fl = f; } }
    private final Map<String, Llamada> llamadas = new LinkedHashMap<>();
    private JComponent panelOpciones;   // OPCIONES: ahora dentro de Ajustes
    private final List<Fila> filasMiFrec = new ArrayList<>();
    /** Frecuencia y hora con cifras de 7 segmentos, como el display de la ventana principal. */
    private static final Color VERDE_RELOJ = new Color(60, 220, 90);
    private final Siete lblFrecuencia = new Siete("   .   .   ", 22, 32, AMBAR), lblHora = new Siete("--:--:--", 20, 30, VERDE_RELOJ);
    /** Botones de banda, en el orden en que se ven (dos filas). */
    private static final String[] BANDAS_BOTONES = {"160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "2m", "70cm", "CB"};
    private final Map<String, JToggleButton> botonesBanda = new LinkedHashMap<>();
    private final JLabel lblBandaModo = new JLabel(" "), lblReloj = new JLabel(" ");
    private final JLabel lblEstado = new JLabel(" ");
    private final BarraPeriodo barra = new BarraPeriodo();
    private final Medidor medidor = new Medidor();
    private final Map<String, JToggleButton> botonesModo = new LinkedHashMap<>();
    private final JTextField[] txMsg = new JTextField[6];
    private final JRadioButton[] txSel = new JRadioButton[6];
    private final JTextField txtRx = new JTextField(4), txtTx = new JTextField(4);
    private final JComboBox<String> cbProfundidad = new JComboBox<>(new String[]{"Rápido", "Normal", "A fondo"});
    private final JToggleButton btnEscuchar;
    private final JLabel lblMiFrecTitulo = new JLabel();
    private TitledBorder bordeMiFrec;
    // contacto en curso
    private final JLabel cIndicativo = new JLabel(" "), cNombre = new JLabel(" "), cLugar = new JLabel(" "), cInforme = new JLabel(" ");
    private final JPanel cEtiquetas = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JLabel[] pasos = new JLabel[7];
    private String dxIndicativo = "", dxGrid = "";
    private int dxSnr = -99, dxFrecuencia = 0;
    // lo que se sabe del LOG
    private final Set<String> trabajados = new HashSet<>(), trabajadosBanda = new HashSet<>(), paisesBanda = new HashSet<>(), gridsBanda = new HashSet<>(),
            paises = new HashSet<>(), grids = new HashSet<>();
    private final Map<String, String[]> datosLog = new HashMap<>();
    /** Lo trabajado según el LOG, para los colores: "DXCC:EA", "DXCC:EA|40m", "DXCC:EA#FT8"... */
    private final Set<String> hecho = new HashSet<>();
    private long ultimaCargaLog = 0;
    private final javax.swing.Timer reloj;
    private int rxHz, txHz;
    private final List<double[]> dtsUltimo = new ArrayList<>();
    // transmitir
    private final EmisorDigitales emisor;
    private final Ft8Mensaje codificador = new Ft8Mensaje();   // solo para preparar lo que se transmite
    private final JToggleButton btnTx, btnAfinar;
    private final JCheckBox chkAuto = new JCheckBox("Secuencia automática"), chkPrimero = new JCheckBox("Responder al primero que conteste"),
            chkMantener = new JCheckBox("Mantener mi frecuencia de TX"), chkGuardar = new JCheckBox("Guardar solo en el LOG al terminar");
    private final JComboBox<String> cbTurno = new JComboBox<>(new String[]{"1º", "2º"});
    private final JSlider sNivel = new JSlider(0, 100, 60);
    private boolean txActivo = false;
    private long ultimaAccion = System.currentTimeMillis();
    private int cqSeguidos = 0;
    private long qsoInicio = 0;
    private String rstEnviado = "", rstRecibido = "";
    private boolean qsoGuardado = false;
    private Double relojMediana = null;
    private String cacheTexto = null;
    private GeneradorFtx.Modo cacheModo = null;
    private int[] cacheTonos = null;
    private String cacheSale = null;
    private static boolean confirmadoEnEstaSesion = false;
    /** Cómo se pone la emisora en transmisión: CAT, VOX, RTS o DTR (estos dos, por el puerto COM elegido). */
    private volatile String metodoPtt = LibroGuardiaUI.ConfigApp.get("DIGITALES_PTT", "CAT");
    private volatile String puertoPtt = LibroGuardiaUI.ConfigApp.get("DIGITALES_PTT_PUERTO", "");
    /** Modo de la emisora para digitales: NINGUNO (no se toca), USB o DATOS (DATA-U). */
    private volatile String modoTx = LibroGuardiaUI.ConfigApp.get("DIGITALES_MODO_TX", "USB");
    private PttSerie serie;
    private static final int PM_DIG_U = 0x08000000;
    private static final long VIGILANTE_MS = 6 * 60_000;
    /** Protecciones que el usuario puede quitar en AJUSTES (todas puestas de fábrica). */
    static final String PROT_VIGILANTE = "DIGITALES_PROT_6MIN", PROT_CQ = "DIGITALES_PROT_5CQ", PROT_RELOJ = "DIGITALES_PROT_RELOJ",
            PROT_CB = "DIGITALES_PROT_CB", PROT_BANDA = "DIGITALES_PROT_BANDA", PROT_ESC = "DIGITALES_PROT_ESC", PROT_CONFIRMAR = "DIGITALES_PROT_CONFIRMAR";

    /** Protecciones, actividad y nº de señales del FOX, leídos de la configuración al abrir y al aceptar AJUSTES. */
    private final Map<String, Boolean> protCache = new HashMap<>();
    private String actividadCache = null;
    private int foxSenalesCache = 5;

    private void leerAjustesRapidos() {
        protCache.clear();
        for (String k : new String[]{PROT_VIGILANTE, PROT_CQ, PROT_RELOJ, PROT_CB, PROT_BANDA, PROT_ESC, PROT_CONFIRMAR})
            protCache.put(k, LibroGuardiaUI.ConfigApp.getInt(k, 1) == 1);
        actividadCache = LibroGuardiaUI.ConfigApp.get("DIGITALES_ACTIVIDAD", NINGUNO);
        foxSenalesCache = Math.max(1, Math.min(5, LibroGuardiaUI.ConfigApp.getInt("DIGITALES_FOX_SENALES", 5)));
    }

    boolean prot(String clave) {
        if (protCache.isEmpty()) leerAjustesRapidos();
        return protCache.getOrDefault(clave, true);
    }
    private static final int MAX_CQ = 5;
    /** Bandas de radioaficionado en España donde se puede transmitir (Hz). La CB queda fuera (de momento). */
    private static final long[][] BANDAS_TX = {{1810000, 2000000}, {3500000, 3800000}, {5351500, 5366500}, {7000000, 7200000},
            {10100000, 10150000}, {14000000, 14350000}, {18068000, 18168000}, {21000000, 21450000}, {24890000, 24990000},
            {28000000, 29700000}, {50000000, 52000000}, {144000000, 146000000}, {430000000, 440000000}};

    // ------------------------------------------------------------------ modos especiales (concursos y expediciones)
    static final String NINGUNO = "NINGUNO", HOUND = "HOUND", FOX = "FOX", NA_VHF = "NA_VHF", EU_VHF = "EU_VHF", FIELD_DAY = "FIELD_DAY",
            RTTY_RU = "RTTY_RU", WW_DIGI = "WW_DIGI", ARRL_DIGI = "ARRL_DIGI";
    static final String[][] ACTIVIDADES = {{NINGUNO, "Ninguna (FT8 / FT4 normal)"}, {HOUND, "Expedición: llamar a un FOX (Hound)"},
            {FOX, "Expedición: ser el FOX (DXpedition)"}, {NA_VHF, "Concurso NA VHF"}, {EU_VHF, "Concurso EU VHF"},
            {FIELD_DAY, "Concurso ARRL Field Day"}, {RTTY_RU, "Concurso ARRL RTTY Roundup"}, {WW_DIGI, "Concurso WW Digi DX"},
            {ARRL_DIGI, "Concurso ARRL Digi"}};

    /** La actividad elegida en AJUSTES. FOX y HOUND solo existen en FT8 (en FT4 se trabaja normal). */
    String actividad() {
        if (actividadCache == null) leerAjustesRapidos();
        String a = actividadCache;
        if ((FOX.equals(a) || HOUND.equals(a)) && motor != null && motor.modo() == GeneradorFtx.Modo.FT4) return NINGUNO;
        return a;
    }

    static String nombreActividad(String a) {
        for (String[] x : ACTIVIDADES) if (x[0].equals(a)) return x[1];
        return a;
    }

    /** Concursos en los que el intercambio es el locator (4 letras). */
    private static boolean concursoGrid(String a) { return NA_VHF.equals(a) || WW_DIGI.equals(a) || ARRL_DIGI.equals(a); }

    private static boolean concurso(String a) { return concursoGrid(a) || EU_VHF.equals(a) || FIELD_DAY.equals(a) || RTTY_RU.equals(a); }

    /** Lo que va detrás de CQ en cada concurso. */
    private static String palabraCq(String a) {
        return switch (a) {
            case NA_VHF, EU_VHF, ARRL_DIGI -> "TEST ";
            case WW_DIGI -> "WW ";
            case FIELD_DAY -> "FD ";
            case RTTY_RU -> "RU ";
            default -> "";
        };
    }

    /** Siguiente número de serie (EU VHF y RTTY Roundup). */
    private static int serie() { return Math.max(1, LibroGuardiaUI.ConfigApp.getInt("DIGITALES_SERIE", 1)); }

    /** RST de RTTY Roundup (5x9) según cómo se le oye. */
    private static String rstRu(int snr) { return "5" + Math.max(2, Math.min(9, 9 + Math.floorDiv(snr + 6, 4))) + "9"; }

    /** Informe de EU VHF (52..59) según cómo se le oye. */
    private static String rptEu(int snr) { return "5" + Math.max(2, Math.min(9, 9 + Math.floorDiv(snr + 6, 4))); }

    private String miGrid6() {
        String g = misDatos()[4];
        g = g == null ? "" : g.trim().toUpperCase();
        return g.length() >= 6 ? g.substring(0, 6) : g;
    }

    /** Una línea descodificada. */
    private record Fila(long inicio, DescodificadorFt8.Resultado r, String pais, String iso, String estado, String banda) { }

    private VentanaDigitales(LibroGuardiaUI app) {
        super("DIGITALES · Bitácora 26");
        this.app = app;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        try { setIconImages(app.getIconImages()); } catch (Exception ignored) { }
        rxHz = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_RX_HZ", 1500);
        txHz = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_TX_HZ", 1500);
        motor = new MotorDigitales(new MotorDigitales.Oyente() {
            @Override public void lineaCascada(float[] db, double hz) { cascada.anadirLinea(db, hz); }
            @Override public void nivel(double db) { medidor.ponerNivel(db); }
            @Override public void resultados(long inicio, GeneradorFtx.Modo m, List<DescodificadorFt8.Resultado> r, long ms) {
                SwingUtilities.invokeLater(() -> mostrarResultados(inicio, m, r, ms));
            }
            @Override public void rapidos(long inicio, GeneradorFtx.Modo m, List<DescodificadorFt8.Resultado> r) {
                // Lo que me contestan, en cuanto se sabe: así el siguiente mensaje sale en mi turno (como WSJT-X)
                SwingUtilities.invokeLater(() -> { secuencia(inicio, r); emisor.descodificado(inicio); });
            }
            @Override public void estado(String t) { SwingUtilities.invokeLater(() -> lblEstado.setText("  " + t)); }
        });
        String modoGuardado = LibroGuardiaUI.ConfigApp.get("DIGITALES_MODO", "FT8");
        motor.ponerModo("FT4".equals(modoGuardado) ? GeneradorFtx.Modo.FT4 : GeneradorFtx.Modo.FT8);
        emisor = new EmisorDigitales(this::ponerPtt, new EmisorDigitales.Avisos() {
            @Override public void empieza(long inicio, EmisorDigitales.Plan p) {
                SwingUtilities.invokeLater(() -> lblEstado.setText("  TRANSMITIENDO: " + p.texto() + "   ·   PARAR o Esc para cortar"));
            }
            @Override public void termina(long inicio, EmisorDigitales.Plan p, boolean completo) {
                SwingUtilities.invokeLater(() -> transmitido(inicio, p, completo));
            }
            @Override public void error(String t) {
                SwingUtilities.invokeLater(() -> { desactivarTx(t); btnAfinar.setSelected(false); });
            }
        });
        emisor.ponerSalida(LibroGuardiaUI.ConfigApp.get("DIGITALES_SALIDA", ""));
        emisor.ponerCanalSalida(canalSalida());
        sNivel.setValue(Math.max(0, Math.min(100, LibroGuardiaUI.ConfigApp.getInt("DIGITALES_NIVEL_TX", 60))));
        emisor.ponerNivelDb((sNivel.getValue() - 100) * 0.4);
        btnEscuchar = conmutador("ESCUCHAR", false);
        btnEscuchar.setFont(new Font("Arial", Font.BOLD, 14));
        btnTx = conmutador("ACTIVAR TX", false);
        btnTx.setFont(new Font("Arial", Font.BOLD, 14));
        btnAfinar = conmutador("AFINAR", false);
        btnAfinar.setFont(new Font("Arial", Font.BOLD, 13));
        construir();
        actualizarOpciones();
        llenarBandas();
        reloj = new javax.swing.Timer(200, e -> cadaRato());
        reloj.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { if (rtty != null) rtty.desactivar(); emisor.cerrar(); reloj.stop(); motor.parar(); cerrarSerie(); }
        });
        setSize(1440, 930);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(app);
        // empezar a escuchar solo
        SwingUtilities.invokeLater(() -> { btnEscuchar.setSelected(true); escuchar(true); });
        // y comprobar la hora con internet (como pinchar en el reloj); si va mal, se pone en hora sola
        SwingUtilities.invokeLater(() -> comprobarHora(true));
        if (FOX.equals(actividad())) SwingUtilities.invokeLater(this::abrirVentanaFox);
    }

    // ------------------------------------------------------------------ piezas sueltas
    static Font f(int st, int sz) { return new Font("Arial", st, sz); }

    static JLabel lbl(String t, Font fo, Color c) { JLabel l = new JLabel(t); l.setFont(fo); l.setForeground(c); return l; }

    static JButton boton(String t) {
        JButton b = new JButton(t);
        b.setUI(new ControlEmisora.BotonEmisoraUI());
        b.setFont(f(Font.BOLD, 13));
        b.setFocusable(false);
        return b;
    }

    static JToggleButton conmutador(String t, boolean sel) {
        JToggleButton b = new JToggleButton(t, sel);
        b.setUI(new ControlEmisora.BotonEmisoraUI());
        b.setFont(f(Font.BOLD, 12));
        b.setFocusable(false);
        b.setMargin(new Insets(2, 7, 2, 7));
        return b;
    }

    static JPanel caja(String titulo, JComponent dentro) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(FONDO2);
        TitledBorder tb = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(LINEA), " " + titulo + " ");
        tb.setTitleFont(f(Font.BOLD, 12));
        tb.setTitleColor(AMBAR);
        p.setBorder(BorderFactory.createCompoundBorder(tb, BorderFactory.createEmptyBorder(2, 6, 6, 6)));
        p.add(dentro);
        return p;
    }

    static JLabel etiqueta(String t, Color c) {
        JLabel l = lbl(" " + t + " ", f(Font.BOLD, 11), c);
        l.setBorder(BorderFactory.createLineBorder(c));
        return l;
    }


    // ------------------------------------------------------------------ construir la ventana
    private void construir() {
        JPanel raiz = new JPanel(new BorderLayout(0, 6));
        raiz.setBackground(FONDO);
        raiz.add(cabecera(), BorderLayout.NORTH);
        JPanel cuerpo = new JPanel(new BorderLayout(0, 6));
        cuerpo.setOpaque(false);
        cuerpo.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        cascada.setPreferredSize(new Dimension(100, 200));
        cuerpo.add(caja("CASCADA  ·  pincha en una señal para escucharla (verde = recibir, rojo = transmitir; botón derecho = transmitir)", cascada), BorderLayout.NORTH);

        JPanel medio = new JPanel(new GridBagLayout());
        medio.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH; g.weighty = 1; g.insets = new Insets(0, 0, 0, 6);
        tablaActividad = tablaActividad();
        JScrollPane sp = new JScrollPane(tablaActividad);
        sp.getViewport().setBackground(CAMPO);
        sp.setBorder(BorderFactory.createEmptyBorder());
        JPanel c1 = caja("ACTIVIDAD EN LA BANDA  ·  doble clic para elegir una estación", sp);
        c1.setPreferredSize(new Dimension(640, 100)); c1.setMinimumSize(new Dimension(500, 100));
        g.gridx = 0; g.weightx = 0.5; medio.add(c1, g);
        panelMiFrec.setLayout(new BoxLayout(panelMiFrec, BoxLayout.Y_AXIS));
        panelMiFrec.setBackground(CAMPO);
        panelMiFrec.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane spMi = new JScrollPane(panelMiFrec);
        spMi.setBorder(BorderFactory.createEmptyBorder());
        spMi.getViewport().setBackground(CAMPO);
        JPanel c2 = caja("MI FRECUENCIA", spMi);
        bordeMiFrec = (TitledBorder) ((javax.swing.border.CompoundBorder) c2.getBorder()).getOutsideBorder();
        c2.setPreferredSize(new Dimension(330, 100)); c2.setMinimumSize(new Dimension(260, 100));
        g.gridx = 1; g.weightx = 0.2; medio.add(c2, g);
        JPanel c3 = caja("CONTACTO EN CURSO", contacto());
        c3.setPreferredSize(new Dimension(420, 100)); c3.setMinimumSize(new Dimension(330, 100));
        g.gridx = 2; g.weightx = 0.3; g.insets = new Insets(0, 0, 0, 0); medio.add(c3, g);
        cuerpo.add(medio);

        JPanel abajo = new JPanel(new GridBagLayout());
        abajo.setOpaque(false);
        GridBagConstraints h = new GridBagConstraints();
        h.fill = GridBagConstraints.BOTH; h.weighty = 1; h.insets = new Insets(0, 0, 0, 6);
        h.gridx = 0; h.weightx = 0.42; abajo.add(caja("MENSAJES QUE SE TRANSMITEN", mensajesTx()), h);
        panelOpciones = opciones();
        panelMeLlaman.setLayout(new BoxLayout(panelMeLlaman, BoxLayout.Y_AXIS));
        panelMeLlaman.setBackground(CAMPO);
        panelMeLlaman.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane spLl = new JScrollPane(panelMeLlaman);
        spLl.setBorder(BorderFactory.createEmptyBorder());
        spLl.getViewport().setBackground(CAMPO);
        spLl.setPreferredSize(new Dimension(300, 100));
        h.gridx = 1; h.weightx = 0.33; abajo.add(caja("ME LLAMAN  ·  rojo: sin contestar · ámbar: a medias · doble clic para elegir", spLl), h);
        h.gridx = 2; h.weightx = 0.25; h.insets = new Insets(0, 0, 0, 0); abajo.add(caja("MANDOS", mandos()), h);
        cuerpo.add(abajo, BorderLayout.SOUTH);
        tarjetas.setOpaque(false);
        tarjetas.add(cuerpo, "FT");
        raiz.add(tarjetas);
        lblEstado.setFont(f(Font.BOLD, 12));
        lblEstado.setForeground(TEXTO);
        lblEstado.setOpaque(true);
        lblEstado.setBackground(new Color(16, 23, 35));
        lblEstado.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        raiz.add(lblEstado, BorderLayout.SOUTH);
        setContentPane(raiz);
        actualizarTituloMiFrec();
    }

    private static boolean existeClase(String nombre) {
        try { Class.forName(nombre); return true; } catch (Throwable e) { return false; }
    }

    /** Canales propios de DIGITALES (si no se han elegido, los de «Archivo → Configurar audio»). */
    static String canalEntrada() { return LibroGuardiaUI.ConfigApp.get("DIGITALES_CANAL", LibroGuardiaUI.ConfigApp.get("AUDIO_CANAL", "AUTO")); }
    static String canalSalida() { return LibroGuardiaUI.ConfigApp.get("DIGITALES_CANAL_SALIDA", LibroGuardiaUI.ConfigApp.get("AUDIO_CANAL_SALIDA", "AUTO")); }

    /** Abre la ventana de un modo que va aparte (WSPR...). Se llama por su nombre: si no está, no pasa nada. */
    private void abrirModoExtra(String clase) {
        try {
            Class.forName(clase).getMethod("abrir", Window.class, String.class, String.class,
                            java.util.function.BiFunction.class, java.util.function.BiConsumer.class, java.util.function.LongConsumer.class,
                            String.class, EmisorDigitales.Ptt.class, String.class)
                    .invoke(null, this, miGrid6(), LibroGuardiaUI.ConfigApp.get("AUDIO_ENTRADA", ""),
                            (java.util.function.BiFunction<String, String, String>) LibroGuardiaUI.ConfigApp::get,
                            (java.util.function.BiConsumer<String, String>) LibroGuardiaUI.ConfigApp::put,
                            (java.util.function.LongConsumer) hz -> SwingUtilities.invokeLater(() -> irAFrecuencia(hz)),
                            miIndicativo(), (EmisorDigitales.Ptt) this::ponerPtt, LibroGuardiaUI.ConfigApp.get("AUDIO_SALIDA", ""));
        } catch (Throwable ex) {
            JOptionPane.showMessageDialog(this, "No se ha podido abrir: " + ex, "DIGITALES", JOptionPane.WARNING_MESSAGE);
        }
    }

    private JComponent cabecera() {
        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setBackground(new Color(16, 23, 35));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINEA), BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        ButtonGroup grupo = new ButtonGroup();
        JPanel izq = new JPanel(new GridLayout(2, 1, 0, 4));
        izq.setOpaque(false);
        String[][] grupos = {{"CONTACTOS", "FT8", "FT4", "RTTY"}, {"BALIZAS", "WSPR"}};
        for (String[] gr : grupos) {
            JPanel fila = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            fila.setOpaque(false);
            JLabel t = lbl(gr[0], f(Font.BOLD, 10), GRIS);
            t.setPreferredSize(new Dimension(70, 20));
            fila.add(t);
            for (int k = 1; k < gr.length; k++) {
                String m = gr[k];
                JToggleButton b = conmutador(m, false);
                boolean listo = m.equals("FT8") || m.equals("FT4");
                // Modos que van en su propia carpeta (digitales/wspr...): si la carpeta no está, el botón sigue en gris
                String extra = m.equals("WSPR") ? "digitales.wspr.VentanaWspr" : null;
                boolean hayExtra = extra != null && existeClase(extra);
                if (m.equals("RTTY")) {   // RTTY va en su propia ventana
                    b.setToolTipText("RTTY: abre su ventana");
                    b.addActionListener(e -> ponerRtty(true));
                    grupo.add(b);
                    botonesModo.put(m, b);
                    fila.add(b);
                    continue;
                }
                b.setEnabled(listo || hayExtra);
                b.setToolTipText(listo ? "Modo " + m : m + ": abre su ventana (balizas)");
                if (listo) b.addActionListener(e -> cambiarModo(m));
                if (hayExtra) b.addActionListener(e -> {
                    abrirModoExtra(extra);
                    JToggleButton actual = botonesModo.get(motor.modo().name());
                    if (actual != null) actual.setSelected(true);
                });
                grupo.add(b);
                botonesModo.put(m, b);
                fila.add(b);
            }
            izq.add(fila);
        }
        botonesModo.get(motor.modo().name()).setSelected(true);
        p.add(izq, BorderLayout.WEST);

        JPanel centro = new JPanel(new GridBagLayout());
        centro.setOpaque(false);
        lblBandaModo.setFont(f(Font.BOLD, 13));
        lblBandaModo.setForeground(TEXTO);
        JPanel cf = new JPanel(new BorderLayout());
        cf.setOpaque(false);
        cf.add(lblFrecuencia);
        cf.add(lblBandaModo, BorderLayout.SOUTH);
        GridBagConstraints gcc = new GridBagConstraints();
        gcc.anchor = GridBagConstraints.CENTER;
        gcc.insets = new Insets(0, 8, 0, 14);
        centro.add(cf, gcc);
        JPanel bandas = new JPanel(new GridLayout(2, 7, 4, 4));
        bandas.setOpaque(false);
        for (String b : BANDAS_BOTONES) {
            JToggleButton x = conmutador(b, false);
            x.setPreferredSize(new Dimension(62, 28));
            x.addActionListener(e -> irABanda(b));
            botonesBanda.put(b, x);
            bandas.add(x);
        }
        gcc.insets = new Insets(0, 0, 0, 8);
        centro.add(bandas, gcc);
        p.add(centro);

        // Reloj (verde, 7 segmentos) con «UTC» al lado; debajo el aviso del reloj y la barra del periodo,
        // del mismo ancho que el reloj con su «UTC»
        JPanel filaHora = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filaHora.setOpaque(false);
        lblHora.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblHora.setToolTipText("Pulsa para comprobar la hora con internet (" + HoraNtp.SERVIDORES[0] + ") y poner en hora el ordenador");
        lblHora.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { comprobarHora(false); }
        });
        filaHora.add(lblHora);
        JLabel utc = lbl("UTC", f(Font.BOLD, 12), TEXTO);
        utc.setVerticalAlignment(SwingConstants.TOP);
        utc.setPreferredSize(new Dimension(utc.getPreferredSize().width, lblHora.getPreferredSize().height));
        filaHora.add(utc);
        int anchoReloj = filaHora.getPreferredSize().width;
        lblReloj.setFont(f(Font.BOLD, 11));
        lblReloj.setForeground(TEXTO);
        lblReloj.setPreferredSize(new Dimension(anchoReloj, 18));
        barra.setPreferredSize(new Dimension(anchoReloj, 18));
        JPanel der = new JPanel(new GridBagLayout());
        der.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.anchor = GridBagConstraints.WEST; gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridy = 0; der.add(filaHora, gc);
        gc.gridy = 1; gc.insets = new Insets(0, 2, 2, 0); der.add(lblReloj, gc);
        gc.gridy = 2; gc.insets = new Insets(0, 0, 0, 0); der.add(barra, gc);
        JPanel audio = new JPanel(new BorderLayout(4, 0));
        audio.setOpaque(false);
        medidor.setPreferredSize(new Dimension(12, 50));
        audio.add(medidor, BorderLayout.CENTER);
        audio.add(medidor.etiqueta, BorderLayout.SOUTH);
        JPanel derTodo = new JPanel(new BorderLayout(10, 0));
        derTodo.setOpaque(false);
        derTodo.add(der);
        derTodo.add(audio, BorderLayout.EAST);
        p.add(derTodo, BorderLayout.EAST);
        return p;
    }

    private JTable tablaActividad() {
        JTable t = new JTable(modeloActividad);
        t.setRowHeight(22);
        t.setShowVerticalLines(false);
        t.setGridColor(new Color(40, 54, 76));
        t.setBackground(CAMPO);
        t.setFont(MONO);
        t.getTableHeader().setFont(f(Font.BOLD, 12));
        t.getTableHeader().setReorderingAllowed(false);
        int[] an = {74, 34, 40, 42, 170, 100, 104};
        t.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        for (int i = 0; i < an.length; i++) { t.getColumnModel().getColumn(i).setPreferredWidth(an[i]); t.getColumnModel().getColumn(i).setMinWidth(an[i] - 6); }
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean fo, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                int mr = tb.convertRowIndexToModel(r);
                Fila fl = mr < filas.size() ? filas.get(mr) : null;
                String msg = fl == null ? "" : fl.r.mensaje();
                String est = fl == null ? "" : fl.estado;
                ColoresDigitales.Estilo tipo = fl == null ? ColoresDigitales.estilo(Set.of(), "") : tipoColor(fl);
                Color fondo = tipo.fondo(), letra = tipo.letra();
                if (s) fondo = tipo.normal() ? fondo.brighter() : fondo.darker();
                l.setBackground(fondo);
                l.setForeground(letra);
                l.setOpaque(true);
                l.setIcon(null);
                l.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 4));
                if (c == 6) {
                    l.setFont(f(Font.BOLD, 11));
                    if (tipo.normal()) {
                        if (est.contains("NUEVO")) l.setForeground(ROSA);
                        else if (est.startsWith("YA") || est.startsWith("OTRA")) l.setForeground(GRIS);
                        else if (est.startsWith("TE")) l.setForeground(AMBAR);
                    }
                } else if (c == 4) l.setFont(MONO_N);
                else if (c == 5) {
                    l.setFont(f(Font.PLAIN, 12));
                    if (fl != null && fl.iso != null) l.setIcon(bandera(fl.iso));
                } else l.setFont(MONO);
                l.setHorizontalAlignment(c >= 1 && c <= 3 ? RIGHT : LEFT);
                return l;
            }
        });
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // pinchando en la cabecera se ordena (ascendente / descendente), como en el LOG
        javax.swing.table.TableRowSorter<DefaultTableModel> ord = new javax.swing.table.TableRowSorter<>(modeloActividad);
        Comparator<Object> numero = (a1, b1) -> {
            double x1, y1;
            try { x1 = Double.parseDouble(String.valueOf(a1)); } catch (Exception e) { x1 = Double.NEGATIVE_INFINITY; }
            try { y1 = Double.parseDouble(String.valueOf(b1)); } catch (Exception e) { y1 = Double.NEGATIVE_INFINITY; }
            return Double.compare(x1, y1);
        };
        for (int c = 1; c <= 3; c++) ord.setComparator(c, numero);
        t.setRowSorter(ord);
        t.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int r = t.rowAtPoint(e.getPoint());
                    if (r >= 0) elegirEstacion(filas.get(t.convertRowIndexToModel(r)));
                }
            }
        });
        return t;
    }

    private final Map<String, Icon> banderas = new HashMap<>();

    private Icon bandera(String iso) {
        return banderas.computeIfAbsent(iso, k -> {
            try {
                ImageIcon i = LibroGuardiaUI.banderaIso(k);
                if (i == null) return null;
                Image im = i.getImage();
                int h = 14, w = Math.max(1, im.getWidth(null) * h / Math.max(1, im.getHeight(null)));
                return new ImageIcon(im.getScaledInstance(w, h, Image.SCALE_SMOOTH));
            } catch (Exception e) { return null; }
        });
    }

    private JComponent contacto() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(FONDO2);
        cIndicativo.setFont(f(Font.BOLD, 30)); cIndicativo.setForeground(AMBAR);
        cNombre.setFont(f(Font.BOLD, 13)); cNombre.setForeground(TEXTO);
        cLugar.setFont(f(Font.PLAIN, 13)); cLugar.setForeground(TEXTO);
        cInforme.setFont(f(Font.PLAIN, 13)); cInforme.setForeground(TEXTO);
        cEtiquetas.setOpaque(false);
        p.add(cIndicativo); p.add(cNombre); p.add(Box.createVerticalStrut(3)); p.add(cLugar); p.add(cInforme);
        p.add(Box.createVerticalStrut(6)); p.add(cEtiquetas);
        p.add(Box.createVerticalStrut(10));
        p.add(lbl("Cómo va el contacto  ", f(Font.BOLD, 12), AMBAR));
        p.add(Box.createVerticalStrut(4));
        String[] nombres = {"CQ", "Responde", "Informe", "R-Informe", "RR73", "73", "LOG"};
        JPanel cad = new JPanel(new GridLayout(1, nombres.length, 3, 0));
        cad.setOpaque(false);
        for (int i = 0; i < nombres.length; i++) {
            JLabel l = new JLabel(nombres[i], SwingConstants.CENTER);
            l.setOpaque(true);
            l.setFont(f(Font.BOLD, 10));
            l.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
            l.setBackground(new Color(40, 52, 72));
            l.setForeground(GRIS);
            pasos[i] = l;
            cad.add(l);
        }
        p.add(cad);
        p.add(Box.createVerticalStrut(10));
        JButton rellenar = boton("Pasar al contacto de la ventana principal");
        rellenar.setToolTipText("Pone indicativo, locator, banda, modo, frecuencia e informes en el contacto de Bitácora 26 (no lo guarda)");
        rellenar.addActionListener(e -> rellenarContactoPrincipal());
        JButton responderC = new JButton("Responder");
        CuentaBitacora.pintarCrearCuenta(responderC);
        responderC.setFont(f(Font.BOLD, 13));
        responderC.setToolTipText("Llama a la estación elegida (pone en marcha la transmisión)");
        responderC.addActionListener(e -> {
            if (dxIndicativo.isEmpty()) { lblEstado.setText("  Primero elige una estación con doble clic en la lista."); return; }
            activarTx(true);
        });
        JPanel filaBot = new JPanel(new BorderLayout(8, 0));
        filaBot.setOpaque(false);
        filaBot.add(rellenar, BorderLayout.WEST);
        filaBot.add(responderC, BorderLayout.EAST);
        filaBot.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        p.add(filaBot);
        // botón derecho sobre el indicativo o el nombre: ver en QRZ.com
        MouseAdapter qrzMenu = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) menu(e); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu(e); }
            private void menu(MouseEvent e) {
                if (dxIndicativo.isEmpty()) return;
                JPopupMenu m = new JPopupMenu();
                JMenuItem q = new JMenuItem("Ver " + dxIndicativo + " en QRZ.com");
                q.addActionListener(a -> verEnQrz(dxIndicativo));
                m.add(q);
                m.show(e.getComponent(), e.getX(), e.getY());
            }
        };
        cIndicativo.addMouseListener(qrzMenu);
        cNombre.addMouseListener(qrzMenu);
        p.add(Box.createVerticalStrut(8));
        JTextArea nota = new JTextArea("Doble clic en una estación de la lista para elegirla y luego «Responder». "
                + "Al terminar el contacto se guarda solo en el LOG.");
        nota.setLineWrap(true); nota.setWrapStyleWord(true); nota.setEditable(false); nota.setOpaque(false);
        nota.setForeground(new Color(160, 178, 205)); nota.setFont(f(Font.PLAIN, 12));
        p.add(nota);
        for (Component c : p.getComponents()) if (c instanceof JComponent j) j.setAlignmentX(0);
        return p;
    }

    private JComponent mensajesTx() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(FONDO2);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 2, 2, 2);
        g.fill = GridBagConstraints.HORIZONTAL;
        ButtonGroup bg = new ButtonGroup();
        for (int i = 0; i < 6; i++) {
            g.gridy = i; g.gridx = 0; g.weightx = 0;
            txSel[i] = new JRadioButton("Tx" + (i + 1), i == 5);
            txSel[i].setFont(f(Font.BOLD, 12));
            txSel[i].setForeground(TEXTO);
            txSel[i].setOpaque(false);
            txSel[i].setToolTipText("El mensaje marcado es el que se transmite en el próximo turno");
            txSel[i].addActionListener(e -> { accionUsuario(); actualizarPlan(); });
            bg.add(txSel[i]);
            p.add(txSel[i], g);
            g.gridx = 1; g.weightx = 1;
            txMsg[i] = new JTextField();
            txMsg[i].setFont(MONO_N);
            mayusculas(txMsg[i]);
            txMsg[i].getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { if (algunoConFoco()) accionUsuario(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { if (algunoConFoco()) accionUsuario(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
            });
            p.add(txMsg[i], g);
        }
        g.gridy = 6; g.gridx = 0; g.gridwidth = 2;
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        b.setOpaque(false);
        JButton cq = boton("Llamar CQ");
        cq.setToolTipText("Deja la estación elegida y empieza a llamar CQ (pone en marcha la transmisión)");
        cq.addActionListener(e -> llamarCq());
        JButton responder = boton("Responder");
        responder.setToolTipText("Llama a la estación elegida (pone en marcha la transmisión)");
        responder.addActionListener(e -> {
            if (dxIndicativo.isEmpty()) { lblEstado.setText("  Primero elige una estación con doble clic en la lista."); return; }
            activarTx(true);
        });
        JButton libre = boton("Texto libre…");
        libre.setToolTipText("Escribe un mensaje corto (13 letras como mucho) y lo pone en Tx5");
        libre.addActionListener(e -> textoLibre());
        b.add(cq); b.add(responder); b.add(libre);
        p.add(b, g);
        generarMensajes();
        return p;
    }

    private JComponent opciones() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(FONDO2);
        Object[][] casillas = {
                {chkAuto, "DIGITALES_SEC_AUTO", 1, "Cuando la otra estación contesta, se elige solo el siguiente mensaje (como en WSJT-X)"},
                {chkPrimero, "DIGITALES_RESPONDER_PRIMERO", 0, "Al llamar CQ, contesta solo a la primera estación que te responda"},
                {chkMantener, "DIGITALES_MANTENER_TX", 1, "Al elegir una estación, tu frecuencia de transmisión no se mueve (solo la de recepción)"},
                {chkGuardar, "DIGITALES_GUARDAR_LOG", 1, "Al terminar el contacto se guarda solo en el LOG; si no, se pasa al contacto de la ventana principal"}};
        for (Object[] c : casillas) {
            JCheckBox x = (JCheckBox) c[0];
            String clave = (String) c[1];
            x.setSelected(LibroGuardiaUI.ConfigApp.getInt(clave, (Integer) c[2]) == 1);
            x.setFont(f(Font.PLAIN, 13));
            x.setOpaque(false);
            x.setToolTipText((String) c[3]);
            x.setAlignmentX(0);
            x.addActionListener(e -> LibroGuardiaUI.ConfigApp.putInt(clave, x.isSelected() ? 1 : 0));
            p.add(x);
        }
        JPanel tu = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tu.setOpaque(false);
        tu.setAlignmentX(0);
        tu.add(lbl("Mi turno de transmitir:", f(Font.PLAIN, 13), TEXTO));
        cbTurno.setToolTipText("<html>1º = periodos que empiezan en el segundo 00 y 30 (FT8)<br>2º = los que empiezan en el 15 y 45.<br>"
                + "Al elegir una estación se pone solo el contrario al suyo.</html>");
        cbTurno.addActionListener(e -> { if (cbTurno.hasFocus() || cbTurno.isPopupVisible()) accionUsuario(); actualizarPlan(); });
        tu.add(cbTurno);
        p.add(tu);
        p.add(Box.createVerticalStrut(6));
        JPanel fr = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        fr.setOpaque(false);
        fr.setAlignmentX(0);
        fr.add(lbl("RX", f(Font.BOLD, 13), VERDE));
        txtRx.setText(String.valueOf(rxHz));
        fr.add(txtRx);
        fr.add(lbl("Hz", f(Font.PLAIN, 12), TEXTO));
        fr.add(Box.createHorizontalStrut(8));
        fr.add(lbl("TX", f(Font.BOLD, 13), ROJO));
        txtTx.setText(String.valueOf(txHz));
        fr.add(txtTx);
        fr.add(lbl("Hz", f(Font.PLAIN, 12), TEXTO));
        soloCifras(txtRx);
        soloCifras(txtTx);
        ActionListener aplicar = e -> {
            rxHz = limitarHz(txtRx.getText(), rxHz);
            txHz = limitarHz(txtTx.getText(), txHz);
            txtRx.setText(String.valueOf(rxHz));
            txtTx.setText(String.valueOf(txHz));
            guardarFrecuencias();
        };
        txtRx.addActionListener(aplicar);
        txtTx.addActionListener(aplicar);
        txtRx.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { aplicar.actionPerformed(null); } });
        txtTx.addFocusListener(new FocusAdapter() { @Override public void focusLost(FocusEvent e) { aplicar.actionPerformed(null); } });
        p.add(fr);
        JPanel pr = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pr.setOpaque(false);
        pr.setAlignmentX(0);
        pr.add(lbl("Descodificar:", f(Font.PLAIN, 13), TEXTO));
        cbProfundidad.setSelectedIndex(Math.max(0, Math.min(2, LibroGuardiaUI.ConfigApp.getInt("DIGITALES_PROFUNDIDAD", 2) - 1)));
        cbProfundidad.setToolTipText("Rápido gasta poco el ordenador; A fondo saca más señales débiles");
        cbProfundidad.addActionListener(e -> {
            LibroGuardiaUI.ConfigApp.putInt("DIGITALES_PROFUNDIDAD", cbProfundidad.getSelectedIndex() + 1);
            actualizarOpciones();
        });
        pr.add(cbProfundidad);
        p.add(pr);
        JPanel po = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        po.setOpaque(false);
        po.setAlignmentX(0);
        po.add(lbl("Nivel de salida", f(Font.PLAIN, 13), TEXTO));
        sNivel.setOpaque(false);
        sNivel.setToolTipText("<html>Volumen del audio que va a la emisora. Súbelo hasta que dé la potencia que quieres<br>"
                + "sin que se encienda el ALC (si el ALC se mueve, la señal sale sucia).</html>");
        sNivel.setPreferredSize(new Dimension(120, 22));
        JLabel lNivel = lbl(String.format(Locale.GERMANY, "%.0f dB", (sNivel.getValue() - 100) * 0.4), f(Font.PLAIN, 12), TEXTO);
        sNivel.addChangeListener(e -> {
            double db = (sNivel.getValue() - 100) * 0.4;
            emisor.ponerNivelDb(db);
            lNivel.setText(String.format(Locale.GERMANY, "%.0f dB", db));
            if (!sNivel.getValueIsAdjusting()) LibroGuardiaUI.ConfigApp.putInt("DIGITALES_NIVEL_TX", sNivel.getValue());
        });
        po.add(sNivel);
        po.add(lNivel);
        p.add(po);
        return p;
    }

    private JComponent mandos() {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.setBackground(FONDO2);
        btnEscuchar.setToolTipText("Escuchar la emisora por la tarjeta de sonido y descodificar");
        btnEscuchar.addActionListener(e -> escuchar(btnEscuchar.isSelected()));
        btnTx.setToolTipText("<html>Permite transmitir el mensaje marcado (Tx1…Tx6) en tu turno.<br>"
                + "Si lo quitas, termina la transmisión en curso y ya no transmite más. PARAR corta al momento.</html>");
        btnTx.addActionListener(e -> activarTx(btnTx.isSelected()));
        p.add(btnEscuchar); p.add(btnTx);
        btnAfinar.setToolTipText("Transmite un tono fijo en tu frecuencia de TX para ajustar la potencia y el ALC (como mucho 30 s)");
        btnAfinar.addActionListener(e -> afinar(btnAfinar.isSelected()));
        JToggleButton afinar = btnAfinar;
        JButton desc = boton("DESCODIFICAR");
        desc.setToolTipText("Vuelve a descodificar el último periodo (por ejemplo, tras cambiar la profundidad)");
        desc.addActionListener(e -> { if (!motor.repetirUltimo()) lblEstado.setText("  Todavía no hay ningún periodo escuchado."); });
        p.add(afinar); p.add(desc);
        JButton borrar = boton("BORRAR");
        borrar.setToolTipText("Vacía la lista de actividad y la de mi frecuencia");
        borrar.addActionListener(e -> { filas.clear(); modeloActividad.setRowCount(0); filasMiFrec.clear(); pintarMiFrec(); });
        JButton ajustes = boton("AJUSTES…");
        ajustes.addActionListener(e -> abrirAjustes());
        p.add(borrar); p.add(ajustes);
        JPanel todo = new JPanel(new BorderLayout(0, 8));
        todo.setBackground(FONDO2);
        // ¿Quién me oye? (PSK Reporter): lista y mapa de las estaciones que han oído mi indicativo
        JButton oyen = new JButton("¿QUIÉN ME OYE?");
        CuentaBitacora.pintarCrearCuenta(oyen);
        oyen.setFont(f(Font.BOLD, 13));
        oyen.setToolTipText("Qué estaciones te han oído en FT8 / FT4, con mapa (datos de PSK Reporter)");
        oyen.addActionListener(e -> digitales.wspr.VentanaQuienMeOye.abrir(this, miIndicativo(), misDatos()[4], this::elegirPorIndicativo, this::oidosEnBanda));
        JPanel norte = new JPanel(new BorderLayout(0, 6));
        norte.setBackground(FONDO2);
        norte.add(p, BorderLayout.NORTH);
        norte.add(oyen, BorderLayout.SOUTH);
        todo.add(norte, BorderLayout.NORTH);
        JButton parar = new JButton("■  PARAR TX") {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, new Color(205, 40, 40), 0, getHeight(), new Color(140, 20, 20)));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.setColor(new Color(60, 5, 5));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.setColor(Color.WHITE);
                g.setFont(f(Font.BOLD, 20));
                FontMetrics fm = g.getFontMetrics();
                String t = getText();
                g.drawString(t, (getWidth() - fm.stringWidth(t)) / 2, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            }
        };
        parar.setPreferredSize(new Dimension(100, 58));
        parar.setBorderPainted(false);
        parar.setContentAreaFilled(false);
        parar.setFocusable(false);
        parar.setToolTipText("Corta la transmisión al momento y quita ACTIVAR TX (también la tecla Esc, si no se ha quitado en AJUSTES)");
        parar.addActionListener(e -> {
            boolean estaba = emisor.transmitiendo() || txActivo;
            btnAfinar.setSelected(false);
            desactivarTx(estaba ? "Transmisión cortada (PARAR)." : "No se estaba transmitiendo.");
        });
        getRootPane().registerKeyboardAction(e -> { if (prot(PROT_ESC)) parar.doClick(); }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        todo.add(parar, BorderLayout.CENTER);
        lblPie.setFont(f(Font.PLAIN, 11));
        lblPie.setForeground(TEXTO);
        actualizarPie();
        todo.add(lblPie, BorderLayout.SOUTH);
        return todo;
    }

    private final JLabel lblPie = new JLabel();

    /** Texto bajo PARAR: cómo se pasa a transmisión y si está la protección de los 6 minutos. */
    private void actualizarPie() {
        String a = actividad();
        lblPie.setText("<html><span style='color:#8fa6c8'>Transmite por " + textoPtt()
                + (prot(PROT_VIGILANTE) ? " · se corta solo a los 6 minutos sin tocar nada" : " · sin corte a los 6 minutos")
                + (NINGUNO.equals(a) ? "" : " · " + nombreActividad(a)) + ".</span></html>");
    }

    // ------------------------------------------------------------------ escuchar
    private void escuchar(boolean si) {
        if (!si) {
            motor.parar();
            emisor.ponerEsperarDescodificacion(false);
            lblEstado.setText("  Escucha parada.");
            medidor.ponerNivel(0);
            return;
        }
        String entrada = LibroGuardiaUI.ConfigApp.get("DIGITALES_ENTRADA", "");
        if (MotorDigitales.entradas().isEmpty()) {
            btnEscuchar.setSelected(false);
            lblEstado.setText("  No se encuentra ninguna entrada de sonido en este ordenador: conecta el cable de audio de la emisora.");
            return;
        }
        try {
            motor.arrancar(MotorDigitales.tarjeta(entrada, canalEntrada()));
            emisor.ponerEsperarDescodificacion(true);
            lblEstado.setText("  Escuchando " + (entrada.isEmpty() ? "la entrada de sonido por defecto" : entrada)
                    + " · los contactos de FT8/FT4 hechos con WSJT-X siguen llegando solos al LOG");
        } catch (Exception ex) {
            btnEscuchar.setSelected(false);
            lblEstado.setText("  " + ex.getMessage() + ". Elige la entrada de la emisora en AJUSTES.");
        }
    }

    /** RTTY: la parte de abajo cambia a la de RTTY (y al volver a FT8/FT4, otra vez a la de siempre). */
    private void ponerRtty(boolean si) {
        if (si == enRtty) return;
        if (si) {
            if (txActivo || emisor.transmitiendo()) desactivarTx("Se ha cambiado a RTTY: transmisión de FT8/FT4 parada.");
            if (rtty == null) { rtty = new VentanaRtty(app, this); tarjetas.add(rtty, "RTTY"); }
            ((CardLayout) tarjetas.getLayout()).show(tarjetas, "RTTY");
            enRtty = true;
            rtty.activar();
            lblEstado.setText("  Modo RTTY: elige la banda arriba para ir a la zona de RTTY.");
        } else {
            if (rtty != null) rtty.desactivar();
            ((CardLayout) tarjetas.getLayout()).show(tarjetas, "FT");
            enRtty = false;
        }
        llenarBandas();
    }

    private void cambiarModo(String m) {
        ponerRtty(false);
        if (txActivo || emisor.transmitiendo()) desactivarTx("Se ha cambiado de modo: transmisión parada.");
        motor.ponerModo("FT4".equals(m) ? GeneradorFtx.Modo.FT4 : GeneradorFtx.Modo.FT8);
        LibroGuardiaUI.ConfigApp.put("DIGITALES_MODO", m);
        llenarBandas();
        generarMensajes();
        lblEstado.setText("  Modo " + m + ": elige la banda arriba para ir a su frecuencia de llamada.");
    }

    private void llenarBandas() {
        String[][] tabla = enRtty ? FREC_RTTY : motor.modo() == GeneradorFtx.Modo.FT8 ? FREC_FT8 : FREC_FT4;
        for (Map.Entry<String, JToggleButton> e : botonesBanda.entrySet()) {
            String hz = null;
            for (String[] b : tabla) if (b[0].equals(e.getKey())) hz = b[1];
            e.getValue().setEnabled(hz != null);
            Object nm = enRtty ? "RTTY" : motor.modo();
            e.getValue().setToolTipText(hz == null ? e.getKey() + ": no hay frecuencia de " + nm
                    : e.getKey() + ": ir a " + formatoMhz(Long.parseLong(hz)) + " MHz (" + nm + ")"
                    + ("CB".equals(e.getKey()) ? " · en CB solo se escucha" : ""));
        }
        ponerBandaEnLista(frecuenciaEmisora());
    }

    private boolean comprobandoHora = false;

    /**
     * Pinchar en el reloj: se pregunta la hora a internet y, si va mal, se ofrece poner en hora el ordenador.
     * automatico = al abrir DIGITALES: no pregunta nada; si hay diferencia, la corrige (Windows pide el permiso).
     */
    private void comprobarHora(boolean automatico) {
        if (comprobandoHora) return;
        comprobandoHora = true;
        lblEstado.setText("  Comprobando la hora con " + HoraNtp.SERVIDORES[0] + "…");
        Thread t = new Thread(() -> {
            HoraNtp.Medida m;
            try { m = HoraNtp.medir(); }
            catch (Exception ex) {
                SwingUtilities.invokeLater(() -> { comprobandoHora = false; lblEstado.setText("  No se ha podido comprobar la hora: " + ex.getMessage()); });
                return;
            }
            SwingUtilities.invokeLater(() -> { if (automatico) horaAutomatica(m); else resultadoHora(m, false); });
        }, "ComprobarHora");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Al abrir DIGITALES: si el reloj está en hora, solo se dice en la línea de estado; si está desincronizado,
     * sale el aviso con cuánto va desfasado y se pregunta si se quiere sincronizar (igual que al pinchar en el reloj).
     */
    private void horaAutomatica(HoraNtp.Medida m) {
        double s = -m.desfaseMs() / 1000.0;
        if (Math.abs(s) < 0.3) {
            comprobandoHora = false;
            lblEstado.setText("  Reloj comprobado con " + m.servidor() + ": el ordenador está en hora.");
            return;
        }
        resultadoHora(m, false);
    }

    private void resultadoHora(HoraNtp.Medida m, boolean despuesDeCorregir) {
        comprobandoHora = false;
        double s = -m.desfaseMs() / 1000.0;   // positivo = el ordenador va adelantado
        String como = Math.abs(s) < 0.3 ? "está en hora" : String.format(Locale.GERMANY, "va %s %.2f s", s > 0 ? "ADELANTADO" : "ATRASADO", Math.abs(s));
        String txt = "Según " + m.servidor() + ", el reloj del ordenador " + como + ".";
        lblEstado.setText("  " + txt);
        if (despuesDeCorregir) {
            JOptionPane.showMessageDialog(this, "Hecho. " + txt, "Hora del ordenador", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (Math.abs(s) < 0.3) {
            JOptionPane.showMessageDialog(this, txt + "\nNo hace falta hacer nada.", "Hora del ordenador", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String cuanto = String.format(Locale.GERMANY, "%.2f segundos %s", Math.abs(s), s > 0 ? "ADELANTADO" : "ATRASADO");
        int r = JOptionPane.showConfirmDialog(this,
                "El reloj del ordenador está DESINCRONIZADO: va " + cuanto + "\n(comprobado con " + m.servidor() + ").\n\n"
                        + "Para FT8 y FT4 el reloj tiene que ir en hora (menos de 0,3 segundos de diferencia).\n\n"
                        + "¿Quieres sincronizar el reloj ahora?\n\n"
                        + "Aviso: para poner en hora el reloj de Windows se abrirá PowerShell (un programa de Windows)\n"
                        + "y Windows te pedirá permiso de administrador. Es normal: acepta para que se ajuste la hora.",
                "Reloj desincronizado", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;
        comprobandoHora = true;
        lblEstado.setText("  Poniendo en hora el ordenador (acepta el permiso de Windows)…");
        long ajuste = m.desfaseMs();
        Thread t = new Thread(() -> {
            try {
                HoraNtp.corregirWindows(ajuste);
                HoraNtp.Medida otra = HoraNtp.medir();
                SwingUtilities.invokeLater(() -> resultadoHora(otra, true));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> { comprobandoHora = false; lblEstado.setText("  No se ha puesto en hora: " + ex.getMessage()); });
            }
        }, "PonerHora");
        t.setDaemon(true);
        t.start();
    }

    /** Se enciende el botón de la banda en la que está la emisora (ninguno si no está en una de ellas). */
    private void ponerBandaEnLista(long hz) {
        String banda = hz > 0 ? app.bandaPorFrecuencia(hz) : null;
        for (Map.Entry<String, JToggleButton> e : botonesBanda.entrySet()) {
            boolean es = banda != null && e.getKey().equalsIgnoreCase(banda);
            if (e.getValue().isSelected() != es) e.getValue().setSelected(es);
        }
    }

    /** Cifras de 7 segmentos (las mismas que el display de la ventana principal); ':' y '.' como puntos. */
    private static final class Siete extends JLabel {
        private final int ancho, alto;
        private final Color color;

        Siete(String t, int ancho, int alto, Color color) {
            super(t);
            this.ancho = ancho;
            this.alto = alto;
            this.color = color;
            setOpaque(false);
        }

        private int anchoDe(char c) { return c == ':' ? ancho / 2 : ancho; }

        @Override public Dimension getPreferredSize() {
            int w = 4;
            for (char c : getText().toCharArray()) w += anchoDe(c);
            return new Dimension(w, alto + 8);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = 2, y = (getHeight() - alto) / 2;
            for (char c : getText().toCharArray()) {
                if (c == ':') {
                    double tam = Math.max(3, ancho * 0.16);
                    g.setColor(color);
                    double cx = x + ancho / 4.0 - tam / 2;
                    g.fill(new java.awt.geom.Rectangle2D.Double(cx, y + alto * 0.28, tam, tam));
                    g.fill(new java.awt.geom.Rectangle2D.Double(cx, y + alto * 0.68, tam, tam));
                } else {
                    ControlEmisora.pintarSieteSegmentos(g, String.valueOf(c), x, y, ancho, alto, color);
                }
                x += anchoDe(c);
            }
            g.dispose();
        }
    }

    private static String formatoMhz(long hz) { return String.format(Locale.US, "%.3f", hz / 1e6); }

    /** Botón de banda: a la frecuencia de llamada de FT8/FT4 de esa banda. */
    private void irABanda(String banda) {
        String[][] tabla = enRtty ? FREC_RTTY : motor.modo() == GeneradorFtx.Modo.FT8 ? FREC_FT8 : FREC_FT4;
        for (String[] b : tabla) if (b[0].equals(banda)) { irAFrecuencia(Long.parseLong(b[1])); break; }
        ponerBandaEnLista(frecuenciaEmisora());
    }

    /** Pone el contacto (y la emisora, con CAT) en una frecuencia de FT8/FT4: dial en USB. */
    private void irAFrecuencia(long hz) {
        ControlEmisora cat = app.cat;
        String banda = app.bandaPorFrecuencia(hz);
        if (banda != null && ControlEmisora.comboContiene(app.cbBanda, banda)) app.cbBanda.setSelectedItem(banda);
        String f = String.format(Locale.GERMANY, "%,d", hz);
        cat.frecuenciaPorPrograma = true;
        try { app.txtFreqTx.setText(f); app.txtFreqRx.setText(f); } finally { cat.frecuenciaPorPrograma = false; }
        String m = motor.modo().name();
        if (ControlEmisora.comboContiene(app.cbModo, m)) app.cbModo.setSelectedItem(m);
        if (LibroGuardiaUI.catConectado) {
            int codigo = codigoModoTx(hz);
            cat.enviarOrden("DIGITALES " + f, rig -> {
                ControlEmisora.ponerFrecuenciaEmisora(rig, (int) hz);
                if (codigo != 0) ponerModoEmisora(rig, codigo);
            });
        }
        cat.refrescarFrontal();
        String nm = "DATOS".equals(modoTx) ? " (modo DATOS)" : "USB".equals(modoTx) ? " USB" : "";
        lblEstado.setText("  " + motor.modo() + " en " + formatoMhz(hz) + " MHz" + nm + (LibroGuardiaUI.catConectado ? " (la emisora también)" : " (sin CAT: pon la emisora a mano)"));
    }

    /** Los campos de Hz solo admiten números (regla de Bitácora 26). */
    private static void soloCifras(JTextField t) {
        ((javax.swing.text.AbstractDocument) t.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int off, String txt, javax.swing.text.AttributeSet a) throws javax.swing.text.BadLocationException {
                replace(fb, off, 0, txt, a);
            }
            @Override public void replace(FilterBypass fb, int off, int len, String txt, javax.swing.text.AttributeSet a) throws javax.swing.text.BadLocationException {
                String limpio = txt == null ? "" : txt.replaceAll("[^0-9]", "");
                if (fb.getDocument().getLength() - len + limpio.length() <= 4) super.replace(fb, off, len, limpio, a);
            }
        });
    }

    private static int limitarHz(String t, int antes) {
        try { int v = Integer.parseInt(t.trim()); return Math.max(100, Math.min(3000, v)); } catch (Exception e) { return antes; }
    }

    private void guardarFrecuencias() {
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_RX_HZ", rxHz);
        LibroGuardiaUI.ConfigApp.putInt("DIGITALES_TX_HZ", txHz);
        actualizarOpciones();
        actualizarTituloMiFrec();
        cascada.repaint();
    }

    private void actualizarTituloMiFrec() {
        if (bordeMiFrec != null) { bordeMiFrec.setTitle(" MI FRECUENCIA (" + rxHz + " Hz) "); repaint(); }
    }

    private void actualizarOpciones() {
        DescodificadorFt8.Opciones o = new DescodificadorFt8.Opciones();
        o.profundidad = cbProfundidad.getSelectedIndex() + 1;
        o.frecRx = rxHz;
        o.frecTx = txHz;
        o.miIndicativo = miIndicativo();
        o.suIndicativo = dxIndicativo;
        o.fMin = 200;
        o.fMax = 3000;
        motor.ponerOpciones(o);
    }

    // ------------------------------------------------------------------ yo (operador)
    private String[] misDatos() {
        String op = LibroGuardiaUI.operadorActual == null ? "" : LibroGuardiaUI.operadorActual;
        try { return app.leerDatosOperador(op); } catch (Exception e) { return new String[]{op, "", "", "", ""}; }
    }

    String miIndicativo() { String c = misDatos()[0]; return c == null ? "" : c.trim().toUpperCase(); }

    private String miGrid4() {
        String g = misDatos()[4];
        g = g == null ? "" : g.trim().toUpperCase();
        return g.length() >= 4 ? g.substring(0, 4) : g;
    }

    private boolean mencionaMiIndicativo(String msg) {
        String mio = miIndicativo();
        if (mio.length() < 3) return false;
        for (String w : msg.split(" ")) {
            String x = w.replace("<", "").replace(">", "");
            if (x.equals(mio)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ resultados
    private void mostrarResultados(long inicio, GeneradorFtx.Modo m, List<DescodificadorFt8.Resultado> r, long ms) {
        cargarLogSiHaceFalta();
        String hora = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(inicio));
        String banda = bandaActual();
        // las de más arriba en la lista: las de mi frecuencia y las que me llaman primero
        List<DescodificadorFt8.Resultado> orden = new ArrayList<>(r);
        orden.sort(Comparator.comparingDouble(DescodificadorFt8.Resultado::frecuencia));
        dtsUltimo.clear();
        int nuevos = 0;
        boolean meLlaman = false;
        List<Cascada.Etiqueta> etiquetas = new ArrayList<>();
        for (DescodificadorFt8.Resultado x : orden) {
            String quien = emisor(x.mensaje());
            String[] pais = quien.isEmpty() ? null : LibroGuardiaUI.paisDeIndicativo(quien, banda);
            String estado = estado(x.mensaje(), quien, pais, banda);
            Fila fl = new Fila(inicio, x, pais == null ? "" : pais[1], pais == null ? null : pais[0], estado, banda);
            filas.add(fl);
            modeloActividad.addRow(new Object[]{hora, x.snr(), String.format(Locale.US, "%.1f", x.dt()), (int) Math.round(x.frecuencia()),
                    x.mensaje(), fl.pais, estado});
            dtsUltimo.add(new double[]{x.dt()});
            boolean mia = Math.abs(x.frecuencia() - rxHz) <= 10 || mencionaMiIndicativo(x.mensaje());
            if (mia) { filasMiFrec.add(fl); if (mencionaMiIndicativo(x.mensaje())) meLlaman = true; }
            if (!quien.isEmpty() && !quien.equals(miIndicativo()) && dirigidoAMi(x.mensaje())) {
                Llamada ll = llamadas.computeIfAbsent(quien, k -> new Llamada(fl));
                ll.fl = fl;
                llamadas.remove(quien); llamadas.put(quien, ll);   // la última, abajo
            }
            etiquetas.add(new Cascada.Etiqueta(x.frecuencia(), x.mensaje().startsWith("CQ") ? "CQ " + quien : quien));
            nuevos++;
            if (!dxIndicativo.isEmpty() && x.mensaje().contains(dxIndicativo)) actualizarPasos(x.mensaje());
        }
        // no dejar que la lista crezca sin fin
        while (filas.size() > 600) { filas.remove(0); modeloActividad.removeRow(0); }
        while (filasMiFrec.size() > 40) filasMiFrec.remove(0);
        cascada.ponerEtiquetas(etiquetas);
        pintarMiFrec();
        pintarMeLlaman();
        // bajar al final de la lista
        if (tablaActividad.getRowCount() > 0 && (tablaActividad.getRowSorter() == null || tablaActividad.getRowSorter().getSortKeys().isEmpty()))
            tablaActividad.scrollRectToVisible(tablaActividad.getCellRect(tablaActividad.getRowCount() - 1, 0, true));
        // reloj del ordenador: si las señales llegan con DT parecido y grande, el reloj va mal
        relojSegunDt();
        secuencia(inicio, r);
        emisor.descodificado(inicio);
        if (meLlaman && LibroGuardiaUI.ConfigApp.getInt("DIGITALES_SONIDO_LLAMADA", 1) == 1) Toolkit.getDefaultToolkit().beep();
        if (!emisor.transmitiendo()) lblEstado.setText(String.format("  %s %s · %d señales · descodificado en %.1f s · %s", m, hora, nuevos, ms / 1000.0,
                motor.enMarcha() ? (txActivo ? "TX activo" : "escuchando") : "parado") + (avisoSecuencia == null ? "" : " · " + avisoSecuencia));
    }

    private void relojSegunDt() {
        relojMediana = null;
        if (dtsUltimo.size() >= 5) {
            double[] d0 = dtsUltimo.stream().mapToDouble(a -> a[0]).sorted().toArray();
            relojMediana = d0[d0.length / 2];
        }
        if (dtsUltimo.size() < 3) { lblReloj.setText("<html><span style='color:#8fa6c8'>reloj: esperando señales</span></html>"); return; }
        double[] d = dtsUltimo.stream().mapToDouble(a -> a[0]).sorted().toArray();
        double med = d[d.length / 2];
        String txt = String.format(Locale.GERMANY, "%+.1f s", med);
        if (Math.abs(med) <= 0.5) lblReloj.setText("<html><span style='color:#50e650'>reloj bien " + txt + "</span></html>");
        else lblReloj.setText("<html><span style='color:#ff8040'>reloj desfasado " + txt + ": ponlo en hora</span></html>");
    }

    /** Quién envía el mensaje (el segundo indicativo; en "CQ ..." el que llama). */
    static String emisor(String msg) {
        String[] w = msg.split(" ");
        if (w.length == 0) return "";
        if (w[0].equals("CQ") || w[0].equals("QRZ")) {
            // CQ [DX|POTA|nnn] INDICATIVO [GRID]
            for (int i = 1; i < w.length; i++) {
                String x = w[i].replace("<", "").replace(">", "");
                if (pareceIndicativo(x)) return x;
            }
            return "";
        }
        if (w.length == 5 && w[1].equals("RR73;")) {   // FOX: "K1ABC RR73; W9XYZ <KH7Z> -12" lo manda KH7Z
            String x = w[3].replace("<", "").replace(">", "");
            return pareceIndicativo(x) ? x : "";
        }
        if (w.length >= 2) {
            String x = w[1].replace("<", "").replace(">", "");
            if (w[0].endsWith(";") && w.length >= 3) x = w[2];   // "TU; A B ..." o "A RR73; B <C> -10"
            return pareceIndicativo(x) ? x : "";
        }
        return "";
    }

    static boolean pareceIndicativo(String x) {
        return x.length() >= 3 && x.matches("[A-Z0-9/]+") && x.matches(".*[0-9].*") && x.matches(".*[A-Z].*") && !x.matches("[A-R]{2}[0-9]{2}");
    }

    /** Grid (4) que aparece en el mensaje, o "". */
    static String gridDe(String msg) {
        String[] w = msg.split(" ");
        String u = w[w.length - 1];
        if (u.matches("[A-R]{2}[0-9]{2}") && !u.equals("RR73")) return u;
        return "";
    }

    private String estado(String msg, String quien, String[] pais, String banda) {
        if (!quien.isEmpty() && quien.equals(miIndicativo())) return "TU SEÑAL";   // tu propia transmisión, oída por el audio
        if (mencionaMiIndicativo(msg)) return "TE LLAMA";
        if (quien.isEmpty()) return "";
        boolean avisoPais = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_AVISO_PAIS", 1) == 1;
        boolean avisoGrid = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_AVISO_LOCATOR", 1) == 1;
        boolean avisoTrab = LibroGuardiaUI.ConfigApp.getInt("DIGITALES_AVISO_TRABAJADO", 1) == 1;
        if (avisoPais && pais != null && !paisesBanda.contains(pais[0] + "|" + banda)) return "PAÍS NUEVO";
        String g = gridDe(msg);
        if (avisoGrid && !g.isEmpty() && !gridsBanda.contains(g + "|" + banda)) return "LOCATOR NUEVO";
        if (avisoTrab && trabajadosBanda.contains(quien + "|" + banda)) return "YA TRABAJADO";
        if (avisoTrab && trabajados.contains(quien)) return "OTRA BANDA";
        return "";
    }

    /** Código de modo de OmniRig según «Modo al transmitir» (0 = no tocar el modo). */
    private int codigoModoTx(long hz) {
        return switch (modoTx) {
            case "NINGUNO" -> 0;
            case "DATOS" -> PM_DIG_U;
            default -> ControlEmisora.codigoModoOmniRig("USB", hz);
        };
    }

    /**
     * Pone el modo en la emisora. Para «Datos (DATA-U)» en los Yaesu modernos (FT-891, FT-991, FT-710, FTDX10, FTDX101)
     * se manda la orden directa MD0C; (DATA-USB), porque OmniRig los pone en RTTY con su modo «datos».
     */
    static void ponerModoEmisora(com.jacob.com.Dispatch rig, int codigo) {
        if (codigo == PM_DIG_U) {
            String tipo = "";
            try { tipo = com.jacob.com.Dispatch.get(rig, "RigType").getString().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", ""); } catch (Exception ignored) { }
            if (tipo.contains("FT891") || tipo.contains("FT991") || tipo.contains("FT710") || tipo.contains("FTDX10") || tipo.contains("FTDX101")) {
                com.jacob.com.Dispatch.call(rig, "SendCustomCommand", new com.jacob.com.Variant("MD0C;"), new com.jacob.com.Variant(0), new com.jacob.com.Variant(""));
                return;
            }
        }
        com.jacob.com.Dispatch.put(rig, "Mode", new com.jacob.com.Variant(codigo));
    }

    /** Antes de transmitir: modo de la emisora (si se ha pedido) y, con PTT por COM, abrir ya el puerto. */
    private void prepararTransmision() {
        long hz = frecuenciaEmisora();
        int codigo = codigoModoTx(hz);
        if (LibroGuardiaUI.catConectado && codigo != 0)
            app.cat.enviarOrden("DIGITALES modo", rig -> ponerModoEmisora(rig, codigo));
        if ("RTS".equals(metodoPtt) || "DTR".equals(metodoPtt)) {
            Thread t = new Thread(() -> {
                try { serie(metodoPtt, puertoPtt); }
                catch (Exception e) { SwingUtilities.invokeLater(() -> desactivarTx("PTT: " + e.getMessage())); }
            }, "AbrirPttSerie");
            t.setDaemon(true);
            t.start();
        }
    }

    /** El PTT, según el ajuste (lo llama el emisor desde su hilo). */
    void ponerPtt(boolean tx) throws Exception { ponerPttCon(metodoPtt, puertoPtt, tx); }

    private void ponerPttCon(String metodo, String puerto, boolean tx) throws Exception {
        switch (metodo) {
            case "VOX" -> { }   // la emisora (o el interfaz) pasa sola a transmisión al llegarle el audio
            case "RTS", "DTR" -> serie(metodo, puerto).poner(tx);
            default -> app.cat.pttDigitales(tx);
        }
    }

    private synchronized PttSerie serie(String metodo, String puerto) throws Exception {
        boolean rts = "RTS".equals(metodo);
        if (serie == null || !serie.es(puerto, rts)) {
            if (serie != null) serie.close();
            serie = null;
            serie = new PttSerie(puerto, rts);
        }
        return serie;
    }

    private synchronized void cerrarSerie() {
        if (serie != null) { serie.close(); serie = null; }
    }

    /**
     * Frecuencia de la emisora en Hz (-1 si no se sabe): con CAT, la que da la emisora; si no, la del display
     * de la ventana principal.
     */
    long frecuenciaEmisora() {
        if (LibroGuardiaUI.catConectado && app.cat != null) {
            long c = app.cat.ultimaFreqCatHz;
            if (c >= 100_000 && c <= 470_000_000) return c;
        }
        String t = app.txtFreqTx.getText();
        long hz = ControlEmisora.herciosDeTexto(t);
        if (hz > 0) return hz;
        String d = t == null ? "" : t.replaceAll("[^0-9]", "");   // por si viene con otro separador
        if (d.length() >= 6 && d.length() <= 9) {
            long v = Long.parseLong(d);
            if (v >= 100_000 && v <= 470_000_000) return v;
        }
        return -1;
    }

    private String bandaActual() {
        try {
            long hz = frecuenciaEmisora();
            String b = hz > 0 ? app.bandaPorFrecuencia(hz) : null;
            if (b != null && !b.isEmpty()) return b;
        } catch (Exception ignored) { }
        Object b = app.cbBanda.getSelectedItem();
        return b == null ? "" : b.toString();
    }

    /** Lee del LOG qué indicativos, países y locators se han trabajado (cada 5 minutos como mucho). */
    private void cargarLogSiHaceFalta() {
        if (System.currentTimeMillis() - ultimaCargaLog < 5 * 60_000) return;
        ultimaCargaLog = System.currentTimeMillis();
        trabajados.clear(); trabajadosBanda.clear(); paisesBanda.clear(); gridsBanda.clear(); paises.clear(); grids.clear(); datosLog.clear();
        hecho.clear();
        try (Connection c = LibroGuardiaUI.DatabaseManager.conectar(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT indicativo, banda, grid, nombre, qth, modo FROM qsos ORDER BY id")) {
            while (r.next()) {
                String ind = r.getString(1) == null ? "" : r.getString(1).trim().toUpperCase();
                String banda = r.getString(2) == null ? "" : r.getString(2).trim();
                String grid = r.getString(3) == null ? "" : r.getString(3).trim().toUpperCase();
                if (ind.isEmpty()) continue;
                trabajados.add(ind);
                trabajadosBanda.add(ind + "|" + banda);
                String[] p = LibroGuardiaUI.paisDeIndicativo(ind, banda);
                if (p != null) { paisesBanda.add(p[0] + "|" + banda); paises.add(p[0]); }
                if (grid.length() >= 4) { gridsBanda.add(grid.substring(0, 4) + "|" + banda); grids.add(grid.substring(0, 4)); }
                String modo = r.getString(6) == null ? "" : r.getString(6).trim().toUpperCase();
                apuntar("CALL:" + ind, banda, modo);
                if (grid.length() >= 4) { apuntar("GRID:" + grid.substring(0, 4), banda, modo); apuntar("CAMPO:" + grid.substring(0, 2), banda, modo); }
                if (p != null) apuntar("ISO:" + p[0], banda, modo);
                if (!"CB".equalsIgnoreCase(banda)) {
                    DatosDigitales.Entidad e = DatosDigitales.buscar(ind, ColoresDigitales.wae);
                    if (e != null) {
                        apuntar("DXCC:" + e.prefijo(), banda, modo);
                        apuntar("CONT:" + e.continente(), banda, modo);
                        apuntar("CQZ:" + e.zonaCq(), banda, modo);
                        apuntar("ITU:" + e.zonaItu(), banda, modo);
                    }
                }
                String nombre = r.getString(4) == null ? "" : r.getString(4).trim();
                String qth = r.getString(5) == null ? "" : r.getString(5).trim();
                if (!nombre.isEmpty() || !qth.isEmpty() || !grid.isEmpty()) datosLog.put(ind, new String[]{nombre, qth, grid});
            }
        } catch (Exception e) {
            lblEstado.setText("  No se ha podido leer el LOG: " + e.getMessage());
        }
    }

    /** ME LLAMAN: rojo = aún no le he contestado, ámbar = le he contestado pero no está terminado, sin color = hecho. */
    private void pintarMeLlaman() {
        panelMeLlaman.removeAll();
        while (llamadas.size() > 40) llamadas.remove(llamadas.keySet().iterator().next());
        if (llamadas.isEmpty()) {
            JLabel v = lbl("Nadie te ha llamado todavía.", f(Font.PLAIN, 12), GRIS);
            v.setAlignmentX(0);
            panelMeLlaman.add(v);
        }
        for (Map.Entry<String, Llamada> en : llamadas.entrySet()) {
            Llamada ll = en.getValue();
            Fila fl = ll.fl;
            Color fondo = ll.estado == 0 ? new Color(232, 96, 96) : ll.estado == 1 ? AMBAR : CAMPO;
            Color letra = ll.estado == 2 ? TEXTO : new Color(25, 25, 25);
            JPanel fila = new JPanel(new BorderLayout(8, 0));
            fila.setBackground(fondo);
            fila.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            String h = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(fl.inicio));
            fila.add(lbl(h + String.format(" %3d", fl.r.snr()), MONO, letra), BorderLayout.WEST);
            fila.add(lbl(fl.r.mensaje(), MONO_N, letra));
            fila.add(lbl(ll.estado == 2 ? "HECHO" : ll.estado == 1 ? "A MEDIAS" : "TE LLAMA", f(Font.BOLD, 11), letra), BorderLayout.EAST);
            fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            fila.setAlignmentX(0);
            fila.setToolTipText("Doble clic: elegir · botón derecho: más opciones");
            String call = en.getKey();
            fila.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) elegirEstacion(fl);
                }
                @Override public void mousePressed(MouseEvent e) { if (e.isPopupTrigger()) menu(e); }
                @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) menu(e); }
                private void menu(MouseEvent e) {
                    JPopupMenu m = new JPopupMenu();
                    JMenuItem q = new JMenuItem("Ver " + call + " en QRZ.com");
                    q.addActionListener(a -> verEnQrz(call));
                    JMenuItem sel = new JMenuItem("Seleccionar contacto");
                    sel.addActionListener(a -> elegirEstacion(fl));
                    JMenuItem resp = new JMenuItem("Responder");
                    resp.addActionListener(a -> { elegirEstacion(fl); activarTx(true); });
                    m.add(q); m.add(sel); m.add(resp);
                    m.show(e.getComponent(), e.getX(), e.getY());
                }
            });
            panelMeLlaman.add(fila);
            panelMeLlaman.add(Box.createVerticalStrut(2));
        }
        panelMeLlaman.revalidate();
        panelMeLlaman.repaint();
        SwingUtilities.invokeLater(() -> panelMeLlaman.scrollRectToVisible(new Rectangle(0, panelMeLlaman.getHeight() - 1, 1, 1)));
    }

    /** Indicativos que se han oído transmitir en la banda (para «¿Quién me oye?»). */
    private java.util.Set<String> oidosEnBanda() {
        java.util.Set<String> r = new java.util.HashSet<>();
        for (Fila fl : filas) { String q = emisor(fl.r.mensaje()); if (!q.isEmpty()) r.add(q.toUpperCase()); }
        return r;
    }

    /** Desde «¿Quién me oye?»: elige (y si se pide, responde a) la última vez que se oyó a esa estación en la banda. */
    private boolean elegirPorIndicativo(String call, boolean responder) {
        for (int i = filas.size() - 1; i >= 0; i--) {
            Fila fl = filas.get(i);
            if (call.equalsIgnoreCase(emisor(fl.r.mensaje()))) {
                elegirEstacion(fl);
                if (responder) activarTx(true);
                toFront();
                return true;
            }
        }
        return false;
    }

    private void verEnQrz(String call) {
        if (call == null || call.isBlank()) return;
        try { Desktop.getDesktop().browse(new java.net.URI("https://www.qrz.com/db/" + java.net.URLEncoder.encode(call.trim(), java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception ex) { lblEstado.setText("  No se ha podido abrir QRZ.com: " + ex.getMessage()); }
    }

    private void pintarMiFrec() {
        panelMiFrec.removeAll();
        for (Fila fl : filasMiFrec) {
            JPanel fila = new JPanel(new BorderLayout(8, 0));
            boolean yo = "TX".equals(fl.estado);
            ColoresDigitales.Estilo tipo = tipoColor(fl);
            fila.setBackground(tipo.fondo());
            fila.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            String h = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(fl.inicio));
            fila.add(lbl(h + (yo ? "  TX" : String.format(" %3d", fl.r.snr())), MONO, tipo.letra()), BorderLayout.WEST);
            fila.add(lbl(fl.r.mensaje(), MONO_N, tipo.letra()));
            fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            fila.setAlignmentX(0);
            panelMiFrec.add(fila);
            panelMiFrec.add(Box.createVerticalStrut(2));
        }
        panelMiFrec.revalidate();
        panelMiFrec.repaint();
        SwingUtilities.invokeLater(() -> panelMiFrec.scrollRectToVisible(new Rectangle(0, panelMiFrec.getHeight() - 1, 1, 1)));
    }

    /**
     * Color que le toca a una línea (Ajustes → Colores, como en WSJT-X). Los «nuevo...» (continente, zonas, país,
     * locator, indicativo) solo se miran en los CQ, y en los 73 / RR73 si está marcada esa opción.
     */
    private ColoresDigitales.Estilo tipoColor(Fila fl) {
        String msg = fl.r.mensaje();
        boolean tx = "TX".equals(fl.estado);
        String quien = tx ? "" : emisor(msg);
        Set<String> c = new HashSet<>();
        if (tx) c.add(ColoresDigitales.TX);
        if (!tx && mencionaMiIndicativo(msg) && !quien.equals(miIndicativo())) c.add(ColoresDigitales.MIO);
        boolean cq = msg.startsWith("CQ ");
        if (cq) c.add(ColoresDigitales.CQ);
        String[] w = msg.split(" ");
        String ult = w[w.length - 1];
        boolean es73 = ult.equals("73") || ult.equals("RR73");
        if (!tx && !quien.isEmpty() && !quien.equals(miIndicativo())) {
            if (DatosDigitales.usuarioLotw(quien, ColoresDigitales.lotwDias)) c.add(ColoresDigitales.LOTW);
            if (cq || (ColoresDigitales.tambien73 && es73)) {
                boolean cb = "CB".equalsIgnoreCase(fl.banda);
                String b = fl.banda;
                nuevo(c, "CALL:" + quien, b, ColoresDigitales.CALL, ColoresDigitales.CALL_BANDA);
                String g = gridDe(msg);
                if (g.isEmpty() && cq) g = DatosDigitales.gridCall3(quien).length() >= 4 ? DatosDigitales.gridCall3(quien).substring(0, 4) : "";
                if (!g.isEmpty()) {
                    if (ColoresDigitales.soloCampos) nuevo(c, "CAMPO:" + g.substring(0, 2), b, ColoresDigitales.GRID, ColoresDigitales.GRID_BANDA);
                    else nuevo(c, "GRID:" + g, b, ColoresDigitales.GRID, ColoresDigitales.GRID_BANDA);
                }
                if (!cb) {
                    DatosDigitales.Entidad e = DatosDigitales.buscar(quien, ColoresDigitales.wae);
                    if (e != null) {
                        nuevo(c, "DXCC:" + e.prefijo(), b, ColoresDigitales.DXCC, ColoresDigitales.DXCC_BANDA);
                        nuevo(c, "CONT:" + e.continente(), b, ColoresDigitales.CONT, ColoresDigitales.CONT_BANDA);
                        nuevo(c, "CQZ:" + e.zonaCq(), b, ColoresDigitales.CQZ, ColoresDigitales.CQZ_BANDA);
                        nuevo(c, "ITU:" + e.zonaItu(), b, ColoresDigitales.ITU, ColoresDigitales.ITU_BANDA);
                    } else if (fl.iso != null) {   // sin cty.dat: el país sale de la lista de prefijos de Bitácora 26
                        nuevo(c, "ISO:" + fl.iso, b, ColoresDigitales.DXCC, ColoresDigitales.DXCC_BANDA);
                    }
                }
            }
        }
        return ColoresDigitales.estilo(c, quien);
    }

    /** Si «clave» no se ha trabajado nunca → tipo «nuevo»; si no se ha trabajado en esta banda → «nuevo en banda». */
    private void nuevo(Set<String> c, String clave, String banda, String tipoNuevo, String tipoBanda) {
        String m = ColoresDigitales.porModo ? "#" + motor.modo().name() : "";
        if (!hecho.contains(clave + m)) c.add(tipoNuevo);
        else if (!hecho.contains(clave + "|" + banda + m)) c.add(tipoBanda);
    }

    /** Apunta en «hecho» lo trabajado (con y sin banda, con y sin modo). */
    private void apuntar(String clave, String banda, String modo) {
        hecho.add(clave);
        hecho.add(clave + "|" + banda);
        hecho.add(clave + "#" + modo);
        hecho.add(clave + "|" + banda + "#" + modo);
    }

    // ------------------------------------------------------------------ elegir estación
    private void elegirEstacion(Fila fl) {
        String quien = emisor(fl.r.mensaje());
        if (quien.isEmpty()) { lblEstado.setText("  En ese mensaje no hay un indicativo al que llamar."); return; }
        if (quien.equals(miIndicativo())) { lblEstado.setText("  Ese mensaje es tuyo."); return; }
        accionUsuario();
        if (!quien.equals(dxIndicativo)) nuevoContacto();
        dxIndicativo = quien;
        String g = gridDe(fl.r.mensaje());
        String[] dl = datosLog.get(quien);
        dxGrid = !g.isEmpty() ? g : (dl != null && dl[2].length() >= 4 ? dl[2] : DatosDigitales.gridCall3(quien));
        dxSnr = fl.r.snr();
        dxFrecuencia = (int) Math.round(fl.r.frecuencia());
        rxHz = dxFrecuencia;
        txtRx.setText(String.valueOf(rxHz));
        if (!chkMantener.isSelected()) { txHz = Math.max(100, Math.min(2950, dxFrecuencia)); txtTx.setText(String.valueOf(txHz)); }
        guardarFrecuencias();
        // se transmite en el turno contrario al suyo
        cbTurno.setSelectedIndex(esPrimerTurno(fl.inicio) ? 1 : 0);
        cIndicativo.setText(quien);
        String nombre = dl != null ? (dl[0] + (dl[1].isEmpty() ? "" : " · " + dl[1])).trim() : "";
        String pais = fl.pais == null ? "" : fl.pais;
        cNombre.setText((nombre.isEmpty() ? "" : nombre.toUpperCase() + " · ") + pais.toUpperCase() + " ");
        cNombre.setIcon(fl.iso == null ? null : bandera(fl.iso));
        double[] yo = LibroGuardiaUI.gridALatLon(misDatos()[4]), el = LibroGuardiaUI.gridALatLon(dxGrid);
        if (yo != null && el != null) {
            double[] dr = distanciaRumbo(yo, el);
            cLugar.setText(String.format(Locale.GERMANY, "%s · %,d km · rumbo %d°", dxGrid, Math.round(dr[0]), Math.round(dr[1])));
        } else cLugar.setText(dxGrid.isEmpty() ? "Locator desconocido" : dxGrid);
        cInforme.setText(String.format("Se le oye a %+d dB  ·  %d Hz", dxSnr, dxFrecuencia));
        completarDatosContacto(quien, fl.banda, pais, fl.iso);
        cEtiquetas.removeAll();
        if (fl.estado.contains("NUEVO")) cEtiquetas.add(etiqueta(fl.estado, ROSA));
        if (trabajadosBanda.contains(quien + "|" + fl.banda)) cEtiquetas.add(etiqueta("Ya trabajado en " + fl.banda, GRIS));
        else if (trabajados.contains(quien)) cEtiquetas.add(etiqueta("Trabajado en otra banda", GRIS));
        else cEtiquetas.add(etiqueta("Nuevo para tu LOG", CELESTE));
        cEtiquetas.revalidate();
        for (JLabel l : pasos) { l.setBackground(new Color(40, 52, 72)); l.setForeground(GRIS); }
        actualizarPasos(fl.r.mensaje());
        generarMensajes();
        // qué mensaje toca: si me está llamando, lo que corresponda; si no, Tx1 (llamarle)
        int paso = mencionaMiIndicativo(fl.r.mensaje()) ? pasoSiguiente(fl.r.mensaje()) : 0;
        if (paso >= 0) txSel[paso].setSelected(true);
        actualizarOpciones();
        actualizarPlan();
        cascada.repaint();
        lblEstado.setText("  Elegido " + quien + ". Pulsa «Responder» para llamarle (transmitirás en el " + cbTurno.getSelectedItem() + " turno).");
    }

    /**
     * Nombre, QTH y locator completo de la estación elegida, como en la ventana principal (datos propios, Bitácora 26,
     * QRZ.com / HamQTH). Se busca en segundo plano y se pinta si sigue siendo la misma estación.
     */
    private void completarDatosContacto(String quien, String banda, String pais, String iso) {
        final boolean cb = "CB".equalsIgnoreCase(banda);
        Thread t = new Thread(() -> {
            String[] d = app.datosCompletosIndicativo(quien, cb);
            SwingUtilities.invokeLater(() -> {
                if (!quien.equals(dxIndicativo)) return;   // ya se ha elegido otra
                String nombre = (d[0] + (d[1].isEmpty() ? "" : " · " + d[1])).trim();
                if (!nombre.isEmpty()) cNombre.setText(nombre.toUpperCase() + " · " + (pais == null ? "" : pais.toUpperCase()) + " ");
                String g = d[2].trim().toUpperCase();
                if (g.length() >= 4 && (dxGrid.isEmpty() || g.startsWith(dxGrid.toUpperCase()))) dxGrid = g;
                double[] yo = LibroGuardiaUI.gridALatLon(misDatos()[4]), el = LibroGuardiaUI.gridALatLon(dxGrid);
                if (yo != null && el != null) {
                    double[] dr = distanciaRumbo(yo, el);
                    cLugar.setText(String.format(Locale.GERMANY, "%s · %,d km · rumbo %d°", dxGrid, Math.round(dr[0]), Math.round(dr[1])));
                } else if (!dxGrid.isEmpty()) cLugar.setText(dxGrid);
                if (!d[0].isEmpty() || !d[1].isEmpty()) datosLog.put(quien, new String[]{d[0], d[1], dxGrid});
            });
        }, "DatosContactoDigital");
        t.setDaemon(true);
        t.start();
    }

    /** Marca en la cadena del contacto lo que se ha visto de esa estación. */
    private void actualizarPasos(String msg) {
        if (qsoGuardado) return;   // contacto ya en el LOG: la cadena se queda toda en verde
        int paso = -1;
        String[] w = msg.split(" ");
        String ult = w[w.length - 1];
        if (msg.startsWith("CQ ")) paso = 0;
        else if (mencionaMiIndicativo(msg)) {
            if (ult.equals("73")) paso = 5;
            else if (ult.equals("RR73") || ult.equals("RRR")) paso = 4;
            else if (ult.startsWith("R+") || ult.startsWith("R-")) paso = 3;
            else if (ult.startsWith("+") || ult.startsWith("-")) paso = 2;
            else paso = 1;
        }
        for (int i = 0; i <= paso && i < pasos.length; i++) {
            pasos[i].setBackground(i == paso ? AMBAR : new Color(20, 90, 40));
            pasos[i].setForeground(i == paso ? new Color(30, 30, 30) : new Color(190, 255, 190));
        }
    }

    static double[] distanciaRumbo(double[] a, double[] b) {
        double la1 = Math.toRadians(a[0]), lo1 = Math.toRadians(a[1]), la2 = Math.toRadians(b[0]), lo2 = Math.toRadians(b[1]);
        double d = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin((la2 - la1) / 2), 2) + Math.cos(la1) * Math.cos(la2) * Math.pow(Math.sin((lo2 - lo1) / 2), 2)));
        double y = Math.sin(lo2 - lo1) * Math.cos(la2), x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(lo2 - lo1);
        double rumbo = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
        return new double[]{d * 6371.0, rumbo};
    }

    /** Mensajes Tx1..Tx6 como los de WSJT-X (también los de los concursos). */
    private void generarMensajes() {
        String mio = miIndicativo(), grid = miGrid4();
        String dx = dxIndicativo.isEmpty() ? "" : dxIndicativo;
        String inf = dxSnr <= -99 ? "-10" : String.format("%+03d", Math.max(-30, Math.min(49, dxSnr)));
        String a = actividad();
        rstEnviado = dx.isEmpty() ? "" : inf;
        if (dx.isEmpty()) {
            for (int i = 0; i < 5; i++) txMsg[i].setText("");
        } else {
            String ex = null;   // intercambio del concurso
            if (concursoGrid(a)) ex = grid;
            else if (FIELD_DAY.equals(a))
                ex = LibroGuardiaUI.ConfigApp.get("DIGITALES_FD_CLASE", "1A").toUpperCase() + " " + LibroGuardiaUI.ConfigApp.get("DIGITALES_FD_SECCION", "DX").toUpperCase();
            else if (RTTY_RU.equals(a)) {
                String e = LibroGuardiaUI.ConfigApp.get("DIGITALES_RU_INTERCAMBIO", "").trim().toUpperCase();
                ex = rstRu(dxSnr <= -99 ? -10 : dxSnr) + " " + (e.isEmpty() ? String.format("%04d", Math.min(7999, serie())) : e);
            } else if (EU_VHF.equals(a))
                ex = rptEu(dxSnr <= -99 ? -10 : dxSnr) + String.format("%04d", Math.min(2047, serie())) + " " + miGrid6();
            if (EU_VHF.equals(a)) {
                txMsg[0].setText(dx + " " + mio + (grid.isEmpty() ? "" : " " + grid));
                txMsg[1].setText("<" + dx + "> <" + mio + "> " + ex);
                txMsg[2].setText("<" + dx + "> <" + mio + "> R " + ex);
                rstEnviado = ex;
            } else if (ex != null) {
                txMsg[0].setText(dx + " " + mio + " " + ex);
                txMsg[1].setText(dx + " " + mio + " " + ex);
                txMsg[2].setText(dx + " " + mio + " R " + ex);
                rstEnviado = ex;
            } else {
                txMsg[0].setText(dx + " " + mio + (grid.isEmpty() ? "" : " " + grid));
                txMsg[1].setText(dx + " " + mio + " " + inf);
                txMsg[2].setText(dx + " " + mio + " R" + inf);
            }
            txMsg[3].setText(dx + " " + mio + " RR73");
            txMsg[4].setText(dx + " " + mio + " 73");
        }
        txMsg[5].setText("CQ " + palabraCq(a) + mio + (grid.isEmpty() ? "" : " " + grid));
        txSel[dx.isEmpty() ? 5 : 0].setSelected(true);
    }

    /** Pone la estación elegida en el contacto de la ventana principal (no lo guarda). */
    private void rellenarContactoPrincipal() {
        if (dxIndicativo.isEmpty()) { lblEstado.setText("  Primero elige una estación con doble clic."); return; }
        app.txtIndicativo.setText(dxIndicativo);
        if (!dxGrid.isEmpty()) app.txtGrid.setText(dxGrid);
        String m = motor.modo().name();
        if (ControlEmisora.comboContiene(app.cbModo, m)) app.cbModo.setSelectedItem(m);
        String inf = String.format("%+03d", Math.max(-30, Math.min(49, dxSnr)));
        for (String campo : new String[]{"txtRstSent"}) {
            try {
                java.lang.reflect.Field fld = LibroGuardiaUI.class.getDeclaredField(campo);
                fld.setAccessible(true);
                ((JTextField) fld.get(app)).setText(inf);
            } catch (Exception ignored) { }
        }
        app.toFront();
        lblEstado.setText("  " + dxIndicativo + " pasado al contacto de la ventana principal (revísalo y guárdalo allí).");
    }

    // ------------------------------------------------------------------ transmitir
    /** Algo que ha hecho el operador (para el vigilante de 6 minutos). */
    private void accionUsuario() { ultimaAccion = System.currentTimeMillis(); }

    private boolean algunoConFoco() {
        for (JTextField t : txMsg) if (t != null && t.hasFocus()) return true;
        return false;
    }

    /** Los mensajes se escriben en mayúsculas (regla de Bitácora 26). */
    private static void mayusculas(JTextField t) {
        ((javax.swing.text.AbstractDocument) t.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int off, String txt, javax.swing.text.AttributeSet a) throws javax.swing.text.BadLocationException {
                replace(fb, off, 0, txt, a);
            }
            @Override public void replace(FilterBypass fb, int off, int len, String txt, javax.swing.text.AttributeSet a) throws javax.swing.text.BadLocationException {
                super.replace(fb, off, len, txt == null ? null : txt.toUpperCase(Locale.ROOT), a);
            }
        });
    }

    /** ¿El periodo que empieza en inicio (ms UTC) es del 1er turno (en FT8, los que empiezan en el segundo 00 y 30)? */
    private boolean esPrimerTurno(long inicio) { return Math.floorMod(inicio / motor.periodoMs(), 2) == 0; }

    private static String limpio(String w) { return w.replace("<", "").replace(">", ""); }

    /** ¿El mensaje va dirigido a mí (mi indicativo es la primera palabra)? */
    private boolean dirigidoAMi(String msg) {
        String mio = miIndicativo();
        String[] w = msg.split(" ");
        return mio.length() >= 3 && w.length >= 2 && limpio(w[0]).equals(mio);
    }

    /** Empieza un contacto nuevo: se olvida lo del anterior. */
    private void nuevoContacto() {
        houndIntentosR = 0;
        houndHzLlamada = 0;
        qsoInicio = 0;
        rstRecibido = "";
        qsoGuardado = false;
        cqSeguidos = 0;
    }

    /**
     * Qué mensaje toca enviar después de lo que me ha mandado la otra estación (como la secuencia de WSJT-X):
     * me llama (o con su locator) → Tx2 (informe); me da informe → Tx3 (R+informe); R+informe → Tx4 (RR73);
     * RR73 o RRR → Tx5 (73); 73 → -1 (terminado). Devuelve el índice 0..5.
     */
    private int pasoSiguiente(String msg) {
        String[] w = msg.split(" ");
        String a = actividad();
        if (concurso(a) && w.length >= 3) {
            List<String> resto = Arrays.asList(w).subList(2, w.length);
            String r0 = resto.get(0);
            if (r0.equals("RRR") || r0.equals("RR73")) return 4;
            if (r0.equals("73")) return -1;
            boolean conR = r0.equals("R");
            List<String> ex = conR ? resto.subList(1, resto.size()) : resto;
            String ult = ex.isEmpty() ? "" : ex.get(ex.size() - 1);
            // en EU VHF primero me llaman con el locator (4) y luego llega el intercambio (informe + serie + locator de 6)
            if (EU_VHF.equals(a) && !conR && ex.size() == 1 && ult.matches("[A-R]{2}[0-9]{2}")) {
                if (dxGrid.isEmpty()) dxGrid = ult;
                return 1;
            }
            rstRecibido = String.join(" ", ex);
            if ((concursoGrid(a) || EU_VHF.equals(a)) && ult.matches("[A-R]{2}[0-9]{2}([A-X]{2})?")) dxGrid = ult;
            return conR ? 3 : 2;
        }
        String c = w.length >= 3 ? w[w.length - 1] : "";
        if (c.matches("[+-][0-9]{2}")) { rstRecibido = c; return 2; }
        if (c.matches("R[+-][0-9]{2}")) { rstRecibido = c.substring(1); return 3; }
        if (c.equals("RRR") || c.equals("RR73")) return 4;
        if (c.equals("73")) return -1;
        return 1;
    }

    private String avisoSecuencia;

    /** Lo descodificado en un periodo: si la otra estación me contesta, se elige el siguiente mensaje. */
    private long secuenciaInicio = Long.MIN_VALUE;
    private final Set<String> secuenciaVistos = new HashSet<>();

    private void secuencia(long inicio, List<DescodificadorFt8.Resultado> r) {
        if (inicio != secuenciaInicio) { secuenciaInicio = inicio; secuenciaVistos.clear(); avisoSecuencia = null; }
        if (miIndicativo().length() < 3) return;
        if (FOX.equals(actividad())) { foxRecibido(inicio, r); return; }
        boolean hound = HOUND.equals(actividad());
        for (DescodificadorFt8.Resultado x : r) {
            String msg = x.mensaje();
            if (msg.startsWith("TU; ")) msg = msg.substring(4);   // RTTY Roundup: "TU; " da las gracias al anterior
            if (hound && houndMensaje(inicio, x, msg)) continue;
            if (!dirigidoAMi(msg)) continue;
            if (!secuenciaVistos.add(msg)) continue;   // ya visto en la pasada rápida
            String de = limpio(msg.split(" ")[1]);
            if (!pareceIndicativo(de)) continue;
            if (dxIndicativo.isEmpty()) {
                // llamando CQ: alguien contesta
                if (txActivo && txSel[5].isSelected() && chkPrimero.isSelected()) {
                    long antes = ultimaAccion;
                    String[] pais = LibroGuardiaUI.paisDeIndicativo(de, bandaActual());
                    elegirEstacion(new Fila(inicio, x, pais == null ? "" : pais[1], pais == null ? null : pais[0], "TE LLAMA", bandaActual()));
                    ultimaAccion = antes;   // no cuenta como si lo hubiera hecho el operador
                    qsoInicio = inicio;
                    avisoSecuencia = "contesto a " + de;
                    actualizarPlan();
                    return;
                }
                continue;
            }
            if (!de.equals(dxIndicativo)) continue;
            if (qsoInicio == 0) qsoInicio = inicio;
            if (!chkAuto.isSelected()) continue;
            int paso = pasoSiguiente(msg);
            cbTurno.setSelectedIndex(esPrimerTurno(inicio) ? 1 : 0);
            if (paso >= 0) {
                txSel[paso].setSelected(true);
                if (!txActivo) avisoSecuencia = de + " te ha contestado: pulsa ACTIVAR TX para enviarle Tx" + (paso + 1);
            } else {
                if (!qsoGuardado) guardarContacto();
                if (txActivo) desactivarTx("Contacto con " + de + " terminado.");
                avisoSecuencia = "contacto con " + de + " terminado";
            }
            actualizarPlan();
        }
    }

    // ------------------------------------------------------------------ HOUND (llamar a una expedición en modo Fox/Hound)
    /** "K1ABC RR73; W9XYZ <KH7Z> -12": RR73 para el primero e informe para el segundo, del FOX (resumido). */
    private static final java.util.regex.Pattern COMPUESTO =
            java.util.regex.Pattern.compile("^([A-Z0-9/]+) RR73; ([A-Z0-9/]+) <([A-Z0-9/.]+)> ([+-]\\d{2})$");
    private int houndHzLlamada = 0, houndFoxHz = 0, houndIntentosR = 0;

    /**
     * Como Hound: lo que el FOX me manda. Si me da el informe, se contesta R+informe en la frecuencia en la que me ha
     * llamado (y si hay que repetirlo, 300 Hz más arriba), como WSJT-X. Si me da RR73, el contacto se guarda y se
     * deja de transmitir (el Hound no manda 73). Devuelve true si el mensaje era para mí del FOX.
     */
    private boolean houndMensaje(long inicio, DescodificadorFt8.Resultado x, String msg) {
        if (dxIndicativo.isEmpty()) return false;
        String mio = miIndicativo(), rpt = null;
        boolean rr73 = false;
        java.util.regex.Matcher m = COMPUESTO.matcher(msg);
        if (m.matches()) {
            String fox = m.group(3);
            if (!fox.equals(dxIndicativo) && !fox.equals("...")) return false;
            if (m.group(1).equals(mio)) rr73 = true;
            else if (m.group(2).equals(mio)) rpt = m.group(4);
            else return false;
        } else {
            String[] w = msg.split(" ");
            if (w.length < 3 || !limpio(w[0]).equals(mio) || !limpio(w[1]).equals(dxIndicativo)) return false;
            String u = w[w.length - 1];
            if (u.equals("RR73") || u.equals("RRR") || u.equals("73")) rr73 = true;
            else if (u.matches("[+-][0-9]{2}")) rpt = u;
            else return false;
        }
        if (!secuenciaVistos.add(msg)) return true;   // ya visto en la pasada rápida
        if (qsoInicio == 0) qsoInicio = inicio;
        if (rr73) {
            if (!qsoGuardado) guardarContacto();
            if (houndHzLlamada > 0) ponerTxHz(houndHzLlamada);
            houndIntentosR = 0;
            if (txActivo) desactivarTx("Contacto con el FOX " + dxIndicativo + " terminado (te ha mandado RR73).");
            avisoSecuencia = "contacto con " + dxIndicativo + " terminado";
            for (int i = 0; i <= 4; i++) { pasos[i].setBackground(new Color(20, 90, 40)); pasos[i].setForeground(new Color(190, 255, 190)); }
            return true;
        }
        rstRecibido = rpt;
        houndFoxHz = (int) Math.round(x.frecuencia());
        if (houndHzLlamada <= 0) houndHzLlamada = txHz;
        if (houndIntentosR == 0) ponerTxHz(houndFoxHz);
        txSel[2].setSelected(true);
        cbTurno.setSelectedIndex(1);   // el FOX transmite en el 1er turno; los hounds, en el 2º
        actualizarPasos(dxIndicativo + " " + mio + " " + rpt);
        avisoSecuencia = "el FOX te ha dado " + rpt + ": le contestas R" + rpt;
        if (!txActivo) activarTx(true);   // como WSJT-X: el R+informe sale aunque se hubiera quitado ACTIVAR TX
        actualizarPlan();
        return true;
    }

    private void ponerTxHz(int hz) {
        txHz = Math.max(100, Math.min(2950, hz));
        txtTx.setText(String.valueOf(txHz));
        guardarFrecuencias();
    }

    // ------------------------------------------------------------------ FOX (ser la expedición)
    /** Un Hound que llama o con el que se está haciendo el contacto. */
    private static final class Perro {
        final String call;
        int snr, hz, envios;
        String grid = "", rstRecibido = "";
        boolean rr73, registrado;
        long inicio;
        Perro(String c) { call = c; }
    }

    private static final int FOX_HZ = 300, FOX_PASO = 60, FOX_REPETIR = 3;
    private final List<Perro> foxLlaman = new ArrayList<>(), foxCola = new ArrayList<>();
    private final Map<String, Integer> foxHechos = new HashMap<>();
    private List<Object[]> foxEnviado = new ArrayList<>();
    private String foxTextoPlan = "";
    private JDialog ventanaFox;
    private DefaultTableModel modeloLlaman, modeloCola;

    private int foxSenales() { if (actividadCache == null) leerAjustesRapidos(); return foxSenalesCache; }

    /** Informe en pasos de 2 dB (lo que cabe en el mensaje doble del FOX). */
    private static String informe2(int snr) {
        int v = Math.max(-30, Math.min(32, 2 * (int) Math.round(snr / 2.0)));
        return String.format("%+03d", v);
    }

    private static Perro buscarPerro(List<Perro> l, String c) {
        for (Perro p : l) if (p.call.equals(c)) return p;
        return null;
    }

    /** Como FOX: lo que han mandado los hounds en este periodo. */
    private void foxRecibido(long inicio, List<DescodificadorFt8.Resultado> r) {
        String mio = miIndicativo();
        for (DescodificadorFt8.Resultado x : r) {
            String[] w = x.mensaje().split(" ");
            if (w.length < 2 || !limpio(w[0]).equals(mio)) continue;
            String de = limpio(w[1]);
            if (!pareceIndicativo(de)) continue;
            String u = w.length >= 3 ? w[w.length - 1] : "";
            Perro enCola = buscarPerro(foxCola, de);
            if (u.matches("R[+-][0-9]{2}")) {   // me devuelve el informe: le toca RR73
                if (enCola != null) { enCola.rr73 = true; enCola.rstRecibido = u.substring(1); enCola.envios = 0; }
                else {
                    int n = foxHechos.getOrDefault(de, 0);
                    if (n > 0 && n < FOX_REPETIR) {   // no ha oído mi RR73: se le repite (ya está en el LOG)
                        Perro p = new Perro(de);
                        p.rr73 = true; p.registrado = true; p.snr = x.snr(); p.rstRecibido = u.substring(1);
                        foxCola.add(0, p);
                    }
                }
                continue;
            }
            if (x.frecuencia() < 1000) continue;   // los hounds tienen que llamar por encima de 1000 Hz
            if (enCola != null || foxHechos.containsKey(de)) continue;
            Perro p = buscarPerro(foxLlaman, de);
            if (p == null) { p = new Perro(de); foxLlaman.add(p); }
            p.snr = x.snr();
            p.hz = (int) Math.round(x.frecuencia());
            p.inicio = inicio;
            if (u.matches("[A-R]{2}[0-9]{2}")) p.grid = u;
        }
        foxLlaman.removeIf(p -> inicio - p.inicio > 120_000);   // los que llevan 2 minutos sin llamar
        if (LibroGuardiaUI.ConfigApp.getInt("DIGITALES_FOX_AUTO", 1) == 1) {
            while (foxCola.size() < 2 * foxSenales() && !foxLlaman.isEmpty()) {
                Perro mejor = Collections.max(foxLlaman, Comparator.comparingInt(q -> q.snr));
                foxLlaman.remove(mejor);
                foxCola.add(mejor);
            }
        }
        refrescarVentanaFox();
        if (txActivo) actualizarPlan();
    }

    /** Mensajes del FOX para el próximo turno: hasta N a la vez (informes, RR73 y, si sobra sitio, CQ). */
    private List<String> foxMensajes(List<Object[]> enviados) {
        String mio = miIndicativo(), grid = miGrid4();
        int n = foxSenales();
        List<String> msgs = new ArrayList<>();
        Set<Perro> usados = new HashSet<>();
        for (Perro p : new ArrayList<>(foxCola)) {
            if (msgs.size() >= n) break;
            if (usados.contains(p)) continue;
            usados.add(p);
            if (p.rr73) {
                Perro sig = null;
                for (Perro q : foxCola) if (!usados.contains(q) && !q.rr73) { sig = q; break; }
                String doble = sig == null ? null : p.call + " RR73; " + sig.call + " <" + mio + "> " + informe2(sig.snr);
                if (doble != null && codificador.aBits(doble) != null) {
                    usados.add(sig);
                    msgs.add(doble);
                    enviados.add(new Object[]{p, true});
                    enviados.add(new Object[]{sig, false});
                } else {
                    msgs.add(p.call + " " + mio + " RR73");
                    enviados.add(new Object[]{p, true});
                }
            } else {
                String m = p.call + " " + mio + " " + informe2(p.snr);
                if (codificador.aBits(m) == null) { foxCola.remove(p); continue; }
                msgs.add(m);
                enviados.add(new Object[]{p, false});
            }
        }
        if (msgs.size() < n) msgs.add("CQ " + mio + (grid.isEmpty() ? "" : " " + grid));
        return msgs;
    }

    /** Prepara la transmisión del FOX (varias señales a la vez, desde 300 Hz y cada 60 Hz, siempre en el 1er turno). */
    private void actualizarPlanFox() {
        List<Object[]> env = new ArrayList<>();
        List<String> msgs = foxMensajes(env);
        String texto = String.join("  |  ", msgs);
        EmisorDigitales.Plan a = emisor.plan();
        if (a != null && texto.equals(foxTextoPlan) && a.texto().equals(texto)) return;
        List<int[]> tonos = new ArrayList<>();
        for (String m : msgs) {
            int[] t = GeneradorFtx.tonos(codificador, m, GeneradorFtx.Modo.FT8);
            if (t == null) { desactivarTx("El mensaje «" + m + "» no se puede enviar en FT8."); return; }
            tonos.add(t);
        }
        double[] otrosHz = new double[tonos.size() - 1];
        for (int i = 0; i < otrosHz.length; i++) otrosHz[i] = FOX_HZ + FOX_PASO * (i + 1);
        foxEnviado = env;
        foxTextoPlan = texto;
        emisor.ponerPlan(new EmisorDigitales.Plan(texto, tonos.get(0), FOX_HZ, GeneradorFtx.Modo.FT8, true,
                new ArrayList<>(tonos.subList(1, tonos.size())), otrosHz));
    }

    /** El FOX ha terminado de transmitir: se apunta a quién se le ha mandado qué (y se guardan los contactos con RR73). */
    private void foxTransmitido(long inicio) {
        for (Object[] e : foxEnviado) {
            Perro p = (Perro) e[0];
            if ((Boolean) e[1]) {
                if (!p.registrado) {
                    registrar(p.call, p.grid, informe2(p.snr), p.rstRecibido, inicio, null);
                    p.registrado = true;
                }
                foxHechos.merge(p.call, 1, Integer::sum);
                foxCola.remove(p);
            } else if (++p.envios >= FOX_REPETIR && !p.rr73) {
                foxCola.remove(p);   // no contesta
            }
        }
        foxEnviado = new ArrayList<>();
        foxTextoPlan = "";
        refrescarVentanaFox();
    }

    /** Ventana del FOX: los que llaman (izquierda) y con los que se está haciendo el contacto (derecha). */
    private void abrirVentanaFox() {
        if (ventanaFox == null) {
            ventanaFox = new JDialog(this, "FOX · hounds que llaman y cola", false);
            modeloLlaman = new DefaultTableModel(new Object[]{"Indicativo", "dB", "Hz", "Locator"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            modeloCola = new DefaultTableModel(new Object[]{"Indicativo", "dB", "Toca", "Enviado"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            JTable tl = new JTable(modeloLlaman), tc = new JTable(modeloCola);
            for (JTable t : new JTable[]{tl, tc}) { t.setFont(MONO); t.setRowHeight(20); t.getTableHeader().setReorderingAllowed(false); }
            tl.setToolTipText("Doble clic: pasar a la cola");
            tc.setToolTipText("Doble clic: quitar de la cola");
            tl.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int r = tl.rowAtPoint(e.getPoint());
                    if (e.getClickCount() == 2 && r >= 0 && r < foxLlaman.size()) {
                        Perro p = foxLlaman.remove(r);
                        foxCola.add(p);
                        accionUsuario();
                        refrescarVentanaFox();
                        if (txActivo) actualizarPlan();
                    }
                }
            });
            tc.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int r = tc.rowAtPoint(e.getPoint());
                    if (e.getClickCount() == 2 && r >= 0 && r < foxCola.size()) {
                        foxCola.remove(r);
                        accionUsuario();
                        refrescarVentanaFox();
                        if (txActivo) actualizarPlan();
                    }
                }
            });
            JCheckBox auto = new JCheckBox("Pasar solos a la cola los que llaman más fuerte", LibroGuardiaUI.ConfigApp.getInt("DIGITALES_FOX_AUTO", 1) == 1);
            auto.addActionListener(e -> LibroGuardiaUI.ConfigApp.putInt("DIGITALES_FOX_AUTO", auto.isSelected() ? 1 : 0));
            JPanel dos = new JPanel(new GridLayout(1, 2, 8, 0));
            JScrollPane s1 = new JScrollPane(tl), s2 = new JScrollPane(tc);
            s1.setBorder(BorderFactory.createTitledBorder("Llaman (por encima de 1000 Hz)"));
            s2.setBorder(BorderFactory.createTitledBorder("En contacto (cola)"));
            dos.add(s1); dos.add(s2);
            JPanel todo = new JPanel(new BorderLayout(0, 6));
            todo.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            todo.add(dos);
            JPanel abajo = new JPanel(new BorderLayout());
            abajo.add(auto, BorderLayout.NORTH);
            abajo.add(new JLabel("<html>Pulsa ACTIVAR TX para empezar. Se transmiten hasta " + foxSenales() + " señales a la vez desde 300 Hz, "
                    + "siempre en el 1er turno; a cada uno se le manda el informe hasta 3 veces. El contacto se guarda al mandar RR73.</html>"));
            todo.add(abajo, BorderLayout.SOUTH);
            ventanaFox.setContentPane(todo);
            ventanaFox.setSize(640, 420);
            ventanaFox.setLocationRelativeTo(this);
        }
        refrescarVentanaFox();
        ventanaFox.setVisible(true);
    }

    private void refrescarVentanaFox() {
        if (modeloLlaman == null) return;
        modeloLlaman.setRowCount(0);
        for (Perro p : foxLlaman) modeloLlaman.addRow(new Object[]{p.call, p.snr, p.hz, p.grid});
        modeloCola.setRowCount(0);
        for (Perro p : foxCola) modeloCola.addRow(new Object[]{p.call, p.snr, p.rr73 ? "RR73" : "Informe " + informe2(p.snr), p.envios + " veces"});
    }

    private void llamarCq() {
        accionUsuario();
        if (FOX.equals(actividad())) { abrirVentanaFox(); activarTx(true); return; }
        if (!dxIndicativo.isEmpty()) {
            dxIndicativo = ""; dxGrid = ""; dxSnr = -99;
            cIndicativo.setText(" "); cNombre.setText(" "); cNombre.setIcon(null); cLugar.setText(" "); cInforme.setText(" ");
            cEtiquetas.removeAll(); cEtiquetas.revalidate();
            for (JLabel l : pasos) { l.setBackground(new Color(40, 52, 72)); l.setForeground(GRIS); }
            actualizarOpciones();
        }
        nuevoContacto();
        generarMensajes();
        txSel[5].setSelected(true);
        activarTx(true);
    }

    private void textoLibre() {
        accionUsuario();
        String t = JOptionPane.showInputDialog(this, "Mensaje libre (13 letras o números como mucho):", "Texto libre", JOptionPane.PLAIN_MESSAGE);
        if (t == null) return;
        t = t.trim().toUpperCase(Locale.ROOT).replaceAll(" +", " ");
        if (t.length() > 13) { lblEstado.setText("  El texto libre admite como mucho 13 caracteres."); return; }
        if (t.isEmpty()) return;
        txMsg[4].setText(t);
        txSel[4].setSelected(true);
        actualizarPlan();
        lblEstado.setText("  Texto libre puesto en Tx5. Pulsa ACTIVAR TX para enviarlo.");
    }

    /** Por qué no se puede transmitir ahora (null = se puede). */
    private String motivoNoTransmitir() {
        if ("CAT".equals(metodoPtt) && !LibroGuardiaUI.catConectado)
            return "Para transmitir con el PTT por CAT hay que conectar el CAT (botón «Conectar CAT» de la ventana principal).";
        if (("RTS".equals(metodoPtt) || "DTR".equals(metodoPtt)) && puertoPtt.isEmpty())
            return "Elige en AJUSTES el puerto COM del PTT (" + metodoPtt + ").";
        if (miIndicativo().length() < 3) return "Configura tu indicativo de operador antes de transmitir.";
        long dial = frecuenciaEmisora();
        if (dial <= 0) return "No sé en qué frecuencia está la emisora (el display dice «" + app.txtFreqTx.getText() + "»): no se transmite.";
        String banda = app.bandaPorFrecuencia(dial);
        boolean cb = "CB".equals(banda);
        if (cb && prot(PROT_CB)) return "En CB no se transmite desde DIGITALES (protección: se puede quitar en AJUSTES).";
        long desde = dial + Math.max(0, txHz - 10), hasta = dial + txHz + 100;
        boolean dentro = false;
        for (long[] b : BANDAS_TX) if (desde >= b[0] && hasta <= b[1]) { dentro = true; break; }
        if (!dentro && !cb && prot(PROT_BANDA)) return "La frecuencia " + formatoMhz(dial) + " MHz está fuera de las bandas de radioaficionado: no se transmite.";
        if (FOX.equals(actividad()))
            for (String[] f : FREC_FT8) if (Math.abs(Long.parseLong(f[1]) - dial) < 2000)
                return "Como FOX no se transmite en la frecuencia normal de FT8 (" + formatoMhz(dial) + " MHz): usa otra frecuencia de la banda.";
        if (relojMediana != null && Math.abs(relojMediana) > 1.0 && prot(PROT_RELOJ))
            return String.format(Locale.GERMANY, "El reloj del ordenador va desfasado %+.1f s: ponlo en hora antes de transmitir.", relojMediana);
        return null;
    }

    private void activarTx(boolean si) {
        accionUsuario();
        if (!si) {
            txActivo = false;
            emisor.ponerPlan(null);   // la transmisión en curso termina; ya no se transmite más
            btnTx.setSelected(false);
            lblEstado.setText("  TX desactivado" + (emisor.transmitiendo() ? ": termina la transmisión en curso (PARAR la corta ya)." : "."));
            return;
        }
        if (HOUND.equals(actividad()) && selIndex() == 0 && txHz < 1000) {
            ponerTxHz(1000 + new Random().nextInt(1500));   // el FOX no contesta a los que llaman por debajo de 1000 Hz
        }
        String motivo = motivoNoTransmitir();
        if (motivo != null) { btnTx.setSelected(false); txActivo = false; lblEstado.setText("  " + motivo); return; }
        if (!confirmar()) { btnTx.setSelected(false); txActivo = false; return; }
        if (FOX.equals(actividad())) foxTextoPlan = "";
        txActivo = true;
        cqSeguidos = 0;
        btnTx.setSelected(true);
        prepararTransmision();
        actualizarPlan();
        if (txActivo) lblEstado.setText(FOX.equals(actividad()) ? "  TX activo como FOX (1er turno, hasta " + foxSenales() + " señales a la vez) · PARAR para cortar"
                : "  TX activo: se transmitirá Tx" + (selIndex() + 1) + " en el " + cbTurno.getSelectedItem() + " turno · PARAR para cortar");
    }

    /** La primera vez de cada sesión se pide confirmación antes de transmitir. */
    private boolean confirmar() {
        if (confirmadoEnEstaSesion || !prot(PROT_CONFIRMAR)) return true;
        long dial = frecuenciaEmisora();
        int r = JOptionPane.showConfirmDialog(this,
                "Bitácora 26 va a poner la emisora en TRANSMISIÓN (PTT: " + textoPtt() + ")\n"
                        + "en " + formatoMhz(dial) + " MHz (" + motor.modo() + ", audio a " + txHz + " Hz).\n\n"
                        + "Comprueba antes la antena y la potencia.\n"
                        + "Para cortar en cualquier momento: botón PARAR o tecla Esc.\n\n¿Transmitir?",
                "Transmitir con DIGITALES", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        confirmadoEnEstaSesion = r == JOptionPane.YES_OPTION;
        return confirmadoEnEstaSesion;
    }

    /** Deja de transmitir YA (corta la transmisión en curso) y explica por qué. */
    private void desactivarTx(String motivo) {
        txActivo = false;
        emisor.parar();
        btnTx.setSelected(false);
        if (motivo != null) lblEstado.setText("  " + motivo);
    }

    private void afinar(boolean si) {
        accionUsuario();
        if (!si) { emisor.parar(); lblEstado.setText("  Afinar terminado."); return; }
        if (emisor.transmitiendo()) { btnAfinar.setSelected(false); lblEstado.setText("  Se está transmitiendo: espera a que termine o pulsa PARAR."); return; }
        String motivo = motivoNoTransmitir();
        if (motivo != null) { btnAfinar.setSelected(false); lblEstado.setText("  " + motivo); return; }
        if (!confirmar()) { btnAfinar.setSelected(false); return; }
        txActivo = false;
        emisor.ponerPlan(null);
        prepararTransmision();
        emisor.afinar(txHz);
        lblEstado.setText("  AFINANDO: tono fijo a " + txHz + " Hz (máximo 30 s). Pulsa AFINAR otra vez, PARAR o Esc para terminar.");
    }

    private String textoPtt() {
        return switch (metodoPtt) {
            case "VOX" -> "VOX";
            case "RTS", "DTR" -> metodoPtt + " de " + puertoPtt;
            default -> "CAT";
        };
    }

    private int selIndex() {
        for (int i = 0; i < txSel.length; i++) if (txSel[i].isSelected()) return i;
        return 5;
    }

    /** Prepara lo que el emisor transmitirá en el próximo turno (o nada). */
    private void actualizarPlan() {
        if (emisor == null || btnTx == null) return;
        if (!txActivo) { emisor.ponerPlan(null); return; }
        String motivo = motivoNoTransmitir();
        if (motivo != null) { desactivarTx(motivo); return; }
        if (FOX.equals(actividad())) { actualizarPlanFox(); return; }
        int i = selIndex();
        String texto = txMsg[i].getText().trim().toUpperCase(Locale.ROOT).replaceAll(" +", " ");
        if (texto.isEmpty()) { desactivarTx("El mensaje Tx" + (i + 1) + " está vacío: no se transmite."); return; }
        GeneradorFtx.Modo m = motor.modo();
        if (!texto.equals(cacheTexto) || m != cacheModo) {
            cacheTonos = GeneradorFtx.tonos(codificador, texto, m);
            cacheTexto = texto;
            cacheModo = m;
            cacheSale = null;
            // lo que de verdad saldría (un texto libre de más de 13 letras se recortaría)
            int[] bits = cacheTonos == null ? null : codificador.aBits(texto);
            if (bits != null) {
                String vuelta = codificador.aTexto(bits).mensaje();
                if (vuelta != null && !vuelta.contains("...") && !limpio(vuelta).trim().equals(limpio(texto))) cacheSale = vuelta.trim();
            }
        }
        if (cacheTonos == null) { desactivarTx("El mensaje «" + texto + "» no se puede enviar en " + m + "."); return; }
        if (cacheSale != null) { desactivarTx("El mensaje «" + texto + "» no se puede enviar entero: solo saldría «" + cacheSale + "». Acórtalo."); return; }
        if (HOUND.equals(actividad())) cbTurno.setSelectedIndex(1);   // los hounds transmiten en el 2º turno
        boolean primero = cbTurno.getSelectedIndex() == 0;
        EmisorDigitales.Plan a = emisor.plan();
        if (a == null || !a.texto().equals(texto) || a.hz() != txHz || a.modo() != m || a.primerTurno() != primero)
            emisor.ponerPlan(new EmisorDigitales.Plan(texto, cacheTonos, txHz, m, primero));
    }

    /** El emisor ha terminado una transmisión (o el afinado, con p == null). */
    private void transmitido(long inicio, EmisorDigitales.Plan p, boolean completo) {
        if (p == null) { btnAfinar.setSelected(false); return; }
        filasMiFrec.add(new Fila(inicio, new DescodificadorFt8.Resultado(0, 0, p.hz(), p.texto(), null, 0, 0), "", null, "TX", bandaActual()));
        while (filasMiFrec.size() > 40) filasMiFrec.remove(0);
        pintarMiFrec();
        if (!completo) { lblEstado.setText("  Transmisión cortada."); return; }
        String t = p.texto();
        Llamada contestada = llamadas.get(t.split(" ")[0]);
        if (contestada != null && contestada.estado == 0) { contestada.estado = 1; pintarMeLlaman(); }
        if (FOX.equals(actividad())) { if (t.equals(foxTextoPlan)) foxTransmitido(inicio); return; }
        if (HOUND.equals(actividad()) && !dxIndicativo.isEmpty() && t.equals(txMsg[2].getText().trim()) && houndFoxHz > 0) {
            houndIntentosR++;   // si hay que repetir el R+informe, se manda 300 Hz más arriba (como WSJT-X)
            ponerTxHz(houndFoxHz + 300);
        }
        if (t.startsWith("CQ ")) {
            cqSeguidos++;
            if (cqSeguidos >= MAX_CQ && txActivo && prot(PROT_CQ) && !FOX.equals(actividad())) {
                desactivarTx("Nadie ha contestado a " + MAX_CQ + " CQ seguidos: transmisión parada (protección).");
                Toolkit.getDefaultToolkit().beep();
                return;
            }
        } else cqSeguidos = 0;
        String mio = miIndicativo();
        if (!dxIndicativo.isEmpty() && limpio(t).startsWith(dxIndicativo + " " + mio + " ")) {
            if (qsoInicio == 0) qsoInicio = inicio;
            if (t.endsWith(" RR73") || t.endsWith(" RRR") || t.endsWith(" 73")) {
                if (!qsoGuardado) guardarContacto();
                if (!t.endsWith(" RRR") && txActivo) {
                    txActivo = false;
                    emisor.ponerPlan(null);
                    btnTx.setSelected(false);
                    lblEstado.setText("  Contacto con " + dxIndicativo + " terminado" + (chkGuardar.isSelected() ? " y guardado en el LOG." : ": revísalo en la ventana principal."));
                }
            }
        }
    }

    /** Guarda el contacto en curso en el LOG (o lo pasa al contacto de la ventana principal). */
    private void guardarContacto() {
        if (dxIndicativo.isEmpty()) return;
        qsoGuardado = true;
        // Contacto guardado: toda la cadena en verde (ya no hay ningún paso pendiente en ámbar)
        for (JLabel pl : pasos) { pl.setBackground(new Color(20, 90, 40)); pl.setForeground(new Color(190, 255, 190)); }
        if (!chkGuardar.isSelected()) {
            rellenarContactoPrincipal();
            if (!rstRecibido.isEmpty()) {
                try {
                    java.lang.reflect.Field fld = LibroGuardiaUI.class.getDeclaredField("txtRstRcvd");
                    fld.setAccessible(true);
                    ((JTextField) fld.get(app)).setText(rstRecibido);
                } catch (Exception ignored) { }
            }
            return;
        }
        registrar(dxIndicativo, dxGrid, rstEnviado, rstRecibido, qsoInicio > 0 ? qsoInicio : System.currentTimeMillis(), datosLog.get(dxIndicativo));
        String a0 = actividad();
        if (EU_VHF.equals(a0) || (RTTY_RU.equals(a0) && LibroGuardiaUI.ConfigApp.get("DIGITALES_RU_INTERCAMBIO", "").isBlank())) {
            LibroGuardiaUI.ConfigApp.putInt("DIGITALES_SERIE", serie() + 1);   // el siguiente contacto lleva el número siguiente
        }
        pasos[6].setBackground(new Color(20, 90, 40));
        pasos[6].setForeground(new Color(190, 255, 190));
    }

    /** Guarda un contacto en el LOG (lo usan el contacto normal y el FOX, que guarda muchos seguidos). */
    private void registrar(String call, String grid, String rstS, String rstR, long cuando, String[] dl) {
        Llamada hecha = llamadas.get(call);
        if (hecha != null) { hecha.estado = 2; SwingUtilities.invokeLater(this::pintarMeLlaman); }
        long dial = frecuenciaEmisora();
        Instant ins = Instant.ofEpochMilli(cuando);
        StringBuilder a = new StringBuilder();
        campoAdi(a, "CALL", call);
        campoAdi(a, "GRIDSQUARE", grid);
        if (motor.modo() == GeneradorFtx.Modo.FT4) { campoAdi(a, "MODE", "MFSK"); campoAdi(a, "SUBMODE", "FT4"); }
        else campoAdi(a, "MODE", "FT8");
        campoAdi(a, "RST_SENT", rstS);
        campoAdi(a, "RST_RCVD", rstR);
        campoAdi(a, "QSO_DATE", DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(ins));
        campoAdi(a, "TIME_ON", DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC).format(ins));
        if (dial > 0) {
            campoAdi(a, "FREQ", String.format(Locale.US, "%.6f", (dial + txHz) / 1e6));
            String b = app.bandaPorFrecuencia(dial);
            if (b != null) campoAdi(a, "BAND", b);
        }
        if (dl != null) { campoAdi(a, "NAME", dl[0]); campoAdi(a, "QTH", dl[1]); }
        a.append("<EOR>");
        app.registrarQsoDigital(a.toString(), "DIGITALES");
        ultimaCargaLog = 0;   // que se vuelva a leer el LOG (ya trabajado, país, locator)
    }

    private static void campoAdi(StringBuilder a, String et, String v) {
        if (v == null || v.isBlank()) return;
        String x = v.trim();
        a.append('<').append(et).append(':').append(x.length()).append('>').append(x).append(' ');
    }

    // ------------------------------------------------------------------ cada 0,2 s
    private void cadaRato() {
        long ahora = System.currentTimeMillis();
        lblHora.setText(DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ahora)));
        long per = motor.periodoMs();
        barra.ponerProgreso((ahora % per) / (double) per, per, motor.enMarcha(), emisor.transmitiendo());
        long hz = frecuenciaEmisora();
        if (hz > 0) {
            String nueve = String.format("%9d", hz);
            lblFrecuencia.setText(nueve.substring(0, 3) + "." + nueve.substring(3, 6) + "." + nueve.substring(6, 9));
        } else lblFrecuencia.setText("   .   .   ");
        ponerBandaEnLista(hz);
        String banda = hz > 0 ? app.bandaPorFrecuencia(hz) : "";
        String act = actividad();
        lblBandaModo.setText((banda == null || banda.isEmpty() ? "" : banda + " · ") + (enRtty ? "RTTY" : motor.modo() + (NINGUNO.equals(act) ? "" : " · " + act.replace('_', ' ')))
                + (LibroGuardiaUI.catConectado ? " · CAT conectado" : " · sin CAT"));
        if (!miIndicativo().equals(motor.mensajes() == null ? "" : ultimoMio)) { ultimoMio = miIndicativo(); actualizarOpciones(); generarMensajes(); }
        // protecciones mientras se transmite
        if (emisor.transmitiendo() && "CAT".equals(metodoPtt) && !LibroGuardiaUI.catConectado) { btnAfinar.setSelected(false); desactivarTx("Se ha perdido el CAT: transmisión cortada."); }
        if (txActivo && ahora - ultimaAccion > VIGILANTE_MS && prot(PROT_VIGILANTE)) {
            desactivarTx("Transmisión parada: 6 minutos sin tocar nada (protección). Pulsa ACTIVAR TX para seguir.");
            Toolkit.getDefaultToolkit().beep();
        }
        if (txActivo) actualizarPlan();
        String t = emisor.transmitiendo() ? (emisor.afinando() ? "AFINANDO" : "TRANSMITIENDO") : txActivo ? "TX ACTIVO" : "ACTIVAR TX";
        if (!t.equals(btnTx.getText())) btnTx.setText(t);
        if (btnTx.isSelected() != txActivo) btnTx.setSelected(txActivo);
        if (!emisor.afinando() && btnAfinar.isSelected() && !emisor.transmitiendo()) btnAfinar.setSelected(false);
    }

    private String ultimoMio = "";

    // ------------------------------------------------------------------ ajustes
    private void abrirAjustes() {
        JDialog d = new JDialog(this, "Ajustes de DIGITALES", true);
        JPanel raiz = new JPanel(new GridBagLayout());
        GridBagConstraints rg = new GridBagConstraints();
        rg.fill = GridBagConstraints.BOTH;
        rg.insets = new Insets(4, 4, 4, 4);
        rg.weightx = 1;
        raiz.setBackground(FONDO);
        raiz.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JComboBox<String> entrada = new JComboBox<>();
        entrada.addItem("(la de por defecto de Windows)");
        for (String n : MotorDigitales.entradas()) entrada.addItem(n);
        String actual = LibroGuardiaUI.ConfigApp.get("DIGITALES_ENTRADA", "");
        if (!actual.isEmpty()) entrada.setSelectedItem(actual);
        JComboBox<String> salida = new JComboBox<>();
        salida.addItem("(la de por defecto de Windows)");
        for (String n : EmisorDigitales.salidas()) salida.addItem(n);
        String actualSal = LibroGuardiaUI.ConfigApp.get("DIGITALES_SALIDA", "");
        if (!actualSal.isEmpty()) salida.setSelectedItem(actualSal);
        salida.setToolTipText("Por dónde sale el audio hacia la emisora al transmitir");
        JComboBox<String> canEnt = ConfigAudio.comboCanal(canalEntrada()), canSal = ConfigAudio.comboCanal(canalSalida());
        canEnt.setToolTipText("Canal de la entrada: Mono, Izquierdo, Derecho o Automático (el que más suena)");
        canSal.setToolTipText("Canal de la salida: Mono, Izquierdo, Derecho o Automático (por los dos)");
        rg.gridx = 0; rg.gridy = 0; rg.weighty = 0;
        raiz.add(caja("SONIDO (la tarjeta de la emisora)", columna(
                fila("Entrada (lo que se oye)", dosCombos(entrada, canEnt)),
                fila("Salida (lo que se transmite)", dosCombos(salida, canSal)),
                lbl("<html><span style='color:#8fa6c8'>Solo para DIGITALES (FT8, FT4, RTTY). WSPR usa «Archivo → Configurar audio».<br>"
                        + "Elige la tarjeta de sonido que va a la emisora: la que lleve<br>"
                        + "la propia emisora o la del interfaz externo (la FT-891 no trae).<br>"
                        + "Entrada: que la barra de audio quede a media altura.<br>"
                        + "Salida: sube el «Nivel de salida» sin que se encienda el ALC.</span></html>", f(Font.PLAIN, 12), TEXTO))), rg);
        long fAhora = frecuenciaEmisora();
        String[][] metodos = {{"CAT", "CAT (como el botón SEND)"}, {"VOX", "VOX"},
                {"RTS", "RTS de un puerto COM"}, {"DTR", "DTR de un puerto COM"}};
        JComboBox<String> cbPtt = new JComboBox<>();
        for (String[] m : metodos) cbPtt.addItem(m[1]);
        for (int i = 0; i < metodos.length; i++) if (metodos[i][0].equals(metodoPtt)) cbPtt.setSelectedIndex(i);
        cbPtt.setToolTipText("<html>CAT: por OmniRig, como el botón SEND.<br>VOX: no se manda nada; transmite al llegarle el audio.<br>"
                + "RTS / DTR: por una línea de un puerto COM (interfaces externos, o el 2º puerto COM de la FT-891).</html>");
        JComboBox<String> cbPuerto = new JComboBox<>();
        List<String> puertosCodigo = new ArrayList<>();
        if (!puertoPtt.isEmpty()) { cbPuerto.addItem(puertoPtt); puertosCodigo.add(puertoPtt); }
        cbPuerto.setToolTipText("El puerto COM por el que va el PTT (no el mismo que usa OmniRig para el CAT)");
        Runnable ajustarPuerto = () -> cbPuerto.setEnabled(cbPtt.getSelectedIndex() >= 2);
        cbPtt.addActionListener(e -> ajustarPuerto.run());
        ajustarPuerto.run();
        // la lista de puertos tarda un poco: se rellena sin esperar
        Thread lp = new Thread(() -> {
            List<String[]> ps = PttSerie.puertos();
            SwingUtilities.invokeLater(() -> {
                String elegido = puertosCodigo.isEmpty() ? null : puertosCodigo.get(cbPuerto.getSelectedIndex() < 0 ? 0 : cbPuerto.getSelectedIndex());
                cbPuerto.removeAllItems();
                puertosCodigo.clear();
                for (String[] x : ps) { cbPuerto.addItem(x[1]); puertosCodigo.add(x[0]); }
                if (elegido != null && !puertosCodigo.contains(elegido)) { cbPuerto.addItem(elegido + " (no está conectado)"); puertosCodigo.add(elegido); }
                if (elegido != null) cbPuerto.setSelectedIndex(puertosCodigo.indexOf(elegido));
                if (puertosCodigo.isEmpty()) cbPuerto.addItem("(no hay puertos COM)");
            });
        }, "PuertosCOM");
        lp.setDaemon(true);
        lp.start();
        String[][] modos = {{"NINGUNO", "Ninguno (no tocar el modo)"}, {"USB", "USB"}, {"DATOS", "Datos (DATA-U)"}};
        JComboBox<String> cbModoTx = new JComboBox<>();
        for (String[] m : modos) cbModoTx.addItem(m[1]);
        for (int i = 0; i < modos.length; i++) if (modos[i][0].equals(modoTx)) cbModoTx.setSelectedIndex(i);
        cbModoTx.setToolTipText("<html>En qué modo se pone la emisora (por el CAT) al elegir banda y al activar TX.<br>"
                + "Datos: el modo DATA de la emisora (en muchas, el audio entra por detrás).</html>");
        JLabel lPrueba = info(" ");
        JButton probar = boton("Probar PTT");
        probar.setToolTipText("Pone la emisora en transmisión 2 segundos (sin audio) para comprobar el PTT");
        probar.addActionListener(e -> {
            String met = metodos[cbPtt.getSelectedIndex()][0];
            int ip = cbPuerto.getSelectedIndex();
            String pu = ip >= 0 && ip < puertosCodigo.size() ? puertosCodigo.get(ip) : "";
            if ("VOX".equals(met)) { lPrueba.setText("Con VOX no hay nada que probar: transmite al llegarle el audio (usa AFINAR)."); return; }
            if ("CAT".equals(met) && !LibroGuardiaUI.catConectado) { lPrueba.setText("Conecta antes el CAT en la ventana principal."); return; }
            if (!"CAT".equals(met) && pu.isEmpty()) { lPrueba.setText("Elige el puerto COM."); return; }
            if (emisor.transmitiendo()) { lPrueba.setText("Se está transmitiendo: espera o pulsa PARAR."); return; }
            int r = JOptionPane.showConfirmDialog(d, "La emisora pasará a TRANSMISIÓN 2 segundos (sin audio).\n¿Seguir?",
                    "Probar PTT", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
            probar.setEnabled(false);
            lPrueba.setText("Probando: la emisora debería estar transmitiendo…");
            Thread t = new Thread(() -> {
                String res;
                try {
                    ponerPttCon(met, pu, true);
                    Thread.sleep(2000);
                    res = "Hecho. Si la emisora ha pasado a TX y ha vuelto, el PTT funciona.";
                } catch (Exception ex) {
                    res = "No ha funcionado: " + ex.getMessage();
                } finally {
                    try { ponerPttCon(met, pu, false); } catch (Exception ignored) { }
                }
                String fin = res;
                SwingUtilities.invokeLater(() -> { lPrueba.setText(fin); probar.setEnabled(true); });
            }, "ProbarPtt");
            t.setDaemon(true);
            t.start();
        });
        JPanel pp = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pp.setOpaque(false);
        pp.setAlignmentX(0);
        pp.add(probar);
        rg.gridx = 1;
        raiz.add(caja("EMISORA Y PTT", columna(
                fila("Pasar a transmisión", cbPtt),
                fila("Puerto (RTS / DTR)", cbPuerto),
                fila("Modo al transmitir", cbModoTx),
                pp, lPrueba,
                info(fAhora > 0 ? "Frecuencia de la emisora: " + formatoMhz(fAhora) + " MHz." : "Ahora no sé en qué frecuencia está la emisora."),
                nota("La frecuencia se lee por el CAT. La fuente del audio (delante o detrás) se elige en el menú de la emisora."))), rg);
        // PROTECCIONES: cada una con su casilla (vienen todas puestas)
        String[][] protecciones = {{PROT_VIGILANTE, "Parar a los 6 minutos sin tocar nada"}, {PROT_CQ, "Parar si nadie contesta a 5 CQ seguidos"},
                {PROT_RELOJ, "No transmitir si el reloj va desfasado más de 1 s"}, {PROT_CB, "No transmitir en CB"},
                {PROT_BANDA, "No transmitir fuera de las bandas de radioaficionado"}, {PROT_ESC, "La tecla Esc corta al momento"},
                {PROT_CONFIRMAR, "Pedir confirmación la primera vez"}};
        List<JCheckBox> cProt = new ArrayList<>();
        JPanel pProt = new JPanel();
        pProt.setLayout(new BoxLayout(pProt, BoxLayout.Y_AXIS));
        pProt.setOpaque(false);
        for (String[] x : protecciones) { JCheckBox c = chk(x[1], x[0]); c.setAlignmentX(0); cProt.add(c); pProt.add(c); }
        pProt.add(nota("Vienen todas puestas. Si quitas alguna, la responsabilidad es tuya. El botón PARAR siempre corta al momento."));
        rg.gridx = 2;
        raiz.add(caja("PROTECCIONES", pProt), rg);
        // COLORES (como Ajustes → Colores de WSJT-X), donde antes estaba INTERNET
        Runnable releer = () -> SwingUtilities.invokeLater(() -> {
            ultimaCargaLog = 0;
            cargarLogSiHaceFalta();
            tablaActividad.repaint();
            pintarMiFrec();
        });
        ColoresDigitales.Apartado colores = new ColoresDigitales.Apartado(d, releer);
        rg.gridx = 0; rg.gridy = 1; rg.gridwidth = 2; rg.weighty = 1;
        raiz.add(caja("COLORES", colores.componente), rg);
        // AVISOS de la columna de la derecha de la lista
        JCheckBox cPais = chk("País nuevo en esta banda, en rosa", "DIGITALES_AVISO_PAIS");
        JCheckBox cGrid = chk("Locator nuevo en esta banda, en rosa", "DIGITALES_AVISO_LOCATOR");
        JCheckBox cTrab = chk("Ya trabajado, en gris", "DIGITALES_AVISO_TRABAJADO");
        JCheckBox cSon = chk("Sonido cuando alguien me llama", "DIGITALES_SONIDO_LLAMADA");
        JComponent avisos = caja("AVISOS (columna de la derecha de la lista)", columna(cPais, cGrid, cTrab, cSon,
                lbl("<html><span style='color:#8fa6c8'>Sale de tu propio LOG: no hace falta otro programa.</span></html>", f(Font.PLAIN, 12), TEXTO)));
        // MODOS ESPECIALES: concursos y expediciones (Fox / Hound), como en WSJT-X
        JComboBox<String> cbAct = new JComboBox<>();
        for (String[] x : ACTIVIDADES) cbAct.addItem(x[1]);
        String actGuardada = LibroGuardiaUI.ConfigApp.get("DIGITALES_ACTIVIDAD", NINGUNO);
        for (int i = 0; i < ACTIVIDADES.length; i++) if (ACTIVIDADES[i][0].equals(actGuardada)) cbAct.setSelectedIndex(i);
        JTextField tClase = new JTextField(LibroGuardiaUI.ConfigApp.get("DIGITALES_FD_CLASE", "1A"), 5);
        JTextField tSeccion = new JTextField(LibroGuardiaUI.ConfigApp.get("DIGITALES_FD_SECCION", "DX"), 5);
        JTextField tRu = new JTextField(LibroGuardiaUI.ConfigApp.get("DIGITALES_RU_INTERCAMBIO", ""), 5);
        for (JTextField t : new JTextField[]{tClase, tSeccion, tRu}) mayusculas(t);
        tClase.setToolTipText("Field Day: número de transmisores y clase, por ejemplo 1A o 2B");
        tSeccion.setToolTipText("Field Day: sección ARRL/RAC; fuera de EE. UU. y Canadá, DX");
        tRu.setToolTipText("RTTY Roundup: estado o provincia (EE. UU. / Canadá). Déjalo vacío para mandar el número de serie (resto del mundo)");
        JSpinner sSerie = new JSpinner(new SpinnerNumberModel(serie(), 1, 7999, 1));
        sSerie.setToolTipText("Número de serie que llevará el próximo contacto (EU VHF y RTTY Roundup); sube solo al guardar cada contacto");
        JSpinner sSenales = new JSpinner(new SpinnerNumberModel(foxSenales(), 1, 5, 1));
        sSenales.setToolTipText("Como FOX: cuántas señales se transmiten a la vez (cada una con menos potencia)");
        JButton bFox = boton("Ventana FOX…");
        bFox.addActionListener(e -> abrirVentanaFox());
        JLabel nAct = nota(" ");
        Runnable explicar = () -> {
            String a = ACTIVIDADES[Math.max(0, cbAct.getSelectedIndex())][0];
            tClase.setEnabled(FIELD_DAY.equals(a)); tSeccion.setEnabled(FIELD_DAY.equals(a));
            tRu.setEnabled(RTTY_RU.equals(a)); sSerie.setEnabled(EU_VHF.equals(a) || RTTY_RU.equals(a));
            sSenales.setEnabled(FOX.equals(a)); bFox.setEnabled(FOX.equals(a));
            nAct.setText("<html><div style='width:250px'><span style='color:#8fa6c8'>" + switch (a) {
                case HOUND -> "Para llamar a una expedición que trabaja en modo Fox/Hound (solo FT8). Doble clic en el FOX y «Responder»: "
                        + "llamas por encima de 1000 Hz en el 2º turno; cuando te da el informe, le contestas en su frecuencia "
                        + "y al recibir RR73 se guarda el contacto. No uses las frecuencias normales de FT8. "
                        + "(El modo SuperFox no está incluido.)";
                case FOX -> "Para ser la expedición (solo FT8). Transmites en el 1er turno hasta 5 señales a la vez desde 300 Hz; "
                        + "los hounds llaman por encima de 1000 Hz. La ventana FOX muestra quién llama y la cola. "
                        + "No se puede usar en las frecuencias normales de FT8.";
                case NA_VHF, WW_DIGI, ARRL_DIGI -> "Concurso con intercambio de locator: «CQ " + palabraCq(a).trim() + "», tu locator y R + locator.";
                case EU_VHF -> "Concurso EU VHF: informe + número de serie + locator de 6 (ponlo completo en tu operador). "
                        + "Los indicativos van resumidos (<...>).";
                case FIELD_DAY -> "ARRL Field Day: «CQ FD»; el intercambio es la clase (1A, 2B...) y la sección (DX fuera de EE. UU. y Canadá).";
                case RTTY_RU -> "ARRL RTTY Roundup: «CQ RU»; se manda 5x9 y el estado, o el número de serie si estás fuera de EE. UU. y Canadá.";
                default -> "FT8 y FT4 normales.";
            } + "</span></div></html>");
        };
        cbAct.addActionListener(e -> explicar.run());
        explicar.run();
        // cada opción en su propia fila (etiqueta a la izquierda y la casilla a la derecha, que no se aprieten)
        JPanel me = new JPanel(new GridBagLayout());
        me.setOpaque(false);
        GridBagConstraints mg = new GridBagConstraints();
        mg.insets = new Insets(3, 2, 3, 2);
        mg.anchor = GridBagConstraints.WEST;
        Object[][] filasMe = {{"Actividad", cbAct}, {"Field Day: clase", tClase}, {"Field Day: sección", tSeccion},
                {"RTTY Roundup: estado", tRu}, {"Nº de serie", sSerie}, {"FOX: señales a la vez", sSenales}, {"", bFox}};
        for (Object[] x : filasMe) {
            JComponent c = (JComponent) x[1];
            Dimension pd = c.getPreferredSize();
            c.setMinimumSize(new Dimension(Math.max(70, c == cbAct ? 150 : pd.width), pd.height));
            mg.gridy++;
            mg.gridx = 0; mg.weightx = 0; mg.fill = GridBagConstraints.NONE;
            me.add(lbl((String) x[0], f(Font.PLAIN, 13), TEXTO), mg);
            mg.gridx = 1; mg.weightx = 1; mg.fill = c == bFox || c == sSenales || c == sSerie ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
            me.add(c, mg);
        }
        mg.gridy++; mg.gridx = 0; mg.gridwidth = 2; mg.fill = GridBagConstraints.HORIZONTAL;
        me.add(nAct, mg);
        mg.gridy++; mg.weighty = 1;
        JPanel relleno = new JPanel();
        relleno.setOpaque(false);
        me.add(relleno, mg);
        JScrollPane scMe = new JScrollPane(me, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scMe.setBorder(null);
        scMe.setOpaque(false);
        scMe.getViewport().setOpaque(false);
        scMe.getVerticalScrollBar().setUnitIncrement(16);
        JComponent modosEsp = caja("MODOS ESPECIALES (concursos y expediciones)", scMe);
        JPanel derecha = new JPanel(new BorderLayout(0, 8));
        derecha.setOpaque(false);
        derecha.add(avisos, BorderLayout.NORTH);
        derecha.add(modosEsp);
        if (panelOpciones != null) derecha.add(caja("OPCIONES", panelOpciones), BorderLayout.SOUTH);
        rg.gridx = 2; rg.gridwidth = 1;
        raiz.add(derecha, rg);
        JPanel todo = new JPanel(new BorderLayout());
        todo.setBackground(FONDO);
        todo.add(raiz);
        JPanel bot = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bot.setOpaque(false);
        JButton cancelar = boton("Cancelar"), aceptar = boton("Aceptar");
        cancelar.addActionListener(e -> d.dispose());
        aceptar.addActionListener(e -> {
            String e1 = entrada.getSelectedIndex() <= 0 ? "" : (String) entrada.getSelectedItem();
            boolean cambio = !e1.equals(LibroGuardiaUI.ConfigApp.get("DIGITALES_ENTRADA", ""));
            LibroGuardiaUI.ConfigApp.put("DIGITALES_ENTRADA", e1);
            String s1 = salida.getSelectedIndex() <= 0 ? "" : (String) salida.getSelectedItem();
            LibroGuardiaUI.ConfigApp.put("DIGITALES_SALIDA", s1);
            emisor.ponerSalida(s1);
            String ce = ConfigAudio.codigoCanal(canEnt), cs = ConfigAudio.codigoCanal(canSal);
            if (!ce.equals(canalEntrada())) cambio = true;
            LibroGuardiaUI.ConfigApp.put("DIGITALES_CANAL", ce);
            LibroGuardiaUI.ConfigApp.put("DIGITALES_CANAL_SALIDA", cs);
            emisor.ponerCanalSalida(cs);
            String met = metodos[cbPtt.getSelectedIndex()][0];
            int ip = cbPuerto.getSelectedIndex();
            String pu = ip >= 0 && ip < puertosCodigo.size() ? puertosCodigo.get(ip) : puertoPtt;
            if (!met.equals(metodoPtt) || !pu.equals(puertoPtt)) { if (txActivo || emisor.transmitiendo()) desactivarTx("Se ha cambiado el PTT: transmisión parada."); cerrarSerie(); }
            metodoPtt = met;
            puertoPtt = pu;
            modoTx = modos[cbModoTx.getSelectedIndex()][0];
            LibroGuardiaUI.ConfigApp.put("DIGITALES_PTT", metodoPtt);
            LibroGuardiaUI.ConfigApp.put("DIGITALES_PTT_PUERTO", puertoPtt);
            LibroGuardiaUI.ConfigApp.put("DIGITALES_MODO_TX", modoTx);
            LibroGuardiaUI.ConfigApp.putInt("DIGITALES_AVISO_PAIS", cPais.isSelected() ? 1 : 0);
            LibroGuardiaUI.ConfigApp.putInt("DIGITALES_AVISO_LOCATOR", cGrid.isSelected() ? 1 : 0);
            LibroGuardiaUI.ConfigApp.putInt("DIGITALES_AVISO_TRABAJADO", cTrab.isSelected() ? 1 : 0);
            LibroGuardiaUI.ConfigApp.putInt("DIGITALES_SONIDO_LLAMADA", cSon.isSelected() ? 1 : 0);
            for (int i = 0; i < protecciones.length; i++) LibroGuardiaUI.ConfigApp.putInt(protecciones[i][0], cProt.get(i).isSelected() ? 1 : 0);
            leerAjustesRapidos();
            // modos especiales
            String antes = actividad();
            LibroGuardiaUI.ConfigApp.put("DIGITALES_ACTIVIDAD", ACTIVIDADES[Math.max(0, cbAct.getSelectedIndex())][0]);
            LibroGuardiaUI.ConfigApp.put("DIGITALES_FD_CLASE", tClase.getText().trim().toUpperCase());
            LibroGuardiaUI.ConfigApp.put("DIGITALES_FD_SECCION", tSeccion.getText().trim().toUpperCase());
            LibroGuardiaUI.ConfigApp.put("DIGITALES_RU_INTERCAMBIO", tRu.getText().trim().toUpperCase());
            LibroGuardiaUI.ConfigApp.putInt("DIGITALES_SERIE", (Integer) sSerie.getValue());
            LibroGuardiaUI.ConfigApp.putInt("DIGITALES_FOX_SENALES", (Integer) sSenales.getValue());
            leerAjustesRapidos();
            String ahora = actividad();
            if (!ahora.equals(antes) && (txActivo || emisor.transmitiendo())) desactivarTx("Se ha cambiado la actividad: transmisión parada.");
            if (!ahora.equals(antes)) { foxCola.clear(); foxLlaman.clear(); foxTextoPlan = ""; nuevoContacto(); }
            // colores
            colores.aplicar();
            d.dispose();
            generarMensajes();
            actualizarPie();
            releer.run();
            if (FOX.equals(ahora)) abrirVentanaFox();
            else if (ventanaFox != null) ventanaFox.setVisible(false);
            if (!actividadCache.equals(ahora))
                lblEstado.setText("  " + nombreActividad(actividadCache) + " solo funciona en FT8: en FT4 se trabaja normal.");
            if (cambio && btnEscuchar.isSelected()) escuchar(true);
        });
        bot.add(cancelar);
        bot.add(aceptar);
        todo.add(bot, BorderLayout.SOUTH);
        d.setContentPane(todo);
        Rectangle pantalla = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        d.setSize(Math.min(1320, pantalla.width - 20), Math.min(960, pantalla.height - 20));
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    /** Una línea informativa (no se puede tocar porque no hay nada que elegir). */
    private static JLabel info(String t) {
        JLabel l = lbl(t, f(Font.PLAIN, 13), TEXTO);
        l.setAlignmentX(0);
        return l;
    }

    private static JLabel nota(String t) {
        JLabel l = lbl("<html><div style='width:330px'><span style='color:#8fa6c8'>" + t + "</span></div></html>", f(Font.PLAIN, 12), TEXTO);
        l.setAlignmentX(0);
        return l;
    }

    private static JCheckBox chk(String t, String clave) {
        JCheckBox c = new JCheckBox(t, LibroGuardiaUI.ConfigApp.getInt(clave, 1) == 1);
        c.setOpaque(false);
        c.setFont(f(Font.PLAIN, 13));
        return c;
    }

    private static JComponent fila(String et, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        JLabel l = lbl(et, f(Font.PLAIN, 13), TEXTO);
        l.setPreferredSize(new Dimension(170, 24));
        p.add(l, BorderLayout.WEST);
        p.add(c);
        p.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return p;
    }

    /** Tarjeta de sonido y, a su derecha, el canal (como en «Configurar audio»). */
    private static JComponent dosCombos(JComponent tarjeta, JComponent canal) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setOpaque(false);
        p.add(tarjeta);
        canal.setPreferredSize(new Dimension(110, tarjeta.getPreferredSize().height));
        p.add(canal, BorderLayout.EAST);
        return p;
    }

    private static JComponent columna(JComponent... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (JComponent c : cs) { c.setAlignmentX(0); p.add(c); }
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ------------------------------------------------------------------ cascada
    /** Cascada de 0 a 3000 Hz: la línea más nueva arriba. Clic = recibir aquí; botón derecho = transmitir aquí. */
    class Cascada extends JPanel {
        record Etiqueta(double hz, String texto) { }
        private BufferedImage img;
        private List<Etiqueta> etiquetas = new ArrayList<>();
        private float suelo = Float.NaN;
        private static final int ESCALA = 18;
        private static final double FMAX = 3000;

        Cascada() {
            setBackground(new Color(8, 14, 26));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int hz = (int) Math.round(e.getX() * FMAX / Math.max(1, getWidth()));
                    hz = Math.max(100, Math.min(2950, hz));
                    if (SwingUtilities.isRightMouseButton(e) || e.isShiftDown()) { txHz = hz; txtTx.setText(String.valueOf(hz)); }
                    else { rxHz = hz; txtRx.setText(String.valueOf(hz)); }
                    guardarFrecuencias();
                }
            });
        }

        void ponerEtiquetas(List<Etiqueta> l) { etiquetas = l; repaint(); }

        /** Se llama desde el hilo de captura. */
        void anadirLinea(float[] db, double hzCasilla) {
            int w = getWidth(), h = getHeight() - ESCALA;
            if (w <= 0 || h <= 0) return;
            // nivel de ruido: mediana suavizada
            float[] copia = db.clone();
            Arrays.sort(copia);
            float med = copia[copia.length / 2];
            suelo = Float.isNaN(suelo) ? med : suelo * 0.95f + med * 0.05f;
            int[] fila = new int[w];
            for (int x = 0; x < w; x++) {
                double hz = x * FMAX / w;
                int i = (int) (hz / hzCasilla);
                float v = i < db.length ? db[i] : suelo;
                if (i + 1 < db.length) v = Math.max(v, db[i + 1]);
                fila[x] = color((v - suelo - 2f) / 28f);
            }
            SwingUtilities.invokeLater(() -> {
                if (img == null || img.getWidth() != w || img.getHeight() != h) {
                    BufferedImage n = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = n.createGraphics();
                    g.setColor(new Color(4, 10, 60));
                    g.fillRect(0, 0, w, h);
                    if (img != null) g.drawImage(img, 0, 0, w, h, null);
                    g.dispose();
                    img = n;
                }
                // bajar todo una línea y poner la nueva arriba
                Graphics2D g = img.createGraphics();
                g.copyArea(0, 0, w, h - 1, 0, 1);
                g.dispose();
                img.setRGB(0, 0, w, 1, fila, 0, w);
                repaint();
            });
        }

        private int color(float v) {
            v = Math.max(0, Math.min(1, v));
            float[][] p = {{0f, 4, 10, 60}, {0.2f, 15, 50, 175}, {0.4f, 40, 140, 235}, {0.6f, 245, 225, 70}, {0.8f, 255, 250, 185}, {1f, 255, 255, 255}};
            for (int k = 0; k < p.length - 1; k++) if (v <= p[k + 1][0]) {
                float t = (v - p[k][0]) / (p[k + 1][0] - p[k][0]);
                return ((int) (p[k][1] + t * (p[k + 1][1] - p[k][1])) << 16) | ((int) (p[k][2] + t * (p[k + 1][2] - p[k][2])) << 8) | (int) (p[k][3] + t * (p[k + 1][3] - p[k][3]));
            }
            return 0xFFFFFF;
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(new Color(22, 32, 48));
            g.fillRect(0, 0, w, ESCALA);
            g.setFont(f(Font.PLAIN, 11));
            for (int hz = 0; hz <= 3000; hz += 100) {
                int x = (int) (hz / FMAX * w);
                g.setColor(new Color(143, 166, 200));
                g.drawLine(x, ESCALA - (hz % 500 == 0 ? 7 : 3), x, ESCALA);
                if (hz % 500 == 0 && hz > 0 && hz < 3000) { g.setColor(new Color(200, 215, 235)); g.drawString(hz + " Hz", x + 3, 11); }
            }
            if (img != null) g.drawImage(img, 0, ESCALA, null);
            else {
                g.setColor(new Color(4, 10, 60));
                g.fillRect(0, ESCALA, w, h - ESCALA);
                g.setColor(TEXTO);
                g.drawString("Esperando audio de la emisora…", 20, ESCALA + 30);
            }
            double ancho = motor.modo() == GeneradorFtx.Modo.FT8 ? 50 : 83;   // ancho de la señal en Hz
            int an = Math.max(3, (int) (ancho / FMAX * w));
            int xr = (int) (rxHz / FMAX * w), xt = (int) (txHz / FMAX * w);
            g.setColor(VERDE);
            g.fillRect(xr, 13, an, 3);
            g.setColor(new Color(80, 230, 80, 140));
            g.drawRect(xr, ESCALA, an, h - ESCALA - 1);
            g.setColor(ROJO);
            g.fillRect(xt, 13, an, 3);
            g.setColor(new Color(235, 60, 60, 150));
            g.drawRect(xt, ESCALA, an, h - ESCALA - 1);
            g.setFont(f(Font.BOLD, 11));
            int ultimoX = -1000;
            for (Etiqueta e : etiquetas) {
                int x = (int) (e.hz / FMAX * w);
                int tw = g.getFontMetrics().stringWidth(e.texto);
                int y = ESCALA + 4 + (x - ultimoX < tw + 10 ? 17 : 0);
                g.setColor(new Color(0, 0, 0, 170));
                g.fillRoundRect(x - 2, y, tw + 8, 15, 6, 6);
                g.setColor(AMBAR);
                g.drawString(e.texto, x + 2, y + 11);
                if (y == ESCALA + 4) ultimoX = x;
            }
        }
    }

    // ------------------------------------------------------------------ barra del periodo y medidor
    static class BarraPeriodo extends JComponent {
        private double prog;
        private long per;
        private boolean escuchando, transmitiendo;

        void ponerProgreso(double p, long per, boolean escuchando, boolean transmitiendo) {
            this.prog = p; this.per = per; this.escuchando = escuchando; this.transmitiendo = transmitiendo; repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(CAMPO);
            g.fillRoundRect(0, 0, w, h, 8, 8);
            g.setColor(transmitiendo ? new Color(205, 40, 40) : escuchando ? new Color(40, 160, 70) : new Color(80, 90, 110));
            g.fillRoundRect(0, 0, (int) (w * prog), h, 8, 8);
            g.setColor(Color.WHITE);
            g.setFont(f(Font.BOLD, 11));
            String t = (transmitiendo ? "TRANSMITIENDO " : escuchando ? "RECIBIENDO " : "PARADO ") + String.format(Locale.GERMANY, "%.1f", prog * per / 1000.0) + " de " + (per / 1000.0 == 15 ? "15" : "7,5") + " s";
            g.drawString(t, 8, h - 5);
        }
    }

    static class Medidor extends JComponent {
        private double nivel;
        final JLabel etiqueta = new JLabel("<html><center>Audio<br>—</center></html>");

        Medidor() { etiqueta.setFont(f(Font.BOLD, 10)); etiqueta.setForeground(TEXTO); }

        void ponerNivel(double db) {
            nivel = db;
            SwingUtilities.invokeLater(() -> {
                etiqueta.setText("<html><center>Audio<br>" + (db <= 0 ? "—" : Math.round(db) + " dB") + "</center></html>");
                repaint();
            });
        }

        @Override protected void paintComponent(Graphics g) {
            int h = getHeight(), lleno = (int) (h * Math.max(0, Math.min(1, nivel / 90.0)));
            g.setColor(CAMPO);
            g.fillRect(0, 0, getWidth(), h);
            g.setColor(nivel > 80 ? ROJO : nivel < 15 ? new Color(200, 160, 40) : new Color(60, 200, 90));
            g.fillRect(0, h - lleno, getWidth(), lleno);
        }
    }
}
